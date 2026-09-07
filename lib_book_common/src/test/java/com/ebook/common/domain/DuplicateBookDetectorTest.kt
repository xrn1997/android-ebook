package com.ebook.common.domain

import com.ebook.common.repository.FakeBookGroupDao
import com.ebook.common.repository.FakeBookInfoDao
import com.ebook.common.repository.FakeBookShelfDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DuplicateBookDetector.findMatchesFor] 的单元测试。
 *
 * 锁的是判重口径本身——判重结果直接决定 UI 给不给「覆盖」这个会删条目的按钮，
 * 误判一次的代价是删掉一本无辜的书：
 * - 键含作者（spec §9.1），只比书名不算判重
 * - 比的是当前主键（spec §9.3 修键面板维护的那一行），不是显示书名
 * - 命中几本就返回几本，调用方没有"取第一条"的余地
 */
class DuplicateBookDetectorTest {

    private lateinit var shelfDao: FakeBookShelfDao
    private lateinit var infoDao: FakeBookInfoDao
    private lateinit var groupDao: FakeBookGroupDao
    private lateinit var detector: DuplicateBookDetector

    @Before
    fun setUp() {
        shelfDao = FakeBookShelfDao()
        infoDao = FakeBookInfoDao()
        groupDao = FakeBookGroupDao()
        detector = DuplicateBookDetector(shelfDao, infoDao, groupDao)
    }

    @Test
    fun `同书名同作者命中`() : Unit = runTest {
        addBook("url1", "斗破苍穹", "天蚕土豆")

        val matches = detector.findMatchesFor(ParsedBookMeta("《斗破苍穹》", "天蚕土豆"))

        assertEquals(1, matches.size)
        assertEquals("url1", matches.single().noteUrl)
    }

    @Test
    fun `同名不同作者不命中`() : Unit = runTest {
        addBook("url1", "斗破苍穹", "天蚕土豆")

        val matches = detector.findMatchesFor(ParsedBookMeta("斗破苍穹", "另一个人"))

        assertTrue("同名不同作者是两本书，命中即给删除入口，不能误判", matches.isEmpty())
    }

    @Test
    fun `作者占位词两边都归空所以命中`() : Unit = runTest {
        // 导入侧解不出作者时填「侠名」，架上那本写的是「佚名」——两者都不参与哈希
        addBook("url1", "斗破苍穹", "佚名")

        val matches = detector.findMatchesFor(ParsedBookMeta("斗破苍穹", "侠名"))

        assertEquals(1, matches.size)
    }

    @Test
    fun `比对的是当前主键，改过匹配名之后检测跟着走`() : Unit = runTest {
        // 显示名仍是《星辰变》，但用户已把匹配名改成「星辰变 全集」→ 主键按后者算
        addBook("url1", "《星辰变》", "我吃西红柿")
        val editedKey = CommentKey.compute("星辰变 全集", "我吃西红柿")
        groupDao.clearPrimary("url1")
        groupDao.insert(BookGroupEntity(commentKey = editedKey, noteUrl = "url1", isPrimary = true))

        assertTrue(
            "按显示名导入不该再被判重——那正是修键面板要解决的错并",
            detector.findMatchesFor(ParsedBookMeta("《星辰变》", "我吃西红柿")).isEmpty()
        )
        assertEquals(
            1,
            detector.findMatchesFor(ParsedBookMeta("星辰变 全集", "我吃西红柿")).size
        )
    }

    @Test
    fun `secondary 键不参与判重`() : Unit = runTest {
        addBook("url1", "斗破苍穹", "天蚕土豆")
        // url1 通过合并沾上了另一个键，但它当前认定自己是「斗破苍穹/天蚕土豆」
        val foreignKey = CommentKey.compute("别一部书", "另一个作者")
        groupDao.insert(BookGroupEntity(foreignKey, "url1", isPrimary = false))

        val matches = detector.findMatchesFor(ParsedBookMeta("别一部书", "另一个作者"))

        assertTrue("判重看的是这本书是谁，不是它读过谁的评论桶", matches.isEmpty())
    }

    @Test
    fun `同键的多个条目全部返回并按来源标注`() : Unit = runTest {
        addBook("local-url", "斗破苍穹", "天蚕土豆", isLocal = true)
        addBook("net-url", "斗破苍穹", "天蚕土豆", isLocal = false)

        val matches = detector.findMatchesFor(ParsedBookMeta("斗破苍穹", "天蚕土豆"))

        assertEquals("覆盖会删掉全部命中项，一条都不能藏", 2, matches.size)
        assertTrue(matches.single { it.noteUrl == "local-url" }.isLocal)
        assertTrue(matches.single { it.noteUrl == "net-url" }.isLocal.not())
    }

    /**
     * 种一本书：书架行（含来源 tag）+ 书籍信息 + 一行主键。
     *
     * 主键按 `compute(name, author)` 写，与 `addToShelf` / 导入器落库时的算法一致，
     * 这样用例测的是判重口径本身，而不是某个手工编出来的键形状。
     */
    private suspend fun addBook(
        noteUrl: String,
        name: String,
        author: String,
        isLocal: Boolean = true,
    ) {
        val tag = if (isLocal) BookShelfEntity.LOCAL_TAG else "书源A"
        shelfDao.insert(BookShelfEntity(noteUrl = noteUrl, tag = tag))
        infoDao.insert(BookInfoEntity(noteUrl = noteUrl, name = name, author = author))
        groupDao.insert(
            BookGroupEntity(
                commentKey = CommentKey.compute(name, author),
                noteUrl = noteUrl,
                isPrimary = true,
            )
        )
    }
}
