package com.ebook.source.sandbox

import com.ebook.source.analyze.ScriptBookParser
import com.ebook.source.script.ScriptTransport
import com.ebook.db.entity.BookShelfEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真语料夹具的**数据驱动回放**：`src/test/resources/scripted_corpus/<slug>/` 下每个目录
 * 就是一条真实书源（真规则串 + 真响应快照 + 当时的解析产物），本类逐条跑
 * 搜索→详情→目录→正文四链并断言产物与录制时一致。
 *
 * **该目录不入 git**（经 `.gitignore` 排除）：夹具含真书源规则与真站响应（含章节正文），
 * 与「应用不再随包携带任何书源」的口径冲突，是本地录制产物。因此**无夹具时按 `assumeTrue`
 * 跳过是常态而非异常**——新克隆的仓库、CI 上都应看到本类被 skip，而不是失败；需要真语料回归时
 * 在本机用 [ScriptedCorpusRecorderTest] 重录。
 *
 * 与 [com.ebook.source.script.ScriptRealSourceGoldenTest] 的分工：那边是手写断言、
 * 只覆盖声明式源，每一处语义都配着一段讲解；这边是**批量**、含 JS 的源走**真内核**
 * （[DirectJsRuntime]），断言的是「录制当天解出了什么」——回归网，不是语义文档。
 * 新增夹具用 ScriptedCorpusRecorderTest 录，别手写。
 *
 * 响应是录制当天从活站抓的解码后文本，之后全程离线；站点改版/死亡不影响本测试，
 * 夹具过期由「重录」解决（与 golden 同一口径）。
 */
class ScriptedCorpusReplayTest {

    private val json = Json

    @Test
    fun `语料夹具逐条回放四链`() = runTest {
        assumeTrue("libebook_js 未加载：先跑 :lib_book_source:buildDesktopJsLib（${QuickJsNative.loadError}）", QuickJsNative.loaded)
        // 判据取 expect.json 而非「目录存在」：录制器自己就以这个文件标记一条夹具录完
        // （见 ScriptedCorpusRecorderTest 的「SKIP-已录」），空壳目录（数据被清走、或录制中断）
        // 因此落进「还没有夹具」这一档按跳过处置——否则 62 个空目录会各自抛 FileNotFoundException，
        // 把上面那段 KDoc 承诺的「无夹具时 skip 是常态」变成整类必红
        val dirs = corpusDir()?.listFiles()?.filter { File(it, "expect.json").isFile }.orEmpty()
        assumeTrue("scripted_corpus 下还没有夹具（用 ScriptedCorpusRecorderTest 录）", dirs.isNotEmpty())

        val failures = mutableListOf<String>()
        for (dir in dirs) {
            try {
                replay(dir)
            } catch (t: Throwable) {
                t.printStackTrace()
                failures += "${dir.name}: ${t::class.simpleName}: ${t.message}"
            }
        }
        assertTrue(
            "夹具回放失败 ${failures.size}/${dirs.size} 条：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun corpusDir(): File? =
        javaClass.classLoader?.getResource("scripted_corpus")?.let { File(it.toURI()) }

    private suspend fun replay(dir: File) {
        suspend fun <T> stage(name: String, block: suspend () -> T): T = try {
            block()
        } catch (t: Throwable) {
            throw IllegalStateException("[$name] ${t::class.simpleName}: ${t.message}", t)
        }
        val raw = File(dir, "source.json").readText()
        val pages = loadMap(File(dir, "pages.json"))
        val sandbox = loadMap(File(dir, "sandbox.json"))
        val expect = stage("expect") { Json.parseToJsonElement(File(dir, "expect.json").readText()).jsonObject }

        val main = TableTransport(pages)
        val parser = ScriptBookParser(
            raw,
            main,
            DirectJsRuntime.host,
            TableTransport(sandbox),
        )

        // —— 搜索 ——
        val keyword = stage("expect.keyword") { expect["keyword"]!!.jsonPrimitive.content }
        val books = stage("search") { parser.searchBook(keyword, 1) }
        assertTrue("搜索无结果", books.isNotEmpty())
        val first = books.first()
        assertEquals(
            "搜索书名",
            expect["search"]!!.jsonObject["firstName"]!!.jsonPrimitive.content,
            first.name,
        )
        assertEquals(
            "搜索详情地址",
            expect["search"]!!.jsonObject["firstUrl"]!!.jsonPrimitive.content,
            first.noteUrl,
        )

        // —— 详情 ——
        val shelf = BookShelfEntity().apply {
            noteUrl = first.noteUrl
            tag = first.tag
        }
        val info = parser.getBookInfo(shelf)
        assertEquals(
            "详情书名",
            expect["detail"]!!.jsonObject["name"]!!.jsonPrimitive.content,
            info.bookInfo?.name,
        )

        // —— 目录 ——
        val chapters = parser.getChapterList(shelf).data.chapterList
        assertEquals(
            "目录章节数",
            expect["toc"]!!.jsonObject["count"]!!.jsonPrimitive.content.toInt(),
            chapters.size,
        )
        assertEquals(
            "首章名",
            expect["toc"]!!.jsonObject["firstChapterName"]!!.jsonPrimitive.content,
            chapters.first().durChapterName,
        )
        assertEquals(
            "首章正文定位符",
            expect["toc"]!!.jsonObject["firstContentRef"]!!.jsonPrimitive.content,
            chapters.first().contentRef,
        )

        // —— 正文 ——
        val text = parser.fetchChapterText(chapters.first().contentRef)
        val minLength = expect["content"]!!.jsonObject["minLength"]!!.jsonPrimitive.content.toInt()
        assertTrue("正文字数 ${text.length} < 录制时 $minLength", text.length >= minLength)
        val sample = expect["content"]!!.jsonObject["sample"]!!.jsonPrimitive.content
        assertTrue("正文开头与录制时不符", text.take(60) == sample)
    }

    private class TableTransport(private val table: Map<String, String>) : ScriptTransport {
        val requests = mutableListOf<com.ebook.source.script.ScriptRequest>()
        override suspend fun execute(request: com.ebook.source.script.ScriptRequest): String {
            requests += request
            // 与 golden 同口径：未命中回空串，选择器失配显形为「没取到值」而不是假件编内容
            return table[request.url] ?: ""
        }
    }

    private fun loadMap(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val obj = Json.parseToJsonElement(file.readText()).jsonObject
        return obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
    }
}
