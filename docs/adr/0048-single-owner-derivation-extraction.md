# 派生口径单一所有者化：会静默分叉的逻辑抽离宿主

Status: accepted

阅读器位置、聚合搜索轮次、导入扫描与判重处置、下载选章计划这四类派生逻辑原本散在宿主（ViewModel / Compose 页面）里，或一处以上各算、或与 UI 状态机交织。本轮把它们抽成各自唯一的纯逻辑主人，宿主只接线；不抽的代价不是编译错误，而是两处口径分叉后用户看到的数字静默地错。

## 背景

- 阅读位置（当前章、页、在架标记）曾在 `BookReadViewModel`、`ReadBookActivity` 与各控制器之间各持一份副本，翻页、追更、换源、切模式都要多处同步；副本一旦漏同步，用户停在的不是自己读的那一页，而且追更与换源在挂起窗口内的交错没有归属判据。
- 聚合搜索的轮次协议（每源页码、已结束源、去重、软 404 判据、进度）曾长在 `SearchViewModel` 里，晚到的结束信号会当场改写共享状态：某源先结束就可能把别源下一轮的页码改掉，进度行也可能永远差一格不消失。
- 本地导入的扫描资格（TXT/EPUB 门槛、排除目录、取消语义）与判重处置可用性（四种处置哪些可给）由导入 ViewModel 与导入页面各算一遍；页面自算 `canMerge` 与仓库侧判据分叉时，会给用户一个执行不了的选项。
- 下载选章的「将下载 N 章」在选章页用 `selected - queued` 反算、`DownloadManageViewModel` 再按同一条规则构造任务，同一口径两处实现；分叉时页面承诺的章数与实际入库任务数不一致——不闪退，只是静默错。

## 决定

1. **阅读位置只由 `ReaderSession` 持有**：书架快照、实时 `(章, 页)`、派生的标题/章数/滑条值以及在架标记都在会话一处，宿主经 `state` 只读、经显式方法写。位置是会话级事实而不是落库事实（持久化仍只在 `onPause` 走仓库），放会话而不放仓库层，是因为它随打开/换书/换源整段生灭，仓库层只该见到提交后的快照。
2. **聚合搜索轮次协议收进 `AggregationSession`**：关键词、每源游标、已结束源集、按 `noteUrl` 的全局去重、软 404 判据与轮次进度只在会话内算，轮次计划一经返回不再随本轮事件变化（晚到的结束信号只推进自己的游标）。`searchAcross` 保持无状态（ADR-0016 决策 6），会话由宿主持有——它活着是为一次搜索服务的 UI 生命周期，不是仓库级事实。
3. **扫描资格与处置可用性各立单一主人**：`ImportScanner` 收目录遍历与资格判断（纯 JVM，不碰权限/挂载/调度），`DispositionPolicy` 收四种处置的可用集（唯一判据「命中项含本地书才可合并」）。两者是判据不是流程，随导入特性同住（现为 `module_book/importer`）让宿主与测试共享同一份；导入循环、权限与 UI 语义不动。
4. **选章计划一处算**：预勾选窗口、确认任务、跳过数与书头三态动作收进 `ChapterSelectionPlan`，页面与 ViewModel 消费同一份计划对象；队列策略仍归 `DownloadBatch`，计划只描述「这次确认产生什么」，不管入队后怎么跑。
5. **三处捎带行为修正随抽取落地**（都只出现在旧宿主代码做不到、单主之后顺理成章的位置）：
   - 加书架写入 `finalDate = 加入时刻`：旧路径照抄打开时快照，搜索进来的书带着旧值入库、按过期时间排书架序；
   - 会话挂起窗口的回读守卫（`isSameBook`）：查在架、加书架、追更在挂起期间换书时，结果不再记到新书上；
   - 翻页经 `readerState.pageIndex` 发状态：位置单主后 UI 改订阅会话，逐页重组的观感代价登记在人工装机验证清单观察。

## 被拒方案

- **全部下沉仓库层**：位置与轮次协议随页面会话生灭，下沉会迫使仓库层携带 UI 生命周期的状态并暴露订阅口；仓库层已有的写路径（`ShelfEntryStore` 等）不动，本轮只抽派生口径。
- **留在 ViewModel、只补测试**：宿主内的状态机只能靠 Robolectric 测接线，判据本身仍是私有的；抽成纯 JVM 模块后判据可以直接锁死，分叉在测试期暴露而不是装机后。
- **页面继续自算派生值**：正是被抽走的病灶——两处口径迟早分叉，且分叉静默。

## 边界

- 下载队列策略（重试、出队、暂停语义）仍归 `DownloadBatch`/`DownloadRunner`；本篇只收「确认前算什么」。
- 取源四档与书源接口拆分另见 [ADR-0045](0045-source-binding-typed-result.md) 与 [ADR-0047](0047-book-source-surface-split.md)；导入的判重口径与处置顺序另见 [ADR-0023](0023-import-time-duplicate-disposition.md)。
- 三处行为修正不改任何数据库契约与接口签名；`isSameBook` 守卫保护的是会话归属，不保护数据库一致性（追更与换源之间的孤儿行风险另行登记，不在本轮修）。

## 下游影响

- `BookReadViewModel`、`ReadBookActivity`、`SearchViewModel`、`BookImportViewModel`、`ImportBookActivity`、`DownloadManageViewModel`、`ChapterDownloadSheet` 全部改为消费对应单主模块；各口径的现状描述以 `docs/architecture.md` 为准，本篇只记为什么抽。
- 四个模块均为纯 JVM（零 Compose / Android 运行时依赖），测试锁判据本身；宿主接线另由各 ViewModel 测试锁住。

锚点：`ReaderSession`；`ReaderSessionTest`；`AggregationSession`；`AggregationSessionTest`；`ImportScanner`；`ImportScannerTest`；`DispositionPolicy`；`DispositionPolicyTest`；`ChapterSelectionPlan`；`ChapterSelectionPlanTest`
