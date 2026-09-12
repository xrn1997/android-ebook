package com.ebook.me.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ScriptSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.api.entity.SourceFormatDetector
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.SourceStorageJson
import com.ebook.common.util.reportFailure
import com.ebook.me.domain.BookSourceValidator
import com.ebook.me.domain.ScriptWarning
import com.ebook.me.domain.ValidationReason
import com.ebook.me.domain.ValidationResult
import com.xrn1997.common.mvvm.model.NoOpModel
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

/**
 * 书源管理页 ViewModel（ADR-0016 第 9 条 / 路线图 P2）。
 *
 * 职责边界：本页是 `book_source` 表唯一的用户可写面，因此**一切读写都经
 * [BookSourceManager]**，不直调 DAO——规则整块存在 `rule_json` 列里（脚本书源那一列存的是
 * 社区原文，见 [BookSourceManager.addScriptSource]），绕开这层拿到的是
 * 没经实体列（`enabled`/`weight`/`group`）覆盖的脏规则。Manager 的接口契约里有几条
 * 直接决定了本页的写法，逐条对应到下面的方法：
 * - [BookSourceManager.addSource] 同 URL 即整行覆盖——所以「重复导入更新规则」是正常用法，
 *   预览层只把它标成「将覆盖」而不拦；
 * - [BookSourceManager.observeSources] 两种出身**都**放进行（用户得看得见、删得掉自己导进来的脚本源），
 *   但脚本行的 `rule` 只是按实体列合成的展示用空壳。所以本页凡是要「拿 rule 当一条可用规则」
 *   的渲染都得先看 [BookSourceItem.format]：出身标记、导出入口的取舍都由此而来
 *   （[exportSourceJson] 因此也带这一道守卫）；
 * - 「这条 URL 库里有没有、是什么出身」一律问 [BookSourceManager.getFormatByUrl]——
 *   `getSourceByUrl` 那类规则类型化读面看不见脚本行，用它们判覆盖会把「覆盖一条脚本源」说成「新增」；
 * - [BookSourceManager.removeSource] **任何一行都可删**（应用不随包携带书源，`book_source` 表里
 *   每一行都是用户导入的），删不掉的唯一成因是这行本就不存在，本页把它翻成
 *   [Notice.DeleteMissing]，分流判据是「清单里还有没有这一行」而**不是** failure 的消息措辞
 *   （理由见 [removeSource]）；
 * - [BookSourceManager.setDefaultSource] **会顺带启用目标源**，所以「设为默认」不需要
 *   先拨开关，两步走反而会把「点了没换」的现象暴露给用户。
 *
 * 继承 [BaseViewModel] 而非裸 `ViewModel`（AGENTS.md 的 VM 基类约定）：本页没有 Model 门面
 * （依赖直接注入 Manager），故用 [NoOpModel] 占位。状态流命名避开基类的 `uiState`
 * （那个只驱加载/错误覆盖层）。
 *
 * **一次性提示为什么不塞进状态流的字符串字段、也不直接 sendToast**：
 * [Notice] 只带语义与数字，文案留给 UI 层经字符串资源解析——中文文案集中在 `strings.xml`
 * 是 domain/VM 层的既有分界（[ValidationResult] 同理）。而它又必须可断言：`sendToast` 走的
 * 命令通道在 lib_common 里是 internal，测试观测不到（`SettingViewModelTest` 已记过这条），
 * 只记日志的失败会「页面没反应、测试也看不出」。故提示进状态流、由 UI 消费后
 * [consumeNotice] 清零；**写库异常这类没有专属文案的失败仍走共享的
 * [reportFailure]**（会话过期只记日志、不重复提示那条不变量由它收口）。
 */
@HiltViewModel
class BookSourceViewModel @Inject constructor(
    private val bookSourceManager: BookSourceManager,
) : BaseViewModel<NoOpModel>(NoOpModel()) {

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
     * 字段留默认值是为了让两处构造点不必写满八个参数，[confirmImport] 里仍对「格式与载荷不符」
     * 留了一条 failure 兜底——真出现了就是构造点写错，宁可计入失败条数也不静默丢条目。
     *
     * [willOverwrite] 与 [overwriteFormat] 在解析时就按库里的当前状态算好（而不是弹层渲染时现算）：
     * 用户确认前若清单已变，落库走的仍是 REPLACE 语义，语义上没错，
     * 但预览上「将覆盖」必须与用户看到的那一份清单同源。
     *
     * @param scriptRawJson 脚本项的原始 JSON 整块，落库时**原样**交给
     *   [BookSourceManager.addScriptSource]（零翻译是脚本书源的存储契约，见 ADR-0029 决策 1）；
     *   原生项为空串（那条路径落库交 [BookSourceManager.addSource]，规则对象本身就是载荷）
     * @param scriptWarnings 脚本项的三项知情警示（原生项恒为空表）。警示**不影响** [isValid]
     * @param overwriteFormat 库里同 URL 那一行的出身，该行不存在时为 null。与 [format] 不同时，
     *   「将覆盖」说的其实是**把另一种出身的源换掉**（原生规则源被脚本导入顶掉、或反过来），
     *   这件事对用户是可惊讶的，故预览文案要把它说出来
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
        /** 只有校验通过的条目会被 [confirmImport] 落库；警示不算不通过 */
        val isValid: Boolean get() = validation is ValidationResult.Valid

        /**
         * 展示名：按 [format] 只读自己那一侧的键，UI 不必先判格式再决定读哪个字段。
         *
         * 写成 `when (format)` 而不是旧的 `nativeRule?.name ?: scriptRule?...`：跨格式的 `?:` 链
         * 会让「出身与载荷不一致」的条目借用另一格式的字段渲染出来——构造点写错（脚本项忘了填
         * scriptRule、或 format 标错）本是一条该暴露出来的路由错误，却表现为「名字照样显示、
         * 一切正常」，永远没人发现。按 format 分支后这种条目只会显示为空名，
         * 预览层于是回落到「未命名」这一眼可见的异常态。
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

    /** 页面一次性提示（只带语义与参数，文案由 UI 层经字符串资源解析） */
    sealed interface Notice {
        /** 整段文本读不出任何书源对象（不是 JSON，或数组里没有对象） */
        data object ImportUnreadable : Notice

        /** 要删的行已不在库里（多为另一处界面刚删过） */
        data object DeleteMissing : Notice

        /**
         * 导入结果统计。
         *
         * @param success 成功落库条数（**含**其中属于覆盖的条目）
         * @param overwritten 成功条目里命中已存在 URL 的条数，是 [success] 的子集
         * @param failed 没进库的条数：校验未通过 + 写库失败（后者另有 [reportFailure] 的具体原因）
         */
        data class Imported(val success: Int, val overwritten: Int, val failed: Int) : Notice
    }

    /**
     * 页面状态：清单 + 默认源 + 导入预览 + 一次性提示。
     *
     * 由 [BookSourceManager.observeSources]（含禁用项，排序 `weight ASC, addedAt ASC, url ASC`）
     * 与 [BookSourceManager.observeDefaultSource]（永不为禁用态；全部禁用时为 null）合并而来，
     * UI 只订阅这一条流，不在 Composable 里散落多条 collect 与回落判断。
     *
     * 清单项用 [BookSourceItem] 而不是裸规则：[BookSourceItem.format] 决定「脚本」出身标记与
     * 导出入口的取舍，而这个事实只在 `book_source` 表的 `format` 列里、规则对象没有。
     * `book_source` 表里每一行都是用户导入的，删除无保护——[removeSource] 的分流只需判
     * 「清单里还有没有这一行」。
     */
    data class BookSourcePageState(
        val sources: List<BookSourceItem> = emptyList(),
        val defaultSourceUrl: String? = null,
        val preview: List<ImportPreviewItem>? = null,
        val notice: Notice? = null,
    ) {
        /**
         * 「共 X 个书源，已启用 Y 个」里的 Y。
         *
         * 两个数**都把脚本书源算进去**：它们是清单里真实存在的行（看得到、能开关），
         * 汇总行把它们排除在外就会与用户眼前的行数对不上。
         * 2e 起书城切换器对脚本行放开，Y 与书城可切数重新一致；
         * 「启用」与「默认源资格」的口径都来自条目面，不再有「看得到切不到」的差集。
         */
        val enabledCount: Int get() = sources.count { it.rule.enabled }
    }

    private val _preview = MutableStateFlow<List<ImportPreviewItem>?>(null)
    private val _notice = MutableStateFlow<Notice?>(null)

    val bookSourceState: StateFlow<BookSourcePageState> = combine(
        bookSourceManager.observeSources(),
        bookSourceManager.observeDefaultSource(),
        _preview,
        _notice,
    ) { sources, defaultSource, preview, notice ->
        BookSourcePageState(
            sources = sources,
            defaultSourceUrl = defaultSource?.sourceUrl,
            preview = preview,
            notice = notice,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eagerly 而不是 MePageViewModel 的 WhileSubscribed(5s)：Manager 的两个流都是**冷流**
        // （收集时才查 Room、不做共享），Eagerly 让这份查询在 VM 构造时就开始，
        // 页面首帧组合完即可读到已合并的清单；WhileSubscribed 下无订阅者时不发射，
        // 只会把同一个查询推到首帧之后。
        started = SharingStarted.Eagerly,
        initialValue = BookSourcePageState(),
    )

    /** 提示已被 UI 取走并展示：清零，避免页面重组时重复弹同一条 */
    fun consumeNotice() {
        _notice.value = null
    }

    /** 收起导入预览弹层：用户取消预览时由页面调用（[confirmImport] 收尾时也会自己收起） */
    fun dismissPreview() {
        _preview.value = null
    }

    /**
     * 设为默认源。
     *
     * Manager 对库里不存在的 URL 只记日志、静默忽略；本页清单本就来自同一个 Room，
     * 传进不存在的 URL 只可能是竞态，不必额外提示用户。禁用源会被顺带启用（见类注释）。
     */
    fun setDefaultSource(url: String) {
        viewModelScope.launch { bookSourceManager.setDefaultSource(url) }
    }

    /**
     * 启用/禁用某个书源。
     *
     * 不判失败：[BookSourceManager.setEnabled] 自身吞异常并记日志，清单由
     * [BookSourceManager.observeSources] 驱动——真没写进去时开关会保持原状，
     * 比「弹一条与界面变化对不上的错误提示」更准。
     */
    fun setEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch { bookSourceManager.setEnabled(url, enabled) }
    }

    /**
     * 删除书源；唯一可预期的人话失败是「这一行已不在库里」，翻成 [Notice.DeleteMissing]。
     *
     * 表里每一行都删得掉（应用不随包携带书源），故失败只可能是**这行本就不存在**或写库异常：
     * 前者翻成 [Notice.DeleteMissing]，后者交回 [reportFailure] 的共享口径。
     *
     * 分流判据是**清单里还有没有这一行**（清单与库经同一条 [BookSourceManager.observeSources] 流
     * 而来），而不是 [BookSourceManager.removeSource] 的失败消息措辞：接口返回的 [Result] 没有
     * 错误码，按关键字分流等于把另一模块的文案措辞变成隐式契约——措辞一改就静默落到
     * [reportFailure]（只记日志），现象是「点了删除，界面不提示也没反应」。而分流所需的事实
     * 本页自己就有。
     */
    fun removeSource(url: String) {
        viewModelScope.launch {
            bookSourceManager.removeSource(url).onFailure { e ->
                val rowStillListed = bookSourceState.value.sources.any { it.rule.url == url }
                if (rowStillListed) reportFailure(e) else _notice.value = Notice.DeleteMissing
            }
        }
    }

    /**
     * 解析导入文本，产出预览清单并置入状态流；**只读不落库**（落库要用户点「确认导入」）。
     *
     * 两种形态自适应：社区书源 JSON 既有一整包数组（`[{...}, {...}]`）也有单条对象（`{...}`）。
     * **实现按首字符分派**（`[` 走数组、`{` 走单条），语义就是「先试数组、退到单条」；
     * 不写成「数组抛异常再解一次单条」：包里混进一个坏元素时，退路会把整包再当单条解一遍，
     * 用户于是看到第二条莫名其妙的错误，而首字符分派只失败一次、结论只有一个。
     *
     * 切出顶层对象后**每个对象再按格式分流**（[SourceFormatDetector] 按键集判别，见 [toPreviewItem]）：
     * 原生走既有的 [BookSourceManager.importFromJson] + 四条结构校验，脚本走 [SourceStorageJson] +
     * [BookSourceValidator.validateScript]。混排的包（原生与脚本各半）因此逐条各走各路，
     * 坏条目也只是标为不通过，不会带崩整包。
     *
     * **为什么用 `org.json` 拆条目、而不是 kotlinx-serialization**：本模块编译类路径上只有
     * `kotlinx-serialization-core`（`Json` 所属的 `-json` 制品在 `lib_ebook_api` 里是
     * `implementation` 依赖，不随 `api` 传播），本阶段不新增依赖；而书源规则的**解码面本来就
     * 收在** [BookSourceManager.importFromJson]（接口注释即契约），再造一个规则解码器反而会出现
     * 两套解码行为。`org.json` 是 Android SDK 自带，这里只用它做「把一段 JSON 文本切成顶层对象」
     * 这一件与规则无关的事。**脚本书源同理不自建解码器**：它走 `lib_book_common` 的
     * [SourceStorageJson]，那与 [BookSourceManager.addScriptSource] 用的是同一个解码器。
     *
     * **为什么「将覆盖」判定不再预取一份清单**（旧写法：`getAllSources()` 取 URL 集合）：
     * 那个读面**只含原生规则书源**（脚本书源被格式挡下，见其接口 KDoc），拿它判覆盖会把
     * 「用一条脚本源覆盖已有的原生源」误报成新增。现在逐条问 [BookSourceManager.getFormatByUrl]
     * ——接口正是为这一处留的（「导入预览的『将覆盖』判定与格式显示经此查询」），
     * 它是唯一对两种出身平等可见的按 URL 读面，还顺带带回被覆盖那行的出身
     * （[ImportPreviewItem.overwriteFormat]）。代价是一包 N 条就问 N 次，
     * 但都是主键单行查、导入又是一次性动作，换掉「两个面各持一份口径」这笔账划得来。
     *
     * @return 预览项列表；空表表示整段文本读不出任何书源对象，此时不置预览、只发
     *   [Notice.ImportUnreadable]（调用方可直接把返回值渲染，无需再判状态流）
     */
    suspend fun parseImportJson(jsonStr: String): List<ImportPreviewItem> {
        val items = splitJsonObjects(jsonStr).map { toPreviewItem(it) }
        _preview.value = items.ifEmpty { null }
        if (items.isEmpty()) _notice.value = Notice.ImportUnreadable
        return items
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
    } catch (e: JSONException) {
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
     * 解不出规则（[BookSourceManager.importFromJson] 返回 null）的唯一成因是 `name` 或 `url`
     * 为空，这两个字段正是这里能从 `org.json` 直接读到的，于是用它们拼一条**退化规则**去校验：
     * 预览层仍能指出「名称为空 / 地址不是 http 开头」，而不会把整条源静默丢掉。
     * 退化规则的其余规则字段为空，会一并报「缺入口 URL / 缺解析规则」——这条源本来也导不进来，
     * 多报的原因不算误导。
     */
    private suspend fun toNativePreviewItem(obj: JSONObject): ImportPreviewItem {
        val rule = bookSourceManager.importFromJson(obj.toString())
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
     * [BookSourceManager.addScriptSource] 同一个解码器，于是预览标「可导入」的条目
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
     * 走 [BookSourceManager.getFormatByUrl] 而不是 `getSourceByUrl`：后者只对原生行有值，
     * 脚本行会被它当成「库里不存在」，覆盖判定与出身文案就都错了。
     * 空白 URL 不必去问——主键为空不可能有行，问了也是白问。
     */
    private suspend fun formatOf(url: String): SourceFormat? =
        url.takeIf { it.isNotBlank() }?.let { bookSourceManager.getFormatByUrl(it) }

    /**
     * 确认导入：只把校验通过的条目落库，统计结果经 [Notice.Imported] 反馈。
     *
     * 逐条写（不是批量接口）：Manager 的写面只有单条 [BookSourceManager.addSource] /
     * [BookSourceManager.addScriptSource]，且它们的失败是**逐条**语义（一条 URL 空白不该让整包回滚）。
     * 两条写路径按 [ImportPreviewItem.format] 分流，**脚本书源交出的必须是预览看到的那份原文**
     * （[ImportPreviewItem.scriptRawJson] 整块）：零翻译是脚本书源的存储契约（ADR-0029 决策 1），
     * 落库前若经模型转一道，规则块与脚本就全丢了，「重导即升级」也跟着失效。
     * 统计口径见 [Notice.Imported]：`success` 含覆盖，`failed` = 校验未通过 + 写库失败。
     * 收尾必定清空预览并弹统计，中途某条写失败不提前退出——用户看到的清单是一整包，
     * 只告诉他「这一包导入的结果」比「第一笔失败就不管了」更贴合预期。
     */
    fun confirmImport(items: List<ImportPreviewItem>) {
        viewModelScope.launch {
            val valid = items.filter { it.isValid }
            var success = 0
            var overwritten = 0
            var writeFailed = 0
            for (item in valid) {
                val result = when (item.format) {
                    SourceFormat.SCRIPT -> bookSourceManager.addScriptSource(item.scriptRawJson)
                    SourceFormat.NATIVE -> {
                        val rule = item.nativeRule
                        // 走到这里 rule 为 null 只可能是构造点写错（预览项由本类自己造的）：
                        // 宁可计入失败条数并上报，也不静默丢一条——静默会让「成功 N 条」这个数字说谎
                        if (rule == null) {
                            Result.failure(IllegalStateException("原生出身的预览项缺少规则，无法落库"))
                        } else {
                            bookSourceManager.addSource(rule)
                        }
                    }
                }
                result
                    .onSuccess {
                        success++
                        if (item.willOverwrite) overwritten++
                    }
                    .onFailure { e ->
                        writeFailed++
                        reportFailure(e)
                    }
            }
            _preview.value = null
            _notice.value = Notice.Imported(
                success = success,
                overwritten = overwritten,
                failed = items.size - valid.size + writeFailed,
            )
        }
    }

    /**
     * 全部书源（含禁用项）导成 JSON 数组文本。
     *
     * 逐条走 [BookSourceManager.exportToJson]（美化输出，社区交换要能看懂能手改）再拼数组壳：
     * 编码面只有 Manager 一份，规则字段日后扩展时导出格式自动跟手，本类不会出现第二套字段清单。
     * 禁用项**一并导出**（用户导出的语义是「把我这套书源备份/分享出去」，禁用只是本地开关状态，
     * `enabled` 字段本身就在规则里，对面导入后仍是禁用态）。
     * 写文件由 UI 层负责（SAF 输出的 `contentResolver` 在页面上）。
     *
     * **脚本书源不在导出范围内**：数据源是 [BookSourceManager.getAllSources]，
     * 那个读面只含原生规则书源（脚本行被格式挡下），所以这里天然不会导出它们——
     * 不是漏了，而是脚本源的导出要连原文与格式声明一起做，那件事随 Plan 2 的解释器一起补
     * （行内导出入口同理，见 [exportSourceJson]）。副作用是「导出全部」这个按钮对
     * 「只导过脚本源」的清单会回空串，UI 那句「没有可导出的书源」因此是对的文案。
     *
     * @return 数组文本；库内没有任何书源时返回**空串**（不是 `[]`——`joinToString` 的 prefix
     *   对空集合也会追加，留个 `[]` 给 UI 判就得改成判字面量，不如用「空串 = 没内容」这条直白契约），
     *   UI 据此提示「没有可导出的书源」
     */
    suspend fun exportAll(): String {
        val rules = bookSourceManager.getAllSources()
        if (rules.isEmpty()) return ""
        return rules.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") {
            bookSourceManager.exportToJson(it)
        }
    }

    /**
     * 单个书源导出（行内操作层「导出该源」用），写文件同样归 UI 层。
     *
     * **脚本书源直接拒绝并返回 null**：页面已经不给它渲染导出入口，
     * 但只靠「不画」这一道不够——[BookSourceItem.rule] 对脚本行只是按实体列合成的展示用空壳
     * （没有任何选择器），换个入口走到这里就会导出一份空规则，用户拿到手的是一份**看着成功、
     * 实则什么都解析不出来**的文件，比报错更难查。故这里按出身再判一次并记日志：
     * 两条防线同源（都读 `format`），不存在「界面放过去了、库里还能导」的口径差。
     * 脚本源的导出（原样吐出 `rule_json`）随 Plan 2 的解释器一起补。
     *
     * @return 美化 JSON；[item] 是脚本书源时为 null（调用方据此不发起写文件）
     */
    fun exportSourceJson(item: BookSourceItem): String? {
        if (item.format != SourceFormat.NATIVE) {
            Logger.w(
                TAG,
                "拒绝导出脚本书源（导出入口本已隐藏，走到这里说明有别的调用方）：${item.rule.url}",
            )
            return null
        }
        return bookSourceManager.exportToJson(item.rule)
    }

    private companion object {
        /** 日志标签：与类名同字（本模块 `ReleaseRepository` 等处的既有口径） */
        const val TAG = "BookSourceViewModel"
    }
}
