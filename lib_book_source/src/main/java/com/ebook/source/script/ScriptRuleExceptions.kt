package com.ebook.source.script

import com.ebook.source.sandbox.JsStatus

/**
 * 脚本书源求值失败的基类。
 *
 * 为什么必须类型化：取源结果（`SourceBinding`）已经把「没有这行 / 原生坏行」与「本地书」
 * 分开，而求值期的规则装载失败
 * （[ScriptRuleParseException]）、语法错误（[RuleSyntaxException]）、不支持的能力
 * （[UnsupportedRuleFeatureException]）、JS 待沙箱（[JsEvaluationPendingException]）、
 * 沙箱协议坏帧（[SandboxProtocolException]）、沙箱不可用（[SandboxUnavailableException]）
 * 与脚本执行失败（[JsExecutionFailedException]）彼此不是一回事，混起来会让用户被支去重导
 * 一条本来好的源。消息面向用户文案，禁止出现生态项目名。
 *
 * 每一种都**不得折叠成空结果**：返回空等于说「这个源没有这条信息」，而真相是「这条规则我解不动」，
 * 两者在页面上是同一种表现、在排查上是完全不同的两件事。唯一的窄回落口是后位 `@js:` 解引用
 * 桥接层未提供绑定时的 `Miss`（规格 §9），它只把「这页没有该字段」交回 `||`，不是通用的
 * 「失败可折叠」口径。
 */
sealed class ScriptRuleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 规则装载失败：JSON 读不出来、或形态根本不是本装载器接受的书源对象 */
class ScriptRuleParseException(message: String, cause: Throwable? = null) : ScriptRuleException(message, cause)

/** 规则串有确定的语法错误（括号不配对、索引形态非法到无法解释）；§3.3 本仓规定：按「未取到值」参与 || 短路 */
class RuleSyntaxException(val rule: String) : ScriptRuleException(
    "规则语法无法解释：${rule.take(80)}"
)

/**
 * 规则含 JS 段，而**这一次求值拿不到沙箱**。
 *
 * 只有两条场景会走到这里：
 * ① 本机构造里没装配 `JsSandboxHost`（单测、Hilt 图中无 binding、独立运行路径）；
 * ② 沙箱回调里的嵌套规则求值——嵌套求值刻意不带 JS（`EvalContext.withoutJs()`，
 *   见 `JsCallbackProxy` 关于「只有一个 runtime 且正攥在外层手上」的说明），
 *   否则就是双向死锁。
 * 与 [JsExecutionFailedException] 的分界始终是「有没有真跑过」：跑过而失败是后者。
 *
 * 消息串不是随便写的：本模块 `SandboxScriptJsTest` 用「需脚本沙箱执行器」这个片段断言嵌套求值
 * 确实被挡住了，改措辞会红那条测试。下游 `JsoupSourceReader` 反过来**不解析**这句话——它把
 * 类型化异常原样上抛，自己的测试直接用本类型锁「消息一字不改透出去」（层级未公开时只能自造
 * 一份同形 RuntimeException，见 ADR-0046）。
 */
class JsEvaluationPendingException(val rule: String) : ScriptRuleException(
    "该规则含可执行脚本段，需脚本沙箱执行器才能求值：${rule.take(80)}"
)

/** 规则用了本仓明确不支持/延后的能力（XPath、webJs、@cache: 等） */
class UnsupportedRuleFeatureException(val feature: String, val rule: String) : ScriptRuleException(
    "该规则使用了本项目不支持的能力（$feature）：${rule.take(80)}"
)

/**
 * 沙箱回了一帧读不出来的东西。
 *
 * 必须与「规则解不动」分开：帧坏掉意味着执行器与主进程的代码版本不一致（或 .so 与 Kotlin 不同步），
 * 是**本仓的接线缺陷**，不是作者写坏了规则。把两者混成一条消息，排查时会去改一条根本没错的规则。
 *
 * 与其它子类一样 public：`lib_book_common` 的取源/正文路径可按类型把「协议坏帧」与
 * 「规则解不动」分开处置，消息文本不是跨模块契约（正文路径当前仍原样上抛）。
 */
class SandboxProtocolException(message: String, cause: Throwable? = null) : ScriptRuleException(
    "沙箱响应无法解析：${message.take(120)}",
    cause,
)

/**
 * 沙箱此刻不可用（未绑定上、进程被杀、加载 .so 失败）。
 *
 * 与 [JsEvaluationPendingException] 的区别是时机性的：后者是「这条规则含 JS，而本仓没有 JS 能力」，
 * 前者是「能力在，这一次没跑起来」。只有前者会稳定复现，只有后者值得让用户去重导源。
 */
class SandboxUnavailableException(reason: String, cause: Throwable? = null) : ScriptRuleException(
    "脚本沙箱执行器当前不可用：${reason.take(120)}",
    cause,
)

/**
 * 脚本被执行了，但没有给出结果：超时、内存/栈越限、脚本自己抛异常、调了白名单外的能力。
 *
 * 与 [JsEvaluationPendingException] 的分界是「有没有真跑」：后者是能力缺失（本机构造里没沙箱），
 * 前者是能力在场而这一次失败。混起来的代价是把「这条源的脚本有 bug」说成「本项目不支持脚本」，
 * 用户会去等一个永远不会来的功能，而不是去换一条源。
 */
class JsExecutionFailedException(
    val status: JsStatus,
    /**
     * 失败原文（沙箱内核报错、或就地构造的原因句）。
     *
     * 判据读这一份，**不读 [message]**：消息是面向用户的拼装文案、且按 120 字截断
     * （ADR-0046「消息只做用户文案，不做跨模块判据」），拿它做匹配会在长属性名上把
     * 尾部的 `of undefined` 切掉，让该回落的回落不了。
     */
    val detail: String,
) : ScriptRuleException("脚本执行失败（${status.name}）：${detail.take(120)}")
