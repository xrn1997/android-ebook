# 登录模块现代化需求规格

> 来源：2026-08-29 grill 会话（grill-with-docs），共 8 项决策。
> 依据：ADR-0007（API 契约）、ADR-0008（认证模型）。
> 范围档：B（接线 + 认证流程现代化 + 安全债），不含 UI/交互重做（C 档，独立迭代）。
>
> **状态（2026-08-29 更新）**：本规格基于「用户名主标识」时代的契约（服务端 ADR-0001）。
> 服务端随后以 ADR-0002 将账号模型改为「邮箱即主键」，客户端已全链路对齐，
> 更迭内容见 **ADR-0009**（账号/注册/找回/契约字段）、**ADR-0010**（静默刷新与会话过期收口，
> 原规格「待实现」项已落地）与 **ADR-0011**（token 与身份解耦：刷新仅凭证、access 只驻内存、过期单响）。
> 以下决策清单中第 3/5 项与「API 契约」表已被 ADR-0009 取代，
> 保留原文仅作决策历史；实施以新增 ADR 为准。
>
> **C 档追加（2026-08-29）**：认证域四页（登录/注册/验证身份/改密）UI 已在独立迭代中完成风格化改造：
> 统一为标准 M3 页面风（background 底色 + OutlinedTextField + 语义色，登录页移除固定背景图），
> 发码按钮增加 60 秒倒计时（请求成功后启动，与服务端频控对齐）。

## 决策清单

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | 迭代范围 | B：客户端接线 + 认证流程现代化 + 安全债；UI 重做不做 |
| 2 | 契约方向 | 以客户端生态为准：ebook-server 改说 `RespDTO{code, error, data}` + 五位业务码，HTTP 恒 200（ADR-0007） |
| 3 | 账号标识 | 用户名主标识 + 邮箱注册必填；弃用手机号语义（校验/文案去手机号化） |
| 4 | 密码持久化 | 密码彻底不落盘；启动恢复走 token，删除密码重放自动登录 |
| 5 | 改密/找回 | 双路径：已登录旧密码改密；未登录走邮件验证码重置；注册不做邮箱验证 |
| 6 | 过期处置 | 会话"救不回来"（refresh 失败）时经 SharedFlow 事件统一收口：清会话 + Toast + 立刻跳登录页 |
| 7 | 头像/昵称 | 本期只补数据通道（字段 + 响应载荷），上传功能独立迭代 |
| 8 | 基址配置 | `local.properties` 的 `ebook.server.host` 注入 BuildConfig；debug 走局域网，release 留部署占位 |

## API 契约（ebook-server 需提供）

信封统一 `{"code": "00000", "error": "", "data": {...}}`，HTTP 恒 200；业务码取 `ErrorCode` 既有码位，不发明新码。

| 方法 | 路径 | 说明 | 认证 | data / 关键业务码 |
|------|------|------|------|------|
| POST | /api/auth/register | 注册（用户名+密码+邮箱必填） | 否 | `{token, refresh_token, user{...}}`；A0110/A0111 用户名校验/已存在、A0120 密码校验、A0153 邮箱格式 |
| POST | /api/auth/login | 登录 | 否 | `{token, refresh_token, user{id, username, nickname, avatar}}`；A0201 账户不存在、A0210 密码错误 |
| POST | /api/auth/refresh | 刷新（旧 refresh 作废、发新双 token） | refresh | `{token, refresh_token}`；失败回 A0230 |
| POST | /api/auth/logout | 登出（作废该用户全部 refresh） | 是 | 空 |
| PUT | /api/users/me/password | 已登录改密（验旧密码） | 是 | A0210 旧密码错误 |
| POST | /api/auth/forgot-password/send-code | 发邮箱验证码（6 位、5 分钟有效、60 秒频控） | 否 | A0201 账户不存在；发送失败 C0503 |
| POST | /api/auth/forgot-password/reset | 验证码 + 新密码重置 | 否 | A0132 验证码错误、A0241 尝试超限 |
| GET/PUT | /api/users/me | 已有；响应补 nickname/avatar | 是 | — |

服务端实施要点：`pkg/errcode` 镜像客户端 `ErrorCode` 子集；`refresh_tokens` 表（token 哈希 + user_id + 过期）；验证码存储（5 分钟过期）；SMTP 配置进 `config.yaml` + `.env.example`（QQ/163 授权码）；用户表补 email（唯一）/nickname/avatar 字段，注册时 nickname 默认 = username。

## 客户端实施要点（android-ebook）

- **lib_ebook_api**：`UserService` 改新路径与新 DTO；基址从 BuildConfig 读取（删 `API.kt` 硬编码）；认证层新增静默刷新（A0230 → 单飞互斥刷新 → 成功重放原请求一次）与「会话过期」SharedFlow 事件（仅刷新失败时发出）
- **lib_book_common**：`UserSessionManager.saveSession(session, refreshToken)` 去掉 password 参数；删 `getPassword()`；持久化改存 refresh token（替换原密码键位）
- **module_main**：`SplashActivity.autoLogin` 从密码重放改为会话直恢复（有持久化会话即就绪，不再打登录接口）；订阅会话过期事件做统一处置
- **module_login**：注册页加邮箱必填项；全部校验/文案去手机号化；`VerifyUserActivity`（假验证码弹窗）改造为邮箱验证码重置流程；`ModifyPwdActivity` 接新改密端点；`LoginViewModel` 删 A0230 逐点分支（由全局事件接管）
- **module_me**：设置页新增「修改密码」入口（跳 module_login 改密页，带登录态）
- **mock 宿主**：`UserNetworkTest` 对齐新契约，保证独立调试宿主可用

## 明确不做（本期排除）

- 登录页双 Tab/协议勾选等 UI 交互重做（C 档）
- 头像上传（multipart + 文件存储 + 裁剪联调）
- refresh 重放检测（吊销全部会话）
- 短信验证码、一键本机号码登录、第三方登录（资质/通道不可得）
- HTTPS/部署（开发期局域网直连，部署另立迭代）

## 验收

> 更新：验收基线已切换——账号/注册/找回按 ADR-0009，静默刷新/过期收口按 ADR-0010；
> 原第 3 条（无明文密码）补升级设备清理（启动时移除旧版两处密码键）；
> 原第 4 条已完成。
> 再更新：AGENTS.md 已不再承载「已知问题」章节，原第 4 条所引用的条目不复存在，该验收项废止。


1. `ebook-server`：`go test ./...` 通过；新端点经 curl 走通注册→登录→刷新→改密→忘记密码全链路
2. `android-ebook`：`./gradlew test` + 涉及模块 `assembleDebug` 通过；真机联调注册/登录/启动恢复/静默续期/改密/找回密码全流程
3. 设备上确认无任何明文密码持久化（两处 SP 键位均清除）
4. ~~AGENTS.md 已知问题「无后端服务器」条目更新为指向 ebook-server~~（已废止：AGENTS.md 不再承载已知问题章节，见上方批注）
