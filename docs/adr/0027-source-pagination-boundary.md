# 书源分页与相对地址落位：兑现章节分页，落位基准归当前页，删除未兑现声明

Status: accepted

书源规则 schema 是书源作者能看到的唯一界面——schema 声明了而解析器不认的字段，作者配了就是静默无效（无报错、无日志、无行为）。2026-09-08 的评审会话（由「导入书源只出 100 章」的截断问题触发）逐项裁决：每一条声明要么兑现、要么删除。页内相对地址的落位基准也在这一篇裁决：归**当前页**，不归源根。

## 背景

多书源架构落地时遗留了一批「声明了但解析器不消费」的分页字段。审计发现的脱节点：

- `TocRule.pageUrl` / `TocRule.nextPage`：章节索引分页的两个字段从未被解析器读取——站点目录分多页时只拿到第一页（即 100 章截断的直接成因）；
- `PageRule.tocPage`：全仓零引用的布尔开关；
- `RankRule` / `RankItem` / `ruleRank`：排行榜能力只有数据模型与书源规则里的空声明（成稿时随包的内置清单里也有一段 `ruleRank`；该清单已随「应用不随包携带任何书源」下线），解析器无方法、接口无成员、无 UI；
- `LibraryEntity.libraryNewBooks`：解析与缓存路径都强制置空的死字段。

## 决策

1. **章节索引分页双模式兑现 `TocRule`**：`nextPage` 选择器优先（页面链接是站点实况），`pageUrl` 模板兜底（页码沿用搜索/分类的 `PageRule` 换算；相对地址的落位形态见第 7 条）；两者都配时选择器赢。循环体与 URL 落位收在独立纯函数对象里，抓取经函数参数注入，单测无需网络。
2. **终止只靠自然条件，上限只作防御兜底**：① 本页章节按 URL 去重后零新增——软 404（越界页以 HTTP 200 重复返回首页内容）与真正的末页同判；② 选择器无匹配或「下一页」指向已访问地址（回环）。防御上限 20_000 章（`MAX_TOC_CHAPTERS`），单位是章而不是页——上限对齐列表的本质约束（一本书的章节数），而非请求机制；触顶记日志截断、不抛异常。
3. **删除 `PageRule.tocPage`**：目录分页是否启用由 `pageUrl.isNotEmpty()` 判定（与 `ContentRule.nextPage`「空串即不分页」的既定约定一致），不设第二个事实源。
4. **删除排行榜整条声明链**（`RankRule`/`RankItem`/`ruleRank` 字段与书源规则里的 `ruleRank` 段）：排行榜是功能级缺口而非分页缺口——schema 宣称一个从未存在的完整功能，比一个冗余字段是更大的陷阱。将来真做排行榜时从 git 历史找回模型、按当时的 UI 设计重新裁决。存量配置不受影响：规则 JSON 的全部解码点都开了 `ignoreUnknownKeys`。
5. **删除 `libraryNewBooks` 与 `LibraryNewBookEntity`**：Java 时代唯一书源站点首页「最新书籍」栏的数据模型，界面全量迁移 Compose 时 UI 被丢弃、模型成了孤儿。删除是清理迁移遗物，不是放弃预留。
6. **书库首页维持每分类第 1 页**：书库首页的职责是各分类的样张与入口，全量的「加载更多」分页浏览属于分类页（该路径已分页）。首页分页意味着 分类数 × 页数 个请求与等比膨胀的缓存，而首页 UI 没有逐分类的展示面。分类首屏过薄归书源规则调整，不归解析器。
7. **页内相对地址的落位基准是「这一页」，不是源根**：凡从页面解出的相对链接——搜索结果页与分类列表页（含「加载更多」那一页）的书目与封面、详情页的封面与目录链接、目录页的章链接、正文章页的下一页——一律按浏览器**路径**相对语义相对当前页目录落位（点段折叠除外，见权衡末条）。站点把列表与目录放在子目录里是常态，拿源根拼会整批丢掉所在目录（症状分别是指向 404、本章第二页永远漏掉、章地址错位）。基准的查询串与片段是当前资源的参数而不是路径段，算目录前先剥掉；`//host/path` 的协议相对沿用**源根**的 scheme（与相邻的 `/` 形态同源）；基准只有 scheme 两个斜杠时把基准本身当目录，不截掉；空白串原样返回空串，不落成当前目录或源根——「没取到值」不是一个可请求的落点，落成目录自身会让翻页循环把同一页再请求一遍。URL **规则串**本身（`searchUrl`/`discoverUrl`）在这一刻还没有「当前页」，非绝对形态按源根拼（`BookSourceNetwork.absoluteUrl`），但前三条形态判据与 join 对齐。

## 权衡

- **零新增即终止 vs 允许跨页重叠**：只拦「整页全是旧条目」；每页粘一条上页末章的部分重叠站点不受影响（新增按去重后的条目数计）。
- **分页中途网络失败整次抛出 vs 返回部分列表**：静默接受半本目录会让用户以为书就这几章，比报错更难查——与单页时代「失败即失败」的契约一致，调用方按失败刷新。
- **防御上限 2 万章的代价**：极端长篇真超 2 万章会被截断（可调常量，属可接受罕见度）；不设独立请求数上限——每页至少 1 条新增才继续，页数 ≤ 章节数，病态场景的请求上限同为 2 万，仅「规则配错且去重失效」时可达。
- **存量章地址不迁移**：`content_ref` 既是 `chapter_list` 的主键，也是 `ChapterTocDiff` 判定追加的唯一身份依据；旧基准写回的地址与新解析结果对不上，本地末章在远端目录里找不到，追更因此判 `Diverged` 并停止更新（结论已写进 `final_refresh_data`，重试无意义）。替代方案是按新基准重写存量主键——那要猜每行原本来自哪一页，且撞 `content_ref` 的整行 REPLACE 会静默把既有行搬到表尾。这些地址本就指错（点开是 404 或不相干页），故取「让受影响条目停更、由用户手动重加目录或换源」，不等它自愈。
- **「浏览器语义」不含点段折叠**：浏览器会把 `/a/b/../x.html` 折成 `/a/x.html`，`TocPageUrl.join` 只做目录拼接，`../x.html`、`./x.html` 原样叠在基准目录后。请求侧 `HttpUrl` 会归一化，页面照常取得到；留在落库侧的代价是 `content_ref` 存未归一串，同一章的两种拼法不互认（去重与回环判定认不出重复）。补折叠要付的是**另一批**存量条目停更——那些当前追更正常的条目会按新键重解析、与本地末章对不上，而收益只是键的形态整齐，故按上一条同一取舍不做。口径由 `TocPagerTest` 锁形，规格 [§6.5](../specs/book-source-rule-grammar.md) 是事实源。

## 下游影响

- `lib_ebook_api`：`BookSourceRule` 删 `tocPage`/`ruleRank`/`RankRule`/`RankItem`；`TocRule` KDoc 重写为双模式契约。
- `lib_ebook_db`：`LibraryEntity` 删 `libraryNewBooks`；删除 `LibraryNewBookEntity.kt`。
- `lib_book_source`（解析器现居此模块，包结构在后续按业务域重排中移动过）：`getChapterList` 改走新增的 `TocPager`/`TocPageUrl`（`JsoupBookParser.kt` 的文件级 internal 对象，与 `ListPageUrl`/`ChapterPageMatcher` 同文件）。当时随包的内置清单也删掉了 `ruleRank` 段（该清单已随「应用不随包携带任何书源」整体删除）。无分页配置的书源行为不变。
- `lib_book_common`：`LibraryCacheData` 删置空语句。
- `module_find`：两处书库相关测试断言同步。
- 调用方（`BookDetailViewModel`/`BookShelfManager`/`BookRepository`）零改动——接口签名未变。
- 页内相对地址的落位统一经 `TocPageUrl.join`：搜索/分类页的基准由 `BookSourceNetwork.absoluteUrl` 给出，保证「解析用的那一页」与「真正请求的那一页」同源；`absoluteUrl` 自身的形态判据与 join 前三条对齐（`http(s)://` 大小写都认、`//` 取源根 scheme），否则「同源」只在拼接结果上成立、在形态识别上不成立。口径的事实源是规格 §6.5，本仓不变量摘要在 [`docs/architecture.md`](../architecture.md) §6.5。

锚点：`TocRule.pageUrl`；`TocRule.nextPage`；`TocPager`；`TocPageUrl.join`；`BookSourceNetwork.absoluteUrl`；`JsoupBookParserTest`；`TocPagerTest`
