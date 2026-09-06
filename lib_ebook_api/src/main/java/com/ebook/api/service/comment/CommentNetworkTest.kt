package com.ebook.api.service.comment

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.entity.CommentMigrateResponse
import com.ebook.api.entity.CommentPage
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.User
import com.ebook.api.utils.TestAssetManager
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 评论测试数据源：模拟服务端状态（内存态），对齐 M2 评论聚合键契约。
 *
 * 与真实后端一致：删除/添加会真正修改数据，getMyComments 返回变更后的列表；
 * 分页按 CommentPage 包裹结构返回（items/total/page/page_size）。
 *
 * mock 语义边界（改动前先读，都是踩过坑的地方）：
 * - **资产形态即服务端契约**：两份评论资产都按 `RespDTO<CommentPage>` 分页包裹出货
 *   （见 ADR-0013），解码类型因此必须是 [CommentPage]。写成 `List<Comment>` 会让 kotlinx
 *   在对象位置期望列表而抛 SerializationException——异常被 CoroutineAdapter 吞成
 *   「未知错误」，表现为 mock 构建下评论页永远加载失败（不闪退，故极易漏诊）。
 * - **种子只读一次**：首次访问时取 `data.items` 摊平成内存列表缓存，之后按调用方传入的
 *   page/pageSize 本地重切；资产里的 total/page 属于服务端那一页的元数据，不参与缓存。
 * - **聚合键过滤（M2）**：[getComments] 按 `commentKey` 列表做并集查询（与后端同语义）。
 *   两份资产的 `comment_key` 是**交叉对齐**编排的：从「我的评论」点任意一条进评论区，
 *   都能看到该章节的对话串（含一条本人可长按删除的）。改资产时须保持这份对齐关系，
 *   否则 mock 评论区会空。
 * - **新增评论按服务端方式回显**：id/作者/时间由 mock 赋值，不信任客户端占位值
 *   （见 [stampAsServer]）。
 * - **作者名以 `user_login.json` 为事实源**：评论里的 username 必须等于登录资产返回的
 *   username，因为登录时该值被写入 SP，而章节评论区的「仅本人可长按删除」门禁拿它作比对；
 *   同时本人评论的 nickname 刻意缺省（展示名回落 username），填了昵称会让门禁失配。
 */
@Singleton
class CommentNetworkTest @Inject constructor(
    private val networkJson: Json,
    private val assets: TestAssetManager,
) : CommentDataSource {

    /** 我的评论内存态：首次访问时从 JSON 加载，之后变更操作直接基于该列表演化。 */
    private var userComments: List<Comment>? = null

    /** 章节评论内存态：首次访问时从 JSON 加载（mock 不做跨章节写入）。 */
    private var chapterComments: List<Comment>? = null

    /** mock 登录身份：取自 user_login.json（与 UserNetworkTest 同源），首次发评论时解析并缓存。 */
    private var mockUser: User? = null

    /** 自增评论 id：起点高于全部种子 id，保证发表多条评论不会与种子撞列表 key。 */
    private var nextCommentId = MOCK_ID_BASE

    /** 服务端时间契约的格式化器（[SERVER_TIME_PATTERN]）；DateTimeFormatter 本身线程安全。 */
    private val serverTimeFormatter = DateTimeFormatter.ofPattern(SERVER_TIME_PATTERN)

    override suspend fun addComment(comment: Comment): RespDTO<Comment> {
        // 种子必须先就位：否则内存态被固定成「只含这一条」，后续访问跳过资产加载，种子永久消失
        ensureUserComments()
        if (comment.commentKey != null) ensureChapterComments()
        val posted = stampAsServer(comment)
        synchronized(this) {
            userComments = userComments.orEmpty() + posted
            // 评论池同步追加，保证「发表后能查到自己刚发的」语义一致
            if (posted.commentKey != null) {
                chapterComments = chapterComments.orEmpty() + posted
            }
        }
        return RespDTO(code = "00000", error = "", data = posted)
    }

    override suspend fun deleteComment(id: Long): RespDTO<Unit> {
        // 删除同样先 ensure：未加载时对 null 列表做 filterNot 等于什么都没删，
        // 之后再加载会把「已删」的条目重新带回列表（服务端删除是持久的）
        ensureUserComments()
        ensureChapterComments()
        synchronized(this) {
            userComments = userComments?.filterNot { it.id == id }
            chapterComments = chapterComments?.filterNot { it.id == id }
        }
        return RespDTO(code = "00000", error = "")
    }

    override suspend fun getMyComments(page: Int, pageSize: Int): RespDTO<CommentPage> =
        pageOf(ensureUserComments(), page, pageSize)

    override suspend fun getComments(
        commentKeys: List<String>,
        page: Int,
        pageSize: Int
    ): RespDTO<CommentPage> {
        // M2：按聚合键列表做并集查询，从两份数据源合并后去重（与后端同语义）
        val keySet = commentKeys.toSet()
        val merged = (ensureUserComments() + ensureChapterComments())
            .filter { it.commentKey in keySet }
            .distinctBy { it.id }
        return pageOf(merged, page, pageSize)
    }

    override suspend fun migrateMyComments(
        oldKey: String,
        newKey: String
    ): RespDTO<CommentMigrateResponse> {
        // 仅迁移当前用户的评论（userComments），按 commentKey 匹配并批量替换
        ensureUserComments()
        var count = 0
        synchronized(this) {
            userComments = userComments?.map { comment ->
                if (comment.commentKey == oldKey) {
                    count++
                    comment.copy(commentKey = newKey)
                } else {
                    comment
                }
            }
        }
        return RespDTO(code = "00000", error = "", data = CommentMigrateResponse(migratedCount = count))
    }

    /** 按 page/page_size 切页，返回后端同构的分页包裹。 */
    private fun pageOf(list: List<Comment>, page: Int, pageSize: Int): RespDTO<CommentPage> {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceAtLeast(1)
        val start = (safePage - 1) * safeSize
        return RespDTO(
            code = "00000",
            error = "",
            data = CommentPage(
                items = list.drop(start).take(safeSize),
                total = list.size.toLong(),
                page = safePage,
                pageSize = safeSize
            )
        )
    }

    /**
     * 我的评论种子懒加载（幂等）：首次读资产后不再重读。
     *
     * 并发首查可能重复读资产，但种子是只读幂等数据，重复赋值无副作用；
     * 写入统一收在 [synchronized] 内，保证跨线程可见（本类是 @Singleton，多协程共享内存态）。
     */
    private suspend fun ensureUserComments(): List<Comment> {
        if (synchronized(this) { userComments } == null) {
            val loaded = getDataFromJsonFile<CommentPage>(USER_COMMENTS).data?.items.orEmpty()
            synchronized(this) { userComments = userComments ?: loaded }
        }
        return synchronized(this) { userComments }.orEmpty()
    }

    /** 章节评论种子懒加载，语义同 [ensureUserComments]。 */
    private suspend fun ensureChapterComments(): List<Comment> {
        if (synchronized(this) { chapterComments } == null) {
            val loaded = getDataFromJsonFile<CommentPage>(CHAPTER_COMMENTS).data?.items.orEmpty()
            synchronized(this) { chapterComments = chapterComments ?: loaded }
        }
        return synchronized(this) { chapterComments }.orEmpty()
    }

    /**
     * 以服务端身份重建评论：真实后端忽略客户端提交的 id/作者/时间，用 token 身份与
     * 服务器时间赋值后回显（见 ADR-0013）。mock 若原样回显客户端对象会有三个后果：
     * - id 恒为客户端占位的 0 → 连发两条在评论列表 `key = { it.id }` 上撞 key，LazyColumn 直接抛异常；
     * - 作者沿用客户端空字段 → 「仅本人评论可长按删除」的门禁（按 userId 判定）永不通过；
     * - add_time 为空 → 时间显示与按时间倒序排序一起失效。
     */
    private suspend fun stampAsServer(comment: Comment): Comment {
        val me = mockCurrentUser()
        val id = synchronized(this) { nextCommentId++ }
        return comment.copy(
            id = id,
            // 昵称如实带上：展示名走「昵称优先」，而本人判定已按 userId 比对，
            // 不再需要靠留空昵称来绕开门禁失配
            user = User(id = me.id, username = me.username, image = me.image, nickname = me.nickname),
            addTime = ZonedDateTime.now(SERVER_ZONE).format(serverTimeFormatter)
        )
    }

    /** mock 当前登录用户：复用 user_login.json（与 UserNetworkTest 同一事实源），避免两份 mock 身份漂移。 */
    private suspend fun mockCurrentUser(): User {
        if (synchronized(this) { mockUser } == null) {
            val user = getDataFromJsonFile<LoginDTO>(USER_LOGIN).data?.user
                ?: User(id = MOCK_USER_ID, username = MOCK_USERNAME)
            synchronized(this) { mockUser = mockUser ?: user }
        }
        return requireNotNull(synchronized(this) { mockUser })
    }

    /**
     * Get data from the given JSON [fileName].
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> getDataFromJsonFile(fileName: String): RespDTO<T> =
        withContext(Dispatchers.IO) {
            assets.open(fileName).use { inputStream ->
                networkJson.decodeFromStream(inputStream)
            }
        }

    companion object {
        private const val USER_COMMENTS = "user_comments.json"
        private const val CHAPTER_COMMENTS = "chapter_comments.json"
        private const val USER_LOGIN = "user_login.json"

        /** mock 分配 id 的起点：高于两份评论资产里的全部种子 id，避免撞列表 key */
        private const val MOCK_ID_BASE = 9001L

        /** 兜底身份：资产解析失败时使用，需与 user_login.json 的 uid/username 保持一致 */
        private const val MOCK_USER_ID = 1L
        private const val MOCK_USERNAME = "user_test01"

        /** 服务端时间契约：add_time 为 Asia/Shanghai 的 yyyy-MM-dd HH:mm:ss（见 ADR-0013） */
        private const val SERVER_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
        private val SERVER_ZONE = ZoneId.of("Asia/Shanghai")
    }
}
