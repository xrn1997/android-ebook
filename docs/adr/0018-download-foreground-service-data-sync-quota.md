# 离线下载保持 dataSync 前台服务：配额超时与启动被拒在服务侧收口，不迁 WorkManager

离线下载继续使用 `foregroundServiceType="dataSync"` 的前台服务（`module_book` 的 `DownloadService`），不迁移到 WorkManager——因为 6 小时配额上限对绝大多数单批次够用、重置条件（用户回到前台）与本产品交互天然吻合，而 WorkManager 的约束模型与本下载链路（逐章抓取 + 实时进度回传 + 通知按钮暂停/取消）差异大、改动面不成比例。同时把 Android 15+（targetSdk 35+，本项目 targetSdk 37）的两条硬约束收口在服务侧：24 小时累计 6 小时配额超时由 `onTimeout` 回调处理，启动被拒由 `DownloadService.start()` 统一 try/catch。

## 动机

### 澄清一个误报

一次评审提出「两参 `startForeground(id, notification)` 在 Android 14+ 内部以 `type=0` 调用 → `MissingForegroundServiceTypeException` → 被外层 `catch(Throwable)` 吞掉 → 服务未前台化 → `ForegroundServiceDidNotStartInTimeException` 杀 App」。以本地 AOSP 源码与 API 37 桩为事实源，该链条从第一环就不成立：

| 断言 | 实况（事实源） |
| --- | --- |
| 两参版本传 `type=0` | 传的是 `ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST`（= `-1`），语义即「取 manifest 声明的全部类型」 |
| 传类型才不崩 | `MissingForegroundServiceTypeException` 仅在 manifest 未声明 `foregroundServiceType` 时抛出；本仓库声明了 `dataSync` 并持有 `FOREGROUND_SERVICE_DATA_SYNC` |
| `type=0` 是常态回落 | `0` 是 `FOREGROUND_SERVICE_TYPE_NONE`，该常量在 API 37 已被标 `@Deprecated`；只有显式传 NONE 才触发异常 |
| API 35/36/37 有新增要求 | Android 15/16/17 的 target 门槛行为变更清单里没有任何「必须在 `startForeground()` 里传类型」的条目 |

所以「补三参调用」不是修复项，真正的风险在配额与启动许可两处。

### 真实约束

- **dataSync 配额（Android 15 起，targetSdk 35+）**：系统只允许 dataSync 前台服务在任意 24 小时内累计运行 6 小时。本服务按章抓取、章节间刻意 `postDelayed(800ms)`、单章失败重试 3 次，整本书（数千章）完全可能撞上上限。到点时系统先摘掉前台态再回调 `Service.onTimeout(int, int)`，只留数秒钟让服务自行退出；不及时 `stopSelf()` 会被记 `RemoteServiceException`——这才是「下载跑到后段闪退」的现实路径。
- **启动被拒**：配额用尽后再次启动 dataSync 服务，系统抛 `ForegroundServiceStartNotAllowedException`；Android 12+ 的后台启动限制抛同一族异常。原先两个启动点均未捕获，用户点「下载/全部开始」即崩。
- **任务只躲在 Intent 里**：`buildStartIntent` 携带章节列表，服务从未起来时这批选择直接丢失（`addTasks` 由服务执行，入库发生在启动之后）。

## 决策

1. **保持 dataSync 前台服务**：manifest 声明与 `FOREGROUND_SERVICE_DATA_SYNC` 权限维持现状，两参 `startForeground` 合法且足够，不改成三参调用。理由：改了也不改变行为，只增加「必须与 manifest 类型取子集」的维护负担。

2. **`onTimeout(startId, fgsType)` 只做数秒内可完成的收尾**：置 `isStartDownload=false`/`isDownloading=false`、清空 `myHandler` 回调、`DownloadRepository.tryEmitState(DownloadState.Paused)` 同步写入 replay 缓冲（避免紧随的 `stopSelf` → `onDestroy` 取消 `serviceScope` 把异步发射吞掉）、发一条可点回应用的提示通知、`stopSelf()`。不得在回调里查库、发网络请求或跑协程。理由：系统只给数秒窗口，超时会被强制终止并记录异常。

3. **启动收口 `DownloadService.start()`**：内部 try/catch，用类名前缀+后缀比对识别 `*ServiceStartNotAllowedException`（不直接 `catch(ForegroundServiceStartNotAllowedException)`——该类 API 31 才引入，minSdk 26 下 catch 子句要解析该类，低版本设备有 `NoClassDefFoundError` 风险），返回 `false` 由调用方提示用户。理由：统一异常处理避免各调用点各自 catch 不同异常子族。

4. **发起方先入库再拉服务**：`BookReadViewModel.startDownload(chapters)` 先 `downloadRepository.addTasks()` 再 `DownloadService.start()`；`addTasks` 按 `durChapterUrl` 去重，服务侧收到同批 Intent 再入一次是幂等的。理由：服务启动被拒时任务已在库中，不会因启动失败而丢失用户选择。

5. **前台化失败不再自动续跑**：`onStartCommand` 里 `startForeground` 抛异常时置 `fgUnavailable`，「无携带任务」的自动续跑分支据此直接收尾并返回 `START_NOT_STICKY`——没有前台态的下载服务既跑不久，也会被系统反复重启刷同一异常。提示走通知而非仅 Toast（该场景应用通常在后台）。注意：通知权限被拒不会让 `startForeground` 失败（Android 13+ 未授权时 notify 被静默丢弃，前台服务照常），故保留原 `catch(Throwable)` 兜底语义。

6. **常驻通知的动作按钮是合法例外的第二启动口**：`DownloadService.commandPendingIntent` 用 `PendingIntent.getForegroundService` 承载「暂停 / 继续 / 取消」，代码现状即如此，不要按「必须一律走 `start()`」去改。理由：`getForegroundService` 能在服务已被回收时先把它拉起来再执行动作（服务已存活时只多一次 `onStartCommand`，各 action 分支幂等）。代价是这条路径拿不到 `DownloadService.start()` 的 `false` 返回值，因此没有也不该有用户提示——系统拒绝启动时按钮静默无响应即属预期。有反馈的重试入口在下载管理页「全部开始」。上面的禁令（决策第 3 条）只约束页面 / ViewModel 侧，不覆盖服务内部这一处。

## 被拒方案

- **迁移到 WorkManager**：本下载链路是「逐章抓取 + 实时进度回传 SharedFlow + 通知按钮暂停/取消 + 按库中未完成任务续跑」，WorkManager 的约束模型与生命周期与之差异大，改动面横跨服务、仓库、两个页面与通知动作；而 6 小时上限对绝大多数单批次够用，重置条件（用户回到前台）也与本产品交互天然吻合。
- **降级为普通 `startService` 重试**：Android 8+ 后台同样限制普通服务启动，不解决问题，只会把异常换一种类型再抛一次。
- **改用 `specialUse`/`shortService` 等类型规避配额**：语义不符（离线下载就是长时间数据同步），且 Android 17 对 specialUse 的审查只会更严。
- **删掉 manifest 的 `foregroundServiceType` 或显式传 `FOREGROUND_SERVICE_TYPE_NONE`**：前者正是触发 `MissingForegroundServiceTypeException` 的唯一途径，后者在 API 37 已废弃。
- **把 `onTimeout` 里的状态发射改成异步协程**：`stopSelf` 后 `onDestroy` 会 `serviceScope.cancel()`，异步发射可能被吞，界面会停在「正在下载」。故新增非挂起的 `tryEmitState`。

## 下游影响

- `module_book`：`DownloadService`（`onTimeout`、`postAttentionNotification`、`fgUnavailable`、companion `start()`/`isStartNotAllowed()`）、`DownloadRepository.tryEmitState`、`BookReadViewModel`（注入 `DownloadRepository` + `startDownload`）、`ReadBookActivity.startChapterDownload`（改为调 `viewModel.startDownload`，删除直接启动与 `ContextCompat` 导入）、`DownloadManageViewModel.sendAction`（改走 `DownloadService.start`）、`res/values/strings.xml`（`download_start_restricted`、`notification_fgs_timeout_text`）。
- 构建/manifest：无变更（`dataSync` 声明与 `FOREGROUND_SERVICE_DATA_SYNC` 权限本已齐备）。
- 测试：`DownloadService` 依赖系统服务生命周期，超时/启动被拒路径需设备或模拟器验证；纯 JVM 侧只对 `DownloadRepository` 的 `tryEmitState` 有可测面。

## 落地状态

已实现，`:module_app:assembleRealDebug` 与 `:module_book:testDebugUnitTest` 通过。

后续若出现「单批次下载常超 6 小时」的真实反馈，再评估 WorkManager 迁移。
