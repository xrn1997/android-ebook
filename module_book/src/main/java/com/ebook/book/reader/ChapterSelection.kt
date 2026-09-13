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
 * 范围选择输入解析：1-based 起止章号 → 0-based 索引闭区间（见 ADR-0034 修订说明）。
 *
 * 任何非法输入（空串 / 非数字 / 越界 [1, total] / 起大于止）都返回 null，由调用方决定
 * 如何提示——返回空区间会让「确定」看起来成功、勾选却没变化，比置灰更难排查。
 * 章号是用户视角的「第 X 章」（1-based，与列表行号同义），索引是 [BookChapterSelection.chapters]
 * 的下标，转换即 -1。
 */
internal fun parseChapterRange(from: String, to: String, total: Int): IntRange? {
    if (total <= 0) return null
    val fromNo = from.trim().toIntOrNull() ?: return null
    val toNo = to.trim().toIntOrNull() ?: return null
    if (fromNo < 1 || toNo < fromNo || toNo > total) return null
    return (fromNo - 1) until toNo
}

/**
 * 二级页「焦点章起 50 章」的默认窗口（0-based 闭区间，封顶到最后一章）。
 *
 * 两个使用点共用这一个口径：阅读器入口的预勾选（取到后再滤掉已缓存/已排队），
 * 与范围对话框在「一个章都没勾」时的默认区间——两处各写一个 `+50` 迟早在边界上分叉
 * （一个 51 章、一个 50 章）且不报错。无有效焦点章（一级入口传 -1）返回 null，
 * 由调用方决定退化成什么。
 */
internal fun defaultChapterWindow(total: Int, focusChapter: Int): IntRange? {
    if (total <= 0) return null
    if (focusChapter !in 0 until total) return null
    return focusChapter..(focusChapter + 50).coerceAtMost(total - 1)
}

/**
 * 范围对话框的默认区间（0-based 闭区间），按「用户已经给出的信息量」逐级退让：
 *
 * 1. **当前勾选的边界**——用户已在列表里圈了一段，范围工具是「把这段改成精确范围」，
 *    起手就该是他圈的那段（此时对话框永远不会是空框）。
 * 2. 没有勾选时用 [defaultChapterWindow]（与阅读器入口的预勾选同一口径，都是「当前章起
 *    50 章」）。
 * 3. 一级入口没有当前章，退化为**第 1 章起 50 章**。
 *
 * 返回 null 只表示「这本书没有章」，对话框据此不给默认值（此时也无从选起）。
 */
internal fun defaultRangeSelection(total: Int, selected: Set<Int>, focusChapter: Int): IntRange? {
    if (total <= 0) return null
    if (selected.isNotEmpty()) return selected.min()..selected.max()
    return defaultChapterWindow(total, focusChapter) ?: (0 until minOf(50, total))
}

/** 书头那一枚主操作按钮当前承载的动作（三态合一，见 [bookDownloadActionOf]）。 */
internal enum class BookDownloadAction { Download, Pause, Resume }

/**
 * 主操作三态判定：有任务在跑 → 暂停；已暂停且没有新的可下发勾选 → 继续；其余 → 下载。
 *
 * 「暂停」压过「下载」是一枚按钮必须替用户挑一件事时的取舍：书正在跑、用户又新勾了几章的
 * 那一格里，先给「停住正在发生的」。想下那批新勾选只需再点一次——暂停后 [effectiveCount]
 * 仍是那个数，同一枚按钮当场变回「下载」，而入库路径本就会顺带解除暂停。
 * 反过来若让「下载」常驻，跑着的队列在这页就没有刹车，只能绕到通知栏去停。
 *
 * 判据取「有没有任务」而非「当前有没有章在传」：队列已入库但服务还没开下时同样该能停，
 * 那一格若按传输中判断会漏掉暂停入口。
 */
internal fun bookDownloadActionOf(
    hasQueue: Boolean,
    paused: Boolean,
    effectiveCount: Int,
): BookDownloadAction = when {
    hasQueue && !paused -> BookDownloadAction.Pause
    hasQueue && paused && effectiveCount == 0 -> BookDownloadAction.Resume
    else -> BookDownloadAction.Download
}

/**
 * 三态各自的可用性：只有「下载」依赖勾选（一个都没勾时按下去没有对象），
 * 「暂停」「继续」面对的是既有队列，与本次勾选无关。
 */
internal fun BookDownloadAction.isEnabledWith(effectiveCount: Int): Boolean =
    this != BookDownloadAction.Download || effectiveCount > 0

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