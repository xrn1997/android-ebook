# M2 来源分组与评论 — 剩余实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 M2 里程碑剩余四项：自动合并算法（§9.4 信号打分 + 三档阈值）、重复检测两级提示、合并/拆分 UI、修键面板（主匹配名/匹配作者/当前主键/已关联键列表），并将 `migrateMyComments` 接入修键面板。

**Architecture:** BookGroupDao 扩展 5 个方法支撑合并/拆分/切主键；BookRepository 新增 merge/split/updateMatchMeta 方法封装事务一致性；纯函数 `BookMergeScorer` 独立可测，输出三档处置建议；`DuplicateBookDetector` 在书架加载后扫描候选对，经 SharedFlow 推送给 UI；修键面板是新 Activity，经 TheRouter 路由到达，编辑后重算键、切主键、迁移本人评论。

**Tech Stack:** Kotlin, Room 3.0.0, Hilt/Dagger DI, kotlinx.coroutines + Flow, JUnit 4, Jetpack Compose (Material 3)

---

## File Structure

### New files
| File | Responsibility |
|------|---------------|
| `lib_book_common/src/main/java/com/ebook/common/domain/BookMergeScorer.kt` | 纯函数：两本书的合并打分，输出三档处置建议 |
| `lib_book_common/src/main/java/com/ebook/common/domain/DuplicateBookDetector.kt` | 扫描书架找出重复候选对，经 Flow 推送合并建议 |
| `lib_book_common/src/test/java/com/ebook/common/domain/BookMergeScorerTest.kt` | 打分算法单测 |
| `lib_book_common/src/test/java/com/ebook/common/domain/DuplicateBookDetectorTest.kt` | 重复检测单测 |
| `module_book/src/main/java/com/ebook/book/EditBookMetaActivity.kt` | 修键面板 UI（主匹配名/匹配作者/当前主键/已关联键列表） |
| `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/EditBookMetaViewModel.kt` | 修键面板 ViewModel |

### Modified files
| File | Changes |
|------|---------|
| `lib_ebook_db/src/main/java/com/ebook/db/dao/BookGroupDao.kt` | 新增 `getPrimaryForNoteUrl`、`getAllForNoteUrl`、`deleteSpecific`、`switchPrimary`、`addSecondary` |
| `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt` | 新增 `mergeBooks`、`splitBook`、`updateMatchMeta`、`getBookGroupRows` |
| `lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt` | `FakeBookGroupDao` 实现新增的 5 个 DAO 方法 |
| `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryTest.kt` | 新增 merge/split/updateMatchMeta 测试 |
| `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookShelfViewModel.kt` | 注入 `DuplicateBookDetector`，收集合并建议并暴露给 UI |
| `module_book/src/main/java/com/ebook/book/page/BookShelfPage.kt` | 合并建议 SnackBar + 导航到修键面板入口 |
| `lib_book_common/src/main/java/com/ebook/common/event/KeyCode.kt` | 新增 `EDIT_BOOK_META_PATH` 路由常量 |
| `module_book/src/main/AndroidManifest.xml` | 注册 `EditBookMetaActivity` |
| `module_book/src/main/module/AndroidManifest.xml` | 同步注册 `EditBookMetaActivity`（独立模式） |

---

### Task 1: BookGroupDao 扩展 — 合并/拆分/切主键数据访问

**Files:**
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/dao/BookGroupDao.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt`

- [ ] **Step 1: Write failing tests for new DAO methods**

Create `lib_book_common/src/test/java/com/ebook/common/repository/BookGroupDaoTest.kt`:

```kotlin
package com.ebook.common.repository

import com.ebook.db.entity.BookGroupEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BookGroupDaoTest {

    private lateinit var dao: FakeBookGroupDao

    @Before
    fun setUp() {
        dao = FakeBookGroupDao()
    }

    @Test
    fun `getPrimaryForNoteUrl returns the primary key`() = runTest {
        dao.insert(BookGroupEntity("ck1:aaa", "url1", isPrimary = true))
        dao.insert(BookGroupEntity("ck1:bbb", "url1", isPrimary = false))

        assertEquals("ck1:aaa", dao.getPrimaryForNoteUrl("url1"))
    }

    @Test
    fun `getPrimaryForNoteUrl returns null when no rows`() = runTest {
        assertNull(dao.getPrimaryForNoteUrl("nonexistent"))
    }

    @Test
    fun `getAllForNoteUrl returns all rows for a noteUrl`() = runTest {
        dao.insert(BookGroupEntity("ck1:aaa", "url1", isPrimary = true))
        dao.insert(BookGroupEntity("ck1:bbb", "url1", isPrimary = false))
        dao.insert(BookGroupEntity("ck1:ccc", "url2", isPrimary = true))

        val rows = dao.getAllForNoteUrl("url1")
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.commentKey == "ck1:aaa" && it.isPrimary })
        assertTrue(rows.any { it.commentKey == "ck1:bbb" && !it.isPrimary })
    }

    @Test
    fun `deleteSpecific removes only the targeted key`() = runTest {
        dao.insert(BookGroupEntity("ck1:aaa", "url1", isPrimary = true))
        dao.insert(BookGroupEntity("ck1:bbb", "url1", isPrimary = false))

        dao.deleteSpecific("url1", "ck1:bbb")

        val remaining = dao.getAllForNoteUrl("url1")
        assertEquals(1, remaining.size)
        assertEquals("ck1:aaa", remaining[0].commentKey)
    }

    @Test
    fun `switchPrimary demotes old and promotes new within same noteUrl`() = runTest {
        dao.insert(BookGroupEntity("ck1:aaa", "url1", isPrimary = true))
        dao.insert(BookGroupEntity("ck1:bbb", "url1", isPrimary = false))

        dao.switchPrimary("url1", "ck1:bbb")

        val rows = dao.getAllForNoteUrl("url1")
        val primary = rows.single { it.isPrimary }
        assertEquals("ck1:bbb", primary.commentKey)
        val secondary = rows.single { !it.isPrimary }
        assertEquals("ck1:aaa", secondary.commentKey)
    }

    @Test
    fun `addSecondary inserts non-primary row`() = runTest {
        dao.insert(BookGroupEntity("ck1:aaa", "url1", isPrimary = true))

        dao.addSecondary("url1", "ck1:bbb")

        val rows = dao.getAllForNoteUrl("url1")
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.commentKey == "ck1:bbb" && !it.isPrimary })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.BookGroupDaoTest" 2>&1 | tail -20`
Expected: FAIL — methods `getPrimaryForNoteUrl`, `getAllForNoteUrl`, `deleteSpecific`, `switchPrimary`, `addSecondary` do not exist on `BookGroupDao`

- [ ] **Step 3: Expand BookGroupDao with new methods**

Modify `lib_ebook_db/src/main/java/com/ebook/db/dao/BookGroupDao.kt`:

```kotlin
package com.ebook.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.ebook.db.entity.BookGroupEntity

/**
 * 作品分组关联表访问器。
 *
 * M1a 写入与随删；M2 新增合并/拆分/切主键操作。"恰好一行 is_primary"由调用方在
 * `withWriteTransaction` 内保证（SQLite 无部分唯一索引）。
 */
@Dao
interface BookGroupDao {

    /** 按 (comment_key, note_url) upsert 一行关联 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: BookGroupEntity)

    /** 删除某条来源的全部关联行：从书架移除书时随之清理 */
    @Query("DELETE FROM book_group WHERE note_url = :noteUrl")
    suspend fun deleteFor(noteUrl: String)

    /** 查出某条来源关联的全部 comment_key（M2：并集读评论，跨源聚合） */
    @Query("SELECT comment_key FROM book_group WHERE note_url = :noteUrl")
    suspend fun getKeysForNoteUrl(noteUrl: String): List<String>

    /** 取某条来源当前的主键（is_primary = true 那行的 comment_key），无行返回 null */
    @Query("SELECT comment_key FROM book_group WHERE note_url = :noteUrl AND is_primary = 1 LIMIT 1")
    suspend fun getPrimaryForNoteUrl(noteUrl: String): String?

    /** 取某条来源的全部关联行（含 isPrimary 标记），供修键面板展示 */
    @Query("SELECT * FROM book_group WHERE note_url = :noteUrl")
    suspend fun getAllForNoteUrl(noteUrl: String): List<BookGroupEntity>

    /** 删除某条来源的特定关联行（拆分操作：只删一行，其余不动） */
    @Query("DELETE FROM book_group WHERE note_url = :noteUrl AND comment_key = :commentKey")
    suspend fun deleteSpecific(noteUrl: String, commentKey: String)

    /**
     * 切主键：把 noteUrl 下所有行的 is_primary 清零，再把目标行设为 1。
     *
     * 两步操作必须在调用方的 `withWriteTransaction` 内执行，保证"恰好一行 primary"。
     */
    @Query("UPDATE book_group SET is_primary = 0 WHERE note_url = :noteUrl")
    suspend fun clearPrimary(noteUrl: String)

    @Query("UPDATE book_group SET is_primary = 1 WHERE note_url = :noteUrl AND comment_key = :commentKey")
    suspend fun promotePrimary(noteUrl: String, commentKey: String)

    /** 添加非主键关联行（合并操作：把另一个键加到当前来源的并集里） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSecondary(row: BookGroupEntity)
}
```

- [ ] **Step 4: Update FakeBookGroupDao to implement new methods**

Modify `lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt`, replace the `FakeBookGroupDao` class:

```kotlin
internal class FakeBookGroupDao : BookGroupDao {
    private val rows = linkedMapOf<String, BookGroupEntity>() // key = "commentKey|noteUrl"

    override suspend fun insert(row: BookGroupEntity) {
        rows["${row.commentKey}|${row.noteUrl}"] = row
    }

    override suspend fun deleteFor(noteUrl: String) {
        rows.entries.removeAll { it.value.noteUrl == noteUrl }
    }

    override suspend fun getKeysForNoteUrl(noteUrl: String): List<String> =
        rows.values.filter { it.noteUrl == noteUrl }.map { it.commentKey }

    override suspend fun getPrimaryForNoteUrl(noteUrl: String): String? =
        rows.values.firstOrNull { it.noteUrl == noteUrl && it.isPrimary }?.commentKey

    override suspend fun getAllForNoteUrl(noteUrl: String): List<BookGroupEntity> =
        rows.values.filter { it.noteUrl == noteUrl }

    override suspend fun deleteSpecific(noteUrl: String, commentKey: String) {
        rows.remove("$commentKey|$noteUrl")
    }

    override suspend fun clearPrimary(noteUrl: String) {
        rows.entries.filter { it.value.noteUrl == noteUrl }.forEach {
            it.value.isPrimary = false
        }
    }

    override suspend fun promotePrimary(noteUrl: String, commentKey: String) {
        rows["$commentKey|$noteUrl"]?.isPrimary = true
    }

    override suspend fun addSecondary(row: BookGroupEntity) {
        val key = "${row.commentKey}|${row.noteUrl}"
        if (key !in rows) {
            rows[key] = row.copy(isPrimary = false)
        }
    }

    fun storedValues(): List<BookGroupEntity> = rows.values.toList()
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.BookGroupDaoTest" 2>&1 | tail -20`
Expected: PASS (6 tests)

- [ ] **Step 6: Run all existing tests to verify no regression**

Run: `./gradlew :lib_book_common:testDebugUnitTest 2>&1 | tail -20`
Expected: All existing tests still pass

- [ ] **Step 7: Commit**

```bash
git add lib_ebook_db/src/main/java/com/ebook/db/dao/BookGroupDao.kt \
       lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt \
       lib_book_common/src/test/java/com/ebook/common/repository/BookGroupDaoTest.kt
git commit -m "feat(lib_ebook_db): BookGroupDao 扩展合并/拆分/切主键数据访问

新增 getPrimaryForNoteUrl、getAllForNoteUrl、deleteSpecific、
clearPrimary/promotePrimary、addSecondary 五个方法，
支撑 M2 合并/拆分 UI 与修键面板的数据操作。"
```

---

### Task 2: BookRepository 合并/拆分/修键方法

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryTest.kt`

- [ ] **Step 1: Write failing tests for merge/split/updateMatchMeta**

Add to `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryTest.kt`:

```kotlin
    // ===== M2：合并/拆分/修键 =====

    @Test
    fun `mergeBooks adds secondary key from another book to target`() = runTest {
        val shelf1 = BookShelfEntity(noteUrl = "http://source1").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹", author = "天蚕土豆")
        }
        val shelf2 = BookShelfEntity(noteUrl = "http://source2").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹", author = "天蚕土豆")
        }
        repository.addToShelf(shelf1)
        repository.addToShelf(shelf2)

        repository.mergeBooks(targetNoteUrl = "http://source1", sourceNoteUrl = "http://source2")

        val rows = repository.getBookGroupRows("http://source1")
        assertEquals(2, rows.size)
        val keys = rows.map { it.commentKey }.toSet()
        assertTrue(keys.contains(CommentKey.compute("斗破苍穹", "天蚕土豆")))
        // source2 的行也加进来了
        val source2Key = rows.first { it.noteUrl == "http://source1" && !it.isPrimary }
        assertTrue(source2Key.commentKey.isNotEmpty())
    }

    @Test
    fun `splitBook removes specific key row without affecting others`() = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹")
        }
        repository.addToShelf(shelf)
        // 手动加一行 secondary
        val secondaryKey = CommentKey.compute("斗破苍穹", "未知作者")
        daos.group.insert(BookGroupEntity(secondaryKey, "http://book", isPrimary = false))

        repository.splitBook("http://book", secondaryKey)

        val rows = repository.getBookGroupRows("http://book")
        assertEquals(1, rows.size)
        assertEquals(CommentKey.compute("斗破苍穹", ""), rows[0].commentKey)
    }

    @Test
    fun `updateMatchMeta recalculates key and switches primary`() = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹", author = "天蚕土豆")
        }
        repository.addToShelf(shelf)
        val oldKey = CommentKey.compute("斗破苍穹", "天蚕土豆")

        repository.updateMatchMeta("http://book", "斗破苍穹", "土豆")

        val rows = repository.getBookGroupRows("http://book")
        // 旧键保留（降级为非主键），新键成为主键
        assertEquals(2, rows.size)
        val newPrimary = rows.single { it.isPrimary }
        assertEquals(CommentKey.compute("斗破苍穹", "土豆"), newPrimary.commentKey)
        assertTrue(rows.any { it.commentKey == oldKey && !it.isPrimary })
    }

    @Test
    fun `getBookGroupRows returns empty for unknown book`() = runTest {
        val rows = repository.getBookGroupRows("http://nonexistent")
        assertTrue(rows.isEmpty())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.BookRepositoryTest" 2>&1 | tail -20`
Expected: FAIL — `mergeBooks`, `splitBook`, `updateMatchMeta`, `getBookGroupRows` do not exist

- [ ] **Step 3: Add merge/split/updateMatchMeta methods to BookRepository**

Add to `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt` (before the `publishAdded` method):

```kotlin
    // ===== M2：合并/拆分/修键 =====

    /**
     * 取某本书的全部 book_group 行（含 isPrimary 标记），供修键面板展示。
     */
    suspend fun getBookGroupRows(noteUrl: String): List<BookGroupEntity> =
        withContext(Dispatchers.IO) {
            bookGroupDao.getAllForNoteUrl(noteUrl)
        }

    /**
     * 合并：把 sourceNoteUrl 的主键行作为 secondary 加到 targetNoteUrl 的并集里。
     *
     * 语义见 spec §9.2：合并 = 加一行。source 自身的 book_group 行不动（它可能有自己的
     * 合并历史），只是 target 的并集多了一个键，读评论时能查到 source 桶里的存量。
     */
    suspend fun mergeBooks(targetNoteUrl: String, sourceNoteUrl: String) =
        withContext(Dispatchers.IO) {
            val sourcePrimary = bookGroupDao.getPrimaryForNoteUrl(sourceNoteUrl) ?: return@withContext
            bookGroupDao.addSecondary(
                BookGroupEntity(commentKey = sourcePrimary, noteUrl = targetNoteUrl, isPrimary = false)
            )
        }

    /**
     * 拆分：从某本书的并集里删掉一个特定键行。
     *
     * 语义见 spec §9.2：拆分 = 删一行。不得删主键行（主键行是这本书自身的身份，
     * 删了就没法写评论了），只能删 secondary 行。
     */
    suspend fun splitBook(noteUrl: String, commentKeyToRemove: String) =
        withContext(Dispatchers.IO) {
            val primary = bookGroupDao.getPrimaryForNoteUrl(noteUrl)
            if (commentKeyToRemove == primary) return@withContext // 不允许删主键
            bookGroupDao.deleteSpecific(noteUrl, commentKeyToRemove)
        }

    /**
     * 修键：改主匹配名/作者 → 重算键 → 旧主键降级、新键成为主键。
     *
     * 旧行保留（spec §9.3）：旧评论不丢，读并集时仍可见。新评论进新键桶。
     * 调用方负责决定是否迁移本人旧评论（经 [CommentRepository.migrateMyComments]）。
     *
     * @return Pair(oldPrimaryKey, newPrimaryKey) 供调用方做评论迁移
     */
    suspend fun updateMatchMeta(
        noteUrl: String,
        newMatchName: String,
        newMatchAuthor: String,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val oldPrimary = bookGroupDao.getPrimaryForNoteUrl(noteUrl)
            ?: throw IllegalStateException("no book_group row for $noteUrl")
        val newKey = CommentKey.compute(newMatchName, newMatchAuthor)
        if (newKey == oldPrimary) return@withContext oldPrimary to newKey

        // 更新 book_shelf 的 matchName/matchAuthor
        val shelf = bookShelfDao.getBookByUrl(noteUrl)
        if (shelf != null) {
            shelf.matchName = newMatchName
            shelf.matchAuthor = newMatchAuthor
            bookShelfDao.update(shelf)
        }

        // 旧主键降级，新键成为主键（旧行保留）
        bookGroupDao.clearPrimary(noteUrl)
        bookGroupDao.insert(
            BookGroupEntity(commentKey = newKey, noteUrl = noteUrl, isPrimary = true)
        )
        // 如果旧键还在（它一定在，因为 clearPrimary 只改 isPrimary 不删行），确保它不是 primary
        // insert REPLACE 语义：如果 newKey 恰好等于旧键则覆盖，否则新增
        oldPrimary to newKey
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.BookRepositoryTest" 2>&1 | tail -20`
Expected: PASS (all tests including new M2 tests)

- [ ] **Step 5: Run all existing tests to verify no regression**

Run: `./gradlew :lib_book_common:testDebugUnitTest 2>&1 | tail -20`
Expected: All pass

- [ ] **Step 6: Commit**

```bash
git add lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt \
       lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryTest.kt
git commit -m "feat(lib_book_common): BookRepository 新增合并/拆分/修键方法

mergeBooks 加一行关联、splitBook 删一行关联、updateMatchMeta 重算键并切主键。
旧键行保留不删（spec §9.2 §9.3），读并集时历史评论仍可见。"
```

---

### Task 3: BookMergeScorer — 自动合并打分算法

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/domain/BookMergeScorer.kt`
- Create: `lib_book_common/src/test/java/com/ebook/common/domain/BookMergeScorerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `lib_book_common/src/test/java/com/ebook/common/domain/BookMergeScorerTest.kt`:

```kotlin
package com.ebook.common.domain

import org.junit.Assert.*
import org.junit.Test

class BookMergeScorerTest {

    private val scorer = BookMergeScorer

    // ===== 各信号的独立贡献 =====

    @Test
    fun `identical chapter names yield high similarity`() {
        val a = bookMeta(chapters = listOf("第一章 初遇", "第二章 修炼", "第三章 突破"))
        val b = bookMeta(chapters = listOf("第一章 初遇", "第二章 修炼", "第三章 突破"))
        val score = scorer.score(a, b)
        assertTrue("章名完全相同应得高分: $score", score.total >= 60)
    }

    @Test
    fun `completely different chapter names yield low similarity`() {
        val a = bookMeta(chapters = listOf("序章", "出发", "冒险"))
        val b = bookMeta(chapters = listOf("楔子", "离别", "归途"))
        val score = scorer.score(a, b)
        assertTrue("章名完全不同应低分: $score", score.total < 30)
    }

    @Test
    fun `same normalized title adds medium signal`() {
        val a = bookMeta(title = "《星辰变》")
        val b = bookMeta(title = "星辰变")
        val score = scorer.score(a, b)
        assertTrue("归一化书名相同应有中档加分: $score", score.titleScore > 0)
    }

    @Test
    fun `same author adds medium signal`() {
        val a = bookMeta(author = "我吃西红柿")
        val b = bookMeta(author = "我吃西红柿")
        val score = scorer.score(a, b)
        assertTrue("作者相同应有中档加分: $score", score.authorScore > 0)
    }

    @Test
    fun `author absent on one side does not penalize`() {
        val a = bookMeta(author = "我吃西红柿")
        val b = bookMeta(author = "")
        val score = scorer.score(a, b)
        assertEquals("一方缺作者不应扣分", 0, score.authorScore)
    }

    @Test
    fun `chapter count close adds small bonus`() {
        val a = bookMeta(chapters = (1..100).map { "第${it}章" })
        val b = bookMeta(chapters = (1..95).map { "第${it}章" })
        val score = scorer.score(a, b)
        assertTrue("章数接近应有小加分: $score", score.chapterCountScore > 0)
    }

    @Test
    fun `chapter count far apart adds no bonus`() {
        val a = bookMeta(chapters = (1..100).map { "第${it}章" })
        val b = bookMeta(chapters = (1..10).map { "第${it}章" })
        val score = scorer.score(a, b)
        assertEquals("章数差距大不应加分", 0, score.chapterCountScore)
    }

    @Test
    fun `paragraph fingerprint match adds strong signal`() {
        val a = bookMeta(fingerprints = mapOf(50 to "hash_abc"))
        val b = bookMeta(fingerprints = mapOf(50 to "hash_abc"))
        val score = scorer.score(a, b)
        assertTrue("段落指纹命中应有强加分: $score", score.fingerprintScore > 0)
    }

    @Test
    fun `paragraph fingerprint mismatch adds no signal`() {
        val a = bookMeta(fingerprints = mapOf(50 to "hash_abc"))
        val b = bookMeta(fingerprints = mapOf(50 to "hash_xyz"))
        val score = scorer.score(a, b)
        assertEquals("段落指纹不命中不应加分", 0, score.fingerprintScore)
    }

    // ===== 三档处置 =====

    @Test
    fun `high score triggers auto-merge disposition`() {
        val a = bookMeta(
            title = "星辰变", author = "我吃西红柿",
            chapters = listOf("第一章 初遇", "第二章 修炼", "第三章 突破"),
            fingerprints = mapOf(1 to "hash_same")),
        val b = bookMeta(
            title = "星辰变", author = "我吃西红柿",
            chapters = listOf("第一章 初遇", "第二章 修炼", "第三章 突破"),
            fingerprints = mapOf(1 to "hash_same")),
        val result = scorer.score(a, b)
        assertEquals(MergeDisposition.AUTO_MERGE, result.disposition)
    }

    @Test
    fun `low score triggers ignore disposition`() {
        val a = bookMeta(title = "书A", author = "作者一", chapters = listOf("序", "一", "二"))
        val b = bookMeta(title = "书B", author = "作者二", chapters = listOf("楔", "甲", "乙"))
        val result = scorer.score(a, b)
        assertEquals(MergeDisposition.IGNORE, result.disposition)
    }

    // ===== 组合场景 =====

    @Test
    fun `same title different author should not auto-merge`() {
        val a = bookMeta(title = "星辰变", author = "我吃西红柿",
            chapters = listOf("第一章", "第二章"))
        val b = bookMeta(title = "星辰变", author = "另一个人",
            chapters = listOf("第一章", "第二章"))
        val result = scorer.score(a, b)
        assertNotEquals(MergeDisposition.AUTO_MERGE, result.disposition)
    }

    private fun bookMeta(
        title: String = "",
        author: String = "",
        chapters: List<String> = emptyList(),
        fingerprints: Map<Int, String> = emptyMap(),
    ): BookMergeScorer.BookCandidate =
        BookMergeScorer.BookCandidate(
            noteUrl = "url_${title.hashCode()}",
            title = title,
            author = author,
            chapterNames = chapters,
            chapterFingerprints = fingerprints,
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.domain.BookMergeScorerTest" 2>&1 | tail -20`
Expected: FAIL — `BookMergeScorer` does not exist

- [ ] **Step 3: Implement BookMergeScorer**

Create `lib_book_common/src/main/java/com/ebook/common/domain/BookMergeScorer.kt`:

```kotlin
package com.ebook.common.domain

/**
 * 自动合并打分算法（spec §9.4）。
 *
 * 纯函数，无 Android 依赖，可 JVM 单测。输入两个 [BookCandidate]，输出 [MergeScore]
 * （含各信号分项得分与总分）与三档处置建议 [MergeDisposition]。
 *
 * 信号权重（与 spec §9.4 表格对应）：
 * - 章名序列相似度：强信号，最高 40 分
 * - 段落指纹命中：强信号，一次命中 30 分
 * - 归一化书名相同：中信号，15 分
 * - 作者相同：中信号，15 分（一方为空不扣分）
 * - 章数接近：校验项，5 分
 *
 * 三档阈值：
 * - >= 70：AUTO_MERGE（高分，默认只加读并集 + 轻确认通知）
 * - 40..69：CANDIDATE（中分，列为候选等确认）
 * - < 40：IGNORE（低分，不打扰）
 */
object BookMergeScorer {

    /**
     * 书架条目的打分输入。
     *
     * [chapterFingerprints] 的 key 是章序号（取中段章节），value 是该章第 M 段归一化后
     * 前 16 字的哈希。取中段而非首段的理由：首段是站点塞广告水印的位置（spec §9.4）。
     */
    data class BookCandidate(
        val noteUrl: String,
        val title: String,
        val author: String,
        val chapterNames: List<String>,
        val chapterFingerprints: Map<Int, String>,
    )

    /** 打分结果：各分项 + 总分 + 处置建议 */
    data class MergeScore(
        val chapterNameScore: Int,
        val fingerprintScore: Int,
        val titleScore: Int,
        val authorScore: Int,
        val chapterCountScore: Int,
        val total: Int,
        val disposition: MergeDisposition,
    )

    fun score(a: BookCandidate, b: BookCandidate): MergeScore {
        val chapterNameScore = scoreChapterNames(a.chapterNames, b.chapterNames)
        val fingerprintScore = scoreFingerprints(a.chapterFingerprints, b.chapterFingerprints)
        val titleScore = scoreTitle(a.title, b.title)
        val authorScore = scoreAuthor(a.author, b.author)
        val chapterCountScore = scoreChapterCount(a.chapterNames.size, b.chapterNames.size)

        val total = chapterNameScore + fingerprintScore + titleScore + authorScore + chapterCountScore
        val disposition = when {
            total >= 70 -> MergeDisposition.AUTO_MERGE
            total >= 40 -> MergeDisposition.CANDIDATE
            else -> MergeDisposition.IGNORE
        }
        return MergeScore(chapterNameScore, fingerprintScore, titleScore, authorScore, chapterCountScore, total, disposition)
    }

    /**
     * 章名序列相似度（最高 40 分）。
     *
     * 用最长公共子序列（LCS）长度除以较长序列长度：同一本书在各站章名几乎一致，
     * LCS 比接近 1；同名不同书 LCS 比低。对章名先过 [CommentKey.normalize] 去噪。
     */
    private fun scoreChapterNames(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val normA = a.map { CommentKey.normalize(it) }
        val normB = b.map { CommentKey.normalize(it) }
        val lcsLen = lcsLength(normA, normB)
        val ratio = lcsLen.toFloat() / maxOf(normA.size, normB.size)
        return (ratio * 40).toInt()
    }

    /** 段落指纹命中（最高 30 分）。取两书中段重叠章节的指纹交集，命中一次即满分 */
    private fun scoreFingerprints(a: Map<Int, String>, b: Map<Int, String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val commonChapters = a.keys.intersect(b.keys)
        val hits = commonChapters.count { a[it] == b[it] }
        return if (hits > 0) 30 else 0
    }

    /** 归一化书名相同（15 分） */
    private fun scoreTitle(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        return if (CommentKey.normalize(a) == CommentKey.normalize(b)) 15 else 0
    }

    /**
     * 作者相同（15 分）。一方为空时不降分（spec §9.4：本地书作者经常解析不出来），
     * 返回 0 而非负分。
     */
    private fun scoreAuthor(a: String, b: String): Int {
        val normA = CommentKey.normalize(a)
        val normB = CommentKey.normalize(b)
        if (normA.isEmpty() || normB.isEmpty()) return 0
        return if (normA == normB) 15 else 0
    }

    /** 章数接近（5 分）：差距在 10% 以内给满分 */
    private fun scoreChapterCount(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        val ratio = minOf(a, b).toFloat() / maxOf(a, b)
        return if (ratio >= 0.9f) 5 else 0
    }

    /** 最长公共子序列长度（DP，O(mn) 时间 O(n) 空间） */
    private fun lcsLength(a: List<String>, b: List<String>): Int {
        val m = a.size
        val n = b.size
        var prev = IntArray(n + 1)
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            for (j in 1..n) {
                curr[j] = if (a[i - 1] == b[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    maxOf(prev[j], curr[j - 1])
                }
            }
            val tmp = prev
            prev = curr
            curr = tmp
            curr.fill(0)
        }
        return prev[n]
    }
}

/** 合并三档处置（spec §9.4 三档阈值） */
enum class MergeDisposition {
    /** 高分：默认只加读并集，发轻确认通知 */
    AUTO_MERGE,
    /** 中分：列为候选等用户确认 */
    CANDIDATE,
    /** 低分：不打扰 */
    IGNORE,
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.domain.BookMergeScorerTest" 2>&1 | tail -20`
Expected: PASS (12 tests)

- [ ] **Step 5: Commit**

```bash
git add lib_book_common/src/main/java/com/ebook/common/domain/BookMergeScorer.kt \
       lib_book_common/src/test/java/com/ebook/common/domain/BookMergeScorerTest.kt
git commit -m "feat(lib_book_common): 新增 BookMergeScorer 自动合并打分算法

纯函数，五信号加权（章名 LCS、段落指纹、书名、作者、章数），
三档处置（AUTO_MERGE >= 70 / CANDIDATE >= 40 / IGNORE < 40）。
spec §9.4 信号表与阈值直接映射。"
```

---

### Task 4: DuplicateBookDetector — 书架扫描与合并建议推送

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/domain/DuplicateBookDetector.kt`
- Create: `lib_book_common/src/test/java/com/ebook/common/domain/DuplicateBookDetectorTest.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt`

- [ ] **Step 1: Write the failing tests**

Create `lib_book_common/src/test/java/com/ebook/common/domain/DuplicateBookDetectorTest.kt`:

```kotlin
package com.ebook.common.domain

import com.ebook.common.repository.FakeBookGroupDao
import com.ebook.common.repository.FakeBookInfoDao
import com.ebook.common.repository.FakeBookShelfDao
import com.ebook.common.repository.FakeChapterListDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DuplicateBookDetectorTest {

    private lateinit var shelfDao: FakeBookShelfDao
    private lateinit var infoDao: FakeBookInfoDao
    private lateinit var chapterDao: FakeChapterListDao
    private lateinit var groupDao: FakeBookGroupDao
    private lateinit var detector: DuplicateBookDetector

    @Before
    fun setUp() {
        shelfDao = FakeBookShelfDao()
        infoDao = FakeBookInfoDao()
        chapterDao = FakeChapterListDao()
        groupDao = FakeBookGroupDao()
        detector = DuplicateBookDetector(shelfDao, infoDao, chapterDao, groupDao)
    }

    @Test
    fun `detects same book from different sources as AUTO_MERGE`() = runTest {
        addBook("url1", "斗破苍穹", "天蚕土豆", listOf("第一章 初遇", "第二章 修炼"))
        addBook("url2", "斗破苍穹", "天蚕土豆", listOf("第一章 初遇", "第二章 修炼"))

        val suggestions = detector.scan()

        assertEquals(1, suggestions.size)
        assertEquals(MergeDisposition.AUTO_MERGE, suggestions[0].disposition)
    }

    @Test
    fun `ignores completely different books`() = runTest {
        addBook("url1", "书A", "作者一", listOf("序", "一", "二"))
        addBook("url2", "书B", "作者二", listOf("楔", "甲", "乙"))

        val suggestions = detector.scan()

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `skips pairs already merged (same primary key)`() = runTest {
        addBook("url1", "斗破苍穹", "天蚕土豆", listOf("第一章"))
        addBook("url2", "斗破苍穹", "天蚕土豆", listOf("第一章"))
        // 已经合并：url1 的并集里包含 url2 的键
        val key2 = com.ebook.common.domain.CommentKey.compute("斗破苍穹", "天蚕土豆")
        groupDao.insert(BookGroupEntity(key2, "url1", isPrimary = false))

        val suggestions = detector.scan()

        // 已合并的对不再建议
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `single book on shelf produces no suggestions`() = runTest {
        addBook("url1", "斗破苍穹", "天蚕土豆", listOf("第一章"))

        val suggestions = detector.scan()

        assertTrue(suggestions.isEmpty())
    }

    private suspend fun addBook(noteUrl: String, name: String, author: String, chapters: List<String>) {
        shelfDao.insert(BookShelfEntity(noteUrl = noteUrl))
        infoDao.insert(BookInfoEntity(noteUrl = noteUrl, name = name, author = author))
        chapterDao.insertAll(chapters.mapIndexed { i, chName ->
            ChapterListEntity(noteUrl = noteUrl, durChapterIndex = i, durChapterName = chName, contentRef = "$noteUrl/$i")
        })
        groupDao.insert(BookGroupEntity(
            commentKey = CommentKey.compute(name, author),
            noteUrl = noteUrl,
            isPrimary = true,
        ))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.domain.DuplicateBookDetectorTest" 2>&1 | tail -20`
Expected: FAIL — `DuplicateBookDetector` does not exist

- [ ] **Step 3: Implement DuplicateBookDetector**

Create `lib_book_common/src/main/java/com/ebook/common/domain/DuplicateBookDetector.kt`:

```kotlin
package com.ebook.common.domain

import com.ebook.common.domain.BookMergeScorer.BookCandidate
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书架重复检测（spec §9.4 §10 M2）。
 *
 * 扫描书架上所有条目，两两打分，输出非 IGNORE 的合并建议。调用方（BookShelfViewModel）
 * 在书架加载后触发一次，把结果经 SharedFlow 推给 UI 展示轻确认通知。
 *
 * 复杂度 O(n²)：书架通常 < 200 本，两两比较 < 20000 对，每对打分是纯字符串操作，
 * 毫秒级完成。如果将来书架规模上千，可以加"先按归一化书名分桶再桶内两两"的优化，
 * 但目前不需要。
 */
@Singleton
class DuplicateBookDetector @Inject constructor(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookGroupDao: BookGroupDao,
) {

    /** 一条合并建议：两本书 + 打分结果 */
    data class MergeSuggestion(
        val targetNoteUrl: String,
        val sourceNoteUrl: String,
        val score: BookMergeScorer.MergeScore,
        val disposition: MergeDisposition,
    )

    /**
     * 扫描书架，返回所有非 IGNORE 的合并建议。
     *
     * 已合并的对（target 的并集已包含 source 的主键）跳过，不重复建议。
     */
    suspend fun scan(): List<MergeSuggestion> = withContext(Dispatchers.IO) {
        val books = bookShelfDao.getAllBooks()
        if (books.size < 2) return@withContext emptyList()

        // 构建候选列表
        val candidates = books.mapNotNull { shelf ->
            val info = bookInfoDao.getBookInfoByUrl(shelf.noteUrl) ?: return@mapNotNull null
            val chapters = chapterListDao.getChaptersForBook(shelf.noteUrl)
            BookCandidate(
                noteUrl = shelf.noteUrl,
                title = info.name,
                author = info.author,
                chapterNames = chapters.map { it.durChapterName },
                chapterFingerprints = emptyMap(), // 段落指纹需要读章文件，暂不启用
            )
        }

        val suggestions = mutableListOf<MergeSuggestion>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val a = candidates[i]
                val b = candidates[j]
                val mergeScore = BookMergeScorer.score(a, b)
                if (mergeScore.disposition == MergeDisposition.IGNORE) continue

                // 检查是否已合并：a 的并集包含 b 的主键，或 b 的并集包含 a 的主键
                if (alreadyMerged(a.noteUrl, b.noteUrl)) continue

                suggestions.add(MergeSuggestion(a.noteUrl, b.noteUrl, mergeScore, mergeScore.disposition))
            }
        }
        suggestions.sortedByDescending { it.score.total }
    }

    /** 判定两本书是否已在同一个并集里（任一方的 book_group 行包含对方的主键） */
    private suspend fun alreadyMerged(noteUrlA: String, noteUrlB: String): Boolean {
        val keysA = bookGroupDao.getKeysForNoteUrl(noteUrlA)
        val keysB = bookGroupDao.getKeysForNoteUrl(noteUrlB)
        val primaryB = bookGroupDao.getPrimaryForNoteUrl(noteUrlB) ?: return false
        val primaryA = bookGroupDao.getPrimaryForNoteUrl(noteUrlA) ?: return false
        return primaryB in keysA || primaryA in keysB
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.domain.DuplicateBookDetectorTest" 2>&1 | tail -20`
Expected: PASS (4 tests)

- [ ] **Step 5: Run all existing tests to verify no regression**

Run: `./gradlew :lib_book_common:testDebugUnitTest 2>&1 | tail -20`
Expected: All pass

- [ ] **Step 6: Commit**

```bash
git add lib_book_common/src/main/java/com/ebook/common/domain/DuplicateBookDetector.kt \
       lib_book_common/src/test/java/com/ebook/common/domain/DuplicateBookDetectorTest.kt
git commit -m "feat(lib_book_common): 新增 DuplicateBookDetector 书架重复检测

两两打分扫描，跳过已合并对，输出非 IGNORE 的合并建议列表。
段落指纹暂不启用（需读章文件），其余四信号已足够判定同书不同源。"
```

---

### Task 5: BookShelfViewModel 接入合并建议 + SnackBar 通知

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookShelfViewModel.kt`
- Modify: `module_book/src/main/java/com/ebook/book/page/BookShelfPage.kt`

- [ ] **Step 1: Read BookShelfViewModel to understand current structure**

Read `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookShelfViewModel.kt` and note the existing data loading flow.

- [ ] **Step 2: Add DuplicateBookDetector injection and suggestion Flow to BookShelfViewModel**

Add to the ViewModel:

```kotlin
    private val _mergeSuggestions = MutableSharedFlow<List<DuplicateBookDetector.MergeSuggestion>>(
        extraBufferCapacity = 1
    )
    val mergeSuggestions: SharedFlow<List<DuplicateBookDetector.MergeSuggestion>> =
        _mergeSuggestions.asSharedFlow()

    fun scanForDuplicates() {
        viewModelScope.launch {
            val suggestions = duplicateBookDetector.scan()
            if (suggestions.isNotEmpty()) {
                _mergeSuggestions.tryEmit(suggestions)
            }
        }
    }

    fun acceptMerge(targetNoteUrl: String, sourceNoteUrl: String) {
        viewModelScope.launch {
            bookRepository.mergeBooks(targetNoteUrl, sourceNoteUrl)
            refreshBookList()
        }
    }
```

Inject `DuplicateBookDetector` into the ViewModel constructor.

- [ ] **Step 3: Add merge suggestion SnackBar to BookShelfPage**

In `BookShelfPage.kt`, collect `mergeSuggestions` and show a SnackBar for AUTO_MERGE suggestions:

```kotlin
    val suggestions by viewModel.mergeSuggestions.collectAsState(initial = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(suggestions) {
        suggestions.firstOrNull()?.let { suggestion ->
            val result = snackbarHostState.showSnackbar(
                message = "检测到同一本书的 2 个来源，点击合并",
                actionLabel = "合并",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.acceptMerge(suggestion.targetNoteUrl, suggestion.sourceNoteUrl)
            }
        }
    }
```

- [ ] **Step 4: Trigger scan after book list loads**

In the book list loading callback, add `viewModel.scanForDuplicates()` after the list is populated.

- [ ] **Step 5: Build and verify**

Run: `./gradlew :module_book:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookShelfViewModel.kt \
       module_book/src/main/java/com/ebook/book/page/BookShelfPage.kt
git commit -m "feat(module_book): 书架页接入重复检测与合并建议 SnackBar

书架加载后自动扫描重复书目，高分对弹 SnackBar「检测到同一本书的 2 个来源」，
用户点「合并」执行 mergeBooks 把 source 主键加到 target 并集。"
```

---

### Task 6: EditBookMetaActivity — 修键面板

**Files:**
- Create: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/EditBookMetaViewModel.kt`
- Create: `module_book/src/main/java/com/ebook/book/EditBookMetaActivity.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/event/KeyCode.kt`
- Modify: `module_book/src/main/AndroidManifest.xml`
- Modify: `module_book/src/main/module/AndroidManifest.xml`

- [ ] **Step 1: Add route constant**

Add to `lib_book_common/src/main/java/com/ebook/common/event/KeyCode.kt` (in the `Book` object):

```kotlin
const val EDIT_BOOK_META_PATH = "/book/edit_meta"
```

- [ ] **Step 2: Create EditBookMetaViewModel**

Create `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/EditBookMetaViewModel.kt`:

```kotlin
package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.domain.CommentKey
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.CommentRepository
import com.ebook.db.entity.BookGroupEntity
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 修键面板 ViewModel（spec §9.3）。
 *
 * 展示当前主匹配名/作者/主键/已关联键列表。用户编辑后重算键、切主键，
 * 并迁移本人旧评论。
 */
@HiltViewModel
class EditBookMetaViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val commentRepository: CommentRepository,
) : BaseViewModel<NoOpModel>(NoOpModel()) {

    private val _state = MutableStateFlow(EditBookMetaState())
    val state: StateFlow<EditBookMetaState> = _state.asStateFlow()

    /** 当前书的 noteUrl，由 Activity initData 设置 */
    var noteUrl: String = ""

    fun loadState() {
        viewModelScope.launch {
            val rows = bookRepository.getBookGroupRows(noteUrl)
            val shelf = bookRepository.getBookByUrl(noteUrl)
            val primary = rows.firstOrNull { it.isPrimary }?.commentKey
            _state.value = EditBookMetaState(
                matchName = shelf?.matchName ?: "",
                matchAuthor = shelf?.matchAuthor ?: "",
                primaryKey = primary ?: "",
                associatedKeys = rows.filter { !it.isPrimary }.map { it.commentKey },
                allKeys = rows.map { it.commentKey },
            )
        }
    }

    /**
     * 保存：重算键 → 切主键 → 迁移本人评论。
     *
     * @return 迁移的评论数（供 UI 展示确认文案）
     */
    fun save(newMatchName: String, newMatchAuthor: String) {
        viewModelScope.launch {
            val (oldKey, newKey) = bookRepository.updateMatchMeta(noteUrl, newMatchName, newMatchAuthor)
            if (oldKey != newKey) {
                val result = commentRepository.migrateMyComments(oldKey, newKey)
                result.onSuccess { resp ->
                    sendToast("你的 ${resp.migratedCount} 条评论已随之迁移；该桶内他人评论不会移动")
                }
            }
            loadState()
            sendFinish()
        }
    }

    /** 拆分：从并集里删掉一个 secondary 键 */
    fun removeAssociatedKey(keyToRemove: String) {
        viewModelScope.launch {
            bookRepository.splitBook(noteUrl, keyToRemove)
            loadState()
        }
    }
}

data class EditBookMetaState(
    val matchName: String = "",
    val matchAuthor: String = "",
    val primaryKey: String = "",
    val associatedKeys: List<String> = emptyList(),
    val allKeys: List<String> = emptyList(),
)
```

- [ ] **Step 3: Create EditBookMetaActivity**

Create `module_book/src/main/java/com/ebook/book/EditBookMetaActivity.kt`:

```kotlin
package com.ebook.book

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ebook.book.mvvm.viewmodel.EditBookMetaState
import com.ebook.book.mvvm.viewmodel.EditBookMetaViewModel
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.ui.CommonUiTokens
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity

/**
 * 修键面板（spec §9.3）：编辑主匹配名/作者，查看当前主键与已关联键列表。
 *
 * 入口：书架页长按书籍 → 「编辑匹配信息」。路由参数携带 `noteUrl`。
 * 键是算出来的，面板让用户看得见输入项（主匹配名、匹配作者），否则它是魔法、错了无法 debug。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Book.EDIT_BOOK_META_PATH)
class EditBookMetaActivity : BaseMvvmActivity<EditBookMetaViewModel>() {
    override val viewModel: EditBookMetaViewModel by viewModels()

    override fun initData() {
        viewModel.noteUrl = intent.extras?.getString(RouteArgs.NOTE_URL) ?: ""
    }

    @Composable
    override fun PageContent() {
        val state by viewModel.state.collectAsState()
        var matchName by remember { mutableStateOf("") }
        var matchAuthor by remember { mutableStateOf("") }

        LaunchedEffect(Unit) { viewModel.loadState() }
        LaunchedEffect(state) {
            if (state.matchName.isNotEmpty() && matchName.isEmpty()) {
                matchName = state.matchName
            }
            if (state.matchAuthor.isNotEmpty() && matchAuthor.isEmpty()) {
                matchAuthor = state.matchAuthor
            }
        }

        EditBookMetaScreen(
            state = state,
            matchName = matchName,
            matchAuthor = matchAuthor,
            onMatchNameChange = { matchName = it },
            onMatchAuthorChange = { matchAuthor = it },
            onSave = { viewModel.save(matchName, matchAuthor) },
            onRemoveKey = { viewModel.removeAssociatedKey(it) },
        )
    }
}

@Composable
private fun EditBookMetaScreen(
    state: EditBookMetaState,
    matchName: String,
    matchAuthor: String,
    onMatchNameChange: (String) -> Unit,
    onMatchAuthorChange: (String) -> Unit,
    onSave: () -> Unit,
    onRemoveKey: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(CommonUiTokens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 匹配名与匹配作者输入
        OutlinedTextField(
            value = matchName,
            onValueChange = onMatchNameChange,
            label = { Text("主匹配名") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = matchAuthor,
            onValueChange = onMatchAuthorChange,
            label = { Text("匹配作者（可空）") },
            modifier = Modifier.fillMaxWidth(),
        )

        // 当前主键展示
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("当前主键", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.primaryKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // 已关联的其他键列表（合并历史）
        if (state.associatedKeys.isNotEmpty()) {
            Text("已关联的其他键（合并历史）", style = MaterialTheme.typography.titleSmall)
            state.associatedKeys.forEach { key ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRemoveKey(key) }) {
                        Text("移除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存并迁移评论")
        }
    }
}
```

- [ ] **Step 4: Register Activity in both manifests**

Add to `module_book/src/main/AndroidManifest.xml` (inside `<application>`):

```xml
<activity
    android:name=".EditBookMetaActivity"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.DayNight.NoActionBar" />
```

Add the same to `module_book/src/main/module/AndroidManifest.xml`.

- [ ] **Step 5: Add NOTE_URL to RouteArgs if not present**

Check `lib_book_common/src/main/java/com/ebook/common/event/RouteArgs.kt` for `NOTE_URL` constant. If absent, add:

```kotlin
const val NOTE_URL = "note_url"
```

- [ ] **Step 6: Build and verify**

Run: `./gradlew :module_book:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add module_book/src/main/java/com/ebook/book/EditBookMetaActivity.kt \
       module_book/src/main/java/com/ebook/book/mvvm/viewmodel/EditBookMetaViewModel.kt \
       module_book/src/main/AndroidManifest.xml \
       module_book/src/main/module/AndroidManifest.xml \
       lib_book_common/src/main/java/com/ebook/common/event/KeyCode.kt
git commit -m "feat(module_book): 新增修键面板 EditBookMetaActivity

面板含主匹配名/匹配作者输入、当前主键展示、已关联键列表（可移除）。
保存时重算键、切主键、迁移本人旧评论（spec §9.3）。"
```

---

### Task 7: 书架页导航到修键面板入口

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/page/BookShelfPage.kt`

- [ ] **Step 1: Add long-press menu item for editing book metadata**

In the book shelf item's long-press menu / dropdown, add a "编辑匹配信息" option that navigates to `EditBookMetaActivity`:

```kotlin
import com.therouter.router.TheRouter
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs

// In the book item long-press menu:
DropdownMenuItem(
    text = { Text("编辑匹配信息") },
    onClick = {
        val bundle = Bundle().apply {
            putString(RouteArgs.NOTE_URL, bookShelf.noteUrl)
        }
        TheRouter.build(KeyCode.Book.EDIT_BOOK_META_PATH)
            .with(bundle)
            .navigation(context)
        menuExpanded = false
    }
)
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew :module_book:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module_book/src/main/java/com/ebook/book/page/BookShelfPage.kt
git commit -m "feat(module_book): 书架页长按菜单新增「编辑匹配信息」入口

导航到修键面板，携带 noteUrl 路由参数。"
```

---

### Task 8: 文档更新

**Files:**
- Modify: `docs/superpowers/specs/2026-09-04-local-book-import-design.md`

- [ ] **Step 1: Update spec §15 status**

Update the M2 status from "未做" to reflect the completed work:

```
- **M2 来源分组与评论**：已完成。comment_key 派生与消费、book_group 多键关联、
  评论读写换键（读并集、写主键）、migrateMyComments 接口、自动合并算法
  （BookMergeScorer 五信号打分 + 三档阈值）、重复检测（DuplicateBookDetector）、
  合并建议 SnackBar、合并/拆分 UI、修键面板（EditBookMetaActivity）。
```

- [ ] **Step 2: Update spec line 2 status**

Change from "待复核（spec 阶段，未开始实现）" to reflect that M1a/M1b/M2/M3 are all implemented.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-04-local-book-import-design.md
git commit -m "docs(spec): 更新 M2 里程碑状态为已完成"
```

---

## 人工装机验证项

> 本节按 2026-09-05 后续改动重写：原第 1、2 条点名的入口（书架 SnackBar 合并、书架长按菜单
> →「编辑匹配信息」）已不存在——重复处置收拢到**导入时点**，修键面板改从**书籍详情页正文**进入，
> `BookMergeScorer` 五信号打分与书架侧扫描整链删除。取代关系与理由见
> `docs/adr/0023-import-time-duplicate-disposition.md`。

完成全部 Task 后，需在真机或模拟器上验证：

1. **导入时点判重**：导入与架上某条目同书名同作者的本地文件 → 弹四动作处置框并列出全部命中条目；
   点「继续添加」后书架出现两个条目、两本都能看到同一批评论。作者不同的同名书**不该**弹框。
2. **智能合并**：新文件结尾比架上那本多出若干章 → 合并后旧条目章数增加、阅读进度不变、新条目消失；
   章节命名不一致（`第1章` vs `第一章`）时提示未补章且两本都在；命中项是网络书时框内不出现该选项。
3. **覆盖**：旧条目从书架消失、新条目在架，且旧条目身上挂过的关联键被新条目吸收（修键面板可见）。
4. **修键面板**：书籍详情页正文底部「编辑匹配信息」→ 改匹配书名 → 保存 → 确认当前主键变化、
   旧键保留在关联列表、本人旧评论迁移提示；**书架显示名不变**。
5. **拆分**：修键面板点「移除」一个关联键 → 确认该键从列表消失。
6. **评论写入仍走主键**：处置后发评论，确认 comment_key 是主键（非 secondary）。
7. **顶栏与转场**：导入页/详情页/书城搜索页在 `CAppTransparentTheme` 下线后状态栏与转场观感正常，
   详情页返回箭头存在。
