## 1. 使用的方法
本项目采用**四层互补的配置来源**，按「编译期→构建时→启动时→运行期」分层管理所有配置：

- **Gradle 版本目录（`gradle/libs.versions.toml`）**：集中声明全仓所有依赖的版本、库引用与 bundle，各模块通过 `libs.xxx.yyy` 引用，禁止硬编码版本号。这是构建阶段的唯一依赖来源。
- **`gradle.properties`（根与子项目）**：注入 Gradle/AGP/KSP 开关（如 `isModule=false`、`android.useAndroidX=true`），并通过 `project.findProperty("isModule")` 在各模块 `build.gradle.kts` 中切换集成模式与独立模式。
- **`local.properties` + `buildConfigField`**：从本地私有文件读取机器级地址注入 `BuildConfig.EBOOK_SERVER_HOST`（默认 `10.0.2.2`），由 `lib_ebook_api/build.gradle.kts` 的 `Properties` 读取后写入 `defaultConfig.buildConfigField`；再被 `API.kt` 的 `BuildConfig.EBOOK_SERVER_HOST` 暴露为常量给网络层消费。同时 `lib_book_common` 用 `buildConfigField("IS_DEBUG", ...)` 区分 debug/release 行为。
- **运行时持久化**：SharedPreferences 封装为 `com.ebook.common.util.SPUtil`（多文件、类型安全 put/get/移除），用于会话、登录态、用户偏好等持久配置；另有 DataStore (`androidx.datastore`) 作为现代偏好的替代方案。

## 2. 关键文件与包

- `gradle/libs.versions.toml`：版本目录，所有第三方依赖版本唯一来源
- `local.properties`：本机私有配置（SDK 路径、`ebook.server.host`），明确注释不得纳入版本控制
- `gradle.properties` / `build-logic/gradle.properties` / `gradle/gradle-daemon-jvm.properties`：构建期全局开关与 JVM 参数
- `lib_ebook_api/build.gradle.kts`：读 `local.properties` 注入 `BuildConfig.EBOOK_SERVER_HOST`
- `lib_ebook_api/src/main/java/com/ebook/api/config/API.kt`：将 BuildConfig 值暴露为 `URL_HOST_*` 常量
- `lib_ebook_api/src/main/java/com/ebook/api/utils/NetworkModule.kt`：把 `BuildConfig.EBOOK_SERVER_HOST` 传给 OkHttp/Retrofit 作为 baseUrl，同时注册 `AuthAllowedHosts` 白名单
- `lib_book_common/build.gradle.kts`：定义 `IS_DEBUG` 构建字段，供 runtime 判断调试能力
- `lib_book_common/src/main/java/com/ebook/common/util/SPUtil.kt`：SharedPreferences 封装，提供多文件、自动类型转换、认证数据专属清理方法
- `module_app/build.gradle.kts`：定义 `network` flavor dimension（`real`/`mock`），用于选择真实后端或内存 mock

## 3. 架构与设计约定

- **配置分层原则**：环境差异（服务器 IP、debug 标志）走 `BuildConfig` 在编译期注入；业务运行态（登录状态、用户偏好）走 `SPUtil`；依赖版本走版本目录。**禁止**在业务代码中以字符串字面量硬编码服务端地址。
- **构建期配置源优先级**：`local.properties` 中覆盖 `ebook.server.host` → 缺省回退到 `10.0.2.2`（模拟器映射宿主机），支持真机局域网联调时仅改 local 即可无需改代码。
- **Flavor + source set 组合**：通过 `productFlavors { real/mock }` 与对应 `src/{real,mock}/` source set 实现“连接真实后端 vs 内存 mock”两种运行模式；独立模式则通过 `gradle.properties` 的 `isModule` 切换，使功能模块可脱离 `module_app` 单独运行。
- **隔离性**：构建期配置（`gradle.properties`、`local.properties`、版本目录）与运行期配置（`SPUtil`、DataStore）严格分离，互不混用；TheRouter 路由表资产、Retrofit URL 等由构建流程生成，不属于运行时编辑配置。

## 4. 规范与约束（来自代码、注释与 ADR）

- `local.properties` 头注释明确要求：**不得纳入版本控制系统**，因包含本机 SDK 路径与服务端地址。
- `gradle.properties` 中关于 `isModule` 的注释强制提交态必须为 `false`，且**禁止使用 `./gradlew -PisModule=true` 命令行覆盖**——该方式会渗入 `settings.gradle.kts` 的 includeBuild，导致 plugin 冲突（已被规则明确标注为坑位并记录 ADR-0020）。
- 依赖版本**只能通过版本目录引入**，不得在 `build.gradle.kts` 内写死版本号（已知结构性豁免仅在 `settings.gradle.kts` 的 `plugins {}` 块中因无法解析版本目录而被允许）。
- 书源请求与更新检查请求必须走专用 OkHttpClient（`@Named("source")`、`@Named("release")`），不携带认证 token，确保非业务服务的配置不影响第三方站点访问（见 AGENTS.md “认证体系约定”）。