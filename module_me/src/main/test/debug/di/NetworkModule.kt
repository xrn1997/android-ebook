package debug.di

import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetworkTest
import com.ebook.api.service.user.UserDataSource
import com.ebook.api.service.user.UserNetworkTest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    @Binds
    fun bindUser(impl: UserNetworkTest): UserDataSource

    @Binds
    fun bindComment(impl: CommentNetworkTest): CommentDataSource
}