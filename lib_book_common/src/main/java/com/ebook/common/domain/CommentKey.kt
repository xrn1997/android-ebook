package com.ebook.common.domain

import java.security.MessageDigest

/**
 * 作品身份 `comment_key` 的派生（spec §9.1）。
 *
 * 为什么是**客户端派生的不透明 token**而不是后端建一张书籍表：后端不得存书籍数据（不存书名、
 * 不建书籍表、不提供"列出所有书"），但评论需要一个跨用户一致的桶键。派生函数让所有客户端
 * **无协调地**算出同一个值——正因为没有人分配，它才能处处一致。sha256 不可逆，后端拿不到书名，
 * 这比现状（明文存 book_name/chapter_name，书籍级评论还靠一个无索引文本列聚合）更合规。
 *
 * 版本前缀 ck1: 是**算法版本**：归一化规则一旦改动（例如将来加繁简转换），不同版本客户端会
 * 静默算出不同键、两堆评论互不可见；而评论不可再生，不能像索引那样"删了重来"。
 * 所以改归一化必须同时升这个前缀。
 *
 * 作者占位词（佚名/侠名 等）归一为空串后才参与哈希，因此显示层用哪个词都不会换掉评论桶
 * ——这条与 spec §8「默认作者显示词改为侠名」配套。
 */
object CommentKey {

    const val ALGORITHM_VERSION: String = "ck1"

    /**
     * 作者字段里出现这些值视同"不知道作者"。比对时两边都先过 [normalize]，
     * 所以这里只写小写、无空白的形态。
     */
    private val authorPlaceholders = setOf(
        "佚名", "侠名", "未知", "不详", "未署名", "作者未知", "n/a", "na", "none", "unknown", "author"
    )

    /** 书名里成对出现的装饰符号，对识别作品无意义 */
    private val titleNoise = charArrayOf('《', '》', '「', '」', '『', '』', '〈', '〉')

    /**
     * 计算作品身份键。
     *
     * @param title 显示书名或主匹配名；@param author null/空/占位词都按"无作者"处理
     * @return `ck1:<64 位小写十六进制>` 形式的固定长度键
     */
    fun compute(title: String?, author: String?): String {
        val normalizedTitle = normalize(title.orEmpty())
        val normalizedAuthor = normalize(author.orEmpty())
            .takeUnless { it in authorPlaceholders }
            .orEmpty()
        // 长度前缀自 delimited：避免「AB」+「C」与「A」+「BC」撞键，不依赖任何分隔字符
        val joined = "${normalizedTitle.length}:$normalizedTitle${normalizedAuthor.length}:$normalizedAuthor"
        return "$ALGORITHM_VERSION:${sha256Hex(joined)}"
    }

    /**
     * 归一化：剥书名号 → 全角转半角 → 转小写 → 折叠所有空白。
     *
     * 刻意**不做**繁简转换：那是会改变键空间的规则升级，做的话必须同时升
     * [ALGORITHM_VERSION]（spec §9.5 约束 1）。
     */
    fun normalize(raw: String): String {
        val folded = buildString(raw.length) {
            for (ch in raw) {
                if (ch in titleNoise) continue
                append(toHalfWidth(ch).lowercaseChar())
            }
        }
        // 折叠所有空白（含全角空格转换来的半角空格）为单个半角空格，再收掉首尾
        return folded.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * 全角 ASCII（U+FF01..U+FF5E）与全角空格（U+3000）折到半角；其余原样返回。
     *
     * U+3000 是 CJK 表意空格（　），宽度等于一个汉字，常见于中文文本。
     */
    private fun toHalfWidth(ch: Char): Char = when {
        ch == '　' -> ' '
        ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
        else -> ch
    }

    /** 把字符串算成 SHA-256 摘要，输出 64 位小写十六进制 */
    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
