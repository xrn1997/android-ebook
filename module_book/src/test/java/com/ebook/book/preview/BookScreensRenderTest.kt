package com.ebook.book.preview

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.ebook.book.BookCommentsContent
import com.ebook.book.DownloadCenterContent
import com.ebook.book.DownloadManageScreen
import com.ebook.book.R
import com.ebook.book.mvvm.viewmodel.BookSelectionState
import com.ebook.book.mvvm.viewmodel.CandidateProgress
import com.ebook.book.mvvm.viewmodel.DownloadCenterStep
import com.ebook.book.previewDownloadGroups
import com.ebook.book.reader.LightPanelContent
import com.ebook.book.reader.SourceSwitchSheetContent
import com.ebook.book.reader.SwitchFailureHint
import com.ebook.book.reader.previewSwitchCandidates
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.SAMPLE_CURRENT_USER_ID
import com.ebook.common.ui.preview.SAMPLE_OTHER_USER_ID
import com.ebook.common.ui.preview.sampleComment
import com.ebook.common.ui.preview.sampleComments
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 本页新抽出的**无状态根**的渲染冒烟。
 *
 * 这些屏幕刚被收成「只吃不可变状态 + 回调」，于是它们本来就能被预览；这一层把预览组合的同一组
 * 状态在 JVM 上再跑一遍，抓的是只在渲染期才露面的三类错：
 * - 取值**顺序/优先级**错（本人判定、失败句的「本地提示盖住整轮失败」），错了不崩，只是说错话；
 * - 整块**该出现/该消失**的分支写反（暂停胶囊、换源进行中的转圈、删除确认框），编译与单测都不看；
 * - 组件根本**组合不出来**（亮度面板的自绘滑条要实测宽度、下载中心二级要 BackgroundHandler）。
 *
 * 样例一律来自 `PreviewSamples` 与页面自己的 `previewXxx()`（与预览面板同一份），两边各写必然漂移。
 * 一个用例只有一次 `setContent`：多档变体一律用 `mutableStateOf` 在同一次组合里翻，
 * 重复 setContent 会直接抛。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BookScreensRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 评论列表同时画出本人与他人两条，并保留底部输入栏的占位句。
     *
     * 判据取用户名而不是内容：内容会被排版换行、用户名是唯一每行都不同的短串。
     * 「说点什么吧」在不在看的是另一件事——列表占主体、输入栏固定底部，输入栏被列表挤掉
     * 时页面不报错，只是整页没有发送出口。
     */
    @Test
    fun `评论页画出本人与他人两条评论与输入栏占位`() {
        composeRule.setContent {
            AppPreview {
                BookCommentsContent(
                    comments = sampleComments(2),
                    currentUserId = SAMPLE_CURRENT_USER_ID,
                    isRefreshing = false,
                    isLoadingMore = false,
                    hasMore = true,
                    loadMoreFailed = false,
                    inputText = "",
                    onRefresh = {},
                    onLoadMore = {},
                    onTextChange = {},
                    onSend = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("临渊读者").assertIsDisplayed()
        composeRule.onNodeWithText("北岸观星").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.say_something))
            .assertIsDisplayed()
    }

    /**
     * 长按删除入口只认 userId：同一个会话切「本人 / 他人」，同一条评论两种结果。
     *
     * 这条锁的正是搬到壳层之前最容易写错的一格——判定用的是 userId，不是展示名；
     * 按展示名比对的实现会让「设过昵称的用户」永久删不掉自己的评论，而页面不报错。
     * 两次长按共用一次 setContent，翻的是 `currentUserId` 这一个值。
     */
    @Test
    fun `长按本人评论出删除确认而长按他人评论不出`() {
        val comment = sampleComment(index = 1, userId = SAMPLE_CURRENT_USER_ID)
        var currentUserId by mutableStateOf(SAMPLE_CURRENT_USER_ID)
        composeRule.setContent {
            AppPreview {
                BookCommentsContent(
                    comments = listOf(comment),
                    currentUserId = currentUserId,
                    isRefreshing = false,
                    isLoadingMore = false,
                    hasMore = true,
                    loadMoreFailed = false,
                    inputText = "",
                    onRefresh = {},
                    onLoadMore = {},
                    onTextChange = {},
                    onSend = {},
                    onDelete = {},
                )
            }
        }
        val dialogText = composeRule.activity.getString(R.string.tv_pop_delete_comment)

        composeRule.onNodeWithText(comment.content.orEmpty()).performTouchInput { longClick() }
        composeRule.onNodeWithText(dialogText).assertIsDisplayed()

        // 关掉确认框，再把会话换成「另一个人」，同一条评论长按就该什么都不出
        composeRule.onNodeWithText(composeRule.activity.getString(com.ebook.common.R.string.cancel))
            .performClick()
        composeRule.onNodeWithText(dialogText).assertIsNotDisplayed()

        currentUserId = SAMPLE_OTHER_USER_ID
        composeRule.waitForIdle()
        composeRule.onNodeWithText(comment.content.orEmpty()).performTouchInput { longClick() }
        composeRule.onNodeWithText(dialogText).assertIsNotDisplayed()
    }

    /**
     * 输入栏的两档：空值时只有 placeholder，打字后 placeholder 让位给内容。
     *
     * 这条防的是「发送读的不是这里显示的那串」：placeholder 与内容同屏出现，就说明
     * `value` 接的是别的状态——发送出去的是空串或另一句话，页面看着是对的。
     */
    @Test
    fun `评论输入框空值给占位、有值给内容`() {
        var inputText by mutableStateOf("")
        composeRule.setContent {
            AppPreview {
                BookCommentsContent(
                    comments = emptyList(),
                    currentUserId = SAMPLE_CURRENT_USER_ID,
                    isRefreshing = false,
                    isLoadingMore = false,
                    hasMore = true,
                    loadMoreFailed = false,
                    inputText = inputText,
                    onRefresh = {},
                    onLoadMore = {},
                    onTextChange = { inputText = it },
                    onSend = {},
                    onDelete = {},
                )
            }
        }
        val placeholder = composeRule.activity.getString(R.string.say_something)
        composeRule.onNodeWithText(placeholder).assertIsDisplayed()

        inputText = "这一章的伏笔埋在最后一句。"
        composeRule.waitForIdle()
        composeRule.onNodeWithText(inputText).assertIsDisplayed()
        composeRule.onNodeWithText(placeholder).assertIsNotDisplayed()
    }

    /**
     * 下载一级：四档书行各自的判据都落在同一屏上（与预览吃同一份 [previewDownloadGroups]）。
     *
     * 三个都是「少一个判据不报错、只多画或少画一块」的形态：
     * - 「正在下载」那行只在 `activeChapter != null` 时出现，且章号 **+1**（库里是 0 基章序号，
     *   所以下标的 93 要上屏成「第 94 章」——不 +1 就永远说慢一章）；
     * - 「已暂停」胶囊只在 `paused` 时出现；
     * - 元信息行是两个进度口径合并的那一句（覆盖率 + 队列剩余），断言按子串取，
     *   整句比对会把格式化串的每一处改动都变成测试噪声。
     */
    @Test
    fun `下载一级画出四个书行的三种进行态与两个进度口径`() {
        composeRule.setContent {
            AppPreview {
                DownloadManageScreen(groups = previewDownloadGroups(), onOpenBook = {})
            }
        }

        composeRule.onNodeWithText("山海拾遗").assertIsDisplayed()
        composeRule.onNodeWithText("已缓存 92/128", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("正在下载 第 94 章", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_manage_paused_tag))
            .assertIsDisplayed()
    }

    /**
     * 下载一级的空态：唯一教「下载从哪里发起」的位置。
     *
     * 只断言主文案会漏掉引导语——那一句才是新用户找得到动作入口的理由，
     * 少了它这页就只剩「暂无任务」，用户对着白页猜。
     */
    @Test
    fun `下载一级空态画出引导语`() {
        composeRule.setContent {
            AppPreview {
                DownloadManageScreen(groups = emptyList(), onOpenBook = {})
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_manage_no_task))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_manage_empty_hint))
            .assertIsDisplayed()
    }

    /**
     * 两级编排的根组件：一级画列表、二级在没有结论时画加载态。
     *
     * `bookSheet` 为 null（VM 还没回答）时二级必须落 [BookSelectionState.Loading]，而不是白屏——
     * 这一格是本次抽根组件时经手的那个 `?:` 表达式，写坏了不报错、只是整屏空白。
     * 顺带锁住 [DownloadCenterContent] 能组合出来：它带 BackHandler，预览面板里没有可绑的
     * dispatcher，只有这里（ComponentActivity 提供）能验。
     */
    @Test
    fun `下载中心一级与二级之间切换都能组合`() {
        var step by mutableStateOf<DownloadCenterStep>(DownloadCenterStep.Books)
        composeRule.setContent {
            AppPreview {
                DownloadCenterContent(
                    step = step,
                    groups = previewDownloadGroups(),
                    bookSheet = null,
                    isDirectFromReader = false,
                    onBackToBooks = {},
                    onFinish = {},
                    onOpenBook = {},
                    onConfirmDownload = {},
                    onPauseBook = {},
                    onResumeBook = {},
                    onCancelBook = {},
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("山海拾遗").assertIsDisplayed()

        step = DownloadCenterStep.PickBook(noteUrl = "note", tag = "tag", focusChapter = -1)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_center_loading))
            .assertIsDisplayed()
    }

    /**
     * 二级装载失败那一档要给「重试」按钮，而不只是一句「加载失败」。
     *
     * 与「该书不在书架」是两件事：后者没有可重试的动作，把它并进失败态就等于把
     * 「这本书被移出书架了」说成「网络不好，再试一次」。
     */
    @Test
    fun `下载二级装载失败画出重试而不是一句提示`() {
        composeRule.setContent {
            AppPreview {
                DownloadCenterContent(
                    step = DownloadCenterStep.PickBook(noteUrl = "note", tag = "tag", focusChapter = -1),
                    groups = emptyList(),
                    bookSheet = BookSelectionState.Failed,
                    isDirectFromReader = false,
                    onBackToBooks = {},
                    onFinish = {},
                    onOpenBook = {},
                    onConfirmDownload = {},
                    onPauseBook = {},
                    onResumeBook = {},
                    onCancelBook = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.load_failed))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.retry)).assertIsDisplayed()
    }

    /**
     * 亮度面板：跟随系统说「自动」且滑条不可调，手动态说当前档位。
     *
     * 两条分支共用同一个数值位，写死任一条都不报错：跟随系统时还挂着数字，用户就会去拖
     * 一根不动的滑条。手动态打的是 0~255 那个整数（原实现口径），所以断言取「128」。
     */
    @Test
    fun `亮度面板跟随系统说自动、手动态说当前档位`() {
        var followSystem by mutableStateOf(true)
        composeRule.setContent {
            AppPreview {
                LightPanelContent(
                    brightness = 128,
                    followSystem = followSystem,
                    onBrightnessChange = {},
                    onSystemBrightnessRestore = {},
                    onFollowSystemChange = {},
                )
            }
        }
        val auto = composeRule.activity.getString(R.string.brightness_auto)
        composeRule.onNodeWithText(auto).assertIsDisplayed()

        followSystem = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText(auto).assertIsNotDisplayed()
        composeRule.onNodeWithText("128", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.reader_current_brightness))
            .assertIsDisplayed()
    }

    /**
     * 换源候选行的三档匹配度标签同屏：只有「完全匹配」提亮到主色容器。
     *
     * 三句都在，说明分数→档位的折叠（`matchBadgeOf`）与候选行都接上了；
     * 档位的配色本身由像素级测试负责（ReaderChromeThemeRenderTest 同套办法）。
     */
    @Test
    fun `换源候选画出三档匹配度标签`() {
        composeRule.setContent {
            AppPreview {
                SourceSwitchSheetContent(
                    bookName = "山海拾遗",
                    candidates = previewSwitchCandidates(),
                    progress = CandidateProgress(),
                    isSearching = false,
                    failureHint = null,
                    pendingUrl = null,
                    onSwitch = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.source_switch_badge_exact))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.source_switch_badge_partial))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.source_switch_badge_author))
            .assertIsDisplayed()
    }

    /**
     * 一笔换源在跑时：那一行有反馈，整列表点不动。
     *
     * 两半都要有——只置灰没反馈像面板卡死（用户会反复点），只给转圈不置灰就会出现
     * 「后点的先完成、界面切到不是最后选的那本」。这里翻的是 `pendingUrl`，
     * 它同时是闸门判据与转圈所在行的依据，所以一次翻值把两件事一起锁住。
     */
    @Test
    fun `换源进行中那一行有反馈且整列表点不动`() {
        val rows = previewSwitchCandidates()
        val firstBookName = rows.first().book.name
        var pendingUrl by mutableStateOf<String?>(rows[1].book.noteUrl)
        var switched: String? by mutableStateOf(null)
        composeRule.setContent {
            AppPreview {
                SourceSwitchSheetContent(
                    bookName = "山海拾遗",
                    candidates = rows,
                    progress = CandidateProgress(),
                    isSearching = false,
                    failureHint = null,
                    pendingUrl = pendingUrl,
                    onSwitch = { switched = it.noteUrl },
                )
            }
        }
        val applyingText = composeRule.activity.getString(R.string.source_switch_applying)
        composeRule.onNodeWithText(applyingText).assertIsDisplayed()

        composeRule.onNodeWithText(firstBookName).performClick()
        assertFalse("有一笔换源在跑时列表仍被点动了", switched != null)

        pendingUrl = null
        composeRule.waitForIdle()
        composeRule.onNodeWithText(applyingText).assertIsNotDisplayed()
        composeRule.onNodeWithText(firstBookName).performClick()
        assertTrue("闸门放开后点候选没有换源动作", switched == rows.first().book.noteUrl)
    }

    /**
     * 四种失败各说各的一句话，且候选列表仍然在。
     *
     * 这四档的动作不一样（另选一本 / 去读架上那本 / 换个源再试 / 关掉面板重试），
     * 并成一句就会指错路；而「书架上的书未受影响」这类话在整轮搜索失败时答非所问。
     * 失败时候选仍可用、弹层不关，所以每档都该同时看得见那一句和列表第一行。
     */
    @Test
    fun `换源四种失败各说一句且候选仍可用`() {
        var hint by mutableStateOf<SwitchFailureHint?>(null)
        composeRule.setContent {
            AppPreview {
                SourceSwitchSheetContent(
                    bookName = "山海拾遗",
                    candidates = previewSwitchCandidates(),
                    progress = CandidateProgress(),
                    isSearching = false,
                    failureHint = hint,
                    pendingUrl = null,
                    onSwitch = {},
                )
            }
        }
        val firstBookName = previewSwitchCandidates().first().book.name
        val texts = mapOf(
            SwitchFailureHint.SourceInvalid to R.string.source_switch_target_invalid,
            SwitchFailureHint.AlreadyOnShelf to R.string.source_switch_target_on_shelf,
            SwitchFailureHint.Generic to R.string.source_switch_failed,
            SwitchFailureHint.SearchFailed to R.string.source_switch_search_failed,
        )
        texts.forEach { (kind, textRes) ->
            hint = kind
            composeRule.waitForIdle()
            composeRule.onNodeWithText(composeRule.activity.getString(textRes)).assertIsDisplayed()
            composeRule.onNodeWithText(firstBookName).assertIsDisplayed()
        }

        hint = null
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.source_switch_failed))
            .assertIsNotDisplayed()
    }

    /**
     * 候选区的两种占位行：还在搜 → 加载；搜完什么都没有 → 「没有找到」。
     *
     * 说反了的代价不对称：把「还在搜」说成「没有找到」，用户关掉面板就不再等（结果马上就到）；
     * 反过来则是让他对着一行转圈一直等一笔已经结束的搜索。
     */
    @Test
    fun `换源无候选时搜索中与搜完各说一句`() {
        var isSearching by mutableStateOf(true)
        composeRule.setContent {
            AppPreview {
                SourceSwitchSheetContent(
                    bookName = "山海拾遗",
                    candidates = emptyList(),
                    progress = CandidateProgress(),
                    isSearching = isSearching,
                    failureHint = null,
                    pendingUrl = null,
                    onSwitch = {},
                )
            }
        }
        val loading = composeRule.activity.getString(R.string.loading)
        val none = composeRule.activity.getString(R.string.source_switch_no_candidates)
        composeRule.onNodeWithText(loading).assertIsDisplayed()

        isSearching = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText(loading).assertIsNotDisplayed()
        composeRule.onNodeWithText(none).assertIsDisplayed()
    }

    /**
     * 副标题在书名取不到时落「未知书籍」占位。
     *
     * 这一句交代的是「在为哪本书找源」，空书名时画成「正在为《》查找」不报错、只是整句没有意义，
     * 而这条链路上书名确实可能取不到（缺元信息的行）。断言按子串取，整句格式化串不参与比对。
     */
    @Test
    fun `换源副标题在书名为空时落未知书籍占位`() {
        composeRule.setContent {
            AppPreview {
                SourceSwitchSheetContent(
                    bookName = "",
                    candidates = previewSwitchCandidates(),
                    progress = CandidateProgress(),
                    isSearching = false,
                    failureHint = null,
                    pendingUrl = null,
                    onSwitch = {},
                )
            }
        }

        composeRule.onNodeWithText(
            text = "正在为《${composeRule.activity.getString(R.string.unknown_book)}》",
            substring = true,
        ).assertIsDisplayed()
    }
}
