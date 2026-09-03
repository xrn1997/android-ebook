# AGP 9 内置 Kotlin（built-in Kotlin）：移除 KGP Android 插件路径

决定迁移到 AGP 9 内置 Kotlin：Android 模块不再应用 `org.jetbrains.kotlin.android`（KGP）插件，删除 `gradle.properties` 中的 `android.builtInKotlin=false` 与 `android.newDsl=false` opt-out 开关，Kotlin 编译支持由 AGP 9 直接提供；各模块顶层 `kotlin { compilerOptions {} }` 块保留（AGP 内置 Kotlin 仍注册 `KotlinAndroidProjectExtension`，行为不变）。

背景：AGP 9.0 起 Kotlin 支持内置，传统 KGP Android 插件路径被标记弃用（每个模块构建时打出 "Deprecated 'org.jetbrains.kotlin.android' plugin usage" 警告），并将在 AGP 10.0 移除。此前项目为兼容 KGP 2.3.0 与旧 DSL 显式设置了两个 opt-out 开关，属于未迁移的过渡状态。android-practice（lib_common 上游）已先行完成迁移（`builtInKotlin=true`、约定插件不再 apply kotlin-android、扩展类型使用 `com.android.build.api.dsl.*`），本项目与之对齐，并保持 lib-common-build 迷你构建的开关与主构建一致。

**已拒绝的选项**：
- 保留 opt-out（`builtInKotlin=false` + `newDsl=false`）维持现状：走的是 AGP 10 将移除的弃用路径，每个模块每次构建持续报警告，迁移成本只会推迟
- 只删开关不删插件（或反之）：`builtInKotlin=true` 与 `org.jetbrains.kotlin.android` 同时存在会冲突（`Cannot add extension with name 'kotlin'`）；只删插件而保留 `newDsl=false` 则旧 DSL 类型仍可用但无意义（旧 DSL 本身同属弃用范围）

**下游影响**：
- 约定插件（`xrn1997.android.*`）的扩展类型必须使用新 DSL 的 `com.android.build.api.dsl.*`（`LibraryExtension`/`TestedExtension` 等旧类型在新 DSL 下不注册）；`sourceSets` 在新 DSL 下暴露于 `CommonExtension`（原 `TestedExtension` 上已无此成员）
- `org.jetbrains.kotlin.jvm`（JVM 模块）与 Compose/serialization 编译器插件（`kotlin.plugin.compose`/`kotlin.plugin.serialization`）不受影响，继续应用
- kapt 与内置 Kotlin 不兼容，须用 KSP（本项目已全部 KSP，无迁移负担）
- `gradle.properties` 中 7 个 AGP 8 时代属性（`android.enableJetifier`、`android.nonFinalResIds` 等）**已删除**：AGP 9 的默认值即推荐值，且 AGP 10 将移除这些属性，无需再显式设置（取法记在 `gradle.properties` 的说明注释里）。同规则下移除的还有 `android.useConstraints`（AGP 9 默认 `false`）与 `android.optimizedResourceShrinking`（默认已为 `true`，显式 `false` 在 9.2 弃用）；仅保留 AGP 9 仍支持且无默认等价的条目
- 根构建与 lib-common-build 不再预声明 `kotlin.android` 插件（KGP 类由 `kotlin.jvm` 预声明加载，约定插件对 `KotlinAndroidProjectExtension` 的引用不受影响）
