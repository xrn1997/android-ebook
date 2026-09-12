package com.ebook.source.script

/** 链式段的角色（规格 §2.5 第一段 + §3.2 取值器）。 */
internal enum class ChainKind { CLASS, ID, TAG, TEXT, CHILDREN, ACCESSOR, ATTRIBUTE, CSS, IDENTITY }

/**
 * 链尾的判读口径（规格 §3.2「末段歧义的消解」）。
 *
 * 一个裸词末段既可能是 CSS 标签选择器（列表字段要节点）、又可能是 `@任意属性名` 取值器
 * （单值字段要文本），无法从规则串本身区分，故由调用方按字段口径传入。
 */
internal enum class ChainTail {
    /** 单值字段口径：末段裸词按 `@任意属性名` 取值器（缺省，与历史行为一致） */
    AUTO,

    /** 列表字段口径（bookList/chapterList/explore）：末段裸词按 CSS 选择器，结果是节点集 */
    SELECTOR,
}

/**
 * 链式规则的一段：`类型 . 名称 . 位置`，或一个取值器/属性名。
 *
 * [index] 合并了两种写法：位置段（`class.odd.0` 里的 `0`）与方括号索引
 * （`tag.div[2:4]`），因为二者语义相同（§4 明说索引也能作为段首规则出现）。
 * 分成两个字段会让求值层去猜「同时出现时谁优先」，而文档没有答案。
 */
internal data class ChainLink(
    val kind: ChainKind,
    val name: String,
    val index: IndexSelector?,
) {

    companion object {

        private val TYPES = mapOf(
            "class" to ChainKind.CLASS,
            "id" to ChainKind.ID,
            "tag" to ChainKind.TAG,
            "text" to ChainKind.TEXT,
            "children" to ChainKind.CHILDREN,
        )

        /**
         * 按深度 0 的 `@` 切段并逐段结构化。
         *
         * 「末段才可能是取值器」这条判据是必须的：`text.下一章` 里的 `text` 是类型关键字，
         * 而链尾的 `text` 是取值器，同一个词两种身份，位置是唯一可用的区分依据。
         */
        fun parseChain(body: String, tail: ChainTail = ChainTail.AUTO): List<ChainLink> {
            if (body.isBlank()) return emptyList()
            val d = RuleScanner.depths(body)
            val hits = RuleScanner.topLevelOf(body, d, "@")
            val segments = ArrayList<String>(hits.size + 1)
            var cursor = 0
            for (h in hits) {
                segments += body.substring(cursor, h)
                cursor = h + 1
            }
            segments += body.substring(cursor)
            return segments.mapIndexed { i, raw -> link(raw, isLast = i == segments.size - 1, tail = tail) }
        }

        private fun link(raw: String, isLast: Boolean, tail: ChainTail): ChainLink {
            var text = raw.trim()
            var index: IndexSelector? = null
            // 尾随方括号索引先摘走（`tag.div[2:4]`）。用 lastIndexOf 且接受下标 0：
            // §4 允许索引作为段首规则（此时等价于 children），整段就是 `[1]` 也必须摘得出索引。
            if (text.endsWith("]")) {
                val open = text.lastIndexOf('[')
                if (open >= 0) {
                    IndexSelector.parse(text.substring(open))?.let {
                        index = it
                        text = text.substring(0, open)
                    }
                }
            }
            // 空文本段有两种来源：① 仅方括号索引（`[1]` 剥括号后变空、index!=null）→ children 上的索引；
            // ② 真正的空段（leading `@` 切出的 `""`、index==null）→ 恒等（当前节点），
            //    格式语义里 `@href` 是对当前节点取 href，不是对子元素取。
            if (text.isEmpty()) {
                return if (index != null) ChainLink(ChainKind.CHILDREN, "", index)
                else ChainLink(ChainKind.IDENTITY, "", null)
            }
            // 裸位置（`0`、`-1`、`.1`、`!0:2`）等价于 children 上的索引
            val bare = text.removePrefix(".")
            val bareLooksLikeIndex = bare.isNotEmpty() && (bare.first().isDigitOrSign() || bare.first() == '!')
            if (index == null && bareLooksLikeIndex) {
                IndexSelector.parse(bare)?.let { return ChainLink(ChainKind.CHILDREN, "", it) }
            }
            // 取值器判定必须**先于**类型表：`text` 既是类型关键字（§2.5「按文本内容定位」）
            // 又是取值器（§3.2），而 §2.5 同时明说「最后一段是取值器」——末段位置上的 `text`
            // 只可能是取值器。先查类型表会把 `class.odd.0@tag.a.0@text` 的链尾解成
            // 一个没有名字的 TEXT 段（不报错，但取不到任何文本）。
            if (isLast && AccessorKind.of(text) != AccessorKind.ATTRIBUTE) {
                return ChainLink(ChainKind.ACCESSOR, text, index)
            }
            val parts = topLevelParts(text)
            TYPES[parts[0].lowercase()]?.let { kind ->
                // children 不需要名称与位置；其余类型第二段是名称、第三段是位置
                var name = if (kind == ChainKind.CHILDREN) "" else (parts.getOrNull(1)?.trim() ?: "")
                val positional = parts.getOrNull(if (kind == ChainKind.CHILDREN) 1 else 2)?.trim()
                if (index == null && positional != null) index = IndexSelector.parse(positional)
                // §2.5/§4 的排除式直接写在名称之后（原文示例 `class.x!0:2@text`）：
                // `!` 之前才是类名，`!` 起的那一段按索引语法解。留着 "x!0:2" 当类名不会报错，
                // 只会永远选不中——本层要防的正是这种「看着正常、实则空到底」的形态。
                if (index == null) {
                    val bang = name.indexOf('!')
                    if (bang >= 0) {
                        IndexSelector.parse(name.substring(bang))?.let {
                            index = it
                            name = name.substring(0, bang)
                        }
                    }
                }
                return ChainLink(kind, name, index)
            }
            // 裸段（无上游前缀）。末段在单值口径下是 `@任意属性名` 取值器（§3.2），其余一律 CSS 选择器（§2.5）。
            if (isLast && tail == ChainTail.AUTO) {
                return if (index == null && text.toIntOrNull() == null) {
                    ChainLink(ChainKind.ATTRIBUTE, text, null)
                } else {
                    ChainLink(ChainKind.CHILDREN, text, index)
                }
            }
            // CSS 选择器段：拆尾随上游位置序号（a.0 → 选 a 取 0），再拆排除式（a!0:2）
            var css = text
            if (index == null) {
                val (base, pos) = splitTrailingPositional(css)
                css = base
                index = pos
            }
            if (index == null) {
                val bang = css.indexOf('!')
                if (bang >= 0) {
                    IndexSelector.parse(css.substring(bang))?.let {
                        index = it
                        css = css.substring(0, bang)
                    }
                }
            }
            return ChainLink(ChainKind.CSS, css, index)
        }

        /**
         * 拆裸 CSS 段尾随的上游位置序号（§2.5/§4）：`a.0` → ("a", At(0))；`div.foo` 的 `foo`
         * 不是索引 → 原样 ("div.foo", null)；`.box` 前导点不拆 → (".box", null)。
         * 按**最后一个顶层 `.`** 切；`[...]`/`{...}` 内的点由 `RuleScanner.depths` 挡在深度域外，
         * `(...)` 内的点（如 `:not(.x)`）不在其深度域内、会被看见，但此时尾段是 `x)` 而非纯数字，
         * 索引解析失败同样不拆——两道防线，不靠单点。
         */
        private fun splitTrailingPositional(text: String): Pair<String, IndexSelector?> {
            val dots = RuleScanner.topLevelOf(text, RuleScanner.depths(text), ".")
            val lastDot = dots.lastOrNull() ?: return text to null
            val base = text.substring(0, lastDot)
            if (base.isEmpty()) return text to null
            val idx = IndexSelector.parse(text.substring(lastDot + 1)) ?: return text to null
            return base to idx
        }

        private fun Char.isDigitOrSign() = this == '-' || this == '+' || isDigit()

        /**
         * 按深度 0 的 `.` 切段（不用 `String.split`）。
         *
         * 段名可以是整条 CSS 属性选择器（语料实证形态 `tag.a[@href^="https://x.com/"]`），
         * 其中的点是**属性值的一部分**；裸切会把名称腰斩成两段，
         * 交给求值层的选择器永远匹配不上，而这一切零报错。
         */
        private fun topLevelParts(text: String): List<String> {
            val hits = RuleScanner.topLevelOf(text, RuleScanner.depths(text), ".")
            if (hits.isEmpty()) return listOf(text)
            val out = ArrayList<String>(hits.size + 1)
            var cursor = 0
            for (h in hits) {
                out += text.substring(cursor, h)
                cursor = h + 1
            }
            out += text.substring(cursor)
            return out
        }
    }
}

/**
 * 取值器（规格 §3.2 已核实全集）。未列入者一律按属性名处理，不猜。
 *
 * [ATTRIBUTE] 不是一个取值器，而是「本词不在 §3.2 全集里」的兜底结果，
 * 因此它的 [token] 为空串、[of] 也把它排除在匹配之外。§3.2 末尾列出的未证实写法
 * （`@textNode` 单数、`@innerHtml`、`@size`…）因此全部落到 [ATTRIBUTE]，
 * 由导入报告如实标出，而不是悄悄当成已知能力求值。
 */
internal enum class AccessorKind(val token: String) {
    TEXT("text"),
    OWN_TEXT("ownText"),
    TEXT_NODES("textNodes"),
    HTML("html"),
    ALL("all"),

    /** 属性快捷名：§3.2 把 href/src 与 text/html 列在同一张表里，故与取值器同档 */
    HREF("href"),
    SRC("src"),
    ATTRIBUTE(""),
    ;

    companion object {
        /** 已知取值器按不区分大小写匹配（大小写在上游文档两种写法都出现，本仓统一不敏感） */
        fun of(token: String): AccessorKind {
            val t = token.trim()
            return entries.firstOrNull { it != ATTRIBUTE && it.token.equals(t, ignoreCase = true) } ?: ATTRIBUTE
        }
    }
}

/**
 * 选择器型模式（CSS / JSONPath / XPath）的「选择器 + 尾随取值器」切分。
 *
 * 与默认链式模式的分工不同：这里只找**最后一个**深度 0 的 `@`，因为选择器内部合法地
 * 含有 `@`（XPath 的 `@text()`、属性值里的 `@`），而尾随取值器恒在最右。
 */
internal object SelectorAndAccessor {
    fun split(body: String): Pair<String, String?> {
        val d = RuleScanner.depths(body)
        val hits = RuleScanner.topLevelOf(body, d, "@")
        val last = hits.lastOrNull() ?: return body to null
        val token = body.substring(last + 1)
        // 末段不是取值器（如 `//img/@_src` 里的 `_src` 是属性）时仍按「选择器 + 属性」切
        return body.substring(0, last) to token.ifBlank { null }
    }
}
