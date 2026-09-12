package com.ebook.me.domain

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ScriptSourceRule

/**
 * 书源结构校验（ADR-0016 第 9 条「导入流程」第 2 步）。
 *
 * 定位是**导入前的最小可用判定**：只看规则字段填没填对，不看站点是否活着。
 * 纯函数、无 Android 依赖（两种入参 [BookSourceRule] / [ScriptSourceRule] 都是
 * `lib_ebook_api` 的普通数据类），因此可在 JVM 单测里逐条锁住这些规则
 * （见 `BookSourceValidatorTest`），也便于日后把同一套判据复用到「书源订阅」之类的入口上。
 *
 * 两种出身各有一条入口：原生规则走 [validate]（四条声明式判据），
 * 脚本书源走 [validateScript]（沿用其中的名称/地址两条，另出三项不拦导入的警示）。
 *
 * **为什么不跑连通性测试**（社区阅读 App 常见的「导入后自动测试书源」）：
 * - 代价过高：一次完整测试要跑搜索 → 详情 → 目录 → 正文四条链路，每条都是网络请求 +
 *   结果比对 + 逐步打勾的 UI，MVP 阶段做不起这个成本；
 * - 收益有限：用户导入的多是社区里已被众人验过的 JSON，坏规则通常在结构上就露馅
 *   （缺选择器、缺入口 URL），正是这四条规则拦得住的部分；
 * - 误判更糟：第三方站点常有地域/时段封锁与防爬，超时与否测不出「规则对不对」，
 *   却会把好源标成不可用，用户反而找不到导入入口。
 * 因此这里只做结构校验，**用户可自行在书城/搜索里验证**；
 * 后续的「书源测试」列入 ADR-0016 的「未来工作」，不在本阶段实现。
 */
object BookSourceValidator {

    /**
     * 校验一条书源规则。
     *
     * 四条规则**逐条独立判定、全部收集**（不在第一条失败处短路）：预览层要把一条源
     * 的所有毛病一次列全，否则用户修一个报一个、来回导三次。
     *
     * @return 全部通过为 [ValidationResult.Valid]，否则 [ValidationResult.Invalid] 带非空原因列表
     */
    fun validate(rule: BookSourceRule): ValidationResult {
        val reasons = buildList {
            // 1. 名称是列表与预览里唯一的识别位，空名字会让用户分不清自己导了哪几本书源
            if (rule.name.isBlank()) add(ValidationReason.NAME_BLANK)
            // 2. 规则里的全部 URL 都以书源根地址拼接，非 http(s) 开头等于整条链路的起点就是坏的
            if (!isHttpUrl(rule.url)) add(ValidationReason.URL_NOT_HTTP)
            // 3. 搜索与书城是两条独立入口，两者都空意味着这本书源没有任何「找到书」的路径
            if (rule.searchUrl.isBlank() && rule.ruleFind.url.isBlank()) {
                add(ValidationReason.NO_ENTRY)
            }
            // 4. 结果列表与正文容器两个选择器缺任何一个，都会让「看得到书名」与「读得到正文」断一条
            if (rule.ruleSearch.list.isBlank() || rule.ruleContent.content.isBlank()) {
                add(ValidationReason.NO_PARSE_RULE)
            }
        }
        return if (reasons.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(reasons)
    }

    /**
     * 校验一条脚本书源的最小模型。
     *
     * **有效性只沿用原生四查里的两条**（名称、地址）：另外两条（缺入口、缺解析规则）判的是
     * [BookSourceRule] 的声明式字段，而脚本书源的入口与规则住在原始 JSON 的规则块里，
     * [ScriptSourceRule] 根本看不到它们（社区语料里 62% 的源规则就是整段脚本，
     * 拿选择器去判会把绝大多数正常源误拦在门外）。名称与地址两条则是两种格式共有的硬前提：
     * 名称是清单里唯一的识别位，地址既是主键又是所有请求的拼接起点——
     * 缺任一条连「存下来」都做不到（`BookSourceManager.addScriptSource` 同样拒收）。
     * 报的原因也用同一批 [ValidationReason] 值，预览层才不必为两种出身各写一套渲染。
     *
     * **三项警示一律不拦导入**（ADR-0029 决策 6：逐源报告要把「依赖登录」「漫画/音频类」
     * 「含可执行代码」明示出来）。理由是这三件事都不是「这条源坏了」，而是「这个项目现在
     * 只能给它一部分能力」：用户手上的源可能是漫画源，他知道自己要什么；
     * 拦下来只会把他要用的东西挡在门外，而拦与不拦在功能上没有任何差别（求值端另有各自的边界）。
     *
     * @param rawJson 该源的原始 JSON（即入库的那一份原文），**必填**。可执行代码的扫描只能看它：
     *   `<js>` 与 `@js:` 都出现在规则块里，而那些键不在最小模型上。
     *   这里刻意不给默认空串——空串的语义是「扫过了，没有代码」，与「根本没扫」长得一模一样，
     *   一旦调用方漏传就有静默关掉 [ScriptWarning.HAS_EXECUTABLE_CODE] 这一项的路径
     *   （不闪退、不出错，只是警示永远不出现）。改成必填后，每个调用方都必须自己说清「我扫的是哪一份原文」。
     */
    fun validateScript(source: ScriptSourceRule, rawJson: String): ScriptValidation {
        val reasons = buildList {
            if (source.bookSourceName.isBlank()) add(ValidationReason.NAME_BLANK)
            if (!isHttpUrl(source.bookSourceUrl)) add(ValidationReason.URL_NOT_HTTP)
        }
        val warnings = buildList {
            // 两种脚本形态都要扫到：整段 HTML 里嵌 <js>…</js>，以及规则字段整个以 @js: 取值
            if (rawJson.contains("<js>") || rawJson.contains("@js:")) add(ScriptWarning.HAS_EXECUTABLE_CODE)
            // 登录一族（含验证码/浏览器自动化）不在 v1 的宿主 API 白名单里（语料实测 642 条里有 110 条）
            if (source.loginUrl.isNotBlank()) add(ScriptWarning.NEEDS_LOGIN)
            // 0 = 文本；1 = 图片（漫画）、2 = 音频：本项目是文字阅读器，这两类读不出文字
            if (source.bookSourceType != 0) add(ScriptWarning.NOT_TEXT_SOURCE)
        }
        return ScriptValidation(
            result = if (reasons.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(reasons),
            warnings = warnings,
        )
    }

    /**
     * 地址是否以 `http://` / `https://` 开头——两种出身共用的**同一把尺子**。
     *
     * 抽出来而不是在两处各写一遍：两种格式的 URL 语义完全相同（都是所有请求的拼接起点），
     * 各写一份就会漂移（原生侧后来加了不区分大小写，脚本侧忘了跟，就会出现「同一地址一边能导一边不能导」）。
     */
    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}

/**
 * 脚本书源的导入警示。
 *
 * 与 [ValidationReason] 分列两处：**原因是错误**（这条源导不进去），**警示是知情**
 * （这条源导得进去，但本项目只能给它一部分能力）。混成一个枚举的话，预览层的
 * 「校验不通过」图标与「确认导入 N 条」的计数就会随文案一起摇摆——
 * 警示出现时条目仍然该是绿色通过态，且计入可导入条数。
 */
enum class ScriptWarning {
    /** 原文里有 `<js>` 或 `@js:`：规则内嵌可执行代码（执行环境为零权限隔离进程） */
    HAS_EXECUTABLE_CODE,

    /** `loginUrl` 非空：该源依赖登录流程，v1 不支持 */
    NEEDS_LOGIN,

    /** `bookSourceType` 非 0：漫画/音频类源，本项目为文字阅读器 */
    NOT_TEXT_SOURCE,
}

/**
 * 脚本书源的校验结论 = 一条 [ValidationResult]（能不能导）+ 若干 [ScriptWarning]（要让用户知道什么）。
 *
 * 有效性只放**一个** [ValidationResult]，不再另存 `isValid` 与 `reasons` 两个字段：
 * 那两个都是它的派生值，各存一份就可能互相矛盾（`isValid = true` 而 `reasons` 非空）。
 * 派生属性在这里够用，且让调用方（`ImportPreviewItem`、预览弹层）对两种出身写同一套判断。
 */
data class ScriptValidation(
    val result: ValidationResult,
    val warnings: List<ScriptWarning>,
) {
    /** 与原生侧同一个判据：只有 [ValidationResult.Valid] 才进确认导入的计数 */
    val isValid: Boolean get() = result is ValidationResult.Valid

    /** 失败原因（通过时为空表），顺序与 [BookSourceValidator.validateScript] 的判定顺序一致 */
    val reasons: List<ValidationReason>
        get() = (result as? ValidationResult.Invalid)?.reasons.orEmpty()
}

/**
 * 一条书源规则的校验结论。
 *
 * 用 sealed 而不是 `List<ValidationReason>`（空表即通过）：调用方写 `is Valid` 比写
 * `isEmpty()` 更贴合「能不能导入」这个判断，也不会把「通过」和「还没校验」混成同一个值。
 */
sealed interface ValidationResult {

    /** 该出身的全部判据都过（原生四条 / 脚本两条），可导入 */
    data object Valid : ValidationResult

    /**
     * 有规则未通过。
     *
     * @param reasons 非空原因列表，顺序与 [BookSourceValidator.validate] /
     *   [BookSourceValidator.validateScript] 的判定顺序一致
     */
    data class Invalid(val reasons: List<ValidationReason>) : ValidationResult
}

/**
 * 校验失败原因。
 *
 * 刻意只放枚举、不放文案：文案属于展示层，归 `res/values/strings.xml`（切语言时随资源走），
 * domain 层出现中文字面量就会与「原因可测、文案可换」这条分界冲突。
 * 枚举 → 资源 ID 的映射见 `module_me` 的 `view/BookSourceManageActivity`（`validationReasonRes`）。
 *
 * 两种出身**共用这一套词汇**（脚本书源只用到其中的 NAME_BLANK / URL_NOT_HTTP / SCRIPT_UNPARSABLE）：
 * 另起一套「脚本专属原因」枚举会让预览层为同一行渲染写两条分支，
 * 用户也会看到两种长得不一样的报错行。
 */
enum class ValidationReason {
    /** `name` 为空白 */
    NAME_BLANK,

    /** `url` 未以 `http://` 或 `https://` 开头 */
    URL_NOT_HTTP,

    /** `searchUrl` 与 `ruleFind.url` 同时为空白 */
    NO_ENTRY,

    /** `ruleSearch.list` 或 `ruleContent.content` 为空白 */
    NO_PARSE_RULE,

    /**
     * 脚本书源的原文连最小模型都解不出来（顶层键装着读不出值的值：`bookSourceType` 装了非数值、
     * 或声明过的键写着 `null`）。
     *
     * 不是 [BookSourceValidator.validate] / [BookSourceValidator.validateScript] 判出来的，
     * 而是导入预览在解码那一步直接给出的：解码器与 `BookSourceManager.addScriptSource`
     * 共用同一实例，所以「预览判解不出」与「落库判存不下」是同一个接受集——
     * 预览标为不通过的条目，确认导入时也确实进不去，反之亦然。
     */
    SCRIPT_UNPARSABLE,
}
