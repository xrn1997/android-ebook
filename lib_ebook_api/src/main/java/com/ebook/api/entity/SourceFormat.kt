package com.ebook.api.entity

/**
 * 书源格式出身。两种格式在同一张 `book_source` 表中并存，按 [SourceFormat] 路由到各自的求值路径
 * （决策见 docs/adr/0029-script-book-source-import.md）。
 *
 * `raw` 就是 `book_source.format` 列的存储值（列以 TEXT 存，避免 TypeConverter 链）。
 * 之所以把列值做成枚举自带的属性、而不是从 Kotlin 标识符推导：枚举 `name` 恒为大写
 * （`"SCRIPT"`），而 `lib_ebook_db` 落定的列值是小写（实体默认值 `"native"`、
 * `MIGRATION_5_6` 的 `DEFAULT 'native'`，口径同 ADR-0029 决策 1）。用 `name` 当列值时写入侧与
 * 读取侧各持一份字面量，大小写一旦漂移不会有任何报错，只会让脚本书源静默按原生规则解析——
 * 内容错乱比崩溃更难查。读写同源于 `raw` 后，两侧不可能再分叉。
 */
enum class SourceFormat(val raw: String) {
    /** 原生规则书源：本仓自己的 [BookSourceRule] 声明式格式 */
    NATIVE("native"),

    /** 脚本书源：社区通用 JSON 格式，规则内嵌可执行脚本，原始 JSON 入库不翻译 */
    SCRIPT("script");

    companion object {
        /**
         * 把 `book_source.format` 列值还原成枚举，比对的是 `raw` 而不是枚举名（理由见类注释）。
         * 未知值一律按 [NATIVE] 处理：存量行与脏数据都走原生格式既有校验，报错信息对用户更有指向性。
         */
        fun fromRaw(raw: String): SourceFormat =
            if (raw == SCRIPT.raw) SCRIPT else NATIVE

        /**
         * 按 JSON 顶层键集判别格式。判别逻辑的唯一实现在 [SourceFormatDetector]，
         * 本函数只是就近的便捷入口，不重复那份判断。
         */
        fun detect(topLevelKeys: Set<String>): SourceFormat = SourceFormatDetector.detect(topLevelKeys)
    }
}

/**
 * 脚本书源格式判别：只认脚本书源的特征键。
 *
 * 两种格式都有 `searchUrl`/`ruleSearch` 同名键，**不能**用它们判别；`bookSourceUrl`
 * 是脚本书源格式的独有顶层键（原生格式的对应键是 `url`）。判别失败一律回落 NATIVE：
 * 未知 JSON 按原生格式走既有校验，报错信息对用户更有指向性。
 */
object SourceFormatDetector {
    private const val SCRIPT_MARKER_KEY = "bookSourceUrl"

    /**
     * 由 [topLevelKeys]（待导入 JSON 的顶层键集合）判别格式，本函数是判别逻辑的唯一实现
     * （[SourceFormat.detect] 委托至此）。空集合与任何不含特征键的键集都回落 [SourceFormat.NATIVE]。
     */
    fun detect(topLevelKeys: Set<String>): SourceFormat =
        if (SCRIPT_MARKER_KEY in topLevelKeys) SourceFormat.SCRIPT else SourceFormat.NATIVE
}

/**
 * 已落库书源的**求值输入与默认源载体**：Manager 按实体 `format` 列构造，交给解析器工厂分发；
 * 2e 起同时是默认源的载体——默认源经 `BookSourceManager.observeDefaultSource()` 订阅获得，
 * 不再有同步取默认源的出口。密封类型同时表达两种出身，
 * 各自带展示信息（[sourceUrl]/[displayName]），调用方不再需要在「规则字段」与「实体列」之间分格式取值。
 * 密封保证新增格式时编译器逼出所有分支。
 */
sealed interface SourceDefinition {

    /** 书源 URL：默认源路由、解析归属与缓存键都是它 */
    val sourceUrl: String

    /** 展示名：书城切换器等 UI 渲染用 */
    val displayName: String

    /** 原生规则书源：已解码的 [BookSourceRule]，展示信息随规则自带 */
    data class Native(val rule: BookSourceRule) : SourceDefinition {
        override val sourceUrl: String get() = rule.url
        override val displayName: String get() = rule.name
    }

    /**
     * 脚本书源：原始 JSON 文本，解释器按其自身语义求值。
     *
     * [name]/[url] 是**展示信息**，生产填充点只有 `BookSourceManagerImpl.toDefinition`
     * （从实体列取；脚本的 `rule_json` 是社区格式原文，本类型从不解析它），求值链路不读这两位。
     * 默认空串让既有构造点（解析器工厂、测试假件）不必逐处改。
     */
    data class Script(
        val rawJson: String,
        val name: String = "",
        val url: String = "",
    ) : SourceDefinition {
        override val sourceUrl: String get() = url
        override val displayName: String get() = name
    }
}
