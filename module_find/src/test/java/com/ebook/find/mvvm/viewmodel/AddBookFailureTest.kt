package com.ebook.find.mvvm.viewmodel

import com.ebook.source.analyze.BookSourceNotFoundException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [findNoticeOf] 的分类口径（ADR-0061）。
 *
 * 失败在到达页面之前只经过这一次分类：`BookSourceNotFoundException` 必须单独成档
 * （它的 message 形如「书源已失效：<URL>」，上屏的是资源文案），其余异常一律通用失败。
 * 分类错不闪退、不编译失败，只是把内部标识甩给用户，或把一条该重导源的故障说成可重试。
 * 故在纯 JVM 直接锁函数本身，不经过页面渲染。
 */
class AddBookFailureTest {

    @Test
    fun `书源失效的异常归到 SourceInvalid 并原样携带 cause`() {
        val failure = BookSourceNotFoundException(SOURCE_URL)

        val notice = findNoticeOf(failure)

        assertTrue("书源失效必须单独成档", notice is FindNotice.SourceInvalid)
        assertSame(
            "cause 不能被替换或重新包装：页面要拿同一个异常走 reportFailure",
            failure,
            notice.cause,
        )
    }

    @Test
    fun `其余异常归到 AddBookFailed 并原样携带 cause`() {
        val failure = IllegalStateException("网络好像不太稳定")

        val notice = findNoticeOf(failure)

        assertTrue("普通失败必须落到通用一档", notice is FindNotice.AddBookFailed)
        assertSame("cause 要原样带出，页面取 message 或兜底文案", failure, notice.cause)
    }

    @Test
    fun `分类看异常类型而不是消息文本`() {
        // 消息里出现「书源已失效」字样、类型却不是 BookSourceNotFoundException：不属于「源没了」。
        // 按文本猜会把解析/网络异常误报成需重导源的失效，把用户支去做无效动作。
        val failure = IllegalStateException("书源已失效：$SOURCE_URL")

        val notice = findNoticeOf(failure)

        assertTrue(notice is FindNotice.AddBookFailed)
        assertSame(failure, notice.cause)
    }

    private companion object {
        const val SOURCE_URL = "https://a.example"
    }
}
