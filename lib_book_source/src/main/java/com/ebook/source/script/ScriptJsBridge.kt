package com.ebook.source.script

/**
 * 规则层看到的「会跑 JS 的东西」（规格 §5.4/§6.2/§1.4 的执行面）。
 *
 * 五个入口对应五个原本抛 [JsEvaluationPendingException] 的位置，**刻意不做成一个泛化的
 * `run(mode, source)`**：五处的输入（有没有页面）、期望产物（文本 / 标量 / 对象 / 字段表）
 * 和「跑成了但没值」该怎么处置各不相同，收成一个方法就会在调用点各写一遍解包逻辑，
 * 而解包口径正是最容易各写各的地方。
 *
 * internal：只有本模块的求值层与取文层实现它、调它。跨模块（`lib_book_common`）只经
 * `JsSandboxHost` 拿装配好的实例，看不见这个接口。
 *
 * 失败口径与 2b 一致：跑坏了抛 [JsExecutionFailedException]，**不返回 null 冒充「没取到值」**。
 * 唯一的例外是 [runSegment] 的 `undefined`——脚本被合法地跑完而完成值是 `undefined`，
 * 那是「这条规则没有值」，回 [RuleResult.Miss] 让 `||` 兜底支照常生效。
 */
internal interface ScriptJsBridge {

    /** `@js:` / `<js></js>` 整段：完成值按文本/文本表取，`undefined` 为 Miss */
    fun runSegment(source: String, input: RuleValue): RuleResult

    /** `{{…}}` 里的表达式：标量文本原样回填，`undefined`/`null` 展开成空串（§9），对象/数组按失败报 */
    fun runExpression(expr: String): String

    /** URL 选项 `js`：改写 `url` 与 `headers` 后回传（§6.2） */
    fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl

    /** URL 选项 `bodyJs`：对响应体做二次处理，回新文本（§6.2） */
    fun runBodyJs(script: String, url: String, body: String): String

    /** `init` 的 JS 分支：脚本回传一个对象，字段名即键（§1.4） */
    fun runInit(script: String, input: RuleValue): Map<String, String>
}

/** [ScriptJsBridge.runUrlJs] 的产物。[headers] 为脚本未改动时按原请求头回传，调用侧不必判空 */
internal data class JsRewrittenUrl(val url: String, val headers: Map<String, String>)
