package com.ebook.book.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [BitIntentDataManager] 的键生成与取用协议回归测试（纯 JVM，无 Android 依赖）。
 *
 * 锁定两条口径：
 * - **key 唯一性**：旧实现用 `System.currentTimeMillis()` 作 key，两次连续写入落在同一毫秒就会
 *   互相覆盖——表现是「从书架长按进详情页，读到的是另一本书的目录」。现改为进程内 AtomicLong
 *   自增，本测试用高频连写钉住它（时间基准做不到这件事）。
 * - **取用协议**：`getData` 返回写入时的同一实例（不是副本，副本会丢掉整份章节列表的共享语义），
 *   `cleanData` 只影响目标 key，清理后再取为 null——消费方据此走「数据缺失」分支。
 *
 * 本类是进程级单例，测试之间共享同一个 map，故每个用例都清掉自己写入的条目，并且只断言
 * 「互不相同」「能否取到」这类与绝对取值无关的性质，不依赖用例执行顺序。
 */
class BitIntentDataManagerTest {

    @Test
    fun `连续写入产出的 key 互不相同，即使全部落在同一毫秒`() {
        val keys = (1..2000).map { BitIntentDataManager.putData("payload-$it") }
        try {
            assertEquals("存在重复 key，写入会互相覆盖", keys.size, keys.toSet().size)
            keys.forEachIndexed { index, key ->
                // 写入用的是 1..2000，这里的下标是 0 基，取载荷时要 +1
                assertEquals("payload-${index + 1}", BitIntentDataManager.getData(key))
            }
        } finally {
            keys.forEach { BitIntentDataManager.cleanData(it) }
        }
    }

    @Test
    fun `getData 返回写入的同一实例，cleanData 之后取不到`() {
        val payload = Any()
        val key = BitIntentDataManager.putData(payload)
        try {
            assertSame(payload, BitIntentDataManager.getData(key))
        } finally {
            BitIntentDataManager.cleanData(key)
        }
        assertNull("清理后仍能取到实体，暂存区在泄漏", BitIntentDataManager.getData(key))
    }

    @Test
    fun `未写入过的 key 取值为 null，消费方可据此快速失败`() {
        assertNull(BitIntentDataManager.getData("never-used-key"))
    }

    @Test
    fun `清理只影响目标 key，不波及其他在途传递`() {
        val consumed = Any()
        val inFlight = Any()
        val consumedKey = BitIntentDataManager.putData(consumed)
        val inFlightKey = BitIntentDataManager.putData(inFlight)
        try {
            BitIntentDataManager.cleanData(consumedKey)
            assertNull(BitIntentDataManager.getData(consumedKey))
            assertSame("同批另一条在途传递被误清理", inFlight, BitIntentDataManager.getData(inFlightKey))
        } finally {
            BitIntentDataManager.cleanData(inFlightKey)
        }
    }
}
