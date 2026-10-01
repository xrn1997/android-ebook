package com.ebook.find.view

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.sampleSearchBook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 找书条目卡的渲染回归——组合的是 `SearchBookItemPreview` 同一组样例、同一个组件。
 *
 * 为什么要在 JVM 上再跑一遍"预览已经画过的东西"：预览面板只有人打开才看得见，
 * 而这里的两条断言防的都不是崩溃，是**画得出来但内容是错的**——那种形态编译期、运行期都不报错。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SearchBookItemRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `四种中间行来源都组合得出来`() {
        composeRule.setContent {
            AppPreview {
                Column {
                    SearchBookItem(
                        searchBook = sampleSearchBook(index = 1),
                        onItemClick = {},
                        onAddShelf = {},
                    )
                    SearchBookItem(
                        searchBook = sampleSearchBook(index = 2, add = true),
                        onItemClick = {},
                        onAddShelf = {},
                    )
                    SearchBookItem(
                        searchBook = sampleSearchBook(index = 3, desc = ""),
                        onItemClick = {},
                        onAddShelf = {},
                    )
                    SearchBookItem(
                        searchBook = sampleSearchBook(index = 4, desc = "", lastChapter = ""),
                        onItemClick = {},
                        onAddShelf = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("山海拾遗").assertIsDisplayed()
    }

    /**
     * 简介与末章都有值时，中间行走**简介**。
     *
     * [listBlurb] 的取值顺序写反不会崩、也不会报错，只会让简介永远露不了面（第三方源的
     * `lastChapter` 几乎总有值）——这正是只能靠断言钉住的那类静默错。
     */
    @Test
    fun `简介与末章同时有值时中间行走简介`() {
        val book = sampleSearchBook(index = 1)

        composeRule.setContent {
            AppPreview {
                SearchBookItem(searchBook = book, onItemClick = {}, onAddShelf = {})
            }
        }

        composeRule.onNodeWithText(book.desc, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(book.lastChapter, substring = true).assertDoesNotExist()
    }
}
