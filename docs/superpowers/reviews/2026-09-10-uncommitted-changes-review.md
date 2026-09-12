# 未提交改动评审 — 两轴报告

- **固定点**：`HEAD`（`cbd51719`）。改动全部位于暂存区，`HEAD` 之后无提交，故 diff 命令为 `git diff -M HEAD`。
  > **读法说明（2026-09-10 追加）**：上面那句只对报告写作那一刻成立——这批堆在工作区的改动随后
  > 拆成了 `cbd51719` 之后的三笔提交：`d119fe81`（vendored QuickJS 与 NDK 约定插件）、
  > `2d4894fd`（ADR/plan/spec/CONTEXT）、`85af3409`（功能落地）。今天照 `git diff -M HEAD` 取到的是**空 diff**。
  > 要复现本报告的范围，固定点应写 `git diff -M cbd51719..85af3409`——实测该区间为 440 文件 /
  > +156,171 / −3,744，比 §1 表格的 439 份恰好多出**本报告自身**一份（它在 `2d4894fd` 才入库）。
  > 本文是**追加式**评审记录：§9 之后未再复核，其结论不随后续改动自动更新。
- **报告日期**：2026-09-10
- **评审方式**：4 个切片 × 2 轴（Standards / Spec）共 8 个并行子代理，切片内两轴互不污染；两轴结论分别呈现、不做跨轴合并与重排。

## 1. 范围与切分

| 口径 | 文件数 | 增 / 删 |
| --- | --- | --- |
| 全部未提交改动 | 439 | +155,819 / −3,744 |
| 其中 `third_party/quickjs`（vendored 第三方 C） | 19 | +77,347 / — |
| 排除第三方后 | 420 | +78,472 / −3,744 |
| 其中 Kotlin 主源码 | 190 | +18,225 / −1,969 |
| Kotlin 测试 | 97 | +18,903 / −221 |
| 文档 / plan / spec | 51 | +28,882 / −953 |
| 构建脚本 | 31 | +429 / −449 |

**这不是一次提交，而是 12 份 plan + 13 篇新 ADR 的工作量堆在工作区**（ADR 0023–0032 新增，0001–0022 修订）。因此按 Kotlin 主源码切 4 片并行：

| 切片 | 范围 | 规模 |
| --- | --- | --- |
| A 沙箱执行器 | `lib_book_source/.../sandbox`、`src/main/cpp`、`src/androidTest`、模块构建脚本 | 26 文件 |
| B 脚本书源解析层 | `lib_book_source/.../script`、`.../analyze` | 121 文件 / +22,203 |
| C 数据与领域层 | `lib_book_common/src/main`、`lib_ebook_api/src/main`、`lib_ebook_db/src/main` + `schemas` | 131 文件 / +15,130 |
| D 功能模块 UI/VM + 构建配置 | `module_*`、`build-logic`、`gradle`、全部 proguard/consumer-rules | ~70 文件 |

**未做独立轴评审**（列为残差，不视为通过）：97 个 Kotlin 测试文件、HTML/JSON 测试夹具、`third_party/quickjs` 本体、`.md` 文档正文。

## 2. Standards（代码是否符合仓库已文档化的标准）

标准来源：`AGENTS.md`（368 行，主源）、`docs/adr/` 相关篇章、`CONTEXT.md`、`docs/adr/ADR-FORMAT.md`，外加 Fowler 气味基线（仓库标准优先于基线，基线项一律为判断项）。

### 2.1 硬违规（文档化标准被违反）

仅 1 条：

- **`lib_book_source/.../sandbox/JsProtocol.kt`** — `data class DecodedRequest`（`:126`）无 KDoc，四个私有帧 DTO `RequestFrame`/`OutcomeFrame`/`HostCallFrame`/`HostReplyFrame`（`:84,:92,:210,:213`）各自也无。`AGENTS.md:112` 要求「每个类…必须有足够的 KDoc/注释」。外围 `JsProtocol` 有类级 KDoc 覆盖协议整体，故为轻微违规。
  - **补注（复核后）**：四个帧 DTO 均为 `private`，字段名自述（`mode`/`source`/`bindings`/`deadlineMonoMs`…），线格式语义在 `SandboxContract` 统一交代。这是「标准要求 per-class KDoc」与「私有线格式 DTO 的语义已在协议层叙述」之间的**边界判断**，不是无争议的硬违规——需 owner 定调，不宜默认按违规处理。

**A/B/C/D 四片的其余标准项全部为清洁**，逐项举证（摘要）：无 `android.util.Log`（统一 `Logger`）；无硬编码依赖版本；无 RxJava3；`isModule=false` 已提交态；未应用 `org.jetbrains.kotlin.android`；`consumer-rules.pro` 遵循 ADR-0024 且 `-keep` 均有证据；`Repository/FailureReport` 单一接缝（`isSessionExpiredHandled` 仅存于 `FailureReport.kt:44`，无调用方手写）；Room 版本 2→6 迁移链连续（`MIGRATION_2_3`…`_5_6`）且 `schemas/3..6.json` 齐备、无 `fallbackToDestructiveMigration`；`BookSourceManagerImpl` 的 `toRule`（读面滤非原生）/`toItem`（`observeSources` 带出脚本行）两侧口径**未被统一**；`format` 列一律 `.raw`；导入判重键为 `comment_key`、新条目先于旧条目处置、`absorbGroupKeys` 先于删除、补章严格前缀 + `max(durChapterIndex)+1`；`LibraryDiskCache` key/文件名收口；`BookStore.storageUsage()` 单列不并入可清理量；新 Activity 均继承 `BaseMvvmActivity`、新 VM 均 `BaseViewModel<NoOpModel>` 且避开 `uiState`；**两份 Manifest 的 Activity 声明与属性（`theme`/`label`/`exported`）已同步**；`module_book` 经 `lib_book_common` 的 `api(project(":lib_book_source"))` 传递依赖，方向无环；已删符号（`ACache`/`BookContentDao`/`BookContentEntity`/`LibraryNewBookEntity`/`ReadBookContentEntity`/`BookImportManager`/`ViewModelErrors`）全仓无悬挂引用。

### 2.2 判断项（气味基线，非硬违规）

按切片，权衡后仅列值得动手的：

| # | 位置 | 气味 | 说明 |
| --- | --- | --- | --- |
| S1 | `ScriptFieldExtractor.fieldText:49-56` → `ScriptRuleEvaluator.evaluate` → `RegexBackend.evaluate:36` | 重复构造（**已复核并改述**） | 实际成本比原述更宽：`fieldText` 对**每个条目**调一次 `evaluate`，而 `evaluate` 每轮都重跑 `Interpolation.expand` + `RuleSplitter.parse` + `Regex(...)`——**是整条规则管线的逐条目重解析**，不只是正则重编译。触发条件有限：目录字段的常见形态是组引用 `$2`（`evaluateOnItem:56` 直接短路）或 CSS 选择器，只有把正则写成字段规则的源才会踩到。详见 §7 更正记录 |
| S2 | `Interpolation.topLevelEntries`、`JsonPathBackend.splitTopLevel`、`ExploreUrlFormat.splitTopLevel`+`topLevelIndexOf`、`ScriptUrlOption.optionTailCut` | Duplicated Code | 同一套「引号 + 花括号深度感知切分」手写了 4 遍，可提一个共享切分器 |
| S3 | `module_app/proguard-rules.pro` + 5 个功能模块 `consumer-rules.pro` | Duplicated Code | 同一段 24 行 TheRouter 规则复制 6 份，集成态 R8 收到约 6 次。R8 会去重，无功能风险，但应下沉到一处 |
| ~~S4~~ | ~~`lib_book_source/build.gradle.kts:13` `api(project(":lib_ebook_db"))`~~ | **已撤回（原判有误）** | 原判为「过度暴露，应改 `implementation`」。实测反证：`BookParser.kt`、`JsoupBookParser.kt`、`AggregateSearchEvent.kt` 均 import `com.ebook.db.entity.*`（`SearchBookEntity`/`BookShelfEntity`/`LibraryEntity`/`WebChapterEntity`/`BookInfoEntity`/`ChapterListEntity` 等），且 `BookParser`/`JsoupBookParser` 的公开签名以这些类型为参数或返回类型。`api` 是**必需**而非过度暴露，改动会破坏消费方编译。详见 §7 更正记录 |
| S5 | `LocalImportCoordinator.duplicateGate:119` | Mysterious Name | `AtomicReference<CompletableDeferred<…>>` 同时兼任暂停闸门与「只解一次」幂等守卫，注释已交代，命名未体现 |
| S6 | `SandboxService` / `BinderJsChannel` / `HostCallbackBinder` 共 6 处 | Duplicated Code | Parcel 帧序（token→version→frame）手写重复。手写 Binder 协议下可接受 |

另有两项经复核**不成立**，不再列为气味：`AddBookFailure.reportAddBookFailure`（`AddBookFailure.kt:24`）是把重复分支抽出的**正确**去重；`BookSourceManageActivity` 用 `ToastUtil` 而非 `MvvmBinder.sendToast`，代码内已注明是为让通知在 `BookSourceViewModelTest` 中可断言，属有意选择。

## 3. Spec（代码是否忠实实现了原始 plan / spec）

### 3.1 缺失或部分实现

四个切片的**主体工作均已落到代码**，无「整块缺失」。全部 partial 项如下：

- **沙箱（切片 A）**：plan §Task 8/11 要求设备实证「断连自动重启」，4 个 `SandboxConnectionTest` **不杀/不重启 `:js` 进程**，自动重启路径（`JsSandboxClient.afterDisconnect`）只在 JVM 测试覆盖 → 该验收路径为**脚手架级，未被本切片的插桩测试覆盖**。
- **脚本书源（切片 B）**：七个 plan 逐一核对均为「✅ 完整」（foundation / rule-lexer / evaluator / url-jsonpath / book-parser / chain-mode-bare-css / bookstore-wiring 的解析侧）。自记的两处 partial 属**有意延后**：POST body 不做 charset 编码（`ScriptRealSourceGoldenTest.kt:128-130`，对齐 §11-20）、`{{key}}` 表单编码注意事项。
- **数据层（切片 C）**：三份 spec/plan 的 M1a/M1b/M2 交付物**无实质缺失**（`LocalBookImporter` 单事务、`BookStore`、`ChapterContentCache`、`Txt/EpubSourceReader`、`EncodingProbe`+`StrictTextReader` 不产 U+FFFD、`CommentKey` 的 `ck1:` 前缀与长度前缀、`pa1:` 段落锚点、`book_group` 多键、`mergeTailChapters` 全部对得上）。
- **模块层（切片 D）**：**2e 书城接线端到端可达**，非部分接线——`LibraryViewModel.sources` 已去掉 format 过滤、`currentSource: StateFlow<SourceDefinition?>`、`bookTypeList` 跟 `source.sourceUrl`、`BookSourceManageActivity.setDefaultSource/setEnabled` 对格式无差别、阅读器换源经 `searchAcross` 纳入脚本候选。

### 3.2 未被要求的行为（越界）

无一处是「unmotivated」的越界。两处需归属确认：

- 切片 B 的 `SandboxScriptJs`/`JsHostApi`/`ScriptHttp`/`ScriptJsBridge` 属 Plan 3 产物落在解析层 diff 中。**自洽**（求值器需要 `ctx.js`），非越界。
- 切片 D 的 `SourceSwitchViewModel`(+318)/`SourceSwitchSheet`(+397) 未被本次列出的 plan 明确要求，疑为 `2026-09-09-script-source-book-parser` 的机械后果（ADR-0029 引用了聚合搜索接入）。**建议确认，而非记为越界**。

### 3.3 看似实现、实则与契约相悖

1. **【真问题】`##$##{…}` 惯用法在规格内部自相矛盾，实现只认带逗号形式。**
   - §6.1（spec `:361`）原文把 `tag.a@href##$##{"webView":true}` 列为**正确**写法（第二个 `##` 后**不带逗号**）；
   - 但 §11-19（spec `:546`）自行承认尾段定位要求「候选 `{` **前有 `,`**」，且给出 JSON 结果的惯例是 `##$##,{…}`（**带逗号**）；
   - 实现 `ScriptUrlOption.optionTailCut`（`ScriptUrlOption.kt:26-49`）**强制要求 `{` 紧前为 `,`**，`RuleSplitterTest.kt:273` 亦按带逗号形式断言；`ScriptRuleEvaluator.kt:240` 注释写的是 `##$##,{...}` 惯用法。
   - 后果：按 §6.1 写源站规则时，替换文本落成裸 `{"webView":true}` 且**不被识别为选项尾段** → URL 被污染成 `…/chapter/1{"webView":true}`，且**静默无错**（正是本仓最忌讳的失败形态）。属规格文本缺陷 + 一处静默失败面。
   - **⚠️ 本条经复核需限定其影响面**：`grep` 实测 **`##$##` 惯用法在内置源 `default_sources.json` 与 4 份真实站点夹具（wuji/sjks/vikbook/book15）中一次都未出现**；测试侧也没有任何端到端断言（`ScriptUrlResolverTest`/`ScriptFieldExtractorTest` 均无 `$##`）。故这是**「文档给出的写法不成立」的写作陷阱**，而非「现网有源正在坏」。据此把代码侧优先级降为 P3、规格文本侧保持 P1。详见 §7 更正记录。
2. **【文档漂移】`getComments` 签名与 spec §4.4 不符。** spec 记 `getComments(commentKeys: List<String>): Result<List<BookComment>>`，实现为 `(commentKeys, page, pageSize): Result<BookCommentPage>`。线上契约（§3.2.1 `RespDTO<CommentPage>`）与实现一致，**应收敛为改 spec 文本**。
3. **【~~健壮性~~ 已降级】栈溢出状态判定依赖字面量匹配。** `JsProtocol.mapStatus:173-174` 仅把含 `"stack overflow"`/`"too deep"` 的消息映射为 `JsStatus.STACK`。原述为「QuickJS 换措辞会静默回退」。**复核后大幅降级**：该文案出自 `third_party/quickjs/quickjs.c:7791` 的 `JS_ThrowInternalError(ctx, "stack overflow")`——即 **vendored 且被 `PIN.sha256` 钉住的依赖字面量**，不会自行漂移；且 `quickjs.c:35722` 的变体 `"stack overflow (op=%d, pc=%d)"` 仍被 `contains` 覆盖。真正的耦合点是「**升级 QuickJS 时必须复核该映射**」。详见 §7 更正记录。

另：`<js>` 标志判定顺序（`RuleMode.flagOf:81`）虽在 `@@`/`@js:` 之后，但 `@js:` 与 `<js>` 结果同为 `JS`，唯一分歧点是 `@@` 与 `<js>` 同现的**不可达/自相矛盾输入** → 复核后**不认定为缺陷**。

### 3.4 验收测试覆盖

- 存在且**确有断言**：`ScriptRealSourceGoldenTest`（wuji/sjks/vikbook/book15 四份真实站点夹具，覆盖搜索/详情/目录/正文链、`&&`、`[2:1]` 反向、`##` 净化、`@html`/`@href`、POST+gb2312）、`LocalBookImporterTest`（断言 `txCount == 1` 的单事务结构性证明）、`CommentKeyTest`、`BookGroupDaoTest`、`EpubSourceReaderTest`、2e 的 `LibraryViewModelTest`/`BookSourceRepositoryNoSourceTest`/`SourceSwitchViewModelTest` 等。
- **唯一被削弱的断言**：sjks 用例**刻意不校验** `{{key}}` body 编码（`ScriptRealSourceGoldenTest.kt:128-130`），与 §11-20 的延后一致，但该行为因此无验证。
- **设备侧验收：已闭环（原判「未闭环」是误读，已更正）**。plan 的**过期段落**（`:7470`）自记「未执行（无设备）」，但同一文件后段 `:7507` 有一条**撤销它的**记录：「2026-09-09 · 设备首跑（撤销第 28 条的「未执行」）」——16 例（`QuickJsBridgeTest` 12 + `SandboxConnectionTest` 4）在 Pixel_8 AVD / Android 17 上全绿，并抓出两个**只有真机才出现**的坑（深递归撞穿 native 栈、堆到限被报成普通运行时失败），两者均已修并各留一条设备锁形用例。
  独立佐证：构建产物 `lib_book_source/build/outputs/androidTest-results/connected/debug/TEST-Pixel_8(AVD) - 17-_lib_book_source-.xml`（`timestamp="2026-09-09T23:30:22"`）记 `tests="16" failures="0" errors="0" **skipped="0"**`，两个 testsuite 分别为 `QuickJsBridgeTest` 12 例、`SandboxConnectionTest` 4 例，`skipped` 同为 0——即 `assumeNative()` 的 `assumeTrue(QuickJsNative.loaded)` **未触发跳过**，原生库确实被加载并跑过。
  故「零失败零跳过」**已由设备实跑证明**，且 plan 正文与构建产物两处互证。残留的只是**读法陷阱**：同一份 plan 里过期段落与撤销段落并存，只读到前者就会得出「未验证」的反向结论（本报告首版即如此）。
  仍需注意：`assumeTrue` 的语义是「环境不具备则跳过」，**未来在缺 `.so` 的构建上跑仍会得到 12 条静默跳过**——这是回归风险而非当前缺口（本仓无 CI，无自动门禁拦它）。

## 4. 独立核验（报告作者亲验，非子代理结论）

| 待核项 | 结论 | 证据 |
| --- | --- | --- |
| 嵌套规则求值是否在宿主进程执行不可信 JS（子代理提出的疑似安全问题） | **误报，设计安全** | `SandboxScriptJs.kt:163-164` 走 `ScriptRuleEvaluator(ctx.withoutJs())`；`JsCallbackProxy.nested:332` 有 `maxCallbackDepth` 上限并明示「阶段一刻意关掉」。不可信 JS 不回流宿主 |
| `##$##{…}` 尾段是否真被静默吞掉 | **成立** | `ScriptUrlOption.optionTailCut:26-49` 仅认 `,{`；spec §11-19 自认该前提，与 §6.1 冲突 |
| 沙箱资源限制是否只是常量未落地 | **清洁** | 墙钟 `DeadlineScope`+`on_interrupt`（`js_bridge.cpp:238,250`）、堆 `JS_SetMemoryLimit`+自定义分配器拒绝（`:130,668`）、栈 `stack_budget`（`:223`）、输出 `gateOutcomeSize`、`maxRequestsPerTask`（`JsCallbackProxy.admit:215`）均实际生效 |
| IPC 边界与拆解是否闭合 | **清洁** | 双向 `enforceInterface` + 版本 + 令牌先验（`SandboxService.kt:108`、`BinderJsChannel.kt:75,104`）；尺寸闸（请求/宿主回复/结果三处）；`onBind` 非隔离态返回 null；`onDestroy` 清 relay/回调/bridge，`nativeDestroy` 释放 ctx+rt+b，Parcel 在 `finally` 回收 |
| ADR-0018（前台服务）在本 diff 的落地 | **清洁** | 全仓无绕过封装直调 `startForegroundService`；`DownloadService.start`（`:706`）带「勿直调 ContextCompat」注释；`skippedCount` 存在且失败章计数、暂停不出队；`onTimeout` 仅做秒级收尾（置标志 / 移除回调 / 发通知 / `stopSelf`，无查库、无网络、无起协程） |
| ADR-0026（缓存页口径）在本 diff 的落地 | **清洁** | `CacheManageActivity.kt:104-109` 本页不删书 + `FLAG_ACTIVITY_CLEAR_TOP` 回 `startDestination`（即书架）；`storageUsage()` 经 `Dispatchers.IO` 单列，未并入可清理量 |
| ADR 交叉引用禁令（AGENTS.md:118-120 硬规则） | **清洁** | 新 ADR（0023–0032）正文无一处 `见 ADR-xxxx`；全仓无 `lib_common ADR-`/`ebook-server ADR-` 形式的跨仓编号引用；新 ADR 均带编号 + slug 标题 + 编号化「决策」段，符合 ADR-FORMAT |

### 4.1 二次核验（按 receiving-code-review 规矩，对**本报告自身结论**的复核）

| 原结论 | 复核判定 | 证据 | 处置 |
| --- | --- | --- | --- |
| 栈溢出靠字面量匹配，QuickJS 换措辞会静默回退 | **不成立（已更正）** | 文案出自 `third_party/quickjs/quickjs.c:7791` 的 `JS_ThrowInternalError(ctx, "stack overflow")`，是**被 `PIN.sha256` 钉住的 vendored 依赖**字面量；`:35722` 的带参变体仍被 `contains` 覆盖 | 撤回「改错误码判定」的建议（过度设计）；仅留「升级 QuickJS 时复核映射」一条注记 |
| `lib_book_source` 的 `api(project(":lib_ebook_db"))` 过度暴露 | **不成立（已撤回）** | `BookParser.kt`/`JsoupBookParser.kt`/`AggregateSearchEvent.kt` 的**公开签名**以 `com.ebook.db.entity.*` 为参数/返回类型 | 撤回该条，不得改 `implementation`（会破坏消费方编译） |
| `##$##{…}` 静默产出被污染 URL | **机制成立，影响面需限定** | `ReplacementApplier` 不补逗号（已读全文）；`RuleSplitter.parse:53` 只在 `##` **之前**的取值段剥尾段；`ScriptFieldExtractor.resolveUrl:71` 用 `splitTail` 要求 `,{`。但 `##$##` 在**内置源 + 4 份真实夹具中零出现**，且无端到端断言 | 机制保留；代码侧降为 P3，规格文本侧保持 P1 |
| 正则逐元素重编译 | **成立，且低估了成本** | `ScriptFieldExtractor.fieldText:49-56` 逐条目调 `evaluate`；`evaluate` 每轮重跑 `Interpolation.expand` + `RuleSplitter.parse` + `Regex(...)` | 改述为「整条规则管线的逐条目重解析」；优先级 P3（触发条件有限） |
| `JsProtocol` 五个类缺 KDoc | **成立但属边界判断** | 四个帧 DTO 为 `private`，线格式语义在 `SandboxContract` 交代 | 保留，标注为需 owner 定调，不默认违规 |

## 5. 待裁决清单（按需一次一项，A/B/C + 推荐）

| 优先级 | 事项 | 选项 | 推荐 |
| --- | --- | --- | --- |
| P1（**仅规格文本**） | `##$##{…}` 与 `##$##,{…}` 二选一：§6.1 写不带逗号、§11-19 与实现要带逗号，按 §6.1 写的源会静默产出被污染的 URL。**经复核：该惯用法在内置源与 4 份真实夹具中零出现，无端到端断言 → 是写作陷阱而非现网故障；代码侧降 P3** | A 改 §6.1 文本为 `##$##,{…}`，并注明「不带逗号＝作者用法错误」<br>B 改 `optionTailCut`/`splitTail` 接受无逗号 `{` 作尾段起点——**但须分层**：仅放宽 URL 结果侧（`ScriptFieldExtractor:71`、`ScriptPageChain:56`、`ScriptUrlResolver:32`、`ScriptBookParser:324`），规则串侧（`RuleSplitter:58`）保留 `,` 要求<br>C 两者并存，但识别失败时抛类型化告警 | **改为「先取证，再判」，取证后大概率是 B（分层）。原推荐 A 已撤回**——理由见 §8：§6.1 的不带逗号写法标注为〔语法文档原文警告〕（**上游出处**），而 spec 自定规则才有「按 bug 修」的余地；据此实现要求 `,` 更可能是**我方的清洁室偏差**，而本仓明规「清洁室语义偏差按 bug 修」。A 只在「上游确实要求逗号、spec 误引」时成立，举证责任在验证而非省事 |
| ~~P1~~ **已闭合** | ~~沙箱 12 条插桩测试靠 `assumeTrue` 静默跳过，plan 要求的「零失败零跳过」无法由 diff 证明~~ | **无需修补。** 复核发现 plan `:7507` 已有撤销「未执行」的设备首跑记录，且构建产物报告记 `skipped="0"`（详见 §3.4）。原判为误读过期段落所致 | **不动代码、不加脚本**——前提被证伪，为不存在的问题引入工具属过度工程。若日后想给「silent skip」上一道自动门禁（本仓无 CI，需落 `scripts/`），那是独立需求，另行决定 |
| P3（原 P2，已降级改述） | 逐条目整条规则重解析：`ScriptFieldExtractor.fieldText` 对每个条目重跑 `Interpolation.expand` + `RuleSplitter.parse` + `Regex(...)`。**触发条件有限**（目录字段常见形态是组引用 `$2`／CSS 选择器，不走正则） | A 在 `ScriptFieldExtractor` 或求值器按「规则串 → 已解析节点/已编译正则」加一级缓存<br>B 只缓存 `Regex`，不动 `RuleSplitter.parse`<br>C 记录到 `docs/test-coverage-todo.md`，等真实大目录源实测再定 | **C 先行，A 备选**。无实测数据前不动热路径；若要做，A 的收益大于 B（重解析比正则编译更贵） |
| P2 | `getComments` 实现签名（分页 `CommentPage`）与 spec §4.4（`List<BookComment>`）不符 | A 改 spec §4.4 对齐实现<br>B 改实现回退到无分页 | **A 但不止于 A**。改 spec 文本对——线上契约 §3.2.1 已定 `RespDTO<CommentPage>`，改实现等于与后端脱钩。**但原推荐漏了一步**：这是**接口契约变更**，按 `AGENTS.md`「由评审驱动的架构级决定必须沉淀为 ADR」，需同时落到 `docs/adr/0013-comment-and-profile-api-alignment.md`（该篇即评论/资料接口对齐的归属 ADR），而非只改 spec 文本 |
| P3（**已降级**） | 栈溢出状态靠 `"stack overflow"`/`"too deep"` 字面匹配 | ~~B 改为按 `JS_GetException` 类型/错误码判定~~ **已撤回（过度设计）**<br>A′ 加一条单测把 `mapStatus` 对三种已知文案的映射钉住<br>C′ 仅在 ADR-0028 或 `third_party/quickjs/VERSION` 旁记一句「升级 QuickJS 须复核对齐」 | **A′ + C′**。文案来自 vendored 且 pinned 的依赖，改错误码判定属无收益重构；用一条测试把耦合显式化即可 |
| P3 | `JsProtocol` 的 `DecodedRequest` 与 4 个帧 DTO 缺 KDoc | A 补齐五行 KDoc<br>B 认定为「私有线格式 DTO，语义已在 `SandboxContract` 交代」而豁免 | **改为 A（原倾向 B 不对）**。本仓明规「文档对则改代码、代码对则同步文档，**不留中间态**」；「认定豁免」= 在 `AGENTS.md:112` 之外开一个**未写进标准的例外**，正是该规则要消灭的中间态。要豁免就得改 `AGENTS.md` 正文，那比补 5 行 KDoc 贵得多 |
| P3 | TheRouter 规则 24 行复制 6 份 | A 下沉为共享约定插件片段<br>B 暂缓 | **B 但须登记**。原推荐「B 暂缓，等 build-logic 另有改动时合并」是**口头的延后**、不是最佳实践——延后要落成可追踪的债：按本仓「known-issues 由各 ADR 自行记录」的约定写进 `docs/adr/0024-proguard-rules-overhaul.md` |
| P3 | 深度感知切分器手写 4 遍；`duplicateGate` 命名；`deleteComment` 缺「无静态资产」注释 | — | **同上：合并为一次小重构可以，但须登记归属 ADR**（切分器重复→脚本规则 ADR；`deleteComment` 注释→评论契约 ADR-0013）。不登记的「不单独排期」会直接丢失 |

## 6. 结论摘要

> 下为**二次核验后**的口径。首次结论中被推翻/降级的部分见 §7。

- **Standards 轴**：硬违规 1 项（`JsProtocol.kt` 类缺 KDoc——经复核属标准边界判断，非无争议违规）；判断项 5 项（原 6 项，**S4 已撤回**）。P0 0 项，**代码侧无 P1**。
- **Spec 轴**：8 项。**其中真问题 1 项、文档漂移 1 项、已关闭的伪缺口 1 项、跨切片归属待确认 2 项、已撤回 1 项、降级 2 项**。最高优先级为规格文本侧的 `##$##{…}` 内部矛盾（**仅文档层 P1，代码层 P3，且零语料实证**）。
- 两轴均无 P0（无安全边界洞、无数据损坏、无构建破坏），**无遗留 P1**。**未评审残差**：97 个测试文件、文档正文、vendored QuickJS。

### 提交前必须由人工完成的验证（Agent 未做装机验证）

> 沙箱插桩测试（原第 1 项）**已由 2026-09-09 的设备首跑完成并留证**，故从本清单移除，见 §3.4。

1. 2e 书城四步：导入含 `exploreUrl` 的脚本源 → 启用 → 冷启动 → 分类胶囊可点。
2. 沙箱断连自动重启：手动杀 `:js` 进程，确认任务自动恢复。
3. M2 与 2e plan 各自「人工装机验证项」共 11 步（见两份 plan 末节）。

## 7. 更正记录（二次核验）

初次报告的两轴结论未对**自身论断**做对抗性复核，存在 2 处误判与 3 处需限定/降级的问题。按「先验证再采纳」的规矩逐条复核后更正如下。**未采纳任何未经验证的建议。**

| # | 首次结论 | 更正 | 定性 |
| --- | --- | --- | --- |
| 1 | 栈溢出靠字面量匹配 → 「QuickJS 换措辞会静默回退」，建议改按错误码判定 | **误判，已撤回**。文案出自 `third_party/quickjs/quickjs.c:7791` `JS_ThrowInternalError(ctx, "stack overflow")`——vendored 且被 `PIN.sha256` 钉住；`:35722` 的带参变体仍被 `contains` 覆盖。不存在「自行漂移」，改错误码判定是无收益重构 | 撤回 + 降级 |
| 2 | `api(project(":lib_ebook_db"))` 过度暴露，应改 `implementation` | **误判，已撤回**。`BookParser`/`JsoupBookParser`/`AggregateSearchEvent` 的公开签名以 `com.ebook.db.entity.*` 为参数/返回类型 → `api` 必需，改动会破坏消费方编译 | 撤回 |
| 3 | `##$##{…}` 静默产出被污染 URL（P1，暗示现网风险） | **机制正确，影响面被高估**。机制链条已逐环验证（`ReplacementApplier` 不补逗号；`RuleSplitter.parse:53` 只在 `##` 前剥尾段；`resolveUrl:71` 要求 `,{`）。但 `##$##` 在**内置源与 4 份真实夹具中零出现**，且无端到端断言 → 是「文档写法不成立」的写作陷阱 | 限定 + 降级 |
| 4 | 正则逐元素重编译（P2） | **成立且低估成本**：`fieldText` 逐条目调 `evaluate`，每轮重跑 `Interpolation.expand` + `RuleSplitter.parse` + `Regex(...)`——是整条管线重解析。但触发条件有限（组引用 `$2` 与 CSS 选择器不走正则） | 改述 + 降级 P3 |
| 5 | `JsProtocol` 五个类缺 KDoc（硬违规） | **成立但属边界**：四个帧 DTO 是 `private`，线格式语义在 `SandboxContract` 统一交代 | 保留 + 标注需 owner 定调 |

**复核方法**：不采信子代理结论、也不采信自身初判，一律回到源码与语料取证——`ReplacementApplier` 读全文、`RuleSplitter.parse`/`evaluate` 读实现、`js_bridge.cpp` 与 vendored `quickjs.c` 定位文案出处、`grep` 语料实证使用情况、`grep` 公开签名判定依赖必要性。

**残留不确定项**（明确声明，未以推断充结论）：
- `##$##{…}` 的**作者意图**只能从 spec §6.1 与 §11-19 的矛盾里推断，无第三方书源语料佐证；修正方向（改文档 vs 改代码）需 owner 裁决。
- 逐条目重解析的**实际性能影响无实测数据**（未在真机大目录源上profile），故建议先记录后实测，不预改热路径。

## 8. 推荐方案的依据自查

§5 的首版推荐被审出**四条不合最佳实践**——共同的错因是**按「改起来最省事」排序，而不是按「哪个判断是对的」排序**。逐条更正如下。

### 8.1 判定原则（替代「成本最小」）

1. **权威优先于成本**：外部格式（脚本书源格式）的语义以**上游出处**为准，不以「改哪边省事」为准；本仓明规「清洁室语义偏差按 bug 修」。→ 决定了 `##$##` 一项应改代码而非改文档。
2. **本仓规则优先于通用直觉**：`AGENTS.md` 已就「不留中间态」「架构级决定必须沉淀为 ADR」「known-issues 由各 ADR 自行记录」立规，推荐必须落在这些既有机制里，不能另发明一套（如「口头暂缓」「认定豁免」）。
3. **按实际基建出方案，不按理想基建**：本仓**无 CI**（已核：无 `.github/workflows`、无 gitlab-ci、无任何 yml/yaml），因此「挂 CI 门禁」不是可执行建议；只能落 `scripts/` 本地脚本或显式人工门禁。
4. **区分「机制」与「影响面」**：机制成立 ≠ 现网受损。影响面必须用语料/调用点实证限定，否则会制造假的 P1。
5. **一次性动作不构成保证**：人工跑一次 ≠ 可复现的约束。要保证就得让缺口**可见**（脚本校验 `skipped`），而不是记一笔就完。
6. **读追加式文档要读到尾**：本仓的 plan 是追加式的，**过期结论由后段就地撤销**而不删前文（如 `:7470` 的「未执行」被 `:7507` 的「设备首跑」撤销）。只读前半段会把**已闭环**的事读成缺口——本报告首版就这么误判了一条 P1。同样，**构建产物（`build/`）是可用证据**，只看源码与 diff 会漏掉跑过的结果。
7. **不得改写引用原文**：引用外部文档的句子（标了〔语法文档原文…〕的）与自家表述必须区别对待——自家表述可直接改到正确，引用只能在其**之外**加读法说明，否则等于伪造出处。

### 8.2 逐条更正

| 原推荐 | 问题 | 更正 |
| --- | --- | --- |
| `##$##`：**A 改文档** | 按成本选边；且忽略了 §6.1 的不带逗号写法标注为〔语法文档原文警告〕=**上游出处**，而 spec 只在自定规则处才声明「按 bug 修」的余地 | **先取证（读上游帮助文档／找真实使用该惯用法的源），若证实上游接受无逗号 → 改代码 B，且分层实施**：只放宽 URL 结果侧 4 个调用点，规则串侧（`RuleSplitter:58`）保留逗号要求——那里与遗留单花括号 JSONPath 形态确有歧义。原「B 有歧义」的反对意见经分层后**不成立** |
| 沙箱测试：**A 人工跑一次** | 前提本身就错——device 跑**早已完成并留证**（plan `:7507` 撤销了 `:7470` 的「未执行」，构建产物 `skipped="0"`），我为不存在的问题设计了补救 | **撤销，不动代码、不加脚本**。真正该记的是读法教训：本仓的 plan 是**追加式**文档，过期条目会被后段**就地撤销**，只读前半段就会得出反向结论 |
| `##$##`：**B 改代码** | 同样是只读到部分证据——`ScriptRuleEvaluator.kt:240`（活代码）本就用带逗号形态，plan 对尾段的定义是「含前导逗号」，§6.1 同句的另两个例子也都以逗号起头 | **改为 A′（统一写法 + 加端到端测试，不动 `optionTailCut`）**；spec §6.1 的引用原文**不改字**（改引用＝伪造出处），只在其外加读法说明 |
| `getComments`：**A 改 spec** | 漏了本仓「架构级/接口契约决定必须沉淀为 ADR」这一硬规 | **A + 沉淀到 `docs/adr/0013-comment-and-profile-api-alignment.md`** |
| `JsProtocol` KDoc：**B 豁免** | 「认定豁免」是在标准之外开未书面化的例外，正是本仓「不留中间态」要消灭的形态；且豁免要改 `AGENTS.md`，比补 5 行 KDoc 更贵 | **A 补齐 KDoc** |
| TheRouter / 切分器 / 命名：**「暂缓」「不单独排期」** | 口头延后 = 会丢失的债 | 延后可以，但**按本仓约定登记到归属 ADR 的 known-issues**（ADR-0024 / 脚本规则 ADR / ADR-0013） |

### 8.3 经自查仍保持的推荐

- **栈溢出项**：不改为错误码判定，只用单测钉住映射 + 在 QuickJS 版本处注明升级须复核——文案出自 vendored 且 pinned 的依赖，改动无收益。
- **逐条目重解析项**：先记录后实测，不预改热路径——无 profile 数据即优化属无证据改动。
- **独立核验表（§4）中的清洁项**：均以读源码/语料取证得出，保留。

### 8.4 本次自查暴露的能力边界

- `##$##` 的**正确修正方向**原判「本仓内无法判定、须外部取证」——第三轮取证推翻了这个判断：证据其实**在仓内**（plan 对尾段的定义「含前导逗号」、`ScriptRuleEvaluator.kt:240` 的活代码写法、§6.1 同句另两个例子均以逗号起头）。反过来说明一条方法问题：**「无法判定」有时是「还没找全」，宣布能力边界前要把仓内证据搜穷。**
- 仍无法在仓内判定的只剩一点：外部语法文档原文里 `##$##` 之后**究竟有没有逗号**。但这已不影响结论——本规格自己的条款（判定条 + §11 边界清单 + 同句两例）已足够定出唯一读法。

## 9. 本轮实施的修补（2026-09-10）

原则：**只修有证据支撑的**；前提被证伪的一律不动，不引入为不存在的问题服务的代码。

| # | 事项 | 处置 | 位置 |
| --- | --- | --- | --- |
| 1 | `JsProtocol` 五个类缺 KDoc | **已补** —— `RequestFrame`/`OutcomeFrame`/`DecodedRequest`/`HostCallFrame`/`HostReplyFrame` 各补 KDoc，说明「为何单独一层 DTO」「可空性的协议语义」「args 为何不在本层解析」 | `JsProtocol.kt` |
| 2 | 栈溢出状态映射 | **已加固** —— 原映射已有 `"stack overflow"` 用例，补 `too deep` 变体并在注释里写明「两句文案出自 vendored 且 `PIN.sha256` 钉住的内核字面量，**升级内核时必须回来复核**」。未按错误码改造（无收益） | `JsProtocolTest.kt` |
| 3 | `deleteComment` mock 缺「为何无静态资产」注释 | **已补** —— 明写「响应随入参变化，静态资产表达不了删了哪条」，并对齐 `AGENTS.md` 的写接口/读接口分工 | `CommentNetworkTest.kt` |
| 4 | `getComments` 仓库层契约与 spec 不符 | **已对齐** —— spec §4.4 与 §5.1 用法示例改为分页形态（含空键列表在仓库层收口这条真实契约）；按「接口契约决定须沉淀 ADR」在 ADR-0013 追加「更新（2026-09-10）」段，并就地修正其决策 2 里「`loadMore` 暂不启用」的过期记述 | `specs/2026-09-05-m2-comment-api-contract.md`、`adr/0013-*.md` |
| 5 | `##$##` 写法不一致 | **已统一 + 已加端到端测试** —— 我方 4 处活代码 KDoc 统一为 `##$##,{…}`（并写明不带逗号的后果）；spec §6.1 **只加读法说明、不改引用原文**；新增端到端用例（替换 → 尾段定位），并固定「无逗号形态不被识别」的现状以备将来决定 | `RegexReplacement.kt`、`RuleSplitter.kt`、`ScriptRuleEvaluator.kt`、`specs/2026-09-08-script-source-rule-grammar.md`、`ScriptUrlOptionTest.kt` |
| 6 | ~~沙箱插桩测试零跳过~~ | **未动手（前提被证伪）** —— 设备首跑已于 2026-09-09 完成并留证（plan `:7507` + 构建产物 `skipped="0"`）。不加脚本、不改 `assumeTrue` | — |

**刻意未做**（及原因）：

- **`optionTailCut` 接受无逗号尾段**：不做的理由不是「歧义」（分层后 URL 侧无歧义），而是**仓内证据一致指向该带逗号**，改代码等于主动偏离本规格自己的判定条款与 §11 边界清单。
- **TheRouter 规则去重**：5 个功能模块的 `consumer-rules.pro` 字节一致（同 md5），`module_app/proguard-rules.pro` 另存一份；但归属由 ADR-0024 定规，且属 release 混淆配置，改动需发布构建验证。**登记为债**，不在此轮动。
- **逐条目重解析缓存**：无 profile 数据即改热路径属无证据改动。
- **深度感知切分器 4 处重复**：四处语义并不相同（`optionTailCut` 独有「逗号紧前 + 规则层深度 0 + 到串尾配平」三条约束），合并前需先证明可统一，否则是把四份各自正确的代码换成一份不够精确的。
- **历史 plan 文档里的 `##$##{...}` 简写**：plan 是追加式工作记录，非事实源；活代码 KDoc（本仓指定的事实源）已统一，故不改历史记录。



