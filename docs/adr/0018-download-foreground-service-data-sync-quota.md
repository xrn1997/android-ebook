# 离线下载保持 dataSync 前台服务，配额与启动被拒在服务侧收口

本 ADR 决定：**离线下载继续使用 `foregroundServiceType="dataSync"` 的前台服务（`module_book` 的 `DownloadService`），不迁移到 WorkManager**；同时把 Android 15+（对 targetSdk 35+ 生效，本项目 targetSdk 37）压在 dataSync 上的两条硬约束收口在服务侧——

- **24 小时内累计 6 小时的配额超时**：`DownloadService` 实现 `Service.onTimeout(int, int)`，在系统给定的数秒内完成收尾并 `stopSelf()`；
- **启动被系统拒绝**：拉起统一走 `DownloadService.start(context, intent)`（返回 `false` = 未起来，调用方提示用户），**调用点不得直接调 `ContextCompat.startForegroundService`**；服务自身常驻通知的动作按钮是**唯一例外**的第二启动口，取不到 `false`、也不弹提示（见决策第 6 条）；
- 配套约束：发起下载的调用方必须**先把任务写入 `download_chapter`，再拉起服务**。

## 动机

### 先澄清一个误报（取证结论，避免后人再踩）

一次评审提出："两参 `startForeground(id, notification)` 在 Android 14+ 内部以 `type=0` 调用 → `MissingForegroundServiceTypeException` → 被外层 `catch(Throwable)` 吞掉 → 服务未前台化 → `ForegroundServiceDidNotStartInTimeException` 杀 App"。以本地 AOSP 源码与 API 37 桩为事实源，该链条从第一环就不成立：

| 断言 | 实况（事实源） |
| --- | --- |
| 两参版本传 `type=0` | 传的是 `ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST`（= `-1`），语义即"取 manifest 声明的全部类型"（`sources/android-34/android/app/Service.java` L772-L782） |
| 传类型才不崩 | `MissingForegroundServiceTypeException` 仅在 **manifest 未声明** `foregroundServiceType` 时抛出（同文件 javadoc L756-L759）；本仓库 `module_book/src/main/AndroidManifest.xml` 声明了 `dataSync` 并持有 `FOREGROUND_SERVICE_DATA_SYNC`，合并进 APK 的 manifest 同样保留 |
| `type=0` 是常态回落 | `0` 是 `FOREGROUND_SERVICE_TYPE_NONE`，该常量在 API 37 已被标 `@Deprecated`；只有**显式传 NONE** 才触发 `InvalidForegroundServiceTypeException` |
| API 35/36/37 有新增要求 | Android 15/16/17 的 target 门槛行为变更清单里**没有任何**"必须在 `startForeground()` 里传类型"的条目；API 37 桩中两参版本仍未废弃、`FOREGROUND_SERVICE_TYPE_MANIFEST` 仍为 `-1` |

所以"补三参调用"不是修复项，真正的风险在配额与启动许可两处。

### 真实约束

- **dataSync 配额（Android 15 起，targetSdk 35+）**：系统只允许 dataSync 前台服务在任意 24 小时内累计运行 6 小时。本服务按章抓取、章节间刻意 `postDelayed(800ms)`、单章失败重试 3 次，整本书（数千章）完全可能撞上上限。到点时系统**先摘掉前台态**再回调 `Service.onTimeout(int, int)`，只留数秒钟让服务自行退出；不及时 `stopSelf()` 会被记 `RemoteServiceException: A foreground service of type dataSync did not stop within its timeout`——这才是"下载跑到后段闪退"的现实路径。
- **启动被拒**：配额用尽后再次启动 dataSync 服务，系统抛 `ForegroundServiceStartNotAllowedException`（`extends ServiceStartNotAllowedException extends IllegalStateException`）；Android 12+ 的后台启动限制抛同一族异常。原先两个启动点（`ReadBookActivity.startChapterDownload`、`DownloadManageViewModel.sendAction`）均未捕获，用户点"下载/全部开始"即崩。
- **任务只躲在 Intent 里**：`buildStartIntent` 携带章节列表，服务从未起来时这批选择直接丢失（`addTasks` 由服务执行，入库发生在启动之后）。

## 决策

1. **保持 dataSync 前台服务**：manifest 声明与 `FOREGROUND_SERVICE_DATA_SYNC` 权限维持现状，两参 `startForeground` 合法且足够，不改成三参调用（改了也不改变行为，只增加"必须与 manifest 类型取子集"的维护负担）。
2. **`onTimeout(startId, fgsType)` 只做数秒内可完成的收尾**：置 `isStartDownload=false`/`isDownloading=false`、清空 `myHandler` 回调、`DownloadRepository.tryEmitState(DownloadState.Paused)` 同步写入 replay 缓冲（避免紧随的 `stopSelf` → `onDestroy` 取消 `serviceScope` 把异步发射吞掉）、发一条可点回应用的提示通知、`stopSelf()`。**不得**在回调里查库、发网络请求或跑协程。
3. **启动收口 `DownloadService.start()`**：内部 try/catch，用**类名前缀+后缀比对**识别 `*ServiceStartNotAllowedException`（不直接 `catch(ForegroundServiceStartNotAllowedException)`——该类 API 31 才引入，minSdk 26 下 catch 子句要解析该类，低版本设备有 `NoClassDefFoundError` 风险），返回 `false` 由调用方提示用户。
4. **发起方先入库再拉服务**：`BookReadViewModel.startDownload(chapters)` 先 `downloadRepository.addTasks()` 再 `DownloadService.start()`；`addTasks` 按 `durChapterUrl` 去重，服务侧收到同批 Intent 再入一次是幂等的。
5. **前台化失败不再自动续跑**：`onStartCommand` 里 `startForeground` 抛异常时置 `fgUnavailable`，"无携带任务"的自动续跑分支据此直接收尾并返回 `START_NOT_STICKY`——没有前台态的下载服务既跑不久，也会被系统反复重启刷同一异常。提示走通知而非仅 Toast（该场景应用通常在后台）。注意：通知权限被拒**不会**让 `startForeground` 失败（Android 13+ 未授权时 notify 被静默丢弃，前台服务照常），故保留原 `catch(Throwable)` 兜底语义（设备侧包归属错乱的 `SecurityException`，见既有注释）。
6. **常驻通知的动作按钮是合法例外的第二启动口**：`DownloadService.commandPendingIntent` 用 `PendingIntent.getForegroundService`（而非 `getActivity`）承载「暂停 / 继续 / 取消」，**代码现状即如此，不要按"必须一律走 `start()`"去改**。取它是因为服务可能已被系统回收，`getForegroundService` 能在**服务已被回收时先把它拉起来再执行动作**（服务已存活时只多一次 `onStartCommand`，各 action 分支幂等）。代价是这条路径**拿不到 `DownloadService.start()` 的 `false` 返回值**，因此**没有也不该有**用户提示——它的语义是「先拉起再执行」，系统拒绝启动（配额耗尽 / 应用后台）时按钮静默无响应即属预期。有反馈的重试入口在下载管理页「全部开始」（`DownloadManageViewModel.sendAction` → `DownloadService.start`，被拒提示 `download_start_restricted`）。上面的禁令（开头收口清单与决策第 3 条）只约束**页面 / ViewModel 侧**，不覆盖服务内部这一处。

## 被拒方案

- **迁移到 WorkManager（官方对 dataSync 超时的推荐替代）**：本下载链路是"逐章抓取 + 实时进度回传 SharedFlow + 通知按钮暂停/取消 + 按库中未完成任务续跑"，WorkManager 的约束模型与生命周期与之差异大，改动面横跨服务、仓库、两个页面与通知动作；而 6 小时上限对绝大多数单批次够用，重置条件（用户回到前台）也与本产品交互天然吻合。**留作后续独立评估**，触发条件见"落地状态"。
- **降级为普通 `startService` 重试**：Android 8+ 后台同样限制普通服务启动，不解决问题，只会把异常换一种类型再抛一次。
- **改用 `specialUse`/`shortService` 等类型规避配额**：语义不符（离线下载就是长时间数据同步），且 Android 17 对 specialUse 的审查只会更严。
- **删掉 manifest 的 `foregroundServiceType` 或显式传 `FOREGROUND_SERVICE_TYPE_NONE`**：前者正是触发 `MissingForegroundServiceTypeException` 的唯一途径，后者在 API 37 已废弃。
- **把 `onTimeout` 里的状态发射改成异步协程**：`stopSelf` 后 `onDestroy` 会 `serviceScope.cancel()`，异步发射可能被吞，界面会停在"正在下载"。故新增非挂起的 `tryEmitState`。

## 下游影响

- `module_book`：`DownloadService`（`onTimeout`、`postAttentionNotification`、`fgUnavailable`、companion `start()`/`isStartNotAllowed()`）、`DownloadRepository.tryEmitState`、`BookReadViewModel`（注入 `DownloadRepository` + `startDownload`）、`ReadBookActivity.startChapterDownload`（改为调 `viewModel.startDownload`，删除直接启动与 `ContextCompat` 导入）、`DownloadManageViewModel.sendAction`（改走 `DownloadService.start`）、`res/values/strings.xml`（`download_start_restricted`、`notification_fgs_timeout_text`）。
- 构建/manifest：无变更（`dataSync` 声明与 `FOREGROUND_SERVICE_DATA_SYNC` 权限本已齐备）。
- 测试：`DownloadService` 依赖系统服务生命周期，超时/启动被拒路径需设备或模拟器验证（见下）；纯 JVM 侧只对 `DownloadRepository` 的 `tryEmitState` 有可测面。

## 落地状态

已实现，`:module_app:assembleRealDebug` 与 `:module_book:testDebugUnitTest` 通过。**Android 15+ 设备/模拟器上的两条路径未做装机验证**，提交前由人工完成：

1. 压短配额：`adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS com.ebook` ＋ `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000`，然后在阅读器发起整本下载，等 60 秒——期望现象：下载停止、`adb logcat -b crash` 无 `RemoteServiceException`、通知栏出现"下载时长已达系统上限"、下载管理页显示"已暂停"、回到前台后点"全部开始"可续跑（配额耗尽后的两条重试入口见第 2 条，现象不同，别互相当成对方的失败）。
2. 配额耗尽后再次发起下载，两个入口分开验：
   - **通知「继续」按钮**（把应用切后台后点）：**静默无响应属预期**，不闪退即通过。该按钮走 `PendingIntent.getForegroundService`（决策第 6 条），不经 `DownloadService.start()`、拿不到 `false` 返回值，代码里不存在任何提示分支——旧版清单写的"期望 Toast 提示"是不可能成立的现象，别再照它判失败。要验有反馈的重试路径，见下一条。
   - **下载管理页「全部开始」**：走 `DownloadManageViewModel.sendAction` → `DownloadService.start`，被拒时 Toast 提示"系统已限制后台下载时长，任务已保留，稍后回到下载管理页可继续"（`download_start_restricted`），任务未丢（重新进入下载管理页仍列出剩余章数）。

后续若出现"单批次下载常超 6 小时"的真实反馈，再按被拒方案首条评估 WorkManager 迁移，并另立 ADR。
