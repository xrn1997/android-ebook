package com.ebook.common.analyze.local

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.coroutineContext

/**
 * 章节切分器（spec §6 §7）：已清洗的行序列 → 章节流。
 *
 * 与旧实现（`BookImportManager（已删除）:128-186`）的三处差异，都是刻意的：
 * 1. **不写数据库**。旧实现在扫描循环里逐章 insert，是 6000 次事务的成因；这里只产出流，
 *    落盘与批量事务归 `ChapterSink` 与导入器负责。
 * 2. **不清洗文本**。旧实现对每行 `.replace(" ", "")` 并塞进全角缩进，两者都不可逆；
 *    这里假设入参已经过 `TextNormalizer`，且段落里不含表现层字符。
 * 3. 标题取**正则匹配到的那段**（`match.value`），旧实现取整行；因此"他想起第三章的情节"
 *    这类行，标题是"第三章的情节"而前缀"他想起"归入上一章正文——与旧实现语义一致但锁进了测试。
 *
 * 空章（只有标题、没有正文）不产出、也不占索引位：与旧实现 `:145` 的判定一致。
 * 每产出一章 `ensureActive` 一次，使后台协程可被取消（旧实现的 `isCancel` 只在入口检查）。
 */
class ChapterSplitter(private val titleRule: Regex = DEFAULT_TITLE_RULE) {

    /** @param index 从 0 起的章序号 */
    data class RawChapter(val index: Int, val title: String, val paragraphs: List<String>)

    fun split(cleanedLines: Sequence<String>): Flow<RawChapter> = flow {
        var index = 0
        var title: String? = null
        val paragraphs = ArrayList<String>()

        for (line in cleanedLines) {
            if (line.isBlank()) continue
            val match = titleRule.find(line)
            if (match != null) {
                // 标题行前若同一行还有正文残留，归上一章（旧实现的 prefix 处理）
                line.substring(0, match.range.first).takeIf { it.isNotEmpty() }?.let(paragraphs::add)
                if (paragraphs.isNotEmpty()) {
                    emit(RawChapter(index, title ?: match.value, paragraphs.toList()))
                    index++
                    paragraphs.clear()
                }
                title = match.value
            } else {
                // 无「第x章」命名的书：以正文首行为章名（旧实现 :175 的回退）
                if (title == null) title = line
                paragraphs.add(line)
            }
            coroutineContext.ensureActive()
        }

        if (paragraphs.isNotEmpty()) {
            emit(RawChapter(index, title ?: "", paragraphs.toList()))
        }
    }

    companion object {
        /** 默认标题规则，与旧实现 `Pattern.compile("第.{1,7}章.*")` 等价 */
        val DEFAULT_TITLE_RULE: Regex = Regex("第.{1,7}章.*")
    }
}
