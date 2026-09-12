package com.ebook.common.analyze.source

import com.ebook.common.event.libraryCacheKey
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LibraryDiskCache] 的存取契约测试（纯 JVM + 临时目录，不碰 Android API——这正是构造只收
 * `File` 的意义，见其 KDoc）。
 *
 * 从旧 `LibraryCacheKeyTest` 手里接棒「缓存按源分区」的锁：key 生成仍唯一收在
 * [libraryCacheKey]，这里锁的是**文件版缓存的读写两侧真的落在同一个分区**——
 * 写 A 源读 B 源必须 miss，否则就是「切了源却看到上一源书目」的静默串数据
 * （缓存命中是成功路径，没有异常没有日志，只有测试能抓住）。
 *
 * 另外三条旧测试锁不到、只有文件版才有的形态也一并钉住：**覆盖写**（Windows 上
 * `File.renameTo` 对已存在目标直接失败，本类用 `Files.move(REPLACE_EXISTING)`，这条
 * 单测就是防回退的哨兵）、**损坏文件自愈**（坏 JSON 按未命中处理且顺手删除）与
 * **临时文件不累积**（写一半被杀留下的 `.tmp` 要被下次写入消费掉，而不是各留一份）。
 */
class LibraryDiskCacheTest {

    private companion object {
        const val URL_A = "https://a.example"
        const val URL_B = "https://b.example"
    }

    /** 每个用例独立目录：书库缓存写入会真实落盘，共用目录会让用例间互相看见对方的文件 */
    private fun tempCacheWithDir(): Pair<LibraryDiskCache, File> {
        val dir = Files.createTempDirectory("library-cache").toFile().apply { deleteOnExit() }
        return LibraryDiskCache(dir) to dir
    }

    /** 一份带两个分类、每分类两本书的书库数据：字段取全（12 个映射字段都用上），锁往返不丢 */
    private fun libraryOf(marker: String) = LibraryEntity().apply {
        kindBooks = listOf(
            LibraryKindBookListEntity(
                kindName = "玄幻@$marker",
                kindUrl = "/xuanhuan",
                books = listOf(
                    SearchBookEntity(
                        noteUrl = "$marker/book/1", name = "书一@$marker", author = "甲",
                        coverUrl = "$marker/cover/1.jpg", words = 12345, state = "连载",
                        lastChapter = "第 3 章", tag = marker, kind = "玄幻", origin = "源@$marker",
                        desc = "简介@$marker",
                    ),
                    SearchBookEntity(noteUrl = "$marker/book/2", name = "书二@$marker"),
                ),
            ),
            LibraryKindBookListEntity("都市@$marker", "/dushi", emptyList()),
        )
    }

    @Test
    fun `写入后能按同一书源读回等价数据`() {
        val (cache, _) = tempCacheWithDir()

        cache.write(URL_A, libraryOf(URL_A))

        val entry = cache.read(URL_A)
        assertNotNull("刚写完就读必须命中", entry)
        assertEquals("往返不丢字段：12 个映射字段全都在 DTO 里", libraryOf(URL_A).kindBooks, entry!!.data.kindBooks)
    }

    @Test
    fun `不同书源各写各的缓存槽互不可见`() {
        val (cache, _) = tempCacheWithDir()
        cache.write(URL_A, libraryOf(URL_A))

        assertNull("B 源没写过就是 miss——共用槽时这里会读到 A 的书目（无异常无日志的串数据）", cache.read(URL_B))

        cache.write(URL_B, libraryOf(URL_B))
        assertEquals(
            "两个源各回各的：分区错了的话 B 的屏幕会显示 A 的书",
            listOf("玄幻@$URL_B", "都市@$URL_B"),
            cache.read(URL_B)!!.data.kindBooks!!.map { it.kindName },
        )
    }

    @Test
    fun `缓存目录不存在时读返回空而不抛`() {
        // 构造后从未 write 过：目录还没被 mkdirs，read 必须安静地 miss
        val cache = LibraryDiskCache(File(Files.createTempDirectory("library-cache-root").toFile(), "not-created"))

        assertNull(cache.read(URL_A))
    }

    @Test
    fun `缓存文件损坏时读返回空且不抛`() {
        val (cache, dir) = tempCacheWithDir()
        cache.write(URL_A, libraryOf(URL_A))
        // 直接把落盘文件改写成坏 JSON：模拟「写一半进程被杀」在原子写覆盖不到的窗口外留下的残骸
        val file = File(dir, md5HexOf(libraryCacheKey(URL_A)) + ".json")
        assertTrue("前置：文件确实存在", file.isFile)
        file.writeText("{ 这不是 JSON")

        assertNull("坏文件按未命中处理，不许把序列化异常抛给调用方", cache.read(URL_A))
        assertFalse("坏文件要顺手删掉自愈：留着只会让每次进书城白 parse 一遍再失败", file.exists())
    }

    @Test
    fun `覆盖写后读回的是新数据`() {
        val (cache, _) = tempCacheWithDir()
        cache.write(URL_A, libraryOf("old"))
        cache.write(URL_A, libraryOf("new"))

        assertEquals(
            "同源第二次写入是覆盖（Files.move REPLACE_EXISTING）。Windows 上 File.renameTo 对已存在"
                + "目标返回 false，若退回那种写法这条用例会红——它是文件版缓存的平台哨兵",
            listOf("玄幻@new", "都市@new"),
            cache.read(URL_A)!!.data.kindBooks!!.map { it.kindName },
        )
    }

    @Test
    fun `读出的条目携带写入时的时间戳`() {
        val (cache, _) = tempCacheWithDir()
        val savedAt = 1_700_000_000_000L

        cache.write(URL_A, libraryOf(URL_A), savedAtMillis = savedAt)

        assertEquals(
            "savedAtMillis 如实带回（过期判断是仓库层的策略，存储层只负责不撒谎）",
            savedAt,
            cache.read(URL_A)!!.savedAtMillis,
        )
    }

    @Test
    fun `缓存文件名是 libraryCacheKey 的 MD5 而非明文 URL`() {
        val (cache, dir) = tempCacheWithDir()
        cache.write(URL_A, libraryOf(URL_A))

        val files = dir.listFiles().orEmpty().map { it.name }
        assertEquals("同源一次写入只落一个文件（覆盖而非追加）", 1, files.size)
        // 期望值在测试里独立计算并指明输入是 libraryCacheKey(URL_A)：如果实现改成对裸 URL 做
        // MD5（跳过 key 函数），文件名会变、这条会红——锁的是「文件名派生自唯一的 key 函数」
        assertEquals(md5HexOf(libraryCacheKey(URL_A)) + ".json", files.single())
        assertFalse("URL 明文不进文件名（非法字符/超长/可读性）", files.single().contains("example"))
    }

    @Test
    fun `上次写入被杀留下的临时文件会被下次写入消费掉`() {
        val (cache, dir) = tempCacheWithDir()
        // 模拟「move 之前进程被杀」：确定名残骸留在目录里
        val leftover = File(dir, md5HexOf(libraryCacheKey(URL_A)) + ".json.tmp")
        leftover.writeText("{ 半截 JSON")

        cache.write(URL_A, libraryOf(URL_A))

        assertFalse(
            "临时文件用目标名 + .tmp 的确定名，故被本次 move 消费掉。改回 createTempFile 的随机"
                + "后缀这条就红——那时每泄漏一次目录里就多一个 read 永远看不见的文件",
            leftover.exists(),
        )
        assertEquals("目录里只该剩那一份缓存文件", 1, dir.listFiles().orEmpty().size)
        assertNotNull("消费完残骸后照常读得回", cache.read(URL_A))
    }

    /** 与实现同算法的 MD5 十六进制：测试里独立持有，实现漂移（如换哈希）时用例能给红 */
    private fun md5HexOf(key: String): String =
        MessageDigest.getInstance("MD5").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
