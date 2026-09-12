package com.ebook.common.analyze.source

import com.ebook.api.entity.ScriptSourceRule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 落库/读库用的紧凑 JSON 解码器（`lib_book_common` 内部共用一份）。
 *
 * **两种出身共用这一个实例**：原生规则的 `rule_json` 编解码（`BookSourceManagerImpl` 里
 * `toItem` / `toEntity` / `importFromJson` 三处都读它，`toRule` 经 `toItem`
 * 间接走同一条路径）与脚本书源的原文解码是同一份配置（对外导出的美化 JSON 另有实例，
 * 那份只写不读、不参与接受集）。全模块只此一个存储侧实例，原因不是省事而是**接受集必须唯一**：
 * `ignoreUnknownKeys` 决定了解码器肯不肯放过社区 JSON 里那几十个本仓模型未声明的键，
 * 一旦这里用一份、别处再开一份（哪怕只差一个开关），就会出现「导入预览判它能进、落库判它解不出」
 * 这类两边各按自己那份配置说话的故障。
 *
 * 规则字段会扩，`ignoreUnknownKeys` 保证老行在新代码下仍可解。
 */
internal val storageJson = Json { ignoreUnknownKeys = true }

/**
 * 脚本书源原文 → [ScriptSourceRule] 的解码面（导入预览用）。
 *
 * 本文件旧名 `ScriptSourceJson`、对象旧名同名，名不副实：文件真正的主体 [storageJson] 同时服务
 * 原生与脚本两种 `rule_json` 的解码，只按「脚本」命名会让后来人以为原生解码另有实例、
 * 于是顺手再开一份——那正好破坏上面那条唯一性。改成 `SourceStorageJson` 说的是同一件事的另一面：
 * 这里是**书源存储侧 JSON 的唯一定义处**。
 *
 * 与 [BookSourceManager.addScriptSource] 共用 [storageJson]，因此**「预览判得出字段」与
 * 「落库存得下来」是同一件事**：预览把一个对象标成可导入，确认时就不会在解码这一步被撞回来。
 * 这也是本函数放在 `lib_book_common`、而不是让调用模块自己开一个 `Json` 实例的理由——
 * 各模块各解一遍，就是给这个接受集添第二个事实源。
 *
 * 为什么只解得出这几个键：脚本书源格式的事实源是**原始 JSON 整块**（规则块、脚本、JSONPath 表达式
 * 都不在最小模型里，见 [ScriptSourceRule] 的类注释）。本函数不承担「整条源能不能用」的判断，
 * 只回答「顶层这几个字段读不读得出来」。
 */
object SourceStorageJson {

    /**
     * 解 [rawJson] 的顶层字段；解不出返回 null（不是 JSON 对象，或顶层键装着读不出值的值——
     * `bookSourceType` 装了非数值、声明过的键写着 `null`）。
     *
     * **带引号的数字（`"0"`）不算失败**：解码器按 token 文本读 Int，引号不是类型不符。
     * 所以「解得出」不等于「每个字段都合规」，这里要保证的是另一件事——
     * 预览与落库对同一份原文**必然同结论**（用的就是这一个实例）。
     *
     * 只捕 [SerializationException] 而不是 `Exception`：后者会把协程取消一并咽掉
     * （本函数由 ViewModel 的挂起调用点使用），与 `BookSourceViewModel.splitJsonObjects` 同一取舍。
     * 失败**不记日志**：调用方（导入预览）要把这一条翻成用户看得懂的原因行，
     * 这里再记一遍只会让同一件事在两处各说一次。
     */
    fun parseOrNull(rawJson: String): ScriptSourceRule? = try {
        storageJson.decodeFromString<ScriptSourceRule>(rawJson)
    } catch (e: SerializationException) {
        null
    }
}
