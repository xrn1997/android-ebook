package com.ebook.common.util

import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.OnOffsetChangedListener
import kotlin.math.abs

/**
 * 监听 AppBarLayout 状态变化的抽象类。
 * 该类通过实现 OnOffsetChangedListener 接口，监听 AppBarLayout 的滚动状态，并触发对应的状态变化回调。
 */
abstract class AppBarStateChangeListener : OnOffsetChangedListener {

    // 当前 AppBarLayout 的状态，默认为 IDLE（静止状态）。
    private var mCurrentState = State.IDLE

    /**
     * 当 AppBarLayout 的偏移发生变化时调用。
     * 根据 AppBarLayout 的偏移量，判断当前的状态是 EXPANDED（展开），COLLAPSED（收起），还是 IDLE（静止）。
     *
     * @param appBarLayout 监听的 AppBarLayout
     * @param i 当前的垂直偏移量
     */
    override fun onOffsetChanged(appBarLayout: AppBarLayout, i: Int) {
        // 如果偏移量为 0，表示 AppBarLayout 处于完全展开状态
        if (i == 0) {
            if (mCurrentState != State.EXPANDED) {
                // 状态从非展开变为展开，触发状态变化回调
                onStateChanged(appBarLayout, State.EXPANDED)
            }
            // 更新当前状态为 EXPANDED
            mCurrentState = State.EXPANDED

            // 如果偏移量的绝对值大于等于总滚动范围，表示 AppBarLayout 处于完全收起状态
        } else if (abs(i.toDouble()) >= appBarLayout.totalScrollRange) {
            if (mCurrentState != State.COLLAPSED) {
                // 状态从非收起变为收起，触发状态变化回调
                onStateChanged(appBarLayout, State.COLLAPSED)
            }
            // 更新当前状态为 COLLAPSED
            mCurrentState = State.COLLAPSED

            // 其他情况下，表示 AppBarLayout 处于滑动中（既不是完全展开也不是完全收起）
        } else {
            if (mCurrentState != State.IDLE) {
                // 状态从非静止变为静止，触发状态变化回调
                onStateChanged(appBarLayout, State.IDLE)
            }
            // 更新当前状态为 IDLE
            mCurrentState = State.IDLE
        }
    }

    /**
     * 抽象方法，当状态发生变化时调用。
     * 子类需要实现该方法以处理 AppBarLayout 状态变化时的逻辑。
     *
     * @param appBarLayout 监听的 AppBarLayout
     * @param state 当前 AppBarLayout 的状态（展开、收起或静止）
     */
    abstract fun onStateChanged(appBarLayout: AppBarLayout?, state: State?)

    /**
     * 定义 AppBarLayout 的状态枚举。
     * EXPANDED 表示展开状态，COLLAPSED 表示收起状态，IDLE 表示滑动中或静止未完全展开/收起。
     */
    enum class State {
        EXPANDED,   // 完全展开
        COLLAPSED,  // 完全收起
        IDLE        // 静止或滑动中
    }
}
