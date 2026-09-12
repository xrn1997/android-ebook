package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Test

/** 规格 §2.5（链式段结构）与 §3.2（取值器全集）。 */
class ChainLinkTest {

    @Test
    fun `三段式类型名称位置`() {
        assertEquals(
            listOf(
                ChainLink(ChainKind.CLASS, "odd", IndexSelector.At(listOf(0))),
                ChainLink(ChainKind.TAG, "a", IndexSelector.At(listOf(0))),
                ChainLink(ChainKind.ACCESSOR, "text", null),
            ),
            ChainLink.parseChain("class.odd.0@tag.a.0@text"),
        )
    }

    @Test
    fun `文本定位类型`() {
        assertEquals(
            listOf(ChainLink(ChainKind.TEXT, "下一章", null)),
            ChainLink.parseChain("text.下一章"),
        )
    }

    @Test
    fun `children 不需要名称与位置`() {
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1)))),
            ChainLink.parseChain("children[1]"),
        )
    }

    @Test
    fun `裸索引段等价于 children`() {
        // 规格 §4：索引可作为段首规则，此时前面等价于 children
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1)))),
            ChainLink.parseChain("[1]"),
        )
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1)))),
            ChainLink.parseChain(".1"),
        )
    }

    @Test
    fun `排除式序号挂在名称后时名称与索引分开`() {
        // §2.5/§4：`class.x!0:2@text` 的 `!` 段是**位置**，`!` 之前才是类名。
        // 不剥这一层，选择器会拿 "x!0:2" 当类名去匹配——永远选不中，且零报错。
        assertEquals(
            listOf(
                ChainLink(ChainKind.CLASS, "x", IndexSelector.Excluding(listOf(0, 2))),
                ChainLink(ChainKind.ACCESSOR, "text", null),
            ),
            ChainLink.parseChain("class.x!0:2@text"),
        )
        // 整段就是一个排除式时，与 `[1]` 同理按 children 上的索引解（§4「可作为段首规则」）
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.Excluding(listOf(0, 2, -1)))),
            ChainLink.parseChain("!0:2:-1"),
        )
    }

    @Test
    fun `href 是取值器而任意属性名走 ATTRIBUTE`() {
        // §3.2：href/src 是「属性快捷名」，与 text/html 同档，故判 ACCESSOR 而非 ATTRIBUTE
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "a", null),
                ChainLink(ChainKind.ACCESSOR, "href", null),
            ),
            ChainLink.parseChain("tag.a@href"),
        )
        assertEquals(
            listOf(ChainLink(ChainKind.ATTRIBUTE, "_src", null)),
            ChainLink.parseChain("_src"),
        )
    }

    @Test
    fun `取值器全集都判为 ACCESSOR`() {
        val tokens = listOf("text", "ownText", "textNodes", "html", "all", "href", "src")
        tokens.forEach {
            assertEquals("取值器 $it 判错", listOf(ChainLink(ChainKind.ACCESSOR, it, null)), ChainLink.parseChain(it))
        }
    }

    @Test
    fun `末段是 text 点内容时按 TEXT 类型而非取值器`() {
        // `text` 既是取值器又是类型关键字，区分依据是有没有后续段：text.下一章 是类型
        assertEquals(
            listOf(ChainLink(ChainKind.TEXT, "下一章", null)),
            ChainLink.parseChain("text.下一章"),
        )
    }

    @Test
    fun `属性选择器内部的 at 被方括号保护`() {
        // `[@href]` 里的 @ 是 CSS 属性选择器的一部分，被括号深度保护；
        // 整个 `a[@href]` 原样留给选择器，由求值层交给 Jsoup——词法层不拆 CSS
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "a[@href]", null),
                ChainLink(ChainKind.ACCESSOR, "text", null),
            ),
            ChainLink.parseChain("tag.a[@href]@text"),
        )
    }

    @Test
    fun `属性选择器里的点不切段`() {
        // 段名可以是整条 CSS 属性选择器（语料实证 `tag.a[@href^="https://x.com/"]`），
        // 其中的点属于**属性值**：按 `.` 裸切会把名称腰斩，交给求值层的选择器永远选不中且零报错。
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "a[@href^=\"https://x.com/\"]", null),
                ChainLink(ChainKind.ACCESSOR, "href", null),
            ),
            ChainLink.parseChain("tag.a[@href^=\"https://x.com/\"]@href"),
        )
    }

    @Test
    fun `不在括号里的连续 at 各切一段`() {
        // 锁住已知边界：没有括号保护时 `@` 一律是段分隔。中段裸词 data-x 按 §2.5 是 CSS 选择器，
        // 末段裸词 y 在 AUTO 口径下是属性名取值器。属性名里真含 @ 的写法在本格式无转义可用（§11-4）。
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "a", null),
                ChainLink(ChainKind.CSS, "data-x", null),
                ChainLink(ChainKind.ATTRIBUTE, "y", null),
            ),
            ChainLink.parseChain("tag.a@data-x@y"),
        )
    }

    @Test
    fun `属性名带方括号选择器时末段仍是取值器`() {
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "meta[@name=\"x\"]", null),
                ChainLink(ChainKind.ATTRIBUTE, "content", null),
            ),
            ChainLink.parseChain("tag.meta[@name=\"x\"]@content"),
        )
    }

    @Test
    fun `选择器与取值器的切分`() {
        assertEquals(".articleDiv p" to "textNodes", SelectorAndAccessor.split(".articleDiv p@textNodes"))
        assertEquals("$._id" to null, SelectorAndAccessor.split("$._id"))
        assertEquals("[property=og:image]" to "content", SelectorAndAccessor.split("[property=og:image]@content"))
    }

    @Test
    fun `取值器识别不区分大小写`() {
        assertEquals(AccessorKind.TEXT, AccessorKind.of("text"))
        assertEquals(AccessorKind.TEXT, AccessorKind.of("TEXT"))
        assertEquals(AccessorKind.TEXT_NODES, AccessorKind.of("textNodes"))
        assertEquals(AccessorKind.ATTRIBUTE, AccessorKind.of("content"))
    }

    @Test
    fun `规格列为未证实的取值器不当作已知取值器`() {
        // §3.2 未证实清单：@textNode（单数）与二手写法冲突，本仓不实现、按属性处理并留给报告
        assertEquals(AccessorKind.ATTRIBUTE, AccessorKind.of("textNode"))
    }

    @Test
    fun `裸 CSS 段非末位判为 CSS 选择器`() {
        // 规格 §2.5：无前缀、非末段的裸词是 CSS 选择器，不是属性名筛选
        assertEquals(
            listOf(
                ChainLink(ChainKind.CSS, ".box", null),
                ChainLink(ChainKind.CSS, "ul", null),
                ChainLink(ChainKind.CSS, "li", null),
            ),
            ChainLink.parseChain(".box@ul@li", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `tag 点位置序号拆成 CSS 选择器加索引`() {
        // §2.5/§4：a.0 = CSS 选 a 再取第 0 个，与 a[0] 等价
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "a", IndexSelector.At(listOf(0)))),
            ChainLink.parseChain("a.0", ChainTail.SELECTOR),
        )
        // div.foo 的 foo 不是索引 → 整段是 CSS 复合选择器
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "div.foo", null)),
            ChainLink.parseChain("div.foo", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `方括号索引与类选择器并存`() {
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, ".autor2", IndexSelector.At(listOf(0)))),
            ChainLink.parseChain(".autor2[0]", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `规格 line220 例子 head 点 1 取 text`() {
        // §2.5 line220：head@.1@text —— 裸标签 head 是选择器、.1 是 children 上的索引、text 是取值器
        assertEquals(
            listOf(
                ChainLink(ChainKind.CSS, "head", null),
                ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1))),
                ChainLink(ChainKind.ACCESSOR, "text", null),
            ),
            ChainLink.parseChain("head@.1@text"),
        )
    }

    @Test
    fun `单值口径末段裸词仍是属性名取值器`() {
        // §3.2：AUTO（单值字段）口径下末段裸词 = @任意属性名，保住 option@value / meta@content
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "option", null), ChainLink(ChainKind.ATTRIBUTE, "value", null)),
            ChainLink.parseChain("option@value", ChainTail.AUTO),
        )
        // 同一规则在列表口径下末段是选择器
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "option", null), ChainLink(ChainKind.CSS, "value", null)),
            ChainLink.parseChain("option@value", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `已知取值器在列表口径下仍按取值器`() {
        // §3.2：作者显式写 @text 就尊重它，即便在 SELECTOR 口径
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "li", null), ChainLink(ChainKind.ACCESSOR, "text", null)),
            ChainLink.parseChain("li@text", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `空链返回空列表`() {
        assertEquals(emptyList<ChainLink>(), ChainLink.parseChain(""))
    }

    @Test
    fun `leading at 空段是恒等而非 children`() {
        // 格式语义：`@href` = 对当前节点取 href。空首段是恒等，不是 children（children 会作用到子元素→Miss）
        assertEquals(
            listOf(ChainLink(ChainKind.IDENTITY, "", null), ChainLink(ChainKind.ACCESSOR, "href", null)),
            ChainLink.parseChain("@href"),
        )
        // 仅方括号索引段仍按 children 上的索引（与 `[1]`/`children[1]` 同解），不能被恒等改写
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1)))),
            ChainLink.parseChain("[1]"),
        )
    }
}
