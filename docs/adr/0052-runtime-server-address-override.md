# 服务器地址允许运行期覆盖：重启生效、换址清会话

Status: accepted

ebook-server 基址原只能经 `local.properties` 注入 BuildConfig，换地址要重新打包。决定允许在设置页持久化覆盖基址、重启后生效；换址即清空本地会话，认证白名单与 Retrofit 基址同读一份配置。

## 决定

1. **运行期覆盖只落一份事实**：`ServerAddressStore`（SP `server_address`/`address`）是唯一读取口，应用**不内置默认地址**（未配置即 null，见 ADR-0053）。理由：白名单与 Retrofit 各读一处会漂移，出现「请求打到新地址、token 白名单还是旧主机」的错配。落盘存 `display`（scheme + 显式端口）而非 Retrofit 用的 `baseUrl`：`baseUrl` 会省略 scheme 默认端口（如 `:80`），回读时无法区分「显式 80」与「未写端口」。
2. **重启生效，不做热切换**：`AuthInterceptor` 持有的白名单 Set 与两个 Retrofit 单例都读 `ServerAddressStore.effective()`——store 构造时冻结的进程快照，而不是 SP 现值。读现值的话，「单例在保存之前还是之后被建」会决定它拿到哪个主机，白名单与基址能分到两处，正是要防的错配。热切换得改成动态白名单加按地址懒重建网络栈；换服务器是低频操作，重启一次的代价比放开认证边界小。
3. **换址与清除配置都清会话，且保存后强制重启**：旧服务器的 refresh token 若随新地址发出，等于把凭证交给一个用户刚输入的主机；不提供「稍后重启」是为了不留「在旧地址重新登录、重启后把凭证带到新地址」的窗口。`clearSession()` 的两处 SP 写用 `commit` 而非 `apply`——保存后可能立刻强制退出进程，异步写会丢，旧 refresh token 会活到下次启动。`commit` 的返回值必须向上透出（`clearSession()` / `save()` / `reset()` 均返回 Boolean）：SharedPreferences 会先改内存再写盘，写盘失败时读回仍是新值，靠回读判不出失败；设置页的保存流程以返回值为准，失败不清会话、不催重启并提示重试，配置面只随成功落盘推进。「写盘 → commit 结果 → 清会话」整段在 `Dispatchers.IO + NonCancellable` 里执行，页面在 IO 提交期间被销毁不会留下「地址已换、会话未清」；登出、改密、会话过期三条清会话路径同样用 `NonCancellable`，收尾一旦开始就不因页面销毁被取消。`save` / `reset` 同时在写地址的同一笔 commit 里置 `session_reset_pending`，应用启动时先 `clearSession()` 再清标记；清会话落盘失败或抛异常时**不消费标记**、只记日志，下次启动重跑（清会话幂等），异常也不逃出 `Application.onCreate` 形成启动崩溃循环。重启提示的可见性由 store 的进程级「地址与进程启动快照不一致」派生，而不是某次保存的一次性回调：转屏或离页后仍要弹，真正重启后自然归零。
4. **输入只收基址**：拒绝路径、查询、锚点与凭据，http 缺省端口 9090、https 缺省 443；反斜杠也按路径拒绝（OkHttp 会把它当路径分隔符，不拦就会被静默削成主机）。理由：基址之外的部分会让 Retrofit 相对接口落位漂移，凭据入配置没有正当用途。输入同样经 `ServerAddress.parse` 规范化：`host:port`、带 scheme 等形态按同一套规则收口，不会拼出双端口。

## 被否方案

- **热切换（动态 Set + 按地址懒重建 Retrofit）**：省一次重启，但要把「白名单启动后不再变化」这条安全边界改活，并处理在途请求与刷新链路，回归风险高于收益。
- **仅 debug 构建可见**：正式包换网络或换公网 IP 后仍要重新打包，痛点没消。

## 后果

- 设置页入口对所有构建可见；mock 包下它不影响假数据源。入口行只显示「默认/自定义」状态、**不显示地址值**，完整地址、明文警告与编辑在独立的服务器地址页（形态对齐书源管理等设置二级页）：服务器地址不是可以随便亮在会被旁人扫到的设置页上的东西。
- 改地址后必须重启才一致；保存后以不可取消的对话框强制一键重启（服务器地址页与设置页都按 `pendingRestart` 状态渲染），重启会中断在途下载。重启经系统 launcher intent 起新任务再退出进程，各厂商设备上的实际行为未做适配、需装机验证。
- 基址没有构建期来源：用户自填、SP 持久化（ADR-0053），`local.properties` 不再参与。
- 允许 http：非回环 http 地址在服务器地址页显示明文警告（登录密码与令牌会明文发送）。未采用「非本地必须 https」——局域网自建后端仍普遍用 http；是否收紧到 https-only 需另立决策。
- `clearSession()` 返回 false 时只记日志：登出、会话过期、改密的内存态已清空，但盘上旧 refresh token 可能残留到下次启动；换址路径由 `session_reset_pending` 兜底重试，其余路径暂无自动重试。

锚点：`lib_ebook_api` 的 `ServerAddress`（`isCleartextToRemoteHost`）/ `ServerAddressStore`（`current` 配置面、`effective` 生效面）/ `NetworkModule.provideAuthAllowedHosts`；`lib_book_common` 的 `BookApplication.applyPendingAddressSessionReset` / `SPUtil.clearAuthData`；`ServerAddressTest`、`ServerAddressStoreTest`、`BookApplicationStartupResetTest`、`AndroidUserSessionManagerTest`、`SettingViewModelTest`、`MeScreensRenderTest`、`ServerAddressScreenRenderTest`；`docs/architecture.md`「认证与会话」。
