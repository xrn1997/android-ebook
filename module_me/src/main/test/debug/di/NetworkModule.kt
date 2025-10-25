package debug.di

import com.ebook.api.service.user.TestUserNetwork
import com.ebook.api.service.user.UserDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    @Binds
    fun binds(impl: TestUserNetwork): UserDataSource
}