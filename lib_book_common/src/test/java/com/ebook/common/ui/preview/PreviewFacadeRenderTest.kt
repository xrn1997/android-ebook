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
 * 用例组合的是**与预览同一个组件、同一份样例**（样例只能从门面取，见 [PreviewSamples]）：
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

    /**
     * 导入判重样例的「本地 / 仅网络」两档只差一条本地命中。
     *
     * 处置框里「智能合并」那一档完全由 `matches.any { it.isLocal }` 决定，这是 module_book
     * 导入页唯一的分岔点：样例若把两档做成同一份，两张预览图就会画一样的东西，而
     * 「网络书命中却给出合并入口」这个静默错再也没有地方能看出来。
     * `ImportBookActivity` 的屏幕根都是 private（文件级私有，测试源集看不见），
     * 所以这条不变量只能钉在样例出口这一处。
     *
     * 命中项**整列**给出也是契约的一部分：同一 `comment_key` 下可能挂着多个条目，
     * 处置框只展示第一条就等于让用户在看不见后果的情况下按「覆盖」。
     */
    @Test
    fun `判重样例的本地与仅网络两档只差一条本地命中`() {
        val withLocal = sampleImportDuplicate(hasLocalMatch = true)
        val networkOnly = sampleImportDuplicate(hasLocalMatch = false)

        assertTrue(
            "「有本地命中」这一档没给出本地条目：处置框不会画「智能合并」",
            withLocal.matches.any { it.isLocal },
        )
        assertTrue(
            "「仅网络」这一档混进了本地命中：处置框会给出对网络书毫无意义的「智能合并」",
            networkOnly.matches.none { it.isLocal },
        )
        assertEquals(2, withLocal.matches.size)
        assertEquals(1, networkOnly.matches.size)
        // 标题句取 meta.title、命中行取 matches：两边书名必须同源，否则预览画的是两本书
        assertEquals(withLocal.meta.title, withLocal.matches.first().title)
        assertNotNull("待导入文件为空：处置框的正文句没有主语", networkOnly.file.name)
    }

    /**
     * 本地导入样例给的是**路径而非磁盘文件**，且逐行不同名。
     *
     * 名字重复会让列表预览看不出省略号与行高；而「不落磁盘」这条是刻意的（设计期渲染环境不该做 IO），
     * 于是体积那一格恒为 `0 B`——这里把它钉成契约，免得下次有人为了"看起来真一点"往预览里加写盘。
     */
    @Test
    fun `本地导入样例只给路径且逐行不同名`() {
        val files = sampleLocalBookFiles(3)

        assertEquals(3, files.size)
        assertEquals(
            "样例文件名重复，列表预览看不出省略号与逐行差异",
            files.size,
            files.map { it.name }.distinct().size,
        )
        // 断言取 `File.path` 而非 `absolutePath`：后者按**宿主**文件系统补全（Windows 上
        // `/storage/emulated/0/x.txt` 会变成 `D:\storage\emulated\0\x.txt`），而预览与非设备环境
        // 都跑在宿主机上——照 absolutePath 断言会因开发机平台不同而红。
        // 即便如此，Windows 的 File 仍会把分隔符归一成 `\`，故比较前先把分隔符归回 `/`。
        assertTrue(
            "样例路径不在存储根前缀下：条目的路径回落那一档永远不会出现",
            files.all { it.path.replace('\\', '/').startsWith(SAMPLE_LOCAL_STORAGE_ROOT) },
        )
        // 「只造路径、不落磁盘」的实证：这些文件在宿主机上确实不存在，所以体积那一格恒为 0 B
        assertTrue("样例文件竟然落在宿主机磁盘上：预览已经开始做 IO", files.none { it.exists() })
    }
}
