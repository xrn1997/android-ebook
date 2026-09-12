package com.ebook.common.util

import com.ebook.api.utils.CoroutineAdapter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * [userMessage] 的文案口径测试：一次失败变成用户所见的那句文案，全仓调用点共用这一处。
 *
 * 锁住三条：
 * - 业务异常显示服务端下发的原文，**不掺内部类名与业务码**（`ApiException` 早期把
 *   `ApiException(code: msg)` 整串拼进 message，内部信息直接泄漏到 Toast）；
 * - 本地异常显示自身消息；
 * - 消息为 null 的异常归**空串**，不是字面量 "null"——收口前 module_book 走的是
 *   `"${exception.message}"`，无消息的本地异常会把 "null" 弹给用户看。
 */
class UserMessageTest {

    @Test
    fun `业务异常取服务端下发的文案，不掺内部类名与业务码`() {
        val exception = CoroutineAdapter.ApiException(code = "A0158", message = "昵称已被占用")

        assertEquals("昵称已被占用", exception.userMessage())
    }

    @Test
    fun `本地异常取自身消息`() {
        assertEquals("网络不可达", IOException("网络不可达").userMessage())
    }

    @Test
    fun `消息为 null 的异常归空串而不是字面量 null`() {
        assertEquals("", NullPointerException().userMessage())
    }
}
