# 正文取文接缝由两种出身共用

Status: accepted

正文抓取原先按出身分岔：脚本走显式的 `ScriptContentParser` 接口（可假、已测），原生由读取器 `instanceof JsoupBookParser` 后掏 `rule` 自己拼请求（必须真网络 + 真 Context，长期零测试）。同一个「取一章文本」在两侧各有一套，存储策略还在读取器里逐字写了两份。决定把接缝提升为格式中立的 `BookContentParser`，两种出身都实现它，读取器只剩一条路径。

## 背景

- 脚本出身先有接缝是不得已：脚本的规则求值件全是 `internal`，跨模块转型拿不到。原生之所以留在读取器，是历史搬迁（M1b）的落点，不是设计选择。
- 后果直接落在测试面：脚本分支有假件用例（落盘、空正文、类型化异常透传），原生分支只有「拿到不支持的 parser 报编程错误」这类入口判断；单页/多页拼接、`replaceRules`、空正文不落盘在原生侧没有任何 JVM 用例。要改原生取文只能装机试。
- 存储策略两份逐字相同（`TextNormalizer.unifyNewlines` → `split('\n')` → 空则不落盘 → `writeChapter`），漂移不会报错，只会让同一本书换源后既有章文件读不回来。

## 决策

1. **`ScriptContentParser` 更名为 `BookContentParser`，两种出身都实现**：原生 `JsoupBookParser.fetchChapterText` 承接 PageWalker、同章分页判定（`ChapterPageMatcher`）与清理规则；脚本解析器实现不变。读取器按 `BookParser` 取源后转成取文面，拿不到才报编程错误。
2. **读取器只保留一份存储策略**：取文本 → 记错误 URL 清单 → 规范化切段 → 空则返回空段落、否则写章文件。取源仍在任何 try 之外（书源失效不能被裹成解析失败）。
3. **error mode 不合并**（ADR-0046）：原生把抓取失败裹成「章节内容解析失败」（选择器失配，重试可能好），脚本把 `ScriptRuleException` 子类原样上抛（消息面向用户，重试不会好）。读取器只记同一份排查线索、不改写类型。
4. **读取器不再注入 `OkHttpClient`**：抓取由解析器一侧完成，`JsoupSourceReader` 的构造面随之收缩。
5. **`JsoupBookParser.rule` 收成 internal**：正文抓取已不读它，对外也没有别的跨模块消费者——「缓存有没有换成新行」由测试断言实例更换（`assertNotSame`）即可，规则内容随构造固化属实现细节；曾因依赖方测试读它而保持 public，那两条断言判冗余后删除。

## 被拒方案

- **新增第五个书源窄面（`ContentResolver`）**：读取器只差一次转型，为它多开一张面要多一份绑定与替身，而转型本身留在读取器里也只是把 `instanceof` 换成 `as?`——真正的不对称是「两条取文实现」，不是「谁来做转型」。
- **把原生取文继续留在读取器**：保住一致性，但原生分支永远只能靠装机验证；而且它要读 `rule`，等于让读取器长期拥有一份解析器内部知识。
- **把两种 error mode 合成一种**：违反 ADR-0046——脚本的类型化异常携带着面向用户的中文消息，裹掉等于把真话换成查不出根因的套话。

## 边界

- 不改 `PageWalker` 的机制（回环守卫、页数上限、零新增即停）；原生与脚本的分页判定仍各自一份（规格 §7.3/§12 明令不合并）。
- `JsoupSourceReader` 仍住 `lib_book_common`（搬去 `lib_book_source` 会成 Gradle 环）。
- 不改章文件格式与 `TextNormalizer` 的分工（存储层只切不洗，缩进归读取层）。

## 下游影响

- `JsoupBookParser` 新增 `fetchChapterText`，`JsoupBookParserTest` 用带拦截器的 OkHttpClient 锁单页、同页分页、清理规则、空正文与失败裹层。
- `JsoupSourceReader` 删掉 `fetchAndStoreNative`/`fetchAndStoreScript` 与 `MAX_CONTENT_PAGES`，构造去掉 `@Named("source") OkHttpClient`；`JsoupSourceReaderTest` 的假件改实现 `BookContentParser`。
- `ScriptContentPager.MAX_CONTENT_PAGES` 与原生上限同值（50），但两者是各自模块的独立常量，不互相引用。

锚点：`BookContentParser`；`JsoupBookParser`；`JsoupBookParserTest`；`JsoupSourceReader`
