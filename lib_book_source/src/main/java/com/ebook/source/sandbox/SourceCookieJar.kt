package com.ebook.source.sandbox

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 一条源的 cookie 罐：既是挂进 OkHttp 客户端的 [CookieJar]（自动存 `Set-Cookie`、自动带回 `Cookie`），
 * 也是脚本侧 `cookie.getCookie/setCookie/removeCookie` 的落点。
 *
 * 两件事必须是同一个对象：如果外呼走一份罐、显式读写走另一份，脚本 `getCookie` 就只能永远读到空串，
 * 而「先 ajax 登录拿会话、再读出来拼签名头」这类源正是靠这条链活着。所以罐由 `ScriptBookParser`
 * 持有一份，[GuardedNetwork.clientFor] 的客户端与 [JsCallbackProxy] 拿到的是同一个实例。
 *
 * ## 为什么作用域是「一个源实例」
 *
 * 不用进程级共享罐：那会把 A 站的会话 cookie 递给 B 站的请求（`CookieJar` 只按 host 匹配，
 * 罐一旦跨源共享，两个源的 host 相同就互通，不同站点的登录态在同一次阅读里混作一团）。
 * 也不用「一次解析任务一罐」：目录页建立的会话到正文页就没了，等于这个能力没有意义。
 * 源级实例正好与 [com.ebook.source.analyze.ScriptBookParser] 同生命周期（随 `BookSourceManagerImpl`
 * 的 LRU 逐出而整份丢掉），代价是**不落盘**——应用重启或被逐出后登录态丢失，这条缺口登记在规格 §11-37。
 *
 * ## 三判都在 OkHttp 手里
 *
 * 回不回得去由 [Cookie.matches]（域、路径、过期）决定，本类不自己写后缀匹配——公域后缀判断
 * 重写一遍就是给自己埋跨域泄漏的雷。唯一的例外是 [removeFor]：删除要「宽一点」（跨路径清掉整域会话），
 * 而多删无害、漏删才是问题，那里用的是域归属判断而不是 [Cookie.matches]。
 *
 * 线程：回调落在 binder 线程池的不同线程上，OkHttp 存 cookie 又在发起请求的那条线程上，
 * 所以读写一律在 [lock] 里（`Collections.synchronizedList` 只保证单次操作原子，
 * 「先按名字淘汰旧条目再追加」这一对必须整体互斥，否则同名 cookie 会留下两份）。
 */
internal class SourceCookieJar : CookieJar {

    private companion object {
        /** 会话 cookie（无 Expires/Max-Age）的到期时间：与 OkHttp 同一口径，永远晚于任何真实时间 */
        const val SESSION_EXPIRES_AT = Long.MAX_VALUE
    }

    private val lock = Any()
    private val stored = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // 同名同域同路径 = 同一条 cookie：不先淘汰旧的，请求头里就会出现两个值，
            // 服务端取第一个，症状是「明明刷新过会话却还在用旧的」
            stored.removeAll { old ->
                cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path }
            }
            stored.removeAll { it.expiresAt <= now }
            stored += cookies
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            return stored.filter { it.expiresAt > now && it.matches(url) }
        }
    }

    /** 脚本侧 `cookie.getCookie(url)`：回 `k1=v1; k2=v2` 形态的请求头文本，没有就回空串 */
    fun headerFor(rawUrl: String): String =
        loadForRequest(requireUrl(rawUrl)).joinToString("; ") { "${it.name}=${it.value}" }

    /**
     * 脚本侧 `cookie.setCookie(url, header)`：把一串 `k=v; k2=v2` 收进罐。
     *
     * **逐对拆成独立 cookie**，不整串交给 [Cookie.parse]：那条路径会把 `;` 后面的内容当第一个 cookie 的
     * 属性吃掉，于是 `b=2` 静默丢失，症状是脚本自己写进去的 token 下次读又没了。
     * 空白值按 [removeFor] 处置：语料里登录前的写法就是 `cookie.setCookie(url, "")`，
     * 它的语义是「丢掉这一域的会话重来」，存一条空值 cookie 反而会在下次请求里发出去。
     */
    fun setHeader(rawUrl: String, header: String) {
        val url = requireUrl(rawUrl)
        if (header.isBlank()) {
            removeFor(rawUrl)
            return
        }
        header.split(';').map { it.trim() }.forEach { pair ->
            val name = pair.substringBefore('=', "").trim()
            if (!pair.contains('=') || name.isEmpty()) return@forEach
            val cookie = Cookie.Builder()
                .name(name)
                .value(pair.substringAfter('=').trim())
                .path("/")
                .hostOnlyDomain(url.host)
                .expiresAt(SESSION_EXPIRES_AT)
                .build()
            saveFromResponse(url, listOf(cookie))
        }
    }

    /** 脚本侧 `cookie.removeCookie(url)`：清掉归属这个 host 的全部条目（跨路径，见类 KDoc） */
    fun removeFor(rawUrl: String) {
        val url = requireUrl(rawUrl)
        synchronized(lock) {
            stored.removeAll { it.inScopeOf(url) }
        }
    }

    /**
     * 域归属判断，照 OkHttp 的 `matchDomain` 写法：hostOnly 的 cookie 只属于那一个 host，
     * 带 `Domain` 属性的属于它和它的子域。只在删除时用——判宽只会多删，判窄才会留下会话。
     */
    private fun Cookie.inScopeOf(url: HttpUrl): Boolean =
        if (hostOnly) {
            domain == url.host
        } else {
            url.host == domain || url.host.endsWith(".$domain")
        }

    /**
     * 归一化：允许脚本传裸 host（语料主流写法是 `cookie.getKey("fanqienovel.com", ...)`、
     * `removeCookie("snssdk.com")`），补 `https://` 后交给 OkHttp 解析。
     * 解不出来按参数错误抛：静默当成「这一域没有 cookie」会让人以为源坏了，其实是它写了个非法地址。
     */
    private fun requireUrl(raw: String): HttpUrl {
        val text = raw.trim()
        val url = (if (text.contains("://")) text else "https://$text").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("cookie 的地址参数无法解析：${text.take(60)}")
        return url
    }
}
