package com.ebook.common.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ebook.common.util.SPUtil
import com.xrn1997.common.BaseApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReaderResumeStore] 的回归测试（Robolectric + 真实 SharedPreferences）。
 *
 * 锁住的是**读取判据**这一条：本对象的全部消费方（启动页）都只靠 `pendingNoteUrl()`
 * 是否为 null 决定要不要恢复阅读，故三态必须泾渭分明——没写过 = null、写过 = noteUrl、
 * 清过 = null。空串写入必须被视同「没有」：否则一旦有调用方传进空串，启动页会拿着空
 * noteUrl 去查库，查不到、白跑一趟恢复流程（且日志里看不出是「没标记」还是「标记是空」）。
 *
 * 为什么必须 Robolectric：[SPUtil] 走 lib_common 的静态 `BaseApplication.context`，
 * 纯 JVM 起不来。
 *
 * SP 文件名是实现内私有常量，此处按字面量钉死（与 `AndroidUserSessionManagerTest` 同法）：
 * 改实现里的文件名必须同步改这里，否则用例会在一片「干净」的 SP 上跑出假绿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderResumeStoreTest {

    @Before
    fun setUp() {
        val application: Application = ApplicationProvider.getApplicationContext()
        // 生产由 BaseApplication.onCreate 赋值；Robolectric 装的是裸 Application，这里手动补齐，
        // 等价于「Application 已启动完成」
        BaseApplication.context = application
        // Robolectric 的 sandbox ClassLoader 在同一 @Config 下被复用，SPUtil 是 Kotlin object、
        // 其 spMap 静态缓存可能跨用例存活；每个用例显式清空本对象专用的 SP，保证起点干净
        SPUtil.clear(SP_NAME)
    }

    @Test
    fun `未标记时读取返回 null`() {
        assertNull(ReaderResumeStore.pendingNoteUrl())
    }

    @Test
    fun `标记后读取返回该书 noteUrl`() {
        ReaderResumeStore.markReading(NOTE_URL)

        assertEquals(NOTE_URL, ReaderResumeStore.pendingNoteUrl())
    }

    @Test
    fun `清除后读取回到 null`() {
        ReaderResumeStore.markReading(NOTE_URL)

        ReaderResumeStore.clear()

        assertNull(ReaderResumeStore.pendingNoteUrl())
    }

    @Test
    fun `再次标记覆盖前一本（换源后恢复必须指向新条目）`() {
        ReaderResumeStore.markReading(OLD_NOTE_URL)

        ReaderResumeStore.markReading(NOTE_URL)

        assertEquals(NOTE_URL, ReaderResumeStore.pendingNoteUrl())
    }

    @Test
    fun `空白 noteUrl 不写入标记`() {
        ReaderResumeStore.markReading("")

        assertNull(ReaderResumeStore.pendingNoteUrl())
    }

    private companion object {
        /** `ReaderResumeStore` 内私有常量 `SP_NAME` 的同名字面量（见类 KDoc 的钉死说明） */
        const val SP_NAME = "reader_resume"

        const val NOTE_URL = "https://example.com/book/1"
        const val OLD_NOTE_URL = "https://old.example.com/book/1"
    }
}
