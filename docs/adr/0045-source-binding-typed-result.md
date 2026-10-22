# 取源结果类型化：SourceBinding 四档

Status: accepted

`BookSourceManager.getParserFor` 用一个 null 同时表示「本地书」「库里没这行」「原生坏行」三种成因，多处生产调用点各自再推导一次。决定把取源结果收成密封的 `SourceBinding`（Local / Found / Missing / Broken），生产调用点统一经 `parserOrThrow` 只翻译一次；ADR-0016 与 ADR-0029 定下的「源不存在」与「规则解不动」分界原样保留。

## 背景

多书源共存后，每本书按 `tag` 取源是解析链路的起点。旧读面 `getParserFor(sourceUrl): BookParser?` 的 null 契约把三种完全不同的状态压进同一个值：

- 本地书的 `tag` 是 `loc_book`，本来就不该查库；
- 空白 `tag` 或库里没有这一行（源被删 / 脏数据）；
- 行在，但原生 `rule_json` 解不出来（降级或手工改库留下的坏行）。

`BookShelfManager`、`BookRepository`（加书架/换源/目录重抓）、`JsoupSourceReader`、`BookSourceRepository`、`BookDetailViewModel` 等多处调用各自写 `?: throw` / `?: return Failed`，每新增一个调用点就要把这份成因表再抄一遍。ADR-0016 决策 5 与 ADR-0029 已明确：null 只说「源不存在/坏行」；「源在、这条规则解不动」必须是另一种可区分的结局。本条把这条分界从注释搬进类型，不改它的语义。

## 决策

1. **`resolveSource` 返回密封的 `SourceBinding` 四档**：`Local`（本地书，不是故障）、`Found(parser)`、`Missing(sourceUrl)`（空白/库中无行）、`Broken(sourceUrl)`（原生 `rule_json` 解不出来）。四档互斥；`Missing` 与 `Broken` 在日志/排查上必须分开——前者是数据没了，后者是数据坏了，分开的位置是 `toItem` 那条 ERROR，因此 `Broken` 本身不复制一个 `cause`（它给不出真解码失败，造一个只说第二遍）。
2. **生产调用点统一经 `parserOrThrow`**：`Found` 给 parser；`Missing`/`Broken` 给 `BookSourceNotFoundException`（用户可处置的源失效）；`Local` 给 `IllegalStateException`（本地书走网络取源是编程错误，不伪装成源失效）。需要把失败折成返回值的调用方（详情页、目录重抓）自行 catch 或折叠；不得各自重推四档。
3. **生产路径不再使用 `getParserFor`**：只保留一个 `@Deprecated` 的扩展适配，把四档压回 parser-or-null，服务尚未迁移的测试布置与旧调用点；新增生产代码用 `resolveSource` 取四档，只需要 parser 时走 `parserOrThrow`，不得在调用点自己重推四档。理由：一次性改几十处测试布置会把这次改动的风险面放大到与收益不相称；适配器只在测试/旧调用侧，生产语义已经类型化。

## 边界

- **正文入口的本地/空白前置短路**：`JsoupSourceReader.resolveParser` 先对 `loc_book` 与空白 tag 抛错（消息带 `bookId`，且不查库），其余才交 `parserOrThrow`；这两支只做前置路由，不重推 `Found/Broken`，四档语义不变。
- **脚本规则失败不是 `Broken`**：`resolveSource` 对脚本行返回 `Found`，坏 JSON 在首次求值才抛公开的 `ScriptRuleException` 子类；该层级的公开是 [ADR-0046](0046-script-failure-hierarchy-public.md) 的决定，本条只负责取源结果。
- ADR-0016/0029 的「源不存在」与「规则解不动」分界原样保留；`Missing` 与 `Broken` 在 UI 上可给同一句提示，但日志与排查必须分开。

## 被拒方案

- **继续返回 null，只抽一个 `parserOrThrow` helper**：helper 只能替调用方抛同一种异常，无法让需要分流处置的调用方各取所需；成因表仍然活在接口 KDoc 里。
- **把脚本失败折叠成 `Broken`**：类型上省一档，但语义上把「规则解不动」与「源不存在」合并，违反 ADR-0016/0029。
- **直接删掉 `getParserFor`、一次改完全部测试布置**：语义上最干净，但改动面横跨四个模块的测试假件与既有断言，与本条要锁的「生产调用点类型化」收益不成比例；适配器明确标注 deprecated，生产代码不依赖它。

## 下游影响

- `lib_book_common`：`BookSourceManager.resolveSource` 取代 `getParserFor` 成为生产读面；`BookSourceManagerImpl` 把 `toDefinition` 的 null 归为 `Broken`；`BookShelfManager`、`BookRepository`、`JsoupSourceReader` 改经 `parserOrThrow`；`BookSourceRepository`/`BookDetailViewModel` 同步。
- 测试：`BookSourceManagerImplTest` 新增 Local/Missing/Broken/Found 四例；既有 fakes 的 `getParserFor` 改为 `resolveSource`，测试调用仍可经 deprecated 适配。
- `getParserFor` 仅作为测试/旧调用适配存在，不属于生产接口。

锚点：`SourceBinding`；`BookSourceManager.resolveSource`；`parserOrThrow`；`BookSourceManagerImplTest`
