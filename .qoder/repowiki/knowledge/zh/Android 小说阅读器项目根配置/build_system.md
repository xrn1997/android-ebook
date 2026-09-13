## 1. 构建系统与工具链

本项目是 **Android 多模块 Gradle 工程**，基于 **AGP + Kotlin 2.x（AGP 内置 Kotlin）**，使用 `gradlew` 作为统一入口。根目录的 `settings.gradle.kts` 通过 `includeBuild("build-logic")` 启用独立子构建，所有业务模块通过 `build-logic/convention/` 中自定义的 `xrn1997.*` 约定插件装配，而非各自散落配置。仓库使用 **Gradle Version Catalog**（`gradle/libs.versions.toml`）集中管理全部依赖版本、库别名与插件 ID，模块级 `build.gradle.kts` 仅引用 `libs.xxx.yyy`，禁止硬编码版本号。

构建环境由 `gradle.properties` 控制：JVM 参数 `-Xmx2048M`，KSP 增量编译开启；`gradle/gradle-daemon-jvm.properties` 配合 `org.gradle.toolchains.foojay-resolver-convention` 自动拉取 JDK toolchain（当前为 JDK 25），无需本地预装。仓库镜像源以阿里云为主（google/central/public/gradle-plugin），辅以 jitpack、tencent、sonatype snapshots。

## 2. 核心文件与包

- **根构建配置**：`settings.gradle.kts`（仓库声明、`dependencyResolutionManagement.FAIL_ON_PROJECT_REPOS` 强制集中化）、`build.gradle.kts`（顶层插件声明）、`gradle/libs.versions.toml`（版本目录）、`gradle.properties`（全局属性）。
- **约定插件工程**：`build-logic/settings.gradle.kts`、`build-logic/build-logic/convention/src/main/kotlin/` 下各 ConventionPlugin：
  - `AndroidApplicationConventionPlugin`：应用 AGP application + lint，设置 targetSdk=37、applicationId=namespace、Managed Devices。
  - `AndroidLibraryConventionPlugin`：应用 AGP library + lint，注入 junit/kotlin-test/tracing，禁用不必要的 androidTest。
  - `AndroidComponentConventionPlugin`：**双态开关核心**——`isModule=true` 时按 application 处理并切换 manifest 到 `src/main/module/`，同时把 `src/main/test` 加入 Kotlin 源码目录供独立调试；`isModule=false` 时按 library 处理。
  - `HiltConventionPlugin`、`AndroidRoomConventionPlugin`、`AndroidComposeConventionPlugin`、`AndroidLintConventionPlugin`：分别为 Hilt/KSP、Room、Compose、Lint 提供统一装配。
  - `com.xrn1997.convention/*`：`KotlinAndroid.kt`、`AndroidInstrumentedTests.kt`、`GradleManagedDevices.kt`、`PrintTestApks.kt`、`ProjectExtensions.kt`、`AndroidCompose.kt`。
- **模块构建脚本**：每个 `module_*` / `lib_*` 目录下 `build.gradle.kts` 仅声明 flavor、buildType、依赖与少量差异化配置。
- **原生构建脚本**：`scripts/build-desktop-js.sh` 用于在开发机用 MinGW-w64/gcc 编译桌面版 QuickJS 动态库（`ebook_js.dll` / `libebook_js.so`），供 JVM 单测加载。
- **Git Hook**：`scripts/install-hooks.sh` 安装 `commit-msg` 钩子，校验 Conventional Commits 格式。

## 3. 架构与约定

### 3.1 模块化与双态构建

仓库包含 9 个模块：`module_app`（聚合入口）、`module_main`、`module_book`、`module_find`、`module_me`、`module_login`、`lib_book_common`、`lib_book_source`、`lib_ebook_api`、`lib_ebook_db`。依赖方向严格单向：业务模块 → lib_book_common → (lib_book_source | lib_ebook_api | lib_ebook_db)。

**`isModule` 双态**是构建体系的枢纽：
- `isModule=false`（默认，提交态）：功能模块以 library 形式被 `module_app` 聚合，`module_app` 显式依赖所有功能模块。
- `isModule=true`（独立调试态）：功能模块自行作为 application 运行，manifest 切到 `src/main/module/`，并把 `src/main/test/debug/` 下的测试宿主并入 main source set 提供 mock 路由。

该开关通过 `gradle.properties` 中的字面量切换，**禁止通过命令行 `-PisModule=true` 覆盖**（会渗入 settings 的 includeBuild，导致 lib_common 冲突）。

### 3.2 Flavor 与 Build Type

`module_app` 定义 `network` flavor dimension：`real`（真实后端）与 `mock`（内存 mock，`applicationIdSuffix=.mock`）。各模块通过 `src/mock/` 与 `src/real/` source set 提供不同实现（如 `NetworkModule`）。Release 构建开启 R8 混淆（`module_app` 恒开，功能模块独立态开启以尽早暴露规则缺口）。

### 3.3 原生构建（:js 沙箱）

`lib_book_source` 通过约定插件启用 C++ 构建：NDK/CMake 版本钉死在版本目录，release 仅输出 `arm64-v8a` ABI（减少可研究内核副本）。模拟器调试所需的 `x86_64` 由模块自身 debug 配置放宽，使「谁多带了一份内核」可见。QuickJS 内核 vendored 于 `third_party/quickjs/`，JNI 桥位于 `src/main/cpp/bridge/`。桌面版编译走 `scripts/build-desktop-js.sh`，产出 DLL/SO 供 JVM 测试加载。

### 3.4 依赖与插件管理策略

- 所有依赖版本集中在 `gradle/libs.versions.toml`，模块内只写 `libs.xxx.yyy`。
- `dependencyResolutionManagement.repositoriesMode = FAIL_ON_PROJECT_REPOS`：禁止在模块内声明仓库，统一在根 settings 管理。
- 插件通过 `plugins {}` 块声明（alias from version catalog），模块仅 `apply` 自定义 `xrn1997.*` 约定插件。
- 外部共享库 `lib_common`（android-practice）默认走 Maven 坐标；本地联调通过注释掉 settings 中的 `includeBuild("lib-common-build")` 切换回源码集成。

### 3.5 测试构建

单元测试使用 JUnit 4，位于各模块 `src/test/java`；插桩测试位于 `src/androidTest/java`。`AndroidLibraryConventionPlugin` 在存在 `androidTest` 时才注入测试依赖以避免警告。`module_book` 等模块启用 Robolectric（含 Compose UI 渲染回归测试）和 Baseline Profile。

## 4. 约束与规范

- **提交态 `isModule` 必须为 `false`**：否则 `module_app` 不依赖任何功能模块，产出的 APK 为空壳。
- **禁止模块内声明仓库或依赖版本**：违反 `FAIL_ON_PROJECT_REPOS` 将直接失败。
- **ABI 口径受控**：release 仅 `arm64-v8a`；debug 放宽需写在模块而非插件，确保 diff 可见。
- **Manifest 双份替换**：独立态用 `src/main/module/AndroidManifest.xml`，集成态用 `src/main/AndroidManifest.xml`，两处 Activity 声明必须同步。
- **混淆规则归属**：consumer 规则放 `consumer-rules.pro`（随 AAR 传播），模块级 `proguard-rules.pro` 仅含 `-include consumer-rules.pro`；纯 library 模块只需 `consumer-rules.pro`。
- **目标 SDK 与 JDK**：targetSdk=37，Java/Kotlin jvmTarget=17，由约定插件统一设定。
- **Git Hook 强制格式**：`scripts/install-hooks.sh` 安装的 `commit-msg` 钩子强制 Conventional Commits 结构、type 白名单、description 长度与标点。
