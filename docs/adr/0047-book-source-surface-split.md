# 书源接口按调用方拆分：四个窄面 + 一个聚合类型

Status: accepted

`BookSourceManager` 曾把取源、清单订阅、管理写路径与 JSON 编解码全塞在一张接口上，每个测试替身为此都要实现一整张面，而 `getEnabledSources` 与 `getSourceByUrl` 已无任何生产调用方。决定按调用方把这张面拆成 `SourceResolver` / `SourceCatalog` / `SourceAdmin` / `SourceCodec` 四个窄接口，`BookSourceManager` 只作四者的聚合类型，生产消费者注入最窄的一面，两个零调用方方法删除——接口的宽度由「谁需要它」决定，而不是由「实现里恰好有什么」决定。

## 背景

- 取 parser、清单订阅、管理写路径与 JSON 编解码共用一个接口，而书源管理页之外的生产调用只碰其中少数几个方法。宽接口让每个注入点都声明自己依赖整张面，新增一个方法就自动成为所有调用方的契约。
- 测试的代价更直接：具名替身与内联替身都要实现大把用不到的成员，形态只能是 `TODO()`/`unsupported`。替身越写越像「假的实现类」，测试想锁的行为被淹没在噪音里。
- `getEnabledSources` 与 `getSourceByUrl` 没有生产调用方，只有测试直接调用。它们不是能力，只是接口宽度带来的惯性；留着就还得有替身成员、KDoc 与测试去维护。

## 决策

1. **按调用方拆成四个窄接口，每个面自带它那部分 KDoc 与不变量**：`SourceResolver`（取 parser、聚合搜索、分类入口）、`SourceCatalog`（清单/默认源订阅、格式查询）、`SourceAdmin`（增删、启停、设默认）、`SourceCodec`（JSON 导入导出）。理由：一个注入点声明它真正调用的能力，读者从构造参数就能读出这个类在书源体系里扮演什么角色；四档取源、默认源现算、REPLACE 语义等约束跟着方法走，不再挤在同一份 KDoc 里。
2. **`BookSourceManager` 保留为聚合类型，生产消费者注入最窄的那一面**：聚合只留给需要整张面的测试，既无生产调用方，也没有自己的 Hilt 绑定。四个窄面各自 `@Binds @Singleton` 到同一个 `BookSourceManagerImpl`——留一条聚合绑定等于给宽接口留一条注入口，生产代码会自然而然地继续用它。
3. **测试替身收窄到测试实际调用到的面**：替身只实现被调用的成员，未实现的成员照旧抛异常。理由：测试替身的面就是测试真实需求的面；少实现一个方法会编译失败（好抓），多实现一个面只会悄悄退回宽替身。
4. **删除 `getEnabledSources` 与 `getSourceByUrl`**：需要「启用的原生规则」用 `getAllSources()` 按实体列过滤，需要单行规则用 `getAllSources()` 里按 url 取；脚本行本来就不在规则类型化读面里，条目断言走 `observeSources()`。零调用方的方法留着只会让下一个人以为它们是入口。
5. **不拆实现类**：四个面仍是同一个 `BookSourceManagerImpl` 的一份实现。理由：它们共享 Room 访问、parser LRU 与默认源现算，拆成四个类会把同一份状态复制四份或引入跨类调用；这次拆的边界是「谁可以调用哪一面」，不是「哪段逻辑住哪里」。

## 被拒方案

- **保留宽接口，只按关注点重排 KDoc**：接口宽度是测试替身与注入点被迫接受整张面的根因；整理注释不改变任何依赖关系，零调用方的方法也仍然在册。
- **把实现类拆成四个类**：四个面读写的状态（parser LRU、默认源、写路径的失效）高度重叠，拆类要么复制状态、要么让四个类互相调用；收益只是文件更小，代价是「同一份清单有四个持有者」。
- **为旧方法留一条 `@Deprecated` 包装**：两个方法已无生产调用方，包装只会把删除节点往后挪，同时继续要求每个替身实现它。

## 下游影响

- 生产注入点全部改注入窄面：取源/搜索/分类路径注入 `SourceResolver`，书城注入 `SourceCatalog` + `SourceAdmin`，设置页注入 `SourceCatalog`，书源管理页另加 `SourceAdmin`/`SourceCodec`；方法体与页面行为不变。
- 具名与内联测试替身只实现各自实际调用的面；装饰器式替身（`SourceBindingTest` 的匿名委托、`ReadingProgressFlowTest` 的 `Proxy`）随之收窄到 `SourceResolver`。
- `BookSourceManagerImplTest` 仍以聚合类型测试实现本身；原先直接调用被删方法的用例改用 `getAllSources()` / `observeSources()` 断言，锁住的语义（实体列覆盖、禁用不切断归属、脚本行不进规则面）不变。
- Hilt 原有的聚合绑定由四个窄面绑定取代：四者共享同一个 `@Singleton` 实现，parser 缓存与默认源仍只有一份。
- `docs/architecture.md`、ADR-0016/0029 与相关 KDoc 的方法名同步改指四面；聚合类型只作为测试入口存在，生产代码不注入它。

锚点：`SourceResolver`；`SourceCatalog`；`SourceAdmin`；`SourceCodec`；`BookSourceManagerImplTest`
