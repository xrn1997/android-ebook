# 客户端评论与用户资料接口对齐服务端 RESTful 契约

客户端评论/用户资料接口整体对齐服务端 RESTful 契约：评论收口到 `/api/comments` 系端点，聚合键从书源章节 URL 换成客户端派生的不透明 `comment_key`，查询与「我的评论」都走分页包裹；头像从 multipart 直传改为「上传拿 URL → 更新资料」两步；昵称/头像独立端点合并进 `PUT /api/users/me` 的部分更新。

## 动机

- 客户端旧契约与后端不一致：`/comments/save` 等路径后端不存在；`Comment` 内容字段（`comment` vs 后端 `content`）、用户内嵌对象键（`id/image` vs 后端 `uid/avatar`）均脱节，mock 链路与实体 `@SerialName` 也已互相矛盾。
- 以 `chapter_url` 聚合评论本就不成立：同一作品在不同书源下章节 URL 不同、本地书的 URL 只有本机认得，同一部书的评论被 URL 切碎。改由客户端派生不透明 token 作聚合键，服务端不校验、不解释、不建书籍表，只按其过滤与改键。

## 决策

1. **端点集合**：`POST /api/comments`（`content` 与 `comment_key` 必填，章节快照字段可选）、`DELETE /api/comments/{id}`、`GET /api/comments/my?page&page_size`（身份取自 token，取代按 username 查询）、`GET /api/comments?comment_keys&page&page_size`（`comment_keys` 是逗号分隔的键列表，服务端返回其并集）、`POST /api/comments/migrate`（`old_key`/`new_key`，服务端按当前登录用户过滤、只改本人的行，返回迁移条数）。
2. **分页包裹**：列表响应统一为 `CommentPage{items,total,page,page_size}`，「是否还有下一页」由仓库层按「本页返回条数达到本次请求页大小」判定。章评论区页大小取 20（评论条目轻、首屏快，自动翻页下大页没有收益），翻页由调用方推进（首页 `page = 1`，上页结果合并完成后取 `page + 1`）；「我的评论」当前一次性展示、未接分页，故用 100 的大页——页大小调小会重新引入「超出即静默截断」的悬崖，该页接入分页时并入章评页大小。仓库层的 `page` 刻意不设默认值：分页接口上省略页码最容易写成「每页都取首页」的静默重复加载，宁可编译不过。
3. **空键列表在仓库层短路**：直接返回空页、不发请求。契约规定 `comment_keys` 缺失时服务端返回**全局最新列表**，而网络层把空列表翻译成 `comment_keys=null`，正好命中该分支——不收口，评论区会把全站最新评论当成该章节的评论。旧数据的 `commentKey` 可为 null，这条路径真实可达；收口放仓库层而非各调用方，是因为隐患出在网络层的空值翻译上，任何新调用方传空列表都会踩同一个坑。
4. **键由客户端算、两端都不解释**：读侧传键列表做并集（跨源合并后一次查询多个键），写侧只传主键单键，且主键必须由入口显式给出——并集查询没有顺序保证，取「列表第一个」会在修键后拿到旧键。章评键是作品键加 `#章序号` 后缀，不带 `#` 的是作品级键。客户端对第三方书源派生出的键原样透传，不替服务端做格式约束。历史 `chapter_url` 只保留兼容位，不再作为查询条件。
5. **字段命名**：`Comment` 的 `comment` → `content`；`comment_key/chapter_name/book_name/add_time` 走 `@SerialName` 边界翻译；`user` 复用 `User` 实体解析（`uid/avatar` 已对齐，`email` 走默认值兜底，服务端评论视图不返回）——消除 mock 链路与实体注解的互相矛盾。
6. **头像两步**：`POST /api/uploads/avatar`（multipart 字段名 `avatar`）拿 URL → `PUT /api/users/me` 提交 `avatar=url`；`ModifyRepository` 内部实现两步，ViewModel 接口不变——两步编排收口在 Repository 层，对上层透明。昵称同样走 `PUT /api/users/me`（服务端按「非空即更新」处理），历史的昵称/头像独立端点及其请求 DTO 已下线。
7. **删除评论判成功**：后端删除成功 `data=null`，以业务码 `00000` 为成功判据，不再依赖 `data`——后端删除响应不携带 data，只能用业务码判断。

## 权衡

- **User 实体复用而非新建 CommentUserView**：后端评论内嵌用户仅四字段，客户端复用 `User` 少一个 DTO 和一组 mapper；代价是 `email` 字段走默认值（服务端评论视图不返回，客户端 `User` 已无 password 字段，无泄漏面）。
- **迁移只做「只动本人行」的那一种**：改书名/作者后自己的旧评论跟着搬到新键，同桶内他人评论不移动。全局改键是服务端的管理员能力，客户端不越权代做。
- **评论的本人判定只认 `userId`**：展示名（昵称）可重复且仅用于展示，长按删除门禁拿展示名与登录名比对，对设过昵称的用户必然失配；`userId` 与会话同源，未登录时取 0 并由「必须为正」的闸门挡掉假阳性。

## 下游影响

- `lib_ebook_api`：`CommentService`/`CommentDataSource`/`CommentNetwork`/`UserService` 按本契约实现；实体侧新增 `CommentPage`/`UploadResponse`/`UpdateUserRequest`/`CommentMigrateRequest`/`CommentMigrateResponse`。
- **mock 资产形态即服务端契约**：两份评论资产（`user_comments.json`/`chapter_comments.json`）都按 `RespDTO<CommentPage>` 分页包裹出货，mock 的解码类型因此必须是 `CommentPage`。写成 `List<Comment>` 会让 kotlinx 在对象位置期望列表而抛 `SerializationException`，该异常被 `CoroutineAdapter` 吞成「未知错误」，表现为 mock 构建下评论页永远加载失败却不闪退。契约由 `CommentNetworkTestTest`（纯 JVM 资产契约测试）钉住；mock 的新增评论按服务端方式回显 id/作者/时间（客户端占位 id 原样回显会让评论列表 `key = { it.id }` 撞 key 抛异常）；两份资产的 `comment_key` 交叉对齐编排，从「我的评论」点任意一条都要能进到非空的评论区，改资产时须保持该对齐。
- `lib_book_common`：`CommentRepository` 承担分页短路、`hasMore` 判定、迁移条数返回与删除判业务码；`Mappers.kt` 做键与字段的边界翻译。
- `module_book`：章评论区按页推进——触底信号可能在滚动中连续到来，加载更多有在途闸门；刷新与加载更多存在并发窗口，合并按 `id` 去重。
- `module_me`：`ModifyRepository` 昵称走 `updateMe`、头像两步上传；ViewModel/UI 层零改动。
