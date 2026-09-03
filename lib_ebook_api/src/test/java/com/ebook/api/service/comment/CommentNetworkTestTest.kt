package com.ebook.api.service.comment

import com.ebook.api.entity.Comment
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.User
import com.ebook.api.utils.TestAssetManager
import com.xrn1997.common.dto.RespDTO
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CommentNetworkTest]（mock 评论数据源）的资产契约测试，纯 JVM。
 *
 * 存在意义：mock 的解码类型与 JSON 资产形态曾出现过一次分叉——资产按 ADR-0013 改成
 * `CommentPage` 分页包裹，mock 却仍按 `List<Comment>` 解码，`SerializationException`
 * 被 CoroutineAdapter 吞成「未知错误」，导致 mock 构建下评论页永远加载失败却无人察觉。
 * 本测试用真机 mock 读的同一批资产文件跑通生产代码路径，把「资产形态 ↔ 解码类型」
 * 「两份资产 chapter_url 交叉对齐」「服务端身份回显」这三条契约钉死。
 *
 * 命名遵循 `<Subject>Test`（Subject 即 [CommentNetworkTest]），故类名叠了 Test 后缀。
 */
class CommentNetworkTestTest {

    /**
     * 构造被测 mock：用文件系统替掉 Android assets。
     *
     * AGP 单元测试的工作目录是模块目录（`lib_ebook_api/`），故 `src/main/assets/…`
     * 相对路径命中的就是 APK 里那份资产——测的是真实数据，不是复刻的样本。
     */
    private fun dataSource(): CommentNetworkTest = CommentNetworkTest(
        networkJson = assetJson,
        assets = TestAssetManager { fileName ->
            val file = File(ASSET_DIR, fileName)
            if (!file.isFile) {
                // 工作目录假设一旦被改动，宁可炸在明确的绝对路径上，也不静默跳过契约校验
                throw IllegalStateException("mock 资产未找到：${file.absolutePath}")
            }
            file.inputStream()
        }
    )

    /** 按客户端（BookCommentsViewModel）提交的形状构造一条待发表评论：id/作者/时间均为占位值。 */
    private fun clientComment(chapterUrl: String?, content: String) = Comment().apply {
        id = 0L
        user = User(id = 1L, username = "", image = "")
        this.chapterUrl = chapterUrl
        chapterName = "第一章 序幕"
        bookName = "天启之书"
        this.content = content
        addTime = ""
    }

    // ===== 契约一：资产形态即解码类型（分页包裹） =====

    @Test
    fun `getMyComments 按 CommentPage 包裹解码，读到全部种子`() = runTest {
        val resp = dataSource().getMyComments(page = 1, pageSize = 100)

        val page = requireNotNull(resp.data) { "data 为空说明解码未取到分页包裹" }
        assertEquals("资产 user_comments.json 应有 5 条种子", 5, page.items.size)
        assertEquals(setOf(201L, 202L, 203L, 204L, 205L), page.items.map { it.id }.toSet())
        // 章节字段是「我的评论」页的书名/章节 chip 数据源，解码不能丢
        assertTrue(page.items.all { it.bookName?.isNotEmpty() == true })
        assertTrue(page.items.all { it.chapterName?.isNotEmpty() == true })
    }

    @Test
    fun `getChapterComments 按 CommentPage 包裹解码`() = runTest {
        val resp = dataSource().getChapterComments(
            chapterUrl = "https://example.com/books/1/chapter/5",
            bookName = null,
            page = 1,
            pageSize = 100
        )

        assertEquals(3, requireNotNull(resp.data).items.size)
    }

    // ===== 契约二：两份资产的 chapter_url 交叉对齐（点得通） =====

    @Test
    fun `我的评论里每个章节都能进到有内容的评论区`() = runTest {
        val source = dataSource()
        val myChapterUrls = source.getMyComments(page = 1, pageSize = 100)
            .let { requireNotNull(it.data).items }
            .mapNotNull { it.chapterUrl }
            .distinct()

        assertTrue("我的评论资产应至少覆盖多个章节", myChapterUrls.size >= 2)
        myChapterUrls.forEach { url ->
            val visible = source.getChapterComments(url, null, 1, 100)
                .let { requireNotNull(it.data).items }
            assertTrue(
                "chapter_url=$url 在章节评论资产里没有任何对应条目：从「我的评论」点进去会是空列表，" +
                    "两份资产的 chapter_url 必须保持交叉对齐",
                visible.isNotEmpty()
            )
        }
    }

    @Test
    fun `章节评论严格过滤，未知章节不串数据`() = runTest {
        val items = dataSource()
            .getChapterComments("https://example.com/books/9/chapter/9", null, 1, 100)
            .let { requireNotNull(it.data).items }

        assertTrue(items.isEmpty())
    }

    // ===== 契约三：新增评论按服务端方式回显（id/作者/时间） =====

    @Test
    fun `连续发表两条评论由 mock 分配不同 id，不撞列表 key`() = runTest {
        val source = dataSource()

        val first = source.addComment(clientComment("https://example.com/books/1/chapter/1", "第一条")).data
        val second = source.addComment(clientComment("https://example.com/books/1/chapter/1", "第二条")).data

        // 客户端占位 id 恒为 0，原样回显会让评论列表 items(key = { it.id }) 撞 key 抛异常
        assertNotEquals(0L, requireNotNull(first).id)
        assertNotEquals(first.id, second?.id)
        assertTrue("mock 分配的 id 应高于种子 id 段", requireNotNull(first).id >= 9001L)
    }

    @Test
    fun `发表的评论被赋服务端身份与时间，本人判定按 userId 命中`() = runTest {
        val posted = requireNotNull(
            dataSource().addComment(clientComment(CHAPTER_ONE, "我的评论")).data
        )
        val me = loginAssetUser()

        // 展示名如实回显昵称：门禁已按 userId 判定，不再需要靠留空昵称绕开失配
        assertEquals(me.nickname, posted.user.nickname)
        assertEquals(me.username, posted.user.username)
        // 作者 uid 与登录资产一致，才是「仅本人评论可长按删除」成立的前提
        assertEquals(me.id, posted.user.id)
        // 时间为服务端契约格式，否则「我的评论」排序与显示一起失效
        assertTrue("add_time 应为 yyyy-MM-dd HH:mm:ss，实际=${posted.addTime}", posted.addTime.matches(TIME_REGEX))
    }

    @Test
    fun `评论资产的作者名与登录资产同源`() = runTest {
        val source = dataSource()
        val expected = listOf(loginAssetUsername())

        val myAuthors = source.getMyComments(page = 1, pageSize = 100)
            .let { requireNotNull(it.data).items }.map { it.user.username }.distinct()
        val ownChapterAuthors = source.getChapterComments(CHAPTER_ONE, null, page = 1, pageSize = 100)
            .let { requireNotNull(it.data).items }.filter { it.user.id == 1L }.map { it.user.username }.distinct()

        // 资产里的作者名一旦与登录资产漂移，本人在评论区/章节区就再也删不掉种子评论
        assertEquals(myAuthors, expected)
        assertEquals(ownChapterAuthors, expected)
    }

    @Test
    fun `发表的评论同时出现在我的评论与该章节评论区`() = runTest {
        val source = dataSource()
        val url = "https://example.com/books/1/chapter/1"

        source.addComment(clientComment(url, "发在第一章的评论"))

        val mine = source.getMyComments(page = 1, pageSize = 100).let { requireNotNull(it.data).items }
        val inChapter = source.getChapterComments(url, null, 1, 100).let { requireNotNull(it.data).items }
        assertTrue(mine.any { it.content == "发在第一章的评论" })
        assertTrue(inChapter.any { it.content == "发在第一章的评论" })
    }

    // ===== 契约四：变更操作不丢种子（懒加载时序无关） =====

    @Test
    fun `先发表评论再看我的评论，种子不会丢失`() = runTest {
        val source = dataSource()

        // 全新实例上先写后读：内存在态若被固定成「只含这一条」，后续读取会跳过资产加载
        source.addComment(clientComment("https://example.com/books/1/chapter/1", "第一条"))

        val page = requireNotNull(source.getMyComments(page = 1, pageSize = 100).data)
        assertEquals("5 条种子 + 1 条新发表", 6, page.items.size)
        assertEquals(6L, page.total)
    }

    @Test
    fun `未预加载时删除种子评论，之后读取不再返回它`() = runTest {
        val source = dataSource()

        // 首句就删除：若删除不先加载种子，filterNot 会落空，之后再加载又把已删条目带回列表
        source.deleteComment(201L)

        val items = requireNotNull(source.getMyComments(page = 1, pageSize = 100).data).items
        assertEquals(4, items.size)
        assertTrue(items.none { it.id == 201L })
    }

    // ===== 分页切页 =====

    @Test
    fun `page 与 pageSize 在本地重切生效`() = runTest {
        val page = requireNotNull(dataSource().getMyComments(page = 2, pageSize = 2).data)

        assertEquals(listOf(203L, 204L), page.items.map { it.id })
        assertEquals(5L, page.total)
        assertEquals(2, page.page)
        assertEquals(2, page.pageSize)
    }

    /** 登录资产里的 mock 身份：uid/用户名/昵称的唯一事实源，评论回显必须与它一致 */
    private fun loginAssetUser(): User =
        assetJson
            .decodeFromString<RespDTO<LoginDTO>>(File(ASSET_DIR, "user_login.json").readText())
            .data?.user
            ?: error("user_login.json 应携带 mock 用户身份")

    private fun loginAssetUsername(): String = loginAssetUser().username

    private companion object {
        /** 资产解码统一用这一份配置：重复新建同配置 Json 会触发警告且无谓 */
        val assetJson = Json { ignoreUnknownKeys = true }

        /** 相对模块目录的资产路径（见 [dataSource] 的工作目录说明） */
        const val ASSET_DIR = "src/main/assets"

        /** 两份资产交叉对齐的示例章节（既有他人评论，也有本人评论 403） */
        const val CHAPTER_ONE = "https://example.com/books/1/chapter/1"

        /** 服务端 add_time 契约格式 */
        val TIME_REGEX = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    }
}
