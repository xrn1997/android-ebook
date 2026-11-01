package com.ebook.me.preview

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ebook.common.ui.preview.AppPreview
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.ServerAddressSaveOutcome
import com.ebook.me.view.ServerAddressScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「服务器地址」页（[ServerAddressScreen]）的渲染回归。
 *
 * 页面形态的契约（原编辑弹窗用例迁移至此，行为不变）：
 * - **进页才见地址**：设置行只给状态，完整地址与明文警告都在本页——这是「点进去再说」的落点；
 * - 非法输入/落盘失败行内报错且不离开本页，回环 http 不提示明文风险；
 * - 清除配置需二次确认；保存成功后不可取消的重启提示由 `pendingRestart` 状态驱动；
 * - 写盘在途按钮禁用并显示「正在保存…」。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ServerAddressScreenRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 造一屏页面（display=null 即未配置态）；回调默认什么都不做。 */
    private fun setScreen(
        display: String? = "http://10.0.2.2:9090",
        isConfigured: Boolean = false,
        isBusy: Boolean = false,
        onSave: (String, (ServerAddressSaveOutcome) -> Unit) -> Unit = { _, _ -> },
        onReset: ((ServerAddressSaveOutcome) -> Unit) -> Unit = { _ -> },
    ) {
        composeRule.setContent {
            AppPreview {
                ServerAddressScreen(
                    currentDisplay = display,
                    isConfigured = isConfigured,
                    pendingRestart = false,
                    isBusy = isBusy,
                    onSave = onSave,
                    onReset = onReset,
                    onRestartApp = {},
                )
            }
        }
    }

    @Test
    fun `进页展示完整地址与局域网说明`() {
        setScreen(display = "http://192.168.0.156:9090")

        // 「点进去再说」的落点：完整地址只在本页出现（设置行不显示地址值）
        composeRule.onNodeWithText("http://192.168.0.156:9090").assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_lan_note)
        ).assertIsDisplayed()
    }

    /**
     * 未配置进页是**空输入框**（应用不带默认地址，ADR-0053）：页面上不出现任何地址值，
     * 只有用户自己填的值才会显示——「地址不内置、也不预填」是这条的契约。
     */
    @Test
    fun `未配置进页为空输入框且不带任何地址`() {
        setScreen(display = null)

        // 空态的两面：输入框在（可输入）、里面**没有值**——一旦有人把默认地址预填回来，
        // EditableText 不再是空串，本例必红。（占位符不在此断言：M3 的占位符语义在未聚焦时
        // 不进语义树，那是它家内部行为；示例文案由页面 hint 承担，不靠占位符。）
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assert(hasText(""))
        // 任何形似地址的值都不得出现在未配置的页面上
        composeRule.onNodeWithText("http://10.0.2.2:9090").assertDoesNotExist()
        composeRule.onNodeWithText("10.0.2.2:9090").assertDoesNotExist()
    }

    @Test
    fun `保存非法输入时显示行内错误`() {
        setScreen(onSave = { _, outcome -> outcome(ServerAddressSaveOutcome.Invalid) })

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_save)
        ).performClick()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_invalid)
        ).assertIsDisplayed()
    }

    @Test
    fun `落盘失败时留在页面并提示重试`() {
        setScreen(onSave = { _, outcome -> outcome(ServerAddressSaveOutcome.Failed) })

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_save)
        ).performClick()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_save_failed)
        ).assertIsDisplayed()
        // 失败不催重启：重启提示是 pendingRestart 状态，失败时它没有理由变 true
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_saved_title)
        ).assertDoesNotExist()
    }

    @Test
    fun `非回环 http 地址显示明文警告`() {
        setScreen(display = "http://192.168.0.156:9090")

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_cleartext_warning)
        ).assertIsDisplayed()
    }

    @Test
    fun `回环地址不显示明文警告`() {
        setScreen(display = "http://127.0.0.1:9090")

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_cleartext_warning)
        ).assertDoesNotExist()
    }

    /** 清除配置会清地址、退出登录并强制重启，不能一点即走：先确认、确认后才回调。 */
    @Test
    fun `清除配置需二次确认后才回调`() {
        var resetCalls = 0
        setScreen(
            isConfigured = true,
            onReset = { outcome ->
                resetCalls++
                outcome(ServerAddressSaveOutcome.Saved)
            },
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_reset)
        ).performClick()

        assertEquals("点击清除配置不得直接执行", 0, resetCalls)
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_reset_confirm_title)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_reset_confirm_action)
        ).performClick()

        assertEquals("确认后才回调清除配置", 1, resetCalls)
    }

    /**
     * 保存成功 → 不可取消的重启提示出现。可见性由 `pendingRestart` 状态驱动（可变状态模拟
     * 父级状态流），不是保存回调里的局部信号——锁住「回调只通知、可见性看状态」。
     */
    @Test
    fun `保存成功后由状态切换出不可取消的重启提示`() {
        composeRule.setContent {
            AppPreview {
                var pending by remember { mutableStateOf(false) }
                ServerAddressScreen(
                    currentDisplay = "http://10.0.2.2:9090",
                    isConfigured = false,
                    pendingRestart = pending,
                    isBusy = false,
                    onSave = { _, outcome ->
                        pending = true
                        outcome(ServerAddressSaveOutcome.Saved)
                    },
                    onReset = { _ -> },
                    onRestartApp = {},
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_save)
        ).performClick()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_saved_title)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_restart_now)
        ).assertIsDisplayed()
    }

    @Test
    fun `地址写入在途时保存按钮禁用并显示正在保存`() {
        setScreen(isBusy = true)

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_save)
        ).assertIsNotEnabled()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_saving)
        ).assertIsDisplayed()
    }

    /** 「清除配置」只对已配置地址有意义：未配置时（应用不带默认地址）没东西可清。 */
    @Test
    fun `未配置不显示清除配置按钮`() {
        setScreen(display = null)
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_reset)
        ).assertDoesNotExist()
    }

    @Test
    fun `已配置显示清除配置按钮`() {
        setScreen(display = "http://192.168.9.9:8080", isConfigured = true)
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_reset)
        ).assertIsDisplayed()
    }
}
