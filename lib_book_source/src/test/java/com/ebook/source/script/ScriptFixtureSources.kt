package com.ebook.source.script

/**
 * 端到端合成源（编排层补充，不再是金标准唯一顶位）。
 *
 * 覆盖 ADR-0029 决策 8 要求的四类证据形态中可离线做的三类：HTML 链式型、JSONPath API 型、
 * `<>` 分页型。**真实站点真响应的金标准已就位**——`ScriptRealSourceGoldenTest` +
 * `src/test/resources/scripted_real/` 冻结了四条声明式源（无极书院/手机看书/阅读书屋/网阅小说）
 * 的真规则串与真响应，由它锁「上游真实规则在真实 HTML 上解得对不对」；本文件退为
 * **解析器编排行为**的补充覆盖：URL 渲染 → 取文 → 字段提取 → 目录链 → 正文链 → 净化，
 * 一环断了这里就红。真实源语义的旧债（语料不在原路径）已由那批真语料金标准偿还；含 JS 的重源
 * 仍走真机临时验证（见 `docs/test-coverage-todo.md` 第 7 项），不转永久夹具。
 *
 * gbk 的字节解码属 OkHttp 传输层，假 transport 只能断言 charset 进了请求，真机验证归同一清单。
 *
 * 每个源都是最小形态：只写走通链路必需的字段，多余字段会让「哪个规则撑住了哪一步」失真。
 *
 * 注意区分两份夹具体的入仓口径：本文件里的合成源是**内联字符串**，永远在场、不受任何外部文件影响；
 * 而上面提到的 `scripted_real/` 目录**不入 git**（含真源规则串与真站章节正文，是本地抓取产物，
 * 见 `.gitignore`），`ScriptRealSourceGoldenTest` 因此带缺席守卫——夹具不在时那条链按跳过处置。
 */
internal object ScriptFixtureSources {

    /** HTML 链式型：GET 搜索、链式列表、详情、字符串规则目录翻页、正文翻页 + 净化 */
    val htmlChain: String = """
        {"bookSourceUrl":"https://www.example.com","bookSourceName":"HTML链式型",
         "searchUrl":"/search?key={{key}}&page={{page}}",
         "ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href",
           "author":"tag.p@text","coverUrl":"tag.img@src"},
         "ruleBookInfo":{"name":"tag.h1@text","author":"class.info@text","intro":"class.intro@text",
           "coverUrl":"tag.img@src","tocUrl":"class.catalog@href"},
         "ruleToc":{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
           "nextTocUrl":"class.next@href"},
         "ruleContent":{"content":"id.content@textNodes","nextContentUrl":"class.next@href",
           "replaceRegex":"##\n本章未完.*继续阅读##"}}
    """.trimIndent()

    /** JSONPath API 型：POST 搜索 + JSON 响应，详情/目录/正文全 JSONPath */
    val jsonApi: String = """
        {"bookSourceUrl":"https://api.example.com","bookSourceName":"JSONAPI型",
         "searchUrl":"/v1/books,{\"method\":\"POST\",\"body\":\"kw={{key}}&pn={{page}}\"}",
         "ruleSearch":{"bookList":"$.data.books","name":"$.name","author":"$.author","bookUrl":"$.url",
           "coverUrl":"$.cover","intro":"$.summary"},
         "ruleBookInfo":{"name":"$.name","author":"$.author","intro":"$.summary","tocUrl":"$.tocUrl"},
         "ruleToc":{"chapterList":"$.chapters","chapterName":"$.title","chapterUrl":"$.url"},
         "ruleContent":{"content":"$.content"}}
    """.trimIndent()

    /** `<>` 分页型：搜索首页不带页码段（§6.4 本仓规定的形态②），目录为字符串规则入口回落 */
    val anglePage: String = """
        {"bookSourceUrl":"https://www.example.com","bookSourceName":"尖括号分页型",
         "searchUrl":"/s<,{{page}}>",
         "ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href"},
         "ruleToc":{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
           "nextTocUrl":["https://www.example.com/toc1.html","https://www.example.com/toc2.html"]},
         "ruleContent":{"content":"id.content@textNodes"}}
    """.trimIndent()
}
