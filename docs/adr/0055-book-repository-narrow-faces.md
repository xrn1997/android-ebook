# BookRepository 按调用方切四个窄面

Status: accepted

`BookRepository` 把书架读写、正文读取、目录同步与评论聚合键全塞在一张约千行、二十余个公开成员的类里，每个生产注入点都声明依赖整张聚合，测试替身也只能按整张面接。决定与书源四面（[ADR-0047](0047-book-source-surface-split.md)）同形、不推翻其结论：按调用方把公开成员切进 `ShelfRepository` / `ContentReader` / `ChapterTocSync` / `CommentKeys` 四个窄面，生产注入点只注实际用到的面；聚合 `BookRepository` 仍是四面的唯一实现，但只留给测试与对账入口（`reconcileContentStore` 不进任何面）。纯声明层收窄，行为与签名逐字不变。

## 背景

- 书架、阅读器、详情页、导入、搜索、我的页、启动页都要碰仓库，但各自只用其中三五个方法；宽类让每个注入点的构造参数都表达不出「这个类到底要仓库的哪一面」，新增一个方法自动成为所有调用方的契约。
- 测试侧的代价与 ADR-0047 时相同：具名替身与手写假件要按整张面接，测试想锁的行为被用不到的成员淹没。
- 与书源四面不同的一处硬约束：`BookListViewModel`（`BaseRefreshViewModel` 的 model 位）、`BookDetailViewModel` 与 `BookReadViewModel`（`BaseViewModel` 的 model 位）的基类泛型约束是 `M : BaseModel`——书架面必须扛住这个约束，其余三面没有 model 位调用方。

## 决策

1. **按调用方切四个窄面，每面自带它那部分 KDoc 与不变量**（`lib_book_common` 的 `ShelfFaces.kt`）：
   - `ShelfRepository`：`bookShelfEvents`、`getAllBooks`、`observeBookShelf`、`getAllBooksWithDetails`、`getBookByUrl`、`getBookWithDetails`、`saveProgress`、`addToShelf`、`removeFromShelf`、`switchSource`、`mergeTailChapters`——书架条目的增删改查、进度、换源与事件；
   - `ContentReader`：`loadChapter`、`refreshChapter`、`getBookWithDetails`——阅读会话的正文读取；
   - `ChapterTocSync`：`syncChaptersFromSource`、`appendRemoteChapters`、`getStoredChapters`——目录重抓与纯追加落库；
   - `CommentKeys`：`getCommentKeysForBook`、`getPrimaryKeyForBook`、`getBookGroupRows`、`absorbGroupKeys`、`splitBook`、`updateMatchMeta`——`book_group` 的读并集、写主键与修键。
   `getBookWithDetails` 同时出现在 `ShelfRepository` 与 `ContentReader`：恢复路径（`RepositoryReaderSessionGateway`）两面都要它；实现仍只有 `BookRepository` 里那一份，重复的只是声明。
2. **书架事件留在写条目那一面（`ShelfRepository.bookShelfEvents`），不留两份**：「事件在提交后才发」是写事务的纪律（`ShelfEntryStore`），发布者与写入者同面，读面另发一份事件流等于给同一事实造第二个发布口。
3. **`ShelfRepository` 继承 `BaseModel`，写成抽象类**：Kotlin 接口不能继承类，「继承 `BaseModel` 的窄面」唯一合法形态是抽象类；这纯为满足上述三个 ViewModel 基类的 `M : BaseModel` 约束，不是本面的语义。其余三面保持纯净接口。
4. **聚合 `BookRepository` 实现四面，但没有 Hilt 绑定**：四面各一条 `@Binds @Singleton` 指向同一个 `@Singleton BookRepository`（形态同书源四面的 `AnalyzeModule`）——留一条聚合绑定就等于给宽接口留一条生产注入口。聚合类型只留给测试与 `MyApplication.ContentStoreEntryPoint` 的对账（`reconcileContentStore` 不进任何面：对账要的是「库 + 磁盘」而不是书架条目能力，塞进任何一面都会让它出现在不该出现的注入点上）。
5. **签名逐字复制、行为零变化**：面上的声明与 `BookRepository` 现有方法同返回类型、同挂起方式；`loadChapter` 的 `chapterContentRef` 与 `syncChaptersFromSource` 的 `force` 默认值挂在面上（override 不得重复声明默认值），经实现类或经面调用都取同一个默认。测试断言零改动——构造点只改参数类型/名字，聚合实例直接传入（`BookRepository` 实现全部面）。

## 边界

- `ShelfEntryFetcher`（[ADR-0054](0054-shelf-entry-builder-single-owner.md)）不经任何面：它是仓库/管理器的内部件，不是调用方能力。
- 面是**声明层**的收窄，不是代码搬家：实现仍是一份 `BookRepository`，四表写事务、事件在提交后发等不变量（§4.1）原位不动。
- 新增仓库公开方法时必须先决定归哪一面——没有「先塞进聚合、以后再分」的路径，聚合在生产上没有注入口。

## 后果

- 生产注入点的构造参数/字段即意图：搜索与分类页只订事件（`ShelfRepository`），我的页只观察书架（`ShelfRepository`），阅读页三面齐（会话适配器要书架+正文+目录），修键面板与阅读页评论入口只拿 `CommentKeys`（+ `getBookByUrl` 所在的书架面）。
- 事件语义不变（ADR-0004），消费方仍在 ViewModel 内穷尽 `when`；换的只是订阅时声明的面。
- `BookShelfManager` 作为书架快照的唯一主人同样只注 `ShelfRepository`（`getAllBooks`/`addToShelf`），聚合的生产注入口归零。

## 被否方案

- **再造一个「书架组合面」把高频调用方一次满足**：被否。那正是宽接口的复辟——第五个面只会成为新的惯性注入口，A 面 B 面的成员迟早又汇到一处。
- **把 `bookShelfEvents` 复制到读面（如 `ContentReader`）方便阅读器订阅**：被否。两个发布口必然漂移（一处漏发就是静默不一致），且阅读器当前根本不订阅事件。
- **`reconcileContentStore` 进 `ContentReader`**：被否。对账回收的是「库里已无书」的磁盘目录，是库+磁盘的对账而不是正文读取；进了正文面，任何拿到 `ContentReader` 的调用方就都摸得到一个启动期一次性动作。
- **三个 ViewModel 改用 `NoOpModel` 占位、绕开 `BaseModel` 约束**：被否。那要改基类用法、把 model 位架空，比让书架面继承 `BaseModel` 动得深；约束真实存在（基类签名 `M : BaseModel`），正面承认它代价最小。

锚点：`lib_book_common` 的 `ShelfFaces.kt`（四面声明与逐面 KDoc）、`BookRepository`（四面唯一实现，`reconcileContentStore` 聚合专属）、`di/RepositoryModule.kt`（四条 `@Binds @Singleton`）、`di/AnalyzeModule.kt`（书源四面的同形绑定）；`MyApplication.ContentStoreEntryPoint`（聚合仅存的生产入口）；注入点：`SearchViewModel`/`ChoiceBookViewModel`（事件订阅）、`MePageViewModel`（`observeBookShelf`）、`BookListViewModel`/`BookDetailViewModel`（model 位 = `ShelfRepository`）、`LocalImportCoordinator`（书架面 + `CommentKeys.absorbGroupKeys`）、`ReadBookActivity`（`CommentKeys`）、`RepositoryReaderSessionGateway`（三面）、`SplashActivity`（`getBookWithDetails`）；测试读数：`BookRepositoryTest`/`BookRepositorySwitchSourceTest`/`ReadingProgressFlowTest` 等断言零改动；`docs/architecture.md` §4.1。
