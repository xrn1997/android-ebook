package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 下载队列的一行：一个**尚未完成**的章节下载任务（表 download_chapter）。
 *
 * 任务自带跑完它所需的全部信息（书的 note_url、章节序号/URL/名、书源归属标记、书名与封面），
 * 因为 DownloadService 抓取时只拿得到这一条记录，不回查书架；书名与章节名进常驻通知文案，
 * 书名与封面还供下载管理页的分组行展示。
 * 任务由阅读器下载面板构造，既入库排队，也随启动 Intent 直达服务——这就是它需要 [Parcelable]
 * 的原因（见 DownloadService.buildStartIntent）。
 *
 * 键的设计（见 ADR-0003）：流水型数据用自增 `id` 主键，"同章不重复排队"由 `dur_chapter_url`
 * 上的唯一索引兜底，`note_url` 普通索引支撑按书取队头与按书取消。
 * 行只在下好、重试耗尽被跳过或取消时删除，故表内行数即剩余章数（见 ADR-0018）。
 */
@Parcelize
@Entity(
    tableName = "download_chapter",
    indices = [
        Index(value = ["note_url"], name = "idx_download_chapter_note_url"),
        Index(value = ["dur_chapter_url"], name = "idx_download_chapter_dur_chapter_url", unique = true),
    ]
)
data class DownloadChapterEntity(
    /**
     * 自增主键。构造时留 0 表示"请数据库分配"，重投已有任务时须回填查到的旧 id
     * （见 DownloadRepository.addTasks），否则 REPLACE 会删旧插新、换掉行号。
     */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    var id: Long = 0,
    /** 所属书的书源侧根地址，与 book_shelf / book_info 的 noteUrl 同源，用于按书成组与按书取消 */
    @ColumnInfo(name = "note_url")
    var noteUrl: String = String(),
    /**
     * 当前章节数
     */
    @ColumnInfo(name = "dur_chapter_index")
    var durChapterIndex: Int = 0,
    /**
     * 当前章节对应的文章地址
     */
    @ColumnInfo(name = "dur_chapter_url")
    var durChapterUrl: String = String(),
    /**
     * 当前章节名称
     */
    @ColumnInfo(name = "dur_chapter_name")
    var durChapterName: String = String(),
    /**
     * 书源归属标记（该任务来自哪个书源，见 CONTEXT.md「书源归属标记」）。
     *
     * 当前是单书源架构，抓取统一走默认书源的 parser（DownloadService 里的
     * `bookSourceManager.requireParser()`），本列暂只作归属记录；按书源找 parser 属 ADR-0016 规划。
     */
    @ColumnInfo(name = "tag")
    var tag: String = String(),
    /** 书名（冗余存储，供常驻通知文案与下载管理页分组标题展示；按书分组实际用的是 noteUrl） */
    @ColumnInfo(name = "book_name")
    var bookName: String = String(),
    /**
     * 小说封面
     *
     * 冗余存储：下载管理页每个分组行要显示缩略图，而它只拿得到队列表的任务，不再回查 book_info。
     */
    @ColumnInfo(name = "cover_url")
    var coverUrl: String = String(),
    /**
     * 强制刷新标记：命中已有缓存也不跳过，先删旧内容再重新抓取。
     *
     * 阅读器下载入口下发的任务统一带上该标记（刷新缓存能力已合并进下载，
     * 勾中已缓存章节即等价重下，见 ReadBookActivity），对未缓存章节则为空操作。
     * v2 新增列，旧行默认 0（命中即跳过的语义不变）。
     *
     * 该列由 DatabaseModule.MIGRATION_1_2 以 ALTER TABLE 追加（不启用破坏性迁移，见 ADR-0003「Schema 演进」）。
     */
    @ColumnInfo(name = "force_refresh")
    var forceRefresh: Boolean = false,
) : Parcelable
