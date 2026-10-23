package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceDefinition
import com.ebook.api.entity.SourceFormat
import kotlinx.coroutines.flow.Flow

/**
 * 清单读面：`book_source` 表之上的订阅与挂起读，默认源每次从 Room 现算。
 *
 * **本面不再有同步出口**：应用不随包携带任何书源，启动时没有任何可同步给出的默认源，
 * 清单与默认源一律经 Room 获得（挂起读与 Flow）。默认源走 [observeDefaultSource] 订阅，
 * 别为了「首帧不空白」重新修一条同步出口——同步出口要么靠随包资产、要么靠第二份内存缓存，
 * 两者都是在 Room 之外另造一处事实源；而清单与默认源的真值只有 Room 这一处，只它自己说了算。
 *
 * **读面返回的类型按「谁消费」分工，这个不对称是刻意的**：
 * [getAllSources] 这类**规则类型化读面**只给 [BookSourceRule]，
 * [observeDefaultSource] 给 [SourceDefinition]?（默认源不限于原生规则，理由见下段），
 * 而面向书源管理页的 [observeSources] 给 [BookSourceItem]（规则 + `format`）。
 * 理由一句话：**解析链路只关心规则，只有面向用户的管理页需要出身元数据**——页面要先看 `format`
 * 才知道这一行该渲染成什么（脚本行没有可用规则、要不要给导出入口也看它），而那个事实只住在
 * `book_source.format` 列里，不该为了它污染规则对象（判据与代价见 [BookSourceItem]）。
 * 后来者注意：书城顶部切换器（ADR-0016 P3-c 已落地）订阅清单时取的是 `item.rule`，
 * 并当场把禁用项滤掉——**别把 `format` 继续沿链路往下带**——元数据一旦出管理页就开始
 * 长出第二个用途，到时要改的就是全链路的签名，而不是这里多写的一句话。
 *
 * **规则类型化读面与默认源载体的分工（2e 起）**：[getAllSources] 仍**只认原生规则书源**——
 * 脚本的社区 JSON 硬解成 [BookSourceRule] 有两种结局，都不是「可用规则」（键名对不上解出空规则
 * 静默零条目；同名不同形的键直接抛解码异常），理由见 [BookSourceManagerImpl.toRule]。
 * 但**默认源不再是原生专属**：[observeDefaultSource] 以 [SourceDefinition] 为载体，脚本行同样有
 * 资格被立为默认源；脚本书源仍在 [observeSources]（条目带 `format`）、[SourceResolver.resolveSource]
 * （真解析器）、[getFormatByUrl]（出身查询）与 [SourceResolver.getExploreEntries]（分类条目）四处现身。
 *
 * **关于「有没有可用默认源」只有一个答案**：Room 里没有任何 enabled 行（用户把源全禁用了）时
 * [observeDefaultSource] 发 null。调用方（书城）此时应展示「请先启用或导入书源」的引导态；
 * 聚合搜索（[SourceResolver.searchAcross]）则天然收到零条源，
 * 由页面把「没有可用书源」呈现成空结果而不是错误。
 */
interface SourceCatalog {

    /**
     * 全部书源（含被禁用的），按 `weight ASC, addedAt ASC, url ASC`；读 Room。
     *
     * **空表就是「零书源」**：应用不随包携带任何书源，表里每一行都来自用户导入，所以读到空表
     * 只说明用户还没导入过（或库被外部清空过）。调用方一律按「无可用书源」走引导态即可。
     *
     * **只含原生规则书源**：脚本书源（`format = script`）的行会被跳过（见 [BookSourceManagerImpl.toRule]
     * 的同条说明），库里只有脚本书源时这里就是空清单，不等于「一个源都没有」——要按格式看清单
     * 请走 [observeSources]。
     */
    suspend fun getAllSources(): List<BookSourceRule>

    /**
     * 按已入库书源的 URL 查格式出身；不存在返回 null。
     * 导入预览的「将覆盖」判定与格式显示经此查询，**不要**用规则类型化读面（[getAllSources]，
     * 它只对 native 行有值）。
     *
     * null 因此只说「库里真没有这一行」，导入预览才不会把「将覆盖已有源」误报成「新增」。
     * 本方法读的是 Room 真值。
     */
    suspend fun getFormatByUrl(url: String): SourceFormat?

    /**
     * 订阅书源清单（含禁用），供书源管理页渲染。
     *
     * 与其余读面不同，这里给的是 [BookSourceItem] 而不是裸 [BookSourceRule]：管理页要先看
     * `format` 才知道这一行该渲染成什么（脚本行没有可用规则、要不要给导出入口也看它），
     * 而 [getAllSources] 那类挂起读服务于解析与导入预览，
     * 多带一份元数据只会诱导调用方去读它（不对称的理由见接口顶部说明）。
     *
     * **只有这一面在条目上携带 `format`**（`item.format`）：脚本书源同样经 [getFormatByUrl] 与
     * [observeDefaultSource] 现身，但那两处只回答出身/载体，不给条目。脚本行的 `item.rule` 是按实体列
     * 合成的展示用空壳（只带名称/地址/启用态，`rule_json` 不参与解码），页面必须先看 `format`
     * 再决定渲染什么（出身标记、导出入口的取舍），不要把那条空规则当成可用规则。
     *
     * 冷流：收集时才查 DAO，不做共享。
     */
    fun observeSources(): Flow<List<BookSourceItem>>

    /**
     * 订阅默认书源，供书城渲染；永不为禁用态。
     *
     * **没有任何 enabled 行时（用户全禁用）发 null**。
     * 回落在**两种格式的启用行**里算（2e 起不再筛 format）：SP 命中项（且启用）→ 否则
     * 启用清单第一条——与实现类的 `defaultFromRows` 是同一个函数，两处口径不可能分裂。
     * 调用方（书城）收到 null 应展示「请先启用或导入书源」的引导态，而不是空白页。
     */
    fun observeDefaultSource(): Flow<SourceDefinition?>
}
