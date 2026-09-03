package com.ebook.book.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ebook.db.dao.BookContentDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookContentEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.LocBookShelfEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.universalchardet.UniversalDetector
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.math.BigInteger
import java.security.MessageDigest
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书籍导入管理器 - 处理本地 TXT 文件导入
 *
 * 封装：文件解析 → 章节识别 → 数据库存储，消除 BookImportModel 的 DAO 参数倾倒。
 */
@Singleton
class BookImportManager @Inject constructor(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookContentDao: BookContentDao
) {
    fun getFileName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) return cursor.getString(index)
                }
            }
        }
        return uri.path?.substringAfterLast('/') ?: "未知"
    }

    suspend fun importBook(context: Context, uri: Uri): LocBookShelfEntity = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val buffer = ByteArray(2048)
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                md.update(buffer, 0, len)
            }
        }

        val md5 = BigInteger(1, md.digest()).toString(16)
        val existing = bookShelfDao.getBookByUrl(md5)
        if (existing != null) {
            // chapterList/bookInfo 是 @Ignore 字段（不入库）：直接返回 DB 实体会缺失章节，
            // 导致阅读器 getChapterListSize()=0 无法分页，此处回填关联数据。
            existing.chapterList = chapterListDao.getChaptersForBook(existing.noteUrl)
            existing.bookInfo = bookInfoDao.getBookInfoByUrl(existing.noteUrl)
            return@withContext LocBookShelfEntity(false, existing)
        }

        val bookShelfEntity = BookShelfEntity(
            noteUrl = md5,
            durChapter = 0,
            durChapterPage = 0,
            finalDate = System.currentTimeMillis(),
            tag = BookShelfEntity.LOCAL_TAG
        )
        bookShelfDao.insert(bookShelfEntity)

        val bookInfoEntity = BookInfoEntity(
            name = getFileName(context, uri).replace(".txt", "").replace(".TXT", ""),
            tag = BookShelfEntity.LOCAL_TAG,
            noteUrl = md5,
            chapterUrl = "",
            finalRefreshData = System.currentTimeMillis(),
            coverUrl = "",
            author = "佚名",
            introduce = "",
            origin = "",
            status = ""
        )
        bookInfoDao.insert(bookInfoEntity)

        saveChapter(context, uri, md5)

        // 章节已写库，回填实体关联（@Ignore 字段），保证外部打开可直接阅读；
        // bookInfo 同步回填，供阅读器菜单/加入书架弹窗取名。
        bookShelfEntity.chapterList = chapterListDao.getChaptersForBook(md5)
        bookShelfEntity.bookInfo = bookInfoEntity

        LocBookShelfEntity(true, bookShelfEntity)
    }

    @Throws(IOException::class)
    private suspend fun saveChapter(context: Context, uri: Uri, md5: String) {
        val chapterRegex = Pattern.compile("第.{1,7}章.*")

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
        // 当前章节全部内容：段落间以 \n 分隔，每段首行以两个全角空格（　　）缩进
        val contentBuilder = StringBuilder()
        // 上一行是否为空行（段落分隔）——下一非空行按"新段落首行"处理
        var paraEnded = false
        // 是否当前章首段：首段前不加段落分隔 \n，仅补首行缩进，避免多出一个空段
        var firstPara = true

        context.contentResolver.openInputStream(uri)?.use { fis ->
            InputStreamReader(fis, encoding).use { reader ->
                BufferedReader(reader).use { br ->
                    br.lineSequence().forEach { rawLine ->
                        // trim 会一并移除行首行尾的全角缩进；判定段落边界需回看原始行
                        val line = rawLine.trim()
                        if (chapterRegex.matcher(line).find()) {
                            // 章节标题行：trim 已移走缩进与空白，"第"字须原样保留，
                            // 若用 substringAfter("第") 会截掉开头"第"（标题显示成"一章"）
                            val prefix = line.substringBefore("第").trim()
                            if (prefix.isNotEmpty()) contentBuilder.append(prefix)

                            if (contentBuilder.isNotEmpty()) {
                                val cleanContent = contentBuilder.toString()
                                    .replace(" ", "")
                                    .replace("\\s*".toRegex(), "")
                                    .trim()
                                if (cleanContent.isNotEmpty()) {
                                    saveDurChapterContent(
                                        md5, chapterIndex, title ?: "",
                                        contentBuilder.toString()
                                    )
                                    chapterIndex++
                                }
                                contentBuilder.clear()
                            }

                            paraEnded = false
                            firstPara = true
                            title = line
                        } else {
                            val cleanLine = line
                                .replace(" ", "")
                                .replace("\\s*".toRegex(), "")

                            if (cleanLine.isNotEmpty()) {
                                // 段落边界判定：前一行空行、或原书该行自带全角/两半角行首缩进。
                                // 命中则补段首缩进，避免源书靠缩进而非空行分段的正文首行顶格、段落粘连。
                                val ledByIndent = rawLine.startsWith("\u3000") ||
                                        rawLine.startsWith("  ")
                                val isNewPara = firstPara || paraEnded || ledByIndent
                                if (isNewPara) {
                                    if (!firstPara) contentBuilder.append("\n")
                                    contentBuilder.append("　　")
                                    paraEnded = false
                                    firstPara = false
                                    // 无"第x章"命名的书：以正文首行为章节标题
                                    if (title == null) title = line
                                }
                                contentBuilder.append(cleanLine)
                            } else {
                                // 空行：结束当前段落，下一非空行为新段落首行
                                paraEnded = true
                            }
                        }
                    }
                }
            }
        }

        if (contentBuilder.isNotEmpty()) {
            saveDurChapterContent(md5, chapterIndex, title ?: "", contentBuilder.toString())
        }
    }

    private suspend fun saveDurChapterContent(
        md5: String,
        chapterPageIndex: Int,
        name: String,
        content: String
    ) {
        val chapterUrl = md5 + "_" + chapterPageIndex
        val bookContentEntity = BookContentEntity(
            durChapterUrl = chapterUrl,
            durChapterIndex = chapterPageIndex,
            durChapterContent = content,
            tag = BookShelfEntity.LOCAL_TAG
        )
        bookContentDao.insert(bookContentEntity)

        val chapterListEntity = ChapterListEntity(
            noteUrl = md5,
            durChapterIndex = chapterPageIndex,
            durChapterUrl = chapterUrl,
            durChapterName = name,
            tag = BookShelfEntity.LOCAL_TAG,
            // 正文就在上一行写进了 book_content，has_cache 必须同步置 true：
            // 全仓的不变式是「写了正文就标缓存」（阅读器见 ReadBookActivity.loadPage、
            // 下载服务见 DownloadService.downloading），本地导入保持同一不变式，
            // 否则 has_cache 与内容表长期不一致（当前唯一消费方是下载管理页的
            // 「已缓存 y/z」覆盖率条，将来扩大使用范围时会直接误报）
            hasCache = true
        )
        chapterListDao.insertAll(listOf(chapterListEntity))
    }
}
