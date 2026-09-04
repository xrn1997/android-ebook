package com.ebook.me.util

/**
 * App 版本号：把发布 tag（`V1.2.0` / `V1.2.3abcd` / `1.2`）解析成可逐段比较的结构。
 *
 * 版本规约：`[Vv]?{数字段}(.{数字段})*{可选尾缀}`，其中：
 * - 前缀 `V`/`v` 可省（远端 tag 习惯带 `V`，本地 `versionName` 习惯不带，两侧都要能比）
 * - **数字段个数不限**，比较时按位置逐段（major → minor → patch → 第四段…）比，
 *   段数不足一侧按 0 补齐。刻意不写死三段：三段式实现只能取「最后一段」当 patch，
 *   遇到 `1.2.3.4` 会把第三段 `3` 丢掉、把 `4` 当 patch，于是 `1.2.3.9` 被误判成
 *   `1.2.9 > 1.2.4`。逐段列表比较从根上排除这一类错位
 * - 尾缀只允许挂在**最后一段**数字之后（`1.2beta`、`1.2.3rc1`）；比较时数字段优先，
 *   数字段全等才比尾缀，空尾缀 < 任意非空尾缀（字典序）
 *
 * 核心契约是数值优先逐段比较：`V1.10.0 > V1.9.0`，不把版本串当浮点数比。
 *
 * 已知取舍：`1.2.0 < 1.2.0alpha` 与语义化版本里「预发布早于正式版」的约定相反。这里按
 * 字典序处理而不猜语义，因为两平台的 `latest` 端点本身已排除 prerelease，能作为 latest
 * 出现的带尾缀 tag 就是「同一版本号的后一轮发布」（如 `V1.1.7alpha` 补发）；真要支持
 * 预发布序，需在发布流程里约定 tag 模板，不在比较层做推断。
 */
data class AppVersion(
    val numbers: List<Int>,
    val suffix: String = "",
) : Comparable<AppVersion> {

    /**
     * 逐段比较：先比数字段（缺失段按 0 计），全等再比字母尾缀。
     */
    override fun compareTo(other: AppVersion): Int {
        val segmentCount = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until segmentCount) {
            val diff = numbers.getOrElse(index) { 0 } - other.numbers.getOrElse(index) { 0 }
            if (diff != 0) return diff
        }
        return suffix.compareTo(other.suffix)
    }

    companion object {
        /**
         * 最后一段：必须以数字开头（可以是多位），后面允许跟一串字母数字尾缀。
         * 强制「以数字开头」是为了让 `1.x`、`abc` 这类不可信串判解析失败，而不是猜个版本号。
         */
        private val LAST_SEGMENT = Regex("^(\\d+)(.*)$")

        /**
         * 从 tag / versionName 解析为 [AppVersion]；无法解析返回 null。
         *
         * 兼容形态：`1` → [1]；`1.2` → [1,2]；`V1.2.0` → [1,2,0]；
         * `V1.2.3abcd` → [1,2,3] + 尾缀 `abcd`；`V1.2beta` → [1,2] + 尾缀 `beta`。
         *
         * 非最后段必须是纯数字，否则整串判不可解析返回 null——两段的尾缀形态
         * （`1.2beta`）在此前是被拒绝的，会让调用方把「解析失败」误当成「已是最新版本」。
         */
        fun parse(raw: String): AppVersion? {
            val text = raw.trim().removePrefix("V").removePrefix("v").trim()
            if (text.isEmpty()) return null
            val segments = text.split(".")
            val numbers = ArrayList<Int>(segments.size)
            var suffix = ""
            segments.forEachIndexed { index, segment ->
                if (index == segments.lastIndex) {
                    val matched = LAST_SEGMENT.matchEntire(segment) ?: return null
                    numbers += matched.groupValues[1].toInt()
                    suffix = matched.groupValues[2]
                } else {
                    numbers += segment.toIntOrNull() ?: return null
                }
            }
            return AppVersion(numbers = numbers, suffix = suffix)
        }
    }
}

/**
 * 把远端 tag 归一化成展示用版本号：去掉可选的 `V`/`v` 前缀。
 *
 * 存在的原因：字符串资源里已自带小写 `v`（「发现新版本 v%1$s」），直接塞 `V1.2.0`
 * 会渲染成「发现新版本 vV1.2.0」。展示层统一先归一化，前缀只由资源文案决定一次。
 */
fun normalizeVersionTag(raw: String): String =
    raw.trim().removePrefix("V").removePrefix("v")

/**
 * 本版本是否落后于 [other]，即 [other] 更大 → 需要提示用户更新。
 */
fun AppVersion.isOlderThan(other: AppVersion): Boolean = this < other
