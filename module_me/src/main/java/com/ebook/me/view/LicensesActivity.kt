package com.ebook.me.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.SectionLabel
import com.ebook.me.R
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity

/**
 * 开源许可页：静态列表展示本项目依赖的主要开源库及其许可证。
 *
 * 项目依赖以 Apache 2.0 为主，列表与
 * gradle/libs.versions.toml 中的主要依赖保持同步——新增核心依赖时更新此处。
 * 库名与许可证为专有名词，不做本地化，保留在代码中；页面标题与说明文案走字符串资源。
 */
@Route(path = KeyCode.Me.LICENSES_PATH)
class LicensesActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.licenses_title)
    }

    @Composable
    override fun PageContent() {
        LicensesScreen(licenses = LICENSES)
    }

    companion object {
        /** 主要开源依赖（与 libs.versions.toml 对应，按类别排列） */
        private val LICENSES = listOf(
            // 语言与异步
            LicenseItem("Kotlin / Kotlin Coroutines", "Apache License 2.0"),
            LicenseItem("Jetpack Compose", "Apache License 2.0"),
            LicenseItem("Material Components for Android", "Apache License 2.0"),
            // 架构与依赖注入
            LicenseItem("AndroidX Lifecycle / ViewModel", "Apache License 2.0"),
            LicenseItem("Hilt / Dagger", "Apache License 2.0"),
            // 网络
            LicenseItem("Retrofit", "Apache License 2.0"),
            LicenseItem("OkHttp", "Apache License 2.0"),
            // 存储
            LicenseItem("Room", "Apache License 2.0"),
            // 图片加载
            LicenseItem("Coil", "Apache License 2.0"),
            // 路由
            LicenseItem("TheRouter", "Apache License 2.0"),
        )
    }
}

/** 开源库条目：库名 + 许可证名（文件内共享，供静态列表与 Screen 使用） */
@Immutable
private data class LicenseItem(val name: String, val license: String)

/**
 * 许可列表：单卡片内库名 + 许可证名两列布局。
 */
@Composable
private fun LicensesScreen(licenses: List<LicenseItem>) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionLabel(text = stringResource(R.string.licenses_section_label))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    licenses.forEachIndexed { index, item ->
                        if (index > 0) CommonListDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = item.license,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
