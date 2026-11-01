package debug

import com.ebook.api.service.user.AccountPassword
import com.ebook.api.service.user.AccountProfile
import com.ebook.api.service.user.AccountSignIn
import com.ebook.api.service.user.SessionRefresh
import com.ebook.api.service.user.UserNetworkTest
import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetworkTest
import com.ebook.api.service.release.ReleaseDataSource
import com.ebook.api.service.release.ReleaseNetworkTest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 独立运行宿主共用的 mock 网络绑定。
 *
 * 五个模块原先各有一份，其中四份逐字相同、module_me 的副本多绑了 Release。这里合成一份：
 * User/Comment/Release 都绑到内存 mock，避免独立调试依赖后端与外网可达性。
 * 只在 `isModule=true` 时随共享源码目录参与编译，集成态不存在。
 *
 * 账号域已按调用方切成四个窄面（ADR-0059），这里四面都绑到同一个 `@Singleton UserNetworkTest`：
 * 独立宿主随时可能注入其中任何一面（module_login 订前两面、module_me 订资料面、
 * `SessionTokenRefresher` 订刷新面），少绑一面就是一次「找不到绑定」的启动崩溃。
 * [CommentDataSource] 仍绑整张 [CommentNetworkTest]（评论域不在本次切面范围内）。
 */
@Module
@InstallIn(SingletonComponent::class)
interface MockNetworkModule {
    @Binds
    @Singleton
    fun bindSignIn(impl: UserNetworkTest): AccountSignIn

    @Binds
    @Singleton
    fun bindPassword(impl: UserNetworkTest): AccountPassword

    @Binds
    @Singleton
    fun bindProfile(impl: UserNetworkTest): AccountProfile

    @Binds
    @Singleton
    fun bindSessionRefresh(impl: UserNetworkTest): SessionRefresh

    @Binds
    fun bindComment(impl: CommentNetworkTest): CommentDataSource

    @Binds
    fun bindRelease(impl: ReleaseNetworkTest): ReleaseDataSource
}
