package com.ebook.find.booksource

import androidx.lifecycle.viewModelScope
import com.ebook.api.entity.SourceFormat
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.analyze.source.SourceAdmin
import com.ebook.common.analyze.source.SourceCatalog
import com.ebook.common.analyze.source.SourceCodec
import com.ebook.common.util.reportFailure
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
import javax.inject.Inject

/**
 * 书源管理页 ViewModel（ADR-0016 第 9 条 / 路线图 P2）。
 *
 * 职责边界：本页是 `book_source` 表唯一的用户可写面，因此**一切读写都经
 * [SourceCatalog] / [SourceAdmin] / [SourceCodec]**（导入解析与落库再经 [SourceImportPlan]），不直调 DAO——规则整块存在 `rule_json` 列里
 * （脚本书源那一列存的是社区原文，见 [SourceAdmin.addScriptSource]），绕开这组接口拿到的是
 * 没经实体列（`enabled`/`weight`/`group`）覆盖的脏规则。三面的接口契约里有几条
 * 直接决定了本页的写法，逐条对应到下面的方法：
 * - [SourceAdmin.addSource] 同 URL 即整行覆盖——所以「重复导入更新规则」是正常用法，
 *   预览层只把它标成「将覆盖」而不拦；
 * - [SourceCatalog.observeSources] 两种出身**都**放进行（用户得看得见、删得掉自己导进来的脚本源），
 *   但脚本行的 `rule` 只是按实体列合成的展示用空壳。所以本页凡是要「拿 rule 当一条可用规则」
 *   的渲染都得先看 [BookSourceItem.format]：出身标记、导出入口的取舍都由此而来
 *   （[exportSourceJson] 因此也带这一道守卫）；
 * - 「这条 URL 库里有没有、是什么出身」一律问 [SourceCatalog.getFormatByUrl]——
 *   `getAllSources` 那类规则类型化读面看不见脚本行，用它们判覆盖会把「覆盖一条脚本源」说成「新增」；
 * - [SourceAdmin.removeSource] **任何一行都可删**（应用不随包携带书源，`book_source` 表里
 *   每一行都是用户导入的），删不掉的唯一成因是这行本就不存在，本页把它翻成
 *   [Notice.DeleteMissing]，分流判据是「清单里还有没有这一行」而**不是** failure 的消息措辞
 *   （理由见 [removeSource]）；
 * - [SourceAdmin.setDefaultSource] **会顺带启用目标源**，所以「设为默认」不需要
 *   先拨开关，两步走反而会把「点了没换」的现象暴露给用户。
 *
 * 继承 [BaseViewModel] 而非裸 `ViewModel`（docs/architecture.md「Hilt 与 Application」的 VM 基类约定）：本页没有 Model 门面
 * （依赖直接注入 `SourceCatalog` / `SourceAdmin` / `SourceCodec` 三个窄面与导入管线 [SourceImportPlan]），
 * 故用 [NoOpModel] 占位。状态流命名避开基类的 `uiState`（那个只驱加载/错误覆盖层）。
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
    private val sourceCatalog: SourceCatalog,
    private val sourceAdmin: SourceAdmin,
    private val sourceCodec: SourceCodec,
    private val importPlan: SourceImportPlan,
) : BaseViewModel<NoOpModel>(NoOpModel()) {

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
     * 由 [SourceCatalog.observeSources]（含禁用项，排序 `weight ASC, addedAt ASC, url ASC`）
     * 与 [SourceCatalog.observeDefaultSource]（永不为禁用态；全部禁用时为 null）合并而来，
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
        sourceCatalog.observeSources(),
        sourceCatalog.observeDefaultSource(),
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
        // Eagerly 而不是 MePageViewModel 的 WhileSubscribed(5s)：SourceCatalog 的两个流都是**冷流**
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
     * `SourceAdmin.setDefaultSource` 对库里不存在的 URL 只记日志、静默忽略；本页清单本就来自同一个 Room，
     * 传进不存在的 URL 只可能是竞态，不必额外提示用户。禁用源会被顺带启用（见类注释）。
     */
    fun setDefaultSource(url: String) {
        viewModelScope.launch { sourceAdmin.setDefaultSource(url) }
    }

    /**
     * 启用/禁用某个书源。
     *
     * 不判失败：[SourceAdmin.setEnabled] 自身吞异常并记日志，清单由
     * [SourceCatalog.observeSources] 驱动——真没写进去时开关会保持原状，
     * 比「弹一条与界面变化对不上的错误提示」更准。
     */
    fun setEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch { sourceAdmin.setEnabled(url, enabled) }
    }

    /**
     * 删除书源；唯一可预期的人话失败是「这一行已不在库里」，翻成 [Notice.DeleteMissing]。
     *
     * 表里每一行都删得掉（应用不随包携带书源），故失败只可能是**这行本就不存在**或写库异常：
     * 前者翻成 [Notice.DeleteMissing]，后者交回 [reportFailure] 的共享口径。
     *
     * 分流判据是**清单里还有没有这一行**（清单与库经同一条 [SourceCatalog.observeSources] 流
     * 而来），而不是 [SourceAdmin.removeSource] 的失败消息措辞：接口返回的 [Result] 没有
     * 错误码，按关键字分流等于把另一模块的文案措辞变成隐式契约——措辞一改就静默落到
     * [reportFailure]（只记日志），现象是「点了删除，界面不提示也没反应」。而分流所需的事实
     * 本页自己就有。
     */
    fun removeSource(url: String) {
        viewModelScope.launch {
            sourceAdmin.removeSource(url).onFailure { e ->
                val rowStillListed = bookSourceState.value.sources.any { it.rule.url == url }
                if (rowStillListed) reportFailure(e) else _notice.value = Notice.DeleteMissing
            }
        }
    }

    /**
     * 解析导入文本，产出预览清单并置入状态流；**只读不落库**。
     *
     * 解析（切顶层对象、判出身、解码、校验、覆盖判定）全部收在 [SourceImportPlan]；
     * 本类只把结果放进状态流，并在整段读不出内容时发 [Notice.ImportUnreadable]。
     *
     * @return 预览项列表；空表表示整段文本读不出任何书源对象
     */
    suspend fun parseImportJson(jsonStr: String): List<ImportPreviewItem> =
        when (val result = importPlan.parse(jsonStr)) {
            is SourceImportPlan.ParseResult.Parsed -> {
                _preview.value = result.items
                result.items
            }

            SourceImportPlan.ParseResult.Unreadable -> {
                _preview.value = null
                _notice.value = Notice.ImportUnreadable
                emptyList()
            }
        }

    /**
     * 确认导入：只把校验通过的条目落库，统计结果经 [Notice.Imported] 反馈。
     *
     * 逐条写与脚本原文零翻译收在 [SourceImportPlan.commit]；本类负责把其中逐条收集的失败经
     * [reportFailure] 上报、清空预览并转发统计。中途某条写失败不提前退出——用户看到的清单是一整包，
     * 只告诉他「这一包导入的结果」比「第一笔失败就不管了」更贴合预期。
     */
    fun confirmImport(items: List<ImportPreviewItem>) {
        viewModelScope.launch {
            val result = importPlan.commit(items)
            result.failures.forEach { reportFailure(it) }
            _preview.value = null
            _notice.value = Notice.Imported(
                success = result.success,
                overwritten = result.overwritten,
                failed = result.failed,
            )
        }
    }

    /**
     * 全部书源（含禁用项）导成 JSON 数组文本。
     *
     * 逐条走 [SourceCodec.exportToJson]（美化输出，社区交换要能看懂能手改）再拼数组壳：
     * 编码面只有一份，规则字段日后扩展时导出格式自动跟手，本类不会出现第二套字段清单。
     * 禁用项**一并导出**（用户导出的语义是「把我这套书源备份/分享出去」，禁用只是本地开关状态，
     * `enabled` 字段本身就在规则里，对面导入后仍是禁用态）。
     * 写文件由 UI 层负责（SAF 输出的 `contentResolver` 在页面上）。
     *
     * **脚本书源不在导出范围内**：数据源是 [SourceCatalog.getAllSources]，
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
        val rules = sourceCatalog.getAllSources()
        if (rules.isEmpty()) return ""
        return rules.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") {
            sourceCodec.exportToJson(it)
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
        return sourceCodec.exportToJson(item.rule)
    }
}
