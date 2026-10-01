package com.ebook.book

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.ebook.book.mvvm.viewmodel.EditBookMetaState
import com.ebook.book.mvvm.viewmodel.EditBookMetaViewModel
import com.ebook.common.domain.CommentKey
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.preview.AppPreview
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 修键面板（spec §9.3）：编辑主匹配名/匹配作者，查看当前主键与已关联键列表。
 *
 * 入口：书籍详情页正文底部「编辑匹配信息」（仅已在书架的条目有）。路由参数携带 `noteUrl`。
 *
 * 编辑的是**匹配**名与匹配作者，不是书架显示名（§9.3 把两者分开存的理由：不分开就会出现
 * 「为了对上评论去改用户看到的书名」）。用户在这里修正的正是 `comment_key` 的两个输入项，
 * 保存后系统重算键、切主键并迁移本人评论；书架上的书名一个字都不动。
 * 这个主键同时也是**导入判重的比对对象**（`DuplicateBookDetector.findMatchesFor`）——
 * 在这里改过匹配名，下一次导入同作品时检测就跟着走。
 *
 * UI 结构：
 * - 匹配名/匹配作者输入框（初始值由 loadState 填充，回落至当前书名/作者）
 * - 当前主键只读展示（monospace 字体，便于比对哈希）
 * - 已关联的其他键列表（合并历史），每项右侧有「移除」按钮（拆分操作）
 * - 底部「保存并迁移评论」按钮
 */
@AndroidEntryPoint
@Route(path = KeyCode.Book.EDIT_BOOK_META_PATH)
class EditBookMetaActivity : BaseMvvmActivity<EditBookMetaViewModel>() {
    override val viewModel: EditBookMetaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.edit_book_meta_title)
    }

    override fun initData() {
        viewModel.noteUrl = intent.extras?.getString(RouteArgs.NOTE_URL) ?: ""
    }

    @Composable
    override fun PageContent() {
        val state by viewModel.editMetaState.collectAsState()
        var matchName by remember { mutableStateOf("") }
        var matchAuthor by remember { mutableStateOf("") }

        // 首次进入自动加载匹配信息
        LaunchedEffect(Unit) { viewModel.loadState() }

        // loadState 完成后填充输入框初始值（只在输入框还是空时填充，避免覆盖用户已编辑的内容）
        LaunchedEffect(state) {
            if (state.matchName.isNotEmpty() && matchName.isEmpty()) {
                matchName = state.matchName
            }
            if (state.matchAuthor.isNotEmpty() && matchAuthor.isEmpty()) {
                matchAuthor = state.matchAuthor
            }
        }

        EditBookMetaScreen(
            state = state,
            matchName = matchName,
            matchAuthor = matchAuthor,
            onMatchNameChange = { matchName = it },
            onMatchAuthorChange = { matchAuthor = it },
            onSave = { viewModel.save(matchName, matchAuthor) },
            onRemoveKey = { viewModel.removeAssociatedKey(it) },
        )
    }
}

/**
 * 修键面板内容。
 *
 * 参数化传入 [viewModel] 的各字段/回调，而非在内部直持 ViewModel：
 * 页面与 Activity 共用同一 VM 实例（由 Activity 通过 by viewModels() 持有）。
 */
@Composable
private fun EditBookMetaScreen(
    state: EditBookMetaState,
    matchName: String,
    matchAuthor: String,
    onMatchNameChange: (String) -> Unit,
    onMatchAuthorChange: (String) -> Unit,
    onSave: () -> Unit,
    onRemoveKey: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(CommonUiTokens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 匹配名与匹配作者输入框：改的是算键的输入项，不动书架显示名（spec §9.3）
        Text(
            text = stringResource(R.string.edit_book_meta_section),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = matchName,
            onValueChange = onMatchNameChange,
            label = { Text(stringResource(R.string.edit_book_meta_match_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = matchAuthor,
            onValueChange = onMatchAuthorChange,
            label = { Text(stringResource(R.string.edit_book_meta_match_author)) },
            modifier = Modifier.fillMaxWidth(),
        )

        // 当前主键展示（monospace 便于比对哈希值）
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.edit_book_meta_primary_key),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.primaryKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // 已关联的其他键列表（合并历史）
        if (state.associatedKeys.isNotEmpty()) {
            Text(
                text = stringResource(R.string.edit_book_meta_associated_keys),
                style = MaterialTheme.typography.titleSmall,
            )
            state.associatedKeys.forEach { key ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRemoveKey(key) }) {
                        Text(
                            text = stringResource(R.string.edit_book_meta_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 保存按钮：重算键 + 迁移评论
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.edit_book_meta_save))
        }
    }
}

/**
 * 修键面板样例状态。
 *
 * [EditBookMetaState] 是 module_book 自己的 UI 状态类型，样例就地给、不塞进共享门面
 * （与 module_find 书城页里 `sampleBookTypes()` 同一口径：门面只收 ebook 域的共享实体）。
 *
 * 键串取 [CommentKey.compute] 的**真值**而不是编一串看着像的假哈希：这一页要展示的就是这一串，
 * 假样例看不出「版本前缀 + 64 位十六进制」的长度会怎么折行，据此判断排版就是错的。
 */
private fun previewEditBookMetaState(): EditBookMetaState = EditBookMetaState(
    matchName = "山海拾遗",
    matchAuthor = "临渊客",
    primaryKey = CommentKey.compute("山海拾遗", "临渊客"),
    // 两条 secondary：合并历史可能不止一条，只给一条看不出列表的排布
    associatedKeys = listOf(
        CommentKey.compute("山海拾遗", ""),
        CommentKey.compute("长安小吏", "临渊客"),
    ),
)

/**
 * 预览：修键面板的**已加载**形态（输入框有初值、主键 + 两条已关联键）。
 *
 * 这一页改的是「算 `comment_key` 的输入项」，不是书架显示名，所以要看的是三块信息各就各位：
 * - 两个输入框都带 label **且**有初值：空值时 label 不浮起，看着就像 placeholder 写错了；
 * - 主键卡片是 monospace 长串，必然折行——字号与行高只有在这一张上才量得准；
 * - 关联键每行右侧那颗「移除」取 error 色（拆分是破坏性动作），且长键串必须省略在自己那一列里，
 *   不能把按钮挤出行外：写坏了不报错，只是那一行点不到移除。
 *
 * 深浅两档各一张，是因为卡片底走 `surfaceVariant`、次要文字走 `onSurfaceVariant`，
 * 硬编码的浅色底在深色档会糊成一片——这张图就是用来发现它的。
 */
@PreviewLightDark
@Composable
private fun EditBookMetaScreenLoadedPreview() {
    AppPreview {
        EditBookMetaScreen(
            state = previewEditBookMetaState(),
            matchName = "山海拾遗",
            matchAuthor = "临渊客",
            onMatchNameChange = {},
            onMatchAuthorChange = {},
            onSave = {},
            onRemoveKey = {},
        )
    }
}

/**
 * 预览：修键面板的**空态**——`loadState` 还没回答（或这本书从没算过键）。
 *
 * `associatedKeys` 为空时「已关联的其他键」整段不该渲染：把标题写成恒显示，页面上就会留下一句
 * 小标题下面空无一物，看起来像加载失败（而这一页的加载失败只发一条轻提示，没有覆盖层可看）。
 * 主键那一格此时是空串——卡片仍在、内容空，这正是「还没算出键」与「卡片没画」的区分点。
 */
@PreviewLightDark
@Composable
private fun EditBookMetaScreenEmptyPreview() {
    AppPreview {
        EditBookMetaScreen(
            state = EditBookMetaState(),
            matchName = "",
            matchAuthor = "",
            onMatchNameChange = {},
            onMatchAuthorChange = {},
            onSave = {},
            onRemoveKey = {},
        )
    }
}
