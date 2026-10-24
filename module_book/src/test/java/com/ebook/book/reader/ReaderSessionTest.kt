package com.ebook.book.reader

import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.repository.ChapterSyncResult
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionTest {

    private class FakeGateway : ReaderSessionGateway {
        var detailShelf: BookShelfEntity? = null
        var row: BookShelfEntity? = null
        var storedChapters: List<ChapterListEntity> = emptyList()
        var content: ChapterContent? = ChapterContent("", listOf("正文"))
        var syncResult: ChapterSyncResult = ChapterSyncResult.UpToDate
        var syncGate: CompletableDeferred<Unit>? = null
        var addGate: CompletableDeferred<Unit>? = null
        var lookupGate: CompletableDeferred<Unit>? = null
        var saveMutation: ((BookShelfEntity) -> Unit)? = null
        var saved: BookShelfEntity? = null
        var added: BookShelfEntity? = null
        var refreshed: Pair<String, Int>? = null
        var syncCalls = 0
        var loadCalls = 0

        /** 阅读会话标记的写入记录：顺序即调用顺序。 */
        val markedNoteUrls = mutableListOf<String>()

        override fun markReading(noteUrl: String) {
            markedNoteUrls += noteUrl
        }

        override suspend fun getBookWithDetails(noteUrl: String) = detailShelf
        override suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? {
            lookupGate?.await()
            return row
        }
        override suspend fun saveProgress(shelf: BookShelfEntity) {
            saved = shelf
            saveMutation?.invoke(shelf)
        }
        override suspend fun loadChapter(shelf: BookShelfEntity, chapter: ChapterListEntity): ChapterContent? {
            loadCalls++
            return content
        }
        override suspend fun refreshChapter(shelf: BookShelfEntity, index: Int) {
            refreshed = shelf.noteUrl to index
        }
        override suspend fun syncChapters(shelf: BookShelfEntity): ChapterSyncResult {
            syncCalls++
            syncGate?.await()
            return syncResult
        }
        override suspend fun getStoredChapters(noteUrl: String) = storedChapters
        override suspend fun addToShelf(shelf: BookShelfEntity) {
            added = shelf
            addGate?.await()
        }
    }

    private fun chapter(index: Int, tag: String = "https://a.example") = ChapterListEntity(
        noteUrl = "a.example/book/1.html",
        durChapterIndex = index,
        contentRef = "$tag/chapter/${index + 1}.html",
        durChapterName = "第${index + 1}章",
        tag = tag,
    )

    private fun shelf(
        vararg chapters: ChapterListEntity,
        durChapter: Int = 0,
        durPage: Int = 0,
        tag: String = "https://a.example",
        noteUrl: String = "a.example/book/1.html",
    ) = BookShelfEntity(
        noteUrl = noteUrl,
        tag = tag,
        durChapter = durChapter,
        durChapterPage = durPage,
    ).apply {
        bookInfo = BookInfoEntity(noteUrl = this.noteUrl, name = "书")
        chapterList = chapters.toList()
    }

    @Test
    fun `adopt 从书架取位置并派生标题与滑条`() {
        val session = ReaderSession(FakeGateway())
        session.adopt(shelf(chapter(0), chapter(1), durChapter = 1, durPage = 3))

        val state = session.state.value
        assertEquals(1, state.chapterIndex)
        assertEquals(3, state.pageIndex)
        assertEquals(2, state.chapterCount)
        assertEquals("第2章", state.chapterTitle)
        assertEquals(2f, state.sliderValue)
        assertFalse(state.inShelf)
        assertFalse(state.shelfChecked)
    }

    @Test
    fun `adopt 写下阅读会话标记`() {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)

        session.adopt(shelf(chapter(0)))

        assertEquals(
            "收下哪本书就记哪本，页面不再各自补记",
            listOf("a.example/book/1.html"),
            gateway.markedNoteUrls,
        )
    }

    @Test
    fun `openFromStore 取不到书架行时不写标记`() = runTest {
        val gateway = FakeGateway().apply { detailShelf = null }
        val session = ReaderSession(gateway)

        assertFalse(session.openFromStore("a.example/book/1.html"))

        assertTrue("没打开任何书，标记不该被写", gateway.markedNoteUrls.isEmpty())
    }

    @Test
    fun `openFromStore 成功时写下标记`() = runTest {
        val gateway = FakeGateway().apply { detailShelf = shelf(chapter(0)) }
        val session = ReaderSession(gateway)

        assertTrue(session.openFromStore("a.example/book/1.html"))

        assertEquals(listOf("a.example/book/1.html"), gateway.markedNoteUrls)
    }

    @Test
    fun `换源收下新条目后标记改成新书的 noteUrl`() {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0), noteUrl = "a.example/book/1.html"))

        session.adoptAfterSourceSwitch(shelf(chapter(0), noteUrl = "b.example/book/2.html"))

        assertEquals(
            "换书必须重记标记，旧书标记留在残留状态",
            listOf("a.example/book/1.html", "b.example/book/2.html"),
            gateway.markedNoteUrls,
        )
    }

    @Test
    fun `saveProgress 不触碰阅读会话标记`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))
        gateway.markedNoteUrls.clear()

        session.saveProgress()

        assertTrue("进度落库与「要不要恢复」是两件事", gateway.markedNoteUrls.isEmpty())
    }

    @Test
    fun `updateProgress 只改位置，不改写 shelf 实体`() {
        val session = ReaderSession(FakeGateway())
        session.adopt(shelf(chapter(0), chapter(1)))

        session.updateProgress(1, 5)

        val state = session.state.value
        assertEquals(1, state.chapterIndex)
        assertEquals(5, state.pageIndex)
        assertEquals(0, state.shelf?.durChapter)
        assertEquals(0, state.shelf?.durChapterPage)
    }

    @Test
    fun `saveProgress 把当前位置合并进实体后落库`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        val adoptedShelf = shelf(chapter(0), chapter(1))
        session.adopt(adoptedShelf)
        session.updateProgress(1, 5)

        session.saveProgress()

        assertEquals(1, gateway.saved?.durChapter)
        assertEquals(5, gateway.saved?.durChapterPage)
        assertEquals("a.example/book/1.html", gateway.saved?.noteUrl)
        assertNotSame(adoptedShelf, gateway.saved)
        assertNotSame(session.state.value.shelf, gateway.saved)
    }

    @Test
    fun `saveProgress 交给网关的是副本，网关改写不影响会话`() = runTest {
        val gateway = FakeGateway()
        gateway.saveMutation = { it.durChapter = 99 }
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0), chapter(1)))
        session.updateProgress(1, 2)

        session.saveProgress()

        assertEquals(1, session.state.value.chapterIndex)
        assertEquals(0, session.state.value.shelf?.durChapter)
        // 网关拿到的确实是可被它改写的独立副本
        assertEquals(99, gateway.saved?.durChapter)
    }

    @Test
    fun `adopt 复制调用方实体，外部修改不影响会话`() {
        val session = ReaderSession(FakeGateway())
        val original = shelf(chapter(0), chapter(1))
        session.adopt(original)

        original.durChapter = 1
        original.durChapterPage = 7
        original.chapterList = emptyList()

        val state = session.state.value
        assertEquals(0, state.chapterIndex)
        assertEquals(0, state.pageIndex)
        assertEquals(2, state.chapterCount)
    }

    @Test
    fun `saveProgress 未 adopt 时是 no-op`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)

        session.saveProgress()

        assertNull(gateway.saved)
    }

    @Test
    fun `sliderValue 不超过章数`() {
        val session = ReaderSession(FakeGateway())
        session.adopt(shelf(chapter(0), chapter(1)))

        session.updateProgress(9, 0)

        assertEquals(2f, session.state.value.sliderValue)
    }

    @Test
    fun `sliderValue 下界与有效章保持原值`() {
        val empty = ReaderSession(FakeGateway())
        empty.adopt(shelf())
        assertEquals(1f, empty.state.value.sliderValue)

        val session = ReaderSession(FakeGateway())
        session.adopt(shelf(chapter(0), chapter(1)))
        session.updateProgress(1, 0)
        assertEquals(2f, session.state.value.sliderValue)
    }

    @Test
    fun `loadChapter 按索引取章并透传标题与 contentRef`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0), chapter(1)))

        val text = session.loadChapter(1)

        assertEquals("第2章", text?.title)
        assertEquals("https://a.example/chapter/2.html", text?.contentRef)
        // text 是展示文本：每段带 TextNormalizer.INDENT 全角缩进。
        assertEquals("　　正文", text?.text)
        assertEquals(1, gateway.loadCalls)
        assertNull(session.loadChapter(9))
    }

    @Test
    fun `loadChapter 空正文返回 null`() = runTest {
        val gateway = FakeGateway().apply { content = ChapterContent("", emptyList()) }
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))

        assertNull(session.loadChapter(0))
    }

    @Test
    fun `openFromStore 取不到行时不改状态`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))
        gateway.detailShelf = null

        assertFalse(session.openFromStore("a.example/book/1.html"))
        assertEquals(1, session.state.value.chapterCount)
        assertFalse(session.state.value.shelfChecked)
    }

    @Test
    fun `openFromStore 命中即落定在架状态`() = runTest {
        val gateway = FakeGateway()
        val storedShelf = shelf(chapter(0), chapter(1), durChapter = 1, durPage = 2)
        gateway.detailShelf = storedShelf
        val session = ReaderSession(gateway)

        assertTrue(session.openFromStore(storedShelf.noteUrl))
        val state = session.state.value
        assertEquals(1, state.chapterIndex)
        assertEquals(2, state.pageIndex)
        assertTrue(state.inShelf)
        assertTrue(state.shelfChecked)
    }

    @Test
    fun `openFromStore 存的是副本`() = runTest {
        val gateway = FakeGateway()
        val storedShelf = shelf(chapter(0), chapter(1), durChapter = 1, durPage = 2)
        gateway.detailShelf = storedShelf
        val session = ReaderSession(gateway)

        assertTrue(session.openFromStore(storedShelf.noteUrl))
        storedShelf.durChapter = 0
        storedShelf.durChapterPage = 0
        storedShelf.chapterList = emptyList()

        val state = session.state.value
        assertNotSame(storedShelf, state.shelf)
        assertEquals(1, state.chapterIndex)
        assertEquals(2, state.pageIndex)
        assertEquals(2, state.chapterCount)
    }

    @Test
    fun `checkInShelf 命中与未命中都落定 checked`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))

        gateway.row = shelf(chapter(0))
        session.checkInShelf()
        assertTrue(session.state.value.inShelf)
        assertTrue(session.state.value.shelfChecked)

        gateway.row = null
        session.checkInShelf()
        assertFalse(session.state.value.inShelf)
        assertTrue(session.state.value.shelfChecked)
    }

    @Test
    fun `checkInShelf 未 adopt 时落定 checked 且不在架`() = runTest {
        val session = ReaderSession(FakeGateway())

        session.checkInShelf()

        val state = session.state.value
        assertFalse(state.inShelf)
        assertTrue(state.shelfChecked)
    }

    @Test
    fun `checkInShelf 挂起期间换书时旧结论不落定到新书`() = runTest {
        val gateway = FakeGateway().apply { row = shelf(chapter(0)) }
        val gate = CompletableDeferred<Unit>()
        gateway.lookupGate = gate
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))

        val check = launch { session.checkInShelf() }
        runCurrent()
        // 归属查询已挂起在 getBookByUrl，此时换到另一本书；旧书的「在架」结论必须作废。
        session.adopt(
            shelf(chapter(0), tag = "https://b.example", noteUrl = "b.example/book/2.html"),
        )
        gate.complete(Unit)
        check.join()

        // 新书的归属由它自己的查询决定，不能被旧书的命中结果标成在架。
        assertFalse(session.state.value.inShelf)
        assertFalse(session.state.value.shelfChecked)
    }

    @Test
    fun `addToShelf 写入当前位置并置在架`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0), chapter(1)))
        session.updateProgress(1, 4)

        assertTrue(session.addToShelf())
        assertEquals(1, gateway.added?.durChapter)
        assertEquals(4, gateway.added?.durChapterPage)
        assertTrue(session.state.value.inShelf)
    }

    @Test
    fun `addToShelf 用当前时间而不是打开时快照的 finalDate`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)).apply { finalDate = 1L })

        assertTrue(session.addToShelf())
        assertTrue("加架应写入当前时间", (gateway.added?.finalDate ?: 0L) > 1L)
    }

    @Test
    fun `addToShelf 未 adopt 时返回 false`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)

        assertFalse(session.addToShelf())
        assertNull(gateway.added)
    }

    @Test
    fun `addToShelf 挂起期间换书时返回 false 且不标记新书`() = runTest {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Unit>()
        gateway.addGate = gate
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))

        var result: Boolean? = null
        val add = launch { result = session.addToShelf() }
        runCurrent()
        // 写入已挂起在 addToShelf，此时换到另一本书。
        session.adopt(
            shelf(chapter(0), chapter(1), tag = "https://b.example", noteUrl = "b.example/book/2.html"),
        )
        gate.complete(Unit)
        add.join()

        assertEquals(false, result)
        assertEquals("a.example/book/1.html", gateway.added?.noteUrl)
        assertFalse(session.state.value.inShelf)
        assertFalse(session.state.value.shelfChecked)
    }

    @Test
    fun `addToShelf 挂起期间追更换目录仍算当前书`() = runTest {
        val gateway = FakeGateway()
        val addGate = CompletableDeferred<Unit>()
        val syncGate = CompletableDeferred<Unit>()
        gateway.addGate = addGate
        gateway.syncGate = syncGate
        gateway.row = shelf(chapter(0))
        gateway.syncResult = ChapterSyncResult.Appended(listOf(chapter(1)))
        gateway.storedChapters = (0..1).map { chapter(it) }
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))

        var result: Boolean? = null
        val add = launch { result = session.addToShelf() }
        runCurrent()
        // 加架挂在写库期间，同一本书的追更回读目录：它会换掉 shelf 实例，但仍是同一本书
        val append = launch { session.appendIfAtTail() }
        runCurrent()
        syncGate.complete(Unit)
        append.join()
        addGate.complete(Unit)
        add.join()

        assertEquals(true, result)
        assertTrue("同一本书换了目录实例不算换书", session.state.value.inShelf)
        assertEquals(2, session.state.value.chapterCount)
    }

    @Test
    fun `refreshCurrentChapter 返回当前章页并调用 gateway`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0), chapter(1)))
        session.updateProgress(1, 3)

        assertEquals(1 to 3, session.refreshCurrentChapter())
        assertEquals("a.example/book/1.html" to 1, gateway.refreshed)
    }

    @Test
    fun `refreshCurrentChapter 未 adopt 时返回 null`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)

        assertNull(session.refreshCurrentChapter())
        assertNull(gateway.refreshed)
    }

    @Test
    fun `未 adopt 时追更返回 NotAtTail`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)

        assertEquals(ReaderAppendResult.NotAtTail, session.appendIfAtTail())
        assertEquals(0, gateway.syncCalls)
    }

    @Test
    fun `非末章不追更且不发网络`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0), chapter(1)))

        assertEquals(ReaderAppendResult.NotAtTail, session.appendIfAtTail())
        assertEquals(0, gateway.syncCalls)
    }

    @Test
    fun `末章但本地行缺失时静默跳过`() = runTest {
        val gateway = FakeGateway()
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))

        assertEquals(ReaderAppendResult.Skipped, session.appendIfAtTail())
        assertEquals(0, gateway.syncCalls)
    }

    @Test
    fun `末章但归属不符时静默跳过`() = runTest {
        val gateway = FakeGateway().apply {
            row = shelf(chapter(0), tag = "https://b.example")
        }
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0), tag = "https://a.example"))

        assertEquals(ReaderAppendResult.Skipped, session.appendIfAtTail())
        assertEquals(0, gateway.syncCalls)
    }

    @Test
    fun `末章但同步没有追加时静默跳过`() = runTest {
        val gateway = FakeGateway()
        gateway.row = shelf(chapter(0))
        gateway.syncResult = ChapterSyncResult.UpToDate
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))

        assertEquals(ReaderAppendResult.Skipped, session.appendIfAtTail())
        assertEquals(1, gateway.syncCalls)
        assertEquals(1, session.state.value.chapterCount)
    }

    @Test
    fun `末章归属一致时追加并回读目录`() = runTest {
        val gateway = FakeGateway()
        val local = shelf(chapter(0), durChapter = 0)
        gateway.row = shelf(chapter(0))
        gateway.syncResult = ChapterSyncResult.Appended(listOf(chapter(1), chapter(2)))
        gateway.storedChapters = (0..2).map { chapter(it) }
        val session = ReaderSession(gateway)
        session.adopt(local)

        assertEquals(ReaderAppendResult.Appended(2), session.appendIfAtTail())
        assertEquals(3, session.state.value.chapterCount)
        assertEquals(0, session.state.value.chapterIndex)
    }

    @Test
    fun `追更回读目录期间换书时跳过且不替换目录`() = runTest {
        val gateway = FakeGateway().apply {
            row = shelf(chapter(0))
            syncResult = ChapterSyncResult.Appended(listOf(chapter(1)))
            storedChapters = (0..1).map { chapter(it) }
        }
        val gate = CompletableDeferred<Unit>()
        gateway.syncGate = gate
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0)))

        var result: ReaderAppendResult? = null
        val append = launch { result = session.appendIfAtTail() }
        runCurrent()
        // 追更已挂起在 syncChapters，此时换到另一本书。
        val otherShelf = shelf(
            chapter(0, tag = "https://b.example"),
            chapter(1, tag = "https://b.example"),
            chapter(2, tag = "https://b.example"),
            tag = "https://b.example",
            noteUrl = "b.example/book/2.html",
        )
        session.adopt(otherShelf)
        gate.complete(Unit)
        append.join()

        assertEquals(ReaderAppendResult.Skipped, result)
        val state = session.state.value
        assertEquals("b.example/book/2.html", state.shelf?.noteUrl)
        assertEquals(3, state.chapterCount)
    }

    @Test
    fun `追更单飞时第二次直接跳过`() = runTest {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Unit>()
        gateway.syncGate = gate
        gateway.row = shelf(chapter(0))
        gateway.syncResult = ChapterSyncResult.UpToDate
        val session = ReaderSession(gateway)
        session.adopt(shelf(chapter(0), durChapter = 0))

        val first = launch { session.appendIfAtTail() }
        runCurrent()
        assertEquals(ReaderAppendResult.Skipped, session.appendIfAtTail())
        assertEquals(1, gateway.syncCalls)
        gate.complete(Unit)
        first.join()
    }

    @Test
    fun `换源后整体替换书架并把位置复位到新条目`() {
        val session = ReaderSession(FakeGateway())
        session.adopt(shelf(chapter(0), durChapter = 0))
        val newShelf = shelf(chapter(0), chapter(1), durChapter = 1, durPage = 0, tag = "https://b.example")

        session.adoptAfterSourceSwitch(newShelf)

        val state = session.state.value
        assertEquals(newShelf, state.shelf)
        assertNotSame(newShelf, state.shelf)
        assertEquals(1, state.chapterIndex)
        assertEquals(0, state.pageIndex)
        assertTrue(state.inShelf)
        assertTrue(state.shelfChecked)
    }
}
