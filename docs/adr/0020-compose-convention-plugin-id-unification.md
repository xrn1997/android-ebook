# compose 约定插件 ID 跨仓统一：删别名留一个 ID，但同 ID 不等于同实现

2026-09-04 与 android-practice 对齐后定下：**compose 约定插件在两个仓库里共用同一个 ID
`xrn1997.android.compose`**，本仓的 `xrn1997.android.library.compose` 及其版本目录条目、以及指向
同一实现类的「兼容别名」注册一并删除。同时明确记录：**统一的只是 ID，不是实现**——本仓版会按
`isModule` 自行套用基础插件，android-practice 版不会，还额外注入 compose ui-test 依赖。

## 背景

- `lib-common-build/` 迷你独立构建只 `includeBuild("../build-logic")`（本仓的 build-logic），却要
  编译 android-practice 的 `lib_common`，而 `lib_common/build.gradle.kts` 应用的是 android-practice
  侧的插件 ID。两仓 ID 不一致时，本仓 build-logic 必须**同时注册两个 ID 指向同一个实现类**才编得过，
  这就是当初「兼容别名」的由来。
- 别名带来两处真实成本：`module_main` 用 `xrn1997.android.library.compose`、`lib_common` 用
  `xrn1997.android.compose`，同一能力两个名字，读者无法判断该用哪个；且 `findProperty("isModule")`
  分支被两个 ID 共享，构成了 `-PisModule=true` 会弄坏复合构建的那条链路（延后项见
  `docs/test-coverage-todo.md`）。
- android-practice 侧已把它的 compose 约定插件定名为 `xrn1997.android.compose`（ID 与类名
  `AndroidComposeConventionPlugin` 均已核对其仓库现状），两边因此可以统一。

## 决策

1. **只留一个 ID**：删除 `gradle/libs.versions.toml` 的 `xrn1997-android-library-compose` 条目与
   `build-logic/convention/build.gradle.kts` 里对应的 `register("androidLibraryCompose")`；实现类改名
   `AndroidComposeConventionPlugin`（与 android-practice 侧类名一致）；`module_main` 改应用
   `xrn1997.android.compose`。
2. **`isModule` 分支本轮保留，不顺手根治**：`module_main` 现在同时应用
   `xrn1997.android.component`（本就按 isModule 套基础插件）与本插件，Gradle 按 ID 应用插件是幂等的，
   因此这处自套对 `module_main` 已属冗余；但它仍是「只挂 compose 插件」的模块能独立运行的唯一入口。
   根治方向（compose 插件不再自套基础插件、只加 compose 能力）留在 `docs/test-coverage-todo.md`，
   不在本次清理里做——它改变的是所有下游模块的插件应用契约，值得单独一次提交与回归。
3. **差异必须写在读者会看到的地方**，不只留本 ADR：`AndroidComposeConventionPlugin` 类 KDoc、
   `build-logic/convention/build.gradle.kts` 注册处注释、`lib-common-build/settings.gradle.kts` 头部
   注释三处都注明「同 ID 不同实现」。

## 权衡

- **统一 ID 优于保留别名**：别名把「两仓共用一套 build-logic」这件事藏进一次隐式映射，改名后
  至少名字是一致的；代价是「同名」会诱使读者以为换到哪个构建语义都一样，这是新引入的风险。
- **新风险的兜底**：今天它不产生故障，靠的是两条事实——`lib_common` 自身已应用
  `xrn1997.android.library`，所以本仓版再套一次是幂等而非冲突；`lib_common` 无 `src/androidTest`，
  所以本仓版没注入 compose ui-test 依赖也无人受害。任一条变化（例如 android-practice 给
  `lib_common` 加 Compose UI 测试），本仓版就会缺依赖，届时按 `docs/test-coverage-todo.md` 的根治方向
  把 compose 插件收敛成「只加 compose 能力」，而不是再造一个别名。
- **`-PisModule=true` 的坑当前处于休眠**：根 `settings.gradle.kts` 的 `includeBuild("lib-common-build")`
  现为**注释态**（lib_common 走 `io.github.xrn1997:common` 中央坐标），所以 `-P` 覆盖眼下不复现。
  `AGENTS.md`、`gradle.properties`、本 ADR 三处都按「恢复联动后即复现」的前提表述，避免读者拿当前
  配置去试之后反过来怀疑文档写错。

## 同批构建清理

- `KotlinAndroid.kt` 去掉 `-Xconsistent-data-class-copy-visibility`：Kotlin 2.4 起该行为已是默认，
  旗标只留下噪声。全仓无 `@ConsistentCopyVisibility` 注解、无私有构造 data class，删除不影响任何
  `copy()` 调用点。
- `build-logic/convention/build.gradle.kts` 中 `libs.plugins.xrn1997.android.library.asProvider().get()`
  改为 `.get()`：该别名条目删除后不再存在多候选，`asProvider()` 是多余的防御。

## 下游影响

- 任何新模块需要 Compose 时一律 `alias(libs.plugins.xrn1997.android.compose)`，不要再引入按 UI
  类型拆分的插件 ID。
- 恢复 `lib-common-build` 联动（取消 `includeBuild` 注释）时，`lib_common` 会用到本仓实现，需先确认
  上述两条兜底事实仍成立。
- 本 ADR 不引用其他仓库的 ADR 编号（见 `AGENTS.md`「跨仓库 ADR 编号」），对侧现状的核对锚点是
  2026-09-04 当时 android-practice 工作副本里的 `gradle/libs.versions.toml` 与
  `build-logic/convention/build.gradle.kts`。
