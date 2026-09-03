package com.ebook.common.provider

/**
 * 登录域对外暴露的跨模块能力（TheRouter SPI，实现见 module_login 的 `LoginProvider`）。
 *
 * 只暴露**服务端侧**的登出：本地会话清理由 [com.ebook.common.domain.UserSessionManager.clearSession]
 * 单点负责，不在这里重复（调用方顺序为「尽力作废服务端 → 无条件清本地」）。
 *
 * 独立运行（isModule=true）时 module_login 不在依赖图里，本服务取不到，
 * 调用方需允许空实现——调试宿主不连后端，本就无需作废服务端凭证。
 */
interface ILoginProvider {
    /**
     * 作废服务端会话（POST /api/auth/logout，服务端会作废该用户全部 refresh token）。
     *
     * @return 成功返回 Unit；网络或业务失败返回异常，由调用方决定是否提示
     */
    suspend fun logout(): Result<Unit>
}
