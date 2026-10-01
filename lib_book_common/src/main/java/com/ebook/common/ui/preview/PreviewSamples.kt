package com.ebook.common.ui.preview

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceDefinition
import com.ebook.common.domain.DuplicateBookDetector
import com.ebook.common.domain.ParsedBookMeta
import com.ebook.common.importer.ImportDuplicateState
import com.ebook.common.domain.BookComment
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.SearchHistoryEntity
import java.io.File

/**
 * 预览与非设备渲染环境共用的**样例数据出口**。
 *
 * 为什么要有这一个文件而不是各页预览函数里手搓实体：
 *
 * 1. **一份数据多种形态**——书架条目卡是三行版式，渲染要吃得下 `bookInfo` 与 `chapterList`
 *    这层嵌套（[BookShelfEntity.bookInfo] 与 [BookShelfEntity.chapterList] 都是 `@Ignore`
 *    字段，由 UI 层填充）。手搓时最容易只填外层、里面留 null 或空列表，于是预览"过得去"
 *    而画出来的是半截条目，据此判断布局就是错的。这里的构造器一律把三级图填满。
 * 2. **预览与渲染冒烟测试同源**——每模块的 Robolectric 渲染测试组合的是同一个组件 + 同一份
 *    样例。两边各写一遍必然漂移：测试绿着、预览却是坏的（或反之）。
 * 3. **归属**：这里全是 ebook 域类型（书架条目、书源定义），本就属 `lib_book_common`；
 *    六个 UI 模块都依赖它，于是一份样例全仓复用，不会出现同一个书名在四个模块有四份假数据。
 *
 * **封面地址取不到图是预期行为**：预览与非设备环境里没有可联网的图像加载，[BookCover] 一类
 * 组件会落到内置兜底封面。这恰好是最该看的一档——真实站点里就是有一批书没有封面。
 * 想看"有封面"的观感只能在设备上确认。
 *
 * 书名与作者一律用**虚构**词条：借真实作品名会让"这只是排版示例"与"这是本真书"两种含义
 * 混在一起，而长标题、空简介这些边界形态需要频繁改写书名，用虚构名改起来没有心理负担。
 */

/** 样例书源归属 URL：`book_shelf.tag`、`book_info.tag` 与章节行的 tag 都取它（多书源下这是解析归属的唯一依据） */
const val SAMPLE_SOURCE_URL: String = "https://sample-source.example.com"

/** 样例封面地址：预览环境取不到，落内置兜底图 */
const val SAMPLE_COVER_URL: String = "https://cover.sample.example.com/covers/1.jpg"

/**
 * 样例头像地址：与 [SAMPLE_COVER_URL] 同一口径——预览与非设备渲染环境里没有可联网的图像加载，
 * [com.ebook.common.ui.Avatar] 因此走「取不到图」那一档兜底。
 *
 * 刻意不给空串：空 URL 走的是「没有头像」那条分支（直接画默认图），而这个串走的是「有地址但加载
 * 失败」那条（加载中 → 兜底）。评论区里真实存在的正是后者，只拍前者就看不见那条路径。
 */
const val SAMPLE_AVATAR_URL: String = "https://avatar.sample.example.com/1.png"

/**
 * 样例会话用户 id：评论「本人判定」的凭据。
 *
 * 章节评论区决定「这一条能不能长按删除」比的是 `userId`（不是展示名），预览与渲染冒烟测试
 * 要用同一个值当「我自己」，两边各写一个数字就会各验各的。
 */
const val SAMPLE_CURRENT_USER_ID: Long = 7L

/** 样例他人用户 id：与 [SAMPLE_CURRENT_USER_ID] 不同，用来拍「他人评论不给删除入口」那一档 */
const val SAMPLE_OTHER_USER_ID: Long = 42L

private val sampleBookTitles = listOf("山海拾遗", "长安小吏", "星轨编年史", "长夜将尽", "雾都手记")
private val sampleBookAuthors = listOf("临渊客", "沈观", "北川 editor", "林间煮酒", "顾遥遥")
private val sampleChapterSuffixes = listOf("启程", "北望", "旧事", "风起", "归途", "长明")
private val sampleKindNames = listOf("玄幻", "都市", "科幻", "历史", "悬疑", "武侠")
private val sampleUsernames = listOf("临渊读者", "北岸观星", "煮酒客", "雾都夜归人", "长明灯下")

/**
 * 第 [index]（从 1 起）本书的完整书架条目：外层进度与归属、`bookInfo`、`chapterList` 三层都填满。
 *
 * @param index 轮转样例书名/作者，让列表预览里每一行都不一样（同名相邻行看不出省略与等高）
 * @param name 书名，可传入超长串检查省略号形态
 * @param chapters 目录条数；同时决定底行「第 N 章 …」里显示的是哪一章
 * @param durChapter 已读到第几章（0 表示还没开始读，底行走「未开始」形态）
 */
fun sampleShelfBook(
    index: Int = 1,
    name: String = sampleTitle(index),
    author: String = sampleAuthor(index),
    coverUrl: String = SAMPLE_COVER_URL,
    introduce: String = "山与海之间遗落的旧事，一册写完便再寻不到第二份。",
    chapters: Int = 128,
    durChapter: Int = 127,
    lastChapterName: String = "第 $chapters 章 ${sampleChapterSuffix(chapters)}",
): BookShelfEntity {
    val noteUrl = "$SAMPLE_SOURCE_URL/book/$index"
    return BookShelfEntity(
        noteUrl = noteUrl,
        durChapter = durChapter,
        finalDate = 1_700_000_000_000L + index * 86_400_000L,
        tag = SAMPLE_SOURCE_URL,
        bookInfo = BookInfoEntity(
            name = name,
            tag = SAMPLE_SOURCE_URL,
            noteUrl = noteUrl,
            coverUrl = coverUrl,
            author = author,
            introduce = introduce,
            origin = "样例原生源",
            status = if (durChapter >= chapters) "完结" else "连载中",
            chapterList = sampleChapters(noteUrl, chapters),
        ),
        chapterList = sampleChapters(noteUrl, chapters),
    )
}

/** 多册书架条目，供列表/网格预览：书名、进度、有无简介都逐行变化 */
fun sampleShelfBooks(count: Int = 3): List<BookShelfEntity> =
    List(count) { i ->
        sampleShelfBook(
            index = i + 1,
            // 让每本的进度不同：全填一样的进度就只看得到一种底行形态
            durChapter = (i + 1) * 17,
            introduce = if (i % 2 == 0) "山与海之间遗落的旧事，一册写完便再寻不到第二份。" else "",
        )
    }

/**
 * 搜索/书城结果条目。[add] 决定右上角是「加书架」还是已加入形态，[desc] 决定中间行是简介还是只有进度行。
 *
 * [origin] 是书源归属名：换源候选行把它单独画成一枚胶囊（换源换的就是源），
 * 因此预览需要「源名长到会挤掉作者」这一档时靠它传值，不要手搓整个实体。
 */
fun sampleSearchBook(
    index: Int = 1,
    name: String = sampleTitle(index),
    author: String = sampleAuthor(index),
    add: Boolean = false,
    desc: String = "山与海之间遗落的旧事。",
    lastChapter: String = "第 96 章 ${sampleChapterSuffix(index)}",
    origin: String = "样例原生源",
): SearchBookEntity = SearchBookEntity(
    noteUrl = "$SAMPLE_SOURCE_URL/book/$index",
    coverUrl = SAMPLE_COVER_URL,
    name = name,
    author = author,
    words = 1_283_000L + index * 41_000L,
    state = if (index % 3 == 0) "完结" else "连载中",
    lastChapter = lastChapter,
    add = add,
    tag = SAMPLE_SOURCE_URL,
    kind = "玄幻",
    origin = origin,
    desc = desc,
)

/** 多条搜索结果，供搜索结果列表预览 */
fun sampleSearchBooks(count: Int = 4): List<SearchBookEntity> =
    List(count) { i -> sampleSearchBook(index = i + 1, add = i % 3 == 0) }

/** 书城一个分类区块（标题 + 该分类下的书目） */
fun sampleKindSection(
    kindName: String = "玄幻",
    bookCount: Int = 4,
): LibraryKindBookListEntity = LibraryKindBookListEntity(
    kindName = kindName,
    kindUrl = "$SAMPLE_SOURCE_URL/xuanhuan/{{page}}",
    books = sampleSearchBooks(bookCount),
)

/**
 * 书城首页的分类区块列表。
 *
 * 区块标题按 [count] 轮转真实分类名：给成「分类 1 / 分类 2」会让"标题宽度、区块间距"这类
 * 只能靠观感判断的东西失去意义。
 */
fun sampleKindSections(
    count: Int = 3,
    booksPerSection: Int = 4,
): List<LibraryKindBookListEntity> = List(count) { i ->
    sampleKindSection(
        kindName = sampleKindNames[i % sampleKindNames.size],
        bookCount = booksPerSection,
    )
}

/**
 * 章节目录行。
 *
 * [contentRef] 是主键（本地书是章文件相对路径、网络书是章节 URL），这里按网络书给；
 * `durChapterIndex` 连续递增，不模拟"删过章留下的洞"——那种形态要单独传一份不连续的列表。
 */
fun sampleChapters(noteUrl: String = "$SAMPLE_SOURCE_URL/book/1", count: Int = 128): List<ChapterListEntity> =
    List(count) { i ->
        ChapterListEntity(
            noteUrl = noteUrl,
            durChapterIndex = i + 1,
            contentRef = "$noteUrl/chapter/${i + 1}",
            durChapterName = "第 ${i + 1} 章 ${sampleChapterSuffix(i + 1)}",
            tag = SAMPLE_SOURCE_URL,
        )
    }

/** 搜索历史词条 */
fun sampleSearchHistories(count: Int = 3): List<SearchHistoryEntity> =
    List(count) { i ->
        SearchHistoryEntity(
            id = (i + 1).toLong(),
            type = 0,
            content = listOf("山海拾遗", "长安小吏 临渊客", "长篇书名会占满一整行所以这里给一条足够长的")[i % 3],
            date = 1_700_000_000_000L - i * 3_600_000L,
        )
    }

/**
 * 原生规则书源（[SourceDefinition.Native]）：默认源载体的一种出身，书城切换器与引导态要能预览它。
 *
 * 只填展示需要的字段，规则选择器留空——预览里没有网络，规则内容不参与渲染。
 */
fun sampleNativeSource(
    name: String = "样例原生源",
    url: String = SAMPLE_SOURCE_URL,
): SourceDefinition.Native = SourceDefinition.Native(
    rule = BookSourceRule(
        name = name,
        url = url,
        group = "小说",
        searchUrl = "$url/search.aspx?key={{key}}",
    ),
)

/**
 * 脚本书源（[SourceDefinition.Script]）：默认源载体的另一种出身。
 *
 * 展示信息取自实体列而非 `rule_json`（生产里也只有 `toDefinition` 这一处填它们），
 * 所以这里 [rawJson] 只需要是一段形状正确的社区格式原文，不参与任何渲染取值。
 */
fun sampleScriptSource(
    name: String = "样例脚本书源",
    url: String = "$SAMPLE_SOURCE_URL/script",
): SourceDefinition.Script = SourceDefinition.Script(
    rawJson = """
        {
          "bookSourceUrl": "$url",
          "bookSourceName": "$name",
          "bookSourceType": 0,
          "bookSourceGroup": "小说",
          "enabled": true,
          "searchUrl": "$url/search/{{key}}={{page}}",
          "ruleSearch": {
            "bookList": ".result-item",
            "bookName": ".title@text",
            "author": ".author@text"
          }
        }
    """.trimIndent(),
    name = name,
    url = url,
)

private fun sampleTitle(index: Int): String = sampleBookTitles[(index - 1).coerceAtLeast(0) % sampleBookTitles.size]

private fun sampleAuthor(index: Int): String =
    sampleBookAuthors[(index - 1).coerceAtLeast(0) % sampleBookAuthors.size]

private fun sampleChapterSuffix(index: Int): String =
    sampleChapterSuffixes[(index - 1).coerceAtLeast(0) % sampleChapterSuffixes.size]

// ── 本地书籍导入页 ────────────────────────────────────────────────────────────

/**
 * 样例存储根路径：[sampleLocalBookFiles] 的假文件都挂在这个前缀下。
 *
 * **不取** `Environment.getExternalStorageDirectory()`：这个门面保持纯 JVM（无 Android 依赖），
 * 于是预览宿主与非设备渲染环境不会因为 Android 静态状态在不同机器上给出不同根路径，
 * 让「路径那一行显示成什么」变成不可比的东西。
 */
const val SAMPLE_LOCAL_STORAGE_ROOT: String = "/storage/emulated/0"

private val sampleLocalFileNames =
    listOf("山海拾遗.txt", "长安小吏.txt", "星轨编年史.txt", "长夜将尽.txt", "雾都手记.txt")

/**
 * 本地导入列表的样例文件：**只造路径、不落磁盘**。
 *
 * [com.ebook.common.util.formatSize] 读的是 `File.length()`，路径不存在时那一格恒为 `0 B`——
 * 这是预期形态而不是漏填：设计期渲染环境不该做 IO，而「0 字节的空文件」在真实扫描结果里
 * 本来就存在。条目要看的排版是「文件名省略 + 体积贴右 + 路径回落」，三者只由路径决定。
 *
 * **路径那一行在 Windows 宿主的预览里会显示成宿主风格**：`ImportBookItem` 取的是
 * `File.absolutePath`，它在 Windows 上会把 `/storage/emulated/0/…` 补成 `D:\storage\emulated\0\…`，
 * 于是「把存储根换成"存储空间"文案」那一档换不掉。真机上是命中替换的那一档，只能在设备上确认。
 */
fun sampleLocalBookFiles(count: Int = 3): List<File> =
    List(count) { i ->
        File("$SAMPLE_LOCAL_STORAGE_ROOT/download/${sampleLocalFileNames[i % sampleLocalFileNames.size]}")
    }

/**
 * 判重处置框的样例命中项：默认给「一条本地 + 一条网络」两行。
 *
 * 两条都要给的理由：处置框里的「智能合并」只在**存在本地命中**时才出现（补章的载体是本机章
 * 文件，网络书的正文在书源那边）。只造一种就永远看不见那一档的有与无。
 *
 * [hasLocal] 传 false 即「命中项全是网络书」那一档，处置框应只剩三个动作。
 */
fun sampleDuplicateMatches(
    title: String = sampleTitle(1),
    author: String = sampleAuthor(1),
    hasLocal: Boolean = true,
): List<DuplicateBookDetector.ImportMatch> = buildList {
    if (hasLocal) {
        add(
            DuplicateBookDetector.ImportMatch(
                // 本地书的定位符就是章文件所在的导入路径，不是 http 地址
                noteUrl = "$SAMPLE_LOCAL_STORAGE_ROOT/download/${sampleLocalFileNames.first()}",
                title = title,
                author = author,
                isLocal = true,
            )
        )
    }
    add(
        DuplicateBookDetector.ImportMatch(
            noteUrl = "$SAMPLE_SOURCE_URL/book/1",
            title = title,
            author = author,
            isLocal = false,
        )
    )
}

/**
 * 判重处置框的完整状态（[ImportDuplicateState.Detected]）：待导入文件 + 解析出的元数据 + 命中清单。
 *
 * 三样缺一不可：处置框的标题句取 `meta.title`、命中行取 `matches`、而「智能合并」那一档由
 * `matches` 里有没有本地命中决定。只在外层拼一半会让预览画出一张缺行的对话框，而据此判断
 * 布局就是错的——所以预览与渲染冒烟测试一律从这里取，不在页面里手搓 `Detected(...)`。
 *
 * @param hasLocalMatch false 即「命中项全是网络书」，处置框不该出现「智能合并」
 */
fun sampleImportDuplicate(
    hasLocalMatch: Boolean = true,
    title: String = sampleTitle(1),
    author: String = sampleAuthor(1),
): ImportDuplicateState.Detected = ImportDuplicateState.Detected(
    file = sampleLocalBookFiles(1).first(),
    meta = ParsedBookMeta(title = title, author = author),
    matches = sampleDuplicateMatches(title = title, author = author, hasLocal = hasLocalMatch),
)

// ── 章节评论区 / 下载队列 ─────────────────────────────────────────────────────

/**
 * 一条章节评论。
 *
 * 两处取值是「按契约编」的，改动前先看理由：
 * - `addTime` 给服务端契约形态 `yyyy-MM-dd HH:mm:ss`：展示走
 *   [com.ebook.common.domain.CommentTime.displayText]，它的口径是「解析不出就给空串、让 UI 留白
 *   而不是显示脏值」。给一个看着像但解不出的串，预览里那片留白就会被当成布局 bug 去修。
 *   想拍「时间解不出」那一档，就显式传一个不合契约的串（见 [sampleComments] 的末条）。
 * - `userId` 默认 [SAMPLE_CURRENT_USER_ID]：评论条目只在「本人」时给长按删除入口，
 *   样例必须能被判成本人，否则预览里看不见那条删除确认框。
 *
 * @param index 轮转用户名/书名/章节名，让列表预览里每一行都不一样
 * @param avatar 默认 [SAMPLE_AVATAR_URL]（取不到图 → 走兜底），传空串即「没有头像」那一档
 */
fun sampleComment(
    index: Int = 1,
    userId: Long = SAMPLE_CURRENT_USER_ID,
    username: String = sampleUsername(index),
    avatar: String = SAMPLE_AVATAR_URL,
    bookName: String = sampleTitle(index),
    chapterIndex: Int = index * 12,
    content: String = "这一章把山海两地的对照写完了，伏笔埋在最后一句。",
    addTime: String = "2026-09-30 16:04:21",
): BookComment {
    val noteUrl = "$SAMPLE_SOURCE_URL/book/$index"
    return BookComment(
        id = index.toLong(),
        userId = userId,
        username = username,
        avatar = avatar,
        commentKey = noteUrl,
        chapterUrl = "$noteUrl/chapter/$chapterIndex",
        chapterName = "第 $chapterIndex 章 ${sampleChapterSuffix(chapterIndex)}",
        bookName = bookName,
        content = content,
        addTime = addTime,
    )
}

/**
 * 评论列表样例：本人与他人**交替**出现，长内容与解不出的时间落在末条。
 *
 * 交替不是为了好看：删除入口只由 `userId` 决定，两种出身不在同一屏里同时出现，就永远看不出
 * 「这一条是我的、那一条不是」有没有被判错——而判错了不报错，只是把别人的评论给了删除按钮。
 * 末条给超长内容（条目卡正文不限行数，只靠卡片自己长高）与一个不合契约的时间串，
 * 这两处都是「少看一眼就可能写歪」的形态。
 */
fun sampleComments(count: Int = 3): List<BookComment> = List(count) { i ->
    val index = i + 1
    // 本人与他人交替：判错的形态是「把别人的评论给了删除按钮」，只有一种出身就看不见
    val userId = if (i % 2 == 0) SAMPLE_CURRENT_USER_ID else SAMPLE_OTHER_USER_ID
    if (i == count - 1) {
        sampleComment(
            index = index,
            userId = userId,
            content = "评论内容很长的一段：这里连着写下去，用来看条目卡被长正文撑高之后，" +
                "同列其他条目的间距与头像对齐有没有跟着跑偏。补齐到足够行数才看得出效果，" +
                "所以这一段还会再续几句，直到它明显超过普通评论的两三行。",
            // 不合契约的时间串：displayText 的口径是给空串、UI 留白，而不是把脏值原样显示
            addTime = "2026-09-30",
        )
    } else {
        sampleComment(index = index, userId = userId)
    }
}

/**
 * 下载队列里的一行任务（`download_chapter`）：下载管理页的「正在下载 第 N 章」副行、
 * 二级选章页的在途章都取它。
 *
 * `id` 留 0：自增主键由库分配，预览与渲染都不落库，填一个假 id 只会让人以为队列行有可读的主键
 * 可断言。`forceRefresh` 同样不填——它不参与任何展示，只影响服务侧「命中缓存也重抓」的取舍。
 */
fun sampleDownloadChapter(
    index: Int = 1,
    noteUrl: String = "$SAMPLE_SOURCE_URL/book/1",
    bookName: String = sampleTitle(1),
    coverUrl: String = SAMPLE_COVER_URL,
    chapterIndex: Int = index,
    chapterName: String = "第 $chapterIndex 章 ${sampleChapterSuffix(chapterIndex)}",
): DownloadChapterEntity = DownloadChapterEntity(
    noteUrl = noteUrl,
    durChapterIndex = chapterIndex,
    durChapterUrl = "$noteUrl/chapter/$chapterIndex",
    durChapterName = chapterName,
    tag = SAMPLE_SOURCE_URL,
    bookName = bookName,
    coverUrl = coverUrl,
)

private fun sampleUsername(index: Int): String =
    sampleUsernames[(index - 1).coerceAtLeast(0) % sampleUsernames.size]
