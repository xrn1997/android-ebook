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

        dao.clearPrimary("url1")
        dao.promotePrimary("url1", "ck1:bbb")

        val rows = dao.getAllForNoteUrl("url1")
        val primary = rows.single { it.isPrimary }
        assertEquals("ck1:bbb", primary.commentKey)
        val secondary = rows.single { !it.isPrimary }
        assertEquals("ck1:aaa", secondary.commentKey)
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
