package com.ebook.source.script

import kotlinx.serialization.json.Json

/**
 * 本包共用的宽容 JSON 实例：`ignoreUnknownKeys` 呼应「原始入库零翻译」——格式允许
 * 未知键存在，把不认识的键当错误会让带新兴键的源整条不可用；`isLenient` 容忍
 * 非严格 JSON 的真实语料。[JsonPathBackend]/[ScriptRuleSet]/[ScriptUrlOption]/
 * [ExploreUrlFormat] 都从这里取，配置一致就别各造各的。
 */
internal val ScriptJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }
