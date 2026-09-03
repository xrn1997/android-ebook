# 登出能力归属：服务端作废经 ILoginProvider，本地清理仍由 clearSession 单点

2026-09-03 评审（grill 会话）定下：**登出的两半分属两处**——服务端 refresh token 作废经 `ILoginProvider.logout()`
跨模块取用（接口在 `lib_book_common`，实现在 `module_login`），本地会话清理仍由 `UserSessionManager.clearSession()`
单点负责，provider 不重复它。调用方固定两行写法：**先尽力作废服务端（失败只记日志），再无条件清本地**。
同时删除 `ILoginProvider` 里零调用方的 `login()`。

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
3. **调用方两行固定写法**（`module_me/.../mvvm/viewmodel/SettingViewModel.kt`）：

   ```kotlin
   TheRouter.get(ILoginProvider::class.java)?.logout()?.onFailure {
       Logger.w(TAG, "服务端登出失败，仍继续清本地会话：${it.message}")
   }
   userSessionManager.clearSession()
   ```

   服务端失败**不阻塞**本地清理：救不回的凭证不该把用户锁在一个他已认为退出的会话里。
   provider 取到 `null` 时（独立运行）直接落到本地清理。
4. **删除 `login(username, password)`**：登录在 `module_login` 内部由各 ViewModel 直用 `UserRepository`，
   跨模块的登录能力无消费者（本轮之前 `ILoginProvider` 整体零调用方）。

## 权衡

- **① 不把「服务端作废 + 本地清理」合并成 provider 内一把清**：独立运行（`isModule=true`）时 `module_login`
  不在依赖图内，`TheRouter.get(ILoginProvider::class.java)` 返回 `null`，合并写法会让 `module_me` 的登出按钮
  在调试宿主里**完全失效**（连本地会话都清不掉）。拆成两半后，provider 缺席只影响服务端那一半。
- **② 不在 `lib_book_common` 新建会话/认证仓库**：那会与本仓既有范式（业务模块 repository 直接包装
  `lib_ebook_api` 的 DataSource）重复一套认证归属，且登出语义本属登录域，`lib_book_common` 不该持有它。
  按 ADR-0015 的判据，`ILoginProvider` 是**被 ≥2 个模块共用**（`module_login` 实现、`module_me` 消费）的项目专属件，
  正好落在 `lib_book_common`。
- **③ 不让 `module_me` 的 ViewModel 直注 `UserDataSource`**：ViewModel 跨过 Model 层直接触网络，破坏
  MVVM 三层（Model → ViewModel → View）约定，且会把认证端点的消费面扩散到登录域之外。
- **④ `ILoginProvider` 从「死架子」变活**：本轮之前它零调用方，按 ADR-0015 的「无调用方 → 删除」判据本可整接口删掉；
  接入登出后它有了第一个真实消费者，故保留接口，同时把仍然零消费者的 `login()` 删掉——**留着死方法不等于架子有远见**。

## 下游影响

- `module_me`：`SettingViewModel.logout()` 变为 **`suspend`**，调用方 `SettingActivity` 必须在 `finish()` **之前**
  `await`（`lifecycleScope.launch { viewModel.logout(); Toast; finish() }`）——否则协程作用域随页面销毁被取消，
  登出请求**等于从未发出**（这一点已在 `SettingViewModel` / `SettingActivity` 注释里写明）。
- `lib_book_common` / `module_login`：`ILoginProvider` 只剩 `logout()`，`login()` 与 `UserSession` 返回类型不再对外暴露。
- `module_login` 改密页（`ModifyPwdViewModel.modify`）**不接** `logout()`：其 KDoc 记明服务端改密本身已使该用户
  全部 token 失效，故成功后只做 `clearSession()`。
- 人工验证项（Agent 止于构建）：真机/模拟器登录后在「我的 → 设置」点退出登录并确认，期望现象：Toast「退出登录成功」
  （`setting_logout_success`）、页面关闭、「我的」页回到未登录态；后端侧确认该用户的 refresh token 已全部作废
  （拿旧 refresh token 再调 `POST /api/auth/refresh` 应被拒绝）。独立模式
  （`-PisModule=true :module_me:assembleDebug`）下重复一次，期望现象：只有本地清理生效、不闪退。

## 交叉引用

- ADR-0008：双 token 模型与会话恢复（refresh token 的持久化归属）。
- ADR-0010：静默刷新接缝与会话过期事件收口（另一条"会话没了"的路径，与登出互为补充）。
- ADR-0015：`lib_book_common` 与 `lib_common` 的分界判据（本 ADR 第 2、④ 条的归属依据）。
