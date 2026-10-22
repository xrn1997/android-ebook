package com.ebook.common.analyze.source

import com.ebook.source.script.JsEvaluationPendingException
import com.ebook.source.script.JsExecutionFailedException
import com.ebook.source.script.RuleSyntaxException
import com.ebook.source.script.SandboxProtocolException
import com.ebook.source.script.SandboxUnavailableException
import com.ebook.source.script.ScriptRuleException
import com.ebook.source.script.ScriptRuleParseException
import com.ebook.source.script.UnsupportedRuleFeatureException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本失败层级必须对 `lib_book_common` 可见，且基类接得住 ADR 点名的每一种失败。
 *
 * 取源/正文路径**可以**按类型把「规则解不动」「JS 未装配」「执行失败」分开处置；类型若是
 * `internal`，跨模块只能退化成比消息字符串——这正是 ADR-0046 要消除的形态。
 *
 * **可见性只由编译锁定**：本模块的测试源集不是 `lib_book_source` 的 friend module，任何一个类型
 * 回退 `internal` 都会让上面这些 import 直接编译失败。反射读 `Modifier.isPublic` 在这里是空断言
 * （Kotlin 把 `internal` 类也编成字节码 public，实测 `internal class ScriptPageChain` 的 class 文件
 * 写作 `public final class`），所以本用例不再用它断言可见性。
 */
class ScriptRuleFailureVisibilityTest {

    /**
     * 按基类收口是本模块唯一可行的处置方式（见 ADR-0046 决策 2：类型化失败原样上抛、不折叠成空结果），
     * 所以「catch 基类 = 接住全部」必须钉住；同时这份清单让 8 个类型都被对面模块引用一次。
     */
    @Test
    fun `脚本失败层级的每一种都能被基类接住`() {
        val subtypes = listOf(
            ScriptRuleParseException::class.java,
            RuleSyntaxException::class.java,
            JsEvaluationPendingException::class.java,
            UnsupportedRuleFeatureException::class.java,
            SandboxProtocolException::class.java,
            SandboxUnavailableException::class.java,
            JsExecutionFailedException::class.java,
        )
        subtypes.forEach { type ->
            assertTrue(
                "${type.simpleName} 必须挂在 ScriptRuleException 下，否则按基类收口会漏掉它",
                ScriptRuleException::class.java.isAssignableFrom(type),
            )
        }
    }
}
