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
 *    [BookInfoDao.insert] 写时间戳」准备的 —— 那是整行 REPLACE，要求调用方传字段完整的对象，
 *    漏读或漏填任一字段就会把书名/封面抹成实体默认值，而页面只是少显示几项、不崩不报错，
 *    属最难发现的一类数据损坏。
 * 2. 按主键精确命中、不误伤别的行；行不存在时静默不写、不抛。
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

        assertEquals(
            "定向 UPDATE 不该改动除 final_refresh_data 以外的任何列",
            original.copy(finalRefreshData = 1_700_000_000_000L),
            dao.getBookInfoByUrl(URL_A),
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
    fun `行不存在时静默不写也不抛`() = runBlocking {
        dao.setFinalRefreshData(noteUrl = "nonexistent", timestamp = 1L)

        assertEquals(null, dao.getBookInfoByUrl("nonexistent"))
    }

    private companion object {
        // noteUrl 在本仓同时是内容仓库的目录名，不带 scheme 的形态在 Windows 的 JVM 测试里更安全
        const val URL_A = "a.example/book/1.html"
        const val URL_B = "a.example/book/2.html"
        const val SOURCE = "https://a.example"
    }
}
