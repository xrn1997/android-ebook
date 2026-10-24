package com.ebook.find.booksource

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ScriptSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.api.entity.SourceFormatDetector
import com.ebook.common.analyze.source.SourceAdmin
import com.ebook.common.analyze.source.SourceCatalog
import com.ebook.common.analyze.source.SourceCodec
import com.ebook.common.analyze.source.SourceStorageJson
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入预览的一项 = **出身格式** + 该格式的载荷 + 校验结论 + 覆盖判定。
 *
 * 两种出身共用这一条数据类、而不是拆成 sealed 的两个子类：预览弹层的一行要按**同一套行样式**
 * 渲染（状态图标 / 名称 / 地址 / 原因行 / 「将覆盖」标记），两者的差别只有「脚本」出身标记与
 * 若干警示行这两小段。拆成两个类就会把那套样式写两遍，日后改一处忘一处——
 * 判据（[validation]）也因此是同一个 [ValidationResult] 类型，脚本侧不另长一套词汇。
 *
 * **不变式：载荷按 [format] 恰好取一个**。原生项填 [nativeRule]；脚本项填 [scriptRule]
 * （+ 落库要用的 [scriptRawJson] 与展示要用的 [scriptWarnings]）。构造点只有
 * [toNativePreviewItem] / [toScriptPreviewItem] 两处，各自只会填自己那一侧；
 * 字段留默认值是为了让两处构造点不必写满八个参数，[commit] 里仍对「格式与载荷不符」
 * 留了一条 failure 兜底——真出现了就是构造点写错，宁可计入失败条数也不静默丢条目。
 *
 * [willOverwrite] 与 [overwriteFormat] 在解析时就按库里的当前状态算好（而不是弹层渲染时现算）：
 * 用户确认前若清单已变，落库走的仍是 REPLACE 语义，语义上没错，
 * 但预览上「将覆盖」必须与用户看到的那一份清单同源。
 */
data class ImportPreviewItem(
    val format: SourceFormat,
    val nativeRule: BookSourceRule? = null,
    val scriptRule: ScriptSourceRule? = null,
    val scriptRawJson: String = "",
    val scriptWarnings: List<ScriptWarning> = emptyList(),
    val validation: ValidationResult,
    val willOverwrite: Boolean,
    val overwriteFormat: SourceFormat? = null,
) {
    /** 只有校验通过的条目会被 [commit] 落库；警示不算不通过 */
    val isValid: Boolean get() = validation is ValidationResult.Valid

    /**
     * 展示名：按 [format] 只读自己那一侧的键，UI 不必先判格式再决定读哪个字段。
     *
     * 写成 `when (format)` 而不是 `nativeRule?.name ?: scriptRule?...`：跨格式的 `?:` 链
     * 会让「出身与载荷不一致」的条目借用另一格式的字段渲染出来——构造点写错
     * （脚本项忘了填 scriptRule、或 format 标错）本是一条该暴露出来的路由错误，
     * 却表现为「名字照样显示、一切正常」，永远没人发现。按 format 分支后这种条目只会显示为
     * 空名，预览层于是回落到「未命名」这一眼可见的异常态。
     */
    val displayName: String
        get() = when (format) {
            SourceFormat.NATIVE -> nativeRule?.name.orEmpty()
            SourceFormat.SCRIPT -> scriptRule?.bookSourceName.orEmpty()
        }

    /** 展示地址（取法同 [displayName]，也是「将覆盖」判定的输入） */
    val displayUrl: String
        get() = when (format) {
            SourceFormat.NATIVE -> nativeRule?.url.orEmpty()
            SourceFormat.SCRIPT -> scriptRule?.bookSourceUrl.orEmpty()
        }
}

/**
 * 书源导入管线：把一段社区 JSON 变成预览项，并在用户确认后逐条落库。
 *
 * 从 [BookSourceViewModel] 抽出的原因：解析（切顶层对象、判出身、解码、校验、覆盖判定）与
 * 落库（分流写面、统计成功/覆盖/失败）都是**与页面状态无关**的纯编排，留在原先的 ViewModel 里
 * 既难单独测试，也让「VM 只持有状态」这条边界失效。抽取后 VM 只负责把结果放进状态流、
 * 把语义提示翻成文案资源；本类可注入假件在单测下锁住导入语义（解析走 `org.json`，用例需 Robolectric）。
 *
 * 两条不变式与抽取前一致：
 * - 原生走 [SourceCodec.importFromJson] + 四条结构校验，脚本走 [SourceStorageJson] +
 *   [BookSourceValidator.validateScript]；混排包逐条各走各路，坏条目只标为不通过；
 * - 落库时脚本交出预览看到的那份原文整块（零翻译，ADR-0029 决策 1），原生交出规则对象。
 */
@Singleton
class SourceImportPlan @Inject constructor(
    private val sourceCatalog: SourceCatalog,
    private val sourceAdmin: SourceAdmin,
    private val sourceCodec: SourceCodec,
) {

    /** 解析结论：要么得到预览清单，要么整段文本读不出任何书源对象 */
    sealed interface ParseResult {
        data class Parsed(val items: List<ImportPreviewItem>) : ParseResult
        data object Unreadable : ParseResult
    }

    /**
     * 落库结论。
     *
     * @param success 成功落库条数（**含**其中属于覆盖的条目）
     * @param overwritten 成功条目里命中已存在 URL 的条数，是 [success] 的子集
     * @param failed 没进库的条数：校验未通过 + 写库失败
     * @param failures 写库失败的异常清单，由调用方（VM）经共享的 `reportFailure` 上报；
     *   本类不持有 ViewModel，也不直接弹提示
     */
    data class CommitResult(
        val success: Int,
        val overwritten: Int,
        val failed: Int,
        val failures: List<Throwable> = emptyList(),
    )

    /**
     * 解析导入文本，产出预览清单；**只读不落库**。
     *
     * 两种形态自适应：社区书源 JSON 既有一整包数组（`[{...}, {...}]`）也有单条对象（`{...}`）。
     * **实现按首字符分派**（`[` 走数组、`{` 走单条），语义就是「先试数组、退到单条」；
     * 不写成「数组抛异常再解一次单条」：包里混进一个坏元素时，退路会把整包再当单条解一遍，
     * 用户于是看到第二条莫名其妙的错误，而首字符分派只失败一次、结论只有一个。
     */
    suspend fun parse(jsonStr: String): ParseResult {
        val items = splitJsonObjects(jsonStr).map { toPreviewItem(it) }
        return if (items.isEmpty()) ParseResult.Unreadable else ParseResult.Parsed(items)
    }

    /**
     * 确认导入：只把校验通过的条目落库，返回统计结果。
     *
     * 逐条写（不是批量接口）：写面只有单条 [SourceAdmin.addSource] /
     * [SourceAdmin.addScriptSource]，且它们的失败是**逐条**语义（一条 URL 空白不该让整包回滚）。
     * 两条写路径按 [ImportPreviewItem.format] 分流，**脚本书源交出的必须是预览看到的那份原文**
     * （[ImportPreviewItem.scriptRawJson] 整块）：零翻译是脚本书源的存储契约（ADR-0029 决策 1），
     * 落库前若经模型转一道，规则块与脚本就全丢了，「重导即升级」也跟着失效。
     * 统计口径见 [CommitResult]：`success` 含覆盖，`failed` = 校验未通过 + 写库失败。
     * 中途某条写失败不提前退出——用户看到的清单是一整包，只告诉他「这一包导入的结果」
     * 比「第一笔失败就不管了」更贴合预期。
     */
    suspend fun commit(items: List<ImportPreviewItem>): CommitResult {
        val valid = items.filter { it.isValid }
        var success = 0
        var overwritten = 0
        val failures = mutableListOf<Throwable>()
        for (item in valid) {
            val result = when (item.format) {
                SourceFormat.SCRIPT -> sourceAdmin.addScriptSource(item.scriptRawJson)
                SourceFormat.NATIVE -> {
                    val rule = item.nativeRule
                    // 走到这里 rule 为 null 只可能是构造点写错（预览项由本类自己造的）：
                    // 宁可计入失败条数并上报，也不静默丢一条——静默会让「成功 N 条」这个数字说谎
                    if (rule == null) {
                        Result.failure(IllegalStateException("原生出身的预览项缺少规则，无法落库"))
                    } else {
                        sourceAdmin.addSource(rule)
                    }
                }
            }
            result
                .onSuccess {
                    success++
                    if (item.willOverwrite) overwritten++
                }
                .onFailure { e ->
                    failures += e
                }
        }
        return CommitResult(
            success = success,
            overwritten = overwritten,
            failed = items.size - valid.size + failures.size,
            failures = failures,
        )
    }

    /**
     * 把文本切成顶层 JSON 对象：数组取每个元素，单对象取它自身。
     *
     * 解析异常一律收敛成空表：调用方按「读不出内容」提示用户，不把 `JSONException` 抛进 UI。
     */
    private fun splitJsonObjects(jsonStr: String): List<JSONObject> = try {
        val text = jsonStr.trim()
        when {
            text.startsWith("[") -> {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }

            text.startsWith("{") -> listOf(JSONObject(text))

            // 既不是数组也不是对象（空文件、纯文本、HTML 错误页存成的 .json）
            else -> emptyList()
        }
        // 只捕 JSONException（Android 的 org.json 解析失败一律抛它）而不是 Exception：
        // 后者会把协程取消也一并咽掉，让「页面已销毁」的调用继续往下走
    } catch (_: JSONException) {
        emptyList()
    }

    /**
     * 单个 JSON 对象 → 预览项：先按顶层键集判别出身，再走各自的解码与校验。
     *
     * 键集取自 `org.json` 已经切好的那一个对象（不回头再解整段文本），所以判的是「这一条」；
     * 判别没有第三种可能——[SourceFormatDetector] 认不出特征键时一律回落 NATIVE，
     * 于是未知形态仍走原生那四条校验，报出的原因对用户更有指向性（判据见该类的注释）。
     */
    private suspend fun toPreviewItem(obj: JSONObject): ImportPreviewItem =
        if (SourceFormatDetector.detect(obj.keys().asSequence().toSet()) == SourceFormat.SCRIPT) {
            toScriptPreviewItem(obj)
        } else {
            toNativePreviewItem(obj)
        }

    /**
     * 原生分支：解码与四条校验**沿用既有路径**，只是包装成双格式的 [ImportPreviewItem]。
     *
     * 解不出规则（[SourceCodec.importFromJson] 返回 null）的唯一成因是 `name` 或 `url`
     * 为空，这两个字段正是这里能从 `org.json` 直接读到的，于是用它们拼一条**退化规则**去校验：
     * 预览层仍能指出「名称为空 / 地址不是 http 开头」，而不会把整条源静默丢掉。
     * 退化规则的其余规则字段为空，会一并报「缺入口 URL / 缺解析规则」——这条源本来也导不进来，
     * 多报的原因不算误导。
     */
    private suspend fun toNativePreviewItem(obj: JSONObject): ImportPreviewItem {
        val rule = sourceCodec.importFromJson(obj.toString())
            ?: BookSourceRule(
                name = obj.optString("name"),
                url = obj.optString("url"),
            )
        val existing = formatOf(rule.url)
        return ImportPreviewItem(
            format = SourceFormat.NATIVE,
            nativeRule = rule,
            validation = BookSourceValidator.validate(rule),
            willOverwrite = existing != null,
            overwriteFormat = existing,
        )
    }

    /**
     * 脚本分支：顶层字段 + 原文 → 有效性与警示。
     *
     * [BookSourceValidator.validateScript] 要同时拿到解码后的顶层字段与原文
     * （可执行代码只能从原文里扫出来），解码用 [SourceStorageJson]——与
     * [SourceAdmin.addScriptSource] 同一个解码器，于是预览标「可导入」的条目
     * 落库时不会在解码这一步被撞回来。
     *
     * **解不出时不丢条目、也不猜字段**（与 [toNativePreviewItem] 的退化规则同一手法）：
     * 名称与地址这两个字符串键仍从 `org.json` 读得到，就用它们拼一条退化模型，
     * 预览层照样指得出「这一条叫什么、地址哪里不对」，再补一条 [ValidationReason.SCRIPT_UNPARSABLE]
     * 说明整块读不出来（这一条排最前：名称/地址那两个原因是它的下游细节）。
     * 此时**不给警示**：`bookSourceType`、`loginUrl` 这些字段本身就在这次失败里读不出来，
     * 拿模型默认值去判「文本源、无登录」等于替用户宣布一个没有依据的结论。
     */
    private suspend fun toScriptPreviewItem(obj: JSONObject): ImportPreviewItem {
        val raw = obj.toString()
        val decoded = SourceStorageJson.parseOrNull(raw)
        val script = decoded ?: ScriptSourceRule(
            bookSourceName = obj.optString("bookSourceName"),
            bookSourceUrl = obj.optString("bookSourceUrl"),
        )
        val check = BookSourceValidator.validateScript(script, raw)
        val validation = if (decoded == null) {
            ValidationResult.Invalid(listOf(ValidationReason.SCRIPT_UNPARSABLE) + check.reasons)
        } else {
            check.result
        }
        val existing = formatOf(script.bookSourceUrl)
        return ImportPreviewItem(
            format = SourceFormat.SCRIPT,
            scriptRule = script,
            scriptRawJson = raw,
            scriptWarnings = if (decoded == null) emptyList() else check.warnings,
            validation = validation,
            willOverwrite = existing != null,
            overwriteFormat = existing,
        )
    }

    /**
     * 库里同 URL 那一行的出身；URL 空白或库里没有这一行时为 null。
     *
     * 走 [SourceCatalog.getFormatByUrl] 而不是 `getAllSources`：后者只对原生行有值，
     * 脚本行会被它当成「库里不存在」，覆盖判定与出身文案就都错了。
     * 空白 URL 不必去问——主键为空不可能有行，问了也是白问。
     */
    private suspend fun formatOf(url: String): SourceFormat? =
        url.takeIf { it.isNotBlank() }?.let { sourceCatalog.getFormatByUrl(it) }
}
