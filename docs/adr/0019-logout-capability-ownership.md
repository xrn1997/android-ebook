# 登出能力拆分归属：服务端作废经 ILoginProvider 跨模块取用，本地清理仍由 clearSession 单点负责

登出的两半分属两处——服务端 refresh token 作废经 `ILoginProvider.logout()` 跨模块取用（接口在 `lib_book_common`，实现在 `module_login`），本地会话清理仍由 `UserSessionManager.clearSession()` 单点负责，provider 不重复它。理由：功能模块互不依赖，登出按钮在 `module_me` 而持有 logout 端点的仓库在 `module_login`，必须经 Provider 接口桥接；同时 provider 缺席（独立运行调试宿主）时本地清理不能跟着失效。调用方固定两行写法：先尽力作废服务端（失败只记日志），再无条件清本地。同时删除 `ILoginProvider` 里零调用方的 `login()`。

2026-09-06 评审就地修订：两行写法与「先服务端后本地」的顺序不变，但执行它的作用域由页面改到 ViewModel——原处方在旋转屏幕时会因 Activity 重建取消页面作用域而让登出静默失败。归属结论（provider 承载服务端那一半、`clearSession()` 单点清本地）不受影响。

## 背景

- 登出规格要求作废服务端该用户的全部 refresh token，服务端 `POST /api/auth/logout` 已实现，客户端 `UserDataSource.logout()` → `UserRepository.logout()` 一路也已就绪，但零调用方：此前的会话失效点（`module_me` 设置页的退出登录、`module_login` 改密成功后的清会话）只做本地清理，服务端凭证留活——换设备后旧 refresh token 仍可用。
- 跨模块障碍：登出按钮在 `module_me`，而持有该端点的仓库在 `module_login`。功能模块互不依赖，`module_me` 拿不到 `UserRepository`。

## 决策

1. **复用既有的 TheRouter SPI 通道**，不新建依赖边、不新建共享仓库：接口 `lib_book_common/src/main/java/com/ebook/common/provider/ILoginProvider.kt`，实现 `module_login/src/main/java/com/ebook/login/provider/LoginProvider.kt`（`@ServiceProvider`）。Provider 由 TheRouter 创建（非 Hilt），仓库实例经已存在的 `UserRepositoryEntryPoint` 以 `EntryPointAccessors.fromApplication` 从 Hilt 图桥接。理由：TheRouter SPI 已是跨模块能力暴露的既有通道，新建依赖边会增加模块间耦合。

2. **接口只承载服务端作废这一半**：`suspend fun logout(): Result<Unit>` 内不外呼 `clearSession()`，不做「一把清」。接口语义 = 作废服务端会话，本地清理不属于登录域对外的能力。理由：独立运行时 `module_login` 不在依赖图内，`TheRouter.get(ILoginProvider::class.java)` 返回 `null`，合并写法会让 `module_me` 的登出按钮在调试宿主里完全失效（连本地会话都清不掉）。拆成两半后，provider 缺席只影响服务端那一半。

3. **两行固定写法写在 `module_me` 的 ViewModel 里**（`SettingViewModel.runLogout(provider)`，2026-09-06 起；原先落在 `SettingActivity` 的页面作用域里）：

   ```kotlin
   provider?.logout()?.onFailure {
       Logger.w(TAG, "服务端登出失败，仍继续清本地会话：${it.message}")
   }
   userSessionManager.clearSession()
   ```

   服务端失败不阻塞本地清理：救不回的凭证不该把用户锁在一个他已认为退出的会话里。provider 取到 `null` 时（独立运行）直接落到本地清理。整段在 `viewModelScope` 内跑完，提示与关页经基类命令通道（`sendToast`/`sendFinish`）下发。理由：ViewModel 由 ViewModelStore 持有、跨配置变更存活，`viewModelScope` 里的协程不受转屏影响——而页面作用域（`lifecycleScope`）在旋转时会被取消，导致 `clearSession()` 永远轮不到执行。

4. **删除 `login(username, password)`**：登录在 `module_login` 内部由各 ViewModel 直用 `UserRepository`，跨模块的登录能力无消费者（本轮之前 `ILoginProvider` 整体零调用方）。理由：零调用方的方法留着只是架子，不等于有远见。

## 权衡

- **不把「服务端作废 + 本地清理」合并成 provider 内一把清**：理由即决策 2——provider 缺席时不能连本地清理一起失效。

- **不在 `lib_book_common` 新建会话/认证仓库**：那会与本仓既有范式（业务模块 repository 直接包装 `lib_ebook_api` 的 DataSource）重复一套认证归属，且登出语义本属登录域，`lib_book_common` 不该持有它。`ILoginProvider` 正好命中「项目专属件被 ≥2 个功能模块使用才进 `lib_book_common`」的上浮判据（`module_login` 实现、`module_me` 消费）。

- **不让 `module_me` 的 ViewModel 直注 `UserDataSource`**：ViewModel 跨过 Model 层直接触网络，破坏 MVVM 三层约定，且会把认证端点的消费面扩散到登录域之外。

- **收尾写在 ViewModel，不留在页面作用域**（2026-09-06）：原处方把 `logout()` 做成 `suspend`、由 `SettingActivity` 用 `lifecycleScope.launch` 串起来，只防了 `finish()` 之后作用域被取消，漏了网络挂起期间旋转屏幕重建 Activity 取消页面作用域——同一个「等于从未发出」的现象换了触发者。而 `module_me` 的两份 Manifest 都没有锁竖屏，这条路径在真机上可达。不用 WorkManager：为一个幂等的登出请求引入后台任务调度不成比例。

## 下游影响

- `module_me`：`SettingViewModel.logout()` 是非 suspend 的入口（内部解析 provider 后交给 `runLogout`），`SettingActivity` 只调一行 `viewModel.logout()`——不再持有登出作用域，也不再自己 `Toast`/`finish`。提示与关页经基类命令通道下发，且先 `sendToast` 后 `sendFinish`：命令通道由 `MvvmBinder` 挂在宿主 `lifecycleScope` 的 `repeatOnLifecycle(STARTED)` 上消费，`finish()` 之后采集器随 `onStop` 取消，还排在 `Channel` 里的命令不会再有人取——顺序反过来提示就真丢。在途期间挂 `Overlay.Loading`，连点由一道 in-flight 闸门挡住——同一条闸门纪律也覆盖缓存清理与资料修改（一次性操作不得并发跑两遍编排）。
- `runLogout(provider)` 是 internal 的最小测试接缝：生产 adapter 是 TheRouter 解析出的真 provider，测试 adapter 是假件。由此「先作废服务端再清本地」的顺序、服务端失败仍清本地、连点只放行一次、覆盖层复位四件事都能在 JVM 下断言（`SettingViewModelTest`，Robolectric）。
- `lib_book_common` / `module_login`：`ILoginProvider` 只剩 `logout()`，`login()` 与 `UserSession` 返回类型不再对外暴露。
- `module_login` 改密页（`ModifyPwdViewModel.modify`）不接 `logout()`：服务端改密本身已使该用户全部 token 失效，故成功后只做 `clearSession()`。
- 会话失效共有两条路径，本地清理一律只经 `clearSession()` 单点：除登出外，另一条是 refresh token 换不到新凭证（access token 过期且静默刷新失败）由网络层收口，全局处置为「清会话 + 提示 + 跳登录」；登出是用户主动的那条。
