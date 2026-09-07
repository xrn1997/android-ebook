# 失败处置的单一出口：文案口径与会话过期静默收口到 lib_book_common

2026-09-06 架构评审（`module_me` 逐模块迭代）定下：一次网络/业务失败**如何变成用户所见**，
收口在 `lib_book_common/src/main/java/com/ebook/common/util/FailureReport.kt` 的两个函数上——
`Throwable.userMessage()`（异常 → 用户可见文案，纯函数）与
`BaseViewModel<*>.reportFailure(exception, message = exception.userMessage())`（弹提示，或按会话过期静默）。
同时删除 `module_me` 私有的 `ViewModelErrors.kt` 与 `module_book`/`module_login` 里三份同体的
`private fun toastFailure(...)`：原先被重复的 **8 处会话过期静默分支**，收拢后落到 **13 个调用点**
（`module_me` 4、`module_book` 3、`module_login` 6），一律写 `reportFailure(...)`。

## 背景

被复制的不是几行样板，而是一条不变量：**A0230（access token 过期）由网络层收口**——单飞静默刷新，
刷不回来就发「会话过期」事件，由全局订阅方统一处置（清会话 + 提示 + 跳登录）。
因此每个业务调用点在 `onFailure` 里**必须只记日志、不再弹 Toast**，否则同一件事响两遍。
这条规则原先靠手写维持，散在三个模块的八个分支里：

- `module_me` 抽了个 `internal fun errorText(exception)` 管文案，但 `internal` 出了模块就看不见，
  于是 `module_book` 与 `module_login` 各自把同一段 `if (exception is ApiException) ... else ...` 内联重写；
- 静默分支（`isSessionExpiredHandled` → `Logger.w` → 跳过 Toast）在八处逐字抄写；
- 两份口径已经分叉：`module_me` 对本地异常取 `message.orEmpty()`，
  另两个模块写的是 `"${exception.message}"`——**消息为 null 的本地异常会把字面量 "null" 弹给用户**；
- 第九个 ViewModel 的作者必须先知道这条不变量存在，否则一定会写错。

## 决策

1. **两个函数，一个接口**：文案选取是纯函数（可在 JVM 直测，见 `UserMessageTest`），
   上报动作是 `BaseViewModel` 的扩展函数。调用点塌缩成一行 `reportFailure(it)`；
   需要固定前缀的（如「上传头像失败：%1$s」）由调用方拼好文案传入，前缀资源仍归各模块。
2. **落 `lib_book_common`，不落 `lib_common`**：这段逻辑依赖 `lib_ebook_api` 的
   `CoroutineAdapter.ApiException` 与 `SessionExpiredException`，是 ebook 域的契约；
   外部基类库 `io.github.xrn1997:common` 不认识它们，也不该为了本 App 的错误码模型改接口。
   按本仓的上浮判据（项目专属件被 ≥2 个功能模块使用才进 `lib_book_common`），三个模块八个调用点，早已过线。
3. **做成扩展函数而不是往基类加方法**：基类在另一个仓库、走 Maven 坐标联动，
   为一个消费面全在 ebook 域的能力去改外部库，代价与收益不匹配；扩展函数放在基类公开 API 之上，
   调用方读起来与成员方法无异。
4. **`reportFailure` 返回「是否为已静默的会话过期」**：「我的评论」页在两类失败下的覆盖层形态不同
   （过期时不摆「暂无数据」，交给全局跳转处置）。没有这个返回值，调用方就得把
   `isSessionExpiredHandled` 那条判断再抄一遍，收口等于白做。
5. **口径统一为 `message.orEmpty()`**：顺带修掉上面第 3 条分叉——无消息的本地异常现在弹空串而不是 "null"。

## 权衡

- **不把它塞进 `sendToast` 的包装层做成「VM 自动提示一切」**：只有失败需要静默分支，
  成功提示的文案仍由各调用方自己 `sendToast(资源)`。把两者捆成一个方法会让「弹什么」这件事
  失去调用方的控制，也把不属于认证域的假设塞进公共出口。
- **不用 `Result.onFailure` 的高阶封装（如 `result.reportOnFailure()`）**：调用点常要在失败分支里
  顺带收尾（收覆盖层、停下拉刷新、保留已有列表），传 lambda 进封装会让这些收尾藏进闭包；
  现状「一行上报 + 顺序执行收尾」更好读。
- **会话过期判断留在 `CoroutineAdapter` 的 companion 上**：它是网络层的产物，
  收口点只是**唯一消费者**而非拥有者；把 `SessionExpiredException` 搬来搬去会让网络层与共享层互相引用。

## 下游影响

- 13 个调用点改为一行 `reportFailure(...)`：`module_me` 评论 2 + 资料修改 2、`module_book` 评论区 3、
  `module_login` 登录 1 + 注册 2 + 改密 3；三份 `toastFailure` 私有实现与 `ViewModelErrors.kt` 全部删除。
- 用户可见的变化只有一条：无消息的本地异常不再弹字面量 "null"。
  **残留（已知未修）**：这类异常现在弹的是一条**空文案 Toast**——比 "null" 好，但仍不是通用兜底文案。
  做兜底要把字符串资源（即 `Context`）引进 `userMessage()` 这个纯函数层，与「口径是纯函数、可 JVM 直测」
  这个取舍相冲，本轮不做；有消息的本地异常显示的是英文系统文案（如 OkHttp 的 `Unable to resolve host`），
  那是收口之前就有的问题，同样不在本轮范围内。
- 新增 ViewModel 的失败分支一律写 `reportFailure(...)`，不再自己判 `isSessionExpiredHandled`；
  确需按两类失败分流收尾时用它的返回值。
- 人工验证项（Agent 止于构建）：① 未登录状态下进入需要登录的页面并触发一次评论删除/资料修改，
  期望只出现一次「登录已过期」类全局提示、业务页不再叠第二条 Toast；
  ② 用错误密码登录，期望 Toast 显示服务端业务文案（不含 `ApiException`、不含业务码）；
  ③ 断网触发一次头像上传，期望 Toast 为「上传头像失败：」加异常消息，且不出现 "null"。
- 测试：`UserMessageTest`（3 例）锁三条文案口径；`FailureReportTest`（2 例）锁分支判定——
  过期失败判为「已静默」、业务与本地异常都不属于该分支。把 `reportFailure` 的返回值写反就红
  （变异检验做过：改分支后测试失败、还原后通过）。这两例是给已落地的收口补测试网，不是测试先行。
  仍观测不到的只有提示内容与关页（命令通道在基类库侧是 internal），属上述人工验证项。
