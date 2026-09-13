# 书架网络书章节更新检查（阶段 1：按需重抓与前缀 diff）

- 状态：**阶段 1 已落地（2026-09-13）**，未提交。阶段 2（书架批量检查 + 红点）见 §10，尚未开工。
- 日期：2026-09-13
- 修订：2026-09-13（落地时把 §4.1 的判定从「按位置逐位比对 `contentRef`」改为
  **「按 `contentRef` 在远端定位 + 校验相对顺序」**。原因由单测暴露：远端行的 `durChapterIndex`
  是 parser 按位置连写的，而本地序号可能有洞（用户删过章），按位置硬比会把这类书**永远**判成
  `Diverged`、再也追不了更 —— 而它其实完全满足「只追加、不动既有章」的安全条件。
  改后不变式 I1/I2 的强度一分不减，决策 1「分叉即整笔放弃」不变。详见 ADR-0039）
- 涉及仓库：`android-ebook`（全部改动落在 `lib_book_common` 与 `module_book`）
- 已新增 ADR：ADR-0039「目录重抓只接受纯追加，结构分叉即整笔放弃」
- 阶段划分：本文只覆盖**阶段 1（按需重抓）**；阶段 2（书架批量检查 + 红点）的设计要点与非目标见 §10

## 0. 一页摘要

### 0.1 状态说明（落地后回写）

写作本 spec 时工作区有 29 个未提交文件（阅读器上下滚屏那批），曾把本功能的开工 gate 在其之后。
**该前提现已不成立**：滚屏那批与本文已分别随 `2b20d51a`/`8ec85425` 与 `2e0c690e` 提交，
阶段 1 也已按本文实现完毕（判定口径的修订见头部「修订」条与 ADR-0039）。

仍然成立的一条：**本文引用的行号是写作时点的快照**。滚屏改造后阅读容器已换成
`ReaderScrollController` 的跨章连续列表，§4.3 阅读页触发点的实际接线位置以代码为准，
不要照本文行号硬找。

### 0.2 决策导航

完整理由与被拒方案在 §2，此处只做导航：

| 一句话 | 详见 |
|---|---|
| 重抓只在「本地目录是远端目录的前缀」时写入，否则整笔放弃 | §2-1 §3 |
| 章文件按**序号**命名 ⇒ 序号一旦漂移就会静默读错章，这是前缀不变式的根因 | §3 |
| diff 是一个零 IO 的纯函数，可单测穷举全部边界 | §4.1 |
| 限频事实源是已存在但从未写过的 `book_info.final_refresh_data`，**阶段 1 不需要 DB 迁移** | §6 |
| 时间戳只在「得出结论」后写：网络失败不写（可重试），`Diverged` 写（重试无意义） | §6 §7 |
| 自动触发的失败一律静默，只有用户主动刷新才把失败说出口 | §7 |
| 阅读页重载复用换源那条已验证的路径，不新造机制 | §4.3 |
| 新增 `BookShelfEvent.ChaptersUpdated`，4 处穷举 `when` 会编译失败、需各补一分支 | §8 |
| 不自动下载新章正文、不引入 WorkManager、不做章名兜底重匹配 | §12 |

## 1. 背景与问题

用户反馈：从网络书源加到书架的书，后续不会更新章节信息 —— 追更的书出了新章，App 里看不到。

排查结论：**书架侧的刷新从不触网**。

- 书架页有下拉刷新，但 `BookShelfPage.kt:146` 的 `onRefresh` 只调 `BookListViewModel.refreshData()`，
  后者调 `BookRepository.getAllBooksWithDetails()`（`BookRepository.kt:102-127`）—— 一次性本地查询，零网络请求。
- 全仓 `parser.getChapterList` 只有 4 个主代码调用点：从搜索加书架（`BookShelfManager.kt:63`）、
  换源（`BookRepository.kt:360`）、详情页（`BookDetailViewModel.kt:226`）、parser 自身实现。
  **没有任何一处把拉回来的目录与本地 `chapter_list` 做 diff。**
- 详情页的书架入口明确不触网：`BookDetailViewModel.initFromBookShelf`（`:92-95`）只用本地实体填充状态，
  `fetchChapterList()` 仅搜索入口经 `getBookShelfInfo()` 调用。

仓里还躺着两个当年想做没做完的钩子，本阶段把它们接上：

- `book_info.final_refresh_data`（`BookInfoEntity.kt:41-42`，KDoc 写「章节最后更新时间」）：
  网络书恒为 `0`，全仓只有本地书导入写过一次（`LocalBookImporter.kt:109`）。
- `BookShelfEntity.REFRESH_TIME = 5 * 60 * 1000`（`BookShelfEntity.kt:80`）：全仓零引用的遗留常量。

## 2. 决策摘要

| # | 决定 | 拒绝的替代 |
|---|---|---|
| 1 | 远端目录与本地不是「纯追加」关系时**整笔放弃**，本地一个字不动，只给用户一条显式提示 | ①章名兜底再比一轮（能自愈「站点只换了 URL 规则」，但序号仍可能漂移，连带要写章文件重命名）；②整本替换 + 章文件按新序号重建（彻底自愈，但要写一套章文件迁移，且替换瞬间正在读的那章会失配） |
| 2 | 身份判据只用 `content_ref`（网络书即该站章节 URL），**不做章名兜底** | 章名参与判定（同一章在不同站/改版后标题写法不一，会把「同一章」判成分叉；反过来站点改标题又会把「不同章」判成同一章） |
| 3 | diff 抽成独立纯函数 `ChapterTocDiff`，`BookRepository` 只做编排 | 把比对逻辑内联进仓库方法（无法在不造假 DAO 与假 parser 的前提下穷举边界形态） |
| 4 | 限频事实源复用 `book_info.final_refresh_data`，**不加新列、不做迁移** | 新加 `last_toc_check_time` 列（语义与 `final_refresh_data` 的 KDoc「章节最后更新时间」重复，且要写 `MIGRATION_8_9` 与 schema JSON） |
| 5 | 新增定向 DAO 查询 `UPDATE book_info SET final_refresh_data = :ts WHERE note_url = :noteUrl` | 读回整个 `BookInfoEntity` 改字段再 `insert`（`BookInfoDao.insert` 是整行 REPLACE，漏读或漏填任一字段就会把书名/封面抹成默认值） |
| 6 | 阶段 1 只做**按需重抓**（进详情页 / 阅读到末章），不做书架批量扫描 | 一上来就做全书架批量检查（N 本 × 多页目录 = 大量请求，需要并发上限与持久化红点状态，改动面大得多；见 §10） |
| 7 | 新增 `BookShelfEvent.ChaptersUpdated` 事件 | 复用 `ProgressUpdated`（语义不符：进度没动，动的是目录；且会让书架页在「保存进度」与「目录变长」两种场合无法区分） |
| 8 | 自动触发的失败**一律静默**（只记日志），仅用户主动刷新时把失败提示出口 | 失败也弹 Toast（用户没要求检查，自动检查的失败提示是纯噪音；断网时进详情页会连弹数次） |

第 1 条是本设计的核心取舍，理由见 §3。第 6 条的界线：diff 与进度语义是两种诉求共用的地基，
先把它做扎实并独立提交，批量检查只是「对全书架逐本调同一个方法 + 一个红点」。

## 3. 核心不变式

### I1 前缀不变式

重抓只在「**本地每一章都还在远端目录里、且相对顺序一致**」时写入（即本地可按 `content_ref`
嵌入远端的前若干位）。不成立即整笔放弃，本地目录与章文件均不改动。

### I2 序号稳定（I1 的直接推论）

重抓之后，任何**已存在**的章，其 `dur_chapter_index` 与 `content_ref` 逐字不变；新章只追加在尾部。

这条是本设计能成立的支点，因为**章文件是按序号命名的**：`BookRepository.loadChapter` 用
`bookStore.chapterRef(noteUrl, index)` 定位章文件（`BookRepository.kt:458`），落地形态是
`filesDir/books/<bookId>/cNNNNN.txt`。序号一旦漂移，已下载的章文件就会与新目录错位 ——
用户点第 50 章读到的是旧的第 50 章内容，**不报错、不闪退，只是内容不对**。这类静默故障比崩溃难查得多。

I2 还带来两个免费结论，评审时会问到，先写在这里：

- **对正在阅读的那一章天然安全**：追加不改已有章的 index 与 contentRef，因此重抓与阅读可并发，
  不需要加锁或暂停阅读。
- **不需要失效任何缓存**：`ChapterContentCache` 的键是 `chapterRef(noteUrl, index)`，
  已有键的取值不变；阅读器的分页块缓存同理按章组织。所以 `syncChaptersFromSource` **不调**
  `contentCache.invalidateBook`（与 `refreshChapter` / `mergeTailChapters` 的处置不同，那两处动的是已有章）。

### I3 新索引起点

新章的 `dur_chapter_index` **必须**从 `max(本地已有 durChapterIndex) + 1` 起重排，既不用 `tail.size` 累加，
也**不能沿用远端行自带的序号**。

两条实测依据：

1. **远端序号是位置序号、连续从 0**。两个 parser 构造目录行时都写 `durChapterIndex = chapters.size`
   （原生 `JsoupBookParser.kt:405`、脚本 `ScriptTocPager.kt:68`；原生的 `reverse` 选项反转后还会
   `forEachIndexed` 重排一遍，`JsoupBookParser.kt:153-155`）。因此本地一旦有洞（历史删章留下的），
   远端序号就与本地序号**不同口径**：直接沿用会让新行与既有行**撞同一个 `durChapterIndex`**。
   撞号不会报错 —— `content_ref` 才是主键，两行都能插进去 —— 但 `chapterList.getOrNull(index)`
   会按排序后的位置返回其中一行，用户点某一章读到的是另一章。这与 I2 要防的是同一类静默故障。
2. **用 `size` 也不行**，理由与本地书补章逐字一致（`BookRepository.mergeTailChapters`，
   `BookRepository.kt:626` 的既有注释）：有洞时 `size != max + 1`，用 `size` 会覆写既有章文件。

一个免费结论，评审时会问到：**`insertAll` 的 REPLACE 不会误覆盖既有章**。
`ChapterListDao.insertAll` 按 `content_ref` 整行 REPLACE（`ChapterListDao.kt:33-34`），
而 tail 行的 `contentRef` 必然不在本地 —— 两个 parser 都用 `seenRefs` 跨页去重
（`JsoupBookParser.kt:401`、`ScriptTocPager.kt:65`），故远端内部无重复；
又因前缀判定已确认 `local[i].contentRef == remote[i].contentRef`，
所以 `remote.drop(local.size)` 的 `contentRef` 与本地那批不相交。

### I4 时间戳语义

`final_refresh_data` 记的是「上次**得出结论**的时间」，不是「上次成功追加的时间」。
网络失败不写（这是暂时性故障，下次该重试）；`UpToDate` 与 `Diverged` 都写
（两者都是确定结论，`Diverged` 重试无意义，不写就等于对一本修不好的书每次进详情页都重爬一遍多页目录）。

## 4. 组件划分与契约

三个单元，职责边界与依赖方向如下：

```
BookDetailViewModel ─┐
                     ├─→ BookRepository.syncChaptersFromSource()  ─→ ChapterTocDiff.diff()  (纯函数)
BookReadViewModel ───┘              │
                                    ├─→ BookSourceManager.getParserFor(tag) → parser.getChapterList()
                                    ├─→ ChapterListDao / BookInfoDao（经 WriteTransactionRunner）
                                    └─→ BookShelfEvent.ChaptersUpdated
```

### 4.1 `ChapterTocDiff`（新文件，纯函数）

位置：`lib_book_common/src/main/java/com/ebook/common/repository/ChapterTocDiff.kt`
（与唯一消费方 `BookRepository` 同包；单独成文是因为它要承载一套完整的边界形态单测）。

**零 IO、零网络、不依赖任何注入件**，输入输出都是 `ChapterListEntity` 列表：

```kotlin
sealed interface TocDiff {
    /** 远端与本地逐位相同，没有新章 */
    data object UpToDate : TocDiff
    /** 本地是远端的真前缀，[tail] 是远端尾部多出来的章（序号尚未按 I3 重排） */
    data class Appendable(val tail: List<ChapterListEntity>) : TocDiff
    /** 不是前缀关系：站点改版 / 中间插章 / 删章重排，按 I1 整笔放弃 */
    data object Diverged : TocDiff
}
```

判定规则（两侧先各自 `sortedBy { durChapterIndex }`，再按 `contentRef` 在远端定位）：

```
本地为空                      → 远端全部即 tail（空则 UpToDate）
本地末章的 contentRef 不在远端 → Diverged
本地任一章不在远端 / 出现在本地末章之后 / 相对顺序与远端不一致 → Diverged
tail = remote.drop(本地末章在远端的位序 + 1)
tail 为空 → UpToDate；否则 → Appendable(tail)
```

**为什么不按位置逐位比**（原文那样写过，落地时改掉）：远端行的 `durChapterIndex` 是 parser
按位置连写的（两个 parser 都写 `chapters.size`），而本地序号可能有洞 —— 用户删过站点的中间章节后，
剩余行的序号是 `0,1,4,5…` 但 `contentRef` 仍是原来那几章。拿位置硬比会让这本书**永远**判成
`Diverged`、再也追不了更，而它完全满足「只追加、不动既有章」的安全条件。
按 `content_ref` 定位后仍能正常追加，安全性不减：既有行的 index 与 contentRef 一个都不动（I2）。

这与本地书补章 `mergeTailChapters`（`BookRepository.kt:616-622`）共用「分叉即整笔放弃」的取舍，
但判据不同：那边要求归一化章名序列是**严格前缀**，因为它的产出要把整本章节**内容**搬过去、
位置必须逐字对齐；本方法的产出只是目录行的追加，不搬运任何既有章。

**`local` 为空是合法的**：返回 `Appendable(remote)`。一本刚加书架但目录抓取失败过的书，
下一次重抓就走这条路径把目录补齐。

**远端为空不由本函数判**：`remote.isEmpty()` 且 `local` 非空时上式给出 `Diverged`，
但「一本书零章」在本仓不是合法状态（详情页对空目录走 `loadError` 态，换源侧另有
`SourceSwitchFeedback.EmptyCatalog`），它更可能是解析规则失配而非站点删光了章。
因此由 §4.2 在调用 diff **之前**挡掉，按失败处置且不写时间戳。

### 4.2 `BookRepository.syncChaptersFromSource()`

位置：现有 `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt`
（**不在未提交改动里**，可独立提交）。仓库已注入本方法所需的全部依赖：
`bookSourceManager`、`chapterListDao`、`bookInfoDao`、`transactions`。

```kotlin
suspend fun syncChaptersFromSource(
    bookShelf: BookShelfEntity,
    force: Boolean = false,
): ChapterSyncResult
```

结果类型与同文件的 `ImportMergeResult`（`BookRepository.kt:760-772`）同形态 —— sealed class、
每个分支都要让 UI 说得出人话：

```kotlin
sealed class ChapterSyncResult {
    /** 追加成功。[appended] 是已按 I3 定好序号的新行；因 I2，调用方可直接 `本地 + appended` 更新状态 */
    data class Appended(val appended: List<ChapterListEntity>) : ChapterSyncResult()
    /** 远端与本地一致，没有新章 */
    data object UpToDate : ChapterSyncResult()
    /** 目录结构分叉，本地未改动，需要用户处置（换源或重新导入） */
    data object Diverged : ChapterSyncResult()
    /** 限频窗口内，未发任何网络请求 */
    data object Throttled : ChapterSyncResult()
    /** 本地书没有「远端目录」这回事，不参与检查 */
    data object NotNetworkBook : ChapterSyncResult()
    /** 检查未完成（源失效 / 网络 / 解析 / 远端目录为空）。未写时间戳，下次会重试 */
    data class Failed(val cause: Throwable) : ChapterSyncResult()
}
```

执行次序（每一道 guard 都在任何写入之前）：

1. `bookShelf.tag == BookShelfEntity.LOCAL_TAG` → `NotNetworkBook`。
   本地书目录由导入决定，拿 `loc_book` 去问 `book_source` 表永远查不到行
   （同 `switchSource` 的既有 guard，`BookRepository.kt:293-297`）。
2. `!force` 且判窗未到期 → `Throttled`（§6）。
3. `bookSourceManager.getParserFor(tag)` 为 null → `Failed(BookSourceNotFoundException(tag))`。
   **不抛**：这是静默路径，抛出会让调用方必须 try-catch 一遍；类型化 `Failed` 已足够让
   手动刷新场景给出「书源已失效」提示。
4. `parser.getChapterList(bookShelf).data.chapterList` 取远端目录。
   `CancellationException` 原样上抛（同 `switchSource` / `addFromSearch` 的既有口径，
   吞掉会让销毁中的页面渲染成错误态）；其余异常 → `Failed(e)`，不写时间戳。
5. 远端目录为空 → `Failed(IllegalStateException(...))`，不写时间戳（理由见 §4.1 末段）。
6. `ChapterTocDiff.diff(local, remote)` 分派：
   - `UpToDate` → 写时间戳，返回 `UpToDate`。
   - `Diverged` → 写时间戳（I4），返回 `Diverged`，**不动 `chapter_list`**。
   - `Appendable(tail)` → 在**一个写事务**里（`transactions.run { ... }`）：
     按 I3 从 `max(local.durChapterIndex) + 1` 起**重排** `tail` 的 `durChapterIndex`
     （这是必须做的一步，远端自带的序号是位置序号、与有洞的本地不同口径，见 I3），
     `noteUrl` 与 `tag` 也以入参 `bookShelf` 为准重写一遍 —— 两个 parser 其实都已正确填充
     （`JsoupBookParser.kt:404,408`、`ScriptTocPager.kt:67`），重写只是一道廉价防线：
     调用方才是「这一行属于哪本书、归哪个源」的事实源，不该依赖 parser 记得填。
     随后 `chapterListDao.insertAll(rows)`，再写时间戳。
     事务成功后发 `BookShelfEvent.ChaptersUpdated(更新后的实体)`（§8）。

**事件只在事务提交之后发**：未提交的写不是事实，这条口径与 `switchSource`（`BookRepository.kt:321-332`）一致。

### 4.3 触发点接线

两处，都走 `force = false`（受限频保护）。

**详情页（书架入口）** —— `BookDetailViewModel`（干净文件）：

- `initFromBookShelf(shelf)`（`:92-95`）现在是同步纯赋值，改为赋值后 `viewModelScope.launch` 一次静默 sync。
- `Appended` → 更新目录并提示「已更新 N 章」（经 `sendToast`，文案走字符串资源）。
  **必须 `copy` 出新实体经 `_detailState.update { it.copy(bookShelf = ...) }` 提交**，
  不能就地改 `bookShelf.chapterList`：`BookShelfEntity` 是装在 `StateFlow` 里的 data class，
  改它的字段不改变对象引用，`StateFlow` 判等后不会重发，页面目录就停在旧长度。
  拼接口径是 `旧 + appended`（I2 保证这样拼是对的，不需要重查库）。
- `Diverged` → 置 `BookDetailUiState` 新增字段 `tocDiverged = true`。UI 在 `BookDetailActivity.kt:273-298`
  那块「详情拉取状态」区加一条提示，形态沿用同处 `loadError` 那条可点击 `Text`
  （文案「目录结构已变化，请换源或重新导入」，颜色用 `MaterialTheme.colorScheme.error`）。
  该字段只在静默检查得出 `Diverged` 时置真，不持久化（§7 说明了代价与阶段 2 的补法）。
- `Throttled` / `UpToDate` / `NotNetworkBook` / `Failed` → 静默（决策 8）。
  **绝不因为 sync 失败而置 `loadError`**：本地目录是完好的，失败只意味着「这次没查到有没有新章」，
  把它渲染成错误态会让一本能正常阅读的书显示「加载失败」。

阶段 1 **不新增「刷新目录」按钮**。`force` 参数留给阶段 2 的书架下拉刷新用。

**阅读页（到达末章）** —— `BookReadViewModel`（干净文件）+ 阅读器宿主：

- `BookReadViewModel` 新增 `suspend fun appendChaptersIfAny(): List<ChapterListEntity>?`（落地时定的名字，
  设计稿原写 `checkForNewChapters`）：内部对 `bookShelf` 调 `syncChaptersFromSource`，`Appended` 时
  就地更新 `bookShelf.chapterList` 并把新章交回宿主。返回值刻意只用「有没有新章」表达，
  因为除 `Appended` 之外宿主什么都不用做，交回整个结果只会让页面去 switch 六个分支。
  方法内带单飞标志，挡住末章来回翻时的重入。
- 调用点在阅读器的「到达末章」边界。**精确接线位置在 plan 阶段对着届时的代码定**
  （`ReaderPager.kt` / `ReadBookActivity.kt` 当前处于未提交改动中，见 §0.1）。
- 成功后的重载**复用换源那条已验证的路径**（`ReadBookActivity.kt:910-941` 的 `onSwitched` 回调）：
  `rePaginate(typesetter, startFromCurrent = true)` → 更新页面本地状态 `sliderValue`（「共 M 章」变了）
  与 `chapterTitle`。
  与换源的差别有三点：**不需要整体替换 `viewModel.bookShelf`**（换源是换了另一本书，
  这里还是同一本，`checkForNewChapters` 已就地更新它的 `chapterList`；阅读器的
  `getChapter` / `getChapterListSize` 都是现取该字段，故就地更新即可生效）、
  `startFromCurrent = true`（保持当前位置，换源是 `false` 因为页级进度不跨源）、
  不调 `gotoPage`（章序号没漂移，I2）。
  之所以复用而不新造：那段回调的注释已经把「为什么必须整体替换 `bookShelf` 而不是另存一份」讲清楚了，
  另立第二条重载路径早晚两处各自演进、写错书。
- 频率保护不需要额外做：用户在末章来回翻会反复触发，但 `Throttled` 是纯本地判断、零网络请求。

## 5. 数据流

```
用户从书架点开一本书
  → BookDetailViewModel.initFromBookShelf(本地实体)   // 先渲染，页面不空白
  → launch { syncChaptersFromSource(shelf) }
       ├─ 本地书？        → NotNetworkBook（静默）
       ├─ 5 分钟内查过？  → Throttled（静默，零网络）
       ├─ 取 parser 失败？→ Failed（静默，不写时间戳）
       └─ parser.getChapterList()  // 可能翻多页目录，这是限频存在的理由
            ├─ 远端为空？  → Failed（静默，不写时间戳）
            └─ ChapterTocDiff.diff(本地, 远端)
                 ├─ UpToDate    → 写时间戳，静默
                 ├─ Diverged    → 写时间戳，详情页提示条
                 └─ Appendable  → 事务[重排序号 + insertAll + 写时间戳]
                                  → emit ChaptersUpdated
                                  → 详情页目录变长 + Toast「已更新 N 章」
                                  → 书架页收到事件 refreshData()
```

## 6. 限频与时间戳

- **事实源**：`book_info.final_refresh_data`（Long，毫秒）。不加新列、不做迁移（决策 4）。
- **窗口**：`BookShelfEntity.REFRESH_TIME`（5 分钟，现存零引用常量，本次接上）。
- **判窗纯函数**，照 `module_me` 的 `ReleaseStateStore.isRefreshDue(lastSuccessMillis, nowMillis, intervalMillis)`
  （`ReleaseStateStore.kt:122-127`）形态，便于单测：
  `finalRefreshData == 0L || now - finalRefreshData >= REFRESH_TIME`。
- **写入路径**：新增 `BookInfoDao` 定向查询（决策 5）
  `@Query("UPDATE book_info SET final_refresh_data = :timestamp WHERE note_url = :noteUrl")`。
  不走「读回整行改字段再 insert」—— `BookInfoDao.insert` 是整行 REPLACE，漏填任一字段就把书名/封面抹成默认值。
- **连带的既有缺陷修正**：`BookRepository.writeEntry`（`:157-185`）加书架时要把 `finalRefreshData` 写成当前时间。
  加书架那一刻已经抓过一次目录（`BookShelfManager.addFromSearch` 调了 `getBookInfo` + `getChapterList`），
  不写就等于刚加的书进详情页立刻白抓一次多页目录。
- **存量数据**：现有网络书的 `final_refresh_data` 全是 `0`，升级后第一次进详情页会触发一次检查 ——
  这是期望行为（它们从来没被检查过），不需要数据修补。

## 7. 错误处置矩阵

| 情形 | 结果 | 写时间戳 | 改本地目录 | 自动触发时 | 手动刷新时 |
|---|---|---|---|---|---|
| 本地书 | `NotNetworkBook` | 否 | 否 | 静默 | 静默 |
| 限频窗口内 | `Throttled` | 否 | 否 | 静默 | ——（`force` 绕开） |
| 源被删 / `tag` 空白 | `Failed(BookSourceNotFoundException)` | 否 | 否 | 静默 + 日志 | 提示「书源已失效」 |
| 网络异常 / 解析异常 | `Failed(cause)` | 否 | 否 | 静默 + 日志 | 提示 `cause.userMessage()` |
| 远端目录为空 | `Failed(IllegalStateException)` | 否 | 否 | 静默 + 日志 | 提示 |
| 远端 = 本地 | `UpToDate` | **是** | 否 | 静默 | 提示「已是最新」 |
| 远端是本地超集 | `Appended` | **是** | 追加 | Toast「已更新 N 章」 | 同左 |
| 不是前缀关系 | `Diverged` | **是** | **否** | 详情页提示条 | 同左 |

矩阵的「手动刷新时」一列描述的是 `force = true` 的行为。**阶段 1 没有任何手动入口**
（§4.3：不新增「刷新目录」按钮），该列是为阶段 2 的书架下拉刷新预先定下的口径，不是本阶段漏实现。

手动刷新的提示统一经 `lib_book_common` 的 `reportFailure`（`FailureReport.kt:40-52`）——
它已内置会话过期收口（`isSessionExpiredHandled` 命中时只记日志、不重复提示），
按 AGENTS.md 约定**不要再手写 `isSessionExpiredHandled` 分支**。

`Diverged` 不持久化的代价：用户 5 分钟内第二次进详情页看不到提示条（限频挡掉了检查）。
可接受 —— 他第一次已经看到了，且提示在每次限频到期后会重新出现（这是持续性故障，重复提示是对的）。
阶段 2 若要把它做成书架上的持久标记，需要新列与迁移，见 §10。

## 8. 事件与消费方改动面

`BookShelfEvent`（`BookRepository.kt:745-754`）新增第四个分支：

```kotlin
/** 目录追加了新章（章节内容未下载，仅目录变长） */
data class ChaptersUpdated(val bookShelf: BookShelfEntity) : BookShelfEvent()
```

现有 3 个分支的 4 个收集方全部使用**穷举 `when`（无 `else`）**，因此加一个分支会让这 4 处编译失败，
每处都必须补：

| 收集方 | 补的分支 |
|---|---|
| `BookListViewModel.kt:41-47`（书架页） | `is ChaptersUpdated -> refreshData()`（目录变长要重查，阶段 2 的红点也挂这里） |
| `BookDetailViewModel.kt:72-87`（详情页） | `is ChaptersUpdated -> Unit`（详情页自己就是发起方，状态已就地更新） |
| `module_find/.../ChoiceBookViewModel.kt:66-76` | `is ChaptersUpdated -> Unit`（与 `ProgressUpdated` 同处置） |
| `module_find/.../SearchViewModel.kt:113-123` | `is ChaptersUpdated -> Unit`（同上） |

另有两处测试断言事件序列，需跟进：`lib_book_common/src/test/.../BookRepositoryTest.kt:157-195`、
`BookRepositorySwitchSourceTest.kt:461-471`。

`Diverged` **不发事件**：数据库没有任何变化，发了等于让消费方去重查一个没变的东西。

## 9. 测试策略

**`ChapterTocDiffTest`（纯函数，穷举边界，无需任何假件）**：

- 远端 = 本地 → `UpToDate`
- 远端多出 1 章 / N 章 → `Appendable`，tail 内容与顺序正确
- 本地为空、远端非空 → `Appendable(全部远端)`
- 两侧都为空 → `UpToDate`
- 首章 `contentRef` 就不同 → `Diverged`
- 中段某章 `contentRef` 不同 → `Diverged`
- 远端比本地短 → `Diverged`
- `contentRef` 全同但章名不同 → 仍按前缀判定（决策 2：章名不参与）
- 本地有洞（`durChapterIndex` 不连续）→ 按位置比对仍正确
- 两侧输入乱序 → 内部各自 `sortedBy` 后比对仍正确

**`BookRepositoryChapterSyncTest`（假 DAO + 假 parser，沿用 `BookRepositoryTest` 现有假件形态）**：

- 本地书 → `NotNetworkBook`，且未调 parser
- 限频窗口内 → `Throttled`，且未调 parser（用假 parser 的调用计数断言）
- `force = true` 绕开限频
- `final_refresh_data == 0` 视为到期
- 源缺失 → `Failed`，未写时间戳、未动 `chapter_list`
- parser 抛异常 → `Failed`，未写时间戳；`CancellationException` 原样上抛而非包成 `Failed`
- 远端目录为空 → `Failed`，未写时间戳
- `UpToDate` → 写了时间戳，`chapter_list` 未变，未发事件
- `Diverged` → 写了时间戳，`chapter_list` 未变，未发事件
- `Appended` → `chapter_list` 增加 N 行、序号从 `max + 1` 起、写了时间戳、发了 `ChaptersUpdated`
- `Appended` 且本地有洞 → 新序号从 `max + 1` 起而非 `size`，且**落库后全书 `durChapterIndex` 无重复**
  （I3 的回归锁：远端自带的是位置序号，直接沿用会与既有行撞号且不报错）
- 追加行的 `noteUrl` / `tag` 取自入参 `bookShelf`，而非远端行自带值

**现有测试跟进**：§8 列出的两处事件序列断言；4 个穷举 `when` 消费方若已有测试则补分支覆盖。

**人工装机验证项（Agent 不做，按 AGENTS.md 分工留给人工，提交时必须显式交代）**：

1. 真书进详情页 → 目录变长 + Toast「已更新 N 章」
2. 断网进详情页 → 不弹任何错误、不显示「加载失败」、本地目录照常可读
3. 阅读到末章 → 触发检查，新章可继续翻下去，且**当前位置与页码不变**
4. 5 分钟内反复进出详情页 → 只发一次目录请求（看网络日志）
5. 已下载章文件在追加后仍读到正确内容（I2 的装机验证）

## 10. 阶段 2 展望（书架批量检查 + 红点，本文不实现）

阶段 1 落地后，阶段 2 只是「对全书架逐本调同一个方法 + 一个红点」，但有四件新事要先想清楚：

- **遍历骨架可复用**：`DownloadRepository.getNextDownloadTask`（`DownloadRepository.kt:73-82`）已有
  「`for (shelf in bookShelfDao.getAllBooks())` + 排除 `LOCAL_TAG` + 跳过暂停书」的循环形态，
  差别是它「找到一本就返回」，批量检查要改成全遍历。
- **并发上限**：N 本 × 多页目录会打出大量请求，需要串行或限并发 2-3，并考虑复用 `DownloadService` 的
  章间隔节流口径（`CHAPTER_INTERVAL_MS = 800L`，`DownloadService.kt:675`）。
- **红点状态需要持久化 ⇒ 大概率要新列 + `MIGRATION_8_9`**：
  「有 N 章更新」**不能**从 `chapterList.size - 1 - durChapter` 派生 —— 那是「剩余未读章数」，
  一本读到中途的书会永远亮红点。需要记「上次用户看到目录时的章数」这类事实。
  同理 `Diverged` 若要变成书架上的持久标记也需要落库。
  按 AGENTS.md 的迁移纪律：version +1、追加紧邻的 `MIGRATION_8_9`、提交 Room 生成的新 schema JSON。
- **不引入 WorkManager**：版本目录已声明 `androidxWork = 2.11.0`（`libs.versions.toml:32`）但全仓零使用，
  也没有任何周期任务先例。只有在「App 没开也要定期检查」时才需要它，而「打开 App 就知道哪些书更新了」
  用前台检查已足够。引入 WorkManager 要连带处理 Hilt 装配（`androidx-hilt-ext-work`）与进程门
  （AGENTS.md：`Application` 上不许有 eager `@Inject` 字段，沙箱进程会崩），成本远高于收益。

## 11. ADR 与文档同步

**新增一篇 ADR**，主题：目录重抓的前缀不变式与「分叉即整笔放弃」。

必须写 ADR 的判据（AGENTS.md）：有真实权衡（自愈能力 vs 静默读错章的风险），
且无上下文会让人惊讶 —— 后人看到「远端目录明明有数据却整笔不写」很容易以为是漏实现而"修好"它，
一修就破坏 I2、让已下载章文件与目录错位。

按仓内 ADR 极简派写（标题 + 1~3 句 + 必要的被拒方案），格式以 `docs/adr/ADR-FORMAT.md` 为准；
正文不交叉引用本仓其他 ADR，需要别篇决策的背景写成自足描述。

**其他文档同步**：

- `CONTEXT.md`：若「章节更新检查」「前缀不变式」构成新领域术语，补进术语表（纯术语，不写实现细节）。
- `AGENTS.md`「Agent 实战建议」：补一条「涉及目录重抓时先读该 ADR」，并把 I2 / I3 两条硬约束点出来
  （章文件按序号命名 ⇒ 序号不得漂移；新索引取 `max + 1` 不取 `size`）。
- `docs/test-coverage-todo.md`：登记 §9 的人工装机验证项。

## 12. 非目标

- 不做章名兜底重匹配（决策 1 的被拒方案 ①）
- 不做整本目录替换与章文件迁移（决策 1 的被拒方案 ②）
- **不自动下载新章正文**：本功能只更新目录，正文仍由用户经下载中心发起（ADR-0018 那套前台服务约定不变）
- 不做通知栏推送
- 不引入 WorkManager 或任何周期任务
- 阶段 1 不改 DB schema
- 本地书不参与（`NotNetworkBook`）
- 不做书架批量检查与红点（阶段 2，§10）
