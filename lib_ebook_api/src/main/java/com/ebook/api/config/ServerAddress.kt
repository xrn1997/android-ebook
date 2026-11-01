package com.ebook.api.config

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * ebook-server 基址（scheme + 主机 + 端口），登录与评论接口共用同一份。
 *
 * 只描述「打到哪台机的哪个口」，不允许路径/查询/凭据：Retrofit 接口是相对路径，
 * 基址带路径会让落位漂移；凭据写进配置没有正当用途（见 ADR-0052）。
 */
data class ServerAddress(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    /** Retrofit baseUrl：恒以 `/` 结尾；IPv6 由 [HttpUrl] 负责加方括号。 */
    val baseUrl: String
        get() = HttpUrl.Builder().scheme(scheme).host(host).port(port).build().toString()

    /** 设置页展示与预填用：`scheme://host:port`（IPv6 带方括号），可被 [parse] 原样读回。 */
    val display: String
        get() = "$scheme://${if (':' in host) "[$host]" else host}:$port"

    /**
     * 是否「非回环 http」：登录密码与双 token 会以明文发往远端主机。
     *
     * 设置页据此显示明文警告；回环与模拟器宿主（10.0.2.2/10.0.3.2）是本地调试的常规形态，
     * 不提示。是否禁止 http 是产品取舍（局域网自建后端仍在用 http），本类只负责给出判据。
     */
    val isCleartextToRemoteHost: Boolean
        get() = scheme == "http" && host.lowercase() !in LOCAL_DEBUG_HOSTS &&
            !host.startsWith("127.")

    companion object {
        const val DEFAULT_SCHEME = "http"

        /** 与 ebook-server 的 server.port=9090 对齐；https 未显式给端口时按 443。 */
        const val DEFAULT_PORT = 9090

        /** 本地调试宿主：回环 + Android 模拟器映射宿主机的固定地址，明文不提示。 */
        private val LOCAL_DEBUG_HOSTS = setOf("localhost", "::1", "10.0.2.2", "10.0.3.2")

        /**
         * 解析用户输入：`host`、`host:port`、`scheme://host[:port][/]` 三种形态。
         * 缺省 [DEFAULT_SCHEME]；http 缺省 [DEFAULT_PORT]，https 缺省 443。
         * 非法输入返回 null（由调用方翻译成行内错误文案，此处不持有用户可见文本）。
         */
        fun parse(input: String): ServerAddress? {
            val raw = input.trim()
            if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null

            val scheme = when {
                raw.startsWith("http://", ignoreCase = true) -> "http"
                raw.startsWith("https://", ignoreCase = true) -> "https"
                raw.contains("://") -> return null
                else -> DEFAULT_SCHEME
            }
            val rest = if (raw.contains("://")) raw.substringAfter("://") else raw
            // 反斜杠必须提前拒绝：OkHttp 会把它当路径分隔符，`http://a.com\api` 会被静默
            // 削成主机 a.com，等于放行了一条带路径的基址。
            if (rest.any { it == '@' || it == '?' || it == '#' || it == '\\' }) return null

            val slash = rest.indexOf('/')
            // 只允许一个收尾的 `/`，其余路径一律拒绝
            if (slash >= 0 && rest.substring(slash) != "/") return null
            val authority = if (slash >= 0) rest.substring(0, slash) else rest
            if (authority.isEmpty() || authority.endsWith(":")) return null

            val url = "$scheme://$authority/".toHttpUrlOrNull() ?: return null
            val hasExplicitPort = if (authority.startsWith("[")) {
                authority.contains("]:")
            } else {
                authority.count { it == ':' } == 1
            }
            val port = when {
                hasExplicitPort -> url.port
                scheme == "http" -> DEFAULT_PORT
                else -> 443
            }
            return ServerAddress(scheme, url.host, port)
        }
    }
}
