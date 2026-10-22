package com.ebook.common.repository

import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShelfEntryStore] 的四表不变式：一个条目由哪些行组成、事务边界、提交后事件。
 *
 * 这些断言原先分散在 [BookRepositoryTest] 与 [LocalBookImporter] 的路径里；
 * 提成 store 后这里直接锁「写/删/替换/补章」四种提交。
 */
class ShelfEntryStoreTest {

    private class RecordingRunner : WriteTransactionRunner {
        var runs = 0
        var committed = false

        /** 事务体正在跑的位置：`extraWrite` 之类的钩子必须落在这里，不能自己另开一笔 */
        var inTransaction = false
            private set

        override suspend fun <R> run(block: suspend () -> R): R {
            runs++
            inTransaction = true
            val result = try {
                block()
            } finally {
                inTransaction = false
            }
            // 只有 block 正常返回才等于「事务提交」；放在 finally 会让回滚也置真，
            // 「事件在提交后才发」的断言就永远成立。
            committed = true
            return result
        }
    }

    private fun shelf(
        noteUrl: String,
        name: String = "斗破苍穹",
        chapters: Int = 2,
    ): BookShelfEntity = BookShelfEntity(noteUrl = noteUrl).apply {
        tag = "https://source.example"
        bookInfo = BookInfoEntity(name = name, author = "天蚕土豆")
        chapterList = (0 until chapters).map { i ->
            ChapterListEntity(contentRef = "$noteUrl/ch/$i", durChapterIndex = i)
        }
    }

    private fun store(
        daos: FakeDaos = FakeDaos(),
        runner: WriteTransactionRunner = RecordingRunner(),
        events: MutableSharedFlow<BookShelfEvent> = MutableSharedFlow(extraBufferCapacity = 8),
    ) = ShelfEntryStore(
        bookShelfDao = daos.shelf,
        bookInfoDao = daos.info,
        chapterListDao = daos.chapter,
        bookGroupDao = daos.group,
        transactions = runner,
        events = events,
    )

    @Test
    fun `add 在同一笔写事务里写满四张表`() = runTest {
        val daos = FakeDaos()
        val runner = RecordingRunner()
        val entry = shelf("http://book")

        store(daos, runner).add(entry)

        assertEquals("整个条目只该提交一次", 1, runner.runs)
        assertEquals("http://book", daos.shelf.getBookByUrl("http://book")?.noteUrl)
        assertEquals("斗破苍穹", daos.info.getBookInfoByUrl("http://book")?.name)
        assertEquals(2, daos.chapter.getChaptersForBook("http://book").size)
        assertTrue(daos.chapter.getChaptersForBook("http://book").all { it.noteUrl == "http://book" })
        assertEquals(1, daos.group.storedValues().size)
        assertTrue(daos.group.storedValues().single().isPrimary)
    }

    @Test
    fun `add 在事务提交后才发 Added 事件`() = runTest {
        val daos = FakeDaos()
        val runner = RecordingRunner()
        val events = MutableSharedFlow<BookShelfEvent>(extraBufferCapacity = 8)
        val received = mutableListOf<BookShelfEvent>()
        val committedAtEvent = mutableListOf<Boolean>()
        // Unconfined：收集器在 emit 的同一调用栈里执行，才能取到「事件送达那一刻」的事务状态；
        // 默认调度器要等 add() 返回后才收集，committed 恒为 true，断言会假绿。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.collect { event ->
                received += event
                committedAtEvent += runner.committed
            }
        }
        runCurrent()

        store(daos, runner, events).add(shelf("http://book"))
        runCurrent()

        assertEquals(1, received.size)
        assertTrue(received.single() is BookShelfEvent.Added)
        assertEquals("事件只能在事务正常返回之后发", listOf(true), committedAtEvent)
    }

    @Test
    fun `事务失败时不发 Added 事件`() = runTest {
        val daos = FakeDaos()
        val events = MutableSharedFlow<BookShelfEvent>(extraBufferCapacity = 8)
        val received = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { events.toList(received) }
        runCurrent()
        val failing = object : WriteTransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R {
                block()
                throw IllegalStateException("rollback")
            }
        }

        val failure = try {
            store(daos, failing, events).add(shelf("http://book"))
            null
        } catch (e: IllegalStateException) {
            e
        }
        runCurrent()

        assertTrue("事务没提交，异常应原样抛给调用方", failure is IllegalStateException)
        assertTrue("回滚的写不是事实，不得广播 Added", received.isEmpty())
    }

    @Test
    fun `replaceRows 在同一笔事务里删旧写新并吸收评论键`() = runTest {
        val daos = FakeDaos()
        val runner = RecordingRunner()
        val events = MutableSharedFlow<BookShelfEvent>(extraBufferCapacity = 8)
        val received = mutableListOf<BookShelfEvent>()
        val old = shelf("http://old")
        val new = shelf("http://new")
        store(daos, runner, events).add(old)
        daos.group.addSecondary(BookGroupEntity(commentKey = "secondary", noteUrl = old.noteUrl))
        backgroundScope.launch { events.toList(received) }
        runCurrent()

        val runsBefore = runner.runs
        // 换源用这一位清旧下载队列：那笔写必须与四表同进同退，否则清队列失败时新条目其实已落库
        // （调用方以为没换成），成功时又留出「队列清了、条目还没提交」的可读窗口
        var sawInTransaction: Boolean? = null
        store(daos, runner, events).replaceRows(new, old) { sawInTransaction = runner.inTransaction }
        runCurrent()

        assertEquals("替换只该多一笔事务", runsBefore + 1, runner.runs)
        assertEquals("extraWrite 必须跑在那笔事务之内", true, sawInTransaction)
        assertNull(daos.shelf.getBookByUrl(old.noteUrl))
        assertEquals("http://new", daos.shelf.getBookByUrl(new.noteUrl)?.noteUrl)
        assertTrue(daos.chapter.getChaptersForBook(old.noteUrl).isEmpty())
        assertEquals(2, daos.chapter.getChaptersForBook(new.noteUrl).size)
        assertTrue(
            "旧条目攒下的 secondary 键必须先搬到新条目",
            daos.group.getAllForNoteUrl(new.noteUrl).any { it.commentKey == "secondary" },
        )
        assertEquals(2, received.size)
        assertTrue(received.first() is BookShelfEvent.Removed)
        assertTrue(received.last() is BookShelfEvent.Added)
    }

    @Test
    fun `remove 只删四张表的行并发出 Removed`() = runTest {
        val daos = FakeDaos()
        val events = MutableSharedFlow<BookShelfEvent>(extraBufferCapacity = 8)
        val received = mutableListOf<BookShelfEvent>()
        val entry = shelf("http://book")
        store(daos, events = events).add(entry)
        backgroundScope.launch { events.toList(received) }
        runCurrent()

        store(daos, events = events).remove(entry)
        runCurrent()

        assertNull(daos.shelf.getBookByUrl(entry.noteUrl))
        assertNull(daos.info.getBookInfoByUrl(entry.noteUrl))
        assertTrue(daos.chapter.getChaptersForBook(entry.noteUrl).isEmpty())
        assertTrue(daos.group.getAllForNoteUrl(entry.noteUrl).isEmpty())
        assertTrue(received.single() is BookShelfEvent.Removed)
    }

    @Test
    fun `appendTailAndRemove 在同一笔事务里补章并删新条目`() = runTest {
        val daos = FakeDaos()
        val runner = RecordingRunner()
        val events = MutableSharedFlow<BookShelfEvent>(extraBufferCapacity = 8)
        val old = shelf("http://old", chapters = 1)
        val new = shelf("http://new", chapters = 1)
        store(daos, runner, events).add(old)
        store(daos, runner, events).add(new)
        val received = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { events.toList(received) }
        runCurrent()

        val tail = listOf(
            ChapterListEntity(
                noteUrl = old.noteUrl,
                durChapterIndex = 1,
                contentRef = "${old.noteUrl}/tail",
            )
        )
        val runsBefore = runner.runs
        store(daos, runner, events).appendTailAndRemove(new, tail)
        runCurrent()

        assertEquals("补章与删新条目只该多一笔事务", runsBefore + 1, runner.runs)
        assertEquals(2, daos.chapter.getChaptersForBook(old.noteUrl).size)
        assertNull(daos.shelf.getBookByUrl(new.noteUrl))
        assertTrue(received.single() is BookShelfEvent.Removed)
    }

    @Test
    fun `absorbGroupKeys 在同一笔事务里搬 secondary 行`() = runTest {
        val daos = FakeDaos()
        val runner = RecordingRunner()
        val store = store(daos, runner)
        store.add(shelf("http://target", chapters = 0))
        store.add(shelf("http://source", chapters = 0))
        daos.group.addSecondary(BookGroupEntity(commentKey = "secondary", noteUrl = "http://source"))

        val runsBefore = runner.runs
        store.absorbGroupKeys("http://target", "http://source")

        assertEquals(runsBefore + 1, runner.runs)
        assertTrue(
            daos.group.getAllForNoteUrl("http://target").any { it.commentKey == "secondary" && !it.isPrimary },
        )
    }
}
