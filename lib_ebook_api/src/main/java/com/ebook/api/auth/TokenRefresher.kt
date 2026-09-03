package com.ebook.api.auth

/**
 * Token 静默刷新器接缝（定义在 lib_ebook_api，实现由上层注入）。
 *
 * 为什么是接口而不是直接实现：刷新需要读取会话持久化（lib_book_common 的
 * UserSessionManager），而依赖方向是 lib_book_common → lib_ebook_api，
 * 反向引用不允许——底层定义接缝、上层提供实现（Hilt @Binds）。
 *
 * 触发时机：业务响应码 A0230（access token 过期）由 [com.ebook.api.utils.CoroutineAdapter]
 * 检测后调用；实现方负责单飞互斥（并发过期只刷一次）。
 *
 * 注意：刷新自身对 /api/auth/refresh 的调用不得经过 CoroutineAdapter，
 * 否则刷新失败会再次触发刷新，形成死循环。
 */
interface TokenRefresher {

    /**
     * 单飞刷新 access token。
     *
     * @param expiredAccessToken 触发刷新时请求携带的（已过期的）access token；
     *   若锁内发现 TokenHolder 当前 token 已与其不同，说明并发请求已完成刷新，
     *   直接返回当前 token 而不再打刷新接口
     * @return 刷新后的新 access token；无 refresh token、刷新失败或服务端拒绝时返回 null
     */
    suspend fun refresh(expiredAccessToken: String?): String?
}
