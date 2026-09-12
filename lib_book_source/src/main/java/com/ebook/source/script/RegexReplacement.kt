package com.ebook.source.script

/**
 * `##` 尾段：正则替换的净化与 OnlyOne 两形态（规格 §2.4）。
 *
 * - 净化（[onlyFirst] = false）：**循环匹配替换**，跟在任意其他规则之后；
 * - OnlyOne（[onlyFirst] = true，三井号收尾）：只对**第一个**匹配替换。
 *
 * [pattern] 与 [replacement] 都按 Java 正则方言使用（文档示例含 `(?i)`、`\w`、`\d`、`.*?`），
 * 本类只负责**切出这两段**，不执行替换——执行在 2b。把「解析替换段」与「跑替换」分开，
 * 是因为词法层必须能脱离任何被解析的文档单独锁形，而跑替换需要 Jsoup 之外的输入语义。
 *
 * 为什么在**第一个** `##` 处剥：把 URL 选项尾段「拼到结果后面」的惯用法写法是
 * `##$##,{...}`（模式段 `$` 命中串尾，替换文本即**含前导逗号**的尾段本身），它要求模式段之后的
 * `##` 属于「模式与替换的分界」，而替换文本内部再出现 `##` 时它已经是字面量了。
 *
 * 注意那条惯用法第二个 `##` 之后**必须带逗号**：选项尾段的定义就是「以 `,{` 起头」
 * （见 `ScriptUrlOption.optionTailCut`）。写成 `##$##{...}` 不会报错，只会把 `{"..."}` 直接
 * 拼进 URL 而**不被识别为选项**——症状是一个永远请求不到内容的地址。此处按惯用法的实际形态记，
 * 不要按早期草记的简写抄。
 */
internal data class RegexReplacement(
    val pattern: String,
    val replacement: String,
    val onlyFirst: Boolean,
) {
    companion object {

        /** [tail] 必须以 `##` 开头（调用方在第一个深度 0 的 `##` 处切），否则返回 null */
        fun parse(tail: String): RegexReplacement? {
            if (!tail.startsWith("##")) return null
            var rest = tail.substring(2)
            var onlyFirst = false
            when {
                rest.endsWith("###") -> {
                    onlyFirst = true
                    rest = rest.substring(0, rest.length - 3)
                }
                rest.endsWith("##") -> rest = rest.substring(0, rest.length - 2)
            }
            val sep = rest.indexOf("##")
            return if (sep < 0) {
                RegexReplacement(rest, "", onlyFirst)
            } else {
                RegexReplacement(rest.substring(0, sep), rest.substring(sep + 2), onlyFirst)
            }
        }
    }
}
