# 版本更新检查：双发布源 failover 的策略归属与状态存储形态

2026-09-04 评审（对提交 `f54d8a9` 的两轴 review）定下「检查更新」这条链路的四处归属与形态：
**策略在 `module_me`、网络在 `lib_ebook_api`、专用纯净客户端 `@Named("release")`、角标结论不落地（每次派生）**。
同时定下「判不出结论就不算检查成功」这条不变量，与「发布检查走自己的 mock 接缝」。

## 背景

- 提交 `f54d8a9` 首次接入真实的版本更新检查（此前设置页版本行是静态展示），把
  `ReleaseRepository`（含双源顺序、failover、`.apk` 过滤）整体落在 `lib_ebook_api`，
  复用 `@Named("source")` 书源客户端，并把「是否有新版」作为布尔结论持久化。
- review 发现三条会直接改变用户所见的缺陷：① 角标存的是结论布尔量，用户升级安装后仍挂最长 7 天；
  ② 远端 tag 解析不出版本时被当成「已是最新」，既覆盖了旧结论又刷新了限频时间戳（假结论占住 7 天窗口）；
  ③ 弹窗标题把 tag `V1.2.0` 插进自带 `v` 前缀的文案，渲染成「发现新版本 vV1.2.0」。
- 另有两条结构性问题：`lib_ebook_api` 按 AGENTS.md 只承担「Retrofit 服务 / 数据实体 / 拦截器」，
  出现仓库层的第一例破坏了分层描述；书源客户端的 KDoc 与 AGENTS.md 都把它专属绑定在「第三方书源抓取」，
  拿去打公开 JSON API 属命名失真（它还带面向中文 URL 的 `EncodingInterceptor`）。

## 决策

1. **策略与网络分家**：
   - `lib_ebook_api/service/release/`：`ReleaseService`（动态 `@Url`）、`ReleaseResponse`（两平台同构投影）、
     `ReleaseDataSource`（接缝：只做「按端点取一次 latest」）、`ReleaseNetwork`（真实实现，自建 Retrofit）。
   - `module_me/repository/ReleaseRepository.kt`：发布源清单与顺序、failover、`.apk` 过滤、
     `ReleaseCheckResult` 投影，与同目录 `ReleaseStateStore` 构成一个域的两半。
2. **发布检查用专属客户端** `@Named("release")`（`lib_ebook_api/utils/NetworkModule.kt`）：connect/read 各 10s，
   无 `EncodingInterceptor`，与书源客户端共享的唯一不变量是**不带 token**（公开数据匿名访问）。
   同时**不套 `CoroutineAdapter`**：它解的是 ebook-server 的 `RespDTO` 业务码信封，平台 Release 响应没有这层包裹。
3. **角标结论不落盘**：`ReleaseStateStore` 只存「上次检查到的 tag」与「上次成功检查的时间」，
   `hasUpdateAvailable` 每次由 tag 与当前装机 `versionName` 现场比较得出；设置页 `onResume` 调
   `SettingViewModel.refreshUpdateBadge()` 重派生。本地 `versionName` 也只在这处读 PackageManager，
   版本行显示值经 VM 的 `appVersionName` 转发（比较基准与显示值同源）。
4. **「判不出结论就不算检查成功」**：远端 tag 解析不出版本、或本地版本读不到 → 主动检查弹
   `UpdateState.CheckError`、静默检查无声忽略，两种场景都**不写成功时间戳**（`markCheckSuccess` 不被调用）。
   代码位置：`SettingViewModel.recordConclusion()` 返回可空，`null` 即「无法判定」。
5. **版本比较用「数值段列表」而非固定三段**：`AppVersion(numbers: List<Int>, suffix: String)`，
   逐段比较、缺失段按 0 补齐；尾缀只允许挂在最后一段数字之后且该段必须以数字开头（`1.x`、`abc` 判解析失败）。
6. **发布检查有自己的 mock 接缝**：`ReleaseNetworkTest` + 资产 `lib_ebook_api/src/main/assets/release_latest.json`
   （**平台原始 JSON 形态，无 `RespDTO` 信封**），在 `module_app/src/real`（真实）、
   `module_app/src/mock`、`module_me/src/main/test/debug/MockNetworkModule`（mock）三处分别绑定。
7. **DTO 字段一律可空**，不在全局 `Json` 上开 `coerceInputValues`：两平台契约允许 `name`/`body`/`assets` 为 `null`，
   全局开关会把契约违规静默降级成默认值并波及 ebook-server 全部 DTO。
8. **发布位置硬编码** `xrn1997/android-ebook`（`ReleaseRepository` 的两个 internal 常量），不做可配置。

## 权衡

- **① 策略不放 `lib_ebook_api`**：本仓其余 Repository 无一例外在 `lib_book_common` 或功能模块里；
  且「先打哪个源、什么算有效结果、无 APK 的发布要不要返回」是本 App 的应用策略，不是网络契约。
  选 `module_me` 而非 `lib_book_common`：当前消费者只有设置页，按 ADR-0015 的共用判据它还不该上浮。
- **② 角标不存结论布尔量**：存结论省一次比较，代价是「装机版本已变」这件事没有任何写入点会通知它——
  派生形态让升级安装后自动纠正，且顺带给 `lastCheckedTag` 找到了真实读者（此前它只写不读）。
- **③ 不申请任何新权限**：GitHub/Gitcode 是公网 host，与 Android 17（targetSdk 37）新引入的
  「本地网络」权限（`ACCESS_LOCAL_NETWORK`）无关，那条只约束 `10.0.0.0/8`、`192.168.0.0/16` 等地址段
  （本仓连本机后端一律走 `adb reverse` + `127.0.0.1`，见 AGENTS.md「认证体系约定」）。
  `INTERNET` 已由 `module_app/src/main` 与 `lib_ebook_api/src/main` 清单声明，全 flavor 合并可见。
- **④ 无 `.apk` 附件不算源失败**：「有新版可升」与「能否一键下载安装包」是两件事。降级形态由 UI 承担
  （收起下载按钮、提示去发布页），而不是回退到备用源——备用源在这种发布上给不出更好的答案，
  还会白耗一次往返。
- **⑤ 尾缀序不实现语义化版本的预发布序**（`1.2.0 < 1.2.0alpha`）：`latest` 端点本身已排除 prerelease，
  能作为 latest 出现的带尾缀 tag 就是「同版本号的后一轮发布」（如 `V1.1.7alpha` 补发）。
  真要支持预发布序需在发布流程里约定 tag 模板，比较层不猜语义。
- **⑥ mock 忽略 `endpoint` 入参**：一份资产表达不了两个源，failover 因此**不在 mock 里验**，
  由 `ReleaseRepositoryTest` 用假数据源锁住（含「取消不算源失败」这条）。

## 下游影响

- `lib_ebook_api` 不再有 `repository/` 包（`com.ebook.api.repository.ReleaseRepository` 已删除），
  消费方需改从 `com.ebook.me.repository.ReleaseRepository` 取用；`ReleaseCheckResult` 一并迁入 module_me，
  并删去零消费者的 `releaseName`/`apkName` 两个字段。
- 字符串资源变更：删除恒等包装 `setting_check_update_found_message`；新增
  `setting_check_update_no_apk`（无安装包时的提示）与 `setting_check_update_open_failed`
  （下载入口打不开——与 `setting_check_update_error`「检查失败」是两回事，不可复用）。
- `SettingViewModel` 不再暴露 `currentVersion`，改为 `appVersionName`；`checkUpdateInternal(force: Boolean)`
  拆为 `checkUpdate()` 与 `startSilentRefresh()`，`UpdateState.Checking`/`CheckError` 仅由主动检查驱动。
- `AppVersion` 由 `data class AppVersion(major, minor, patch, suffix)` 变为 `(numbers: List<Int>, suffix)`，
  构造点与断言需同步（本仓仅 `ReleaseStateStore` 与 module_me 单测使用）。
- 人工验证项（Agent 止于构建，装机与页面确认由人工完成）：
  1. mock flavor（`:module_app:assembleMockDebug`）→ 打开「我的 → 设置」点版本行：期望弹窗标题
     「发现新版本 v1.3.0」（**不出现 `vV`**），正文为资产里的 mock 说明，有「下载」按钮；
     版本行右侧出现「新版」角标。
  2. 同处把 `release_latest.json` 的 apk 附件名改成 `.zip` 重新构建：期望弹窗**没有**下载按钮，
     正文下多一行「本次发布未提供安装包，请到发布页获取。」。
  3. real flavor 断网（或把两源端点临时改错）后点版本行：期望「检查更新失败，请稍后重试」，
     且**恢复网络后立刻再点即成功**（证明失败没有复位限频窗口、没有把角标停在错误值上）。
  4. 升级安装到 `versionName` 不低于远端 tag 的版本后回到设置页（不杀进程）：期望角标当场消失。
  5. 独立模式（`gradle.properties` 的 `isModule=true`，调试完改回且不提交）跑 `module_me`：
     期望检查更新走 mock 资产、不请求外网。

## 交叉引用

- ADR-0007：`RespDTO` + 业务码的响应契约——本 ADR 第 2、7 条「发布 API 不套这层信封」的对照面。
- ADR-0014：认证客户端收敛（本 ADR 的 `@Named("release")` 与之同理：**不同信任域各用各的客户端**）。
- ADR-0015：`lib_book_common` 与功能模块的分界判据（本 ADR 第 ① 条为何策略落 `module_me` 而不上浮）。
- AGENTS.md「模块架构」「Mock 数据源与独立开发」「认证体系约定」需与本 ADR 保持一致。
