package com.ebook.book.view

import androidx.test.core.app.ApplicationProvider
import com.xrn1997.common.BaseApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReadBookControl.settings] 的回归：每次写入推一份新快照，越界值不推。
 *
 * 页面（`ReadBookScreen`）与字体/设置面板都收集这份快照，原先的四个页面级镜像
 * （`textKindVersion` / `bgVersion` / `clickTurnEnabled` / `turnModeIndex`）已删除。
 * 快照不再更新就等于「改了设置但正文不动」——这正是要锁住的行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadBookControlTest {

    @Before
    fun setUp() {
        // ReadBookControl 是 object：初始化时要真 Context（SPUtil / DisplayUtil），与仓内其他
        // Robolectric 用例同一跑法
        BaseApplication.context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `updateTextKindIndex 推进 settings 的字号与索引`() {
        val before = ReadBookControl.settings.value
        val target = if (before.textKindIndex == ReadBookControl.DEFAULT_TEXT) 0 else ReadBookControl.DEFAULT_TEXT

        ReadBookControl.updateTextKindIndex(target)

        val after = ReadBookControl.settings.value
        assertEquals(target, after.textKindIndex)
        assertNotEquals(before.textSize, after.textSize)
        assertEquals(ReadBookControl.textSize, after.textSize)
        assertEquals(ReadBookControl.textExtra, after.textExtra)
    }

    @Test
    fun `越界字号被忽略且不推快照`() {
        ReadBookControl.updateTextKindIndex(0)
        val before = ReadBookControl.settings.value

        ReadBookControl.updateTextKindIndex(99)

        assertSame("越界索引不改变状态，也不该发布新快照", before, ReadBookControl.settings.value)
    }

    @Test
    fun `updateTextDrawableIndex 推进 settings 的配色`() {
        val before = ReadBookControl.settings.value
        val target = if (before.textDrawableIndex == ReadBookControl.DEFAULT_BG) 0 else ReadBookControl.DEFAULT_BG

        ReadBookControl.updateTextDrawableIndex(target)

        val after = ReadBookControl.settings.value
        assertEquals(target, after.textDrawableIndex)
        assertEquals(ReadBookControl.textColor, after.textColor)
        assertEquals(ReadBookControl.textBackground, after.textBackground)
    }

    @Test
    fun `setCanClickTurn 推进 settings`() {
        ReadBookControl.setCanClickTurn(false)

        assertEquals(false, ReadBookControl.settings.value.canClickTurn)

        ReadBookControl.setCanClickTurn(true)

        assertEquals(true, ReadBookControl.settings.value.canClickTurn)
    }

    @Test
    fun `setCanKeyTurn 推进 settings`() {
        ReadBookControl.setCanKeyTurn(false)

        assertEquals(false, ReadBookControl.settings.value.canKeyTurn)

        ReadBookControl.setCanKeyTurn(true)

        assertEquals(true, ReadBookControl.settings.value.canKeyTurn)
    }

    @Test
    fun `updateTurnModeIndex 推进 settings`() {
        ReadBookControl.updateTurnModeIndex(1)

        assertEquals("滚屏档必须真的推进快照，否则页面收不到新值", 1, ReadBookControl.settings.value.turnModeIndex)
    }

    @Test
    fun `turnModeIndex 越界被忽略`() {
        ReadBookControl.updateTurnModeIndex(0)
        val before = ReadBookControl.settings.value

        ReadBookControl.updateTurnModeIndex(7)

        assertSame(before, ReadBookControl.settings.value)
        assertEquals(0, ReadBookControl.settings.value.turnModeIndex)
    }
}
