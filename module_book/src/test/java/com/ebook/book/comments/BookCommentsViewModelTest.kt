package com.ebook.book.comments

import androidx.test.core.app.ApplicationProvider
import com.ebook.api.auth.SessionEventBus
import com.ebook.api.auth.TokenRefresher
import com.ebook.api.entity.Comment
import com.ebook.api.entity.CommentMigrateResponse
import com.ebook.api.entity.CommentPage
import com.ebook.api.entity.User
import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.domain.UserSession
import com.ebook.common.repository.CommentRepository
import com.xrn1997.common.BaseApplication
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.dto.RespDTO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BookCommentsViewModel] 的翻页接线测试（ADR-0057：游标与信号时序收进会话单主）。
 *
 * 锁的不是判据（`mergeCommentPage` 由 `MergeCommentPageTest` 锁、会话本身由 `PageSessionTest` 锁），
 * 而是**这个宿主把会话接对了**：
 * - 起始页是 2——首屏走刷新（第 1 页），刷新后第一次触底必须向仓库要**第 2 页**；
 *   要成第 1 页就是游标复位没接上，列表会原地打转；
 * - 在途闸门由会话给出——刷新成功后连点两下触底，只放行一笔第 2 页请求。
 *
 * 跑法沿用仓内 VM 用例口径（`BookDetailViewModelSourceTest` 同款）：真 [CommentRepository] 配
 * 假 [CommentDataSource]，只把调度器挂 [mainDispatcher]，`advanceUntilIdle` 即可推进协程。
 * Robolectric 只为给 `BaseApplication.context` 一个真 Context（纯 JVM 下它是抛「Stub!」的桩）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookCommentsViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        BaseApplication.context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 轮询等到 [cond] 成立：交替推进测试调度器与真实时间。
     *
     * `CommentRepository.getComments` 走 `safeApiCall` 的 `withContext(Dispatchers.IO)`，那跑在
     * **真实 IO 线程**上、不受虚拟时钟控制，只推进调度器就会与之竞态（同一写法见
     * `BookDetailViewModelSourceTest`、`SourceSwitchViewModelTest`）。
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

    @Test
    fun `刷新后第一次触底向仓库要第 2 页`() = runTest(mainDispatcher) {
        val dataSource = FakeCommentDataSource()
        val viewModel = viewModelWith(dataSource)
        dataSource.enqueue(fullPage(idStart = 1L))

        viewModel.refreshData()
        awaitUntil("刷新已落地、列表已回填") { viewModel.list.value.isNotEmpty() }
        assertEquals("首屏走刷新、固定要第 1 页", listOf(1), dataSource.requestedPages)

        dataSource.enqueue(fullPage(idStart = 21L))
        viewModel.loadMore()
        awaitUntil("第 2 页已落地、列表已追加") { viewModel.list.value.size == 2 * PAGE_SIZE }

        assertEquals(
            "刷新成功后游标该复位到 startPage=2：取成第 1 页就是列表原地打转",
            listOf(1, 2),
            dataSource.requestedPages,
        )
        assertEquals("刷新整表替换第 1 页、加载更多追加第 2 页，去重后共 40 条", 2 * PAGE_SIZE, viewModel.list.value.size)
    }

    @Test
    fun `触底连发两下只放行一笔在途请求`() = runTest(mainDispatcher) {
        val dataSource = FakeCommentDataSource()
        val viewModel = viewModelWith(dataSource)
        dataSource.enqueue(fullPage(idStart = 1L))

        viewModel.refreshData()
        awaitUntil("刷新已落地、列表已回填") { viewModel.list.value.isNotEmpty() }

        // 只入队一笔第 2 页的应答：第二下若被闸门拦下，就取不到第二笔，removeFirst 不会炸
        dataSource.enqueue(fullPage(idStart = 21L))
        dataSource.gateNextCall()
        viewModel.loadMore()
        viewModel.loadMore()
        // 等第一笔第 2 页确实已抵达数据源（此刻它仍挂起在闸门上），第二下才真正落在它的在途窗口里
        awaitUntil("第一笔第 2 页请求已抵达数据源") { dataSource.requestedPages.contains(2) }

        assertEquals(
            "第二下触底被会话闸门拦下：只该有一笔第 2 页请求在飞",
            listOf(1, 2),
            dataSource.requestedPages,
        )

        dataSource.release()
        awaitUntil("释放后第 2 页落地、列表已追加") { viewModel.list.value.size == 2 * PAGE_SIZE }
        assertEquals("释放后第 2 页落地，列表追加到 40 条", 2 * PAGE_SIZE, viewModel.list.value.size)
    }

    private fun viewModelWith(dataSource: CommentDataSource) = BookCommentsViewModel(
        CommentRepository(
            dataSource,
            CoroutineAdapter(
                tokenRefresher = object : TokenRefresher {
                    // 成功路径不触达刷新器；真触发即为测试数据问题，返回 null 走会话过期路径足够醒目
                    override suspend fun refresh(expiredAccessToken: String?): String? = null
                },
                sessionEventBus = SessionEventBus(),
                tokenHolder = TokenHolder(),
            ),
        ),
        FakeUserSessionManager(),
    ).apply { commentKeys = listOf(KEY) }

    private companion object {
        const val KEY = "ck1:tianqi#0"

        /** 一整页（20 条）满页评论：仓库按 `items.size >= pageSize` 推 hasMore，
         *  少给一条就会被判到底、把会话的闸门锁死（PAGE_SIZE 见 `CommentRepository`）。 */
        fun fullPage(idStart: Long): RespDTO<CommentPage> = RespDTO(
            code = "00000",
            error = "",
            data = CommentPage(
                items = List(PAGE_SIZE) { index ->
                    Comment(
                        id = idStart + index,
                        user = User(id = 9L, username = "用户${idStart + index}", image = ""),
                        commentKey = KEY,
                        content = "评论 ${idStart + index}",
                        addTime = "2026-01-01 10:00:00",
                    )
                },
                total = PAGE_SIZE.toLong(),
                page = 1,
                pageSize = PAGE_SIZE,
            ),
        )

        /** 与 `CommentRepository.PAGE_SIZE` 对齐（私有常量读不到，这里复述一份） */
        const val PAGE_SIZE = 20
    }
}

/**
 * 记录每笔请求的页码、按序回放预置应答；[gateNextCall] 让下一笔挂起，
 * 好让「第二下触底」真正落在第一笔在途期间——不卡住的话闸门断言就成了恒真。
 */
private class FakeCommentDataSource : CommentDataSource {

    val requestedPages = mutableListOf<Int>()

    private val answers = ArrayDeque<RespDTO<CommentPage>>()

    private var gate: CompletableDeferred<Unit>? = null

    fun enqueue(answer: RespDTO<CommentPage>) {
        answers.addLast(answer)
    }

    fun gateNextCall() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
    }

    override suspend fun getComments(
        commentKeys: List<String>,
        page: Int,
        pageSize: Int,
    ): RespDTO<CommentPage> {
        requestedPages += page
        gate?.await()
        return answers.removeFirst()
    }

    override suspend fun addComment(comment: Comment): RespDTO<Comment> = unsupported()

    override suspend fun deleteComment(id: Long): RespDTO<Unit> = unsupported()

    override suspend fun getMyComments(page: Int, pageSize: Int): RespDTO<CommentPage> = unsupported()

    override suspend fun migrateMyComments(
        oldKey: String,
        newKey: String,
    ): RespDTO<CommentMigrateResponse> = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("FakeCommentDataSource 不该被触达")
}

/** 只服务 `currentUserId` 的身份流；本组用例不触达其余会话出口 */
private class FakeUserSessionManager : com.ebook.common.domain.UserSessionManager {
    override val isLoggedIn: StateFlow<Boolean> = MutableStateFlow(true)
    override val currentUser: StateFlow<UserSession?> = MutableStateFlow(null)

    override suspend fun saveSession(session: UserSession, refreshToken: String) = unsupported()
    override suspend fun rotateCredentials(accessToken: String, refreshToken: String) = unsupported()
    override fun clearSession(): Boolean = unsupported()
    override suspend fun endSession() = unsupported()
    override fun getRefreshToken(): String? = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("FakeUserSessionManager 不该被触达")
}
