package com.ebook.source.sandbox

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 出网派生唯一出口（ADR-0058）的锁：两路派生的守门面、同源派生、「永不附凭证」。
 *
 * 与既有用例的分工：`SourceCookieJarTest` 锁沙箱那路挂 cookie 罐，`JsNetworkGuardTest`
 * 锁 `GuardedDns` 内部的地址判据——本类锁的是**派生动作本身**：沙箱路挂守门 DNS、
 * 取文路保持默认 DNS（LAN/IP 直连源今天可用、明天也可用，首合并行为不变）、
 * 两路都从同一个 base 继承（拦截器/超时）、都不往链上添任何凭证拦截器。
 */
class SourceNetworkExitTest {

    private val guard = JsNetworkGuard(SourceHostAllowlist.of("https://novel.example/", emptyList()))

    @Test
    fun `沙箱派生挂上守门 DNS 而注入的 base 不被污染`() {
        val base = OkHttpClient()
        val derived = SourceNetworkExit.sandboxClientFor(base, guard)

        assertTrue("沙箱外呼必须经 GuardedDns 判定私网与 DNS 重绑", derived.dns is GuardedDns)
        assertNotSame("派生不得改动注入的 base", base.dns, derived.dns)
    }

    @Test
    fun `取文派生保持默认 DNS——LAN 与 IP 直连源继续可用`() {
        val base = OkHttpClient()
        val derived = SourceNetworkExit.contentClientFor(base)

        // 首合并行为不变（ADR-0058）：私网/DNS 重绑判定仍只在沙箱外呼那一路，
        // 取文挂上 GuardedDns 会掐死 LAN/IP 直连源——这是用户裁决过的边界，不是漏改
        assertSame("取文不得挂 GuardedDns", Dns.SYSTEM, derived.dns)
        assertTrue(derived.dns !is GuardedDns)
    }

    @Test
    fun `两路都从同一个 base 派生——拦截器与超时原样继承`() {
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }
        val base = OkHttpClient.Builder()
            .addInterceptor(marker)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()
        val sandbox = SourceNetworkExit.sandboxClientFor(base, guard)
        val content = SourceNetworkExit.contentClientFor(base)

        assertEquals("沙箱路继承 base 的拦截器集", listOf<Interceptor>(marker), sandbox.interceptors)
        assertEquals("取文路继承同一份拦截器集", sandbox.interceptors, content.interceptors)
        assertEquals(base.connectTimeoutMillis, sandbox.connectTimeoutMillis)
        assertEquals(base.connectTimeoutMillis, content.connectTimeoutMillis)
    }

    @Test
    fun `两路派生都不引入凭证拦截器——base 是无 token 的纯净客户端`() {
        // 生产的 base 是 @Named("source") 纯净客户端（不带用户 token），派生只做 newBuilder
        // 的继承、不往链上添任何拦截器——附凭证的那一类拦截器就住在这一层，添了即破功
        val base = OkHttpClient()
        val clients = listOf(
            "沙箱路" to SourceNetworkExit.sandboxClientFor(base, guard),
            "取文路" to SourceNetworkExit.contentClientFor(base),
        )
        for ((name, client) in clients) {
            assertEquals("$name 不得引入应用拦截器（Authorization 一类凭证拦截器住在这一层）", emptyList<Interceptor>(), client.interceptors)
            assertEquals("$name 不得引入网络拦截器", emptyList<Interceptor>(), client.networkInterceptors)
        }
    }
}
