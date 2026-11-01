package com.ebook.api.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `纯主机名补默认 scheme 与端口`() {
        val address = ServerAddress.parse("192.168.0.156")
        assertEquals(ServerAddress("http", "192.168.0.156", 9090), address)
        assertEquals("http://192.168.0.156:9090/", address?.baseUrl)
    }

    @Test
    fun `显式端口被保留`() {
        assertEquals(8080, ServerAddress.parse("192.168.0.156:8080")?.port)
    }

    /** 显式写出 http 的 scheme 默认端口也是「显式端口」，不得被缺省 9090 覆盖。 */
    @Test
    fun `显式 scheme 默认端口不被缺省端口覆盖`() {
        assertEquals(80, ServerAddress.parse("http://a.com:80")?.port)
    }

    @Test
    fun `https 缺省端口为 443 且尾斜杠归一`() {
        val address = ServerAddress.parse("https://api.example.com/")
        assertEquals(ServerAddress("https", "api.example.com", 443), address)
        assertEquals("https://api.example.com/", address?.baseUrl)
    }

    @Test
    fun `首尾空白被忽略`() {
        assertEquals(ServerAddress("http", "10.0.2.2", 9090), ServerAddress.parse("  10.0.2.2  "))
    }

    @Test
    fun `IPv6 基址带方括号`() {
        assertEquals("http://[::1]:9090/", ServerAddress.parse("http://[::1]:9090")?.baseUrl)
    }

    @Test
    fun `展示形态携带 scheme 与端口`() {
        assertEquals("http://192.168.0.156:9090", ServerAddress("http", "192.168.0.156", 9090).display)
        assertEquals("http://[::1]:9090", ServerAddress("http", "::1", 9090).display)
    }

    @Test
    fun `展示形态可被 parse 原样读回`() {
        listOf(
            ServerAddress("http", "192.168.0.156", 9090),
            ServerAddress("https", "api.example.com", 8443),
            ServerAddress("http", "::1", 9090),
        ).forEach { assertEquals(it, ServerAddress.parse(it.display)) }
    }

    @Test
    fun `路径查询锚点凭据与非法输入一律拒绝`() {
        val rejected = listOf(
            "",
            "   ",
            "http://a.com/api",
            "http://a.com?x=1",
            "http://a.com#frag",
            "http://user:pass@a.com",
            "ftp://a.com",
            "http://a.com:99999",
            "http://a.com:",
            "a b.com",
        )
        rejected.forEach { assertNull("应拒绝：$it", ServerAddress.parse(it)) }
    }

    /**
     * OkHttp 会把反斜杠当路径分隔符：若不提前拒绝，`http://a.com\api` 会被静默削成主机 a.com，
     * 与「基址不允许路径」的约定相反，用户还会误以为路径被保留。
     */
    @Test
    fun `反斜杠路径同样按非法路径拒绝`() {
        listOf(
            "http://a.com\\api",
            "a.com\\api",
            "http://a.com\\:8080",
            "host\\",
        ).forEach { assertNull("应拒绝：$it", ServerAddress.parse(it)) }
    }

    @Test
    fun `scheme 与主机大小写被规范化`() {
        val address = ServerAddress.parse("HTTP://API.Example.COM:8080")

        assertEquals("http", address?.scheme)
        assertEquals("api.example.com", address?.host)
        assertEquals(8080, address?.port)
    }

    /** IDN 主机不能被拒：OkHttp 负责 Punycode，Retrofit 需要 ASCII 主机。 */
    @Test
    fun `IDN 主机被规范化为 ASCII`() {
        val address = ServerAddress.parse("https://例子.测试")

        assertNotNull(address)
        assertTrue("应转成 Punycode：${address?.host}", address!!.host.startsWith("xn--"))
    }

    @Test
    fun `非回环 http 需要明文警告`() {
        listOf(
            "http://192.168.0.156:9090",
            "http://example.com:9090",
            "http://8.8.8.8:9090",
        ).forEach {
            assertTrue("应提示明文风险：$it", ServerAddress.parse(it)!!.isCleartextToRemoteHost)
        }
    }

    @Test
    fun `回环模拟器宿主与 https 不提示明文警告`() {
        listOf(
            "http://127.0.0.1:9090",
            "http://localhost:9090",
            "http://[::1]:9090",
            "http://10.0.2.2:9090",
            "http://10.0.3.2:9090",
            "https://example.com",
        ).forEach {
            assertFalse("不该提示明文风险：$it", ServerAddress.parse(it)!!.isCleartextToRemoteHost)
        }
    }
}
