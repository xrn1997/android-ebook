package com.ebook.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 一个书源（一本第三方小说站点的解析配置），表 book_source 的一行（见 ADR-0016）。
 *
 * 本表把「书源」从编译期 assets 配置升级成**运行时可管理数据**：用户可导入、启用/禁用、删除，
 * 多个书源共存。业务表（book_shelf / book_info / chapter_list / download_chapter）的 `tag` 列
 * 存的就是这里 [url] 的值（本地书行除外，见 [url] 的例外说明），构成「每本书绑源」的归属关系——
 * 因此 [url] 一旦写入就是外键意义上的稳定标识，改 URL 等于换一个书源（REPLACE 覆盖时 URL 是匹配键，不会变）。
 *
 * 刻意不实现 Parcelable：本行只在「DAO → BookSourceManager → 规则反序列化」这条链上出现，
 * 跨页面传递用的是解析后的 `BookSourceRule`（`lib_ebook_api`），不是数据库行本身。
 */
@Entity(tableName = "book_source")
data class BookSourceEntity(
    /**
     * 书源站点根地址（如 `https://www.bqquge.com`），同时是书架等表 `tag` 列的取值来源。
     *
     * 例外：本地书行的 `tag` 是常量 [BookShelfEntity.LOCAL_TAG]（`"loc_book"`），它不指向任何书源、
     * 也不参与「按书源 URL 找 parser」，本表里永无这一行——按 `tag` 取不到书源不等于书源被删。
     *
     * 一个站点的解析规则只应有一份，天然唯一，故直接做主键（见 ADR-0003 的自然键策略）：
     * 不必额外分配自增 id，「同一站点重复导入即更新规则」这件事由主键 + REPLACE 免费得到。
     */
    @PrimaryKey
    @ColumnInfo(name = "url")
    val url: String,

    /** 展示用书源名（如「笔趣阁」），书源管理页列表标题；与规则内的 `name` 同值，冗余存列只为列表不必先解 JSON */
    @ColumnInfo(name = "name")
    val name: String,

    /**
     * 整段 `BookSourceRule` 的序列化结果（选择器、分页、请求头、替换规则等全在里面）。
     *
     * 存一整块 JSON 而不是把规则拆成列：规则字段还在演进（发现页、排行榜、正文图片选择器等），
     * 拆列意味着每次扩字段都要过一次 schema 迁移，而本表没有任何「按规则内字段过滤」的查询需求
     * （见 ADR-0016 权衡）。代价是 SQL 侧筛不到规则内容，读取一律由 `BookSourceManager`
     * 反序列化成 `BookSourceRule` 后再用。
     */
    @ColumnInfo(name = "rule_json")
    val ruleJson: String,

    /**
     * 是否启用。禁用只意味着「不参与聚合搜索、不出现在书城切换器候选里」，**不切断已有归属关系**：
     * 书架里绑这个源的书仍按 [url] 取规则解析，否则禁用一个源就会让用户书架上的书集体打不开。
     */
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /** 列表排序权重，越小越靠前；与 [addedAt]、[url] 共同构成 DAO 各查询的统一排序口径 */
    @ColumnInfo(name = "weight")
    val weight: Int = 0,

    /**
     * 书源分组（如「小说」「漫画」），来自社区书源 JSON 的 `group` 字段。
     *
     * 列名必须写作 `group_name`：`group` 是 SQL 关键字，拿它当列名会让 Room 生成的建表/查询语句
     * 在 SQLite 侧踩保留字（即使加反引号也与其它列风格不一致），故仅列名换掉、Kotlin 属性保持 `group`
     * 以对上 `BookSourceRule.group`。
     */
    @ColumnInfo(name = "group_name")
    val group: String = "小说",

    /**
     * 书源格式出身（决策见 ADR-0029）：`native` = 原生规则书源（[ruleJson] 是本仓 `BookSourceRule`
     * （`lib_ebook_api`）的序列化）；`script` = 脚本书源（[ruleJson] 是社区通用格式的原始 JSON，
     * 整块存储不翻译）。两种格式共存于同一张表，本列是解析链路的**路由键**。
     *
     * 存枚举名而不是加 TypeConverter：判别列要能在 SQL 侧直接读写与排查，转换器链只是多一层失败面。
     * 落库值一律小写（`native` / `script`，与 ADR-0029 的措辞一致），读取侧经 `lib_ebook_api` 的
     * `SourceFormat.fromRaw` 还原成枚举，未知值按 native 处理——本模块依赖不到那个枚举，
     * 故此处只约定列的取值、不做校验。
     */
    @ColumnInfo(name = "format", defaultValue = "native")
    val format: String = "native",

    /**
     * 入库时刻（毫秒）。两个用途：`weight` 相同时的次级排序键（先导入者靠前），
     * 以及书源管理页的「导入时间」展示。
     *
     * 默认值取当前毫秒，故批量导入整包社区书源时各行很可能同毫秒、次级键分不出先后——列表顺序的稳定性
     * 由 DAO 排序末尾的 `url ASC` 兜底（见 [com.ebook.db.dao.BookSourceDao]）。
     */
    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
)
