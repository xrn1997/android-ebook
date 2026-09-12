# 下载中心与目录定位交互 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 目录抽屉把「顺序/倒序」与「锁定当前章」解耦（切顺序保持锚点、瞄准镜切换锁定），下载选章并入「下载中心」两级结构（一级按书任务列表、二级该书选章并展示每章下载状态），阅读器下载入口直达该书二级。

**Architecture:** 全部可测语义（章节下载状态合并、分组/三态/上限）留在 `reader` 包纯逻辑文件；`DownloadRepository` 收敛「先入库再拉服务」统一入口并提供二级数据源；`DownloadManageActivity`（两级）与 `DownloadManageViewModel` 承担下载中心编排；`ChapterDownloadSheet.kt` 改为无 ModalBottomSheet 的 `BookChapterSelectPage`；阅读器仅负责加架后带参打开下载中心。

**Tech Stack:** Kotlin / Jetpack Compose（foundation 1.11.3 + material3 1.4.0，经 Compose BOM 2026.06.00）/ Room / Hilt / JUnit4。

**规格（事实源）:** `docs/superpowers/specs/2026-09-12-download-center-and-catalog-anchor-design.md`

---

## 前置约定

- **提交节奏**：每个 Task 结尾各一笔提交，提交后仓库必须可编译（`.\gradlew :module_book:compileDebugKotlin`）且单测绿。不要跨 Task 攒改动。
- **命令**：Windows PowerShell。提交信息含换行时用下方 PowerShell here-string 写法。
- **测试分工（AGENTS.md）**：纯逻辑 Task（1）走 TDD（先红后绿）；Compose 组合与 Activity 接线（2、4、5、6）正确性由编译 + Task 8 人工装机项负责，不为它们硬造 UI 测试。
- **已核实的依赖 API**：
  - `Icons.Outlined.MyLocation` / `Icons.AutoMirrored.Outlined.ArrowBack`：module_book 已有 `implementation(libs.androidx.compose.material.iconsExtended)`（`gradle/libs.versions.toml:93`），可直接 import。
  - `bookShelfDao.getBookFullInfoByUrl(noteUrl): BookShelfFullInfo?`（`@Embedded bookShelf` + `info` + `chapters`）已存在。
  - `DownloadService.start(context, intent)` / `buildStartIntent(context, chapters)` 已存在（原被 `BookReadViewModel.startDownload` 使用）。
  - 可复用既有 strings：`catalog_order_ascending/descending/toggle`、`download_manage_title/status_running/status_paused/status_idle/total_format/remaining_format/coverage_format/active_tag/queued_tag`、`download_selected_format`（已选 %1$d 章）、`select_all/select_uncached/clear_selection`、`chapter_group_range_format`、`chapter_group_cached_format`、`chapter_group_expand/collapse`、`chapter_number`（第 %1$d 章）、`cached_badge`、`download_bulk_confirm_message`、`download_bulk_confirm_continue`。
  - `offline_download` **不能删**：`DownloadService`（:509、:585）通知标题仍在用。

---

## Task 1: `ChapterDownloadStatus` —— 章节下载状态合并（TDD）

**Files:**
- Create: `module_book/src/main/java/com/ebook/book/reader/ChapterDownloadStatus.kt`
- Test: `module_book/src/test/java/com/ebook/book/reader/ChapterDownloadStatusTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [chapterDownloadStatus] 的纯逻辑用例：把缓存存在性、队列任务、当前下载章三方合并成逐章状态。
 *
 * 合并顺序算错只会让二级视图的「已缓存/待下载/下载中」标签错位，不编译失败也不闪退，
 * 故集中在纯 JVM 上锁住（本仓 Compose 页面不做装机级单测，见 AGENTS.md）。
 */
class ChapterDownloadStatusTest {

    @Test
    fun `无任何事实时全部是未下载`() {
        val status = chapterDownloadStatus(3, emptySet(), emptySet(), activeChapterIndex = null)

        assertEquals(
            listOf(
                ChapterDownloadStatus.NOT_DOWNLOADED,
                ChapterDownloadStatus.NOT_DOWNLOADED,
                ChapterDownloadStatus.NOT_DOWNLOADED,
            ),
            status
        )
    }

    @Test
    fun `缓存命中的章标记为已缓存`() {
        val status = chapterDownloadStatus(
            count = 3,
            cachedIndices = setOf(0, 1),
            queuedIndices = emptySet(),
            activeChapterIndex = null
        )

        assertEquals(ChapterDownloadStatus.CACHED, status[0])
        assertEquals(ChapterDownloadStatus.CACHED, status[1])
        assertEquals(ChapterDownloadStatus.NOT_DOWNLOADED, status[2])
    }

    @Test
    fun `队列与缓存并行时队列态优先`() {
        // 章 1 已被缓存但又在队列（forceRefresh 重下进行中）：展示「待下载」而非「已缓存」，
        // 用户因此知道勾中它会产生重下动作
        val status = chapterDownloadStatus(
            count = 3,
            cachedIndices = setOf(1),
            queuedIndices = setOf(1),
            activeChapterIndex = null
        )

        assertEquals(ChapterDownloadStatus.QUEUED, status[1])
    }

    @Test
    fun `当前下载章在队列内标记为下载中`() {
        val status = chapterDownloadStatus(
            count = 3,
            cachedIndices = emptySet(),
            queuedIndices = setOf(1, 2),
            activeChapterIndex = 1
        )

        assertEquals(ChapterDownloadStatus.DOWNLOADING, status[1])
        assertEquals(ChapterDownloadStatus.QUEUED, status[2])
    }

    @Test
    fun `active 不在队列时按普通队列态处理`() {
        // 防御：activeIndex 与 queued 失配（极端中间态）时不应出现"没在队却显示下载中"
        val status = chapterDownloadStatus(
            count = 3,
            cachedIndices = emptySet(),
            queuedIndices = setOf(2),
            activeChapterIndex = 1
        )

        assertEquals(ChapterDownloadStatus.NOT_DOWNLOADED, status[1])
    }

    @Test
    fun `空目录与越界输入不崩溃`() {
        assertEquals(emptyList(), chapterDownloadStatus(0, emptySet(), emptySet(), null))

        val status = chapterDownloadStatus(
            count = 2,
            cachedIndices = setOf(9),
            queuedIndices = setOf(9),
            activeChapterIndex = 9
        )
        assertEquals(
            listOf(ChapterDownloadStatus.NOT_DOWNLOADED, ChapterDownloadStatus.NOT_DOWNLOADED),
            status
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :module_book:testDebugUnitTest --tests "com.ebook.book.reader.ChapterDownloadStatusTest"`
Expected: 编译失败（`Unresolved reference: chapterDownloadStatus`）。

- [ ] **Step 3: 写实现**

```kotlin
package com.ebook.book.reader

/** 下载中心二级视图中一章的下载状态（仅作展示，不参与勾选决策——勾选语义仍是不变的重下）。 */
internal enum class ChapterDownloadStatus { NOT_DOWNLOADED, CACHED, QUEUED, DOWNLOADING }

/**
 * 把三方数据合并成逐章下载状态：缓存存在性（文件在 ≠ 内容对，ADR-0034 口径）、
 * 下载队列任务、当前正在下载的章。
 *
 * 状态优先级：**下载中 > 待下载 > 已缓存 > 未下载**。
 * - 已缓存且又入队（forceRefresh 重下）要展示队列态，用户才看得出勾中它会重抓；
 * - 下载中必须同时在队列内，active 与队列失配的中间态按普通队列/未下载处理，不出现幽灵「下载中」。
 */
internal fun chapterDownloadStatus(
    count: Int,
    cachedIndices: Set<Int>,
    queuedIndices: Set<Int>,
    activeChapterIndex: Int?,
): List<ChapterDownloadStatus> = List(count) { index ->
    when {
        index == activeChapterIndex && index in queuedIndices -> ChapterDownloadStatus.DOWNLOADING
        index in queuedIndices -> ChapterDownloadStatus.QUEUED
        index in cachedIndices -> ChapterDownloadStatus.CACHED
        else -> ChapterDownloadStatus.NOT_DOWNLOADED
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :module_book:testDebugUnitTest --tests "com.ebook.book.reader.ChapterDownloadStatusTest"`
Expected: PASS，6 个用例全绿。

- [ ] **Step 5: 反向实验（确认用例真的锁住了行为）**

把 `chapterDownloadStatus` 的优先级临时改成「`index in cachedIndices` 判断放到 `index in queuedIndices` 之前」，运行 `队列与缓存并行时队列态优先` 应变红，然后改回。

- [ ] **Step 6: 提交**

```powershell
$msg = @'
feat(module_book): 新增章节下载状态合并的纯逻辑

下载中心二级视图要给每章标注 已缓存/待下载/下载中，把缓存存在性、下载队列、
当前下载章三方合并成逐章状态，优先级下载中 > 待下载 > 已缓存 > 未下载，
先落成可单测的纯函数。

- 新增 ChapterDownloadStatus.kt 与 test：含「active 不在队列按普通态处理」的防御用例
EOF
'@
git add module_book/src/main/java/com/ebook/book/reader/ChapterDownloadStatus.kt module_book/src/test/java/com/ebook/book/reader/ChapterDownloadStatusTest.kt; git commit -m $msg
```

---

## Task 2: 目录抽屉 —— 顺序/锁定解耦 + 切顺序锚点保持

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt`（`ChapterListDrawer`）
- Modify: `module_book/src/main/res/values/strings.xml`

- [ ] **Step 1: 新增两条文案**

在 `strings.xml` 的 `<string name="catalog_order_toggle">切换章节顺序</string>` 之后插入：

```xml
    <string name="catalog_lock_enabled">定位当前章（开）</string>
    <string name="catalog_lock_disabled">定位当前章（关）</string>
```

- [ ] **Step 2: 拆状态并加锚点**

把 `ChapterListDrawer` 里这段（`var descending` 之后、`val displayChapters` 之前）：

```kotlin
    var descending by remember { mutableStateOf(false) }
    val count = chapters.size
```

替换为：

```kotlin
    var descending by remember { mutableStateOf(false) }
    // 打开抽屉/切顺序时是否自动定位当前章：独立于顺序的第二个维度（瞄准镜切换）。
    // 默认开（打开即定位当前章）；**点过顺序胶囊即关闭**——一切顺序就说明要浏览，
    // 不再把用户拽回当前章（ADR-0034「目录倒序会话内有效」口径的延续）。
    var lockedToCurrent by remember { mutableStateOf(true) }
    // 顺序翻转前的锚点（当前视口顶部章节的原始序号）：翻转后滚回该章，保证「进度条位置」不变
    var pendingAnchor by remember { mutableStateOf<Int?>(null) }
    val count = chapters.size
```

- [ ] **Step 3: 打开定位与锚点滚动两个 effect 分离**

把现有的（`val listState` 之后的）：

```kotlin
    // 打开即定位当前章节；descending 必须在 key 里——切模式当刻不重新定位，用户会被甩到
    // 与所读章节无关的位置
    LaunchedEffect(visible, descending) {
        if (visible && durChapter in chapters.indices) {
            listState.scrollToItem(
                displayPositionOf(
                    count = count,
                    descending = descending,
                    originalIndex = durChapter
                )
            )
        }
    }
```

替换为：

```kotlin
    // 职责分离：
    // 1) 打开只由 visible 驱动：锁定态才滚动到当前章（未锁定保持上次列表位置）；
    //    lockedToCurrent 仅读取现值、不进 key——翻转时的滚动由下面的锚点 effect 负责。
    // 2) 切顺序由 descending 驱动：把翻转前记下的锚点章滚回视口顶部。两者互不干扰。
    LaunchedEffect(visible) {
        if (visible && lockedToCurrent && durChapter in chapters.indices) {
            listState.scrollToItem(
                displayPositionOf(
                    count = count,
                    descending = descending,
                    originalIndex = durChapter
                )
            )
        }
    }
    LaunchedEffect(descending) {
        pendingAnchor?.let { anchor ->
            pendingAnchor = null
            if (anchor in 0 until count) {
                listState.scrollToItem(
                    displayPositionOf(
                        count = count,
                        descending = descending,
                        originalIndex = anchor
                    )
                )
            }
        }
    }
```

- [ ] **Step 4: 顺序胶囊改为「记锚点 → 翻转 → 关锁定」**

把顺序胶囊 `InfoChip(...)` 的 `onClick = { descending = !descending }` 替换为：

```kotlin
                        onClick = {
                            // 切顺序三件事一起做：
                            // 1) 记下当前视口顶部的章，翻转后滚回它（锚点保持，用户视角"进度条位置不变"）；
                            // 2) 翻转；
                            // 3) 关闭自动定位当前章（切过顺序 = 进入浏览态）。
                            // 视口尚未完成首次测量时（理论上不可达：此按钮只在抽屉可见时可达）不记锚点。
                            if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                                pendingAnchor = originalIndexAt(
                                    count = count,
                                    descending = descending,
                                    position = listState.firstVisibleItemIndex
                                )
                            }
                            descending = !descending
                            lockedToCurrent = false
                        }
```

- [ ] **Step 5: 顺序胶囊左侧加瞄准镜按钮**

在同一个 `Row` 里、顺序胶囊 `InfoChip` 之前插入：

```kotlin
                    // 定位开关（瞄准镜）：显示当前是否「打开即定位当前章」，点一下锁定、再点取消。
                    // 与顺序胶囊是两套独立逻辑（顺序管排序、锁定管定位），互不隐含。
                    IconButton(onClick = { lockedToCurrent = !lockedToCurrent }) {
                        Icon(
                            imageVector = Icons.Outlined.MyLocation,
                            contentDescription = stringResource(
                                if (lockedToCurrent) R.string.catalog_lock_enabled
                                else R.string.catalog_lock_disabled
                            ),
                            tint = if (lockedToCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
```

并补 import：`import androidx.compose.material.icons.outlined.MyLocation`（`Icons` / `IconButton` / `Icon` 以编译提示为准，缺则补）。

- [ ] **Step 6: 同步 KDoc**

`ChapterListDrawer` 的 KDoc（「打开时定位滚动到当前章节；…切模式时会重新定位到当前章，章号一律按原始序显示」）改为：「锁定态打开抽屉才定位当前章（瞄准镜切换）；切顺序保持锚点（视口顶部章节不变）并关闭自动定位；章号一律按原始序显示」。

- [ ] **Step 7: 编译并确认无新警告**

Run: `.\gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，输出无 warning。

- [ ] **Step 8: 提交**

```powershell
$msg = @'
feat(module_book): 目录抽屉顺序与定位解耦并保持切序锚点

打开抽屉一律定位当前章的做法在倒序下让倒序失去意义：切顺序后用户被拽回
当前章在倒序里的等价位置，最新章明明在顶部却看不到。把「顺序」与「锁定」
拆成两个独立状态，切顺序只保持锚点。

- 新增 lockedToCurrent（默认开，瞄准镜按钮切换）；点顺序胶囊即关闭自动定位
- 新增锚点滚动：翻转前记视口顶章，翻转后滚回该章（进度条位置不变）
- 打开定位与锚点滚动两个 LaunchedEffect 职责分离
EOF
'@
git add module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt module_book/src/main/res/values/strings.xml; git commit -m $msg
```

---

## Task 3: `DownloadRepository` 统一下发入口与二级数据源

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/repository/DownloadRepository.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/dao/DownloadChapterDao.kt`
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt`（删 `startDownload`）

- [ ] **Step 1: DAO 加按书取任务**

`DownloadChapterDao.kt` 的 `getLastByNoteUrl` 之后加：

```kotlin
    /** 某本书的全部待下载任务（按章序升序）：下载中心二级据此标注「待下载/下载中」 */
    @Query("SELECT * FROM download_chapter WHERE note_url = :noteUrl ORDER BY dur_chapter_index ASC")
    suspend fun getByNoteUrl(noteUrl: String): List<DownloadChapterEntity>
```

- [ ] **Step 2: Repository 构造注 Context 并补依赖**

`DownloadRepository.kt`：构造参数加 `@ApplicationContext private val context: Context`；补 import：

```kotlin
import android.content.Context
import com.ebook.book.service.DownloadService
import com.ebook.db.entity.BookShelfFullInfo
import com.ebook.db.entity.ChapterListEntity
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.qualifiers.ApplicationContext
```

- [ ] **Step 3: 新增二级数据源与统一下发入口**

在 `DownloadRepository` 类内（`getCacheCoverage` 之后）追加：

```kotlin
    /**
     * 某书完整书架信息（含书名/封面/章节目录）：下载中心二级数据源。
     * 不在书架返回 null（阅读器入口会先把书加进书架再进下载中心）。
     */
    suspend fun getBookFullInfo(noteUrl: String): BookShelfFullInfo? =
        withContext(Dispatchers.IO) { bookShelfDao.getBookFullInfoByUrl(noteUrl) }

    /** 某书全部待下载任务（按章序）：供二级标注每章状态。 */
    suspend fun getTasksByBook(noteUrl: String): List<DownloadChapterEntity> =
        withContext(Dispatchers.IO) { downloadChapterDao.getByNoteUrl(noteUrl) }

    /**
     * 某书已缓存章集合（网络书，章文件存在性为事实源）。
     *
     * 与 [getCacheCoverage] 同一判定口径：二级视图只看网络书（下载任务与书架下载入口
     * 都排除本地书），故定位直接用 `NETWORK`，不做本地书分支。
     */
    suspend fun getCachedIndices(
        noteUrl: String,
        tag: String,
        chapters: List<ChapterListEntity>,
    ): Set<Int> = withContext(Dispatchers.IO) {
        val location = BookLocation(noteUrl, BookFormat.NETWORK, tag)
        chapters.filter { bookStore.hasChapter(location, it.durChapterIndex) }
            .mapTo(mutableSetOf()) { it.durChapterIndex }
    }

    /**
     * 批量下发下载任务：**先入库、再拉起前台服务**（顺序即「发起方先入库再拉服务」——
     * 服务启动被拒时任务已落库不丢；[addTasks] 按章 URL 去重，重入幂等）。
     *
     * 原 `BookReadViewModel.startDownload` 的实现迁移至此，作为全仓唯一下发入口；
     * 启动被拒（dataSync 配额用尽等）时页内提示，不抛未捕获异常。
     */
    suspend fun startDownload(chapters: List<DownloadChapterEntity>) {
        if (chapters.isEmpty()) return
        addTasks(chapters)
        if (!DownloadService.start(context, DownloadService.buildStartIntent(context, chapters))) {
            ToastUtil.showShort(context, context.getString(R.string.download_start_restricted))
        }
    }
```

- [ ] **Step 4: 删除 `BookReadViewModel.startDownload` 并清理引用**

`BookReadViewModel.kt`：删除 `startDownload(chapters)` 整体；删除因此未再使用的 import（`DownloadService`、`ToastUtil`，以编译提示为准）。`addToShelf` / `OnAddListener` / `getChapter` 等其余成员保持不动（Task 6 还会用 `addToShelf`）。

- [ ] **Step 5: 编译并确认无新警告**

Run: `.\gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，无 warning。

- [ ] **Step 6: 提交**

```powershell
$msg = @'
refactor(module_book): 下载下发入口收敛到 DownloadRepository

阅读器确认下载的"构建任务→入库→拉服务"链路要与下载中心二级共用，把先入库再
拉前台服务收成一个仓库级入口，避免两处各写一遍顺序敏感的链路。

- 新增 startDownload：addTasks + DownloadService.start（原 BookReadViewModel.startDownload 迁移）
- 新增二级数据源：getBookFullInfo / getTasksByBook / getCachedIndices
- DownloadChapterDao 新增按书取任务查询 getByNoteUrl
EOF
'@
git add module_book/src/main/java/com/ebook/book/repository/DownloadRepository.kt lib_ebook_db/src/main/java/com/ebook/db/dao/DownloadChapterDao.kt module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt; git commit -m $msg
```

---

## Task 4: 下载中心一级 —— 卡片可点 + 显示「正在下载 第 N 章」

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/DownloadManageActivity.kt`
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/DownloadManageViewModel.kt`
- Modify: `module_book/src/main/res/values/strings.xml`

- [ ] **Step 1: 新增文案**

在 `strings.xml` 的 `<string name="download_manage_remaining_format">…</string>` 之后插入：

```xml
    <string name="download_manage_downloading_chapter_format">正在下载 第 %1$d 章 · %2$s</string>
    <string name="download_group_pick">选择本书章节</string>
```

- [ ] **Step 2: `DownloadBookGroup` 加字段**

`DownloadManageViewModel.kt` 的 `DownloadBookGroup` 追加：

```kotlin
    /** 该书书源归属标记（二级视图取 parser 与缓存定位用，来自组内首条任务） */
    val tag: String,
    /** 当前正在下载的章节（仅活跃书有值；下载进度口径之一：下载进度 ≠ 全书覆盖率，见类 KDoc） */
    val activeChapter: DownloadChapterEntity? = null,
```

- [ ] **Step 3: `loadGroups` 填充新字段**

`loadGroups()` 的 `DownloadBookGroup(...)` 构造改为：

```kotlin
                    val active = tasks.any { it.durChapterUrl == activeChapterUrl }
                    DownloadBookGroup(
                        noteUrl = noteUrl,
                        bookName = first.bookName,
                        coverUrl = first.coverUrl,
                        tag = first.tag,
                        remaining = tasks.size,
                        totalChapters = coverage.total,
                        cachedChapters = coverage.cached,
                        isActive = active,
                        activeChapter = if (active) {
                            tasks.first { it.durChapterUrl == activeChapterUrl }
                        } else null
                    )
```

- [ ] **Step 4: 一级卡改造（可点进二级 + 下载进度行）**

`DownloadManageActivity.kt`：

1. `DownloadManageScreen` 签名加 `onOpenBook: (DownloadBookGroup) -> Unit`，`items(groups...)` 里的 `DownloadGroupCard(...)` 调用处加 `onClick = { onOpenBook(group) }`；
2. `DownloadGroupCard` 签名加 `onClick: () -> Unit`，`CommonCard { Column(...) }` 的**内层** `Column` 加 `Modifier.clickable(onClickLabel = stringResource(R.string.download_group_pick), onClick = onClick)`（卡内「取消本书」TextButton 消费自己的点击，不受影响）；
3. 在「剩余 %1$d 章」`Text` 之下加：

```kotlin
                    // 下载进度口径之一：正在下载第几章；与「全书缓存覆盖率」进度条刻意分开
                    //（下载进度 ≠ 覆盖率，见 DownloadBookGroup KDoc）
                    group.activeChapter?.let { active ->
                        Text(
                            text = stringResource(
                                R.string.download_manage_downloading_chapter_format,
                                active.durChapterIndex + 1,
                                active.durChapterName
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
```

4. 补 import：`androidx.compose.foundation.clickable`。

（点击行为由 Task 5 的 `viewModel::openBook` 接线消费；本任务先用 `onOpenBook(group)` 参数接住、Activity 处暂传 `viewModel::openBook`，OpenBook 在 Task 5 才实现——因此本任务先在 `DownloadManageScreen` 调用处传 `onOpenBook = { }` 占位，Task 5 换成真实引用。）

- [ ] **Step 5: 编译并确认无新警告**

Run: `.\gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，无 warning。

- [ ] **Step 6: 提交**

```powershell
$msg = @'
feat(module_book): 下载中心一级卡片显示下载进度并可点进选章

下载管理页此前进度条是全书覆盖率，看不出"这批任务下到哪"——卡内新增
"正在下载 第 N 章 · 章名"行，与覆盖率条口径分开；整卡可点，为二级选章页入口。
EOF
'@
git add module_book/src/main/java/com/ebook/book/DownloadManageActivity.kt module_book/src/main/java/com/ebook/book/mvvm/viewmodel/DownloadManageViewModel.kt module_book/src/main/res/values/strings.xml; git commit -m $msg
```

---

## Task 5: 下载中心二级 —— 该书选章页（改造 ChapterDownloadSheet 并接线）

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/DownloadManageViewModel.kt`
- Modify: `module_book/src/main/java/com/ebook/book/DownloadManageActivity.kt`
- Modify: `module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt`（改为 `BookChapterSelectPage`）
- Modify: `module_book/src/main/res/values/strings.xml`

- [ ] **Step 1: 新增文案**

在 `strings.xml` 插入（位置任取，就近语义）:

```xml
    <string name="download_skip_queued_format">已跳过 %1$d 章（已在下载队列）</string>
    <string name="download_center_back">返回下载列表</string>
    <string name="download_center_loading">正在加载…</string>
    <string name="download_short_format">下载 %1$d 章</string>
```

- [ ] **Step 2: VM 新增步骤与选中数据结构**

`DownloadManageViewModel.kt` 追加（文件层级的类型）：

```kotlin
/** 下载中心页面级步骤：一级按书列表 / 二级某书选章。 */
sealed interface DownloadCenterStep {
    data object Books : DownloadCenterStep
    data class PickBook(val noteUrl: String, val tag: String) : DownloadCenterStep
}

/**
 * 二级视图数据：书信息 + 章目录 + 缓存/队列事实 + 当前下载章 + 预勾选。
 *
 * [initialSelected] 只在「从阅读器进入（带了 focusChapter）」时非空：沿用原下载面板
 * 的「当前章 + 50 章内未缓存且未排队者」一键下载预勾选；从一级进入则为空集。
 */
data class BookChapterSelection(
    val noteUrl: String,
    val tag: String,
    val bookName: String,
    val coverUrl: String,
    val chapters: List<ChapterListEntity>,
    val cachedIndices: Set<Int>,
    val queuedIndices: Set<Int>,
    val activeChapterIndex: Int?,
    val initialSelected: Set<Int>,
)
```

- [ ] **Step 3: VM 成员（状态 + openBook / refreshSelection / backToBooks / confirmDownload）**

`DownloadManageViewModel` 类内（`_groups` 之下）追加：

```kotlin
    private val _step = MutableStateFlow<DownloadCenterStep>(DownloadCenterStep.Books)
    val step: StateFlow<DownloadCenterStep> = _step.asStateFlow()

    private val _selection = MutableStateFlow<BookChapterSelection?>(null)
    val selection: StateFlow<BookChapterSelection?> = _selection.asStateFlow()

    /** 阅读器进入时携带的当前章（>=0 才启用「当前章+50」预勾选与定位；一级入口为 -1）。 */
    private var pendingFocusChapter: Int = -1

    /**
     * 进入某书二级选章态并装载数据。
     *
     * [focusChapter] 为阅读器传入的当前章；书不在架时 _selection 置 null（页面空态）。
     */
    fun openBook(noteUrl: String, tag: String, focusChapter: Int = -1) {
        _step.value = DownloadCenterStep.PickBook(noteUrl, tag)
        pendingFocusChapter = focusChapter
        loadSelection()
    }

    /** 重新装载当前书的二级数据（下载进行中每章推进后刷新状态标签用）。 */
    fun refreshSelection() {
        if (_step.value is DownloadCenterStep.PickBook) loadSelection()
    }

    private fun loadSelection() {
        val step = _step.value as? DownloadCenterStep.PickBook ?: return
        viewModelScope.launch {
            val full = model.getBookFullInfo(step.noteUrl)
            if (full == null) {
                _selection.value = null
                return@launch
            }
            val chapters = full.chapters
            val cached = model.getCachedIndices(step.noteUrl, step.tag, chapters)
            val tasks = model.getTasksByBook(step.noteUrl)
            val queued = tasks.mapTo(mutableSetOf()) { it.durChapterIndex }
            val active = tasks.firstOrNull { it.durChapterUrl == activeChapterUrl }?.durChapterIndex
            val initialSelected = buildInitialSelection(chapters, cached, queued)
            _selection.value = BookChapterSelection(
                noteUrl = step.noteUrl,
                tag = step.tag,
                bookName = full.info?.name ?: "",
                coverUrl = full.info?.coverUrl ?: "",
                chapters = chapters,
                cachedIndices = cached,
                queuedIndices = queued,
                activeChapterIndex = active,
                initialSelected = initialSelected,
            )
        }
    }

    /**
     * 预勾选：「当前章 .. 当前章+50」中未缓存且未排队者（原下载面板一键下载习惯）。
     * **排除已排队**——排队中的章确认时会跳过，预勾上只会让确认文案多一行"已跳过"。
     */
    private fun buildInitialSelection(
        chapters: List<ChapterListEntity>,
        cached: Set<Int>,
        queued: Set<Int>,
    ): Set<Int> {
        if (chapters.isEmpty() || pendingFocusChapter < 0) return emptySet()
        val end = (pendingFocusChapter + 50).coerceAtMost(chapters.size - 1)
        return (pendingFocusChapter..end).filterTo(mutableSetOf()) { i ->
            i !in cached && i !in queued
        }
    }

    /** 从二级返回一级列表。 */
    fun backToBooks() {
        _step.value = DownloadCenterStep.Books
        _selection.value = null
    }

    /**
     * 确认下载：**剔除已在队列中的章**（排队中再勾 = 无意义重下，与队列唯一索引语义重复），
     * 构建任务（forceRefresh=true）后经 [DownloadRepository.startDownload] 统一下发。
     * 跳过计数由页面读 selection + selected 计算（见 BookChapterSelectPage）。
     */
    fun confirmDownload(selected: Set<Int>) {
        val selection = _selection.value ?: return
        val tasks = (selected - selection.queuedIndices).sorted().mapNotNull { i ->
            selection.chapters.getOrNull(i)?.let { chapter ->
                DownloadChapterEntity(
                    noteUrl = selection.noteUrl,
                    durChapterIndex = chapter.durChapterIndex,
                    durChapterName = chapter.durChapterName,
                    durChapterUrl = chapter.contentRef,
                    tag = selection.tag,
                    bookName = selection.bookName,
                    coverUrl = selection.coverUrl,
                    forceRefresh = true,
                )
            }
        }
        if (tasks.isEmpty()) return
        viewModelScope.launch { model.startDownload(tasks) }
    }
```

补 import：`com.ebook.db.entity.ChapterListEntity`、`com.ebook.db.entity.DownloadChapterEntity`、`kotlinx.coroutines.flow.StateFlow`（如缺失）。

- [ ] **Step 4: Activity 加 intent 常量 + 两级编排 + 通知权限**

`DownloadManageActivity.kt`：

1. companion（类内加）：

```kotlin
    companion object {
        const val EXTRA_NOTE_URL = "extra_note_url"
        const val EXTRA_TAG = "extra_tag"
        const val EXTRA_FOCUS_CHAPTER = "extra_focus_chapter"
        const val EXTRA_OPEN_PICK = "extra_open_pick"
    }
```

2. `PageContent()` 改为：

```kotlin
    @Composable
    override fun PageContent() {
        DownloadCenterScreen(activity = this, viewModel = viewModel)
    }
```

3. 新增通知权限申请（从阅读器迁移；「拒绝不影响下载」语义保持）：

```kotlin
    /**
     * 申请下载进度通知权限（POST_NOTIFICATIONS）。
     *
     * 无论授予与否都回调 [onResult]：通知只是进度的展示渠道，前台服务与落库不依赖它；
     * 把它当成下载前置门槛会造成"拒绝过一次通知 → 点下载完全没反应"。
     */
    fun requestDownloadPermission(onResult: () -> Unit) {
        PermissionX
            .init(this)
            .permissions(PermissionX.permission.POST_NOTIFICATIONS)
            .request { _: Boolean, _: List<String?>?, _: List<String?>? -> onResult() }
    }
```

补 import：`android.content.Intent`、`com.permissionx.guolindev.PermissionX`（以 ReadBookActivity 现有用法为准）。

4. 文件内新增编排 composable：

```kotlin
/**
 * 下载中心两级编排：一级按书任务列表，二级该书选章页。
 *
 * 硬件返回：二级 → 一级；一级 → 系统默认（退出页面）。
 * 从阅读器带 extras 进入时直达二级（EXTRA_OPEN_PICK）。
 */
@Composable
private fun DownloadCenterScreen(
    activity: DownloadManageActivity,
    viewModel: DownloadManageViewModel,
) {
    val step by viewModel.step.collectAsState()

    BackHandler(enabled = step is DownloadCenterStep.PickBook) {
        viewModel.backToBooks()
    }

    // 状态驱动刷新：进度每推进一章，一级分组与（若在二级）该书状态标签同步刷新；
    // 打开页面时若队列有任务则自动续跑（对齐原弹窗 initWait，见 resumeIfPending）
    LaunchedEffect(Unit) {
        viewModel.loadGroups()
        viewModel.resumeIfPending()
        viewModel.downloadState.collect { s ->
            when (s) {
                is DownloadState.Progress -> {
                    viewModel.onProgressChapter(s.chapter)
                    viewModel.refreshSelection()
                    viewModel.loadGroups()
                }
                DownloadState.Paused, DownloadState.Finished -> viewModel.loadGroups()
            }
        }
    }

    // 阅读器直达：首次组合即进入某书二级选章
    LaunchedEffect(Unit) {
        val intent = activity.intent
        if (intent.getBooleanExtra(DownloadManageActivity.EXTRA_OPEN_PICK, false)) {
            val noteUrl = intent.getStringExtra(DownloadManageActivity.EXTRA_NOTE_URL) ?: return@LaunchedEffect
            val tag = intent.getStringExtra(DownloadManageActivity.EXTRA_TAG) ?: ""
            val focus = intent.getIntExtra(DownloadManageActivity.EXTRA_FOCUS_CHAPTER, -1)
            viewModel.openBook(noteUrl, tag, focus)
        }
    }

    when (val current = step) {
        is DownloadCenterStep.Books -> DownloadManageScreen(
            viewModel = viewModel,
            onOpenBook = viewModel::openBook
        )
        is DownloadCenterStep.PickBook -> {
            val selection by viewModel.selection.collectAsState()
            val sel = selection
            if (sel == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.download_center_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                BookChapterSelectPage(
                    selection = sel,
                    onBack = viewModel::backToBooks,
                    onConfirm = { selected ->
                        activity.requestDownloadPermission { viewModel.confirmDownload(selected) }
                    }
                )
            }
        }
    }
}
```

**注意**：把原 `DownloadManageScreen` 内部的「LaunchedEffect 拉分组/续跑/状态收集再刷新」整体移到 `DownloadCenterScreen`，`DownloadManageScreen` 只保留渲染（删除其内部两个 `LaunchedEffect`），并改为接收 `onOpenBook: (DownloadBookGroup) -> Unit`（Task 4 已加参数位）。

- [ ] **Step 5: `ChapterDownloadSheet.kt` 改造为 `BookChapterSelectPage`**

把 `module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt` 整体改写为下面代码（删除 ModalBottomSheet 壳；行加状态标签；确认按钮文案带实际下发数；队列跳过计数显示在按钮上方；文件名保留，内容与函数名替换）：

```kotlin
package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.book.mvvm.viewmodel.BookChapterSelection
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip

/**
 * 下载中心二级：某书全章节的「状态 + 选章」整屏页（替代原 ModalBottomSheet 下载面板）。
 *
 * 每章状态来自 [chapterDownloadStatus] 的三方合并（已缓存/待下载/下载中），**只作展示**：
 * 勾选语义保持不变——勾中任何章（含已缓存）都按 forceRefresh 重下。
 * 确认时剔除已在队列中的章（见 DownloadManageViewModel.confirmDownload），跳过计数显示在
 * 按钮上方。分组/三态/软上限等语义沿用 ChapterSelection.kt。
 */
@Composable
fun BookChapterSelectPage(
    selection: BookChapterSelection,
    onConfirm: (Set<Int>) -> Unit,
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf(selection.initialSelected) }
    // 软上限二次确认：超过 500 章时不直接下发（见 exceedsSelectionCap）
    var capConfirmVisible by remember { mutableStateOf(false) }

    val chapters = selection.chapters
    val queuedIndices = selection.queuedIndices
    val statusList = remember(chapters, selection.cachedIndices, queuedIndices, selection.activeChapterIndex) {
        chapterDownloadStatus(
            count = chapters.size,
            cachedIndices = selection.cachedIndices,
            queuedIndices = queuedIndices,
            activeChapterIndex = selection.activeChapterIndex,
        )
    }

    // 未缓存且未排队索引集：「仅未缓存」chip 的选择输入（队列中的章预勾只会被确认时跳过）
    val uncachedIndices = remember(chapters, selection.cachedIndices, queuedIndices) {
        chapters.indices.filterTo(mutableSetOf()) { i ->
            i !in selection.cachedIndices && i !in queuedIndices
        }
    }

    val groups = remember(chapters.size) { chapterGroups(chapters.size) }
    // 默认只展开含焦点章（阅读器当前章）的那一组：其余折叠后 3000 章 = 30 行组头
    val focusChapter = selection.initialSelected.minOrNull() ?: -1
    val focusGroupIndex = if (focusChapter >= 0) {
        groups.indexOfFirst { focusChapter in it.first..it.last }
    } else -1
    var expanded by remember {
        mutableStateOf(if (focusGroupIndex >= 0) setOf(focusGroupIndex) else emptySet())
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (focusGroupIndex >= 0) {
            listState.scrollToItem(rowIndexOfGroup(groups, expanded, focusGroupIndex))
        }
    }

    // 确认实际下发数（剔除已排队）与跳过统计
    val downloadCount = (selected - queuedIndices).size
    val skippedQueued = (selected intersect queuedIndices).size
    val confirmEnabled = downloadCount > 0

    Column(modifier = Modifier.fillMaxSize()) {
        // 页头：返回 + 书名 + 已选计数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.download_center_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = selection.bookName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            InfoChip(
                text = stringResource(R.string.download_selected_format, selected.size),
                shape = RoundedCornerShape(50),
                containerColor = if (selected.isEmpty()) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSecondaryContainer,
                textStyle = MaterialTheme.typography.labelMedium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        // 快捷选择：全选 / 仅未缓存 / 清除
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickSelectChip(
                label = stringResource(R.string.select_all),
                modifier = Modifier.weight(1f),
            ) { selected = chapters.indices.toSet() }
            QuickSelectChip(
                label = stringResource(R.string.select_uncached),
                modifier = Modifier.weight(1f),
            ) { selected = uncachedIndices }
            QuickSelectChip(
                label = stringResource(R.string.clear_selection),
                modifier = Modifier.weight(1f),
            ) { selected = emptySet() }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 章节列表：整屏可用（替代原半屏 ModalBottomSheet），分组后导航面 = 组数
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            // 行结构是与 rowIndexOfGroup 的契约：每组恒占 1 行组头 + 展开时 count 行章行
            groups.forEach { group ->
                stickyHeader(key = "g${group.index}", contentType = "group") { _ ->
                    GroupHeaderRow(
                        rangeText = stringResource(
                            R.string.chapter_group_range_format,
                            group.first + 1,
                            group.last + 1
                        ),
                        cachedText = stringResource(
                            R.string.chapter_group_cached_format,
                            (group.first..group.last).count { it in selection.cachedIndices },
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
                            status = statusList[index],
                        ) {
                            selected = if (index in selected) selected - index else selected + index
                        }
                    }
                }
            }
        }
        // 跳过统计 + 确认按钮
        Column(modifier = Modifier.padding(CommonUiTokens.pagePadding)) {
            if (skippedQueued > 0) {
                Text(
                    text = stringResource(R.string.download_skip_queued_format, skippedQueued),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Button(
                onClick = {
                    if (exceedsSelectionCap(downloadCount)) capConfirmVisible = true
                    else onConfirm(selected)
                },
                enabled = confirmEnabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(stringResource(R.string.download_short_format, downloadCount))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 超限确认：点确认后才真正下发；点取消只关弹窗，选择集合原样保留
    if (capConfirmVisible) {
        AlertDialog(
            onDismissRequest = { capConfirmVisible = false },
            text = {
                Text(stringResource(R.string.download_bulk_confirm_message, downloadCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        capConfirmVisible = false
                        onConfirm(selected)
                    }
                ) {
                    Text(stringResource(R.string.download_bulk_confirm_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { capConfirmVisible = false }) {
                    Text(stringResource(com.ebook.common.R.string.cancel))
                }
            }
        )
    }
}

/** 组头行：三态勾选框 + 章范围 + 已缓存计数 + 展开箭头（吸附头需实心底色防串字）。 */
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
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(
                onClickLabel = stringResource(
                    if (expanded) R.string.chapter_group_collapse
                    else R.string.chapter_group_expand
                ),
                onClick = onToggleExpand
            )
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
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}

/** 快捷选择胶囊（同原下载面板）。 */
@Composable
private fun QuickSelectChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 下载行：勾选框 + 序号 + 章名 + 状态标签。
 *
 * 整行可点切换勾选；Checkbox 的 onCheckedChange 置 null（语义由整行点击统一控制）。
 * 状态标签只展示（下载中/待下载/已缓存），不参与勾选决策。
 */
@Composable
private fun DownloadChapterRow(
    index: Int,
    name: String,
    isChecked: Boolean,
    status: ChapterDownloadStatus,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isChecked) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isChecked, onCheckedChange = null)
        Text(
            text = stringResource(R.string.chapter_number, index + 1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        val chipText = when (status) {
            ChapterDownloadStatus.DOWNLOADING -> R.string.download_manage_active_tag
            ChapterDownloadStatus.QUEUED -> R.string.download_manage_queued_tag
            ChapterDownloadStatus.CACHED -> R.string.cached_badge
            ChapterDownloadStatus.NOT_DOWNLOADED -> null
        }
        if (chipText != null) {
            InfoChip(
                text = stringResource(chipText),
                shape = RoundedCornerShape(50),
                containerColor = if (status == ChapterDownloadStatus.DOWNLOADING) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (status == ChapterDownloadStatus.DOWNLOADING) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textStyle = MaterialTheme.typography.labelSmall,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
```

- [ ] **Step 6: 清理旧文案**

`ChapterDownloadSheet.kt` 重写后不再引用 `download_skip_cached_format`；grep 全仓确认无其它引用后删除该 string（`offline_download` **保留**，`DownloadService` 在用）。

- [ ] **Step 7: 编译并确认无新警告**

Run: `.\gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，无 warning。若从旧文件带出未用 import（`navigationBarsPadding`/`LocalDensity`/`LocalWindowInfo` 等）或被删 LaunchedEffect 留下的孤立 import，逐条清理。

- [ ] **Step 8: 提交**

```powershell
$msg = @'
feat(module_book): 下载中心新增书籍选章二级页

把下载选章从半屏 ModalBottomSheet 改为下载中心的二级整屏页：一级点书卡进入，
阅读器入口带参直达。每章叠加下载状态（已缓存/待下载/下载中），确认剔除已在
队列中的章并留在本页实时刷新。

- DownloadManageViewModel 新增 step/selection 状态与 openBook/confirmDownload/refreshSelection
- ChapterDownloadSheet 改为 BookChapterSelectPage（去 ModalBottomSheet 壳，整屏列表）
- Activity 新增 intent 常量、两级编排、通知权限申请迁移
EOF
'@
git add module_book/src/main/java/com/ebook/book/mvvm/viewmodel/DownloadManageViewModel.kt module_book/src/main/java/com/ebook/book/DownloadManageActivity.kt module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt module_book/src/main/res/values/strings.xml; git commit -m $msg
```

---

## Task 6: 阅读器下载入口接入下载中心并清理旧链路

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt`
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt`（删 `ReaderPanel.DOWNLOAD`）

- [ ] **Step 1: 下载入口改为「先加架 → 打开下载中心」**

`ReadBookActivity.kt` 把 `val openDownloadSheet: () -> Unit = { ... }` 整体替换为：

```kotlin
    // 下载入口：阅读器不再自己构造任务，打开「下载中心」直达该书选章二级页。
    // 下载任务须挂在书架行上才能被 DownloadService 拉取，且二级页按 note_url 读章目录，
    // 故先确保该书在架（原「确认下载时加架」语义提前到入口），成功后再带参打开。
    val openDownloadCenter = {
        menuVisible = false
        viewModel.addToShelf(object : BookReadViewModel.OnAddListener {
            override fun addSuccess() {
                val shelf = viewModel.bookShelf ?: return
                context.startActivity(
                    Intent(context, DownloadManageActivity::class.java).apply {
                        putExtra(DownloadManageActivity.EXTRA_NOTE_URL, shelf.noteUrl)
                        putExtra(DownloadManageActivity.EXTRA_TAG, shelf.tag)
                        putExtra(DownloadManageActivity.EXTRA_FOCUS_CHAPTER, shelf.durChapter)
                        putExtra(DownloadManageActivity.EXTRA_OPEN_PICK, true)
                    }
                )
            }
        })
    }
```

把 `onDownload = openDownloadSheet` 改为 `onDownload = openDownloadCenter`。补 import：`android.content.Intent`（`DownloadManageActivity` 同包无需 import）。

- [ ] **Step 2: 删除阅读器旧下载链路**

删除：
- 私有 data class `DownloadSheetArgs`；
- `ReaderPanel.DOWNLOAD` 分支（`when (panel)` 里整段 ChapterDownloadSheet 调用）；
- `private fun startChapterDownload(...)` 整体；
- `fun requestDownloadPermission(...)` 整体（已迁到 DownloadManageActivity）；
- 页面状态 `var downloadArgs by remember { MutableStateOf<DownloadSheetArgs?>(null) }`；
- 文件顶部 `import com.ebook.book.reader.ChapterDownloadSheet`；
- 因此不再使用的 import（`DownloadChapterEntity` 等，以编译提示为准）。

- [ ] **Step 3: `ReaderPanel` 枚举去掉 DOWNLOAD**

`ReaderPanels.kt:131` 改为：

```kotlin
internal enum class ReaderPanel { NONE, CHAPTER, LIGHT, FONT, SETTING, SOURCE_SWITCH }
```

（`ReadBookActivity` 的 `when (panel)` 已无 DOWNLOAD 分支，不再引用。）

- [ ] **Step 4: 编译并确认无新警告**

Run: `.\gradlew :module_book:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，无 warning。

- [ ] **Step 5: 提交**

```powershell
$msg = @'
refactor(module_book): 阅读器下载入口接入下载中心并清理旧链路

阅读器确认下载的整条旧链路（Sheet 弹窗、预勾选快照、startChapterDownload、
通知权限申请）全部下线，改为先确保在架再带参打开下载中心直达该书选章二级页。
EOF
'@
git add module_book/src/main/java/com/ebook/book/ReadBookActivity.kt module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt; git commit -m $msg
```

---

## Task 7: 文档 —— ADR-0034 更新、ADR-0035 新增、ADR-0018 引用同步

**Files:**
- Modify: `docs/adr/0034-long-catalog-browse-and-select.md`
- Create: `docs/adr/0035-download-center.md`
- Modify: `docs/adr/0018-download-foreground-service-data-sync-quota.md`

- [ ] **Step 1: ADR-0034 就地更新**

`docs/adr/0034-long-catalog-browse-and-select.md`：
- 「目录倒序会话内有效、不落盘」决策保留；
- 把「切模式总是重新定位到当前章」表述更新为：**目录抽屉的「顺序」与「定位」是两个独立开关——锁定（默认开）时才在打开抽屉时定位当前章；点过顺序胶囊即关闭锁定；顺序切换保持锚点（视口顶部章节不变）**，并标注本次修订日期；
- 若正文把下载选择描述为「面板（ModalBottomSheet）」，补一句自足修订说明：「下载选章已并入下载中心的书籍选章页，不再使用半屏面板形态」。

- [ ] **Step 2: 新增 ADR-0035「下载中心」**

新建 `docs/adr/0035-download-center.md`（编号递增、正文自足、不引用本仓其他 ADR 编号）：

```markdown
# 下载中心：选章与管理合一（两级结构）

下载选章与下载管理合并为一个「下载中心」页面：一级按书任务列表，二级某书全章节的「状态 + 选章」整屏视图；阅读器下载入口与该页路由到同一页面。同时定下三条容易被后来者改回去的口径：下载进度与全书覆盖率是两个不同指标、`download_chapter` 只存未完成章（已下好哪些章以缓存文件存在性判断）、确认下载后留在二级。

## 动机

下载选章此前是阅读器内的半屏底部面板，三千章的书要在半屏里扫分组，滑动误触还会连选择一起丢掉；下载管理页的进度条画的是全书缓存覆盖率（不是本次批次的下载进度），且看不到具体下了哪些章节。两者数据本可复用同一份「章目录 + 缓存 + 队列」，却分居两处。

## 决策

1. **选章与管理合一为下载中心的两级结构**：一级按书任务列表（状态 + 队列剩余 + 正在下载第 N 章 + 全书覆盖率 + 取消），点书卡进二级；二级是该书全部章节的分组勾选列表，每行叠加下载状态（已缓存 / 待下载 / 下载中）。阅读器「下载」入口直达该书二级。
2. **下载进度 ≠ 全书覆盖率**。一级卡内「正在下载 第 N 章 · 章名」与「队列剩余 M 章」表达本次下载进度；「已缓存 x/y 章」进度条表达全书可离线覆盖率（随阅读/下载单调增长），口径分开展示，不混成一条假装在报下载进度。
3. **`download_chapter` 只存未完成的任务（下好即删），「已下好哪些章节」以缓存文件存在性判断**，二级逐章状态由 章目录 + 缓存存在性 + 队列任务 三方合并（优先级：下载中 > 待下载 > 已缓存 > 未下载）。
4. **确认后留在二级**：任务入库并拉起前台服务后页面不关闭，状态标签实时刷新，可继续加单。
5. **确认时剔除已在队列中的章节**（同一章不重复入队，与队列唯一索引语义一致），跳过计数在按钮上方提示；「仅未缓存」快捷选择同样排除已排队章。
6. **统一下发入口**：先入库、再拉前台服务的链路收敛为仓库层单一入口，阅读器与下载中心共用（服务启动被拒时任务已在库中不丢）。

## 权衡

- **保持两个独立页面（被拒）**：管理页与选章页各管各的数据、互相跳转，两份接口与状态流都要维护，「看不见下了哪些章」也未解决。
- **二级状态参与“是否已下载”决策（被拒）**：缓存文件存在不等于内容正确（缓存失败也可能落盘），状态只作展示，勾中已缓存章仍按强制刷新重下。
- **确认后跳转下载管理列表（被拒）**：用户常一次选一路下，留在二级可以就地续选；去向一目了然，无需额外跳转。

## 下游影响

- `DownloadManageActivity` 承担两级编排（intent 常量 + 页面步骤状态 + BackHandler），`DownloadManageViewModel` 新增步骤与选中数据装载。
- 阅读器旧下载链路（半屏面板、预勾选快照、构造任务、通知权限）下线，入口改为先确保在架再带参打开下载中心。
- `DownloadRepository` 新增统一下发入口与二级数据源；选章列表的分组/三态/软上限纯逻辑不动，继续由 `BookChapterSelectPage` 承接。
- 下载行状态标签为纯信息；抽屉目录的「顺序/锁定」两态交互见另有记录。
```

- [ ] **Step 3: ADR-0018 同步下发入口引用**

`docs/adr/0018-download-foreground-service-data-sync-quota.md` 决策 4 中「`BookReadViewModel.startDownload(chapters)` 先 `downloadRepository.addTasks()` 再 `DownloadService.start()`」的符号随迁移失效——把该句改为「`DownloadRepository.startDownload(chapters)` 先入库再拉服务」，其余（先入库再拉服务的理由、addTasks 去重幂等）不变。

---

## Task 8: 全量验证与人工验证交接

**Files:** 无（只跑命令、只写交接说明）

- [ ] **Step 1: 跑单测与编译**

Run: `.\gradlew :module_book:testDebugUnitTest :module_book:assembleDebug`
Expected: BUILD SUCCESSFUL；`ChapterSelectionTest`（既有 11 例）与 `ChapterDownloadStatusTest`（6 例）全绿；输出无新 warning。

- [ ] **Step 2: 确认没有夹带改动**

Run: `git status --short` 与 `git log --oneline -10`
Expected: 工作区干净；8 笔提交（Task 1-7 各一笔 + 上一篇设计文档提交），无 `local.properties`、无构建产物。

- [ ] **Step 3: 把人工验证清单写进交付说明（Agent 未做，原样列出并标注「未验证」）**

1. **抽屉锚点**：锁定态打开抽屉定位当前章；阅读中切倒序视口顶部仍是正在读的那章；浏览到远处（如第 5000 章处）切顺序仍停在原章；切顺序后再次开关抽屉不再被拽回当前章。
2. **瞄准镜**：点一下锁定（打开定位当前章）、再点取消（保持位置）；与正序/倒序互不干扰。
3. **倒序首次进入**：锁定关闭后首次切倒序停在顶部（最新章）。
4. **下载中心一级**：运行中显示「正在下载 第 N 章 · 章名」+ 队列剩余 + 全书已缓存 x/y，口径分明；点书卡进二级。
5. **二级状态**：已缓存徽章 / 待下载 / 下载中字样正确；分组、三态、快捷 chips、展开吸附不串字（v1 项回归）；确认后留在二级且新任务实时出现；勾选已在队中的章被跳过且有文案提示；超 500 章二次确认正常。
6. **阅读器入口**：下载按钮先加架、直达该书二级；返回回一级；拒绝通知权限不影响下载。
7. **书架入口**：书架下载管理入口仍进一级列表，未受影响。
8. **双模式回归**：集成态（`assembleRealDebug`/`assembleMockDebug`）与独立态（`isModule=true`，调完改回 `false`）阅读器与下载中心全链路无异常。

- [ ] **Step 4: 提交（若上述步骤暴露了需要修的问题）**

修完问题后按 Task 归属各提一笔，不要把多个 Task 的修复攒成「杂项」提交。