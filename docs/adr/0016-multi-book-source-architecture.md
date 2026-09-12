# 多书源共存：从「全局单一书源」到「每本书绑源」

成稿时 App 为单书源架构（全局一个 `currentSource`，所有解析路径共用）。本 ADR 决定升级为多书源共存架构：书源作为运行时可管理数据（Room 表 + 用户导入/启用/禁用/删除），每本书架书按 `tag` 字段绑定归属书源，搜索改为多书源并发聚合，阅读器额外支持「阅读中换源」——理由是当前单书源（笔趣阁）一旦挂掉全站瘫痪无 fallback，且数据层的 `tag` 字段已天然承载书源归属语义，Manager 层强行收敛成单 parser 反而浪费了这份能力。

体系今天已完全落地，并在此之上长出了第二种书源格式（脚本书源，见「决策 4」的默认源载体一节）：本文的处方按**现状**描述，成稿后收缩或改形的原写法不再逐处保留。

### 事实更新（2026-09-11）：应用不随包携带书源，「内置源」一族机制整体下线

**本文以下把「内置默认源」写成现状的段落均已失效**。应用现**不随包携带任何书源**，书源清单的唯一事实源是 `book_source` 表、每一行都由用户导入。随之删除的是一整族互为前提的机制：assets 的内置清单与首启灌库（`ensureSeeded()`）、`is_user_imported` 列与整套「内置源不可删」保护（删除路径不再带保护条件，任何一行都可删）、同步属性 `currentSource` 与内存中的默认源快照（连同「默认源的 parser 不进 LRU」那条特殊待遇）。默认源改为**每次从 Room 现算**（SP 命中的启用行 → 否则第一条启用行，两种格式平等），`observeDefaultSource()` 是它唯一的出口，SP 只承载「用户上次主动选了哪个」这份记号。直接后果是默认源没有同步初值：书城首帧落进一个「还不知道」的档位（整片留空），而不是「先猜一个再由库纠正」。读到下方把同步快照、seeding、内置标记写成现状的句子，一律按本段理解。

## 动机

- **能力已实现却无入口**：`BookSourceManager` 接口早就定义了 `switchSource`/`importFromJson`/`exportToJson`/`saveCurrentSource`，`BookSourceManagerImpl` 也实现了 SharedPreferences 恢复上次书源，但业务代码无任何调用方（除内部 `switchSource`），处于「接线完毕、待接 UI」的死代码状态。

- **单书源风险高**：成稿时随包的内置清单只有 1 条（笔趣阁）。该站点一旦挂掉或改结构，搜索/书城/书架刷新/下载/阅读全部瘫痪，无 fallback。

- **架构与语义错位**：`BookShelfEntity.tag` / `BookInfoEntity.tag` / `ChapterListEntity.tag` / `DownloadChapterEntity.tag` 一直由 `JsoupBookParser` 写入 `rule.url`，事实上已经是每本书/每章/每段缓存的书源归属标记，只是过去恰好只有一个书源所以恒定不变。数据层已具备多书源共存的表达能力，Manager 层强行收敛成单 parser 反而浪费了这份能力。（成稿时还有 `BookContentEntity.tag`，该实体与 `book_content` 表已在网络书正文迁章文件的那轮 v3→v4 迁移中删除。）

- **书源管理是阅读类 App 的必备能力**：成熟的阅读 App 均以「书源可导入、可切换、可共存」为核心卖点，本项目定位安卓小说阅读器，缺此能力等于产品力硬伤。

## 决策

1. **`tag` 字段显式承担「书源归属」语义，不新增列**
   `BookShelfEntity` / `BookInfoEntity` / `ChapterListEntity` / `DownloadChapterEntity` 的 `tag` 字段
   （值均为书源 URL）保持不变，仅在文档与 KDoc 中显式化其语义。理由：零 Migration 风险——老用户升级时 `tag` 值本就是当前唯一书源 URL，天然指向默认书源。
   正文本身不在此列：网络书正文早已不落 `book_content` 表（该表随 v3→v4 迁移删除），而由 `filesDir/books/` 下的章文件承载，其归属经 `BookLocation.sourceUrl` 传递（见决策 5）。

2. **Room schema 在 v5 新增 `book_source` 表**（成稿时工程处于 v2，其间 `chapter_list`/`book_shelf` 随正文迁章文件走过 v2→v3→v4，故本表落在 v5）

   ```
   BookSourceEntity(
       url: String           @PrimaryKey   // 书源 URL，天然唯一
       name: String                       // 显示名
       ruleJson: String                   // 整个 BookSourceRule 序列化（避免规则字段变更时改表）
       enabled: Boolean                   // 启用/禁用
       weight: Int                        // 排序权重（越小越靠前）
       group: String                      // 分组（如「小说」）
       isUserImported: Boolean            // 成稿时的列：true=用户导入（可删）；false=内置源（不可删可禁用）。该列已在 v6→v7 迁移中随「内置源」一并删除
       format: String                     // 书源格式出身，列值小写 "native"/"script"
       addedAt: Long                      // 导入时间戳
   )
   ```

   Migration 只做 `CREATE TABLE`，无破坏性变更。`ruleJson` 存整段 JSON 而非拆列，规则字段扩展时不改表——理由：结构化数据一律走 Room 实体 + DAO，与本地存储统一收敛方向一致。

   迁移里的建表语句与 Room 为全新安装生成的语句**逐字对齐**，且**刻意不写实体未声明的 `DEFAULT`**。Room 的 schema 校验只比对实体声明过的东西，多写一份默认值不会被抓出来，只会让覆盖安装与全新安装两侧的表结构漂移，同一条漏列的裸 `INSERT` 因此在两种装机形态下行为分叉。

   `format` 是第二种书源格式（脚本书源）落地时以 v5→v6 迁移补的判别列，两种格式同表共存、按它路由到各自的求值后端。
   读写两侧一律取枚举自带的 `raw` 属性而不是 Kotlin 枚举的 `name`：`name` 恒为大写，而列值是小写，
   两侧各持一份字面量时大小写漂移**不会有任何报错**，只会让脚本行静默按原生规则去解——拉回的是不相干的内容，
   比崩溃难查得多。收在 `raw` 上之后两侧不可能再分叉。

3. **书源清单加载：Room 是唯一来源**
   `BookSourceManagerImpl` 启动时：
   - 清单里每一行都由用户导入（应用不随包携带任何书源），因此没有 assets 清单可读、也没有首启灌库：构造期不碰任何 IO，清单一律经挂起读或 Flow 从 Room 取。
   - **Manager 不持有清单副本**：挂起面（`getAllSources`/`getEnabledSources`/`getSourceByUrl`/`getParserFor`/两个 Flow）每次都现查 Room，排序统一为 `weight ASC, addedAt ASC, url ASC`。理由：留一份内存镜像就要在 seeding、`addSource` 覆盖、`setEnabled`、`removeSource` 每一处之后同步它，漏一处就是一个「库里已改、界面仍是旧值」的静默漂移点，而 Room 的失效追踪已经给了 UI 跟随变化的能力（`observeSources`）。代价只是每次取清单多一次本地查询。
   - **本接口没有任何同步读面**：默认源等不了 Room，而应用又不随包携带书源、「启动时就能同步给出的默认源」这种东西根本不存在，于是同步面整体下线（曾经的同步属性 `currentSource` 与它消费的冷启动清单都已删除）。别为「首帧不空白」重新修一条同步出口——它要么靠随包资产、要么靠第二份内存缓存，两条路都是在 Room 之外另造一处事实源，而清单与默认源的真值只有 Room 一处。
   - **挂起面直接查 Room，没有要先等的后台写任务**：清单的写入只剩用户导入/启用/删除这几条显式路径，不存在「批量灌库与用户操作抢同一个 URL」的 TOCTOU 窗口，seeding 句柄与配套的 `await()` 不变式一并消失。
   - SharedPreferences `KEY_CURRENT_SOURCE` 语义收敛为「书城/默认搜索书源」（阅读/下载不再依赖它）。理由：阅读/下载路径按 `tag` 找 parser 后不再需要「当前书源」概念，只留书城使用。

4. **`BookSourceManager` 的读面按「谁消费」分工**
   现行成员（`lib_book_common` 的 `BookSourceManager.kt`）：
   ```
   suspend fun getAllSources(): List<BookSourceRule>           // 只含原生行
   suspend fun getEnabledSources(): List<BookSourceRule>       // 只含原生行
   suspend fun getSourceByUrl(url: String): BookSourceRule?    // 只含原生行
   suspend fun getFormatByUrl(url: String): SourceFormat?      // 只问一行的出身
   suspend fun getParserFor(sourceUrl: String): BookParser?    // 按归属取源的唯一入口（LRU 3 + Mutex）
   fun observeSources(): Flow<List<BookSourceItem>>            // 唯一带元数据的清单面，含脚本行
   fun observeDefaultSource(): Flow<SourceDefinition?>         // 默认源唯一出口（每次从 Room 现算）
   fun searchAcross(keyword, page, skipSourceUrls): Flow<AggregateSearchEvent>
   suspend fun addSource(rule: BookSourceRule): Result<Unit>          // REPLACE
   suspend fun addScriptSource(rawJson: String): Result<Unit>         // REPLACE，format 落 script
   suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry>  // 书城分类条目
   suspend fun removeSource(url: String) / setEnabled(url, enabled) / setDefaultSource(url)
   fun importFromJson(jsonStr: String): BookSourceRule? / fun exportToJson(rule): String   // 纯函数
   ```
   成稿时列的 `switchSource(rule)` 与 `saveCurrentSource(context)` 都不在接口上：前者与「阅读中换源」那条书架事务同名，留着等于两个「换源」共用一个词；后者「设为默认」的职责由 `setDefaultSource` 承担，没有独立实体。成稿时的同步属性 `currentSource` 同样不在接口上了（见本节开头的事实更新）——本接口现在没有任何同步读面。

   - **只有清单订阅面带元数据**：`observeSources` 发的是 `BookSourceItem`（规则 + `format` 出身位），规则类型化读面仍只发 `BookSourceRule`。判据是「谁消费」：解析链路只关心规则，面向用户的管理页才需要知道这一行出自哪种格式（脚本行的 `rule` 只是按实体列合成的展示空壳）。不把 `format` 塞进 `BookSourceRule`——规则要随社区 JSON 导入导出，带上一个只属于本机这一行的状态会让往返语义变脏；也不把 `BookSourceEntity` 直接递给 UI——那会让 Compose 依赖数据库行。元数据同样**不沿链路往下带**：书城顶部切换器取的是 `item.rule` 并当场滤掉禁用项，一旦 `format` 出管理页就开始长出第二个用途。
   - **`addSource` 是覆盖（REPLACE）语义**，不是「url 冲突拒绝」——社区书源 JSON 通常整包重发，拒绝策略会让已有书源永远无法更新，且与导入预览的「将覆盖」标记互斥。覆盖后若默认源正指向该 URL，下一次现算自然取到新规则，不需要额外的同步动作。
   - **默认源的载体是格式中立的密封 `SourceDefinition`**，而不是 `BookSourceRule`：`Native` 装已解码的规则，`Script` 装原始 JSON 加实体列带来的展示信息（`name`/`url` 的生产填充点只有 `toDefinition` 一处，绝不从 `rule_json` 里解展示字段）。密封保证新增第三种格式时编译器逼出所有分支。现算与写路径收敛共用同一个 `defaultFromRows(rows)`（条目面、**不筛格式**：SP 命中且启用 → 否则第一条启用行），`observeDefaultSource()` 因此是唯一出口、不可能口径分裂。
   - **规则类型化读面挡下脚本行，条目面带出它们**：脚本的社区 JSON 硬解成 `BookSourceRule` 有两种结局、都不是「可用规则」（键名对不上解出空规则而静默零条目；同名不同形的键直接抛解码异常）。所以 `getAllSources`/`getEnabledSources`/`getSourceByUrl` 只含原生行，而 `observeSources()` 必须继续带出脚本行（管理页要看得见、能禁用、能删），`searchAcross` 的候选集也取自条目面的全部启用行（脚本源参与聚合搜索）。两侧口径本就不同，不去「统一」。
   - **本接口没有任何同步读面**：`requireParser()` / `currentParser` 已删除，且**不得重新引入**；随后 `currentSource` 也一并删除（它的存在前提是「启动时能同步给出一个默认源」，而那要求随包资产或第二份内存缓存，如今两条都没有）。成稿时处方是「标 `@Deprecated` 保留一版、让未改造完的调用点仍可运行」，这一站没有走——调用点在同一条分支里一次迁完，没有要保护的中间态；反过来留一个「按全局默认源去解析任何一本书」的口子，其失败形态是「不闪退、拉回不相干的内容」，比崩溃难查，留着比删掉危险。
   - **默认源的 parser 与其它源同构**：它进同一份 LRU（`getParserFor` 不再有默认源快路径），覆盖规则时一次 `evictParser` 即失效——「必须把定义连同 parser 整对换掉」这套口令只属于已拆除的内存快照时代。
   - **`setDefaultSource(url)` 有一个不在名字里的副作用**：目标源处于禁用态时顺带把它启用。否则「默认源永不为禁用态」的回落规则会在下一次读清单时把它换掉，用户看到的现象是「点了没换」；因此 UI 侧不必走「先启用、再设默认」两步。反向同理：禁用当前默认源时自动回落到下一条启用源；当前没有任何可用默认源时，启用任意一条源都会把它提升为默认源。

5. **业务解析路径一律跟书走，不跟全局配置走**
   每一处解析都从**那本书自己**的归属字段取源：书架加入与详情页刷新取 `BookShelfEntity.tag`、离线下载取 `DownloadChapterEntity.tag`、正文取 `BookLocation.sourceUrl`、书城取当前源的 URL。成稿时盘点的「正文与下载各自直调 parser」两处已不存在——网络书正文在 v3→v4 那轮改由 `chapterReaders[BookFormat].readChapter(entry, location)` 供给，`BookReadViewModel` 与 `DownloadService` 都收敛到 `JsoupSourceReader` 一处取正文，因此绑源语义只需在那一处落地。
   正文的归属经 `BookLocation` 新增的 `sourceUrl` 字段承载，而不是「给取正文的方法加一个 `sourceTag` 参数」：书级归属放进书级定位值类型里，`readChapter` 的签名不动，reader 一族不必为多书源长出一个参数。**该字段刻意不给默认值**——有默认值等于允许新构造点忘记表态，而漏传是静默的（正文会退回默认源去抓，抓回别的内容）。
   取不到源按「用户能不能处置」分两支：本地书 `loc_book` 走到网络 reader 是编程错误（`IllegalStateException`）；网络书的 `tag` 为空白、或该 URL 在库里没有行（用户删了源）、或那一行 `rule_json` 是坏行，抛 `BookSourceNotFoundException`，UI 提示「书源已失效，请重新导入或换源」。`getParserFor` 返回 null 恒等于「书源不存在/坏行」这一种成因，脚本行的装载失败一律走类型化异常，两者不得混用（混了等于把「这条规则解不动」说成「这条源已失效」，用户会被支去重导一条本来好的源）。
   「抛异常 + UI 提示」不是一句承诺就能兑现，两侧各需要一处专门接线：
   - **正文侧**：取正文发生在阅读器页面，那里原先的 `catch (e: Exception)` 把一切异常吞成 `null`，提示永远出不来。现由该方法单独 catch `BookSourceNotFoundException`、经 `reportFailure` 上报（它只记日志、不重复提示会话过期），其余异常保持静默降级。
   - **下载侧**：与早期设想相反，书源失效**不**「留任务等重导源」，而是按普通失败重试到耗尽后出队并计入跳过章数——注定失败的队头会阻塞该书其余章节（常驻通知永不消失、后续章节全被卡住）。用户重导书源后重新发起下载即可续跑；暂停中断重试时不出队（任务保留待续跑）。

6. **聚合搜索：`flatMapMerge` 并发 + 每源独立分页游标 + 结果打标签**
   ```
   fun searchAcross(keyword: String, page: Int, skipSourceUrls: Set<String>): Flow<AggregateSearchEvent>
   ```
   实现要点：
   - 对所有启用书源用 `flatMapMerge(concurrency = 5)` 并发调用 `parser.searchBook(keyword, page)`；concurrency 上限 5 避免同时打爆 10+ 站点触发风控。
   - 每个书源独立分页游标（VM 内 `Map<sourceUrl, Int>`），某书源返回空只标记该书源 `hasMore=false`，不影响其他书源继续翻页。
   - **轮次由调用方自己串行化**：基类的加载更多状态机只在「已进入 Loading」时拒绝重入，而首轮搜索由搜索按钮直接发起、不经状态机——那一刻机器仍在 Idle，触底判据又只看 `!isLoadingMore`，于是首轮结果正一条条往外蹦时用户停在底部就会并起第二轮，而游标表与本轮账目都是 VM 字段、新一轮开局即清空，旧轮随后到达的事件于是记在新一轮的账上（「已收到 X/Y」算重、每源游标互相覆写）。处方取「上一轮在途即忽略」而不是「取消旧轮重开」：旧轮收尾会放出状态机，界面仍在底部时触底自会再触发一次并带上正确游标；取消旧轮则会重问已问过的页，那页去重后「零新条目」把每条源都判到底、footer 亮出假的「没有更多」。
   - **「哪些源已到底」由调用方每轮带进来**（`skipSourceUrls`）：Manager 不持有搜索会话状态——它跨页面、跨关键词，任何「记住上一轮」的做法都会在 VM 之外长出第二个事实源；而这个信息只有握着游标的那一方知道，不带进来下一轮就会对已结束的源重复发请求。
   - 事件流：`AggregateSearchEvent` = `SourceStarted(url)` / `SourceResult(url, books)` / `SourceFailed(url, err)` / `SourceFinished(url, hasMore)` / `AllFinished`；UI 层按需渲染「已收到 X/Y 书源结果」进度条。**收尾判据是 `AllFinished`，不是「Finished 计数 == Started 计数」**：单源异常一律收敛成 `SourceFailed` + `SourceFinished(hasMore = false)`，但 `CancellationException` 原样上抛（否则换关键词重搜掐不掉旧轮次），代价是某一路被取消时 `flatMapMerge` 丢弃整路、该源连 Finished 都不发，只数 Finished 会让进度永远差一格。
   - 去重策略：按 `noteUrl` 全局去重（同一 URL 只可能出现一次）；不按 name+author 去重——不同书源的同名书是有效备选，交给用户在换源时选择。
   - 每条结果自带 `origin`（书源名）与 `tag`（书源 URL），UI 用 `InfoChip` 显示书源标签。理由：聚合搜索让用户体验一次搜全站，并发上限与独立游标平衡了风控风险与可用性。

7. **书城：顶部书源切换器 + 缓存按源分区**
   - `LibraryViewModel` 的当前源是 `StateFlow<SourceDefinition?>`（订阅 `observeDefaultSource()`），切换器候选 `sources: StateFlow<List<BookSourceRule>>` 来自 `observeSources()` 的启用过滤——**不按格式过滤**：脚本行同样进得来、同样能被立为当前源。分类入口与书库两件事都由这一条当前源流驱动，页面不再自己 `triggerRefresh`。
   - 页面「该说什么」的唯一判据是 `sourceState`：首帧 `Unknown`（默认源由 Room 现算，那一刻还没有结论 → **整片留空**，不画引导语也不画半截切换器），落定后是 `Ready` / `NoSource`（一条源都没启用 → 去启用或导入，不发任何请求）/ `BrokenSource`（源还在但这条解析不出来 → 换一个源或重导这条源）。后两档复用同一处引导区、各说一句话；**把后者并进前者是有害的误报**——用户会被指去启用**正是坏掉的那条源**。零源档另有「去导入书源」按钮（跨模块跳书源管理页，模块独立运行时由占位页承接）。
   - **只有一条启用源时，顶部胶囊不是一颗「点了没反应」的按钮**：源名照常显示，但不画下拉箭头，`clickable` 传 `enabled = false`（收不到点击也不给涟漪，视觉与行为一致），而 `Role.Button` 保留——无障碍据此仍把它读成一颗禁用的按钮，而不是一段碰巧不能按的文字。这条形态刻意做「减法」，顺手给它补个箭头就等于把它退回成诱导点击。要找更多源是书源管理页的事，那里有入口。
   - 仓库（`module_find` 的 `BookSourceRepository`）的分类/书库方法**一律以 `sourceUrl` 为入参、不自己读全局默认源**——顶部切换器要浏览的恰恰可能不是默认源，仓库自己去读全局值等于把切换器的一半语义吞掉。
   - 书库缓存按源分区：key 生成唯一收在 `libraryCacheKey(sourceUrl)`，读写两侧都过它。理由是「各拼一次字符串只要有一点差别，结果就是写完永远读不到」——表现为每次进书城都在重拉网络，功能上却看不出任何毛病。载体是 `cacheDir/library_cache/` 下的文件缓存（文件名取该 key 的 MD5），缓存策略（TTL 过期、进页 stale-while-revalidate、下拉强刷）住在仓库层，解析器只负责「逐分类抓首页 + 拼装」、不碰缓存。

8. **阅读中换源：按书名+作者跨源匹配，章序号映射进度**
   - 阅读器菜单/目录页新增「换源」入口（`ReadBookActivity`）。
   - 弹出 `SourceSwitchSheet`（Compose `ModalBottomSheet`）：
     1. 用当前书 `bookInfo.name` + `bookInfo.author` 调 `searchAcross`（排除当前书源）；
     2. 结果按匹配度排序：完全匹配 (name==) > 部分匹配 (name.contains) > 作者匹配 (author==)；
     3. 用户选中一条 → 触发换源流程。
   - 换源流程（`BookRepository.switchSource(oldShelf, newBook)`，事务）：
     ```
     1. 本地书（tag == loc_book）直接 Result.failure：本地书没有「源」可换
     2. 事务之外按新条目的 tag 取 parser，拉 BookInfo + ChapterList（取不到源 → BookSourceNotFoundException）
     3. 章序号映射：
          durChapter     = min(旧 durChapter, 新章节总数 - 1)   // 越界回落到末章；新目录为空取 0
          durChapterPage = DUR_PAGE_INDEX_BEGIN                 // 页级不跨源（不同书源分页规则不同）
          finalDate      = 保留（阅读时间不变）
          matchName/matchAuthor = 保留旧值（评论桶不因换源而漂移）
     4. 一个写事务内：插新条目 → 吸收旧条目的 book_group 关联键 → 删旧条目的四张表行
        （先插新、后删旧：反过来一旦中途失败，这本书就从书架上消失了）
     5. 提交成功后才发 BookShelfEvent.Removed(旧) + BookShelfEvent.Added(新)
     ```
   - 章序号映射是有损转换：不同书源章节划分可能不一致（分章/合章/番外顺序），极端情况会偏 1-3 章。已接受此代价——比「从第一章重开」体验好，比「全文匹配定位」实现成本低。
   - **旧源名下的行一律删净、只有盘上章文件与内存缓存不删**（刻意不走 `removeFromShelf`）。目录与正文都不是可独立存在的缓存：`book_content` 表已随 v3→v4 迁移删除，正文住在 `filesDir/books/<noteUrl>/cNNNNN.txt`、目录住在 `chapter_list` 里按 `note_url` 归属的行，两者都是**书架条目的附属物**——「条目删了而行留着」造出的不是缓存而是**没有任何清理路径的孤儿行**（`getAllBooksWithDetails` 只清「有书架行却缺 `book_info`」那一个方向，`@Relation` 也不会去捞没有书架行的章节）。
   - 代价与保留的实效：切回旧源要重拉一次目录（一次 HTTP，不是整本正文），而已读章节的正文命中盘上文件——reader 判的是 `BookStore.hasChapter(location, index)`，同一站点的章序稳定，重抓的目录 index 与既有文件名对得上。**这份「秒开」只在同一次进程内成立**：旧目录此刻正是启动对账（`BookStore.reconcile`，`liveBookIds` 取自 `book_shelf`）眼里的无主目录，下一次启动会回收它，而这本来就是那套目录既有的回收器。为保住跨启动的缓存而给 `book_shelf` 加一列「隐藏条目」已否掉：要走 schema 迁移 + 全链路查询都带过滤条件，换来的只是省一次目录请求。
   - 换源同时把旧 `noteUrl` 名下的在队下载任务一并删掉：服务按书架逐本取任务，旧 noteUrl 已不在架上，那些行从此谁也取不到，却仍撑着下载管理页一个 `totalChapters = 0` 的分组与不归零的角标。**不迁移**：任务里的章节地址属于旧站，换个归属重跑就是拿旧 URL 打新站。
   - 仓库侧两条守卫：换源目标与当前条目 `noteUrl` 相同时直接判失败，否则「先插新、后删旧」会把刚写进去的那行删掉，这本书就从书架上消失了；目标那条 `noteUrl` **本就是书架上另一个条目**时同样判失败（`BookAlreadyOnShelfException`）——放行不是「白换一次源」而是丢数据：`book_shelf` 主键是自然键 `note_url`，REPLACE 会抹掉那本书自己的阅读进度并把两本并成一册，而 `chapter_list` 按 `content_ref` 为主键、两个站的同一章互不覆盖，那一册里会混着两份目录。面板为这一档单出一句话（动作是「去读那一本」，不是「另选一本」）。
   - 换源失败的提示出口在面板、不在弹层自己的 ViewModel：`SourceSwitchViewModel` 是弹层用 `hiltViewModel()` 起的第二个 ViewModel，而命令通道只绑宿主的 `BookReadViewModel`——它的 `sendToast` 没有任何收集方，写了不报错也不响。失败一律经状态与 `Result` 交出去，由面板内联渲染一句。

9. **书源设置页（`module_me`）**
   - 新增 `BookSourceManageActivity`，路由 `/ebook/me/book_source`（`KeyCode.Me.BOOK_SOURCE_PATH`）。
   - `SettingActivity` 通用组新增「书源管理」入口行（图标 + 当前书源数副标题）。
   - UI：卡片列表，每项含：书源名 / URL 副标题 / 启用 Switch / 「默认」`InfoChip` 标记。删除走「点或长按行 → 操作层 → 确认框」两跳（不做滑动删除：手势不可见，用户找不到）。
   - **删除没有任何保护**：应用不随包携带书源，清单里每一行都是用户自己导入的，因此没有「内置源」可供保护——任何一行都可删、可禁用、可设默认。`removeSource` 的失败只剩「这行本就不存在」一种，页面因此也没有置灰的删除项与配套解释文案。
   - **导入 / 导出两个入口在内容区首行右侧**，不在 Toolbar 上：基类的 `ToolbarLayout` 不提供 actions 插槽（只给标题、返回箭头与配色），要塞进去得连带搬它内部的状态栏取色与返回逻辑。实际做成与「共 X 个、已启用 Y 个」汇总行同排的两个 IconButton——入口与次数信息都在，只是不在同一条带上。
   - 导入流程：
     1. 读文件 → 自适应单条或数组：**按首字符分派**（`[` 走 `JSONArray` 逐元素、`{` 走单条），实现在 `BookSourceViewModel.splitJsonObjects`。刻意不写成「先按数组解、抛了再退单条」——包里混进一个坏元素时那条退路会把整包再当单条解一遍，用户于是看到第二条莫名其妙的错误；切条目用 Android 自带的 `org.json`，规则解码仍只有 `importFromJson` 一处。
     2. **先按 JSON 顶层键判别出身**（判别约定只有一处实现），原生行走结构校验、脚本行走 `addScriptSource` 落原文；Manager 对脚本内容**不做格式校验**，校验与警示归导入 UI；
     3. 原生路径的结构校验：`name` 非空、`url` 合法 http(s)、`searchUrl` 或 `ruleFind.url` 至少一个非空、`ruleSearch.list` 与 `ruleContent.content` 均非空（否则书源无法完成核心链路）；
     4. 预览弹层：展示待导入书源清单（名称 / URL / 校验结果），已存在的 URL 按 `getFormatByUrl` 标出出身并标「将覆盖」；
     5. 用户确认 → 逐条落 Room，冲突策略 REPLACE；
     6. Toast 反馈「导入成功 X 条 / 覆盖 Y 条 / 失败 Z 条（校验未通过）」。
     预览判「解不解得开」与落库判「存不存得下」共用同一个解码器实例，否则会长出两个接受集、出现「预览说能导、落库说解不出」这种两边各按自己那份配置说话的故障。
   - 校验粒度：结构校验 + 导入前预览（不做完整的书源连通性测试跑搜索/详情/目录/正文，代价过高；用户可自行搜索验证）。理由：导入前的结构校验能拦住格式错误，连通性测试不做。

10. **文档与术语同步**
    - CONTEXT.md 收录术语：默认书源、用户导入书源、多书源共存、聚合搜索、阅读中换源、书源归属标记（`tag` 字段的显式语义）。
    - AGENTS.md「Agent 实战建议」记两条长期约束：涉及书源解析的代码先按 `entity.tag` 找 parser、不默认走全局 `currentSource`；`requireParser()` 已删除且新代码不得重新引入。

## 权衡

- **`tag` 复用 vs 新增 `source_url` 列**：复用 `tag` 零 Migration、老数据天然对齐；代价是 `tag` 语义从「泛化标签」显式化为「书源 URL」，需要 KDoc 与 CONTEXT.md 补充说明。新增列 Migration 风险更大（老数据兜底填什么？若填错等于把书归到错误书源）。选前者。

- **聚合搜索 vs 单选书源搜索**：聚合搜索用户体验好（一次搜全站），代价是并发控制、去重、分页游标管理复杂，且同时对多个第三方站点发请求可能触发风控。选聚合搜索但并发上限压到 5，并保留每源独立分页游标（某源失败不影响其他源）。

- **章序号映射 vs 全文匹配定位**：全文匹配（拿旧源当前章正文前 500 字到新源全章搜索）精度高，但需要拉全量章节内容，成本高、耗时长。章序号映射实现简单、瞬时完成，代价是可能偏 1-3 章。选章序号映射（用户可通过目录手动微调）。

- **旧源内容保留 vs 清理**：换源后把旧源名下的东西一并清干最省空间，代价是用户切回时连整本正文都要重拉。选「盘上章文件与内存缓存保留、库内行删净」——存储成本远低于网络重拉成本，而留着库内行换来的是没人能清理的孤儿数据（取舍细节见决策 8）。

- **`BookSourceEntity` 表存 `ruleJson` 整段 vs 拆列**：整段 JSON 灵活，规则字段扩展不改表；代价是无法用 SQL 直接过滤规则内字段（如「找所有支持 POST 搜索的书源」）。当前无此类查询需求，选整段。

## 被拒方案

- **只做「全局书源切换」（不共存）**：用户切书源后原有书架全部打不开，等于每次换源清空书架，体验灾难。且 BookSourceManager 已有能力被闲置的问题仍未解决。

- **书源存 SharedPreferences**：整段 JSON 数组塞 SP，脏、难查询、难迁移。与本地存储统一收敛到 Room 的既定方向相悖（本地数据库已由 ObjectBox 整体迁 Room，结构化数据一律走实体 + DAO）。

- **书源存内部文件 `book_sources.json`**：介于 SP 与 Room 之间，自己维护序列化/读写/并发，重复造 Room 已经解决的轮子。

- **完整级书源连通性测试**（导入前跑一遍搜索/详情/目录/正文）：MVP 阶段代价过高（网络请求 + 结果比对 + UI），且用户导入的书源大多来自社区已验证的 JSON。

- **网络订阅 URL 一键导入**（其他阅读 App 的「书源订阅」）：需要额外做订阅管理、去重、更新检查、失败重试。MVP 只做本地文件导入。

- **换源时把旧源内容整体清理**（连章文件与内存缓存一起删）：被拒的是这一档激进形态——用户随时可能切回，整本正文重拉的代价远高于留下的磁盘代价。库内那四张表的行反倒一律删净，因为它们离开书架条目就没有任何清理路径。

## 下游影响

- **`lib_ebook_db`**：新增 `BookSourceEntity` + `BookSourceDao`；`AppDatabase` version 4→5，`DatabaseModule` 加 `MIGRATION_4_5`，Room 生成的 `5.json` schema 提交入库。`format` 列再走 v5→v6；`is_user_imported` 列与其中的内置行随 v6→v7 一并删除。
- **`lib_book_common`**：`BookSourceManager` 接口重写（本接口现已无任何同步读面——`requireParser` / `currentParser` 与后来的同步属性 `currentSource` 都已删除）；`BookSourceManagerImpl` 以 Room 为唯一事实源（没有 assets 冷启动快照、也没有默认源内存快照，默认源每次从行现算）；`BookLocation` 加 `sourceUrl`；`BookRepository` 新增 `switchSource`；书库缓存分两处收口——key 的**形态**唯一由 `com.ebook.common.event` 的顶层函数 `libraryCacheKey(sourceUrl)` 决定，读写落在独立的 `LibraryDiskCache` 类（落盘文件名取该 key 的 MD5），刻意不同类：挪进缓存类等于把「谁都能自己拼一份」的旧格局换个地方长回来。
- **`lib_book_source`**：解析器一族与 `AggregateSearchEvent`、`BookSourceNotFoundException` 住在这里（Manager 侧只留编排与持久化）。
- **`lib_ebook_api`**：多书源落地本身无改动；该模块原有的书库缓存工具类 `ACache` 已删除（缓存整体换到 `lib_book_common` 的文件缓存，策略上收 `module_find` 的 `BookSourceRepository`）。
- **`module_find`**：`SearchViewModel` 从单书源分页改为聚合搜索（消费 `searchAcross`、自持轮次与游标）；`LibraryViewModel` 加 `currentSource` / `sources` / `sourceState` 与切换源的方法；搜索/书城 UI 加书源标签与顶部切换器。
- **`module_book`**：`BookDetailViewModel` 按 `tag` 找 parser，`JsoupSourceReader` 按 `BookLocation.sourceUrl` 找 parser（`BookReadViewModel` / `DownloadService` 经它取正文，不直调）；`ReadBookActivity` 挂「换源」入口与 `SourceSwitchSheet`（面板 + `SourceSwitchViewModel`）。
- **`module_me`**：`BookSourceManageActivity` + `BookSourceViewModel`；`SettingActivity` 加书源管理入口；`KeyCode.Me.BOOK_SOURCE_PATH`；导入/导出/校验失败/覆盖确认等字符串资源。
- **兼容性**：成稿时老用户升级后 `tag` 值天然指向内置默认源，书架/缓存/下载全部可用，零手工迁移；不存在「按全局默认源兜底」这条旧路径。（**事实更新 2026-09-11**：应用不再随包携带书源，该内置源已随 v6→v7 迁移从库里清掉——只在开发分支装过携带内置源版本的设备上，绑着它的书会显示「书源已失效」，重新导入该书源即恢复；发布基线从未建过这张表，正式用户不受影响。）

## 遗留

以下能力按需迭代，本轮不做：

- **网络订阅 URL 导入**：一键拉取远程 JSON 数组，需要订阅管理/去重/更新检查。
- **完整书源测试**：导入前跑一遍搜索/详情/目录/正文，逐步打勾验证规则正确性。
- **跨源换源的智能匹配**（内容前 500 字全文搜索定位）：精度高于章序号映射，代价高。
- **书源分组管理**（`group` 字段已存在但 UI 未暴露）：书源超过 20 条后按分组折叠展示。
- **书源权重排序**（`weight` 字段已存在但 UI 未暴露）：管理页没有任何上下移动按钮，清单顺序只来自 `weight ASC, added_at ASC, url ASC` 这条查询排序。
- **多书源聚合的书城**：书城只按「单个书源 + 顶部切换器」展示，未做的是可选的「聚合书库」模式——把各书源的分类与首屏书目合并成一份浏览。
- **导入结果的失败项回看**：导入前的预览面板已逐条给出校验失败原因，未做的是「确认导入之后」——统计 Toast 只给「成功 X / 覆盖 Y / 失败 Z」三个数，被跳过的项要点开预览才看得到原因。
