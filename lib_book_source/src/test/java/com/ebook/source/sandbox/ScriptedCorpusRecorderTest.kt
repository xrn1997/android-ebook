package com.ebook.source.sandbox

import com.ebook.source.script.OkHttpScriptTransport
import com.ebook.source.analyze.ScriptBookParser
import com.ebook.source.script.ScriptRequest
import com.ebook.source.script.ScriptRuleSet
import com.ebook.source.script.ScriptTransport
import com.ebook.db.entity.BookShelfEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 语料夹具录制器（**显式触发**，发真网络请求）：把真实书源的四链
 * （搜索→详情→目录→正文）跑一遍，把解析器实际发出的每个请求的响应体冻进
 * `src/test/resources/scripted_corpus/<slug>/`，连同当时的解析产物写成期望值。
 *
 * **该目录不入 git**（经 `.gitignore` 排除）：夹具含真书源规则与真站 HTML（含章节正文），
 * 与「应用不再随包携带任何书源」的口径冲突，故只作本地录制产物保留——它只存在于录过的那台机器上，
 * 需要时在本机重录，克隆仓库后没有夹具是正常状态。
 *
 * 两种触发（`corpus.*` 系统属性由 build.gradle.kts 透传）：
 * - 单条：`-Pcorpus.record=<书源JSON文件> -Pcorpus.keyword=<关键词>`
 * - 批量：`-Pcorpus.batch=<A桶JSON> -Pcorpus.batchOffset=0 -Pcorpus.batchLimit=50
 *          [-Pcorpus.keywords=剑来,斗破苍穹,三体] [-Pcorpus.conn=<连通性TSV>]`
 *   批量按 [OFFSET, OFFSET+LIMIT) 切片、**已录夹具自动跳过**（天然可续跑），
 *   逐源记录成败与原因，汇总 TSV 写到 `build/corpus-summary-<offset>.tsv`。
 *
 * 为什么用**真解析器**录而不是手抓：录下来的 URL 就是解析器会发的 URL（选项尾段、
 * charset、POST body、翻页链全跟生产一致），回放时 TableTransport 按同键口径命中。
 * 沙箱外呼（`java.ajax`）也经守门客户端录进 sandbox.json——含 JS 的源因此能整体离线回放。
 *
 * 跳过口径（批量）：🔞 成人内容 HTML 不入仓；连通性记录里不可达/4xx/5xx 的站点
 * 不再空跑；已录夹具跳过。搜索失败按多关键词兜底后如实记 FAIL。
 *
 * 命名红线：录完扫 `legado`/`gedoor`，出现即失败（书源备注里的先行替换，正文里出现就人工处理）。
 */
class ScriptedCorpusRecorderTest {

    private val json = Json { prettyPrint = true }

    // —— 单条录制（保持原触发方式）——

    @Test
    fun `录制一条真实书源的四链夹具`() = runTest {
        val srcPath = System.getProperty("corpus.record")
        assumeTrue("未指定 corpus.record，录制按跳过处置（显式动作，不随常规测试跑）", srcPath != null)
        assumeTrue("libebook_js 未加载，含 JS 的源录不出真产物", QuickJsNative.loaded)
        val keyword = System.getProperty("corpus.keyword") ?: error("录制需要 -Pcorpus.keyword=<关键词>")
        val raw = File(srcPath!!).readText()
        val record = recordOne(raw, listOf(keyword), dir = null)
        assertTrue("录制失败：$record", record.ok)
        println("已录制 ${record.slug}  pages=${record.mainPages}  sandbox=${record.sandboxPages}")
    }

    // —— 批量录制（A 桶规模化）——

    @Test
    fun `批量录制语料夹具`() = runBlocking {
        val batchPath = System.getProperty("corpus.batch")
        assumeTrue("未指定 corpus.batch，批量录制按跳过处置", batchPath != null)
        assumeTrue("libebook_js 未加载，含 JS 的源录不出真产物", QuickJsNative.loaded)
        val offset = System.getProperty("corpus.batchOffset")?.toIntOrNull() ?: 0
        val limit = System.getProperty("corpus.batchLimit")?.toIntOrNull() ?: 0
        val keywords = (System.getProperty("corpus.keywords") ?: "剑来,斗破苍穹,三体").split(',').map { it.trim() }
        val conn = System.getProperty("corpus.conn")?.let(::loadConnectivity) ?: emptyMap()

        val entries = Json.parseToJsonElement(File(batchPath!!).readText()).jsonArray
        val slice = entries.drop(offset).take(if (limit > 0) limit else entries.size)
        println("批量录制: 总 ${entries.size} 条，本片 [$offset, ${offset + slice.size})")

        // runBlocking 用真实挂钟时间，withTimeoutOrNull 不会像 runTest 虚拟时间那样
        // 在网络 I/O 挂起时瞬间推进——这是批量录制全超时（131/131）的根因修复
        withTimeout(30.minutes) {
            val summary = mutableListOf<String>()
            var n = 0
            for (element in slice) {
                val obj = element as? JsonObject ?: continue
                val raw = element.toString()
                val name = (obj["bookSourceName"] as? JsonPrimitive)?.content ?: ""
                val url = (obj["bookSourceUrl"] as? JsonPrimitive)?.content ?: ""
                n++
                val tag = "[$n/${slice.size}]"
                val line = withTimeoutOrNull(30.seconds) {
                    recordBatchEntry(raw, name, url, keywords, conn)
                } ?: "FAIL\t${slugOf(url.split('#').first().trim(), name)}\t$name\t$url\t超时（30s）"
                summary += line
                println("$tag $line")
            }
            val out = File("build/corpus-summary-$offset.tsv")
            out.writeText(("category\tslug\tname\turl\tdetail\n" + summary.joinToString("\n")))
            val byCat = summary.groupingBy { it.split('\t').first() }.eachCount()
            println("=== 本片汇总（写入 ${out.absolutePath}）===\n" +
                byCat.entries.sortedByDescending { it.value }.joinToString("\n") { "  ${it.value}\t${it.key}" })
        }
    }

    /** 单源处置：跳过类在进网络前判掉，失败类带阶段与原因；成功写夹具 */
    private suspend fun recordBatchEntry(
        raw: String,
        name: String,
        url: String,
        keywords: List<String>,
        conn: Map<String, String>,
    ): String {
        val base = url.split('#').first().trim()
        if (name.contains("🔞")) return "SKIP-成人内容\t-\t$name\t$url\t成人内容 HTML 快照不入仓"
        conn[base]?.let { status ->
            if (status != "200") return "SKIP-不可达\t-\t$name\t$url\t连通性记录 status=$status"
        }
        val slug = slugOf(base, name)
        val dir = File("src/test/resources/scripted_corpus/$slug")
        if (File(dir, "expect.json").isFile) return "SKIP-已录\t$slug\t$name\t$url\t夹具已存在（可续跑）"
        return try {
            val record = recordOne(raw, keywords, dir)
            "OK\t${record.slug}\t$name\t$url\t书=${record.book} 目录=${record.chapters} 正文=${record.contentLen}字" +
                " pages=${record.mainPages} sandbox=${record.sandboxPages} kw=${record.keyword}"
        } catch (t: Throwable) {
            "FAIL\t$slug\t$name\t$url\t${t::class.simpleName}: ${t.message?.take(160)}"
        }
    }

    // —— 录制核心：一条源的四链 + 落盘 ——

    private class Record(
        val ok: Boolean,
        val slug: String,
        val book: String,
        val chapters: Int,
        val contentLen: Int,
        val mainPages: Int,
        val sandboxPages: Int,
        val keyword: String,
    )

    private suspend fun recordOne(raw: String, keywords: List<String>, dir: File?): Record {
        // 允许清单与解析器内部同源（从规则原文导出），守门口径才一致
        val rules = ScriptRuleSet.load(raw)
        val guard = JsNetworkGuard(
            SourceHostAllowlist.of(
                rules.sourceUrl,
                rules.objects.values.flatMap { it.values } + listOfNotNull(rules.searchUrl, rules.exploreUrl),
            ),
        )
        // 批量录制用短超时（connect 5s / read 10s），避免慢站拖垮整批
        val baseClient = OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val main = RecordingTransport(OkHttpScriptTransport(baseClient))
        val sandbox = RecordingTransport(OkHttpScriptTransport(GuardedNetwork.clientFor(baseClient, guard)))
        val parser = ScriptBookParser(raw, main, DirectJsRuntime.host, sandbox)

        suspend fun <T> stage(name: String, block: suspend () -> T): T = try {
            block()
        } catch (t: Throwable) {
            throw IllegalStateException("[$name] ${t::class.simpleName}: ${t.message?.take(160)}", t)
        }

        // 搜索：多关键词兜底（小众站没有主流书名很正常），全部落空才判失败
        var lastEmpty = 0
        var keyword = keywords.first()
        val books = run {
            var found: List<com.ebook.db.entity.SearchBookEntity>? = null
            for (kw in keywords) {
                val r = try {
                    stage("search[$kw]") { parser.searchBook(kw, 1) }
                } catch (t: Throwable) {
                    keyword = kw
                    throw t
                }
                if (r.isNotEmpty()) {
                    keyword = kw
                    found = r
                    break
                }
                lastEmpty++
            }
            found ?: throw IllegalStateException("[search] ${lastEmpty}/${keywords.size} 个关键词均无结果")
        }
        val first = books.first()
        val shelf = BookShelfEntity().apply {
            noteUrl = first.noteUrl
            tag = first.tag
        }
        val info = stage("detail") { parser.getBookInfo(shelf) }
        val chapters = stage("toc") { parser.getChapterList(shelf).data.chapterList }
        assertTrue("目录为空，夹具录不成", chapters.isNotEmpty())
        val text = stage("content") { parser.fetchChapterText(chapters.first().contentRef) }

        val expect = buildJsonObject {
            put("keyword", JsonPrimitive(keyword))
            put("search", buildJsonObject {
                put("firstName", JsonPrimitive(first.name))
                put("firstUrl", JsonPrimitive(first.noteUrl))
                put("author", JsonPrimitive(first.author))
            })
            put("detail", buildJsonObject {
                put("name", JsonPrimitive(info.bookInfo?.name ?: ""))
                put("author", JsonPrimitive(info.bookInfo?.author ?: ""))
            })
            put("toc", buildJsonObject {
                put("count", JsonPrimitive(chapters.size))
                put("firstChapterName", JsonPrimitive(chapters.first().durChapterName))
                put("firstContentRef", JsonPrimitive(chapters.first().contentRef))
            })
            put("content", buildJsonObject {
                put("minLength", JsonPrimitive(text.length))
                put("sample", JsonPrimitive(text.take(60)))
            })
        }

        val slug = slugOf(rules.sourceUrl, rules.name)
        val outDir = dir ?: File("src/test/resources/scripted_corpus/$slug")
        outDir.mkdirs()
        val scrubbed = scrub(raw)
        File(outDir, "source.json").writeText(json.encodeToString(JsonObject.serializer(), scrubbed))
        File(outDir, "pages.json").writeText(json.encodeToString(JsonObject.serializer(), toJsonObject(main.pages)))
        File(outDir, "sandbox.json").writeText(json.encodeToString(JsonObject.serializer(), toJsonObject(sandbox.pages)))
        File(outDir, "expect.json").writeText(json.encodeToString(JsonObject.serializer(), expect))

        // 命名红线自检：替换备注之后再核一遍全目录
        outDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val content = f.readText()
            assertTrue("夹具 ${f.name} 命中命名红线词", !REDLINE.containsMatchIn(content))
        }
        return Record(true, slug, first.name, chapters.size, text.length, main.pages.size, sandbox.pages.size, keyword)
    }

    /** 连通性记录 TSV（A-connectivity.tsv）：url → status，供批量预跳过不可达站点 */
    private fun loadConnectivity(path: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        File(path).readLines().drop(1).filter { it.isNotBlank() }.forEach { line ->
            val col = line.split('\t')
            if (col.size >= 3) map[col[1].trim()] = col[2].trim()
        }
        return map
    }

    private class RecordingTransport(private val inner: ScriptTransport) : ScriptTransport {
        val pages = linkedMapOf<String, String>()
        override suspend fun execute(request: ScriptRequest): String {
            val body = inner.execute(request)
            pages[request.url] = body
            return body
        }
    }

    private fun toJsonObject(map: Map<String, String>): JsonObject =
        JsonObject(map.mapValues { JsonPrimitive(it.value) })

    /** 书源备注是「适配某阅读 App」的重灾区：只动备注，不碰规则字段 */
    private fun scrub(raw: String): JsonObject {
        val obj = Json.parseToJsonElement(raw).let { it as? JsonObject ?: error("书源 JSON 不是对象") }
        val comment = obj["bookSourceComment"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val cleaned = comment.replace(REDLINE, "")
        return if (cleaned == comment) obj else JsonObject(obj + ("bookSourceComment" to JsonPrimitive(cleaned)))
    }

    private fun slugOf(sourceUrl: String, name: String): String {
        val host = sourceUrl.removePrefix("https://").removePrefix("http://")
            .split('#', '/').first().replace(Regex("[^A-Za-z0-9]"), "").lowercase()
        return host.ifBlank { "src" } + "-" + Integer.toHexString(name.hashCode())
    }

    private companion object {
        val REDLINE = Regex("legado|gedoor", RegexOption.IGNORE_CASE)
    }
}
