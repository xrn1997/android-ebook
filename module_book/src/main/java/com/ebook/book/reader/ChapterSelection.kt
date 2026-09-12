package com.ebook.book.reader

/**
 * 章节目录的展示与选择语义（纯逻辑，零 Compose 依赖）。
 *
 * 拆出来的理由与同目录 [switchFeedbackOf] 一族相同：这些决定要么影响"屏幕上显示第几章"，
 * 要么影响"哪些章会被下发下载"，算错既不会编译失败也不会闪退——只会静默给出错的内容。
 * 全是纯函数，直接 JVM 单测即可锁住；Compose 层只负责把它们接到列表上
 * （本仓 Compose 页面不做装机级单测，见 AGENTS.md 测试约定）。
 */

/** 下载面板的分组粒度：每 100 章一组。 */
internal const val DEFAULT_GROUP_SIZE = 100

/**
 * 单次下载的软上限（章数）。
 *
 * 超过只触发一次二次确认、不阻止下发：长书"整本离线"是正当操作，硬截断会让它变得做不成。
 */
internal const val MAX_DOWNLOAD_SELECTION = 500

/**
 * 一个章节分组。
 *
 * [first] / [last] 是**原始章序号**（0 基，与 `dur_chapter_index`、勾选集合 `selected` 同口径）
 * 的闭区间，与列表显示位置无关——倒序、分组展开都不改变这里的值。
 */
internal data class ChapterGroup(
    /**
     * 组的 0 基序号（稠密、自 0 递增）。
     *
     * 目前恒等于本组在 [chapterGroups] 返回列表中的下标，但**身份语义是序号而非下标**：
     * 任何"组的身份集合"（如展开集合）一律存这个序号，不要存某个可能被过滤过的列表下标——
     * 两者分离时不会报错也不会闪退，只会静默指向错误的组。
     */
    val index: Int,
    val first: Int,
    val last: Int,
) {
    /** 本组章数：末组不足一组时如实反映（3050 章时末组为 50）。 */
    val count: Int get() = last - first + 1
}

/**
 * 按固定粒度切分章节区间。
 *
 * 末组不足一组时如实收尾，不补齐也不丢弃；[total] <= 0 时返回空列表——空目录产出一个
 * "第 1-0 章"的组会让组头渲染出无意义的范围文案。
 */
internal fun chapterGroups(total: Int, size: Int = DEFAULT_GROUP_SIZE): List<ChapterGroup> {
    require(size > 0) { "分组粒度必须为正数" }
    if (total <= 0) return emptyList()
    return (0 until total step size).map { start ->
        ChapterGroup(
            index = start / size,
            first = start,
            last = minOf(start + size - 1, total - 1),
        )
    }
}

/** 组头勾选框的三态。 */
internal enum class GroupState { NONE, PARTIAL, ALL }

/**
 * 组头勾选框当前应显示的状态。
 *
 * 判定按 [ChapterGroup.count] 走而不是按分组粒度走：末组可能不足 100 章，按粒度判会让一个
 * 已全选的末组永远停在半选态。
 */
internal fun groupState(group: ChapterGroup, selected: Set<Int>): GroupState {
    var hit = 0
    for (i in group.first..group.last) if (i in selected) hit++
    return when {
        hit == 0 -> GroupState.NONE
        hit == group.count -> GroupState.ALL
        else -> GroupState.PARTIAL
    }
}

/**
 * 点组头勾选框：整组一起进/出。
 *
 * **`PARTIAL` 走"补齐"而不是"清空"**：半选表达的是"这组还差几章"，用户点它的意图是先补齐；
 * 若按清空处理，用户会看到已选章数不升反降，且再点一次才能全选。这是本文件最容易写反的一处。
 */
internal fun toggleGroup(group: ChapterGroup, selected: Set<Int>): Set<Int> =
    when (groupState(group, selected)) {
        GroupState.ALL -> selected - (group.first..group.last).toSet()
        GroupState.NONE, GroupState.PARTIAL -> selected + (group.first..group.last).toSet()
    }

/** 是否超出单次下载软上限（超过仅触发二次确认，不阻止下发）。 */
internal fun exceedsSelectionCap(size: Int): Boolean = size > MAX_DOWNLOAD_SELECTION
