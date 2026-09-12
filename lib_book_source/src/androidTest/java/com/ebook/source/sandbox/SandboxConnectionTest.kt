package com.ebook.source.sandbox

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `:js` 执行器的连通性用例——**本计划能否成立就看它**。
 *
 * 不测策略（策略在 `SandboxTaskRunnerTest` 里全绿），只测三件在 JVM 上根本不存在的事：
 *
 * 1. `.so` 能在隔离进程里加载并跑出一条真脚本（Task 8 事实 4：这条只是推断，此处换成证据）。
 * 2. 双向事务真的双向：脚本 → host 回调 → 主进程 → 回脚本，同一线程进去同一线程出来。
 * 3. 拉起的那个进程确实被系统隔离——`onBind` 里那道自检不是纸面规则，`isolatedProcess` 也不是装饰。
 *    判据是 host 回调发起方的 uid（隔离进程在主进程侧**没有**别的可见入口，见那条用例的注释）。
 *
 * 用例之间不共享通道：每次都重新 `open()`，因为「重连能不能拿到一个新执行器」本身就是要验的事。
 *
 * 用例名一律不带空格：D8 在 DEX 040 之前不允许 SimpleName 里有空格（Task 7 已在 `QuickJsBridgeTest`
 * 踩过，`Space characters in SimpleName … not allowed prior to DEX version 040`）。
 */
@RunWith(AndroidJUnit4::class)
class SandboxConnectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val limits = JsLimits()

    @Test
    fun 连上执行器并算出一条表达式() {
        val channel = JsSandboxConnector(context, limits).open()
        val invocation = JsInvocation(JsMode.SEGMENT, "result = 1 + 1", emptyMap())
        val outcome = JsProtocol.decodeOutcome(
            channel.execute(JsProtocol.encodeRequest(invocation, deadline())) { error("不该有 host 调用") },
            limits,
        )
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("2", outcome.text)
        channel.close()
    }

    @Test
    fun 脚本里的host调用拿得到主进程回的页面() {
        var seenApi = ""
        var seenArgs = ""
        val channel = JsSandboxConnector(context, limits).open()
        val invocation = JsInvocation(JsMode.SEGMENT, "result = java.ajax('https://example.com/').length", emptyMap())
        val outcome = JsProtocol.decodeOutcome(
            channel.execute(JsProtocol.encodeRequest(invocation, deadline())) { callFrame ->
                val (api, args) = JsProtocol.decodeHostCall(callFrame)
                seenApi = api
                seenArgs = args
                JsProtocol.encodeHostReply(ok = true, data = JsonPrimitive("abc"), error = null)
            },
            limits,
        )
        channel.close()
        // 白名单在垫片里、边界在 HostDispatcher 的表里（Task 7），两者都放行才可能到这里
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("3", outcome.text)
        assertEquals("ajax", seenApi)
        assertTrue("参数应当是 argsJson 数组：$seenArgs", seenArgs.startsWith("[\"https://"))
    }

    @Test
    fun 两次执行之间不留上一个任务的全局量() {
        val channel = JsSandboxConnector(context, limits).open()
        channel.execute(
            JsProtocol.encodeRequest(JsInvocation(JsMode.SEGMENT, "var leaked = 42; result = 'a'", emptyMap()), deadline()),
        ) { error("不该有 host 调用") }
        val second = JsProtocol.decodeOutcome(
            channel.execute(
                JsProtocol.encodeRequest(JsInvocation(JsMode.SEGMENT, "result = typeof leaked", emptyMap()), deadline()),
            ) { error("不该有 host 调用") },
            limits,
        )
        channel.close()
        assertEquals("undefined", second.text)
    }

    /**
     * 「拉起来的那个进程真的被隔离」——判据是**host 回调发起方的 uid**，不是进程枚举。
     *
     * 为什么不能用 `ActivityManager.getRunningAppProcesses()`：Android 5 起这个 API 只列**调用方自己
     * UID** 的进程，而隔离进程用的是另一个 uid 段，主进程枚举它在结构上就看不见。设备首跑的假阴
     * 正是这个：这条用例报「`:js` 进程没起来」，而同文件另外三条真跑了脚本、真走了回调的用例全绿。
     * 补权限也不会让它可见——这不是权限问题，是作用域问题，别照着旧注释去加 `GET_TASKS`。
     *
     * uid 从现成的通道里拿：脚本发起一次 host 回调，`HostCallbackBinder.handleCall` 是**就地**中继
     * （不切线程），所以受理它的那条 binder 线程上 `Binder.getCallingUid()` 就是发起方（`:js`）的 uid。
     * 判据用 API 34 才有的公开谓词 `Process.isIsolatedUid`；34 以下退成「uid 不等于我们自己」——
     * 隔离进程从来共享不了宿主的 uid，剩下的隔离性由执行器 `onBind` 那道
     * [SandboxProcess.isInIsolatedProcess] 自检兜住（不隔离就不给 binder，前三条用例会同势全红）。
     */
    @Test
    fun 拉起来的执行器进程确实被系统隔离() {
        var callingUid = -1
        val channel = JsSandboxConnector(context, limits).open()
        val invocation = JsInvocation(JsMode.SEGMENT, "result = java.ajax('https://example.com/').length", emptyMap())
        val outcome = JsProtocol.decodeOutcome(
            channel.execute(JsProtocol.encodeRequest(invocation, deadline())) { _ ->
                callingUid = Binder.getCallingUid()
                JsProtocol.encodeHostReply(ok = true, data = JsonPrimitive("abc"), error = null)
            },
            limits,
        )
        channel.close()
        assertEquals("回调没走通，uid 自然也就没拿到：${outcome.error}", JsStatus.OK, outcome.status)
        assertTrue("没在回调上读到发起方 uid（回调没落在 binder 线程上？）", callingUid >= 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue(
                "host 回调发起方的 uid $callingUid 不在隔离区间：isolatedProcess 没生效",
                Process.isIsolatedUid(callingUid),
            )
        } else {
            assertTrue(
                "host 回调来自我们自己的 uid（$callingUid）：跑脚本的不是隔离进程",
                callingUid != Process.myUid(),
            )
        }
    }

    private fun deadline(): Long = SystemClock.elapsedRealtime() + limits.wallClockMs
}
