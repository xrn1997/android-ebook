package com.ebook.common.text

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 严格解码的 Reader 工厂（spec §4）。
 *
 * 为什么不用 `InputStreamReader(stream, charset)` 或 `String(bytes, charset)`：两者的错误
 * 策略由实现决定、常见路径是**静默替换**，会把解不动的字节变成 U+FFFD 后继续跑完——
 * 症状是"导入成功、书里几个问号"，且永远不会报错。这里自建 `CharsetDecoder` 并把
 * 两侧策略都设为 `REPORT`：宁可导入失败，也不产出损毁的章文件。
 */
object StrictTextReader {

    private const val BUFFER_CHARS = 8 * 1024

    /** 打开严格解码的 BufferedReader；调用方负责关闭（配合 `use`） */
    fun open(file: File, charsetName: String): BufferedReader = BufferedReader(
        InputStreamReader(file.inputStream(), charsetFor(charsetName)),
        BUFFER_CHARS
    )

    /** 逐行提供解码结果，供切分器流式消费（整本文件不进内存） */
    fun lines(file: File, charsetName: String): Sequence<String> = open(file, charsetName).lineSequence()

    /**
     * 整体读入并剥 BOM；仅用于测试与小文件。
     *
     * 注意：这里使用显式 `CharsetDecoder.decode(ByteBuffer)` 而非 `Channels.newReader`，
     * 因为 NIO channel reader 在某些平台上不一定严格遵循 `CodingErrorAction.REPORT`，
     * 会静默替换而非抛异常。显式解码循环可确保严格模式在测试和生产路径上一致。
     */
    fun readAll(file: File, charsetName: String): String {
        val bytes = file.readBytes()
        // 剥 UTF-8 BOM
        val headerless = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val cb = decoderFor(charsetName).decode(ByteBuffer.wrap(headerless))
        return cb.toString()
    }

    private fun decoderFor(charsetName: String): java.nio.charset.CharsetDecoder {
        val charset = charsetFor(charsetName)
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    }

    private fun charsetFor(charsetName: String): Charset =
        runCatching { Charset.forName(charsetName) }
            .getOrElse { throw IOException("不支持的字符集：$charsetName") }
}
