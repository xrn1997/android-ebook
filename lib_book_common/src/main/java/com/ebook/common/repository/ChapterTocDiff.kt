package com.ebook.common.repository

import com.ebook.db.entity.ChapterListEntity

/**
 * [ChapterTocDiff.diff] 的三种结局（spec §4.1）。
 */
sealed interface TocDiff {
    /** 远端没有本地之外的章，本地已是最新 */
    data object UpToDate : TocDiff

    /**
     * 本地每一章都还在远端、且相对顺序一致，[tail] 是远端在本地末章之后多出来的那些章。
     *
     * `tail` 里的 `durChapterIndex` 是 parser 给的**位置序号**，本地有洞时与本地口径不同，
     * 必须由调用方按不变式 I3 重排后才能落库（见 [BookRepository.syncChaptersFromSource]）。
     */
    data class Appendable(val tail: List<ChapterListEntity>) : TocDiff

    /** 关系不是「本地 ⊆ 远端且顺序一致」（站点改版 / 中间插章 / 删章重排），按不变式 I1 整笔放弃 */
    data object Diverged : TocDiff
}

/**
 * 目录前缀判定：本地那批章是不是都还在远端目录里、且相对顺序一致；是的话远端多出了哪些章。
 *
 * 为什么独立成一个零 IO 的纯函数：判错的后果是**静默读错章** ——
 * 章文件按序号命名（`filesDir/books/<bookId>/cNNNNN.txt`），序号一漂移，
 * 用户点第 50 章读到的是旧的第 50 章，不报错、不闪退、页面上一切正常。
 * 这种故障在集成测试里用眼睛看不出来，只能靠穷举边界形态钉住；
 * 而比对逻辑一旦与 DAO、parser 混在一个方法里，每写一条边界用例都得先搭一套假件。
 *
 * 身份判据只用 `content_ref`（网络书即该站章节 URL），**章名不参与判定**：
 * 站点改标题不代表换了书，而同一章在不同站的标题写法也可能不一致。
 *
 * 与本地书补章 [BookRepository.mergeTailChapters] 共用「分叉即整笔放弃」这个取舍，
 * 差别在判据：那边要求章名序列是**严格前缀**，因为它的产出要把整本章节内容搬过去、
 * 位置必须逐字对齐；本方法的产出只是**目录行的追加**，不搬运任何既有章，
 * 所以「本地有洞」（用户删过章）不构成风险，只是不能按位置硬比。
 */
internal object ChapterTocDiff {

    /**
     * 判定规则（两侧先各自按 `durChapterIndex` 排序 —— 上游 `@Relation` 关联查询不带
     * ORDER BY、按物理 rowid 返回，顺序不保证）：
     *
     * 1. 本地为空 → 远端全部即新章（加书架时目录抓取失败的书走这条，把整本目录补齐）；
     * 2. 本地末章的 `contentRef` 不在远端 → `Diverged`（末章都没了，谈不上追加）；
     * 3. 本地任一章不在远端，或出现在本地末章**之后**，或相对顺序与远端不一致 → `Diverged`；
     * 4. 远端在本地末章之后的那些章即 `tail`；为空则 `UpToDate`；
     * 5. `tail` 与本地既有定位符重合、或 `tail` 内部自撞 → `Diverged`：`content_ref` 是整表主键
     *    而落库是整行 REPLACE，撞键不报错，只会搬走既有行或让两行互吃。
     *
     * 为什么不按「逐位比对」：本地 `durChapterIndex` 可能有洞（历史删章，AGENTS.md 明载
     * 「新索引取 `max + 1` 不用 `size`」正是为此），而远端序号是 parser 按位置连写的。
     * 拿位置硬比会让一本用户删过章的书**永远**判成分叉、再也追不了更，
     * 而它其实完全符合「只追加、不动既有章」的安全条件。
     * 改为按 `contentRef` 定位后，删除过的书仍能正常追加，安全性不变：
     * 既有行的 index 与 contentRef 一个都不动（不变式 I2）。
     *
     * @param remote 本次从书源抓回的目录行；**调用方必须先挡掉「远端为空而本地非空」**
     *   （「一本书零章」不是合法状态，按失败处置而非分叉，见 spec §4.1 末段）
     */
    fun diff(
        local: List<ChapterListEntity>,
        remote: List<ChapterListEntity>,
    ): TocDiff {
        val byIndex = local.sortedBy { it.durChapterIndex }
        val remoteSorted = remote.sortedBy { it.durChapterIndex }

        if (byIndex.isEmpty()) {
            return when {
                remoteSorted.isEmpty() -> TocDiff.UpToDate
                // 本地为空也要过一遍键冲突：整本补齐时两行同主键会互相吃掉，凭空少一章
                !hasDistinctRefs(remoteSorted) -> TocDiff.Diverged
                else -> TocDiff.Appendable(remoteSorted)
            }
        }

        // contentRef 在远端的位序。parser 侧已用 seenRefs 跨页去重，故远端内部无重复；
        // putIfAbsent 只是万一有脏行时取第一次出现的位置，不让它覆盖成后一个。
        val positionInRemote = HashMap<String, Int>(remoteSorted.size)
        remoteSorted.forEachIndexed { i, chapter ->
            positionInRemote.putIfAbsent(chapter.contentRef, i)
        }

        val tailOfLocal = positionInRemote[byIndex.last().contentRef]
            ?: return TocDiff.Diverged

        var previous = -1
        for (chapter in byIndex) {
            val at = positionInRemote[chapter.contentRef] ?: return TocDiff.Diverged
            // at > tailOfLocal 意味着本地末章之前就该出现的章跑到了它后面：相对顺序变了
            if (at > tailOfLocal || at <= previous) return TocDiff.Diverged
            previous = at
        }

        val tail = remoteSorted.drop(tailOfLocal + 1)
        // 撞主键守卫：`content_ref` 是 chapter_list 的整表主键，而 insertAll 是整行 REPLACE
        // （先删后插）。tail 里若有哪一行的定位符与本地既有行重合，落库不会报错，而是把既有那一行
        // **搬到表尾** —— 序号静默错位（用户点第 3 章读到别的内容），且调用方按「本地 + tail」
        // 数出来的条数会比库里多；tail 内部自撞则会让两行互相吃掉，一本书凭空少一章。
        // 远端目录出现这种自相矛盾，说明这份清单整体不可信，按 I1 整笔放弃（两个 parser 正常都会
        // 用 seenRefs 去重，走到这里意味着规则失配或站点侧真的挂了两遍）。
        val localRefs = byIndex.mapTo(HashSet()) { it.contentRef }
        if (!hasDistinctRefs(tail) || tail.any { it.contentRef in localRefs }) {
            return TocDiff.Diverged
        }
        return if (tail.isEmpty()) TocDiff.UpToDate else TocDiff.Appendable(tail)
    }

    /** 一批行的 `content_ref` 是否互不相同（空集平凡通过） */
    private fun hasDistinctRefs(chapters: List<ChapterListEntity>): Boolean =
        chapters.map { it.contentRef }.distinct().size == chapters.size
}
