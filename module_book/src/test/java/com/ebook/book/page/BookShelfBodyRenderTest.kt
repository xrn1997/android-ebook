package com.ebook.book.page

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ebook.book.R
import com.ebook.common.importer.ParsingBook
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.sampleShelfBooks
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 书架列表区无状态根 [BookShelfBody] 的渲染回归。
 *
 * 抓的是只在**首帧**才显形的那一类错：`list` 的初值恒为空表，若在首轮刷新落定前就按"空"画，
 * 有书用户冷启动会先闪出一帧「书架还是空的 / 去书城挑一本」的误导文案——不崩、不报错、
 * 其余单测也全绿，只有真渲染才判得出来。反向的错（守卫写成"永远不画空态"）同样只在这里能拦。
 *
 * 样例取自 `PreviewSamples`（与预览面板同一份），一个用例只有一次 `setContent`：
 * 多档变体一律用 `mutableStateOf` 在同一次组合里翻，重复 `setContent` 会直接抛。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BookShelfBodyRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val emptyTitle: String get() = composeRule.activity.getString(R.string.shelf_empty_title)
    private val emptyAction: String get() = composeRule.activity.getString(R.string.shelf_empty_action)

    /**
     * 时点判据：同一组合里把 `initialLoadDone` 从 false 翻到 true，空态必须**先无后有**。
     *
     * 两条分向都在这一条里锁住——只锁"落定后有"会漏掉首帧闪帧，只锁"落定前没有"则会把
     * 守卫写成"永远不画空态"也照样绿。
     */
    @Test
    fun `首轮刷新落定前不画空态、落定后才画`() {
        val loaded = mutableStateOf(false)
        composeRule.setContent {
            AppPreview {
                BookShelfBody(
                    listState = rememberLazyListState(),
                    books = emptyList(),
                    parsingBooks = emptyList(),
                    initialLoadDone = loaded.value,
                    onGoBookstore = {},
                    onItemClick = {},
                    onItemLongClick = {},
                )
            }
        }

        composeRule.onNodeWithText(emptyTitle).assertDoesNotExist()

        loaded.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
        composeRule.onNodeWithText(emptyAction).assertIsDisplayed()
    }

    /**
     * 宿主没有书城 Tab 时（模块独立运行的调试宿主传 `null`）动作整块不渲染：
     * 留一句「去书城找书」却点不动，比什么都不说更糟。
     */
    @Test
    fun `拿不到宿主能力时空态不画动作`() {
        composeRule.setContent {
            AppPreview {
                BookShelfBody(
                    listState = rememberLazyListState(),
                    books = emptyList(),
                    parsingBooks = emptyList(),
                    initialLoadDone = true,
                    onGoBookstore = null,
                    onItemClick = {},
                    onItemLongClick = {},
                )
            }
        }

        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
        composeRule.onNodeWithText(emptyAction).assertDoesNotExist()
    }

    /**
     * 只剩"解析中"占位行时画的是那几行，不是空态文案——导入落库的那几秒是用户唯一在看的反馈，
     * 这时说「书架还是空的」与屏幕上那行「解析中…」自相矛盾。
     */
    @Test
    fun `只剩解析中占位行时不画空态`() {
        composeRule.setContent {
            AppPreview {
                BookShelfBody(
                    listState = rememberLazyListState(),
                    books = emptyList(),
                    parsingBooks = listOf(ParsingBook(id = "parsing-1", title = "雾都手记")),
                    initialLoadDone = true,
                    onGoBookstore = {},
                    onItemClick = {},
                    onItemLongClick = {},
                )
            }
        }

        composeRule.onNodeWithText(emptyTitle).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.shelf_parsing, "雾都手记"))
            .assertIsDisplayed()
    }

    /**
     * 守卫只管**空**那一支：书目到位时即便首轮还没落定也要照画列表，
     * 否则守卫就成了"冷启动藏书架"。
     */
    @Test
    fun `有书目时即便首轮未落定也画列表`() {
        val book = sampleShelfBooks(1).first()
        composeRule.setContent {
            AppPreview {
                BookShelfBody(
                    listState = rememberLazyListState(),
                    books = listOf(book),
                    parsingBooks = emptyList(),
                    initialLoadDone = false,
                    onGoBookstore = {},
                    onItemClick = {},
                    onItemLongClick = {},
                )
            }
        }

        composeRule.onNodeWithText(book.bookInfo?.name.orEmpty()).assertIsDisplayed()
        composeRule.onNodeWithText(emptyTitle).assertDoesNotExist()
    }
}
