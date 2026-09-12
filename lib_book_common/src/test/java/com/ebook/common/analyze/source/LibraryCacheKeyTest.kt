package com.ebook.common.analyze.source

import com.ebook.common.event.LIBRARY_CACHE_KEY
import com.ebook.common.event.libraryCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书库缓存 key 形态的测试（[libraryCacheKey] 是纯函数，本组纯 JVM）。
 *
 * key 的**消费链**已随缓存重构分了层，各自的「真的按这个 key 读写」由对应的测试锁：
 * - 文件缓存的读写落在同一分区（写 A 读 B 必须 miss）——`LibraryDiskCacheTest`；
 * - 仓库层策略（TTL/SWR/强刷）真的读这份缓存——module_find 的 `BookSourceRepositoryLibraryCacheTest`。
 *
 * 本组只钉 key 自己的两条性质：
 * 1. 同一 URL 两次得到同一个 key（否则写完永远读不到，表现是每次进书城都在重拉网络、
 *    功能上却看不出毛病）；
 * 2. 不同 URL 得到不同 key，且带上源地址（否则两个源共用一个槽，「切了源没反应」）。
 */
class LibraryCacheKeyTest {

    private companion object {
        const val URL_A = "https://a.example"
        const val URL_B = "https://b.example"
    }

    @Test
    fun `同一书源两次生成的缓存键相同`() {
        assertEquals(libraryCacheKey(URL_A), libraryCacheKey(URL_A))
        assertTrue(
            "key 保留 LIBRARY_CACHE_KEY 作前缀（书库缓存条目的命名空间，文件名由它取 MD5）",
            libraryCacheKey(URL_A).startsWith("$LIBRARY_CACHE_KEY:"),
        )
    }

    @Test
    fun `不同书源生成的缓存键不同且各自带上源地址`() {
        assertNotEquals(libraryCacheKey(URL_A), libraryCacheKey(URL_B))
        assertEquals("$LIBRARY_CACHE_KEY:$URL_B", libraryCacheKey(URL_B))
    }
}
