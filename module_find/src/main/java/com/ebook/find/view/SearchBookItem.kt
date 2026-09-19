package com.ebook.find.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.BookItemLayout
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.bookItemLineCount
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
 * 其余口径（[BookItemFrame] 与 [BookItemMetaRow] 上各有一段）：
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
 * 条目的外壳：定高正文列 + 封面 + 书名 + 浮在右上角的加书架图标。
 *
 * 三个"不参与内容"的决定都收在这里，变体只管正文列里那一坨：
 * - 正文列定高 [BookItemLayout.columnHeight]，卡片高度与字段有无无关。
 * - 顶对齐：封面顶边、书名顶边、图标顶边是同一条基线，谁都不该把书名挤到中间。
 * - 图标是覆盖层：只有书名那一行为它让出 [BookItemLayout.actionSlot] 宽（不让就叠字），
 *   下面的行通宽，书源因此能顶到卡片右内边距。
 *
 * @param body 正文列里书名之下的内容（`ColumnScope`，所以变体能用 weight 排空白）
 */
@Composable
private fun BookItemFrame(
    searchBook: SearchBookEntity,
    onItemClick: () -> Unit,
    onAddShelf: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    CommonItemCard(
        onClick = onItemClick,
        // 12dp 收到 10dp：条目内边距是从正文列里扣走的宽度，列表密排时左右各还回 2dp
        contentPadding = PaddingValues(10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            BookCover(
                url = searchBook.coverUrl,
                contentDescription = searchBook.name,
                modifier = Modifier.size(
                    width = BookItemLayout.coverWidth(BookItemLayout.columnHeight),
                    height = BookItemLayout.columnHeight
                ),
                shape = RoundedCornerShape(6.dp)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(BookItemLayout.columnHeight)
                    .padding(start = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = searchBook.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 行尾让出图标：图标浮在右上角，不让宽就会叠在字上
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = BookItemLayout.actionSlot)
                    )
                    body()
                }
                IconButton(
                    onClick = onAddShelf,
                    enabled = !searchBook.add,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(BookItemLayout.actionSlot),
                    // 主色走按钮的 contentColor，不给 Icon 硬填 tint：M3 的禁用态只经
                    // LocalContentColor 下发，tint 写死会让「已在书架」与可点态同色、看不出点不动
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
        }
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
        searchBook = searchBook,
        onItemClick = onItemClick,
        onAddShelf = onAddShelf,
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
        searchBook = searchBook,
        onItemClick = onItemClick,
        onAddShelf = onAddShelf,
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
 * 底行：作者靠左、书源贴右，两列各自省略。
 *
 * 谁也不挤谁——长源名截在自己的 120dp 里，不会像旧版那样把同行字段压成 0 宽整片消失
 * （Row 给无权重子项的是「剩余宽度」上限，见 RowColumnMeasurePolicy 的 mainAxisMax = remaining）。
 * 作者缺席时左列仍占位（空 Text + weight），书源才不会被拽到中间。
 */
@Composable
private fun BookItemMetaRow(author: String, origin: String) {
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = author,
            style = typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (origin.isNotEmpty()) {
            Text(
                text = origin,
                style = typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .widthIn(max = 120.dp)
            )
        }
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
