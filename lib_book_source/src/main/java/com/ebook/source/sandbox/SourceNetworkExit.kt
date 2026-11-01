package com.ebook.source.sandbox

import okhttp3.OkHttpClient

/**
 * 书源出网客户端的唯一派生点（ADR-0058）：沙箱外呼与取文两路都从这里派生，谁都不自己 `new OkHttpClient()`。
 *
 * 为什么收成一处：派生动作（`newBuilder()` 共享连接池与线程池、换 dns/cookie 罐）此前分居两处、
 * 且取文那路**根本没有派生**（直接把注入的 `@Named("source")` 客户端包进 `OkHttpScriptTransport`），
 * 「这台设备上书源请求长什么样」要读两个文件才拼得出来，将来收紧也只能改一半。收成一处之后，
 * base 的出处（`@Named("source")` 纯净客户端）与「永不附凭证」的口径只在这一个对象上成立一次，
 * 也才有了 `SourceNetworkExitTest` 能直接钉住的接缝。
 *
 * 两路共用同一个 `base`：`newBuilder()` 派生共享连接池与线程池，取体与探测不必各建各的客户端。
 * 传入的 `base` 必须是 `@Named("source")` 那个纯净客户端——第三方站点永远拿不到用户 token。
 */
internal object SourceNetworkExit {

    /**
     * 沙箱外呼的守门客户端：挂 [GuardedDns]（把地址判定挂在 OkHttp 真正解析的那一次上，
     * 不留 DNS 重绑窗口）；可选挂源级会话罐。
     *
     * `cookies` 是「取体与探测共用同一只罐」的那半边理由：脚本外呼要能自动攒下会话（否则
     * `cookie.getCookie` 永远读到空串），而攒下的那一份必须就是脚本显式读写的那一份——
     * 由调用方（`ScriptBookParser`）持罐、在这里挂进客户端。不传即维持「不带 cookie」的原状：
     * 调用点只有 `sandboxGateway` 与 `HeadStatusProbe` 两处，两处漏一处就长成
     * 「会话在取体那侧有、在探测那侧没有」。
     */
    fun sandboxClientFor(
        base: OkHttpClient,
        guard: JsNetworkGuard,
        cookies: SourceCookieJar? = null,
    ): OkHttpClient = base.newBuilder()
        .dns(GuardedDns(guard))
        .apply { cookies?.let { cookieJar(it) } }
        .build()

    /**
     * 取文的客户端（声明式 URL 与 URL 选项 `js` 改写出的地址同走这一路）。
     *
     * **首合并是等价派生：刻意不挂 [GuardedDns]、也不查白名单**（ADR-0058）。LAN 与 IP 直连的
     * 书源今天能用、必须继续能用——私网/DNS 重绑判定此刻仍只在沙箱外呼那一路，一步收紧会掐死
     * 那批源（用户裁决，被否方案见 ADR-0058）。本函数的价值因此不是多一道拒绝，而是：一个具名
     * 出口、同一份 base 出处、永不附凭证，以及将来收紧时**只改这一处**的落点。
     * 凭证口径由 base 保证（`@Named("source")` 纯净客户端、无 token），本派生不添任何拦截器。
     */
    fun contentClientFor(base: OkHttpClient): OkHttpClient = base.newBuilder().build()
}
