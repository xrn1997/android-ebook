# 书架网络书章节更新检查（阶段 1）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让书架上的网络书在进详情页与读到末章时静默重抓目录，按「本地是远端前缀」判定追加新章，不是前缀则整笔放弃并提示用户。

**Architecture:** 三层单向：`ChapterTocDiff` 是零 IO 的纯函数（只做前缀判定）；`BookRepository.syncChaptersFromSource` 负责编排（限频判窗 → 取源 → 抓目录 → 调 diff → 事务落库 → 发事件）；两个 ViewModel 只做接线与 UI 反馈。限频事实源复用已存在但从未写过的 `book_info.final_refresh_data` 列，**阶段 1 不改 DB schema**。

**Tech Stack:** Kotlin、Coroutines/Flow、Room 3（`androidx.room3`）、Hilt、JUnit4 + Robolectric、Jetpack Compose。

**设计依据：** `docs/superpowers/specs/2026-09-13-chapter-update-check-design.md`（下称 spec）。
每个任务都标了对应 spec 小节；不变式 I1–I4 在 spec §3。

---

## 开工前置条件（状态说明）

**For agentic workers:** 本计划写于 2026-09-13 并已于同日执行完毕（见下方「落地报告」）。
原先挂的两条前置条件现状如下：

1. ~~工作区必须先清干净~~ **已不成立**：写作时有 29 个未提交文件属于阅读器上下滚屏那批，
   曾与本计划撞 `strings.xml`、`ReadBookActivity.kt`、`ReaderPager.kt` 三个文件；
   那批已随 `2b20d51a`/`8ec85425` 提交，执行时三个文件均只含本计划的改动，可整文件 stage。
2. **行号确已变过**：Task 7 引用的行号（482/509/512/519/539/926）是写作时点快照，落地时按下列
   判据重新定位并成功接线 —— `rememberReaderTypesetter(` 声明处、`ReaderPagerController(` 与
   `ReaderScrollController(` 各自的 `onProgress = { c, p ->` lambda、换源那条
   `activity.rePaginate(typesetter, startFromCurrent = false)`。**后来者仍照判据定位，别照行号硬找。**
   若控制器签名再变，以测试用例为契约、以真实 API 为准，不要照抄本计划的代码。
3. **本计划中所有"Run:"命令在 Git Bash 下执行**；PowerShell 用户把 `./gradlew` 换成 `.\gradlew`。
4. **勾选框 `- [ ]` 不代表待办状态**（AGENTS.md：本仓 plan 的勾选项不可信）。进度以代码实况为准。

### 落地报告（2026-09-13，Task 1–8 已完成，未提交）

全量 `./gradlew test` 通过；`:module_book:assembleDebug` 与 `:module_find:assembleDebug` 通过。
新增 35 例（`ChapterTocDiffTest` 14 / `BookRepositoryChapterSyncTest` 18 / `BookInfoDaoTest` 3），
均无 skip。**照本计划动工前先看这里**，下列六处与计划不一致，实现为准：

1. **Task 1 的判定规则已被推翻**（最重要的一条）。计划里写的「按位置逐位比对 `contentRef`」
   在单测下暴露缺陷：远端序号是 parser 按位置连写的，本地序号可能有洞（用户删过章），
   按位置硬比会让这类书**永远**判成 `Diverged`、再也追不了更。实现改为
   **按 `content_ref` 在远端定位 + 校验相对顺序**，`ChapterTocDiff.diff` 的函数体与本计划 Step 3
   那份不一样。不变式与「分叉即放弃」的取舍没变，细节见 spec §4.1 与 ADR-0039。
2. **Task 2 漏了一个必改文件**：`BookInfoDao` 加方法会打破它的测试替身，
   必须同步给 `lib_book_common/src/test/.../FakeDaos.kt` 的 `FakeBookInfoDao` 补 `setFinalRefreshData`
   override，否则 `:lib_book_common:compileDebugUnitTestKotlin` 直接失败。
3. **Task 4 的 `ThrowingBookParser` 不能继承 `RecordingBookParser`** —— 后者是 final
   （Kotlin 类默认 final），实现改为直接实现 `BookParser` 的五个方法。
4. **Task 7 的方法名与签名变了**：spec 原写 `checkForNewChapters(): ChapterSyncResult`，
   落地为 `appendChaptersIfAny(): List<ChapterListEntity>?`（宿主只关心「有没有新章」）。
5. **Task 7 的插入点**：`syncAtTailChapter` 必须声明在两个控制器**之前**（本计划正文已改），
   实际落在 `val chapterAll = viewModel.getChapterListSize()` 之后。
6. **Task 3 的 `TestBookStore` 没采用**：改用 JUnit `TemporaryFolder` Rule 传给 `BookStore(...)`，
   与 `BookRepositorySwitchSourceTest` 同口径，不共享 tmp 目录。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `lib_book_common/src/main/java/com/ebook/common/repository/ChapterTocDiff.kt` | 新建 | 前缀判定的纯函数 + `TocDiff` 结果类型。零 IO |
| `lib_book_common/src/test/java/com/ebook/common/repository/ChapterTocDiffTest.kt` | 新建 | 穷举前缀判定的边界形态（无需任何假件） |
| `lib_ebook_db/src/main/java/com/ebook/db/dao/BookInfoDao.kt` | 修改 | 加一条定向 UPDATE，只写 `final_refresh_data` |
| `lib_ebook_db/src/test/java/com/ebook/db/dao/BookInfoDaoTest.kt` | 新建 | 用 Robolectric + 内存库锁那条 SQL 的语义 |
| `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt` | 修改 | `ChapterSyncResult`、`isTocCheckDue`、`syncChaptersFromSource`；`writeEntry` 补写时间戳；`BookShelfEvent.ChaptersUpdated` |
| `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryChapterSyncTest.kt` | 新建 | 编排层的分支与「什么情况下不写时间戳」 |
| `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookListViewModel.kt` | 修改 | 新事件 → `refreshData()` |
| `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModel.kt` | 修改 | 书架入口静默 sync + `tocDiverged` 状态 + 新事件分支 |
| `module_book/src/main/java/com/ebook/book/BookDetailActivity.kt` | 修改 | `Diverged` 提示条 |
| `module_book/src/main/res/values/strings.xml` | 修改 | 两条新文案 |
| `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt` | 修改 | `checkForNewChapters()`（单飞） |
| `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt` | 修改 | 末章触发 + 追加后重分页 |
| `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/ChoiceBookViewModel.kt` | 修改 | 新事件分支（`Unit`） |
| `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/SearchViewModel.kt` | 修改 | 新事件分支（`Unit`） |
| `docs/adr/00NN-toc-resync-prefix-invariant.md` | 新建 | 见 Task 8（编号以届时 `docs/adr/` 最大值 +1 为准） |
| `AGENTS.md` / `CONTEXT.md` / `docs/test-coverage-todo.md` | 修改 | 文档同步（Task 8） |

**依赖顺序：** Task 1、Task 2 相互独立，可并行；Task 3 依赖 1+2；Task 4 依赖 3；Task 5、6 依赖 3+4；Task 7、8 最后。

---

### Task 1: `ChapterTocDiff` —— 前缀判定纯函数

对应 spec §4.1、不变式 I1/I2。这一步不碰网络与数据库，是整个功能的地基。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/repository/ChapterTocDiff.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/repository/ChapterTocDiffTest.kt`

- [ ] **Step 1: 写失败的测试**

创建 `lib_book_common/src/test/java/com/ebook/common/repository/ChapterTocDiffTest.kt`。
这是纯函数测试，不需要 `FakeDaos`，也不需要 Robolectric：

```kotlin
package com.ebook.common.repository

import com.ebook.db.entity.ChapterListEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterTocDiff] 的单元测试（纯 JVM，零假件）。
 *
 * 锁住 spec §3 的前缀不变式 I1：只有「本地逐位是远端的前缀」才允许写入，否则整笔放弃。
 * 这一层之所以必须独立成纯函数：判定错一次的后果是**静默读错章**
 * （章文件按序号命名，序号一漂移内容与文件就对不上，不报错也不闪退），
 * 而这种后果无法在集成测试里用眼睛发现，只能靠穷举边界形态钉住。
 */
class ChapterTocDiffTest {

    private companion object {
        const val NOTE_URL = "a.example/book/1.html"
        const val SOURCE = "https://a.example"

        /** 造第 [index] 章：contentRef 按序号变化，章名同序 */
        fun chapter(index: Int, name: String = "第${index + 1}章") = ChapterListEntity(
            noteUrl = NOTE_URL,
            durChapterIndex = index,
            contentRef = "$SOURCE/chapter/${index + 1}.html",
            durChapterName = name,
            tag = SOURCE,
        )

        /** 本地 [count] 章、序号连续从 0 */
        fun local(count: Int) = (0 until count).map { chapter(it) }

        /** 把某章换成另一个 contentRef（模拟站点改了章节地址） */
        fun withRef(list: List<ChapterListEntity>, at: Int, ref: String): List<ChapterListEntity> =
            list.mapIndexed { i, c -> if (i == at) c.copy(contentRef = ref) else c }
    }

    @Test
    fun `远端与本地逐位相同 - UpToDate`() {
        assertEquals(TocDiff.UpToDate, ChapterTocDiff.diff(local(3), local(3)))
    }

    @Test
    fun `两侧都是空目录 - UpToDate`() {
        assertEquals(TocDiff.UpToDate, ChapterTocDiff.diff(emptyList(), emptyList()))
    }

    @Test
    fun `远端多出 1 章 - Appendable 且 tail 只有那 1 章`() {
        val result = ChapterTocDiff.diff(local(3), local(4))
        assertTrue("实际 $result", result is TocDiff.Appendable)
        assertEquals(listOf(chapter(3)), (result as TocDiff.Appendable).tail)
    }

    @Test
    fun `远端多出多章 - tail 保序且数量正确`() {
        val result = ChapterTocDiff.diff(local(2), local(7)) as TocDiff.Appendable
        assertEquals((2 until 7).map { chapter(it) }, result.tail)
    }

    @Test
    fun `本地为空而远端非空 - Appendable 带出全部远端`() {
        // 加书架时目录抓取失败的书，下一次重抓走这条路径把目录补齐
        val result = ChapterTocDiff.diff(emptyList(), local(3)) as TocDiff.Appendable
        assertEquals(local(3), result.tail)
    }

    @Test
    fun `首章 contentRef 就不同 - Diverged`() {
        val remote = withRef(local(4), at = 0, ref = "https://a.example/other/1.html")
        assertEquals(TocDiff.Diverged, ChapterTocDiff.diff(local(3), remote))
    }

    @Test
    fun `中段某章 contentRef 不同 - Diverged（中间插章或删章重排）`() {
        val remote = withRef(local(4), at = 1, ref = "https://a.example/other/2.html")
        assertEquals(TocDiff.Diverged, ChapterTocDiff.diff(local(3), remote))
    }

    @Test
    fun `远端比本地短 - Diverged（站点删章不得跟着删本地）`() {
        assertEquals(TocDiff.Diverged, ChapterTocDiff.diff(local(5), local(3)))
    }

    @Test
    fun `contentRef 全同但章名不同 - 仍判 UpToDate（章名不参与判定）`() {
        // spec 决策 2：站点改标题不代表换了书，拿章名参与判定会把同一章判成分叉
        val remote = listOf(chapter(0, name = "改名了"), chapter(1, name = "也改名了"))
        assertEquals(TocDiff.UpToDate, ChapterTocDiff.diff(local(2), remote))
    }

    @Test
    fun `本地序号有洞 - 按位置比对仍得出 Appendable`() {
        // 历史删章留下的洞：durChapterIndex 不连续，但序列逐位仍是远端前缀
        val holed = listOf(chapter(0), chapter(1), chapter(5))
        val result = ChapterTocDiff.diff(holed, local(4)) as TocDiff.Appendable
        assertEquals(listOf(chapter(3)), result.tail)
    }

    @Test
    fun `输入乱序 - 内部按 durChapterIndex 排序后仍得出正确结论`() {
        // @Relation 关联查询不带 ORDER BY、按物理 rowid 返回，上游传入的顺序不保证
        val shuffledLocal = listOf(chapter(1), chapter(0), chapter(2))
        val shuffledRemote = listOf(chapter(3), chapter(2), chapter(0), chapter(1))
        val result = ChapterTocDiff.diff(shuffledLocal, shuffledRemote) as TocDiff.Appendable
        assertEquals(listOf(chapter(3)), result.tail)
    }

    @Test
    fun `本地有洞且远端更长 - tail 数量等于两者长度差`() {
        val holed = listOf(chapter(0), chapter(1), chapter(5), chapter(6))
        val result = ChapterTocDiff.diff(holed, local(8)) as TocDiff.Appendable
        assertEquals(4, result.tail.size)
    }
}
```

- [ ] **Step 2: 跑测试，确认它编译失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.ChapterTocDiffTest"`
Expected: 编译失败，报 `Unresolved reference 'ChapterTocDiff'` 与 `Unresolved reference 'TocDiff'`

- [ ] **Step 3: 写实现**

创建 `lib_book_common/src/main/java/com/ebook/common/repository/ChapterTocDiff.kt`：

```kotlin
package com.ebook.common.repository

import com.ebook.db.entity.ChapterListEntity

/**
 * [ChapterTocDiff.diff] 的三种结局（spec §4.1）。
 */
sealed interface TocDiff {
    /** 远端与本地逐位相同，没有新章 */
    data object UpToDate : TocDiff

    /**
     * 本地是远端的真前缀，[tail] 是远端尾部多出来的那些章。
     *
     * `tail` 里的 `durChapterIndex` 是 parser 给的**位置序号**，与本地有洞时不同口径，
     * 必须由调用方按 spec 的 I3 重排后才能落库（见 [BookRepository.syncChaptersFromSource]）。
     */
    data class Appendable(val tail: List<ChapterListEntity>) : TocDiff

    /** 不是前缀关系（站点改版 / 中间插章 / 删章重排），按不变式 I1 整笔放弃 */
    data object Diverged : TocDiff
}

/**
 * 目录前缀判定：本地目录序列是不是远端目录序列的前缀，是的话尾部多出了哪些章。
 *
 * 为什么独立成一个零 IO 的纯函数（spec 决策 3）：判定的后果是**静默读错章**——
 * 章文件按序号命名（`filesDir/books/<bookId>/cNNNNN.txt`），序号一漂移，
 * 用户点第 50 章读到的是旧的第 50 章，不报错、不闪退、页面上什么都正常。
 * 这种故障在集成测试里看不出来，只能靠穷举边界形态钉住，所以比对逻辑不能与 DAO、
 * parser 混在一个方法里（那样每写一条用例都要先搭一套假件）。
 *
 * 身份判据只用 `content_ref`（网络书即该站章节 URL），**章名不参与判定**（spec 决策 2）：
 * 站点改标题不代表换了书，而同一章在不同站标题写法也可能不一致。
 *
 * 与本地书补章 [BookRepository.mergeTailChapters] 是同构的判定（那边比的是归一化章名序列），
 * 差别只在身份判据；「分叉即整笔放弃」这个取舍两边共用同一份理由。
 */
internal object ChapterTocDiff {

    /**
     * 比对本地与远端目录。两侧都先按 `durChapterIndex` 排序（上游 `@Relation` 关联查询
     * 不带 ORDER BY、按物理 rowid 返回，顺序不保证），再**逐位**比 `contentRef`。
     *
     * 按位置比而不是按 index 值比：本地可能有洞（历史删章），此时 `local[i].durChapterIndex`
     * 不等于 `i`，但「第 i 章」这个位置语义仍然成立。
     *
     * @param local 本地 `chapter_list` 已有的行
     * @param remote 本次从书源抓回的目录行；**调用方必须先挡掉空列表**
     *   （「一本书零章」不是合法状态，按失败处置而非分叉，理由见 spec §4.1 末段）
     */
    fun diff(local: List<ChapterListEntity>, remote: List<ChapterListEntity>): TocDiff {
        val byIndex: List<ChapterListEntity> = local.sortedBy { it.durChapterIndex }
        val remoteSorted: List<ChapterListEntity> = remote.sortedBy { it.durChapterIndex }

        // 远端更短意味着本地有远端已经没有的章。跟着删会吃掉用户已下载的章文件，
        // 判定成分叉让上层去提示，比静默删章安全。
        if (byIndex.size > remoteSorted.size) return TocDiff.Diverged

        for (i in byIndex.indices) {
            if (byIndex[i].contentRef != remoteSorted[i].contentRef) return TocDiff.Diverged
        }

        val tail = remoteSorted.drop(byIndex.size)
        return if (tail.isEmpty()) TocDiff.UpToDate else TocDiff.Appendable(tail)
    }
}
```

- [ ] **Step 4: 跑测试，确认全绿**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.ChapterTocDiffTest"`
Expected: PASS，12 个用例全过

注意 `internal` 只在同模块测试源集可见，所以测试文件必须放在 `lib_book_common` 的 `src/test` 下
（AGENTS.md：跨模块不是 friend module，`internal` 会直接编译失败）。

- [ ] **Step 5: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/repository/ChapterTocDiff.kt \
        lib_book_common/src/test/java/com/ebook/common/repository/ChapterTocDiffTest.kt
git commit -m "feat(lib_book_common): 新增目录前缀判定纯函数，锁住重抓的整笔放弃语义"
```

---

### Task 2: `BookInfoDao` 的定向时间戳更新

对应 spec 决策 5、§6。**只加一条 SQL 查询，不动 schema、不写迁移。**

**Files:**
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/dao/BookInfoDao.kt`
- Test: `lib_ebook_db/src/test/java/com/ebook/db/dao/BookInfoDaoTest.kt`（新建）

- [ ] **Step 1: 写失败的测试**

创建 `lib_ebook_db/src/test/java/com/ebook/db/dao/BookInfoDaoTest.kt`。
形态照同目录的 `PausedBookDaoTest.kt`（Robolectric + Room 内存库，跑真实 SQL）：

```kotlin
package com.ebook.db.dao

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.AppDatabase
import com.ebook.db.entity.BookInfoEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BookInfoDao] 的回归测试（Robolectric + Room 内存库，JVM 上跑真实 SQL）。
 *
 * 锁的是 [BookInfoDao.setFinalRefreshData] 这条定向 UPDATE 的两件事：
 * 1. **只改 `final_refresh_data` 一列**，其余字段逐字不变。这条断言是给「顺手改用
 *    insert(REPLACE) 写时间戳」准备的 —— 整行 REPLACE 要求调用方传完整对象，
 *    漏读或漏填任一字段就会把书名/封面/简介抹成实体默认值，而页面只是少显示几个字段、
 *    不会崩，属于典型的静默数据损坏（AGENTS.md「主键策略两套」那条同源的问题）。
 * 2. 按主键精确命中，不误伤别的行；行不存在时静默不写、不抛。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookInfoDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BookInfoDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 不 setDriver(...)：与 PausedBookDaoTest / SearchHistoryDaoTest 同口径，
        // 锁 DAO 的 SQL 语义而非某个引擎的行为
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.bookInfoDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `写时间戳只动那一列，其余字段逐字不变`() = runBlocking {
        val original = BookInfoEntity(
            name = "斗破苍穹",
            author = "天蚕土豆",
            noteUrl = URL_A,
            chapterUrl = "$URL_A/toc",
            coverUrl = "$URL_A/cover.jpg",
            introduce = "简介",
            origin = "起点",
            status = "连载中",
            tag = SOURCE,
            finalRefreshData = 0L,
        )
        dao.insert(original)

        dao.setFinalRefreshData(noteUrl = URL_A, timestamp = 1_700_000_000_000L)

        val after = dao.getBookInfoByUrl(URL_A)
        assertEquals(
            "定向 UPDATE 不应改动除 final_refresh_data 以外的任何列，否则整行 REPLACE 的漏填会静默毁数据",
            original.copy(finalRefreshData = 1_700_000_000_000L),
            after,
        )
    }

    @Test
    fun `按 noteUrl 精确命中，不误伤另一本书`() = runBlocking {
        dao.insert(BookInfoEntity(noteUrl = URL_A, name = "A"))
        dao.insert(BookInfoEntity(noteUrl = URL_B, name = "B", finalRefreshData = 111L))

        dao.setFinalRefreshData(noteUrl = URL_A, timestamp = 222L)

        assertEquals(222L, dao.getBookInfoByUrl(URL_A)?.finalRefreshData)
        assertEquals(111L, dao.getBookInfoByUrl(URL_B)?.finalRefreshData)
    }

    @Test
    fun `行不存在时静默不写、不抛`() = runBlocking {
        dao.setFinalRefreshData(noteUrl = "nonexistent", timestamp = 1L)
        assertEquals(null, dao.getBookInfoByUrl("nonexistent"))
    }

    private companion object {
        const val URL_A = "a.example/book/1.html"
        const val URL_B = "a.example/book/2.html"
        const val SOURCE = "https://a.example"
    }
}
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `./gradlew :lib_ebook_db:testDebugUnitTest --tests "com.ebook.db.dao.BookInfoDaoTest"`
Expected: 编译失败，报 `Unresolved reference 'setFinalRefreshData'`

- [ ] **Step 3: 加那条查询**

在 `lib_ebook_db/src/main/java/com/ebook/db/dao/BookInfoDao.kt` 的 `deleteByUrl` 声明之前插入：

```kotlin
    /**
     * 只写 `final_refresh_data` 一列（章节最后更新时间）。
     *
     * 为什么不用现成的 [insert]：那是整行 `OnConflictStrategy.REPLACE`，要求调用方传一个
     * 字段完整的对象。拿它写时间戳就要先读回整行、改一个字段、再写回去 ——
     * 中间漏读或漏填任何一个字段（书名、封面、简介…）就会把那列静默抹成实体默认值，
     * 页面只是少显示几项、不崩不报错，属最难发现的一类数据损坏。
     * 定向 UPDATE 让「改一列」在 SQL 层面就只碰那一列。
     *
     * 主要消费方是目录重抓的限频（见
     * `com.ebook.common.repository.BookRepository.syncChaptersFromSource`），
     * 语义是「上次得出结论的时间」而非「上次成功追加的时间」。
     * 行不存在时静默不写（不抛），调用方不需要先确认存在。
     */
    @Query("UPDATE book_info SET final_refresh_data = :timestamp WHERE note_url = :noteUrl")
    suspend fun setFinalRefreshData(noteUrl: String, timestamp: Long)
```

- [ ] **Step 4: 跑测试，确认全绿**

Run: `./gradlew :lib_ebook_db:testDebugUnitTest --tests "com.ebook.db.dao.BookInfoDaoTest"`
Expected: PASS，3 个用例

- [ ] **Step 5: 提交**

```bash
git add lib_ebook_db/src/main/java/com/ebook/db/dao/BookInfoDao.kt \
        lib_ebook_db/src/test/java/com/ebook/db/dao/BookInfoDaoTest.kt
git commit -m "feat(lib_ebook_db): BookInfoDao 新增只写章节时间戳的定向更新"
```

---

### Task 3: 限频判窗 + 加书架即视为已检查

对应 spec §6。含一个连带的既有缺陷修正。

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt:157-185`（`writeEntry`）
- Test: `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryChapterSyncTest.kt`（新建，本任务只写其中一部分）

- [ ] **Step 1: 写失败的测试**

创建 `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryChapterSyncTest.kt`。
假件全部复用现成的 `FakeDaos` / `DirectTransactionRunner`（同包 `internal`，可直接引用）
与 `FakeBookSourceManager` / `RecordingBookParser`（`com.ebook.common.analyze.source`）：

```kotlin
package com.ebook.common.repository

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.FakeBookSourceManager
import com.ebook.common.analyze.source.RecordingBookParser
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.WebChapterEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [BookRepository.syncChaptersFromSource] 与目录重抓限频的单元测试（纯 JVM，假 DAO + 假 parser）。
 *
 * 锁住 spec §3 的四条不变式在**编排层**的落点：
 * - I1 前缀不成立时一行目录都不写（比对本身由 [ChapterTocDiff] 的测试穷举，这里只锁「分派对了没有」）；
 * - I3 新序号从 `max + 1` 起，且落库后全书序号无重复（远端自带位置序号，直接沿用会撞号且不报错）；
 * - I4 时间戳只在得出结论后写：网络与取源失败**不写**（下次该重试），
 *   `UpToDate` 与 `Diverged` 都写（都是确定结论）；
 * - 失败一律不抛、以类型化结果带回（这是静默路径，抛出会逼每个调用方 try-catch 一遍）。
 */
class BookRepositoryChapterSyncTest {

    private companion object {
        const val SOURCE = "https://a.example"
        // noteUrl 在本仓同时是内容仓库的目录名（filesDir/books/<noteUrl>/），
        // 带 scheme 在 Windows 的 JVM 测试里会被当盘符，故取不带 https:// 的形态（同换源测试的口径）
        const val NOTE_URL = "a.example/book/1.html"

        fun chapter(index: Int) = ChapterListEntity(
            noteUrl = NOTE_URL,
            durChapterIndex = index,
            contentRef = "$SOURCE/chapter/${index + 1}.html",
            durChapterName = "第${index + 1}章",
            tag = SOURCE,
        )
    }

    private lateinit var daos: FakeDaos
    private lateinit var manager: FakeBookSourceManager
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        daos = FakeDaos()
        manager = FakeBookSourceManager()
        repository = BookRepository(
            bookShelfDao = daos.shelf,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            downloadChapterDao = daos.download,
            // 本路径不读正文，reader 一律用不上
            chapterReaders = emptyMap<BookFormat, ChapterReader>(),
            bookSourceManager = manager,
            bookStore = TestBookStore(),
            contentCache = ChapterContentCache(capacity = 3),
            transactions = DirectTransactionRunner,
        )
    }

    // ===== 限频判窗（纯函数） =====

    @Test
    fun `从未检查过（时间戳为 0）视为到期`() {
        assertTrue(isTocCheckDue(lastMillis = 0L, nowMillis = 9_000_000L))
    }

    @Test
    fun `窗口内不再检查`() {
        val now = 1_700_000_000_000L
        assertEquals(
            false,
            isTocCheckDue(lastMillis = now - BookShelfEntity.REFRESH_TIME + 1, nowMillis = now),
        )
    }

    @Test
    fun `恰好到期的那一刻算到期（边界是 >=）`() {
        val now = 1_700_000_000_000L
        assertTrue(isTocCheckDue(lastMillis = now - BookShelfEntity.REFRESH_TIME, nowMillis = now))
    }

    // ===== 加书架即视为已检查 =====

    @Test
    fun `addToShelf 把章节时间戳写成当前时间`() = runTest {
        // 加书架那一刻已经抓过一次目录（BookShelfManager.addFromSearch 调了 getChapterList）。
        // 不写时间戳，用户紧接着进详情页就会白爬一遍多页目录。
        val shelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹", finalRefreshData = 0L)
            chapterList = listOf(chapter(0))
        }

        repository.addToShelf(shelf)

        val stored = daos.info.getBookInfoByUrl(NOTE_URL)
        assertTrue(
            "加书架必须把 finalRefreshData 写成非零，否则刚加的书进详情页立刻重抓一次目录",
            (stored?.finalRefreshData ?: 0L) > 0L,
        )
    }
}
```

`TestBookStore()` 这个 helper 本任务先加上（后续任务会用）——在测试类之后追加：

```kotlin
/**
 * 指向临时目录的 [BookStore]：本测试不读写章文件，但 `BookRepository` 的构造要求有，
 * 而 [BookStore] 的构造需要一个根目录。用 `java.io.tmpdir` 下的一次性子目录，
 * 避免把 `TemporaryFolder` Rule 拖进每个用例。
 */
private class TestBookStore : BookStore(
    java.io.File(System.getProperty("java.io.tmpdir"), "ebook-chapter-sync-test").apply { mkdirs() }
)
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.BookRepositoryChapterSyncTest"`
Expected: 编译失败，报 `Unresolved reference 'isTocCheckDue'`

- [ ] **Step 3: 写判窗纯函数**

在 `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt` 文件末尾
（紧跟 `ImportMergeResult` 那个 sealed class 之后）追加：

```kotlin
/**
 * 目录重抓是否已到限频窗口（spec §6）。
 *
 * 独立成纯函数的理由与 `ReleaseStateStore.isRefreshDue` 同源：判据只有一个「距上次得出结论
 * 是否已超过间隔」，但它决定要不要发一批网络请求（目录可能要翻好几页）。
 * 混在仓库方法里就得为边界各搭一套假件才能测，抽出来三个断言即可穷举。
 *
 * `lastMillis == 0` 是「从来没检查过」，直接放行 —— 存量网络书的 `final_refresh_data`
 * 全是 0（该列此前只有本地书导入写过），升级后第一次进详情页会各触发一次检查，这是期望行为。
 *
 * 边界取 `>=`：恰好到期即放行。
 */
internal fun isTocCheckDue(
    lastMillis: Long,
    nowMillis: Long,
    intervalMillis: Long = BookShelfEntity.REFRESH_TIME,
): Boolean = lastMillis == 0L || nowMillis - lastMillis >= intervalMillis
```

同时给 `BookRepository.kt` 顶部补 import（`ChapterListEntity` 已导入，无需再加）：

```kotlin
import com.ebook.source.analyze.BookParser
```

- [ ] **Step 4: 修正 `writeEntry` 的时间戳**

在 `writeEntry` 里，把「先保存 bookInfo（如果存在）」那段改成：

```kotlin
        // 先保存 bookInfo（如果存在）。章节时间戳顺手写成「现在」：调用方（加书架 / 换源）
        // 都在刚才那次解析里抓过目录了，不写就等于让用户紧接着进详情页时白爬一遍多页目录。
        bookShelf.bookInfo?.let { bookInfo ->
            bookInfo.noteUrl = bookShelf.noteUrl
            if (bookInfo.finalRefreshData == 0L) {
                bookInfo.finalRefreshData = System.currentTimeMillis()
            }
            bookInfoDao.insert(bookInfo)
        }
```

（`BookInfoEntity.finalRefreshData` 是 `var`，可直接赋值。）

- [ ] **Step 5: 跑测试，确认全绿**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.BookRepositoryChapterSyncTest"`
Expected: PASS，4 个用例

- [ ] **Step 6: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt \
        lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryChapterSyncTest.kt
git commit -m "feat(lib_book_common): 目录重抓限频判窗并让加书架即视为已检查"
```

---

### Task 4: `syncChaptersFromSource` 编排

对应 spec §4.2、I3、I4、§7 错误处置矩阵。

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryChapterSyncTest.kt`（追加用例）

- [ ] **Step 1: 追加失败的测试**

在 `BookRepositoryChapterSyncTest` 类内追加。先加两个 helper（放在类的 `setUp()` 之后）：

```kotlin
    /** 登记一个「解出 [chapterCount] 章」的 parser，并记下实例以便断言调用次数 */
    private fun givenRemote(chapterCount: Int, noteUrl: String = NOTE_URL): RecordingBookParser {
        val shelf = BookShelfEntity(noteUrl = noteUrl, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = noteUrl, name = "斗破苍穹", finalRefreshData = 0L)
        }
        val parser = RecordingBookParser(
            ownedSourceUrl = SOURCE,
            bookInfoResult = shelf,
            chapterListData = shelf.copy(chapterList = (0 until chapterCount).map { chapter(it) }),
        )
        manager.putParser(SOURCE, parser)
        return parser
    }

    /** 走真实写入把一本有 [localCount] 章的网络书放进书架 */
    private suspend fun seedShelfWithChapters(localCount: Int) {
        val shelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
            chapterList = (0 until localCount).map { chapter(it) }
        }
        repository.addToShelf(shelf)
    }

    /** 从库里的行造一个条目（`syncChaptersFromSource` 的入参形态：调用方手里的实体） */
    private fun entryForSync(): BookShelfEntity = BookShelfEntity(
        noteUrl = NOTE_URL,
        tag = SOURCE,
    ).apply {
        bookInfo = daos.info.getBookInfoByUrl(NOTE_URL)
        chapterList = daos.chapter.getChaptersForBook(NOTE_URL)
    }
```

然后追加用例：

```kotlin
    // ===== guard：不触网的三种出口 =====

    @Test
    fun `本地书不参与目录检查，且不取 parser`() = runTest {
        val parser = givenRemote(3)
        val localBook = BookShelfEntity(
            noteUrl = NOTE_URL,
            tag = BookShelfEntity.LOCAL_TAG,
        )

        val result = repository.syncChaptersFromSource(localBook)

        assertEquals(ChapterSyncResult.NotNetworkBook, result)
        assertTrue("本地书不该被拿去查 parser：loc_book 在 book_source 表永远查不到行",
            manager.parserForCalls.isEmpty())
        assertEquals(0, parser.chapterListCalls.size)
    }

    @Test
    fun `限频窗口内直接返回，不发任何网络请求`() = runTest {
        val parser = givenRemote(4)
        seedShelfWithChapters(localCount = 3)

        val result = repository.syncChaptersFromSource(entryForSync())

        assertEquals(ChapterSyncResult.Throttled, result)
        assertEquals("被限频挡下时不该取 parser、更不该抓目录", 0, parser.chapterListCalls.size)
        assertEquals(emptyList<String>(), manager.parserForCalls)
    }

    @Test
    fun `force 绕开限频`() = runTest {
        val parser = givenRemote(4)
        seedShelfWithChapters(localCount = 3)

        repository.syncChaptersFromSource(entryForSync(), force = true)

        assertEquals(1, parser.chapterListCalls.size)
    }

    // ===== 失败：一律不写时间戳（I4 的另一半） =====

    @Test
    fun `取不到 parser 时以类型化失败带回，不写时间戳也不动目录`() = runTest {
        seedShelfWithChapters(localCount = 3)
        val before = daos.chapter.storedValues().size
        val beforeTs = daos.info.getBookInfoByUrl(NOTE_URL)?.finalRefreshData

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        assertTrue("实际 $result", result is ChapterSyncResult.Failed)
        assertEquals(before, daos.chapter.storedValues().size)
        assertEquals("失败不该写时间戳：那是暂时性故障，写了就等于把这个用户永久挡在检查之外",
            beforeTs, daos.info.getBookInfoByUrl(NOTE_URL)?.finalRefreshData)
    }

    @Test
    fun `抓目录抛异常时包成 Failed，不吞成 UpToDate`() = runTest {
        seedShelfWithChapters(localCount = 3)
        manager.putParser(SOURCE, ThrowingBookParser(java.io.IOException("网络断了")))

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        assertTrue("实际 $result", result is ChapterSyncResult.Failed)
    }

    @Test
    fun `远端目录为空按失败处置而非分叉，且不写时间戳`() = runTest {
        // 「一本书零章」不是合法状态，更可能是解析规则失配。写成 Diverged 会连带写时间戳，
        // 于是解析修好之后 5 分钟内都不会再试（spec §4.1 末段）。
        givenRemote(0)
        seedShelfWithChapters(localCount = 3)
        val beforeTs = daos.info.getBookInfoByUrl(NOTE_URL)?.finalRefreshData

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        assertTrue("实际 $result", result is ChapterSyncResult.Failed)
        assertEquals(beforeTs, daos.info.getBookInfoByUrl(NOTE_URL)?.finalRefreshData)
        assertEquals(3, daos.chapter.storedValues().size)
    }

    // ===== 三种结论 =====

    @Test
    fun `远端与本地一致时写时间戳、不加行、不发事件`() = runTest {
        givenRemote(3)
        seedShelfWithChapters(localCount = 0)
        repository.syncChaptersFromSource(entryForSync(), force = true)

        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)
        runCurrent()

        assertEquals(ChapterSyncResult.UpToDate, result)
        assertTrue("时间戳应已被写成非零",
            (daos.info.getBookInfoByUrl(NOTE_URL)?.finalRefreshData ?: 0L) > 0L)
        assertTrue("UpToDate 不该发事件：数据库没有任何变化", events.isEmpty())
    }

    @Test
    fun `分叉时写时间戳但一行目录都不动、也不发事件`() = runTest {
        // 本地第 2 章的 contentRef 与远端对不上 → 整笔放弃（不变式 I1）
        daosSeedDiverged()
        givenRemote(4)
        val chaptersBefore = daos.chapter.storedValues()

        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)
        runCurrent()

        assertEquals(ChapterSyncResult.Diverged, result)
        assertEquals("分叉时本地目录必须逐字不变", chaptersBefore, daos.chapter.storedValues())
        assertTrue("Diverged 不发事件", events.isEmpty())
        assertTrue("Diverged 是确定结论，要写时间戳（否则每次进详情页都重爬一遍目录）",
            (daos.info.getBookInfoByUrl(NOTE_URL)?.finalRefreshData ?: 0L) > 0L)
    }

    @Test
    fun `前缀成立时追加新章、写时间戳并发 ChaptersUpdated`() = runTest {
        givenRemote(5)
        seedShelfWithChapters(localCount = 3)

        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)
        runCurrent()

        val appended = (result as ChapterSyncResult.Appended).appended
        assertEquals(2, appended.size)
        assertEquals(5, daos.chapter.getChaptersForBook(NOTE_URL).size)
        assertEquals(listOf(3, 4), appended.map { it.durChapterIndex })
        assertEquals(listOf(chapter(3).contentRef, chapter(4).contentRef),
            appended.map { it.contentRef })
        assertEquals(1, events.size)
        assertTrue("实际 $events", events.single() is BookShelfEvent.ChaptersUpdated)
        assertEquals(5, (events.single() as BookShelfEvent.ChaptersUpdated).bookShelf.chapterList.size)
    }

    @Test
    fun `本地序号有洞时新章从 max+1 起，全书序号不得重复`() = runTest {
        // 这是本任务最要紧的一条回归锁。远端行的 durChapterIndex 是 parser 给的**位置序号**
        // （两个 parser 都写 chapters.size），本地有洞时两套口径不同：
        // 直接沿用会让两行撞同一个 index。撞号不报错（主键是 content_ref，两行都插得进去），
        // 但 chapterList.getOrNull(index) 会返回其中一行 —— 用户点某章读到另一章。
        seedShelfWithHoles()
        givenRemote(9)

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        val all = daos.chapter.getChaptersForBook(NOTE_URL)
        val appended = (result as ChapterSyncResult.Appended).appended
        assertEquals("有洞时 size 与 max+1 不等，用 size 会覆写既有章文件",
            listOf(7, 8), appended.map { it.durChapterIndex })
        assertEquals("全书序号必须唯一，否则就是静默读错章",
            all.size, all.map { it.durChapterIndex }.distinct().size)
    }

    @Test
    fun `本地目录为空时把远端整本写进去（加书架时抓目录失败的书靠这条自愈）`() = runTest {
        givenRemote(3)
        seedShelfWithChapters(localCount = 0)
        assertTrue(daos.chapter.storedValues().isEmpty())

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        assertEquals(3, (result as ChapterSyncResult.Appended).appended.size)
        assertEquals(3, daos.chapter.getChaptersForBook(NOTE_URL).size)
    }
```

再追加两个私有 helper 到测试类里（`entryForSync()` 之后）：

```kotlin
    /** 播种一本「本地第 2 章的 contentRef 与远端不同」的书，构成分叉 */
    private suspend fun daosSeedDiverged() {
        val shelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
            chapterList = listOf(
                chapter(0),
                chapter(1).copy(contentRef = "$SOURCE/old/2.html"),
                chapter(2),
            )
        }
        repository.addToShelf(shelf)
    }

    /** 播种一本序号有洞的书：0,1,2,5,6（模拟历史删章），远端第 3、4 章位置对应本地的 5、6 */
    private suspend fun seedShelfWithHoles() {
        val shelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
            chapterList = listOf(chapter(0), chapter(1), chapter(2), chapter(5), chapter(6))
        }
        repository.addToShelf(shelf)
    }
```

在文件末尾（`TestBookStore` 之后）加抛异常的 parser 与两个 import：

```kotlin
/** 抓目录必然失败的 parser，用来锁「异常不吞成 UpToDate」 */
private class ThrowingBookParser(
    private val failure: Throwable,
    override val ownedSourceUrl: String = SOURCE_URL,
) : RecordingBookParser(ownedSourceUrl = SOURCE_URL) {
    override suspend fun getChapterList(
        bookShelf: BookShelfEntity,
    ): WebChapterEntity<BookShelfEntity> = throw failure
}

private const val SOURCE_URL = "https://a.example"
```

需要在测试文件顶部补的 import：

```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import java.io.IOException
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.BookRepositoryChapterSyncTest"`
Expected: 编译失败，报 `Unresolved reference 'syncChaptersFromSource'` 与 `Unresolved reference 'ChapterSyncResult'`

- [ ] **Step 3: 写结果类型**

在 `BookRepository.kt` 末尾、`isTocCheckDue` 之后追加：

```kotlin
/**
 * [BookRepository.syncChaptersFromSource] 的结局。
 *
 * 与同文件的 [ImportMergeResult] 同形态：每个分支都要让 UI 说得出人话，
 * 因为「自动检查」的绝大多数结局对用户是不可见的（静默），只有 `Diverged` 与 `Appended`
 * 需要开口。把「不写时间戳」的三种（`Throttled` / `NotNetworkBook` / `Failed`）
 * 与「写了时间戳」的三种分开，是 spec §3 不变式 I4 的直译。
 */
sealed class ChapterSyncResult {
    /**
     * 追加成功。[appended] 是已按 I3 定好序号的新行。
     *
     * 因不变式 I2（已有章的序号与 contentRef 逐字不变），调用方可以直接 `旧 + appended`
     * 拼出新的完整目录，不必回头重查数据库。
     */
    data class Appended(val appended: List<ChapterListEntity>) : ChapterSyncResult()

    /** 远端与本地逐位相同，没有新章 */
    data object UpToDate : ChapterSyncResult()

    /** 目录结构分叉，本地未作任何改动，需要用户处置（换源或重新导入） */
    data object Diverged : ChapterSyncResult()

    /** 限频窗口内，未发任何网络请求 */
    data object Throttled : ChapterSyncResult()

    /** 本地书没有「远端目录」这回事 */
    data object NotNetworkBook : ChapterSyncResult()

    /**
     * 检查未完成（源失效 / 网络 / 解析 / 远端目录为空）。
     *
     * **不抛而是带回**：这是一条静默路径（用户没要求检查），抛出会逼每个调用方
     * try-catch 一遍，而 catch 完通常也只是记一行日志。`cause` 留着，
     * 只有用户主动刷新时才经 `reportFailure` 提示出去（spec §7）。
     */
    data class Failed(val cause: Throwable) : ChapterSyncResult()
}
```

- [ ] **Step 4: 写仓库方法**

在 `BookRepository.kt` 的 `refreshChapter` 方法之后插入：

```kotlin
    /**
     * 按需重抓目录并追加新章（阶段 1，见 spec §4.2）。
     *
     * 做的事：限频判窗 → 按这本书的 `tag` 取 parser → 抓远端目录 → 与本地 `chapter_list`
     * 做前缀比对 → 只在「本地是远端前缀」时把尾部新章追加进去。
     * 不做的事：**不下载任何正文**、**不删任何既有章**、**不改任何既有章的序号**。
     *
     * 为什么只接受纯追加（不变式 I1，spec §3 给了完整理由）：章文件按序号命名
     * （`filesDir/books/<bookId>/cNNNNN.txt`），序号一旦漂移就会静默读错章 ——
     * 用户点第 50 章读到旧的第 50 章，不报错、不闪退、页面上一切正常。
     * 站点改版 / 中间插章 / 删章重排这几种情形一律判 `Diverged` 整笔放弃，
     * 把处置权交回用户（换源有 `switchSource` 兜着）。
     *
     * 因为是纯追加，本方法**不需要失效任何缓存**（与 [refreshChapter]、[mergeTailChapters]
     * 的处置不同，那两处动的是已有章）：[ChapterContentCache] 的键是
     * `chapterRef(noteUrl, index)`，已有键的取值不变，故不调 `invalidateBook`。
     * 同理，与「用户正在阅读某一章」天然可并发，不需要加锁。
     *
     * @param force 跳过限频。自动触发一律 false；用户主动刷新才 true（阶段 1 没有手动入口）
     */
    suspend fun syncChaptersFromSource(
        bookShelf: BookShelfEntity,
        force: Boolean = false,
    ): ChapterSyncResult {
        // 本地书没有「源」可抓：它的 tag 恒为 loc_book，目录由导入那一刻决定。
        // 不挡就会拿 loc_book 去查 book_source 表（必然查不到）并误报「书源已失效」——
        // 同一取舍见 switchSource 开头的注释。
        if (bookShelf.tag == BookShelfEntity.LOCAL_TAG) return ChapterSyncResult.NotNetworkBook

        val noteUrl = bookShelf.noteUrl

        val localChapters: List<ChapterListEntity>
        val lastCheckMillis: Long
        withContext(Dispatchers.IO) {
            localChapters = chapterListDao.getChaptersForBook(noteUrl)
            lastCheckMillis = bookInfoDao.getBookInfoByUrl(noteUrl)?.finalRefreshData ?: 0L
        }
        if (!force && !isTocCheckDue(lastCheckMillis, System.currentTimeMillis())) {
            return ChapterSyncResult.Throttled
        }

        // 取源在任何 try 之外：null 的四种成因见 BookSourceManager.getParserFor，
        // 本路径每一种都按 Failed 带回（不抛，见 ChapterSyncResult.Failed 的理由）。
        val parser: BookParser = bookSourceManager.getParserFor(bookShelf.tag)
            ?: return ChapterSyncResult.Failed(
                BookSourceNotFoundException(bookShelf.tag, detail = "目录重抓 noteUrl=$noteUrl")
            )

        val remoteChapters: List<ChapterListEntity> = try {
            parser.getChapterList(bookShelf).data.chapterList
        } catch (e: CancellationException) {
            // 取消不是「检查失败」：吞掉会让调用方把销毁中的页面当成一个结论
            throw e
        } catch (e: Exception) {
            return ChapterSyncResult.Failed(e)
        }

        // 「一本书零章」不是合法状态：更可能是解析规则失配而非站点删光了章。
        // 判 Diverged 会连带写时间戳，于是规则修好后 5 分钟内都不再试，故按失败处置（不写时间戳）。
        if (remoteChapters.isEmpty() && localChapters.isNotEmpty()) {
            return ChapterSyncResult.Failed(
                IllegalStateException("远端目录为空，不按分叉处置：noteUrl=$noteUrl, tag=${bookShelf.tag}")
            )
        }

        return when (val diff = ChapterTocDiff.diff(localChapters, remoteChapters)) {
            is TocDiff.UpToDate -> {
                stampTocChecked(noteUrl)
                ChapterSyncResult.UpToDate
            }

            is TocDiff.Diverged -> {
                // 写时间戳：分叉不是暂时性故障，重试无意义，不写就等于每次进详情页重爬一遍目录
                stampTocChecked(noteUrl)
                ChapterSyncResult.Diverged
            }

            is TocDiff.Appendable -> {
                // I3：远端自带的是位置序号（parser 写 chapters.size），本地有洞时两套口径不同，
                // 直接沿用会让新行与既有行撞同一个 durChapterIndex —— 撞号不报错，只是读错章。
                val nextIndex = (localChapters.maxOfOrNull { it.durChapterIndex } ?: -1) + 1
                val rows = diff.tail.mapIndexed { offset, chapter ->
                    chapter.copy(
                        durChapterIndex = nextIndex + offset,
                        // 归属以入参条目为准：parser 其实也填对了，重写一遍是廉价防线 ——
                        // 「这一行属于哪本书、归哪个源」的事实源是调用方，不该依赖 parser 记得填
                        noteUrl = noteUrl,
                        tag = bookShelf.tag,
                    )
                }
                val updated = bookShelf.copy(chapterList = localChapters + rows)
                try {
                    withContext(Dispatchers.IO) {
                        transactions.run {
                            chapterListDao.insertAll(rows)
                            bookInfoDao.setFinalRefreshData(noteUrl, System.currentTimeMillis())
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return ChapterSyncResult.Failed(e)
                }
                // 事件只在事务提交之后发（未提交的写不是事实，口径同 switchSource）
                _bookShelfEvents.emit(BookShelfEvent.ChaptersUpdated(updated))
                ChapterSyncResult.Appended(rows)
            }
        }
    }

    /** 记下「这次检查得出了结论」的时间戳（I4）。单独一个定向 UPDATE，不整行 REPLACE */
    private suspend fun stampTocChecked(noteUrl: String) {
        withContext(Dispatchers.IO) {
            bookInfoDao.setFinalRefreshData(noteUrl, System.currentTimeMillis())
        }
    }
```

- [ ] **Step 5: 跑测试，确认全绿**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.BookRepositoryChapterSyncTest"`
Expected: PASS。若 `ChaptersUpdated` 相关用例编译失败，是因为 Task 5 才加该事件 —— **先做 Task 5 再回来跑绿**（两个任务互锁：编译期依赖）。

- [ ] **Step 6: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt \
        lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryChapterSyncTest.kt
git commit -m "feat(lib_book_common): 新增按需重抓目录并按前缀判定追加新章"
```

---

### Task 5: `BookShelfEvent.ChaptersUpdated` 与 4 处消费方

对应 spec §8。**这一步会让 4 个 ViewModel 编译失败（穷举 `when` 无 `else`），必须同一提交内一起改完。**

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt:745-754`
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookListViewModel.kt:42-46`
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModel.kt:73-87`
- Modify: `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/ChoiceBookViewModel.kt`
- Modify: `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/SearchViewModel.kt`

- [ ] **Step 1: 加事件分支**

`BookRepository.kt` 的 `sealed class BookShelfEvent` 内，`ProgressUpdated` 之后追加：

```kotlin
    /**
     * 目录追加了新章（章节正文并未下载，只是目录变长）。
     *
     * 不复用 [ProgressUpdated]：动的是目录而不是阅读进度，混在一起书架页与详情页
     * 就没法区分「该重查目录」和「只是进度变了」。
     *
     * `Diverged` **不发本事件**：数据库一行未动，发了等于让消费方去重查一个没变的东西。
     */
    data class ChaptersUpdated(val bookShelf: BookShelfEntity) : BookShelfEvent()
```

- [ ] **Step 2: 跑书架模块编译，确认它在 4 处报穷举失败**

Run: `./gradlew :module_book:compileDebugKotlin :module_find:compileDebugKotlin`
Expected: FAIL，报 `'when' expression must be exhaustive` 之类，位置即下面四个文件

- [ ] **Step 3: 补 `BookListViewModel`（书架页要重查）**

把 `BookListViewModel.kt:42-46` 的 `when` 改成：

```kotlin
                when (event) {
                    is BookShelfEvent.Added,
                    is BookShelfEvent.Removed,
                    is BookShelfEvent.ProgressUpdated,
                    // 目录变长要重查：书架条目将来要显示最新章节与更新角标（阶段 2），
                    // 现在虽不展示章节数，但事件语义上「这本书的数据变了」就得重取
                    is BookShelfEvent.ChaptersUpdated -> refreshData()
                }
```

同时把该类 KDoc 里「（Added/Removed/ProgressUpdated → 自动刷新）」补上 `ChaptersUpdated`。

- [ ] **Step 4: 补 `BookDetailViewModel`**

在其 `when` 的 `is BookShelfEvent.ProgressUpdated -> Unit` 之前插入一个分支：

```kotlin
                    // 详情页自己就是目录重抓的发起方，状态已就地更新过；再收一遍事件会把
                    // 刚显示的条目整个换掉，反而丢掉用户此刻的展开状态
                    is BookShelfEvent.ChaptersUpdated -> Unit
```

- [ ] **Step 5: 补 `ChoiceBookViewModel` 与 `SearchViewModel`**

两处在各自 `when` 的 `is BookShelfEvent.ProgressUpdated -> Unit` 之前插入：

```kotlin
                    is BookShelfEvent.ChaptersUpdated -> Unit // 目录变长与「选哪本」列表无关
```

（`SearchViewModel` 那处注释尾语改成「目录变长与搜索结果列表无关」，保持各自文案。）

- [ ] **Step 6: 跑测试与编译，确认全绿**

Run: `./gradlew :lib_book_common:testDebugUnitTest :module_book:compileDebugKotlin :module_find:compileDebugKotlin`
Expected: PASS。此时 Task 4 留下的 `BookRepositoryChapterSyncTest` 也一并转绿。

- [ ] **Step 7: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt \
        module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookListViewModel.kt \
        module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModel.kt \
        module_find/src/main/java/com/ebook/find/mvvm/viewmodel/ChoiceBookViewModel.kt \
        module_find/src/main/java/com/ebook/find/mvvm/viewmodel/SearchViewModel.kt
git commit -m "feat(all): 书架事件新增 ChaptersUpdated 并补齐四处穷举消费方"
```

---

### Task 6: 详情页接线

对应 spec §4.3「详情页」、§7 矩阵。

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModel.kt:37-42,92-95`
- Modify: `module_book/src/main/java/com/ebook/book/BookDetailActivity.kt`（状态区）
- Modify: `module_book/src/main/res/values/strings.xml`

- [ ] **Step 1: 加两条文案**

`module_book/src/main/res/values/strings.xml` 内追加（键名风格对齐 `source_switch_moved_format`）：

```xml
    <!-- 目录重抓（阶段 1）：追加成功的条数与分叉后的处置指引 -->
    <string name="chapters_appended">已更新 %1$d 章</string>
    <string name="toc_diverged_hint">目录结构已变化，请换源或重新导入</string>
```

- [ ] **Step 2: 加 `tocDiverged` 状态字段**

`BookDetailUiState`（`BookDetailViewModel.kt:37-42`）改为：

```kotlin
data class BookDetailUiState(
    val bookShelf: BookShelfEntity? = null,
    val inBookShelf: Boolean = false,
    val loading: Boolean = false,
    val loadError: Boolean = false,
    /**
     * 目录重抓判定为分叉（本地不是远端的前缀），本地目录未作改动。
     *
     * 只在本次会话内成立、不持久化：限频挡掉了 5 分钟内的重复检查，故用户第二次进来
     * 看不到这条提示是可接受的（第一次已经看到了），阶段 2 要把它做成持久标记再落库。
     */
    val tocDiverged: Boolean = false,
)
```

并给 `@property` KDoc 补一行 `@property tocDiverged 目录重抓判定分叉，需要用户处置`。

- [ ] **Step 3: 书架入口接上静默检查**

`initFromBookShelf`（`BookDetailViewModel.kt:92-95`）改为：

```kotlin
    /**
     * 书架入口：先用本地实体立即渲染（页面不空白），随后静默重抓一次目录。
     *
     * 「先渲染后检查」是刻意的次序：静默检查可能耗时数秒（目录要翻好几页），
     * 把它摆在渲染之前会让一本完全能读的书白转圈。
     *
     * 与搜索入口 [getBookShelfInfo] 的差别：那条是用户主动找书、必须拿网络结果，
     * 失败要进错误态；本条失败时必须什么都不说（spec 决策 8）——
     * 本地目录完好，失败只意味着「这次没查到有没有新章」，
     * 置 loadError 会让一本正常显示的书凭空变成「加载失败」。
     */
    fun initFromBookShelf(shelf: BookShelfEntity) {
        _detailState.update { it.copy(bookShelf = shelf, inBookShelf = true) }
        syncChaptersQuietly(shelf)
    }

    /**
     * 静默目录重抓：只在追加成功时报一句、只在判定时分叉给一条常驻提示，
     * 其余结局（Throttled / UpToDate / NotNetworkBook / Failed）全部不出声。
     */
    private fun syncChaptersQuietly(shelf: BookShelfEntity) {
        viewModelScope.launch {
            when (val result = bookRepository.syncChaptersFromSource(shelf)) {
                is ChapterSyncResult.Appended -> {
                    val count = result.appended.size
                    // 必须 copy 出新实体经 update 提交：BookShelfEntity 装在 StateFlow 里，
                    // 就地改它的 chapterList 不改变对象引用，StateFlow 判等后不会重发，
                    // 页面目录就停在旧长度。拼接口径「旧 + appended」由不变式 I2 保证正确。
                    _detailState.update { state ->
                        val current = state.bookShelf ?: return@update state
                        state.copy(
                            bookShelf = current.copy(
                                chapterList = current.chapterList + result.appended,
                            )
                        )
                    }
                    sendToast(context.getString(R.string.chapters_appended, count))
                }

                is ChapterSyncResult.Diverged -> _detailState.update { it.copy(tocDiverged = true) }

                is ChapterSyncResult.Failed ->
                    Logger.e(TAG, "目录静默重抓失败（本地目录未受影响，不打扰用户）：${shelf.noteUrl}", result.cause)

                // 三种不用开口的结局（spec 决策 8：自动触发的失败与"没有新章"都是噪音）
                ChapterSyncResult.UpToDate,
                ChapterSyncResult.Throttled,
                ChapterSyncResult.NotNetworkBook -> Unit
            }
        }
    }
```

补 import：

```kotlin
import com.ebook.common.repository.ChapterSyncResult
```

- [ ] **Step 4: 加提示条 UI**

`BookDetailActivity.kt` 的「详情拉取状态」`when { state.loading -> ...; state.loadError -> ... }`
（`:273-298`）之后、`Spacer(modifier = Modifier.height(24.dp))` 之前追加：

```kotlin
        // 目录分叉提示（阶段 1）：本地目录未作改动，用户手上的处置是换源或重新导入。
        // 与 loadError 不是一回事 —— 页面数据完好、能读能下载，只是「查不到新章」。
        // 故不做成可点重试（重试无意义，分叉不是暂时性故障），也不置 loadError。
        if (state.tocDiverged) {
            Text(
                text = stringResource(R.string.toc_diverged_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
```

- [ ] **Step 5: 构建验证**

Run: `./gradlew :module_book:assembleDebug`
Expected: BUILD SUCCESSFUL（无新警告）

- [ ] **Step 6: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModel.kt \
        module_book/src/main/java/com/ebook/book/BookDetailActivity.kt \
        module_book/src/main/res/values/strings.xml
git commit -m "feat(module_book): 详情页进页静默重抓目录并提示分叉"
```

---

### Task 7: 阅读页末章触发

对应 spec §4.3「阅读页」。**本任务的接线在 `ReadBookActivity.kt`，该文件当前处于未提交的滚屏改动中，见「开工前置条件」第 1、2 条。**

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt`
- Modify: `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt`（两个控制器的 `onProgress`）

- [ ] **Step 1: ViewModel 加单飞检查**

`BookReadViewModel.kt` 在 `refreshCurrentChapter()` 之后插入：

```kotlin
    /**
     * 读到末章时静默检查目录更新（spec §4.3）。
     *
     * 追加成功时就地更新 `bookShelf.chapterList`：阅读器的一切都是现取这个字段
     * （`getChapter` / `getChapterListSize`），故就地更新即可生效，**不需要整体替换实体**
     * —— 换源那条路径要替换是因为换了另一本书，这里还是同一本、只是目录变长。
     *
     * **单飞**：`onProgress` 在末章的每一页翻动都会调本方法，而限频只能保证「5 分钟一次网络」，
     * 挡不住同一批页快速来回翻时并发起来。用 [syncInFlight] 挡住重入：
     * 本方法只在主线程（Compose 回调）被调，故普通 Boolean 足够，不需要原子量。
     *
     * @return 追加的新章（供宿主重分页），其余结局返回 null 表示「页面不用动」
     */
    suspend fun appendChaptersIfAny(): List<ChapterListEntity>? {
        val shelf = bookShelf ?: return null
        if (syncInFlight) return null
        syncInFlight = true
        try {
            val result = bookRepository.syncChaptersFromSource(shelf)
            val appended = (result as? ChapterSyncResult.Appended)?.appended ?: return null
            bookShelf = shelf.copy(chapterList = shelf.chapterList + appended)
            return appended
        } finally {
            syncInFlight = false
        }
    }

    /** 目录检查是否正在进行（单飞标志，见 [appendChaptersIfAny]） */
    private var syncInFlight = false
```

补 import：`com.ebook.common.repository.ChapterSyncResult`、`com.ebook.db.entity.ChapterListEntity`（后者已导入）。

- [ ] **Step 2: 宿主接上末章触发**

在 `ReadBookActivity` 的 Composable 内、**`// ---------------- 翻页控制器 ----------------` 注释之前**
（即 `val chapterAll = viewModel.getChapterListSize()` 那一行之后）插入。
放这里而不是 `gotoPage` 之后：控制器的 `remember { }` 会引用它，而 Kotlin 不允许 lambda
前向引用后面声明的局部 `val`。落点判据是「`typesetter`（`rememberReaderTypesetter(`）与
`chapterTitle` / `sliderValue` / `scope` 四个都已在它之前声明」。

```kotlin
    /**
     * 到达末章时查一次目录更新。
     *
     * 之所以挂在 `onProgress` 而不是别处：它是两种翻页方式（左右翻页 / 上下滚屏）**唯一共用**
     * 的进度回调，翻页方式与容器无关，挂在这里就不用各接一遍（AGENTS.md：跳转与进度口径
     * 在两种模式共用同一条分块链）。
     *
     * 追加成功后按换源那条已验证的路径重载（见下方 `ReaderPanel.SOURCE_SWITCH` 的 `onSwitched`），
     * 差别有三点：不需要整体替换 `viewModel.bookShelf`（VM 已就地更新）、
     * `startFromCurrent = true`（换源是 false，因为页级进度不跨源）、不调 `gotoPage`
     * （章序号没漂移，不变式 I2）。
     * `sliderValue` 与 `chapterTitle` 是页面本地状态、不随 VM 重组，必须一并跟上，
     * 否则滑条仍按旧「共 M 章」计算、重组成后读到的 chapterAll 也停在旧值。
     */
    val syncAtTailChapter: (Int) -> Unit = { chapterIndex ->
        if (chapterIndex == viewModel.getChapterListSize() - 1) {
            scope.launch {
                val appended = viewModel.appendChaptersIfAny()
                if (!appended.isNullOrEmpty()) {
                    activity.rePaginate(typesetter, startFromCurrent = true)
                    chapterTitle = viewModel.getChapterTitle(chapterIndex)
                    sliderValue = (chapterIndex + 1).toFloat()
                    ToastUtil.showShort(
                        context,
                        context.getString(R.string.chapters_appended, appended.size),
                    )
                }
            }
        }
    }
```

把两个控制器的 `onProgress` lambda（`:519-525` 与 `:539-544`）各自在末尾加一行：

```kotlin
                syncAtTailChapter(c)
```

> **注意前向引用**：`syncAtTailChapter` 定义在 565 行，而 `controller` 的 `remember { }`
> 在 512 行就引用了它 —— Kotlin 不允许 lambda 前向引用后面声明的局部 `val`。
> 落地时把 `syncAtTailChapter` 的声明**上移到 `typesetter` 之后、两个控制器之前**
> （即紧跟 `rememberReaderTypesetter(...)`，约 482 行后），
> 并确认它依赖的 `chapterTitle`/`sliderValue`/`scope` 都声明得更早。
> 若 `sliderValue` 等确实声明在后，退路是改用 `mutableStateOf<Int?>` 版本号 +
> 一个声明在控制器之后的 `LaunchedEffect` 消费（同 `LaunchedEffect(typesetter)` 那套时序纪律）。

- [ ] **Step 3: 构建验证**

Run: `./gradlew :module_book:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt \
        module_book/src/main/java/com/ebook/book/ReadBookActivity.kt
git commit -m "feat(module_book): 阅读器到末章时静默补章并复用换源重载路径"
```

---

### Task 8: ADR 与文档同步

对应 spec §11。**AGENTS.md 要求：评审驱动的架构决定必须沉淀为 ADR，不能只留在提交信息与会话记录里。**

- [ ] **Step 1: 新增 ADR**

先 `ls docs/adr/ | tail -5` 取届时最大编号 +1（写本计划时最大是 0038），创建
`docs/adr/00NN-toc-resync-prefix-invariant.md`。按仓内极简派写法（标题 + 1~3 句，
章节是例外；格式以 `docs/adr/ADR-FORMAT.md` 为准；正文不交叉引用本仓其他 ADR）：

```markdown
# 目录重抓只接受前缀追加，分叉即整笔放弃

网络书按需重抓目录时，只有「本地目录逐位是远端目录的前缀」才写入尾部新章；
首章就对不上、中段不同、或远端更短，一律整笔放弃本地不动，只在页面上提示用户换源或重新导入。

**理由**：章文件按序号命名（`filesDir/books/<bookId>/cNNNNN.txt`），序号一旦漂移就会静默读错章
—— 用户点第 50 章读到旧的第 50 章，不报错、不闪退、页面上一切正常。
已下载的章与目录的对应关系没有第二份事实源可以校正它，所以宁可放弃一次更新，不做可能错位的合并。

**被拒的方案**：①章名兜底再比一轮（能自愈「站点只换了 URL 规则」，但序号仍可能漂移，
连带要写一套章文件重命名）；②整本替换 + 章文件按新序号重建（彻底自愈，
但替换瞬间正在阅读的那一章会失配）。两者都把一次低频的站点改版，换成了高频路径上的静默数据损坏风险。

**后续注意**：新章序号必须从 `max(dur_chapter_index) + 1` 起、不能用 `size`（历史删章会留洞），
且**远端行自带的序号不可沿用** —— 两个 parser 都按位置写 `chapters.size`，
本地有洞时两套口径不同，直接沿用会让两行撞同一个 index 而不报错。

日期：2026-09-XX（落地当天填）
```

- [ ] **Step 2: `AGENTS.md` 补一条实战建议**

在「Agent 实战建议」区，紧接本地书导入那条之后加一段：

```markdown
- 涉及目录重抓/章节更新检查时，先读 ADR-00NN：**只有「本地逐位是远端前缀」才写入**，
  分叉一律整笔放弃（章文件按序号命名，序号漂移=静默读错章）。三条别重新发现：
  新索引取 `max(durChapterIndex) + 1` 不用 `size`；**远端行自带的是位置序号（parser 写
  `chapters.size`），本地有洞时沿用会与既有行撞号且不报错**；追加成功后不需要失效任何缓存
  （已有章的 index 与 contentRef 逐字不变）。限频落在 `book_info.final_refresh_data`
  （`isTocCheckDue` + `BookShelfEntity.REFRESH_TIME`），**成功与分叉都写时间戳、失败不写**。
```

- [ ] **Step 3: `CONTEXT.md` 补术语**

若「目录重抓」「前缀判定」「分叉（目录）」在 `CONTEXT.md` 里还没有条目，按该文件的纯术语格式各补一行
（只写「是什么」，不写实现细节）。已有则不重复。

- [ ] **Step 4: `docs/test-coverage-todo.md` 登记人工装机项**

追加 spec §9 那 5 条（按该文件既有编号续排）：真书进详情页看到「已更新 N 章」；
断网进详情页不弹错误、本地目录照常可读；读到末章能继续翻且位置不变；
5 分钟内反复进出详情页只发一次目录请求；已下载章在追加后仍读到正确内容。

- [ ] **Step 5: 全量验证并提交**

Run: `./gradlew test :module_book:assembleDebug :module_find:assembleDebug`
Expected: BUILD SUCCESSFUL，无新警告

```bash
git add docs/adr/ AGENTS.md CONTEXT.md docs/test-coverage-todo.md
git commit -m "docs: 新增 ADR 目录重抓只接受前缀追加并同步指南与术语表"
```

---

## 验证边界（务必读）

按 AGENTS.md 的分工，**Agent 止于「构建通过 + 单测绿」**。Task 6、Task 7 涉及 UI 与启动链路，
安装运行与打开页面确认由人工完成。交付时必须显式交代未验证的部分：

- 单测与 DAO 测试覆盖的是判定与落库语义（Task 1–5）。
- **未做装机验证**：Task 6 的「已更新 N 章」Toast 与分叉提示条是否出现在正确位置、
  Task 7 的末章触发是否在两种翻页方式下都生效且不丢当前位置 —— 都要真机或模拟器上看。
- 「构建通过不等于可运行」：`stringResource`/`ToastUtil` 与 Compose 重组时序只在运行时才暴露问题。
