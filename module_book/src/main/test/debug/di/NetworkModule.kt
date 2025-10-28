package debug.di

import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetworkTest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    @Binds
    fun bindComment(impl: CommentNetworkTest): CommentDataSource
}