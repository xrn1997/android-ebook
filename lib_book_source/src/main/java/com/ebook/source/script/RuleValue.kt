package com.ebook.source.script

import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Element

/**
 * 一次规则求值的**输入**（规格 §9 第 5 步「每支的输入 = 当前上下文文档/文本」）。
 *
 * 三种形态对应格式里的三类上下文，刻意不做互相隐式转换：从页面起步与从节点集起步
 * 的语义不同（前者要先 `Jsoup.parse`，后者已经在列表内），隐式转换会让「列表字段」
 * 与「字段内的子规则」两条路径混成一条，而那两条在 §1.2 里是明确分层的。
 */
internal sealed interface RuleValue {

    /** 整页源码。AllInOne 正则与「从页面起步」的链式/CSS 规则用它 */
    data class Page(val source: String, val baseUrl: String = "") : RuleValue

    /** 当前节点集。列表字段解出的每一条子规则以此为输入 */
    data class Nodes(val elements: List<Element>) : RuleValue

    /** 当前文本集（如上一步已取到的字段值） */
    data class Texts(val values: List<String>) : RuleValue

    /**
     * 当前 JSON 文档（规格 §2.1 的 JSONPath 模式上下文）。
     *
     * 真实来源是取文层拿回的响应文本经 lenient 解析（解析失败按 [RuleResult.Miss] 处理，
     * 见 [JsonPathBackend] 的种子逻辑）——响应不是规则串，坏响应属「未取到值」而非「规则写错」。
     */
    data class Json(val element: JsonElement) : RuleValue
}
