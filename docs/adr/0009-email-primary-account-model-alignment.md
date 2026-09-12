# 邮箱即登录主标识，注册三步不发 token

服务端将账号主标识从用户名改为邮箱，客户端随之对齐：登录用「邮箱 + 密码」；注册改为三步（邮箱发码 → 验证码 + 密码建号，注册即激活、不发 token → 用户主动登录）；找回密码纯邮箱化；用户名降格为展示用标识（可重复、注册时服务端自动生成占位、可后改）。这一改动取代了更早一版认证模型中「用户名为登录主标识、邮箱仅作注册必填与找回密码通道」的账号体系与注册/找回流程设定；同一版决策里的双 token、密码不落盘、业务码信封（响应统一包 `{code, error, data}`、成败由五位业务码承载）等部分不变。

## 动机

- 用户名是展示性标识，不是所有权凭据；邮箱既易表达又能证明所有权，「邮箱 + 验证码」注册一次完成「建号」与「证明邮箱归我」，杜绝填错邮箱造成「永远无法找回密码」的死账号。
- 注册不再收集用户名，注册表单从 4 项缩到「邮箱/验证码/密码」3 项，注册更快。
- 「只有 login 发 token」收束了凭证签发语义：注册成功不等于已建立用户会话，会话只由登录（及刷新）产生，边界清晰。

## 决策

1. **账号标识**：`email` 唯一、登录主标识；`username` 非空但可重复，仅展示用——用户名是展示性标识，不是所有权凭据。
2. **注册三步**：`POST /api/auth/send-code {email}` → `POST /api/auth/register {email, code, password}`（返回不含 token）→ 客户端引导用户主动登录（跳登录页并预填邮箱）——注册成功不等于已建立会话，凭证只由登录签发。
3. **登录**：`POST /api/auth/login {email, password}`，返回双 token + user——登录是签发会话的唯一主动路径。
4. **找回密码纯邮箱**：`forgot-password/send-code {email}` → `forgot-password/reset {email, code, new_password}`；验证码正确性一律由服务端在重置时校验，客户端不做本地假码校验——旧「本地生成验证码弹窗」已删除，服务端校验是单一事实源。
5. **密码管理双模式**：已登录改密（编辑资料页入口 → 改密页 LOGGED_IN 模式，旧密码服务端校验）；忘记密码（登录页入口 → 验证身份页 → 改密页 RESET 模式）——两条路径的用户身份确认方式不同，已登录凭旧密码、未登录凭邮箱验证码。
6. **序列化边界翻译用逐字段 `@SerialName`**：服务端载荷为蛇形（`refresh_token`/`old_password`/`uid`/`avatar` 等），Kotlin 属性保持驼峰；拒绝在 Json 配置上开全局 `NamingStrategy.SnakeCase`——该 Json 实例的管辖范围内还有不可控载荷（三方书源等），全局规则是隐性风险；逐字段标注让每个 DTO 的线上键显式可查。`User` 实体属性名（`id`/`image`）不动（UI/Parcelable 层广泛使用），仅边界翻译。

## 被拒方案

- **继续用户名主标识**：与服务端账号契约冲突，且「注册必填用户名」是多余摩擦。
- **注册即自动登录（register 发 token）**：稀释「仅 login 发 token」语义，被服务端明确拒绝。
- **全局蛇形命名策略**：对不可控载荷是隐性风险，Json 实例同时服务于书源等第三方载荷。

## 下游影响

- `lib_ebook_api`：`LoginRequest`/`RegisterRequest`/`SendCodeRequest{email}` 等请求 DTO 重建；`UserService` 增加 `sendRegisterCode`；`register` 返回 `RespDTO<Unit>`；mock 资产 JSON 改为与服务端线上载荷同形（`uid`/`avatar`/`refresh_token`，无 password）。
- `module_login`：登录页邮箱化（`KeyboardType.Email`）；注册页三步表单；`VerifyUserActivity` 重建为真发码流程；`ModifyPwdActivity` 双模式（RESET/LOGGED_IN）；`tel_register`→`register_entry`、`print_tel` 删除（去手机号语义收尾）。
- `module_me`：编辑资料页「修改密码」入口从忘记密码路径改指已登录改密路径——已持有会话时邮箱验证码身份核验形同虚设，且会绕过旧密码校验。
- 发码频控、登录限流与账号锁定的**判定全在服务端**：客户端不自行校验验证码、不自建第二套限流，只把服务端返回的业务码翻成提示（发码类错误同样经 `ApiException` 的服务端原文展示）。客户端唯一的本地计时是与服务端发码频控对齐的 **60 秒重发倒计时**（`codeCountdown`，期间禁用「获取验证码」按钮）——它防的是用户重复点击，服务端频控照旧是拒绝的唯一来源。
