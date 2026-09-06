package com.ebook.common.analyze.local

import com.ebook.common.text.TextNormalizer
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.coroutineContext

/**
 * 章节切分器（spec §6 §7）：**原文**行序列 → 章节流。
 *
 * 入参是严格解码出来的原文行，产出的 [RawChapter.paragraphs] 同样是原文行——清洗发生在
 * 读取层（`TextNormalizer`，spec §4 §8），不在这里做，否则「切分后、清洗前」的形态就没了。
 * 只有章名例外：它是 `chapter_list.dur_chapter_name` 里的元数据（要显示、要参与章名比对），
 * 取的是该行的清洗结果。
 *
 * 与旧实现（`BookImportManager（已删除）`）的三处差异，都是刻意的：
 * 1. **不写数据库**。旧实现在扫描循环里逐章 insert，是 6000 次事务的成因；这里只产出流，
 *    落盘与批量事务归 `ChapterSink` 与导入器负责。
 * 2. **不清洗正文**。旧实现对每行 `.replace(" ", "")` 并塞进全角缩进，两者都不可逆。
 * 3. 标题取**正则匹配到的那段**（而非整行）；因此"他想起第三章的情节"
 *    这类行，标题是"第三章的情节"而前缀"他想起"归入上一章正文——与旧实现语义一致但锁进了测试。
 *
 * 空章（只有标题、没有正文）不产出、也不占索引位：与旧实现 `:145` 的判定一致。
 * 每产出一章 `ensureActive` 一次，使后台协程可被取消（旧实现的 `isCancel` 只在入口检查）。
 */
class ChapterSplitter(private val titleRule: Regex = DEFAULT_TITLE_RULE) {

    /** @param index 从 0 起的章序号 */
    data class RawChapter(val index: Int, val title: String, val paragraphs: List<String>)

    fun split(lines: Sequence<String>): Flow<RawChapter> = flow {
        var index = 0
        var title: String? = null
        val paragraphs = ArrayList<String>()

        for (line in lines) {
            // 原文行判空白：折叠空白不会让非空白行变空白，判定与清洗后等价
            if (line.isBlank()) continue
            val match = titleRule.find(line)
            if (match != null) {
                // 标题行前若同一行还有正文残留，归上一章（旧实现的 prefix 处理）；
                // 存原文切片，纯缩进的残留不另起一段
                line.substring(0, match.range.first).takeIf { it.isNotBlank() }?.let(paragraphs::add)
                if (paragraphs.isNotEmpty()) {
                    emit(RawChapter(index, title ?: normalizeTitle(match.value), paragraphs.toList()))
                    index++
                    paragraphs.clear()
                }
                title = normalizeTitle(match.value)
            } else {
                // 无「第x章」命名的书：以正文首行为章名（旧实现 :175 的回退）
                if (title == null) title = normalizeTitle(line)
                paragraphs.add(line)
            }
            coroutineContext.ensureActive()
        }

        if (paragraphs.isNotEmpty()) {
            emit(RawChapter(index, title ?: "", paragraphs.toList()))
        }
    }

    /** 章名规范化：剥行首缩进、折叠行内空白、去行尾空白（章名是元数据，不进"原文切片"契约） */
    private fun normalizeTitle(raw: String): String = TextNormalizer.cleanParagraph(raw)

    companion object {
        /** 默认标题规则，与旧实现 `Pattern.compile("第.{1,7}章.*")` 等价 */
        val DEFAULT_TITLE_RULE: Regex = Regex("第.{1,7}章.*")
    }
}
