# M1a 本地书内容基座与导入流水线 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把本地 TXT 书籍的正文从 SQLite 迁到应用私有目录的"一章一文件"，让导入耗时与文件大小解耦，并建立 `SourceReader` 这一格式无关的内容接缝。

**Architecture:** 三层解耦——纯函数层（文本规范化、编码探测、章节切分、作品键派生，全部 JVM 可单测）、存储层（`BookStore` 管目录与章文件、`BookGroupDao` 管评论键关联行）、契约层（`SourceReader`，`TxtSourceReader` 为首个实现）。导入流水线为"拷贝即哈希 → 后台切分落盘 → 一次事务批量写索引"，前台只付拷贝的代价。

**Tech Stack:** Kotlin 2.4.10、AGP 9 内置 Kotlin、Room 3（`androidx.room3` + `BundledSQLiteDriver`）、Coroutines/Flow、Hilt、juniversalchardet、JUnit4 + `kotlinx-coroutines-test`。

**依据 spec:** `docs/superpowers/specs/2026-09-04-local-book-import-design.md`（r5）。**本计划只覆盖 §10 的 M1a**，不含 M1b（网络书出 DB、`BookParser` 拆分）与 M2/M3。

**验证分工（AGENTS.md 硬规定）：** Agent 只做编译、静态检查与 JVM 单测；凡 `androidTest`、装机、UI 路径确认一律归人工。每个含人工步骤的任务都写明"打开哪个页面、走哪条路径、看什么现象"，Agent 完成时也必须交代未验证项。

**约定：** 所有命令在仓库根目录执行；Windows 下把 `./gradlew` 换成 `.\gradlew`。

---

## 文件结构

**新建 — `lib_book_common`（全部纯 JVM，可脱离 Robolectric 单测）**

| 路径 | 职责 |
|---|---|
| `src/main/java/com/ebook/common/text/TextNormalizer.kt` | 唯一的文本规范化入口：BOM、换行、行内空白、段落缩进 |
| `src/main/java/com/ebook/common/text/EncodingProbe.kt` | 从字节头部探测字符集 |
| `src/main/java/com/ebook/common/text/StrictTextReader.kt` | 严格解码 Reader 工厂（不可映射字节必抛，绝不静默替换） |
| `src/main/java/com/ebook/common/domain/FileNameMetadata.kt` | 从文件名解析书名与作者 |
| `src/main/java/com/ebook/common/domain/CommentKey.kt` | 作品身份 `comment_key` 的派生与归一化 |
| `src/main/java/com/ebook/common/analyze/local/Contracts.kt` | `BookFormat` / `BookLocation` / `BookSourceFile` / `ChapterEntry` / `ChapterContent` / `LocalBookMeta` / `ChapterSink` / `SourceReader` |
| `src/main/java/com/ebook/common/analyze/local/ChapterSplitter.kt` | 字符流 → 章节流（含标题规则） |
| `src/main/java/com/ebook/common/analyze/local/TxtSourceReader.kt` | TXT 的 `SourceReader` 实现 |
| `src/main/java/com/ebook/common/store/BookStore.kt` | 私有目录仓库：目录布局、章文件读写、`.tmp` 提交点、孤儿对账 |
| `src/main/java/com/ebook/common/store/ChapterContentCache.kt` | 章节正文内存缓存（LRU + 显式失效） |
| `src/main/java/com/ebook/common/di/ContentStoreModule.kt` | 把 `BookStore` 绑到 `filesDir/books` |

**新建 — `lib_ebook_db`**：`entity/BookGroupEntity.kt`、`dao/BookGroupDao.kt`

**新建 — `module_book`**：`importer/LocalBookImporter.kt`（取代 `util/BookImportManager.kt`）

**修改**

- `lib_ebook_db`：`AppDatabase.kt`（version 3 + 新表）、`di/DatabaseModule.kt`（`MIGRATION_2_3`、新 DAO provider）、`entity/BookShelfEntity.kt`（4 新列）、`entity/ChapterListEntity.kt`（`durChapterUrl` → `contentRef`）、`dao/ChapterListDao.kt`（列名同步 + 批量插入正文表接口）
- `lib_book_common`：`repository/BookRepository.kt`（内容读路径按 tag 分叉、新增 `publishAdded`）
- `module_book`：`mvvm/viewmodel/BookImportViewModel.kt`、`ImportBookActivity.kt`、`repository/BookImportRepository.kt`、`ReadBookActivity.kt`、`mvvm/viewmodel/BookReadViewModel.kt`、`service/DownloadService.kt`、`repository/DownloadRepository.kt`、`mvvm/viewmodel/DownloadManageViewModel.kt`、`reader/ReaderPanels.kt`
- `lib_book_common`：`analyze/source/JsoupBookParser.kt`（字段改名）
- 文档：`AGENTS.md`、`CONTEXT.md`、`docs/test-coverage-todo.md`、`docs/superpowers/specs/2026-09-04-local-book-import-design.md`（回填实测数字）

`durChapterUrl` / `dur_chapter_url` 全仓引用共 75 处、17 个文件（`grep -rn "durChapterUrl\|dur_chapter_url" --include=*.kt .` 可复现），上表修改清单已覆盖全部命中文件。注意 `DownloadChapterEntity.durChapterUrl` 与 `BookContentEntity.durChapterUrl` 是**另两张表**的同名字段，M1a **不改名**（它们仍指网络书章节 URL，收口在 M1b）。

---

## Task 1: 建立改前性能基线

没有基线数字就无法证明"变快了"而不是"换了个慢法"。spec §6 明确承认性能只有量级推演，本任务把它变成实测，且必须在改任何代码之前跑。

**Files:**
- Create: `module_book/src/androidTest/java/com/ebook/book/ImportBaselineTest.kt`
- Create: `module_book/src/androidTest/java/com/ebook/book/DebugMemory.kt`

- [ ] **Step 1: 写基线测试**

`module_book/src/androidTest/java/com/ebook/book/ImportBaselineTest.kt`：

```kotlin
package com.ebook.book

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ebook.book.util.BookImportManager
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.dao.BookContentDao
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * 本地导入性能基线（spec §6）。**必须在改 BookImportManager 之前跑一次**，数字回填 spec；
 * M1a 收尾时用同一份夹具再跑一次作对比。
 *
 * 夹具为 2000 章、约 6MB 的 UTF-8 TXT，走真实导入链路（含真实 Room 写库与真实文件读）。
 *
 * 计时区必须覆盖 `importBook` **加上调用方的 `addToShelf`**：三个热点里的第三个——"导入后
 * 又把章节表全量 REPLACE 一遍"——不在 `importBook` 内部，而在两个调用方
 * （`BookImportViewModel.kt:91`、`ReadBookActivity.kt:181`）。只计 `importBook` 会让基线少算
 * 一份正被移除的成本，于是改后对比低估收益。改后侧对应跑 `LocalBookImporter.import(file)`
 * （它在自己事务里写完三张表、只 `publishAdded` 发事件），两边都覆盖"按下导入到可读"的
 * 完整路径，才是 apples-to-apples。
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ImportBaselineTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var bookImportManager: BookImportManager
    @Inject lateinit var bookShelfDao: BookShelfDao
    @Inject lateinit var chapterListDao: ChapterListDao
    @Inject lateinit var bookInfoDao: BookInfoDao
    @Inject lateinit var bookContentDao: BookContentDao

    @Before fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun import_2000ChapterTxt_asBaseline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixture = File(context.cacheDir, "baseline_book.txt")
        buildFixture(fixture, chapters = 2000, charsPerChapter = 3000)

        val startNs = System.nanoTime()
        val memBeforeKb = DebugMemory.snapshotKb()
        val result = bookImportManager.importBook(context, Uri.fromFile(fixture))
        // 复现真实调用链：生产路径在 new==true 时紧接着调 addToShelf，把章节表全量重写一遍
        if (result.new) bookRepository.addToShelf(result.bookShelf)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val memAfterKb = DebugMemory.snapshotKb()

        val noteUrl = result.bookShelf.noteUrl
        val chapters = chapterListDao.getChaptersForBook(noteUrl)
        println(
            "BASELINE elapsed=${elapsedMs}ms chapters=${chapters.size} " +
                "fileKb=${fixture.length() / 1024} memDeltaKb=${memAfterKb - memBeforeKb}"
        )

        assertTrue("应切出 2000 章，实际 ${chapters.size}", chapters.size == 2000)

        // 清理：本地书的正文此刻仍在 book_content，按章 URL 逐条清掉
        bookContentDao.deleteByChapterUrls(chapters.map { it.durChapterUrl })
        chapterListDao.deleteChaptersForBook(noteUrl)
        bookInfoDao.deleteByUrl(noteUrl)
        bookShelfDao.deleteByUrl(noteUrl)
        fixture.delete()
    }

    private fun buildFixture(target: File, chapters: Int, charsPerChapter: Int) {
        val paragraph = "这是用于基线测量的正文内容，重复若干次以凑出目标章长。"
        target.bufferedWriter().use { writer ->
            for (i in 1..chapters) {
                writer.write("第$i章 基线测试章节 $i\n")
                repeat(charsPerChapter / paragraph.length) {
                    writer.write(paragraph)
                    writer.write("\n")
                }
            }
        }
    }
}
```

- [ ] **Step 2: 写内存快照辅助类**

`module_book/src/androidTest/java/com/ebook/book/DebugMemory.kt`：

```kotlin
package com.ebook.book

import android.os.Debug

/** 基线计时用的堆快照，单位 KB（native heap，含 SQLite 页缓存） */
object DebugMemory {
    fun snapshotKb(): Long = Debug.getNativeHeapAllocatedSize() / 1024
}
```

- [ ] **Step 3: 确认能编译（Agent 的执行边界）**

Run: `./gradlew :module_book:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 人工跑一次并记录**（人工，Agent 不得代替执行）

连上设备/模拟器后：
`./gradlew :module_book:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ebook.book.ImportBaselineTest`
Expected: 控制台输出 `BASELINE elapsed=...ms chapters=2000 fileKb=... memDeltaKb=...`

- [ ] **Step 5: 把数字写进 spec §6**

在 `docs/superpowers/specs/2026-09-04-local-book-import-design.md` §6 的"性能数字目前只有量级推演"条目末尾追加：
`改前实测（2000 章 / 6MB / <机型>）：elapsed=___ms，memDelta=___KB —— <日期>`

- [ ] **Step 6: 提交**

```bash
git add module_book/src/androidTest/java/com/ebook/book/ImportBaselineTest.kt \
        module_book/src/androidTest/java/com/ebook/book/DebugMemory.kt \
        docs/superpowers/specs/2026-09-04-local-book-import-design.md
git commit -m "test(module_book): 建立本地书籍导入改前性能基线"
```

---

## Task 2: TextNormalizer（唯一的规范化入口）

spec §8 的全部规则收进一个纯函数对象。这是"规范化不再毁内容"的落点。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/text/TextNormalizer.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/text/TextNormalizerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TextNormalizer] 单测。锁两件事：
 * 1) 行内空白折叠为一个空格而**不是删光**——旧实现 `.replace(" ", "")` 配合
 *    `.replace("\\s*".toRegex(), "")`（BookImportManager.kt:159-161）会永久销毁
 *    `1 000`、英文书名、代码里的空格；
 * 2) 段落缩进只出现在 toDisplayText，不出现在段落数据里——这是"存储层不清洗"的前提，
 *    也让段评锚点（spec §9.1）不被表现层字符污染。
 */
class TextNormalizerTest {

    @Test
    fun cleanParagraphKeepsSingleSpaceBetweenWords() {
        assertEquals("Sherlock 1 000", TextNormalizer.cleanParagraph("Sherlock   1 000"))
    }

    @Test
    fun cleanParagraphStripsLeadingIndentAndTrailingSpace() {
        assertEquals("正文开头", TextNormalizer.cleanParagraph("　　正文开头   "))
    }

    @Test
    fun cleanParagraphStripsBom() {
        assertEquals("第一段", TextNormalizer.cleanParagraph("第一段"))
    }

    @Test
    fun unifyNewlinesNormalisesCrlfAndCr() {
        assertEquals(listOf("a", "b", "c"), TextNormalizer.unifyNewlines("a\r\nb\rc").split("\n"))
    }

    @Test
    fun cleanParagraphsDropsBlankLines() {
        assertEquals(listOf("甲", "乙"), TextNormalizer.cleanParagraphs(listOf("甲", "   ", "乙")))
    }

    @Test
    fun toDisplayTextIndentsEveryParagraph() {
        assertEquals("　　甲\n　　乙", TextNormalizer.toDisplayText(listOf("甲", "乙")))
    }

    @Test
    fun cleanParagraphAbsorbsLegacyFullWidthIndent() {
        // 历史章文件若已带缩进，读取时先吸收再统一补，避免出现四个全角空格
        assertEquals("老数据", TextNormalizer.cleanParagraph("　　　　老数据"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.text.TextNormalizerTest"`
Expected: 编译失败，`Unresolved reference: TextNormalizer`

- [ ] **Step 3: 实现**

```kotlin
package com.ebook.common.text

/**
 * 文本规范化的唯一入口（spec §8）。
 *
 * 设计约束是**存储层不清洗**：章文件存"切分后、规范化前"的原文，本对象只在读取时把
 * 原文转成段落数据与展示文本。于是"发现规则定错了"永远是改一行读取代码，而不是
 * "数据已不可逆损毁"。旧实现把 `.replace(" ", "")` 与全角缩进都写进正文，属后者。
 *
 * 段落缩进只出现在 [toDisplayText]、绝不出现在 [cleanParagraph] 的结果里：段评锚点建立在
 * 段落数据之上，掺进表现层字符会让锚点随渲染规则漂移。
 *
 * [cleanParagraph] 会先吸收行首已有的全角/半角空白再统一补缩进，因此旧数据（正文里已带
 * `　　`）与新数据（不带）渲染结果一致，不会出现四个全角空格。
 */
object TextNormalizer {

    /** 段首缩进：两个全角空格，与阅读器分页与渲染的既有假设一致 */
    const val INDENT: String = "　　"

    private const val BOM: Char = '﻿'

    /**
     * 统一换行为 LF。
     *
     * `ReaderTypesetter.lineStartOffsets` 依赖 `\n` 作段落分隔（其 KDoc 记录了 CRLF 下
     * Compose 吞换行导致段落并团的缺陷）；规范化后章内不再出现 `\r`。
     */
    fun unifyNewlines(text: String): String =
        if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text

    /** 单行清洗：剥 BOM、行内连续空白折叠为单个半角空格、去首尾空白 */
    fun cleanParagraph(rawLine: String): String {
        val line = if (rawLine.startsWith(BOM)) rawLine.substring(1) else rawLine
        if (line.isEmpty()) return line
        return buildString(line.length) {
            var pendingSpace = false
            for (ch in line) {
                if (ch.isWhitespace()) {
                    // 只在已产出内容后才记空白，行尾空白因此在末尾被整体丢弃
                    if (isNotEmpty()) pendingSpace = true
                    continue
                }
                if (pendingSpace) {
                    append(' ')
                    pendingSpace = false
                }
                append(ch)
            }
        }
    }

    /** 整行集合清洗并丢弃空行（空行只是段落分隔信号，不产出空段落） */
    fun cleanParagraphs(rawLines: List<String>): List<String> =
        rawLines.map { cleanParagraph(it) }.filter { it.isNotEmpty() }

    /** 段落数据 → 展示文本；分页测量与渲染共用同一份（见 ReaderTypesetter 的同源契约） */
    fun toDisplayText(paragraphs: List<String>): String =
        paragraphs.joinToString("\n") { INDENT + it }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.text.TextNormalizerTest"`
Expected: `BUILD SUCCESSFUL`，7 个测试通过

- [ ] **Step 5: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/text/TextNormalizer.kt \
        lib_book_common/src/test/java/com/ebook/common/text/TextNormalizerTest.kt
git commit -m "feat(lib_book_common): 新增文本规范化入口并保留行内空格"
```

---

## Task 3: EncodingProbe 与 StrictTextReader

spec §4 新增的硬约束：章文件统一重编码为 UTF-8，且**解码必须严格**。默认解码策略会把不可映射字节静默换成 `U+FFFD`——不报错、不闪退、书里悄悄冒出问号，正是本次要根除的那类不可逆损毁。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/text/EncodingProbe.kt`
- Create: `lib_book_common/src/main/java/com/ebook/common/text/StrictTextReader.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/text/EncodingTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * 编码探测与严格解码的测试（spec §4 §7）。
 *
 * 探测结果不断言具体编码名：juniversalchardet 对 GBK 语料可能回 `GBK` 也可能回
 * `GB18030`，写死名字会得到"实现没错、测试脆弱"的用例。真正要锁的是
 * **探测出的编码能把那批字节解回期望文本**，以及它确实不是 UTF-8。
 */
class EncodingTest {

    private val sample = "第一章 风起云涌的年代，正文需要足够长才能被探测算法判定出编码特征。"

    @Test
    fun utf8CorpusDecodesBackToOriginalText() {
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val detected = EncodingProbe.detect(bytes, bytes.size)
        assertEquals(sample, String(bytes, charsetOf(detected)))
    }

    @Test
    fun gbkCorpusIsNotDetectedAsUtf8AndDecodesBack() {
        val gbk = charsetOf("GBK")
        val bytes = sample.toByteArray(gbk)
        val detected = EncodingProbe.detect(bytes, bytes.size)
        assertNotEquals("GBK 语料不该探测成 UTF-8，实际=$detected", "UTF-8", detected)
        assertEquals(sample, String(bytes, charsetOf(detected)))
    }

    @Test
    fun emptyInputFallsBackToUtf8() {
        assertEquals(EncodingProbe.FALLBACK, EncodingProbe.detect(ByteArray(0), 0))
    }

    @Test
    fun strictReaderThrowsOnUndecodableBytes() {
        // 0xFF 0xFE 不是合法的 UTF-8 起始字节
        val file = tempFile("strict-bad", byteArrayOf(0x31, 0x32, 0x33, 0xFF.toByte(), 0xFE.toByte()))
        var thrown: Throwable? = null
        val text = try { StrictTextReader.readAll(file, "UTF-8") } catch (e: IOException) { thrown = e; null }
        assertNotNull("必须抛异常而不是静默替换出内容", thrown)
        assertTrue("结果里不得出现 U+FFFD", text?.contains('\uFFFD') != true)
    }

    @Test
    fun strictReaderStripsBom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = tempFile("strict-bom", bom + "正文".toByteArray(Charsets.UTF_8))
        assertEquals("正文", StrictTextReader.readAll(file, "UTF-8"))
    }

    @Test
    fun unknownCharsetNameBecomesIoException() {
        val file = tempFile("strict-charset", "abc".toByteArray(Charsets.UTF_8))
        try {
            StrictTextReader.readAll(file, "NOT-A-REAL-CHARSET")
            fail("未知编码名必须报错")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("NOT-A-REAL-CHARSET"))
        }
    }

    private fun tempFile(prefix: String, bytes: ByteArray): File =
        File.createTempFile(prefix, ".txt").apply { deleteOnExit(); writeBytes(bytes) }

    private fun charsetOf(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.text.EncodingTest"`
Expected: 编译失败，`Unresolved reference: EncodingProbe`

- [ ] **Step 3: 实现 EncodingProbe**

```kotlin
package com.ebook.common.text

import org.mozilla.universalchardet.UniversalDetector

/**
 * 从字节头部探测字符集（spec §7）。
 *
 * 探测只需文件头若干字节；旧实现对整本文件再读一遍专门探编码，是三遍全文件读之一。
 * 探测结果由调用方存进 `book_shelf.text_charset`，**一本书只探一次**，此后重读不再重探
 * ——这也让"用户手工指定编码"在 M2 之后有唯一的落点。
 */
object EncodingProbe {

    /** 探测用的头部长度；与 juniversalchardet 的常规用法一致 */
    const val HEAD_BYTES: Int = 512 * 1024

    /** 探测不出时的回落编码 */
    const val FALLBACK: String = "UTF-8"

    /**
     * @param head 文件头字节，长度可超过 [HEAD_BYTES]，只取前 [length] 个
     * @param length [head] 中的有效字节数
     * @return 可直接交给 `Charset.forName` 的编码名，永不返回 null
     */
    fun detect(head: ByteArray, length: Int): String {
        val safeLength = minOf(length, head.size)
        if (safeLength <= 0) return FALLBACK
        val detector = UniversalDetector(null)
        var offset = 0
        while (offset < safeLength && !detector.isDone) {
            val chunk = minOf(4096, safeLength - offset)
            detector.handleData(head, offset, chunk)
            offset += chunk
        }
        detector.dataEnd()
        return detector.detectedCharset ?: FALLBACK
    }
}
```

- [ ] **Step 4: 实现 StrictTextReader**

```kotlin
package com.ebook.common.text

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 严格解码的 Reader 工厂（spec §4）。
 *
 * 为什么不用 `InputStreamReader(stream, charset)` 或 `String(bytes, charset)`：两者的错误
 * 策略由实现决定、常见路径是**静默替换**，会把解不动的字节变成 U+FFFD 后继续跑完——
 * 症状是"导入成功、书里几个问号"，且永远不会报错。这里自建 `CharsetDecoder` 并把
 * 两侧策略都设为 `REPORT`：宁可导入失败，也不产出损毁的章文件。
 */
object StrictTextReader {

    private const val BUFFER_CHARS = 8 * 1024

    /** 打开严格解码的 BufferedReader；调用方负责关闭（配合 `use`） */
    fun open(file: File, charsetName: String): BufferedReader = BufferedReader(
        Channels.newReader(Channels.newChannel(file.inputStream()), decoderFor(charsetName)),
        BUFFER_CHARS
    )

    /** 逐行提供解码结果，供切分器流式消费（整本文件不进内存） */
    fun lines(file: File, charsetName: String): Sequence<String> = open(file, charsetName).lineSequence()

    /** 整体读入并剥 BOM；仅用于测试与小文件 */
    fun readAll(file: File, charsetName: String): String {
        val text = open(file, charsetName).use { it.readText() }
        return if (text.startsWith('﻿')) text.substring(1) else text
    }

    private fun decoderFor(charsetName: String): java.nio.charset.CharsetDecoder {
        val charset = runCatching { Charset.forName(charsetName) }
            .getOrElse { throw IOException("不支持的字符集：$charsetName") }
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.text.EncodingTest"`
Expected: `BUILD SUCCESSFUL`，6 个测试通过

若 `strictReaderThrowsOnUndecodableBytes` 没抛异常，说明 `Channels.newReader` 未按 decoder 的 REPORT 策略执行——**不要放宽断言**，改用显式解码循环：

```kotlin
    fun readAll(file: File, charsetName: String): String {
        val bytes = file.readBytes()
        val headerless = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            bytes.copyOfRange(3, bytes.size) else bytes
        val cb = java.nio.charset.Charset.forName(charsetName).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(headerless))
        return cb.toString()
    }
```
（`MalformedInputException` 是 `IOException` 子类，无需额外包装。）

- [ ] **Step 6: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/text/EncodingProbe.kt \
        lib_book_common/src/main/java/com/ebook/common/text/StrictTextReader.kt \
        lib_book_common/src/test/java/com/ebook/common/text/EncodingTest.kt
git commit -m "feat(lib_book_common): 新增编码探测与严格解码读取"
```

---

## Task 4: FileNameMetadata（从文件名解析书名与作者）

spec §6：作者恒为占位会让 `comment_key` **既误并又分裂**，必须在开始积累评论数据之前解析。旧实现是 `BookImportManager.kt:81` 文件名去扩展名当书名、`:87` 作者硬写占位。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/domain/FileNameMetadata.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/domain/FileNameMetadataTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FileNameMetadata] 单测。模式集合覆盖本地书文件名的四种真实写法与两种落空情况。
 *
 * 解析不出作者是常态而非异常：返回 null，由显示层填占位词；占位词绝不参与键计算
 * （见 CommentKeyTest 的对应断言）。
 */
class FileNameMetadataTest {

    @Test
    fun parsesTitleInBookMarksWithAuthorPrefix() {
        val r = FileNameMetadata.parse("网络小说《星辰变》作者：我吃西红柿")
        assertEquals("星辰变", r.title)
        assertEquals("我吃西红柿", r.author)
    }

    @Test
    fun parsesBareTitleWithAuthorPrefix() {
        val r = FileNameMetadata.parse("斗破苍穹 作者：天蚕土豆")
        assertEquals("斗破苍穹", r.title)
        assertEquals("天蚕土豆", r.author)
    }

    @Test
    fun parsesEnglishByForm() {
        val r = FileNameMetadata.parse("The Hobbit by Tolkien")
        assertEquals("The Hobbit", r.title)
        assertEquals("Tolkien", r.author)
    }

    @Test
    fun parsesBookMarksOnly() {
        val r = FileNameMetadata.parse("《凡人修仙传》")
        assertEquals("凡人修仙传", r.title)
        assertNull(r.author)
    }

    @Test
    fun fallsBackToWholeNameWhenNothingMatches() {
        val r = FileNameMetadata.parse("星辰变 全文 无删减")
        assertEquals("星辰变 全文 无删减", r.title)
        assertNull(r.author)
    }

    @Test
    fun stripsExtensionCaseInsensitively() {
        assertEquals("书名", FileNameMetadata.parse("书名.TXT").title)
        assertEquals("书名", FileNameMetadata.parse("书名.txt").title)
    }

    @Test
    fun stripsTrailingBracketedNoise() {
        assertEquals("剑来", FileNameMetadata.parse("剑来 (起点小说 2024-01-01)").title)
        assertEquals("剑来", FileNameMetadata.parse("剑来【完结】").title)
    }

    @Test
    fun authorWithHalfwidthColonIsParsed() {
        val r = FileNameMetadata.parse("赘婿 作者: 愤怒的香蕉")
        assertEquals("赘婿", r.title)
        assertEquals("愤怒的香蕉", r.author)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.domain.FileNameMetadataTest"`
Expected: 编译失败，`Unresolved reference: FileNameMetadata`

- [ ] **Step 3: 实现**

```kotlin
package com.ebook.common.domain

/**
 * 从本地文件名解析书名与作者（spec §6）。
 *
 * 存在的理由是**作品身份**：`comment_key = hash(书名 ‖ 作者)`（spec §9.1）。旧实现直接拿
 * 文件名去扩展名当书名、作者写死占位，后果是两本同名不同作者的书算出同一个键（误并），
 * 同一本书的两种文件名写法算出两个键（分裂）。这两类错误一旦发生就开始积累评论数据。
 *
 * 解析不出作者返回 null，由显示层决定占位词；占位词与键计算无关（见 [CommentKey]）。
 */
object FileNameMetadata {

    /** @param author null 表示文件名里没有可识别的作者信息 */
    data class Parsed(val title: String, val author: String?)

    private val extensions = listOf(".txt", ".epub")

    /** 解析规则：titleGroup 是书名所在捕获组，authorGroup 为 null 表示该模式不含作者 */
    private class Rule(val regex: Regex, val titleGroup: Int, val authorGroup: Int?)

    /** 按优先级排列，命中即止；第一条模式的前缀杂项用非捕获组，书名仍是组 1 */
    private val rules = listOf(
        Rule(Regex("""^(?:.*?)《(.+?)》.*?作者\s*[：:]\s*(.+)$"""), 1, 2),
        Rule(Regex("""^(.+?)\s+作者\s*[：:]\s*(.+)$"""), 1, 2),
        Rule(Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE), 1, 2),
        Rule(Regex("""^《(.+?)》\s*$"""), 1, null),
    )

    /** 文件名里常见的站点/版本尾巴，成对括号包裹，反复剥直到不再变化 */
    private val trailingNoise = listOf(
        Regex("""\s*[(（][^)）]*[)）]\s*$"""),
        Regex("""\s*[【\[][^】\]]*[】\]]\s*$"""),
    )

    /** 文件名可能极长（整段简介塞进文件名），书名截断上限 */
    private const val MAX_TITLE_CHARS = 120

    fun parse(rawName: String): Parsed {
        val base = stripNoise(rawName)
        for (rule in rules) {
            val match = rule.regex.find(base) ?: continue
            val title = match.groupValues[rule.titleGroup].trim().limitTitle()
            if (title.isEmpty()) continue
            val author = rule.authorGroup
                ?.let { match.groupValues[it].trim() }
                ?.takeIf { it.isNotEmpty() }
            return Parsed(title, author)
        }
        return Parsed(base.limitTitle(), null)
    }

    private fun stripNoise(name: String): String {
        var result = name.trim()
        extensions.firstOrNull { result.endsWith(it, ignoreCase = true) }?.let { ext ->
            result = result.substring(0, result.length - ext.length).trim()
        }
        var changed = true
        while (changed) {
            changed = false
            for (noise in trailingNoise) {
                val stripped = noise.replace(result, "").trim()
                if (stripped != result) {
                    result = stripped
                    changed = true
                }
            }
        }
        return result
    }

    private fun String.limitTitle(): String =
        if (length <= MAX_TITLE_CHARS) this else take(MAX_TITLE_CHARS).trim()
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.domain.FileNameMetadataTest"`
Expected: `BUILD SUCCESSFUL`，9 个断言全部通过。若 `parsesBareTitleWithAuthorPrefix` 失败，是模式 2 的 `作者\s*[：:]` 未吃掉空格——按实际失败输出修 `patterns[1]` 的正则，**不要改断言**。

- [ ] **Step 5: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/domain/FileNameMetadata.kt \
        lib_book_common/src/test/java/com/ebook/common/domain/FileNameMetadataTest.kt
git commit -m "feat(lib_book_common): 新增本地书文件名的书名作者解析"
```

---

## Task 5: CommentKey（作品身份派生）

spec §9.1 的算法。M1a 只**写入**关联行、不消费（消费在 M2），但归一化规则从第一行数据起就生效，所以必须现在定死并测死。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/domain/CommentKey.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/domain/CommentKeyTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CommentKey] 单测（spec §9.1、§9.5 约束 1）。
 *
 * 要锁住四件事：同书不同来源算出同键、同名不同作者算出不同键、作者占位词不参与键计算、
 * 结果带算法版本前缀。评论是**用户产生的不可再生**数据，归一化一改就是换键空间，这些断言
 * 是防回归的最后一道闸。
 */
class CommentKeyTest {

    @Test
    fun keyCarriesAlgorithmVersion() {
        assertTrue(CommentKey.compute("星辰变", "我吃西红柿").startsWith("ck1:"))
    }

    @Test
    fun bookMarksAndRedundantWhitespaceDoNotChangeKey() {
        assertEquals(
            CommentKey.compute("星辰变", "我吃西红柿"),
            CommentKey.compute("《 星辰变 》", "我吃西红柿")
        )
    }

    @Test
    fun fullWidthFormsFoldToHalfWidthBeforeHashing() {
        assertEquals(
            CommentKey.compute("agent 007", "x"),
            CommentKey.compute("ａｇｅｎｔ　００７", "Ｘ")
        )
    }

    @Test
    fun sameTitleDifferentAuthorMustNotCollide() {
        assertNotEquals(
            CommentKey.compute("星辰变", "我吃西红柿"),
            CommentKey.compute("星辰变", "另一个人")
        )
    }

    @Test
    fun authorPlaceholdersAreTreatedAsAbsent() {
        val empty = CommentKey.compute("剑来", null)
        listOf("佚名", "侠名", "未知", "不详", "N/A", "unknown", "  ").forEach { placeholder ->
            assertEquals("占位词「$placeholder」不该改变键", empty, CommentKey.compute("剑来", placeholder))
        }
    }

    @Test
    fun absentAndEmptyAuthorYieldSameKey() {
        assertEquals(CommentKey.compute("剑来", null), CommentKey.compute("剑来", ""))
    }

    @Test
    fun titleAndAuthorBoundariesCannotBeShifted() {
        // 「AB」+「C」与「A」+「BC」必须不同键，靠分隔符保证
        assertNotEquals(CommentKey.compute("AB", "C"), CommentKey.compute("A", "BC"))
    }

    @Test
    fun keyIsFixedLengthLowercaseHexAfterPrefix() {
        val key = CommentKey.compute("某书", "某作者")
        assertEquals(4 + 64, key.length)
        assertTrue(key.substringAfter(':').all { it in "0123456789abcdef" })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.domain.CommentKeyTest"`
Expected: 编译失败，`Unresolved reference: CommentKey`

- [ ] **Step 3: 实现**

```kotlin
package com.ebook.common.domain

import java.security.MessageDigest

/**
 * 作品身份 `comment_key` 的派生（spec §9.1）。
 *
 * 为什么是**客户端派生的不透明 token**而不是后端建一张书籍表：后端不得存书籍数据（不存书名、
 * 不建书籍表、不提供"列出所有书"），但评论需要一个跨用户一致的桶键。派生函数让所有客户端
 * **无协调地**算出同一个值——正因为没有人分配，它才能处处一致。sha256 不可逆，后端拿不到书名，
 * 这比现状（明文存 book_name/chapter_name，书籍级评论还靠一个无索引文本列聚合）更合规。
 *
 * 版本前缀 ck1: 是**算法版本**：归一化规则一旦改动（例如将来加繁简转换），不同版本客户端会
 * 静默算出不同键、两堆评论互不可见；而评论不可再生，不能像索引那样"删了重来"。
 * 所以改归一化必须同时升这个前缀。
 *
 * 作者占位词（佚名/侠名 等）归一为空串后才参与哈希，因此显示层用哪个词都不会换掉评论桶
 * ——这条与 spec §8「默认作者显示词改为侠名」配套。
 */
object CommentKey {

    const val ALGORITHM_VERSION: String = "ck1"

    /**
     * 作者字段里出现这些值视同"不知道作者"。比对时两边都先过 [normalize]，
     * 所以这里只写小写、无空白的形态。
     */
    private val authorPlaceholders = setOf(
        "佚名", "侠名", "未知", "不详", "未署名", "作者未知", "n/a", "na", "none", "unknown", "author"
    )

    /** 书名里成对出现的装饰符号，对识别作品无意义 */
    private val titleNoise = charArrayOf('《', '》', '「', '」', '『', '』', '〈', '〉')

    /** @param title 显示书名或主匹配名；@param author null/空/占位词都按"无作者"处理 */
    fun compute(title: String?, author: String?): String {
        val normalizedTitle = normalize(title.orEmpty())
        val normalizedAuthor = normalize(author.orEmpty())
            .takeUnless { it in authorPlaceholders }
            .orEmpty()
        // 长度前缀自 delimited：避免「AB」+「C」与「A」+「BC」撞键，不依赖任何分隔字符
        val joined = "${normalizedTitle.length}:$normalizedTitle${normalizedAuthor.length}:$normalizedAuthor"
        return "$ALGORITHM_VERSION:${sha256Hex(joined)}"
    }
```

- [ ] **Step 3b: 实现归一化与哈希（接在 `compute` 之后，同一个 object 内）**

```kotlin
    /**
     * 归一化：剥书名号 → 全角转半角 → 转小写 → 折叠所有空白。
     *
     * 刻意**不做**繁简转换：那是会改变键空间的规则升级，做的话必须同时升
     * [ALGORITHM_VERSION]（spec §9.5 约束 1）。
     */
    fun normalize(raw: String): String {
        val folded = buildString(raw.length) {
            for (ch in raw) {
                if (ch in titleNoise) continue
                append(toHalfWidth(ch).lowercaseChar())
            }
        }
        // 折叠所有空白（含全角空格转换来的半角空格）为单个半角空格，再收掉首尾
        return folded.trim().replace(Regex("\\s+"), " ")
    }

    /** 全角 ASCII（U+FF01..U+FF5E）与全角空格（U+3000）折到半角；其余原样返回 */
    private fun toHalfWidth(ch: Char): Char = when {
        ch == '　' -> ' '
        ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
        else -> ch
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.domain.CommentKeyTest"`
Expected: `BUILD SUCCESSFUL`，8 个测试通过。若 `bookMarksAndRedundantWhitespaceDoNotChangeKey` 失败，检查 `normalize` 里 `trim()` 是否在折叠空白**之前**执行（顺序错会留下内部双空格）。

- [ ] **Step 5: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/domain/CommentKey.kt \
        lib_book_common/src/test/java/com/ebook/common/domain/CommentKeyTest.kt
git commit -m "feat(lib_book_common): 新增作品身份 comment_key 派生与归一化"
```

---

## Task 6: 契约类型与 ChapterSplitter

`Contracts.kt` 是 M1b 与 M3 都要依赖的形状，本任务把它定下来；`ChapterSplitter` 是纯函数版的章节切分，替掉 `BookImportManager.kt:128-186` 那个"边扫边写库"的循环。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/analyze/local/Contracts.kt`
- Create: `lib_book_common/src/main/java/com/ebook/common/analyze/local/ChapterSplitter.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/analyze/local/ChapterSplitterTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.analyze.local

import com.ebook.common.text.TextNormalizer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterSplitter] 单测。这是被从"扫描循环里直接 insert 数据库"中解放出来的那段逻辑，
 * 从此可单测——旧实现 `BookImportManager` 零测试覆盖正是因为切分与写库焊在一起。
 */
class ChapterSplitterTest {

    private val splitter = ChapterSplitter()

    private suspend fun split(vararg lines: String): List<ChapterSplitter.RawChapter> =
        splitter.split(lines.asSequence().map { TextNormalizer.cleanParagraph(it) }).toList()

    @Test
    fun splitsChaptersByTitleRule() = runTest {
        val chapters = split(
            "第一章 起", "正文甲", "", "第二章 承", "正文乙", "第三章 转", "正文丙"
        )
        assertEquals(3, chapters.size)
        assertEquals(listOf("第一章 起", "第二章 承", "第三章 转"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        assertEquals(listOf("正文甲", "正文乙", "正文丙"), chapters.map { it.paragraphs.single() })
    }

    @Test
    fun titleMayCarryNumberOnlyForm() = runTest {
        val chapters = split("第12章 风雨欲来", "正文")
        assertEquals("第12章 风雨欲来", chapters.single().title)
    }

    @Test
    fun bookWithoutTitlesBecomesSingleChapterNamedByFirstLine() = runTest {
        val chapters = split("开篇第一段", "第二段", "第三段")
        assertEquals(1, chapters.size)
        assertEquals("开篇第一段", chapters.single().title)
        assertEquals(3, chapters.single().paragraphs.size)
    }

    @Test
    fun emptyChaptersAreNotEmitted() = runTest {
        // 只有标题没有正文的章不该占一个索引位
        val chapters = split("第一章 空", "第二章 有内容", "正文")
        assertEquals(1, chapters.size)
        assertEquals("第二章 有内容", chapters.single().title)
    }

    @Test
    fun blankLinesAreDropped() = runTest {
        val chapters = split("第一章 起", "", "   ", "正文")
        assertEquals(listOf("正文"), chapters.single().paragraphs)
    }

    @Test
    fun leadingTitleStillStartsAtIndexZero() = runTest {
        val chapters = split("第一章 起", "正文")
        assertEquals(0, chapters.single().index)
    }

    @Test
    fun proseMentioningChapterMarkerSplitsChapter() = runTest {
        // 已知取舍：正文里出现"第三章"字样的整行会被当标题。与旧实现行为一致，
        // 这里把它锁成显式契约而不是留作意外
        val chapters = split("第一章 起", "他想起第三章的情节", "正文")
        assertEquals(2, chapters.size)
        assertEquals("第三章的情节", chapters[1].title)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.local.ChapterSplitterTest"`
Expected: 编译失败，`Unresolved reference: ChapterSplitter`

- [ ] **Step 3: 定义契约类型**

```kotlin
package com.ebook.common.analyze.local

import com.ebook.common.text.TextNormalizer
import java.io.File

/**
 * 本地书籍的格式枚举。M1a 只有 [TXT]；[EPUB] 在 M3 落地前先占位，
 * 因为 `book_shelf.book_format` 存的是它的名字，加枚举值不需要迁移。
 */
enum class BookFormat(val extension: String) {
    TXT("txt"),
    EPUB("epub");

    companion object {
        /** 按扩展名解析，未知扩展名返回 null 交给调用方报错而不是硬猜 */
        fun fromExtension(ext: String): BookFormat? =
            entries.firstOrNull { it.extension.equals(ext, ignoreCase = true) }
    }
}

/**
 * 一本书内容仓库的定位值类型（spec §4）。
 *
 * reader 只认它、不认 `File` 也不认 `Uri`：§13 的 SAF 迁移将来只换这个类型的构造方式，
 * 读取路径一行不动。[bookId] 同时是 `book_shelf.note_url`（本地书即内容 md5）与目录名。
 */
data class BookLocation(val bookId: String, val format: BookFormat)

/** 待导入的源文件：路径 + 已探测出的编码（探测一次即固化，见 spec §7） */
data class BookSourceFile(val file: File, val charset: String)

/**
 * 章节索引行，与 `chapter_list` 一一对应。
 * [contentRef] 是"正文在哪"：本地书为 `books/<bookId>/c00042.txt` 相对路径，
 * 网络书（M1b）为章节 URL（spec §4 §5 决定 3）。
 */
data class ChapterEntry(val index: Int, val title: String, val contentRef: String)

/**
 * 一章的内容。[paragraphs] 是**规范化后的纯段落数据**，不含缩进等表现层字符——
 * 段评锚点建立在它之上（spec §9.1），掺进表现层会让锚点随渲染规则漂移。
 * [displayText] 才是给分页与渲染共用的文本（spec §8）。
 */
data class ChapterContent(val title: String, val paragraphs: List<String>) {

    val displayText: String get() = TextNormalizer.toDisplayText(paragraphs)

    /**
     * 段评锚点（spec §9.1）：`pa1:<下标>:<该段规范化后前 16 字的 sha256 前 12 位>`。
     *
     * `pa1:` 版本前缀与 `ck1:` 同理——锚点建立在规范化规则之上，规则一改所有锚点全变、
     * 段评整体散桶，而评论不可再生。哈希命中优先、失败回落下标，是跨来源切分不一致时
     * 唯一的补救手段。
     */
    fun anchorFor(paragraphIndex: Int): String {
        val text = paragraphs.getOrNull(paragraphIndex).orEmpty()
        return "pa1:$paragraphIndex:" + shortHash(text.take(PARAGRAPH_ANCHOR_PREFIX_CHARS))
    }

    private fun shortHash(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    private companion object {
        /** 锚点取样长度：太短易撞、太长则一处排版差异即失配 */
        const val PARAGRAPH_ANCHOR_PREFIX_CHARS = 16
    }
}

/** 源文件能提供的书级元数据（作者为 null 表示未知，由显示层填占位词） */
data class LocalBookMeta(val title: String, val author: String?, val coverFile: File?)

/**
 * 章节正文的落盘出口，由 `BookStore` 实现。
 *
 * 为什么 spec §7 的 `buildChapters` 到这里多了这个参数：TXT 导入是"扫一遍边切边写"，
 * 章文件必须随 Flow 元素一起落盘，否则要么整本进内存、要么扫两遍。返回的 Flow 因此
 * 是**冷流**，收集时才真正推进切分与写入。
 */
interface ChapterSink {
    /** 写一章正文，返回该章的 content_ref */
    suspend fun write(index: Int, paragraphs: List<String>): String
}

/**
 * 一个来源 = 一本书的内容从哪来、怎么解析（spec §7）。
 *
 * 与 `BookParser` 的分界：本接口只管**内容**（元数据、目录、正文）；发现类能力
 * （搜索、分类、书城）不属于它，M1b 会把 `BookParser` 拆成「发现」与「内容」两半。
 */
interface SourceReader {
    suspend fun readMetadata(source: BookSourceFile): LocalBookMeta
    fun buildChapters(source: BookSourceFile, sink: ChapterSink): Flow<ChapterEntry>
    suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent
}
```

不设 `supports(format)`：路由用注入的 `Map<BookFormat, SourceReader>` 完成，格式是**键**而不是需要每个实现自答的问题——多一个 `supports` 就多出"map 与自述不一致"这种可以矛盾的地方。`readChapter` 需要 [BookLocation] 才能把 `content_ref` 解析成文件，故入参带上；M1b 的网络实现里它用于选书源归属，不会是死参数。

文件顶部 import 区补 `import kotlinx.coroutines.flow.Flow`。

- [ ] **Step 4: 实现 ChapterSplitter**

```kotlin
package com.ebook.common.analyze.local

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.coroutineContext

/**
 * 章节切分器（spec §6 §7）：已清洗的行序列 → 章节流。
 *
 * 与旧实现（`BookImportManager.kt:128-186`）的三处差异，都是刻意的：
 * 1. **不写数据库**。旧实现在扫描循环里逐章 insert，是 6000 次事务的成因；这里只产出流，
 *    落盘与批量事务归 `ChapterSink` 与导入器负责。
 * 2. **不清洗文本**。旧实现对每行 `.replace(" ", "")` 并塞进全角缩进，两者都不可逆；
 *    这里假设入参已经过 `TextNormalizer`，且段落里不含表现层字符。
 * 3. 标题取**正则匹配到的那段**（`match.value`），旧实现取整行；因此"他想起第三章的情节"
 *    这类行，标题是"第三章的情节"而前缀"他想起"归入上一章正文——与旧实现语义一致但锁进了测试。
 *
 * 空章（只有标题、没有正文）不产出、也不占索引位：与旧实现 `:145` 的判定一致。
 * 每产出一章 `ensureActive` 一次，使后台协程可被取消（旧实现的 `isCancel` 只在入口检查）。
 */
class ChapterSplitter(private val titleRule: Regex = DEFAULT_TITLE_RULE) {

    /** @param index 从 0 起的章序号 */
    data class RawChapter(val index: Int, val title: String, val paragraphs: List<String>)

    fun split(cleanedLines: Sequence<String>): Flow<RawChapter> = flow {
        var index = 0
        var title: String? = null
        val paragraphs = ArrayList<String>()

        for (line in cleanedLines) {
            if (line.isBlank()) continue
            val match = titleRule.find(line)
            if (match != null) {
                // 标题行前若同一行还有正文残留，归上一章（旧实现的 prefix 处理）
                line.substring(0, match.range.first).takeIf { it.isNotEmpty() }?.let(paragraphs::add)
                if (paragraphs.isNotEmpty()) {
                    emit(RawChapter(index, title ?: match.value, paragraphs.toList()))
                    index++
                    paragraphs.clear()
                }
                title = match.value
            } else {
                // 无「第x章」命名的书：以正文首行为章名（旧实现 :175 的回退）
                if (title == null) title = line
                paragraphs.add(line)
            }
            coroutineContext.ensureActive()
        }

        if (paragraphs.isNotEmpty()) {
            emit(RawChapter(index, title ?: "", paragraphs.toList()))
        }
    }

    companion object {
        /** 默认标题规则，与旧实现 `Pattern.compile("第.{1,7}章.*")` 等价 */
        val DEFAULT_TITLE_RULE: Regex = Regex("第.{1,7}章.*")
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.local.ChapterSplitterTest"`
Expected: `BUILD SUCCESSFUL`，7 个测试通过

- [ ] **Step 6: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/analyze/local/ \
        lib_book_common/src/test/java/com/ebook/common/analyze/local/ChapterSplitterTest.kt
git commit -m "feat(lib_book_common): 新增来源契约与纯函数章节切分器"
```

---

## Task 7: BookStore（私有目录仓库）

spec §4 的落地：目录布局、章文件读写、`.tmp` 原子提交点、孤儿对账。构造参数收 `File` 而不是 `Context`，因此纯 JVM 可测。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/store/BookStore.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/store/BookStoreTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.store

import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [BookStore] 单测（spec §4）。用 [TemporaryFolder] 冒充 `filesDir/books`，
 * 因此不需要 Robolectric 或设备。
 *
 * 重点锁三件容易做错的事：章文件往返**无损**（不掺入表现层字符）、`.tmp` 改名是唯一
 * 提交点、对账只删"DB 里已不存在的书"而绝不误删在册书。
 */
class BookStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: BookStore

    private val bookId = "3f9a1c7d5e6f7a8b9c0d1e2f3a4b5c6d"
    private val location = BookLocation(bookId, BookFormat.TXT)

    @Before
    fun setUp() {
        root = tmp.newFolder("books")
        store = BookStore(root)
    }

    @Test
    fun chapterRefIsSelfContainedRelativePath() {
        val ref = store.chapterRef(bookId, 42)
        assertEquals("books/$bookId/c00042.txt", ref)
    }

    @Test
    fun writeThenReadChapterRoundTripsWithoutAddingIndent() {
        val paragraphs = listOf("第一段 保留空格", "第二段")
        store.writeChapter(location, 0, paragraphs)

        assertEquals(paragraphs, store.readParagraphs(location, 0))
        // 存储层不清洗也不加工：文件里不得出现渲染层缩进
        val raw = File(root, "$bookId/c00000.txt").readText(Charsets.UTF_8)
        assertFalse("章文件不得含全角缩进", raw.contains("　　"))
    }

    @Test
    fun chapterFileEndsWithSingleNewlineBetweenParagraphs() {
        store.writeChapter(location, 7, listOf("甲", "乙"))
        assertEquals("甲\n乙", File(root, "$bookId/c00007.txt").readText(Charsets.UTF_8))
    }

    @Test
    fun readParagraphsReturnsEmptyForMissingFile() {
        assertEquals(emptyList<String>(), store.readParagraphs(location, 99))
        assertFalse(store.hasChapter(location, 99))
    }

    @Test
    fun importCommitMovesTmpDirToFinalName() {
        val staging = store.beginImport(bookId)
        assertTrue(staging.name.endsWith(".tmp"))
        store.writeChapterRaw(staging, 0, "内容")

        store.commitImport(staging, bookId)

        assertFalse(staging.exists())
        assertTrue(File(root, bookId).exists())
    }

    @Test
    fun abortImportRemovesStagingDir() {
        val staging = store.beginImport(bookId)
        store.writeChapterRaw(staging, 0, "半本")

        store.abortImport(staging)

        assertFalse(staging.exists())
    }

    @Test
    fun reconcileDeletesOnlyUnlistedAndStaleTmpDirs() {
        File(root, "$bookId/c00000.txt").apply { parentFile.mkdirs(); writeText("在册") }
        File(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/c00000.txt").apply { parentFile.mkdirs(); writeText("孤儿") }
        File(root, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.txt").apply { mkdirs(); writeText("半成品") }

        store.reconcile(setOf(bookId))

        assertTrue(File(root, bookId).exists())
        assertFalse(File(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").exists())
        assertFalse(File(root, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.txt").exists())
    }

    @Test
    fun deleteBookRemovesWholeDirectory() {
        store.writeChapter(location, 0, listOf("甲"))
        store.writeChapter(location, 1, listOf("乙"))

        store.deleteBook(location)

        assertFalse(File(root, bookId).exists())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.store.BookStoreTest"`
Expected: 编译失败，`Unresolved reference: BookStore`

- [ ] **Step 3: 实现 BookStore**

```kotlin
package com.ebook.common.store

import com.ebook.common.analyze.local.BookLocation
import java.io.File

/**
 * 本地书籍内容仓库（spec §4）：`filesDir/books/<bookId>/cNNNNN.txt`，一章一个文件。
 *
 * 只收一个 [booksRoot] 目录参数、不碰 `Context`，因此整本书内容基座可在纯 JVM 下测试。
 * 生产环境由 `ContentStoreModule` 传入 `File(context.filesDir, "books")`。
 *
 * **章文件存 UTF-8 重编码的规范化前文本**：无损指文本层（字符不缺），字节层统一 UTF-8，
 * 于是读取侧不必再管源编码。段落之间以单个 LF 分隔，不写缩进（缩进是表现层，见 spec §8）。
 */
class BookStore(private val booksRoot: File) {

    /** content_ref 是自包含的 filesDir 相对路径，读取方拿到即用、不回查书级字段（spec §4） */
    fun chapterRef(bookId: String, index: Int): String =
        "$DIR_NAME/$bookId/${chapterFileName(index)}"

    fun bookDir(location: BookLocation): File = File(booksRoot, location.bookId)

    fun chapterFile(location: BookLocation, index: Int): File =
        File(bookDir(location), chapterFileName(index))

    fun hasChapter(location: BookLocation, index: Int): Boolean = chapterFile(location, index).exists()

    /** 写一章正文：段落以单个 LF 连接，UTF-8 */
    fun writeChapter(location: BookLocation, index: Int, paragraphs: List<String>) {
        writeChapterRaw(bookDir(location), index, paragraphs.joinToString("\n"))
    }

    /** 读一章正文并切回段落；文件不存在返回空表（调用方据此判"内容缺失"而非抛异常） */
    fun readParagraphs(location: BookLocation, index: Int): List<String> {
        val file = chapterFile(location, index)
        if (!file.exists()) return emptyList()
        return file.readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    /** 导入暂存目录：带 `.tmp` 后缀，未改名的目录在对账时一律作废（spec §4 原子提交点） */
    fun beginImport(bookId: String): File =
        File(booksRoot, "$bookId$TMP_SUFFIX").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

    /** 往暂存目录直接写一章（导入期还没有 BookLocation） */
    fun writeChapterRaw(dir: File, index: Int, text: String) {
        val target = File(dir, chapterFileName(index))
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
    }

    fun commitImport(staging: File, bookId: String) {
        val final = File(booksRoot, bookId)
        if (final.exists()) final.deleteRecursively()
        // renameTo 同分区是原子操作：要么整本可见，要么完全不存在，不会有半本被读到
        if (!staging.renameTo(final)) {
            staging.copyRecursively(final, overwrite = true)
            staging.deleteRecursively()
        }
    }

    fun abortImport(staging: File) {
        staging.deleteRecursively()
    }

    fun deleteBook(location: BookLocation) {
        bookDir(location).deleteRecursively()
    }

    /**
     * 对账：删除"DB 里已不存在的书"的目录、所有 `.tmp` 残留目录，以及散落在仓库根的文件。
     *
     * 存在理由是删书与导入中断都会留下无主文件，而没有对账就没人再发现它们（用户侧表现为
     * "占了空间却看不见书"）。 loose 文件一并清掉是因为正常路径不会在 `books/` 根下产生文件。
     */
    fun reconcile(liveBookIds: Set<String>) {
        booksRoot.listFiles()?.forEach { entry ->
            when {
                entry.isDirectory && entry.name.endsWith(TMP_SUFFIX) -> entry.deleteRecursively()
                entry.isDirectory && entry.name !in liveBookIds -> entry.deleteRecursively()
                entry.isFile -> entry.delete()
            }
        }
    }

    companion object {
        /** 仓库目录名，同时是 content_ref 的首段 */
        const val DIR_NAME = "books"
        private const val TMP_SUFFIX = ".tmp"

        /** 序号零填充到 5 位，保证字典序等于数值序；上限 99999 章足够 */
        fun chapterFileName(index: Int): String = "c%05d.txt".format(index)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.store.BookStoreTest"`
Expected: `BUILD SUCCESSFUL`，9 个测试通过

- [ ] **Step 5: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/store/BookStore.kt \
        lib_book_common/src/test/java/com/ebook/common/store/BookStoreTest.kt
git commit -m "feat(lib_book_common): 新增本地书章文件仓库与对账"
```

---

## Task 8: TxtSourceReader 与 Hilt 装配

TXT 格式的 `SourceReader` 实现，把 Task 2/3/6 的纯部件串起来；`BookStore` 同时充当 `ChapterSink` 的适配处。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/analyze/local/TxtSourceReader.kt`
- Create: `lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/analyze/local/TxtSourceReaderTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.analyze.local

import com.ebook.common.store.BookStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [TxtSourceReader] 的往返测试：源文件 → 切分落盘 → 再读回。
 *
 * 这里锁的是**整条链**而不只是切分：章文件必须能被 `readChapter` 原样读回，
 * 且 `content_ref` 与 `BookStore.chapterRef` 严格一致（不一致会导致索引指向不存在的文件，
 * 症状是"导入成功但翻开是空白页"）。
 */
class TxtSourceReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var booksRoot: File
    private lateinit var store: BookStore
    private lateinit var reader: TxtSourceReader

    private val bookId = "a".repeat(32)
    private val location = BookLocation(bookId, BookFormat.TXT)

    @Before
    fun setUp() {
        booksRoot = tmp.newFolder("books")
        store = BookStore(booksRoot)
        reader = TxtSourceReader(store)
    }

    @Test
    fun buildChaptersWritesFilesAndEmitsMatchingEntries() = runTest {
        val source = txt("第一章 起\n正文甲\n\n第二章 承\n正文乙")

        val entries = reader.buildChapters(BookSourceFile(source, "UTF-8"), sink(bookId)).toList()

        assertEquals(listOf(0, 1), entries.map { it.index })
        assertEquals(listOf("第一章 起", "第二章 承"), entries.map { it.title })
        assertEquals(
            listOf(store.chapterRef(bookId, 0), store.chapterRef(bookId, 1)),
            entries.map { it.contentRef }
        )
        assertEquals(listOf("正文甲"), store.readParagraphs(location, 0))
    }

    @Test
    fun readChapterReturnsParagraphsAndDisplayText() = runTest {
        val source = txt("第一章 起\n正文甲 保留空格\n正文乙")
        val entry = reader.buildChapters(BookSourceFile(source, "UTF-8"), sink(bookId)).toList().single()

        val content = reader.readChapter(entry, location)

        assertEquals("第一章 起", content.title)
        assertEquals(listOf("正文甲 保留空格", "正文乙"), content.paragraphs)
        assertEquals("　　正文甲 保留空格\n　　正文乙", content.displayText)
        assertFalse("段落数据里不该有缩进", content.paragraphs.any { it.startsWith("　　") })
    }

    @Test
    fun readChapterOnMissingFileYieldsEmptyParagraphs() = runTest {
        val content = reader.readChapter(ChapterEntry(5, "无", store.chapterRef(bookId, 5)), location)
        assertEquals(emptyList<String>(), content.paragraphs)
    }

    @Test
    fun readMetadataUsesFileNameParsing() {
        val source = File(booksRoot, "《星辰变》作者：我吃西红柿.txt").apply { writeText("第一章 x\n正文") }

        val meta = TxtSourceReader.readMetadataOf(source)

        assertEquals("星辰变", meta.title)
        assertEquals("我吃西红柿", meta.author)
    }

    private fun sink(bookId: String) = object : ChapterSink {
        override suspend fun write(index: Int, paragraphs: List<String>): String {
            store.writeChapter(BookLocation(bookId, BookFormat.TXT), index, paragraphs)
            return store.chapterRef(bookId, index)
        }
    }

    private fun txt(content: String): File =
        File(tmp.root, "in.txt").apply { writeText(content, Charsets.UTF_8) }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.local.TxtSourceReaderTest"`
Expected: 编译失败，`Unresolved reference: TxtSourceReader`

- [ ] **Step 3: 实现 TxtSourceReader**

```kotlin
package com.ebook.common.analyze.local

import com.ebook.common.domain.FileNameMetadata
import com.ebook.common.store.BookStore
import com.ebook.common.text.EncodingProbe
import com.ebook.common.text.StrictTextReader
import com.ebook.common.text.TextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject

/**
 * TXT 格式的 [SourceReader]（spec §7）。M3 的 `EpubSourceReader` 与 M1b 的
 * `JsoupSourceReader` 与它并列，由 `Map<BookFormat, SourceReader>` 路由。
 *
 * 源文件按 `charset` **严格解码**（[StrictTextReader]）后逐行喂给 [ChapterSplitter]，
 * 章文件则以 UTF-8 落盘——于是读取路径与源编码彻底解耦。
 */
class TxtSourceReader @Inject constructor(
    private val store: BookStore,
    private val splitter: ChapterSplitter = ChapterSplitter(),
) : SourceReader {

    override suspend fun readMetadata(source: BookSourceFile): LocalBookMeta =
        readMetadataOf(source.file)

    override fun buildChapters(source: BookSourceFile, sink: ChapterSink): Flow<ChapterEntry> = flow {
        val lines = StrictTextReader.lines(source.file, source.charset)
            .map { TextNormalizer.cleanParagraph(it) }
        splitter.split(lines).collect { raw ->
            emit(ChapterEntry(index = raw.index, title = raw.title, contentRef = sink.write(raw.index, raw.paragraphs)))
        }
    }

    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent {
        val paragraphs = store.readParagraphs(location, entry.index)
        return ChapterContent(title = entry.title, paragraphs = paragraphs)
    }

    companion object {
        /**
         * 元数据只能来自文件名——TXT 没有内嵌元数据，旧格式连作者都没有。
         * 作者解不出时返回 null，由显示层填「侠名」，占位词不参与 `comment_key`（spec §8 §9.1）。
         */
        fun readMetadataOf(file: File): LocalBookMeta {
            val parsed = FileNameMetadata.parse(file.name)
            return LocalBookMeta(title = parsed.title, author = parsed.author, coverFile = null)
        }

        /** 探测源文件编码；导入器在拷贝那一次读里调用它，结果固化进 `book_shelf.text_charset` */
        fun probeCharset(file: File): String {
            val headSize = minOf(EncodingProbe.HEAD_BYTES.toLong(), file.length()).toInt()
            val head = ByteArray(headSize)
            file.inputStream().use { it.read(head) }
            return EncodingProbe.detect(head, headSize)
        }
    }
}
```

- [ ] **Step 4: 装配 BookStore 与 splitter**

Create `lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt`:

```kotlin
package com.ebook.common.di

import android.content.Context
import com.ebook.common.analyze.local.ChapterSplitter
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * 本地内容仓库的装配点。
 *
 * `BookStore` 只接 `filesDir/books` 这个目录参数而不接 `Context`——留下 `Context` 会让整个
 * 内容基座只能在仪器测试里跑。这里做唯一一次 `Context` 到 `File` 的转换。
 */
@Module
@InstallIn(SingletonComponent::class)
object ContentStoreModule {

    @Provides
    @Singleton
    fun provideBookStore(@ApplicationContext context: Context): BookStore =
        BookStore(File(context.filesDir, BookStore.DIR_NAME))

    @Provides
    @Singleton
    fun provideChapterSplitter(): ChapterSplitter = ChapterSplitter()

    @Provides
    @Singleton
    fun provideChapterContentCache(): ChapterContentCache = ChapterContentCache()
}
```

`ChapterContentCache` 在 Task 9 建，因此**本任务先只写前两个 provider**，把 `provideChapterContentCache` 与它的 import 留到 Task 9 再加回来——否则本任务编译不过。

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.local.TxtSourceReaderTest"`
Expected: `BUILD SUCCESSFUL`，4 个测试通过

- [ ] **Step 6: 编译整库确认无残留引用**

Run: `./gradlew :lib_book_common:assembleDebug :lib_ebook_db:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/analyze/local/TxtSourceReader.kt \
        lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt \
        lib_book_common/src/test/java/com/ebook/common/analyze/local/TxtSourceReaderTest.kt
git commit -m "feat(lib_book_common): 新增 TXT 来源解析器与仓库装配"
```

---

## Task 9: ChapterContentCache（章节正文内存缓存）

spec §7 r5 补的那条：正文出 DB 后 `loadPage` **每翻一页都取一次正文**，不缓存就退化成每页读盘 + 解码，比原来的一次主键查询更慢。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/store/ChapterContentCache.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/store/ChapterContentCacheTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.store

import com.ebook.common.analyze.local.ChapterContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ChapterContentCache] 单测。锁两件事：同一章只读盘一次；三种失效条件各自生效。
 *
 * 失效条件是这套缓存唯一容易错的地方——读完不失效，用户重导入同一本书（章文件已被新目录
 * 覆盖）后会继续读到内存里的旧内容。
 */
class ChapterContentCacheTest {

    private fun content(mark: String) = ChapterContent("标题", listOf(mark))

    @Test
    fun repeatedLoadCallsLoaderOnce() = runTest {
        val cache = ChapterContentCache(capacity = 3)
        var calls = 0

        repeat(5) { cache.getOrLoad("ref-a") { calls++; content("a") } }

        assertEquals(1, calls)
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondCapacity() = runTest {
        val cache = ChapterContentCache(capacity = 2)
        var calls = 0
        suspend fun touch(ref: String) = cache.getOrLoad(ref) { calls++; content(ref) }

        touch("a"); touch("b"); touch("a"); touch("c")

        assertEquals("a 是最近使用过的，不该被逐出", 2, calls)
        touch("b")
        assertEquals("b 已被逐出，应重新加载", 3, calls)
    }

    @Test
    fun invalidateBookDropsOnlyThatBook() = runTest {
        val cache = ChapterContentCache(capacity = 4)
        var calls = 0
        cache.getOrLoad("books/A/c00000.txt") { calls++; content("x") }
        cache.getOrLoad("books/B/c00000.txt") { calls++; content("y") }

        cache.invalidateBook("A")
        cache.getOrLoad("books/A/c00000.txt") { calls++; content("x") }
        cache.getOrLoad("books/B/c00000.txt") { calls++; content("y") }

        assertEquals("A 重新加载、B 未受影响", 3, calls)
    }

    @Test
    fun clearEmptiesEverything() = runTest {
        val cache = ChapterContentCache(capacity = 4)
        var calls = 0
        cache.getOrLoad("r") { calls++; content("x") }

        cache.clear()
        cache.getOrLoad("r") { calls++; content("x") }

        assertEquals(2, calls)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.store.ChapterContentCacheTest"`
Expected: 编译失败，`Unresolved reference: ChapterContentCache`

- [ ] **Step 3: 实现**

```kotlin
package com.ebook.common.store

import com.ebook.common.analyze.local.ChapterContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 章节正文的内存缓存（spec §7）。
 *
 * 为什么必须有：`ReadBookActivity.loadPage` 是**每翻一页都取一次正文**。原先正文在
 * `book_content` 里，那只是一次主键查询；改成章文件后若无缓存，同一章翻 20 页就是
 * 20 次 open + read + 解码，性能不升反降。容量取 3（当前章 + 前后各一），正好覆盖
 * 预加载上一页/下一页。
 *
 * 键用 `content_ref` 而不是 (书, 章) 二元组：`content_ref` 本身是持久定位符且内含 bookId，
 * 于是 [invalidateBook] 按 `/<bookId>/` 片段剔除即可，不必再维护反向索引。
 *
 * 失效入口对应真实事件：重解析、删书、合并来源 → [invalidateBook]；改字号字体**不**失效
 * 本缓存（那只影响排版偏移，另在 Task 15 处理）。
 */
class ChapterContentCache(private val capacity: Int = DEFAULT_CAPACITY) {

    private val mutex = Mutex()

    // accessOrder = true 使 LinkedHashMap 按访问序排列，头部即最久未使用
    private val entries = object : LinkedHashMap<String, ChapterContent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChapterContent>): Boolean =
            size > capacity
    }

    /** 命中即返回；未命中时执行 [loader] 并放入缓存。loader 在锁外跑，避免读盘阻塞其他章 */
    suspend fun getOrLoad(contentRef: String, loader: suspend () -> ChapterContent): ChapterContent {
        mutex.withLock { entries[contentRef] }?.let { return it }
        val loaded = loader()
        mutex.withLock { entries[contentRef] = loaded }
        return loaded
    }

    suspend fun invalidateBook(bookId: String) {
        val marker = "/$bookId/"
        mutex.withLock {
            entries.keys.filter { it.contains(marker) }.forEach { entries.remove(it) }
        }
    }

    suspend fun clear() {
        mutex.withLock { entries.clear() }
    }

    private companion object {
        /** 当前章 + 前后各一章 */
        const val DEFAULT_CAPACITY = 3
    }
}
```

- [ ] **Step 4: 补回 Task 8 暂缓的 provider**

在 `lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt` 加入：

```kotlin
    @Provides
    @Singleton
    fun provideChapterContentCache(): ChapterContentCache = ChapterContentCache()
```

（Task 8 Step 4 已给出完整文件，含此 provider 与 `import com.ebook.common.store.ChapterContentCache`；若当时按说明只写了前两个 provider，现在补齐。）

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.store.ChapterContentCacheTest"`
Expected: `BUILD SUCCESSFUL`，4 个测试通过

- [ ] **Step 6: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/store/ChapterContentCache.kt \
        lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt \
        lib_book_common/src/test/java/com/ebook/common/store/ChapterContentCacheTest.kt
git commit -m "feat(lib_book_common): 新增章节正文内存缓存"
```

---

## Task 10: DB 层改造（实体改名、新列、book_group、MIGRATION_2_3）

全计划唯一会大范围破坏编译的任务，因此单独成任务、一步到位：改实体 → 改 DAO → 写迁移 → **让编译器把引用点全找出来** → 逐处修。顺序不能反，靠编译器找引用比靠 grep 可靠。

**Files:**
- Create: `lib_ebook_db/src/main/java/com/ebook/db/entity/BookGroupEntity.kt`
- Create: `lib_ebook_db/src/main/java/com/ebook/db/dao/BookGroupDao.kt`
- Modify: `lib_ebook_db/.../entity/ChapterListEntity.kt`、`entity/BookShelfEntity.kt`、`dao/ChapterListDao.kt`、`AppDatabase.kt`、`di/DatabaseModule.kt`
- Modify（编译器驱动）：`lib_book_common/.../repository/BookRepository.kt`、`lib_book_common/.../analyze/source/JsoupBookParser.kt`、`module_book/.../service/DownloadService.kt`、`module_book/.../ReadBookActivity.kt`、`module_book/.../repository/DownloadRepository.kt`、`module_book/.../mvvm/viewmodel/DownloadManageViewModel.kt`、`module_book/.../reader/ReaderPanels.kt`、`module_book/.../util/BookImportManager.kt`，以及 `lib_book_common/src/test/.../BookRepositoryTest.kt`

- [ ] **Step 1: 新增 book_group 实体**

```kotlin
package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 作品分组关联行（spec §3 §9.2）：把"一个来源条目"与"一个评论桶键"绑起来。
 *
 * 刻意没有 `works` 表、也没有 `work_id` 这类标识符——**"作品"只以 `comment_key` 这个不透明
 * token 存在**。后端不得存书籍数据；客户端这边也不给"作品"配一个看起来像注册表主键的东西，
 * 免得将来有人以为服务端能解释它。
 *
 * 一个 `note_url` 可有任意多行（读评论取全部键的并集），其中恰好一行 [isPrimary]（写评论
 * 只用它）。"恰好一行"SQLite 与 Room 都表达不了（无部分唯一索引），由调用方在
 * `withWriteTransaction` 内保证。
 */
@Parcelize
@Entity(
    tableName = "book_group",
    primaryKeys = ["comment_key", "note_url"],
    indices = [Index(value = ["note_url"], name = "idx_book_group_note_url")]
)
data class BookGroupEntity(
    /** 客户端派生的不透明评论桶键，形如 `ck1:<64 hex>`（见 CommentKey） */
    @ColumnInfo(name = "comment_key")
    var commentKey: String = String(),
    /** 来源条目，等于 `book_shelf.note_url` */
    @ColumnInfo(name = "note_url")
    var noteUrl: String = String(),
    /** 写评论时使用的键所在行 */
    @ColumnInfo(name = "is_primary")
    var isPrimary: Boolean = false,
) : Parcelable
```

- [ ] **Step 2: 新增 BookGroupDao（M1a 只要两个方法）**

```kotlin
package com.ebook.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.ebook.db.entity.BookGroupEntity

/**
 * 作品分组关联表访问器。
 *
 * M1a 只写入与随删——消费方（评论读写、合并/拆分 UI）在 M2 才出现。现在就铺满查询接口
 * 会得到一批没有调用方的方法，本仓库已经背过这种债（见 `docs/test-coverage-todo.md` 与
 * 零调用方 DAO 方法那条）。
 */
@Dao
interface BookGroupDao {

    /** 按 (comment_key, note_url) upsert 一行关联 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: BookGroupEntity)

    /** 删除某条来源的全部关联行：从书架移除书时随之清理 */
    @Query("DELETE FROM book_group WHERE note_url = :noteUrl")
    suspend fun deleteFor(noteUrl: String)
}
```

- [ ] **Step 3: 改 ChapterListEntity 的主键字段**

把

```kotlin
    /**
     * 当前章节对应的文章地址
     */
    @PrimaryKey
    @ColumnInfo(name = "dur_chapter_url")
    var durChapterUrl: String = String(),
```

替换为

```kotlin
    /**
     * 内容定位符：本地书是私有目录里的相对路径（`books/<bookId>/c00042.txt`），网络书是
     * 该站章节 URL。主键仍是自然键——一章一定位符（见 ADR-0003）。
     *
     * 原名 `dur_chapter_url`。改名能成立的前提是评论聚合键已从章节 URL 换成 `comment_key`
     * （spec §9）：此前这个字段同时背着"书源章节 URL"与"评论关联键"两个身份，谁也动不了它。
     */
    @PrimaryKey
    @ColumnInfo(name = "content_ref")
    var contentRef: String = String(),
```

`hasCache` 与 `@Ignore bookContent` **保持不动**：网络书仍在用，M1b（v4 迁移）才收。

- [ ] **Step 4: 改 BookShelfEntity**

在 `tag` 字段之后、`@Ignore bookInfo` 之前插入：

```kotlin
    /**
     * 本地书的格式名（`BookFormat` 枚举名），网络书为 null。与 [textCharset] 一起构成
     * 重解析所需的全部信息；路由 reader 也读它。
     */
    @ColumnInfo(name = "book_format")
    var bookFormat: String? = null,
    /**
     * 探测一次即固化的**源文件**编码。章文件本身统一 UTF-8，因此此列只在重解析时用
     * （spec §4 §7：旧实现每次导入都重头探测一遍全文件）。
     */
    @ColumnInfo(name = "text_charset")
    var textCharset: String? = null,
    /**
     * 主匹配名：算 `comment_key` 用，为空回落到 `book_info.name`。
     * 与显示名分开的理由见 spec §9.3——不分开就会出现"为了对上评论去改用户看到的书名"。
     */
    @ColumnInfo(name = "match_name")
    var matchName: String? = null,
    /** 匹配作者，为空回落到 `book_info.author` */
    @ColumnInfo(name = "match_author")
    var matchAuthor: String? = null,
```

- [ ] **Step 5: 同步 ChapterListDao 的 SQL**

`getChapterByUrl` 与 `updateHasCache` 两个 `@Query` 里的 `WHERE dur_chapter_url = :chapterUrl` 改成 `WHERE content_ref = :chapterUrl`。参数名保持 `chapterUrl` 不改（它是调用方传入的定位符值，改名会波及 M1b 之外的无关 diff）。

`ChapterListDao` 与 `getChaptersForBook` 上关于"REPLACE 先删后插会挪 rowid、因此必须显式 ORDER BY"的两段 KDoc **原样保留**——该约束与列名无关，仍然成立。

- [ ] **Step 6: 注册进 AppDatabase**

```kotlin
@Database(
    entities = [
        BookShelfEntity::class,
        BookInfoEntity::class,
        ChapterListEntity::class,
        BookContentEntity::class,
        SearchHistoryEntity::class,
        DownloadChapterEntity::class,
        BookGroupEntity::class
    ],
    version = 3,
    exportSchema = true
)
```

在 DAO 抽象方法区加：

```kotlin
    /** 作品分组关联表：来源条目与评论桶键的多对多关联（见 spec §9.2） */
    abstract fun bookGroupDao(): BookGroupDao
```

类 KDoc 里的"六张表的装配点"改为"七张表"，并补一句 `book_group` 承担作品身份；`book_content` 那句要注明它**当前仍是网络书的缓存事实源，本地书已改走章文件**（M1b 删除），否则注释会与代码分裂。

- [ ] **Step 7: 写 MIGRATION_2_3**

```kotlin
    /**
     * v2 → v3（M1a，spec §5）：本地书正文从 `book_content` 迁到应用私有目录的章文件。
     *
     * 做四件事：建 `book_group`、给 `book_shelf` 补本地来源所需列、把 `chapter_list` 主键列
     * 改名成通用内容定位符、**直接删除全部本地书数据**。
     *
     * 删而不迁移是刻意的：本地书的索引与正文都可再生（重新导入即得），而旧正文是**被清洗过**
     * 的——旧实现删光了行内空格并把全角缩进写进正文，把它搬进章文件等于将损毁固化成新基座。
     * 判据见 spec §2 决定 9（可再生则不背兼容）。
     *
     * `book_content` 表与 `chapter_list.has_cache` 本次都不删：网络书正文要到 M1b 才出 DB，
     * M1a 期间它们仍是网络书的缓存事实源与"已缓存"徽章依据，v4 一并收掉。
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `book_group` (" +
                    "`comment_key` TEXT NOT NULL, `note_url` TEXT NOT NULL, " +
                    "`is_primary` INTEGER NOT NULL, PRIMARY KEY(`comment_key`, `note_url`))"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_book_group_note_url` " +
                    "ON `book_group` (`note_url`)"
            )
            connection.execSQL("ALTER TABLE book_shelf ADD COLUMN book_format TEXT")
            connection.execSQL("ALTER TABLE book_shelf ADD COLUMN text_charset TEXT")
            connection.execSQL("ALTER TABLE book_shelf ADD COLUMN match_name TEXT")
            connection.execSQL("ALTER TABLE book_shelf ADD COLUMN match_author TEXT")
            connection.execSQL("ALTER TABLE chapter_list RENAME COLUMN dur_chapter_url TO content_ref")
            connection.execSQL("DELETE FROM book_content WHERE tag = 'loc_book'")
            connection.execSQL("DELETE FROM chapter_list WHERE tag = 'loc_book'")
            connection.execSQL("DELETE FROM book_info WHERE tag = 'loc_book'")
            connection.execSQL("DELETE FROM book_shelf WHERE tag = 'loc_book'")
        }
    }
```

同文件里把链改成 `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`，并加 provider：

```kotlin
    @Provides
    @Singleton
    fun provideBookGroupDao(db: AppDatabase): BookGroupDao = db.bookGroupDao()
```

- [ ] **Step 8: 生成 schema，让编译器列出全部引用点**

Run: `./gradlew :lib_ebook_db:kspDebugKotlin :lib_ebook_db:assembleDebug`
Expected: 新增 `lib_ebook_db/schemas/com.ebook.db.AppDatabase/3.json`；若 KSP 报"created schema does not match migrations"，说明 Step 7 的 SQL 与实体定义不一致，逐列比对（最常错的是 `book_group` 的列顺序与 PRIMARY KEY 声明顺序，Room 做的是**字符串级** schema 比对）。

Run: `./gradlew compileDebugKotlin --continue`
Expected: 失败，报出 `durChapterUrl`/`unresolved reference` 的全部位置——这就是 Step 9 的待办清单，比 grep 可靠。

- [ ] **Step 9: 按语义逐处修引用**

三类，**不要机械替换字符串**：

1. 指"章节内容在哪"的读写 → `contentRef`。包括 `BookRepository.loadBookContent` / `getCachedChapterUrls` / `updateChapterCache` 的调用方、`ReadBookActivity.loadPage`、`JsoupBookParser` 里构造 `ChapterListEntity` 处、下载队列取章定位符处。
2. 传给 `BookContentDao` / `DownloadChapterDao` 的参数：那两个 DAO 的**字段名未改**（`BookContentEntity.durChapterUrl`、`DownloadChapterEntity.durChapterUrl` 是另两张表的列，M1b 才动），只是取值来自 `chapter.contentRef`。写成 `bookContentDao.deleteByChapterUrls(chapters.map { it.contentRef })`——对网络书 `content_ref` 就是原 URL，值不变，行为不变。
3. 测试数据与断言：`BookRepositoryTest` 19 处、`module_book` 侧若干。同样按语义替换，**不要为了让测试通过而放宽断言**。

- [ ] **Step 10: 全量编译与单测**

Run: `./gradlew test :module_book:assembleDebug :lib_ebook_db:assembleDebug :lib_book_common:assembleDebug`
Expected: `BUILD SUCCESSFUL`，且无新增编译警告（AGENTS.md：提交应保持警告清洁）

- [ ] **Step 11: 提交**

```bash
git add lib_ebook_db/ lib_book_common/ module_book/
git commit -m "refactor(all): 章节主键改名 content_ref 并接入 book_group 表" -m "$(cat <<'EOF'
ChapterListEntity.durChapterUrl 改名 contentRef、chapter_list 列 dur_chapter_url
改名为 content_ref，使其能同时承载本地章文件路径与网络章节 URL。改名可行的前提是
评论聚合键已从章节 URL 换成客户端派生的 comment_key。

Room version 2 升 3；v3 迁移直接删除全部本地书数据而不做搬运——旧正文被删空格与
塞缩进污染过，搬进章文件等于把损毁固化。book_content 与 has_cache 留到 v4 随网络书
出 DB 一并收掉。

BREAKING CHANGE: ChapterListEntity.durChapterUrl 不再存在，改为 contentRef。
EOF
)"
```

---

## Task 11: 迁移验证

Room 在构建期已经把"迁移 SQL 与实体声明是否一致"管起来了（`exportSchema` + KSP 比对 `3.json`），本任务补的是**数据层**验证：覆盖安装后网络书数据必须完好、本地书必须按设计消失。后者只能在设备上确认。

**Files:**
- Modify: `docs/test-coverage-todo.md`

- [ ] **Step 1: 确认构建期校验已覆盖形状**

Run: `./gradlew :lib_ebook_db:kspDebugKotlin --rerun-tasks`
Expected: `BUILD SUCCESSFUL` 且无 `Migration validation warnings`。Room 若发现 `MIGRATION_2_3` 产出的 schema 与实体声明不符会在此步直接报错——**不要**改用 `fallbackToDestructiveMigration` 绕过（ADR-0003 明令禁止）。

- [ ] **Step 2: 写人工覆盖安装规程**

在 `docs/test-coverage-todo.md` 末尾追加一节，标题「Room v2→v3 覆盖安装验证（M1a）」，内容照录：

```
前置：装的是改动前的包，且书架上同时有 ①至少一本本地导入的 TXT ②至少一本网络书源加进
书架的书。

1. 记下网络书的阅读进度与"已缓存 y/z"数字。
2. 覆盖安装改动后的包（不要清数据）。
3. 打开书架：本地书应全部消失，网络书仍在、进度与缓存数字不变。
   —— 本地书消失是设计如此（spec §2 决定 9：可再生数据不背兼容），不是 bug。
4. 重新导入那本 TXT：应在数秒内出现在书架上，点开能翻页。
5. `adb logcat -b crash` 应无 FATAL EXCEPTION。
6. `adb shell run-as <包名> ls files/books` 应看到以 32 位 md5 命名的目录，里面是
   c00000.txt、c00001.txt …
```

- [ ] **Step 3: 提交**

```bash
git add docs/test-coverage-todo.md
git commit -m "docs: 补充 Room v2 升 v3 的覆盖安装人工验证规程"
```

> Agent 完成到此只能确认"构建期 schema 一致"。**步骤 2 的六项全部是未验证项**，必须在交接时显式说明。

---

## Task 12: BookRepository 内容读路径按 tag 分叉

`BookRepository` 是"取一章正文"的唯一收口。本地书在这里改走章文件，网络书路径**一行不改**——这正是 spec §3 那条"阅读器契约不变"的落点。

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryTest.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/repository/LocalContentReadTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.common.repository

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.SourceReader
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.BookShelfEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 本地书内容读取路径的测试（spec §7）。
 *
 * 锁的是那条"来源不同、下游相同"的边界：`tag == LOCAL_TAG` 时正文一律走 reader + 章文件，
 * **绝不查 book_content**。旧实现缺这层分叉，导致本地书一旦缓存 miss 就拿 `md5_章号`
 * 当 URL 去打第三方书源（`BookReadViewModel.kt:120-128` 的缺陷）。
 */
class LocalContentReadTest {

    private val bookId = "b".repeat(32)
    private val location = BookLocation(bookId, BookFormat.TXT)

    @Test
    fun loadsLocalChapterThroughReaderNotDatabase() = runTest {
        val read = RecordingReader(ChapterContent("第一章", listOf("正文甲")))
        val repository = Fixture.repositoryWith(read)

        val content = repository.loadLocalChapter(
            BookShelfEntity(noteUrl = bookId, tag = BookShelfEntity.LOCAL_TAG),
            index = 0,
            title = "第一章",
        )

        assertEquals(listOf("正文甲"), content?.paragraphs)
        assertEquals(1, read.calls)
    }

    @Test
    fun secondPageTurnHitsCacheAndDoesNotCallReaderAgain() = runTest {
        val read = RecordingReader(ChapterContent("第一章", listOf("正文甲")))
        val repository = Fixture.repositoryWith(read)
        val shelf = BookShelfEntity(noteUrl = bookId, tag = BookShelfEntity.LOCAL_TAG)

        repeat(3) { repository.loadLocalChapter(shelf, 0, "第一章") }

        assertEquals("同一章翻三页只该读一次盘", 1, read.calls)
    }

    @Test
    fun unknownFormatYieldsNullInsteadOfFallingBackToNetwork() = runTest {
        val repository = Fixture.repositoryWith(RecordingReader(ChapterContent("t", listOf())))
        val content = repository.loadLocalChapter(
            BookShelfEntity(noteUrl = bookId, tag = BookShelfEntity.LOCAL_TAG, bookFormat = "mobi"),
            0,
            "第一章",
        )
        assertNull("没有对应 reader 时必须返回缺失，绝不能退回网络", content)
    }

    /** 记录调用次数的假 reader，同时充当"章文件存在"的判据 */
    private class RecordingReader(private val content: ChapterContent) : SourceReader {
        var calls = 0
        override suspend fun readMetadata(source: com.ebook.common.analyze.local.BookSourceFile) =
            com.ebook.common.analyze.local.LocalBookMeta("t", null, null)
        override fun buildChapters(
            source: com.ebook.common.analyze.local.BookSourceFile,
            sink: com.ebook.common.analyze.local.ChapterSink,
        ) = kotlinx.coroutines.flow.emptyFlow<com.ebook.common.analyze.local.ChapterEntry>()
        override suspend fun readChapter(entry: com.ebook.common.analyze.local.ChapterEntry, loc: BookLocation): ChapterContent {
            calls++
            return content
        }
    }

    private object Fixture {
        fun repositoryWith(reader: SourceReader): BookRepository {
            val store = BookStore(File(System.getProperty("java.io.tmpdir"), "unused-books-dir"))
            val daos = FakeDaos()
            return BookRepository(
                daos.shelf, daos.info, daos.chapter, daos.content,
                localReaders = mapOf(BookFormat.TXT to reader),
                bookStore = store,
                contentCache = ChapterContentCache(),
            )
        }
    }
}
```

`FakeDaos()` 复用 `BookRepositoryTest` 里已有的那批手写 fake（同包内可见），因此**必须先把 `BookRepositoryTest` 的 fake 提为 internal 类或补一个共用的 `FakeDaos` 工厂**——见 Step 3。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.LocalContentReadTest"`
Expected: 编译失败，`BookRepository` 没有四参以上的构造、也没有 `loadLocalChapter`

- [ ] **Step 3: 把 fake DAO 抽成共用工厂**

`BookRepositoryTest` 现在把 `FakeBookShelfDao` 等四个类写在测试文件内。把它们移到 `lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt`，四个 fake 都改成 `internal class`（Task 13 的导入器测试要用它们），并加：

```kotlin
package com.ebook.common.repository

/** 手写 fake 的集中出口，供本模块多个测试类复用（避免每个测试各抄一份） */
internal class FakeDaos {
    val shelf = FakeBookShelfDao()
    val info = FakeBookInfoDao()
    val chapter = FakeChapterListDao()
    val content = FakeBookContentDao()
    val group = FakeBookGroupDao()
}
```

两个前置要求，Task 13 的测试直接依赖：

1. 每个 fake 暴露一个**快照读取器**，用于断言"到底写了几行、写了什么"：

```kotlin
internal class FakeChapterListDao : ChapterListDao {
    private val rows = LinkedHashMap<String, ChapterListEntity>()
    /** 按写入顺序返回已存行；测试用它断言批量写的数量与 content_ref */
    fun storedValues(): List<ChapterListEntity> = rows.values.toList()
    // ...其余方法照原实现，写路径落到 rows
}
```

`FakeBookShelfDao` / `FakeBookInfoDao` / `FakeBookGroupDao` 同样各加一个 `storedValues()`。

2. 新增 `FakeBookGroupDao : BookGroupDao`，实现 `insert`（存 map）与 `deleteFor`（按 noteUrl 过滤删除）两个方法即可——它是 Task 10 新 DAO 的 fake。

`BookRepositoryTest` 的 `setUp()` 改为用 `FakeDaos()` 构造，其余断言不动。

- [ ] **Step 4: 给 BookRepository 加本地内容通道**

先在 `lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt` 补上 `localReaders` 的提供（**必须在本任务加**，否则下一步的构造参数无法满足注入、整个模块编译不过）：

```kotlin
    /**
     * 按格式索引的本地来源解析器。
     *
     * M1a 只有 TXT，故直接 mapOf。M3 加 EPUB 时换成多模块 `@IntoMap` + `@ClassKey` 聚合，
     * 那时本函数删除——写进 docs/test-coverage-todo.md 以免留下"两种装配方式并存"。
     */
    @Provides
    @Singleton
    fun provideLocalReaders(txt: TxtSourceReader): Map<BookFormat, SourceReader> =
        mapOf(BookFormat.TXT to txt)
```

（`TxtSourceReader` 在 Task 8 已建，可直接注入。需补 import：`com.ebook.common.analyze.local.{BookFormat, SourceReader, TxtSourceReader}`。）

再改构造参数（保持既有四项的顺序，新参数放后面以免打乱现有调用）：

```kotlin
@Singleton
class BookRepository @Inject constructor(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookContentDao: BookContentDao,
    /** 按格式索引的本地来源解析器。M3 加 EPUB 只是往这个 map 里多塞一项 */
    private val localReaders: Map<BookFormat, SourceReader>,
    private val bookStore: BookStore,
    private val contentCache: ChapterContentCache,
) : BaseModel() {
```

新增读取入口：

```kotlin
    /**
     * 读本地书某一章的正文（spec §7）。
     *
     * 与 [loadBookContent] 的分界是**来源**而不是"缓存有没有"：本地书的正文永远在章文件里，
     * 查 `book_content` 既无意义又会给出假 miss；而旧实现在 miss 时直接去找书源 parser，
     * 于是拿 `md5_章号` 当 URL 发网络请求。这里宁可得 null 也不回落网络。
     *
     * 缓存是必需的：`loadPage` 每翻一页都取一次正文，章文件读出后不缓存就比原来的一次
     * 主键查询更慢（spec §7 r5 补）。
     */
    suspend fun loadLocalChapter(bookShelf: BookShelfEntity, index: Int, title: String): ChapterContent? {
        val format = bookShelf.bookFormat?.let { runCatching { BookFormat.valueOf(it) }.getOrNull() }
            ?: BookFormat.TXT
        val reader = localReaders[format] ?: return null
        val location = BookLocation(bookShelf.noteUrl, format)
        val contentRef = bookStore.chapterRef(bookShelf.noteUrl, index)
        return contentCache.getOrLoad(contentRef) {
            val content = reader.readChapter(
                ChapterEntry(index = index, title = title, contentRef = contentRef),
                location,
            )
            // 空段落 = 章文件不在（被删、导入中断），不当作有效内容缓存进内存
            content.takeIf { it.paragraphs.isNotEmpty() } ?: return@getOrLoad null
        }
    }
```

注：`ChapterContentCache.getOrLoad` 的返回类型因此需允许 null——把它改成 `suspend fun getOrLoad(contentRef: String, loader: suspend () -> ChapterContent?): ChapterContent?`，`entries` 只存非 null 结果。改完 `ChapterContentCacheTest` 的断言不变（它喂的 loader 永不返回 null）。

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.repository.LocalContentReadTest" --tests "com.ebook.common.repository.BookRepositoryTest"`
Expected: 两个测试类都 `BUILD SUCCESSFUL`。若第三个用例（unknownFormat）失败，检查 `bookFormat = "mobi"` 是否被 `?: BookFormat.TXT` 兜住了——`runCatching` 返回 null 后不该再兜 TXT，"格式未知"与"格式列没写"要区分：**格式列真为 null 时兜 TXT**（当前唯一在产的本地格式），**列有值但无对应 reader 时返回 null**。

- [ ] **Step 6: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt \
        lib_book_common/src/main/java/com/ebook/common/store/ChapterContentCache.kt \
        lib_book_common/src/test/java/com/ebook/common/repository/
git commit -m "feat(lib_book_common): 本地书正文改走章文件与内存缓存"
```

---

## Task 13: LocalBookImporter（导入流水线）

spec §6 的落地，也是本轮唯一直接解决"入库超长"的任务。旧实现的 6000 次事务、三遍全文件读、章节表二次全量写全部在这里消失。

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/store/WriteTransactionRunner.kt`
- Create: `lib_book_common/src/main/java/com/ebook/common/importer/LocalBookImporter.kt`
- Create: `lib_book_common/src/test/java/com/ebook/common/importer/LocalBookImporterTest.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt`（补 `group` 一个 fake）

导入器放 `lib_book_common` 而不是 `module_book`：它不需要任何 Android 类型（暂存目录与仓库都是 `File` 参数），而放在 `lib_book_common` 才能与 `SourceReader`、`BookRepository` 同层复用，测试也才能直接拿到该模块已有的 fake DAO——`module_book` 的测试看不见 `lib_book_common` 的测试类。

- [ ] **Step 1: 加事务接缝（让导入器能在 JVM 上测）**

```kotlin
package com.ebook.common.store

/**
 * 一个"把这些写当作一次事务提交"的接缝。
 *
 * 存在的理由有两个：一是全仓业务代码此前零使用事务，需要一个明确的收口而不是每处自己
 * `db.withWriteTransaction`；二是让导入器能在纯 JVM 测试里跑完整个"批量写"路径——直接
 * 依赖 `AppDatabase` 会把这段逻辑锁死在仪器测试里，而它恰恰是本轮最容易写错的地方
 * （提交顺序、原子性）。
 */
interface WriteTransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}
```

Create `lib_book_common/src/main/java/com/ebook/common/di/TransactionModule.kt`:

```kotlin
package com.ebook.common.di

import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.AppDatabase
import androidx.room3.withWriteTransaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** [WriteTransactionRunner] 的生产实现：Room 3 的写事务（IMMEDIATE） */
@Module
@InstallIn(SingletonComponent::class)
object TransactionModule {

    @Provides
    @Singleton
    fun provideWriteTransactionRunner(db: AppDatabase): WriteTransactionRunner =
        object : WriteTransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R = db.withWriteTransaction { block() }
        }
}
```

- [ ] **Step 2: 写失败测试**

```kotlin
package com.ebook.common.importer

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.TxtSourceReader
import com.ebook.common.domain.CommentKey
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.FakeDaos
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LocBookShelfEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [LocalBookImporter] 的端到端测试（纯 JVM，临时目录 + fake DAO + 立即执行的事务接缝）。
 *
 * 锁住五件事，每一件都对应旧实现的一处缺陷：
 * 1. 一次导入只提交**一个**事务（旧实现逐章 2 次、共 6000 次）；
 * 2. 源文件只被完整读**一遍**（拷贝即哈希，旧实现读三遍）；
 * 3. 同一文件重复导入直接命中、**不重解析也不重复写**；
 * 4. 章文件真的按 `books/<md5>/cNNNNN.txt` 落盘，且索引里的 `content_ref` 与之严格一致
 *    （不一致的症状是"导入成功但翻开空白"）；
 * 5. 解码失败时不留半成品目录。
 */
class LocalBookImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var booksRoot: File
    private lateinit var scratch: File
    private lateinit var store: BookStore
    private lateinit var daos: FakeDaos
    private lateinit var importer: LocalBookImporter
    private var txCount = 0

    @Before
    fun setUp() {
        booksRoot = tmp.newFolder("books")
        scratch = tmp.newFolder("scratch")
        store = BookStore(booksRoot)
        daos = FakeDaos()
        importer = importerWith(TxtSourceReader(store))
    }

    /** 用给定 reader 组一个导入器；失败用例靠它换成一写就抛的假 reader */
    private fun importerWith(reader: SourceReader): LocalBookImporter = LocalBookImporter(
        bookStore = store,
        scratchDir = scratch,
        readers = mapOf(BookFormat.TXT to reader),
        bookShelfDao = daos.shelf,
        bookInfoDao = daos.info,
        chapterListDao = daos.chapter,
        bookGroupDao = daos.group,
        transactions = ImmediateTransactionRunner { txCount++ },
        bookRepository = BookRepository(
            daos.shelf, daos.info, daos.chapter, daos.content,
            localReaders = mapOf(BookFormat.TXT to reader),
            bookStore = store,
            contentCache = ChapterContentCache(),
        ),
    )

    @Test
    fun importWritesOneTransactionAndMatchesChapterFiles() = runTest {
        val source = book("第一章 起\n正文甲\n\n第二章 承\n正文乙")

        val result = importer.import(source)

        assertTrue("应为新书", result is LocBookShelfEntity)
        assertEquals("整次导入只该提交一个事务", 1, txCount)
        val noteUrl = (result as LocBookShelfEntity).bookShelf.noteUrl
        val chapters = daos.chapter.storedValues()
        assertEquals(2, chapters.size)
        chapters.forEachIndexed { i, row ->
            assertEquals(store.chapterRef(noteUrl, i), row.contentRef)
            assertTrue(
                "章文件必须存在：c0000$i",
                File(File(booksRoot, noteUrl), "c%05d.txt".format(i)).exists()
            )
        }
    }

    @Test
    fun noteUrlIsContentMd5AndDirNameMatchesIt() = runTest {
        val source = book("第一章 起\n正文甲")

        val shelf = (importer.import(source) as LocBookShelfEntity).bookShelf

        assertEquals(32, shelf.noteUrl.length)
        assertTrue(shelf.noteUrl.all { it in "0123456789abcdef" })
        assertTrue("提交后的目录名就是 md5", File(booksRoot, shelf.noteUrl).isDirectory)
        assertFalse("暂存目录必须已被改名掉", File(booksRoot, "${shelf.noteUrl}.tmp").exists())
    }

    @Test
    fun reimportingSameFileShortCircuitsWithoutReparse() = runTest {
        val source = book("第一章 起\n正文甲")
        val first = importer.import(source) as LocBookShelfEntity

        txCount = 0
        val second = importer.import(source)

        assertFalse("重复导入不该再开事务", second.new)
        assertEquals(first.bookShelf.noteUrl, (second as LocBookShelfEntity).bookShelf.noteUrl)
        assertEquals(0, txCount)
    }

    @Test
    fun titleAndAuthorComeFromFileNameAndGoIntoBookInfo() = runTest {
        val source = book("第一章 起\n正文甲", name = "《星辰变》作者：我吃西红柿.txt")

        val shelf = (importer.import(source) as LocBookShelfEntity).bookShelf
        val info = daos.info.storedValues().single()

        assertEquals("星辰变", info.name)
        assertEquals("我吃西红柿", info.author)
        assertEquals(BookShelfEntity.LOCAL_TAG, shelf.tag)
        assertEquals(BookFormat.TXT.name, shelf.bookFormat)
    }

    @Test
    fun bookGroupRowIsWrittenWithDerivedCommentKey() = runTest {
        val source = book("第一章 起\n正文甲", name = "剑来 作者：烽火戏诸侯.txt")

        val shelf = (importer.import(source) as LocBookShelfEntity).bookShelf
        val row = daos.group.storedValues().single()

        assertEquals(shelf.noteUrl, row.noteUrl)
        assertTrue(row.isPrimary)
        assertEquals(
            com.ebook.common.domain.CommentKey.compute("剑来", "烽火戏诸侯"),
            row.commentKey
        )
    }

    @Test
    fun failureMidSplitLeavesNoPartialBookAndNoRows() = runTest {
        // 前提要可靠：与其赌"这三个字节会被探测成什么编码"，不如直接注入一个写到一半就抛的
        // reader —— 要验的是"失败不留半成品"这条提交顺序保证，不是解码本身（解码已在 Task 3 锁）
        val failing = FailingReader(store, failAfter = 2)
        val importer = importerWith(failing)
        val source = book("第一章\n甲\n第二章\n乙\n第三章\n丙\n第四章\n丁")

        val outcome = runCatching { importer.import(source) }

        assertTrue("应报错而不是产出一本坏书", outcome.isFailure)
        assertEmptyDir(booksRoot)
        assertEquals("不该写进任何章节行", 0, daos.chapter.storedValues().size)
        assertEquals("不该写进书架行", 0, daos.shelf.storedValues().size)
    }

    private fun book(content: String, name: String = "样本书.txt"): File =
        File(scratch, name).apply { writeText(content, Charsets.UTF_8) }

    private fun assertEmptyDir(dir: File) {
        assertEquals("$dir 应为空", 0, dir.list()?.size ?: 0)
    }
}
```

`FailingReader` 与 `ImmediateTransactionRunner` 放在同一测试文件底部：

```kotlin
/** 写完 [failAfter] 章就抛错的 reader，用来验证导入不是"边写边提交" */
private class FailingReader(
    private val store: BookStore,
    private val failAfter: Int,
) : SourceReader {
    override suspend fun readMetadata(source: BookSourceFile) = LocalBookMeta("t", null, null)

    override fun buildChapters(source: BookSourceFile, sink: ChapterSink): Flow<ChapterEntry> = flow {
        repeat(failAfter + 1) { i ->
            if (i == failAfter) throw IOException("模拟解码失败")
            val ref = sink.write(i, listOf("段落$i"))
            emit(ChapterEntry(i, "第${i + 1}章", ref))
        }
    }

    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation) =
        ChapterContent(entry.title, emptyList())
}

/** 不做真事务、只计数并原样执行 block 的事务接缝 */
internal class ImmediateTransactionRunner(private val onBegin: () -> Unit) : WriteTransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R {
        onBegin()
        return block()
    }
}
```

对应的 import 需补：`com.ebook.common.analyze.local.{BookLocation, BookSourceFile, ChapterContent, ChapterEntry, ChapterSink, LocalBookMeta, SourceReader}`、`com.ebook.common.store.WriteTransactionRunner`、`kotlinx.coroutines.flow.Flow`、`kotlinx.coroutines.flow.flow`、`java.io.IOException`。

- [ ] **Step 3: 给 SourceReader 补一个编码探测钩子**

`probeCharset` 是格式相关的能力（TXT 要猜、EPUB 自带声明），放进接口比让导入器按格式 `if` 干净。在 `Contracts.kt` 的 `SourceReader` 里加：

```kotlin
    /** 探测源文件编码。TXT 需要猜，容器格式自带声明，故默认 UTF-8 */
    fun probeCharset(file: File): String = "UTF-8"
```

`TxtSourceReader` 里把 companion 的 `probeCharset(file)` 改成 `override fun probeCharset(file: File): String`（实现体不变，从 companion 移到类内）。

- [ ] **Step 4: 给 BookRepository 补 publishAdded**

```kotlin
    /**
     * 只发"已加入书架"事件、不重复写任何表。
     *
     * 存在的理由：[addToShelf] 会级联写 book_info / book_shelf / chapter_list，而导入器已经
     * 在自己的事务里写完这些了——旧实现导入后仍调 `addToShelf`，把 N 条章节行又 REPLACE 了
     * 一遍（`BookImportViewModel.kt:91`）。事件是书架刷新与"换源"提示的唯一依据，不能省，
     * 所以把"发事件"从"写数据"里拆出来。
     */
    suspend fun publishAdded(bookShelf: BookShelfEntity) {
        _bookShelfEvents.emit(BookShelfEvent.Added(bookShelf))
    }
```

- [ ] **Step 5: 实现 LocalBookImporter**

```kotlin
package com.ebook.common.importer

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.BookSourceFile
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterSink
import com.ebook.common.analyze.local.SourceReader
import com.ebook.common.domain.CommentKey
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.BookStore
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.LocBookShelfEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.DigestInputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地书籍导入流水线（spec §6）。
 *
 * 三步一次成型：**拷贝即哈希**（源文件只完整读一遍，旧实现读三遍）→ 后台切分落章文件 →
 * **一个事务**批量写索引（旧实现逐章 2 次、2000 章就是 4000 次提交）。
 *
 * 提交顺序是"先改名章文件目录、后写数据库"，反过来的话一旦数据库写入失败就会留下指向
 * 不存在目录的索引行——用户看到的是"书架上有本书但翻开空白"，比反过来那种"孤儿目录"
 * 更难解释也更难回收（孤儿目录由 [BookStore.reconcile] 静默清掉即可）。
 */
@Singleton
class LocalBookImporter @Inject constructor(
    private val bookStore: BookStore,
    /** 源文件暂存目录；生产注入 `cacheDir/import`，测试注入临时目录 */
    private val scratchDir: File,
    private val readers: Map<BookFormat, SourceReader>,
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookGroupDao: BookGroupDao,
    private val transactions: WriteTransactionRunner,
    private val bookRepository: BookRepository,
) {

    /**
     * @param onChapter 每落一章回调一次，供 UI 显示进度（spec §6 要求逐章进度而非布尔遮罩）
     * @return `new = false` 表示同一份内容已在书架上，此时不产生任何写入
     */
    suspend fun import(source: File, onChapter: (chaptersDone: Int) -> Unit = {}): LocBookShelfEntity =
        withContext(Dispatchers.IO) {
            val format = BookFormat.fromExtension(source.extension.substringAfter('.', ""))
                ?: throw IllegalArgumentException("不支持的本地书格式：${source.extension}")
            val reader = readers[format]
                ?: throw IllegalStateException("没有 $format 的解析器，可用格式：${readers.keys}")

            val staged = File(scratchDir, "src-${System.nanoTime()}.${format.extension}")
            staged.parentFile?.mkdirs()
            try {
                val md5 = copyAndHash(source, staged)
                val existing = bookShelfDao.getBookByUrl(md5)
                if (existing != null) {
                    // chapterList/bookInfo 是 @Ignore 字段，不回填会让阅读器算出 0 页（旧实现同样的理由）
                    existing.chapterList = chapterListDao.getChaptersForBook(existing.noteUrl)
                    existing.bookInfo = bookInfoDao.getBookInfoByUrl(existing.noteUrl)
                    return@withContext LocBookShelfEntity(false, existing)
                }

                val charset = reader.probeCharset(staged)
                val meta = reader.readMetadata(BookSourceFile(staged, charset))
                val staging = bookStore.beginImport(md5)
                val chapters = try {
                    reader.buildChapters(BookSourceFile(staged, charset), sink(staging, md5))
                        .onEach { onChapter(it.index + 1) }
                        .toList()
                        .map { it.toRow(md5) }
                } catch (t: Throwable) {
                    bookStore.abortImport(staging)
                    throw t
                }
                if (chapters.isEmpty()) {
                    bookStore.abortImport(staging)
                    throw IllegalStateException("未能从 ${source.name} 切出任何章节")
                }

                bookStore.commitImport(staging, md5)
                val shelf = BookShelfEntity(
                    noteUrl = md5,
                    tag = BookShelfEntity.LOCAL_TAG,
                    finalDate = System.currentTimeMillis(),
                    bookFormat = format.name,
                    textCharset = charset,
                )
                val info = BookInfoEntity(
                    name = meta.title,
                    tag = BookShelfEntity.LOCAL_TAG,
                    noteUrl = md5,
                    chapterUrl = String(),
                    finalRefreshData = System.currentTimeMillis(),
                    coverUrl = String(),
                    author = meta.author ?: DEFAULT_AUTHOR,
                    introduce = String(),
                    origin = String(),
                    status = String(),
                )
                val group = BookGroupEntity(
                    commentKey = CommentKey.compute(info.name, info.author),
                    noteUrl = md5,
                    isPrimary = true,
                )
                try {
                    transactions.run {
                        bookShelfDao.insert(shelf)
                        bookInfoDao.insert(info)
                        chapterListDao.insertAll(chapters)
                        bookGroupDao.insert(group)
                    }
                } catch (t: Throwable) {
                    // 库写不进去就把章文件一并撤掉，别在磁盘上留一本"库里不存在"的书
                    bookStore.deleteBook(BookLocation(md5, format))
                    throw t
                }

                shelf.chapterList = chapters
                shelf.bookInfo = info
                bookRepository.publishAdded(shelf)
                LocBookShelfEntity(true, shelf)
            } finally {
                staged.delete()
            }
        }

    /** 往暂存目录写章文件，返回的是**改名后**的 content_ref（暂存目录只是物理位置） */
    private fun sink(staging: File, bookId: String) = object : ChapterSink {
        override suspend fun write(index: Int, paragraphs: List<String>): String {
            bookStore.writeChapterRaw(staging, index, paragraphs.joinToString("\n"))
            return bookStore.chapterRef(bookId, index)
        }
    }

    private fun ChapterEntry.toRow(noteUrl: String) = ChapterListEntity(
        noteUrl = noteUrl,
        durChapterIndex = index,
        contentRef = contentRef,
        durChapterName = title,
        tag = BookShelfEntity.LOCAL_TAG,
        // 本地书不走 book_content 缓存，has_cache 对它是无意义列（M1b 连同表一起删）
        hasCache = false,
    )

    /** 单遍流式拷贝并顺手算 MD5：省掉旧实现那次独立的全文件读 */
    private fun copyAndHash(from: File, to: File): String {
        val digest = MessageDigest.getInstance("MD5")
        from.inputStream().buffered(BUFFER_BYTES).use { input ->
            DigestInputStream(input, digest).use { digested ->
                to.outputStream().buffered(BUFFER_BYTES).use(digested::copyTo)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        /** 仅用于显示；不参与 comment_key 计算（见 CommentKey 的占位词处理） */
        const val DEFAULT_AUTHOR = "侠名"
    }
}
```

- [ ] **Step 6: 注入暂存目录**

`provideLocalReaders` 已在 Task 12 Step 4 就位。本步只加源文件暂存目录：

```kotlin
    /** 导入期源文件的落点：把 Uri 变成 File 以便复用同一条"拷贝即哈希"流水线 */
    @Provides
    @Singleton
    fun provideImportScratchDir(@ApplicationContext context: Context): File =
        File(context.cacheDir, "import")
```

`LocalBookImporter` 直接注入这个 `File` 会让 Hilt 找不到"哪个 File"的区分（将来还有别的目录参数时），因此实现时给它加一个 `@Qualifier` 注解 `@ImportScratch`，`LocalBookImporter` 构造参数同步标注。

- [ ] **Step 7: 跑测试确认通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.importer.LocalBookImporterTest"`
Expected: `BUILD SUCCESSFUL`，6 个测试通过，其中 `importWritesOneTransactionAndMatchesChapterFiles` 断言 `txCount == 1`——这条是本轮性能改动的**结构性证明**，如果它是 2 或更大，说明还有写落在事务外。

- [ ] **Step 8: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/ \
        lib_book_common/src/test/java/com/ebook/common/importer/
git commit -m "feat(lib_book_common): 新增单次事务的本地书导入流水线"
```

---

## Task 14: 导入调用方与进度反馈

旧实现前台只有一个布尔遮罩、`importBooks` 里还调 `addToShelf` 造成章节表二次全量写（`BookImportViewModel.kt:91`）。本任务把它换成调新导入器并给出逐本进度。

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/repository/BookImportRepository.kt`
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookImportViewModel.kt`
- Modify: `module_book/src/main/java/com/ebook/book/ImportBookActivity.kt`（`:107`、`:256-264`、`:452-456`）

- [ ] **Step 1: BookImportRepository 改走新导入器**

```kotlin
/**
 * 书籍导入仓库：唯一入口是 [LocalBookImporter]。
 *
 * 不再在这里做 Uri/文件路径转换以外的任何事——旧实现把解析与写库都塞在
 * `BookImportManager` 里，导致那段逻辑既不能单测也没法被别的调用方（外部打开、批量导入）复用。
 */
@Singleton
class BookImportRepository @Inject constructor(
    private val application: Application,
    private val importer: LocalBookImporter,
) : BaseModel() {

    /** 从 SAF/文件选择器得到的 Uri 导入：先落到 cacheDir 再走统一流水线 */
    suspend fun import(uri: Uri, onChapter: (Int) -> Unit = {}): LocBookShelfEntity {
        val staged = File(application.cacheDir, "pickup-${System.nanoTime()}.txt")
        application.contentResolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("无法打开源文件：$uri")
        try {
            return importer.import(staged, onChapter)
        } finally {
            staged.delete()
        }
    }

    suspend fun import(file: File, onChapter: (Int) -> Unit = {}): LocBookShelfEntity =
        importer.import(file, onChapter)
}
```

（`importer.import` 自己会把源文件再拷进 `scratchDir` 并算哈希——这里的落盘只为把 `Uri` 变成 `File`，两处拷贝不重复做哈希。）

- [ ] **Step 2: ViewModel 换成导入器 + 进度流**

`BookImportViewModel` 的 `importBooks` 整体替换为：

```kotlin
    /** 导入进度：0..books.size，0 表示未在导入 */
    val importProgress = MutableStateFlow(0)

    /**
     * 批量导入。
     *
     * 与旧实现的两处关键差别：①不再调 `bookRepository.addToShelf`——导入器已在自己的事务里
     * 写完三张表，再调一次会把 N 条章节行全量 REPLACE 一遍；②进度是真实计数，不再只有一个
     * 布尔遮罩。整批在 `Dispatchers.IO` 上跑，UI 线程不参与解析。
     */
    fun importBooks(books: List<File>) {
        if (books.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            importProgress.value = 0
            var failCount = 0
            books.forEachIndexed { i, file ->
                val outcome = runCatching { importer.import(file) }
                if (outcome.isFailure) {
                    Logger.e(TAG, "导入失败: ${file.name}", outcome.exceptionOrNull())
                    failCount++
                }
                importProgress.value = i + 1
            }
            importProgress.value = 0
            if (failCount == 0) addSuccessEvent.tryEmit(Unit)
            else {
                addErrorEvent.tryEmit(Unit)
                sendToast(context.getString(R.string.import_result_format, books.size - failCount, failCount))
            }
        }
    }
```

构造参数把 `bookRepository: BookRepository` 换成 `private val importer: LocalBookImporter`（进度回调不需要单独传，导入器内部 `onChapter` 已有，UI 只消费"第几本"这一层粒度）。`importing: Boolean` 遮罩改为观察 `importProgress`。

- [ ] **Step 3: Activity 显示进度**

`ImportBookActivity` 的 `LoadingView("放入书架中...")`（`:452-456`）替换为观察 `importProgress` 的文本：

```kotlin
    // 进度是"第几本"而不是百分比：单本内部还有逐章计数，但章数在导入前未知，
    // 百分比会跳变。"正在导入 3/12"是可稳定兑现的承诺。
    val progress by viewModel.importProgress.collectAsStateWithLifecycle()
    if (progress > 0) {
        Text(
            text = stringResource(R.string.importing_progress, progress, selectedCount),
            modifier = Modifier.align(Alignment.Center)
        )
    }
```

`module_book/src/main/res/values/strings.xml` 加：

```xml
<string name="importing_progress">正在导入 %1$d/%2$d…</string>
```

- [ ] **Step 4: 编译并跑全量单测**

Run: `./gradlew :module_book:assembleDebug test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 人工验证并记录**（人工）

书架 → 右上"+" → 勾选 3 本以上 TXT → 加入书架。要看：①进度文本按本递增；②界面不冻结（期间可以返回、滚动）；③完成后书架出现全部书且显示"解析中"到正常的过渡；④点开每本都能翻页；⑤`adb logcat -b crash` 无 FATAL EXCEPTION。

- [ ] **Step 6: 提交**

```bash
git add module_book/
git commit -m "feat(module_book): 导入改走新流水线并显示逐本进度"
```

---

## Task 15: 阅读器内容路由与排版偏移缓存

spec §7 的两条：本地书取内容不再可能误发网络请求；每页整章重排的既有放大开销顺手治掉。

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt:260-325`
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt:96-128`
- Create: `module_book/src/main/java/com/ebook/book/reader/ChapterLayoutCache.kt`
- Test: `module_book/src/test/java/com/ebook/book/reader/ChapterLayoutCacheTest.kt`

- [ ] **Step 1: 写 ChapterLayoutCache 的失败测试**

```kotlin
package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排版偏移缓存的测试。锁的是**失效键**：字号或宽度变了必须重算，否则页数与折行会
 * 与当前样式不符（`ReaderTypesetter` 的 KDoc 记录过这类"被裁掉的一行"缺陷）。
 */
class ChapterLayoutCacheTest {

    @Test
    fun sameKeyComputesOnce() {
        val cache = ChapterLayoutCache(capacity = 4)
        var calls = 0
        val key = ChapterLayoutKey("ref", 42f, 800)

        repeat(3) { cache.getOrCompute(key) { calls++; listOf(0, 5) } }

        assertEquals(1, calls)
    }

    @Test
    fun styleChangeIsANewKey() {
        val cache = ChapterLayoutCache(capacity = 4)
        var calls = 0

        cache.getOrCompute(ChapterLayoutKey("ref", 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("ref", 50f, 800)) { calls++; listOf(0) }

        assertEquals("字号变了要重算", 2, calls)
    }

    @Test
    fun bookInvalidationDropsAllItsChapters() {
        val cache = ChapterLayoutCache(capacity = 8)
        var calls = 0
        cache.getOrCompute(ChapterLayoutKey("books/A/c00000.txt", 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("books/B/c00000.txt", 42f, 800)) { calls++; listOf(0) }

        cache.invalidateBook("A")
        cache.getOrCompute(ChapterLayoutKey("books/A/c00000.txt", 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("books/B/c00000.txt", 42f, 800)) { calls++; listOf(0) }

        assertEquals(3, calls)
    }
}
```

- [ ] **Step 2: 实现**

```kotlin
package com.ebook.book.reader

/** 缓存键：同一章 + 同一字号 + 同一正文宽度才算同一份排版结果 */
data class ChapterLayoutKey(val contentRef: String, val fontSizeSp: Float, val widthPx: Int)

/**
 * 「整章排版偏移」的内存缓存（spec §7）。
 *
 * `ReaderTypesetter.lineStartOffsets` 是 O(章长) 且**每页都调一次**——同章 N 页即 N 次整章
 * 重排，该方法的 KDoc 自己承认了这点。缓存后同章翻页只重排一次。
 *
 * 键里必须带字号与宽度：漏掉就会在用户改字号后拿到旧偏移，表现为"页数没变但内容接不上"
 * 这种极难定位的错乱。
 */
class ChapterLayoutCache(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = object : LinkedHashMap<ChapterLayoutKey, List<Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChapterLayoutKey, List<Int>>): Boolean =
            size > capacity
    }

    fun getOrCompute(key: ChapterLayoutKey, computer: () -> List<Int>): List<Int> =
        entries.getOrPut(key) { computer() }

    fun invalidateBook(bookId: String) {
        val marker = "/$bookId/"
        entries.keys.filter { it.contentRef.contains(marker) }.toList().forEach { entries.remove(it) }
    }

    fun clear() = entries.clear()

    private companion object {
        /** 约覆盖"当前章 + 前后各两章"的两屏余量 */
        const val DEFAULT_CAPACITY = 5
    }
}
```

线程安全说明：`loadPage` 会**并发**跑（预加载上一页与下一页，见 `ReaderPagerController`），而本类刻意不加锁——`LinkedHashMap` 在并发写下的风险是结构损坏，不是"值旧一帧"。因此实现时必须二选一并写进 KDoc：改成 `Collections.synchronizedMap` / 加 `Mutex`（与 `ChapterContentCache` 一致），或者把整章偏移的计算收敛到单线程（`Dispatchers.Unconfined` 不行，用一个 dedicated `Actor`/单线程 dispatcher）。**不要留着裸 map 就提交。**

- [ ] **Step 3: loadPage 按来源分叉**

`ReadBookActivity.loadPage` 里把"取内容"那一段（`:267-279`）替换为：

```kotlin
            // 取正文：本地书一律走章文件（含内存缓存），**绝不查 book_content**；
            // 网络书保持"查缓存 → miss 则抓 → 回填"的原结构不变。
            val chapterText: String? = if (bookShelf.tag == BookShelfEntity.LOCAL_TAG) {
                viewModel.loadLocalChapter(chapter)?.displayText
            } else {
                var bookContent = viewModel.loadBookContent(chapter.contentRef)
                if (bookContent == null || bookContent.durChapterContent.isEmpty()) {
                    bookContent = viewModel.fetchBookContent(chapter.contentRef, chapterIndex)
                    if (bookContent.durChapterContent.isNotEmpty()) {
                        viewModel.saveBookContent(bookContent)
                        viewModel.updateChapterCache(chapter.contentRef, true)
                    }
                }
                bookContent.durChapterContent.ifEmpty { null }
            }
            if (chapterText.isNullOrEmpty()) return null
```

并把下面用到 `bookContent.durChapterContent` 的两处（`:290` 的 `val content = ...`）改为 `val content = chapterText`，`title` 仍取 `chapter.durChapterName`。

排版改走缓存（替换 `:287-293` 那段"每页整章重排"）：

```kotlin
            val width = readerContentWidthPx
            if (width <= 0) return null
            // 排版结果按（章, 字号, 宽度）缓存：同章翻页不再整章重排（见 ChapterLayoutCache）
            val layoutKey = ChapterLayoutKey(
                contentRef = chapter.contentRef,
                fontSizeSp = ReadBookControl.getInstance().textSize,
                widthPx = width,
            )
            val lineStarts = withContext(Dispatchers.Default) {
                layoutCache.getOrCompute(layoutKey) { typesetter.lineStartOffsets(content, width) }
            }
```

`ReadBookActivity` 增加 `private val layoutCache = ChapterLayoutCache()` 字段；`fontSizeSp` **直接取 `ReadBookControl.textSize`**——它正是 `rememberReaderTypesetter(textSizeSp, lineHeight)` 的入参来源。缓存键与排版器必须读同一个值，否则"键没变但样式变了"会静默返回旧的整章偏移，表现为改字号后页数不动。`widthPx` 同理取 `readerContentWidthPx`。

- [ ] **Step 4: ViewModel 加本地读取入口**

`BookReadViewModel` 加：

```kotlin
    /** 读本地书某章正文；缺失返回 null，**不会**回落网络（旧实现的 `md5_章号 当 URL` 缺陷即此） */
    suspend fun loadLocalChapter(chapter: ChapterListEntity): ChapterContent? =
        bookShelf?.let { bookRepository.loadLocalChapter(it, chapter.durChapterIndex, chapter.durChapterName) }
```

- [ ] **Step 5: 跑单测与编译**

Run: `./gradlew :module_book:testDebugUnitTest --tests "com.ebook.book.reader.ChapterLayoutCacheTest" :module_book:assembleDebug`
Expected: 通过

- [ ] **Step 6: 人工验证**（人工）

打开一本刚导入的本地书 → 连翻 10 页 → 改字号 → 再翻几页 → 改回。要看：内容与页序连续无缺失、改字号后页数正确重算、`adb logcat` 里**没有任何对第三方书源域名的请求**（这一步是那个误发网络缺陷的验收）。

- [ ] **Step 7: 提交**

```bash
git add module_book/
git commit -m "perf(module_book): 本地书正文走章文件并缓存整章排版偏移"
```

---

## Task 16: 删除旧实现与文档同步

**Files:**
- Delete: `module_book/src/main/java/com/ebook/book/util/BookImportManager.kt`
- Modify: `AGENTS.md`、`CONTEXT.md`、`docs/test-coverage-todo.md`、`docs/superpowers/specs/2026-09-04-local-book-import-design.md`

- [ ] **Step 1: 确认无引用后删除**

Run: `grep -rn "BookImportManager" --include=*.kt . | grep -v "/build/"`
Expected: 只剩 `module_book/.../util/BookImportManager.kt` 自身；若有其他引用，先按语义迁到 `LocalBookImporter`/`BookStore` 再删文件。

```bash
git rm module_book/src/main/java/com/ebook/book/util/BookImportManager.kt
```

- [ ] **Step 2: 改 `AGENTS.md`**

三处：
1. 「模块架构」与「核心架构模式」里凡提到 `book_content` / `has_cache` 作为"缓存事实源"的地方，补一句**本地书正文已改走 `filesDir/books/<md5>/cNNNNN.txt`，`book_content` 仅服务网络书（M1b 删除）**。
2. Room 那条"改实体必须接迁移链"里的当前版本从 `version = 2` 改为 `version = 3`，迁移链补 `DatabaseModule.MIGRATION_2_3`。
3. 「Agent 实战建议」新增一条：*涉及本地书籍导入或章节正文读取时，先读 `docs/superpowers/specs/2026-09-04-local-book-import-design.md`；本地书正文不在数据库里，改内容路径要同时看 `BookStore`、`SourceReader` 与 `ChapterContentCache` 三处。*

- [ ] **Step 3: 改 `CONTEXT.md`（领域术语）**

「内容存储」一节新增：

```markdown
**内容仓库（Content Store）**:
应用私有目录里为一本书保留的全部章文件，目录名即该书的内容 md5。本地书的**唯一**正文归宿。
_Avoid_: 缓存目录、书目录

**章节索引（Chapter Index）**:
一本书"有哪些章、每章在哪"的列表，落库为 `chapter_list`；本地书条目指向章文件路径，
网络书条目指向章节 URL。
_Avoid_: 目录（与文件系统目录混淆）

**作品（Work）**:
跨来源的同一部书。只以客户端派生的不透明键 `comment_key` 存在，**没有**作品表也没有作品 ID。
_Avoid_: 书目、书籍聚合

**评论标识键（comment_key）**:
`ck1:` + 归一化书名与作者的 sha256，评论桶键。后端只按其过滤、不解释、不校验。
_Avoid_: 书 ID、work_key
```

并改写既有的「章节缓存」条目：删去"本地持久化的章节内容"里含本地书的暗示，明确它现在只是**网络书**的正文缓存。

- [ ] **Step 4: 更新 spec 的落地状态**

在 spec 文末追加：

```markdown
## 15. M1a 落地状态

M1a 已实现（见 docs/superpowers/plans/2026-09-04-local-book-content-base-m1a.md）：
正文出 DB 进章文件、SourceReader 接缝、导入流水线、章节缓存、content_ref 改名与 Room v3。
M1b 未做：`book_content` 与 `has_cache` 仍在（服务网络书），两种基座并存的窗口期从此起算。
实测性能数字见 §6。
```

- [ ] **Step 5: 全量验证**

Run: `./gradlew clean test :module_app:assembleRealDebug lint`
Expected: 构建成功、单测全绿、lint 无新增警告。
`gradle.properties` 必须是 `isModule=false`（AGENTS.md 的提交态要求）。

- [ ] **Step 6: 未验证项交代（禁止省略）**

Agent 到此**只**验证了编译与 JVM 单测。以下必须由人工在设备上确认后才算 M1a 完成：Room v2→v3 覆盖安装（Task 11 六项）、导入进度与不冻结（Task 14 五项）、本地书翻页连续性与无网络请求（Task 15 一项）、`filesDir/books` 目录实际形态。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "docs(all): 同步本地书内容基座改动并移除旧导入实现"
```

---

## 完成定义

M1a 视为完成需要同时满足：`./gradlew test` 全绿且无新增警告；`LocalBookImporterTest` 里 `txCount == 1` 通过；真机基线对比数字已回填 spec §6；Task 11/14/15 的人工验证项全部由人确认通过。


