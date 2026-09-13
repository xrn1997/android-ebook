# 阅读器上下滚屏模式 施工计划

> **状态说明（2026-09-13 落地后补记）**：本计划已实施完毕，但「上下滚屏 = 整章屏块的 `LazyColumn`」这一
> 容器形态已被同日的后续决策撤销——滚屏列表改成**跨章连续**（章段 = 标题项 + 块项，进入某章即物化相邻
> 两章），章界的「上一章/下一章」链接项已删除，保留集裁剪也从「只有翻页一端做」改成两端都做（滚屏按
> 章距 ±2）。故下文 Task 4/5 里的 `leadingItemCount`、`goPrevChapter`/`goNextChapter`、`blockCount`/
> `targetBlock` 等符号在代码中已不存在，示例代码不可照抄；勾选框状态一律不代表待办。
> 现行事实源：`docs/adr/0037-reader-turn-mode.md` 与 `ReaderScrollController` 类 KDoc。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给阅读器增加「翻页方式」选择，在现有左右翻页之外新增上下滚屏模式。

**Architecture:** 两种模式共用同一条分块链（`ReaderTypesetter` 断行 → `pageLineCount` → `loadPage` 切子串），差别只在容器：左右翻页是三页窗口 + 横向拖拽，上下滚屏是整章屏块的 `LazyColumn` + 竖向自由滚动。加载去重/三态/prune 抽成共享的 `ReaderPageStore`，两个前端控制器各自只管一种容器的几何。进度仍是 `(durChapter, durChapterPage)`，零 schema 改动。

**Tech Stack:** Kotlin、Jetpack Compose（`LazyColumn`/`Animatable`/`awaitEachGesture`）、Hilt、Room（不改）、JUnit4 + Robolectric。

**事实源：** `docs/superpowers/specs/2026-09-13-reader-scroll-mode-design.md`（下称 spec）。本计划与 spec 冲突时以 spec 为准，并回写本计划。

**与 spec 的一处偏离（已确认）**：spec §13 列了 `ReadBookControlTest`，本计划不写它。理由：`ReadBookControl` 是 `object`，类初始化即读 `BaseApplication.context` 并调 `DisplayUtil.dp2px`（`ReadBookControl.kt:32-39`），在 JVM/Robolectric 下拿不到应用图，测试会卡在单例初始化而不是被测逻辑上；其越界回落是一行、且与已在线上的 `textKindIndex` 同写法。改为把可测的纯逻辑（`fitLines`、落点换算、控制器状态机）各自单测。实施时同步修订 spec §13。

---

## 文件结构

新增：

| 文件 | 职责 |
| --- | --- |
| `module_book/src/main/java/com/ebook/book/reader/ReaderPageStore.kt` | key → `ReaderPageUi` 的加载仓库：job 去重与身份比对、三态、按保留集 prune |
| `module_book/src/main/java/com/ebook/book/reader/ReaderScrollController.kt` | 滚屏前端：章内块列表、滚动落点（含哨兵解析）、章间衔接、进度上报 |
| `module_book/src/main/java/com/ebook/book/reader/ReaderScroll.kt` | 滚屏容器 Composable：视口测量、标题/上下章 header-footer、块渲染、常驻位置行 |
| `module_book/src/test/java/com/ebook/book/reader/ReaderPageStoreTest.kt` | 仓库层回归 |
| `module_book/src/test/java/com/ebook/book/reader/ReaderScrollControllerTest.kt` | 滚屏状态机回归 |
| `module_book/src/test/java/com/ebook/book/reader/ReaderBlockMetricsTest.kt` | `fitLines` 纯函数回归 |
| `docs/adr/00XX-reader-turn-mode.md` | ADR（编号取当前最大值 +1） |

修改：

| 文件 | 改动 |
| --- | --- |
| `reader/ReaderTypesetter.kt` | 抽出纯函数 `fitLines`，增 `measureBlock`/`ReaderBlockMetrics`，`fitRenderLineCount` 改为委托 |
| `reader/ReaderPager.kt` | `ReaderPagerController` 的加载部分让位给 `ReaderPageStore`（构造签名与外部行为不变） |
| `view/ReadBookControl.kt` | `TurnMode` 枚举、`turnModeIndex`、`turnMode`、`updateTurnModeIndex` |
| `reader/ReaderPanels.kt` | `PanelChoiceRow`（private）、`MoreSettingPanel` 增「翻页方式」单选卡与 `onTurnModeChanged` |
| `ReadBookActivity.kt` | 按模式分派容器与按键、模式切换的落点换算、滚屏视口的重分页触发 |
| `res/values/strings.xml` | 新增 §Task 6 列出的字符串 |
| `agents.md` | Compose 体系一节补滚屏模式的分层口径 |

---

## Task 1: 抽出 `ReaderPageStore`（纯重构，翻页行为零变化）

**Files:**
- Create: `module_book/src/main/java/com/ebook/book/reader/ReaderPageStore.kt`
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderPager.kt:112-347`
- Test: `module_book/src/test/java/com/ebook/book/reader/ReaderPagerControllerTest.kt`（**一字不改**，作为重构的安全网）

- [ ] **Step 1: 先跑现有测试确认基线绿**

Run: `./gradlew :module_book:testDebugUnitTest --tests "*ReaderPagerControllerTest*"`
Expected: 7 个用例全 PASS。这是重构前的基线，红了就不要往下走。

- [ ] **Step 2: 新建 `ReaderPageStore.kt`**

把 `ReaderPagerController` 里的 `pages`/`jobs`/`ensureLoad`/`reload` 与 `prune` 原样搬过来，KDoc 一并搬（那些注释记的是竞态结论，不是装饰）。新增 `retain(keep)` 取代 `prune`——保留集由调用方给，翻页模式传三键、滚屏模式传可见邻域，这是两个前端唯一需要不同的地方。

```kotlin
package com.ebook.book.reader

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 阅读页的加载仓库：[ReaderPageKey] → [ReaderPageUi]，两种翻页方式共用。
 *
 * 只回答「这一屏的内容是什么状态」，不含任何几何（横向拖拽见 [ReaderPagerController]，
 * 竖向滚动见 [ReaderScrollController]）。抽出来的理由是两类前端都需要同一套竞态处置，
 * 而那套处置的结论很贵（见下），不该在两个类里各写一遍再各自漂移。
 *
 * 竞态说明（原 [ReaderPagerController] 的结论，搬移时一并保留）：页面加载以
 * [ReaderPageKey] 为键去重（在途 job 与已 Loaded 的页都不重复请求）；加载完成后经
 * [onLoaded] 回调通知前端重算窗口/块列表。跳转时由前端调 [clear] 取消全部在途任务。
 */
internal class ReaderPageStore(
    private val scope: CoroutineScope,
    private val loadPage: suspend (chapterIndex: Int, pageIndex: Int) -> ReaderPageUi.Loaded?,
) {

    private val pages = mutableStateMapOf<ReaderPageKey, ReaderPageUi>()
    private val jobs = mutableMapOf<ReaderPageKey, Job>()

    /**
     * 加载完成回调（仅成功时触发）。
     *
     * 前端用它重算自己的几何：翻页模式判 `key == durKey` 后重算三页窗口，
     * 滚屏模式判 `key.chapterIndex == chapterIndex` 后落定块总数与滚动落点。
     * 判据由前端给，仓库不关心。
     */
    var onLoaded: ((ReaderPageKey, ReaderPageUi.Loaded) -> Unit)? = null

    /** 取指定页渲染状态（未登记按加载中兜底） */
    fun uiOf(key: ReaderPageKey?): ReaderPageUi = key?.let { pages[it] } ?: ReaderPageUi.Loading

    fun ensureLoad(key: ReaderPageKey) {
        if (jobs.containsKey(key)) return
        // 已就绪的页不重抓：job 完成即从 [jobs] 注销，只看 jobs 去重会让前端重算把仍是
        // Loaded 的来路页打回 Loading 再抓一遍（快速回翻时刚读过的那页会闪一下转圈，
        // 白跑一次 DB/网络 + 整章重排）。翻页只改窗口、不改排版，Loaded 的正文不会失效；
        // 真正的换装点（字号/跳章/换模式）走 [clear]，那里已清空 [pages]，不受本短路影响；
        // [reload] 只挂在错误态重试按钮上（Error 不是 Loaded），也不会被挡。
        if (pages[key] is ReaderPageUi.Loaded) return
        pages[key] = ReaderPageUi.Loading
        jobs[key] = scope.launch {
            val myJob = coroutineContext[Job]
            val loaded = loadPage(key.chapterIndex, key.pageIndex)
            // 仅当本协程仍是该 key 的当前登记任务时注销：若中途被 clear/reload/retain
            // 取消并移出 jobs，后继任务可能已用同 key 重新登记，此处无条件删除会把后继任务
            // 误删成孤儿（完成后用陈旧页覆盖已清空的窗口）。身份比对 + isActive 双保险。
            if (myJob?.isActive == true && jobs[key] === myJob) jobs.remove(key)
            if (isActive.not()) return@launch
            if (loaded != null) {
                pages[key] = loaded
                onLoaded?.invoke(key, loaded)
            } else {
                pages[key] = ReaderPageUi.Error
            }
        }
    }

    /** 加载失败重试（只挂在错误态的重试按钮上） */
    fun reload(key: ReaderPageKey) {
        jobs.remove(key)?.cancel()
        ensureLoad(key)
    }

    /** 跳转/换装点：取消全部在途任务并清空状态 */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pages.clear()
    }

    /** 清理保留集之外的页面状态与在途任务，防止内存累积。保留集由前端按自己的几何给出 */
    fun retain(keep: Set<ReaderPageKey>) {
        pages.keys.toList().filter { it !in keep }.forEach {
            pages.remove(it)
            jobs.remove(it)?.cancel()
        }
    }
}
```

- [ ] **Step 3: `ReaderPagerController` 改为持有仓库**

构造签名、`internal` 可见性、全部公开成员与行为**不变**（测试是判据）。删掉 `pages`/`jobs` 字段与 `ensureLoad`/`reload` 的实现体，改成转发；`prune()` 改成 `store.retain(setOfNotNull(durKey, prevKey, nextKey))`；`setInitData` 里的三行清理改成 `store.clear()`。

`ReaderPagerController.kt` 内的具体改动：

```kotlin
// 字段区：删掉 pages / jobs，增
private val store = ReaderPageStore(scope, loadPage).apply {
    // 完成时若该页已是当前页（翻页途中加载完成），立即重算窗口
    onLoaded = { key, loaded -> if (key == durKey) refreshWindow(loaded) }
}

// uiOf 改为转发
fun uiOf(key: ReaderPageKey?): ReaderPageUi = store.uiOf(key)

// setInitData 开头的三行（jobs.values.forEach cancel / jobs.clear / pages.clear）改为一行
store.clear()

// refreshWindow 末尾两行 ensureLoad 改为
prevKey?.let(store::ensureLoad)
nextKey?.let(store::ensureLoad)

// reload 改为转发
fun reload(key: ReaderPageKey) = store.reload(key)

// prune 改为
private fun prune() = store.retain(setOfNotNull(durKey, prevKey, nextKey))
```

`ensureLoad` 这个私有方法整体删除（调用点都已改走 `store`）。类 KDoc 里「竞态说明」那一段改成指向 `ReaderPageStore`，**保留**「窗口收敛规则」那一段（它属于窗口前端，不属于仓库）。

- [ ] **Step 4: 跑测试，必须与 Step 1 同样绿**

Run: `./gradlew :module_book:testDebugUnitTest --tests "*ReaderPagerControllerTest*"`
Expected: 同样 7 个 PASS，且**测试文件未被修改**（`git diff --stat` 里不出现它）。

若有用例变红：不是测试要改，是重构改动了翻页行为。回去比对 `ensureLoad` 的短路顺序与 `onLoaded` 的触发时机——原实现里「完成时若该页已是当前页则重算窗口」是在 `pages[key] = loaded` **之后**，顺序颠倒会让 `refreshWindow` 读到还没落定的状态。

- [ ] **Step 5: 新建 `ReaderPageStoreTest.kt`**

仓库层现在有了独立边界，补它自己的测试（窗口收敛仍由 `ReaderPagerControllerTest` 锁）。

```kotlin
package com.ebook.book.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderPageStore] 的回归测试（纯 JVM，不渲染、不依赖 Context）。
 *
 * 锁三条仓库层自己的口径，它们与前端几何无关、两种翻页方式都依赖：
 * 在途去重、已 Loaded 不重抓、[retain] 只清保留集之外。
 * 窗口收敛规则不在这里——那是翻页前端的事，由 ReaderPagerControllerTest 锁。
 */
class ReaderPageStoreTest {

    @Test
    fun `在途页不重复发请求`() = runTest {
        val book = FakeStoreBook()
        book.gates[KEY] = CompletableDeferred()
        val store = newStore(book)

        store.ensureLoad(KEY)
        store.ensureLoad(KEY)
        advanceUntilIdle()

        assertEquals("在途 job 已登记，第二次 ensureLoad 必须被挡下", 1, book.requests[KEY])
    }

    @Test
    fun `已加载的页不重抓`() = runTest {
        val book = FakeStoreBook()
        val store = newStore(book)

        store.ensureLoad(KEY)
        advanceUntilIdle()
        store.ensureLoad(KEY)
        advanceUntilIdle()

        assertEquals("job 完成即注销，只看 jobs 去重会让刚读过的页闪一下加载态", 1, book.requests[KEY])
        assertTrue(store.uiOf(KEY) is ReaderPageUi.Loaded)
    }

    @Test
    fun `加载失败置错误态且 reload 会重发`() = runTest {
        val book = FakeStoreBook()
        book.fail(KEY)
        val store = newStore(book)

        store.ensureLoad(KEY)
        advanceUntilIdle()
        assertEquals(ReaderPageUi.Error, store.uiOf(KEY))

        store.reload(KEY)
        advanceUntilIdle()

        assertEquals(2, book.requests[KEY])
    }

    @Test
    fun `retain 清掉保留集之外的页并取消其在途任务`() = runTest {
        val book = FakeStoreBook()
        book.gates[OTHER] = CompletableDeferred()
        val store = newStore(book)

        store.ensureLoad(KEY)
        store.ensureLoad(OTHER)
        advanceUntilIdle()

        store.retain(setOf(KEY))

        assertTrue("保留集内的页不动", store.uiOf(KEY) is ReaderPageUi.Loaded)
        assertEquals(
            "保留集外的在途任务被取消后，uiOf 回到加载中兜底而不是残留陈旧状态",
            ReaderPageUi.Loading, store.uiOf(OTHER)
        )
    }

    @Test
    fun `onLoaded 只在成功时回调一次`() = runTest {
        val book = FakeStoreBook()
        val hits = mutableListOf<ReaderPageKey>()
        val store = ReaderPageStore(this) { c, p -> book.load(c, p) }.apply {
            onLoaded = { key, _ -> hits += key }
        }

        store.ensureLoad(KEY)
        advanceUntilIdle()

        assertEquals(listOf(KEY), hits)
    }

    private companion object {
        val KEY = ReaderPageKey(0, 0)
        val OTHER = ReaderPageKey(0, 1)
    }
}

/** 假书：只够仓库层用（无章节数概念），失败与闸门语义与 ReaderPagerControllerTest 的 FakeBook 一致 */
private class FakeStoreBook {
    val requests = mutableMapOf<ReaderPageKey, Int>()
    val gates = mutableMapOf<ReaderPageKey, CompletableDeferred<Unit>>()
    private val failures = mutableSetOf<ReaderPageKey>()

    fun fail(vararg keys: ReaderPageKey) {
        failures += keys
    }

    suspend fun load(chapterIndex: Int, pageIndex: Int): ReaderPageUi.Loaded? {
        val key = ReaderPageKey(chapterIndex, pageIndex)
        requests[key] = (requests[key] ?: 0) + 1
        gates[key]?.await()
        if (key in failures) return null
        return ReaderPageUi.Loaded(
            title = "第${chapterIndex + 1}章",
            chapterIndex = chapterIndex,
            durPageIndex = pageIndex,
            pageAll = 3,
            text = "正文 $chapterIndex-$pageIndex"
        )
    }
}

private fun TestScope.newStore(book: FakeStoreBook) =
    ReaderPageStore(this) { c, p -> book.load(c, p) }
```

- [ ] **Step 6: 跑全部 module_book 单测**

Run: `./gradlew :module_book:testDebugUnitTest`
Expected: 全 PASS（新增 5 个 + 原有全部）。

- [ ] **Step 7: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ReaderPageStore.kt \
        module_book/src/main/java/com/ebook/book/reader/ReaderPager.kt \
        module_book/src/test/java/com/ebook/book/reader/ReaderPageStoreTest.kt
git commit -m "refactor(module_book): 抽出阅读器加载仓库 ReaderPageStore

把三页窗口状态机里的加载去重、三态与 prune 抽成与几何无关的仓库层，
为上下滚屏模式复用同一套竞态处置做准备；翻页行为零变化，
ReaderPagerControllerTest 未改动且原样通过。"
```

---

## Task 2: `ReaderTypesetter` 增 `measureBlock`（块高必须实测）

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderTypesetter.kt:52-95`
- Test: `module_book/src/test/java/com/ebook/book/reader/ReaderBlockMetricsTest.kt`

spec §5：块高不能用 `pageLineCount × lineHeight` 心算（那又是拿度量猜渲染几何，正是 `fitRenderLineCount` KDoc 记着的老病根），必须由排版器实测。把「扫描行底部」这段纯逻辑抽成可测的 `fitLines`，测量入口只剩「取 layout → 喂 lineBottoms」。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [fitLines] 的回归测试（纯函数，无排版引擎依赖）。
 *
 * 锁两条：块高取「最后一行的实测底部」而不是行数乘行高（后者会差出零点几像素×行数），
 * 以及视口放不下任何一行时返回 null（调用方据此不分块，而不是切出 0 行的空块）。
 */
class ReaderBlockMetricsTest {

    @Test
    fun `块高取放得下的最后一行的实测底部`() {
        // 每行 40px 高，视口 100px → 放得下 2 行，块高 80（不是 100，也不是 2×行高的估算值）
        val metrics = fitLines(intArrayOf(40, 80, 120, 160), viewportHeightPx = 100)
        assertEquals(ReaderBlockMetrics(lineCount = 2, heightPx = 80), metrics)
    }

    @Test
    fun `视口恰好等于行底部时该行算放得下`() {
        val metrics = fitLines(intArrayOf(40, 80), viewportHeightPx = 80)
        assertEquals(ReaderBlockMetrics(lineCount = 2, heightPx = 80), metrics)
    }

    @Test
    fun `一行都放不下时返回 null`() {
        assertNull(fitLines(intArrayOf(40, 80), viewportHeightPx = 20))
    }

    @Test
    fun `空布局返回 null`() {
        assertNull(fitLines(intArrayOf(), viewportHeightPx = 100))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :module_book:testDebugUnitTest --tests "*ReaderBlockMetricsTest*"`
Expected: 编译失败，`unresolved reference: fitLines`

- [ ] **Step 3: 实现**

在 `ReaderTypesetter.kt` 文件末尾（`readerBodyTextStyle` 之后）加纯函数与数据类：

```kotlin
/**
 * 一屏正文块的度量：放得下几行 + 这几行的实测总高。
 *
 * [heightPx] 是滚屏模式的**块高**：块与块要精确无缝拼接，高度必须取渲染引擎实测的
 * 行底部，不能用 `lineCount × lineHeight` 心算——那是拿度量猜几何，每行差零点几像素、
 * 25 行累计近 20px（同 [ReaderTypesetter.fitRenderLineCount] KDoc 记的老病根）。
 */
internal data class ReaderBlockMetrics(val lineCount: Int, val heightPx: Int)

/**
 * 给定每行的实测底部位置，求视口放得下几行、以及这几行的实测总高。
 *
 * 纯函数（不碰排版引擎），故可单测；[ReaderTypesetter.measureBlock] 与
 * [ReaderTypesetter.fitRenderLineCount] 都由它给结论，两者不可能算出不同的行数。
 *
 * @param lineBottoms 逐行的实测底部位置（升序），取自探针布局
 * @return 一行都放不下（或布局为空）时为 null——调用方据此不分块，
 *   而不是切出 0 行的空块让页面永远空白
 */
internal fun fitLines(lineBottoms: IntArray, viewportHeightPx: Int): ReaderBlockMetrics? {
    var fit = 0
    for (i in lineBottoms.indices) {
        if (lineBottoms[i] > viewportHeightPx) break
        fit = i + 1
    }
    if (fit == 0) return null
    return ReaderBlockMetrics(lineCount = fit, heightPx = lineBottoms[fit - 1])
}
```

把 `fitRenderLineCount` 改成委托，并新增 `measureBlock`（替换 `ReaderTypesetter.kt:65-74` 整个方法体）：

```kotlin
    /**
     * 正文区高度放得下多少渲染行（翻页模式用：它只需要行数）。
     *
     * 不再用 `(高度 - 段距) / (字高 + 段距)` 估算——该公式拿平台字体度量算字高，
     * 与 Compose 实际行高（含整数进位）每行差约 0.7px，25 行累计近 20px，
     * 叠上「分行行数 != 渲染行数」就会把整行挤出可见区。这里直接问渲染引擎本身。
     */
    fun fitRenderLineCount(widthPx: Int, heightPx: Int): Int =
        measureBlock(widthPx, heightPx)?.lineCount ?: 0

    /**
     * 一次测量同时给出「视口放得下几行」与「这几行的实测总高」（滚屏模式用：块高要精确）。
     *
     * 与 [fitRenderLineCount] 共用同一把探针与同一条 [fitLines] 判据，
     * 因此两种翻页方式对「一屏几行」的回答只可能一致。
     */
    fun measureBlock(widthPx: Int, viewportHeightPx: Int): ReaderBlockMetrics? {
        if (widthPx <= 0 || viewportHeightPx <= 0) return null
        val layout = measure(probeText, widthPx)
        return fitLines(IntArray(layout.lineCount) { layout.getLineBottom(it) }, viewportHeightPx)
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :module_book:testDebugUnitTest --tests "*ReaderBlockMetricsTest*" --tests "*ReaderPagerControllerTest*"`
Expected: 全 PASS。`fitRenderLineCount` 改委托后翻页模式行为不变，由 `ReaderPagerControllerTest` 与既有分页链路兜住。

- [ ] **Step 5: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ReaderTypesetter.kt \
        module_book/src/test/java/com/ebook/book/reader/ReaderBlockMetricsTest.kt
git commit -m "feat(module_book): 排版器增 measureBlock 给出实测块高

滚屏模式的块与块要精确无缝拼接，块高必须取渲染引擎实测的行底部，
不能用行数乘行高心算。扫描行底部的纯逻辑抽成 fitLines 以便单测，
fitRenderLineCount 改为委托，两种翻页方式对「一屏几行」只可能同解。"
```

---

## Task 3: `ReadBookControl` 增翻页方式

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/view/ReadBookControl.kt`

不写单测，理由见本计划开头「与 spec 的一处偏离」。实现严格照抄同文件 `textKindIndex` 的既有写法（含越界回落），不发明新花样。

- [ ] **Step 1: 加枚举与列表**

在 `ReadBookControl` 内、`TextDrawable` 之后加：

```kotlin
    /**
     * 翻页方式。
     *
     * 落 SP 的是 [turnModeList] 的**索引**而不是枚举名（与 textKindIndex/textDrawableIndex
     * 同口径）：索引越界能回落默认，枚举名一旦改名就会让老数据解析失败。
     * 枚举只活在内存里，不进任何持久化字段。
     */
    enum class TurnMode {
        /** 左右翻页：三页窗口 + 横向拖拽 */
        PAGE,

        /** 上下滚屏：整章屏块的竖向连续滚动 */
        SCROLL,
    }

    /** 翻页方式列表（顺序即设置面板的展示顺序） */
    private val turnModeList: List<TurnMode> = listOf(TurnMode.PAGE, TurnMode.SCROLL)
```

在常量区加：

```kotlin
    const val DEFAULT_TURN_MODE = 0
```

- [ ] **Step 2: 加内存缓存属性**

在 `canKeyTurn` 之后加：

```kotlin
    var turnModeIndex: Int
        private set

    /** 当前翻页方式（= turnModeList[turnModeIndex]，索引已在 init 里钳过界） */
    val turnMode: TurnMode
        get() = turnModeList[turnModeIndex]
```

- [ ] **Step 3: init 里读 SP**

在 `canKeyTurn = SPUtil.get(...)` 之后加：

```kotlin
        turnModeIndex = SPUtil.get("turnModeIndex", DEFAULT_TURN_MODE, SP_NAME)
        // 越界回落默认：默认是 PAGE，老用户升级后行为不变，不会一觉醒来变成滚屏
        if (turnModeIndex !in turnModeList.indices) turnModeIndex = DEFAULT_TURN_MODE
```

- [ ] **Step 4: 加更新方法**

在 `setCanKeyTurn` 之后加：

```kotlin
    fun updateTurnModeIndex(index: Int) {
        if (index !in turnModeList.indices) return
        turnModeIndex = index
        SPUtil.put("turnModeIndex", index, SP_NAME)
    }

    fun getTurnModeList(): List<TurnMode> = turnModeList
```

- [ ] **Step 5: 编译**

Run: `./gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，无新警告。

- [ ] **Step 6: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/view/ReadBookControl.kt
git commit -m "feat(module_book): 阅读配置增翻页方式（左右翻页/上下滚屏）

落 SP 的是列表索引而非枚举名，沿用 textKindIndex 的越界回落写法；
默认 0 = 左右翻页，老用户升级后行为不变。"
```

---

## Task 4: `ReaderScrollController`（滚屏状态机）

**Files:**
- Create: `module_book/src/main/java/com/ebook/book/reader/ReaderScrollController.kt`
- Test: `module_book/src/test/java/com/ebook/book/reader/ReaderScrollControllerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.book.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.event.DBCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReaderScrollController] 的回归测试（Robolectric 仅供 Context，不渲染任何 View）。
 *
 * 锁五条滚屏特有的口径，翻页模式的窗口收敛不在这里（见 ReaderPagerControllerTest）：
 * 块总数由首个加载结果落定、哨兵落点解析、前导 item 数随首末章变化、
 * 章间衔接、进度上报与翻页模式同一个回调签名。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderScrollControllerTest {

    @Test
    fun `块总数在首个加载结果到达前为 0`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        book.gates[ReaderPageKey(0, 0)] = CompletableDeferred()
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()

        assertEquals("排版未完成时块数未知，LazyColumn 只画加载态而不是一堆空块", 0, controller.blockCount)

        book.gates.getValue(ReaderPageKey(0, 0)).complete(Unit)
        advanceUntilIdle()

        assertEquals(3, controller.blockCount)
    }

    @Test
    fun `章末哨兵解析为最后一块`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_END)
        advanceUntilIdle()

        assertEquals(2, controller.targetBlock)
    }

    @Test
    fun `越界落点钳到章内`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(0, 99)
        advanceUntilIdle()

        assertEquals(2, controller.targetBlock)
    }

    @Test
    fun `前导 item 数 第一章为 1 中间章为 2`() = runTest {
        val book = FakeScrollBook(listOf(3, 4, 5))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        assertEquals("第一章没有上一章 header", 1, controller.leadingItemCount)

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        assertEquals(2, controller.leadingItemCount)
    }

    @Test
    fun `滚到最后一块后下一屏切到下一章章首`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        controller.onScrolledTo(2)
        advanceUntilIdle()

        controller.scrollOneScreen(forward = true)
        advanceUntilIdle()

        assertEquals("章末的下一屏是下一章，不是弹「没有下一页」", 1, controller.chapterIndex)
        assertEquals(0, controller.targetBlock)
        assertEquals(4, controller.blockCount)
    }

    @Test
    fun `最后一章的末块再下一屏才提示没有下一页`() = runTest {
        val book = FakeScrollBook(listOf(3))
        val controller = newScroll(book)

        controller.setInitData(0, 2)
        advanceUntilIdle()

        controller.scrollOneScreen(forward = true)
        advanceUntilIdle()

        assertEquals(0, controller.chapterIndex)
        assertEquals("整本书末尾不推进", 2, controller.targetBlock)
    }

    @Test
    fun `第一块再上一屏切到上一章末尾`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()

        controller.scrollOneScreen(forward = false)
        advanceUntilIdle()

        assertEquals(0, controller.chapterIndex)
        assertEquals("回退落在上一章最后一块", 2, controller.targetBlock)
    }

    @Test
    fun `进度经与翻页模式同一个回调上报已解析的块号`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val progress = mutableListOf<Pair<Int, Int>>()
        val controller = newScroll(book, onProgress = { c, p -> progress += c to p })

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        controller.onScrolledTo(1)
        advanceUntilIdle()

        assertEquals(listOf(0 to 0, 0 to 1), progress)
    }

    @Test
    fun `块总数不因某块加载失败而改变`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        book.fail(ReaderPageKey(0, 1))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        controller.onScrolledTo(1)
        advanceUntilIdle()

        assertEquals("失败块仍占一屏高，滚动位置不塌陷", 3, controller.blockCount)
        assertEquals(ReaderPageUi.Error, controller.uiOfBlock(1))
    }
}

/** 假书：每章块数由入参给定；load 的哨兵解析与生产 loadPage 同一套语义 */
private class FakeScrollBook(val blocksPerChapter: List<Int>) {
    val requests = mutableMapOf<ReaderPageKey, Int>()
    val gates = mutableMapOf<ReaderPageKey, CompletableDeferred<Unit>>()
    private val failures = mutableSetOf<ReaderPageKey>()

    fun fail(vararg keys: ReaderPageKey) {
        failures += keys
    }

    suspend fun load(chapterIndex: Int, pageIndex: Int): ReaderPageUi.Loaded? {
        val key = ReaderPageKey(chapterIndex, pageIndex)
        requests[key] = (requests[key] ?: 0) + 1
        gates[key]?.await()
        if (key in failures) return null
        val resolved = when (pageIndex) {
            DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN -> 0
            DBCode.BookContentView.DUR_PAGE_INDEX_END -> blocksPerChapter[chapterIndex] - 1
            else -> pageIndex.coerceIn(0, blocksPerChapter[chapterIndex] - 1)
        }
        return ReaderPageUi.Loaded(
            title = "第${chapterIndex + 1}章",
            chapterIndex = chapterIndex,
            durPageIndex = resolved,
            pageAll = blocksPerChapter[chapterIndex],
            text = "正文 $chapterIndex-$resolved"
        )
    }
}

private fun TestScope.newScroll(
    book: FakeScrollBook,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
) = ReaderScrollController(
    scope = this,
    context = ApplicationProvider.getApplicationContext<Context>(),
    chapterSize = { book.blocksPerChapter.size },
    chapterTitle = { "第${it + 1}章" },
    loadPage = { c, p -> book.load(c, p) },
    onProgress = onProgress,
)
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :module_book:testDebugUnitTest --tests "*ReaderScrollControllerTest*"`
Expected: 编译失败，`unresolved reference: ReaderScrollController`

- [ ] **Step 3: 实现 `ReaderScrollController.kt`**

```kotlin
package com.ebook.book.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.ebook.book.R
import com.ebook.db.event.DBCode
import com.xrn1997.common.util.ToastUtil
import kotlinx.coroutines.CoroutineScope

/**
 * 上下滚屏模式的控制器：一章的正文块列表 + 滚动落点 + 章间衔接。
 *
 * 与 [ReaderPagerController] 是**并列的两个前端**，共用 [ReaderPageStore]（加载去重与三态）
 * 与 `loadPage`（分块链），差别只在几何：那边是三页窗口 + 横向拖拽，这边是整章块列表 +
 * 竖向自由滚动。两边都上报同一个 `onProgress(chapterIndex, pageIndex)`，
 * 因此进度落库口径完全一致（`dur_chapter_page` = 章内第几屏），换模式不必改 schema。
 *
 * 块总数（[blockCount]）**在首个加载结果到达前是 0**：一屏几行由排版实测得出，
 * 排版没跑完就无从知道这章切几块。此时 LazyColumn 只画一个加载态，
 * 而不是先画一堆空块再回填——后者会让滚动位置在块数落定那一刻整体跳变。
 *
 * 哨兵页码沿用 [ReaderPageKey] 的语义（BEGIN/END 由加载结果解析），
 * 解析后的落点暴露在 [targetBlock]，由 UI 侧 `scrollToItem` 消费。
 */
internal class ReaderScrollController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val chapterSize: () -> Int,
    private val chapterTitle: (Int) -> String,
    loadPage: suspend (chapterIndex: Int, pageIndex: Int) -> ReaderPageUi.Loaded?,
    private val onProgress: (chapterIndex: Int, pageIndex: Int) -> Unit,
) {

    private val store = ReaderPageStore(scope, loadPage).apply {
        // 显式标签：这是赋值给属性的 lambda，没有隐式标签，写 return@apply 编译不过
        onLoaded = onLoaded@{ key, loaded ->
            if (key.chapterIndex != chapterIndex) return@onLoaded
            // 块总数只在首次落定与换章时变；同章内后续块的 pageAll 必然相同（同一份排版）
            if (loaded.pageAll != blockCount) {
                blockCount = loaded.pageAll
                pendingTarget = resolveTarget(loaded.durPageIndex)
            }
            store.retain(keepSet())
        }
    }

    /** 当前章索引 */
    var chapterIndex by mutableIntStateOf(0)
        private set

    /** 本章块总数；0 = 排版尚未落定（见类 KDoc） */
    var blockCount by mutableIntStateOf(0)
        private set

    /**
     * UI 应当滚到的块序号。
     *
     * 由 [setInitData] 的哨兵解析而来，块总数落定后会再解析一次
     * （END 要等知道共几块才能算出是第几块）。UI 侧 `LaunchedEffect(targetBlock)`
     * 消费它，消费后不必回写——下次跳转会给出新值。
     */
    var targetBlock by mutableIntStateOf(0)
        private set

    /** 当前读到的块（由 [onScrolledTo] 写入），进度上报与预取都以它为基准 */
    var currentBlock by mutableIntStateOf(0)
        private set

    /** 尚未解析的落点（可能是哨兵），块总数落定后解析进 [targetBlock] */
    private var pendingTarget: Int = DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN

    /**
     * LazyColumn 里块之前的前导 item 数。
     *
     * 中间章是 2（上一章 header + 章节标题 header），第一章只有 1（无上一章，见 spec §6），
     * 最后一章的章末 footer 在块之后、不影响前导数。
     * **块渲染侧必须按同一个值排布 item**：两侧各算一次的话，进度会整体偏移一屏，
     * 表现为恢复阅读时停在上一屏——不崩不报错，很难发现。
     */
    val leadingItemCount: Int
        get() = if (chapterIndex > 0) 2 else 1

    /** 是否有上一章 / 下一章（整本书首末没有，此时不画对应 header/footer） */
    val hasPrevChapter: Boolean get() = chapterIndex > 0
    val hasNextChapter: Boolean get() = chapterSize() > 0 && chapterIndex < chapterSize() - 1

    /** 相邻章标题（header/footer 文案用） */
    fun prevChapterTitle(): String = chapterTitle(chapterIndex - 1)
    fun nextChapterTitle(): String = chapterTitle(chapterIndex + 1)
    fun currentChapterTitle(): String = chapterTitle(chapterIndex)

    /** 取某块的渲染状态 */
    fun uiOfBlock(blockIndex: Int): ReaderPageUi = store.uiOf(ReaderPageKey(chapterIndex, blockIndex))

    /**
     * 初始化/跳转：取消在途加载，块列表收敛到指定章的指定块（哨兵由加载结果解析）。
     * 与 [ReaderPagerController.setInitData] 同一语义，故两边可共用调用点。
     */
    fun setInitData(chapterIndex: Int, pageIndex: Int) {
        store.clear()
        blockCount = 0
        currentBlock = 0
        pendingTarget = pageIndex
        targetBlock = resolveTarget(pageIndex)
        this.chapterIndex = chapterIndex
        store.ensureLoad(ReaderPageKey(chapterIndex, pageIndex))
        onProgress(chapterIndex, targetBlock)
    }

    /** 滚动位置变化：更新当前块并上报进度（UI 侧由 firstVisibleItemIndex 换算后调用） */
    fun onScrolledTo(blockIndex: Int) {
        if (blockCount == 0) return
        val clamped = blockIndex.coerceIn(0, blockCount - 1)
        if (clamped == currentBlock) return
        currentBlock = clamped
        onProgress(chapterIndex, clamped)
        // 预取相邻块：块内容是原文子串，同章内命中 ChapterLayoutCache 后很便宜，
        // 但不预取的话快速滚动会一路看着加载态
        (clamped - PREFETCH .. clamped + PREFETCH).forEach { i ->
            if (i in 0 until blockCount) store.ensureLoad(ReaderPageKey(chapterIndex, i))
        }
        store.retain(keepSet())
    }

    /**
     * 滚一屏（点击左右三分区 / 音量键）。
     *
     * 章边界与滚动手势语义一致：末块的下一屏 = 下一章章首，首块的上一屏 = 上一章末尾
     * （spec §10）。只有整本书的首末才提示「没有上一页/下一页」，
     * 判据与 [ReaderPagerController] 的 prevKey/nextKey 为 null 同源。
     */
    fun scrollOneScreen(forward: Boolean) {
        if (blockCount == 0) return
        if (forward) {
            if (currentBlock < blockCount - 1) {
                targetBlock = currentBlock + 1
                onScrolledTo(targetBlock)
            } else if (hasNextChapter) {
                setInitData(chapterIndex + 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
            } else {
                ToastUtil.showShort(context, context.getString(R.string.no_next_page))
            }
        } else {
            if (currentBlock > 0) {
                targetBlock = currentBlock - 1
                onScrolledTo(targetBlock)
            } else if (hasPrevChapter) {
                setInitData(chapterIndex - 1, DBCode.BookContentView.DUR_PAGE_INDEX_END)
            } else {
                ToastUtil.showShort(context, context.getString(R.string.no_prev_page))
            }
        }
    }

    /** 点章末 footer：切下一章章首 */
    fun goNextChapter() {
        if (hasNextChapter) setInitData(chapterIndex + 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
    }

    /** 点章首 header：切上一章末尾 */
    fun goPrevChapter() {
        if (hasPrevChapter) setInitData(chapterIndex - 1, DBCode.BookContentView.DUR_PAGE_INDEX_END)
    }

    /** 加载失败重试 */
    fun reload(blockIndex: Int) = store.reload(ReaderPageKey(chapterIndex, blockIndex))

    /** 哨兵/越界解析：BEGIN → 首块，END → 末块，其余钳到章内 */
    private fun resolveTarget(pageIndex: Int): Int = when {
        pageIndex == DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN -> 0
        pageIndex == DBCode.BookContentView.DUR_PAGE_INDEX_END -> (blockCount - 1).coerceAtLeast(0)
        blockCount == 0 -> 0
        else -> pageIndex.coerceIn(0, blockCount - 1)
    }

    /** 保留集：当前块 ± PREFETCH。翻页模式保留三键，这里保留可见邻域，是两端唯一的差别 */
    private fun keepSet(): Set<ReaderPageKey> =
        ((currentBlock - PREFETCH)..(currentBlock + PREFETCH))
            .filter { it in 0 until blockCount.coerceAtLeast(1) }
            .map { ReaderPageKey(chapterIndex, it) }
            .toSet()

    private companion object {
        /** 预取与保留的邻域半径（屏） */
        const val PREFETCH = 2
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :module_book:testDebugUnitTest --tests "*ReaderScrollControllerTest*"`
Expected: 9 个用例全 PASS。

若「块总数不因某块加载失败而改变」红：`store.retain` 在 `onLoaded` 里被调用时，失败块不在保留集内会被清掉——但失败态是 `ReaderPageUi.Error`，`retain` 会把它连同 key 一起移除，`uiOfBlock` 随即回到 `Loading` 兜底。修法是把保留集半径与 `uiOfBlock` 的可见范围对齐（都是 ±PREFETCH），而不是给失败态开特例。

- [ ] **Step 5: 跑全量单测确认没有连带回归**

Run: `./gradlew :module_book:testDebugUnitTest`
Expected: 全 PASS。

- [ ] **Step 6: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ReaderScrollController.kt \
        module_book/src/test/java/com/ebook/book/reader/ReaderScrollControllerTest.kt
git commit -m "feat(module_book): 新增滚屏模式控制器 ReaderScrollController

与翻页控制器并列的前端，共用 ReaderPageStore 与分块链：块总数由排版实测落定、
哨兵落点解析、章间衔接与整本书首末的提示判据同翻页模式一致，
进度仍按「章内第几屏」上报，落库口径不变。"
```

---

## Task 5: 滚屏容器 `ReaderScroll.kt`

**Files:**
- Create: `module_book/src/main/java/com/ebook/book/reader/ReaderScroll.kt`

无 JVM 单测（纯 Composable，几何与手感属人工装机验证项，见 spec §14）。结构严格按 spec §4 的布局草图。

- [ ] **Step 1: 实现容器**

```kotlin
package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebook.book.R

/**
 * 上下滚屏容器（与 [ReaderPager] 并列的另一种承载方式，见 spec §3/§4）。
 *
 * 布局：外层 Column 承担与 [ReaderPageCard] **完全相同**的 insets 与内边距口径，
 * 内部 LazyColumn 是滚动视口（`weight(1f)`，`onSizeChanged` 回报宽高供分块测算），
 * 视口下方一条常驻位置行——位置行留在滚动区**之外**而不是做覆盖层，
 * 正文就永远不会滚到它底下被压住（覆盖层要么遮最后一行，要么得做半透明/自动隐藏）。
 *
 * 块高由 [blockHeightPx] 给定（排版实测，见 [ReaderTypesetter.measureBlock]），
 * 每块恰好一屏、块与块精确无缝拼接。
 *
 * @param onViewportSizeChanged 视口宽高回调（喂 `ReadBookActivity.rePaginate` 测算一屏几行）
 * @param onCenterTap 点击中间三分之一区域唤出菜单（与翻页模式同一分区口径）
 */
@Composable
fun ReaderScroll(
    controller: ReaderScrollController,
    textColor: Color,
    bgColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    blockHeightPx: Int,
    canClickTurn: Boolean,
    onCenterTap: () -> Unit,
    onViewportSizeChanged: (widthPx: Int, heightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val leading = controller.leadingItemCount
    val touchSlop = LocalViewConfiguration.current.touchSlop

    // 落点：targetBlock 变化即滚过去（跳转、换章、点击/按键滚一屏都走这里）
    LaunchedEffect(controller.targetBlock, controller.blockCount) {
        if (controller.blockCount > 0) {
            listState.scrollToItem(controller.targetBlock + leading)
        }
    }

    // 滚动位置 → 当前块。扣掉前导 item 数（前导数随首末章变化，由控制器一处给出）
    val visibleBlock by remember {
        derivedStateOf { (listState.firstVisibleItemIndex - leading).coerceAtLeast(0) }
    }
    LaunchedEffect(visibleBlock) { controller.onScrolledTo(visibleBlock) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            // 与 ReaderPageCard 同一口径：阅读器 enableFitsSystemWindows=false，
            // 内容铺到屏幕边缘，不避让就会被系统手势条/三键栏压住
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(start = 15.dp, top = 5.dp, end = 15.dp, bottom = 5.dp)
            // 点击分区：左右三分区滚一屏（受「点击翻页」开关控制），中间唤出菜单。
            // 刻意**只判点击、不接管拖拽**：竖向滚动交给 LazyColumn 自己的滚动手势
            // （它带惯性与 fling，自己实现一套只会更差），这里若在 pointerInput 里消费
            // 竖向 drag 就会与它抢事件。故用 touchSlop 区分「点」与「滚」，滚动一律不消费。
            .pointerInput(controller, canClickTurn, touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val startX = down.position.x
                    val widthPx = size.width.toFloat()
                    var pointerX = startX
                    var moved = false
                    var pressed = true
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        pressed = change.pressed
                        pointerX = change.position.x
                        if (!moved && abs(pointerX - startX) > touchSlop) moved = true
                        if (!moved && abs(change.position.y - down.position.y) > touchSlop) moved = true
                    }
                    if (moved) return@awaitEachGesture
                    when {
                        canClickTurn && startX <= widthPx / 3 ->
                            controller.scrollOneScreen(forward = false)
                        canClickTurn && startX >= widthPx / 3 * 2 ->
                            controller.scrollOneScreen(forward = true)
                        else -> onCenterTap()
                    }
                }
            }
    ) {
        val blockHeightDp: Dp = with(LocalDensity.current) { blockHeightPx.toDp() }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 视口尺寸**只在这里回报一次**。绝不能让块自己也回报：块高是由视口高
                // 算出来的（measureBlock），块再把自己回报成"正文区高度"会让 rePaginate
                // 拿块高当视口高、算出更少的行数、得到更矮的块——一路自我收缩到空。
                .onSizeChanged { onViewportSizeChanged(it.width, it.height) }
        ) {
            if (controller.blockCount == 0) {
                // 排版未落定：只画一个加载态，不先画一堆空块再回填（块数落定那一刻
                // 会让滚动位置整体跳变，见 ReaderScrollController 类 KDoc）。
                // 高度不参与——视口尺寸由上面 LazyColumn 的 onSizeChanged 给出，
                // 与有没有 item 无关，故不存在"加载完才能测量、测量后才能分块"的死锁
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                        ScrollBlockState(
                            ui = ReaderPageUi.Loading,
                            textColor = textColor,
                            onRetry = {},
                        )
                    }
                }
            } else {
                if (controller.hasPrevChapter) {
                    item {
                        ScrollChapterLink(
                            text = stringResource(R.string.prev_chapter_format, controller.prevChapterTitle()),
                            textColor = textColor,
                            onClick = controller::goPrevChapter,
                        )
                    }
                }
                item {
                    Text(
                        text = controller.currentChapterTitle(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(0.7f)
                            .padding(bottom = 10.dp),
                        color = textColor,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                itemsIndexed(List(controller.blockCount) { it }) { _, blockIndex ->
                    ScrollBlock(
                        ui = controller.uiOfBlock(blockIndex),
                        textColor = textColor,
                        textSizeSp = textSizeSp,
                        lineHeight = lineHeight,
                        blockHeightDp = blockHeightDp,
                        onRetry = { controller.reload(blockIndex) },
                    )
                }
                if (controller.hasNextChapter) {
                    item {
                        ScrollChapterLink(
                            text = stringResource(R.string.next_chapter_format, controller.nextChapterTitle()),
                            textColor = textColor,
                            onClick = controller::goNextChapter,
                        )
                    }
                }
            }
        }

        // 常驻位置行：高度与翻页模式的页码行同一个 token，两种模式的位置指示节奏一致
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReaderPageTokens.pageNumberRowHeight),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (controller.blockCount > 0) {
                Text(
                    text = stringResource(
                        R.string.page_indicator_format,
                        controller.currentBlock + 1,
                        controller.blockCount,
                    ),
                    color = textColor,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 正文块：只含正文，不含标题与页码行（spec §4）。
 *
 * 标题变成章首会滚走的 header、页码变成视口外的常驻行——块内若再画一遍，
 * 每屏都会重复章节标题，且相邻两块正文之间出现一条纸张色空带。
 *
 * 块高固定为 [blockHeightDp]（排版实测的 lineCount 行总高），与内容无关，
 * 因此 Loading/Error 块照样占一屏高，滚动位置不会因某块加载失败而塌陷
 * （与 ReaderPageCard「正文区高度与页面状态无关」是同一个占位契约）。
 */
@Composable
private fun ScrollBlock(
    ui: ReaderPageUi,
    textColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    blockHeightDp: Dp,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(blockHeightDp)
    ) {
        ScrollBlockState(
            ui = ui,
            textColor = textColor,
            textSizeSp = textSizeSp,
            lineHeight = lineHeight,
            onRetry = onRetry,
        )
    }
}

/**
 * 块的三态内容（加载中 / 失败 / 正文），不定高。
 *
 * 从 [ScrollBlock] 拆出来的唯一理由：排版尚未落定时的占位也要画同一套加载态，
 * 但它没有块高可给（块高正是排版要算的东西）。配色全部由正文色按透明度派生——
 * 这三态同样画在纸上，属「阅读背景主题」层，正文层豁免深浅色切换。
 */
@Composable
private fun ScrollBlockState(
    ui: ReaderPageUi,
    textColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    onRetry: () -> Unit,
) {
    when (ui) {
        is ReaderPageUi.Loaded -> Text(
            text = ui.text,
            modifier = Modifier.fillMaxWidth(),
            color = textColor,
            // 样式必须与分页测量同一份：字号/行高/行高对齐任何一项不一致，
            // 「切几行」与「画几行」就会错开（契约见 readerBodyTextStyle）
            style = readerBodyTextStyle(textSizeSp, lineHeight),
        )
        is ReaderPageUi.Loading -> Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = textColor.copy(alpha = 0.35f),
                strokeWidth = 2.5.dp,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.loading),
                color = textColor.copy(alpha = 0.55f),
                fontSize = 14.sp,
            )
        }
        is ReaderPageUi.Error -> Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = textColor.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.reader_load_failed),
                color = textColor.copy(alpha = 0.8f),
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(textColor.copy(alpha = 0.07f))
                    .border(
                        width = 1.dp,
                        color = textColor.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.retry),
                    color = textColor.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                )
            }
        }
    }
}
```

`ScrollBlockState` 里的 `Modifier.align(Alignment.Center)` 要求父级是 `BoxScope`——
`ScrollBlock` 的外壳是 `Box`，占位那处也包在 `Box` 里，两处都满足。
若编译器报 `align` 不可见，把 `ScrollBlockState` 的接收者显式声明为
`@Composable BoxScope.() -> Unit` 形式即可（不要改成传 `Modifier` 进来，
那会让三态各自的居中语义散到调用点）。

```kotlin
/** 章首/章末的相邻章链接行 */
@Composable
private fun ScrollChapterLink(text: String, textColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor.copy(alpha = 0.55f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

- [ ] **Step 2: 补齐 import**

`ReaderScroll.kt` 需要而 Step 1 代码里用到的、容易漏的 import：

```kotlin
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf   // 若本文件未用到则不加
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
```

点击分区**不另抽 `Modifier` 扩展函数**：它要读 `LocalViewConfiguration.current.touchSlop`
（组合期才能取），抽成扩展就得套 `Modifier.composed`，而 `composed` 在新版 Compose 已弃用。
内联在 `ReaderScroll` 的修饰符链上既拿得到 `touchSlop`，也让「这一层只管点击、不管拖拽」
的边界与它的容器写在同一处。

- [ ] **Step 3: 编译**

Run: `./gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若报 `ReaderPageTokens` 不可见：它在 `ReaderPager.kt` 里是 `private object`，改成 `internal object`（同包内两个文件都要用它，private 只对声明文件可见）。

- [ ] **Step 4: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ReaderScroll.kt \
        module_book/src/main/java/com/ebook/book/reader/ReaderPager.kt
git commit -m "feat(module_book): 新增上下滚屏容器 ReaderScroll

块只含正文（标题滚走、位置行常驻视口外），块高取排版实测值故精确无缝拼接；
点击分区沿用左右三分区口径但只判点击不接管拖拽，滚动交给 LazyColumn 的惯性与 fling。"
```

---

## Task 6: 字符串与设置面板单选卡

**Files:**
- Modify: `module_book/src/main/res/values/strings.xml:91` 附近
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt:1187`（加 `PanelChoiceRow`）、`:1601`（`MoreSettingPanel`）

- [ ] **Step 1: 加字符串**

在 `reader_section_turn`（strings.xml:91）之后插入。**不改 `reader_section_turn` 的既有文案**——「翻页方式」正好是这一节的标题，模式单选与两个开关都归它。

```xml
    <string name="turn_mode_page">左右翻页</string>
    <string name="turn_mode_page_desc">横向滑动，一屏一屏地翻</string>
    <string name="turn_mode_scroll">上下滚屏</string>
    <string name="turn_mode_scroll_desc">竖向连续滚动，手指滚到哪读到哪</string>
    <string name="prev_chapter_format">上一章：%1$s</string>
    <string name="next_chapter_format">下一章：%1$s</string>
```

确认 `loading`、`retry`、`reader_load_failed`、`page_indicator_format`、`no_prev_page`、`no_next_page` 均已存在（strings.xml:29/35/36/37/38），不新增。

- [ ] **Step 2: 加 `PanelChoiceRow`**

紧跟在 `PanelSwitchRow`（ReaderPanels.kt:1187-1229）之后。结构与它逐行对齐（同一套 `ReaderChromeTokens` 尺寸、同样的「图标块 + 标题 + 说明 + 尾部控件」），只把尾部 `Switch` 换成 `RadioButton`——两档互斥的选择用 Switch 会被读成「开/关某个功能」而不是「二选一」。

```kotlin
/**
 * 面板单选行：36dp 彩色图标块 + 标题/说明 + 尾部 RadioButton。
 *
 * 与 [PanelSwitchRow] 同一套尺寸口径（[ReaderChromeTokens]），只把尾部控件换成单选钮：
 * 翻页方式是**互斥的二选一**，用 Switch 会被读成「开/关某个功能」而不是「选哪一档」。
 * 整行可点（不只尾部控件），与 PanelSwitchRow 的点击面一致。
 */
private fun PanelChoiceRow(
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !selected, onClick = onSelect)
            .padding(horizontal = ReaderChromeTokens.switchRowPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(ReaderChromeTokens.iconBox),
            shape = RoundedCornerShape(10.dp),
            color = iconContainerColor
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = iconContentColor
                )
            }
        }
        Spacer(modifier = Modifier.width(ReaderChromeTokens.iconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = selected, onClick = onSelect)
    }
}
```

需要补的 import：`androidx.compose.material3.RadioButton`。图标用已 import 的 `Icons.AutoMirrored.Outlined.SwapHoriz`（左右翻页）与 `Icons.Outlined.SwapVert`（上下滚屏）；若未 import 则补 `androidx.compose.material.icons.automirrored.outlined.SwapHoriz` 与 `androidx.compose.material.icons.outlined.SwapVert`。

- [ ] **Step 3: `MoreSettingPanel` 增模式单选卡与即时回调**

签名加一个参数，并在现有开关 `CommonCard` **之上**插入一张新卡：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingPanel(
    onDismiss: () -> Unit,
    onClickTurnChanged: (Boolean) -> Unit,
    onTurnModeChanged: (Int) -> Unit,
) {
    var canKeyTurn by remember { mutableStateOf(ReadBookControl.canKeyTurn) }
    var canClickTurn by remember { mutableStateOf(ReadBookControl.canClickTurn) }
    var turnModeIndex by remember { mutableIntStateOf(ReadBookControl.turnModeIndex) }
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .padding(horizontal = CommonUiTokens.pagePadding)
                .padding(top = ReaderChromeTokens.sheetTopPadding)
        ) {
            SheetHeader(stringResource(R.string.setting))
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            SectionLabel(stringResource(R.string.reader_section_turn))
            // 翻页方式（互斥二选一）单独一张卡，与下面的两个开关分开：
            // 「选哪一档」与「某个输入能不能翻页」是两类设置，混在一张卡里
            // 单选钮与开关并排会让人以为单选也是一种开关
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    PanelChoiceRow(
                        icon = Icons.AutoMirrored.Outlined.SwapHoriz,
                        label = stringResource(R.string.turn_mode_page),
                        description = stringResource(R.string.turn_mode_page_desc),
                        selected = turnModeIndex == 0,
                        onSelect = {
                            turnModeIndex = 0
                            ReadBookControl.updateTurnModeIndex(0)
                            onTurnModeChanged(0)
                        },
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ReaderChromeTokens.switchDividerIndent),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    PanelChoiceRow(
                        icon = Icons.Outlined.SwapVert,
                        label = stringResource(R.string.turn_mode_scroll),
                        description = stringResource(R.string.turn_mode_scroll_desc),
                        selected = turnModeIndex == 1,
                        onSelect = {
                            turnModeIndex = 1
                            ReadBookControl.updateTurnModeIndex(1)
                            onTurnModeChanged(1)
                        },
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            // ↓ 现有的开关卡（ReaderPanels.kt:1614-1646）整体下移到这里，
            //   内含的 PanelSwitchRow(音量键翻页) / HorizontalDivider / PanelSwitchRow(点击翻页)
            //   三行**一字不改**——它们已定案，本任务只在它们上方插入模式单选卡。
            //   唯一允许的改动是这张 CommonCard 之前多了一个 Spacer（上面那行）。
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    /* 原 :1616-1644 的内容原样保留 */
                }
            }
            Spacer(modifier = Modifier.height(ReaderChromeTokens.sheetBottomPadding))
        }
    }
}
```

更新 `MoreSettingPanel` 的 KDoc：把「按键翻页 / 点击翻页开关」改成「翻页方式选择 + 按键翻页 / 点击翻页开关」，并补 `@param onTurnModeChanged` 说明——理由与 `onClickTurnChanged` 同（`ReadBookControl` 的属性不是 Compose State，写入不触发重组，不能让「换模式是否生效」依赖 panel 变化恰好重组）。

需要补的 import：`androidx.compose.runtime.mutableIntStateOf`。

- [ ] **Step 4: 编译**

Run: `./gradlew :module_book:compileDebugKotlin`
Expected: 失败，`ReadBookActivity.kt:759` 处 `MoreSettingPanel` 缺 `onTurnModeChanged` 实参。这是预期的——Task 7 补上。本步只确认报错**仅此一处**。

- [ ] **Step 5: 提交**

```bash
git add module_book/src/main/res/values/strings.xml \
        module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt
git commit -m "feat(module_book): 设置面板增翻页方式单选卡

与既有的音量键/点击翻页两个开关分卡：互斥二选一与开关是两类设置。
尾部用 RadioButton 而非 Switch，避免被读成「开关某个功能」；
不改已定案的 reader_section_turn 文案，模式选择正好归这一节。"
```

---

## Task 7: `ReadBookActivity` 接线

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt`（:107/:142/:236/:342/:352/:462/:522/:538/:581/:759）

这是把前六个任务接到页面上的一步，也是唯一改动既有启动链路的一步。

- [ ] **Step 1: 加滚屏控制器与模式状态字段**

在 `pagerController`（:107）之后加：

```kotlin
    /** 滚屏控制器引用：音量键滚一屏由 Activity.onKeyUp 转发（组合外入口），与 pagerController 二者只有一个非空 */
    var scrollController: ReaderScrollController? = null

    /** 滚屏模式的块高（px），由 rePaginate 按实测落定；0 = 尚未测算 */
    internal var readerBlockHeightPx: Int = 0
        private set
```

- [ ] **Step 2: `rePaginate` 按模式落定行数与块高**

替换 `rePaginate`（:236-249）的实现体。翻页模式仍只需行数；滚屏模式还要块高（spec §5）。

```kotlin
    internal fun rePaginate(typesetter: ReaderTypesetter, startFromCurrent: Boolean = true) {
        val width = readerContentWidthPx
        val height = readerBodyHeightPx
        if (width <= 0 || height <= 0) return
        val metrics = typesetter.measureBlock(width, height) ?: return
        if (metrics.lineCount <= 0) return
        // 样式与行数一起落定：随后的 loadPage 取的就是这份样式，测算与分页不可能错身
        readerTypesetter = typesetter
        viewModel.pageLineCount = metrics.lineCount
        readerBlockHeightPx = metrics.heightPx
        val shelf = viewModel.bookShelf ?: return
        if (startFromCurrent) {
            pagerController?.setInitData(shelf.durChapter, shelf.durChapterPage)
            scrollController?.setInitData(shelf.durChapter, shelf.durChapterPage)
        }
    }
```

- [ ] **Step 3: 加落点换算（spec §8）**

在 `rePaginate` 之后加。这是全仓唯一一处换算，不要在别处再算一次。

```kotlin
    /**
     * 翻页方式切换时的落点换算：按**行号**而不是屏号跨模式对齐。
     *
     * 两种模式的正文视口高度不同（滚屏模式没有块内标题行，视口更高，见 spec §4），
     * 因此 `pageLineCount` 不同、同一个屏号指向的不是同一段字。行号是两边共通的量：
     * 旧模式读到第 oldPage 屏 = 读到了第 `oldPage × oldLineCount` 行，
     * 新模式下含该行的屏是 `lineOffset / newLineCount`。
     *
     * 只在阅读器内切换时调用：冷启动时 `durChapterPage` 与已持久化的模式天然同口径，
     * 不需要也不应该换算。
     */
    internal fun convertPageIndex(oldPageIndex: Int, oldLineCount: Int, newLineCount: Int): Int {
        if (oldLineCount <= 0 || newLineCount <= 0) return 0
        val lineOffset = oldPageIndex.coerceAtLeast(0) * oldLineCount
        return lineOffset / newLineCount
    }
```

- [ ] **Step 4: 按键按模式分派**

替换 `onKeyUp`（:352-366）的 `when` 分支：

```kotlin
    /** 音量键翻页/滚一屏（受"按键翻页"开关控制），其余按键走系统默认 */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (ReadBookControl.canKeyTurn) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // 两个控制器只有一个非空（模式互斥），非空的那个就是当前方式
                    pagerController?.turnNext() ?: scrollController?.scrollOneScreen(forward = true)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    pagerController?.turnPrev() ?: scrollController?.scrollOneScreen(forward = false)
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }
```

`onKeyDown`（:342）不变：它只负责消费音量键事件，与模式无关。

注意 `pagerController?.turnNext() ?: ...` 依赖「`turnNext()` 返回 Unit 时 `?:` 不会走右边」——Kotlin 里 `Unit?` 的 Elvis 只在**接收者为 null** 时走右边，`turnNext()` 返回 `Unit` 非 null，故语义正确。若担心可读性，写成显式 `if (pagerController != null) ... else ...` 亦可，二者等价。

- [ ] **Step 5: `ReadBookScreen` 里建滚屏控制器并按模式分派容器**

在 `controller`（:462-477）之后加滚屏控制器，两个控制器都建、但只把当前模式那个挂到 Activity 上（按键分派靠「非空的那个」判模式）：

```kotlin
    // 翻页方式的页面级镜像：ReadBookControl.turnModeIndex 不是 Compose State，写入不触发重组。
    // 与 clickTurnEnabled 同一套理由（见其注释）——不能让「换模式是否生效」依赖 panel 变化恰好重组。
    var turnModeIndex by remember { mutableIntStateOf(ReadBookControl.turnModeIndex) }

    val scrollController = remember {
        ReaderScrollController(
            scope = scope,
            context = context,
            chapterSize = { viewModel.getChapterListSize() },
            chapterTitle = { viewModel.getChapterTitle(it) },
            loadPage = { c, p -> activity.loadPage(c, p) },
            onProgress = { c, p ->
                viewModel.updateProgress(c, p)
                chapterTitle = viewModel.getChapterTitle(c)
                sliderValue = (c + 1).toFloat()
            }
        )
    }
    // 只有当前模式的控制器挂到 Activity：音量键分派靠「哪个非空」判模式（见 onKeyUp）
    DisposableEffect(turnModeIndex) {
        val isPage = turnModeIndex == 0
        activity.pagerController = if (isPage) controller else null
        activity.scrollController = if (isPage) null else scrollController
        onDispose {
            activity.pagerController = null
            activity.scrollController = null
        }
    }
```

把原有 `DisposableEffect(controller)`（:478-481）**删掉**——它已被上面这个合并的 `DisposableEffect(turnModeIndex)` 取代，两个都留会互相覆盖 `pagerController`。

- [ ] **Step 6: 首屏启动同时喂两个控制器**

`LaunchedEffect(bookReady, bodyHeight)`（:522-533）里 `controller.setInitData(...)` 之后加一行：

```kotlin
            scrollController.setInitData(
                shelf?.durChapter ?: 0,
                shelf?.durChapterPage ?: DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
            )
```

`LaunchedEffect(typesetter)`（:538-540）不变：`rePaginate` 内部已同时 `setInitData` 两个控制器。

- [ ] **Step 7: 布局按模式分叉**

把 `ReaderPager(...)`（:581-595）整段替换为：

```kotlin
        if (turnModeIndex == 0) {
            ReaderPager(
                controller = controller,
                textColor = textColor,
                bgColor = bgColor,
                textSizeSp = textSizeSp,
                lineHeight = lineHeight,
                canClickTurn = clickTurnEnabled,
                onCenterTap = { menuVisible = !menuVisible },
                onBodySizeChanged = { w, h ->
                    activity.onBodyMeasured(w, h)
                    if (w != bodyWidth) bodyWidth = w
                    if (h != bodyHeight) bodyHeight = h
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            ReaderScroll(
                controller = scrollController,
                textColor = textColor,
                bgColor = bgColor,
                textSizeSp = textSizeSp,
                lineHeight = lineHeight,
                blockHeightPx = activity.readerBlockHeightPx,
                canClickTurn = clickTurnEnabled,
                onCenterTap = { menuVisible = !menuVisible },
                onViewportSizeChanged = { w, h ->
                    activity.onBodyMeasured(w, h)
                    if (w != bodyWidth) bodyWidth = w
                    if (h != bodyHeight) bodyHeight = h
                },
                modifier = Modifier.fillMaxSize()
            )
        }
```

- [ ] **Step 8: 模式切换的重分页与落点换算**

在 `LaunchedEffect(typesetter)` 之后加。这是 spec §8 时序的落地点：改模式 → 重组 → 新容器回报新视口 → 重分页 → 换算 → 定位。

```kotlin
    // 翻页方式切换：新容器要等重组后才成形、新视口尺寸才回报得上来，因此换算不能在面板回调里
    // 立刻做（那会拿旧视口量新布局），而是等 bodyHeight 落到新模式的那一份之后再算。
    // 与「字号变化由 LaunchedEffect(typesetter) 接力」是同一条时序纪律。
    var pendingTurnModeSwitch by remember { mutableStateOf(false) }
    LaunchedEffect(bodyHeight, pendingTurnModeSwitch) {
        if (!pendingTurnModeSwitch || !pagerStarted) return@LaunchedEffect
        pendingTurnModeSwitch = false
        val shelf = viewModel.bookShelf ?: return@LaunchedEffect
        val oldLineCount = activity.lastLineCount
        activity.rePaginate(typesetter, startFromCurrent = false)
        val newLineCount = viewModel.pageLineCount
        val target = activity.convertPageIndex(shelf.durChapterPage, oldLineCount, newLineCount)
        viewModel.updateProgress(shelf.durChapter, target)
        if (turnModeIndex == 0) {
            controller.setInitData(shelf.durChapter, target)
        } else {
            scrollController.setInitData(shelf.durChapter, target)
        }
    }
```

`MoreSettingPanel` 调用点（:759-762）补实参：

```kotlin
        ReaderPanel.SETTING -> MoreSettingPanel(
            onDismiss = { panel = ReaderPanel.NONE },
            onClickTurnChanged = { clickTurnEnabled = it },
            onTurnModeChanged = { index ->
                if (index != turnModeIndex) {
                    turnModeIndex = index
                    // 切换前先记下旧模式的行数：换算要用两个模式的行数（spec §8）
                    activity.lastLineCount = viewModel.pageLineCount
                    pendingTurnModeSwitch = true
                }
            }
        )
```

`ReadBookActivity` 加字段（紧跟 `readerBlockHeightPx`）：

```kotlin
    /** 上一次落定的每屏行数，供翻页方式切换时做落点换算（见 convertPageIndex） */
    internal var lastLineCount: Int = 0
```

并在 `rePaginate` 里 `viewModel.pageLineCount = metrics.lineCount` 之后加 `lastLineCount = metrics.lineCount`。

- [ ] **Step 9: 编译 + 全量单测**

Run: `./gradlew :module_book:compileDebugKotlin && ./gradlew :module_book:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，单测全 PASS（含未改动的 `ReaderPagerControllerTest`）。

- [ ] **Step 10: 集成构建**

Run: `./gradlew :module_app:assembleRealDebug`
Expected: BUILD SUCCESSFUL。先确认 `gradle.properties` 的 `isModule=false`（提交态铁律，AGENTS.md）。

- [ ] **Step 11: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/ReadBookActivity.kt
git commit -m "feat(module_book): 阅读页按翻页方式分派容器并支持上下滚屏

- 翻页方式镜像成页面级 State，两个控制器都建、只把当前模式那个挂到 Activity，
  音量键分派靠「哪个非空」判模式
- 切模式按行号换算落点（两模式视口高度不同故 pageLineCount 不同），
  换算等新模式视口回报后再做，不在面板回调里拿旧尺寸量新布局
- rePaginate 改用 measureBlock 一次落定行数与块高，翻页模式行为不变"
```

---

## Task 8: ADR 与文档同步

**Files:**
- Create: `docs/adr/00XX-reader-turn-mode.md`（编号取 `docs/adr/` 当前最大值 +1，先 `ls docs/adr/`）
- Modify: `agents.md`（「MVVM 架构约定 → Compose 体系」一节）
- Modify: `docs/superpowers/specs/2026-09-13-reader-scroll-mode-design.md`（§13 删 `ReadBookControlTest` 一行，与本计划开头的偏离说明对齐）

- [ ] **Step 1: 确认 ADR 编号**

Run: `ls docs/adr/ | sort | tail -5`
Expected: 看到当前最大编号（写作时为 0036，实施时以实际输出为准），新 ADR 取 +1。

- [ ] **Step 2: 写 ADR**

按 `docs/adr/ADR-FORMAT.md` 的规范写。必须自足（**不交叉引用本仓其它 ADR 编号**，需要背景就写成自足描述），且讲清三件令人惊讶/难回退的事：

1. **滚屏模式为什么还保留「页」的概念**：进度 `dur_chapter_page` 在两种模式下都是「章内第几屏」，因此零 schema 改动、零迁移，且换模式可换算落点。代价是滚屏模式的「屏」不是物理屏幕而是排版块——它比翻页模式的屏高（块内不含标题行）。
2. **为什么两种模式共用分块链而不是各写一套**：「分页与渲染必须同源」是既有契约，两套切法必然长出「上一页与下一页接不上」那一类静默错乱。
3. **权衡与被否方案**：块内保留 chrome 高度换零换算（否：每屏一条约 70dp 空带）；跨章无缝的单一 LazyColumn（否：item 总数需先排版才知道，前插章节会平移索引与滚动位置）。

- [ ] **Step 3: 同步 `agents.md`**

在「MVVM 架构约定 → Compose 体系」一节，现有「阅读界面分两层」那段之后补一句（保持该文件「只记稳定约束、不复述接线细节」的口径）：

```markdown
- **翻页方式有两种且共用分块链**（左右翻页 / 上下滚屏，`ReadBookControl.turnMode`）：
  两者都由同一条排版分块链给出「第 N 屏的正文」，差别只在容器（三页窗口 + 横向拖拽 /
  整章块列表 + 竖向滚动），故进度口径同为「章内第几屏」、`dur_chapter_page` 语义不变。
  **块高一律取排版实测值**，不得用「行数 × 行高」心算；两种模式的正文视口高度不同
  （滚屏块内不含标题行），故 `pageLineCount` 不同，切换时按行号换算落点
```

- [ ] **Step 4: 修订 spec §13**

删掉 `- `ReadBookControlTest`：`turnModeIndex` 越界回落默认、`updateTurnModeIndex` 越界不写` 这一行，替换为：

```markdown
- `ReaderBlockMetricsTest`：`fitLines` 纯函数（块高取实测行底部、一行都放不下返回 null）

（`ReadBookControl` 不写单测：它是 `object`，类初始化即读 `BaseApplication.context` 并调
`DisplayUtil.dp2px`，JVM/Robolectric 下拿不到应用图，测试会卡在单例初始化而不是被测逻辑上；
其越界回落是一行，且与已在线上的 `textKindIndex` 同写法。）
```

- [ ] **Step 5: 跑全量验证**

Run: `./gradlew :module_book:testDebugUnitTest && ./gradlew :module_app:assembleRealDebug && ./gradlew :module_book:lint`
Expected: 三项全过，无新警告（AGENTS.md：不要引入新的编译警告）。

- [ ] **Step 6: 提交**

```bash
git add docs/adr/ agents.md docs/superpowers/specs/2026-09-13-reader-scroll-mode-design.md
git commit -m "docs(adr): 新增阅读器翻页方式的决策记录并同步仓库指南

记下三件无上下文会令人惊讶的事：滚屏模式为何仍按「屏」记进度、
两种模式为何共用分块链、以及被否掉的两个方案（保留 chrome 高度换零换算、跨章无缝）。"
```

---

## Task 9: 单选行的深浅色渲染回归（补 spec §13 的缺口）

**Files:**
- Create: `module_book/src/test/java/com/ebook/book/reader/ReaderRenderProbe.kt`
- Create: `module_book/src/test/java/com/ebook/book/reader/ReaderTurnModeRowRenderTest.kt`
- Modify: `module_book/src/test/java/com/ebook/book/reader/ReaderChromeThemeRenderTest.kt`（改用共享探针）
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt`（`PanelChoiceRow` 由 `private` 改 `internal`）

前面八个任务没有覆盖 spec §13 的「设置面板新增单选卡在浅色与深色下都能正确渲染」这一条。
补法与既有 `ReaderChromeThemeRenderTest` 同路：数像素，因为要防的失败形态正是
「某个颜色角色被写回常量」——写回之后布局、文案、语义全对，只有像素是错的。

`PanelChoiceRow` 要能被测试调用，必须从 `private` 放宽到 `internal`。这不是为了测试
破坏封装：同模块测试源集本就可见 `internal`，而本仓已有多处为可测性放宽可见性的先例
（`JsoupBookParser.rule`、`ChapterPageMatcher`、`ScriptBookParser` 的生产构造都是 public）。

- [ ] **Step 1: 抽出共享探针 `ReaderRenderProbe.kt`**

把 `ReaderChromeThemeRenderTest.kt:130-196` 的 `captureDecor` / `pixelBox` / `matches` /
`PixelBox` / `ColorTolerance` 原样搬过来，改成 internal 顶层声明（`captureDecor` 需要
`composeRule`，故收成一个持有 rule 的小类）：

```kotlin
package com.ebook.book.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 渲染回归的取色探针：把 decorView 画进 Bitmap 后数某个颜色的命中像素与外接矩形。
 *
 * 取图走 `decorView.draw(Canvas)` 而不是 compose 的 `captureToImage`：后者依赖
 * PixelCopy + frame commit 回调，Robolectric 的 paused looper 不驱动该回调，2s 后必抛超时。
 *
 * 从 ReaderChromeThemeRenderTest 抽出来共用，是因为「某个颜色角色被写回常量」这类失败
 * 在顶栏/底栏与设置面板行上是同一种形态，判据也同一套；两处各抄一遍像素扫描，
 * 早晚有一处的容差或坐标算法悄悄改了而另一处没跟上。
 */
internal class ReaderRenderProbe<out R : androidx.activity.ComponentActivity>(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<R>, R>,
) {

    fun capture(): Bitmap {
        val decor: View = rule.activity.window!!.decorView
        assertTrue(
            "decor 尚未布局（" + decor.width + "x" + decor.height + "），取到的像素无意义",
            decor.width > 0 && decor.height > 0
        )
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap
    }
}

/** 与 [color] 近似（容差 [ColorTolerance]）的像素的外接矩形；零命中返回空矩形 */
internal fun Bitmap.pixelBox(color: Color): PixelBox {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = 0
    var bottom = 0
    var hit = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (matches(getPixel(x, y), color)) {
                hit++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    return if (hit == 0) PixelBox(0, 0, -1, -1, 0) else PixelBox(left, top, right, bottom, hit)
}

private fun matches(argb: Int, color: Color): Boolean {
    val red = (color.red * 255).roundToInt()
    val green = (color.green * 255).roundToInt()
    val blue = (color.blue * 255).roundToInt()
    return abs((argb shr 16 and 0xFF) - red) <= ColorTolerance &&
        abs((argb shr 8 and 0xFF) - green) <= ColorTolerance &&
        abs((argb and 0xFF) - blue) <= ColorTolerance
}

internal data class PixelBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val count: Int,
) {
    val width get() = right - left + 1
    val height get() = bottom - top + 1
    fun describe() = "[$left,$top→$right,$bottom] ${width}x$height ${count}px"
}

/** 探针色比对的容差（抗锯齿与色彩管理会带来 ±几的偏差） */
internal const val ColorTolerance = 6
```

实施时以 `ReaderChromeThemeRenderTest.kt` 里 `composeRule` 的**实际类型**为准调整
`ReaderRenderProbe` 的泛型签名（本仓用的是 `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`，
其返回类型与经典版不同）。若泛型对不上，最简做法是把 `capture()` 写成接收 `View` 的顶层函数
`fun captureDecor(decor: View): Bitmap`，调用方各自取 `composeRule.activity.window!!.decorView`——
不要为了凑泛型去改两个测试的 rule 声明。

- [ ] **Step 2: `ReaderChromeThemeRenderTest` 改用共享探针**

删掉它自己的 `captureDecor` / `pixelBox` / `matches` / `PixelBox` / `ColorTolerance`，
改调 Step 1 的共享版。**两个 @Test 断言与探针色常量一个字不改**——这个测试当前是绿的，
抽取后必须仍然绿，否则是抽取改动了行为。

Run: `./gradlew :module_book:testDebugUnitTest --tests "*ReaderChromeThemeRenderTest*"`
Expected: PASS（与抽取前一致）。

- [ ] **Step 3: `PanelChoiceRow` 放宽可见性**

`ReaderPanels.kt` 里 `private fun PanelChoiceRow(` 改为 `internal fun PanelChoiceRow(`，
并在其 KDoc 补一句为什么不是 private：

```kotlin
 * 可见性为 internal 而非 private：ReaderTurnModeRowRenderTest 要直接渲染它来锁
 * 「图标块底色随调板翻转」——该测试防的是颜色角色被写回常量，那种失败布局与文案全对、
 * 只有像素是错的，只能靠渲染看见。
```

- [ ] **Step 4: 写渲染测试**

```kotlin
package com.ebook.book.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 翻页方式单选行的深浅色渲染回归：图标块底色只能来自当前 `colorScheme`。
 *
 * 与 ReaderChromeThemeRenderTest 防的是同一种失败形态（某个颜色角色被写回常量），
 * 判据同一套：把 `primaryContainer`/`secondaryContainer` 换成画面里不可能自然出现的
 * 探针色，命中即等于「这一行确实按调板取色」，另一套调板的探针色必须零命中。
 *
 * 数像素而不断言布局/文案的理由同前：写回常量之后布局、文案、语义全对，只有像素是错的。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ReaderTurnModeRowRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 变体开关：两套调板共用一次 setContent（重复 setContent 会抛 already set content） */
    private val darkChrome = mutableStateOf(false)

    @Composable
    private fun TurnModeRows() {
        Column(modifier = Modifier.fillMaxSize()) {
            PanelChoiceRow(
                icon = Icons.AutoMirrored.Outlined.SwapHoriz,
                label = "左右翻页",
                description = "横向滑动，一屏一屏地翻",
                selected = true,
                onSelect = {},
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            PanelChoiceRow(
                icon = Icons.Outlined.SwapVert,
                label = "上下滚屏",
                description = "竖向连续滚动，手指滚到哪读到哪",
                selected = false,
                onSelect = {},
                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    @Test
    fun `翻页方式单选行的图标块底色随深浅色调板翻转`(): Unit {
        composeRule.setContent {
            MaterialTheme(
                colorScheme = if (darkChrome.value) DarkProbe else LightProbe
            ) {
                TurnModeRows()
            }
        }

        composeRule.waitForIdle()
        assertRowsFollowScheme(expectLight = true)

        darkChrome.value = true
        composeRule.waitForIdle()
        assertRowsFollowScheme(expectLight = false)
    }

    /**
     * 两行都要翻：两个探针色各自的命中数都必须过下限。
     * 只翻一行（另一行写死）时对应探针色零命中。
     */
    private fun assertRowsFollowScheme(expectLight: Boolean) {
        val bitmap = ReaderRenderProbe(composeRule).capture()
        val primary = if (expectLight) ProbePrimaryLight else ProbePrimaryDark
        val secondary = if (expectLight) ProbeSecondaryLight else ProbeSecondaryDark
        val foreign = if (expectLight) {
            listOf(ProbePrimaryDark, ProbeSecondaryDark)
        } else {
            listOf(ProbePrimaryLight, ProbeSecondaryLight)
        }
        val report = "当前为" + (if (expectLight) "浅" else "深") + "色调板，画布 " +
            bitmap.width + "x" + bitmap.height +
            "，左右翻页行 " + bitmap.pixelBox(primary).describe() +
            "，上下滚屏行 " + bitmap.pixelBox(secondary).describe()

        assertTrue("左右翻页行未按调板取色，探针色零命中。" + report,
            bitmap.pixelBox(primary).count >= MinIconPixels)
        assertTrue("上下滚屏行未按调板取色，探针色零命中。" + report,
            bitmap.pixelBox(secondary).count >= MinIconPixels)
        foreign.forEach {
            assertTrue("另一套调板的探针色漏了出来。" + report, bitmap.pixelBox(it).count == 0)
        }
    }

    private companion object {
        val ProbePrimaryLight = Color(0xFF2E9BD6)
        val ProbeSecondaryLight = Color(0xFF3FA07B)
        val ProbePrimaryDark = Color(0xFF7B3FA0)
        val ProbeSecondaryDark = Color(0xFFA07B3F)

        val LightProbe = lightColorScheme(
            primaryContainer = ProbePrimaryLight,
            secondaryContainer = ProbeSecondaryLight,
        )
        val DarkProbe = darkColorScheme(
            primaryContainer = ProbePrimaryDark,
            secondaryContainer = ProbeSecondaryDark,
        )

        /** 单个 36dp 图标块在 xhdpi 下约 72x72px，取其一半做下限，够咬住「图标块没画出来」 */
        const val MinIconPixels = 2_500
    }
}
```

- [ ] **Step 5: 跑两个渲染测试**

Run: `./gradlew :module_book:testDebugUnitTest --tests "*RenderTest*"`
Expected: `ReaderChromeThemeRenderTest` 与 `ReaderTurnModeRowRenderTest` 均 PASS。

若新测试零命中：先看 `PanelChoiceRow` 的图标块是不是真用了传进来的 `iconContainerColor`
（而不是某个写死的 `MaterialTheme.colorScheme.surface`），再看 `MinIconPixels` 是否高于
36dp 图标块在该 qualifier 下的实际像素数（把下限调低到命中数的三分之一，不要调成 0）。

- [ ] **Step 6: 提交**

```bash
git add module_book/src/test/java/com/ebook/book/reader/ReaderRenderProbe.kt \
        module_book/src/test/java/com/ebook/book/reader/ReaderTurnModeRowRenderTest.kt \
        module_book/src/test/java/com/ebook/book/reader/ReaderChromeThemeRenderTest.kt \
        module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt
git commit -m "test(module_book): 补翻页方式单选行的深浅色渲染回归

像素探针抽成 ReaderRenderProbe 供两条渲染测试共用，避免两处各抄一遍扫描逻辑后
容差或坐标算法悄悄漂移；PanelChoiceRow 放宽到 internal 以便直接渲染。"
```

---

## 收尾：人工装机验证清单

Agent 止于编译 + 单测（AGENTS.md 的分工）。以下各项**未验证**，交付时必须原样列给用户：

见 spec §14 的 10 项。其中最容易出问题、要重点看的三项：

1. **块与块之间是否精确无缝**——既不能有空隙也不能叠行。有缝说明 `blockHeightPx` 不是实测值或没传到 `ReaderScroll`
2. **切模式后是否落在同一段字附近**——偏了说明 `lastLineCount` 记晚了（必须在 `rePaginate` 之前记）或 `convertPageIndex` 的除数用错
3. **杀进程重进后停在哪一屏**——偏移一屏说明 `leadingItemCount` 在控制器与 `ReaderScroll` 两侧算得不一致（spec §7 点명过这个坑）
