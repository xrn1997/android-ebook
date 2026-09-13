## 1. 采用的体系与工具

- **构建系统**：Gradle（`settings.gradle.kts`、`build.gradle.kts`），Android Gradle Plugin 9.x，Kotlin 2.4.x。
- **统一仓库源**：根 `settings.gradle.kts` 的 `dependencyResolutionManagement { repositoriesMode = FAIL_ON_PROJECT_REPOS }` 强制所有子模块通过根级声明的仓库解析依赖；仓库顺序为阿里云镜像 → Google → Maven Central → JitPack → 腾讯云 → Apache Snapshots → Sonatype Snapshot → Gradle Plugin Portal，并启用 `org.gradle.toolchains.foojay-resolver-convention` 以自动拉取 JDK toolchain。各业务模块不得自建 `repositories {}`，避免私有/内网仓库散落。
- **版本管理**：`gradle/libs.versions.toml` 是唯一的版本事实源，集中声明 `[versions]`、`[libraries]`、`[bundles]`、`[plugins]`，子模块通过 `alias(libs.xxx)` 引用，禁止在模块 `build.gradle.kts` 中硬编码版本号。
- **自定义约定插件**：`build-logic/convention/` 提供 `xrn1997.*` 前缀的 Gradle 插件（application/library/compose/lint/hilt/room/component/native），由 `build-logic/convention/build.gradle.kts` 注册，统一注入 AGP/KSP/Room/Hilt/TheRouter 等插件及默认依赖，使业务模块脚本保持“只声明 feature，不装配工程”的状态。
- **本地源码联调**：外部共享库 `lib_common`（坐标 `io.github.xrn1997:common`）默认走 Maven 中央；开发者可通过在根 `settings.gradle.kts` 打开注释的 `includeBuild("lib-common-build")` + `dependencySubstitution` 切换为本地源码联调，联调结束必须切回坐标发布版本，这是仓库约定的双向开关而非新增机制。

## 2. 关键文件

- `settings.gradle.kts`：仓库源、`FAIL_ON_PROJECT_REPOS` 策略、模块 include、foojay resolver、`lib_common` 联调开关。
- `gradle/libs.versions.toml`：全部第三方依赖与插件的版本、命名别名、BOM、Gradle 插件 ID。
- `build.gradle.kts`（根）：顶层 `plugins {}` 仅做 `apply false` 声明，把插件应用交给约定插件。
- `build-logic/convention/build.gradle.kts`：约定插件集合及其实现类注册。
- `gradle.properties`：`isModule=false`（提交态）、JVM args、R8/AGP 开关。
- `gradle/gradle-daemon-jvm.properties`：守护进程 JDK 配置（配合 foojay 自动下载）。
- `third_party/quickjs/PIN.sha256` + `third_party/quickjs/*.c/.h`：QuickJS 内核 vendored，作为 C++ 沙箱引擎源码随仓分发。
- 各模块 `consumer-rules.pro` / `proguard-rules.pro`：混淆规则按 Android 标准拆分，唯一来源为 `consumer-rules.pro`，业务模块仅 `-include consumer-rules.pro` 避免重复。

## 3. 架构与约定

- **单一事实源原则**：所有第三方依赖的版本集中在 `libs.versions.toml`，任何升级只需改该文件一处；若需新依赖，先在 `[versions]` 加版本、再在 `[libraries]` 加条目、最后在模块中 `implementation(libs.xxx)` 引用。
- **仓库白名单**：除根 `settings.gradle.kts` 声明的仓库外，不允许在子模块引入额外仓库（含私有 Nexus/Artifactory），如有需要应在根层追加并按团队流程评审。
- **BOM 优先**：Compose 相关依赖通过 `androidx-compose-bom` 统一管理，避免 Compose 子组件版本漂移。
- **平台变体区分**：SQLite Bundled 同时暴露 `sqlite-bundled`（Android ABI）和 `sqlite-bundled-jvm`（桌面 JVM natives），用于让 JVM 单测也能加载生产同款原生库；这种区分通过版本目录条目名表达，而非在模块里写 `if (isJvm) {...}`。
- **Vendoring 策略**：仅对无法通过 Maven/JitPack 稳定获取或需严格锁定版本的 C/C++ 源码使用 vendoring（当前为 QuickJS），其余依赖一律走远程仓库 + 版本目录；vendored 源码放在 `third_party/` 下，附带 `PIN.sha256` 记录校验值，CMake 列表由 `lib_book_source/src/main/cpp/CMakeLists.txt` 维护。
- **构建产物隔离**：混淆规则通过 `consumerProguardFiles` 下沉到 AAR，集成态由 `module_app` 合并；独立调试态由各模块自行执行 R8，保证问题尽早暴露。

## 4. 约束与规范（可验证项）

- **禁止子模块自管仓库**：`repositoriesMode = FAIL_ON_PROJECT_REPOS`，子模块出现 `repositories {}` 会直接失败。
- **禁止硬编码版本号**：根 build 脚本已用 `apply false` 预声明所有插件，业务模块只能 `alias(libs.plugins.xxx)`；文档明确要求“依赖版本仅通过版本目录管理”。
- **`isModule` 提交态必须为 `false`**：`gradle.properties` 中的注释明确说明提交态应为集成构建，临时改为 `true` 后必须改回且不提交，因为 `isModule=true` 会改变模块的 application/library 形态。
- **`lib_common` 切换方式固定**：只能通过根 `settings.gradle.kts` 的 `includeBuild("lib-common-build")` + `dependencySubstitution` 切换本地源码，不能通过命令行 `-P` 覆盖（会导致 lib_common 被错误套上 application 插件）。
- **混淆规则归属**：第三方库自带 consumer 规则已覆盖，业务模块不得重复写；新增反射面应先查依赖自带规则，无证据不得写 `-keep`/`-dontwarn`。
- **Toolchain 管理**：通过 foojay resolver 自动下载 JDK，不需要开发者本地预装特定版本 JDK；守护进程 JVM 由 `gradle/gradle-daemon-jvm.properties` 指定。
- **AGP 内置 Kotlin**：Android 模块不再显式应用 `org.jetbrains.kotlin.android`，由 AGP 9 内置提供；顶层 `kotlin {}` 块仍可用。
- **Native ABI 口径收敛**：release 最小 ABI 集为 `arm64-v8a`，模拟器所需 `x86_64` 仅在 debug 中放宽，且放宽点写在模块而非约定插件，以便“谁多带了一份内核”当场可见。