package com.ebook.find.booksource

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ScriptSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.SAMPLE_SOURCE_URL
import com.ebook.common.ui.preview.sampleNativeSource
import com.ebook.common.ui.preview.sampleScriptSource
import com.ebook.find.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 书源管理页的渲染冒烟（随页面从 module_me 迁入）。
 *
 * 防的是三类不崩、不报错、只在渲染时显形的错：
 * - 汇总行的两个数没有跟着清单走（漏算脚本行或禁用项）；
 * - 脚本行与原生行长得几乎一样，出身标记没画出来；
 * - 导入预览把校验不通过的条目也算进「确认导入 N 条」。
 *
 * 样例一律取共享门面的 `sampleNativeSource()` / `sampleScriptSource()`，断言的字符串
 * 因此与预览同源，不会「测试绿着、预览是坏的」。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BookSourceManageScreenRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `书源管理页渲染出原生与脚本两种出身的行与汇总数`() {
        composeRule.setContent {
            AppPreview {
                BookSourceManageScreen(
                    state = mixedSourceState(),
                    onImportClick = {},
                    onExportAllClick = {},
                    onSetDefault = {},
                    onToggleEnabled = { _, _ -> },
                    onExportSource = {},
                    onDelete = {},
                    onConfirmImport = {},
                    onDismissPreview = {},
                )
            }
        }

        // 汇总行的两个数都来自清单本身（含脚本行、含禁用项），故整句可以精确断言
        composeRule.onNodeWithText("共 3 个书源，已启用 2 个").assertIsDisplayed()
        composeRule.onNodeWithText(sampleNativeSource().rule.name).assertIsDisplayed()
        // 脚本行与原生行「长得几乎一样」，全靠名字旁那个出身标记区分
        composeRule.onNodeWithText(sampleScriptSource().name).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.book_source_script_badge))
            .assertIsDisplayed()
    }

    @Test
    fun `书源管理页空清单进引导态而不是空白列表`() {
        composeRule.setContent {
            AppPreview {
                BookSourceManageScreen(
                    state = BookSourceViewModel.BookSourcePageState(),
                    onImportClick = {},
                    onExportAllClick = {},
                    onSetDefault = {},
                    onToggleEnabled = { _, _ -> },
                    onExportSource = {},
                    onDelete = {},
                    onConfirmImport = {},
                    onDismissPreview = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.book_source_empty_title))
            .assertIsDisplayed()
    }

    /**
     * 导入预览弹层：通过的、带警示的、被拒的一起列出，且「确认导入 N 条」的 N 只数通过的。
     *
     * 三条样例里恰好一条被拒，所以按钮文案必须是「确认导入 2 条」——
     * 这个数写成 `items.size` 不崩不报错，只是把导不进去的条目算进了承诺里。
     */
    @Test
    fun `书源管理页导入预览只数校验通过的条目`() {
        composeRule.setContent {
            AppPreview {
                BookSourceManageScreen(
                    state = BookSourceViewModel.BookSourcePageState(
                        sources = listOf(nativeRow()),
                        defaultSourceUrl = SAMPLE_SOURCE_URL,
                        preview = importPreviewItems(),
                    ),
                    onImportClick = {},
                    onExportAllClick = {},
                    onSetDefault = {},
                    onToggleEnabled = { _, _ -> },
                    onExportSource = {},
                    onDelete = {},
                    onConfirmImport = {},
                    onDismissPreview = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.book_source_preview_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText("确认导入 2 条").assertIsDisplayed()
        // 跨出身覆盖要说清是哪种出身被换掉（同 URL 从规则源翻成脚本书源，参与方式整片变）
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.book_source_will_overwrite_native)
        ).assertIsDisplayed()
    }

    private fun nativeRow(): BookSourceItem = BookSourceItem(
        rule = BookSourceRule(
            name = sampleNativeSource().rule.name,
            url = SAMPLE_SOURCE_URL,
            enabled = true,
        ),
    )

    /** 三行清单：原生启用（非默认）+ 脚本启用（默认源）+ 原生禁用，覆盖三种行长相 */
    private fun mixedSourceState(): BookSourceViewModel.BookSourcePageState =
        BookSourceViewModel.BookSourcePageState(
            sources = listOf(
                nativeRow(),
                BookSourceItem(
                    rule = BookSourceRule(
                        name = sampleScriptSource().name,
                        url = sampleScriptSource().url,
                        enabled = true,
                    ),
                    format = SourceFormat.SCRIPT,
                ),
                BookSourceItem(
                    rule = BookSourceRule(
                        name = "已禁用的样例源",
                        url = "$SAMPLE_SOURCE_URL/disabled",
                        enabled = false,
                    ),
                ),
            ),
            defaultSourceUrl = sampleScriptSource().url,
        )

    /** 三条预览项：一条通过、一条通过但带警示且将覆盖一条原生行、一条被拒 */
    private fun importPreviewItems(): List<ImportPreviewItem> = listOf(
        ImportPreviewItem(
            format = SourceFormat.NATIVE,
            nativeRule = sampleNativeSource().rule,
            validation = ValidationResult.Valid,
            willOverwrite = false,
        ),
        ImportPreviewItem(
            format = SourceFormat.SCRIPT,
            scriptRule = ScriptSourceRule(
                bookSourceName = "含脚本的社区源",
                bookSourceUrl = "$SAMPLE_SOURCE_URL/script",
            ),
            scriptWarnings = listOf(ScriptWarning.HAS_EXECUTABLE_CODE),
            validation = ValidationResult.Valid,
            willOverwrite = true,
            overwriteFormat = SourceFormat.NATIVE,
        ),
        ImportPreviewItem(
            format = SourceFormat.NATIVE,
            nativeRule = BookSourceRule(name = "", url = "biquge.example.com"),
            validation = ValidationResult.Invalid(listOf(ValidationReason.NAME_BLANK)),
            willOverwrite = false,
        ),
    )
}
