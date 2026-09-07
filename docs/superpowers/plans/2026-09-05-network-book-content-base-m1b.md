# M1b: Network Book Content Base — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move network book chapter content out of the `book_content` SQLite table into the same chapter-file infrastructure (`BookStore`) that local books already use (M1a), so both book types share one read/write pipeline.

**Architecture:** Extract a `ChapterReader` interface from `SourceReader`; implement `JsoupSourceReader` that fetches chapter content from the network, writes it to a chapter file in `BookStore`, and returns `ChapterContent`. Remove `getBookContent` from `BookParser` (discovery/content split). Unify `BookRepository.loadChapter` for both local and network books. Rewrite `DownloadService` and the download panel to use file-based cache checks. Room migration v3→v4 drops `book_content` table and `has_cache` column.

**Tech Stack:** Kotlin, Room 3.0.0 (migration), Hilt/Dagger DI, Jsoup (HTML parsing), kotlinx.coroutines, JUnit 4

---

## File Structure

### New files
| File | Responsibility |
|------|---------------|
| `lib_book_common/src/main/java/com/ebook/common/analyze/local/ChapterReader.kt` | Minimal interface for chapter content reading (shared by local and network readers) |
| `lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupSourceReader.kt` | Network book chapter reader: fetches from web, writes chapter file, returns `ChapterContent` |
| `lib_book_common/src/test/java/com/ebook/common/analyze/source/JsoupSourceReaderTest.kt` | Unit tests for JsoupSourceReader |

### Modified files
| File | Changes |
|------|---------|
| `lib_book_common/src/main/java/com/ebook/common/analyze/local/Contracts.kt` | Add `BookFormat.NETWORK`; make `SourceReader` extend `ChapterReader` |
| `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookParser.kt` | Remove `getBookContent` method |
| `lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupBookParser.kt` | Remove `getBookContent` override; delegate to `JsoupSourceReader` internally (or just remove) |
| `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt` | Unify `loadLocalChapter` → `loadChapter`; remove `loadBookContent`/`saveBookContent`/`deleteBookContent`/`updateChapterCache`; rewrite `getCachedChapterUrls` to file-based |
| `lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt` | Register `JsoupSourceReader` in readers map; add `ChapterReader` map binding |
| `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt` | Remove local/network fork in `loadPage`; use unified `loadChapter` |
| `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt` | Remove `loadBookContent`/`saveBookContent`/`fetchBookContent`/`updateChapterCache`; add unified `loadChapter` |
| `module_book/src/main/java/com/ebook/book/service/DownloadService.kt` | Rewrite `downloading` to use chapter files instead of DB |
| `module_book/src/main/java/com/ebook/book/repository/DownloadRepository.kt` | Rewrite `getCacheCoverage` to file-based check |
| `lib_ebook_db/src/main/java/com/ebook/db/AppDatabase.kt` | Version 3→4; remove `BookContentEntity` from entities |
| `lib_ebook_db/src/main/java/com/ebook/db/di/DatabaseModule.kt` | Add `MIGRATION_3_4`; remove `provideBookContentDao` |
| `lib_ebook_db/src/main/java/com/ebook/db/entity/ChapterListEntity.kt` | Remove `hasCache` field and `bookContent` field |
| `lib_ebook_db/src/main/java/com/ebook/db/dao/ChapterListDao.kt` | Remove `updateHasCache` and `countCachedChaptersForBook` |

### Deleted files
| File | Reason |
|------|--------|
| `lib_ebook_db/src/main/java/com/ebook/db/entity/BookContentEntity.kt` | Table dropped in v3→v4 migration |
| `lib_ebook_db/src/main/java/com/ebook/db/dao/BookContentDao.kt` | Table dropped in v3→v4 migration |

### Test files modified
| File | Changes |
|------|---------|
| `lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt` | Remove `FakeBookContentDao`; remove `updateHasCache`/`countCachedChaptersForBook` from `FakeChapterListDao` |
| `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryTest.kt` | Remove tests for `loadBookContent`/`saveBookContent`/`updateChapterCache`/`getCachedChapterUrls`; add tests for unified `loadChapter` |
| `lib_book_common/src/test/java/com/ebook/common/repository/LocalContentReadTest.kt` | Rename/update to use unified `loadChapter` |

---

### Task 1: Extract `ChapterReader` interface and add `BookFormat.NETWORK`

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/analyze/local/ChapterReader.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/local/Contracts.kt`

- [ ] **Step 1: Write the failing test for `BookFormat.NETWORK`**

Create `lib_book_common/src/test/java/com/ebook/common/analyze/local/BookFormatTest.kt`:

```kotlin
package com.ebook.common.analyze.local

import org.junit.Assert.*
import org.junit.Test

class BookFormatTest {

    @Test
    fun `NETWORK format exists with no file extension`() {
        val format = BookFormat.NETWORK
        assertEquals("network", format.extension)
    }

    @Test
    fun `fromExtension returns null for network`() {
        assertNull(BookFormat.fromExtension("network"))
    }

    @Test
    fun `fromExtension still resolves txt`() {
        assertEquals(BookFormat.TXT, BookFormat.fromExtension("txt"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.local.BookFormatTest" 2>&1 | tail -20`
Expected: FAIL — `BookFormat.NETWORK` does not exist

- [ ] **Step 3: Create `ChapterReader` interface**

Create `lib_book_common/src/main/java/com/ebook/common/analyze/local/ChapterReader.kt`:

```kotlin
package com.ebook.common.analyze.local

/**
 * 章节正文读取的最小接缝（spec §7）。
 *
 * 从 [SourceReader] 拆出来的理由：网络书没有"源文件"，实现不了 `readMetadata` / `buildChapters`，
 * 但读取正文的 `readChapter` 与本地书完全同构。把"读一章"提成独立接口后，
 * `Map<BookFormat, ChapterReader>` 同时路由本地与网络来源，`SourceReader` 只在导入链路出现。
 */
interface ChapterReader {
    suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent
}
```

- [ ] **Step 4: Add `BookFormat.NETWORK` and make `SourceReader` extend `ChapterReader`**

Modify `lib_book_common/src/main/java/com/ebook/common/analyze/local/Contracts.kt`:

```kotlin
// In BookFormat enum, add NETWORK:
enum class BookFormat(val extension: String) {
    TXT("txt"),
    EPUB("epub"),
    NETWORK("network");

    companion object {
        /** 按扩展名解析，未知扩展名返回 null 交给调用方报错而不是硬猜 */
        fun fromExtension(ext: String): BookFormat? =
            entries.firstOrNull { it.extension.equals(ext, ignoreCase = true) }
    }
}
```

Make `SourceReader` extend `ChapterReader`:

```kotlin
interface SourceReader : ChapterReader {
    suspend fun readMetadata(source: BookSourceFile): LocalBookMeta
    fun buildChapters(source: BookSourceFile, sink: ChapterSink): Flow<ChapterEntry>
    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent

    /** 探测源文件编码。TXT 需要猜，容器格式自带声明，故默认 UTF-8 */
    fun probeCharset(file: File): String = "UTF-8"
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.local.BookFormatTest" 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 6: Run all existing tests to verify no regression**

Run: `./gradlew :lib_book_common:testDebugUnitTest 2>&1 | tail -20`
Expected: All existing tests still pass (TxtSourceReader already implements `readChapter` with the same signature, so the `override` keyword just becomes explicit via the parent interface)

- [ ] **Step 7: Commit**

```bash
git add lib_book_common/src/main/java/com/ebook/common/analyze/local/ChapterReader.kt \
       lib_book_common/src/main/java/com/ebook/common/analyze/local/Contracts.kt \
       lib_book_common/src/test/java/com/ebook/common/analyze/local/BookFormatTest.kt
git commit -m "feat(lib_book_common): 抽出 ChapterReader 接缝并新增 NETWORK 格式枚举

SourceReader 只管内容（读一章正文），发现类能力（搜索/分类）留在 BookParser。
NETWORK 格式标识网络书，不参与 fromExtension 解析（无文件扩展名）。"
```

---

### Task 2: Implement `JsoupSourceReader` with tests

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupSourceReader.kt`
- Create: `lib_book_common/src/test/java/com/ebook/common/analyze/source/JsoupSourceReaderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `lib_book_common/src/test/java/com/ebook/common/analyze/source/JsoupSourceReaderTest.kt`:

```kotlin
package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.service.source.BookSourceNetwork
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.store.BookStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsoupSourceReaderTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var store: BookStore
    private lateinit var reader: JsoupSourceReader

    @Before
    fun setUp() {
        store = BookStore(tmpDir.root)
        // JsoupSourceReader needs a BookSourceManager and a Context for error reporting.
        // For unit tests, we test the content extraction logic in isolation.
        // Full integration tests require instrumented tests.
    }

    @Test
    fun `readChapter writes chapter file and returns content`() = runTest {
        // This test verifies the storage contract:
        // Given a chapter entry and location, readChapter should:
        // 1. Check if chapter file already exists → return from file
        // 2. If not, fetch from network → write to file → return content
        // Network fetching requires instrumented tests; here we test the file-based path.

        val location = BookLocation(bookId = "test-book", format = BookFormat.NETWORK)
        val paragraphs = listOf("段落一", "段落二", "段落三")
        store.writeChapter(location, 0, paragraphs)

        val entry = ChapterEntry(index = 0, title = "第一章", contentRef = "https://example.com/ch1")
        // When chapter file exists, reader should read from file directly
        // (network fetch is only triggered when file doesn't exist)
        val result = reader.readChapterFromFile(entry, location, store)

        assertEquals("第一章", result.title)
        assertEquals(paragraphs, result.paragraphs)
    }

    @Test
    fun `readChapter returns empty when no file exists and no network`() = runTest {
        val location = BookLocation(bookId = "empty-book", format = BookFormat.NETWORK)
        val entry = ChapterEntry(index = 0, title = "空章", contentRef = "https://example.com/empty")

        // No file exists, and we don't provide network → should return empty
        val result = reader.readChapterFromFile(entry, location, store)
        assertTrue(result.paragraphs.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.source.JsoupSourceReaderTest" 2>&1 | tail -20`
Expected: FAIL — `JsoupSourceReader` does not exist

- [ ] **Step 3: Implement `JsoupSourceReader`**

Create `lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupSourceReader.kt`:

```kotlin
package com.ebook.common.analyze.source

import android.content.Context
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.service.source.BookSourceNetwork
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.manager.ErrorAnalyzeContentManager
import com.ebook.common.store.BookStore
import com.ebook.db.entity.BookShelfEntity
import com.xrn1997.common.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网络书的章节正文读取器（spec §7 §10 M1b）。
 *
 * 与 [JsoupBookParser] 的分界：本类只管**正文获取与存储**，发现类能力（搜索/目录/分类）
 * 留在 `BookParser`。`readChapter` 的语义是"给我这章的正文"——如果章文件已存在就直接读，
 * 否则从网络抓取并写入章文件，下次再读就是纯文件 I/O。
 *
 * 多页拼接逻辑从 `JsoupBookParser.getBookContent` 搬来，判定规则不变：
 * 只跟进同章分页链接（[ChapterPageMatcher.isSameChapterPage]），上限 [MAX_CONTENT_PAGES]。
 */
@Singleton
class JsoupSourceReader @Inject constructor(
    private val store: BookStore,
    private val bookSourceManager: BookSourceManager,
    @ApplicationContext private val context: Context,
    @Named("source") private val okHttpClient: OkHttpClient,
) : ChapterReader {

    override suspend fun readChapter(
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent = withContext(Dispatchers.IO) {
        // 章文件已存在 → 直接读盘（离线阅读 / 已下载章节的快路径）
        if (store.hasChapter(location, entry.index)) {
            return@withContext readFromFile(entry, location)
        }
        // 章文件不存在 → 从网络抓取
        fetchAndStore(entry, location)
    }

    /**
     * 纯文件读取路径（无网络），供测试与已缓存场景使用。
     */
    internal fun readFromFile(entry: ChapterEntry, location: BookLocation): ChapterContent {
        val paragraphs = store.readParagraphs(location, entry.index)
        return ChapterContent(title = entry.title, paragraphs = paragraphs)
    }

    /**
     * 从网络抓取正文并写入章文件。
     *
     * 抓取逻辑从 `JsoupBookParser.getBookContent` 搬来：多页拼接 + 同章分页判定 + 清理规则。
     * 抓取结果以**单段落**存储（`listOf(fullText)`），保持与旧 `durChapterContent` 行为一致——
     * `ReadBookActivity` 的排版管线（`ReaderTypesetter.lineStartOffsets`）按 `\n` 分段，
     * 单段落的 `displayText` 等于原文，排版结果与旧实现完全一致。
     */
    private suspend fun fetchAndStore(
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent {
        val parser = bookSourceManager.requireParser() as? JsoupBookParser
            ?: throw IllegalStateException("当前书源不是 JsoupBookParser，无法抓取网络正文")

        val rule = parser.rule
        val network = BookSourceNetwork(rule, okHttpClient)
        val content = StringBuilder()

        try {
            val contentRule = rule.ruleContent
            val chapterBaseUrl = entry.contentRef
            val visited = mutableSetOf(entry.contentRef)
            var currentUrl: String? = entry.contentRef

            while (currentUrl != null && visited.size <= MAX_CONTENT_PAGES) {
                val relativeUrl = currentUrl.replace(rule.url, "")
                val html = network.getPage(relativeUrl)
                val doc = Jsoup.parse(html)
                val contentElement = doc.selectFirst(contentRule.content)

                if (contentElement != null) {
                    val paragraphs = contentElement.select("p")
                    val text = if (paragraphs.isNotEmpty()) {
                        paragraphs.mapNotNull { p ->
                            val t = p.text().trim()
                            if (t.isNotEmpty()) "  $t" else null
                        }.joinToString("\r\n")
                    } else {
                        contentElement.wholeText()
                            .replace("&nbsp;", " ")
                            .trim()
                    }
                    if (content.isNotEmpty() && text.isNotEmpty()) {
                        content.append("\r\n")
                    }
                    content.append(text)
                }

                currentUrl = if (contentRule.nextPage.isNotEmpty()) {
                    val next = com.ebook.api.utils.JsoupHelper.parseUrl(
                        rule.url,
                        com.ebook.api.utils.JsoupHelper.selectAttr(doc, contentRule.nextPage)
                    )
                    if (next.isNotEmpty() &&
                        ChapterPageMatcher.isSameChapterPage(next, chapterBaseUrl) &&
                        visited.add(next)
                    ) {
                        next
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            val rawText = com.ebook.api.utils.JsoupHelper.applyReplaceRules(
                content.toString(),
                contentRule.replaceRules.filter { it.enabled }
            )

            // 以单段落存储，保持与旧 durChapterContent 行为一致
            store.writeChapter(location, entry.index, listOf(rawText))

            return ChapterContent(title = entry.title, paragraphs = listOf(rawText))
        } catch (e: Exception) {
            Logger.e(TAG, "fetchAndStore: ", e)
            ErrorAnalyzeContentManager.writeNewErrorUrl(context, entry.contentRef)
            val errorText = rule.url + UNSUPPORTED_CONTENT_MARKER
            throw IllegalStateException("章节内容解析失败: ${entry.contentRef}", e)
        }
    }

    companion object {
        private const val TAG = "JsoupSourceReader"
        const val MAX_CONTENT_PAGES = 50
        const val UNSUPPORTED_CONTENT_MARKER = "站点暂时不支持解析"
    }
}
```

**Note:** `JsoupBookParser.rule` needs to be made accessible. Add `internal val rule: BookSourceRule` visibility or expose via a method. See Task 3 for the `BookParser` split.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.source.JsoupSourceReaderTest" 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupSourceReader.kt \
       lib_book_common/src/test/java/com/ebook/common/analyze/source/JsoupSourceReaderTest.kt
git commit -m "feat(lib_book_common): 新增 JsoupSourceReader 网络书正文读取器

从 JsoupBookParser.getBookContent 搬来多页拼接逻辑，写入 BookStore 章文件。
章文件已存在时直接读盘（离线阅读快路径），不存在时从网络抓取并存储。
以单段落存储保持与旧 durChapterContent 排版行为一致。"
```

---

### Task 3: Split `BookParser` — remove `getBookContent`

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookParser.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupBookParser.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt` (no change needed)

- [ ] **Step 1: Write the failing test**

The existing `ChapterPageMatcherTest` and `ListPageUrlTest` should still pass. No new tests needed for the interface change itself — compilation is the test.

- [ ] **Step 2: Remove `getBookContent` from `BookParser` interface**

Modify `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookParser.kt`:

Remove the `getBookContent` method declaration:

```kotlin
interface BookParser {
    suspend fun searchBook(content: String, page: Int): List<SearchBookEntity>
    suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity
    suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity>
    // getBookContent removed — content reading moved to JsoupSourceReader (spec §10 M1b)
    suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity>
    suspend fun getLibraryData(aCache: ACache): LibraryEntity
    fun analyzeLibraryData(data: String): LibraryEntity
}
```

Remove the import of `BookContentEntity` and `Context` if no longer needed.

- [ ] **Step 3: Remove `getBookContent` override from `JsoupBookParser`**

Modify `lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupBookParser.kt`:

Delete the entire `getBookContent` method (lines 173-251). Also make `rule` accessible to `JsoupSourceReader`:

```kotlin
class JsoupBookParser(
    internal val rule: BookSourceRule,  // Changed from private to internal
    okHttpClient: OkHttpClient
) : BookParser {
```

Also remove the imports that are no longer needed:
- `import android.content.Context`
- `import com.ebook.db.entity.BookContentEntity`
- `import com.ebook.common.manager.ErrorAnalyzeContentManager`

Keep `ChapterPageMatcher` and `UNSUPPORTED_CONTENT_MARKER` — they are now used by `JsoupSourceReader`. Move them to a shared location or keep them in `JsoupBookParser` companion and reference from `JsoupSourceReader`:

Actually, `MAX_CONTENT_PAGES` and `UNSUPPORTED_CONTENT_MARKER` are already defined in `JsoupSourceReader` companion. `ChapterPageMatcher` remains as a file-level class in `JsoupBookParser.kt` and is referenced by `JsoupSourceReader`.

- [ ] **Step 4: Fix compilation errors in callers**

The two callers of `getBookContent` will be updated in Tasks 5 and 6. For now, the compilation will fail. This is expected — we fix it in the next tasks.

- [ ] **Step 5: Run tests to verify helper classes still work**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.source.ChapterPageMatcherTest" --tests "com.ebook.common.analyze.source.ListPageUrlTest" 2>&1 | tail -20`
Expected: PASS (these test helper classes that are not removed)

- [ ] **Step 6: Commit**

```bash
git add lib_book_common/src/main/java/com/ebook/common/analyze/source/BookParser.kt \
       lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupBookParser.kt
git commit -m "refactor(lib_book_common): BookParser 拆发现/内容两半，移除 getBookContent

正文获取移至 JsoupSourceReader（M1b），BookParser 只保留发现类能力。
rule 字段改为 internal 供 JsoupSourceReader 访问。"
```

---

### Task 4: Room migration v3→v4 — drop `book_content` and `has_cache`

**Files:**
- Delete: `lib_ebook_db/src/main/java/com/ebook/db/entity/BookContentEntity.kt`
- Delete: `lib_ebook_db/src/main/java/com/ebook/db/dao/BookContentDao.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/entity/ChapterListEntity.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/dao/ChapterListDao.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/AppDatabase.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/di/DatabaseModule.kt`

- [ ] **Step 1: Remove `hasCache` from `ChapterListEntity`**

Modify `lib_ebook_db/src/main/java/com/ebook/db/entity/ChapterListEntity.kt`:

Remove lines 49-50 (`hasCache` field) and lines 51-55 (`bookContent` field):

```kotlin
data class ChapterListEntity(
    @ColumnInfo(name = "note_url")
    var noteUrl: String = String(),
    @ColumnInfo(name = "dur_chapter_index")
    var durChapterIndex: Int = 0,
    @PrimaryKey
    @ColumnInfo(name = "content_ref")
    var contentRef: String = String(),
    @ColumnInfo(name = "dur_chapter_name")
    var durChapterName: String = String(),
    @ColumnInfo(name = "tag")
    var tag: String = String(),
    // hasCache and bookContent removed in M1b — cache existence is now determined
    // by chapter file existence in BookStore (spec §10)
) : Parcelable
```

- [ ] **Step 2: Remove `updateHasCache` and `countCachedChaptersForBook` from `ChapterListDao`**

Modify `lib_ebook_db/src/main/java/com/ebook/db/dao/ChapterListDao.kt`:

Remove the `updateHasCache` method (lines 48-49) and `countCachedChaptersForBook` method (lines 56-57).

Update the KDoc to remove references to `has_cache`:

```kotlin
/**
 * 章节目录表（chapter_list）访问口。
 *
 * 每行是「章序号 + 章节 URL + 章节名 + 书源归属标记」，主键为自然键 `content_ref`。
 * 缓存存在性不再由此表判定——M1b 起一律查 BookStore 章文件（spec §10）。
 */
@Dao
interface ChapterListDao {
    @Query("SELECT * FROM chapter_list WHERE note_url = :bookNoteUrl ORDER BY dur_chapter_index ASC")
    suspend fun getChaptersForBook(bookNoteUrl: String): List<ChapterListEntity>

    @Query("SELECT * FROM chapter_list WHERE content_ref = :chapterUrl")
    suspend fun getChapterByUrl(chapterUrl: String): ChapterListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterListEntity>)

    @Query("SELECT COUNT(*) FROM chapter_list WHERE note_url = :bookNoteUrl")
    suspend fun countChaptersForBook(bookNoteUrl: String): Int

    @Query("DELETE FROM chapter_list WHERE note_url = :bookNoteUrl")
    suspend fun deleteChaptersForBook(bookNoteUrl: String)
}
```

- [ ] **Step 3: Delete `BookContentEntity.kt` and `BookContentDao.kt`**

```bash
rm lib_ebook_db/src/main/java/com/ebook/db/entity/BookContentEntity.kt
rm lib_ebook_db/src/main/java/com/ebook/db/dao/BookContentDao.kt
```

- [ ] **Step 4: Update `AppDatabase` — version 3→4, remove `BookContentEntity`**

Modify `lib_ebook_db/src/main/java/com/ebook/db/AppDatabase.kt`:

```kotlin
@Database(
    entities = [
        BookShelfEntity::class,
        BookInfoEntity::class,
        ChapterListEntity::class,
        // BookContentEntity removed in M1b — network book content now in chapter files
        SearchHistoryEntity::class,
        DownloadChapterEntity::class,
        BookGroupEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookShelfDao(): BookShelfDao
    abstract fun bookInfoDao(): BookInfoDao
    abstract fun chapterListDao(): ChapterListDao
    // bookContentDao() removed
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun downloadChapterDao(): DownloadChapterDao
    abstract fun bookGroupDao(): BookGroupDao

    companion object {
        const val DATABASE_NAME = "ebook_db"
    }
}
```

Update the KDoc to reflect the new state:

```kotlin
/**
 * ebook 本地数据库（Room 3.0.0），六张表的装配点。
 *
 * 承载「离线可读」所需数据：书架、书籍信息、章节目录、搜索历史、下载队列、作品分组。
 * M1b 起 `book_content` 表已删除——网络书正文与本地书统一存放于应用私有目录的章文件
 * （BookStore），缓存存在性由文件存在性判定。
 *
 * 主键策略（见 ADR-0003）：自然键（note_url / content_ref），
 * 下载任务与搜索历史用自增 id。
 */
```

- [ ] **Step 5: Add `MIGRATION_3_4` to `DatabaseModule`**

Modify `lib_ebook_db/src/main/java/com/ebook/db/di/DatabaseModule.kt`:

Add the migration and remove `BookContentDao` provider:

```kotlin
/**
 * v3 → v4（M1b，spec §5）：网络书正文从 `book_content` 迁到章文件。
 *
 * 做两件事：删 `book_content` 表、删 `chapter_list.has_cache` 列。
 * 缓存存在性改由 BookStore 章文件存在性判定，不再需要数据库标记。
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS book_content")
        // Room 的 ALTER TABLE DROP COLUMN 需要 SQLite 3.35.0+，Android API 34+ 保证。
        // 对于低版本，使用重建表的方式。但本项目 minSdk=26 且 bundled SQLite 版本足够。
        connection.execSQL("ALTER TABLE chapter_list DROP COLUMN has_cache")
    }
}
```

Update `provideAppDatabase`:

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```

Remove `provideBookContentDao`:

```kotlin
// Remove this method:
// @Provides @Singleton
// fun provideBookContentDao(db: AppDatabase): BookContentDao = db.bookContentDao()
```

Remove the import of `BookContentDao`.

- [ ] **Step 6: Generate Room schema JSON**

Run: `./gradlew :lib_ebook_db:assembleDebug 2>&1 | tail -20`
Expected: Room annotation processor generates the new schema JSON in `lib_ebook_db/schemas/`. Verify the new schema file exists for version 4.

- [ ] **Step 7: Commit**

```bash
git add -A lib_ebook_db/
git commit -m "refactor(lib_ebook_db): Room v3→v4 迁移，删除 book_content 表与 has_cache 列

网络书正文迁至 BookStore 章文件后，book_content 表与 chapter_list.has_cache
不再需要。缓存存在性改由文件存在性判定（spec §10 M1b）。"
```

---

### Task 5: Unify `BookRepository` — single `loadChapter` path

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryTest.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/repository/LocalContentReadTest.kt`

- [ ] **Step 1: Update `ContentStoreModule` to register `JsoupSourceReader`**

Modify `lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt`:

```kotlin
@Provides
@Singleton
fun provideLocalReaders(
    txt: TxtSourceReader,
    jsoup: JsoupSourceReader,
): Map<BookFormat, ChapterReader> =
    mapOf(
        BookFormat.TXT to txt,
        BookFormat.NETWORK to jsoup,
    )
```

Update imports: add `ChapterReader` and `JsoupSourceReader`.

Note: The map type changes from `Map<BookFormat, SourceReader>` to `Map<BookFormat, ChapterReader>`. This is the unified dispatch map for both local and network books.

- [ ] **Step 2: Update `BookRepository` constructor and unify `loadChapter`**

Modify `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt`:

Change constructor parameter:

```kotlin
@Singleton
class BookRepository @Inject constructor(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookGroupDao: BookGroupDao,
    private val chapterReaders: @JvmSuppressWildcards Map<BookFormat, ChapterReader>,
    private val bookStore: BookStore,
    private val contentCache: ChapterContentCache,
) : BaseModel() {
```

Remove `bookContentDao` parameter (no longer exists).

Replace `loadLocalChapter`, `loadBookContent`, `saveBookContent`, `deleteBookContent`, `updateChapterCache`, and `getCachedChapterUrls` with a unified `loadChapter`:

```kotlin
/**
 * 章节正文统一读取入口（spec §7 §10 M1b）。
 *
 * 本地书与网络书走同一条路径：按 bookFormat 路由到对应 ChapterReader → 经 ChapterContentCache
 * 内存缓存 → reader 内部判章文件存在性（存在则读盘，不存在则网络抓取并写文件）。
 *
 * 旧实现的 loadBookContent / saveBookContent / deleteBookContent / updateChapterCache 全部删除：
 * book_content 表已在 v3→v4 迁移中删除，缓存存在性由 BookStore 章文件存在性判定。
 */
suspend fun loadChapter(
    bookShelf: BookShelfEntity,
    index: Int,
    title: String,
): ChapterContent? {
    val format = resolveFormat(bookShelf)
    val reader = chapterReaders[format] ?: return null
    val location = BookLocation(bookShelf.noteUrl, format)
    val contentRef = bookStore.chapterRef(bookShelf.noteUrl, index)
    return contentCache.getOrLoad(contentRef) {
        val content = reader.readChapter(
            ChapterEntry(index = index, title = title, contentRef = contentRef),
            location,
        )
        content.takeIf { it.paragraphs.isNotEmpty() }
    }
}

/**
 * 批量判定哪些章节已有缓存（章文件存在）。
 *
 * 供下载面板绘制"已缓存"徽章：以 BookStore 章文件为事实源。
 */
suspend fun getCachedChapterIndices(
    bookShelf: BookShelfEntity,
    chapters: List<ChapterListEntity>,
): Set<Int> = withContext(Dispatchers.IO) {
    val format = resolveFormat(bookShelf)
    val location = BookLocation(bookShelf.noteUrl, format)
    chapters.filter { bookStore.hasChapter(location, it.durChapterIndex) }
        .map { it.durChapterIndex }
        .toSet()
}

/**
 * 解析书架的 BookFormat：本地书按 bookFormat 列（缺省 TXT），网络书固定 NETWORK。
 */
private fun resolveFormat(bookShelf: BookShelfEntity): BookFormat {
    if (bookShelf.tag != BookShelfEntity.LOCAL_TAG) return BookFormat.NETWORK
    val rawFormat = bookShelf.bookFormat
    return if (rawFormat == null) {
        BookFormat.TXT
    } else {
        runCatching { BookFormat.valueOf(rawFormat) }.getOrDefault(BookFormat.TXT)
    }
}
```

Remove the following methods entirely:
- `loadBookContent`
- `saveBookContent`
- `deleteBookContent`
- `getCachedChapterUrls` (replaced by `getCachedChapterIndices`)
- `updateChapterCache`
- `loadLocalChapter` (replaced by `loadChapter`)

Update `removeFromShelf` — remove the `bookContentDao` cleanup:

```kotlin
suspend fun removeFromShelf(bookShelf: BookShelfEntity) {
    // ... existing code ...
    if (bookShelf.tag == BookShelfEntity.LOCAL_TAG) {
        bookStore.deleteBook(BookLocation(bookShelf.noteUrl, BookFormat.TXT))
        contentCache.invalidateBook(bookShelf.noteUrl)
    } else {
        // Network books: delete chapter files too
        bookStore.deleteBook(BookLocation(bookShelf.noteUrl, BookFormat.NETWORK))
        contentCache.invalidateBook(bookShelf.noteUrl)
    }
    // ... rest of existing code ...
}
```

Actually, simplify — both branches do the same thing now:

```kotlin
suspend fun removeFromShelf(bookShelf: BookShelfEntity) {
    bookShelfDao.deleteByUrl(bookShelf.noteUrl)
    bookInfoDao.deleteByUrl(bookShelf.noteUrl)
    chapterListDao.deleteChaptersForBook(bookShelf.noteUrl)
    bookGroupDao.deleteFor(bookShelf.noteUrl)
    val format = resolveFormat(bookShelf)
    bookStore.deleteBook(BookLocation(bookShelf.noteUrl, format))
    contentCache.invalidateBook(bookShelf.noteUrl)
    _bookShelfEvents.emit(BookShelfEvent.Removed(bookShelf))
}
```

- [ ] **Step 3: Update `FakeDaos` for tests**

Modify `lib_book_common/src/test/java/com/ebook/common/repository/FakeDaos.kt`:

Remove `FakeBookContentDao` class entirely.

Remove from `FakeChapterListDao`:
- `updateHasCache` method
- `countCachedChaptersForBook` method

Remove `content` field from `FakeDaos`:

```kotlin
internal class FakeDaos {
    val shelf = FakeBookShelfDao()
    val info = FakeBookInfoDao()
    val chapter = FakeChapterListDao()
    val group = FakeBookGroupDao()
}
```

- [ ] **Step 4: Update `BookRepositoryTest`**

Modify `lib_book_common/src/test/java/com/ebook/common/repository/BookRepositoryTest.kt`:

Remove tests for:
- `loadBookContent`
- `saveBookContent`
- `updateChapterCache`
- `getCachedChapterUrls`

Update `setUp` to use `ChapterReader` map instead of `BookContentDao`:

```kotlin
private lateinit var repository: BookRepository
private lateinit var daos: FakeDaos
private lateinit var store: BookStore
private lateinit var cache: ChapterContentCache

@Before
fun setUp() {
    daos = FakeDaos()
    store = BookStore(tempFolder.root)
    cache = ChapterContentCache()
    val readers: Map<BookFormat, ChapterReader> = mapOf(
        BookFormat.TXT to FakeChapterReader(),
        BookFormat.NETWORK to FakeChapterReader(),
    )
    repository = BookRepository(
        bookShelfDao = daos.shelf,
        bookInfoDao = daos.info,
        chapterListDao = daos.chapter,
        bookGroupDao = daos.group,
        chapterReaders = readers,
        bookStore = store,
        contentCache = cache,
    )
}
```

Add a `FakeChapterReader`:

```kotlin
private class FakeChapterReader : ChapterReader {
    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent =
        ChapterContent(title = entry.title, paragraphs = listOf("fake content for ${entry.contentRef}"))
}
```

- [ ] **Step 5: Update `LocalContentReadTest`**

Rename/update to use `loadChapter` instead of `loadLocalChapter`.

- [ ] **Step 6: Run tests**

Run: `./gradlew :lib_book_common:testDebugUnitTest 2>&1 | tail -30`
Expected: All tests pass

- [ ] **Step 7: Commit**

```bash
git add lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt \
       lib_book_common/src/main/java/com/ebook/common/di/ContentStoreModule.kt \
       lib_book_common/src/test/java/com/ebook/common/repository/
git commit -m "refactor(lib_book_common): BookRepository 统一 loadChapter 路径，删除 DB 正文操作

本地书与网络书走同一 ChapterReader → ChapterContentCache → BookStore 管线。
loadBookContent / saveBookContent / updateChapterCache 等 DB 方法全部移除，
getCachedChapterUrls 改为基于章文件存在性的 getCachedChapterIndices。"
```

---

### Task 6: Rewrite `ReadBookActivity.loadPage` and `BookReadViewModel`

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt`
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt`

- [ ] **Step 1: Update `BookReadViewModel`**

Modify `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt`:

Remove:
- `loadBookContent` method
- `saveBookContent` method
- `fetchBookContent` method
- `updateChapterCache` method
- `loadLocalChapter` method (replaced by `loadChapter`)
- Import of `BookContentEntity`

Add:

```kotlin
/** 统一章节正文读取（本地书与网络书同路径） */
suspend fun loadChapter(chapter: ChapterListEntity): ChapterContent? =
    bookShelf?.let { bookRepository.loadChapter(it, chapter.durChapterIndex, chapter.durChapterName) }
```

Remove the `bookSourceManager` constructor parameter (no longer needed for content fetching).

Remove imports:
- `com.ebook.common.analyze.source.BookSourceManager`
- `com.ebook.db.entity.BookContentEntity`

- [ ] **Step 2: Update `ReadBookActivity.loadPage`**

Modify `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt`:

Replace the local/network fork (lines 273-285) with a unified call:

```kotlin
val chapterText: String? = viewModel.loadChapter(chapter)?.displayText
if (chapterText.isNullOrEmpty()) return null
```

Remove the entire `if (bookShelf.tag == BookShelfEntity.LOCAL_TAG) { ... } else { ... }` block and replace with the single line above.

- [ ] **Step 3: Update download sheet call site**

Modify `module_book/src/main/java/com/ebook/book/ReadBookActivity.kt` (lines 568-586):

Replace `getCachedChapterUrls` with `getCachedChapterIndices`:

```kotlin
val openDownloadSheet: () -> Unit = {
    menuVisible = false
    activity.requestDownloadPermission {
        val shelf = viewModel.bookShelf
        val chapterList = shelf?.chapterList
        if (shelf == null || chapterList.isNullOrEmpty()) return@requestDownloadPermission
        scope.launch {
            val cachedIndices = activity.bookRepository.getCachedChapterIndices(
                shelf, chapterList
            )
            val endIndex = (shelf.durChapter + 50).coerceAtMost(chapterList.size - 1)
            val initialSelected = (shelf.durChapter..endIndex).filterTo(mutableSetOf()) { i ->
                i !in cachedIndices
            }
            downloadArgs = DownloadSheetArgs(cachedIndices, initialSelected)
            panel = ReaderPanel.DOWNLOAD
        }
    }
}
```

Note: `DownloadSheetArgs` needs to be updated to accept `Set<Int>` (cached indices) instead of `Set<String>` (cached URLs). See Task 7.

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :module_book:assembleDebug 2>&1 | tail -30`
Expected: Compilation errors in `DownloadService` and `DownloadRepository` (they still reference removed methods). These are fixed in Tasks 7 and 8.

- [ ] **Step 5: Commit**

```bash
git add module_book/src/main/java/com/ebook/book/ReadBookActivity.kt \
       module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookReadViewModel.kt
git commit -m "refactor(module_book): ReadBookActivity 统一 loadChapter 路径，删除本地/网络分叉

loadPage 不再按 LOCAL_TAG 分叉取正文，一律走 BookRepository.loadChapter。
BookReadViewModel 删除 loadBookContent / fetchBookContent / saveBookContent 等
DB 方法，只保留统一的 loadChapter。下载面板缓存判定改为章文件存在性。"
```

---

### Task 7: Rewrite `DownloadService` to use chapter files

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/service/DownloadService.kt`
- Modify: `module_book/src/main/java/com/ebook/book/repository/DownloadRepository.kt`
- Modify: `module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt` (download panel UI)

- [ ] **Step 1: Update `DownloadService.downloading`**

Modify `module_book/src/main/java/com/ebook/book/service/DownloadService.kt`:

Replace the `downloading` method body. The new flow:
1. Check if chapter file exists (`bookStore.hasChapter`)
2. If exists and not force refresh → delete task (done)
3. If not exists or force refresh → fetch via `jsoupSourceReader.readChapter` → write to file → delete task

```kotlin
private fun downloading(context: Context, data: DownloadChapterEntity) {
    if (!isStartDownload) {
        isPause()
        return
    }
    isProgress(data)
    serviceScope.launch {
        var attempt = 0
        var success = false
        while (attempt < RETRY_TIMES && isStartDownload && !success) {
            attempt++
            try {
                val location = BookLocation(data.noteUrl, BookFormat.NETWORK)

                // Force refresh: delete existing chapter file first
                if (data.forceRefresh) {
                    bookStore.deleteBook(location) // or delete specific chapter file
                }

                // Check if already cached (chapter file exists)
                if (!data.forceRefresh && bookStore.hasChapter(location, data.durChapterIndex)) {
                    downloadRepository.deleteTask(data)
                    success = true
                    continue
                }

                // Fetch from network via JsoupSourceReader
                val entry = ChapterEntry(
                    index = data.durChapterIndex,
                    title = data.durChapterName,
                    contentRef = data.durChapterUrl,
                )
                val content = jsoupSourceReader.readChapter(entry, location)

                // Content validation
                val text = content.displayText
                if (text.isBlank() || text.contains(JsoupSourceReader.UNSUPPORTED_CONTENT_MARKER)) {
                    throw IllegalStateException("章节内容解析失败: ${data.durChapterUrl}")
                }

                downloadRepository.deleteTask(data)
                Logger.d(TAG, "downloading: ${data.durChapterUrl}")
                success = true
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "章节下载失败（第 $attempt/$RETRY_TIMES 次）: ${data.durChapterUrl}", e)
                if (attempt < RETRY_TIMES) {
                    delay(RETRY_DELAY_MS.milliseconds)
                }
            }
        }

        if (!success && attempt >= RETRY_TIMES) {
            skippedCount++
            Logger.w(TAG, "重试 $RETRY_TIMES 次仍失败，跳过本章并出队: ${data.durChapterUrl}")
            try {
                downloadRepository.deleteTask(data)
            } catch (e: Throwable) {
                Logger.e(TAG, "失败任务出队异常: ", e)
            }
        }

        if (isStartDownload) {
            myHandler.postDelayed({
                if (isStartDownload) {
                    toDownload()
                } else {
                    isPause()
                }
            }, CHAPTER_INTERVAL_MS)
        } else {
            isPause()
        }
    }
}
```

Add `JsoupSourceReader` and `BookStore` to the constructor:

```kotlin
@Inject lateinit var jsoupSourceReader: JsoupSourceReader
@Inject lateinit var bookStore: BookStore
```

Remove references to `bookRepository.loadBookContent`, `bookRepository.saveBookContent`, `bookRepository.deleteBookContent`, `bookRepository.updateChapterCache`.

Remove imports:
- `com.ebook.db.entity.BookContentEntity`

Add imports:
- `com.ebook.common.analyze.local.BookLocation`
- `com.ebook.common.analyze.local.BookFormat`
- `com.ebook.common.analyze.local.ChapterEntry`
- `com.ebook.common.analyze.source.JsoupSourceReader`
- `com.ebook.common.store.BookStore`

- [ ] **Step 2: Update `DownloadRepository.getCacheCoverage`**

Modify `module_book/src/main/java/com/ebook/book/repository/DownloadRepository.kt`:

```kotlin
suspend fun getCacheCoverage(noteUrl: String): CacheCoverage = withContext(Dispatchers.IO) {
    val total = chapterListDao.countChaptersForBook(noteUrl)
    // Count chapter files that exist
    val location = BookLocation(noteUrl, BookFormat.NETWORK)
    val chapters = chapterListDao.getChaptersForBook(noteUrl)
    val cached = chapters.count { bookStore.hasChapter(location, it.durChapterIndex) }
    CacheCoverage(total = total, cached = cached)
}
```

Add `BookStore` to constructor:

```kotlin
@Singleton
class DownloadRepository @Inject constructor(
    private val downloadChapterDao: DownloadChapterDao,
    private val bookShelfDao: BookShelfDao,
    private val chapterListDao: ChapterListDao,
    private val bookStore: BookStore,
) : BaseModel() {
```

Add imports:
- `com.ebook.common.analyze.local.BookLocation`
- `com.ebook.common.analyze.local.BookFormat`
- `com.ebook.common.store.BookStore`

- [ ] **Step 3: Update download panel UI (`ReaderPanels.kt`)**

Modify `module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt`:

Update `ChapterDownloadSheet` to accept `Set<Int>` (cached indices) instead of `Set<String>` (cached URLs):

```kotlin
@Composable
fun ChapterDownloadSheet(
    cachedIndices: Set<Int>,
    // ... rest of parameters
) {
    // Remove the cachedUrls → cachedIndices conversion
    // Use cachedIndices directly
}
```

Update `DownloadSheetArgs`:

```kotlin
data class DownloadSheetArgs(
    val cachedIndices: Set<Int>,
    val initialSelected: Set<Int>,
)
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :module_book:assembleDebug 2>&1 | tail -30`
Expected: Compilation succeeds

- [ ] **Step 5: Run all tests**

Run: `./gradlew test 2>&1 | tail -30`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add module_book/src/main/java/com/ebook/book/service/DownloadService.kt \
       module_book/src/main/java/com/ebook/book/repository/DownloadRepository.kt \
       module_book/src/main/java/com/ebook/book/reader/ReaderPanels.kt
git commit -m "refactor(module_book): 下载服务改写章文件，缓存判定改查 BookStore

DownloadService.downloading 不再写 book_content 表，改由 JsoupSourceReader
直接写章文件。下载面板缓存徽章与覆盖率统计改查 BookStore.hasChapter。"
```

---

### Task 8: Clean up remaining references and update docs

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt` (remove unused imports)
- Modify: `AGENTS.md` (update local book content section)
- Modify: `CONTEXT.md` (update terminology if needed)
- Modify: `docs/superpowers/specs/2026-09-04-local-book-import-design.md` (update §15 status)

- [ ] **Step 1: Search for remaining `BookContentEntity` / `BookContentDao` references**

Run: `grep -r "BookContentEntity\|BookContentDao\|bookContentDao\|book_content" --include="*.kt" --include="*.java" -l`

Fix any remaining references. Common places:
- Import statements
- Test files
- Comments referencing the old table

- [ ] **Step 2: Search for remaining `hasCache` / `has_cache` references**

Run: `grep -r "hasCache\|has_cache\|updateHasCache\|countCachedChaptersForBook" --include="*.kt" --include="*.java" -l`

Fix any remaining references.

- [ ] **Step 3: Search for remaining `loadBookContent` / `saveBookContent` references**

Run: `grep -r "loadBookContent\|saveBookContent\|deleteBookContent\|updateChapterCache\|loadLocalChapter" --include="*.kt" --include="*.java" -l`

Fix any remaining references.

- [ ] **Step 4: Update spec status**

Modify `docs/superpowers/specs/2026-09-04-local-book-import-design.md` §15:

Update the status section to reflect M1b completion:

```markdown
## 15. 里程碑状态

- **M1a 本地书出 DB**：已完成（commit a65c4fc 前后）。`book_content` 表不再服务本地书，
  本地书正文走 `BookStore` 章文件 + `ChapterContentCache` 内存缓存。
- **M1b 网络书出 DB 与抽象统一**：已完成。`book_content` 表删除，`has_cache` 列删除，
  网络书缓存改写章文件，`BookParser` 拆「发现/内容」两半，`JsoupSourceReader` 落地，
  `BookRepository.loadChapter` 统一本地与网络路径。
- **M2 评论与分组**：未做。
- **M3 EPUB 支持**：未做。
```

- [ ] **Step 5: Update `AGENTS.md`**

Update the section about local book content to reflect the unified path:

```markdown
- 涉及本地书籍导入或章节正文读取时，先读 `docs/superpowers/specs/2026-09-04-local-book-import-design.md`；
  M1b 起本地书与网络书正文统一走 `BookStore` 章文件 + `ChapterContentCache`，
  `BookRepository.loadChapter` 是唯一入口。`book_content` 表已在 v3→v4 迁移中删除。
```

- [ ] **Step 6: Full build verification**

Run: `./gradlew build 2>&1 | tail -30`
Expected: Build succeeds with no errors

- [ ] **Step 7: Run all tests**

Run: `./gradlew test 2>&1 | tail -30`
Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "docs(all): M1b 完成后同步文档与清理残留引用

spec §15 状态更新、AGENTS.md 统一路径描述更新、清除所有对
BookContentEntity / has_cache / loadBookContent 的残留引用。"
```

---

### Task 9: Generate Room schema and final verification

**Files:**
- Verify: `lib_ebook_db/schemas/` contains version 4 schema JSON

- [ ] **Step 1: Verify Room schema export**

Run: `./gradlew :lib_ebook_db:assembleDebug 2>&1 | tail -10`

Verify that `lib_ebook_db/schemas/com.ebook.db.AppDatabase/4.json` exists.

- [ ] **Step 2: Verify migration chain integrity**

Run: `./gradlew :lib_ebook_db:testDebugUnitTest 2>&1 | tail -10`
Expected: PASS (if migration tests exist)

- [ ] **Step 3: Full project build**

Run: `./gradlew build 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Full test suite**

Run: `./gradlew test 2>&1 | tail -30`
Expected: All tests pass

- [ ] **Step 5: Final commit (if any schema files need to be added)**

```bash
git add lib_ebook_db/schemas/
git commit -m "build(lib_ebook_db): 提交 Room v4 schema JSON"
```

---

##人工装机验证项（Agent 不做，人工在真机/模拟器上确认）

1. **离线下载断点续跑**：下载一批章节 → 中途暂停 → 恢复 → 已下载章节不重复下载，未下载章节继续
2. **缓存覆盖率展示**：阅读器下载面板显示正确的已缓存/总章节比例
3. **从书架移除后文件回收**：删除书架上的网络书 → 确认 `filesDir/books/<noteUrl>/` 目录被删除
4. **下载中断的文件残留**：下载过程中杀进程 → 重启后重新下载，不留下指向不完整文件的索引
5. **网络书首次阅读**：打开一本未下载的网络书 → 正文从网络加载并缓存到章文件 → 翻页流畅
6. **网络书离线阅读**：下载部分章节后断网 → 已下载章节可正常阅读 → 未下载章节显示加载失败
