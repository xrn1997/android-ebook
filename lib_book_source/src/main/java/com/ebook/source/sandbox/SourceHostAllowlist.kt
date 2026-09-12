package com.ebook.source.sandbox

import java.util.Locale

/**
 * 「按源导出可解析 host 白名单」〔ADR-0028 决策 6〕：书源自身 URL 的 host，加上它的规则文本里
 * 出现过的 host。
 *
 * 为什么从规则文本导出、而不是让作者填一个字段：脚本书源是社区格式，导入侧没有任何字段声明
 * 「这条源会访问哪些域名」，而 URL 规则本身就是答案的可靠来源——正文地址、图片地址、翻页地址
 * 都写在那里。导出的是「规则已经声明会去的地方」，脚本临时改道去别处才需要判。
 *
 * **默认严格，不自动放行子域**：`https://example.com` 只放行 `example.com` 本身，不放行
 * `cdn.example.com`。子域通配等于把一个域的持有者范围放大成「所有以它为后缀的域名」，而真实
 * 风险形态恰恰是 `attacker.example.com` 这类近似域；放行的收益（少数源把内容挂在另一子域）
 * 远小于代价。导出漏了时的后果是「这一源的内容请求被拒」，按〔ADR-0028 决策 7〕与该源本轮
 * 解析失败同口径处置——不串内容、不崩溃，而且拒绝日志带着 host。〔ADR-0028 遗留〕明说白名单
 * 的宽松度将来要按真实源失败率调校，那批调校数据就是这些日志。
 */
internal class SourceHostAllowlist private constructor(val entries: Set<String>) {

    /** [host] 必须是 [JsNetworkGuard] 归一化后的形态（小写、无端口、无方括号、无尾点） */
    fun allows(host: String): Boolean = host in entries

    companion object {

        /**
         * 规则文本里的绝对 URL 授权段。字符类排掉 `/ ? #` 空白 引号 `<` `>` 反斜杠，因此只吃
         * 「scheme://」后紧跟的那一段：`"http://" + host` 这类拼接里 `://` 后面是引号，匹配不到；
         * 正则字面量里的 `https?:\/\/` 同理不产 host。
         */
        private val URL_AUTHORITY = Regex("""https?://([^/\s"'<>\\?#]+)""", RegexOption.IGNORE_CASE)

        /**
         * 合法 host 的字符集（正向白名单，deny-by-default）。
         *
         * 归一化后允许：字母、数字、`.` `-` `_`，以及 IPv6 剥掉方括号后留下的 `:`。
         * 为什么必须有这一道：[normalize] 的入参来自正则捕获组之外的手工切片，`/`、`?`、`#`
         * 这类分隔符一旦漏进来（调用方直接喂授权段，或用例之外的将来调用方），
         * 「解不出 host 就回 null」就只覆盖了空串这一种形态，会把 `/` 当成一个 host 存进白名单——
         * 白名单里多一条永远匹配不上的垃圾项不出错，但也就没人发现归一化其实漏判了。
         */
        private val HOST_CHARS = Regex("""[0-9a-z._:\-]+""")

        /** [sourceUrl] 是书源自身 URL；[ruleTexts] 是该源所有 URL 形态规则的原文 */
        fun of(sourceUrl: String, ruleTexts: List<String>): SourceHostAllowlist {
            val hosts = LinkedHashSet<String>()
            (ruleTexts + sourceUrl).forEach { text ->
                URL_AUTHORITY.findAll(text).forEach { match ->
                    normalize(match.groupValues[1])?.let(hosts::add)
                }
            }
            return SourceHostAllowlist(hosts)
        }

        /**
         * 归一化：剥 userinfo、剥端口、剥 IPv6 方括号与 zone id、去尾点、转小写。
         * 解不出 host 的授权段（空、只有端口、只有 `[`、含分隔符）回 null，由调用方判拒。
         */
        internal fun normalize(authority: String): String? {
            val withoutUserInfo = authority.substringAfterLast('@', authority)
            val hostPart = if (withoutUserInfo.startsWith('[')) {
                withoutUserInfo.substringAfter('[').substringBefore(']')
            } else {
                withoutUserInfo.substringBefore(':')
            }
            return hostPart
                .substringBefore('%')
                .trimEnd('.')
                .lowercase(Locale.US)
                .takeIf { it.isNotEmpty() && HOST_CHARS.matches(it) }
        }
    }
}
