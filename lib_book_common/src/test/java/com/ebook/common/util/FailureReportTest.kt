package com.ebook.common.util

import com.ebook.api.utils.CoroutineAdapter
import com.xrn1997.common.mvvm.model.NoOpModel
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [reportFailure] 的分支测试：锁住「会话过期已被全局处置 → 本处静默」这条不变量。
 *
 * 之所以值得单独锁：返回值不是装饰——「我的评论」页按它决定覆盖层形态（过期时不摆
 * 「暂无数据」，交给全局跳转处置），而整条收口的存在理由就是「过期不重复弹提示」。
 * 若哪天有人把判断写反，这里会红；只测 `userMessage` 测不到它。
 *
 * 诚实交代顺序：这两个函数先写完再补测试（收口当时只锁了纯函数侧），属给既有改动补网，
 * 不是测试先行。Toast/关页这类一次性命令在基类库侧是 internal，观测不到，
 * 因此本测试断言的是可观察的分支判定，不是提示内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FailureReportTest {

    /** 只为拿到 BaseViewModel 这个宿主：不持状态、不驱动任何流。 */
    private class ProbeViewModel : BaseViewModel<NoOpModel>(NoOpModel())

    @Test
    fun `会话过期的失败被判定为已全局处置，本处不得再弹提示`() {
        val viewModel = ProbeViewModel()

        val silenced = viewModel.reportFailure(
            CoroutineAdapter.SessionExpiredException(code = "A0230"),
        )

        assertTrue("过期失败必须走静默分支", silenced)
    }

    @Test
    fun `业务异常与本地异常都不属于静默分支，交给提示出口`() {
        val viewModel = ProbeViewModel()

        val apiSilenced = viewModel.reportFailure(
            CoroutineAdapter.ApiException(code = "A0158", message = "昵称已被占用"),
        )
        val localSilenced = viewModel.reportFailure(IOException("网络不可达"))

        assertFalse("业务异常要提示", apiSilenced)
        assertFalse("本地异常要提示", localSilenced)
    }
}
