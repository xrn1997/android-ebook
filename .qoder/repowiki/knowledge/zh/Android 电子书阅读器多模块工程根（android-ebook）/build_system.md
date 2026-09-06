## 1. 总体方案

本项目使用 **Gradle 9.4.1 + Android Gradle Plugin (AGP) 9.2.1** 构建多模块 Android 工程，依赖解析通过根 `gradle/libs.versions.toml`（Version Catalog）统一管理。核心构建配置集中在 `build-logic/convention/` 子工程中，以 **自定义 Gradle 约定插件**（ID 前缀 `xrn1997.`）的形式统一应用 Android/Kotlin/Compose/Hilt/Room/Lint 等通用设置，业务模块只需 `plugins { alias(libs.plugins.xrn1997.android.application|library) }` 即可接入。

仓库同时启用 Gradle Included Build：根 `settings.gradle.kts` 通过 `includeBuild("build-logic")` 将构建逻辑作为独立项目加载；另有可选的 `includeBuild("lib-common-build")`（当前注释态）用于与上游 `android-practice` 库做本地联调，通过 `dependencySubstitution` 把坐标 `io.github.xrn1997:common` 替换成本地项目。

Maven 仓库策略采用阿里云镜像优先（google/central/public/gradle-plugin/… 加上 `https://mirrors.cloud.tencent.com/maven`），配合 `org.gradle.toolchains.foojay-resolver-convention:1.0.0` 自动拉取 JDK 25，通过 `gradle/gradle-daemon-jvm.properties` 声明各平台 toolchain URL，实现无需预装 JDK 的跨平台构建。

## 2. 关键文件

- 根级脚本：`settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`、`gradle/gradle-daemon-jvm.properties`、`gradle.properties`
- 约定插件工程：`build-logic/settings.gradle.kts`、`build-logic/convention/build.gradle.kts`
- 约定插件源码：`build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`、`AndroidLibraryConventionPlugin.kt`、`AndroidComponentConventionPlugin.kt`、`AndroidComposeConventionPlugin.kt`、`HiltConventionPlugin.kt`、`AndroidRoomConventionPlugin.kt`、`AndroidLintConventionPlugin.kt`、`GradleManagedDevices.kt`、`KotlinAndroid.kt`、`ProjectExtensions.kt`、`PrintTestApks.kt`、`AndroidInstrumentedTests.kt`
- 应用入口模块：`module_app/build.gradle.kts`（flavor/构建类型/版本定义）
- 共享库门面：`lib_book_common/build.gradle.kts`（聚合 api/implementation 依赖）

## 3. 架构与约定

### 3.1 分层约定插件
`build-logic` 内按职责拆分多个 Convention Plugin，每个插件负责一组横切关注点：
- `AndroidApplicationConventionPlugin`：应用 `com.android.application`、Kotlin 配置、targetSdk=37、默认 applicationId = namespace、`configureGradleManagedDevices`、打印 APK 任务
- `AndroidLibraryConventionPlugin`：同上面向 library，禁用不必要的 `androidTest`，注入 `kotlin.test`/`junit`/`androidx.tracing.ktx` 等通用依赖
- `HiltConventionPlugin`、`AndroidRoomConventionPlugin`、`AndroidLintConventionPlugin`、`AndroidComposeConventionPlugin`：按需装配相应编译器/注解处理器和 Lint 规则
- `KotlinAndroid.kt`、`ProjectExtensions.kt`、`PrintTestApks.kt`、`GradleManagedDevices.kt` 为扩展函数与辅助工具

模块级 `build.gradle.kts` 极其精简，仅声明插件别名（如 `alias(libs.plugins.xrn1997.android.application)`）、`namespace`、`applicationId/versionCode/versionName` 与少量 flavor/compileOptions 差异。依赖与测试基线全部收敛到约定插件与 Version Catalog。

### 3.2 多模块结构
通过 `settings.gradle.kts` 聚合七个子模块：`module_app`、`module_main`、`module_book`、`module_find`、`module_me`、`module_login`、`lib_book_common`、`lib_ebook_api`、`lib_ebook_db`。依赖方向严格遵循 `业务模块 → lib_book_common → lib_ebook_api → lib_ebook_db` 分层；功能模块之间互不依赖，通过 TheRouter 解耦。

### 3.3 Flavor 与独立运行
`module_app` 定义 `network` flavor dimension，含 `real`（连接后端）与 `mock`（加 `.mock` applicationIdSuffix，内存 mock 数据源，applicationId 为 `com.ebook.mock`）。`isModule` 属性控制是否将 `module_*` 作为依赖引入 `module_app`（集成态）或独立作为可运行的 App（`gradle.properties` 中默认 `isModule=false`）。

### 3.4 Compose 与 KSP/Room
所有 Kotlin/JVM 相关编译选项（包括内置 Kotlin 的 JvmTarget 17）在约定插件中统一配置；Composable/编译期注解通过各自的 Convention Plugin 统一开启；Room (`androidx.room3`) 由 Hilt 与 KSP (`ksp=2.3.10`) 协同生成 DAO/实体代码。

### 3.5 版本管理
版本号在 `module_app/build.gradle.kts` 的 `defaultConfig` 中硬编码（`versionCode=10`, `versionName="1.2.0"`）；依赖版本一律从 `gradle/libs.versions.toml` 中的 `[versions]` 区引用，禁止在各模块 `build.gradle.kts` 硬编码数字版本（settings 的 `plugins {}` 块因读不到版本目录而豁免此规约，foojay resolver 插件版本仍用字面量 `1.0.0`）。

## 4. 规范与约束

- **依赖版本集中**：所有第三方库版本号只能出现在 `gradle/libs.versions.toml`，模块内通过 `libs.<group>.<name>` 引用（已知的 settings plugins 例外）。
- **仓库镜像强制**：`dependencyResolutionManagement.repositoriesMode = FAIL_ON_PROJECT_REPOS`，禁止在子模块自行声明仓库；根 `pluginManagement` 与 `dependencyResolutionManagement` 两处的阿里/腾讯云镜像必须保持一致。
- **JDK Toolchain 自动化**：通过 foojay resolver + `gradle/gradle-daemon-jvm.properties` 拉取 JDK 25，开发者无需本地安装特定 JDK，目标字节码固定 JVM 17（`kotlin.jvmTarget=17`，`sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17`）。
- **独立调试模式安全**：切换单模块独立运行只允许修改 `gradle.properties` 中的 `isModule=true`，禁止使用 `./gradlew -PisModule=true` 命令行覆盖——会渗透进 includeBuild 并导致 `lib_common` 误套 `com.android.application` 插件冲突（见 ADR-0020 相关注释）。
- **清单文件双写**：`src/main/AndroidManifest.xml` 与 `src/main/module/AndroidManifest.xml` 为集成态/独立态两份替换清单，任何新增权限、Activity、Service 需在两处同步增改，否则两种模式下行为分裂。
- **构建产物定位**：约定插件暴露 `printApks` 任务（由 `configurePrintApksTask` 注册），并在 `gradle.properties` 中启用 `ksp.incremental` 与 `ksp.incremental.intermodule=true` 加速增量构建。
- **R8/Minify**：release 变体默认 `isMinifyEnabled=false`，保留 `proguard-rules.pro` 作为后续启用的预留位置。
- **模块应用方式**：除 `module_app` 外，其余功能模块仅在 `!isModule`（集成态）下被 `module_app` 依赖，符合 MVVM 架构“业务模块 → lib_book_common”的单向依赖约定（见 ADR-0015）。

当前仓库未发现 CI Pipeline（GitHub Actions / GitLab CI / Jenkinsfile 等）与 Dockerfile，发布流程未见脚本；版本 bump 语义（fix/feat/BREAKING CHANGE 对应 PATCH/MINOR/MAJOR）由提交规范 Conventional Commits 文档规定而非自动化脚本强制执行。