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
    fun `switchPrimary demotes old row and keeps exactly one primary`() = runTest {
        dao.insert(BookGroupEntity("ck1:aaa", "url1", isPrimary = true))
        dao.insert(BookGroupEntity("ck1:bbb", "url1", isPrimary = false))

        // 生产形态（BookRepository.updateMatchMeta）：清零后插入新键行，而非提升既有行
        dao.clearPrimary("url1")
        dao.insert(BookGroupEntity("ck1:ccc", "url1", isPrimary = true))

        val rows = dao.getAllForNoteUrl("url1")
        assertEquals(3, rows.size)
        assertEquals("ck1:ccc", rows.single { it.isPrimary }.commentKey)
        assertEquals("ck1:ccc", dao.getPrimaryForNoteUrl("url1"))
        assertTrue(rows.filter { !it.isPrimary }.map { it.commentKey }.containsAll(listOf("ck1:aaa", "ck1:bbb")))
    }

    @Test
    fun `addSecondary inserts non-primary row`() = runTest {
        dao.insert(BookGroupEntity("ck1:aaa", "url1", isPrimary = true))

        dao.addSecondary(BookGroupEntity("ck1:bbb", "url1", isPrimary = false))

        val rows = dao.getAllForNoteUrl("url1")
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.commentKey == "ck1:bbb" && !it.isPrimary })
    }
}
