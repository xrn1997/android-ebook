## 1. 体系概览

本项目使用 **Android Gradle Plugin (AGP) 9.2.1 + Kotlin 2.4.10** 的多模块工程，通过以下三层完成第三方库的版本与来源管理：

- **统一仓库源集中配置**：根 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 与 `pluginManagement.repositories` 集中声明 Maven 仓库，子模块禁止自行声明仓库。
- **依赖版本号集中管理**：所有第三方库版本集中在 `gradle/libs.versions.toml`（版本目录 / version catalog）的 `[versions]` 段，各模块 `build.gradle.kts` 仅以 `libs.xxx.yyy` 引用。
- **构建配置收敛到 Convention Plugins**：`build-logic/` 下提供 `xrn1997.android.application/library/component/compose/lint/room/hilt` 等约定插件，业务模块不手写 AGP/Kotlin/KSP/Hilt 公共设置，只按 `plugins { alias(libs.plugins.xrn1997.*) }` 应用。

## 2. 关键文件

- `gradle/libs.versions.toml` —— 全部版本、别名库声明、[bundles]；构建脚本中不再出现硬编码版本。
- `settings.gradle.kts` —— 定义全局仓库镜像（阿里云 google/central/public/gradle-plugin、腾讯云 maven、Apache snapshots、Sonatype snapshots、Maven Central）、启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`、聚合 `includeBuild("build-logic")`、注册 foojay resolver（自动拉取 JDK toolchain）。
- `build-logic/settings.gradle.kts` —— 将根目录 `gradle/libs.versions.toml` 通过 `versionCatalogs.create("libs") from(files("../gradle/libs.versions.toml"))` 共享给 build-logic 的子工程（`:convention`）。`build-logic` 子模块自己不再维护版本表副本。
- 各模块 `build.gradle.kts` —— 仅引用约定插件 ID、业务源码目录与具体依赖项（如 `implementation(project(":lib_book_common"))`、`implementation(libs.router)`）。

## 3. 架构与约定

### 3.1 仓库镜像策略
为应对国内网络环境对 `dl.google.com` 的访问不稳定，根 `settings.gradle.kts` 在 `repositories` 与 `pluginManagement.repositories` 中统一配置了阿里云 Google 镜像、阿里云 Central/Public/Gradle Plugin 仓库、腾讯云 Maven 仓库、Apache snapshots 与 Sonatype snapshots 作为第一优先级，再回退到 `google()`、`mavenCentral()`、`gradlePluginPortal()`。该策略同时作用于普通依赖与插件下载。

### 3.2 强制中央控制依赖来源
`dependencyResolutionManagement.repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS` 被设置在根 settings 以及 `build-logic/settings.gradle.kts` 两处，使任何模块在自身 `repositories { ... }` 块中加仓库都会导致同步失败；新增仓库必须改根 `settings.gradle.kts`，保证全仓唯一入口。

### 3.3 版本目录（Version Catalog）
`gradle/libs.versions.toml` 统一管理四类内容：
- `[versions]`：单一来源版本号，如 `kotlin=2.4.10`、`hilt=2.60.1`、`retrofit=3.0.0`、`room=3.0.0`、`androidxComposeBom=2026.06.00`、`moduleGraph=2.9.0` 等 ~65 个。
- `[libraries]`：以短名称映射 artifact（`alias(libs.libs.name)` 风格），如 `okhttp-logging`、`room-runtime`、`router`、`hilt-android`、`coil-kt-compose` 等。
- `[bundles]`：相关依赖分组，如 `androidx-compose-ui-test` 包。
- 版本目录通过 `build-logic/settings.gradle.kts` 中的 `versionCatalogs.create("libs")` 注入到 convention 插件中，确保 build-logic 可复用同一份版本表。

### 3.4 依赖传递性约束（API vs Implementation）
`lib_book_common`（聚合层）暴露出对外 API：使用 `api(...)` 暴露 Compose BOM、Coil、Router、Dagger、Jsoup、Retrofit scalars converter、Java 字符集探测（juniversalchardet）、PermissionX 等供上层模块直接引用；内部实现用的库（如 Room runtime）用 `implementation` 限制传递。业务功能模块（`module_book` 等）则普遍用 `implementation(project(":lib_book_common"))` + 自身 `implementation(libs.* )` 组合引入。这体现“共享库决定导出面”的依赖可见性约定。

### 3.5 本地联合开发切换（mini composite build）
`settings.gradle.kts` 中包含一段注释化的 `includeBuild("lib-common-build")` 代码块，使用 `dependencySubstitution` 将 Maven 坐标 `io.github.xrn1997:common` 替换成本地 `:lib_common` 项目，用于与 android-practice（lib_common）联调时即时生效。发布前需要注释掉、回到中央坐标解析；反之联调时需取消注释并配合相对路径（`../../../CodeUp/android-practice`）指向另一个仓库。此方案替代了 `git submodule`，保持依赖声明仍为版本坐标。

### 3.6 工具链与 KSP
构建使用 Gradle 9.4.1，并通过 `org.gradle.toolchains.foojay-resolver-convention` 插件（已在根 `plugins {}` 中以字面值 `1.0.0` 注册——settings `plugins {}` 无法读取版本目录）自动 provision JDK 工具链（无需本地预装）。KSP 通过 `alias(libs.plugins.ksp)` 启用，版本由 libs.versions.toml 中 `ksp=2.3.10` 控制；增量编译开关 `ksp.incremental=true`、`ksp.incremental.log=true`、`ksp.incremental.intermodule=true` 写于根 `gradle.properties`。

## 4. 规则与约束（观察到的显式约束）

- **依赖版本不得在模块 `build.gradle.kts` 中硬编码**：必须由版本目录（`libs.versions.toml`）集中管理，这是 AGENTS.md 的明确指令（“新增依赖必须走版本目录，不要硬编码版本”）。
- **子模块不得声明自己的仓库**：`RepositoriesMode.FAIL_ON_PROJECT_REPOS` 在根 settings 和 build-logic settings 双重开启，违反会在 IDE 同步阶段报错。
- **build-logic 的仓库列表与 root settings 保持一致**：两份文件的仓库顺序/镜像配置高度一致（阿里云 google→google→阿里云 central/public→阿里云 gradle-plugin→jitpack→tencent maven→apache-snapshots→sonatype snapshots→mavenCentral→gradlePluginPortal），避免 convention 插件因仓库差异找不到插件或依赖。
- **外部坐标优先、本地联调可替换**：对上游 `android-practice` 的依赖以 `io.github.xrn1997:common:0.3.1` 坐标为准；仅在本地联调时通过 `includeBuild` + `dependencySubstitution` 临时替换为本地源码，且要求注释化恢复。（见根 `settings.gradle.kts` 注释说明）
- **产物仓库快照源已开放**：同时启用 Apache snapshots（`https://maven.aliyun.com/repository/apache-snapshots`）与 Sonatype snapshots（`https://central.sonatype.com/repository/maven-snapshots/`），允许依赖 `-SNAPSHOT` 版本但需开发者自行维护。
- **独立模块调试开关在 gradle.properties**：`isModule=false` 表示集成态（默认提交值），设为 `true` 后各功能模块可独立编译运行，此时约定插件会将 `src/main/module/AndroidManifest.xml` 作为合并清单。

## 5. 不适用项

本仓库为纯 Android/Gradle 工程，不使用 npm/yarn/pnpm 的 node_modules 与 lockfile、也不采用 Python pip 的 `requirements.txt`/`poetry.lock` 等模式；依赖锁定完全由 Gradle 与所选仓库缓存（无独立的 `*.lock` 文件）。

