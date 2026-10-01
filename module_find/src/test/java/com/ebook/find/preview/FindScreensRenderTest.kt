package com.ebook.find.preview

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.sampleSearchBook
import com.ebook.find.view.SearchBookItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 找书条目卡在**两种页面形态**下的渲染冒烟。
 *
 * 为什么这一层要在 JVM 上再跑一遍预览画过的东西：`module_find` 里能预览的根只剩 private 组件
 * （书城内容区、切换胶囊、搜索栏、历史面板都收在各自文件的 private 函数里，测试源集看不见），
 * 而唯一 public 的条目卡恰恰是**两个页面共用、只差一个开关**的那个——`ChoiceBookActivity`
 * 的根是 Activity 的 `PageContent()`（自己 collect `ChoiceBookViewModel.list`），结构上预览不了，
 * 于是那一页的条目形态只能由这里锁住。
 *
 * 断言的不是"崩不崩"，而是**画出来但内容是错的**那两类静默形态：
 * `showOrigin` 写反既不报错也不闪退，只是分类页每行右侧多出一个相同站名（或搜索页丢掉
 * 「这条是谁家的」这一判据，聚合搜索下更糟）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class FindScreensRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 分类选书形态（`showOrigin = false`）：书名照画，书源名一个像素都不该出现。
     *
     * 那一页整段会话锁定导航参数带进来的那一个源，每条 `origin` 都是同一个名字，
     * 摆出来只是重复噪声——所以这一条断言的是「噪声确实被挡在参数这一层」，而不是布局。
     */
    @Test
    fun `分类选书形态不画书源名`() {
        val book = sampleSearchBook(index = 1)

        composeRule.setContent {
            AppPreview {
                SearchBookItem(
                    searchBook = book,
                    onItemClick = {},
                    onAddShelf = {},
                    showOrigin = false,
                )
            }
        }

        composeRule.onNodeWithText(book.name).assertIsDisplayed()
        composeRule.onNodeWithText(book.origin).assertDoesNotExist()
    }

    /**
     * 搜索形态（默认 `showOrigin = true`）：书源名必须画出来。
     *
     * 与上一条是一对：聚合搜索（一份列表混着多个站的条目）里 `origin` 是「这条是谁家的」的
     * 唯一判据，它由解析器写入。默认值如果被改成 false，搜索页不报错、只是再也分不出来源，
     * 而分类页照样是对的——只看分类页那张图发现不了。
     */
    @Test
    fun `搜索形态画出书源名以便分辨聚合结果的家`() {
        val book = sampleSearchBook(index = 1)

        composeRule.setContent {
            AppPreview {
                SearchBookItem(
                    searchBook = book,
                    onItemClick = {},
                    onAddShelf = {},
                )
            }
        }

        composeRule.onNodeWithText(book.origin).assertIsDisplayed()
    }

    /**
     * 分类选书形态的三种信息条数都组合得出来，且无简介时中间行回落**最新章节**。
     *
     * 书源被关掉之后，底行只剩作者一列；此时「作者也为空」那一档会把有效信息数压到两行，
     * 走条目卡的两行版式（第二行在剩下的地方垂直居中）。这个分支只在 `showOrigin = false` 时才可能被真实数据
     * 触发（搜索形态下 origin 恒在），所以它必须有自己的一张图与一条断言——回落文案出现在中间行，
     * 说明 `listBlurb` 的取值顺序在两种版式里都没写反。
     */
    @Test
    fun `分类选书形态三种条数都组合得出来并回落最新章节`() {
        val withoutBlurb = sampleSearchBook(index = 2, desc = "")

        composeRule.setContent {
            AppPreview {
                Column {
                    SearchBookItem(
                        searchBook = sampleSearchBook(index = 1),
                        onItemClick = {},
                        onAddShelf = {},
                        showOrigin = false,
                    )
                    SearchBookItem(
                        searchBook = withoutBlurb,
                        onItemClick = {},
                        onAddShelf = {},
                        showOrigin = false,
                    )
                    SearchBookItem(
                        searchBook = sampleSearchBook(index = 3, desc = "", author = ""),
                        onItemClick = {},
                        onAddShelf = {},
                        showOrigin = false,
                    )
                }
            }
        }

        composeRule.onNodeWithText(withoutBlurb.lastChapter, substring = true).assertIsDisplayed()
    }
}
