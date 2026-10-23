package com.ebook.book.reader

/** 阅读器当前翻页方式：左右翻页 / 上下滚屏（ADR-0037）。 */
enum class ReaderTurnMode { PAGED, SCROLL }

/** 两种翻页方式共用的控制面：定位与前后翻。 */
interface ReaderTurnController {
    /**
     * 定位到「章, 屏」。
     *
     * @param reportProgress false = 只把落点放好、不回报进度，给**当前没显示的那一侧**预热用
     *   （见 [ReaderNavigation.converge]）。屏号在两种模式里指向的不是同一个量，未显示那侧按
     *   自己的块数解析完报回来，会把当前模式刚写好的进度盖掉。
     */
    fun setPosition(chapterIndex: Int, pageIndex: Int, reportProgress: Boolean = true)

    fun turnNext()

    fun turnPrev()
}

/**
 * 阅读器导航的唯一控制面：当前模式、控制器路由与进度出口。
 *
 * 原先「当前是翻页还是滚屏」有两套编码（`turnModeIndex` 与「哪个控制器 ref 非空」），
 * 音量键、启动、换源、滑条各写一处分支；两个控制器还各接一份逐字相同的 `onProgress`。
 * 现在：
 * - 路由判据只有 [mode]（页面用 `turnModeIndex` 镜像驱动重组，切换时同步写入）；
 * - 调用方只调 [turnNext]/[turnPrev]/[setPosition]/[converge]，不关心当前挂的是哪个控制器；
 * - 两个控制器共用 [onProgress] 一个出口，进度交会话；标题/滑条由会话派生，追更由会话判定。
 *
 * 定位分两档：跳转（目录、滑条、换源）与换模式后的换算只驱动当前模式（[setPosition]）；
 * 冷启动与重分页另用 [converge] 把另一侧的落点预热上——不预热它就带着「章号 0、块数 0」的初值
 * 躺着，用户一换模式，新容器成形的那一帧落点是错的，而 [setPosition] 那类事后换算赶在这一帧之后。
 *
 * 控制器在组合期创建、由页面的 `DisposableEffect` 调 [attach] 挂入；在此之前调用路由方法为 no-op。
 */
internal class ReaderNavigation(
    initialMode: ReaderTurnMode,
    /** 两个控制器共用的唯一进度出口（由页面注入；落点见 `ReadBookScreen`） */
    val onProgress: (chapterIndex: Int, pageIndex: Int) -> Unit,
) {
    private var paged: ReaderTurnController? = null
    private var scroll: ReaderTurnController? = null

    var mode: ReaderTurnMode = initialMode
        private set

    fun attach(paged: ReaderTurnController, scroll: ReaderTurnController) {
        this.paged = paged
        this.scroll = scroll
    }

    fun setMode(mode: ReaderTurnMode) {
        this.mode = mode
    }

    /** 跳转：只驱动当前模式的控制器——另一侧此刻没显示，驱动它只会白加载一次并退回报错口径的进度。 */
    fun setPosition(chapterIndex: Int, pageIndex: Int) {
        activeController()?.setPosition(chapterIndex, pageIndex)
    }

    /** 当前模式照常定位并上报进度；另一侧只预热落点、不报进度（两档的分别见类注释）。 */
    fun converge(chapterIndex: Int, pageIndex: Int) {
        activeController()?.setPosition(chapterIndex, pageIndex)
        inactiveController()?.setPosition(chapterIndex, pageIndex, reportProgress = false)
    }

    fun turnNext() {
        // 滚屏控制器把 turnNext 映射成「滚一屏」，导航不必知道它的内部动作
        activeController()?.turnNext()
    }

    fun turnPrev() {
        activeController()?.turnPrev()
    }

    private fun activeController(): ReaderTurnController? = when (mode) {
        ReaderTurnMode.PAGED -> paged
        ReaderTurnMode.SCROLL -> scroll
    }

    private fun inactiveController(): ReaderTurnController? = when (mode) {
        ReaderTurnMode.PAGED -> scroll
        ReaderTurnMode.SCROLL -> paged
    }
}
