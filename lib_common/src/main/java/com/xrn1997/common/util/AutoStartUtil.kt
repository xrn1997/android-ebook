package com.xrn1997.common.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast


/**
 * Intent跳转到自启动页面全网最全适配机型解决方案
 * @author 东芝
 * @date 2019/8/6
 */
@Suppress("unused", "SpellCheckingInspection")
object AutoStartUtil {
    private const val TAG = "AutoStartUtil"
    private val hashMap: HashMap<String?, List<String?>?> =
        object : HashMap<String?, List<String?>?>() {
            init {
                put(
                    "Xiaomi", listOf(
                        "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",  //MIUI10_9.8.1(9.0)
                        "com.miui.securitycenter"
                    )
                )
                put(
                    "samsung", listOf(
                        "com.samsung.android.sm_cn/com.samsung.android.sm.ui.ram.AutoRunActivity",
                        "com.samsung.android.sm_cn/com.samsung.android.sm.ui.appmanagement.AppManagementActivity",
                        "com.samsung.android.sm_cn/com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
                        "com.samsung.android.sm_cn/.ui.ram.RamActivity",
                        "com.samsung.android.sm_cn/.app.dashboard.SmartManagerDashBoardActivity",
                        "com.samsung.android.sm/com.samsung.android.sm.ui.ram.AutoRunActivity",
                        "com.samsung.android.sm/com.samsung.android.sm.ui.appmanagement.AppManagementActivity",
                        "com.samsung.android.sm/com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
                        "com.samsung.android.sm/.ui.ram.RamActivity",
                        "com.samsung.android.sm/.app.dashboard.SmartManagerDashBoardActivity",
                        "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
                        "com.samsung.android.sm_cn",
                        "com.samsung.android.sm"
                    )
                )
                put(
                    "HUAWEI", listOf(
                        "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",  //EMUI9.1.0(方舟,9.0)
                        "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
                        "com.huawei.systemmanager/.optimize.process.ProtectActivity",
                        "com.huawei.systemmanager/.optimize.bootstart.BootStartActivity",
                        "com.huawei.systemmanager" //最后一行可以写包名, 这样如果签名的类路径在某些新版本的ROM中没找到 就直接跳转到对应的安全中心/手机管家 首页.
                    )
                )
                put(
                    "vivo", listOf(
                        "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
                        "com.iqoo.secure/.safeguard.PurviewTabActivity",
                        "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",  //                    "com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity", //这是白名单, 不是自启动
                        "com.iqoo.secure",
                        "com.vivo.permissionmanager"
                    )
                )
                put(
                    "Meizu", listOf(
                        "com.meizu.safe/.permission.SmartBGActivity",  //Flyme7.3.0(7.1.2)
                        "com.meizu.safe/.permission.PermissionMainActivity",  //网上的
                        "com.meizu.safe"
                    )
                )
                put(
                    "OPPO", listOf(
                        "com.coloros.safecenter/.startupapp.StartupAppListActivity",
                        "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
                        "com.oppo.safe/.permission.startup.StartupAppListActivity",
                        "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                        "com.coloros.safecenter/com.coloros.privacypermissionsentry.PermissionTopActivity",
                        "com.coloros.safecenter",
                        "com.oppo.safe",
                        "com.coloros.oppoguardelf"
                    )
                )
                put(
                    "oneplus", listOf(
                        "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
                        "com.oneplus.security"
                    )
                )
                put(
                    "letv", listOf(
                        "com.letv.android.letvsafe/.AutobootManageActivity",
                        "com.letv.android.letvsafe/.BackgroundAppManageActivity",  //应用保护
                        "com.letv.android.letvsafe"
                    )
                )
                put(
                    "zte", listOf(
                        "com.zte.heartyservice/.autorun.AppAutoRunManager",
                        "com.zte.heartyservice"
                    )
                )
                //金立
                put(
                    "F", listOf(
                        "com.gionee.softmanager/.MainActivity",
                        "com.gionee.softmanager"
                    )
                )

                //以下为未确定(厂商名也不确定)
                put(
                    "smartisanos", listOf(
                        "com.smartisanos.security/.invokeHistory.InvokeHistoryActivity",
                        "com.smartisanos.security"
                    )
                )
                //360
                put(
                    "360", listOf(
                        "com.yulong.android.coolsafe/.ui.activity.autorun.AutoRunListActivity",
                        "com.yulong.android.coolsafe"
                    )
                )
                //360
                put(
                    "ulong", listOf(
                        "com.yulong.android.coolsafe/.ui.activity.autorun.AutoRunListActivity",
                        "com.yulong.android.coolsafe"
                    )
                )
                //酷派
                put(
                    "coolpad" /*厂商名称不确定是否正确*/, listOf(
                        "com.yulong.android.security/com.yulong.android.seccenter.tabbarmain",
                        "com.yulong.android.security"
                    )
                )
                //联想
                put(
                    "lenovo" /*厂商名称不确定是否正确*/, listOf(
                        "com.lenovo.security/.purebackground.PureBackgroundActivity",
                        "com.lenovo.security"
                    )
                )
                put(
                    "htc" /*厂商名称不确定是否正确*/, listOf(
                        "com.htc.pitroad/.landingpage.activity.LandingPageActivity",
                        "com.htc.pitroad"
                    )
                )
                //华硕
                put(
                    "asus" /*厂商名称不确定是否正确*/, listOf(
                        "com.asus.mobilemanager/.MainActivity",
                        "com.asus.mobilemanager"
                    )
                )
            }
        }

    fun startToAutoStartSetting(context: Context) {
        Log.d(TAG, "******************当前手机型号为：" + Build.MANUFACTURER)
        val entries: MutableSet<MutableMap.MutableEntry<String?, List<String?>?>> = hashMap.entries
        var has = false
        for ((manufacturer, actCompatList) in entries) {
            if (Build.MANUFACTURER.equals(manufacturer, ignoreCase = true)) {
                if (actCompatList != null) {
                    for (act in actCompatList) {
                        try {
                            var intent: Intent
                            if (act != null) {
                                if (act.contains("/")) {
                                    intent = Intent()
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    val componentName = ComponentName.unflattenFromString(act)
                                    intent.component = componentName
                                } else {
                                    //找不到? 网上的做法都是跳转到设置... 这基本上是没意义的 基本上自启动这个功能是第三方厂商自己写的安全管家类app
                                    //所以我是直接跳转到对应的安全管家/安全中心
                                    intent = context.packageManager.getLaunchIntentForPackage(act)!!
                                }
                                context.startActivity(intent)
                                has = true
                                break
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        if (!has) {
            Toast.makeText(context, "兼容方案", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.action = "android.settings.APPLICATION_DETAILS_SETTINGS"
                intent.data = Uri.fromParts("package", context.packageName, null)
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}

