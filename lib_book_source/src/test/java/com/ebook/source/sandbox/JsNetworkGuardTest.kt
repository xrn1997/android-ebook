package com.ebook.source.sandbox

import com.ebook.source.script.JsApiRejectedException
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络代理的准入判据。这是整个沙箱唯一**能碰到内网**的接缝，逐形态锁死：
 * 判错一个方向是断掉内容站点（可恢复、有日志、按单源失败处置）；判错另一个方向是让一台
 * 零权限设备变成可读的 HTTP 客户端（云元数据服务、路由器后台、本机服务）。
 *
 * 地址判定一律喂**原始字节**给 `AddressPolicy.isBlocked(ByteArray)`，不走
 * `InetAddress.getByAddress`：Android 与 JDK 对「IPv4 映射的 IPv6 地址」处理不一致
 * （Android 会就地转成 `Inet4Address`），走平台路径会让同一用例在两边解成不同地址族，
 * 测到的就不是判据本身。
 */
class JsNetworkGuardTest {

    private fun guardFor(vararg ruleTexts: String): JsNetworkGuard =
        JsNetworkGuard(SourceHostAllowlist.of("", ruleTexts.toList()))

    /** 拒绝不只要拒，还要把被拒 URL 带进消息——白名单的调校数据全靠它 */
    private fun assertRejected(guard: JsNetworkGuard, url: String) {
        val e = assertThrows("$url 必须被拒", JsApiRejectedException::class.java) { guard.check(url) }
        assertTrue("拒绝消息要带被拒 URL：${e.message}", e.message!!.contains(url.take(80)))
    }

    /** 4 位十六进制组 → 字节数组（地址用例按原始字节构造，绕开平台地址族转换） */
    private fun bytes(vararg groups: String): ByteArray =
        groups.flatMap { group -> group.chunked(2).map { hex -> hex.toInt(16).toByte() } }.toByteArray()

    private fun v4(text: String): ByteArray = text.split('.').map { it.toInt().toByte() }.toByteArray()

    // ---------- 纯字符串段：check() ----------

    @Test
    fun `仅 http(s) 通过，其余协议一律拒绝`() {
        val guard = guardFor("https://novel.example/list")
        assertEquals("novel.example", guard.check("https://novel.example/list"))
        assertEquals("novel.example", guard.check("http://novel.example/list"))
        assertEquals("novel.example", guard.check("HTTPS://novel.example/list"))
        listOf(
            "file:///android_asset/x",
            "gopher://novel.example/",
            "jar:http://novel.example!/entry",
            "data:text/html,hi",
            "ftp://novel.example/",
            "javascript:alert(1)",
        ).forEach { url -> assertRejected(guard, url) }
    }

    @Test
    fun `相对地址与缺协议的地址被拒`() {
        val guard = guardFor("https://novel.example/")
        listOf("//novel.example/x", "novel.example/x", "/a/b", "https:/novel.example/x", "")
            .forEach { url -> assertRejected(guard, url) }
    }

    @Test
    fun `内嵌凭证、空 host 与含非法字符的授权段被拒`() {
        val guard = guardFor("https://novel.example/")
        listOf(
            "https://u:p@novel.example/x", // 凭证既不内嵌也不附加
            "https:///x",                  // 空 host
            "https://:8080/x",             // 空 host 带端口
            "https://no vel.example/x",    // 授权段含空白
            "https://%31%32%37.0.0.1/x",   // 授权段含百分号编码
            "https://nov\u0000.example/x", // 授权段含控制字符
        ).forEach { url -> assertRejected(guard, url) }
    }

    @Test
    fun `URL 长度超上限被拒`() {
        val guard = JsNetworkGuard(
            SourceHostAllowlist.of("", listOf("https://novel.example/")),
            maxUrlLength = 40,
        )
        assertRejected(guard, "https://novel.example/" + "a".repeat(60))
    }

    @Test
    fun `白名单外的 host 被拒且消息带归一化后的 host`() {
        val guard = guardFor("https://novel.example/list")
        val e = assertThrows(JsApiRejectedException::class.java) { guard.check("https://other.example/x") }
        assertTrue("拒绝消息要带 host 供调校：${e.message}", e.message!!.contains("other.example"))
    }

    @Test
    fun `host 归一化后才比对：大小写、端口、根点、IPv6 方括号`() {
        val guard = guardFor("https://[2001:db8::1]:8443/x", "https://novel.example/list")
        assertEquals("2001:db8::1", guard.check("https://[2001:DB8::1]:8443/x"))
        assertEquals("novel.example", guard.check("https://Novel.Example.:80/list"))
    }

    // ---------- 地址归属段：AddressPolicy ----------

    @Test
    fun `IPv4 内网与保留段逐条拒绝`() {
        mapOf(
            "0.0.0.0" to "这一网络",
            "0.255.255.255" to "0/8 上界",
            "10.1.2.3" to "RFC1918 /8",
            "127.0.0.1" to "环回",
            "127.9.9.9" to "环回整个 /8",
            "169.254.169.254" to "云元数据",
            "172.16.0.1" to "RFC1918 /12 下界",
            "172.31.255.255" to "RFC1918 /12 上界",
            "192.168.1.1" to "RFC1918 /16",
            "192.0.0.1" to "IETF 保留",
            "100.64.0.1" to "CGNAT 下界",
            "100.127.255.255" to "CGNAT 上界",
            "198.18.0.1" to "基准测试",
            "224.0.0.1" to "组播",
            "239.255.255.255" to "组播上界",
            "240.0.0.1" to "保留",
            "255.255.255.255" to "广播",
        ).forEach { (text, why) ->
            assertTrue("$text（$why）该被拒", AddressPolicy.isBlocked(v4(text)))
        }
    }

    @Test
    fun `IPv4 紧邻内网段的公网地址放行`() {
        listOf(
            "93.184.216.34",
            "8.8.8.8",
            "172.32.0.1",      // 172.16/12 之外
            "100.128.0.1",     // 100.64/10 之外
            "169.255.0.1",     // 169.254/16 之外
            "192.0.1.1",       // 192.0.0/24 之外
            "197.255.255.255", // 198.18/15 之外
            "223.255.255.255", // 组播之前
        ).forEach { text ->
            assertFalse("$text 是公网，不该被误拒", AddressPolicy.isBlocked(v4(text)))
        }
    }

    @Test
    fun `IPv6 环回、ULA、链路本地与组播拒绝，全球单播放行`() {
        mapOf(
            "0000 0000 0000 0000 0000 0000 0000 0000" to "未指定 ::",
            "0000 0000 0000 0000 0000 0000 0000 0001" to "环回 ::1",
            "0000 0000 0000 0000 0000 0001 0000 0000" to "::/96 里的非常规形态",
            "fe80 0000 0000 0000 0000 0000 0000 0001" to "链路本地",
            "fd12 3456 0000 0000 0000 0000 0000 0001" to "ULA fc00::/7",
            "ff02 0000 0000 0000 0000 0000 0000 0001" to "组播",
        ).forEach { (text, why) ->
            val groups = text.split(" ").toTypedArray()
            assertTrue("$text（$why）该被拒", AddressPolicy.isBlocked(bytes(*groups)))
        }
        assertFalse(
            AddressPolicy.isBlocked(bytes("2001", "4860", "8000", "0000", "0000", "0000", "0000", "8888")),
        )
    }

    @Test
    fun `IPv4 映射与兼容形态按内嵌的 IPv4 判`() {
        // ::ffff:127.0.0.1
        assertTrue(AddressPolicy.isBlocked(bytes("0000", "0000", "0000", "0000", "0000", "ffff", "7f00", "0001")))
        // ::169.254.169.254 —— IPv4 兼容形态的云元数据
        assertTrue(AddressPolicy.isBlocked(bytes("0000", "0000", "0000", "0000", "0000", "0000", "a9fe", "a9fe")))
        // ::ffff:93.184.216.34 —— 映射的公网地址该放行
        assertFalse(AddressPolicy.isBlocked(bytes("0000", "0000", "0000", "0000", "0000", "ffff", "5db8", "d822")))
    }

    @Test
    fun `6to4 就地解出内嵌 IPv4，未知长度一律拒绝`() {
        // 2002:7f00:0001:: —— 6to4 内嵌 127.0.0.1
        assertTrue(AddressPolicy.isBlocked(bytes("2002", "7f00", "0001", "0000", "0000", "0000", "0000", "0000")))
        // 2002:5db8:d822:: —— 6to4 内嵌公网地址
        assertFalse(AddressPolicy.isBlocked(bytes("2002", "5db8", "d822", "0000", "0000", "0000", "0000", "0000")))
        assertTrue(AddressPolicy.isBlocked(ByteArray(3)))
        assertTrue(AddressPolicy.isBlocked(ByteArray(8)))
    }

    // ---------- DNS 挂载点：GuardedDns ----------

    @Test
    fun `多 A 记录里混入内网就整体拒绝`() {
        val public = InetAddress.getByAddress(v4("93.184.216.34"))
        val loopback = InetAddress.getByAddress(v4("127.0.0.1"))
        val dns = GuardedDns(guardFor("https://novel.example/"), resolver = { listOf(public, loopback) })
        val e = assertThrows(UnknownHostException::class.java) { dns.lookup("novel.example") }
        assertTrue("消息要指出是哪个地址被拒：${e.message}", e.message!!.contains("127.0.0.1"))
    }

    @Test
    fun `解析结果为空按解析失败处理`() {
        val dns = GuardedDns(guardFor("https://novel.example/"), resolver = { emptyList() })
        assertThrows(UnknownHostException::class.java) { dns.lookup("novel.example") }
    }

    @Test
    fun `公网地址原样交回 OkHttp`() {
        val addresses = listOf(InetAddress.getByAddress(v4("93.184.216.34")))
        val dns = GuardedDns(guardFor("https://novel.example/")) { addresses }
        assertEquals(addresses, dns.lookup("novel.example"))
    }

    // ---------- 本仓真实攻击面：混淆写法与 DNS 重绑 ----------

    /**
     * 十进制 / 八进制 / 十六进制的 IPv4 混淆写法收敛成同一批字节，判据只认字节。
     *
     * 实测口径（JDK 17 与 JDK 25 一致）：`2130706433` 与 `127.1` 被就地解成 127.0.0.1，
     * `0177.0.0.1` 按**十进制**解成 177.0.0.1（现代 JDK 已不再支持逐段八进制/十六进制），
     * `0x7f000001` 直接 `UnknownHostException`。也就是说「平台会怎么解这些写法」**因平台与
     * 版本而异**（curl、OkHttp 的老版本、bionic 各有历史），所以这里不锁平台的解法，
     * 只锁「不管解成哪四个字节，落在内网段就拒」——那才是本仓能负责的部分。
     */
    @Test
    fun `IPv4 混淆写法解出的字节形态逐条拒绝`() {
        mapOf(
            // 2130706433 / 0177.0.0.1 / 0x7f000001 / 127.1 都指向 127.0.0.1
            "7f00 0001" to "十进制、八进制、十六进制与缩写形态同解一个环回",
            "c0a8 0101" to "3232235777 = 192.168.1.1（路由器后台）",
            "a9fe a9fe" to "2852039166 = 169.254.169.254（云元数据）",
            "6440 0001" to "1715486721 = 100.64.0.1（CGNAT）",
            "0a00 0001" to "167772161 = 10.0.0.1（RFC1918）",
        ).forEach { (hex, why) ->
            val groups = hex.split(" ").toTypedArray()
            assertTrue("$hex（$why）该被拒", AddressPolicy.isBlocked(bytes(*groups)))
        }
        // 同一套混淆写法用在公网地址上不该被误拒：0x5db8d822 = 93.184.216.34
        assertFalse(
            "混淆写法不能变成「看着像 IP 就拒」",
            AddressPolicy.isBlocked(bytes("5db8", "d822")),
        )
    }

    /**
     * 字符串层**刻意不特判**这些写法（JsNetworkGuard KDoc 的第三条理由），它们一律走到 DNS 挂载点。
     * 若哪天有人在 [JsNetworkGuard.check] 里加「含 `%2e`、纯数字 host 直接拒」这类规则，
     * 这条用例就会红——因为在字符串层猜地址必然同时长出假阴性（漏一种新写法）与假阳性
     * （把作者合法写的 IP 源拒了）。
     */
    @Test
    fun `混淆字面量与泛解析域名都通过字符串段，到 DNS 挂载点才被地址判据拦下`() {
        val guard = guardFor("http://2130706433/x", "http://anything.127.0.0.1.nip.io/x")
        assertEquals("2130706433", guard.check("http://2130706433/y"))
        assertEquals("anything.127.0.0.1.nip.io", guard.check("http://anything.127.0.0.1.nip.io/y"))

        // 十进制字面量：平台就地解成 127.0.0.1（已按 JDK 17/25 实测），连接那次解析必然撞判据
        val decimal = GuardedDns(guard) { listOf(InetAddress.getByAddress(v4("127.0.0.1"))) }
        val e = assertThrows(UnknownHostException::class.java) { decimal.lookup("2130706433") }
        assertTrue("消息要指出被拒地址：${e.message}", e.message!!.contains("127.0.0.1"))

        // nip.io 形态：字面量里没有任何内网痕迹，只有解析结果有
        val nip = GuardedDns(guard) { listOf(InetAddress.getByAddress(v4("127.0.0.1"))) }
        assertThrows(UnknownHostException::class.java) { nip.lookup("anything.127.0.0.1.nip.io") }
    }

    /**
     * DNS 重绑：TTL=0 的域名两次解析给出不同答案。
     * 守门器不持任何解析缓存，所以每次连接真正用的那次结果都要重新过一遍判据。
     */
    @Test
    fun `DNS 重绑时第二次解析出内网同样被拒，守门器不缓存上一次结果`() {
        val guard = guardFor("https://novel.example/")
        val public = InetAddress.getByAddress(v4("93.184.216.34"))
        val loopback = InetAddress.getByAddress(v4("127.0.0.1"))
        var calls = 0
        val dns = GuardedDns(guard) {
            calls++
            if (calls == 1) listOf(public) else listOf(loopback)
        }
        assertEquals(listOf(public), dns.lookup("novel.example"))
        val e = assertThrows(UnknownHostException::class.java) { dns.lookup("novel.example") }
        assertTrue("第二次（重绑后的）解析必须被拒：${e.message}", e.message!!.contains("127.0.0.1"))
        // 两次都真去解析了：缓存第一次的结果就等于把「检查与使用同址」这条前提丢掉
        assertEquals(2, calls)
    }

    /**
     * 纯字符串段一次都不触发解析。
     *
     * 「先解析一次做检查、连接时再解析一次」是 DNS 重绑的标准成因（两次结果不是同一个地址），
     * 所以地址判定**只能有一个位置**：OkHttp 真正拿去连接的那次。这条用例锁的就是这个位置唯一性。
     */
    @Test
    fun `字符串段不触发任何解析，地址判定只挂在连接真正用的那次结果上`() {
        val guard = guardFor("https://novel.example/")
        var calls = 0
        val dns = GuardedDns(guard) {
            calls++
            listOf(InetAddress.getByAddress(v4("93.184.216.34")))
        }
        assertEquals("novel.example", guard.check("https://novel.example/list"))
        assertTrue("check() 里不得顺手解析", calls == 0)
        dns.lookup("novel.example")
        assertTrue("GuardedDns 只在该解析时解析一次", calls == 1)
    }

    /** IPv6 形态走完整解析链路（`InetAddress` 路径）也要被拦：地址族与写法都不给通融 */
    @Test
    fun `IPv6 环回与链路本地经解析层同样被拦`() {
        val guard = guardFor("https://v6.example/")
        // 八个组全部展开写：判据吃的就是这 16 个字节，助记符反而容易看错地址族
        val cases = listOf(
            "环回 ::1" to
                arrayOf("0000", "0000", "0000", "0000", "0000", "0000", "0000", "0001"),
            "链路本地 fe80::1" to
                arrayOf("fe80", "0000", "0000", "0000", "0000", "0000", "0000", "0001"),
            "IPv4 映射的环回 ::ffff:127.0.0.1" to
                arrayOf("0000", "0000", "0000", "0000", "0000", "ffff", "7f00", "0001"),
            "IPv4 兼容的云元数据 ::169.254.169.254" to
                arrayOf("0000", "0000", "0000", "0000", "0000", "0000", "a9fe", "a9fe"),
        )
        cases.forEach { (why, groups) ->
            // getByAddress 在 JDK 上会把 IPv4 映射形态就地转成 Inet4Address（Android 未必），
            // 两条地址族路径都由同一个字节判据覆盖，故这里只断「被拒」、不断平台解成哪一族。
            val dns = GuardedDns(guard) { listOf(InetAddress.getByAddress(bytes(*groups))) }
            val e = assertThrows("$why 该在 DNS 挂载点被拒", UnknownHostException::class.java) {
                dns.lookup("v6.example")
            }
            assertTrue("消息要指出 host（$why）：${e.message}", e.message!!.contains("v6.example"))
        }
    }

    /**
     * 全球单播的 IPv6 字面量源必须放行。
     *
     * 这是 AddressPolicy 里 `::/96` 那一段独立于 `when` 之外的原因（映射/兼容形态要先按内嵌
     * IPv4 判，才能区分「映射到公网」与「映射到环回」）：判据收错位置的症状不是报错，
     * 是**所有 IPv6 源整体不可用**，而拒绝消息看着还都像样。
     */
    @Test
    fun `全球单播 IPv6 地址原样放行`() {
        val guard = guardFor("https://v6.example/")
        // 2001:db8::1 —— 文档段，但地址族上是全球单播（2001::/16 不进任何被拒分支）
        val global = listOf(
            InetAddress.getByAddress(bytes("2001", "0db8", "0000", "0000", "0000", "0000", "0000", "0001")),
        )
        val dns = GuardedDns(guard) { global }
        assertEquals(global, dns.lookup("v6.example"))
    }
}
