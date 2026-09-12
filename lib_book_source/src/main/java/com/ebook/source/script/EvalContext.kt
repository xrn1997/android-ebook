package com.ebook.source.script

/**
 * 一次解析任务的可变上下文（规格 §5.2 的「本仓规定」）。
 *
 * 作用域刻意是**单次解析任务**（一本书的一轮求值）：公开文档没写清 `@put:`/`java.put`
 * 存的是局部变量还是书源级持久变量（§5.2、§11），按任务级实现最保守——
 * 真依赖跨请求存活的源会在真实运行里取到空，而规格 §5.2 要求导入侧把这条风险提示出来；
 * 该提示项至今没进 `ScriptUnsupported` 清单（导入报告只报能力缺失，不报这条作用域风险）。
 * 上游另有明确持久的三套变量面（书源级 / book / chapter）与 `cache`：前三套的**按键**读写
 * 在本仓全部落到这一张 [variables]（作用域降级，代价与理由记在规格 §11 的变量作用域条），
 * `cache` 不参加这次折叠：它在沙箱里根本没有这个名字（脚本碰到的是 ReferenceError），
 * 规则侧的 `@cache:` 前缀在装载期就记进 `ScriptRuleSet.unsupported`。
 *
 * [key] 与 [page] 只在搜索/发现的 URL 上有意义（§5.3 限定），这里给出默认值即可，
 * 页码换算与 URL 选项在 `ScriptTocPager` / `ScriptUrlOption`；[variables] 用 LinkedHashMap 保序，
 * 让 `@put:` 多条写入的顺序在错误日志里可读。
 *
 * [baseUrl] 是 `var`：翻页链每取回一页就把它推进到该页绝对地址——下一页字段结果的相对
 * 落位基准是「链接写在哪页、就相对那页」（与 `TocPageUrl.join` 的既有语义同一条）。
 * 变量表等其余状态不动：作用域仍是单次解析任务（§5.2）。
 *
 * [js] 是通往沙箱执行器的桥；[withoutJs] 是给沙箱回调用的 JS 关闭视图（嵌套求值不能再要一次 JS）。
 */
internal class EvalContext(
    var baseUrl: String = "",
    var key: String = "",
    var page: Int = 1,
    /**
     * 源级自定义变量**整串**（`variable` 键的当前值）。
     *
     * 与 [variables] 分开是两个形状：这张表按键存取（`@put:`/`java.put`/`source.put`），而这一份是
     * 一整段 JSON 文本，脚本的写法就是 `JSON.parse(source.getVariable())` 改字段再整串写回。
     * 上游把它持久在书源行上，本仓的作用域与 [variables] 同档（单次解析任务）——理由与后果都记在
     * 规格 §11 的变量作用域条里，不要「顺手」把它写回 Room：解析过程中改书源行是另一类竞态。
     *
     * 跨线程读写的前提与变量表同一条：沙箱一次只有一帧在飞（客户端的 `inFlight` 锁），
     * 写在受理回调的 binder 线程、读在下一次 `bridgeFor` 的调用线程。
     */
    var sourceVariable: String = "",
    /**
     * 变量表。默认每次新建；[withoutJs] 走**传引用**这条路，让嵌套求值里 `@put:` 写的量
     * 外层 `@get:` 立刻读得到（嵌套求值与外层共用同一个变量表实例）。
     */
    val variables: MutableMap<String, String> = linkedMapOf(),
) {
    /**
     * 本次任务的 JS 桥。null = 没有沙箱，规则层的 JS 段继续按 [JsEvaluationPendingException] 报。
     *
     * 为什么是 `var` 而不是构造参数：桥的构造要收 ctx（`baseUrl`/`page` 现取、变量表共用），
     * ctx 又要持有桥，做成构造参数就是一个环。赋值点**全仓只有一处**
     * （`ScriptBookParser.newContext`，先建 ctx 再回填），别处不要写它。
     */
    var js: ScriptJsBridge? = null

    /**
     * 关掉 JS 的上下文视图，供沙箱回调里的嵌套规则求值使用（规格 §5.4：递归求值不得再要 JS）。
     *
     * 变量表**按引用共用**，三个标量取当下的快照：嵌套求值里若改了 `baseUrl`/`page`，
     * 外层翻页链的基准不能跟着动——那会让下一页从内层最后一次修改的地方长出来。
     */
    fun withoutJs(): EvalContext = EvalContext(
        baseUrl = baseUrl,
        key = key,
        page = page,
        sourceVariable = sourceVariable,
        variables = variables,
    )

    /**
     * 内置量表（§5.3）：只放声明式子集认得的这几个，其余一律按 JS 待执行处理。
     *
     * `book` / `chapter` / `result` 这类 JS 侧绑定**刻意不在这里出现**：它们的值来自 Room
     * 里的书籍实体，而本段禁止碰持久层（§5.1/§9 的分工）。返回 null 就是「我不认得这个量」，
     * 由插值层据此抛待执行，而不是猜一个空串糊上去。
     */
    fun builtin(name: String): String? = when (name) {
        "key" -> key
        "page" -> page.toString()
        "baseUrl" -> baseUrl
        else -> null
    }
}
