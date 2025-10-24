package com.ebook.book.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import java.net.URI
import java.security.MessageDigest
import java.util.regex.Pattern

object BookImportUtil {
    fun getFileName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) return cursor.getString(index)
                }
            }
        }
        // fallback: file path
        return uri.path?.substringAfterLast('/')?:"未知"
    }

    fun importBook(context: Context,uri: Uri): Observable<LocBookShelf> {
        return Observable.create { e: ObservableEmitter<LocBookShelf> ->
            val md = MessageDigest.getInstance("MD5")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(2048)
                var len: Int
                while (inputStream.read(buffer).also { len = it } != -1) {
                    md.update(buffer, 0, len)
                }
            }

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
                bookInfo.name = getFileName(context,uri).replace(".txt", "").replace(".TXT", "")
                bookInfo.finalRefreshData = System.currentTimeMillis()
                bookInfo.coverUrl = ""
                bookInfo.noteUrl = md5
                bookInfo.tag = BookShelf.LOCAL_TAG
                bookShelf.bookInfo.target = bookInfo
                //保存章节
                saveChapter(context,uri, md5)
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
    private fun saveChapter(context: Context,uri: Uri, md5: String) {
        val chapterRegex = Pattern.compile("第.{1,7}章.*")

        // ---------- 自动检测编码 ----------
        val encoding = context.contentResolver.openInputStream(uri)?.use { fis ->
            val buf = ByteArray(4096)
            val detector = UniversalDetector(null)
            var nRead: Int
            while (fis.read(buf).also { nRead = it } > 0 && !detector.isDone) {
                detector.handleData(buf, 0, nRead)
            }
            detector.dataEnd()
            detector.detectedCharset ?: "utf-8"
        }

        var chapterIndex = 0
        var title: String? = null
        val contentBuilder = StringBuilder()

        // ---------- 读取并解析章节 ----------
        context.contentResolver.openInputStream(uri)?.use { fis ->
            InputStreamReader(fis, encoding).use { reader ->
                BufferedReader(reader).use { br ->
                    br.lineSequence().forEach { rawLine ->
                        val line = rawLine.trim()
                        val matcher = chapterRegex.matcher(line)

                        if (matcher.find()) {
                            // 当前行匹配“第X章”
                            val prefix = line.substringBefore("第").trim()
                            if (prefix.isNotEmpty()) contentBuilder.append(prefix)

                            if (contentBuilder.isNotEmpty()) {
                                val cleanContent = contentBuilder.toString()
                                    .replace(" ", "")
                                    .replace("\\s*".toRegex(), "")
                                    .trim()
                                if (cleanContent.isNotEmpty()) {
                                    saveDurChapterContent(md5, chapterIndex, title ?: "", contentBuilder.toString())
                                    chapterIndex++
                                }
                                contentBuilder.clear()
                            }

                            title = line.substring(line.indexOf("第"))
                        } else {
                            // 非章节标题行，拼接正文内容
                            val cleanLine = line
                                .replace(" ", "")
                                .replace(" ", "")
                                .replace("\\s*".toRegex(), "")

                            if (cleanLine.isEmpty()) {
                                // 段落空行：添加段首空格
                                contentBuilder.append(
                                    if (contentBuilder.isNotEmpty()) "\r\n\u3000\u3000" else "\r\u3000\u3000"
                                )
                            } else {
                                contentBuilder.append(cleanLine)
                                if (title == null) title = line
                            }
                        }
                    }
                }
            }
        }

        // ---------- 保存最后一章 ----------
        if (contentBuilder.isNotEmpty()) {
            saveDurChapterContent(md5, chapterIndex, title ?: "", contentBuilder.toString())
        }
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