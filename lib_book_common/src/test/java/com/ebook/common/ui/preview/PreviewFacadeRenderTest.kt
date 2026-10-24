package com.ebook.common.ui.preview

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.EmptyState
import com.ebook.common.ui.InfoChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 预览门面的渲染回归。
 *
 * 防的是这样一类失败：**预览函数编译得过、样例也编得出来，但组合到一半抛异常或画成半截**，
 * 而 Android Studio 里只留一行看不出根因的报错。这一层用 Robolectric 在 JVM 上真组合一次，
 * 把"渲染得出来"变成可断言的事实，而不是等人打开预览面板才发现。
 *
 * 用例组合的是**与预览同一个组件、同一份样例**（样例只能从门面取，见 `PreviewSamples`）：
 * 两边各造一份数据就会漂移，测试绿着而预览却是坏的。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PreviewFacadeRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `样例书架条目把渲染要用的三级嵌套填满`() {
        val book = sampleShelfBook()

        // 书架行自己不带书名与封面：这两样在 book_info 那层。缺了它，条目卡画出来是半截的
        assertNotNull("bookInfo 为空：条目卡只能画出空书名", book.bookInfo)
        assertEquals(book.noteUrl, book.bookInfo?.noteUrl)
        // 底行「第 N 章 …」取的是 chapterList 末尾，空列表会让这一格静默消失
        assertTrue("chapterList 为空：底行没有章节信息可显示", book.chapterList.isNotEmpty())
        // 多书源下 tag 是「按哪套规则解析」的唯一依据，样例里三处必须同源
        assertEquals(book.tag, book.bookInfo?.tag)
        assertEquals(book.tag, book.chapterList.first().tag)
        // 列表预览要逐行不同，否则看不出省略号与等高
        assertNotEquals(
            "两册样例书名相同，列表预览看不出长标题省略形态",
            sampleShelfBooks(2)[0].bookInfo?.name,
            sampleShelfBooks(2)[1].bookInfo?.name,
        )
    }

    @Test
    fun `共享组件吃门面样例能组合出来`() {
        val book = sampleShelfBook()
        composeRule.setContent {
            AppPreview {
                Column {
                    Text(book.bookInfo?.name.orEmpty())
                    // 封面在 JVM 渲染环境里必然取不到图，走的正是内置兜底那一档
                    BookCover(url = book.bookInfo?.coverUrl.orEmpty(), modifier = Modifier.size(57.dp))
                    InfoChip(text = book.bookInfo?.status.orEmpty())
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = "没有找到相关书籍",
                        hint = "换个词试试",
                        actionText = "去书城",
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("山海拾遗").assertIsDisplayed()
        composeRule.onNodeWithText("去书城").assertIsDisplayed()
    }

    /**
     * [AppPreview] 的深浅档确实换了一套调板。
     *
     * 判据是探针而不是像素：把开关翻一下，`MaterialTheme.colorScheme.primary` 必须变。
     * 如果门面把 `darkTheme` 写死成 false，预览里所有"深色态"都是假的，而编译与渲染都不报错。
     */
    @Test
    fun `门面的深浅档由入参翻转而非恒为浅色`() {
        val dark = mutableStateOf(false)
        var primaryInLight = Color.Unspecified
        var primaryInDark = Color.Unspecified

        composeRule.setContent {
            AppPreview(darkTheme = dark.value) {
                val primary = MaterialTheme.colorScheme.primary
                // 只记录、不在此处断言：组合可能重跑，断言留给测试主体
                if (dark.value) primaryInDark = primary else primaryInLight = primary
                Text(text = "探针")
            }
        }

        composeRule.waitForIdle()
        dark.value = true
        composeRule.waitForIdle()

        assertNotEquals("深浅两档主色相同：门面的 darkTheme 没起作用", primaryInLight, primaryInDark)
    }
}
