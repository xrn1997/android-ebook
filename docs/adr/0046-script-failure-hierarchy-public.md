# 脚本失败层级公开：ScriptRuleException 跨模块按类型处置

Status: accepted

`ScriptRuleException` 及其子类原本是 `internal sealed`，`lib_book_common` 的取源/正文路径只能靠消息字符串区分「规则装载失败」「JS 未装配」「执行失败」「不支持的能力」。决定把整个失败层级上浮为 public，让跨模块调用点按类型处置；错误语义与用户文案不变。

## 背景

脚本书源的失败不是一种：坏 JSON 是装载期失败，`@js:` 段没有执行器是能力缺失，真跑而超时/越界是执行失败，用了 XPath/webJs 是不支持的能力。旧层级把这些类型关在 `lib_book_source` 内部，`lib_book_common` 只能把它们当普通 `Exception` 透传，无法按类型分流；测试为了锁「消息原样透出」甚至自造一份同形 `RuntimeException`。消息本是面向用户的文案，不该承担跨模块契约。

## 决策

1. **基类与全部子类 public**：`ScriptRuleException`、`ScriptRuleParseException`、`RuleSyntaxException`、`JsEvaluationPendingException`、`UnsupportedRuleFeatureException`、`SandboxProtocolException`、`SandboxUnavailableException`、`JsExecutionFailedException` 全部公开。只公开基类不够——调用方需要区分「能力缺失」与「这次执行失败」才能决定是提示重导源还是重试。
2. **消息只做用户文案，不做跨模块判据**：跨模块代码需要分流时按类型分支；类型化失败仍原样上抛，不折叠成空结果。唯一窄例外留在求值器内部：后位 `@js:` 解引用桥接层从不提供的 `src`/`title` 时按「这页没有该字段」回落 `Miss`，把结果交回 `||` 链——判据、残余窗口与收紧方向的家在规格 §9，它不是「失败可折叠」的通用口径。
3. **不与取源结果合并**：`SourceBinding.Broken` 只表示原生坏行（ADR-0045）；脚本的「规则解不动」始终是求值期异常，不得折叠成 `Broken` 或 `Missing`。

## 被拒方案

- **保持 internal，另加一个返回布尔/枚举的 helper**：helper 仍要解析消息或维护一份平行分类，类型信息在模块边界处已经丢了。
- **只公开基类**：调用方能知道「脚本失败了」，但不能区分「没装执行器」与「执行超时」，用户会被支去重导一条本来好的源。
- **把脚本失败折叠成 `SourceBinding.Broken`**：把「规则解不动」与「源不存在/坏行」合并，违反 ADR-0016/0029 的失败分界。

## 下游影响

- `lib_book_source`：`ScriptRuleExceptions.kt` 全部 public；文档、KDoc 与 `JsoupSourceReader` 的注释同步为「类型可见、正文路径原样上抛」。
- `lib_book_common`：可在 `catch` 中按 `ScriptRuleException` 子类分支；不再依赖消息字符串。当前正文路径仍原样上抛，未新增按类型分流的分支。
- 测试：`ScriptRuleFailureVisibilityTest` 从 `lib_book_common` 引用基类与全部 7 个子类——可见性**靠跨模块编译锁**（测试源集不是 friend module，回退 `internal` 直接编译失败；反射读 `Modifier.isPublic` 断言不出任何事，Kotlin 把 `internal` 类也编成字节码 public），该用例另锁「catch 基类接得住每一个子类」；`JsoupSourceReaderTest` 直接用 `JsEvaluationPendingException` 锁「消息原样透出」，不再自造同形异常；各脚本失败类型的既有用例语义不变。
- 消息文案仍是用户可见文案，改措辞不影响跨模块契约。

锚点：`ScriptRuleException`；`ScriptRuleFailureVisibilityTest`；`ADR-0045-source-binding-typed-result`
