package com.ebook.common.importer

import com.ebook.common.domain.DuplicateBookDetector.ImportMatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispositionPolicyTest {

    private fun local() = ImportMatch("a", "书", "作者", isLocal = true)
    private fun network() = ImportMatch("b", "书", "作者", isLocal = false)

    @Test
    fun `无命中时没有智能合并`() {
        val available = DispositionPolicy.available(emptyList())
        assertTrue(available.containsAll(setOf(
            DuplicateResolution.KEEP_BOTH, DuplicateResolution.OVERWRITE, DuplicateResolution.CANCEL,
        )))
        assertFalse(DuplicateResolution.MERGE in available)
        assertFalse(DispositionPolicy.canMerge(emptyList()))
    }

    @Test
    fun `只有网络书命中时没有智能合并`() {
        val matches = listOf(network())
        assertFalse(DuplicateResolution.MERGE in DispositionPolicy.available(matches))
        assertFalse(DispositionPolicy.canMerge(matches))
    }

    @Test
    fun `本地书命中时提供智能合并`() {
        val matches = listOf(local())
        assertTrue(DuplicateResolution.MERGE in DispositionPolicy.available(matches))
        assertTrue(DispositionPolicy.canMerge(matches))
    }

    @Test
    fun `混合命中时提供智能合并`() {
        val matches = listOf(network(), local())
        assertTrue(DuplicateResolution.MERGE in DispositionPolicy.available(matches))
        assertTrue(DispositionPolicy.canMerge(matches))
    }
}
