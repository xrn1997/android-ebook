# M2 评论标识接口协议

> 本文档定义 M2（来源分组与评论）的服务端契约与客户端 API 接口。
> 服务端（ebook-server）与客户端（android-ebook）各自按此文档独立实现，不互相引用 ADR 编号。

## 1. 背景

当前评论按 `chapter_url`（第三方书源章节 URL）聚合，问题：
- 不同用户用不同书源，`chapter_url` 不同，评论无法聚合
- 本地书的 `chapter_url` 是 `md5_章号`，只有本机认得
- 书籍级评论靠明文 `BookName` 文本列聚合，无索引、实际已失效

M2 将聚合键换成客户端派生的不透明 token `comment_key`，服务端不校验、不解释、不建书籍表，只按其过滤与改键。

## 2. 键的格式

```
comment_key = "ck1:" + sha256( len(归一化(书名)) ":" 归一化(书名) len(归一化(作者)) ":" 归一化(作者) )
章评键      = comment_key + "#" + 章序号(0-based)
```

- `ck1:` 是算法版本前缀，归一化规则变更时前缀递增
- 键的拼接采用**长度前缀**格式（如 `5:hello6:world`），避免分隔符冲突（如「AB」+「C」与「A」+「BC」撞键）
- 章评键是 `comment_key` 加上 `#章序号` 后缀，服务端视为不透明字符串
- 书籍级评论的 `comment_key` 不含 `#` 后缀
- 归一化规则：去书名号、折叠空白、全角转半角、转小写；作者占位词（佚名/侠名/未知等）归一为空串

## 3. 服务端改动

### 3.1 数据库

评论表新增列：

```sql
ALTER TABLE comments ADD COLUMN comment_key TEXT;
CREATE INDEX idx_comments_comment_key ON comments(comment_key);
```

- `comment_key` 为不透明 token，服务端不校验其格式
- 旧 `chapter_url` 列保留但不再作为聚合键（过渡期兼容旧客户端）
- 旧 `book_name` 聚合列废弃（书籍级评论改按 `comment_key` 前缀匹配）

### 3.2 端点变更

#### 3.2.1 查询评论（改造）

```
GET /api/comments
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `comment_keys` | string | 否 | 逗号分隔的 `comment_key` 列表，返回所有匹配键的评论并集。支持章评键（含 `#`）和作品键（不含 `#`） |
| `chapter_url` | string | 否 | **已废弃**，保留兼容旧客户端。新客户端不使用此参数 |
| `book_name` | string | 否 | **已废弃**，保留兼容旧客户端 |
| `page` | int | 否 | 页码，默认 1 |
| `page_size` | int | 否 | 每页条数，默认 10 |

**查询语义**：
- `comment_keys` 提供时：`WHERE comment_key IN (提供的键列表)`
- 仅 `chapter_url` 提供时（旧客户端）：`WHERE chapter_url = ?`（兼容旧行为）
- 都没提供时：返回全局最新列表

**响应**：`RespDTO<CommentPage>`，结构不变。

#### 3.2.2 创建评论（改造）

```
POST /api/comments
```

请求体 `Comment`：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `comment_key` | string | 是 | 评论聚合键。章评为 `ck1:hash#N`，书籍级为 `ck1:hash` |
| `chapter_name` | string | 否 | 章节名快照（展示用，不参与聚合） |
| `book_name` | string | 否 | 书名快照（展示用，不参与聚合） |
| `content` | string | 是 | 评论内容 |

**服务端行为**：
- 将 `comment_key` 存入评论行
- `chapter_name` / `book_name` 作为展示快照存储（不用于聚合）
- 旧字段 `chapter_url` 不再使用，新客户端不发送

**响应**：`RespDTO<Comment>`，返回创建的评论（含服务端填充的 `id`、`user`、`add_time`）。

#### 3.2.3 删除评论（不变）

```
DELETE /api/comments/{id}
```

无变更。

#### 3.2.4 我的评论（不变）

```
GET /api/comments/my
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `page` | int | 否 | 页码 |
| `page_size` | int | 否 | 每页条数 |

**响应**：`RespDTO<CommentPage>`，每条评论包含 `comment_key` 字段。

#### 3.2.5 迁移我的评论（新增）

```
POST /api/comments/migrate
```

请求体：

```json
{
  "old_key": "ck1:abc123...",
  "new_key": "ck1:def456..."
}
```

**服务端行为**：
```sql
UPDATE comments SET comment_key = :new_key
WHERE user_id = :current_user AND comment_key = :old_key
```

- 仅修改当前登录用户自己的评论
- 不需要管理员权限
- 返回迁移条数

**响应**：

```json
{
  "code": "00000",
  "data": {
    "migrated_count": 5
  }
}
```

#### 3.2.6 管理员改键（新增，可选）

```
POST /api/comments/rehash
```

请求体：

```json
{
  "old_key": "ck1:abc123...",
  "new_key": "ck1:def456..."
}
```

**服务端行为**：
```sql
UPDATE comments SET comment_key = :new_key WHERE comment_key = :old_key
```

- 需要管理员权限
- 用于全局性桶污染修复（错键上已有大量他人评论）

**响应**：

```json
{
  "code": "00000",
  "data": {
    "migrated_count": 42
  }
}
```

### 3.3 响应 DTO 变更

`Comment` 响应新增字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `comment_key` | string? | 评论聚合键。新评论必有此字段；旧数据迁移前可能为 null |

旧字段 `chapter_url` 保留返回（兼容旧客户端展示），新客户端优先使用 `comment_key`。

### 3.4 迁移策略

1. **Phase 1**：服务端加 `comment_key` 列 + 新端点，旧端点保留。新客户端使用 `comment_keys` 查询，旧客户端继续使用 `chapter_url`
2. **Phase 2**：旧客户端淘汰后，废弃 `chapter_url` 查询参数和 `chapter_url` 响应字段
3. **Phase 3**：管理员 `rehash` 将旧 `chapter_url` 时代的评论批量迁移到 `comment_key`（可选，视数据量决定）

## 4. 客户端 API 接口定义

### 4.1 DTO 变更

#### Comment（API 实体）

```kotlin
@Serializable
@Parcelize
data class Comment(
    var id: Long = 0L,
    @JvmField
    var user: User = User(),
    @SerialName("comment_key")
    var commentKey: String? = null,    // 新增：评论聚合键
    @SerialName("chapter_url")
    var chapterUrl: String? = null,    // 废弃：保留兼容
    @SerialName("chapter_name")
    var chapterName: String? = null,
    @SerialName("book_name")
    var bookName: String? = null,
    @SerialName("content")
    var content: String? = null,
    @SerialName("add_time")
    var addTime: String = ""
) : Parcelable
```

#### CommentMigrateResponse（新增）

```kotlin
@Serializable
data class CommentMigrateResponse(
    @SerialName("migrated_count")
    val migratedCount: Int
)
```

#### BookComment（领域模型）

```kotlin
data class BookComment(
    val id: Long,
    val userId: Long,
    val username: String,
    val avatar: String,
    val commentKey: String?,     // 新增：评论聚合键
    val chapterUrl: String?,     // 废弃：保留兼容
    val chapterName: String?,
    val bookName: String?,
    val content: String?,
    val addTime: String
)
```

### 4.2 CommentService（Retrofit 接口）

```kotlin
interface CommentService {
    // 创建评论（comment_key 必填）
    @POST("/api/comments")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun addComment(@Body comment: Comment): RespDTO<Comment>

    // 删除评论（不变）
    @DELETE("/api/comments/{id}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun deleteComment(@Path("id") id: Long): RespDTO<Unit>

    // 我的评论列表（不变，但响应含 comment_key）
    @GET("/api/comments/my")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun getMyComments(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): RespDTO<CommentPage>

    // 查询评论（comment_keys 数组过滤，替代 chapter_url）
    @GET("/api/comments")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun getComments(
        @Query("comment_keys") commentKeys: String?,  // 逗号分隔的键列表
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): RespDTO<CommentPage>

    // 迁移我的评论（新增）
    @POST("/api/comments/migrate")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun migrateMyComments(
        @Body request: CommentMigrateRequest
    ): RespDTO<CommentMigrateResponse>
}
```

#### CommentMigrateRequest（新增）

```kotlin
@Serializable
data class CommentMigrateRequest(
    @SerialName("old_key")
    val oldKey: String,
    @SerialName("new_key")
    val newKey: String
)
```

### 4.3 CommentDataSource（接口）

```kotlin
interface CommentDataSource {
    suspend fun addComment(comment: Comment): RespDTO<Comment>
    suspend fun deleteComment(id: Long): RespDTO<Unit>
    suspend fun getMyComments(page: Int, pageSize: Int): RespDTO<CommentPage>

    // 改造：多键并集查询（替代 getChapterComments）
    suspend fun getComments(
        commentKeys: List<String>,
        page: Int,
        pageSize: Int
    ): RespDTO<CommentPage>

    // 新增：迁移我的评论
    suspend fun migrateMyComments(oldKey: String, newKey: String): RespDTO<CommentMigrateResponse>
}
```

### 4.4 CommentRepository

```kotlin
@Singleton
class CommentRepository @Inject constructor(
    private val dataSource: CommentDataSource,
    private val coroutineAdapter: CoroutineAdapter
) : BaseModel() {

    // 查询章节评论：传入所有关联键的章评键列表（并集读取）
    suspend fun getChapterComments(commentKeys: List<String>): Result<List<BookComment>>

    // 查询书籍级评论：传入所有关联键的作品键列表
    suspend fun getBookComments(commentKeys: List<String>): Result<List<BookComment>>

    // 发评论：使用主键
    suspend fun addComment(comment: BookComment): Result<BookComment>

    // 删除评论（不变）
    suspend fun deleteComment(id: Long): Result<Unit>

    // 我的评论（不变）
    suspend fun getUserComments(): Result<List<BookComment>>

    // 新增：迁移我的旧评论到新键
    suspend fun migrateMyComments(oldKey: String, newKey: String): Result<Int>
}
```

## 5. 客户端使用模式

### 5.1 读评论（并集）

```kotlin
// 从 book_group 表取出该书目的所有关联 comment_key
val allKeys = bookGroupDao.getKeysForNoteUrl(noteUrl)  // List<String>

// 构造章评键列表（加 #章序号 后缀）
val chapterKeys = allKeys.map { "$it#$chapterIndex" }

// 并集查询
val comments = commentRepository.getChapterComments(chapterKeys)
```

### 5.2 写评论（主键）

```kotlin
// 取主键（is_primary = true 的那行）
val primaryKey = bookGroupDao.getPrimaryKey(noteUrl)  // String

// 构造章评键
val chapterKey = "$primaryKey#$chapterIndex"

// 发评论
commentRepository.addComment(BookComment(
    commentKey = chapterKey,
    content = "...",
    ...
))
```

### 5.3 改元数据后迁移评论

```kotlin
// 用户改了书名/作者 → 重算 comment_key
val newKey = CommentKey.derive(newMatchName, newMatchAuthor)

// 在 book_group 中添加新关联行（旧行保留）
bookGroupDao.upsert(BookGroupEntity(newKey, noteUrl, is_primary = true))
// 旧主键降级为非主键
bookGroupDao.demoteOldPrimary(noteUrl, exceptKey = newKey)

// 迁移自己的旧评论
val result = commentRepository.migrateMyComments(oldKey, newKey)
// UI 提示："你的 ${result} 条评论已随之迁移；该桶内他人评论不会移动"
```

## 6. 不变量

1. **服务端不校验 `comment_key` 格式**：键由客户端算，服务端只存不解释
2. **服务端不建书籍表**：不提供"列出所有书"接口，`comment_key` 是纯过滤条件
3. **迁移接口只动本人行**：`migrate` 端点按 `user_id` 过滤，不碰他人评论
4. **`ck1:` 前缀不可变**：归一化规则变更时前缀递增，新旧键空间不互通
5. **旧 `chapter_url` 过渡期保留**：服务端同时支持两种查询参数，直到旧客户端淘汰
