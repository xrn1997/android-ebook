package com.ebook.login.repository

import com.ebook.api.entity.LoginRequest
import com.ebook.api.entity.ModifyPwdRequest
import com.ebook.api.entity.RegisterRequest
import com.ebook.api.entity.ResetPasswordRequest
import com.ebook.api.entity.SendCodeRequest
import com.ebook.api.service.user.UserDataSource
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.domain.UserSession
import com.ebook.common.mapper.toUserSession
import com.xrn1997.common.mvvm.model.BaseModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户认证仓库：登录/注册/改密/忘记密码全部端点的数据层收口。
 *
 * 所有方法经 [CoroutineAdapter.safeApiCall] 包裹：传输层异常与业务码异常
 * 统一转为 [Result]，ViewModel 只处理成败分支；会话过期（refresh 失败）
 * 已在网络层全局处置，调用点经 isSessionExpiredHandled 静默。
 */
@Singleton
class UserRepository @Inject constructor(
    private val dataSource: UserDataSource,
    private val coroutineAdapter: CoroutineAdapter
) : BaseModel() {

    /**
     * 注册发码（注册专用端点，与忘记密码发码分离）
     */
    suspend fun sendRegisterCode(email: String): Result<Unit> =
        coroutineAdapter.safeApiCall { dataSource.sendRegisterCode(SendCodeRequest(email)) }
            .mapCatching { resp ->
                resp.data ?: Unit
            }

    /**
     * 注册（邮箱 + 验证码 + 密码）：注册即激活但不发 token，成功后由用户主动登录。
     */
    suspend fun register(email: String, code: String, password: String): Result<Unit> =
        coroutineAdapter.safeApiCall { dataSource.register(RegisterRequest(email, code, password)) }
            .mapCatching { resp ->
                resp.data ?: Unit
            }

    /** 登录（邮箱 + 密码）：成功返回服务端下发的会话（身份 + 双 token）。 */
    suspend fun login(email: String, password: String): Result<UserSession> =
        coroutineAdapter.safeApiCall { dataSource.login(LoginRequest(email, password)) }
            .mapCatching { resp ->
                resp.data?.toUserSession() ?: throw Exception("登录失败：返回数据为空")
            }

    /** 登出：服务端作废该用户全部 refresh token，本地会话由调用方清理。 */
    suspend fun logout(): Result<Unit> =
        coroutineAdapter.safeApiCall { dataSource.logout() }
            .mapCatching { resp ->
                resp.data ?: Unit
            }

    /** 已登录改密（旧密码由服务端校验，A0210）：成功后原会话失效，需重新登录。 */
    suspend fun modifyPwd(oldPassword: String, newPassword: String): Result<Unit> =
        coroutineAdapter.safeApiCall { dataSource.modifyPwd(ModifyPwdRequest(oldPassword, newPassword)) }
            .mapCatching { resp ->
                resp.data ?: Unit
            }

    /** 忘记密码发码（与注册发码端点分离）：服务端向邮箱发 6 位验证码，60 秒频控（A0241）。 */
    suspend fun sendForgotPasswordCode(email: String): Result<Unit> =
        coroutineAdapter.safeApiCall { dataSource.sendForgotPasswordCode(SendCodeRequest(email)) }
            .mapCatching { resp ->
                resp.data ?: Unit
            }

    /** 忘记密码重置（邮箱 + 验证码 + 新密码）：验证码由服务端校验（A0132/A0241）。 */
    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit> =
        coroutineAdapter.safeApiCall { dataSource.resetPassword(ResetPasswordRequest(email, code, newPassword)) }
            .mapCatching { resp ->
                resp.data ?: Unit
            }
}

/**
 * UserRepository 的 Hilt EntryPoint：Provider 由 TheRouter 创建（非 Hilt 注入），
 * [com.ebook.login.provider.LoginProvider] 经此桥接获取仓库实例。
 */
@InstallIn(SingletonComponent::class)
@EntryPoint
interface UserRepositoryEntryPoint {
    fun getUserRepository(): UserRepository
}
