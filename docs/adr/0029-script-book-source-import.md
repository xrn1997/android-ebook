# 脚本书源：双格式共存、原始入库与自研解释器

社区广泛流传的开源阅读器书源 JSON 格式（本仓定名「脚本书源格式」）是一个庞大的现成书源生态。2026-09-08 的 grill 设计会话裁决：接受它作为本项目**第二种书源输入格式**——原始 JSON 入库零翻译、解释器自研（清洁室，不复制其 GPL 代码）、规则内嵌脚本一律进零权限隔离进程执行、全仓不出现该生态项目名。

## 背景

- 本仓原有格式（`BookSourceRule`，本决策称「原生规则书源」）是纯声明式：URL 模板 + CSS 选择器 + 正则清洗，表达能力止于「选取」。
- 实测语料（642 条、4.8MB，覆盖 639 个独立站点）：**62% 的源规则内嵌可执行 JS**，且常是整段内容提取与反爬逻辑而非胶水；JSONPath 模式 135 条、POST 搜索 233 条、目录/正文翻页链 180/236 条、gbk 系字符集 68 条、依赖登录的 110 条、漫画/音频类 88 条。声明式翻译覆盖不了这个生态。
- 该格式的参考引擎以 GPL-3.0 发布且上游代码已下架——**代码不可搬进本仓**（许可证传染）；但格式语法与行为规格可以重实现。
- 若不支持，本项目与该生态的兼容方式只剩「用户手工把每条源改写成原生格式」，红利归零。

## 决策

1. **双格式共存，`format` 判别列路由**：`book_source` 表新增 `format` 列（`native` / `script`）。原生格式走既有 `BookSourceRule` 求值；脚本书源**原样存原始 JSON**，不翻译、不归一化——零信息损失，解释器升级后重导即生效，格式本身的升级（如翻页语义）自动跟进。书架/搜索/换源按统一解析器接口消费两种出身。
   - 该列随 v5→v6 迁移加入，实体上声明 `defaultValue = "native"`，存量行由列默认值兜住、迁移不写 backfill UPDATE。列值是小写 `native`/`script`，读写两侧一律取枚举自带的 `raw` 而不是 Kotlin 枚举的 `name`：大小写漂移不报错也不闪退，只会让脚本行静默按原生规则去解——拉回的是不相干的内容，比崩溃难查得多。
   - **失败口径按「用户能不能处置」分两支，禁止合并**：规则装载惰性到首次求值，坏 JSON 在那里抛类型化 `ScriptRuleParseException`；`getParserFor` 对脚本行**永不返回 null**，而 null 恒等于「书源不存在/坏行」。合并成「该源已失效」会把用户支去重导一条本来好的源；改成构造期抛则会在 parser 缓存锁内炸出未预期异常。
   - **读面两副口径是刻意分工**：规则类型化读面（`getAllSources`/`getEnabledSources`/`getSourceByUrl`）经 `toRule` 一律挡下非原生行，而 `observeSources()` 必须继续带出脚本行——管理页要看得见、能禁用、能删。两侧口径本就不同，别去「统一」。
   - **默认源的载体是格式中立的密封 `SourceDefinition`**（`Native` 带规则、`Script` 带原始 JSON 加实体列上的展示信息），同步快照与订阅面两种出身都能承载；回落与回填收敛到 `defaultFromRows` 一处（条目面、不筛格式，SP 命中优先、否则首条启用源）。`SourceDefinition.Script` 的 name/url 生产填充点只有 `toDefinition`，绝不去解 `rule_json` 取展示信息。书城顶部切换器的候选同样不带格式过滤（= 启用行，脚本行进得来、能被立为当前源）；分类入口经格式中立的 `getExploreEntries`：原生走 kinds、脚本走 `exploreUrl` 条目（url 承载规则串，`ScriptExplore` 是脚本发现条目的唯一公开门面）。**保留语义**（非漏改）：脚本出身的默认源在冷启动首帧仍按 assets 猜，由 Room 回填纠正。
   - 含 JS 的规则段（`@js:`/`<js>` 段、`{{}}` 表达式、URL 选项 `js`/`bodyJs`、JS 形态的 `init`）经 `EvalContext` 上的桥接面进求值链；**没装配执行器的路径**照实抛「需脚本沙箱执行器」那句类型化异常，不与「执行失败」合并成同一句话。
2. **解释器清洁室自研**：按该格式的公开语法文档与行为规格重写求值器，不复制 GPL 代码。v1 实现三种规则模式——**链式选择器（HTML）、正则、JSONPath**；**XPath 与 webJs（WebView 内执行）明确延后**：语料中仅 3/642 条源受影响，二者按装载期能力清单登记为不支持，不静默产出坏源。
   - **链式段的模式判读按 CSS 收编**：不以 `class.`/`tag.`/`id.` 前缀开头的段一律按 Jsoup CSS 选择器解，`@css:` 只是显式写法而非唯一入口。成稿时那句「CSS 只能经 `@css:` 模式」是范围取舍，真语料把它推翻了——只认前缀方言时裸 CSS 段被静默当成 `@任意属性名` 取值器解，产物是**空结果且零报错**，而语料里 283 条源的 `bookList` 正是裸 CSS。
   - **裸词末段的歧义由字段口径消解**：同一个裸词既可能是列表字段的标签选择器、也可能是单值字段的属性名，规则串本身区分不了，只能由调用方传入口径——列表字段（`bookList`/`chapterList`/explore）走 `ChainTail.SELECTOR` 出节点集，单值字段走 `ChainTail.AUTO` 按属性名取值器。
   - **首个 `@` 前的空段按恒等解**（当前节点），不是 `children`：`chapterUrl: "@href"` 这类整条取值器形态（语料 19 处）此前把空段当 `children` 解，取值器落到叶子元素的子元素上即空集、同样零报错。
   - 语法细则的事实源是规则文法规格，本决策只记「模式判读按 CSS 收编」这一层取舍；两处属本仓规定而非上游实证的口径（组合符次序、索引闭区间与越界不抛）改一处必须规格、实现、单测三处同步。
3. **脚本一律进零权限隔离进程**：规则内嵌的 JS 不在主进程求值——执行器为独立 Android 进程（`isolatedProcess` 零权限、SELinux 受限域），引擎 QuickJS 源码自集成、桥接层自有，Host API 白名单 deny-by-default（计算类在执行器进程内就地应答、网络经主进程受限代理、递归求值走回调通道），wall-clock 超时 + 堆/栈上限 + 崩溃自动重启。信任边界上不向主进程引入任何陌生代码。
4. **宿主 API 白名单按语料频谱定版**（642 条实测）：计算类（md5/sha 族、base64 四个变体、hex、对称加解密、时间戳与格式化）由**执行器进程内的 Kotlin/JDK 承担**（`HostCompute`），不落原生注册——原话是「原生 C++ 注册」，改主意的理由：`MessageDigest`/`java.util.Base64`/`javax.crypto` 已在设备上，重写一份 C++ 只会多一处要自己审的密码学实现。代价是这些 API 走一次 JNI 往返进 Kotlin。两条口径钉在该文件里：输入一律 UTF-8 字节、摘要一律小写十六进制；失败一律抛类型化拒绝而不是回 null（null 在垫片里的形态是「调用成功、值为 null」，脚本会拿它拼出 `url=null` 继续请求），且入参形态不对时也必须类型化——未类型化的异常会在 `.so` 的调用栈里撞掉整个 `:js` 进程，而那里还在跑别的书。分组密码的密钥/IV 派生属**本仓规定**（上游语义未核实）：无随机 IV 是刻意的，`aesEncode(x, k)` 的结果要被当 URL 参数复用，必须可复现。
   网络类（`ajax`/`load`/`post`/`responseCode`，语料使用频次第一）全部经主进程的受限代理，scheme、内嵌凭证、host 白名单、私网与保留地址段（含 DNS 重绑时的第二次解析）、URL 长度上限、单次回调的响应字节上限，以及每任务外呼次数配额都在主进程那一侧判。`ajaxAll`（批量外呼）与 `connect`（跟随重定向取真实地址）与浏览器自动化/登录/验证码/`cookieManager`/持久层一族同列**明确不支持**，在垫片里各留一个可诊断的拒绝桩（一调即报「属本项目不支持的能力」，不是撞进 `ReferenceError`）。toast 类映射成日志、不弹窗。对应 110 条登录源部分功能受限，导入时明示。
5. **模块归属 `lib_book_source`**（新建）：原生解析器一族自 `lib_book_common` 迁入，连同脚本书源解释器、解析器接口本身（`BookParser`、`ScriptContentParser`）、`AggregateSearchEvent`、`:js` 沙箱 Service 与 `cpp/` 执行器。**接口不下沉到 `lib_ebook_api`**：跨模块共享的只有两种格式的数据模型（`SourceFormat`、`SourceDefinition`、`ScriptSourceRule`），解析器签名要带 db 实体，下沉等于让 api 层依赖数据库模块。`lib_book_common` 保留 Manager、持久化与聚合搜索编排，并额外承担**沙箱的 Hilt 装配**（`di/SandboxModule.kt`）——`lib_book_source` 没有 Hilt/KSP 依赖，装配点只能在 `lib_book_common`，为此 `JsSandboxHost`/`HostCallbackRouter` 从 internal 上浮 public。转子必须先建、再显式传进客户端，两边不是同一个对象时每条回调都报「主进程没有正在进行的脚本任务」。
   - 唯一的例外是 `JsoupSourceReader`：它依赖 `BookStore`/`ChapterReader`/`BookSourceManager`，搬去 `lib_book_source` 就是一个 Gradle 环，故留在 `lib_book_common`，按 `BookLocation.sourceUrl` 向 Manager 取 parser 再委托。
   - build-logic 新增 NDK 约定插件 `xrn1997.android.native`：`ndkVersion` 钉死并落在版本目录（AGP 默认值随小版本漂移，会让同一份 C++ 在不同机器上编出不同 `.so`），ABI 集合 release 只 `arm64-v8a`。**debug 的 `x86_64` 刻意不在插件里放宽**，而由各模块在自己的 `buildTypes["debug"]` 里加——「哪个模块多带了一份内核」必须在 diff 里当场可见。ABI 口径是安全语义而不只是构建细节：多余 ABI 等于多份可被研究的内核副本。
   - 应用侧只承担**进程门禁**：`BookApplication.onCreate` 与 `MyApplication.onCreate` 一律先判 `SandboxProcess.isInIsolatedProcess` 提前返回，`:js` 里不跑主题装配与 `ThemeModeManager` 的 SharedPreferences 读取（隔离进程读不到本应用的数据目录，留它就是纯粹的 IO 失败）。反过来主进程这一侧绝不能被误判跳过。原话里的「`module_app` 负责后端接线」不成立，接线在 `lib_book_common` 的 module 里。
6. **导入链路复用与增强**：现有文件选择器 + 逐条预览骨架直接承接；按 JSON 形态（键名特征）自动判别格式；逐源校验报告的明示项为「依赖登录」（读最小模型的 `loginUrl`）、「漫画/音频类」（`bookSourceType` 非 0，本项目是文字阅读器）与「含可执行代码」（扫 `rawJson` 里的 `<js>`/`@js:`）三项，都不经词法层。**三项一律不拦导入**——它们说的不是「这条源坏了」，而是「本项目只能给它一部分能力」，用户手上的源是漫画源时他自己知道要什么。**「不支持规则类型」一项的数据源是 `ScriptRuleSet.unsupported`**（装载期登记的能力缺失集合），接入时必须从这份数据接，不得在预览层凭字符串扫描另判一次、也不得在解析器里为 UI 另判一次。
7. **命名红线**：全仓（代码、类名、注释、文档、测试资产、fixture 文件名）不出现该生态项目名；术语定名「脚本书源（Script-based BookSource）」与「原生规则书源」。
8. **测试锚点**：4.8MB 语料留仓外，入仓的金标准是**真站点真响应快照**——`lib_book_source/src/test/resources/scripted_real/` 下四条声明式源（无极书院 `wuji`、手机看书 `sjks`、阅读书屋 `vikbook`、网阅小说 `book15`），各含 `rule.json` 与搜索/详情/正文的原始字节响应；**独立目录快照只有 `vikbook` 一份**，另外三条源的目录就印在详情页上（规则未配 `tocUrl`，`getChapterList` 复用详情响应），没有可另冻的东西。`curl` 从活站抓一次即冻结，测试全程离线（假 transport 直接回冻结文本），站点死活不影响断言——时效性由「抓一次冻起来」消解。这批锁的是「上游真实规则在真实 HTML 上解得对不对」（实体编码、广告残留、选择器作用域里的诱饵、真实翻页），清洁室自研的语义偏差只有真站点真页照得出来。合成源（`ScriptFixtureSources`：HTML 链式、JSONPath API、`<>` 分页三条）退为**编排行为**的补充覆盖——URL 渲染 → 取文 → 字段提取 → 目录链 → 正文链 → 净化，一环断了这里就红。**含 JS 的重源不设永久夹具**：实测站会随域名过期与站点改版失效，冻下来的载荷若干月后只会是一条永远红、没人能修的用例，而可复现的那一半（协议、判据、限值与垫片语义）已在 JVM 与设备侧用例里锁住——这是有意的覆盖缺口，取证据的做法见测试台账。沙箱的恶意脚本面按**限值**覆盖：设备侧 `QuickJsBridgeTest` 有死循环在挂钟期限内被打断（且超时后下一个任务仍可用）、深递归以栈耗尽结束而不是撞掉进程、堆超限按内存失败、绕过垫片直呼白名单外能力被就地拒绝、返回 Promise 按不支持处置、任务边界清掉上一个任务留下的全局量；地址守门（协议、内嵌凭证、私网与保留段、host 归一化、DNS 重绑）在 JVM 侧的 `JsNetworkGuardTest`。**红线**：fixture 的文件名与内容不出现生态项目名，抽取时逐条核。

## 权衡

- **翻译式转换器 vs 原始入库 + 自研解释器**：转换器看似轻，但要做对仍需完整解析该格式 DSL（最难的活一样不少），还要叠加语义漂移与表达力缺口（索引语法、变量系统、URL 选项语义都翻译不动）；原始入库零损失，解释器按生态语义直接求值，兼容率上限高得多。代价：本仓长期维护第二套求值引擎。
- **清洁室 vs 移植 GPL 引擎代码**：移植到能跑最快，但整仓许可证被传染；清洁室按规格重写，存在语义偏差风险——用真实源 fixtures 锁行为，偏差按 bug 修。
- **延后 XPath/webJs**：3/642 的影响面，换掉两块重资产（XPath 求值器引入、WebView 无头桥接）；「诚实失败」兜底，真实需求出现再补。
- **原始 JSON 入库 vs 归一化建模**：入库形态即生态形态，重导/升级零成本；代价是两种求值路径长期共存（这正是 `format` 判别列存在的理由）。

## 被拒方案

- **仅做转换器、不引入 JS 执行**：实测语料 62% 含 JS，覆盖率上限 38%，吃不到红利——与本次目标（生态兼容）直接冲突。
- **移植生态 GPL 引擎**：最快可用，但 GPL-3.0 传染整仓；且其上游已因法律压力下架代码，该领域本身法律敏感，本仓不引入其代码。
- **脚本在主进程内执行（经白名单绑定约束）**：防护是约定式的，引擎漏洞可直达业务进程——已由隔离进程决策取代。

## 下游影响

- 新模块 `lib_book_source`：原生解析器一族自 `lib_book_common` 迁出，`BookSourceManager` 升级为双后端分发；解析器接口（`BookParser`/`ScriptContentParser`）与 `AggregateSearchEvent` 也住在这里。
- `lib_ebook_db`：`BookSourceEntity` 加 `format` 列，version+1 迁移链（不跳版、提交新 schema JSON）。
- `lib_ebook_api`：只加脚本书源格式的最小序列化模型（`ScriptSourceRule`，只覆盖导入校验与落库所需的顶层键，`ignoreUnknownKeys` 让未声明的规则段原样留在原始 JSON 里）与格式枚举/默认源载体（`SourceFormat`、`SourceDefinition`）；解析器接口**不在**此模块。
- `module_me`：导入预览的格式判别、依赖登录/漫画音频/含可执行代码三项警示。
- `third_party/quickjs/`：vendored 上游原文（5 个 `.c` 编译单元 + 10 个配套头文件 + `LICENSE`/`VERSION`；CMake 只把那 5 个 `.c` 编进 `libquickjs`，桥接层另成 `libebook_js`），`PIN.sha256` 逐文件钉摘要，校验靠人工 `sha256sum -c`（本仓无 CI、非构建期强制）；根 `.gitattributes` 对 `third_party/quickjs/**` 关掉行尾归一化——Windows 上 `core.autocrlf` 会把 LF 转成 CRLF，内容一字没错却逐字节不等，那条属性是这份摘要清单成立的前提。刻意排除 `quickjs-libc.c`（POSIX/`os.*`/worker 层，沙箱的前提就是脚本看不到这些）与已不存在的 `libbf.c`。
- `CONTEXT.md`：新增「脚本书源」「原生规则书源」术语。
- `:js` Service 声明（含 `isolatedProcess`）落在 `lib_book_source` 唯一那份库清单里，由清单合并进 App——本模块没有功能模块那种 main/module 双清单，不存在两处同步的问题。

## 遗留

- XPath 与 webJs 支持（触发条件：语料中真实需求出现）。
- 登录与浏览器自动化族能力、简繁转换（t2s）、源级脚本库（jsLib，实现成本低但排期视验证）。
- 网络 URL 白名单宽松度按失败率调校；CPU 时间计量。
- **脚本源的导出不存在**：把库里的原始 JSON 写回该生态格式（与导入对称）没有实现，与执行器无关。现只有原生规则书源能导出。
- **发现页 `exploreUrl` 的第三形态（`<js>` 程序）不消费**：该形态由发现页脚本
  现场算出 `[{title,url,style,type,chars,default,action}]`，其中 `url` 常是 `buildUrl(tagId)` 这类
  调用结果、`tagId` 来自**控件当前值**——缺发现页 UI 宿主（`infoMap` / `java.refreshExplore` /
  `style.layout_*` 一族）就拿不到有意义的地址。现口径：词法层识别并登记
  `ScriptUnsupported.EXPLORE_URL_SCRIPT`，切分层对整串返回空清单（后果是「少整份发现入口」，
  搜索/详情/目录/正文不受影响）。**此前它掉进文本切分**，把 JS 源码逐行当 URL 发出去——
  `番茄（发现）` 事故（105 条空标题条目 + 105 次 404 + 书城列表 key 全空而崩溃）即由此而来。
  要真正支持需整块引入发现页交互控件能力，触发条件：真实需求出现。
- **「不支持规则类型」的明示项没有消费者**：`ScriptRuleSet.unsupported`（逐项清单以 `ScriptUnsupported`
  枚举为准，本文不复述条数）在装载期就算好了，
  导入预览却只看那三项顶层键警示，于是「一条源被判定为不支持某项能力」这件事在导入那一刻用户看不到。
  补齐需要 `lib_book_source` 新增一个公开只读面（`ScriptUnsupported` 现在是 `internal`，
  跨模块契约的形状 `ScriptExplore` 是现成例子）并在预览层做映射。
- 上游源规则自身的写法问题（**非解析器缺陷**）：网阅小说（`book15.net`）的
  `nextContentUrl: "#after_link@href"` 指向的是**下一章**链接而非章内分页，解析器忠实执行 → 该源
  的正文链会一路拼接后续章节直到 `ScriptContentPager` 的页数上限。金标准 fixture 只冻结正文第 1、2 页
  的响应，第 3 页的请求照发但无载荷（页上没有 `#after_link`，链自然停）——请求序列被锁住以证明
  「链确实在跟」，未冻结的那一页不会被误当成解析缺陷；真机阅读该书会表现为「一章里串了多章」，属源作者写法问题。
- **字段抽取把整条规则管线按条目重跑一遍**：`ScriptFieldExtractor.fieldText` 对每个条目各调一次
  `ScriptRuleEvaluator.evaluate`，而 `evaluate` 每次开头都重做 `Interpolation.expand` + `RuleSplitter.parse`
  ——一条 200 条目的目录就把同一串规则切分解析 200 次。触发面有限（组引用 `$2` 与 CSS 选择器不走正则编译，
  最贵的那一步并不每条目都付）。**无 profile 数据即改热路径属无证据改动**，故只登记不动手；
  解锁前提是真实大目录源上量出可测开销，届时按「规则串 → 已解析 AST」加一层缓存。
- **括号深度感知的扫描循环写了 5 份**：`RuleScanner.depths`、`ScriptUrlOption.optionTailCut`、
  `Interpolation` 的配平扫描，以及 `ExploreUrlFormat` 内部两处同形的循环。
  未合并的原因是**它们语义并不相同**：`optionTailCut` 独带「逗号紧前 + 规则层深度 0 + 到串尾配平」三条约束，
  其余几份各只管自己那一对括号。合并前须先逐份证明可统一，否则是把几份各自正确的代码换成一份不够精确的。
