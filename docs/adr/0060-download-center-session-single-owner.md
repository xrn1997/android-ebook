# 下载中心的焦点章派生与刷新编排收进 DownloadCenterSession 单主

Status: accepted

下载中心「哪一章在跑 / 服务是否在下载 / 结局该刷哪一面 / 确认下载的暂停与排队乐观回显」原先分散在 ViewModel 的两个裸字段、`loadGroups`/`loadSelection` 两处各自的重算，以及 `DownloadCenterScreen` 的 `LaunchedEffect` 里。决定把这三件事收进 `module_book/download` 的纯 JVM 会话 `DownloadCenterSession`：宿主只交入服务结局与任务表快照、订阅会话结论并据此接线——与 [ADR-0057](0057-page-session-pagination-single-owner.md) 的分页会话、[ADR-0048](0048-single-owner-derivation-extraction.md) 的派生口径单主同族。

## 背景

- **焦点章两处各算**：一级分组在 `loadGroups` 里判 `isDownloading && tasks.any { durChapterUrl == activeChapterUrl }` 再 `tasks.first { … }` 取那一行；二级选章在 `loadSelection` 里独立地 `if (isDownloading) tasks.firstOrNull { … }?.durChapterIndex`。两处读同一对 ViewModel 裸字段（`activeChapterUrl`、`isDownloading`），`loadSelection` 的注释自陈「与一级卡片同口径」——口径靠注释与记性对齐，改一处必须记得改另一处，分叉时两级显示的「正在下载第 M 章」会静默不一致（不闪退、不编译失败）。
- **结局→该刷哪面的编排住在 Composable**：`DownloadCenterScreen` 的 `LaunchedEffect` 用 `when (DownloadState)` 决定 Progress / Paused / Finished 各触发哪几面刷新；这个 `when` 既是页面路由、又紧耦合于 `onDownloadState` 里推进的 `isDownloading`——路由与状态推进分居两层，新增终态要同时改 Composable 与 ViewModel。
- **乐观回显是第三处派生**：`confirmDownload` 在页面状态上直接做 `paused = false` + 并入新排队索引，好让二级当场显示「继续下载」与「排队中」，不必等下一次 Progress 事件驱动的重刷——「哪些展示字段即时翻转」这条规则散在确认动作里。

## 决定

1. **焦点章只由 `DownloadCenterSession.activeChapter(tasks)` 一处算出**：它读会话内部的 `isDownloading` 门与 `activeChapterUrl`，返回这本书当前正在下载的那一行（不在下载、或活跃章不属于这本书时返回 null）。一级卡片拿这行实体展示，二级拿它的 `durChapterIndex` 打徽章——两级读同一方法，`loadSelection` 那句「与一级卡片同口径」的注释连同病根一起消失。
2. **服务结局→下载态推进 + 刷新路由收进 `onDownloadState(state)`**：会话先据结局置 `isDownloading`（仅 Progress 为真）、落 `activeChapterUrl`，再返回「该刷新哪几面」的 `Set<DownloadRefreshTarget>`（Groups / Selection）。`when (DownloadState)` 的编排从 `LaunchedEffect` 移回 ViewModel：`DownloadManageViewModel.onDownloadState` 交结局给会话、按返回的路由调 `loadGroups`/`refreshSelection`，Composable 只剩 `collect(viewModel::onDownloadState)`。终态路由若有差异，只改会话这一处。
3. **乐观回显收进 `optimisticEnqueue(selection, queuedIndices)`**：唯一一处定义「确认下载后 `paused` 收起、`queuedIndices` 并入新章」；它只翻展示字段，不碰章节/缓存等装载事实（那些仍在下一次 `loadSelection` 回库取），与下发顺序（先解暂停再拉服务）无关。
4. **会话纯 JVM、无 Context**：构造无参、入参与返回都是 `DownloadState` / `DownloadChapterEntity` / `BookChapterSelection` 这些既有数据形状，可在 `testDebugUnitTest` 下直接锁——与 `PageSession` 同一形态。

## 边界

- **某本书算不算暂停的真值源仍是 `paused_book` 表**（经 `DownloadBatch`，页面直读 `DownloadRepository.getPausedBooks`）。会话不回查库，只负责「暂停书不得同时显示为活跃章」这条呈现层不变量，它由 `activeChapter` 里的 `isDownloading` 门兑现（暂停/收尾时收起，队列里的章不冒充「正在下载」）。
- **下载队列语义、服务生命周期、批次循环与出队不在本篇**：`DownloadBatch` / `DownloadRunner` / `DownloadBatchLoop` 的归属不变；下载仍一律经 `DownloadService.start(context, intent)`、先入库再拉服务（ADR-0018/0035/0036/0049）。
- **选章计划仍是 `ChapterSelectionPlan` 的地盘**：预勾选、任务构造、跳过数与书头三态不因本篇改动（ADR-0048）；会话只在计划之上做「确认后即时显示什么」的乐观层。
- **C7 的文案/`getString` 归位不在本篇**：本页只搬派生与编排，不动 Composable 里的文案放置。

## 后果

- `DownloadManageViewModel` 删除 `activeChapterUrl` / `isDownloading` 两个裸字段，改持一个 `DownloadCenterSession`；`loadGroups`、`loadSelection`、`confirmDownload` 改读会话结论。
- `DownloadManageActivity` 的 `DownloadCenterScreen` 里 `LaunchedEffect` 的 `when (DownloadState)` 编排删除，改为把结局直接交给 `viewModel::onDownloadState`。
- 新增 `DownloadCenterSessionTest`（4 例，纯 JVM）：同一任务表下一级与二级读到同一焦点章、书在架但零任务不给焦点、暂停/完成都刷新两面并收起焦点章、乐观写入即时收起暂停并并入排队位置。

## 被否方案

- **留在 ViewModel，只把两个裸字段并进 Screen**：被否。焦点章的两处重算与「与一级卡片同口径」的注释依赖原样保留，分叉风险不变；且路由仍散在 Composable。
- **复用 `PageSession` 或抽一个通用「派生会话」基类**：被否。分页游标/闸门与下载焦点章/暂停回显是两个不相关领域的时序，硬凑共同抽象会把两边的判据搅成一个含糊的泛型壳，收益不及成本。
- **把会话做成可注入、由 Hilt 提供**：不必要。会话无外部协作者（不像 `PageSession` 也只吃构造参数），宿主自带一个即可，注入只增加装配面而不增加可测性。

锚点：`module_book` 的 `DownloadCenterSession`（焦点章 / 刷新路由 / 乐观回显的唯一主人）、`DownloadRefreshTarget`（路由输出枚举）、`DownloadCenterSessionTest`（4 例，锁「一级与二级同读 activeChapter」与路由）；消费方 `DownloadManageViewModel.loadGroups`/`loadSelection`/`onDownloadState`/`confirmDownload`、`DownloadCenterScreen` 的 `LaunchedEffect`；同族先例 [ADR-0057](0057-page-session-pagination-single-owner.md)（分页会话）、[ADR-0048](0048-single-owner-derivation-extraction.md)（派生口径单主）。
