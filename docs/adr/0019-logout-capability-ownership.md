# 登出能力归属：服务端作废经 ILoginProvider，本地清理仍由 clearSession 单点

2026-09-03 评审（grill 会话）定下：**登出的两半分属两处**——服务端 refresh token 作废经 `ILoginProvider.logout()`
跨模块取用（接口在 `lib_book_common`，实现在 `module_login`），本地会话清理仍由 `UserSessionManager.clearSession()`
单点负责，provider 不重复它。调用方固定两行写法：**先尽力作废服务端（失败只记日志），再无条件清本地**。
同时删除 `ILoginProvider` 里零调用方的 `login()`。

2026-09-06 评审就地修订：两行写法与「先服务端后本地」的顺序不变，但**执行它的作用域由页面改到 ViewModel**
（原处方在旋转时会让登出静默失败，见权衡⑤）。归属结论（provider 承载服务端那一半、`clearSession()`
单点清本地）不受影响。

## 背景

- `docs/login-modernization-spec.md` 要求登出作废服务端该用户的全部 refresh token，服务端 `POST /api/auth/logout`
  已实现（见该文档端点表），客户端 `UserDataSource.logout()` → `UserRepository.logout()` 一路也已就绪，
  但**零调用方**：此前的会话失效点（`module_me` 设置页的退出登录、`module_login` 改密成功后的清会话）
  只做本地清理，服务端凭证留活——换设备后旧 refresh token 仍可用。
- 跨模块障碍：登出按钮在 `module_me`，而持有该端点的仓库在 `module_login`。功能模块互不依赖
  （依赖方向见 AGENTS.md「模块架构」），`module_me` 拿不到 `UserRepository`。

## 决策

1. **复用既有的 TheRouter SPI 通道**，不新建依赖边、不新建共享仓库：接口
   `lib_book_common/src/main/java/com/ebook/common/provider/ILoginProvider.kt`，实现
   `module_login/src/main/java/com/ebook/login/provider/LoginProvider.kt`（`@ServiceProvider`）。
   Provider 由 TheRouter 创建（非 Hilt），仓库实例经**已存在的** `UserRepositoryEntryPoint`
   （定义在 `module_login/.../repository/UserRepository.kt`）以 `EntryPointAccessors.fromApplication` 从 Hilt 图桥接。
2. **接口只承载服务端作废这一半**：`suspend fun logout(): Result<Unit>` 内不外呼 `clearSession()`，
   不做"一把清"。接口语义 = 作废服务端会话，本地清理不属于登录域对外的能力（理由见权衡①）。
3. **两行固定写法写在 `module_me` 的 ViewModel 里**（`SettingViewModel.runLogout(provider)`，
   2026-09-06 起；原先落在 `SettingActivity` 的页面作用域里）：

   ```kotlin
   provider?.logout()?.onFailure {
       Logger.w(TAG, "服务端登出失败，仍继续清本地会话：${it.message}")
   }
   userSessionManager.clearSession()
   ```

   服务端失败**不阻塞**本地清理：救不回的凭证不该把用户锁在一个他已认为退出的会话里。
   provider 取到 `null` 时（独立运行）直接落到本地清理。
   整段在 `viewModelScope` 内跑完，提示与关页经基类命令通道（`sendToast`/`sendFinish`）下发，
   页面只负责发起（为什么不能由页面自己串，见权衡⑤）。
4. **删除 `login(username, password)`**：登录在 `module_login` 内部由各 ViewModel 直用 `UserRepository`，
   跨模块的登录能力无消费者（本轮之前 `ILoginProvider` 整体零调用方）。

## 权衡

- **① 不把「服务端作废 + 本地清理」合并成 provider 内一把清**：独立运行（`isModule=true`）时 `module_login`
  不在依赖图内，`TheRouter.get(ILoginProvider::class.java)` 返回 `null`，合并写法会让 `module_me` 的登出按钮
  在调试宿主里**完全失效**（连本地会话都清不掉）。拆成两半后，provider 缺席只影响服务端那一半。
- **② 不在 `lib_book_common` 新建会话/认证仓库**：那会与本仓既有范式（业务模块 repository 直接包装
  `lib_ebook_api` 的 DataSource）重复一套认证归属，且登出语义本属登录域，`lib_book_common` 不该持有它。
  本仓的上浮判据是「项目专属件被 ≥2 个功能模块使用才进 `lib_book_common`」——`ILoginProvider` 正好命中
  （`module_login` 实现、`module_me` 消费），落在 `lib_book_common`。
- **③ 不让 `module_me` 的 ViewModel 直注 `UserDataSource`**：ViewModel 跨过 Model 层直接触网络，破坏
  MVVM 三层（Model → ViewModel → View）约定，且会把认证端点的消费面扩散到登录域之外。
- **④ `ILoginProvider` 从「死架子」变活**：本轮之前它零调用方，按上浮判据的另一面「零调用方的架子不保留」
  本可整接口删掉；接入登出后它有了第一个真实消费者，故保留接口，同时把仍然零消费者的 `login()` 删掉——
  **留着死方法不等于架子有远见**。
- **⑤ 收尾写在 ViewModel，不留在页面作用域**（2026-09-06）：原处方把 `logout()` 做成 `suspend`、
  由 `SettingActivity` 用 `lifecycleScope.launch { viewModel.logout(); Toast; finish() }` 串起来，
  目的是防「`finish()` 之后作用域被取消、登出请求等于从未发出」。这个推理只覆盖了 `finish()` **之后**，
  漏了网络挂起**期间**：旋转屏幕会重建 Activity 并取消页面作用域，`clearSession()` 就永远轮不到执行——
  同一个「等于从未发出」的现象，换了触发者。而 `module_me` 的两份 Manifest 都没有锁竖屏
  （`module_main`、`module_book` 锁了 `screenOrientation="portrait"`），这条路径在真机上可达。
  补法不是给页面加 await 技巧，而是让**发起与收尾同处一个比页面活得久的作用域**：ViewModel 由
  ViewModelStore 持有、跨配置变更存活，`viewModelScope` 里这段协程不受转屏影响。
  不用 `WorkManager`：它才真能扛进程死亡，但为一个幂等的登出请求引入后台任务调度不成比例，
  而且改造前现状本来也有这个洞。

## 下游影响

- `module_me`：`SettingViewModel.logout()` 是**非 suspend** 的入口（内部解析 provider 后交给 `runLogout`），
  `SettingActivity` 只调一行 `viewModel.logout()`——不再持有登出作用域，也不再自己 `Toast`/`finish`。
  提示与关页经基类命令通道下发，且**先 `sendToast` 后 `sendFinish`**：命令通道由 `MvvmBinder`
  挂在宿主 `lifecycleScope` 的 `repeatOnLifecycle(STARTED)` 上消费，`finish()` 之后采集器随
  `onStop` 取消，还排在 `Channel` 里的命令不会再有人取——顺序反过来提示就是真丢，不是变丑。
  在途期间挂 `Overlay.Loading`，连点由一道 in-flight 闸门挡住——
  同一条闸门纪律也覆盖缓存清理与资料修改（一次性操作不得并发跑两遍编排）。
- **`runLogout(provider)` 是 internal 的最小测试接缝**：生产 adapter 是 TheRouter 解析出的真 provider，
  测试 adapter 是假件。由此「先作废服务端再清本地」的顺序、服务端失败仍清本地、连点只放行一次、
  覆盖层复位四件事都能在 JVM 下断言（`SettingViewModelTest`，Robolectric）。命令通道本身
  （`sendToast`/`sendFinish`）在 lib_common 侧是 internal，测试观测不到——提示与关闭属人工验证项。
- `lib_book_common` / `module_login`：`ILoginProvider` 只剩 `logout()`，`login()` 与 `UserSession` 返回类型不再对外暴露。
- `module_login` 改密页（`ModifyPwdViewModel.modify`）**不接** `logout()`：其 KDoc 记明服务端改密本身已使该用户
  全部 token 失效，故成功后只做 `clearSession()`。
- 会话失效共有两条路径，本地清理一律只经 `clearSession()` 单点：除登出外，另一条是 refresh token 换不到新凭证
  （access token 过期且静默刷新失败）由网络层收口，全局处置为「清会话 + 提示 + 跳登录」；登出是用户主动的那条。
- 人工验证项（Agent 止于构建）：真机/模拟器登录后在「我的 → 设置」点退出登录并确认，期望现象：等待态转起来 →
  Toast「退出登录成功」（`setting_logout_success`）→ 页面关闭 →「我的」页回到未登录态；后端侧确认该用户的
  refresh token 已全部作废（拿旧 refresh token 再调 `POST /api/auth/refresh` 应被拒绝）。
  **本轮新增两项**：① 点完确认后立刻旋转屏幕，转回来期望登出仍然完成（修复前这里会停在登录态且毫无提示）；
  ② 连点两次「退出登录」确认、「清理全部缓存」与「保存昵称」，期望各只有一条提示、一次结果。
  独立模式（把 `gradle.properties` 里的 `isModule` 临时改成 `true`，**不要用 `-PisModule=true` 命令行覆盖**，
  调试完改回且不提交）下重复一次，期望现象：只有本地清理生效、不闪退。
