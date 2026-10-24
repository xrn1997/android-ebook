# 书源管理面归 module_find，导入管线收进 SourceImportPlan

Status: accepted

书源清单、导入导出、启用禁用与默认源是「找书」的原料，管理页却长期住在个人中心 `module_me`：书城零源空态要跨模块跳，模块独立运行还得造一个占位页。决定把页面、ViewModel、校验器与测试整体迁到 `module_find`，并把原先塞在 ViewModel 里的解析/落库管线抽成 `SourceImportPlan`。

## 决定

1. **管理面跟随领域归 `module_find`**：`BookSourceManageActivity`、`BookSourceViewModel`、`BookSourceValidator` 与对应测试同住 `com.ebook.find.booksource`；module_me 只保留设置页的只读源数入口。理由是管理页的全部动作都作用在书城与搜索的同一张 `book_source` 表上，放在领域模块内可以让「清单谁写、谁读、谁展示」在一处读全；`module_me` 因此回到个人中心 + 设置。

2. **路由改为 `KeyCode.Find.BOOK_SOURCE_PATH`**：设置页仍经 TheRouter 跳转，模块独立运行不再需要 `TestBookSourceActivity` 占位与路径替换。理由是管理页与书城同模块后，集成态与独立态的落点完全一致；保留一条假的跨模块路由只会继续制造「两种运行形态行为不同」的维护面。

3. **导入管线抽成 `SourceImportPlan`**：切顶层 JSON、判出身、解码、结构校验、覆盖判定与逐条落库统计收进该类；`BookSourceViewModel` 只把结果放进状态流、把语义提示翻成文案资源并调用 `reportFailure`。理由是这些判据与页面状态无关，留在 ViewModel 里既难单独测试，也让「VM 只持有状态」失效。脚本原文仍以预览看到的那一份整块入库（零翻译，ADR-0029 决策 1）。

## 被否方案

- **继续留在 `module_me`**：书城零源引导永远要跨模块跳，独立运行永远要靠占位页承接；且书源管理与个人中心没有任何领域交集，留在那里只会让「书源归谁」继续靠注释解释。
- **新建 `module_source`**：当前管理面规模（页面 + VM + 校验器 + 一套导入管线）放 `module_find` 不需要新 Gradle 模块与新的依赖边；等脚本调试、规则编辑等独立能力长出来再拆，成本更低。

## 下游影响

- `module_find` 的两份 Manifest 增加管理页声明，`module_me` 的两份删除；`book_source_*` 字符串随页面迁入 `module_find`。
- `module_me` 独立运行时 `module_find` 不在依赖图里，设置页按 `matchRouteMap` 探测路由，路由缺失时整行不渲染书源入口（不留假入口）。
- 具名测试替身 `FakeBookSourceManager` 从 `module_me` 迁到 `module_find`；设置页改用只实现 `SourceCatalog` 的最小假件。
- 书城零源引导不再提「独立运行占位页」；`KeyCode` 删除 `Me.BOOK_SOURCE_PATH` 与 `Find.TEST_BOOK_SOURCE_PATH`。

锚点：`BookSourceManageActivity`；`BookSourceViewModel`；`SourceImportPlan`；`BookSourceManageScreenRenderTest`；`KeyCode.Find.BOOK_SOURCE_PATH`
