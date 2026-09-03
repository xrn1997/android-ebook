package com.ebook.api.auth

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 会话级全局事件（统一收口，避免逐调用点分支处置）。
 */
sealed class SessionEvent {

    /**
     * 会话「救不回来」：refresh token 刷新失败（过期/作废/服务端拒绝）。
     *
     * 订阅方处置约定（见登录现代化规格）：清会话 + 提示用户 + 立即跳登录页。
     */
    data object SessionExpired : SessionEvent()
}

/**
 * 会话事件总线：网络层发射、UI 层订阅的全局收口。
 *
 * 设计说明：
 * - 单订阅语义下用 [MutableSharedFlow.tryEmit] + 1 个缓冲容量：事件是幂等处置
 *   （清会话 + 跳登录），并发过期风暴时允许丢弃重复事件，绝不阻塞请求线程；
 * - 事件定义与发射都在 lib_ebook_api（依赖方向允许），订阅方在上层模块。
 */
@Singleton
class SessionEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 1)

    /** 会话事件流（只读）。 */
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /**
     * 非阻塞发射：缓冲满时丢弃（重复的过期事件无需重复处置）。
     */
    fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }
}
