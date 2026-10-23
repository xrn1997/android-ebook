package com.ebook.common.importer

import com.ebook.common.domain.DuplicateBookDetector

/**
 * 用户对判重命中的处置选择。
 *
 * [KEEP_BOTH] 是 spec §6 要求的非破坏默认项（"要看现有条目还是继续添加"）：同键的两个条目
 * 读评论时天然取并集，共存本身就是这套模型支持的正常形态，不是需要修补的缺陷。
 */
enum class DuplicateResolution { KEEP_BOTH, MERGE, OVERWRITE, CANCEL }

/**
 * 判重命中后四种处置里哪些可用。
 *
 * 唯一判据是「命中项里有没有本地书」：只有本地书的正文归本机文件管，才谈得上把缺的章节补进来；
 * 网络书的章节由书源提供，补章无意义，只能共存或覆盖。[available] 与 [canMerge] 共用同一判据，
 * UI 不再自己算。
 */
object DispositionPolicy {

    /** 可选的处置集合；[DuplicateResolution.MERGE] 仅在命中项含本地书时出现 */
    fun available(matches: List<DuplicateBookDetector.ImportMatch>): Set<DuplicateResolution> {
        val always = setOf(
            DuplicateResolution.KEEP_BOTH,
            DuplicateResolution.OVERWRITE,
            DuplicateResolution.CANCEL,
        )
        return if (canMerge(matches)) always + DuplicateResolution.MERGE else always
    }

    /** 是否提供智能合并：命中项里至少有一个本地书 */
    fun canMerge(matches: List<DuplicateBookDetector.ImportMatch>): Boolean =
        matches.any { it.isLocal }
}
