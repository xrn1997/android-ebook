package com.ebook.source.script

/**
 * 模式判定的结果：模式 + 已剥净的载荷 + 列表反序标志。
 *
 * [reverse] 来自规格 §2.5「**列表反序**：在取列表的规则最前面加负号 `-`」（§10 能力矩阵把该项列为 ✅ 实现）。
 * 它做成 [RuleMode.of] 的一等输出、而不是留给求值层自己看 `-`：反序前缀辖在整条规则上、
 * 且必须**先于**模式判定剥掉，否则 §2.5 自己给的语料实证形态
 * `-:<li><a[^"]+"([^"]*)">([^<]*)` 既认不出 AllInOne（正则身份丢失）、又丢掉反序，
 * 求值层会拿一条链式规则去解一个正则——静默解错内容。
 *
 * [mode] / [body] 是 [RuleMode.of] 原有 Pair 返回位的两个分量，故 `val (mode, body) = RuleMode.of(x)`
 * 的解构写法继续可用（data class 的 component1/component2）。
 */
internal data class RuleHead(val mode: RuleMode, val body: String, val reverse: Boolean)

/**
 * 一条规则（或规则的一段）用哪种解析模式（规格 §2.1 标志表）。
 *
 * 模式必须在链式切分**之前**判定：标志前缀本身就是语义的一部分，
 * 而 `@` 同时也是链式模式的分隔符——先切再判会把 `@css:` 切成一个空段。
 *
 * 词法层只**识别**模式，求值在 `ScriptRuleEvaluator`，而三种特殊模式的下场并不相同：[JS] 已接
 * 沙箱执行器（本机没装配桥时才抛 `JsEvaluationPendingException`）；[XPATH] 与 [UNSUPPORTED]
 * （`@cache:`）都在求值时抛 `UnsupportedRuleFeatureException` 明确拒绝（XPath 判为延后，见 ADR-0029），
 * 区别只在装载期：两者连同 [JS] 一起被记进 `ScriptRuleSet.unsupported`，`@cache:` 额外把自己的
 * 路径登记为 irregular。**那份能力清单目前没有任何 UI 消费者**（只有内部记着，导入预览看不到它），
 * 将来要出逐项警示是从这份数据接，别假设用户已经看得见。识别出来却不实现，和静默当成默认模式解错，
 * 是两件完全不同的事。
 */
internal enum class RuleMode {
    DEFAULT_CHAIN,
    CSS,
    XPATH,
    JSON_PATH,
    REGEX_ALL_IN_ONE,
    VARIABLE_PUT,
    VARIABLE_GET,
    JS,
    UNSUPPORTED,
    ;

    companion object {

        /**
         * 判定模式并剥掉标志前缀，返回 [RuleHead]（模式、载荷、反序标志）。
         * 剥前缀是为了 2b 拿到的就是净内容，不需要再解析一遍前缀。
         */
        fun of(rule: String): RuleHead {
            val trimmed = rule.trim()
            // 反序前缀只在「后面确实跟着一个已知标志」时才算数，见 [hasKnownFlag]
            if (trimmed.startsWith("-") && hasKnownFlag(trimmed.substring(1))) {
                val (mode, body) = flagOf(trimmed.substring(1))
                return RuleHead(mode, body, reverse = true)
            }
            // 全取前缀（`+`）：语料形态 `+@css:.bookbox`，语义是「取全部匹配」——
            // 列表字段本就全取（§3.1），故此处只剥不记，效果等同于 no-op。
            // 与 `-` 共用 hasKnownFlag 守卫：裸 `+100` 这类算术表达式不能误剥
            val body = if (trimmed.startsWith("+") && hasKnownFlag(trimmed.substring(1)))
                trimmed.substring(1) else trimmed
            val (mode, bodyContent) = flagOf(body)
            return RuleHead(mode, bodyContent, reverse = false)
        }

        /**
         * 余串是否以一个已知标志开头（规格 §2.1 表内行）。
         *
         * 未列入的 `@put:`/`@get:`/`@cache:`/`$.`/单花括号都不剥减号：规格 §2.5 的语料实证形态
         * 至今只有 `-:`（AllInOne）一种，把它们扩到写变量、读变量、JSONPath 裸写都属于无据推广，
         * 而误剥的后果是载荷少一个字符、下游按错模式静默求值。2b 需要时再按新证据扩。
         */
        private fun hasKnownFlag(body: String): Boolean =
            body.startsWith(":") ||
                body.startsWith("@@") ||
                body.startsWith("@css:", ignoreCase = true) ||
                body.startsWith("@js:", ignoreCase = true) ||
                body.startsWith("@xpath:", ignoreCase = true) ||
                body.startsWith("@json:", ignoreCase = true) ||
                body.startsWith("//") ||
                // <js></js> 可出现在任意位置并充当分隔符（§2.1），故这一行用 contains 而不是 startsWith
                body.contains("<js>")

        /**
         * §2.1 标志表：按表内顺序先匹配者胜。
         *
         * **标志键一律不区分大小写**（本仓规定，规格 §2.1）：上游文档与真语料里同一标志的两种
         * 写法并存（`@XPath:`/`@xpath:`、`@JSon:`/`@json:`、`@CSS:`/`@css:`——大写 `@CSS:` 在
         * 1168 条源里出现 64 处）。只给其中几行开 ignoreCase 的结局是「同一个源换个大小写就
         * 整条判成语法错误」，且报错话术会把人支去改规则而不是改解释器。
         * 表内的符号型标志（`@@`、`//`、`$.`、`:`）无大小写可言，`<js>` 标签照上游写法保持小写。
         */
        private fun flagOf(body: String): Pair<RuleMode, String> = when {
            // `@@` 是默认（链式）模式的**显式**标志（§2.1 第一行「直接写时可以省略 `@@`」，
            // §5.1 更要求插值里写默认规则必须带它）。必须在此剥掉：留着它会让下游按 `@`
            // 切链式段时先切出两个空段，把 `@@tag.a@href` 变成「空、空、tag.a、href」。
            body.startsWith("@@") -> DEFAULT_CHAIN to body.substring(2)
            body.startsWith("@js:", ignoreCase = true) -> JS to body.substring(4)
            // <js></js> 可出现在任意位置并充当分隔符（§2.1），故用 contains 且整串交给 JS 处理；
            // 先于表内其他标志判定还因为规格 §9：`<js>` 在任何一步出现都必须按「JS 待 Plan 3」处置，
            // 若让 `@css:` 之类先命中，一条混了内联 JS 的规则就会被当成纯 CSS 静默求值。
            body.contains("<js>") -> JS to body
            body.startsWith("@css:", ignoreCase = true) -> CSS to body.substring(5)
            body.startsWith("@xpath:", ignoreCase = true) -> XPATH to body.substring(7)
            body.startsWith("//") -> XPATH to body
            body.startsWith("@json:", ignoreCase = true) -> JSON_PATH to body.substring(6)
            body.startsWith("$.") -> JSON_PATH to body
            body.startsWith("@put:", ignoreCase = true) -> VARIABLE_PUT to body.substring(5)
            body.startsWith("@get:", ignoreCase = true) -> VARIABLE_GET to body.substring(5)
            body.startsWith("@cache:", ignoreCase = true) -> UNSUPPORTED to body
            body.length >= 2 && body.startsWith("{") && !body.startsWith("{{") && body.endsWith("}") ->
                // 单花括号是上一代格式遗留，文档明示只走 JSONPath（§2.1）。
                // 必须先排除 `{{`：整条就是一个插值的规则（`{{java.xxx}}`）属 JS/插值，不是 JSONPath
                JSON_PATH to body.substring(1, body.length - 1)
            body.startsWith(":") -> REGEX_ALL_IN_ONE to body.substring(1)
            else -> DEFAULT_CHAIN to body
        }
    }
}
