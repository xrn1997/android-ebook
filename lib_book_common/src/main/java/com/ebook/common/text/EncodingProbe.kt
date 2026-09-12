package com.ebook.common.text

import org.mozilla.universalchardet.UniversalDetector

/**
 * 从字节头部探测字符集（spec §7）。
 *
 * 探测只需文件头若干字节；旧实现对整本文件再读一遍专门探编码，是三遍全文件读之一。
 * 探测结果由调用方存进 `book_shelf.text_charset`，**一本书只探一次**，此后重读不再重探
 * ——这也让"用户手工指定编码"在 M2 之后有唯一的落点。
 */
object EncodingProbe {

    /** 探测用的头部长度；与 juniversalchardet 的常规用法一致 */
    const val HEAD_BYTES: Int = 512 * 1024

    /** 探测不出时的回落编码 */
    const val FALLBACK: String = "UTF-8"

    /**
     * @param head 文件头字节，长度可超过 [HEAD_BYTES]，只取前 [length] 个
     * @param length [head] 中的有效字节数
     * @return 可直接交给 `Charset.forName` 的编码名，永不返回 null
     */
    fun detect(head: ByteArray, length: Int): String {
        val safeLength = minOf(length, head.size)
        if (safeLength <= 0) return FALLBACK
        val detector = UniversalDetector(null)
        var offset = 0
        while (offset < safeLength && !detector.isDone) {
            val chunk = minOf(4096, safeLength - offset)
            detector.handleData(head, offset, chunk)
            offset += chunk
        }
        detector.dataEnd()
        return detector.detectedCharset ?: FALLBACK
    }
}
