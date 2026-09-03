package com.ebook.db.dao

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.AppDatabase
import com.ebook.db.entity.SearchHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SearchHistoryDao] 的回归测试（Robolectric + Room 内存库，JVM 上直接跑真实 SQL）。
 *
 * 锁定搜索历史的语义（见 ADR-0005）：
 * - 面板展示取该类型全量（[SearchHistoryDao.getByType]）
 * - 清除操作清空该类型全部历史（[SearchHistoryDao.clearByType]）
 * - upsert 查重（[SearchHistoryDao.findByTypeAndContent]）保持精确匹配
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchHistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SearchHistoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 不 setDriver(BundledSQLiteDriver)：其原生库只随 APK 打包，JVM 单测加载不到；
        // Android 构建的默认驱动是 FrameworkSQLiteDriver，由 Robolectric 模拟实现
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.searchHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 与搜索页一致的测试数据：type = SearchViewModel.BOOK(2)。 */
    private fun seedHistory() = runBlocking {
        dao.insert(SearchHistoryEntity(type = 2, content = "斗破苍穹", date = 1L))
        dao.insert(SearchHistoryEntity(type = 2, content = "斗罗大陆", date = 2L))
        dao.insert(SearchHistoryEntity(type = 2, content = "凡人修仙传", date = 3L))
    }

    @Test
    fun `取该类型全部历史`() = runBlocking {
        seedHistory()

        val all = dao.getByType(2)

        assertEquals(3, all.size)
    }

    @Test
    fun `历史按时间倒序返回`() = runBlocking {
        seedHistory()

        val all = dao.getByType(2)

        assertEquals(listOf("凡人修仙传", "斗罗大陆", "斗破苍穹"), all.map { it.content })
    }

    @Test
    fun `清除该类型全部历史`() = runBlocking {
        seedHistory()

        dao.clearByType(2)

        assertEquals(0, dao.getByType(2).size)
    }

    @Test
    fun `清除不影响其他类型历史`() = runBlocking {
        seedHistory()
        dao.insert(SearchHistoryEntity(type = 1, content = "斗", date = 4L))

        dao.clearByType(2)

        assertEquals(listOf("斗"), dao.getByType(1).map { it.content })
    }

    @Test
    fun `精确内容查询用于 upsert 查重`() = runBlocking {
        seedHistory()

        assertNotNull(dao.findByTypeAndContent(2, "斗破苍穹"))
        assertNull(dao.findByTypeAndContent(2, "斗"))
    }
}
