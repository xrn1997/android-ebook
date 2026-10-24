package com.ebook.me.preview

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ebook.common.domain.BookComment
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.SAMPLE_SOURCE_URL
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.ProfileDisplayState
import com.ebook.me.view.ModifyInformationScreen
import com.ebook.me.view.ModifyNicknameScreen
import com.ebook.me.view.MyCommentScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `module_me` 其余「已无状态根」屏幕的渲染冒烟。
 *
 * 与 [MeScreensRenderTest] 同一份配方（Robolectric + `@GraphicsMode(NATIVE)` + 固定 `qualifiers`），
 * 差别只在覆盖面：那一类打的是首批四屏，这里补上后来才发现「根本来就只吃状态与回调」的页面
 * （我的评论 / 修改昵称 / 编辑资料）。锁的还是那两类不崩、不报错、只在渲染时显形的错：
 * 穷举分支走漏一态（空列表 vs 有列表、`showPassword` 两档），以及取值顺序写反导致的静默显示错内容。
 *
 * **本页没有覆盖的四个屏**：关于（`AboutScreen`）、协议（`DocScreen`）、开源许可（`LicensesScreen`）、
 * 头像裁剪（`ClipImageScreen`）——它们的屏幕根都是 `private`，测试源集看不见，
 * 只有 Android Studio 的预览面板出得了图。要进这一层就得先把根放开到 `public`（属签名改动，本轮不做）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class OtherMeScreensRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `我的评论页列表态渲染出书名与章节标记`() {
        composeRule.setContent {
            AppPreview {
                MyCommentScreen(
                    comments = listOf(previewComment()),
                    onCommentClick = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("山海拾遗").assertIsDisplayed()
        composeRule.onNodeWithText("第 12 章 北望").assertIsDisplayed()
    }

    /**
     * 空态走引导文案。
     *
     * 两态互斥（`if (comments.isEmpty())`），列表态的用例永远走不到这一支；
     * 而这一支的文案全部来自资源，故按 `getString` 断言、不写死中文。
     */
    @Test
    fun `我的评论页空态渲染引导文案而不是空列表`() {
        composeRule.setContent {
            AppPreview {
                MyCommentScreen(
                    comments = emptyList(),
                    onCommentClick = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.my_comment_empty_title))
            .assertIsDisplayed()
    }

    /**
     * 修改昵称页：参照行把「当前昵称」拼进资源模板。
     *
     * `onNodeWithText` 是**全串匹配**，而这一行是 `modify_nickname_current` 格式化出来的，
     * 所以断言按整句给（顺带把「模板 + 值」的拼接顺序钉住），比只断言 `临渊客` 更严。
     */
    @Test
    fun `修改昵称页渲染出当前昵称参照行`() {
        composeRule.setContent {
            AppPreview {
                ModifyNicknameScreen(currentNickname = "临渊客", onSubmit = {})
            }
        }

        composeRule.onNodeWithText("当前昵称：临渊客").assertIsDisplayed()
    }

    /**
     * 编辑资料页未集成 module_login 那一档：密码行连同其分隔线一起不出现。
     *
     * 只断言「昵称在」看不出 `showPassword` 有没有生效；`assertDoesNotExist` 才锁得住
     * 「独立运行不给一个跳不到的入口」。昵称是 `trailingText` 原样展示的，可精确匹配。
     */
    @Test
    fun `编辑资料页在未集成登录模块时不给修改密码行`() {
        composeRule.setContent {
            AppPreview {
                ModifyInformationScreen(
                    profileState = ProfileDisplayState(nickname = "临渊客", avatarUrl = ""),
                    onModifyPhotoClick = {},
                    onModifyPasswordClick = {},
                    onModifyNicknameClick = {},
                    showPassword = false,
                )
            }
        }

        composeRule.onNodeWithText("临渊客").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.modify_password))
            .assertDoesNotExist()
    }

    /** 密码行显示的对照档：同一条状态、`showPassword = true` 时那一行必须在 */
    @Test
    fun `编辑资料页集成登录模块时给出修改密码行`() {
        composeRule.setContent {
            AppPreview {
                ModifyInformationScreen(
                    profileState = ProfileDisplayState(nickname = "临渊客", avatarUrl = ""),
                    onModifyPhotoClick = {},
                    onModifyPasswordClick = {},
                    onModifyNicknameClick = {},
                    showPassword = true,
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.modify_password))
            .assertExists()
    }

    private fun previewComment(): BookComment = BookComment(
        id = 1L,
        userId = 7L,
        username = "reader@example.com",
        avatar = "",
        commentKey = "$SAMPLE_SOURCE_URL/book/1",
        chapterUrl = "$SAMPLE_SOURCE_URL/book/1/chapter/12",
        chapterName = "第 12 章 北望",
        bookName = "山海拾遗",
        content = "这一章把山海两地的对照写完了，伏笔埋在最后一句。",
        addTime = "2026-09-30 16:04:21",
    )
}
