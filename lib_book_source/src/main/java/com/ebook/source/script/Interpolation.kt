package com.ebook.source.script

/**
 * 一次 `{{}}` 展开的产物：**占位符化**的规则文本 + 序号到展开值的映射。
 *
 * 为什么用占位符而不是在切分前就地回填（规格 §5.1 的本仓规定）：展开值常含
 * `||`/`&&`/`##`/`@`（关键词、带查询串的 URL、HTML 片段），若先回填再切分，一条规则会被
 * 自己的数据腰斩——症状是「换个关键词就解不出东西」，而根因在规则串里完全看不出来。
 * 故 [Interpolation.expand] 只把每个 `{{…}}` 换成一个不含任何分隔符的占位符，
 * 由本类在**使用点**（leaf 载荷、替换段的模式与文本、最终结果）把值换回去。
 *
 * 占位符用控制字符 [HEAD] 包序号：真实规则串里不会出现这个字符，也不与
 * `{}`/`[]`/引号中的任何一种深度规则冲突。
 */
internal class Expansion(private val values: Map<Int, String>) {

    /**
     * 把 `\u0001<序号>\u0001` 换回展开值。不认识/残缺的占位符原样保留——
     * 这类形态只可能来自手工构造的字符串，回退成空串会静默吞掉规则串的一部分。
     */
    fun fill(text: String): String {
        if (values.isEmpty() || !text.contains(HEAD)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == HEAD) {
                val end = text.indexOf(HEAD, i + 1)
                val slot = if (end > i + 1) text.substring(i + 1, end).toIntOrNull() else null
                if (slot != null && values.containsKey(slot)) {
                    out.append(values.getValue(slot))
                    i = end + 1
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    /**
     * 结果侧兜底回填：展开值本身成为结果文本时（`@get:` 读回的变量、`@@` 递归求出的值）
     * 也不许把占位符漏给用户。[RuleResult.Nodes] 原样穿过——节点来自被解析的文档，
     * 文档里不含占位符。
     */
    fun fillResult(result: RuleResult): RuleResult = when (result) {
        RuleResult.Miss -> result
        is RuleResult.Texts -> RuleResult.Texts(result.values.map { fill(it) })
        is RuleResult.Matches -> RuleResult.Matches(result.items.map { item -> item.map { fill(it) } })
        is RuleResult.Nodes -> result
        // 与 Nodes 同理：JSON 节点来自被解析的文档，不含占位符
        is RuleResult.Jsons -> result
    }

    companion object {
        /** 占位符包装字符（控制字符，理由见类 KDoc） */
        const val HEAD = '\u0001'

        /** 没有展开项时的空实现：不携带展开上下文的求值路径（如 `@@` 递归）用它 */
        val EMPTY = Expansion(emptyMap())
    }
}

/**
 * `{{}}` 插值与 `@put:`/`@get:` 的**声明式子集**（规格 §5.1、§5.2、§5.3）。
 *
 * 支持的形态只有三类：`@@<规则>` 递归求值、[EvalContext] 的内置量（`key`/`page`/`baseUrl`）、
 * 已 `@put:` 写入的变量名。其余交给 `ctx.js` 求值，**未装配沙箱时才抛**
 * [JsEvaluationPendingException]——**不原样保留、也不回退成空**：
 * `{{(page-1)*20}}` 原样拼进 URL 拿到的是字面量表达式而不是页码，属「看着正常、实则错到底」的内容
 * （§9 末段、§12）。这条取舍如今解释的是桥那边：`{{…}}` 交给 `ctx.js` 后没有「没值」这个第三态——
 * [ScriptJsBridge.runExpression] 的返回类型是 String，跑成 `undefined`/`null` 就展开成空串
 * （§9「未取到值 → 空串」），既不回一个 null 让调用方去拼「半个 URL」，也不会因为一个本职是
 * 副作用的表达式把整条规则作废；回对象/数组才是失败（拼成文本会得到一段看着像 URL 片段的 JSON）。
 * `book.*`/`chapter.*`/`$.`/`java.*` 都不在本段的
 * 声明式子集里，一律落给 `ctx.js`：`book` 由手上真有书实体的调用点绑进来（详情/目录页），
 * 正文侧的 `chapter`/`src`/`title` 恒缺（规格 §11-31，正文链路只握得到一个 contentRef 字符串）。
 * 本段不猜它们的值。
 *
 * 「展开发生在切分之前还是之后」是 §11 的未知项：本仓选**之前**（§9 第 1 步），
 * 但展开值不参与切分，由 [Expansion] 的占位符承担。改这条次序必须同时改本 KDoc、
 * [ScriptRuleEvaluator.evaluate] 与单测。
 */
internal object Interpolation {

    /** 裸标识符或点分属性路径（`key`、`baseUrl`、`book.name`）；含运算符/括号/`$` 的一律不是 */
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")

    /**
     * 第 1 步：把规则串里的每个 `{{…}}` 换成占位符，返回（展开表, 占位符化的规则串）。
     *
     * [evaluateInner] 用于 `@@<规则>` 的递归求值——它必须用**同一次求值的输入**，
     * 故由调用方（[ScriptRuleEvaluator]）以闭包形式带进来，本对象不持有求值上下文。
     *
     * 残缺形态一律按字面量留着、不抛：`class.{{x@text` 这类残缺串是真实输入，
     * 抛出去会让整条字段作废，而原样保留最坏只是选不中。同串里「先残缺后合法」时各归各——
     * 内层又出现 `{{` 说明外层没有合法的 `}}` 与之配对（否则内层就在值里面了），
     * 此时把外层那个 `{{` 当普通字符放行，其后的合法插值照常展开。
     */
    fun expand(
        rule: String,
        ctx: EvalContext,
        evaluateInner: (String) -> RuleResult,
    ): Pair<Expansion, String> {
        val values = LinkedHashMap<Int, String>()
        val masked = StringBuilder(rule.length)
        var slot = 0
        var i = 0
        while (i < rule.length) {
            if (rule.startsWith("{{", i)) {
                val end = rule.indexOf("}}", i + 2)
                val nested = rule.indexOf("{{", i + 2)
                if (end < 0 || (nested in 0 until end)) {
                    // 未闭合、或内层又开了一个 `{{`：当前 `{{` 按字面量处理，只前进一个字符，
                    // 让紧随其后的合法插值仍能被扫描到
                    masked.append(rule[i])
                    i++
                } else {
                    values[slot] = resolve(rule.substring(i + 2, end).trim(), ctx, evaluateInner)
                    masked.append(Expansion.HEAD).append(slot).append(Expansion.HEAD)
                    slot++
                    i = end + 2
                }
            } else {
                masked.append(rule[i])
                i++
            }
        }
        return Expansion(values) to masked.toString()
    }

    /**
     * 单个 `{{…}}` 的求值：`@@` 递归；合法标识符先查内置量与变量表；其余交给 `ctx.js` 求值，
     * **未装配沙箱时才抛** [JsEvaluationPendingException]。
     *
     * 判定刻意只有两条（是不是合法标识符），而不是去枚举「哪些写法看起来像 JS」——
     * 后者每加一种写法就要改一次条件，且漏判的症状是静默算出错内容。
     */
    private fun resolve(expr: String, ctx: EvalContext, evaluateInner: (String) -> RuleResult): String {
        if (expr.startsWith("@@")) return evaluateInner(expr).firstText()
        // §5.1 实证形态 `{{$.type_name}}`：`$.` 前缀是 JSONPath 规则，走规则求值而不是 JS
        if (expr.startsWith("$.")) return evaluateInner(expr).firstText()
        if (IDENTIFIER.matches(expr)) {
            // 内置量与变量表都查得到就先查它们：`{{key}}` 没有理由为了一个字符串去趟沙箱
            ctx.builtin(expr)?.let { return it }
            ctx.variables[expr]?.let { return it }
        }
        // §5.1「可写任意规则，但必须带标志头」：带已知标志的表达式按规则求值，
        // 而不是送进 JS 引擎（`@css:selector@text` 送 JS 只会得到 `expecting ';'`）
        if (hasRuleFlag(expr)) return evaluateInner(expr).firstText()
        // 到这一支的一律是「声明式子集解不了」：非标识符（算术、函数调用）或标识符但表里没有
        // （`book.name` 走这里）。有桥就交给 JS，没桥才是「待执行」
        return ctx.js?.runExpression(expr) ?: throw JsEvaluationPendingException(expr)
    }

    /**
     * 表达式是否以已知规则标志开头（§2.1 标志表）。
     *
     * 与 [RuleMode.hasKnownFlag] 口径一致但独立维护：那边剥前缀用、这边判分支用，
     * 合并会让两个调用方的演化互相牵制。漏判的症状是静默送 JS 得到语法错；
     * 多判的症状是把 JS 表达式误当规则——后者至少抛类型化异常而不是算错内容。
     * **大小写口径跟着 RuleMode 一起演化**（标志键一律不区分大小写）：只在那边开 ignoreCase
     * 而漏了这一处，`{{@CSS:…}}` 仍会被当 JS 送进内核，报错话术是内核的 `expecting ';'`。
     */
    private fun hasRuleFlag(expr: String): Boolean =
        expr.startsWith("@css:", ignoreCase = true) || expr.startsWith("@js:", ignoreCase = true) ||
            expr.startsWith("@xpath:", ignoreCase = true) ||
            expr.startsWith("@json:", ignoreCase = true) ||
            expr.startsWith("@put:", ignoreCase = true) || expr.startsWith("@get:", ignoreCase = true) ||
            expr.startsWith("@cache:", ignoreCase = true) ||
            expr.startsWith("//") || expr.startsWith(":") ||
            expr.contains("<js>")

    /**
     * `@put:` 的一条目：键与规则原文（引号已剥）。§5.2 原文：JSONPath 值不需要引号、
     * 其他模式的规则要加引号——本方法两种都接受，**只负责剥引号**，不校验载荷形态。
     * 键或值为空、缺冒号时返回 null（调用方据此判「这条写入无效」）。
     */
    internal fun parsePutEntry(entry: String): Pair<String, String>? {
        val t = entry.trim()
        val colon = t.indexOf(':')
        if (colon <= 0) return null
        val key = t.substring(0, colon).trim()
        var value = t.substring(colon + 1).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        return if (key.isEmpty() || value.isEmpty()) null else key to value
    }

    /**
     * 逗号切段，但跳过引号与 `{}`/`[]` 内部——值里含逗号是常态（`{"a":"x,y"}`）。
     * 切错不会报错，只会静默少存一个变量，故单测直接对切分结果断言。
     *
     * 不需要处理 `{{}}`：它在进本方法之前已被 [expand] 换成占位符（占位符不含逗号）。
     */
    internal fun topLevelEntries(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var cursor = 0
        var depth = 0
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && (i == 0 || text[i - 1] != '\\') -> inQuotes = !inQuotes
                !inQuotes && (c == '{' || c == '[') -> depth++
                !inQuotes && (c == '}' || c == ']') -> depth = (depth - 1).coerceAtLeast(0)
                !inQuotes && c == ',' && depth == 0 -> {
                    out += text.substring(cursor, i)
                    cursor = i + 1
                }
            }
            i++
        }
        out += text.substring(cursor)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
