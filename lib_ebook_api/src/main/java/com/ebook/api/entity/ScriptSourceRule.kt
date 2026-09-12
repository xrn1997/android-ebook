package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 脚本书源最小模型：仅覆盖导入校验与落库所需的顶层字段。
 * 解码一律开 `ignoreUnknownKeys`——未声明的键（规则段等）原样保留在原始 JSON 里，
 * Plan 2 的解释器直接消费原始 JSON，不经本模型。
 */
@Serializable
data class ScriptSourceRule(
    /** 书源名称（脚本书源格式键名 `bookSourceName`） */
    val bookSourceName: String = "",
    /** 书源 URL（主键语义，同原生格式的 `url`） */
    val bookSourceUrl: String = "",
    /** 书源分组 */
    val bookSourceGroup: String = "",
    /**
     * 书源内容类型：`0` 文本、`1` 音频、`2` 图片（漫画）、`3` 文件/下载站、`4` 视频。
     * 非 0 一律给「非文本源」导入警示——本项目是文字阅读器，判断只看「是不是 0」，
     * 不逐类区分（音频/图片/视频的处置结果相同：明示可能无法正常阅读）。
     */
    val bookSourceType: Int = 0,
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 登录入口（非空表示该源依赖登录流程，v1 不支持，导入警示） */
    val loginUrl: String = "",
)
