package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule

/**
 * 管理写面：书源的增删、启停与设默认。
 *
 * **REPLACE 是刻意语义**：主键 `url` 命中即整行替换，社区书源 JSON 通常整包重发，用户重导即更新规则。
 * 整行替换意味着未回填的列会吃实体默认值，所以两条 REPLACE 写路径（[addSource]/[addScriptSource]）
 * 都要带回旧行的 `addedAt`；[addScriptSource] 还保留旧行的 `weight`/`group`（[addSource] 的
 * `weight`/`group` 取入参）。其余写路径不经 REPLACE——
 * [setEnabled] 只改 `enabled` 单列，[setDefaultSource] 写 SP 与订阅面（必要时补一次 `enabled`
 * 单列 UPDATE），都不会冲掉这些列。落库成功后必须失效该 URL 的 parser 缓存，
 * 否则 LRU 就成了第二个事实源。
 *
 * **默认源没有内存快照**：它每次由 Room 现算（见 [SourceCatalog.observeDefaultSource]），写路径只在
 * 必要时把这次现算到的选择固化进 SP 并补一次订阅面推送，不承担「补齐默认源」的职责。
 *
 * 写失败经 [Result.failure] 返回值表达（[setEnabled]/[setDefaultSource] 无返回值、只记日志），
 * 不把异常抛进调用方。[removeSource] 唯一的**业务**失败是「书源不存在」；写库异常同样经
 * [Result.failure] 返回。
 */
interface SourceAdmin {

    /**
     * 新增或覆盖书源（主键 `url` 命中即 REPLACE）。
     *
     * 覆盖是刻意语义：社区书源 JSON 通常整包重发，用户重导即更新规则。
     * 失败（URL 空白、写库异常）经 [Result.failure] 返回，不抛异常。
     *
     * 落库成功后失效该 URL 的 parser 缓存；当前无可用默认源时，新导入的启用源会被提升为默认源。
     */
    suspend fun addSource(rule: BookSourceRule): Result<Unit>

    /**
     * 新增或覆盖脚本书源（主键 `url` 命中即 REPLACE），[rawJson] 原样落 `rule_json`，`format` 落 `script`。
     *
     * 与 [addSource] 同一套不变式：落库成功后失效 parser 缓存、无默认源时提升为默认源。
     * **不做格式内容校验**——校验与警示归导入 UI（是否含可执行代码、依赖登录等），本方法只负责存储。
     * 要在落库前读出顶层字段（预览、警示）请走 [SourceStorageJson.parseOrNull]：它与本方法共用同一个
     * 解码器，于是「预览判解得开」与「这里存得下来」是同一个接受集，不会两边各说一套。
     * [rawJson] 必须含 `bookSourceUrl` 键（判别约定见 [com.ebook.api.entity.SourceFormatDetector]）；
     * 名称与 URL 从 JSON 顶层读取，缺任一即 [Result.failure]。
     *
     * 「无默认源时提升为默认源」这一条对脚本行**同样成立**（2e 起）：默认源候选是条目面的
     * 全部启用行、不再筛格式，库里没有可用默认源时导入一条启用的脚本源会把它立为默认源。
     */
    suspend fun addScriptSource(rawJson: String): Result<Unit>

    /**
     * 删除书源；**任何一行都可删**（应用不随包携带书源，表里每行都是用户导入的），
     * 唯一的**业务**失败是这行本就不存在，由返回的 [Result] 表达；写库异常同样经
     * [Result.failure] 返回。
     *
     * 成功后失效该 URL 的 parser 缓存，删掉的若是当前默认源则回落到下一条启用源。
     */
    suspend fun removeSource(url: String): Result<Unit>

    /**
     * 启用/禁用某书源。
     *
     * 禁用只意味着「不参与聚合搜索、不出现在书城候选」，**不切断已有归属**：
     * 书架里绑着该源的书仍经 [SourceResolver.resolveSource] 解析。两条默认源分支（判据都在写之前求值）：
     * - 禁用分支：**SP 是否命中被禁用的这个 URL**，命中才收敛；
     * - 启用分支：**写之前是否没有可用默认源**（源全被禁用），是才收敛。
     *
     * 收敛本身只是「把这次现算到的选择固化进 SP + 补一次订阅面推送」，不承担「补齐默认源」的职责：
     * 默认源由实现每次从 Room 现算，Room 的失效事件已让订阅面自己给出正确结果，
     * 不存在「启用完仍是无源可用」这种要靠写路径兜的情形。
     *
     * 成功后失效该 URL 的 parser 缓存。
     */
    suspend fun setEnabled(url: String, enabled: Boolean)

    /**
     * 把 [url] 指向的书源设为默认源（写 SharedPreferences + 推订阅面）；库里无该行则忽略。
     *
     * **注意一个不在名字里的副作用：目标源当前是禁用态时，本方法会顺带把它 `enabled = true`。**
     * 理由是「默认源永不为禁用态」这条不变式：只记下 URL 而不启用，回落规则会在下一次读清单时
     * 把它换回第一条启用源，用户看到的现象就是「点了没换」。因此 UI 侧**不必**「先启用、再设默认」
     * 两步走，直接调本方法即可。
     *
     * 成功后失效该 URL 的 parser 缓存。
     */
    suspend fun setDefaultSource(url: String)
}
