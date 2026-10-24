package com.ebook.book.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入页预览夹具的契约测试。
 *
 * 夹具随特性下沉到本模块后，原先钉在共享门面出口的两条不变量也一并搬来：处置框的
 * 「本地 / 仅网络」两档必须真的只差一条本地命中，列表样例必须只给路径、不落磁盘。
 */
class ImportPreviewSamplesTest {

    /**
     * 处置框里「智能合并」那一档完全由 `matches.any { it.isLocal }` 决定，这是导入页唯一的分岔点：
     * 样例若把两档做成同一份，两张预览图就会画一样的东西，而「网络书命中却给出合并入口」
     * 这个静默错再也没有地方能看出来。
     *
     * 命中项**整列**给出也是契约的一部分：同一 `comment_key` 下可能挂着多个条目，
     * 处置框只展示第一条就等于让用户在看不见后果的情况下按「覆盖」。
     */
    @Test
    fun `判重样例的本地与仅网络两档只差一条本地命中`() {
        val withLocal = sampleImportDuplicate(hasLocalMatch = true)
        val networkOnly = sampleImportDuplicate(hasLocalMatch = false)

        assertTrue(
            "「有本地命中」这一档没给出本地条目：处置框不会画「智能合并」",
            withLocal.matches.any { it.isLocal },
        )
        assertTrue(
            "「仅网络」这一档混进了本地命中：处置框会给出对网络书毫无意义的「智能合并」",
            networkOnly.matches.none { it.isLocal },
        )
        assertEquals(2, withLocal.matches.size)
        assertEquals(1, networkOnly.matches.size)
        assertEquals(withLocal.meta.title, withLocal.matches.first().title)
        assertNotNull("待导入文件为空：处置框的正文句没有主语", networkOnly.file.name)
    }

    /**
     * 本地导入样例给的是**路径而非磁盘文件**，且逐行不同名。
     *
     * 名字重复会让列表预览看不出省略号与行高；而「不落磁盘」这条是刻意的（设计期渲染环境不该做 IO），
     * 于是体积那一格恒为 `0 B`——这里把它钉成契约，免得下次有人为了"看起来真一点"往预览里加写盘。
     */
    @Test
    fun `本地导入样例只给路径且逐行不同名`() {
        val files = sampleLocalBookFiles(3)

        assertEquals(3, files.size)
        assertEquals(
            "样例文件名重复，列表预览看不出省略号与逐行差异",
            files.size,
            files.map { it.name }.distinct().size,
        )
        assertTrue(
            "样例路径不在存储根前缀下：条目的路径回落那一档永远不会出现",
            files.all { it.path.replace('\\', '/').startsWith(SAMPLE_LOCAL_STORAGE_ROOT) },
        )
        assertTrue("样例文件竟然落在宿主机磁盘上：预览已经开始做 IO", files.none { it.exists() })
    }
}
