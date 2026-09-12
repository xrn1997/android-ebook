# 外观主题三态：选择持久化在 SharedPreferences，装配点经伴生单例取用

深色外观此前只有「跟随系统」一个来源（装配 `MyApplicationTheme` 时直接读 `isSystemInDarkTheme()`）。现新增用户可选的**浅色 / 深色 / 跟随系统**三态：选择持久化在 SharedPreferences（`theme_mode` 文件、单键 `mode`），以 `StateFlow` 暴露，由主题装配 lambda 在 Compose 重组时读取——为此把管理器实例在 Hilt 注入完成后挂到**伴生对象**上，让这个位于 Hilt 图之外的调用点也能取到与设置页同一份状态。

## 决策

1. **三态，默认跟随系统**：语义上「跟随系统」已是一种选择，再叠一个独立的应用内强制开关就会出现「跟随系统开着、又要求强制深色」这类需要额外解释的组合态。默认 `SYSTEM` 与 Compose `isSystemInDarkTheme()` 的默认语义一致，老用户升级后观感不变；浅色/深色供「系统深色但我想看浅色」这类明确偏好。
2. **载体是 SharedPreferences，坏值一律回落 `SYSTEM`**：单键枚举没有迁移与类型化查询需求，而装配点需要**同步**拿到确定值——首次组合就要给出 `darkTheme`，异步读会先按系统色渲染一帧再跳变。枚举名解析包在 `runCatching` 内：跨版本改名或被外部改写的坏值不得让应用起不来。
3. **读取入口是伴生单例，不是 Hilt 注入**：主题装配 lambda 经 `AppTheme.install {}` 注册，由各页面在 `setContent` 内调用——它处在 Composable 作用域里，但**不是 Composable 也没有可注入的调用者**（`hiltViewModel()` 够不到它）。因此 lambda 只能读进程级访问点 `ThemeModeManager.instance`：`@Volatile` 且可空，未就绪时按 `SYSTEM` 兜底（理论上不出现——装配只发生在 `setContent` 内，晚于 `Application.onCreate`）。lambda 内 `collectAsState()` 订阅同一个 `StateFlow`，改选择即重组、全 App 立即换肤，无需重启。
4. **实例仍归 Hilt 管构造与生命周期，单例位只做交接**：`ThemeModeManager` 由 `ThemeModule` 提供为 `@Singleton`，`BookApplication.onCreate` 用 `EntryPointAccessors` 取出后调 `installIntoCompanion()`。不走字段注入有三条具体理由：`BookApplication` 本身不是 `@HiltAndroidApp`（那是应用模块的宿主类）；Application 上的 eager `@Inject` 会跑在 `super.onCreate()` 内部、早于隔离进程门；而本管理器构造后要读的 SharedPreferences 在隔离进程里根本读不到。这样「构造与生命周期」只有 Hilt 一个源，伴生位不是第二个实例源。
5. **写路径只有一条**：设置页经 `SettingViewModel.setThemeMode` 落盘并推进 `StateFlow`，其余页面只读。选择是全局单值，不允许任何页面持有本地镜像。
6. **隔离进程不参与**：`BookApplication.onCreate` 在 `SandboxProcess.isInIsolatedProcess` 时提前返回，既不注册装配点也不挂单例——那个进程不画 UI，且读不到应用数据目录，读 SP 只会得到一串 IO 失败日志。
7. **判「当前是否深色」一律经本管理器或 `MaterialTheme.colorScheme`，不直接调 `isSystemInDarkTheme()`**：三态下两者不等价。「我的」页头部渐变曾直接读系统深色，用户设置里强制深色而系统为浅色时渐变不切换、与页面底色脱节；现按同一三段映射取值，与装配点同源。
8. **阅读器不受本开关管辖**：阅读器整片豁免系统深色——chrome 层在 `ReadBookActivity` 作用域内固定浅色调板，正文配色由阅读背景主题独立控制。故三态对阅读界面不生效、也不应长出一条分支；将来若要「夜间阅读」，那是阅读背景主题多一档，与外观主题模式是两套控制。

## 权衡

- **不做成 ViewModel 状态（只服务设置页与「我的」页）**：装配点在基类提供的 `AppTheme` lambda 里、位于 Hilt 图之外，ViewModel 状态到不了那里；靠它换肤就得每个页面各包一层主题，分散且必然漏。
- **不用 DataStore / 新依赖**：为单键枚举引入异步读取 API，装配点就得处理「值还没读出来」的中间态，而这在换肤场景恰是最难看的——先按系统色渲染一帧再跳变。
- **不引入应用级 `attachBaseContext`/`Configuration` 覆写**：那要让每个 Activity 的 context 被包装过，且 Compose 作用域内的固定浅色（阅读器）会被一并改写；仅有的收益是让直接读 `isSystemInDarkTheme()` 的旧代码也跟随，而旧代码只剩两处，改成读本管理器更便宜。

## 下游影响

- `lib_book_common`：新增 `ThemeMode`（枚举）、`ThemeModeManager`（持久化 + `StateFlow` + 伴生实例位）、`ThemeModule`（`@Singleton` 提供）；`BookApplication` 的装配 lambda 改为按三态决定 `darkTheme`，并新增 `ThemeModeManagerEntryPoint` 取出实例。装配 `darkTheme` 用的 `dynamicColor` 入参留位：将来固定品牌色只改这一处，页面零改动。
- `module_me`：`SettingViewModel` 转发 `themeMode` 并提供 `setThemeMode`；设置页「通用」区改为「夜间模式跟随系统」开关 + 关闭后展开的日间/夜间子菜单（选中项带勾选标记）；`MePage` 头部渐变改为按同一映射取值。
- 三态映射（`when` 三段）目前在 `BookApplication` 与 `MePage` 各写一份，是刻意的两处小重复（两个调用点分属不同模块，共享读取点应落在 `lib_book_common`）；出现第三处前先抽到一处。
- 本机制无自动化测试覆盖：选择读写落在 SharedPreferences 与 Compose 重组上，`SettingViewModelTest` 只构造了管理器实例、未断言主题转发。可观测行为以 `ThemeModeManager` 的 KDoc 与设置页三条文案（跟随系统 / 日间模式 / 夜间模式）为准。
