package com.ebook.common.store

/**
 * 一个"把这些写当作一次事务提交"的接缝。
 *
 * 存在的理由有两个：一是全仓业务代码此前零使用事务，需要一个明确的收口而不是每处自己
 * `db.withWriteTransaction`；二是让导入器能在纯 JVM 测试里跑完整个"批量写"路径——直接
 * 依赖 `AppDatabase` 会把这段逻辑锁死在仪器测试里，而它恰恰是本轮最容易写错的地方
 * （提交顺序、原子性）。
 */
interface WriteTransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}
