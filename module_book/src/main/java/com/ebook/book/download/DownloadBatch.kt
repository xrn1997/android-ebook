package com.ebook.book.download

import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.dao.PausedBookDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.ebook.db.entity.PausedBookEntity

/**
 * 下载队列的唯一策略模块：候选域、按书暂停、孤儿清理与队列计数。
 *
 * 原先 `DownloadRepository` 里有三份同构的书架遍历（取队头、取队尾、清孤儿），
 * `DownloadService` 又在两处用「队头为空」判暂停。收进这里后：
 * 候选域只写一次，暂停只表现为 `paused_book` 的一行标记，UI 与服务读同一份队列事实。
 * 「队头为空 + 表内仍有任务 → 已暂停而非完成」的终态分支仍留在服务生命周期里。
 *
 * 本类不做线程切换：调用方（仓库/服务）在自己的 IO 上下文里调用。
 */
internal class DownloadBatch(
    private val downloadChapterDao: DownloadChapterDao,
    private val bookShelfDao: BookShelfDao,
    private val pausedBookDao: PausedBookDao,
) {

    /** 下一章：按书架顺序逐本找非本地、未暂停的书，取该书最小章序号的任务。 */
    suspend fun nextTask(): DownloadChapterEntity? = firstTask(smallest = true)

    /** 与 [nextTask] 同候选域，取该书最大章序号的任务；下载页用来判「还有没有可续跑的任务」。 */
    suspend fun latestTask(): DownloadChapterEntity? = firstTask(smallest = false)

    private suspend fun firstTask(smallest: Boolean): DownloadChapterEntity? {
        val paused = pausedBookDao.getAll().toSet()
        for (shelf in bookShelfDao.getAllBooks()) {
            if (shelf.tag == BookShelfEntity.LOCAL_TAG || shelf.noteUrl in paused) continue
            val task = if (smallest) {
                downloadChapterDao.getFirstByNoteUrl(shelf.noteUrl)
            } else {
                downloadChapterDao.getLastByNoteUrl(shelf.noteUrl)
            }
            if (task != null) return task
        }
        return null
    }

    suspend fun pauseBook(noteUrl: String) {
        pausedBookDao.insert(PausedBookEntity(noteUrl = noteUrl))
    }

    suspend fun resumeBook(noteUrl: String) {
        pausedBookDao.delete(noteUrl)
    }

    suspend fun deleteTask(task: DownloadChapterEntity) {
        downloadChapterDao.delete(task)
    }

    /** 清空队列时连暂停标记一起清：队列空了，标记留着只会让下次排队静默保持暂停。 */
    suspend fun clearAllTasks() {
        downloadChapterDao.clearAll()
        pausedBookDao.clearAll()
    }

    /** 取消某本书：任务与暂停标记一起删，避免下次重新排队时被残留标记静默暂停。 */
    suspend fun deleteTasksForBook(noteUrl: String) {
        downloadChapterDao.deleteByNoteUrl(noteUrl)
        pausedBookDao.delete(noteUrl)
    }

    /**
     * 清理孤儿任务：书已不在书架（或为本地书）的任务永远取不到，删掉；
     * 孤儿书的暂停标记同样按书删。
     */
    suspend fun deleteOrphans() {
        val shelfUrls = bookShelfDao.getAllBooks()
            .filter { it.tag != BookShelfEntity.LOCAL_TAG }
            .mapTo(mutableSetOf()) { it.noteUrl }
        downloadChapterDao.getAllTasks()
            .mapTo(mutableSetOf()) { it.noteUrl }
            .filter { it !in shelfUrls }
            .forEach { deleteTasksForBook(it) }
    }

    suspend fun count(): Int = downloadChapterDao.count()
}
