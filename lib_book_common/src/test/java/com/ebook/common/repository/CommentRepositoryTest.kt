package com.ebook.common.repository

import com.ebook.api.auth.SessionEventBus
import com.ebook.api.auth.TokenRefresher
import com.ebook.api.entity.Comment
import com.ebook.api.entity.CommentMigrateResponse
import com.ebook.api.entity.CommentPage
import com.ebook.api.entity.User
import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.utils.CoroutineAdapter
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.dto.RespDTO
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CommentRepository] 的 JVM 单元测试（假数据源 + 真实 [CoroutineAdapter]）。
 *
 * 2026-09-07 评论分页改造时补齐——此前该仓库层零覆盖（见 docs/test-coverage-todo.md）。
 * 假件路子沿用 [com.ebook.common.domain.SessionTokenRefresherTest]：不引入
 * Robolectric/Android 依赖，[CoroutineAdapter] 的三个依赖（刷新器/事件总线/令牌桶）
 * 全部直接构造或匿名实现，成功路径不触达刷新器。
 *
 * 锁住的行为：
 * - **空键守卫**：空 `commentKeys` 直接返回空页、不发请求——网络层把空键翻译成
 *   `comment_keys=null` 会命中「返回全局最新列表」的契约分支（M2 spec §3.2.1）
 * - **hasMore 推导**：返回条数达到请求的 pageSize 即视为可能还有下一页，不依赖服务端 total
 * - **透传**：page/pageSize 原样到达数据源
 * - **失败翻译**：非成功业务码 → 失败；数据源抛异常 → 失败（不炸出）
 * - **兜底**：data 为 null 的响应兜底为空页（维持分页改造前的容错口径）
 */
class CommentRepositoryTest {

    // ===== 空键守卫 =====

    @Test
    fun `空键列表直接返回空页且不发请求`() = runTest {
        val (repository, dataSource) = repository()

        val result = repository.getComments(emptyList(), page = 1)

        val page = result.getOrThrow()
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        assertEquals("空键不得触达数据源", 0, dataSource.getCommentsCalls)
    }

    // ===== hasMore 推导 =====

    @Test
    fun `返回条数达到请求的页大小时 hasMore 为真`() = runTest {
        val (repository, dataSource) = repository()
        dataSource.nextGetCommentsPages.addLast(fullPage(size = 100))

        val page = repository.getComments(listOf(KEY), page = 1, pageSize = 100).getOrThrow()

        assertEquals(100, page.items.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `返回条数不足页大小时 hasMore 为假`() = runTest {
        val (repository, dataSource) = repository()
        dataSource.nextGetCommentsPages.addLast(fullPage(size = 3))

        val page = repository.getComments(listOf(KEY), page = 1, pageSize = 100).getOrThrow()

        assertEquals(3, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `整页之后取回空页时 hasMore 收敛为假`() = runTest {
        val (repository, dataSource) = repository()
        dataSource.nextGetCommentsPages.addLast(fullPage(size = 100))
        dataSource.nextGetCommentsPages.addLast(fullPage(size = 0))

        val first = repository.getComments(listOf(KEY), page = 1, pageSize = 100).getOrThrow()
        val second = repository.getComments(listOf(KEY), page = 2, pageSize = 100).getOrThrow()

        assertTrue(first.hasMore)
        assertTrue(second.items.isEmpty())
        assertFalse(second.hasMore)
    }

    // ===== 透传与映射 =====

    @Test
    fun `page 与 pageSize 原样透传给数据源`() = runTest {
        val (repository, dataSource) = repository()
        dataSource.nextGetCommentsPages.addLast(fullPage(size = 1))

        repository.getComments(listOf(KEY), page = 3, pageSize = 50).getOrThrow()

        assertEquals(listOf(3 to 50), dataSource.requestedPageAndSizes)
    }

    @Test
    fun `评论条目映射到领域模型且 id 不丢`() = runTest {
        val (repository, dataSource) = repository()
        dataSource.nextGetCommentsPages.addLast(
            fullPage(size = 2, idStart = 401L)
        )

        val page = repository.getComments(listOf(KEY), page = 1, pageSize = 2).getOrThrow()

        assertEquals(listOf(401L, 402L), page.items.map { it.id })
        assertEquals(KEY, page.items.first().commentKey)
    }

    // ===== 失败翻译 =====

    @Test
    fun `非成功业务码翻译为失败`() = runTest {
        val (repository, dataSource) = repository()
        dataSource.nextGetCommentsPages.addLast(RespDTO(code = "A0101", error = "参数非法"))

        val result = repository.getComments(listOf(KEY), page = 1, pageSize = 100)

        assertTrue(result.isFailure)
        assertEquals("参数非法", result.exceptionOrNull()?.message)
    }

    @Test
    fun `数据源抛异常翻译为失败而不炸出`() = runTest {
        val (repository, dataSource) = repository()
        dataSource.getCommentsError = IOException("网络不可达")

        val result = repository.getComments(listOf(KEY), page = 1, pageSize = 100)

        assertTrue(result.isFailure)
    }

    @Test
    fun `data 为 null 的成功响应兜底为空页`() = runTest {
        val (repository, dataSource) = repository()
        dataSource.nextGetCommentsPages.addLast(RespDTO(code = "00000", error = ""))

        val page = repository.getComments(listOf(KEY), page = 1, pageSize = 100).getOrThrow()

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    // ===== 假件 =====

    private fun repository(): Pair<CommentRepository, FakeCommentDataSource> {
        val dataSource = FakeCommentDataSource()
        val adapter = CoroutineAdapter(
            tokenRefresher = object : TokenRefresher {
                // 成功路径不触达刷新器；真触发即为测试数据问题，返回 null 走会话过期路径足够醒目
                override suspend fun refresh(expiredAccessToken: String?): String? = null
            },
            sessionEventBus = SessionEventBus(),
            tokenHolder = TokenHolder()
        )
        return CommentRepository(dataSource, adapter) to dataSource
    }

    /** id 从 [idStart] 连续编号的整页评论；键统一 [KEY]（条目字段映射的锚点） */
    private fun fullPage(size: Int, idStart: Long = 1L): RespDTO<CommentPage> = RespDTO(
        code = "00000",
        error = "",
        data = CommentPage(
            items = List(size) { index ->
                Comment(
                    id = idStart + index,
                    user = User(id = 9L, username = "user$index", image = ""),
                    commentKey = KEY,
                    content = "评论 $index",
                    addTime = "2026-01-01 10:00:00"
                )
            },
            total = size.toLong(),
            page = 1,
            pageSize = size
        )
    )

    private class FakeCommentDataSource : CommentDataSource {

        /** getComments 的应答队列：测试按序入队，调用按序消费 */
        val nextGetCommentsPages = ArrayDeque<RespDTO<CommentPage>>()

        val requestedPageAndSizes = mutableListOf<Pair<Int, Int>>()

        var getCommentsCalls = 0

        var getCommentsError: Exception? = null

        override suspend fun addComment(comment: Comment): RespDTO<Comment> =
            RespDTO(code = "00000", error = "")

        override suspend fun deleteComment(id: Long): RespDTO<Unit> =
            RespDTO(code = "00000", error = "")

        override suspend fun getMyComments(page: Int, pageSize: Int): RespDTO<CommentPage> =
            RespDTO(code = "00000", error = "")

        override suspend fun getComments(
            commentKeys: List<String>,
            page: Int,
            pageSize: Int
        ): RespDTO<CommentPage> {
            getCommentsCalls++
            requestedPageAndSizes += page to pageSize
            getCommentsError?.let { throw it }
            return nextGetCommentsPages.removeFirst()
        }

        override suspend fun migrateMyComments(
            oldKey: String,
            newKey: String
        ): RespDTO<CommentMigrateResponse> =
            RespDTO(code = "00000", error = "")
    }

    private companion object {
        const val KEY = "ck1:test#0"
    }
}
