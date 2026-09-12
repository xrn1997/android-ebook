package com.ebook.source.script

import com.ebook.db.entity.BookShelfEntity
import com.ebook.source.analyze.ScriptBookParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 真实语料金标准（ADR-0029 决策 8 还债）。
 *
 * 与 [ScriptSourceEndToEndTest] 的分工：那里是**合成源**，锁解析器编排行为；本类是**真实站点的
 * 真规则串 + 真响应快照**，锁「上游真实规则在真实 HTML 上解得对」——清洁室自研的语义偏差只有
 * 拿真站点真页才照得出来（实体编码、广告残留、选择器作用域里的诱饵、真实翻页）。
 *
 * 响应是 2026-09-10 用 `curl` 从活站抓的**原始字节**冻进 `src/test/resources/scripted_real/`，
 * 之后**全程离线**：假 transport 直接回冻结文本，不发任何网络请求，站点死了也不影响本测试。
 * 时效性被「抓一次冻起来」消解（口径见与用户的约定）。
 *
 * 红线：fixture 不含生态项目名（抽取时已核命名红线词为零）。
 *
 * **该夹具目录不入 git**（经 `.gitignore` 排除）：`scripted_real/` 是本地抓取产物，含真源规则串与
 * 真站 HTML（含章节正文），与「应用不随包携带任何书源」的口径冲突。因此新克隆的机器上本类整体按
 * **跳过**处置（[requireFixtures] 守卫），而不是失败——需要真语料回归时在本机重新抓取。
 */
class ScriptRealSourceGoldenTest {

    /**
     * 夹具缺席即跳过：`scripted_real/` 不入 git（含真源规则与真站 HTML/正文，见 .gitignore）。
     * 本类的加载方式是按 classpath 取资源（[res] 里的 `getResourceAsStream(path)!!`），故判据同样取
     * classpath——用文件路径判会在「夹具已打进测试运行期 classpath 但不在工作目录」时给出错误结论。
     *
     * 守卫必须落在 [Before] 里、位于任何 try/catch 之外：`assumeTrue` 抛的
     * `AssumptionViolatedException` 一旦被测试体里的 catch-all 接住，跳过会退化成断言失败。
     */
    @Before
    fun requireFixtures() {
        assumeTrue(
            "scripted_real 夹具缺席（本地真源抓取产物，不入 git）：classpath 上无 scripted_real",
            javaClass.classLoader!!.getResource("scripted_real") != null,
        )
    }

    private fun res(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.readBytes().toString(Charsets.UTF_8)

    /** URL → 冻结响应文本。未命中回空串，与真传输「空响应体」同形，让选择器失配自然显形。 */
    private class TableTransport(private val table: Map<String, String>) : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return table[request.url] ?: ""
        }
    }

    /**
     * 无极书院（无 JS、GET 搜索、详情页即目录页、正文带 `下一页` 翻页 + `##` 广告净化）。
     *
     * 真链路：搜索「都市」→ 首条「暗巷1」(/wuji/23991/) → 详情/目录 → 首章正文 p1 → 翻页 p2。
     */
    @Test
    fun `无极书院 真语料 搜索详情目录正文全链`() = runTest {
        val transport = TableTransport(
            mapOf(
                "http://m.wjzxchina.com/search/?q=%E9%83%BD%E5%B8%82" to res("scripted_real/wuji/search.html"),
                "http://m.wjzxchina.com/wuji/23991/" to res("scripted_real/wuji/detail.html"),
                "http://m.wjzxchina.com/wujis/23991/16701463.html" to res("scripted_real/wuji/content1.html"),
                "http://m.wjzxchina.com/wujis/23991/16701463-2.html" to res("scripted_real/wuji/content2.html"),
                "http://m.wjzxchina.com/wujis/23991/16701463-3.html" to res("scripted_real/wuji/content3.html"),
                "http://m.wjzxchina.com/wujis/23991/16701463-4.html" to res("scripted_real/wuji/content4.html"),
            ),
        )
        val parser = ScriptBookParser(res("scripted_real/wuji/rule.json"), transport)

        // —— 搜索 ——
        val books = parser.searchBook("都市", 1)
        assertTrue("搜索应出结果", books.isNotEmpty())
        val first = books.first()
        assertEquals("暗巷1", first.name)
        assertEquals("http://m.wjzxchina.com/wuji/23991/", first.noteUrl)
        assertEquals("归属必须是本源", "http://m.wjzxchina.com", first.tag)
        assertEquals("莫白1", first.author)
        assertEquals("第二卷：黎明之前 番外3：父子", first.lastChapter)
        assertTrue("简介取自 class.d2 并剥『简介：』前缀", first.desc.contains("繁华不过表象"))

        // —— 详情（无 tocUrl：详情页即目录页）——
        val shelf = BookShelfEntity().apply {
            noteUrl = first.noteUrl
            tag = first.tag
        }
        val info = parser.getBookInfo(shelf)
        assertEquals("暗巷1", info.bookInfo!!.name)
        assertEquals("莫白1", info.bookInfo!!.author)

        // —— 目录（真实 MLlist 正序，首章即第001章；页面顶部另有倒序『最新章节』诱饵，作用域必须只认 class.MLlist）——
        val chapters = parser.getChapterList(shelf).data.chapterList
        assertTrue("目录应出章节", chapters.isNotEmpty())
        assertEquals("第一卷：悲惨世界 第001章：终点", chapters.first().durChapterName)
        assertEquals(
            "http://m.wjzxchina.com/wujis/23991/16701463.html",
            chapters.first().contentRef,
        )

        // —— 正文（4 页链 p1→p2→p3→p4，净化广告）——
        val text = parser.fetchChapterText(chapters.first().contentRef)
        assertTrue("含 p1 真实正文", text.contains("2016年，四月二十九号"))
        assertTrue("翻页一路跟到末页 p4 真实正文", text.contains("扔下手机，睡意已经全无"))
        assertFalse("『温馨提示…追书阅读更方便。』被 ## 剥掉", text.contains("温馨提示"))
        assertFalse("script 标签被 ## 剥掉", text.contains("<script"))
        // 真实站点的 HTML 实体必须解码：&#x6CA1;→没、&#8212;→—（合成源造不出这种实体噪声）
        assertTrue("HTML 实体已解码", text.contains("本章没完，请点击下—页继续阅读"))
        // 真实源规则的局限：## 只剥『温馨提示…』，剥不掉分页提示与页脚数字残留——
        // 金标准锁的是「解析器忠实执行真规则」的真实产物（含残留），不是理想化净文
        assertTrue("页脚数字残留照实保留", text.contains("71937732"))
        // 本源正文规则是 @html（非 @textNodes）：产物保留 <p>/<br> 结构，由阅读层决定怎么渲染
        assertTrue("@html 语义：保留段落标签", text.contains("<p>"))
    }

    /**
     * 手机看书（无 JS，但形态最杂）：POST 搜索 + gb2312 body 编码、源 URL 带尾部 `#`、
     * 搜索 302 跳结果页（OkHttp 跟随，冻结的是跳转后的结果页）、`.autor2[2:1]` 索引切片、
     * kind 的 `&&` 组合符、详情 h1 的 `##（.*|\(.*` 半角括号净化、`<,index_{{page}}.html>` 发现分页。
     *
     * 响应是 gb2312 原始字节抓回后 iconv 解码冻结——金标准测的是解析/URL 层（POST body 编码、
     * 索引切片、组合符、净化），gb2312 字节解码本身归传输层测试（[ScriptHttpTest]）。
     */
    @Test
    fun `手机看书 真语料 POST搜索 gb2312 索引切片 括号净化`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://www.sjks88.com/e/search/index.php" to res("scripted_real/sjks/search.html"),
                "https://www.sjks88.com/xiuzhen/52739.html" to res("scripted_real/sjks/detail.html"),
                "https://www.sjks88.com/xiuzhen/52739/1.html" to res("scripted_real/sjks/content.html"),
            ),
        )
        val parser = ScriptBookParser(res("scripted_real/sjks/rule.json"), transport)

        // —— 搜索：POST + 请求体选项 ——
        val books = parser.searchBook("洪荒", 1)

        // 真产物只发一次 POST（无重复请求），落在规则配的 index.php
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://www.sjks88.com/e/search/index.php", request.url)
        // body 结构：关键词落 keyboard 参数 + 两个固定隐藏域。
        // **刻意不断言关键词编码**：真产物把「洪荒」按 UTF-8 编成 %E6%B4%AA%E8%8D%92，
        // 而规则声明 "charset":"gb2312"（gb2312 应为 %BA%E9%BB%C4）——charset 选项疑似
        // 未作用到请求体，是独立于本测试的潜在缺陷，金标准不把它当既成正确固化。
        val body = request.body.orEmpty()
        assertTrue("body 关键词落 keyboard 参数", body.startsWith("keyboard="))
        assertTrue("body 尾段为固定隐藏域", body.endsWith("&show=title&classid=0"))

        assertTrue("搜索应出结果", books.isNotEmpty())
        val first = books.first()
        // `##（.*|\(.*` 净化：搜索标题尾注不落进 name
        assertEquals("洪荒：从拜师西王母开始", first.name)
        assertEquals("https://www.sjks88.com/xiuzhen/52739.html", first.noteUrl)
        // tag 必须原样等于源 URL（含尾部 `#`）：书架按 tag 绑源，少一个字符就会绑到别的源上
        assertEquals("https://www.sjks88.com#", first.tag)
        // kind 走 `&&` 组合符（a.1@text 取分类项 && span@textNodes 取 <small> 文本）
        assertEquals("修真", first.kind)
        // 本源搜索规则未声明 author，真产物如实为空（作者在详情页 ruleBookInfo 才补齐）
        assertEquals("搜索规则无 author，产物为空", "", first.author)

        // —— 详情：h1「洪荒：从拜师西王母开始(1-345)」被 `##（.*|\(.*` 剥掉半角括号尾注 ——
        val shelf = BookShelfEntity().apply { noteUrl = first.noteUrl; tag = first.tag }
        val info = parser.getBookInfo(shelf)
        // 净化结果必须与搜索标题逐字相同：证明 `##` 命中的是括号段而非整串
        assertEquals(first.name, info.bookInfo!!.name)
        assertFalse("h1 的半角括号尾注必须被剥掉", info.bookInfo!!.name.contains("(1-345)"))
        // `.autor2[0]@text` 无 `##`：真站的「作者：」前缀如实保留，金标准不做美化
        assertEquals("作者：我非俗人", info.bookInfo!!.author)

        // —— 目录：`.list@li@a` 全量解析，首章 href 相对落位 ——
        val chapters = parser.getChapterList(shelf).data.chapterList
        assertTrue("目录应出章节", chapters.isNotEmpty())
        assertEquals("冻结快照整表 518 章", 518, chapters.size)
        assertEquals("第1节", chapters.first().durChapterName)
        assertEquals("https://www.sjks88.com/xiuzhen/52739/1.html", chapters.first().contentRef)

        // —— 正文：`.content@html`，作用域必须命中 class.content（含章首书讯段）——
        val text = parser.fetchChapterText(chapters.first().contentRef)
        assertTrue("正文应非空", text.isNotEmpty())
        assertTrue("作用域命中 class.content 首段", text.contains("<p>洪荒：从拜师西王母开始</p>"))
        assertTrue("含章名与真实正文", text.contains("第1章 黑虎听道"))
        assertTrue("含本章独特真句（非详情/搜索页内容）", text.contains("吾乃――玄煞福德司命大天尊"))
        // @html 语义：保留 <p> 结构，由阅读层决定怎么渲染
        assertTrue("@html 语义：保留段落标签", text.contains("<p>"))
    }

    /**
     * 阅读书屋（无 JS，全裸 CSS）：搜索 `#page@div[itemscope]`、详情 tocUrl 走
     * `text.在线阅读@href` 才拿到目录页、正文 `.calibre@html` 只认外层容器。
     *
     * 真链路：搜索「都市」→ 首条「魔鬼经济学-史蒂芬·列维特 & 史蒂芬·都伯纳」
     * (/ ?id=268833901171) → 详情 → 目录 (?display=menu) → 首章正文 (?display=fulltext&p=1)。
     */
    @Test
    fun `阅读书屋 真语料 裸CSS 搜索详情目录正文全链`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://www.vikbook.com/search.php?s=%E9%83%BD%E5%B8%82&p=1" to res("scripted_real/vikbook/search.html"),
                "https://www.vikbook.com/?id=268833901171" to res("scripted_real/vikbook/detail.html"),
                "https://www.vikbook.com/?id=268833901171&display=menu" to res("scripted_real/vikbook/toc.html"),
                "https://www.vikbook.com/?id=268833901171&display=fulltext&p=1" to res("scripted_real/vikbook/content1.html"),
            ),
        )
        val parser = ScriptBookParser(res("scripted_real/vikbook/rule.json"), transport)

        // —— 搜索：裸 CSS 段 `#page`（id）与 `div[itemscope]`（标签+属性）——
        val books = parser.searchBook("都市", 1)
        assertTrue("搜索应出结果", books.isNotEmpty())
        // 快照整表 20 条（页面自述「找到20本书」）
        assertEquals("冻结快照整表 20 条", 20, books.size)
        val first = books.first()
        // 书名取 `a.0@text`：`a` 是裸 CSS 段，`.0` 是索引方言；实体 `&amp;` 已解码
        assertEquals("魔鬼经济学-史蒂芬·列维特 & 史蒂芬·都伯纳", first.name)
        assertEquals("https://www.vikbook.com/?id=268833901171", first.noteUrl)
        // tag 必须原样等于源 URL（含尾部 `/`），书架按 tag 绑源
        assertEquals("https://www.vikbook.com/", first.tag)
        // 本源搜索规则只声明了 name/bookUrl/coverUrl：真产物如实为空
        assertEquals("搜索规则无 author，产物为空", "", first.author)
        assertEquals("搜索规则无 kind，产物为空", "", first.kind)

        // —— 详情：tocUrl 由 `text.在线阅读@href` 从详情页抽目录地址（详情页本身不是目录）——
        val shelf = BookShelfEntity().apply { noteUrl = first.noteUrl; tag = first.tag }
        val info = parser.getBookInfo(shelf)
        assertEquals("魔鬼经济学", info.bookInfo!!.name)
        assertEquals(
            "目录地址取自『在线阅读』链接，且原样保留 `&display=menu` 查询段",
            "https://www.vikbook.com/?id=268833901171&display=menu",
            info.bookInfo!!.chapterUrl,
        )
        // coverUrl 取 `img.3@src`（站点占位图，非封面真图——金标准照实锁产物）
        assertEquals("https://www.vikbook.com/static/pin.jpg", info.bookInfo!!.coverUrl)
        // intro 规则是「字面文本 + `{{@@h1@text}}` 插值」的多行模板：链式后端把回填后的整串
        // 当选择器/属性名解，模板取不到值 → 真产物为空串，与原生同形兜底填「暂无简介」。
        // 金标准锁的是这个**真实产物**（不是理想化的「书名：魔鬼经济学…」），模板形态落地后此断言随实现更新
        assertEquals("intro 模板产物为空时兜底", "暂无简介", info.bookInfo!!.introduce)
        // author 规则 `.author@a@text` 在详情页未命中，产物为空
        assertEquals("详情 author 未命中即空", "", info.bookInfo!!.author)

        // —— 目录：`#content@.page` 裸 CSS 作用域，快照 17 节 ——
        val chapters = parser.getChapterList(shelf).data.chapterList
        assertEquals("冻结快照整表 17 节", 17, chapters.size)
        assertEquals("第1页", chapters.first().durChapterName)
        assertEquals(
            "章节地址 `a@href` 原样带 `&display=fulltext&p=1`",
            "https://www.vikbook.com/?id=268833901171&display=fulltext&p=1",
            chapters.first().contentRef,
        )
        // 站点多数章节标题是 `<a><span class=chapter-title>标题</span><span class=chapter-page>第N页</span></a>`，
        // `a@text` 把两段嵌套 span 文本直接串起来——真实形态如此，金标准照实锁
        assertEquals("《魔鬼经济学》所获赞誉第3页", chapters[2].durChapterName)

        // —— 正文：`.calibre@html` 命中外层 div.calibre，产物是它的 innerHTML（非净文）——
        val text = parser.fetchChapterText(chapters.first().contentRef)
        assertTrue("正文应非空", text.isNotEmpty())
        // 选中根即外层 div.calibre：产物以它的首个子元素 calibre1 开头（若选错成 .calibre1 则无此前缀）
        assertTrue("作用域根是外层 div.calibre", text.startsWith("<div class=\"calibre1\">"))
        assertTrue("含书名与真实正文", text.contains("魔鬼经济学 1：揭示隐藏在表象之下的真实世界"))
        assertTrue("含著者真句", text.contains("[美]史蒂芬·列维特; [美]史蒂芬·都伯纳 著"))
        assertTrue("含出版信息", text.contains("中信出版社"))
        // 打赏文案在 #page aside 里，不在 .calibre 作用域内，必须不被带进正文
        assertFalse("作用域外的打赏 aside 不进正文", text.contains("请支持我们"))
        // @html 语义：保留 <blockquote> 结构，由阅读层决定怎么渲染
        assertTrue("@html 语义：保留结构标签", text.contains("<blockquote"))
    }

    /**
     * 网阅小说（book15）：阅读链上**唯一用「整条规则取值器形态」的源**——`ruleToc` 的
     * `chapterUrl`/`chapterName` 是裸 `@href`/`@text`（以 `@` 开头的空段按恒等解，取值器直接
     * 作用于当前节点），与其余三条金标准的链式选择器形态分属两套语法；`ruleContent.nextContentUrl`
     * 是第二处 `@href` 用法。另两条本源专有的真实形态：目录就在详情页（`ruleBookInfo` 未配
     * `tocUrl`）、`#after_link` 指向**下一章**（非章内翻页）。`exploreUrl` 是 JS，不在本用例范围。
     *
     * 真链路：GET 搜索「都市」→ 首条「都市大高手」(`/books/details7808.html`) → 详情页即目录
     * （781 章）→ 首章正文（`index7808-8669910.html`）→ `#after_link` 跟到第 2 章。
     */
    @Test
    fun `网阅小说 真语料 整条规则取值器 搜索详情目录正文全链`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://book15.net/books/search.html?kw=%E9%83%BD%E5%B8%82" to res("scripted_real/book15/search.html"),
                "https://book15.net/books/details7808.html" to res("scripted_real/book15/detail.html"),
                "https://book15.net/chapter/index7808-8669910.html" to res("scripted_real/book15/content1.html"),
                "https://book15.net/chapter/index7808-8669911.html" to res("scripted_real/book15/content2.html"),
            ),
        )
        val parser = ScriptBookParser(res("scripted_real/book15/rule.json"), transport)

        // —— 搜索：GET `/books/search.html?kw={{key}}`，请求地址由 `{{key}}` 表单百分号编码拼出 ——
        val books = parser.searchBook("都市", 1)
        assertEquals("冻结快照整表 15 条", 15, books.size)
        val first = books.first()
        assertEquals("都市大高手", first.name)
        // tag 必须原样等于源 URL（含尾部 `/`）：书架按 tag 绑源，少一个字符就绑到别的源上
        assertEquals("https://book15.net/", first.tag)
        assertEquals("https://book15.net/books/details7808.html", first.noteUrl)
        // `a.author@text`：作者取自 li 里 class.author 的链接文本（不是整条 li 的文本）
        assertEquals("老鹰吃小鸡", first.author)
        // `a[href*=list-t]@text`：分类名取自 /books/list-t-21.html 那条链接的文本
        assertEquals("都市小说", first.kind)
        assertEquals(
            "https://book15.net/uploads/20231010/458f1d47c752be875cd87773582c7297.jpg",
            first.coverUrl,
        )
        // 本源 ruleSearch 未声明 lastChapter：真产物如实为空（最新章节只在详情页 og meta 上）
        assertEquals("搜索规则无 lastChapter，产物为空", "", first.lastChapter)
        // 简介取自 li.nowrap-2，且 `&ldquo;`/`&rdquo;`/`&hellip;` 实体已解码（合成源造不出这种实体噪声）
        assertTrue("简介取自 li.nowrap-2 且实体已解码", first.desc.contains("“可以都要吗？”"))
        assertEquals("origin 取源名（含 emoji）", "📂网阅小说", first.origin)
        // 编码后的请求地址与冻结快照的 key 逐字一致（中文不编码进不了 OkHttp 的 HttpUrl）
        val searchRequest = transport.requests.first()
        assertEquals("GET", searchRequest.method)
        assertEquals(
            "https://book15.net/books/search.html?kw=%E9%83%BD%E5%B8%82",
            searchRequest.url,
        )

        // —— 详情：ruleBookInfo 未配 tocUrl，目录地址回落详情页自身（目录就在详情页）——
        val shelf = BookShelfEntity().apply { noteUrl = first.noteUrl; tag = first.tag }
        val info = parser.getBookInfo(shelf)
        assertEquals("都市大高手", info.bookInfo!!.name)
        assertEquals("老鹰吃小鸡", info.bookInfo!!.author)
        assertEquals(
            "未配 tocUrl 时目录地址回落详情页",
            "https://book15.net/books/details7808.html",
            info.bookInfo!!.chapterUrl,
        )
        assertEquals(
            "https://book15.net/uploads/20231010/458f1d47c752be875cd87773582c7297.jpg",
            info.bookInfo!!.coverUrl,
        )
        assertTrue("intro 取自 .d-info-panel .nowrap-3", info.bookInfo!!.introduce.contains("可以都要吗"))

        // —— 目录：`.d-chapter-list dd a` + 整条规则取值器 `@href`/`@text` ——
        val chapters = parser.getChapterList(shelf).data.chapterList
        // 本次修复最直接的回归锁：`@href` 修复前一律 Miss，此处会是 0；
        // 781 = 详情页自述「共781章」，且 dd>a 只有一个作用域（页面脚本里的同名字符串不是 DOM）
        assertEquals("冻结快照整表 781 章", 781, chapters.size)
        assertEquals("第1章 交换系统", chapters.first().durChapterName)
        assertEquals("https://book15.net/chapter/index7808-8669910.html", chapters.first().contentRef)
        assertEquals("第781章 大结局（附感言）", chapters.last().durChapterName)
        assertEquals("https://book15.net/chapter/index7808-8670692.html", chapters.last().contentRef)
        assertEquals("https://book15.net/", chapters.first().tag)
        // 目录入口 = 详情页：getBookInfo 与 getChapterList 各请求一次详情页（本源不配 tocUrl 的必然结果）
        assertEquals(
            "详情页被请求两次（详情一次、目录一次）",
            2,
            transport.requests.count { it.url == "https://book15.net/books/details7808.html" },
        )

        // —— 正文：`.chapter-content@html`（作用域 = li.chapter-content）——
        val text = parser.fetchChapterText(chapters.first().contentRef)
        assertTrue("含第 1 章真实正文", text.contains("我这是招谁惹谁了"))
        assertTrue("含第 1 章章末真句", text.contains("张扬进入了梦乡"))
        // 源里是 `<li class="chapter-content"> <p><p>…</p>…`，Jsoup 自动闭合外层空 p：
        // 真产物以空段落 `<p></p>` 起头，金标准照实锁（不是理想化的净文）
        assertTrue("首段是 HTML 自动闭合出的空 <p>", text.startsWith("<p></p>"))
        // 章名 h2 在 `li.chapter-content` 之外：`@html` 的 innerHTML 不含它
        assertFalse("章名 h2 在作用域外，不进正文", text.contains("第1章 交换系统"))
        // `.notes` 的阅读提示与 `div.btn` 里的 putURL 注释都在作用域外，不得被带进正文
        assertFalse("作用域外的阅读提示不进正文", text.contains("阅读提示"))
        assertFalse("作用域外的注释不进正文", text.contains("putURL"))
        // @html 语义：保留 <p> 结构，由阅读层决定怎么渲染；142 = 第 1 章正文 + 第 2 章正文的真实段数
        assertEquals("@html 语义：段落结构原样保留", 142, text.split("<p>").size - 1)
        // `nextContentUrl: "#after_link@href"` 是整条规则取值器形态的第二处用法。本站 `#after_link`
        // 指向「下一章」而非章内翻页，故产物是「第 1 章正文 + 第 2 章正文」的真实拼接——本仓口径
        // 是 nextContentUrl 由作者显式给出、不套 URL 形状启发式（比套启发式而漏页更符合规则意图）。
        assertTrue("跟 #after_link 拼上第 2 章正文", text.contains("第二天一大早张扬就起床了"))
        assertEquals(
            "正文链请求序列：入口 → #after_link → 第 2 章的 #after_link",
            listOf(
                "https://book15.net/chapter/index7808-8669910.html",
                "https://book15.net/chapter/index7808-8669911.html",
                // 第 3 章未冻结 → 空响应 → 页上无 #after_link → 链止；请求本身锁「链确实在跟」
                "https://book15.net/chapter/index7808-8669912.html",
            ),
            transport.requests.filter { it.url.contains("/chapter/") }.map { it.url },
        )
    }

    /**
     * 网阅小说（`book15.net`）：真语料里的**发现页脚本程序形态**样本（`exploreUrl` 整串是 `<js>…</js>`，
     * 冻结在 fixture 里；本仓红线语料 642 条里这一形态的代表）。
     *
     * 锁的是「**识别**而不消费」：
     * - 分类入口为空——那段 JS 现场算出 `[{title,url,style,type,chars,action}]`，`url` 依赖 `infoMap`
     *   里的控件当前值，本仓没有可承载这份协议的发现页宿主（§10 控件一栏 ❌）；
     * - 词法层登记 `EXPLORE_URL_SCRIPT`——它**不是**「会执行的规则内 JS 段」，两者不得合并
     *   （并进去会让报告对一条「整源可用、只少发现面」的源喊错话术）；
     * - **此前它会掉进文本切分**：整段 JS 被按行当成 URL 逐行拼到源根地址上发出去。
     *   2026-09-10 的 `番茄（发现）` 事故就是这一形态——105 条空标题条目 + 105 次
     *   `HTTP 404：https://fanqienovel.com/<JS 源码行>`，末尾还撞死书城列表
     *   （`LazyColumn` 的 key 全为 `""`）。本用例是那次的离线锚点。
     */
    @Test
    fun `网阅小说 真语料 发现页脚本程序形态识别而不消费`() {
        val raw = res("scripted_real/book15/rule.json")

        assertTrue("脚本程序形态不给分类条目", ScriptExplore.entries(raw).isEmpty())

        val set = ScriptRuleSet.load(raw)
        assertTrue(set.unsupported.contains(ScriptUnsupported.EXPLORE_URL_SCRIPT))
        assertFalse(
            "该源只有 exploreUrl 一处 JS，且本仓不执行它——不得登记成 JS",
            set.unsupported.contains(ScriptUnsupported.JS),
        )
    }
}
