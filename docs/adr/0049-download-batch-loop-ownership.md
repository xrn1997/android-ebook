# 下载批次循环与终态判定收进纯 JVM 类

Status: accepted

`DownloadService` 里「取篇 → 跑一章 → 跳过计数 → 间隔 → 下一章」的批次循环与「队头取不到 ≠ 队列跑空」的终态三分都长在 Android 服务里，JVM 侧零测试；单章执行（`DownloadRunner`）与队列策略（`DownloadBatch`）已经抽出，只有这最后一段仍把策略与前台生命周期混在一起。决定把循环与终态判定收进 `DownloadBatchLoop`，Service 只保留前台生命周期、通知、Intent 分派与按结局收尾。

## 背景

- 终态判定写了两份：`toDownload` 的「清孤儿 → `countTasks > 0` → 暂停态 + 停服 / 否则完成」，与 `isPause` 的「队头 / `countTasks > 0` → 暂停态 / 完成」。两份都在 Android 服务里，只能装机试；而它是本仓改动最频繁的文件之一。
- 「空队列发 RESUME 会误报下载完成」这条 implementation 细节已经泄漏进两个调用方的注释（下载管理页与 ViewModel 各自提醒），说明判定没有住在能自证的地方。
- `DownloadRunner`（重试、出队、暂停不出队）与 `DownloadBatch`（候选域、暂停标记、孤儿清理）已有 7 条 JVM 用例各自锁住；缺的正是驱动它们的那层循环。

## 决策

1. **循环与终态收进 `DownloadBatchLoop`**，依赖以函数注入：取篇、跑章、清孤儿、计数、延时，外加一个章间隔常量。于是判定在纯 JVM 下可测，Service 不再持有「下一步做什么」的策略。
2. **终态分三档**：`Finished`（跑空：发完成通知并停服）、`PausedRemaining`（只剩暂停书的任务：转暂停态并静默停服）、`Interrupted`（用户暂停或 runner 打断：任务保留、服务常驻并改写通知）。前两档的差别是「这一批没得跑了」，第三档是「用户还打算继续」——停服与否则不该由一句布尔文案决定。
3. **跳过计数归 loop**：`skippedCount` 是循环侧的事实，每批开始归零，Service 只在收尾文案里读它。
4. **Service 保留**：前台生命周期、通知、Intent 分派、`finishDownload` 的提示与 `stopService` 决策。

## 被拒方案

- **只抽终态判定、循环留在 Service**：两份判定并成一份，但批次循环仍不可测，Service 仍持有策略；下次改重试节奏又回到只能装机试。
- **把整个服务换成 WorkManager / 协程 worker**：前台服务类型与通知契约（ADR-0018/0035）不变，换载体是另一件事，代价远超本轮。
- **让 loop 直接发通知、停服**：把 Android 副作用塞回纯逻辑，测试又要 mock 一堆框架，等于把 seam 挪错地方。

## 边界

- 不改队列语义（ADR-0035/0036）、不改单章执行（`DownloadRunner`）、不改 `dataSync` 配额处置（ADR-0018）。
- 不改 `DownloadService.start(context, intent)` 的对外契约与「启动 Intent 只作信号」的语义。

## 下游影响

- `DownloadService` 删掉 `downloading` 与 `findNextDownloadChapter`，`toDownload` 只按 `BatchEnd` 收尾；`docs/architecture.md` §7 的「跳过计数、暂停收尾与下一章调度」从 Service 移出。
- `DownloadBatchLoopTest` 锁住空队列、只剩暂停书、runner 暂停、跳过计数、打断与孤儿清理顺序。

锚点：`DownloadBatchLoop`；`BatchEnd`；`DownloadBatchLoopTest`
