package com.ebook.common.store

import com.ebook.common.util.SPUtil

/**
 * 「阅读会话进行中」标记：回答「上次进程是不是在阅读界面里没正常收尾就没了」。
 *
 * 要解决的问题：应用被划掉 / 强杀 / 崩溃时进程直接终止，`onPause`、`onDestroy` 都可能不执行，
 * 下次启动无从知道「用户上次停在阅读界面」，只能一律回主页——用户得重新找书、翻回原处。
 * 本对象把这份信息落在 SP：阅读器**成功打开某本书**时写入该书 noteUrl，**正常退出**
 * （`Activity.onDestroy` 且 `isFinishing`）时清除。于是「标记还在」恰好等价于
 * 「上次异常关闭，且当时在读某本书」，启动页据此决定是否直接恢复阅读界面。
 *
 * 为什么用 SP 而不是 Room：这份标记只承载一个是非判断 + 一个 key，随进程生死各读写一次，
 * 不值得为它改实体表、走迁移链。
 *
 * 为什么写入方是阅读器、读取方是启动页：标记的**语义是「会话未收尾」**，只有阅读器自己知道
 * 「我正常退出了」（`isFinishing`）。若由启动页负责写，正常退出路径（用户按返回离开阅读器、
 * 进程仍活着）就根本没有写入方，标记会永远停在上一次异常关闭的旧值上，
 * 表现为「之后每次启动都被强行拉回阅读界面」。
 *
 * 注意这不是「阅读进度」：进度（章 / 页）的唯一事实源是 `book_shelf.dur_chapter(_page)`，
 * 本对象只记「上次没读完的是哪本书」。两者可以不一致——进度写入有钳制与事务语义，
 * 标记只承担「要不要恢复」这一个开关。
 */
object ReaderResumeStore {

    /**
     * 单独一个 SP 文件：本标记的生命周期（进程异常即残留）与配置类 SP（`CONFIG`/`spUtils`）
     * 完全无关，混在一个文件里会让「清配置」「清会话」之类的批量操作误伤它，反之亦然。
     */
    private const val SP_NAME = "reader_resume"

    /** 上次阅读的书籍 noteUrl；键不存在 = 没有未收尾的阅读会话 */
    private const val KEY_NOTE_URL = "last_reading_note_url"

    /**
     * 记下「正在读这本书」。
     *
     * 每次切换「当前读的是哪一本」都要重新调用（首次打开、应用外导入后打开、换源成功后）——
     * 换源会换掉 noteUrl，忘了重记会让下次恢复打开一本已经被换源事务删掉的旧条目。
     *
     * 落盘用同步 commit（[SPUtil.put] 的 `isCommit = true`）：本标记的**全部价值**就在于
     * 「进程马上就要非正常消失」时它已经写进去了，走 apply 的异步磁盘写在这里正好是最不该省的成本。
     * 调用频次也支持这个选择——一本书整个阅读过程只写几次（打开 / 换源），不在翻页热路径上。
     */
    fun markReading(noteUrl: String) {
        if (noteUrl.isBlank()) return
        SPUtil.put(KEY_NOTE_URL, noteUrl, SP_NAME, isCommit = true)
    }

    /**
     * 清除标记（阅读器正常退出时调用）。
     *
     * 同样用同步 commit：清完紧接着就是 `finish()`，之后再没有可靠的写盘时机。
     */
    fun clear() {
        SPUtil.remove(KEY_NOTE_URL, SP_NAME, isCommit = true)
    }

    /**
     * 取上次未收尾的阅读会话所在书籍的 noteUrl；null = 没有需要恢复的阅读会话。
     *
     * 空串按「没有」处理（SP 里可能残留过空写入），调用方据此只需判 null 一个分支。
     */
    fun pendingNoteUrl(): String? =
        SPUtil.get(KEY_NOTE_URL, "", SP_NAME).takeIf { it.isNotBlank() }
}
