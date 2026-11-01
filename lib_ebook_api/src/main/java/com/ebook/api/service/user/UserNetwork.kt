package com.ebook.api.service.user

import com.ebook.api.RetrofitBuilder
import com.ebook.api.config.ServerAddressNotConfiguredException
import com.ebook.api.config.ServerAddressStore
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.LoginRequest
import com.ebook.api.entity.ModifyPwdRequest
import com.ebook.api.entity.RefreshTokenRequest
import com.ebook.api.entity.RegisterRequest
import com.ebook.api.entity.ResetPasswordRequest
import com.ebook.api.entity.SendCodeRequest
import com.ebook.api.entity.UpdateUserRequest
import com.ebook.api.entity.User
import com.ebook.api.utils.CoroutineAdapter
import com.xrn1997.common.util.Logger
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号四面的真实后端实现：一张 Retrofit 接口（[UserService]）之上的**传输适配器**。
 *
 * 本类是信封唯一的消化处——`RespDTO` 在方法体内被 [UserEnvelope.kt] 的那几个出口折成
 * 类型化结论后就地消失，调用方（`module_login` 的 `UserRepository`、`module_me` 的
 * `ModifyRepository`）拿到的是 `Result<载荷>` 或 [TokenRefresh]，再也摸不到业务码。
 *
 * 四面合成一个实现（同 ADR-0055 的 `BookRepository`）：拆实例会各自持有一份 lazy Retrofit
 * 与刷新状态，静默复制状态；收窄只发生在声明层。
 */
@Singleton
class UserNetwork @Inject constructor(
    retrofitBuilder: RetrofitBuilder,
    serverAddressStore: ServerAddressStore,
    private val coroutineAdapter: CoroutineAdapter,
) : AccountSignIn, AccountPassword, AccountProfile, SessionRefresh {

    /**
     * 生效面而非配置面：本进程的基址在 store 构造时冻结，改地址要重启（ADR-0052）。
     * lazy + 未配置即抛 [ServerAddressNotConfiguredException]：应用不带默认地址（ADR-0053），
     * 未配置时任何服务端调用在此快速失败（异常 message 即用户所见提示），不发请求、
     * 也不造出一个「打到野主机」的半成品 Retrofit。
     */
    private val networkApi: UserService by lazy {
        val baseUrl = serverAddressStore.effective()?.baseUrl
            ?: throw ServerAddressNotConfiguredException()
        retrofitBuilder.getRetrofitObject(baseUrl).create(UserService::class.java)
    }

    override suspend fun login(request: LoginRequest): Result<LoginDTO> =
        coroutineAdapter.concluding("登录会话") { networkApi.login(request) }

    override suspend fun sendRegisterCode(request: SendCodeRequest): Result<Unit> =
        coroutineAdapter.acknowledged { networkApi.sendRegisterCode(request) }

    override suspend fun register(request: RegisterRequest): Result<Unit> =
        coroutineAdapter.acknowledged { networkApi.register(request) }

    override suspend fun logout(): Result<Unit> =
        coroutineAdapter.acknowledged { networkApi.logout() }

    override suspend fun modifyPwd(request: ModifyPwdRequest): Result<Unit> =
        coroutineAdapter.acknowledged { networkApi.modifyPwd(request) }

    override suspend fun sendForgotPasswordCode(request: SendCodeRequest): Result<Unit> =
        coroutineAdapter.acknowledged { networkApi.sendForgotPasswordCode(request) }

    override suspend fun resetPassword(request: ResetPasswordRequest): Result<Unit> =
        coroutineAdapter.acknowledged { networkApi.resetPassword(request) }

    override suspend fun updateMe(request: UpdateUserRequest): Result<User> =
        coroutineAdapter.concluding("更新后的用户资料") { networkApi.updateMe(request) }

    /**
     * 上传头像：字节 + 文件名在这里才变成 multipart——字段名 `avatar` 与媒体类型是
     * 服务端契约（`UserService.uploadAvatar`），不是调用方的知识。空 URL 与缺 `data`
     * 同归「载荷缺失」：服务端说上传成功却没给可访问地址，与「上传失败」不是一回事。
     */
    override suspend fun uploadAvatar(bytes: ByteArray, fileName: String): Result<String> =
        coroutineAdapter.concluding("头像地址", { networkApi.uploadAvatar(avatarPart(bytes, fileName)) }) { upload ->
            upload.url.takeIf { it.isNotEmpty() }
        }

    /**
     * 会话刷新：**不经** [CoroutineAdapter.safeApiCall]——刷新自身的失败（A0230）若再进
     * 那条管道会被判成「又过期了」而再刷一次，死循环（ADR-0010）。所以这里直接拿裸信封交给
     * [toTokenRefresh] 消化成分档结论；`CancellationException` 原样上抛（取消不是「救不回来」），
     * 其余异常自吞成 [TokenRefresh.NotEstablished]——本面对外只回结论，且「没打通」与
     * 「服务端答了不给续」不并成一句（[toTokenRefresh] 的拒绝档走业务码那条路）。
     */
    override suspend fun refresh(refreshToken: RefreshTokenRequest): TokenRefresh = try {
        networkApi.refreshToken(refreshToken).toTokenRefresh()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // 传输异常与服务端地址未配置（惰性构造 networkApi 时抛，ADR-0053）都在这里：
        // 请求压根没成立，不是服务端拒绝
        Logger.w(TAG, "刷新请求未成立：${e.message}")
        TokenRefresh.NotEstablished(reason = e.message.orEmpty())
    }

    /**
     * 头像的服务端契约（multipart 字段名 + 媒体类型）收在适配器内部，字段名对外不可见；
     * 本 companion 之所以是 `internal` 而不是 `private`，只为让 `AvatarPartTest` 能在 JVM 上
     * 钉住那条契约——[UserService.uploadAvatar] 的注解是裸 `@Part MultipartBody.Part`，
     * Retrofit 不从任何注解重新推导字段名，写错不会有任何编译信号。
     */
    internal companion object {
        private const val TAG = "UserNetwork"

        /**
         * 头像上传管线目前一律 JPEG（拍照与相册两条路都先经裁剪压缩成 jpg），故媒体类型
         * 归适配器而不是让页面把一个 MIME 串传来传去；换格式时改这里一处。
         */
        private const val AVATAR_MEDIA_TYPE = "image/jpeg"

        /** multipart 字段名：与服务端 `POST /api/uploads/avatar` 的契约同名。 */
        private const val AVATAR_FIELD = "avatar"

        internal fun avatarPart(bytes: ByteArray, fileName: String): MultipartBody.Part =
            MultipartBody.Part.createFormData(
                AVATAR_FIELD,
                fileName,
                bytes.toRequestBody(AVATAR_MEDIA_TYPE.toMediaTypeOrNull()),
            )
    }
}
