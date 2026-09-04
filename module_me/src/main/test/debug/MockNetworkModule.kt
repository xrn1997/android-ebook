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
 * 独立运行宿主专用 mock 网络模块。
 *
 * 当 isModule=true 时本文件参与编译（source set 优先级高于 main/），
 * 将 UserDataSource 和 CommentDataSource 绑定到内存 mock 实现，
 * 无需后端服务器即可独立调试模块 UI。
 *
 * [ReleaseDataSource] 同样绑 mock：设置页的「检查更新」真实实现要打 GitHub/Gitcode，
 * 独立调试不该依赖外网可达性，固定资产 `release_latest.json` 让弹窗与角标可直接演练。
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
