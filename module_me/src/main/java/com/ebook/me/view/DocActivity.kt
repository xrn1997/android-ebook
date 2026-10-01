package com.ebook.me.view

import android.os.Bundle
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.preview.AppPreview
import com.ebook.me.R
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity

/**
 * 协议文本页：用户协议 / 隐私政策共用一个纯静态展示页。
 *
 * 协议文案为静态文本（不随后端接口变化），以 res/raw 本地文本承载（可随语言限定符本地化），
 * 路由 extra [EXTRA_DOC_TYPE] 区分展示内容（0=用户协议 1=隐私政策），
 * 避免为两份同类文本各建一个页面。
 */
@Route(path = KeyCode.Me.DOC_PATH)
class DocActivity : BaseActivity() {

    /** 文档类型（intent extra），只读取一次供标题与内容共用 */
    private val docType: Int by lazy {
        intent.getIntExtra(EXTRA_DOC_TYPE, DOC_TYPE_USER_AGREEMENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // manifest label 固定，这里按类型覆盖标题（onTitleChanged 回退逻辑只在未设置时生效）
        toolbarTitle.value = getString(docTitleRes(docType))
    }

    @Composable
    override fun PageContent() {
        // 走 LocalResources 而非 LocalContext.current.resources：后者对 Configuration 变化不敏感，
        // 文案随语言限定符本地化时换语言不会重新取值（lint LocalContextResourcesRead）
        val resources = LocalResources.current
        // 文档文本为小型静态资源（约 1KB），一次性读取即可；resources 必须参与 key——
        // Configuration 变化只让本组合失效，remember 不带 key 仍会复用失效前缓存的旧文案
        val sections = remember(docType, resources) {
            parseDocSections(
                resources.openRawResource(docRawRes(docType))
                    .bufferedReader()
                    .use { it.readText() }
            )
        }
        DocScreen(sections = sections)
    }

    companion object {
        /** 路由 extra key：文档类型 */
        const val EXTRA_DOC_TYPE = "doc_type"

        /** 文档类型：用户协议 */
        const val DOC_TYPE_USER_AGREEMENT = 0

        /** 文档类型：隐私政策 */
        const val DOC_TYPE_PRIVACY_POLICY = 1
    }
}

/** 文档标题资源（供 toolbar 展示） */
@StringRes
internal fun docTitleRes(docType: Int): Int = when (docType) {
    DocActivity.DOC_TYPE_PRIVACY_POLICY -> R.string.about_privacy_policy
    else -> R.string.about_user_agreement
}

/** 文档正文资源（res/raw，随功能演进同步更新） */
@RawRes
private fun docRawRes(docType: Int): Int = when (docType) {
    DocActivity.DOC_TYPE_PRIVACY_POLICY -> R.raw.privacy_policy
    else -> R.raw.user_agreement
}

/** 文档章节：标题 + 正文 */
internal data class DocSection(val title: String, val body: String)

/**
 * 解析 res/raw 文档文本为章节列表。
 *
 * 格式约定：`# ` 开头的行是章节标题，其后到下一个 `# ` 之间的非空行是该章节正文
 * （多行自动以换行拼接）。纯函数、不依赖 Android 环境，便于单元测试与文案维护。
 */
internal fun parseDocSections(raw: String): List<DocSection> {
    val sections = mutableListOf<DocSection>()
    var title: String? = null
    val body = StringBuilder()
    raw.lineSequence().forEach { line ->
        if (line.startsWith("# ")) {
            title?.let { sections += DocSection(it, body.toString().trim()) }
            title = line.removePrefix("# ").trim()
            body.clear()
        } else if (line.isNotBlank()) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(line)
        }
    }
    title?.let { sections += DocSection(it, body.toString().trim()) }
    return sections
}

/**
 * 协议内容：标题 + 段落列表的滚动文本页。
 *
 * 页面左右留白取 [CommonUiTokens.pagePadding]：此前本页单独写 20dp，与本模块其余内容页
 * （设置/关于/许可等 16dp）无理由地差 4dp，现统一到同一令牌。
 */
@Composable
private fun DocScreen(sections: List<DocSection>) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CommonUiTokens.pagePadding)
        ) {
            sections.forEachIndexed { index, section ->
                if (index > 0) Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = section.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 预览：协议正文的三种「章节结构」。
 *
 * 三档是同一个 `forEachIndexed` 的三种输入，各自会静默画错的地方不同：
 * - **多章节**：首项不留顶部间距（`if (index > 0)` 那个条件写反就是整页往下挪一档），
 *   标题与正文的 8dp 只出现在标题下方；
 * - **章节只有标题**（正文为空）：`body` 为空串的 `Text` 仍占一行行高——这一档看不出「漏渲染」，
 *   但能看出空正文造成的空档是否可接受，那是 `res/raw` 里少写一行就会走到的形态；
 * - **空文档**：整页一片空白、连顶部留白都没有。这一档是 `parseDocSections` 认不出任何 `# ` 行时的
 *   结果（协议文本被改成别的记法、或 raw 资源被清空），不崩不报错，只有空白页。
 *
 * 正文一律经生产同一个 [parseDocSections] 切出来，而不是手搭 `DocSection` 列表：
 * 「`# ` 之外的空行被丢弃、多行正文以换行拼接」这两条格式约定只有真解析一遍才看得到，
 * 手搭列表等于把解析器和渲染分成两处各测一次。
 */
@Preview(showBackground = true)
@Composable
private fun DocScreenPreview(
    @PreviewParameter(DocSectionsProvider::class) sections: List<DocSection>,
) {
    AppPreview {
        DocScreen(sections = sections)
    }
}

/**
 * 三档章节结构（顺序即 [previewDocRaw] / [previewDocTitleOnlyRaw] / [previewDocEmptyRaw]）。
 *
 * `getDisplayName` 给中文形态名，预览面板上的图就按形态标注，不必对着缩略图猜是哪一档。
 */
private class DocSectionsProvider : PreviewParameterProvider<List<DocSection>> {
    override val values: Sequence<List<DocSection>>
        get() = sequenceOf(
            parseDocSections(previewDocRaw),
            parseDocSections(previewDocTitleOnlyRaw),
            parseDocSections(previewDocEmptyRaw),
        )

    override fun getDisplayName(index: Int): String = when (index) {
        0 -> "多章节"
        1 -> "只有标题"
        else -> "空文档"
    }
}

/**
 * 协议样例正文。
 *
 * 门面（`lib_book_common` 的 `PreviewSamples`）只放 ebook 域实体（书架条目、书源定义…），
 * 这段文本属于本页的展示样式，与 `res/raw/user_agreement` 同构，就地给（同 `module_find`
 * 书城预览自己给 `BookType` 样例的口径）。刻意保留「标题行 + 两行正文」与「标题 + 空行 + 正文」
 * 两种写法，用来核对解析器对空行的处理。
 */
private const val previewDocRaw = """
# 一、服务说明
本应用仅提供阅读工具，不存储、不分发任何书籍内容。
所有章节由用户自行导入的书源在设备上实时解析。

# 二、书源与第三方站点
书源由用户导入，访问第三方站点时不携带任何账号凭证。

# 三、账号与本地数据
阅读进度、书架与书源清单只保存在本机；注销账号不删除本地数据。
"""

/** 只有一行标题、没有正文的协议（`body` 解出来是空串） */
private const val previewDocTitleOnlyRaw = """
# 一、服务说明

# 二、书源与第三方站点
"""

/** 认不出任何章节的文本（没有 `# ` 开头的行）：解析结果为空列表 */
private const val previewDocEmptyRaw = """
这段文本没有按「# 标题」的约定书写，因此切不出任何章节。
"""
