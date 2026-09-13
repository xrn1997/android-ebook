# 阅读器长目录的浏览与选择形态 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给阅读器章节目录抽屉加会话内有效的倒序开关，把离线下载面板的单层平铺列表改为每 100 章一组的可展开分组（组头可整组勾选），并为单次下载量加 500 章软上限。

**Architecture:** 把全部可测语义（分组切分、组头三态、整组勾选、倒序索引换算、组头行号、上限判定）收进新的纯逻辑文件 `ChapterSelection.kt`，Compose 层只负责接线；下载面板从 1785 行的 `ReaderPanels.kt` 搬进独立文件后再改造。倒序与分组都只动展示层，`chapters` 顺序、DB、下载任务构建链路一概不变。

**Tech Stack:** Kotlin / Jetpack Compose（foundation 1.11.3 + material3 1.4.0，经 Compose BOM 2026.06.00）/ JUnit4。

**规格（事实源）:** `docs/superpowers/specs/2026-09-12-reader-catalog-order-and-download-grouping-design.md`

---

## 前置约定

- **提交节奏**：每个 Task 结尾各一笔提交，提交后仓库必须可编译、单测全绿。不要跨 Task 攒改动。
- **命令**：Git Bash 用 `./gradlew`；PowerShell 用 `.\gradlew`。
- **测试分工（AGENTS.md）**：Task 1–2 是纯逻辑，走 TDD（先红后绿）；Task 3–6 是 Compose 组合改动，本仓无 Compose 测试先例，**其正确性由编译 + Task 8 的人工装机项负责**，不要为它们硬造 UI 测试。
- **已核实的依赖 API**（省得实施时再查一遍）：
  - `LazyListScope.stickyHeader(key, contentType, content: @Composable LazyItemScope.(Int) -> Unit)` —— foundation 1.11.3 里**已是稳定 API，无需 `@OptIn`**；content 收一个 `Int`（该表头全局下标），用 `{ _ -> ... }` 接。
  - `TriStateCheckbox(state: ToggleableState, onClick: (() -> Unit)?, ...)` —— material3 1.4.0。
  - `InfoChip(..., onClick = ...)` —— `lib_book_common` 的共享件已支持可点（自带 `Role.Button` 语义），**不要再手搓胶囊**。
  - 确定/取消文案：`module_book` 的 `R.string.confirm`（确定）、`lib_book_common` 的 `com.ebook.common.R.string.cancel`（取消）。

---

## Task 1: `ChapterSelection.kt` —— 分组、组头三态、整组勾选、软上限

**Files:**
- Create: `module_book/src/main/java/com/ebook/book/reader/ChapterSelection.kt`
- Test: `module_book/src/test/java/com/ebook/book/reader/ChapterSelectionTest.kt`

- [ ] **Step 1: 写失败测试**

新建 `module_book/src/test/java/com/ebook/book/reader/ChapterSelectionTest.kt`：

```kotlin
package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterSelection] 的纯逻辑用例：分组切分、组头三态、整组勾选、下载软上限。
 *
 * 这些语义算错既不会编译失败也不会闪退——只会静默给出错的内容（错位的组范围、少勾的一百章、
 * 该弹的确认没弹），所以集中在纯 JVM 上锁住（本仓 Compose 页面不做装机级单测，见 AGENTS.md）。
 */
class ChapterSelectionTest {

    @Test
    fun `分组按粒度整除时恰好切满`() {
        val groups = chapterGroups(3000)

        assertEquals(30, groups.size)
        assertEquals(ChapterGroup(index = 0, first = 0, last = 99), groups.first())
        assertEquals(ChapterGroup(index = 29, first = 2900, last = 2999), groups.last())
    }

    @Test
    fun `末组不足一组时如实收尾`() {
        val last = chapterGroups(3050).last()

        assertEquals(ChapterGroup(index = 30, first = 3000, last = 3049), last)
        assertEquals(50, last.count)
    }

    @Test
    fun `不足一组时只有一组`() {
        val groups = chapterGroups(37)

        assertEquals(1, groups.size)
        assertEquals(0, groups.single().first)
        assertEquals(36, groups.single().last)
        assertEquals(37, groups.single().count)
    }

    @Test
    fun `空目录不产出空组`() {
        // 空目录若产出「第 1-0 章」这种组，组头会渲染出无意义的范围文案
        assertTrue(chapterGroups(0).isEmpty())
        assertTrue(chapterGroups(-1).isEmpty())
    }

    @Test
    fun `组头三态按实际章数判定`() {
        val group = ChapterGroup(index = 0, first = 0, last = 2)

        assertEquals(GroupState.NONE, groupState(group, emptySet()))
        assertEquals(GroupState.PARTIAL, groupState(group, setOf(1)))
        assertEquals(GroupState.ALL, groupState(group, setOf(0, 1, 2)))
        assertEquals(GroupState.NONE, groupState(group, setOf(7)))
    }

    @Test
    fun `点未选或半选的组头是补齐整组`() {
        val group = ChapterGroup(index = 0, first = 0, last = 2)

        assertEquals(setOf(0, 1, 2), toggleGroup(group, emptySet()))
        // 半选态点击必须是「补齐」而不是「清空」：按清空处理会让用户看到已选章数不升反降
        assertEquals(setOf(0, 1, 2), toggleGroup(group, setOf(1)))
    }

    @Test
    fun `点已全选的组头是整组移除`() {
        val group = ChapterGroup(index = 0, first = 0, last = 2)

        assertEquals(setOf(9), toggleGroup(group, setOf(0, 1, 2, 9)))
    }

    @Test
    fun `下载软上限在五百章边界上放行`() {
        assertFalse(exceedsSelectionCap(0))
        assertFalse(exceedsSelectionCap(MAX_DOWNLOAD_SELECTION))
        assertTrue(exceedsSelectionCap(MAX_DOWNLOAD_SELECTION + 1))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module_book:testDebugUnitTest --tests "com.ebook.book.reader.ChapterSelectionTest"`
Expected: 编译失败（`Unresolved reference: chapterGroups` 等），即"未解析引用"形态的红。

- [ ] **Step 3: 写实现**

新建 `module_book/src/main/java/com/ebook/book/reader/ChapterSelection.kt`：

```kotlin
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module_book:testDebugUnitTest --tests "com.ebook.book.reader.ChapterSelectionTest"`
Expected: PASS，8 个用例全绿。

- [ ] **Step 5: 反向实验（确认用例真的锁住了行为）**

把 `toggleGroup` 的 `GroupState.NONE, GroupState.PARTIAL ->` 临时改成只处理 `GroupState.NONE`（让 PARTIAL 掉进 ALL 分支），运行测试，确认 `点未选或半选的组头是补齐整组` 变红，然后改回。

- [ ] **Step 6: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ChapterSelection.kt module_book/src/test/java/com/ebook/book/reader/ChapterSelectionTest.kt
git commit -m "$(cat <<'EOF'
feat(module_book): 新增章节分组与整组勾选的纯逻辑层

下载面板要按百章分组，分组切分、组头三态、整组勾选这些语义先落到可单测的纯函数里，
Compose 层只负责接线。

- 新增 ChapterSelection.kt：分组切分、三态判定、整组勾选往返、500 章软上限判定
- 同目录配套 ChapterSelectionTest，含半选态点击是"补齐"而非"清空"的反向实验
EOF
)"
```

---

## Task 2: `ChapterSelection.kt` —— 倒序索引换算与组头行号

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/reader/ChapterSelection.kt`
- Test: `module_book/src/test/java/com/ebook/book/reader/ChapterSelectionTest.kt`

- [ ] **Step 1: 写失败测试**

在 `ChapterSelectionTest` 的最后一个 `}` 之前追加：

```kotlin
    @Test
    fun `倒序换算与正序换算互为逆运算`() {
        val count = 10

        for (position in 0 until count) {
            assertEquals(position, originalIndexAt(count, descending = false, position = position))
        }
        for (index in 0 until count) {
            assertEquals(
                index,
                originalIndexAt(
                    count = count,
                    descending = true,
                    position = displayPositionOf(count, descending = true, originalIndex = index),
                ),
            )
        }
    }

    @Test
    fun `倒序时首行是最新章`() {
        assertEquals(2999, originalIndexAt(count = 3000, descending = true, position = 0))
        assertEquals(0, originalIndexAt(count = 3000, descending = true, position = 2999))
    }

    @Test
    fun `组头行号等于排在它前面的组头行与已展开组的章行之和`() {
        val groups = chapterGroups(300) // 3 组，每组恰好 100 章

        // 无展开：组 2 的组头紧跟在组 0、组 1 的组头之后
        assertEquals(2, rowIndexOfGroup(groups, emptySet(), 2))
        // 组 0 展开：组 2 的组头前多了组 0 的 100 行章行
        assertEquals(102, rowIndexOfGroup(groups, setOf(0), 2))
        // 组 0、1 都展开
        assertEquals(202, rowIndexOfGroup(groups, setOf(0, 1), 2))
        // 只展开自己、或只展开排在后面的组，都不影响自己的组头行号
        assertEquals(2, rowIndexOfGroup(groups, setOf(2), 2))
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module_book:testDebugUnitTest --tests "com.ebook.book.reader.ChapterSelectionTest"`
Expected: 编译失败（`Unresolved reference: originalIndexAt`、`displayPositionOf`、`rowIndexOfGroup`）。

- [ ] **Step 3: 写实现**

在 `ChapterSelection.kt` 末尾（`exceedsSelectionCap` 之后）追加：

```kotlin
/** 列表显示位置 → 原始章序号（正序时两者相同）。 */
internal fun originalIndexAt(count: Int, descending: Boolean, position: Int): Int =
    if (descending) count - 1 - position else position

/** 原始章序号 → 列表显示位置（[originalIndexAt] 的逆运算）。 */
internal fun displayPositionOf(count: Int, descending: Boolean, originalIndex: Int): Int =
    if (descending) count - 1 - originalIndex else originalIndex

/**
 * 某组组头在列表中的行号。
 *
 * 排在该组之前的每组各占一行组头，**已展开的组还要再多出它的章行**（一组展开就是 100 行，
 * 不是 1 行）——所以不能简化成"组序号 + 前置展开组数"，那会让滚动目标差出上百行。
 * 面板打开时要把含当前章的组滚到顶部，用的就是这个行号。
 */
internal fun rowIndexOfGroup(
    groups: List<ChapterGroup>,
    expanded: Set<Int>,
    groupIndex: Int,
): Int {
    var rows = 0
    for (group in groups) {
        if (group.index >= groupIndex) break
        rows += 1
        if (group.index in expanded) rows += group.count
    }
    return rows
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module_book:testDebugUnitTest --tests "com.ebook.book.reader.ChapterSelectionTest"`
Expected: PASS，11 个用例全绿。

- [ ] **Step 5: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ChapterSelection.kt module_book/src/test/java/com/ebook/book/reader/ChapterSelectionTest.kt
git commit -m "$(cat <<'EOF'
feat(module_book): 新增倒序索引换算与组头行号计算

目录抽屉倒序后必须拿原始章序号去显示章号、判高亮、跳章，下载面板打开时又要把
含当前章的组滚到顶部，两者都是纯算术，先落成可单测的纯函数。

- originalIndexAt / displayPositionOf：显示位置与原始章序号互为逆运算
- rowIndexOfGroup：组头行号 = 前置组头行 + 前置已展开组的章行（展开一组是 100 行而非 1 行）
EOF
)"
```

---

## Task 3: 目录抽屉倒序 + 两处列表清理

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt`（`ChapterListDrawer` 736-859、`ChapterRow` 868-916）
- Modify: `module_book/src/main/res/values/strings.xml`

- [ ] **Step 1: 新增两条文案**

在 `module_book/src/main/res/values/strings.xml` 的 `<string name="chapter_count_format">共%1$d章</string>` 之后插入：

```xml
    <string name="catalog_order_ascending">正序</string>
    <string name="catalog_order_descending">倒序</string>
```

- [ ] **Step 2: 给 `ChapterListDrawer` 加会话内倒序状态与定位重算**

在 `ReaderPanels.kt` 的 `ChapterListDrawer` 里，把这两段**现有代码**（`var listState` 那行与紧随其后的 `LaunchedEffect(visible)` 块）：

```kotlin
    val listState = rememberLazyListState()
    // 打开即定位当前章节（对齐原 scrollToPositionWithOffset(durChapter, 0)）
    LaunchedEffect(visible) {
        if (visible && durChapter in chapters.indices) {
            listState.scrollToItem(durChapter)
        }
    }
```

整体替换为：

```kotlin
    // 倒序开关：**声明在 AnimatedVisibility 之外**——放进去每次关抽屉都会随内容一起被丢弃，
    // 用户来回开合会被打回正序。放这里则关抽屉不丢、退出阅读器（组合销毁）自然回正序，
    // 即"仅本次阅读会话有效"（不落盘）。
    var descending by remember { mutableStateOf(false) }
    val count = chapters.size
    // asReversed() 是 O(1) 的视图不拷贝，但必须 remember：每次重组都新建实例会让 LazyColumn
    // 的 item provider 每帧换身份
    val displayChapters = remember(chapters, descending) {
        if (descending) chapters.asReversed() else chapters
    }
    val listState = rememberLazyListState()
    // 打开即定位当前章节；descending 必须在 key 里——切模式当刻不重新定位，用户会被甩到
    // 与所读章节无关的位置
    LaunchedEffect(visible, descending) {
        if (visible && durChapter in chapters.indices) {
            listState.scrollToItem(displayPositionOf(count, descending, durChapter))
        }
    }
```

- [ ] **Step 3: 在抽屉标题行加顺序切换胶囊**

在同一个 `Row` 里、`IconButton(onClick = onDismiss)` 之前插入：

```kotlin
                    // 顺序切换：显示"当前模式"而不是"点击后的动作"，避免"点了会变成什么"的歧义；
                    // 复用共享 InfoChip 的可点胶囊形态，与左侧章节数标签同一视觉语言（ADR-0006）
                    InfoChip(
                        text = stringResource(
                            if (descending) R.string.catalog_order_descending
                            else R.string.catalog_order_ascending
                        ),
                        shape = RoundedCornerShape(50),
                        containerColor = if (descending) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (descending) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textStyle = MaterialTheme.typography.labelSmall,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                        onClick = { descending = !descending }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
```

- [ ] **Step 4: 列表按显示位置渲染、用原始索引取章号与高亮**

把该 `LazyColumn` 的 `itemsIndexed(...)` 整段替换为：

```kotlin
                        // 不带 key：本列表每行没有需要跨滚动保留的状态，"index 作 key"与"不要 key"
                        // 的位置身份完全等价，却会让 LazyList 常驻一张 N 项 key→index 表
                        // （数千章 = 数千个装箱 Integer，且 item provider 换实例时重建）。
                        itemsIndexed(displayChapters) { position, chapter ->
                            // 章号、高亮、跳转一律用**原始索引**：倒序后首行仍显示"第 3000 章"，
                            // 点击跳转语义与正序完全一致
                            val index = originalIndexAt(count, descending, position)
                            ChapterRow(
                                index = index,
                                name = chapter.durChapterName,
                                isCurrent = index == durChapter,
                                onClick = { onChapterClick(index) }
                            )
                        }
```

- [ ] **Step 5: `ChapterRow` 的裁剪层只留给当前章**

把 `ChapterRow` 的 `Row(...)` 起手到 `verticalAlignment` 之前替换为：

```kotlin
    // 圆角底与它的裁剪层只给当前章那一行建：非当前行的背景是 Transparent，为它保留 clip
    // 等于给每个可见行都分配一个渲染层（数千章滚动时的纯浪费）。代价是非当前行的水波纹
    // 从圆角变直角——那些行本身没有底色，视觉差异可以忽略。
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 1.dp)
        .then(
            if (isCurrent) {
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            } else {
                Modifier
            }
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 11.dp)
    Row(
        modifier = rowModifier,
```

（`Row(` 的 `verticalAlignment = Alignment.CenterVertically` 及其后的行体保持不变。）

- [ ] **Step 6: 同步 KDoc**

- `ChapterListDrawer` 的 KDoc：在"打开时定位滚动到当前章节"那句后补一句——「倒序开关仅本次会话有效（不落盘，见 ADR-0034）；切模式时会重新定位到当前章，章号一律按原始序显示」。
- `ChapterRow` 的 KDoc：把"条目改圆角行卡——当前章节用 secondaryContainer 整行底色"补一句限定——「圆角与底色只建在当前章那一行，其余行不建裁剪层」。
- 若文件顶部因删除 `Color.Transparent` 出现未使用 import（Kotlin 只警告），一并删掉；该文件其余位置仍在用 `Color`，别误删。

- [ ] **Step 7: 编译并确认无新警告**

Run: `./gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，输出中无 warning。

- [ ] **Step 8: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt module_book/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat(module_book): 目录抽屉支持会话内倒序并减少行渲染层

长连载书要翻到最新章节只能靠快速滚动条拖到底，补一个倒序开关；顺带清掉两处
每行都在付的固定开销。

- 倒序状态声明在 AnimatedVisibility 之外：关抽屉不丢、退出阅读器回正序（仅本次会话有效）
- 章号、高亮、跳转一律取原始索引，倒序后首行仍是"第 N 章"
- 切换模式时重新定位到当前章，避免被甩到无关位置
- 去掉 index 作 key：与"不要 key"等价，却让 LazyList 常驻一张 N 项 key→index 表
- 圆角裁剪层只建在当前章那一行
EOF
)"
```

---

## Task 4: 把下载面板搬进独立文件（纯搬迁，行为不变）

**Files:**
- Create: `module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt`
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt`（删 1553-1745 段）

改造前先做纯搬迁，让 Task 5 的 diff 只看得到"分组"这件事，评审时不必在搬家和改逻辑之间来回对照。

- [ ] **Step 1: 建新文件并原样搬入三个函数**

新建 `module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt`，把 `ReaderPanels.kt` 里的 `ChapterDownloadSheet`（1553-1664）、`QuickSelectChip`（1672-1691）、`DownloadChapterRow`（1701-1745）**逐字搬过来**（含各自的 KDoc，不改一个字），补齐 import：

```kotlin
package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.db.entity.ChapterListEntity

// 以下三个可组合函数自 ReaderPanels.kt 原样搬入，行为不变
```

然后贴上搬来的三段代码。

- [ ] **Step 2: 从 `ReaderPanels.kt` 删掉这三段**

删除 `ChapterDownloadSheet`、`QuickSelectChip`、`DownloadChapterRow` 三个函数及其 KDoc；`ReaderPanels.kt` 的 `AddShelfDialog` 及其后的亮度/字体设置函数**保持原位不动**。

- [ ] **Step 3: 收敛 import**

Run: `./gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。
若有 unresolved reference → 新文件缺 import；若有 unused import 警告 → 从 `ReaderPanels.kt` 删掉已无引用的那几条（`Checkbox`/`Button`/`ModalBottomSheet` 等），逐个以编译器提示为准，直到输出无 warning。

- [ ] **Step 4: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt
git commit -m "$(cat <<'EOF'
refactor(module_book): 下载面板搬出 ReaderPanels

ReaderPanels.kt 已 1785 行，下载面板即将改成分组列表、还会再长一截，先把三个
相关可组合函数原样搬进独立文件，使后续改造的 diff 只剩逻辑变更。

- 纯搬迁，不改一个字符的行为
- 全仓调用点只有 ReadBookActivity 一处，SourceSwitchSheet 的 KDoc 链接因同包继续解析
EOF
)"
```

---

## Task 5: 下载面板改为按百章分组

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt`
- Modify: `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt:797`
- Modify: `module_book/src/main/res/values/strings.xml`

- [ ] **Step 1: 新增四条文案**

在 `strings.xml` 的 `<string name="cached_badge">已缓存</string>` 之后插入：

```xml
    <string name="chapter_group_range_format">第 %1$d-%2$d 章</string>
    <string name="chapter_group_cached_format">已缓存 %1$d/%2$d</string>
    <string name="chapter_group_expand">展开本组</string>
    <string name="chapter_group_collapse">收起本组</string>
```

- [ ] **Step 2: 加 `focusIndex` 入参并准备分组状态**

把 `ChapterDownloadSheet` 的签名与函数体开头改为：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterDownloadSheet(
    chapters: List<ChapterListEntity>,
    cachedIndices: Set<Int>,
    initialSelected: Set<Int>,
    focusIndex: Int,
    onConfirm: (selected: Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initialSelected) }

    // 未缓存索引集：列表打开期间不变，remember 避免每次勾选重算
    val uncachedIndices = remember(chapters, cachedIndices) {
        chapters.indices.filterTo(mutableSetOf()) { it !in cachedIndices }
    }

    // 分组只随章节数变化（章节列表在面板存活期间不会变）
    val groups = remember(chapters.size) { chapterGroups(chapters.size) }

    // 默认只展开含当前章的那一组：其余折叠后 3000 章 = 30 行，首屏一眼看全范围。
    // 面板是"关闭即离开组合"的，这份状态每次打开都重建 → 每次进面板都回到当前章那组
    val focusGroupIndex = groups.indexOfFirst { focusIndex in it.first..it.last }
    var expanded by remember {
        mutableStateOf(if (focusGroupIndex >= 0) setOf(focusGroupIndex) else emptySet())
    }

    // 打开即把当前章所在组的组头滚到列表顶部，确保用户在 UI 上直接看见它。
    // scrollToItem 在列表尚未完成首次测量时也能用（它会等到能滚动时再落位）
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (focusGroupIndex >= 0) {
            listState.scrollToItem(rowIndexOfGroup(groups, expanded, focusGroupIndex))
        }
    }
```

（`skippedCached` / `confirmText` 两行保持原样，紧随其后。）

- [ ] **Step 3: 用分组列表替换平铺列表**

把 `HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)` 之后那段**注释 + `LazyColumn`** 整体替换为下面的代码。注意那段注释已被本次改动推翻（"大目录快速滚动可加，本面板以选择为目的……不引入 FastScroll"——分组把导航面从三千行降到三十行，缺口正是这样闭合的，所以新注释要交代这一点，不能留着旧理由）：

```kotlin
            // 章节列表：高度仍限半屏（ModalBottomSheet 不该被内容无限撑开），但导航面已从
            // 「章节数」降到「组数」——每 100 章一行组头，3000 章的书首屏就能看全范围，
            // 这也是本面板始终不需要快速滚动条的原因。
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                groups.forEach { group ->
                    // 组头与子项的 key 都取自"原始章序号"域内的稳定值，绝不能用显示位置：
                    // 展开/收起会让其后所有位置整体位移，拿位置当 key 等于每次操作全量重建。
                    // 两类 item 给不同 contentType，展开/收起时才能真正复用组合。
                    stickyHeader(key = "g${group.index}", contentType = "group") { _ ->
                        GroupHeaderRow(
                            rangeText = stringResource(
                                R.string.chapter_group_range_format,
                                group.first + 1,
                                group.last + 1
                            ),
                            cachedText = stringResource(
                                R.string.chapter_group_cached_format,
                                (group.first..group.last).count { it in cachedIndices },
                                group.count
                            ),
                            state = groupState(group, selected),
                            expanded = group.index in expanded,
                            onToggleGroup = { selected = toggleGroup(group, selected) },
                            onToggleExpand = {
                                expanded = if (group.index in expanded) {
                                    expanded - group.index
                                } else {
                                    expanded + group.index
                                }
                            }
                        )
                    }
                    if (group.index in expanded) {
                        items(
                            count = group.count,
                            key = { offset -> "c${group.first + offset}" },
                            contentType = { "chapter" }
                        ) { offset ->
                            val index = group.first + offset
                            DownloadChapterRow(
                                index = index,
                                name = chapters[index].durChapterName,
                                isChecked = index in selected,
                                isCached = index in cachedIndices,
                            ) {
                                selected = if (index in selected) selected - index else selected + index
                            }
                        }
                    }
                }
            }
```

- [ ] **Step 4: 新增组头行组件**

在 `ChapterDownloadSheet.kt` 内（`QuickSelectChip` 之后）加：

```kotlin
/**
 * 下载面板的组头行：三态勾选框 + 章范围 + 已缓存计数 + 展开箭头。
 *
 * 整行点击 = 展开/收起，勾选框独立响应：一次误触整行没有后果，但落到勾选框上就是一次选中
 * 100 章，两个热区必须分开（Checkbox 的 onClick 只接自己的点击，行点击另经 clickable）。
 *
 * 组头必须有**实心底色**：它是吸附头，滚动时下面的章行会从它背后经过，透明底会串字。
 *
 * 已缓存计数只是**信息**——本面板不拿"是否已缓存"做任何决策（ADR-0034）：缓存文件存在不等于
 * 内容正确（缓存失败时也会落盘），故它既不参与勾选、也不参与上限判定。
 */
@Composable
private fun GroupHeaderRow(
    rangeText: String,
    cachedText: String,
    state: GroupState,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TriStateCheckbox(
            state = when (state) {
                GroupState.ALL -> ToggleableState.On
                GroupState.PARTIAL -> ToggleableState.Indeterminate
                GroupState.NONE -> ToggleableState.Off
            },
            onClick = onToggleGroup
        )
        Text(
            text = rangeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = cachedText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.chapter_group_collapse else R.string.chapter_group_expand
            ),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}
```

- [ ] **Step 5: 补 import**

在 `ChapterDownloadSheet.kt` 的 import 区加入：

```kotlin
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.state.ToggleableState
```

（`stickyHeader` 是 `LazyListScope` 的成员函数，不需要额外 import；`items` 同理。）

**同时删掉** Task 4 从 `ReaderPanels.kt` 带过来的 `import androidx.compose.foundation.lazy.itemsIndexed`——平铺列表已被替换，它在新文件里再无引用（Kotlin 只给警告，但 AGENTS.md 要求提交无新警告）。

- [ ] **Step 6: 调用侧传 `focusIndex`**

`ReadBookActivity.kt` 的 `ChapterDownloadSheet(...)` 调用处，在 `initialSelected` 之后加一行：

```kotlin
                focusIndex = bookShelf?.durChapter ?: 0,
```

- [ ] **Step 7: 编译并确认无新警告**

Run: `./gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，无 warning。

- [ ] **Step 8: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt module_book/src/main/java/com/ebook/book/ReadBookActivity.kt module_book/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat(module_book): 下载面板改为按百章分组并定位到当前章

单层平铺让 3000 章的书要在半屏里手滚找章，改为每 100 章一组、组头可整组勾选，
导航面从章节数降到组数。

- 默认只展开含当前章的那一组，并把该组头滚到列表顶部
- 组头三态勾选（半选态点击是补齐整组），整行点击只切换展开
- 组头用 stickyHeader 吸附，带实心底色避免章行从背后串字
- 组头与子项的 key 取自原始章序号，不用显示位置
- 已缓存计数仅作展示，不参与勾选与上限判定
EOF
)"
```

---

## Task 6: 单次下载超 500 章的二次确认

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt`
- Modify: `module_book/src/main/res/values/strings.xml`

- [ ] **Step 1: 新增文案**

在 `strings.xml` 的 `<string name="download_skip_cached_format">下载 %1$d 章（跳过 %2$d 章已缓存）</string>` 之后插入：

```xml
    <string name="download_cap_confirm_format">本次将下载 %1$d 章，超过单次上限 %2$d 章。确认后一次性下发这些下载任务。</string>
```

- [ ] **Step 2: 加确认状态并改动确认按钮**

在 `ChapterDownloadSheet` 的 `var selected by remember { mutableStateOf(initialSelected) }` 之后加：

```kotlin
    // 软上限的二次确认：超过 500 章时不直接下发，先让用户看到规模
    var capConfirmVisible by remember { mutableStateOf(false) }
```

把底部确认按钮的 `onClick` 改为：

```kotlin
            Button(
                onClick = {
                    if (exceedsSelectionCap(selected.size)) capConfirmVisible = true
                    else onConfirm(selected)
                },
                enabled = selected.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(confirmText)
            }
```

- [ ] **Step 3: 加确认弹窗**

在 `ChapterDownloadSheet` 的最外层（`ModalBottomSheet { ... }` **之后**、函数体的最后一个 `}` 之前）加：

```kotlin
    // 超限确认：确认后才真正下发；取消只是回到面板，选择集合原样保留
    if (capConfirmVisible) {
        AlertDialog(
            onDismissRequest = { capConfirmVisible = false },
            text = {
                Text(
                    stringResource(
                        R.string.download_cap_confirm_format,
                        selected.size,
                        MAX_DOWNLOAD_SELECTION
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        capConfirmVisible = false
                        onConfirm(selected)
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { capConfirmVisible = false }) {
                    Text(stringResource(com.ebook.common.R.string.cancel))
                }
            }
        )
    }
```

- [ ] **Step 4: 补 import**

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
```

- [ ] **Step 5: 编译并确认无新警告**

Run: `./gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，无 warning。

- [ ] **Step 6: 提交**

```bash
git add module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt module_book/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat(module_book): 单次下载超过五百章时二次确认

组头勾选让"一次选中上百章"只需一次点击，长书误触会一口气入库数千条任务并占用
前台服务数小时，给单次下发加一道软上限。

- 超过 500 章时点确认先弹二次确认，确认后照常下发，500 以内零摩擦
- 阈值不按"是否已缓存"区分：缓存文件存在不等于内容正确，不做判据
EOF
)"
```

---

## Task 7: ADR-0034

**Files:**
- Create: `docs/adr/0034-long-catalog-browse-and-select.md`

- [ ] **Step 1: 写 ADR**

新建 `docs/adr/0034-long-catalog-browse-and-select.md`（编号已扫现有最大 0033，未跳号；正文不引用本仓其他 ADR，也不引外部仓库编号）：

```markdown
# 长目录的浏览与选择形态：目录倒序会话内有效、下载面板按百章分组

阅读器有两个长目录入口——章节目录抽屉与离线下载面板。目录加一个仅本次会话有效的倒序开关；下载面板的单层平铺改为每 100 章一组的可展开分组，组头可整组勾选，并为单次下发量加 500 章软上限。这次形态调整同时定下三条容易被后来者改回去的口径：倒序不落盘、"有缓存文件"不等于"内容正确"因而不作任何判据、目录的快速滚动条不因有了倒序而降级。

## 动机

目录抽屉只有正序，连载书的读者要翻到最新章只能靠右侧快速滚动条从顶拖到底；下载面板则是把全部章节平铺进一个半屏高的列表，三千章的书要在半屏里手滚找章，这个缺口在实现时被明确记过一笔"大目录快速滚动可加，本面板不引入"。

## 决策

1. **目录倒序仅在本次会话内有效、不落盘**。理由是这个开关的默认值本身就是有争议的：连载书读者要倒序、已完结书读者要正序，而"上次开了倒序、这次找不到最新章节"的困惑比"每次都要重切一次"更贵。

2. **下载面板按 100 章分组，组头承担整组勾选**。下载面板的用途是"按范围取章"（下最近三百章、补全后半本），分组的导航面是组数而非章数，比给它单独加一个倒序开关更贴合用途；组头勾选让"下最近三百章"从三十次点击降到三四次。

3. **单次下发 500 章为软上限：超过只多一次确认，不阻止**。长书整本离线是正当操作，硬截断会让它变得做不成；而组头勾选让"一次选中上百章"只需一次点击，没有护栏时长书误触会一口气入库数千条任务、占住前台服务数小时。

4. **"是否已缓存"不作任何决策判据**。缓存文件存在不等于内容正确——缓存失败时也会有文件落盘，据此判定"已缓存"会得出错误结论。因此已缓存徽章与组头计数只作展示：既不参与勾选状态，也不参与上限判定；勾中已缓存章节仍按既有的强制刷新语义重下。

5. **目录的快速滚动条不作降级**。上千章的目录靠纯滑动翻找效率低，即使有了倒序，滚动条仍是"跳到书中任意位置"的唯一手段。

## 权衡

- **给下载面板加倒序（被拒）**：两个长目录用两套导航手法，用户要分别学；且倒序只解决"找最新"，不解决"在三千章里定位到某一章"。分组同时改善这两件事。
- **硬上限 500 章（被拒）**：会让"全选"与整本离线在长书上直接不可用，等于用一个护栏砍掉一个正当能力。
- **倒序落盘（被拒）**：见决策 1，默认值有争议时，不记忆比记错更便宜。
- **保留"仅未缓存"快捷选择**：它是唯一以缓存存在性为输入的选择入口，与决策 4 有张力。保留的理由是"补全还没下载的部分"仍是真实需求，且点击意图明确、结果在组头三态上立即可见；代价是那些"文件在但内容错"的章不会被它修到，要修得手动展开或全选。

## 下游影响

- 新增 `module_book` 的 `reader/ChapterSelection.kt`（全部可测语义）与 `reader/ChapterDownloadSheet.kt`（下载面板从 `ReaderPanels.kt` 搬出）。
- `ChapterListDrawer` 新增会话内倒序状态；章号、高亮、跳转一律改取原始章序号。
- `ChapterDownloadSheet` 新增 `focusIndex` 入参（含当前章的组默认展开并滚到该组头）。
- 已缓存徽章与新增的组头计数降级为纯信息，不再参与任何决策。
- 新增文案：目录顺序两态、组范围、组缓存计数、组展开/收起描述、超限确认正文。
```

- [ ] **Step 2: 核对编号未跳号**

Run: `ls docs/adr/ | tail -3`
Expected: 列表以 `0033-app-ships-no-book-source.md`、`0034-long-catalog-browse-and-select.md` 结尾。

- [ ] **Step 3: 提交**

```bash
git add docs/adr/0034-long-catalog-browse-and-select.md
git commit -m "$(cat <<'EOF'
docs(adr): 沉淀长目录浏览与选择形态决策

记录目录倒序会话内有效、下载面板按百章分组、500 章软上限，以及"缓存存在性不作
判据"这条容易被后来者改回去的口径。
EOF
)"
```

---

## Task 8: 全量验证与人工验证交接

**Files:** 无（只跑命令、只写交接说明）

- [ ] **Step 1: 跑单测与编译**

Run: `./gradlew :module_book:testDebugUnitTest :module_book:assembleDebug`
Expected: BUILD SUCCESSFUL；`ChapterSelectionTest` 11 个用例全绿；输出无新 warning。

- [ ] **Step 2: 确认没有夹带改动**

Run: `git status --short` 与 `git log --oneline -7`
Expected: 工作区干净；7 笔提交（6 笔功能/重构 + 1 笔 ADR），无意外文件（尤其无 `local.properties`、无构建产物）。

- [ ] **Step 3: 把人工验证清单写进交付说明**

Agent 止于编译与单测，以下各项**由人工在设备/模拟器上完成**，交付时必须原样列出并标注"未验证"：

1. **目录倒序**：切到倒序后首行是最新章且章号仍是绝对号（如"第 3000 章"）、当前章高亮位置正确、点击跳章落到正确章节；切回正序位置不错乱；关抽屉再打开仍是倒序（会话内保持）；退出阅读器再进回到正序。
2. **目录性能**：在 3000 章的书上疯狂滑动，抓 `adb shell dumpsys gfxinfo <pkg> framestats` 看掉帧分布——本轮两处清理是否有效、主瓶颈是否另有其处，据此决定是否再投入（本轮不承诺卡顿消除）。
3. **下载面板默认态**：打开即只展开当前章所在组，且列表已滚到该组头（当前章不在首组时尤其要确认滚动生效）。
4. **组交互**：点组头整行只展开/收起、不误选；点勾选框选中整组 100 章且三态正确；展开组滚动时组头吸附在顶部且不串字。
5. **上限护栏**：选到 501 章点确认出现二次确认弹窗，确认后任务照常下发；选 500 章无弹窗。
6. **任务落地**：确认后到下载管理页核对任务数与所选章数一致。
7. **独立模式回归**：`isModule=true` 下打开阅读器与下载面板无异常（调完改回 `false`，不要提交）。

- [ ] **Step 4: 提交（若上述步骤暴露了需要修的问题）**

修完问题后按 Task 归属各提一笔，不要把多个 Task 的修复攒成"杂项"提交。
