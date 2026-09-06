package com.ebook.common.domain

import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.entity.BookShelfEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入前重复检测（spec §6 重复检测两级中的「模糊」一级）。
 *
 * 判定发生在**导入时点**：待导入文件的标题+作者算出 `comment_key`，与书架上每本书当前的
 * 主键比对，命中即弹框让用户处置（共存 / 补章合并 / 覆盖 / 跳过）。
 *
 * 曾经存在的「书架侧两两扫描 + 五信号打分 + 轻确认通知」（spec §9.4）已被本入口取代——
 * 重复是在导入那一刻被创造出来的，事后在书架上 nag 用户既不及时也缺输入（打分要章名，
 * 而那意味着先把书切一遍章）。整条链的删除理由与影响面见 ADR-0023。
 *
 * 不包 `withContext(Dispatchers.IO)`：底层 DAO 都是 suspend 函数（Room 自己管线程），
 * 多一层调度器切换既无必要又会破坏 `runTest` 对协程的等待。
 */
@Singleton
class DuplicateBookDetector @Inject constructor(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val bookGroupDao: BookGroupDao,
) {

    /**
     * 一个命中的已有条目。
     *
     * [isLocal] 决定可用处置：只有本地书的正文归本机文件管，才谈得上「把缺的章节补进来」；
     * 网络书的章节由书源提供，补章无意义，只能共存或覆盖（并集键两者都会并入）。
     */
    data class ImportMatch(
        val noteUrl: String,
        val title: String,
        val author: String,
        val isLocal: Boolean,
    )

    /**
     * 查找书架上与待导入作品 `comment_key` 相同的条目。
     *
     * 比的是**主键**而非显示书名，理由有两条：
     * - 键含作者（spec §9.1）。只比书名会把「同名不同作者」判成同一本书，而命中即给出删除
     *   旧条目的处置，误判的代价是删掉另一本无辜的书。
     * - 主键正是修键面板维护的那一行（spec §9.3）。比主键意味着用户改过匹配名之后检测自动
     *   跟随；比 `book_info.name` 则两边脱节——面板改 `match_name`、检测读 `name`，
     *   用户改了名却仍然被当成另一本书。
     *
     * 作者解析结果两边不一致时算出不同键 → 不判重。这是刻意的保守方向：宁可放过一次提示，
     * 不可误并两本不同的书（与正文分页跟进那条「宁漏页不串章」同一取舍）。
     */
    suspend fun findMatchesFor(meta: ParsedBookMeta): List<ImportMatch> {
        val key = CommentKey.compute(meta.title, meta.author)
        val hitNoteUrls = bookGroupDao.getPrimaryRows()
            .filter { it.commentKey == key }
            .map { it.noteUrl }
            .toSet()
        if (hitNoteUrls.isEmpty()) return emptyList()

        return bookShelfDao.getAllBooks()
            .filter { it.noteUrl in hitNoteUrls }
            .mapNotNull { shelf ->
                // 没有 book_info 行的是孤立条目（书架查询侧同样过滤掉它们）：弹框里只能显示
                // 一个空书名，让用户去处置一条看不出是什么的行不如直接不理
                val info = bookInfoDao.getBookInfoByUrl(shelf.noteUrl) ?: return@mapNotNull null
                ImportMatch(
                    noteUrl = shelf.noteUrl,
                    title = info.name,
                    author = info.author,
                    isLocal = shelf.tag == BookShelfEntity.LOCAL_TAG,
                )
            }
    }
}
