package com.ebook.me.mvvm.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ebook.common.store.BookStore
import com.ebook.me.repository.CacheModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CacheManageViewModel] 的「书籍内容」呈现测试。
 *
 * 锁住缓存管理页的范围边界：页面上那个可清理的总量**只含 cacheDir**，书籍内容
 * （`filesDir/books` 的章文件）**单列一行**呈现——它占的往往比缓存多一个量级，
 * 不显示就会让用户以为「缓存才几十 MB，手机报的占用错了」；而把它算进可清理总量，
 * 又会诱使用户一键清掉自己下载的藏书。两条都得钉住。
 *
 * 接缝都是临时的（`CacheModel(cacheRoot)`、`BookStore(booksRoot)`），所以整页编排在
 * 纯 JVM 下可断言——这是 C4 把 `CacheModel` 从 `Application` 换成目录参数之后才有的余地。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheManageViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @JvmField
    @Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheRoot: File
    private lateinit var booksRoot: File

    /**
     * 轮询等到 [cond] 成立：交替推进测试调度器与真实时间。
     *
     * 必要的理由同 `SettingViewModelTest`：`CacheModel`/`BookStore` 的遍历挂在真实
     * `Dispatchers.IO` 上，不受虚拟时钟控制，只 `advanceUntilIdle()` 会读到还没算完的空状态。
     */
    private suspend fun TestScope.awaitUntil(message: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            advanceUntilIdle()
            if (cond()) break
            assertTrue("$message（5s 内未达成）", System.currentTimeMillis() < deadline)
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        advanceUntilIdle()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        cacheRoot = tempFolder.newFolder("cache")
        booksRoot = tempFolder.newFolder("books")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun File.put(name: String, size: Int): File =
        File(this, name).apply { parentFile?.mkdirs(); writeText("x".repeat(size)) }

    private fun newViewModel(): CacheManageViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return CacheManageViewModel(
            context = app,
            cacheModel = CacheModel(cacheRoot),
            bookStore = BookStore(booksRoot),
        )
    }

    @Test
    fun `书籍内容单列呈现且不计入可清理总量`() = runTest(mainDispatcher) {
        File(cacheRoot, "image_cache").put("cover.bin", 1_000)
        File(booksRoot, "book-a").put("c00001.txt", 5_000)
        File(booksRoot, "book-b").put("c00001.txt", 2_000)

        val viewModel = newViewModel()
        awaitUntil("书籍占用应出现在页面上") { viewModel.cacheState.value.booksSizeText.isNotEmpty() }

        val state = viewModel.cacheState.value
        assertEquals("藏书占用的数字要看得见", "6.8 KB", state.booksSizeText)
        assertEquals("同一行还要报出藏了几本", 2, state.bookCount)
        assertEquals("可清理总量仍只算 cacheDir", "1000 B", state.totalText)
        assertEquals("按钮可用态也不被书籍撑起来", 1_000L, state.totalBytes)
    }

    @Test
    fun `清理全部缓存不碰书籍内容`() = runTest(mainDispatcher) {
        File(cacheRoot, "image_cache").put("cover.bin", 1_000)
        val bookFile = File(booksRoot, "book-a").put("c00001.txt", 5_000)
        val viewModel = newViewModel()
        awaitUntil("初始占用应算完") { viewModel.cacheState.value.booksSizeText == "4.9 KB" }

        viewModel.clearAll()
        awaitUntil("缓存应被清空") { viewModel.cacheState.value.totalText == "0 B" }

        val state = viewModel.cacheState.value
        assertEquals("书籍内容一字不动", "4.9 KB", state.booksSizeText)
        assertEquals(5_000L, bookFile.length())
    }

    @Test
    fun `还没有任何藏书时书籍行显示零而不是空`() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        awaitUntil("空仓库也应给出明确的零") { viewModel.cacheState.value.booksSizeText == "0 B" }

        assertEquals("0 B", viewModel.cacheState.value.booksSizeText)
    }
}
