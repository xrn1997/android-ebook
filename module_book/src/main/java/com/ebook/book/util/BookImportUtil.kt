package com.ebook.book.util

import com.ebook.db.ObjectBoxManager.bookShelfBox
import com.ebook.db.ObjectBoxManager.chapterListBox
import com.ebook.db.entity.BookContent
import com.ebook.db.entity.BookInfo
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.BookShelf_
import com.ebook.db.entity.ChapterList
import com.ebook.db.entity.ChapterList_
import com.ebook.db.entity.LocBookShelf
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import org.mozilla.universalchardet.UniversalDetector
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.math.BigInteger
import java.security.MessageDigest
import java.util.regex.Pattern

object BookImportUtil {
    fun importBook(book: File): Observable<LocBookShelf> {
        return Observable.create { e: ObservableEmitter<LocBookShelf> ->
            val md = MessageDigest.getInstance("MD5")
            val `in` = FileInputStream(book)
            val buffer = ByteArray(2048)
            var len: Int
            while ((`in`.read(buffer, 0, 2048).also { len = it }) != -1) {
                md.update(buffer, 0, len)
            }
            `in`.close()

            val md5 = BigInteger(1, md.digest()).toString(16)
            val bookShelfBox = bookShelfBox
            val bookShelf: BookShelf
            var temp: List<BookShelf>
            bookShelfBox.query(BookShelf_.noteUrl.equal(md5)).build().use { query ->
                temp = query.find()
            }
            var isNew = true
            if (temp.isNotEmpty()) {
                isNew = false
                bookShelf = temp[0]
            } else {
                bookShelf = BookShelf()
                bookShelf.finalDate = System.currentTimeMillis()
                bookShelf.durChapter = 0
                bookShelf.durChapterPage = 0
                bookShelf.tag = BookShelf.LOCAL_TAG
                bookShelf.noteUrl = md5

                val bookInfo = BookInfo()
                bookInfo.author = "佚名"
                bookInfo.name = book.name.replace(".txt", "").replace(".TXT", "")
                bookInfo.finalRefreshData = System.currentTimeMillis()
                bookInfo.coverUrl = ""
                bookInfo.noteUrl = md5
                bookInfo.tag = BookShelf.LOCAL_TAG
                bookShelf.bookInfo.target = bookInfo
                //保存章节
                saveChapter(book, md5)
                chapterListBox
                    .query(ChapterList_.noteUrl.equal(bookShelf.noteUrl))
                    .order(ChapterList_.durChapterIndex)
                    .build().use { query ->
                        for (chapterList in query.find()) {
                            bookShelf.bookInfo.target.chapterList.add(chapterList)
                        }
                    }
                bookShelfBox.put(bookShelf)
            }
            e.onNext(LocBookShelf(isNew, bookShelf))
            e.onComplete()
        }
    }

    @Suppress("unused")
    private fun isAdded(temp: BookShelf, shelf: List<BookShelf>?): Boolean {
        if (shelf.isNullOrEmpty()) {
            return false
        } else {
            var a = 0
            for (i in shelf.indices) {
                if (temp.noteUrl == shelf[i].noteUrl) {
                    break
                } else {
                    a++
                }
            }
            return a != shelf.size
        }
    }

    @Throws(IOException::class)
    private fun saveChapter(book: File, md5: String) {
        val regex = "第.{1,7}章.*"

        var encoding: String

        var fis = FileInputStream(book)
        val buf = ByteArray(4096)
        val detector = UniversalDetector(null)
        var nRead: Int
        while ((fis.read(buf).also { nRead = it }) > 0 && !detector.isDone) {
            detector.handleData(buf, 0, nRead)
        }
        detector.dataEnd()
        encoding = detector.detectedCharset
        if (encoding.isEmpty()) encoding = "utf-8"
        fis.close()

        var chapterPageIndex = 0
        var title: String? = null
        val contentBuilder = StringBuilder()
        fis = FileInputStream(book)
        val inputReader = InputStreamReader(fis, encoding)
        val buffReader = BufferedReader(inputReader)
        var line: String
        while ((buffReader.readLine().also { line = it }) != null) {
            val p = Pattern.compile(regex)
            val m = p.matcher(line)
            if (m.find()) {
                val temp =
                    line.trim { it <= ' ' }.substring(0, line.trim { it <= ' ' }.indexOf("第"))
                if (temp.trim { it <= ' ' }.isNotEmpty()) {
                    contentBuilder.append(temp)
                }
                if (contentBuilder.toString().isNotEmpty()) {
                    if (contentBuilder.toString().replace(" ".toRegex(), "")
                            .replace("\\s*".toRegex(), "").trim { it <= ' ' }.isNotEmpty()
                    ) {
                        saveDurChapterContent(
                            md5,
                            chapterPageIndex,
                            title!!,
                            contentBuilder.toString()
                        )
                        chapterPageIndex++
                    }
                    contentBuilder.delete(0, contentBuilder.length)
                }
                title = line.trim { it <= ' ' }.substring(line.trim { it <= ' ' }.indexOf("第"))
            } else {
                val temp =
                    line.trim { it <= ' ' }.replace(" ".toRegex(), "").replace(" ".toRegex(), "")
                        .replace("\\s*".toRegex(), "")
                if (temp.isEmpty()) {
                    if (contentBuilder.isNotEmpty()) {
                        contentBuilder.append("\r\n\u3000\u3000")
                    } else {
                        contentBuilder.append("\r\u3000\u3000")
                    }
                } else {
                    contentBuilder.append(temp)
                    if (title == null) {
                        title = line.trim { it <= ' ' }
                    }
                }
            }
        }
        if (contentBuilder.isNotEmpty()) {
            saveDurChapterContent(md5, chapterPageIndex, title!!, contentBuilder.toString())
            contentBuilder.delete(0, contentBuilder.length)
        }
        buffReader.close()
        inputReader.close()
        fis.close()
    }

    private fun saveDurChapterContent(
        md5: String,
        chapterPageIndex: Int,
        name: String,
        content: String
    ) {
        val chapterList = ChapterList()
        chapterList.noteUrl = md5
        chapterList.durChapterIndex = chapterPageIndex
        chapterList.tag = BookShelf.LOCAL_TAG
        chapterList.durChapterUrl = md5 + "_" + chapterPageIndex
        chapterList.durChapterName = name

        val bookContent = BookContent()
        bookContent.durChapterUrl = chapterList.durChapterUrl
        bookContent.tag = BookShelf.LOCAL_TAG
        bookContent.durChapterIndex = chapterList.durChapterIndex
        bookContent.durChapterContent = content
        chapterList.bookContent.target = bookContent
        chapterListBox.put(chapterList)
    }
}