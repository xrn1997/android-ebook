# 客户端评论与用户资料接口对齐服务端新版契约（RESTful）

客户端评论/用户资料接口整体对齐服务端新版评论/资料契约：评论接口从历史路径
（`/comments/save`、`/comments/delete/{id}`、`/comments/query/*`）迁移到 RESTful 的
`/api/comments` 系端点，支持章节归属与分页；头像从 multipart 直传改为「上传拿 URL → 更新资料」
两步；昵称/头像独立端点合并进 `PUT /api/users/me` 部分更新。

## 动机

- 客户端旧契约与后端不一致：`/comments/save` 等路径后端不存在；`Comment` 内容字段
  （`comment` vs 后端 `content`）、用户内嵌对象键（`id/image` vs 后端 `uid/avatar`）均脱节，
  mock 链路与实体 `@SerialName` 也已互相矛盾。
- 服务端已定稿新契约（章节冗余快照 + 分页包裹 + 上传端点），客户端必须对齐才能联通。

## 决策

- **评论四接口对齐**：`POST /api/comments`（body 含可选章节字段）、`DELETE /api/comments/{id}`、
  `GET /api/comments/my`（token 身份，取代按 username 查询）、`GET /api/comments?chapter_url=`。
- **分页包裹**：列表返回 `CommentPage{items,total,page,page_size}`；客户端当前一次性取第一页
  全量（`DEFAULT_PAGE_SIZE=100`），`loadMore` 暂不启用。
- **字段命名**：`Comment` 的 `comment` → `content`；`chapter_url/chapter_name/book_name/add_time`
  走 `@SerialName` 边界翻译；`user` 复用 `User` 实体解析（`uid/avatar` 已对齐，`email`
  默认值兜底，服务端评论视图不返回）。
- **头像两步**：`POST /api/uploads/avatar`（multipart 字段名 `avatar`）拿 URL →
  `PUT /api/users/me` 提交 `avatar=url`；`ModifyRepository` 内部实现两步，ViewModel 接口不变。
- **删除评论判成功**：后端删除成功 `data=null`，以业务码 `00000` 为成功判据，不再依赖 `data`。

## 权衡

- **User 实体复用而非新建 CommentUserView**：后端评论内嵌用户仅四字段，客户端复用 `User`
  少一个 DTO 和一组 mapper；代价是 `email` 字段走默认值（服务端评论视图不返回，
  客户端 `User` 已无 password 字段，无泄漏面）。
- **章节查询不带 book_name**：后端按 `chapter_url` 聚合即可，`book_name` 冗余参数暂不传，
  减少客户端状态传递。
- **服务端刻意不校验 chapter_url 格式**（无书源数据），客户端对第三方书源 URL
  原样透传，两端一致。

## 下游影响

- `lib_ebook_api`：`CommentService`/`CommentDataSource`/`CommentNetwork`/`UserService` 等重写；
  废弃 `ModifyNicknameRequest`；新增 `CommentPage`/`UploadResponse`/`UpdateUserRequest`；
  mock 资产（`user_comments.json`/`chapter_comments.json`）升级新键并改分页包裹。
- `lib_book_common`：`CommentRepository` 分页解析、删除判成功改业务码；`Mappers.kt` 字段改名。
- `module_me`：`ModifyRepository` 昵称走 `updateMe`、头像两步上传；ViewModel/UI 层零改动。

## 落地补记（2026-09-03）

- 上述“mock 资产改分页包裹”当时**只改了资产，没改 mock 解码类型**（`CommentNetworkTest` 仍按
  `List<Comment>` 读）；错配抛的 `SerializationException` 被 `CoroutineAdapter` 吞成「未知错误」，
  表现为 mock 构建下评论页永远加载失败而不闪退。现以 `CommentNetworkTestTest`（纯 JVM 资产契约测试）钉住。
- 同时重排两份评论资产：`chapter_url` 交叉对齐（从「我的评论」点任意一条都能进到非空的评论区）、
  作者名与 `user_login.json` 同源（UI 的本人长按删除门禁拿登录写入 SP 的用户名作比对）、
  mock 的 `addComment` 改为按服务端方式回显 id/作者/时间（客户端占位 id=0 原样回显会让
  评论列表 `key = { it.id }` 撞 key 抛异常）。
