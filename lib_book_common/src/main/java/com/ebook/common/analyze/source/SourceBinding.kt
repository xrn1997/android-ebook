package com.ebook.common.analyze.source

import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException

/**
 * 按书源 URL 解析出的四档结果。
 *
 * 旧接口 `getParserFor(): BookParser?` 的 null 同时代表「本地书」「空白/库中无行」
 * 「原生坏行」三种成因，各生产调用点都得各自再推导一次；脚本失败又是另一套异常。
 * 这里把四档收进类型，调用点只模式匹配一次：
 *
 * - [Local]：本地书 tag（`loc_book`），不归属任何书源，不是故障；
 * - [Found]：真解析器。脚本行取 parser 永不抛，坏 JSON 留到首次求值；
 * - [Missing]：空白 tag 或库里没有这行（源被删 / 脏 tag）；
 * - [Broken]：行在，但原生 `rule_json` 解不出来（降级或手工改库的脏行）。
 *   本档**不带 cause**：真正的解码失败已在 [BookSourceManagerImpl] 的 `toItem` 记 ERROR，
 *   这里再造一个异常只会把「坏了」说第二遍——类型不承诺它给不出的东西。
 *
 * [Missing] 与 [Broken] 在 UI 上可以给同一句「书源已失效」，但日志/排查必须分开：
 * 前者是数据没了，后者是数据坏了。脚本的「规则解不动」不在这里——它是求值期抛出的
 * `ScriptRuleException` 子类（ADR-0029/0045），不得折叠成 [Broken]。
 */
sealed interface SourceBinding {
    data object Local : SourceBinding

    data class Found(val parser: BookParser) : SourceBinding

    data class Missing(val sourceUrl: String) : SourceBinding

    data class Broken(val sourceUrl: String) : SourceBinding
}

/**
 * 旧 null 读面的兼容适配：只在测试/旧调用点把四档压回 parser-or-null。
 *
 * **生产解析路径一律走 [BookSourceManager.resolveSource] / [parserOrThrow]**：前者区分
 * [SourceBinding.Missing]（数据没了）与 [SourceBinding.Broken]（数据坏了），也才能把
 * 本地书（[SourceBinding.Local]）与故障分开；后者把「只要 parser」的调用统一翻译一次。
 * 本适配保留是为了不把几十处测试布置一次性改成模式匹配；新增生产代码不得使用。
 */
@Deprecated("生产路径用 BookSourceManager.resolveSource；本适配只服务旧调用点/测试",
    ReplaceWith("(resolveSource(sourceUrl) as? SourceBinding.Found)?.parser")
)
suspend fun BookSourceManager.getParserFor(sourceUrl: String): BookParser? =
    (resolveSource(sourceUrl) as? SourceBinding.Found)?.parser

/**
 * 生产取源调用点的统一翻译：
 * - [SourceBinding.Found] → parser；
 * - [SourceBinding.Missing]/[SourceBinding.Broken] → [BookSourceNotFoundException]（用户可处置的「源失效」）；
 * - [SourceBinding.Local] → [IllegalStateException]（本地书不该走网络取源，是编程错误，不是用户可处置的源失效）。
 *
 * 调用方不需要各自重推四档；需要把失败折成返回值的（目录重抓）自行 catch
 * [BookSourceNotFoundException]。
 */
suspend fun BookSourceManager.parserOrThrow(
    sourceUrl: String,
    detail: String? = null,
): BookParser = when (val binding = resolveSource(sourceUrl)) {
    is SourceBinding.Found -> binding.parser
    SourceBinding.Local -> throw IllegalStateException("本地书不应走网络取源：tag=$sourceUrl")
    is SourceBinding.Missing, is SourceBinding.Broken ->
        throw BookSourceNotFoundException(sourceUrl, detail)
}
