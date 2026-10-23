package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule

/**
 * 书源编解码面：社区 JSON 与 [BookSourceRule] 的互转，**纯函数、不落库、不改变任何状态**。
 *
 * 导入只解出规则对象，落库与否由调用方交给 [SourceAdmin.addSource]；导出与导入共用同一套字段口径，
 * 保证「导出再导入」的往返语义。解码器与落库共用 [SourceStorageJson] 里那一个实例，
 * 别在这里另开一个 `Json`——接受集一旦分裂，就会出现「预览能导、落库解不出」的故障。
 */
interface SourceCodec {

    /**
     * 把一段书源 JSON 解成 [BookSourceRule]（纯函数，**不落库、不改变任何状态**）。
     *
     * 解析失败或关键字段缺失（`name`/`url` 为空）返回 null。调用方拿到结果后自行决定是否
     * 交给 [SourceAdmin.addSource] 落库——导入 UI 通常要先做预览与校验。
     */
    fun importFromJson(jsonStr: String): BookSourceRule?

    /** 把单个书源规则导出成美化 JSON（配 [importFromJson] 用于社区书源交换） */
    fun exportToJson(rule: BookSourceRule): String
}
