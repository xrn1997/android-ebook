package com.ebook.common.analyze.source

import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceBinding] 四档到生产解析入口的翻译：`parserOrThrow` 是 ADR-0045 决策 2 的唯一出口。
 *
 * 四档本身由 [BookSourceManagerImplTest] 锁；这里只锁调用点拿到的结局——`Found` 给 parser、
 * `Missing`/`Broken` 给可提示的源失效、`Local` 给编程错误。翻译若漂移，所有调用点会同时
 * 把「本地书路由错」与「源被删了」说成同一句话。
 */
class SourceBindingTest {

    private fun managerWith(binding: SourceBinding): BookSourceManager =
        object : BookSourceManager by FakeBookSourceManager() {
            override suspend fun resolveSource(sourceUrl: String): SourceBinding = binding
        }

    private suspend fun errorFrom(binding: SourceBinding): Throwable? =
        runCatching { managerWith(binding).parserOrThrow(SOURCE_URL, detail = DETAIL) }.exceptionOrNull()

    @Test
    fun `Found 原样交出同一个 parser`() = runTest {
        val parser: BookParser = RecordingBookParser(SOURCE_URL)

        assertSame(parser, managerWith(SourceBinding.Found(parser)).parserOrThrow(SOURCE_URL))
    }

    @Test
    fun `Missing 与 Broken 都翻译成可提示的源失效`() = runTest {
        for (binding in listOf(SourceBinding.Missing(SOURCE_URL), SourceBinding.Broken(SOURCE_URL))) {
            val error = errorFrom(binding)

            assertTrue("$binding 应抛 BookSourceNotFoundException，实际 $error", error is BookSourceNotFoundException)
            assertEquals(SOURCE_URL, (error as BookSourceNotFoundException).sourceUrl)
            assertTrue("现场描述要带到消息里", error.message.orEmpty().contains(DETAIL))
        }
    }

    @Test
    fun `Local 翻译成编程错误而不是源失效`() = runTest {
        val error = errorFrom(SourceBinding.Local)

        assertTrue("本地书走网络取源是编程错误，实际 $error", error is IllegalStateException)
        assertTrue("不能伪装成用户可处置的源失效", error !is BookSourceNotFoundException)
    }

    private companion object {
        const val SOURCE_URL = "https://source.example"
        const val DETAIL = "聚合搜索 source=示例源"
    }
}
