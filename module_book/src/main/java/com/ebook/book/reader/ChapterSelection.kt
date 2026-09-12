package com.ebook.book.reader

/**
 * 章节目录的展示与选择语义（纯逻辑，零 Compose 依赖）。
 *
 * 拆出来的理由与同目录 [switchFeedbackOf] 一族相同：这些决定要么影响"屏幕上显示第几章"，
 * 要么影响"哪些章会被下发下载"，算错既不会编译失败也不会闪退——只会静默给出错的内容。
 * 全是纯函数，直接 JVM 单测即可锁住；Compose 层只负责把它们接到列表上
 * （本仓 Compose 页面不做装机级单测，见 AGENTS.md 测试约定）。
 *
 * 历史：下载选章曾按百章分组（chapterGroups/groupState/toggleGroup/rowIndexOfGroup 一族，
 * 含 DEFAULT_GROUP_SIZE），2026-09-12 二级页改平铺列表后该族已随折叠组头一同移除
 * （见 ADR-0034 修订说明）；倒序索引换算（originalIndexAt/displayPositionOf）仍被
 * 阅读器目录抽屉使用，保留。
 */

/** 单次下载的软上限（章数）。 */
internal const val MAX_DOWNLOAD_SELECTION = 500

/** 是否超出单次下载软上限（超过仅触发二次确认，不阻止下发）。 */
internal fun exceedsSelectionCap(size: Int): Boolean = size > MAX_DOWNLOAD_SELECTION

/**
 * 列表显示位置 → 原始章序号（正序时两者相同）。
 *
 * 与 [displayPositionOf] 签名同形且互为逆运算，调用点务必用具名参数（`position =`），
 * 传错不报错、只会在倒序时静默给出另一个错值。
 */
internal fun originalIndexAt(count: Int, descending: Boolean, position: Int): Int =
    if (descending) count - 1 - position else position

/**
 * 原始章序号 → 列表显示位置（[originalIndexAt] 的逆运算）。
 *
 * 与 [originalIndexAt] 签名同形且互为逆运算，调用点务必用具名参数（`originalIndex =`），
 * 传错不报错、只会在倒序时静默给出另一个错值。
 */
internal fun displayPositionOf(count: Int, descending: Boolean, originalIndex: Int): Int =
    if (descending) count - 1 - originalIndex else originalIndex