# 多书源共存：从「全局单一书源」到「每本书绑源」

将 App 从**单书源架构**（全局一个 `currentSource`，所有解析路径共用）升级为**多书源共存架构**：
书源作为**运行时可管理数据**（Room 表 + 用户导入/启用/禁用/删除），每本书架书**按** **`tag`** **字段绑定归属书源**
（该字段一直存在，语义从「隐式指向唯一书源」显式化为「本书归属的书源 URL」），搜索改为**多书源并发聚合**，
书城/书架/阅读器/下载**按书找 parser**，阅读器额外支持\*\*「阅读中换源」\*\*（按书名+作者跨源匹配 + 章序号映射进度）。

## 落地状态

本 ADR 为**方案记录，尚未实现**：当前代码仍是单书源架构，书架/阅读/下载/搜索/书城各解析路径统一走
`BookSourceManager.requireParser()`（默认书源 parser），`requireParser()` 未废弃、仍是主路径。本 ADR 与
`docs/multi-source-plan.md` 描述的 `getParserFor`/`searchAcross`/`observeDefaultSource`/`BookSourceNotFoundException`、
Room `book_source` 表、schema v3 迁移等**均尚未落地**；`BookSourceManager` 现有的 `importFromJson`/`exportToJson`/
`switchSource`/`saveCurrentSource` 是为该规划预留的脚手架，暂无业务调用方。实施前请勿按多书源现状描述相关模块。

## 动机

- **能力已实现却无入口**：`BookSourceManager` 接口早就定义了 `switchSource`/`importFromJson`/`exportToJson`/
  `saveCurrentSource`，`BookSourceManagerImpl` 也实现了 SharedPreferences 恢复上次书源，但业务代码
  **无任何调用方**（除内部 `switchSource`），处于「接线完毕、待接 UI」的死代码状态。

- **单书源风险高**：`default_sources.json` 目前只有 1 条（笔趣阁）。该站点一旦挂掉或改结构，
  搜索/书城/书架刷新/下载/阅读全部瘫痪，无 fallback。

- **架构与语义错位**：`BookShelfEntity.tag` / `BookInfoEntity.tag` / `ChapterListEntity.tag` /
  `BookContentEntity.tag` / `DownloadChapterEntity.tag` 一直由 `JsoupBookParser` 写入 `rule.url`，
  事实上**已经是每本书/每章/每段缓存的书源归属标记**，只是过去恰好只有一个书源所以恒定不变。
  数据层已具备多书源共存的表达能力，Manager 层强行收敛成单 parser 反而**浪费了这份能力**。

- **书源管理是阅读类 App 的必备能力**：成熟的阅读 App 均以「书源可导入、可切换、可共存」为核心卖点，
  本项目定位安卓小说阅读器，缺此能力等于产品力硬伤。

## 决策

1. **数据模型：`tag`** **字段显式承担「书源归属」语义，不新增列**
   `BookShelfEntity` / `BookInfoEntity` / `ChapterListEntity` / `BookContentEntity` /
   `DownloadChapterEntity` 的 `tag` 字段（值均为书源 URL）**保持不变**，仅在文档与 KDoc 中显式化其语义。
   **零 Migration 风险**：老用户升级时 `tag` 值本就是当前唯一书源 URL，天然指向默认书源。

2. **Room schema v2 → v3：仅新增** **`book_source`** **表**

   ```
   BookSourceEntity(
       url: String           @PrimaryKey   // 书源 URL，天然唯一
       name: String                       // 显示名
       ruleJson: String                   // 整个 BookSourceRule 序列化（避免规则字段变更时改表）
       enabled: Boolean                   // 启用/禁用
       weight: Int                        // 排序权重（越小越靠前）
       group: String                      // 分组（如「小说」）
       isUserImported: Boolean            // true=用户导入（可删）；false=默认书源（不可删可禁用）
       addedAt: Long                      // 导入时间戳
   )
   ```

   Migration 只做 `CREATE TABLE`，无破坏性变更；`ruleJson` 存整段 JSON 而非拆列，规则字段扩展时不改表。

3. **书源清单加载：assets 默认源 + Room 用户源合并**
   `BookSourceManagerImpl` init 时：

   - 从 assets `default_sources.json` 读默认源，**首次启动**写入 Room（`isUserImported=false`）；
     后续启动跳过（默认源已在库中，避免覆盖用户对默认源的启用/禁用/权重调整）。

   - 从 Room 读全部书源，按 `weight ASC, addedAt ASC` 排序，得到内存清单。

   - SharedPreferences `KEY_CURRENT_SOURCE` 语义收敛为「**书城/默认搜索书源**」（阅读/下载不再依赖它）。

4. **`BookSourceManager`** **API 重构（保留旧 API 兼容性）**
   新增能力：

   ```
   fun getAllSources(): List<BookSourceRule>            // 已存在，改为读 Room
   fun getEnabledSources(): List<BookSourceRule>        // 已存在
   fun getSourceByUrl(url: String): BookSourceRule?     // 新增：按 URL 查
   fun getParserFor(sourceUrl: String): BookParser?     // 新增：按 URL 拿 parser（LRU 缓存 3 个实例）
   fun addSource(rule: BookSourceRule): Result<Unit>    // 新增：Room 插入（url 冲突拒绝）
   fun removeSource(url: String): Result<Unit>          // 新增：Room 删除（默认源拒绝）
   fun setEnabled(url: String, enabled: Boolean)        // 新增：Room 更新
   fun observeSources(): Flow<List<BookSourceRule>>     // 新增：书源列表 Flow（UI 订阅）
   fun observeDefaultSource(): Flow<BookSourceRule?>    // 新增：默认书源 Flow（书城订阅）
   fun setDefaultSource(url: String)                    // 新增：写 SharedPreferences
   fun searchAcross(keyword: String, page: Int): Flow<AggregateSearchEvent>  // 新增：聚合搜索
   ```

   保留：`currentSource`（作为「默认书源」的同步快照，仅供书城/兜底使用）；
   **废弃**：`requireParser()` —— 改为兜底返回「默认书源 parser」，标 `@Deprecated`，
   业务代码全部改为 `getParserFor(entity.tag)`，迁移完成后删除。

5. **业务解析路径按** **`tag`** **走独立 parser**
   10 处 `bookSourceManager.requireParser()` 全部改造：

   - `BookShelfManager.addFromSearch`：`getParserFor(searchBook.tag)`

   - `BookDetailViewModel.refreshBook/refreshChapterList`：`getParserFor(bookShelf.tag)`

   - `BookReadViewModel.getBookContent`：`getParserFor(bookShelf.tag)`

   - `DownloadService.downloading`：`getParserFor(data.tag)`

   - `BookSourceRepository.getKindBook/getLibraryData`：`getParserFor(currentSource.url)`（书城仍用默认书源）

   - `SearchViewModel.searchBook`：改用 `searchAcross`（见决策 6）
     找不到 parser（书源已被用户删除）时抛 `BookSourceNotFoundException`，UI 提示「书源已失效，请重新导入或换源」。

6. **聚合搜索：`flatMapMerge`** **并发 + 每源独立分页游标 + 结果打标签**

   ```
   fun searchAcross(keyword: String, page: Int): Flow<AggregateSearchEvent>
   ```

   实现要点：

   - 对**所有启用书源**用 `flatMapMerge(concurrency = 5)` 并发调用 `parser.searchBook(keyword, page)`；
     concurrency 上限 5 避免同时打爆 10+ 站点触发风控。

   - 每个书源**独立分页游标**（VM 内 `Map<sourceUrl, Int>`），某书源返回空只标记该书源
     `hasMore=false`，不影响其他书源继续翻页。

   - 事件流：`AggregateSearchEvent` = `SourceStarted(url)` / `SourceResult(url, books)` /
     `SourceFailed(url, err)` / `SourceFinished(url)` / `AllFinished`；
     UI 层按需渲染「已收到 X/Y 书源结果」进度条。

   - 去重策略：按 `noteUrl` 全局去重（同一 URL 只可能出现一次）；不按 name+author 去重
     （不同书源的同名书是**有效备选**，交给用户在换源时选择）。

   - 每条结果自带 `origin`（书源名）与 `tag`（书源 URL），UI 用 `InfoChip` 显示书源标签。

7. **书城：顶部书源切换器 + 缓存按源分区**

   - `LibraryViewModel` 新增 `currentSource: StateFlow<BookSourceRule?>`（订阅 `observeDefaultSource`）
     与 `sources: StateFlow<List<BookSourceRule>>`（订阅 `observeSources`）。

   - UI 顶部加书源切换胶囊（复用 `CommonCard` + 下拉菜单），切换后调 `setDefaultSource(url)`
     → SharedPreferences 更新 → `LibraryViewModel` 重新拉书库数据。

   - `ACache` 的书库缓存 key 从固定 `LIBRARY_CACHE_KEY` 改为 `"$LIBRARY_CACHE_KEY:${rule.url}"`，
     每书源独立缓存，切换书源立即命中已有缓存或触发新拉取。

8. **阅读中换源：按书名+作者跨源匹配，章序号映射进度**

   - 阅读器菜单/目录页新增「换源」入口（`ReadBookActivity`）。

   - 弹出 `SourceSwitchSheet`（Compose `ModalBottomSheet`）：

     1. 用当前书 `bookInfo.name` + `bookInfo.author` 调 `searchAcross`（**排除当前书源**）；
     2. 结果按匹配度排序：完全匹配 (name==) > 部分匹配 (name.contains) > 作者匹配 (author==)；
     3. 用户选中一条 → 触发换源流程。

   - 换源流程（`BookRepository.switchSource(bookShelf, newSearchBook)`，事务）：

     ```
     1. 记录旧 durChapter (章序号)
     2. 用新 parser 拉取 newSearchBook.noteUrl 的 BookInfo + ChapterList
     3. 删除旧 BookShelfEntity (主键 note_url)
     4. 插入新 BookShelfEntity:
          noteUrl        = 新 noteUrl
          tag            = 新 tag（新书源 URL）
          durChapter     = min(旧 durChapter, 新章节总数 - 1)   // 章序号映射，越界回落到末章
          durChapterPage = 0                                    // 页级不跨源（不同书源分页规则不同）
          finalDate      = 保留（阅读时间不变）
     5. 章节列表按新 noteUrl 落库；旧书源的 ChapterList/BookContent **保留**
        （用户可能切回；缓存本来就按 dur_chapter_url 全局唯一，无冲突）
     6. 发 BookShelfEvent.Removed(旧) + BookShelfEvent.Added(新)
     ```

   - 章序号映射是**有损转换**：不同书源章节划分可能不一致（分章/合章/番外顺序），
     极端情况会偏 1-3 章。已接受此代价（比"从第一章重开"体验好，比"全文匹配定位"实现成本低）。

9. **书源设置页（`module_me`）**

   - 新增 `BookSourceManageActivity`，路由 `/ebook/me/book_source`（`KeyCode.Me.BOOK_SOURCE_PATH`）。

   - `SettingActivity` 通用组新增「书源管理」入口行（`Icons.Outlined.Source` 图标 + 当前书源数副标题）。

   - UI：`CommonCard` + `CommonListItem` 列表，每项含：书源名 / URL 副标题 / 启用 Switch /
     默认源标识（`InfoChip` 「默认」）/ 长按或滑动删除（默认源禁用删除）。

   - 顶部按钮：「导入」（SAF `OpenDocument` 选 JSON 文件）、「导出全部」（SAF `CreateDocument` 写文件）。

   - 导入流程：

     1. 读文件 → `Json.decodeFromString` 自适应单条或数组（先试数组，失败退单条）；
     2. **结构校验**：`name` 非空、`url` 合法 http(s)、`searchUrl` 或 `ruleFind.url` 至少一个非空、
        `ruleSearch.list` 与 `ruleContent.content` 均非空（否则书源无法完成核心链路）；
     3. **预览弹层**：展示待导入书源清单（名称 / URL / 校验结果），已存在的 URL 标记「将覆盖」；
     4. 用户确认 → `addSource` 逐条落 Room（`isUserImported=true`），冲突策略 REPLACE；
     5. Toast 反馈「导入成功 X 条 / 覆盖 Y 条 / 失败 Z 条（校验未通过）」。

   - 校验粒度：**结构校验 + 导入前预览**（不做完整的书源连通性测试跑搜索/详情/目录/正文，
     MVP 阶段代价过高；用户可自行搜索验证）。

10. **文档与术语同步**

    - CONTEXT.md 新增术语：**默认书源**（Default BookSource）、**用户导入书源**（User-Imported BookSource）、
      **多书源共存**（Multi-Source Coexistence）、**聚合搜索**（Aggregated Search）、
      **阅读中换源**（In-Reader Source Switch）、**书源归属标记**（`tag` 字段的显式语义）。

    - AGENTS.md「Agent 实战建议」新增一条：涉及书源解析的代码，先按 `entity.tag` 找 parser，
      不再默认走全局 `currentSource`；`requireParser()` 已废弃，新代码不得调用。

## 权衡

- **`tag`** **复用 vs 新增** **`source_url`** **列**：复用 `tag` 零 Migration、老数据天然对齐；代价是 `tag`
  语义从「泛化标签」显式化为「书源 URL」，需要 KDoc 与 CONTEXT.md 补充说明。新增列 Migration
  风险更大（老数据兜底填什么？若填错等于把书归到错误书源）。选前者。

- **聚合搜索 vs 单选书源搜索**：聚合搜索用户体验好（一次搜全站），代价是并发控制、去重、分页游标
  管理复杂，且**同时对多个第三方站点发请求可能触发风控**。选聚合搜索但并发上限压到 5，
  并保留每源独立分页游标（某源失败不影响其他源）。

- **章序号映射 vs 全文匹配定位**：全文匹配（拿旧源当前章正文前 500 字到新源全章搜索）精度高，
  但需要拉全量章节内容，成本高、耗时长。章序号映射实现简单、瞬时完成，代价是可能偏 1-3 章。
  选章序号映射（用户可通过目录手动微调）。

- **旧缓存保留 vs 清理**：换源后旧 `ChapterList`/`BookContent` 保留（按 URL 全局唯一无冲突），
  代价是数据库体积膨胀；清理省空间但用户切回时要重拉。选保留（存储成本 << 网络重拉成本）。

- **`BookSourceEntity`** **表存** **`ruleJson`** **整段 vs 拆列**：整段 JSON 灵活，规则字段扩展不改表；
  代价是无法用 SQL 直接过滤规则内字段（如「找所有支持 POST 搜索的书源」）。当前无此类查询需求，选整段。

## 被拒方案

- **只做「全局书源切换」（不共存）**：用户切书源后原有书架全部打不开，等于每次换源清空书架，
  体验灾难。且 BookSourceManager 已有能力被闲置的问题仍未解决。

- **书源存 SharedPreferences**：整段 JSON 数组塞 SP，脏、难查询、难迁移。与 ADR-0003 的 Room 收敛方向相悖。

- **书源存内部文件** **`book_sources.json`**：介于 SP 与 Room 之间，自己维护序列化/读写/并发，
  重复造 Room 已经解决的轮子。

- **完整级书源连通性测试**（导入前跑一遍搜索/详情/目录/正文）：MVP 阶段代价过高（网络请求 + 结果比对 + UI），
  且用户导入的书源大多来自社区已验证的 JSON。留到 P4 按需迭代。

- **网络订阅 URL 一键导入**（其他阅读 App 的「书源订阅」）：需要额外做订阅管理、去重、更新检查、失败重试。
  MVP 只做本地文件导入，网络订阅留到 P4。

- **换源时清理旧缓存**：见权衡第 4 条，被拒。

## 下游影响

- **`lib_ebook_db`**：新增 `BookSourceEntity` + `BookSourceDao`；`AppDatabase` version 2→3；
  `DatabaseModule` 加 `MIGRATION_2_3`；`schemas/com.ebook.db.AppDatabase/3.json` 提交入库。

- **`lib_book_common`**：`BookSourceManager` 接口扩展（新增 7 个方法，`requireParser` 标废弃）；
  `BookSourceManagerImpl` 重写（Room 加载 + LRU parser 缓存 + 聚合搜索）；
  新增 `BookSourceNotFoundException` / `AggregateSearchEvent`；`BookRepository` 新增 `switchSource`；
  `AnalyzeModule` 保持 `@Binds`（无需改）。

- **`lib_ebook_api`**：`ACache` 使用方（`JsoupBookParser.getLibraryData`）改用参数化 cache key。

- **`module_find`**：`SearchViewModel` 从单书源分页改为聚合搜索（消费 `searchAcross`）；
  `LibraryViewModel` 加 `currentSource`/`sources` 状态与 `switchSource` 方法；
  搜索/书城 UI 加书源标签与顶部切换器。

- **`module_book`**：`BookReadViewModel`/`BookDetailViewModel`/`DownloadService` 按 `tag` 找 parser；
  `ReadBookActivity` 新增「换源」入口与 `SourceSwitchSheet`。

- **`module_me`**：新增 `BookSourceManageActivity` + `BookSourceViewModel`；
  `SettingActivity` 加书源管理入口；`KeyCode.Me` 加 `BOOK_SOURCE_PATH`；
  新增字符串资源（导入/导出/校验失败/覆盖确认等）。

- **文档**：CONTEXT.md 补 5 个术语；AGENTS.md 补一条 Agent 实战建议；
  实施路线图沉淀到 `docs/multi-source-plan.md`（阶段划分、每步交付物、验证方式）。

- **测试**：`BookSourceManagerImplTest` 覆盖 Room 加载/parser 缓存/聚合搜索去重/换源匹配；
  `BookRepositoryTest` 覆盖 `switchSource` 事务；DAO 测试覆盖 `BookSourceDao` CRUD。

- **兼容性**：老用户升级后 `tag` 值天然指向默认书源（笔趣阁），书架/缓存/下载全部可用，
  零手工迁移；`requireParser()` 保留兜底为默认书源 parser，未改造完成的调用点仍可运行。

## 遗留（P4 按需迭代，本轮不做）

- **网络订阅 URL 导入**：一键拉取远程 JSON 数组，需要订阅管理/去重/更新检查。

- **完整书源测试**：导入前跑一遍搜索/详情/目录/正文，逐步打勾验证规则正确性。

- **跨源换源的智能匹配**（内容前 500 字全文搜索定位）：精度高于章序号映射，代价高。

- **书源分组管理**（`group` 字段已存在但 UI 未暴露）：书源超过 20 条后按分组折叠展示。

- **书源权重拖拽排序**（`weight` 字段已存在但 UI 未暴露）：MVP 用列表上下移动按钮替代。

- **多书源聚合的书城**（当前书城仍单书源展示）：可选「聚合书库」模式，各书源分类合并展示。

- **书源导入失败原因详情**（当前只 Toast 计数）：可展开每项校验失败的具体原因。

