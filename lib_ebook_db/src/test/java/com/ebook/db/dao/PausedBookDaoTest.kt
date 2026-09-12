package com.ebook.db.dao

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.AppDatabase
import com.ebook.db.entity.PausedBookEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PausedBookDao] 的回归测试（Robolectric + Room 内存库，JVM 上直接跑真实 SQL）。
 *
 * 锁定按书暂停标记的语义（见 ADR-0036）：
 * - 行存在即该书暂停（[PausedBookDao.getAll]），删行即继续
 * - 重复暂停幂等（主键 REPLACE，一本书最多一行标记）
 * - [PausedBookDao.clearAll] 清空全部标记（随「清空队列/取消全部」连带清理）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PausedBookDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PausedBookDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 不 setDriver(...)：与 SearchHistoryDaoTest 同口径，锁 DAO 的 SQL 语义而非某个引擎的行为
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.pausedBookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `插入即暂停，删行即继续`() = runBlocking {
        dao.insert(PausedBookEntity(noteUrl = URL_A))

        assertEquals(listOf(URL_A), dao.getAll())

        dao.delete(URL_A)

        assertTrue("删行后不应再有暂停标记，实际为 ${dao.getAll()}", dao.getAll().isEmpty())
    }

    @Test
    fun `重复暂停同一本书幂等，不会长出第二行`() = runBlocking {
        dao.insert(PausedBookEntity(noteUrl = URL_A))
        dao.insert(PausedBookEntity(noteUrl = URL_A))

        assertEquals(
            "主键 REPLACE 应保证一本书最多一行标记，否则继续/暂停的判定会摇摆",
            listOf(URL_A),
            dao.getAll()
        )
    }

    @Test
    fun `多本书标记互不影响`() = runBlocking {
        dao.insert(PausedBookEntity(noteUrl = URL_A))
        dao.insert(PausedBookEntity(noteUrl = URL_B))

        dao.delete(URL_A)

        assertEquals(listOf(URL_B), dao.getAll())
    }

    @Test
    fun `清空全部标记`() = runBlocking {
        dao.insert(PausedBookEntity(noteUrl = URL_A))
        dao.insert(PausedBookEntity(noteUrl = URL_B))

        dao.clearAll()

        assertTrue(dao.getAll().isEmpty())
    }

    private companion object {
        const val URL_A = "https://a.example/book1"
        const val URL_B = "https://b.example/book2"
    }
}
