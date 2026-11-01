package com.ebook.me.view

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ebook.common.R as CommonR
import com.ebook.me.R
import kotlin.system.exitProcess

/**
 * 服务器地址页的两个对话框：清除配置二次确认 与 不可取消的重启提示。
 *
 * 编辑表单不在这里——它是 [ServerAddressScreen] 的页面本体：地址与明文警告是要看的内容，
 * 不该收在弹层里（用户点进设置行就是为了看/改地址）。保存/清除配置的结果由页面处置，
 * 本文件不持有任何业务状态。
 */

/** 清除配置二次确认：清自定义地址 + 退出登录 + 强制重启，不能像普通按钮一样一点即走。 */
@Composable
fun ServerAddressResetConfirmDialog(
    isBusy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text(stringResource(R.string.setting_server_address_reset_confirm_title)) },
        text = { Text(stringResource(R.string.setting_server_address_reset_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isBusy) {
                Text(stringResource(R.string.setting_server_address_reset_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(CommonR.string.cancel))
            }
        },
    )
}

/** 保存成功后的重启提示：不可取消，只有「立即重启」。 */
@Composable
fun ServerAddressRestartDialog(
    onRestart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.setting_server_address_saved_title)) },
        text = { Text(stringResource(R.string.setting_server_address_saved_message)) },
        confirmButton = {
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.setting_server_address_restart_now))
            }
        },
    )
}

/**
 * 一键重启：经 launcher intent 起新任务再退出进程。
 * 不用组件名硬编码宿主 Activity——module_me 不知道宿主是谁，launcher intent 是模块中立的取法。
 * 取不到 launcher intent（null）时仍照常退出：正常安装的包一定有入口，
 * 缺了也要先把旧地址的进程结束掉，不能因为「起不来」而留着旧地址继续跑。
 *
 * 不调用 `finishAffinity()`：`CLEAR_TASK` 已负责接管旧任务，主动 finish 只会与刚排队的
 * 启动竞态，可能把新页面一起收掉。换址重启会中断在途下载，这是低频操作的已接受代价（ADR-0052）。
 */
internal fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
    }
    exitProcess(0)
}
