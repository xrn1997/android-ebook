package com.ebook.me.repository

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [CacheModel] 的分类、差值与清理规则——纯 JVM，拿临时目录当 `cacheDir`。
 *
 * 这些规则原先只能在设备上验：模型收的是 `Application`，`cacheDir` 拿不到就别无选择。
 * 换成收一个目录参数（照 `lib_book_common` 的 `BookStore` 的做法：不碰 `Context`，
 * 生产装配点做一次 `Context → File` 转换）之后，分类口径终于能在这一层锁死——
 * 而「什么算图片缓存、什么算临时文件」恰恰是缓存管理页唯一会被改动、改错了就少算或多算
 * 用户磁盘占用的那段逻辑。
 *
 * 锁住四条：
 * - Coil 的 `image_cache`（连同老版本遗留的 Glide 目录）计入 IMAGE，明细以**目录**为粒度；
 * - 根目录松散文件计入 TEMP，明细以**文件**为粒度（`cropped_*.jpg` 这类名字对用户有意义）；
 * - 其余子目录计入 OTHER，且清 OTHER 不得连带删掉图片缓存目录；
 * - 明细按大小降序（用户最关心占空间的内容）。
 */
class CacheModelTest {

    @JvmField
    @Rule
    val tempFolder = TemporaryFolder()

    /** 建出父目录并写入 size 个字节 */
    private fun File.touch(size: Int): File {
        parentFile?.mkdirs()
        writeBytes(ByteArray(size))
        return this
    }

    /** 一棵覆盖四个分类的目录树：图片 1000+500、临时 300、其他 200 */
    private fun buildTree(root: File) {
        File(root, "image_cache/cover_1.bin").touch(1_000)
        File(root, "image_manager_disk_cache/legacy_1.bin").touch(500)
        File(root, "cropped_1700000000000.jpg").touch(300)
        File(root, "import/scratch.tmp").touch(200)
    }

    @Test
    fun `分类按图片目录、根目录松散文件、其余子目录三档统计`() = runTest {
        val root = tempFolder.newFolder()
        buildTree(root)
        val model = CacheModel(root)

        val breakdown = model.cacheBreakdown()

        assertEquals("Coil 目录与老 Glide 残留都归图片", 1_500L, breakdown.imageBytes)
        assertEquals(300L, breakdown.tempBytes)
        assertEquals(200L, breakdown.otherBytes)
        assertEquals(2_000L, breakdown.totalBytes)
        assertEquals("总量与递归遍历整棵树一致", 2_000L, model.cacheSizeBytes())
    }

    @Test
    fun `临时文件按文件列明细、图片按目录列明细`() = runTest {
        val root = tempFolder.newFolder()
        buildTree(root)
        val model = CacheModel(root)

        val temp = model.cacheEntries(CacheType.TEMP)
        val image = model.cacheEntries(CacheType.IMAGE)

        assertEquals(
            "TEMP 以文件为粒度（文件名对用户有意义）",
            listOf(
                CacheEntry("cropped_1700000000000.jpg", 300L, isDirectory = false),
            ),
            temp,
        )
        assertEquals(
            "IMAGE 以目录为粒度（内部文件名是 hash，给用户看没有意义）",
            listOf(
                CacheEntry("image_cache", 1_000L, isDirectory = true),
                CacheEntry("image_manager_disk_cache", 500L, isDirectory = true),
            ),
            image,
        )
    }

    @Test
    fun `明细按大小降序排列`() = runTest {
        val root = tempFolder.newFolder()
        File(root, "import/small.tmp").touch(10)
        File(root, "databases/big.db").touch(5_000)
        File(root, "middle/mid.bin").touch(500)
        val model = CacheModel(root)

        val entries = model.cacheEntries(CacheType.OTHER)

        assertEquals(
            listOf("databases", "middle", "import"),
            entries.map { it.name },
        )
    }

    @Test
    fun `清理其他缓存不连带删掉图片缓存目录`() = runTest {
        val root = tempFolder.newFolder()
        buildTree(root)
        val model = CacheModel(root)

        model.clearOtherCache()

        assertTrue("图片缓存必须留下（用户单独清的是「其他」）", File(root, "image_cache").exists())
        assertFalse("未识别子目录应被删除", File(root, "import").exists())
        assertTrue("根目录松散文件归 TEMP，不受 OTHER 清理影响", File(root, "cropped_1700000000000.jpg").exists())
        assertEquals(1_500L, model.cacheBreakdown().imageBytes)
    }

    @Test
    fun `清理图片缓存只删图片目录`() = runTest {
        val root = tempFolder.newFolder()
        buildTree(root)
        val model = CacheModel(root)

        model.clearImageCache()

        assertFalse(File(root, "image_cache").exists())
        assertFalse("老版本遗留的 Glide 目录一并回收", File(root, "image_manager_disk_cache").exists())
        assertTrue(File(root, "import").exists())
        assertEquals(0L, model.cacheBreakdown().imageBytes)
        assertEquals("剩下的松散文件归 TEMP、其余子目录归 OTHER", 200L, model.cacheBreakdown().otherBytes)
        assertEquals(300L, model.cacheBreakdown().tempBytes)
    }

    @Test
    fun `清理全部后占用归零且目录本身仍在`() = runTest {
        val root = tempFolder.newFolder()
        buildTree(root)
        val model = CacheModel(root)

        model.clearCache()

        assertEquals(0L, model.cacheSizeBytes())
        assertTrue("清的是子项，cacheDir 本身不该被删掉", root.exists())
        assertTrue(root.isDirectory)
    }

    @Test
    fun `空缓存目录不抛异常且各项归零`() = runTest {
        val root = tempFolder.newFolder()
        val model = CacheModel(root)

        val breakdown = model.cacheBreakdown()

        assertEquals(0L, breakdown.totalBytes)
        assertEquals(0L, breakdown.imageBytes)
        assertEquals(0L, breakdown.otherBytes)
        assertEquals(emptyList<CacheEntry>(), model.cacheEntries(CacheType.IMAGE))
    }

    @Test
    fun `缓存目录不存在时按零处理而不是崩`() = runTest {
        val missing = File(tempFolder.root, "not-created-yet")
        val model = CacheModel(missing)

        assertEquals(0L, model.cacheSizeBytes())
        assertEquals(0L, model.cacheBreakdown().totalBytes)
        model.clearCache()
        model.clearImageCache()
    }
}
