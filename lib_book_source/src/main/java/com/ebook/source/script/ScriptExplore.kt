package com.ebook.source.script

/**
 * 脚本书源发现页条目的公开只读面：原始 JSON → 分类条目（标题 + URL 规则串）。
 *
 * **纯解析**：零网络、零 JS、不触发任何取文——书城分类胶囊只是「这源有哪些入口」的清单，
 * 渲染一个胶囊不允许付出一次请求。坏 JSON 抛类型化 [ScriptRuleParseException]
 * （与求值链路同一句话术），调用方按「这条源解不动」处置；未配 `exploreUrl` 返回空列表
 * （配置形态而非错误，与 `ScriptBookParser.fetchLibraryData` 同口径）。
 *
 * 为什么要这一层：[ExploreUrlFormat] 与 [ScriptRuleSet] 都是 internal，跨模块
 * （lib_book_common 的 Manager 要做「按格式路由的分类条目」读面）够不着——这里只递出
 * 「条目清单」这一件事，不暴露任何求值内部。别为了省这一层把求值内部改成 public。
 */
data class ScriptExploreEntry(val title: String, val urlRule: String)

object ScriptExplore {

    fun entries(rawJson: String): List<ScriptExploreEntry> {
        val exploreUrl = ScriptRuleSet.load(rawJson).exploreUrl ?: return emptyList()
        return ExploreUrlFormat.split(exploreUrl).map { ScriptExploreEntry(it.title, it.urlRule) }
    }
}
