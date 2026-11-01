package com.ebook.login.repository

import com.ebook.api.entity.LoginRequest
import com.ebook.api.entity.ModifyPwdRequest
import com.ebook.api.entity.RegisterRequest
import com.ebook.api.entity.ResetPasswordRequest
import com.ebook.api.entity.SendCodeRequest
import com.ebook.api.service.user.AccountPassword
import com.ebook.api.service.user.AccountSignIn
import com.ebook.api.service.user.missingPayloadAs
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
 * 注入的是账号的两个窄面（ADR-0059）——[AccountSignIn]（登录/注册/登出）与
 * [AccountPassword]（改密/找回密码），不是整张宽接口：本仓库用到哪几个端点，构造参数就写着哪几面。
 *
 * 本层**不再拆信封**：业务码翻译、A0230 单飞静默刷新与重放、`data` 为空那一档都在
 * 网络适配器的缝内做完（`lib_ebook_api` 的 `UserEnvelope.kt`），仓库收到的已经是
 * `Result<载荷>`。仓库只补两件属于它的事：把载荷换成域类型（[toUserSession]），
 * 以及给「成功码却没带回载荷」这一档挑一句本域说得出口的话（[missingPayloadAs]）——
 * 文案归调用方，缝内不造句子。
 *
 * 会话过期（refresh 失败）已在网络层全局处置，调用点一律经共享的
 * [com.ebook.common.util.reportFailure] 上报——那条路径只记日志、不再弹提示。
 */
@Singleton
class UserRepository @Inject constructor(
    private val accountSignIn: AccountSignIn,
    private val accountPassword: AccountPassword,
) : BaseModel() {

    /**
     * 注册发码（注册专用端点，与忘记密码发码分离）
     */
    suspend fun sendRegisterCode(email: String): Result<Unit> =
        accountSignIn.sendRegisterCode(SendCodeRequest(email))

    /**
     * 注册（邮箱 + 验证码 + 密码）：注册即激活但不发 token，成功后由用户主动登录。
     */
    suspend fun register(email: String, code: String, password: String): Result<Unit> =
        accountSignIn.register(RegisterRequest(email, code, password))

    /** 登录（邮箱 + 密码）：成功返回服务端下发的会话（身份 + 双 token）。 */
    suspend fun login(email: String, password: String): Result<UserSession> =
        accountSignIn.login(LoginRequest(email, password))
            .map { it.toUserSession() }
            .missingPayloadAs("登录失败：返回数据为空")

    /** 登出：服务端作废该用户全部 refresh token，本地会话由调用方清理。 */
    suspend fun logout(): Result<Unit> = accountSignIn.logout()

    /** 已登录改密（旧密码由服务端校验，A0210）：成功后原会话失效，需重新登录。 */
    suspend fun modifyPwd(oldPassword: String, newPassword: String): Result<Unit> =
        accountPassword.modifyPwd(ModifyPwdRequest(oldPassword, newPassword))

    /** 忘记密码发码（与注册发码端点分离）：服务端向邮箱发 6 位验证码，60 秒频控（A0241）。 */
    suspend fun sendForgotPasswordCode(email: String): Result<Unit> =
        accountPassword.sendForgotPasswordCode(SendCodeRequest(email))

    /** 忘记密码重置（邮箱 + 验证码 + 新密码）：验证码由服务端校验（A0132/A0241）。 */
    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit> =
        accountPassword.resetPassword(ResetPasswordRequest(email, code, newPassword))
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
