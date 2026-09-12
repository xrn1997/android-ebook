package com.ebook.source.script

import com.ebook.source.sandbox.JsStatus
import kotlinx.serialization.json.JsonObject

/**
 * URL 规则串 → 绝对地址 + 选项（规格 §6）。
 *
 * 处理次序（每一步的理由）：
 * 1. `{{}}` 展开复用 [Interpolation] 的占位符机制——展开值常含 `&`/`||`/`{}`，
 *    就地回填会让 URL 被自己的数据腰斩（§5.1）；`key`/`page`/`baseUrl`/变量经
 *    `EvalContext.builtin` 就地可解，`@@规则` 递归求值由调用方注入闭包。
 * 2. 尾段在**占位符态**定位（花括号结构完整）、在**回填后**解析（值进 JSON 字符串
 *    才有意义）；回填值破坏 JSON 结构（关键词带引号）时按语法错误如实报。
 * 3. `<,内容>` 页码形态（§6.4 本仓规定）在**占位符态**判形与取舍——若先回填再判断，
 *    展开值里形如 `<...,...>` 的内容会被误当页码段吃掉（§5.1「展开值不参与切分」
 *    的同一口径），取舍完成后才回填。页码 1 整段（含分隔符）不进 URL，其余页输出
 *    「分隔符 + 内容」。没有 `,` 结构的 `<...>` 不是页码段，原样保留——
 *    本仓不得自行发明 `<...>` 的其它用法。
 * 4. 相对落位复用 `TocPageUrl.join` 三形态语义（§6.5/§12：不另立口径）。
 *    **绝不复用 `ListPageUrl.build` 的首页裁剪**：脚本 URL 由作者写全形态，
 *    对已算好的 URL 再裁一次会裁掉真实页码段（§6.4 明令）。
 * 5. **例外，且在 1 之前**：整条写成 JS 的规则（§6.1「复杂 URL 可整条写成 JS」）先求值再走 2–4，
 *    见 [resolveJsUrl]。
 */
internal object ScriptUrlResolver {

    fun resolve(
        ruleUrl: String,
        ctx: EvalContext,
        sourceRoot: String,
        evaluateInner: (String) -> RuleResult,
    ): ResolvedScriptUrl {
        if (isWholeJsUrl(ruleUrl)) return resolveJsUrl(ruleUrl, ctx, sourceRoot, evaluateInner)
        val (expansion, masked) = Interpolation.expand(ruleUrl, ctx, evaluateInner)
        val (urlMasked, tailMasked) = ScriptUrlOption.splitTail(masked) ?: (masked to null)
        val urlFilled = expansion.fill(anglePages(urlMasked, ctx.page))
        val absolute = com.ebook.source.analyze.TocPageUrl.join(ctx.baseUrl, urlFilled, sourceRoot)
        val options = tailMasked
            ?.let { ScriptUrlOption.parse(parseObject(expansion.fill(it), ruleUrl), ruleUrl) }
            ?: ScriptUrlOptions.DEFAULT
        return ResolvedScriptUrl(absolute, options)
    }

    /** 见 [resolveJsUrl]：判据只看串首，不用 `contains`。 */
    private fun isWholeJsUrl(rule: String): Boolean {
        val head = rule.trimStart()
        return head.startsWith("@js:", ignoreCase = true) || head.startsWith("<js>")
    }

    /**
     * 整条是 JS 的 URL 规则（规格 §6.1「复杂 URL 可整条写成 JS」；语料 1168 条源里
     * 282 条 `@js:` + 70 条 `<js>` 开头）：先求值出地址文本，再按同一套尾段/落位口径处置。
     *
     * 未接线时的症状不是报错，而是把脚本原文当相对地址拼到源根后**真的发出去**
     * （回放日志里满屏 `https://host/@js: var k = …` 的 403/404），比崩溃更难发现。
     *
     * 为什么**不**先展开再判形：本层把原样规则串交给求值器，剥标志、`{{}}` 展开、真求值全在
     * 求值器与沙箱那一侧。这里先 expand 一次、求值器再 expand 一次就是两套事实源，
     * 且 `{{java.put(…)}}` 这类带副作用的插值会跑两遍。
     *
     * 判据只看**串首**：`@js:` 与 `<js>` 作为查询参数值出现在合法 URL 里是真实形态，
     * 放宽成 `contains` 会把那条地址换进 JS 分支——症状是搜索永远没结果而不报错。
     *
     * 产出侧不再做 [anglePages]：JS 的输出是**数据**，里面的 `<,2>` 不是作者写的页码段
     * （§6.4「只在占位符态取舍」的同一条理由）；但选项尾段照切——语料里 JS 就是靠
     * `url + ',{"headers":{…}}'` 把选项拼出来的（§6.1）。
     */
    private fun resolveJsUrl(
        rule: String,
        ctx: EvalContext,
        sourceRoot: String,
        evaluateInner: (String) -> RuleResult,
    ): ResolvedScriptUrl {
        // 空产出不能一路走到 join：那会把请求落在源首页，拿回来的正是
        // 「看着正常、实则错到底」的内容（§9 末段禁止的回退），故就地按执行失败报
        val produced = evaluateInner(rule).firstText().trim()
        if (produced.isEmpty()) {
            throw JsExecutionFailedException(JsStatus.RUNTIME, "URL 的 JS 规则没有给出地址：${rule.take(80)}")
        }
        val (urlText, tailText) = ScriptUrlOption.splitTail(produced) ?: (produced to null)
        return ResolvedScriptUrl(
            url = com.ebook.source.analyze.TocPageUrl.join(ctx.baseUrl, urlText, sourceRoot),
            options = tailText
                ?.let { ScriptUrlOption.parse(parseObject(it, rule), rule) }
                ?: ScriptUrlOptions.DEFAULT,
        )
    }

    /**
     * 已回填的尾段文本 → JSON 对象；回填值破坏 JSON 结构时按语法错误如实报（§3.3）。
     *
     * [tail] 由 [ScriptUrlOption.splitTail] 产出、以定位用的 `,` 开头——那是切分定位的
     * 分隔符残留而非 JSON 的一部分，解析前剥掉；kotlinx 对前导逗号即使在 lenient 模式下
     * 也按语法错误拒绝，不剥就是「所有带尾段的 URL 全部误报语法错误」。
     */
    private fun parseObject(tail: String, rule: String): JsonObject =
        runCatching { ScriptJson.parseToJsonElement(tail.removePrefix(",")) }
            .getOrNull() as? JsonObject
            ?: throw RuleSyntaxException(rule)

    /**
     * `<分隔符,内容>` 形态（§6.4）。输入是**占位符态**文本：段落取舍只看结构，
     * 选中段的内容（含 `{{page}}`）留给调用方在取舍后回填——先回填再判断会把
     * 展开值里形如 `<...,...>` 的内容误当页码段吃掉。未闭合的 `<` 原样保留：
     * 残缺形态是真实输入，抛出去会让整条 URL 作废。
     */
    private fun anglePages(url: String, page: Int): String {
        if (!url.contains('<')) return url
        val out = StringBuilder(url.length)
        var i = 0
        while (i < url.length) {
            if (url[i] == '<') {
                val close = url.indexOf('>', i + 1)
                val inner = if (close > i) url.substring(i + 1, close) else null
                val comma = inner?.indexOf(',')
                if (inner != null && comma != null && comma >= 0) {
                    if (page > 1) {
                        out.append(inner, 0, comma).append(inner.substring(comma + 1))
                    }
                    i = close + 1
                    continue
                }
            }
            out.append(url[i])
            i++
        }
        return out.toString()
    }
}

/** 一次 URL 解析的产物：绝对地址 + 请求选项（无尾段时为 [ScriptUrlOptions.DEFAULT]） */
internal data class ResolvedScriptUrl(val url: String, val options: ScriptUrlOptions)
