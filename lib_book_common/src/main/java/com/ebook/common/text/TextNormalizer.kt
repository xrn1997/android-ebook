package com.ebook.common.text

/**
 * 文本规范化的唯一入口（spec §8）。
 *
 * 设计约束是**存储层不清洗**：章文件存"切分后、规范化前"的原文，本对象只在**读取时**把
 * 原文转成段落数据与展示文本。这样"发现规则定错了"永远是改一行读取代码，而不是
 * "数据已不可逆损毁"。旧实现把空格删除与缩进写入都放在**存储时**做
 * （`BookImportManager（已删除）:159-161` 删空格、`:171` 把 `　　` append 进正文），正是后者。
 *
 * 段落缩进只出现在 [toDisplayText]、绝不出现在 [cleanParagraph] 的结果里：段评锚点
 * （spec §9.1）建立在段落数据之上，掺进表现层字符会让锚点随渲染规则漂移。
 *
 * 与缩进相关的分工是"先吸收、再统一补"：[cleanParagraph] 把行首已有的全角/半角空白剥掉，
 * 缩进由 [toDisplayText] 一律补 [INDENT]。因此旧数据（正文里已带 `　　`）与新数据（不带）
 * 渲染结果一致，不会出现四个全角空格。
 */
object TextNormalizer {

    /**
     * 段首缩进：两个全角空格（U+3000 IDEOGRAPHIC SPACE），与阅读器分页与渲染的既有假设一致。
     *
     * 这里保留**字面**全角空格而不用 `\u3000` 转义：全角空格肉眼可辨，转义只会降低可读性。
     * 需要转义的是不可见字符（见 [BOM]）。
     */
    const val INDENT: String = "　　"

    /**
     * 行首 BOM。必须用 `\uFEFF` 显式转义书写，勿改成字面字符——BOM 在源文件里完全不可见，
     * 字面写法会被编辑器无声吞掉，review 也看不出来（测试里同理，见 TextNormalizerTest）。
     */
    private const val BOM: Char = '\uFEFF'

    /**
     * 统一换行为 LF。
     *
     * 阅读器的 `com.ebook.book.reader.ReaderTypesetter.lineStartOffsets` 以 `\n` 作段落分隔，
     * 其 KDoc 记录了 CRLF 下 Compose 把 `\n` 吞进行间隔、使段落并团重新折行的缺陷（页数与
     * 折行同时错乱）；规范化后章内不再出现 `\r`，该缺陷不再有触发面。
     *
     * 先替换 `\r\n` 再单独替换 `\r`：反过来会把 CRLF 变成两个 LF（多出一个空段落）。
     * 无 `\r` 时直接回原串，省去整章复制（正文可达数十万字）。
     */
    fun unifyNewlines(text: String): String =
        if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text

    /**
     * 单行清洗：剥 BOM、行内连续空白折叠为**一个**半角空格、去首尾空白。
     *
     * 折叠而非删除是本次迁移的核心修正——旧实现删光行内所有空白，`1 000`、英文书名、
     * 代码片段里的空格随之永久丢失且无从恢复。
     *
     * 行首空白（全角 `　`、半角 ` `、制表符等）被整体丢弃而不留折叠空格：这正是"吸收历史
     * 缩进"那一步，使结果与 [INDENT] 的补法互不重复。实现是单次遍历（O(行)），不产生
     * 中间字符串，因为本函数按段落逐行调用、整章可达数十万行。
     *
     * 空白判据是 Kotlin 标准库的 [Char.isWhitespace]，其表**宽于** `java.lang.Character`
     * （实测 Kotlin 2.4.10：U+00A0、U+2007、U+202F 三个不换行空格在 Kotlin 里算空白、在 JDK 里
     * 不算），故它们也会被折成半角空格、"不换行"语义随之消失。这条标准库依赖需要记住：段评锚点
     * （spec §9.1）取"该段规范化后前 16 字的哈希"，若哪天标准库把表与 JDK 对齐，含不换行空格的
     * 书会整体改锚——这正是 `pa1:` 版本前缀要接住的变更，升级 Kotlin 时须重跑本类单测。
     */
    fun cleanParagraph(rawLine: String): String {
        val line = if (rawLine.startsWith(BOM)) rawLine.substring(1) else rawLine
        if (line.isEmpty()) return line
        return buildString(line.length) {
            var pendingSpace = false
            for (ch in line) {
                if (ch.isWhitespace()) {
                    // 只在已产出内容后才记空白，行尾空白因此在末尾被整体丢弃
                    if (isNotEmpty()) pendingSpace = true
                    continue
                }
                if (pendingSpace) {
                    append(' ')
                    pendingSpace = false
                }
                append(ch)
            }
        }
    }

    /**
     * 整行集合清洗并丢弃空行。
     *
     * 空行只是**段落分隔信号**（旧实现靠它判定段落边界），本身不产出段落，
     * 否则渲染与段评锚点都会多出空段落项。
     */
    fun cleanParagraphs(rawLines: List<String>): List<String> =
        rawLines.map { cleanParagraph(it) }.filter { it.isNotEmpty() }

    /**
     * 段落数据 → 展示文本；分页测量与渲染共用同一份（同源契约见
     * `com.ebook.book.reader.ReaderTypesetter`）。
     *
     * 段落间以单个 `\n` 分隔并各带 [INDENT]：入参必须已是 [cleanParagraph] 的结果（不带
     * 行首空白），否则缩进会叠加。
     */
    fun toDisplayText(paragraphs: List<String>): String =
        paragraphs.joinToString("\n") { INDENT + it }
}
