package com.ebook.source.script

/**
 * 索引方言（规格 §4）：`[0]` `[-1]` `[1,3]` `[!1,3]` `[2:4]` `[1:9:2]` `[:3]` `[2:]` `[:]` `[-1:0]`，
 * 以及位置段里的裸写法 `0` `-1` `!0:2:-1`。
 *
 * **区间是闭区间**（规格 §4 已订判据）：文档同时说「start 为 0 时可省略、end 为 -1 时可省略」
 * 与「`[:]` 即全取」，只有闭区间能让这两句同时成立。半开会让「省略 end」变成丢掉最后一个元素。
 *
 * 解析阶段**不换算负索引**：负数是「从尾数」的语义，只有拿到列表长度才能换算，
 * 而词法层不知道长度。把换算留在 [selectIndices]，词法与求值的分工才不会在越界处理上互相猜。
 */
internal sealed interface IndexSelector {

    object All : IndexSelector

    data class At(val indexes: List<Int>) : IndexSelector

    data class Excluding(val indexes: List<Int>) : IndexSelector

    /** [start] / [end] / [step] 为 null 表示该端点省略；闭区间；start > end 时反向遍历 */
    data class Slice(val start: Int?, val end: Int?, val step: Int?) : IndexSelector

    companion object {

        /** 解析一段索引文本（可带或不带方括号）；不是索引形态则返回 null，交回调用方按名称处理 */
        fun parse(text: String): IndexSelector? {
            var body = text.trim()
            // 方括号形态要单独记一笔：`[]` 与 `[:]` 一样是「不加任何限制」，按全取解（§4）；
            // 而没有任何括号、内容为空的裸文本不是索引形态，仍须返回 null 交回调用方按名称处理。
            val bracketed = body.startsWith("[") && body.endsWith("]")
            if (bracketed) body = body.substring(1, body.length - 1).trim()
            if (body.isEmpty()) return if (bracketed) All else null
            if (body == ":") return All
            if (body.startsWith("!")) {
                // 排除式：方括号形态用逗号（[!1,3]）、位置段形态用冒号（!0:2:-1），两种都收
                val nums = body.drop(1).split(',', ':').map { it.trim() }
                if (nums.isEmpty() || nums.any { it.toIntOrNull() == null }) return null
                return Excluding(nums.map { it.toInt() })
            }
            if (body.contains(':')) {
                val parts = body.split(':')
                if (parts.size > 3) return null
                val v = parts.map { it.trim().let { t -> if (t.isEmpty()) null else t.toIntOrNull() } }
                if (parts.filter { it.trim().isNotEmpty() }.any { it.trim().toIntOrNull() == null }) return null
                return Slice(v.getOrNull(0), v.getOrNull(1), v.getOrNull(2))
            }
            val nums = body.split(',').map { it.trim() }
            if (nums.any { it.toIntOrNull() == null }) return null
            return At(nums.map { it.toInt() })
        }
    }
}

/**
 * 按规格 §4 + 本仓规定裁剪：**逐项过滤、端点按集合边界裁剪、越界的单个索引跳过、
 * 全部被过滤即返回空列表**。任何情况都不抛——越界在语料里是常态（`[2:999]`），
 * 抛出去会让一条写歪了下标的规则毁掉整本书的目录。
 *
 * 之所以是文件级扩展而不是 `IndexSelector` 的 companion 扩展：求值层要在
 * `List<Element>`、`List<String>`、`List<JsonElement>` 三种列表上反复调用，
 * companion 版本要求调用方套一层 `IndexSelector.run { ... }`，那层作用域技巧
 * 对读者是纯噪音。
 */
internal fun <T> List<T>.selectIndices(sel: IndexSelector): List<T> = when (sel) {
    IndexSelector.All -> this
    is IndexSelector.At -> sel.indexes.mapNotNull { resolveIndex(it, size) }.map { this[it] }
    is IndexSelector.Excluding -> {
        val drop = sel.indexes.mapNotNull { resolveIndex(it, size) }.toSet()
        filterIndexed { i, _ -> i !in drop }
    }
    is IndexSelector.Slice -> sliceIndices(sel.start, sel.end, sel.step, size).map { this[it] }
}

private fun resolveIndex(i: Int, n: Int): Int? = (if (i < 0) n + i else i).takeIf { it in 0 until n }

/** 闭区间端点换算：返回**待取的下标序列**（反向时降序），越界的单个索引直接跳过、端点按边界裁剪 */
private fun sliceIndices(start: Int?, end: Int?, step: Int?, n: Int): List<Int> {
    if (n == 0) return emptyList()
    val a = start?.let { if (it < 0) (n + it).coerceAtLeast(0) else it.coerceAtMost(n - 1) } ?: 0
    // 省略 end 按「取到末尾」解（闭区间语义），而不是 Python 的「到 -1 之前」
    val b = end?.let { if (it < 0) (n + it).coerceAtLeast(0) else it.coerceAtMost(n - 1) } ?: (n - 1)
    val stride = (step ?: 1).let { if (it == 0) 1 else kotlin.math.abs(it) }
    // start > end 本身就是反序信号（[-1:0] 的形态），与 step 的正负无关
    val out = ArrayList<Int>()
    if (a <= b) { var i = a; while (i <= b) { out += i; i += stride } }
    else { var i = a; while (i >= b) { out += i; i -= stride } }
    return out
}
