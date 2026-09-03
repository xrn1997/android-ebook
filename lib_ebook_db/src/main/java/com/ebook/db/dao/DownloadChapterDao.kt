package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.DownloadChapterEntity
import kotlinx.coroutines.flow.Flow

/**
 * 下载队列表（download_chapter）访问器：离线下载尚未完成的任务。
 *
 * **只存未完成任务**：一章下完（或重试耗尽被跳过）就删行，不保留历史，因此行数即"剩余章数"
 * （[count]/[observeRemainingCount] 的语义建立在这条不变式上）。发起方必须先入库再拉起服务，
 * 任务不能只躲在 Intent 里（见 ADR-0018）。
 *
 * 消费方是 `module_book` 的 DownloadService：逐轮取「队头」抓一章、成功或放弃后出队、
 * 延迟 800ms 再取下一章，章节顺序即目录顺序。入队只来自阅读器下载面板
 * （BookReadViewModel.startDownload → DownloadRepository.addTasks），暂停/继续/取消由下载管理页与通知按钮下达。
 *
 * 键的设计（见 ADR-0003）：主键是自增 `id`（流水型数据），另有 `dur_chapter_url` **唯一索引**
 * 保证一章只排一个任务，`note_url` 普通索引支撑按书取队头与按书取消。
 */
@Dao
interface DownloadChapterDao {
    /**
     * 按章节 URL 查已排任务：入队前查重用。
     *
     * 主键是自增 id 而非 URL，本查询命中的行必须把它的 id 回填给 [insertAll]
     * （DownloadRepository.addTasks 里的 `existing?.id ?: 0L`）才算原地覆盖；
     * 传 0 是让 SQLite 分配新行，同 URL 会撞唯一索引而走 REPLACE 的"先删后插"。
     */
    @Query("SELECT * FROM download_chapter WHERE dur_chapter_url = :chapterUrl")
    suspend fun getChapterByUrl(chapterUrl: String): DownloadChapterEntity?

    /**
     * 某书的队头：章序号最小的待下载任务。
     *
     * 服务每轮就靠它定下一章，所以**失败章必须从表里删掉**——队头不动，下一轮 [getFirstByNoteUrl]
     * 还会拿到同一章，外层无退避无上限即成无限重试（常驻通知不消失、前台 dataSync 配额白烧，
     * 后续章节全被队头阻塞），详见 ADR-0018 与 DownloadService.downloading。
     * 暂停中断则相反：任务保留待续跑。
     */
    @Query("SELECT * FROM download_chapter WHERE note_url = :noteUrl ORDER BY dur_chapter_index ASC LIMIT 1")
    suspend fun getFirstByNoteUrl(noteUrl: String): DownloadChapterEntity?

    /**
     * 某书队尾（章序号最大的一章）。
     *
     * 调用方 DownloadRepository.findLatestDownloadTask 与队头同构（逐本遍历书架后按书取一章），
     * 目前只被下载管理页 `resumeIfPending` 当作"队列是否还有任务"的判据（只判 null，不取内容）——
     * 空队列发 RESUME 会走到服务的 finishDownload、误发"下载完成"通知，所以要先确认再发。
     */
    @Query("SELECT * FROM download_chapter WHERE note_url = :noteUrl ORDER BY dur_chapter_index DESC LIMIT 1")
    suspend fun getLastByNoteUrl(noteUrl: String): DownloadChapterEntity?

    /**
     * 跨书的全局队头（仅按章序号取最小，不区分哪本书）。
     *
     * 与 [getFirstByNoteUrl] 的差别只在少了 `note_url` 过滤：服务取任务是"按书架逐本"遍历的
     * （DownloadRepository.getNextDownloadTask → [getFirstByNoteUrl]），不经本方法。
     */
    @Query("SELECT * FROM download_chapter ORDER BY dur_chapter_index ASC LIMIT 1")
    suspend fun getFirst(): DownloadChapterEntity?

    /**
     * 全部待下载任务（按 `note_url` 再按章序号升序，供下载管理页按书分组）。
     *
     * 排序用的是 note_url 而非书名，分组键与之一致（书名只是展示字段）。
     * 队列表体量小（未完成任务），一次性取全在 Kotlin 侧分组即可，
     * 不必为分组引入聚合 SQL。
     */
    @Query("SELECT * FROM download_chapter ORDER BY note_url ASC, dur_chapter_index ASC")
    suspend fun getAllTasks(): List<DownloadChapterEntity>

    /** 删除某本书的全部待下载任务（下载管理页"取消本书"） */
    @Query("DELETE FROM download_chapter WHERE note_url = :noteUrl")
    suspend fun deleteByNoteUrl(noteUrl: String)

    /**
     * 队列剩余数（表内只存未完成任务，故等价"剩余章数"）。
     *
     * 供下载通知展示进度总量。
     */
    @Query("SELECT COUNT(*) FROM download_chapter")
    suspend fun count(): Int

    /**
     * 队列剩余数的响应式观察（书架下载图标角标）。
     *
     * Room Flow：任务增/删时自动重推新值，角标无需手动刷新；
     * 无任务时推 0，角标自然隐藏。
     */
    @Query("SELECT COUNT(*) FROM download_chapter")
    fun observeRemainingCount(): Flow<Int>

    /**
     * 写入单个任务。自增主键下 `id = 0` 表示"请 SQLite 分配新行"，带既有 id 才是覆盖那一行；
     * 无论哪种，`dur_chapter_url` 的唯一索引都保证同章不会排两份（冲突时 REPLACE 删旧插新）。
     *
     * REPLACE 是整行替换，重投任务时若不带上原行的 `force_refresh`，标记会被默认值 false 冲掉
     * （DownloadRepository.addTasks 因此逐字段回填旧值）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: DownloadChapterEntity)

    /** [insert] 的批量版本；批量入队前请先按 URL 查重回填 id（见 [getChapterByUrl]） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<DownloadChapterEntity>)

    /**
     * 按主键（`id`）删除单个任务，即"出队"。
     *
     * 服务侧三条出队路径都走它：正文入库成功后、命中已有缓存且任务无强制刷新标记（视为已完成）时、
     * 以及重试耗尽跳过该章时（后者**必须**出队，否则队头不动 → 无限重试，见 [getFirstByNoteUrl] 与 ADR-0018）。
     * 被暂停打断的重试不出队，任务保留待续跑。
     */
    @Delete
    suspend fun delete(chapter: DownloadChapterEntity)

    /**
     * 清空全部任务：本轮队列跑完时的收尾（顺带清掉按书架遍历取不到的残留行，
     * 如书已被移出书架的任务），以及用户从下载管理页/通知上取消全部（ACTION_CANCEL）。
     *
     * 只用于批次结束：想跳过某一章得走 [delete]，否则会绕过服务里 skippedCount 的计数，
     * 收尾提示就报不出"跳过了几章"（见 ADR-0018）。
     */
    @Query("DELETE FROM download_chapter")
    suspend fun clearAll()
}
