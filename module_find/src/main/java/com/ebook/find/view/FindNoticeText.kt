package com.ebook.find.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ebook.find.R
import com.ebook.find.mvvm.viewmodel.FindNotice

/**
 * 列表页失败结论 → 用户可见文案（module_find 两个列表页**唯一**的文案处）。
 *
 * ViewModel 侧只发 [FindNotice]（语义 + 异常），句子在这里拼（ADR-0061）：
 * 页面文案优先进 `strings.xml`（带参/兜底模板留在那儿可译）；通用失败档沿用异常自带的
 * 用户可读消息，取不到才用资源兜底——这一档不随语言切换。写成 `when` 且**不写 else**——
 * 新增一档结论时这里编译不过，比运行时静默少弹一条好。
 *
 * 两个页面共用这一处而不是各写一份：它们走的是同一条加书架链路，
 * 同一个失败必须说同一句话（口径分列过一次就是这段分支原先在两个 VM 里逐字重复的老病根）。
 */
@Composable
internal fun findNoticeText(notice: FindNotice): String = when (notice) {
    // 书源失效的异常消息形如「书源已失效：<URL>」，直接把 message 上屏等于甩内部标识给用户
    is FindNotice.SourceInvalid -> stringResource(R.string.book_source_invalid)

    // 其余失败沿用异常自身消息（多为服务端/解析层的人话），取不到才兜底超时那句
    is FindNotice.AddBookFailed ->
        notice.cause.message ?: stringResource(R.string.network_request_timeout)
}
