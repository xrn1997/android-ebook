package com.ebook.login

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ebook.common.ui.preview.AppPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 认证域四张屏幕（登录 / 注册 / 验证身份 / 改密）的渲染冒烟。
 *
 * 四个根都收成了「不可变状态 + 回调」的无状态根，于是这一层能在 JVM 上组合**预览用的同一组状态**：
 * 预览面板只有人打开才看得见，而这里要抓的不是崩不崩，是**画得出来但内容是错的**那几类静默形态——
 *
 * - 发码按钮的禁用条件（倒计时期间必须撤走「获取验证码」）：写反了不报错，只是放任用户连点撞
 *   服务端 60 秒频控（A0241）；
 * - 改密页两种模式的旧密码栏有/无：写反了要么让重置用户去填一个他不知道的旧密码，
 *   要么让改密提交永远提示「密码未填写完整」；
 * - 登录页的预填邮箱：注册/重置成功都靠它把用户接住，取错 extra 键时页面照样渲染，只是永远空白。
 *
 * 校验文案（「邮箱不能为空」等）与提交在途不在这里断言：它们经 `sendToast` 与基类的 loading
 * 覆盖层给出，属 ViewModel 侧行为，不由屏幕状态承载。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class LoginScreensRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 登录页带着预填邮箱组合得出来，且两个次级入口都在。
     *
     * 预填邮箱是注册/重置成功跳回本页时唯一的接住手段（`LoginActivity` 经 intent extra 读它），
     * 「注册」「忘记密码」则分别是去 [RegisterActivity] 与 [VerifyUserActivity] 的出口。
     */
    @Test
    fun `登录页渲染出预填邮箱与两个次级入口`() {
        composeRule.setContent {
            AppPreview {
                LoginScreen(
                    email = "reader@example.com",
                    password = "mima123456",
                    onEmailChange = {},
                    onPasswordChange = {},
                    onLogin = {},
                    onRegisterClick = {},
                    onForgetPwdClick = {},
                )
            }
        }

        composeRule.onNodeWithText("reader@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("注册").assertIsDisplayed()
        composeRule.onNodeWithText("忘记密码").assertIsDisplayed()
    }

    /**
     * 空表单这一档也组合得出来（只断言「没抛」）。
     *
     * 冷启动第一帧就是它：两个框都空、label 落在输入框基线上、品牌标题区把首屏撑满。
     * 这一档没有来自入参的可断言文案，但它恰恰是最容易因间距/避让改动而崩掉的形态。
     */
    @Test
    fun `登录页空表单组合不抛`() {
        composeRule.setContent {
            AppPreview {
                LoginScreen(
                    email = "",
                    password = "",
                    onEmailChange = {},
                    onPasswordChange = {},
                    onLogin = {},
                    onRegisterClick = {},
                    onForgetPwdClick = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("请输入邮箱").assertIsDisplayed()
    }

    /**
     * 未倒计时时发码按钮是「获取验证码」。
     *
     * 与下一条是一对：这一档错了，用户根本找不到发码的入口。
     */
    @Test
    fun `注册页未倒计时显示可发码文案`() {
        composeRule.setContent {
            AppPreview {
                RegisterScreen(
                    countdownSeconds = 0,
                    onSendCode = {},
                    onRegister = { _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("获取验证码").assertIsDisplayed()
        composeRule.onNodeWithText("输入邮箱获取验证码，设置密码即可完成注册", substring = true)
            .assertIsDisplayed()
    }

    /**
     * 倒计时期间按钮文案换成剩余秒数，且**旧的「获取验证码」不再存在**。
     *
     * 后半句才是这条用例的价值：`AuthCodeField` 里 `enabled`/文案的判据若写反，页面上仍然是
     * 一颗能点的按钮、只是颜色淡一点，既不报错也不闪退，只有连着点才会撞上服务端频控。
     */
    @Test
    fun `注册页倒计时中撤走可发码文案`() {
        composeRule.setContent {
            AppPreview {
                RegisterScreen(
                    countdownSeconds = 37,
                    onSendCode = {},
                    onRegister = { _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("秒后重发", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("获取验证码").assertDoesNotExist()
    }

    /**
     * 验证身份页渲染出引导文案与「下一步」。
     *
     * 这一页是忘记密码流程的第一跳：`onNext` 携 email + 验证码跳 [ModifyPwdActivity] 的
     * RESET 模式，所以引导语与按钮必须同时在图上——少了引导语用户不知道验证码发去哪里。
     */
    @Test
    fun `验证身份页渲染出引导文案与下一步`() {
        composeRule.setContent {
            AppPreview {
                VerifyUserScreen(
                    countdownSeconds = 0,
                    onSendCode = {},
                    onNext = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("输入注册邮箱并获取验证码，验证通过后即可重置密码")
            .assertIsDisplayed()
        composeRule.onNodeWithText("下一步").assertIsDisplayed()
    }

    /**
     * 已登录改密模式：旧密码栏必须在。
     *
     * 少了这一栏，`ModifyPwdViewModel.modify` 收到的 oldPwd 恒为空，提交只会得到
     * 「密码未填写完整」，用户永远改不了密码。
     */
    @Test
    fun `改密页已登录模式出现旧密码栏`() {
        composeRule.setContent {
            AppPreview {
                ModifyPwdScreen(
                    isResetMode = false,
                    onModifyLogged = { _, _, _ -> },
                    onReset = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("请输入旧密码").assertIsDisplayed()
        composeRule.onNodeWithText("请输入新密码").assertIsDisplayed()
    }

    /**
     * 重置模式：旧密码栏必须**不存在**，且引导文案换成重置那一句。
     *
     * 这一档用户不知道旧密码（他正是因此才走忘记密码流程），摆一栏出来就是把人堵死；
     * 两条断言合起来才钉住「模式」这个入参真的路由到了文案与控件两侧。
     */
    @Test
    fun `改密页重置模式不出现旧密码栏`() {
        composeRule.setContent {
            AppPreview {
                ModifyPwdScreen(
                    isResetMode = true,
                    onModifyLogged = { _, _, _ -> },
                    onReset = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("请输入旧密码").assertDoesNotExist()
        composeRule.onNodeWithText("为账号设置新密码，完成后需重新登录").assertIsDisplayed()
    }
}
