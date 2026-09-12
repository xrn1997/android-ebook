package com.ebook.source.sandbox

import com.ebook.source.script.JsApiRejectedException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import okhttp3.Dns

/**
 * 网络代理的准入判定。**无状态**：只回答「这个 URL / 这个地址能不能碰」。
 * 每任务请求次数上限（〔ADR-0028 决策 6〕的「限流」）由持有 [JsLimits.maxRequestsPerTask] 的
 * Task 9 `JsCallbackProxy` 计数——守门器不持计数器，同一个实例才能在一轮任务里被反复问、
 * 也能跨任务复用。
 *
 * 判定分两段，**位置不同是刻意的**：
 * 1. [check] 纯字符串（长度 → 协议 → 结构 → 白名单），零 DNS；
 * 2. [GuardedDns] 把地址判定挂在 OkHttp 真正解析的那一次上。
 *
 * 第 2 点不是「顺手也能放进第 1 点」的事：若在 [check] 里解析一次、OkHttp 连接时再解析一次，
 * 攻击者把 TTL 设成 0 就能让两次解析给出不同结果（一次公网、一次 127.0.0.1），检查与使用
 * 就不是同一个地址——DNS 重绑。同理，`http://2130706433/`（十进制字面量即 127.0.0.1）与
 * `http://anything.127.0.0.1.nip.io/` 这类形态**不需要**在字符串层特判：平台解析完就是环回地址，
 * [GuardedDns] 必然撞上 [AddressPolicy]。
 */
internal class JsNetworkGuard(
    private val allowlist: SourceHostAllowlist,
    /** 通用 URL 长度口径：防的是脚本拼出一个 10 MB 的「URL」这种无意义请求 */
    private val maxUrlLength: Int = 4_096,
) {

    /**
     * 通过则回归一化后的 host（供日志与统计），拒绝抛 [JsApiRejectedException]。
     * 每条消息都带被拒 URL 前 80 字符——〔ADR-0028 遗留〕的白名单调校就靠这批 host。
     */
    fun check(url: String): String {
        val brief = url.take(80)
        if (url.length > maxUrlLength) {
            throw JsApiRejectedException("URL 长度 ${url.length} 超上限 $maxUrlLength：$brief")
        }
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) throw JsApiRejectedException("必须是带协议的绝对 URL：$brief")
        val scheme = url.substring(0, schemeEnd).lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw JsApiRejectedException("仅代理 http(s)，拒绝 $scheme：$brief")
        }
        val authority = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.any { it.code < 0x21 || it == '%' }) {
            // 空白/控制字符不是合法授权段；百分号编码的 host 还原不出真实目标，一并拒
            throw JsApiRejectedException("URL 的 host 段含非法字符：$brief")
        }
        if (authority.contains('@')) throw JsApiRejectedException("禁止在 URL 里内嵌凭证：$brief")
        val host = SourceHostAllowlist.normalize(authority)
            ?: throw JsApiRejectedException("URL 的 host 为空：$brief")
        if (!allowlist.allows(host)) {
            throw JsApiRejectedException(
                "host $host 不在本源可解析白名单内（白名单 ${allowlist.entries.size} 项）：$brief",
            )
        }
        return host
    }

    /**
     * 拒绝时抛 [UnknownHostException]：`okhttp3.Dns.lookup` 的契约声明的就是它
     * （源码里 `@Throws(UnknownHostException::class)`），换别的类型会让连接栈走非预期分支。
     */
    fun rejectIfBlocked(address: InetAddress, hostname: String) {
        if (AddressPolicy.isBlocked(address)) {
            throw UnknownHostException("拒绝访问内网/保留地址 ${address.hostAddress}（host $hostname）")
        }
    }
}

/**
 * 地址判定挂在 OkHttp 的解析点上：连接用的就是这次解析的结果，检查与使用同址，不留 DNS 重绑窗口。
 *
 * 被拒时对脚本的表现与「真实网络失败」一致：Task 9 的代理把 [UnknownHostException]（它是
 * `IOException`）转成失败回包，垫片抛成 JS 异常——脚本里自己 `try/catch` 的写法照常生效。
 * 这是刻意的：静默放行会让沙箱只剩约定没有边界，而报一个脚本看不懂的「安全策略」错误码
 * 又会让源作者以为站点挂了。
 *
 * [resolver] 可注入是本层的测试口径：**地址归属的判定必须在 JVM 上跑**（这是安全边界，
 * 不能只靠设备上的冒烟），而真 DNS 既不可控也不可离线重复。默认实现就是 OkHttp 要的那一个，
 * 注入只替换「去哪儿拿地址」，不替换「拿到之后怎么判」——后者由 [GuardedDns] 内部保证
 * 每次 lookup 都重判、一次都不缓存（缓存会让 TTL=0 的重绑攻击绕过判据）。
 */
internal class GuardedDns(
    private val guard: JsNetworkGuard,
    /** 默认实现取 getAllByName 的**全部**记录：只取第一条就把「多 A 记录混内网」这条判据废掉了 */
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).asList() },
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver(hostname)
        if (addresses.isEmpty()) throw UnknownHostException("DNS 无解析结果：$hostname")
        // 多 A 记录一律全查、有一个内网就整体拒绝：挑哪个地址连接是 OkHttp 的事，我们控制不了
        addresses.forEach { address -> guard.rejectIfBlocked(address, hostname) }
        return addresses
    }
}

/**
 * 内网、保留与不可路由地址的**字节级**判据。
 *
 * 不用 [InetAddress.isLoopbackAddress] / [InetAddress.isSiteLocalAddress] 那一族谓词：
 * 它们对「IPv4 映射的 IPv6 地址」的处理在 JDK 与 Android 上不一致（Android 的
 * `getByAddress` 会就地转成 `Inet4Address`），且 `isSiteLocalAddress` 只覆盖 RFC1918 三段，
 * **不含**云元数据所在的 169.254/16 与运营商级 NAT 的 100.64/10。这里是安全边界，
 * 判据必须写死在一处，并且能被单测按原始字节喂进来。
 *
 * 「按字节判」还顺带解决了 IPv4 混淆写法：`2130706433`、`0177.0.0.1`、`0x7f000001`、`127.1`
 * 这些形态各平台的解法并不一致（实测 JDK 17/25 只认十进制单段与点分形态），但**解出来的一定是
 * 四个字节**，落到本判据上就是同一个 127.0.0.0/8。所以字符串层不做地址猜测（见
 * [JsNetworkGuard] KDoc），全部收敛到这里。
 */
internal object AddressPolicy {

    fun isBlocked(address: InetAddress): Boolean = isBlocked(address.address)

    fun isBlocked(raw: ByteArray): Boolean = when (raw.size) {
        4 -> isBlockedIpv4(raw)
        16 -> isBlockedIpv6(raw)
        else -> true // 平台给了没见过的长度：deny-by-default
    }

    private fun isBlockedIpv4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        val a2 = b[2].toInt() and 0xff
        return when {
            a0 == 0 -> true                         // 0.0.0.0/8 这一网络
            a0 == 10 -> true                        // RFC1918
            a0 == 127 -> true                       // 环回整个 /8，不只 127.0.0.1
            a0 == 169 && a1 == 254 -> true          // 链路本地；云元数据 169.254.169.254 在此段
            a0 == 172 && a1 in 16..31 -> true       // RFC1918
            a0 == 192 && a1 == 168 -> true          // RFC1918
            a0 == 192 && a1 == 0 && a2 == 0 -> true // 192.0.0.0/24 IETF 保留
            a0 == 100 && a1 in 64..127 -> true      // 100.64.0.0/10 CGNAT：运营商级 NAT 与不少云内网
            a0 == 198 && a1 in 18..19 -> true       // 198.18.0.0/15 基准测试
            a0 in 224..239 -> true                  // 组播
            a0 >= 240 -> true                       // 保留段 + 255.255.255.255 广播
            else -> false
        }
    }

    private fun isBlockedIpv6(b: ByteArray): Boolean {
        val b0 = b[0].toInt() and 0xff
        val b1 = b[1].toInt() and 0xff
        if (b1 == 0 && (2..9).all { b[it].toInt() == 0 }) {
            // 字节 0..9 全 0 即 ::/96：未指定、环回、IPv4 兼容与映射形态都住这里，整体不可路由
            val hi = b[10].toInt() and 0xff
            val lo = b[11].toInt() and 0xff
            val embedded = b.copyOfRange(12, 16)
            if (hi == 0xff && lo == 0xff) return isBlockedIpv4(embedded) // ::ffff:a.b.c.d
            if (hi == 0 && lo == 0) return isBlockedIpv4(embedded)       // ::a.b.c.d
            return true
        }
        return when {
            b0 == 0xfe && (b1 and 0xc0) == 0x80 -> true // fe80::/10 链路本地
            (b0 and 0xfe) == 0xfc -> true               // fc00::/7 唯一本地地址
            b0 == 0xff -> true                          // ff00::/8 组播
            b0 == 0x20 && b1 == 0x02 -> isBlockedIpv4(b.copyOfRange(2, 6)) // 2002::/16 6to4：内嵌 IPv4 就地解
            else -> false
        }
        // 刻意**不**解 Teredo（2001::/32 里异或混淆的 IPv4）：Android 不跑 Teredo 客户端，
        // 该形态在装机环境里根本发不出包，为它加分支只增加判据复杂度。已知边界，非静默放行。
    }
}
