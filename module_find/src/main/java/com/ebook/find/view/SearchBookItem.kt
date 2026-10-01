package com.ebook.find.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.ebook.common.ui.BookItemFrame
import com.ebook.common.ui.BookItemLayout
import com.ebook.common.ui.BookItemMetaRow
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.bookItemLineCount
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.sampleSearchBook
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.R

/**
 * 一条找书结果能凑出几行有效信息（书名恒算一行）。
 *
 * 单独做成纯函数，是因为**形态由数据条数决定**这件事必须可测、可一眼看清：
 * 布局里翻来覆去的 `if` 是这一串空白问题的病根（居中/贴顶/等分各写一套分支，
 * 改一处就漏一处），所以分支只留这一处判断，两个形态内部都是直线布局。
 *
 * @param showOrigin 分类选书页关掉书源（整页锁定同一个源），那一格就不算有效信息
 */
internal fun searchBookInfoRows(
    searchBook: SearchBookEntity,
    showOrigin: Boolean = true,
): Int {
    val hasSecondLine = listBlurb(searchBook).isNotEmpty()
    val hasMetaLine = searchBook.author.isNotEmpty() || (showOrigin && searchBook.origin.isNotEmpty())
    return 1 + (if (hasSecondLine) 1 else 0) + (if (hasMetaLine) 1 else 0)
}

/**
 * 搜索结果 / 分类选书列表条目。
 *
 * **形态按有效信息的条数分**，不是一个 composable 里靠 `if` 换布局：
 * - [ThreeLineBookItem]：书名 + 简介（无简介回落最新章，1~2 行）+ 作者·书源。
 * - [TwoLineBookItem]：只有两行信息时用它，第二行在剩下的地方垂直居中。
 * 两者共用 [BookItemFrame]（封面 + 书名 + 右上角动作图标），高度都是
 * [BookItemLayout.columnHeight] + 上下 10dp 内边距 = **96dp**，所以同一列表里仍严格等高。
 *
 * 为什么两行要居中、三行的第二行却紧跟书名：三行齐全时把空白摊进行与行之间，看起来就是
 * "行距被放大了"（实测一行简介时上下各 22dp）；而只有两行内容时若还顶对齐，卡片底下会空
 * 一整片。这两种排法各自只有一种情形，所以拆成两个形态比在一个形态里来回判断更稳。
 *
 * 其余口径（共享 [BookItemFrame] 与 [BookItemMetaRow] 上各有一段）：
 * - 基线取封面顶边：封面、书名、加书架图标三者顶边齐平；封面宽由列高按 3:4 推出（57×76），
 *   [BookCover] 的 Crop 因此一边都不裁（不少站点把书名印在封面底边，裁不得）。
 * - 加书架图标是浮在正文列右上角的覆盖层，不参与任何一行的高度，也不占二、三行的宽度，
 *   书源因此能贴到卡片右内边距。
 * - 中间行显示几行由 [bookItemLineCount] 按列高算：系统字号越大行数越少，卡片高度不变；
 *   连一行都放不下时那一格不渲染。
 * - 分类（`kind`）不展示：解析器仍会写它（`JsoupBookParser` / `ScriptBookParser` 都写），
 *   三行结构给它留不下位置。别把某格显示不出来当成解析漏了。
 *
 * 字号一律取 Material typography 角色（[MaterialTheme.typography]），不写死 sp：
 * 书名为 `titleSmall` + SemiBold（靠字重而不是更大的字号撑层级）；简介 `bodySmall`；
 * 作者/书源 `labelSmall` + `outline`，让视觉重量逐行递减。
 *
 * @param searchBook 书籍数据
 * @param onItemClick 点击条目（打开书籍详情）
 * @param onAddShelf 点击加书架图标
 * @param showOrigin 是否显示书源。搜索页必须显示：聚合搜索（ADR-0016 P3-b）后一份列表混着
 *   多个站的条目，`origin` 是「这条是谁家的」的判据（由解析器写入，见
 *   JsoupBookParser.parseSearchBookWithRule）。分类选书页整段会话锁定进入那一页时的那一个源
 *   （ChoiceBookViewModel 的 sourceUrl 注释），每条 origin 都是同一个名字，因此传 false 省掉噪声。
 */
@Composable
fun SearchBookItem(
    searchBook: SearchBookEntity,
    onItemClick: () -> Unit,
    onAddShelf: () -> Unit,
    showOrigin: Boolean = true
) {
    val blurb = listBlurb(searchBook)
    val author = searchBook.author
    val origin = if (showOrigin) searchBook.origin else ""
    if (searchBookInfoRows(searchBook, showOrigin) >= 3) {
        ThreeLineBookItem(
            searchBook = searchBook,
            blurb = blurb,
            author = author,
            origin = origin,
            onItemClick = onItemClick,
            onAddShelf = onAddShelf,
        )
    } else {
        TwoLineBookItem(
            searchBook = searchBook,
            blurb = blurb,
            author = author,
            origin = origin,
            onItemClick = onItemClick,
            onAddShelf = onAddShelf,
        )
    }
}

/**
 * 右上角动作槽：加书架图标，已加入时换成对勾且点不动。
 *
 * 宿主是共享 [BookItemFrame] 的 `action` 槽——图标浮在正文列右上角，**不参与任何一行的高度**，
 * 所以卡片高度与它在不在无关；只有书名那一行为它让宽（由外壳负责），下面各行通宽。
 *
 * 主色走按钮的 `contentColor`，不给 `Icon` 硬填 tint：M3 的禁用态只经 `LocalContentColor` 下发，
 * tint 写死会让「已在书架」与可点态同色、看不出点不动。
 *
 * @param modifier 对着槽位用的修饰（`align(Alignment.TopEnd)` 与尺寸都靠它，见 [SearchBookItem] 的
 *   调用点）；组件不自己 align——那样它就得是 `BoxScope` 的扩展，而槽内对齐本就是调用方的语境
 */
@Composable
private fun AddToShelfAction(
    searchBook: SearchBookEntity,
    onAddShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onAddShelf,
        enabled = !searchBook.add,
        modifier = modifier.size(BookItemLayout.actionSlot),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        // 图标 24dp 与命中盒同大：外面再套一圈空盒就会压到下面那行的右端
        // （点它点成加书架），而这一圈的点击收益是 0——整张卡本来就可点
        Icon(
            imageVector = if (searchBook.add) Icons.Filled.Check else Icons.Filled.LibraryAdd,
            contentDescription = stringResource(
                if (searchBook.add) R.string.already_on_shelf else R.string.add_to_shelf
            )
        )
    }
}

/**
 * 三行形态：书名 → 简介（紧跟，恒定 [BookItemLayout.middleSpacing]）→ 作者·书源（贴底）。
 *
 * 空白只有一处、且落在简介与底行之间：三行齐全时行距就是设计要的那两个值，
 * 不会被摊成"上下各一半"。
 */
@Composable
private fun ThreeLineBookItem(
    searchBook: SearchBookEntity,
    blurb: String,
    author: String,
    origin: String,
    onItemClick: () -> Unit,
    onAddShelf: () -> Unit,
) {
    val typography = MaterialTheme.typography
    // 简介能占几行由列高算出来（两行档）：字号越大越少，卡片高度不变
    val introLines = bookItemLineCount(
        columnHeight = BookItemLayout.columnHeight,
        nameStyle = typography.titleSmall,
        bottomStyle = typography.labelSmall,
        bodyStyle = typography.bodySmall,
        spacing = BookItemLayout.middleSpacing,
    )
    BookItemFrame(
        coverUrl = searchBook.coverUrl,
        coverDescription = searchBook.name,
        title = searchBook.name,
        onClick = onItemClick,
        action = {
            AddToShelfAction(
                searchBook = searchBook,
                onAddShelf = onAddShelf,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        },
    ) {
        if (introLines > 0 && blurb.isNotEmpty()) {
            Text(
                text = blurb,
                style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = introLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BookItemLayout.middleSpacing)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        BookItemMetaRow(author, origin)
    }
}

/**
 * 两行形态：书名 + 一行信息，那一行在剩下的地方垂直居中。
 *
 * 只有两行内容时若仍顶对齐，卡片底下会空出一整片；居中把空白分到两侧，看起来就是
 * "这一条本来就只有两行"，而不是"布局漏了一块"。上下各一个 `weight` 的 Spacer，
 * 布局里没有条件分支。
 */
@Composable
private fun TwoLineBookItem(
    searchBook: SearchBookEntity,
    blurb: String,
    author: String,
    origin: String,
    onItemClick: () -> Unit,
    onAddShelf: () -> Unit,
) {
    val typography = MaterialTheme.typography
    val introLines = bookItemLineCount(
        columnHeight = BookItemLayout.columnHeight,
        nameStyle = typography.titleSmall,
        bottomStyle = typography.labelSmall,
        bodyStyle = typography.bodySmall,
        spacing = BookItemLayout.middleSpacing,
    )
    BookItemFrame(
        coverUrl = searchBook.coverUrl,
        coverDescription = searchBook.name,
        title = searchBook.name,
        onClick = onItemClick,
        action = {
            AddToShelfAction(
                searchBook = searchBook,
                onAddShelf = onAddShelf,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        },
    ) {
        Spacer(modifier = Modifier.weight(1f))
        // 两行时第二行只有两种来源：简介（或末章回落）与「作者·书源」，取实际存在的那个
        if (blurb.isNotEmpty() && introLines > 0) {
            Text(
                text = blurb,
                style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = introLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (author.isNotEmpty() || origin.isNotEmpty()) {
            BookItemMetaRow(author, origin)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * 条目第二行的取文：**简介优先，无简介才回落最新章节**。
 *
 * 抽成纯函数是为了让这一条取舍可被单测钉住（[SearchBookItemTest]）——它是列表里唯一
 * 「两个字段抢同一格」的地方，写反了不会崩、只会静默显示成另一种内容：
 * 第三方源的 `lastChapter` 几乎总有值，旧顺序（末章优先）等于让简介永远露不了面。
 */
internal fun listBlurb(searchBook: SearchBookEntity): String =
    searchBook.desc.ifEmpty { searchBook.lastChapter }

/**
 * 预览：找书条目卡的四种中间行来源，一次拍全 [listBlurb] 的取值顺序。
 *
 * 这四张图防的是**静默显示错内容**：简介与末章抢同一格，顺序写反不会崩、也不会报错，
 * 只会让第三方源的简介永远露不了面（那些站 `lastChapter` 几乎总有值）。所以除了常规档，
 * 必须给出「只有简介」「只有末章」「两个都空」三档——最后一档决定卡片走两行还是三行版式。
 *
 * 第二张 `add = true`：右上角动作槽从「加书架」换成已加入形态。
 */
@PreviewLightDark
@Composable
private fun SearchBookItemPreview() {
    AppPreview {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing),
        ) {
            SearchBookItem(
                searchBook = sampleSearchBook(index = 1),
                onItemClick = {},
                onAddShelf = {},
            )
            SearchBookItem(
                searchBook = sampleSearchBook(index = 2, add = true),
                onItemClick = {},
                onAddShelf = {},
            )
            // 没有简介、只有末章：中间行应回落末章文案
            SearchBookItem(
                searchBook = sampleSearchBook(index = 3, desc = ""),
                onItemClick = {},
                onAddShelf = {},
            )
            // 两个都空：整格不占位，卡片落到两行版式
            SearchBookItem(
                searchBook = sampleSearchBook(index = 4, desc = "", lastChapter = ""),
                onItemClick = {},
                onAddShelf = {},
            )
        }
    }
}

/**
 * 预览：**分类选书页**（`ChoiceBookActivity`）那一档——`showOrigin = false`。
 *
 * 那一页的根是 Activity 的 `PageContent()`（自己 collect `ChoiceBookViewModel.list`），结构上
 * 预览不了，而它与搜索结果页的唯一差别就在条目里这一格书源：整段会话锁定导航参数带进来的
 * 那一个源，每条 `origin` 都是同一个名字，摆出来只是重复噪声。于是这一张替那一页把条目形态拍全。
 *
 * 防的是**静默多画**：`showOrigin` 写反既不报错也不闪退，只是分类页每一行右侧多出一个相同站名
 * （反向写错则搜索页丢掉了「这条是谁家的」这一判据，聚合搜索下更糟）。深浅两档各一张，
 * 是因为底行的 `outline` 文字色在深色下调过一遍才看得出书源那一格真的没画。
 *
 * 三张依次是：三行齐全（关掉书源后底行只剩作者）、无简介回落末章、作者为空——
 * 三者都由 [searchBookInfoRows] 决定走两行还是三行版式，[listBlurb] 的取值顺序与上一页同源。
 */
@PreviewLightDark
@Composable
private fun SearchBookItemWithoutOriginPreview() {
    AppPreview {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing),
        ) {
            SearchBookItem(
                searchBook = sampleSearchBook(index = 1),
                onItemClick = {},
                onAddShelf = {},
                showOrigin = false,
            )
            // 没有简介、只有末章：中间行仍回落末章，书源那一格不该因为少了一行就补回来
            SearchBookItem(
                searchBook = sampleSearchBook(index = 2, desc = ""),
                onItemClick = {},
                onAddShelf = {},
                showOrigin = false,
            )
            // 作者也为空：底行两列都没有内容，卡片落到两行版式（书源被关掉时这一档最容易整行空掉）
            SearchBookItem(
                searchBook = sampleSearchBook(index = 3, desc = "", author = ""),
                onItemClick = {},
                onAddShelf = {},
                showOrigin = false,
            )
        }
    }
}
