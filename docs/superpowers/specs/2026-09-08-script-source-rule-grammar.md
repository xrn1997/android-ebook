# 脚本书源格式：规则语言的行为与语法规格（清洁室）

日期：2026-09-08
状态：草案（供 Plan 2「脚本书源解释器」作为事实源使用）
上游决策：`docs/adr/0029-script-book-source-import.md`（双格式共存、原始入库、自研解释器）、`docs/adr/0028-untrusted-js-sandbox.md`（不可信 JS 沙箱）
相关实现：`lib_ebook_api/src/main/java/com/ebook/api/entity/ScriptSourceRule.kt`（导入用最小模型）、`lib_book_source/src/main/java/com/ebook/source/analyze/JsoupBookParser.kt`（原生格式的既有裁决）、`lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt`（本规格的页面装配：规则装载 + 求值 + 取文 + 翻页链 → `BookParser` 五面）

## 0. 这份文档是什么、取证口径是什么

本文是**社区脚本书源格式（script book-source format）规则语言的行为规格**：一个从未接触过该生态的工程师，照本文即可实现一个规则求值器。它是清洁室产物——只描述**外部可见的语法与行为**，不含任何上游实现代码、类名、包名（命名红线：全仓不出现该生态的项目名，术语一律用「脚本书源 / 脚本书源格式」）。

每条事实标注取证等级，实现者与后来的读者都要知道哪些是硬的、哪些是软的：

| 标记 | 含义 |
|---|---|
| 〔官方文档〕 | 该生态应用**内置帮助文档**（随 APK 出货的规则/JS/正则/调试帮助页）中的原文陈述 |
| 〔语法文档〕 | 广为流传并被应用内置帮助**外链引用**的规则说明（同一份文档的两个维护副本，二者内容一致处互为佐证） |
| 〔二手〕 | 第三方整理的知识库/AI 技能文档。仅用于补全键名清单，**不得当作行为事实源** |
| 〔语料〕 | 从公开书源 JSON 实例中直接观察到的形态 |
| 〔本仓规定〕 | 公开文档没有给出确定答案，由本仓为求确定性而规定的口径（不是上游实证） |
| 〔未能核实〕 | 公开文档找不到确定答案。**Plan 2 不得凭猜实现**：要么按「本仓规定」写死并单测锁形，要么显式不支持并在导入时报告 |

已读过的取证材料（不含实现源码）：应用内置帮助四篇（规则帮助、JS 变量与函数帮助、正则学习、书源调试）＋ 规则说明的两个维护副本 ＋ 两份第三方知识库 ＋ 若干公开书源 JSON 实例。规格刻意**不去读上游求值器源码**——那是 ADR 里「清洁室」一词的实际含义。

## 1. 顶层字段清单

### 1.1 书源对象（顶层）

一个书源文件是**书源对象的数组**（也允许单个对象；导入时逐条处理）。已核实的顶层键：

| 键 | 类型 | 语义 | 默认 | Plan 2 |
|---|---|---|---|---|
| `bookSourceUrl` | String | 书源唯一标识；重复即覆盖〔语法文档〕 | 必填 | ✅ 主键 |
| `bookSourceName` | String | 展示名，可重复〔语法文档〕 | 必填 | ✅ |
| `bookSourceGroup` | String | 书源分组，仅用于整理〔语法文档〕。多分组之间的分隔符〔未能核实〕（语料中同时出现 `;`、`,`、空格） | "" | ✅ 展示用 |
| `bookSourceType` | Int | **`0` 文本、`1` 音频、`2` 图片、`3` 文件/下载站、`4` 视频**〔官方文档 + 语料：音频站点取值为 1〕 | 0 | ✅ 导入警示 |
| `bookUrlPattern` | String | 详情页 URL 正则，粘贴网址时识别书源〔语法文档〕 | "" | ⏸ 延后（无「按 URL 加书」链路） |
| `customOrder` / `weight` / `respondTime` / `lastUpdateTime` | Int/Long | 手工排序 / 智能排序权重 / 响应时间 / 更新时间戳〔语法文档、二手〕 | 0 | ✅ 排序与重导判据 |
| `enabled` / `enabledExplore` | Boolean | 是否启用 / 是否启用发现〔语法文档〕 | true | ✅ |
| `enabledCookieJar` | Boolean | 启用后自动保存响应头 `Set-Cookie`，适用需要 session 的站点〔官方文档〕 | true | ⏸ **开关不消费**：全仓无一处读它（导入时随未知键一并丢掉）。自动存/带 cookie 这件事 2026-09-12 起在**沙箱外呼**链路上恒开（一源一份 `SourceCookieJar`，§5.4 口径 5），声明式取文不带罐、也不受该开关支配，见 §11-37 |
| `header` | String | 全局请求头（JSON 字符串）〔语法文档〕 | "" | ✅ |
| `concurrentRate` | String | 并发率：`1000` = 间隔 1s；`20/60000` = 60s 内 20 次〔官方文档〕 | "" | ✅ 网络层限流 |
| `jsLib` | String | 注入引擎的公共 JS：裸 JS 文本，或 `{"name":"https://…/x.js"}` 形式自动复用已下载文件〔官方文档〕 | "" | ⏸ 延后（执行器已在仓仍不做：缺源级脚本装载位与 `@import` 解析；且函数级并发共享有竞争约束） |
| `loginUrl` / `loginUi` / `loginCheckJs` | String | 登录地址 / 登录表单 UI / 登录检查 JS〔官方文档〕 | "" | ❌ 不支持，导入时明示（`loginUrl` 非空即触发既有「依赖登录」警示） |
| `coverDecodeJs` | String | 封面解密 JS〔二手〕 | "" | ❌ 延后（Plan 3） |
| `variableComment` | String | 自定义变量说明〔二手〕 | "" | ✅ 纯展示 |
| `bookSourceComment` | String | 作者备注（常含改版说明、备用域名）〔语料〕 | "" | ✅ |
| `exploreUrl` | String | **发现页分类清单**（见 §8）〔语法文档〕 | "" | ✅ |
| `exploreScreen` | String | 发现筛选规则〔二手，语义未能核实〕 | "" | ❌ 延后 |
| `searchUrl` | String | **搜索地址**（见 §6）〔语法文档〕 | "" | ✅ |
| `ruleSearch` / `ruleExplore` / `ruleBookInfo` / `ruleToc` / `ruleContent` / `ruleReview` | Object | 六类规则对象，见 §1.2~§1.7 | {} | 分述 |
| `eventListener` / `customButton` | Boolean | 回调事件开关 / 自定义按钮〔二手〕 | false | ❌ 不支持（回调事件族属 UI 侧能力，见官方文档「回调事件」章） |

**未知键一律忽略**：格式在实践中靠「多余键不影响解析」存活。本仓解码已开 `ignoreUnknownKeys`，原始 JSON 整块入库、不做归一化（ADR-0029 决策 1）。

### 1.2 `ruleSearch`（搜索结果）

字段规则全部是**规则字符串**（§2 起）。列表字段先取条目、逐条求值其余字段：

| 键 | 语义 | Plan 2 |
|---|---|---|
| `bookList` | 书籍条目列表（必需，否则无结果） | ✅ |
| `name` | 书名 | ✅ |
| `author` | 作者 | ✅ |
| `bookUrl` | 详情页 URL | ✅ |
| `coverUrl` | 封面 URL | ✅ |
| `intro` | 简介 | ✅ |
| `kind` | 分类标签（多值可用 `,` 串接多个规则，见 §6.5 与 `%%`） | ✅ |
| `wordCount` | 字数 | ✅ |
| `lastChapter` | 最新章节名 | ✅ |
| `updateTime` | 更新时间 | ✅ |
| `checkKeyWord` | 校验关键词：批量校验时优先于用户输入〔官方文档：校验搜索优先用书源填写的校验关键词；键名来自二手〕 | ✅ 供「校验所选」类功能 |
| `url` | 〔未能核实〕二手知识库列出「搜索 URL 也在 ruleSearch 里」，但顶层 `searchUrl` 才是规则说明与全部示例使用的位置。**本仓规定**：只读 `searchUrl`，`ruleSearch.url` 忽略 | ❌ |

> **条目有效性判据**〔本仓规定，与原生链路一致〕：`name` 与 `bookUrl` 都非空才收录，其余字段缺失只留空。

### 1.3 `ruleExplore`（发现列表）

字段集合与 `ruleSearch` 的列表字段**完全相同**（`bookList`/`name`/`author`/`kind`/`wordCount`/`lastChapter`/`intro`/`coverUrl`/`bookUrl`/`updateTime`），区别只在输入的页面是 `exploreUrl` 里某一条分类页。〔语法文档 + 语料：示例源的 `ruleExplore` 与 `ruleSearch` 同构〕

> **一处需要澄清的常见误解**：发现页的「分类有哪些」不住在 `ruleExplore`，而在顶层 `exploreUrl`；`ruleExplore.kind` 是**某本书的分类标签**，不是分类页清单。不存在 `kindUrl` / `summary` 这类 `ruleExplore` 字段（公开文档与语料均未见）。

### 1.4 `ruleBookInfo`（详情页）

| 键 | 语义 | Plan 2 |
|---|---|---|
| `init` | **预处理规则**：在其余字段之前先执行一次；只能是「正则 AllInOne」（以 `:` 开头）或 JS，JS 必须返回 JSON 对象，之后各字段按该对象的键取值（例：`name` 填 `a`）〔语法文档〕 | ✅ 声明式部分（正则 AllInOne，2d 以**首个匹配**为条目上下文、字段经 `$n` 组引用取值；「字段=对象键」的读法属 JS 分支待 Plan 3，见 §11-21） |
| `name` / `author` / `intro` / `kind` / `wordCount` / `lastChapter` / `updateTime` / `coverUrl` | 字段规则 | ✅ |
| `tocUrl` | 目录页 URL；**只支持单个 URL**；为空时用详情页 URL〔语法文档〕 | ✅ |
| `canReName` | 允许用详情页的书名/作者覆盖搜索页取值：规则非空且对应字段解出非空才覆盖〔语法文档〕 | ✅（**本管道无行为差**：入参 shelf 不携带搜索值，字段解出即填，与原生 `getBookInfo` 同形，见 §11-22） |
| `downloadUrls` | 文件源（`bookSourceType=3`）的下载链接数组〔二手 + 官方文档「书源类型: 文件」〕 | ❌ 延后（本项目为文字阅读器） |

### 1.5 `ruleToc`（章节索引）

| 键 | 语义 | Plan 2 |
|---|---|---|
| `chapterList` | 章节条目列表；**首字符 `-` 表示列表反序**〔语法文档〕 | ✅ |
| `chapterName` | 章节标题（规则说明正文写作 `ruleChapterName`，但全部示例与语料的实际键名是 `chapterName`） | ✅ |
| `chapterUrl` | 章节 URL（语料实际键名 `chapterUrl`） | ✅ |
| `nextTocUrl` | 目录下一页，见 §7 | ✅ |
| `updateTime` | 章节更新时间（逐章） | ✅（**可解不存**：`ChapterListEntity` 无对应列，与原生链路同形，见 §11-26） |
| `isVip` | VIP 标识：结果落在**假值集**（`null` `false` `0` `""`）时为非 VIP〔语法文档〕 | ✅（**可解不存**：同 `updateTime`，见 §11-26） |
| `wordCount` / `intro` / `chapterPicUrl` / `addTocUrl` / `volumeList` / `style` / `canUpdate` | 二手文档互不一致、公开规则说明未列。**本仓规定**：Plan 2 不实现，导入时不计入「不支持」（不是坏源）；有真实需求再逐条核实 | ❌ |

### 1.6 `ruleContent`（正文）

| 键 | 语义 | Plan 2 |
|---|---|---|
| `content` | 正文 | ✅ |
| `replaceRegex` | 正文替换规则（见 §2.4 的正则三形态；JS 回存正文会自动套用本规则〔官方文档〕） | ✅ |
| `nextContentUrl` | 同章下一页，见 §7 | ✅ |
| `contentBatch` | **批量正文**：一次取多章并用 `java.cacheContent` 逐章回存；支持裸 JS / `<js></js>` / `@js:`〔官方文档〕 | ❌ 延后（纯 JS 能力，Plan 3 后再评估） |
| `webJs` | 在 WebView 内执行的 JS，须有非空返回值，返回值参与后续资源正则/正文〔语法文档〕 | ❌ **明确延后**（ADR-0029：仅 3/642 源受影响；导入时逐条如实报告） |
| `sourceRegex` | 资源嗅探正则（配合 `webView`）〔语法文档〕 | ❌ 延后（无 WebView 嗅探） |
| 副文 / 购买操作 | 官方文档有「副文规则」（文本时拼接到正文后、音频时作歌词）与「购买操作」（链接或 JS，返回 true 自动刷新目录与当前章）两章，但**未给出 JSON 键名**〔未能核实〕 | ❌ 延后 |
| `title` / `image` | 〔未能核实〕 | ❌ |

### 1.7 `ruleReview`（段评/章说）

公开规则说明**没有**章说规则对象的字段清单（只有订阅源的对应文档与「图片段评兼容键 `reviewCount`」的痕迹）。新版应用改由 **JS 单文件书源**的 `getReviewSummary` / `getReviewDetail` / `getReviewReplies` 三函数提供段评〔官方文档〕，与声明式 `ruleReview` 是两条路。

**本仓规定**：Plan 2 **不实现** `ruleReview`；导入时若该对象非空，计入「不支持项」提示（本项目无段评 UI 对接口），不得静默当成可用。

## 2. 规则字符串：词法与组合

一条规则字符串（下面简称「规则」）是求值器的输入单元，例如 `@css:.bookbox@tag.a.0@text##全文`。

### 2.1 模式标志（前缀）

规则**必须以标志判定用哪种解析模式**；识别顺序即下表，先匹配者胜。**一处例外**：`<js>…</js>` 可出现在任意位置并充当分隔符（见下表内该行），故实现上**先于表内其余标志**判定——若让 `@css:` 之类先命中，一条混了内联 JS 的规则（如 `@css:.x<js></js>`）就会被当成纯 CSS 静默求值。判定依据是 §9「`<js>` 在任何一步出现都必须按 JS 处置」；实现见 `RuleMode` 的 `flagOf`，该处 KDoc 记了同一理由。

**标志键一律按不区分大小写匹配**（**本仓规定**，覆盖表内全部 `@单词:` 形式的标志）。理由有两层：一是上游文档对同一标志就给两种写法，二是 1168 条真语料里大写形态是既成事实（`@CSS:` 64 处、`@JSon:` 52 处、`@XPath:` 8 处）。只给其中几行开不区分大小写的症状是「同一条源换个大小写就整条判成规则语法错误」，而那句报错会把人支去改规则、不会怀疑解释器。符号型标志（`@@`、`//`、`$.`、开头的 `:`）无大小写可言；`<js>` 标签按上游写法保持小写（语料里未见 `<JS>` 形态）。实现落在三处：`RuleMode.flagOf`、`RuleMode.hasKnownFlag`（`-`/`+` 前缀豁免用）、`Interpolation.hasRuleFlag`（`{{}}` 里带标志的表达式判分支用）——**改一处必须三处同步**，漏一处的表现不是报错而是「带大写标志的插值被当 JS 送进内核，拿到内核的 `expecting ';'`」。

| 标志 | 模式 | 判定与依据 |
|---|---|---|
| `@@` | 默认（链式）规则 | 「直接写时可以省略 `@@`」〔官方文档〕。省略标志且不被下面任何标志命中，即默认模式 |
| `@css:` | CSS **模式**标志 | 整条规则是一个 CSS 选择器 + 尾随取值器时以 `@css:` 开头〔语法文档〕。注意：这是**整条规则的模式标志**，与「默认链式模式里某个 `@` 段本身是不是 CSS 选择器」是两件事——后者见 §2.5（链段可以是裸 CSS 选择器）。 |
| `@XPath:` 或以 `//` 开头 | XPath | 〔官方文档 + 语法文档〕。大小写在上游文档里两种写法都出现（`@XPath:` / `@xpath:`）→ 匹配口径见本节表前的「标志键一律按不区分大小写匹配」总则 |
| `@json:` 或以 `$.` 开头 | JSONPath | 「最好以 `@json:` 或 `$.` 开头，其他形式不可靠」〔语法文档〕；以 `.` 开头的相对路径不在文档保证范围内 |
| 以 `:` 开头 | 正则 AllInOne | 「必须以 `:` 开头，不可省略；只能用在书籍列表、发现列表、详情页预处理与目录列表」〔语法文档 + 官方文档〕 |
| `@js:` | JavaScript 段 | 「`@js:` 只能放在其他规则的最后使用」〔语法文档〕 |
| `<js>…</js>` | JavaScript 段（内联） | 「可在任意位置使用，还能作为其他规则的分隔符，例 `tag.li<js></js>//a`」〔语法文档〕 |
| `@put:` | 写变量 | 见 §5.2 |
| `@get:` | 读变量 | 见 §5.2；JS 内不能用 `@get`，要用 `java.get` |
| `@cache:` | 〔未能核实〕 | **在官方文档、两份规则说明、两份第三方知识库与语料中均检索不到 `@cache:` 这一前缀**。缓存能力在公开文档里只有 JS 侧的 `cache` 对象（`cache.put/get/delete/putFile/getFile/putMemory/…`）与 `java.cacheFile(url)`。Plan 2 **不实现** `@cache:`；若某条源真写了该前缀，按「不支持的规则标志」报告，不要猜语义 |

`{…}`（单花括号）：遗留自上一代格式，**只能使用 JSONPath**，文档明示「尽量避免使用」〔语法文档〕。语料中仍常见 `{$._id}` 这类形态 → 本仓 Plan 2 实现（成本 = 已有的 JSONPath 分支）。

### 2.2 组合符：`&&`、`||`、`%%`

| 符 | 行为 | 真值 |
|---|---|---|
| `&&` | **合并**所有分支取到的值〔语法文档〕 | 每支都求值 |
| `||` | 以**第一个取到值的**分支为准〔语法文档〕 | 短路：空结果（未取到值）才继续下一支 |
| `%%` | **依次交错取数**：三个列表时先取列表 1 的第 1 个、列表 2 的第 1 个、列表 3 的第 1 个，再取列表 1 的第 2 个……〔语法文档〕 | 每支都求值 |

三条硬约束：

1. **只能在同种规则之间使用，不包括 js 和正则**〔语法文档〕。即 `A@text@js:…` 合法，`A@js:…||B@js:…` 不受该组合符管辖。
2. `@js:` 只能出现在整条规则的**末尾**〔语法文档〕；`<js></js>` 可出现在任意位置并充当分隔符。
3. 组合符**不嵌套**：`a||b&&c` 的切分次序公开文档没有给出唯一答案 → 见 §2.3 的「本仓规定」。

### 2.3 切分次序（精确判定规则）

以下为**本仓规定**的确定性切分次序。理由：公开文档只给出各符的语义，未给出混用时的优先级，而清洁室实现必须有唯一答案；此次序与文档给的三条硬约束、与全部已核实示例兼容（示例中不存在三种组合符混用的形态）。**它不是上游实证**，Plan 2 必须把它写成语料 + 单测双锁的形态，将来若与真实源行为冲突按 bug 修（ADR-0029「清洁室语义偏差按 bug 修」）。

```
输入规则串 R
1. 若 R 以 @js: 开头、或含 <js>     → 整条即 JS 段（交执行器求值；未装配执行器时抛类型化「需脚本沙箱执行器」）
1b.若 R 以 : 开头（正则 AllInOne）  → 整条即一个正则段，不再参与组合符切分
   （§2.2 硬约束 1 明说组合符「不包括 js 和正则」。步骤 1 与 1b 一律先于切分，
    否则 `:x("a||b")` 这类正则里的字面量 `||` 会把一条规则切成两支——
    不是报错，而是静默解错内容。步骤 1b 是 2026-09-08 实现期依 §2.2 补上的：
    本节原先只列了 @js:，与 §2.2 冲突，冲突时以 §2.2 为准。）
2. 剥出正则替换尾段：从**第一个** ## 起为替换段（§2.4），余下为取值段 V
3. 在 V 末尾剥 URL 选项尾段：,**{ 开头且以最后一个 } 收尾（§6.1），余下 V'
4. 以 %% 优先切分 → 得到 N 支；对每支再按顺序处理：
   4.1 以 || 切分（**短路求值**：第一支拿到值即停）
   4.2 每支内以 && 切分（**全部求值后合并**）
   4.3 每段（&& 的一份）以 @ 切分为规则段链（§2.5）；
       段内保留 [] , : 等索引字符，不当作分隔符
5. 依次把替换段、选项尾段应用到结果上
```

**空段行为**：`%%`/`&&`/`||` 切出的空串分支视为「未取到值」（`||` 会继续下一支；`&&` 合并时跳过；`%%` 少一路）。整条规则为空串 → 该字段结果为空串（不报错）。〔本仓规定，与原生格式 `JsoupHelper` 的空选择器返回空串一致〕

**转义**：公开文档**没有**任何反斜杠转义 `&&`/`||`/`%%`/`##`/`@` 的记法。唯一可见的规避手段是把含分隔符的字面量放进 JS 字符串或用正则字符类。→ Plan 2 不支持转义；若分支文本里出现 `@`（如邮箱、`@media`），只能依赖「按段解释」的自然结果，需在未知项清单中跟踪（§11-4）。

**嵌套**：`{}`/`{{}}`/`[]` 内的 `&&`、`||`、`%%`、`##` 不参与切分（花括号/方括号配对优先）。正则段内的 `#` 不参与 `##` 判定。〔本仓规定，依据：语料中 `##[|\\]` 这类形态要求正则先于组合符被识别〕

### 2.4 正则的三种形态

| 形态 | 写法 | 用在哪 | 行为 |
|---|---|---|---|
| AllInOne | 以 `:` 开头，如 `:href="(/chapter/[^"]*)"[^>]*>([^<]*)</a>([^<]*)` | 只能用于**搜索列表、发现列表、详情页预处理、目录列表** | 对**整个源文本**做正则切分，条目由正则产生，字段用 `$1`/`$2`… 引用捕获组〔语法文档 + 语料：`chapterName: "$2"`、`chapterUrl: "$1"`、`updateTime: "$3"`〕 |
| OnlyOne | `##正则##替换内容###`（三井号收尾） | **除**上述四种列表场景之外 | **只对第一个匹配**做替换〔语法文档〕 |
| 净化（Purify） | `##正则##替换内容`（可只到第一个 `##`） | 跟在任意其他规则之后 | **循环匹配替换**（全部替换）；独立使用时等价于 `all##正则##替换内容`〔语法文档〕 |

细则（已核实）：

- 替换内容为空时，第二个 `##` 可省略：`…@text##全文阅读` 即删除「全文阅读」〔语法文档〕。
- 替换文本用 `$1`、`$2` 引用捕获组〔语料：`##/book/(\d+)##https://img.x.com/bookpic/s$1.jpg###`〕。
- 正则方言为通用 Java 正则风格，文档示例含 `(?i)` 内联标志、`\w\W`、`\d`、惰性量词 `.*?`〔官方正则学习章 + 语料〕。
- **`##$##{"webView":true}` 是一个惯用法**：用「匹配末尾锚点 `$`、替换为给定文本」的方式，把 URL 选项尾段拼到结果后面〔语法文档明确示范，见 §6.2〕。

### 2.5 默认模式的链式语法

`@` 为分隔符，链上每一段可分三段：`类型 . 名称 . 位置`〔语法文档〕。

- **类型**（第一段）：`class`、`id`、`tag`、`text`、`children` 等。`children` 取所有子标签、**不需要**第二三段；`text` 按文本内容定位。
- **裸 CSS 段（无类型前缀）**：一个 `@` 段若不以 `class./id./tag./text./children` 开头、不是纯位置序号、不是末段取值器，则整段按 **CSS 选择器**交给 Jsoup（`Element.select`）在当前节点集内收窄——支持类 `.box`、id `#after_link`、标签 `ul`/`li`、复合 `div.foo`、属性 `a[href*=/article/]`、后代与伪类 `.pagination a:contains(下頁)`。该生态的 `class./tag.` 前缀只是它的**扩展简写**，二者并存〔语料实证：642 条里 283 条 bookList 用裸 CSS；§2.5 line220 的 `head@.1@text` 里 `head` 即裸标签段〕。
- **裸 CSS 段的位置序号**：`tag.N`（如 `a.0`/`span.2`）= CSS 选 `tag` 再取第 N 个，与方括号索引 `[N]` 等价（§4）。判据：按最后一个顶层 `.` 切，尾段能解析成索引才拆（`a.0`→选 a 取 0；`div.foo` 的 `foo` 不是索引→整段是 CSS 复合选择器；`.box` 前导点不拆→CSS 类选择器）。
- **空段（恒等）**：`@` 切出的空串段表示**恒等**——节点集原样传下一步。整条规则以取值器开头（`@href`/`@text`，语料实证形态）时，取值器作用于**当前节点**而不是它的子元素；这与 §4「索引可作为段首规则（此时前面等价于 children）」不冲突——后者指以**索引**（`[1]`/`.1`）开头，段本身不空。
- **名称**（第二段）：类型的取值名（类名/标签名/id）；`text` 时为「文本内容的一部分」。
- **位置**（第三段）：正数从 0 起（0 是第一个）；负数从尾部数（-1 是最后一个）；**不写位置则取全部**〔语法文档〕。
- **排除**：`!` 开头，序号用 `:` 隔开，如 `!0:2:-1`（排除第 1 个、第 3 个、最后一个）〔语法文档原文如此，未给完整可复制示例 → 形态细节见 §4〕。
- **列表反序**：在取列表的规则最前面加负号 `-`（文档场景是目录列表倒序的站点）；语料实证 `"-:<li><a[^"]+"([^"]*)">([^<]*)"`——**`-` 在 `:`（AllInOne）之前**。
- 最后一段是**取值器**（`text`/`href`/…），见 §3。

示例（全部为文档/语料原文形态）：

```
class.odd.0@tag.a.0@text||tag.dd.0@tag.h1@text##全文阅读
class.odd.0@tag.a.0@text&&tag.dd.0@tag.h1@text##全文阅读
head@.1@text            ←→  head@[1]@text  ←→  head@children[1]@text
@css:.articleDiv p@textNodes##搜索.*手机访问|一秒记住.*|.*阅读下载
```

## 3. 取值语义

### 3.1 默认取第一个 vs 取全部

- 单值字段（`name`、`author`…）：链上没写位置的匹配段会**取全部**，随后按本仓规定的收敛口径取第一个〔语法文档说「不加位置会获取所有」，但未说单值字段如何收敛为字符串；真实结果必然是拼成一个串或取首个 → 记 §11-2〕。
- 列表字段（`bookList`、`chapterList`）：取全部匹配，顺序即文档顺序（或 `-` 反序后的顺序）。

### 3.2 取值器（后缀）全集

已核实的取值器（作为链上最后一段，写法是 `@xxx`，**不是** `.xxx`）〔语法文档〕：

| 取值器 | 语义 | 证据 |
|---|---|---|
| `text` | 元素及其所有子元素的文本 | 官方 + 二手一致 |
| `ownText` | 仅当前元素自身文本、排除子元素 | 二手与语料一致（`@css:#info@ownText`） |
| `textNodes` | 子文本节点列表（逐段返回） | 语法文档列在收集合内；语料 `@css:.articleDiv p@textNodes##…` |
| `html` | 元素内部 HTML | 语法文档 + 语料 `@css:#bookintro@html` |
| `all` | 整个元素（含自身标签）；独立正则规则的默认隐式取值器（§2.4 净化形态） | 语法文档 |
| `href` / `src` | 属性快捷名 | 语法文档 + 语料 |
| `@任意属性名` | 取该属性：`@content`（meta）、`@value`、`@_src`（延迟加载）、`@data-*` 等 | 语料实证：`@css:[property=og:image]@content`、`//img/@_src`、`option@value` |
| `children` | 所有子标签（作为类型段使用，不取文本） | 语法文档 |

**末段歧义的消解（本仓规定）**：一个裸词末段既可能是 CSS 标签选择器（`.box@ul@li` 的 `li`，列表字段要的是**节点**），也可能是 `@任意属性名` 取值器（`option@value` 的 `value`、`meta@content` 的 `content`，单值字段要的是**文本**）。两者无法从规则串本身区分，故按**字段口径**定：

- **列表字段**（`bookList`/`chapterList`/`ruleExplore.bookList`）：末段一律按**选择器**解，结果是节点集。
- **单值字段**（`name`/`author`/`content`/`coverUrl`/`tocUrl`/`chapterUrl`/`nextTocUrl`/`nextContentUrl` 等）：末段是已知取值器（text/html/href/src/all/ownText/textNodes）按取值器；否则按 `@任意属性名` 取值器。

实现上由调用方把口径（`ChainTail.SELECTOR` / `ChainTail.AUTO`）传进求值器；缺省 AUTO 即单值口径。**已知取值器关键字在两种口径下都按取值器**（作者显式写了 `@text` 就尊重它）。

**未证实的取值器**（第三方文档列出但公开规则说明里没有，本仓 Plan 2 不实现，遇到按不支持报告）：`@textNode`（单数——二手知识库写作 `@textNode`，与语法文档的 `@textNodes` 冲突，见 §11-3）、`@innerHtml`、`@outerHtml`、`@before`、`@after`、`@first`、`@last`、`@size`、`@id`、`@class`、`@tag`、`@val`。

> **对本仓既有写法的映射**：原生格式用 `cssSelector@attrName` 表示取属性、无 `@attr` 之外的取值器（`JsoupHelper.selectAttr`）。脚本格式的后缀集是它的**超集**，两套语义不要混用：脚本解释器里 `@text`/`@html`/`@all` 有明确定义，原生侧的 `selectText`/`selectAttr` 不复用。

### 3.3 缺字段与空结果

- 字段规则为空或缺失 → 结果为空串，**不报错、不回退**（除 §3.4 的假值语义外没有任何隐式默认）。
- 选择器解不到任何节点 → 「未取到值」：`||` 走下一支；单支则结果为空。
- CSS/XPath/JSONPath 语法错误 → 〔未能核实〕上游是抛异常还是按空结果处理，公开文档无述。**本仓规定**：抛类型化的「规则语法错误」（可被 `||` 当作未取到值继续下一支），并在日志给出规则原文与前 80 字符；不得静默返回上一个分支的结果。
- 真值判定（供 `isVip`、回调结果等使用）：空白串、`null`、`false`、`no`、`not`、`0`、`0.0` 为**假**，其余为真〔官方文档明确列出，是本文档中唯一成文的 falsy 集合〕。

## 4. 索引语法

在默认模式与 CSS 模式之后追加，形如 `tag.div[...]`；也可以作为 `@` 分段后每部分的**首规则**（此时前面等价于 `children`）〔语法文档〕：

| 形态 | 语义 | 依据 |
|---|---|---|
| `[0]` | 第 1 个 | 语法文档（`head@.1@text` ≡ `head@[1]@text`） |
| `a.0` / `.autor2[2:1]` | 裸 CSS 段同样可带位置序号：`tag.N` 与 `tag[N]` 等价（§2.5）；`[start:end]` 闭区间口径不变 | 语料实证（tag.dotindex 1118 例、dot-class+bracket 30 例） |
| `[-1]` | 倒数第 1 个；负数一律从尾数 | 语法文档 |
| `[1,3]` | 逗号分隔的索引集：取第 2 与第 4 个 | 语法文档「格式如 `[index,index, ...]`」 |
| `[!1,3]` | `[!` 开头 = **排除**这些索引 | 语法文档 |
| `[2:4]` | 区间 `[start:end]`，**闭区间**（含 start 含 end） | 语法文档给出区间格式。**闭区间的判据**：同一文档说「start 为 0 时可省略，end 为 -1 时可省略」且「`[:]` 即全取」——若 `end` 是半开的排他上界，则省略 `end` 等于「到 `-1` 之前」即丢掉最后一个元素，与「省略=取到末尾」自相矛盾；取闭区间两处才同时成立 |
| `[1:9:2]` | `[start:end:step]` | 语法文档 |
| `[:3]` / `[2:]` / `[:]` | `start` 为 0 时可省略、`end` 为 -1 时可省略；`[:]` 即全取 | 语法文档「start为0时可省略，end为-1时可省略」 |
| `[-1:0]` | 特殊用法：**在任意位置让列表反向** | 语法文档原文 |
| 位置段里的 `!`（`class.x!0:2@text`） | 排除式序号，多个序号以 `:` 分隔 | 语法文档 |

**越界行为**〔未能核实〕：公开文档未规定索引越界是丢弃、截断还是整条失败。**本仓规定**：逐项过滤——越界的索引/区间端点按集合边界裁剪（`[2:999]` 等价 `[2:]`），单个越界索引直接跳过，全部被过滤则「未取到值」（参与 `||` 短路）；不得抛异常。此口径必须与 §2.3 一起锁进单测。

**正则 AllInOne 里的 `$n`** 与索引语法无关：那是捕获组引用，只在 `chapterName`/`name` 这类逐字段规则里生效（§2.4）。

## 5. 变量系统

### 5.1 `{{}}` 插值

| 位置 | 允许内容 | 依据 |
|---|---|---|
| `searchUrl`、`exploreUrl`（发现 URL）内的 `{{}}` | **只能写 JS** | 语法文档 |
| 其他一切规则内的 `{{}}` | 可写**任意规则**，但必须带 §2.1 的标志头；**无标志即按 JS 求值**（默认）。Default 规则需以 `@@` 开头 | 语法文档 + 官方文档「{{……}}内使用规则必须有明显的规则标志，没有规则标志当作 js 执行」 |
| `{}`（单花括号） | 只走 JSONPath（上一代格式遗留），文档建议避免 | 语法文档 |

真实形态：

```
/search/?key={{key}}&page={{page}}                         ← 搜索 URL：JS 变量 key/page
{{(page-1)*20}}                                            ← 页码换算写成 JS 表达式
{{java.base64Encode(key)}}                                 ← 关键词编码
{{@@tag.a@href}}                                           ← 规则插值要带 @@ 标志
//li[3]/a/@text()##{{book.name+chapter.title}}              ← 用 book / chapter 对象净化正文噪声
{{$.type_name}},{{$.catalog_name}}                         ← JSONPath 插值（kind 字段）
```

**插值位置**：`{{}}` 出现在规则字符串的任意位置都被先就地展开（含 URL 选项 JSON 的值内部，语料实证 `…,"body": "page={{page}}&key={{key}}"`）。展开发生在**切分之前还是之后**〔未能核实〕——**本仓规定**：在 §2.3 的第 2 步之前先做 `{{}}` 展开，但展开出的文本**不再参与**分隔符切分（把展开结果当不透明字符串占位，切分结束后回填）。理由：语料里存在把 `{{$.id}}` 放进 URL 的形态，若展开结果含 `&` 或 `||` 就会自炸。

**JS 插值「跑成了但没有值」按空串展开**（**本仓规定**）。无标志的 `{{…}}` 交 JS 求值后，完成值有三态：标量、空（`undefined`/`null`）、结构化（对象/数组）。本仓取**空 → 展开成空串**、**结构化 → 抛类型化「脚本执行失败」**。依据：语料里这类表达式的本职是副作用而非返回值（`{{java.put('key',key)}}`、`{{java.cacheFile(url)}}` 一类，真内核下完成值恒为 `undefined`，共 16 次），按失败报会让整条规则因为一个「没打算回值」的表达式作废；而 §9 早已规定「未取到值 → 空串」，插值没有理由另立一套更严的口径。反过来，对象/数组**不**降格成空串：拼成文本得到的是一段「看着像 URL 片段的 JSON」，比抛错更难查。症状对照：这条若退回「空值即失败」，表现是搜索/正文里带 `java.put` 插值的源整字段报「脚本执行失败」。实现见 `SandboxScriptJs.runExpression`，锁形见 `SandboxScriptJsTest`。

### 5.2 `@put:` / `@get:`

```
@put:{bid:"//*[@bid-data]/@bid-data"}
```

- 只能在 **JS 之外**的规则里使用；在 JS 内改用 `java.put(key, value)` / `java.get(key)`，「在 js 中无法使用 `@get`」〔语法文档〕。
- `@put:` 内使用 **JSONPath 时不需要引号**，其他模式的规则要加引号〔语法文档原文〕。
- 作用域与生命周期：〔未能核实〕文档没说清 `java.put/@put` 存的是「本次解析的局部变量」还是「书源级持久变量」。**本仓规定**：按**单次解析任务（一本书的一轮求值）**作用域实现（同任务内跨规则可见，任务结束即销毁），并要求导入侧提示——若某条源依赖跨请求的变量存活，会在真实运行中取到空。**上游另有四套明确持久的变量面**〔官方文档〕：`source.put/get(key)`（自定义书源变量）、`book.putVariable/getVariable`、`chapter.putVariable/getVariable`，以及 `cache.put/get(key, saveTime)`（落数据库 + 50M 缓存文件，`saveTime=0` 为不过期）。**本仓 2026-09-11 的落地口径见 §11-34**：前三套的**按键**读写折到与 `@put:`/`java.put` 同一张任务级表上（同 §11-29 的方向——保留上游名字、接受作用域降级、宁可读到空也不读到脏值）；`source.getVariable()`/`setVariable(s)` 是**整串**形态（语料里前者 262 次全为零参，与按键的两个形状不是一回事），落在 `EvalContext.sourceVariable` 这份任务级副本上，初值取规则的 `variable` 键；`cache` 不参加折叠，沙箱里连这个名字都没有。

### 5.3 内置变量全集

JS 段与「按 JS 求值的 `{{}}`」中可绑定的变量（〔官方文档〕逐项列出的完整表）：

| 变量 | 含义 | 何时可用 |
|---|---|---|
| `java` | 当前求值上下文对象（规则求值方法、网络、编解码、日志…） | 所有 JS 场景；**不同场景可用的方法子集不同**（官方文档明说「不同的书源规则中支持的调用的 Java 类和方法可能有所不同」） |
| `baseUrl` | 当前 URL（String） | 有页面上下文的场景 |
| `result` | 上一步（同一条规则链的前一段 / 前一步求值）的结果 | `@js:`、`<js></js>` |
| `src` | 请求返回的源码 | 页面级场景 |
| `book` | 书籍对象 | 详情/目录/正文；搜索与发现阶段可能为 null |
| `chapter` | 章节对象 | 目录/正文 |
| `title` | 当前章节标题（String） | 正文 |
| `source` | 书源对象（变量、登录头、刷新） | 所有 JS |
| `cookie` | cookie 操作 | 所有 JS |
| `cache` | 缓存操作 | 所有 JS |
| `nextChapterUrl` | 下一章 URL | 正文（配合 `getContent`/正文规则） |
| `isFromBookInfo` | 是否为详情页刷新 | 正文/目录 |
| `key` | 搜索关键词 | 仅 `searchUrl` 的 `{{}}`〔语法文档：`key` 为关键字标识〕 |
| `page` | 页码，**初值 1** | 仅 `searchUrl` / `exploreUrl` 的 `{{}}` |
| `infoMap` | 发现页按钮/筛选控件的当前值（可读写、`.save()`） | 仅发现 URL〔官方文档〕 |
| `chapters` / `result` | `contentBatch` 场景的本批章节数组 | 仅批量正文〔官方文档〕 |
| `rssArticle` | 订阅源文章对象 | 订阅源（本项目不涉及） |

> **本仓沙箱的绑定面（2026-09-12）**：上表是上游全集，本仓只装 `java`/`baseUrl`/`result`/`src`/`key`/`page`/`title`/`book`/`chapter`/`source`/`cookie` 十一项（`src`/`title`/`chapter` 在正文链路恒为 `undefined`，§11-31）；`cache`/`infoMap` 在沙箱里**没有这个名字**，脚本碰到的是 ReferenceError。`source` 分**数据与行为两半**：数据由 `ScriptRuleSet.sourceBindingJson()` 经 `sourceJson` 绑定过边界，是装载期快照，只放 `bookSourceUrl`/`bookSourceName` 两个恒有键与**声明过的**可选键（「压根没配」在脚本里读到 `undefined`，与「配了个空头」的空串可分——语料里 `if (!source.variableComment)` 这类判法真实存在）；方法由垫片每次执行挂上（`key`/`getKey()` 同指 `bookSourceUrl`；变量族折表见 §11-34；登录/刷新一族 12 个方法给的是 `__UNSUPPORTED__:` 那句可诊断的话，不是 TypeError）。`cookie` 是**行为面无数据面**的对象（形状与锁见 §5.4 口径 5）：只有三个方法，属性一个都不绑。

`book` 的可用属性（官方文档逐项列出，节选，足以支撑净化类用法）：`bookUrl`、`tocUrl`、`origin`（书源 URL）、`originName`、`name`、`author`、`kind`、`customTag`、`coverUrl`、`customCoverUrl`、`persistedCoverUrl`、`intro`、`customIntro`、`charset`、`type`、`group`、`latestChapterTitle`、`latestChapterTime`、`lastCheckTime`、`lastCheckCount`、`totalChapterNum`、`durChapterTitle`、`durChapterIndex`、`durChapterPos`、`durChapterTime`、`canUpdate`、`order`、`originOrder`、`variable`〔官方文档〕。

`chapter` 的可用属性：`url`、`title`、`baseUrl`（拼相对地址用）、`bookUrl`、`index`、`resourceUrl`、`tag`、`start`、`end`、`variable`〔官方文档〕。

> 与 ADR 能力清单的差项：任务/ADR 提到的「`useRealUrl` 之类的内置变量」**在上述全集中不存在**，官方与语法文档均检索不到该键。重定向取真实地址的公开手段是 JS 侧 `java.connect(url).raw().request().url()` 或 `java.post(...).header("Location")`〔官方文档示范〕，属 Plan 3 能力。**记为 §11-5。**

### 5.4 `java.*` 能力面（垫片的正面清单）

§5.3 说 `java` 是「当前求值上下文对象」且「不同场景可用的子集不同」——本仓只有一个子集，且它**写在 `QuickJsPrelude.SOURCE` 一处**。正面清单在这里，负面是「别处也不存在」：脚本碰不到的名字拿到 `ReferenceError`，而不是「方法存在但什么都不做」。名字按**要不要主进程**分三族，三族的登记形状不同、各有各的锁（`JsHostApiTest`）：

| 族 | 名字 | 表里有没有它 |
|---|---|---|
| **纯计算**（`JsApiTarget.COMPUTE`，执行器进程内算完就回，不经主进程） | 第一批 15 项：`md5` `sha1` `sha256` `base64Encode`/`base64Decode`/`base64EncodeUrl`/`base64DecodeUrl` `hexEncode`/`hexDecode` `aesEncode`/`aesDecode` `desEncode`/`desDecode` `timestamp` `FormatDate`；2026-09-12 补 6 项：`uriEncode` `randomUUID` `digestHex` `hmacBase64` `symmetricCrypto` `bytesToStr` | 有。顶层与 `java.` 两个入口并存（语料两种写法都有） |
| **改名（别名）** | `md5Encode`→`md5`、`hexDecodeToString`→`hexDecode`、`HMacBase64`→`hmacBase64`、`encodeURI`→`uriEncode`、`getString`→`queryString`、`timeFormat`→`FormatDate`（单参时补默认格式 `yyyy-MM-dd HH:mm:ss`，语料 32/49 处只给时间戳）、`longToast`→`toast`（2026-09-12：语料两池全量 248 次调用、按 `bookSourceUrl` 去重 34 条源（去重后 144 次），但**按承载字段拆开才是真收益**（`build/survey-longtoast-per-source2.js`）——196 次写在 `loginUrl`(164)/`jsLib`(20)/`loginCheckJs`(6)/`ruleContent.payAction`(6) 这四类**本仓根本不执行**的字段里，落在会求值的规则上的是 **52 次 / 16 条源**（`ruleSearch.bookList`、`ruleToc.chapterList`、`ruleContent.content`、`exploreUrl`、`ruleBookInfo.init`、`searchUrl`）；`bookSourceComment` 里 0 次，那字段是自定义 UI 脚本、本仓不执行；**一律 1 参**，故表里 `TOAST` 的元数不放宽；长短的差别只在弹多久的 UI，本仓这一族一律映射成日志） | 表里只有**目标** api 的名字。只在 `java.` 上，不上 `globalThis` |
| **门面**（脚本侧把一次调用包成对象或一步） | `createSymmetricCrypto(变换, key, iv)` → `{decryptStr, decrypt, encrypt, encryptBase64, encryptHex}`（语料 91 次）、`aesBase64DecodeToString(密文, key, 变换, iv)`（19 次，实参顺序固定）、`base64DecodeToByteArray`、`hexDecodeToByteArray`、`strToBytes` | **不登记**。登记了就等于声称主进程有这门能力，而它实际只是一次 `symmetricCrypto` 转发或一个标记 |
| **对象面转发**（第二个名字面，纯转发到 HOST） | `cookie.getCookie(url)` / `setCookie(url, header)` / `removeCookie(url)`，以及上游同义的 `java.getCookie` / `setCookie` / `removeCookie`。2026-09-12 按接收者复测（`build/survey-cookie-receivers.js`，两池全量记录、含 `bookSourceComment`）：`cookie.` 面 31/18/104 次（`removeCookie` 覆盖 49 条记录、是这一族的主力），`java.` 面 10/0/2 次，裸调用 0；再按承载字段拆执行面（`build/survey-field-split.js`，剔除 `loginUrl`/`jsLib`/`bookSourceComment` 等本仓不执行的字段）：`getCookie` 12 次/5 源、`setCookie` 12 次/5 源、`removeCookie` 59 次/31 源；表里 api 名是 `cookieGet`(1) / `cookieSet`(2) / `cookieRemove`(1) | 有。脚本侧名字与表名**不同名**，因此与别名族同样走 `defOn(...)` 转发行、不上 `globalThis` |

五条口径，改动时实现、单测与本节三处同步：

1. **字节材料只有字符串过边界**。帧上搬不动 `byte[]`，所以「这是一串字节」用 `"<enc>:<text>"` 令牌表达，`enc` ∈ `base64`/`hex`/`utf8`/`latin1`；**前缀不认得即整串按 UTF-8 文本处理**（`https://x` 含冒号也不会被误当标记）。JS 侧的 `base64DecodeToByteArray` 一族因此**零跨进程往返**——只打标记，真解码发生在下一次计算里（`HostCompute.materialToBytes` 按前缀解，与垫片是同一套约定）。「产物是字节」的调用（`encrypt`/`decrypt`）回 `base64:` 令牌并被就地重新包成对象，好让它接着喂给下一次调用；`decryptStr`/`encryptHex`/`encryptBase64` 才是给人看的形态。
2. **算法名归一后查表，认不出即拒绝**。归一 = 转小写 + 只留字母数字，故 `SHA-256`/`sha-256`/`SHA256` 同义；`PKCS7Padding` 在 16 字节块上与 `PKCS5Padding` 同义、而 JCE 只认后者，故归一为 `PKCS5Padding`。**绝不回落默认算法**：默默换成 MD5 会算出一个「看着像」的签名，站点回 401 时才查得出来。对称变换必须三段 `algorithm/mode/padding`；ECB 不消费 IV，带 IV 的变换没给 IV 时报可诊断的拒绝而不是 JCE 那句 `IV required`。
3. **网络面两处补齐**（2026-09-12）：`java.post(url, body, headers)` 的第三参（语料 42/42 全是三参）——对象或其 JSON 文本都收，脚本自己写的 `Content-Type` **覆盖**表单体默认头；字符串体**不猜** `Content-Type`（猜错的表现是站点回 400 而不是本地报错，比让脚本自己写头更难查）。`java.setContent(text, url?)` 改写后续规则求值的基准页（语料 31 处一律是「先 `ajax` 取一页、再让 `getElements`/`getString` 定位到那一页」）——它**不发网络、不烧外呼配额**，第二参缺席时**不清空**既有基准 URL。
4. **`encodeURI` 只挂 `java` 面**：顶层 `encodeURI` 是 ECMA 内建（空格成 `%20`），而 `java.encodeURI` 要的是表单百分号编码（空格成 `+`）。把后者挂上 `globalThis` 会静默改掉前者的语义——纯计算族那个统一的 `globalThis` 落点因此**不适用于别名族**。
5. **`cookie` 面落在「一源一份」的会话罐上**（2026-09-12）：`SourceCookieJar` 既是守门客户端的 `CookieJar`（自动存 `Set-Cookie`、自动带回 `Cookie`），也是这三个方法的落点，**两处必须同一个实例**——两份罐的形态是「脚本 `java.ajax` 登录攒下了会话，`cookie.getCookie` 永远读到空串」，而装配点因此只有 `ScriptBookParser` 一处（罐随该 parser 进 LRU、被逐出即丢掉，**不落盘**，代价登记在 §11-37）。三条语义口径：实参一律是**地址或裸 host**（裸 host 是语料主流写法，补 `https://` 后交 OkHttp 解析；解不出按参数错误拒绝，静默当「这一域没有 cookie」会把人支去换源）；`setCookie(url, header)` 把 `k=v; k2=v2` **逐对拆成独立 cookie**、路径固定 `/`、hostOnly（整串交给 `Cookie.parse` 会把 `;` 之后的内容当成第一个 cookie 的属性吃掉，症状是脚本自己写进去的 token 下次读又没了），**空串是清除**而不是写入空值（语料登录前的写法就是 `setCookie(url, '')`）；三方法**不烧外呼配额**（一次 `java.ajax` 才是一次外呼，读罐不是）。

> **登记形状与测试锁的对应（本仓规定）**：垫片里字面量 `def('名字', min, max…` 只用于**纯转发到 HOST** 的能力，`JsHostApiTest` 按该字面量比对「名字在表里、元数与表同值」；别名族与门面族刻意用数组在循环里装配（`defOn(java, p[0], …)`），名字对那条正则不可见，改由各自的专门锁把住——别名族锁「转发目标都在表里、脚本侧名单不漂移」，门面族锁「这五个名字**不在**表里、装配点仍在垫片」。把别名或门面改写字面量 `def(`，症状是白名单锁把每条改名都判成「表里查无此名」而红。`cookie` 面同属正则看不见的一类（`defOn(cookie, 'getCookie', 1, 1, fwdVar('cookieGet'))`：脚本名与表名不同名、且实参是 `fwdVar(...)` 的返回值），它由两处把住：`JsHostApiTest` 的**对象面逐条比对**（`cookie` 与 `source`/`book`/`chapter`/`java` 同列，核「转发的 api 在表里、元数同值、目标就是这几个名字」）与 `DesktopJsHostTest` 的**真内核写读清三步**（JVM 侧的代理用例证明不了垫片有没有把这个名字挂上）。另一处只在真内核才现形的约束：转发行引用的 `fwdVar` 是 `var` 赋值的函数表达式，**不提升**，所以 `cookie` 那六行必须排在 `var fwdVar = function (api) {...}` **之后**——挪到前面，症状不是「cookie 用不了」而是**每一条含 JS 的源都解不开**（垫片整体求值失败，`js_bridge` 只留下一行 `prelude eval failed`）。

## 6. URL 语义

### 6.1 `URL,{option}` 形态与切分

```
https://www.baidu.com,{"charset":"gbk","method":"POST","body":"bid=10086","headers":{"User-Agent":"…"},"webView":true}
/search/,{\n "charset": "gbk",\n "method": "POST",\n "body": "page={{page}}&key={{key}}"\n}
```

- **判定**：规则字符串**末尾**的 `,{` … `}` 尾段即选项对象；选项整体是一个 JSON 对象（因此值里含逗号、花括号必须由 JSON 自己表达——引号与转义才是切分依据，不做「按逗号裸切」）。〔依据：全部示例都是合法 JSON 对象；`charset` 为 utf-8 时可省略〔语法文档〕〕
- **不能挂在链上**：`tag.a@href,{"webView":true}` 与 `$.link,{"webView":true}` 是**明确错误**的写法；正确写法是 `tag.a@href##$##{"webView":true}`、`{{@@tag.a@href}},{"webView":true}`、`tag.a@href@js:result+',{"webView":true}'`〔语法文档原文警告〕。含义：选项尾段只在「该字符串本身就是 URL」时被识别，**不接受挂在选择器语法后面**。
- **⚠️ 上一条三个例子中，第一个（`##$##{...}`）的写法按本规格其余条款读应带逗号，即 `##$##,{...}`。**
  理由是同句的另外两个例子都把逗号写在替换文本里——`{{@@…}},{...}` 的尾段以 `,` 起头，
  `@js:result+',{...}'` 的 JS 字符串字面量同样以 `,` 起头；而本节的**判定**条款（上一行）明写
  「规则字符串末尾的 `,{` … `}` 尾段即选项对象」，§11 的边界清单亦记「尾段定位要求候选 `{` 前有 `,`」。
  三处相互印证，故把 `##$##{...}` 视为该例的**转写脱字**、以 `##$##,{...}` 为准。
  实践中两者差别是静默的：不带逗号时那个 `{"..."}` 会被当作 URL 正文拼进去、不被识别为选项，
  最终请求一个带花括号的地址而取不到内容（无报错）。**此处不改写引用原文**——它是外部文档的
  原话，擅改等于伪造出处；本条只是本规格对它的读法说明。若能取到该外部文档原文，
  应回头核对 `##$##` 之后究竟有无逗号，并据此把本条并入判定条款。
- **复杂 URL 可整条写成 JS**，用 `"https://host," + JSON.stringify(option)` 返回〔语法文档〕。
  语料实证规模：1168 条源里 URL 位规则以 `@js:` 开头 282 条、以 `<js>` 开头 70 条
  （其中 39 条**只写开标记、不写闭合**）。本仓的实现口径：
  - **判据只看串首**（`@js:` 不区分大小写，或 `<js>`），**不用 `contains`**：标志作为查询参数值
    出现在合法 URL 里是真实形态，放宽判据会把一条普通地址换进 JS 分支——症状是「搜索永远没结果
    且不报错」。
  - **求值发生在尾段切分与落位之前**，且交给求值器的是**原样规则串**：剥标志、`{{}}` 展开、
    真求值全在求值器与沙箱那一侧（§9 第 1 步因此对本形态而言是在求值器内部发生的，不是两道）。
    URL 层自己先展开一次的后果除了「两套事实源」，还有 `{{java.put(…)}}` 这类副作用插值跑两遍。
  - **产出文本再走同一套尾段切分与三形态落位**（§6.5）：上面那句 `"https://host," + JSON.stringify(option)`
    正是要求选项在 JS 拼完之后才被识别。产出侧**不**再做 §6.4 的 `<分隔符,内容>` 取舍——
    JS 的输出是数据，里面的 `<,2>` 不是作者写的页码段。
  - **空产出按「脚本执行失败」报**，不落到 `join`：空地址经落位会请求源首页，拿回的是
    「看着正常、实则错到底」的内容（§9 末段明令禁止的回退）。
  - 串首 `<js>` 且**无闭合**按「整条规则写成 JS」的标记处置（代码取到串尾、无后链，与 `@js:` 同义）；
    「有前链又缺闭合」仍是语法错误——那条规则里的前链文本注定解不了（§2.1）。
  - 未接线时的症状值得留档：脚本原文被当相对地址拼到源根后**真的发出去**，回放日志里满屏
    `https://host/@js: var k = …` 的 403/404。实现见 `ScriptUrlResolver.resolveJsUrl`，
    锁形见 `ScriptUrlResolverTest` 与 `ScriptHttpTest`。

### 6.2 全部已知选项键

| 键 | 语义 | 证据 |
|---|---|---|
| `method` | 请求方法；`POST` 时配合 `body` | 〔语法文档 UrlOption 清单 + 示例〕 |
| `charset` | 响应解码字符集；`utf-8` 可省略；语料含 `gbk`/`gb2312`/`gbk` 系 | 〔语法文档 + 语料〕 |
| `body` | 请求体（字符串）。必须是 JS 的 String 类型（文档反复强调 `String(body)` 强转） | 〔语法文档〕 |
| `headers` | 请求头。**值可为嵌套 JSON 对象，也可为「被字符串化的 JSON」**（两种示例并存）；key 区分大小写（`User-Agent` 正确、`user-agent` 无效）；`proxy` 放在 headers 里（`socks5://host:port`、`http://host:port`、`http://user:pass@host:port`、旧式 `http://host:port@user@pass`），无意义的头会被忽略，无效代理配置直接返回错误而不绕过代理 | 〔官方文档 + 语法文档〕 |
| `webView` | 非空即用内置 WebView 加载该页；进入 WebView 后 `timeout`/`followRedirects` 不再控制页面加载 | 〔语法文档 + 官方文档〕 |
| `js` | **解析 URL 时执行**，可改 `java.url`、`java.headerMap` | 〔官方文档〕 |
| `bodyJs` | **对响应体做二次 JS 处理**：`{"bodyJs":"if(result)'这里的文本作为访问返回的响应体body'else result"}` | 〔官方文档〕 |
| `retry` | 重试次数（Int，默认 0） | 〔语法文档 UrlOption〕 |
| `timeout` | 读取超时（毫秒） | 〔官方文档〕 |
| `followRedirects` | 是否跟随重定向（Boolean） | 〔官方文档〕 |
| `dnsIp` | 强制指定目标 IP；英文逗号分隔多个 IPv4/IPv6；仅作用于当前链接的域名，不覆盖重定向/CDN 域名；**不能与 `proxy` 同时使用**（组合直接返回错误） | 〔官方文档〕 |
| `type` | 〔未能核实〕在 UrlOption 清单里，官方文档只说「文件信息获取失败时会自动拼接书名、作者和下载链接 UrlOption 的 `type` 字段」→ 判断与文件源的命名/类型有关 |
| `js` 的变体 `@js:` | 不是一回事：`@js:` 是规则段（§2.1） | — |

图片链接专用选项（正文里的 `<img src="URL,{...}">`，同一套尾段解析）：`click`（点击执行的 JS）、`js`、`style`（`text`/`full`/`single`/`left`/`right`，正文场景大写 `TEXT` 占 1.5 字符位）、`width`（数字=像素、带 `%`=最大宽度百分比）、`reviewCount`（旧书源行内段评兼容，仅 `style` 为 `text`/`TEXT` 且 `click` 非空时生效）。〔官方文档〕

### 6.3 POST 与 body 形态

- **form**：`{"method":"POST","body":"searchkey={{key}}"}`（语料原始形态，`&` 连接、值可含 `{{}}` 插值）。
- **json / data**：文档未给出 `{"body":{"a":1}}` 这类结构化 body 的语义〔未能核实〕。语料与文档一致地把 `body` 当**字符串**处理（并强调必须是 String）。**本仓规定**：`body` 只接受字符串；给到对象/数组时按 JSON 文本序列化后使用，并记入 §11-6 跟踪。Content-Type 由 `headers` 显式给出（文档未规定 form 默认头）。
- **`@js:` 包裹的请求体**：整条 URL 用 JS 生成（`"url," + JSON.stringify(option)`），或 `body` 值由 `{{java.…}}` 计算得来——两种都是 §6.1 的正规做法。

### 6.4 `{{page}}` 与 `<page>` 两种占位形态

- `{{page}}`：页码，**初值 1**；可在里面做算术：`{{(page-1)*20}}`（offset 型站点）。〔语法文档〕
- 首页不带页码的站点，文档给两种写法〔语法文档原文〕：
  1. `{{page - 1 == 0 ? "": page}}` —— 纯 JS 三元；
  2. `<,{{page}}>` —— 尖括号形态。
- **`<>` 的精确语法〔未能核实〕**：公开文档只在「第一页没有页数」这一节里给出 `<,{{(page-1)*25}}>` 这一种形态（且出现在查询串与路径段两种位置，语料亦如此），没有任何一处解释 `<>` 与内部逗号的含义。**本仓规定**：按「页码为 1 时整段（含前导分隔符）不进入最终 URL」实现，形态取 `<` + `分隔符,内容` + `>`；实现前先以真实源在装机环境验证，验证不了就只支持形态 ①，并在导入报告里把形态 ② 标为「未支持的分页写法」。**不得**因为「看起来像」就自行发明 `<...>` 的其它用法（如条件段、多分支）。
- 与本仓原生链路的差别（重要）：原生格式用 `ListPageUrl` 把「首页裁掉 `/{{page}}` 段」做成一处收口的推算（`JsoupBookParser.kt` 的 `ListPageUrl.build`），而脚本格式**由书源作者在规则里自己写首页形态**。两条链路的页码语义因此**不同源**：脚本路径必须先完成 `{{}}`/`<>` 展开得到最终 URL，再发请求，绝不复用 `ListPageUrl` 的裁剪规则（复用它 = 对已经算好的 URL 再裁一次，会裁掉真实页码段）。

### 6.5 相对地址与多值串接

- 搜索 URL 与发现 URL **支持相对 URL**〔语法文档〕；落位语义与本仓 `TocPageUrl.join` 相同（`http(s)` 原样、`//host/path` 沿用基准 scheme、`/` 相对源根、其余相对当前页目录）。协议相对是网页通用惯用法〔语料〕：站点双协议服务时 meta/href 常这么写（真语料「小说屋」的 `og:novel:read_url` 即此形态），公开规则说明未见明文——2026-09-11 按语料补齐，须排在 `/` 形态之前判定（`//x` 同样以 `/` 开头）。
- 字段结果里出现「规则 + 逗号 + 选项」时按 §6.1 处理；`kind` 字段用 `,` 串接两个 `{{}}` 插值时，逗号是**结果文本**的一部分，不是分隔符（语料：`"kind": "{{$.type_name}},{{$.catalog_name}}"`）。

## 7. 目录与正文的翻页链

### 7.1 `ruleToc.nextTocUrl`

〔语法文档逐条列出，全为已核实〕

- 支持**单个 URL**，也支持 **URL 数组**（数组 = 多页目录页一次给出）。
- JS 中返回 `[]`、`null`、`""` → **停止**加载下一页。
- 求值时机：每抓完一页目录后执行，输入是本页文档。
- 逐字段规则 `chapterList`/`chapterName`/`chapterUrl` 在**每一页**上都跑一遍，页序即 `nextTocUrl` 给出顺序。

### 7.2 `ruleContent.nextContentUrl`（同章分页）

- 支持单个 URL 与 URL 数组〔语法文档〕。
- 语义：把返回的页面继续当作**同一章**的正文拼接（该字段住在 `ruleContent` 里，天然表示「本章未结束」）。
- 停止条件未成文 → 〔本仓规定〕与 `nextTocUrl` 同：空/`[]`/`null` 停止；另加**已访问 URL 集合**（回环即停）与**页数上限**（防御规则配错，参照本仓 `TocPager.MAX_TOC_CHAPTERS` 的既有风格）。

### 7.3 与本仓 `ChapterPageMatcher` 的关系（不会打架，但必须分道）

本仓原生链路的「同章分页判定」是**从 URL 形状推断**：只对候选链接剥一次 `[-_]\d+` 分页后缀、再与目录页给出的原始章节 URL 比对，判据是「宁漏页不串章」（`lib_book_source/.../JsoupBookParser.kt` 的 `ChapterPageMatcher`，形态由 `ChapterPageMatcherTest` 锁死）。之所以要推断，是因为原生格式**没有**「下一页链接」字段之外的信息可用。

脚本格式**不需要**这个推断：`nextContentUrl` 是书源作者显式命名的下一页链接，其可信度高于 URL 形状启发式。因此：

1. 脚本路径**不得**套用 `ChapterPageMatcher.isSameChapterPage` 去过滤 `nextContentUrl` 的结果——很多站点的同章分页 URL 完全不带 `-2`/`_2` 形态（`?page=2`、`/1234_2/`、`index2.html`），一过滤就永久漏页；
2. 反过来，脚本路径也**不得**用「`nextContentUrl` 非空就一路跟到上限」而不判回环——显式规则配错时（选到「下一章」链接）就是串章，且串得比推断式更彻底；
3. 两个格式**共用的只有防御件**：已访问 URL 集合、页数上限、正文按页拼接的顺序；判定「这一页属不属于本章」的依据各自不同（原生=URL 形状；脚本=作者显式给出 + 回环/上限拦截）。
4. `ChapterPageMatcher` 保持为原生链路唯一事实源，**新解释器不改它、不复制它的口径**。

## 8. 发现页（explore）

### 8.1 分类清单：顶层 `exploreUrl`

三种形态：两种**可切分**的成文格式〔语法文档 + 官方文档〕，加一种本仓**不消费**的脚本程序（2026-09-10 补记）。

**格式一 · 文本**：`名称::URL`，多条之间用 `&&` 或换行 `\n` 分隔。

```
男生书库::/shuku/0_1_0_0_0_{{page}}_0_0\n男频连载::/shuku/0_2_0_0_0_{{page}}_0_0
```

（URL 支持相对形态；`{{page}}` 语义同 §6.4；`{{}}` 内只能写 JS。）

**格式二 · JSON 数组**：每项 `{title, url, style}`，`style` 的五个已核实排版键为 `layout_flexGrow`、`layout_flexShrink`、`layout_alignSelf`、`layout_flexBasisPercent`、`layout_wrapBefore`（另有 `layout_justifySelf` 出现在登录 UI 示例中）。官方文档补充：与登录 UI 同构，`name` 换成 `title`，并可出现交互控件项，按钮类型全集为 `url`、`text`、`button`、`toggle`、`select`；控件当前值通过 `infoMap` 读取/修改/`save()`。

**格式三 · 脚本程序**（2026-09-10 补记，本仓不消费）：整串就是一段 `<js>…</js>`，由发现页脚本**现场算出**条目数组，返回值定义为 `[{title, url, style, type, chars, default, action}]`。`url` 是分类地址，其余键是**发现页 UI 的渲染与交互协议**——`type`/`chars`/`default`/`action` 描述选择器等控件，`style.layout_*` 是布局键，控件当前值经 `infoMap` 读写、`java.refreshExplore()` 触发重跑。

判断这条链：`url` 往往不是字面量，而是形如 `buildUrl(tagId)` 的调用结果，`tagId` 来自**控件当前值**。故没有发现页 UI 宿主（§10 把控件一栏列为 ❌）就拿不到有意义的 URL——把这段 JS 求值出来，得到的仍是一批 `url` 为空串的条目。

本仓口径：**识别出来，按空清单处置，不切、不求值、不落进 `exploreUrl` 的文本切分**。判据是「整串（去前导空白后）以 `<js>` 开头」——只看开头不扫内容，因为 `分类::<js>…</js>` 是合法的格式一，那段 JS 是待求值的目标表达式，不是发现页程序。后果是「该源少整份发现入口」，不是「整条源不可用」：搜索/详情/目录/正文与 `exploreUrl` 无关。这与 §10 矩阵「❌ 项求值时抛类型化异常」的通用口径有一处**明示的例外**（此处不抛，按空清单——与 `getKindBook` 把分类页失败按空列表处置同源），并已在词法层登记为不支持项（见 §10 该行）。

> **为什么必须识别而不是让它掉进文本切分**：按行切会把 **JS 源码逐行当成 URL** 拼到源根地址上发出去。2026-09-10 的真实事故——`番茄（发现）`（`bookSourceUrl = https://fanqienovel.com`）被切成 **105 条**空标题条目、发出 105 次 `HTTP 404：https://fanqienovel.com/<JS 源码行>`，随后 105 个空区块撞死书城列表（`LazyColumn` 的 key 全为 `""`，`IllegalArgumentException: Key "" was already used`）。锁形用例见 `ExploreUrlFormatTest` 与 `ScriptRuleSetTest`。

### 8.2 与 `bookSourceGroup` 的关系

**没有关系，两个层次**：`bookSourceGroup` 是「这一整个书源属于哪一组」（源管理页筛选用），发现页顶部分类一律来自 `exploreUrl`。应用侧的可见性判据：`exploreUrl` 非空 = 有发现，`enabledExplore` 决定是否展示（书源列表页用绿点/红点/无标志三态表示「有发现且已启用 / 有发现未启用 / 无发现」〔官方文档〕）。

### 8.3 花括号与分隔符的坑（Plan 2 必须处理）

`exploreUrl` 用 `&&` 作**条目分隔符**，而 `&&` 同时是规则组合符（§2.2）；URL 里又常含 `{...}` 选项尾段与 `{{...}}` 插值。切分 `exploreUrl` 时**必须**先把 `{{}}`/`{}` 内的内容当不透明串（含其中出现的 `&&`），否则一条带 JS 表达式的发现 URL 会被腰斩。〔本仓规定；依据：官方文档同时允许这两种写法〕

## 9. 求值顺序与错误语义（一页总览）

```
一条字段规则 R：
 1. {{}} / {} 展开（不透明结果，见 §5.1）
 2. 剥尾段：正则替换段（##…）、URL 选项尾段（,{…}）
 3. 按 §2.3 的次序切成支（%% → || → &&）与段链（@）
 4. 判定模式（§2.1 标志表）
 5. 逐支求值：每支的输入 = 当前上下文文档/文本
      · 列表字段：结果 = 节点列表，逐条作为子字段规则的上下文
      · 单值字段：结果 = 取值器输出（§3.2）
      · || 拿到值即停；&& 全部求值后合并；%% 交错取数
 6. 结果应用正则替换（AllInOne 在取条目时即生效；OnlyOne 只替第一个；净化循环替全部）
 7. 结果为 URL 类字段时按 §6.1/§6.5 落位成绝对地址、必要时附选项参与下一次请求
 失败与空：
 · 空规则串 → 空结果（不报错）
 · 未取到值 → || 下一支；无下一支 → 空串
 · 语法错误 → 类型化「规则语法错误」，按未取到值参与 || 短路（本仓规定）
 · @js: / <js> → 交执行器（零权限隔离进程）；未装配执行器时抛类型化「需脚本沙箱执行器」，
   装配了而运行失败抛「脚本执行失败（状态）」并带内核原文——两者不得合并成一句提示，
   且一律不得回退成「返回原值」或静默截断（否则产出的是看起来正常、实则错到底的内容）
 · {{…}} 的 JS 分支：标量按文本回填、undefined/null 展开成空串（同「未取到值」，见 §5.1）、
   对象/数组按「脚本执行失败」报——不回 null，也不把 JSON 文本冒充成 URL 片段
 · webJs / jsLib / XPath → 能力缺口，不是还没接线：XPath 段求值时抛类型化「本项目不支持」；
   webJs 与 jsLib 是源级字段，装载时计入不支持清单、导入预览逐条明示，求值时一律不消费
```

## 10. 能力矩阵

「语料频度」列只用三类数字：ADR-0029 记录的 642 条实测语料统计（原文照引）、2026-09-11 起**录制回放**按调用名统计的整数（1168 条源，工具在仓、录音与统计件在 gitignore 之外），以及本仓明确没有统计到的项写 `unknown`。**不编造新数字**；出自第二类的数字若已无法复算，按 `unknown` 看待——它能支撑「这条能力值不值得做」的判断，不能单独支撑「表里给它出口」。

**调用数是整条记录口径，不等于解析链路收益**：记录里另有四类本仓根本不执行的字段（`bookSourceComment` 自定义 UI 脚本、`loginUrl`/`loginCheckJs` 登录面、`jsLib`、`ruleContent.payAction`），名字出现在这些字段里的调用不会被本仓求值。差距大的项在行内注明按承载字段拆开后的数（今日已拆：`longToast` 全量 248 次/34 源 → 执行面上 52 次/16 源，见 §5.4 别名行），未拆的行按全量看待。

本表是**求值层**（2a~2c）的能力口径。2d 的页面装配与接线**不新增语法**：✅ 项即 `ScriptBookParser` 实现的五个求值面（搜索 / 详情 / 目录 / 发现 / 正文），五面均已用户可达——**发现面已接书城**（2e：`exploreUrl` 条目经 `getExploreEntries` → 书城分类胶囊，url 承载规则串、由 `getKindBook` 求值渲染）。❌ 项仍是 ❌——求值时抛类型化异常、导入时计入不支持清单，不会因为「已接页面」就悄悄变成可用。Plan 3（执行器）落地后本表 ❌ 列**只减少不新增**：矩阵说的是语法能力，某个源因白名单/超时跑不出来属运行期失败，不改本表口径。

| 语法特性 | 语料出现频度 | 本仓是否实现 | 不实现的后果 / 已实现的残余限制 |
|---|---|---|---|
| 默认（链式）选择器模式 + `@` 段链 | 642 条中声明式源的主体（ADR：62% 含 JS ⇒ 至多 38% 纯声明式） | ✅ | — |
| `@css:` CSS 模式 | unknown | ✅ | — |
| 组合符 `&&` | unknown | ✅ | 缺字段拼不全（书名+作者分两段时最常见） |
| 组合符 `||` 兜底 | unknown（ADR 统计的「JSONPath 模式 135 条」中含大量 `||` 兜底写法） | ✅ | 改版站点直接空结果 |
| 组合符 `%%` 交错取数 | unknown | ✅（纯取数逻辑，成本低） | `kind` 等「多来源交错成一个列表」字段顺序错乱 |
| 净化正则 `##re##rep` | unknown | ✅ | 正文残留广告/站点噪声（本仓正文净化的主要手段） |
| OnlyOne `##re##rep###` | unknown | ✅ | 详情页字段留前缀 |
| 正则 AllInOne（`:` 开头 + `$n`） | 目录/正文翻页链 180/236 条（ADR）；AllInOne 自身 unknown | ✅ | 无 DOM 结构的 TXT 流站点整源不可用 |
| JSONPath（`@json:`/`$.`/`{}`） | **135 条**（ADR） | ✅ | API 型站点整源不可用 |
| 索引语法 `[]`（含区间、排除、`[-1:0]` 反序） | unknown | ✅ | 取错条目；目录多表站点全废 |
| 列表反序（`-` 前导） | unknown | ✅ | 倒序目录站点章节顺序颠倒 |
| 变量 `@put:` / `@get:` / `java.put` / `java.get` | 递归求值与变量存取合计 300+ 次使用（ADR，含 JS 场景） | ✅（`java.put`/`java.get` 经回调通道落 `EvalContext.variables`，与 `@put:`/`@get:` 同表） | `java.get` 只有「读变量」一义、不重载成取页（§11-27；网络形态的 48 次调用缺口见 §11-35）；变量表的作用域是**单次解析任务**（跨多次沙箱调用可见、嵌套求值按引用共用），按「一次沙箱调用」重置的是 `globalThis`（§11-33） |
| `java.*` 纯计算与编解码族（签名/加解密/编码/UUID） | `createSymmetricCrypto` 91、`uriEncode` 26、`randomUUID` 12、`digestHex` 10、`bytesToStr` 10、`hmacBase64` 4（名单见 §5.4） | ✅（21 项，执行器进程内算完即回、不经主进程；字节量按 `"<enc>:<text>"` 令牌过边界） | 这一族缺席时的症状是 `not a function` 而不是「解不出内容」：反爬签名站点整源搜不到，且脚本不会给出任何「规则写错了」的线索 |
| `java.*` 改名别名族与脚本侧门面族 | `getString` 531、`longToast` 248 次/34 源（拆承载字段后：执行面 52 次/16 源，§5.4）、`timeFormat` 49（32 处单参）、`md5Encode`/`hexDecodeToString`/`HMacBase64`/`encodeURI` 若干；门面 `aesBase64DecodeToString` 19 | ✅（2026-09-12 补齐，只在垫片装配，`JsHostApi` 表里**不登记**这些名字） | 别名与表名混登会让白名单谎报「主进程有这门能力」；别名缺失的症状同样是 `not a function`。`encodeURI` 刻意不覆盖 ECMA 内建（§5.4 口径 4） |
| `java.post` 请求头第三参 / `java.setContent` 改写基准页 | `post` 42/42 为三参、`setContent` 31 | ✅（`setContent` 不发网络、不烧外呼配额） | 缺 `setContent` 时「先 ajax 取一页再定位」的写法会把后续求值留在上一页，解出的是不相干内容而不是报错 |
| `java.get` 网络形态与 Response 面（`.statusCode()`/`.header()`/`.body()`） | `java.get` 313 次中 48 次（另需 `ScriptTransport` 携带状态码与响应头） | ❌ 缺口（§11-35） | 只靠「嗅探重定向真实地址」「读响应头判断登录态」的源解不开；明示为能力缺失，不给一个恒空的 `getResponse` 出口 |
| `cookie` 面（`cookie.getCookie`/`setCookie`/`removeCookie` 与 `java.` 同义三名） | 14 条录制记录用到（其中 12 条只用到 `removeCookie`）；语料按接收者复测（`build/survey-cookie-receivers.js`，两池全量记录）：`cookie.` 面 31/18/104 次（`removeCookie` 覆盖 49 条记录）、`java.` 面 10/0/2 次、裸调用 0 | ✅（2026-09-12 补。落点是一源一份的 `SourceCookieJar`，与守门客户端同一实例，形状与锁见 §5.4 口径 5；`cookieManager` 与 `getKey`/`getCookieMap`/`replaceCookie`/`setWebCookie`/`mapToCookie` 仍 ❌，§11-36） | 罐**不落盘**：跨应用重启存活的会话冷启动后读到空串（§11-37）；**声明式取文不带罐**，纯靠 `Set-Cookie` 自动回种的源仍解不开（同处） |
| `{{}}` 插值（含 `book`/`chapter` 属性、净化用） | 62% 含 JS 的源几乎必用 | ✅（标识符形态的内置量与变量在本地取值，其余表达式分支交执行器） | 表达式取 `chapter`/`title`/`src` 时拿到 `undefined`——正文链路手上只有 `contentRef`（§11-31）；每个 JS 表达式各付一次跨进程往返 |
| `source` 对象面（源级字段 + 书源变量） | **248/1168 条**源引用（`getVariable` 262、`bookSourceUrl` 238、`setVariable` 150、`getKey`+`key` 244 次） | ✅（数据经 `sourceJson` 绑定过边界，方法由垫片每次执行装配；按键变量折进任务表，§11-34） | 登录/刷新一族 12 个方法按 `__UNSUPPORTED__:` 明示；`server`（3 次）/`loginUi`（2 次）不绑，读到 `undefined`；源级 `header` 键只作为 `source.header` 文本给脚本读，**声明式链路的请求头不消费它**（§11-34） |
| `<js></js>` 内联段 | unknown | ✅（经执行器） | `jsLib` 仍 ❌ ⇒ 依赖 `@import` 声明的全局库函数的脚本段以内核 ReferenceError 失败；`coverDecodeJs` 一类源级 JS 字段不消费 |
| `@js:` | 62% 的源含可执行 JS（ADR） | ✅（经执行器，「至多 38% 纯声明式」的覆盖率上限自此解开） | 一帧之内受限：wall-clock 超时、堆限、栈限，超限即销毁该帧；脚本回调进来的**嵌套规则层刻意关掉 JS**，其中的 `@js:` 仍报「需脚本沙箱执行器」 |
| URL 选项尾段 `,{…}` | unknown | ✅ | POST 型搜索失效 |
| `method` + `body`（POST） | **233 条** POST 搜索（ADR） | ✅ | 三分之一强语料搜不到东西 |
| `charset`（含 gbk 系） | **68 条** gbk 系（ADR） | ✅ | 中文乱码（老站常态）；本仓已有 `EncodingInterceptor`/charset 处理可复用 |
| `headers`（含 `proxy`） | unknown | ✅（headers）；`proxy` ❌（本项目不允许书源请求出到任意代理，安全口径） | 反爬 403；代理类源不可用 |
| `timeout` / `retry` / `followRedirects` / `dnsIp` | unknown | 部分：`timeout`、`retry` ✅；`followRedirects`、`dnsIp` ❌（OkHttp 客户端配置口径，非规则语义） | 个别源超时/重定向行为与作者预期不符 |
| `js` / `bodyJs` 选项 | unknown | ✅（解析期携带、取文期执行） | 改写出的 URL 与请求头由**取文层**按原路径发出：走不带凭证的书源纯净客户端，但**不**过脚本外呼那道 host 白名单与私网拒绝（守门目前只覆盖 `java.ajax`/`load`/`post`/`responseCode`） |
| `webView` / `sourceRegex`（嗅探） | unknown | ❌ 明确延后（无 WebView 桥） | 动态渲染站与音视频源整源不可用 |
| `webJs` | 3/642 与 XPath 合计（ADR：仅 3 条源受 XPath/webJs 影响） | ❌ **明确延后**（ADR-0029 决策 2） | 导入时逐条如实报告「不支持」，不静默产出坏源 |
| XPath（`@XPath:` / `//`） | 与 webJs 合计 3/642（ADR） | ❌ **明确延后**（同上） | 同上 |
| `nextTocUrl` | 目录翻页 **180 条**（ADR） | ✅ | 长目录被截成第一页 |
| `nextContentUrl` | 正文翻页 **236 条**（ADR） | ✅ | 章节正文缺一半（分页站常态） |
| `contentBatch` | unknown | ❌ 延后 | 逐章请求，慢但可用 |
| 发现页 `exploreUrl`（文本格式一） | unknown | ✅ | 书城少一个入口（本项目书城按默认源浏览） |
| 发现页 JSON 格式二（含 `style`、交互控件、`infoMap`） | unknown | 仅 `{title,url}`；控件 ❌ | 需要筛选控件的分类页取不到数据 |
| 发现页第三形态 `<js>` 程序（现场算出条目数组） | unknown | ❌（识别后按空清单处置） | 该源少整份发现入口。**不得**落进文本切分：会把 JS 源码逐行当 URL 发出去（2026-09-10 真实事故，见 §8.1 注记） |
| `loginUrl`/`loginUi`/`loginCheckJs` | **110 条**依赖登录（ADR） | ❌ 不支持，导入明示（ADR-0029 决策 4） | 这些源部分功能受限，明示优于静默坏 |
| `bookSourceType` 非 0（漫画/音频/文件/视频） | **88 条**漫画/音频类（ADR） | ❌ 本项目为文字阅读器，导入明示 | 明示项，非缺陷 |
| `ruleReview`（段评） | unknown | ❌ 不实现（§1.7） | 无段评 |
| 回调事件 / 自定义按钮 / 封面与图片解密 | unknown | ❌ | UI 侧能力，不在解析范围 |
| 登录态/浏览器自动化/验证码一族、旧 Java 类桥 | 300+ 次递归与变量使用（ADR 同源统计） | ❌ 不支持（ADR-0028 决策 6） | 明示 |

## 11. 本仓需澄清的未知项

诚实清单：这些在公开文档里找不到确定答案。Plan 2 的实现者**不得**把它们当已知事实；每条都要么按「本仓规定」写死并单测锁形，要么在导入报告里如实标「不支持」。

1. **组合符混用优先级**：`%%` / `&&` / `||` 同时出现时的切分次序无成文规定（§2.3 是本仓规定）。→ **2a 已钉死**：次序 `%%` → `||` → `&&` 实现成树（`RuleNode.Percent`/`FirstOf`/`AllOf`/`Leaf`），见 `RuleSplitter` 与 `RuleSplitterTest`；空段留在树上成 `RuleNode.Empty`，嵌套由 `RuleScanner` 的括号深度把住。
2. **单值字段遇到多匹配**：取第一个、还是全部串接（以及用什么连接符）——文档只说「不加位置会获取所有」。→ **2b 已钉死收敛口径**：求值层一律**保留全部匹配**（`RuleResult.Nodes/Texts/Matches`），「取第一个」由字段级调用方经 `RuleResult.firstText()` 一处收口（取第一个，没有则空串），词法/求值两层都不私自收敛，见 `RuleResult.kt` 与 `RuleResultTest`；「用什么连接符」的串接形态随「取第一个」决策不再出现，连接符问题只余 `&&` 合并场景（→ 本条清单第 15 项）。
3. **`@textNodes` 与 `@textNode`**：两种写法在文档与第三方知识库中并存，是否等价、返回是列表还是串接串，未核实。→ **2b 已按本仓规定钉死**：只认复数 `@textNodes`（`AccessorKind.TEXT_NODES`，只取命中元素自己的直接文本子节点、不递归）；单数 `@textNode` 与其余未证实取值器一样落 `ATTRIBUTE` 兜底（按属性名取值，查不到该属性即未取到值），由导入报告如实标出（见 `ChainLink.AccessorKind` 的 KDoc）。用例见 `RuleResultTest` 的 textNodes 用例。
4. **转义**：是否存在任何反斜杠转义分隔符的记法（`@`、`##`、`&&` 都可能出现在字面文本里）。公开文档未见。→ **2a 按「无转义可用」落地并已锁形**：`ChainLinkTest` 的 `不在括号里的连续 at 各切一段` 断言「没有括号保护时 `@` 一律切段」，故属性名真含 `@`（邮箱、`@media`）在本格式无写法；改这条口径必须同时改该用例与本条。
5. **`useRealUrl` 一类内置变量**：ADR/任务清单里提到，官方变量表中不存在。重定向取真实 URL 只有 JS 途径（Plan 3）。**结论：应从 Plan 2 的能力清单里删除。**
6. **`body` 为对象/数组时的语义**、`Content-Type` 默认值、以及 `type` 选项键的确切含义。→ **2c 已按本仓规定落地**：`body` 对象/数组按 JSON 文本序列化（`ScriptUrlOption.parse`）；`Content-Type` 默认值仍未核实——实现**不注入**任何默认头，须要 form 头的源由 `headers` 显式给；`type` 键语义未核实，实现选择**忽略**（连同一切未知键——把不认识的键当错误会让带新兴键的源整条不可用）。
7. **`<>` 页码形态的完整语法**（§6.4），以及它是否还用于其它「首页特殊化」场景。→ **2c 已按本仓规定落地**（`ScriptUrlResolver.anglePages`）：页码 1 整段（含分隔符）不进 URL、其余页输出「段内首逗号之前的分隔符 + 之后的内容」、无逗号结构的 `<...>` 原样保留；页码段在**插值占位符态**判形与取舍、回填在其后（展开值里形如 `<...,...>` 的内容不是结构，2026-09-09 评审修正）。真实源验证仍未做（2d 装机）。
8. **索引越界行为**（§4）。→ **2a 已钉死**：`IndexSelector.select` 端点按集合边界裁剪、越界的单个索引逐项跳过、全部被过滤即空列表（交回 §3.3 的「未取到值」），**任何情况都不抛**，见 `IndexSelectorTest` 的裁剪语义用例。
9. **规则求值抛错传播**：单条规则内部异常对外表现为「未取到值」还是整次解析失败，文档未述。→ **2b 已钉死**：`RuleSyntaxException` 在 `||` 的每一支按「未取到值」处理、继续下一支（§3.3），但**全部支都失败时把最后一个错误抛出**——静默返回 Miss 等于把「规则写错了」冒充成「这个源没有这条信息」；`JsEvaluationPendingException`/`UnsupportedRuleFeatureException` 不参与短路、照常穿透。见 `ScriptRuleEvaluator.firstOf` 与 `ScriptRuleEvaluatorCombinatorTest` 的错误传播用例。
10. **`@cache:` 前缀是否存在**（本文检索过的一切材料均无）。
11. **`ruleContent` 的「副文」「购买操作」两个能力的 JSON 键名**，以及 `ruleToc` 中除已核实六项之外还有哪些真实存在的键。
12. **`bookSourceGroup` 的多分组分隔符**。
13. **`exploreUrl` 中 `&&` 与 URL 内 `&&`（查询串）如何不互相误切**：文档允许「`&&` 或 `\n` 分隔条目」，而 URL 查询串天然含 `&&`。本仓规定：条目分隔用**行优先**（先按 `\n` 切，再在单行内按 `&&` 切），并要求真实源验证。→ **2c 已落地**（`ExploreUrlFormat.splitText`）：行优先实现如上本仓规定；插值/引号内容对分隔符不透明（§8.3），未闭合引号/花括号从宽（余段保持不透明、只少切不炸不抛）。两个已知边界须 2d 语料验证：`exploreUrl` 以 `[` 开头会被当作 JSON 格式二解析，首条目标题以 `[` 开头的文本格式因此误判返回空清单（锁形测试在案）；引号判定是朴素版（`\` 转义对不跳，`\"` 序列可能引错状态）。
14. **列表反序 `-`（§2.5）与组合符混用时的作用域**：文档只给了「在取列表的规则最前面加负号」与语料实证形态 `-:<li>…`（`-` 直接前导一个标志），没有说 `-` 遇上 `%%`/`||`/`&&` 时反序辖整条组合表达式还是只辖紧邻的那一支。本仓按**每条规则段各自**实现：`RuleMode.of` 在进组合切分之前剥前缀并产出 `RuleHead.reverse`，`RuleSplitter` 对每支各判一次，标志落在各支自己的 `RuleNode.Leaf.reverse` 上（见 `RuleSplitterTest` 的 `反序标志按支各判而不是辖整条组合表达式`）。2b 消费时不得自行推广成「辖整条」，并要求真实源验证。
15. **`&&` 合并多个文本结果的连接符**（§2.2/§3.1）：**真语料已验证，答案是「不拼接」**——合并层一律**扁平合并成多值列表**，本层不替调用方收敛，「单值字段取第一个」由字段级的 `firstText` 一处收口（`ScriptRuleEvaluator.mergeAllOf` 的 `Texts` 分支 + `RuleResult.firstText`）。判据是手机看书的真语料金标准（`ScriptRealSourceGoldenTest`）：`kind = a.1@text&&span@textNodes` 两支各出一个值（分类「修真」与日期「2026-09-07」），站点要的是「修真」；若在合并层拼成单串会得到「修真2026-09-07」。反面同理：`A&&B` 用在列表字段上时拼接会把整份列表塌成一个串从而丢条目。故**不存在**「单值拼接」这一档，连接符问题随之消解。
16. **`@put:` 值的引号规则在 JSONPath 之外的其它模式上是否一律要求引号**（§5.2 原文只豁免了 JSONPath）：本仓现状是成对引号剥去、裸值原样当规则串（`Interpolation.parsePutEntry`），缺引号不报错，须真实源验证。
17. **JSONPath 子集边界**（2c 落地）：实现 `$`/`.name`/`['name']`/`[n]`/`[-n]`/`[*]`/`.*`/`..name`/`[a:b(:c)]`/`[a,b]`；过滤器 `[?(…)]` 与脚本表达式 `[(…)]` 按不支持类型化拒绝；切片按标准 JSONPath **半开方言**（end 排他、负数从尾数）——与 §4 链式索引的**闭区间**（本仓规定）分属两个语法域，**不得互相推广**。扩集以 2d 金标准 fixtures 的语料证据为准。
18. **JSON 输入与 HTML/正则后端的域不匹配**（2c 落地的本仓规定）：`RuleValue.Json` 进链式/CSS/正则 AllInOne 后端一律抛 `UnsupportedRuleFeatureException`——它**不参与** `||` 短路（`firstOf` 只捕获 `RuleSyntaxException`），与 `JsonPathBackend` 种子对 Page/Texts「解析失败按 Miss」**刻意不对称**：前者是「选择器跑在它跑不了的东西上」，如实报能力缺口；后者是「响应不是规则串」，坏响应按未取到值交给 `||` 兜底。两侧不得为「对称好看」互相改。
19. **URL 选项尾段的两个已知边界**（2c 落地）：(a) 尾段只附着在**文本结果**上（`ScriptRuleEvaluator.withOptionTail`）——JSON 结果上直接写 `$.url,{...}` 时尾段被静默放过，§6.1 判为作者用法错误，惯例是把尾段写进替换文本（`##$##,{...}`）随替换落成 Texts；(b) 尾段定位（`ScriptUrlOption.optionTailCut`）要求**整段花括号配平**且候选 `{` 前有 `,`——不配平的段整段当取值串、不切尾段。两者都须 2d 金标准语料验证。
20. **`{{key}}` 的编码**（2d 本仓规定）：搜索关键词在装进 `EvalContext.key` 前按 `URLEncoder.encode(UTF-8)` 表单百分号编码（`ScriptBookParser.tryEncodeKey`）；不编码时中文关键词在 OkHttp `HttpUrl` 里直接非法。上游是否对 `{{key}}` 自行编码无文档，待真实语料验证——过度编码的证据会是「搜索词含 `+`/`%` 的源取回空结果」。
21. **`init` 的 AllInOne 键取值语义**（§1.4，2d 本仓规定）：规格「字段按对象键取值」的读法属 JS 分支（Plan 3）；声明式 AllInOne 的产物以**首个匹配**为条目上下文，字段规则经组引用（`$n`）在该条目上求值（`ScriptBookParser.getBookInfo` 的 `initItem`）。求值结果不是 `RuleResult.Matches`（如 init 配成链式规则）或无匹配时视为**无预处理**、字段回落整页求值——init 只是改写「字段规则喂哪个输入」的可选层，不是必经环节。
22. **`canReName` 无行为差**（§1.4）：本仓 `getBookInfo` 的入参 shelf 不携带搜索值（`addFromSearch` 只装 noteUrl/tag），「允许覆盖搜索页取值」这一前提在本管道无从发生——字段解出即填，与原生 `getBookInfo` 的无条件覆盖同形。§1.4 该行已注记；接入搜索页快照后需重新核实。
23. **翻页数组形态的访问次序**（§7.1/§7.2）：`nextTocUrl`/`nextContentUrl` 的 URL 数组按**固定页序**访问、**入口先抓**，数组元素与入口同址（剥选项尾段后比对）时跳过而不终止（`ScriptPageChain`）。目录链在数组形态下同样适用「零新增即到底」——固定清单也可能配错，零新增是护栏不是障碍；正文链无此判据（§7.2）。须真实源验证数组形态是否真按页序给出。
24. **content 字段的收敛例外**（§11-2 的例外）：单值字段一律 `firstText`，但 content 是**段落性**字段——各形态值集按 `\n` 连接（`textNodes`、多匹配的段落不许丢，`ScriptContentPager`）。`replaceRegex` 缺前导 `##` 时补一个，按净化形态生效（§2.4）。
25. **合成金标准与真实语料债**：2c 欠下的语料证据债（§11-7 的 `<>` 真实形态、§11-13 的 `[` 开头误判与朴素引号判定、§11-17 的 JSONPath 子集扩集、§11-6 的选项键频度）在 2d 以**仓内合成源**顶位锁编排行为（`ScriptFixtureSources` + `ScriptSourceEndToEndTest`）。真实 642 条语料不可得（文件已不在原路径），真源金标准 fixtures 待语料回位后按 ADR-0029 决策 8 补齐——**合成源只锁编排，不锁真实站点语义**。
26. **`isVip`/`updateTime` 无落点**（§1.5）：规格标 ✅ 且求值链路能解，但 `ChapterListEntity` 无对应列，故 2d 起按「**可解不存**」注记（与原生链路同形）。实体加列时才真正落地，届时本条改为记录落库口径。
27. **`java.get` 不实现网络形态**（Plan 3，本仓规定）：上游按重载区分 `java.get(key)` 与 `java.get(url)`，§5.2 只给了变量语义。本仓垫片只注册「读变量」一义，脚本要发请求走 `java.ajax`/`java.post`/`java.load`。同名两种行为是排查地狱，宁缺。
28. **Host API 返回裸字符串时的编码**（Plan 3，本仓规定）：`java.ajax`/`load`/`post` 拿回的是按**该请求 charset 显式字节解码**后的文本，未声明 charset 按 UTF-8——与取文层同一条口径（`body.string()` 会被 `EncodingInterceptor` 强改的 UTF-8 吃掉，gbk 站必乱码）。上游是否对裸串响应再猜编码未核实，须真实源验证；失败证据形态是「ajax 拿回的 gbk 页面解出乱码，而同一 URL 的声明式规则正常」。
29. **页面级变量（`putToPage`）与任务变量同表**（Plan 3，本仓规定）：上游区分「请求级/页面级/全局」三档作用域，本仓只有一张随 `EvalContext.variables` 走的表——`@put:`/`@get:`、`java.put`/`java.get` 与 `putToPage` 同表，表里没有的键回 `null`。留着 `putToPage` 这个名字只为让抄来的脚本不撞 ReferenceError。跨章缓存因此失效：按上游写法拿到的是 null 而不是脏值（可接受的方向）。
30. **`responseCode` 无状态**（Plan 3，本仓规定）：每次调用现做一次 HEAD 探测，不复用、也不缓存上一个请求的状态码。取 HEAD 而非复用 GET 响应的代价是多一次请求，收益是「不依赖宿主侧隐式会话状态」这条实现能单测锁住。上游是否记录最近一次请求未核实。
31. **`src`/`title`/`chapter` 绑定在本阶段恒缺**（Plan 3，本仓规定；是正文侧限制不是执行器限制）：垫片把值为 null 的绑定落成 `undefined` 而非字符串 `'null'`（后者会让净化逻辑把 `'null'` 当正文），而 `src`/`title`/`chapterJson` 三个绑定每次执行都传 null——正文链路的入口只有一个 `contentRef` 字符串，章名与书籍字段要查 Room 才有，解析层不得伸手（§5.1 分工）。`book` 由手上真有书实体的调用点给出，故详情页/目录页的 `book.name` 有值。接入章节快照后改写本条。
32. **JS 形态 `init` 的「字段=对象键」**（Plan 3，对齐 §11-21 的另一半）：脚本回传对象时字段按对象键取值；声明式 AllInOne 形态仍是「首个匹配为条目上下文 + `$n` 组引用」（§11-21）。两种形态同一条规则里不混用——`evaluateInitObject` 只在规则是**单个 JS 叶子**时命中（`@js:a||b` 里的 `||` 是脚本内容，不是组合符）。
33. **变量表与 `globalThis` 的作用域不是一回事**（Plan 3，本仓规定）：变量表随 `EvalContext`——同一次求值链路内跨多次沙箱调用可见，嵌套求值按引用共享同一张表；`globalThis` 随**一次沙箱调用**——每次执行前重建 `JSContext`，脚本自己挂上去的全局量活不过这一帧。上游靠全局量跨段缓存的写法在本仓失效，症状是「第二次调用拿到 undefined 而第一次正常」；经 `java.put`/`@put:` 写进变量表的量不受此影响。
34. **`source` 对象面的落地口径**（2026-09-11，本仓规定；接 §11-29 的作用域折表方向）：
    - **按键的三套持久变量折成一张表**。`source.put/get(key)`、`book.putVariable/getVariable`、`chapter.putVariable/getVariable` 在上游各自持久（书源行 / 书 / 章），本仓一律落 `EvalContext.variables`：保留上游方法名与元数、只降级作用域，读不到就是「没值」而不是脏值。代价是跨章/跨任务存活的写法取到空，收益是不必为三套键空间各造一份持久层。语料调用数（`source.put` 55 全为二参、`source.get` 51 中 50 为一参、`book.getVariable` 40 全为一参、`book.putVariable` 19、`chapter.putVariable` 4、`chapter.getVariable` 0）说明元数在每套上都是**一致**的，折表不会因为元数分歧而丢信息；`book.*` 合计 59 次是折表的主要需求方。
    - **整串变量是另一个形状**，不并进上表：`source.getVariable()`（262 次，**全为零参**）读的是 `variable` 那一整段文本，`setVariable(s)`（150 次真实调用，另有 4 处只出现在 `loginCheckJs` 的可用 API 注释里）整串覆盖。本仓把它落在 `EvalContext.sourceVariable`，初值取规则的 `variable` 键，**不回写 Room**——解析过程中改书源行是另一类竞态，且语料 1168 条源里 `variable` 键**非空的有 0 条**（该键在上游是给用户填的模板，本仓还没有变量编辑界面），故当前恒从空串起。症状预告：靠它跨任务存值的源每次读到空串，会走 `(!v||v=="")?source.getKey():v` 这类兜底分支——拿到的是源地址而不是脏值，方向可接受。
    - **`source` 的可选键「没配」与「配了空头」必须可分**：`sourceBindingJson()` 只在键声明过（非空白）时占位，未配的键在脚本里是 `undefined`。反过来给一堆恒空串，`if (!source.xxx)` 判不出来、`source.header.length` 之类一判就假。绑定的字段以 §5.3 本仓绑定面所列九键为限；语料里另出现的 `source.server`（3 次）与 `source.loginUi`（2 次）不在其中，脚本读到 `undefined`。
    - **源级 `header` 键仍未被任何请求路径消费**（本条登记的缺口，不是折表的一部分）：它现在只作为 `source.header` 文本给脚本读，声明式链路取页不带它。原生解析器同样不消费该键，故影响面是「配了源级默认头、又没在规则里自己写头」的源；要补应当作独立改动（涉及取文层与沙箱外呼两条路径的头合并次序），不要顺手接进这一批。
35. **`java.get` 的网络形态与 Response 面无出口**（2026-09-12 登记，`JsHostApi` 的 `GET_VAR` 注释指本条）：语料 313 次 `java.get` 里 48 次写的是 `java.get(url, headers)` 再取回响应对象（`.statusCode()` / `.header(k)` / `.body()`）。本仓**只实现单参读变量一义**（§11-27），且表里刻意不登记 `getResponse`——取文层 `ScriptTransport.execute` 只回文本，状态码与响应头在那一步已被丢掉，登记一个拿不到内容的名字等于让能力表说谎（这张表是白名单的**正面清单**）。处方（真要补时按这条改，别只加一个名字）：
    - `ScriptTransport.execute` 的返回从 `String` 换成携带 `status` / `headers` / `text` 的结果类型；**charset 显式字节解码口径不变**（§11-28），守门客户端与白名单判定也不变（§10 `js`/`bodyJs` 行记的那条缝隙不因此扩大）。
    - 表里加 `getResponse`（HOST，1–2 参），与 `ajax`/`load` 共用受理侧的准入与限流——一次 `getResponse` 就是一次外呼配额，不因为回的是对象而免单。
    - 垫片把它包成 `{statusCode, header, body}` 对象面，**字段一次跨界算齐**：若每个方法各调一次宿主，`.header()` + `.body()` 就是两次外呼、两次配额、两个可能不同的页面。形状照 §5.4 的门面族（脚本侧装配、表里不登记门面名）。
    - 失败仍走 `ok=false` 带可诊断原文（`HostHandler` 契约），不把网络失败折成「状态码 0」。
36. **`java.*` 其余未做一族**（2026-09-12 登记，频度出自录制回放统计、按 §10 开头第三类口径看待）：
    - **`cookie` 面剩下的成员**（2026-09-12 收窄：`getCookie`/`setCookie`/`removeCookie` 三名已落在按源分区的 `SourceCookieJar` 上，落点与锁见 §5.4 口径 5；本条原先「不得直接用 OkHttp 默认 `CookieJar`——那是进程级、跨源共享，等于把 A 站的会话递给 B 站」这条规定**已照办**，罐的持有者是 `ScriptBookParser` 实例）。仍缺：`cookieManager`（留在垫片 `__UNSUPPORTED__:` 名单，上游它是一整族带 WebView 的管理面）与顶层 `cookie` 上另有名字的四个成员 `getKey` / `getCookieMap` / `replaceCookie` / `setWebCookie`（`mapToCookie` 同）。**`getKey` 是这一族里用量最大的一项**（语料 708 次、集中在 6 条源；按承载字段拆（`build/survey-field-split.js getKey "cookie."`）后执行面上是 **682 次、全在 `exploreUrl` 里、只覆盖 2 条去重源**，余下 26 次在 `loginUrl`/`jsLib` 等本仓不执行的字段），但录制里用到它的源全部同时用到登录面或 Response 面，故它不是任何一条 ungated 记录的唯一拦路项；更要紧的是它的上游签名本仓未从一手文档核实（第二个实参是 cookie 名还是变量名、读的是罐还是任务表），按本节结尾「不登记即不存在」处置——照猜接线会造出一个「名字对但语义错」的出口，比 `ReferenceError` 难查得多。
    - **`jsLib`**（69 条源声明、录制里 5 条 FAIL 直接由它引起）。装载期已计入 `ScriptRuleSet.unsupported`（`ScriptUnsupported.JS_LIB`），求值时一律不消费。动它之前必须先改规格：① 源级字段形态（裸 JS 文本 / `{"name":"https://…"}`，§1.1 该行现标 ⏸）；② `@import` 的 URL 要外呼取，因此**必须落在白名单与配额之内**，不能走「取文层按原路径发出」那条不受守门的缝；③ 它是 **runtime 级**（与垫片同侧，一次装、跨帧存活，因此能覆盖 `java` 上的名字）还是**每次执行**重装（与绑定同侧，脚本改不动它）——这个选择决定「库函数能不能被用户脚本自己改掉」，属安全口径不属手感。规格没写死这三条之前，实现不要先接。
    - **`toNumChapter`**（中文数字章号归一）：属求值层，落点在章名比较那一侧而不是沙箱，与 §7.3、`ChapterPageMatcher` 的口径要一起定，不在本批。
    - **`t2s` / `s2t`**（2026-09-12 实测：`java.t2s` 66 次/23 条记录、`java.s2t` 4 次/4 条，**全部带 `java.` 前缀**、无裸调用，故只需挂 `java` 面；按承载字段拆（`build/survey-field-split.js`）后执行面上是 `t2s` 64 次/12 条去重源、`s2t` 4 次/2 条——这一族的数字基本没被登录面与 `jsLib` 灌水。同一天在 10 份 `corpus-summary-*.tsv` 全文搜不到 `t2s` 字样——detail 里连嵌着的 JS 原文都搜得到，可见它不是任何一条已录失败的第一道坎）：拦路的是**数据**不是接线——需要一张转换表。表放哪儿有三个落点，各自的代价不同：① 打进 native 只读段（随 `.so` 走，多一处要自己审的二进制）；② 由主进程当 COMPUTE 提供（`HostCompute` 里一张 Kotlin 常量表，跨一次 JNI 往返）；③ 随 APK 打包成只读资源（隔离进程读不到的是**本应用数据目录**，APK 自身资源随包 mmap，读得到——所以「`:js` 读不到数据目录」不构成排除这一项的理由，见 ADR-0028）。**实现前还有两件事没从一手来源核实**：上游这两个名字的确切语义（是否就是繁↔简互转、单向还是带方向参数）与用哪张表（简体侧一个字往往对应多个繁体字：`发` ← `發`/`髮`、`干` ← `乾`/`幹`，纯字级映射在繁→简方向必错一半，词级表才是上游那种做法）。核实之前不登记，按本条结尾「不登记即不存在」处置。
    - **`getWebViewUA`**：本仓无 WebView 桥（§10 `webView` 行 ❌），同因不做；脚本要 UA 只能在 `headers` 里自己写。
    - **`createAsymmetricCrypto` / RSA 一族**（2026-09-12 实测，`build/survey-rsa-family.js` + `survey-rsa-ctx.js`）：**4 次计数、按 `bookSourceUrl` 去重只剩 2 条源**（两池高度重叠，同一条源被数了两遍），且这 2 条里有一条的调用点写在 **`jsLib` 字段内**——本仓不装载 `jsLib`（本条上面那项），故它拦的是那条源而不是这一族；真正落在解析链路上的只有 1 条源的一处正文解密。**量小到不值得单独排期**，但形状已被实证与 §5.4 的对称门面**完全同形**：现场写法是 `java.createAsymmetricCrypto("RSA").setPublicKey(key).decryptStr(data)`，方法名 `setPublicKey`/`setPrivateKey`/`decryptStr` 与对称门面上已有的那几个一字不差。要做就照那个形状（一次 `asymmetricCrypto` 转发 + 脚本侧对象面 + 字节令牌），别在表里登记门面名；`RSA/ECB/PKCS1Padding` 这类变换串参数要按上游语义受理，不要静默忽略。

    这一族的共同处置原则是**「不登记即不存在」**：宁缺不假——脚本拿到 `ReferenceError` 或一句 `__UNSUPPORTED__:` 是可诊断的，拿到一个恒空/恒 0 的出口则是把「这条源要的能力本仓没有」伪装成「这个站点没有这个字段」，后者会把用户支去重导一条本来好的源（同 §12 最后一条）。

37. **cookie 会话落地的两处刻意边界**（2026-09-12 随 cookie 面登记，`SourceCookieJar` 类 KDoc 指本条）：三个方法接上了一源一份的 `SourceCookieJar`（§5.4 口径 5），下面两处是这一片有意**不越过**的线，不是漏做：
    - **罐不落盘**。它跨的是**同一条源的多次解析任务**（搜索→详情→目录→正文一路存活，罐挂在 `ScriptBookParser` 上，随 `BookSourceManagerImpl` 的 LRU 逐出而整份丢掉），不跨**应用重启**。症状预告：「登录一次、此后每次冷启动都直接读会话」的源在冷启动后读到空串，脚本于是走它自己的未登录分支——多数写法会重新拿会话（代价是多一次外呼），少数写法（把会话当常量拼进签名）会永远解不出目录。§11-36 原文那句「生命周期要比 `EvalContext.variables` 长，因此是另一层持久件」是当时的设想，现按本节口径收窄：**先给跨任务，持久件另拍**。真要落盘，落之前得先定三件事——① 存的是**第三方站点的凭证**，明文还是加密（本仓对用户 token 的口径是 access token 只驻内存）；② 谁有权清（`module_me` 的缓存管理页现在的口径是「只清 `cacheDir`、不删用户数据」，会话既不是 cacheDir 也不是书，得新给一档）；③ 唯一键用什么（`bookSourceUrl` 用户可改，改了以后旧罐成孤儿，还会把旧会话发给改了地址的那条源）。这三条都是要拍板的决定，不属接线工作，故不在 cookie 面这一批里顺手做。
    - **声明式取文链不带罐**。规则里没写 `@js:` 的那一路（`ScriptPageFetcher` 经取文层发请求，生产客户端是 `@Named("source")` 纯净件）不挂 `SourceCookieJar`，因此「站点 `Set-Cookie` 回种、后续纯声明式页面靠这个会话」的源仍解不开——那是上游 `enabledCookieJar`（§1.1 该行现标 ⏸）的语义。语料里 cookie 的用法**全部**出现在 `@js:` 段内，故这一片的受影响源数为 unknown；补它要先回答「罐挂在解析任务上还是源上、跨源时怎么隔离」，而那两个答案在声明式链路上比在沙箱里更难给（取文层没有源实例可挂）。
    - 附带一条待装机确认：`cookie.getCookie` 的回读**不区分 `HttpOnly`**（罐不是浏览器：`Set-Cookie` 里标了 HttpOnly 的会话，脚本读得到），`Secure` 则由 `Cookie.matches` 天然挡在 https 之内、本类不另判。前者与上游 WebView 的口径不同，是真站上才会暴露的差异。

## 12. 不重新裁决的事（本仓既有事实源）

以下判断已在 `lib_book_source` 里落地并有测试锁定，脚本解释器**照用、不另立口径**：

- **列表分页「到底」判据**：越界页会以 HTTP 200 重复返回首页书目（软 404）→ 追加页按 `noteUrl` 去重、无新条目即 `hasMore=false`；列表页以 `noteUrl` 作 item key（`BookPageMergeTest`、`ListPageUrlTest`）。脚本路径同样适用：搜索/发现的每一页解出的条目并入时走同一去重与终止判据。
- **章节索引的终止与防御上限**：`contentRef` 跨页去重、零新增即到底、回环即停、`MAX_TOC_CHAPTERS` 触顶按截断记日志不抛（`TocPager`）。脚本路径用 §7.1 的 `nextTocUrl` 驱动，但**终止判据与上限策略复用这一套**。
- **相对地址落位**：`TocPageUrl.join` 的三形态语义（绝对原样 / `/` 相对源根 / 其余相对当前页目录）。
- **正文分页的「宁漏页不串章」取舍**：属原生链路的推断式判定（§7.3 已说明脚本链路为何不同，但**不推翻**原生链路的既有口径）。
- **解析器不碰缓存**：书库/分页缓存策略住在仓库层（`module_find` 的 `BookSourceRepository` + `LibraryDiskCache`），脚本解释器同样只做网络解析。
- **多书源归属**：每本书按 `tag`（= 书源 URL）绑源、经 `bookSourceManager.getParserFor(url)` 取解析器；不存在全局默认源去解别人的书。
- **失败要如实报**：`getParserFor` 对脚本行**永不返回 null、也不在构造期抛**——「行在但 JSON 坏」以首次求值时的类型化 `ScriptRuleParseException` 如实报（规则装载是惰性的，见 `ScriptBookParser`），null 恒等于「书源不存在/坏行」，两者**不得混用**（混了等于把「这条规则解不动」说成「这条源已失效」，用户会被支去重导一条本来好的源）。规则用了本规格未覆盖的语法同样必须给出类型化、可区分的失败，不得返回空列表冒充「这个源没有结果」。
- **`bookSourceType` 的取值口径以本文 §1.1 为准**：`0` 文本、`1` 音频、`2` 图片（漫画）、`3` 文件/下载站、`4` 视频（音频站点取值为 1）。`ScriptSourceRule.kt` 的 KDoc 曾写作「0=文本，1=图片(漫画)，2=音频」，与公开文档及语料**顺序不符**；导入警示逻辑本身用的是 `!= 0`，行为从未受影响，注释已于 2026-09-08（Plan 2a Task 8）就地校正为上述取值。
