package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceFormat

/**
 * 书源管理页的一条清单条目 = 解析规则 + 库里的元数据（见 ADR-0016 决策 9）。
 *
 * 存在理由已变为承载**管理面专属的 [format] 出身位**：解析链路只关心规则，只有管理页要按出身
 * 决定渲染方式与导出入口，而这个事实只住在 `book_source` 表的 `format` 列里，
 * 规则对象 [BookSourceRule] 没有、也不该有它。
 *
 * 为什么不直接把 `BookSourceEntity` 递出去、也不把 [format] 塞进 [BookSourceRule]：
 * - 递实体会让 UI 依赖数据库行（`lib_ebook_db` 的实体不该越过 [BookSourceManager] 出现在 Compose 里），
 *   届时列名调整、schema 迁移都会直接打在页面上；
 * - 塞进规则则让「导入一份社区 JSON」多出一个与规则内容无关的本机列状态，而且规则还要在社区之间交换，
 *   带一个本机装机才有的字段会让「导出再导入」的往返语义变脏（交换的是规则，不是这台机器上的行状态）。
 *
 * 因此这条元数据只在**面向用户的管理面**（[BookSourceManager.observeSources]）出现，
 * 解析链路拿到的仍是纯规则。
 */
data class BookSourceItem(
    /**
     * 展示用的规则。
     *
     * - **原生行**：解码 `rule_json` 并用实体列（`enabled`/`weight`/`group`）覆盖过的规则，
     *   与挂起读面给的是同一形态；
     * - **脚本行**（[format] 非 [SourceFormat.NATIVE]）：只由实体列合成的**展示用空壳**
     *   （名字/地址/启用态/权重/分组，其余字段吃默认值），`rule_json` 从不参与解码。
     *   社区 JSON 里有若干与本类型**同名但形状不同**的键，硬解会抛异常而不是解出空壳，
     *   所以脚本行走的是「不尝试解码」这条路——抛异常若按脏数据处置会让整行从管理页消失，
     *   用户既看不到也删不掉自己导入的源（判据见 `BookSourceManagerImpl.toItem`）。
     */
    val rule: BookSourceRule,

    /**
     * 这条书源的格式出身（`book_source.format` 列）：原生规则源还是脚本书源。
     *
     * 这个事实只住在列里，规则对象里没有也不该有。它是**管理面专属**的标记：
     * 页面靠它给脚本书源打「脚本」出身标、并决定要不要给导出入口（脚本书源的导出要等解释器落地）。
     *
     * 为什么必须随条目一起递出去：脚本书源的行在这条面上**是可见的**（用户得知道他导入过什么、得能删），
     * 而它的 [rule] 只是按实体列合成的展示用空壳（规则字段一条都没有）。没有这一位，页面就会把一条
     * 空规则当成「一个正常的原生源」渲染——那句「先看 format 再决定画什么」因此是硬要求，不是建议。
     *
     * 默认值为 [SourceFormat.NATIVE]：既有构造点（含测试假件）清一色是原生源，带默认值就不必逐处改。
     */
    val format: SourceFormat = SourceFormat.NATIVE,
)
