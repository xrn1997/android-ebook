package com.ebook.find.booksource

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ScriptSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.common.ui.preview.SAMPLE_SOURCE_URL
import com.ebook.common.ui.preview.sampleNativeSource
import com.ebook.common.ui.preview.sampleScriptSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入管线的契约测试。
 *
 * 解析与落库从 ViewModel 抽出后，这里直接锁三件事：文本 → 条目（含出身/覆盖判定）、
 * 只写校验通过项、统计口径（成功含覆盖、失败含校验未通过与写库失败）。
 *
 * 用 Robolectric 的理由与 `BookSourceViewModelTest` 相同：解析路径落在 `org.json`
 * （Android SDK 自带），纯 JVM 下它是抛「Stub!」的桩实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceImportPlanTest {

    private fun planWith(fake: FakeBookSourceManager) =
        SourceImportPlan(fake, fake, fake)

    @Test
    fun `整段读不出书源对象时返回 Unreadable`() = runTest {
        val result = planWith(FakeBookSourceManager()).parse("这不是 JSON")

        assertEquals(SourceImportPlan.ParseResult.Unreadable, result)
    }

    @Test
    fun `数组与单对象都按顶层形态解析并逐条判出身`() = runTest {
        val fake = FakeBookSourceManager()
        val plan = planWith(fake)

        val single = plan.parse(nativeJson(name = "甲站", url = "$SAMPLE_SOURCE_URL/a"))
        assertTrue(single is SourceImportPlan.ParseResult.Parsed)
        val singleItem = (single as SourceImportPlan.ParseResult.Parsed).items.single()
        assertEquals(SourceFormat.NATIVE, singleItem.format)
        assertTrue(singleItem.isValid)

        val mixed = plan.parse(
            "[${nativeJson(name = "乙站", url = "$SAMPLE_SOURCE_URL/b")}, ${sampleScriptSource().rawJson}]"
        )
        assertTrue(mixed is SourceImportPlan.ParseResult.Parsed)
        val items = (mixed as SourceImportPlan.ParseResult.Parsed).items
        assertEquals(listOf(SourceFormat.NATIVE, SourceFormat.SCRIPT), items.map { it.format })
    }

    @Test
    fun `同 URL 已存在时标记将覆盖并带回被覆盖的出身`() = runTest {
        val fake = FakeBookSourceManager(
            initial = listOf(BookSourceRule(name = "旧原生源", url = sampleScriptSource().url))
        )

        val result = planWith(fake).parse(sampleScriptSource().rawJson)

        val item = (result as SourceImportPlan.ParseResult.Parsed).items.single()
        assertTrue("库里同 URL 有原生行，预览必须标将覆盖", item.willOverwrite)
        assertEquals(SourceFormat.NATIVE, item.overwriteFormat)
    }

    @Test
    fun `commit 只写校验通过项并统计覆盖与失败`() = runTest {
        val fake = FakeBookSourceManager()
        val plan = planWith(fake)
        val validNative = ImportPreviewItem(
            format = SourceFormat.NATIVE,
            nativeRule = sampleNativeSource().rule,
            validation = ValidationResult.Valid,
            willOverwrite = true,
            overwriteFormat = SourceFormat.NATIVE,
        )
        val invalid = ImportPreviewItem(
            format = SourceFormat.NATIVE,
            nativeRule = BookSourceRule(name = "", url = "bad"),
            validation = ValidationResult.Invalid(listOf(ValidationReason.NAME_BLANK)),
            willOverwrite = false,
        )
        val scriptDef = sampleScriptSource()
        val script = ImportPreviewItem(
            format = SourceFormat.SCRIPT,
            scriptRule = ScriptSourceRule(
                bookSourceName = scriptDef.name,
                bookSourceUrl = scriptDef.url,
            ),
            scriptRawJson = scriptDef.rawJson,
            validation = ValidationResult.Valid,
            willOverwrite = false,
        )

        val result = plan.commit(listOf(validNative, invalid, script))

        assertEquals(2, result.success)
        assertEquals(1, result.overwritten)
        assertEquals(1, result.failed)
        assertEquals(
            "校验不通过的条目不得进原生写面",
            listOf(sampleNativeSource().rule.url),
            fake.addedUrls,
        )
        assertEquals(
            "脚本书源走原文写面，且原样保留那份 JSON",
            listOf(sampleScriptSource().url),
            fake.scriptRawSources().keys.toList(),
        )
    }

    @Test
    fun `commit 把写库失败计入 failed 而不是中断整包`() = runTest {
        val fake = FakeBookSourceManager().apply {
            failingAddUrls = setOf("$SAMPLE_SOURCE_URL/fail")
        }
        val failing = ImportPreviewItem(
            format = SourceFormat.NATIVE,
            nativeRule = BookSourceRule(name = "失败源", url = "$SAMPLE_SOURCE_URL/fail"),
            validation = ValidationResult.Valid,
            willOverwrite = false,
        )
        val ok = ImportPreviewItem(
            format = SourceFormat.NATIVE,
            nativeRule = BookSourceRule(name = "成功源", url = "$SAMPLE_SOURCE_URL/ok"),
            validation = ValidationResult.Valid,
            willOverwrite = false,
        )

        val result = planWith(fake).commit(listOf(failing, ok))

        assertEquals(1, result.success)
        assertEquals(1, result.failed)
    }

    private fun nativeJson(name: String, url: String): String = """
        {
          "name": "$name",
          "url": "$url",
          "searchUrl": "$url/search?q={{key}}",
          "ruleSearch": { "list": ".item" },
          "ruleContent": { "content": ".content" }
        }
    """.trimIndent()
}
