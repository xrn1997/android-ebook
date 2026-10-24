package com.ebook.common.repository

import com.ebook.common.store.WriteTransactionRunner

/**
 * 不做真事务、只计数并原样执行 block 的事务接缝。
 *
 * 与 [DirectTransactionRunner] 分开：这里要的是「事务被开启了几次」，用于锁住导入只提交一笔；
 * 那个只需要接缝能跑通。
 */
class ImmediateTransactionRunner(private val onBegin: () -> Unit) : WriteTransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R {
        onBegin()
        return block()
    }
}
