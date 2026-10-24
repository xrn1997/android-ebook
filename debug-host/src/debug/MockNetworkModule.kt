package debug

import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetworkTest
import com.ebook.api.service.release.ReleaseDataSource
import com.ebook.api.service.release.ReleaseNetworkTest
import com.ebook.api.service.user.UserDataSource
import com.ebook.api.service.user.UserNetworkTest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 独立运行宿主共用的 mock 网络绑定。
 *
 * 五个模块原先各有一份，其中四份逐字相同、module_me 的副本多绑了 Release。这里合成一份：
 * User/Comment/Release 都绑到内存 mock，避免独立调试依赖后端与外网可达性。
 * 只在 `isModule=true` 时随共享源码目录参与编译，集成态不存在。
 */
@Module
@InstallIn(SingletonComponent::class)
interface MockNetworkModule {
    @Binds
    fun bindUser(impl: UserNetworkTest): UserDataSource

    @Binds
    fun bindComment(impl: CommentNetworkTest): CommentDataSource

    @Binds
    fun bindRelease(impl: ReleaseNetworkTest): ReleaseDataSource
}
