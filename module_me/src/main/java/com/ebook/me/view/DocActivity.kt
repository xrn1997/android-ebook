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
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
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
                .padding(horizontal = 20.dp)
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
