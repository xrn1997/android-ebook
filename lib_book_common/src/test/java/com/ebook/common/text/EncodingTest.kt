package com.ebook.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * 编码探测与严格解码的测试（spec §4 §7）。
 *
 * 探测结果不断言具体编码名：juniversalchardet 对 GBK 语料可能回 `GBK` 也可能回
 * `GB18030`，写死名字会得到"实现没错、测试脆弱"的用例。真正要锁的是
 * **探测出的编码能把那批字节解回期望文本**，以及它确实不是 UTF-8。
 */
class EncodingTest {

    private val sample = "第一章 风起云涌的年代，正文需要足够长才能被探测算法判定出编码特征。"

    @Test
    fun `UTF-8 语料探测出的编码能解回原文`() {
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val detected = EncodingProbe.detect(bytes, bytes.size)
        assertEquals(sample, String(bytes, charsetOf(detected)))
    }

    @Test
    fun `GBK 语料不被探测成 UTF-8 且能解回原文`() {
        val gbk = charsetOf("GBK")
        val bytes = sample.toByteArray(gbk)
        val detected = EncodingProbe.detect(bytes, bytes.size)
        assertNotEquals("GBK 语料不该探测成 UTF-8，实际=$detected", "UTF-8", detected)
        assertEquals(sample, String(bytes, charsetOf(detected)))
    }

    @Test
    fun `空输入回落到 FALLBACK 编码`() {
        assertEquals(EncodingProbe.FALLBACK, EncodingProbe.detect(ByteArray(0), 0))
    }

    @Test
    fun `不可解码字节必须抛异常而非静默替换出 U+FFFD`() {
        // 0xFF 0xFE 不是合法的 UTF-8 起始字节
        val file = tempFile("strict-bad", byteArrayOf(0x31, 0x32, 0x33, 0xFF.toByte(), 0xFE.toByte()))
        var thrown: Throwable? = null
        val text = try { StrictTextReader.readAll(file, "UTF-8") } catch (e: IOException) { thrown = e; null }
        assertNotNull("必须抛异常而不是静默替换出内容", thrown)
        assertTrue("结果里不得出现 U+FFFD", (text?.indexOf('\uFFFD') ?: -1) == -1)
    }

    @Test
    fun `StrictTextReader 剥离 BOM`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = tempFile("strict-bom", bom + "正文".toByteArray(Charsets.UTF_8))
        assertEquals("正文", StrictTextReader.readAll(file, "UTF-8"))
    }

    @Test
    fun `未知编码名转成带该名字的 IOException`() {
        val file = tempFile("strict-charset", "abc".toByteArray(Charsets.UTF_8))
        try {
            StrictTextReader.readAll(file, "NOT-A-REAL-CHARSET")
            fail("未知编码名必须报错")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("NOT-A-REAL-CHARSET"))
        }
    }

    private fun tempFile(prefix: String, bytes: ByteArray): File =
        File.createTempFile(prefix, ".txt").apply { deleteOnExit(); writeBytes(bytes) }

    private fun charsetOf(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
}
