package com.ebook.api.service.user

import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.LoginRequest
import com.ebook.api.entity.ModifyPwdRequest
import com.ebook.api.entity.RefreshTokenRequest
import com.ebook.api.entity.RegisterRequest
import com.ebook.api.entity.ResetPasswordRequest
import com.ebook.api.entity.SendCodeRequest
import com.ebook.api.entity.UpdateUserRequest
import com.ebook.api.entity.User

/**
 * 账号接口按调用方切出的四个窄面（ADR-0059；与书架四面 ADR-0055、书源四面 ADR-0047 同形）。
 *
 * 原 `UserDataSource` 把十个方法塞在一张面里、且每个方法都回服务端的 `RespDTO` 信封，
 * 于是每个调用方都要自己抄一遍「比业务码 → 掏 data → 不然抛」：`module_login` 与
 * `module_me` 合计十处逐字副本，一处漏改就是另一处行为。现在**信封消化收在缝内**
 * （唯一出口见 `UserEnvelope.kt`），面上只出现类型化结论：
 *
 * - 请求/响应三面回 `Result<T>`，`T` 是**载荷本身**（不是信封）；业务码非成功、A0230
 *   静默刷新与重放、传输异常都已在缝内翻译为既有异常类型（`CoroutineAdapter.ApiException` /
 *   `SessionExpiredException`），所以 `reportFailure` 的口径一字不变；
 * - 会话刷新面回密封的 [TokenRefresh]：它**刻意不经** `Result`——刷新失败必须自吞成
 *   [TokenRefresh] 的失败档（[TokenRefresh.Rejected] / [TokenRefresh.NotEstablished]），
 *   否则会被网络层当成 A0230 再刷一次（死循环）。
 *
 * 切分判据是**调用方**而不是层次（同 ADR-0055）：`UserRepository`（module_login）注
 * [AccountSignIn] + [AccountPassword] 两面，`ModifyRepository`（module_me）只注
 * [AccountProfile]，`SessionTokenRefresher`（lib_book_common）只注 [SessionRefresh]。
 * 两个实现（[UserNetwork] 真实后端、[UserNetworkTest] mock 资产）都实现全部四面，
 * 四面各一条 `@Binds @Singleton` 指向同一个单例——拆实例会静默复制 mock 的内存态。
 * **聚合的宽接口已删除**：账号域没有「要整张面」的生产或测试消费者，留一条聚合绑定
 * 就等于给宽接口留一条注入口。
 */

/**
 * 登录注册面：会话的建立（邮箱 + 密码）与主动作废，加上注册那条发码→建号链。
 *
 * 唯一生产消费者是 `module_login` 的 `UserRepository`，其上的三个入口分别是
 * `LoginViewModel.login`、`RegisterViewModel`（发码 + 建号）与 `LoginProvider.logout`。
 *
 * [logout] 归本面而不归刷新面：它是**用户主动**作废服务端的全部 refresh token，
 * 与「access token 过期后续期」是两件事；放进刷新面会把「救不回来的会话」和
 * 「不想再要了」混成同一张能力表。服务端作废失败不回滚本地清理，语义见
 * [UserRepository.logout] 与 ADR-0019。
 */
interface AccountSignIn {

    /** 登录（邮箱 + 密码）：结论是服务端下发的双 token + 用户资料。 */
    suspend fun login(request: LoginRequest): Result<LoginDTO>

    /** 注册发码（按邮箱发 6 位验证码，注册专用端点，与找回密码发码分离）。 */
    suspend fun sendRegisterCode(request: SendCodeRequest): Result<Unit>

    /** 注册（邮箱 + 验证码 + 密码）：注册即激活、不发 token，成功后由用户主动登录。 */
    suspend fun register(request: RegisterRequest): Result<Unit>

    /** 登出：服务端作废该用户全部 refresh token；本地会话清理由调用方负责。 */
    suspend fun logout(): Result<Unit>
}

/**
 * 密码面：已登录改密与找回密码（发码 → 验证码重置）两条路径的端点。
 *
 * 唯一生产消费者是 `module_login` 的 `UserRepository`，其上的入口都挂在
 * `ModifyPwdViewModel` 的双路径上（已登录改密 / RESET 模式重置）。
 * 找回密码与改密合成一张面而不是两张：它们的消费者、页面与失败口径都是同一条流程，
 * 按调用方切的判据在此只给出一个面。
 */
interface AccountPassword {

    /** 已登录改密（旧密码由服务端校验，A0210）：成功后该用户全部 token 失效。 */
    suspend fun modifyPwd(request: ModifyPwdRequest): Result<Unit>

    /** 找回密码发码（与注册发码端点分离）：服务端 60 秒频控（A0241）。 */
    suspend fun sendForgotPasswordCode(request: SendCodeRequest): Result<Unit>

    /** 验证码重置密码（A0132 码错 / A0241 超限由服务端裁决）。 */
    suspend fun resetPassword(request: ResetPasswordRequest): Result<Unit>
}

/**
 * 资料面：当前用户资料的局部更新与头像文件的上传。
 *
 * 唯一生产消费者是 `module_me` 的 `ModifyRepository`（改昵称、换头像两步流程）。
 *
 * [uploadAvatar] 收**字节 + 文件名**：`MultipartBody.Part`、multipart 字段名 `avatar`
 * 与媒体类型都是传输细节，归适配器（`UserService.uploadAvatar` 的契约）；调用方只该
 * 知道「我有这些字节、它叫什么」。返回结论直接是可访问 URL——空串按「载荷缺失」处置。
 */
interface AccountProfile {

    /** 更新当前用户信息（昵称/头像/邮箱部分更新，非空即更新），结论是更新后的用户。 */
    suspend fun updateMe(request: UpdateUserRequest): Result<User>

    /** 上传头像字节（文件名决定服务端存储名），结论是可访问 URL。 */
    suspend fun uploadAvatar(bytes: ByteArray, fileName: String): Result<String>
}

/**
 * 会话刷新面：`POST /api/auth/refresh` 的单次续期。
 *
 * 唯一生产消费者是 `lib_book_common` 的 `SessionTokenRefresher`（单飞互斥在它那里）。
 *
 * 本面是四面里唯一不回 `Result` 的：刷新**不得**经过 `CoroutineAdapter.safeApiCall`
 * ——否则刷新自身失败（A0230）会被网络层再判一次过期、再刷一次，形成死循环
 * （见 ADR-0010）。因此信封在这里消化成密封的 [TokenRefresh]：一份轮换、两份失败（失败按
 * 成因分档），每一档都是结论、都不是异常，调用方按档处置即可。取消（`CancellationException`）
 * **不折进**任何失败档而是原样上抛：取消不是「会话救不回来」，吞掉会误发 SessionExpired
 * 把用户踢到登录页。
 */
interface SessionRefresh {

    /** 用 refresh token 换新的双 token。 */
    suspend fun refresh(refreshToken: RefreshTokenRequest): TokenRefresh
}

/**
 * 会话刷新面的结论：服务端**接受并轮换**（[Rotated]），或**这次没能换来新凭证**
 * （[Rejected] 与 [NotEstablished]）。
 *
 * 失败分两档而不是合成一档（ADR-0059）：`CONTEXT.md`「续期结论」的 `_Avoid_` 禁的正是
 * 「把『被服务端拒绝』与『请求没成立』说成同一件事」，同一纪律在源域写作「未装配执行器与
 * 执行失败是两种类型化异常，不得合并」。两档对调用方是同一个处置（无新 token 可用 →
 * 网络层发一次 SessionExpired），但**排查方向相反**——[Rejected] 要查服务端的拒绝理由
 * （这份凭据确实不给续），[NotEstablished] 要查本机连通/配置或响应契约本身。
 *
 * [Rotated.refreshToken] 可空——refresh 端点已不再返回 user（ADR-0011），个别响应也
 * 会缺 `refresh_token`；**保留旧凭据**这一处置归调用方（`SessionTokenRefresher`），
 * 面只负责如实说「服务端没给新的」。
 */
sealed interface TokenRefresh {

    /** 服务端接受：新 access token 必在，[refreshToken] 为服务端下发的那一份（可能缺省）。 */
    data class Rotated(val accessToken: String, val refreshToken: String?) : TokenRefresh

    /**
     * 服务端明确拒绝续期：**答复到了，且答复的内容就是「不给续」**（业务码非成功，
     * 如 A0230 refresh token 已失效、A0201 账户不存在）。
     *
     * 判据只有一条——业务码非成功，所以 [code] 总是带值（服务端答复里的那五位码）。
     * 「成功码却没有 access token」**不**归本档：服务端没说过「不给续」，不能替它把
     * 一句未定的答复读成拒绝，那类成因见 [NotEstablished]。[reason] 是服务端文案，
     * 只作排查线索（本面不经 `reportFailure`，不承诺可直接上屏）。
     */
    data class Rejected(val code: String, val reason: String) : TokenRefresh

    /**
     * 本次没能把请求成立：**没有拿到一份足以判定「服务端拒绝」的答复**。
     *
     * 归档判据与 `UserNetwork.refresh` 的 try/catch 一一对应：
     * - 压根没打通——传输异常，以及服务端地址未配置时惰性构造 Retrofit 抛出的
     *   `ServerAddressNotConfiguredException`（应用不内置地址，ADR-0053）；
     * - 打通了但答复不成形——**成功码却没有 access token**（`data` 为空或 token 字段为空串）。
     *   这是刷新端的「载荷缺失」（与 [MissingPayloadException] 同一档语义）：结论未定，
     *   既不是服务端报出来的错，也不是没打通；业务面把它走 `Result` 里的异常，本面不经
     *   `Result`，所以落在这一档。
     *
     * [reason] 是给日志看的成因描述：异常时为异常 message（可能为空串）。
     */
    data class NotEstablished(val reason: String) : TokenRefresh
}

/**
 * 业务码成功、`data` 却缺失（或必要的载荷字段为空）——**结论未定**，不是服务端报错。
 *
 * 它与 [CoroutineAdapter.ApiException][com.ebook.api.utils.CoroutineAdapter.ApiException]
 * 分工明确：后者携带服务端下发的用户可见文案，本类型什么文案都不带，因为「这次失败该
 * 说什么」只有调用方知道（`R.string.modify_nickname_failed` 之类的页面文案不能上移到
 * 共享库）。所以翻译文案用 [missingPayloadAs]，别在缝里造句子。
 */
class MissingPayloadException(message: String) : Exception(message)

/**
 * 把 [MissingPayloadException] 这一档换成调用方的文案，其余失败原样透传。
 *
 * 「载荷缺失」是缝内消化后剩下的唯一一档需要页面来定说法的失败；把它收成一个函数而不是
 * 在每个调用点重写 `data ?: throw`——后者正是本任务要消灭的副本形态。
 */
fun <T> Result<T>.missingPayloadAs(text: String): Result<T> = recoverCatching {
    throw if (it is MissingPayloadException) MissingPayloadException(text) else it
}
