package com.ebook.common.di

import androidx.room3.withWriteTransaction
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** [WriteTransactionRunner] 的生产实现：Room 3 的写事务（IMMEDIATE） */
@Module
@InstallIn(SingletonComponent::class)
object TransactionModule {

    @Provides
    @Singleton
    fun provideWriteTransactionRunner(db: AppDatabase): WriteTransactionRunner =
        object : WriteTransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R =
                db.withWriteTransaction { block() }
        }
}
