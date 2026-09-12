package com.ebook.book

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ebook.common.importer.LocalBookImporter
import com.ebook.common.repository.BookRepository
import com.ebook.db.dao.ChapterListDao
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * 本地导入性能基线（spec §6）——**改后**（新流水线：拷贝即哈希 → 章文件 → 单事务落库）这一侧。
 *
 * 改前数字必须用**旧链路**的夹具量：旧链路（`BookImportManager` + `book_content` 表）已随本次
 * 重构删除，其夹具保留在 develop_book 分支（提交 2f248fa，同名测试同规格夹具）。
 * 两侧对比时注意**测量边界纪律**：旧夹具只包住 `importBook`，且量不到调用方那遍
 * 「导入后再 `addToShelf` 全量重写」的热点；本夹具包住 [LocalBookImporter.import]，
 * 新链路里该书架级联写入已在导入器事务内完成、不存在第二个热点，故两侧对比时旧数字
 * 应理解为"旧链路实际最优耗时"，新数字为"全链路耗时"——口径差异在 spec §6 回填时注明。
 *
 * 夹具为 2000 章、约 6MB 的 UTF-8 TXT，走真实导入链路（真实 Room 写库、真实文件写）。
 *
 * 为什么用 [runBlocking] 而不是 `runTest`：导入内部 `withContext(Dispatchers.IO)` 做的是
 * 真实文件读与真实 SQLite 写，虚拟时间调度器对这类墙钟耗时毫无意义。这里要的是端到端
 * 墙钟毫秒数。
 *
 * 为什么测 native heap（见 [DebugMemory]）而不是 `Runtime.totalMemory()`：
 * Room 3 走 `BundledSQLiteDriver`，SQLite 的页缓存与语句分配在 native 堆上，
 * Java 堆快照量不到导入过程中真正涨起来的那部分。
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ImportBaselineTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    /** 被测链路：新导入流水线（拷贝即哈希 → 严格解码切分 → 章文件 → 单事务落库） */
    @Inject lateinit var importer: LocalBookImporter

    @Inject lateinit var chapterListDao: ChapterListDao

    /** 收尾清理走生产移除路径：DB 行 + 章文件目录 + book_group 随书清干净 */
    @Inject lateinit var bookRepository: BookRepository

    /** 必须先 inject()，否则上面的 @Inject 字段全为 null（HiltAndroidRule 只在此处拉起组件树）。 */
    @Before fun setUp() {
        hiltRule.inject()
    }

    /**
     * 跑一次 2000 章导入，打印基线四元组：耗时、章节数、夹具体积、堆增量。
     *
     * 断言章节数只是自检（确认夹具的 `第N章` 标题确实被切分器的 `第.{1,7}章.*` 规则识别），
     * 不是被测目标；真正的产出是那行 BASELINE 日志。
     *
     * **返回类型必须显式写 `: Unit`**：表达式函数体会按块尾表达式（`fixture.delete()`
     * 返回 Boolean）推断出 boolean，而 JUnit4 在执行前校验 `@Test` 方法必须是 void，
     * 会直接抛「Method ... should be void」——一条测量都没跑到。别把它改回 `= runBlocking`。
     */
    @Test
    fun `2000 章 TXT 导入产出 BASELINE 基线四元组且切分数自检通过`() : Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.cacheDir, "baseline_book.txt")
        buildFixture(fixture, chapters = 2000, charsPerChapter = 3000)

        val startNs = System.nanoTime()
        val memBeforeKb = DebugMemory.snapshotKb()
        val result = importer.import(fixture)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val memAfterKb = DebugMemory.snapshotKb()

        val noteUrl = result.bookShelf.noteUrl
        val chapters = chapterListDao.getChaptersForBook(noteUrl)
        // 用 println 而非 lib_common 的 Logger：本行是给 gradle 仪器测试控制台/日志采集
        // 消费的测量产出，需要与 Logger 的级别裁剪与 debug/release 开关解耦，
        // 免得基线数字在 release 变体下被裁掉。System.out 仍会落进 logcat 的 System.out tag。
        println(
            "BASELINE elapsed=${elapsedMs}ms chapters=${chapters.size} " +
                "fileKb=${fixture.length() / 1024} memDeltaKb=${memAfterKb - memBeforeKb}"
        )

        assertTrue("应切出 2000 章，实际 ${chapters.size}", chapters.size == 2000)

        // 清理：书架移除路径会级联删 book_shelf/book_info/chapter_list/book_group 行与章文件目录
        bookRepository.removeFromShelf(result.bookShelf)
        fixture.delete()
    }

    /**
     * 生成夹具：`chapters` 个以 `第N章` 起头、每章约 `charsPerChapter` 字的段落块。
     *
     * 标题格式刻意对齐线上小说的常见写法，以命中切分器的章节识别正则；段落用重复文本而非
     * 随机文本，是为了让改前/改后两次跑的字节数与解码代价完全可比，不掺杂随机内容带来的差异。
     * 逐行写入缓冲流而不是先攒满字符串，避免 6MB 夹具在计时区间外就把 Java 堆撑起来。
     */
    private fun buildFixture(target: File, chapters: Int, charsPerChapter: Int) {
        val paragraph = "这是用于基线测量的正文内容，重复若干次以凑出目标章长。"
        target.bufferedWriter().use { writer ->
            for (i in 1..chapters) {
                // 插值必须写成 ${i}：Kotlin 会把紧跟表达式后的中文字符并入标识符
                //（"$i章" 解析成引用 i章），直接写 $i 无法编译
                writer.write("第${i}章 基线测试章节 $i\n")
                repeat(charsPerChapter / paragraph.length) {
                    writer.write(paragraph)
                    writer.write("\n")
                }
            }
        }
    }
}
