# 应用不内置服务器地址：用户自行配置

Status: accepted

ebook-server 是自部署服务，App 的用户未必有、作者也不打算替所有人托管。决定发版包**不内置任何服务器地址**（构建期不注入主机值），未配置时服务端能力快速失败、阅读不受影响；地址由用户在「设置 → 服务器地址」自行填入并持久化。这与「应用不内置书源、书源由用户导入」是同一条产品基因。

## 决定

1. **构建期零地址**：不设 `BuildConfig` 主机字段、`local.properties` 不再注入 `ebook.server.host`，debug 与 release 同构——发版包里没有可提取的默认地址，开发值也没有漏进 release 的通道（只写「不带默认值」挡不住开发配置被顺手打进包）。
2. **状态只有两档**：未配置（`ServerAddressStore` 的 null）与用户自填地址。没有「默认」档：`effective()` 为 null 时网络栈以 `ServerAddressNotConfiguredException`（文案即用户所见提示）快速失败、不发请求；token 白名单为空集。阅读、书源、搜索、下载走第三方书源，不经服务端，不受影响。
3. **配置入口与隐藏口径**：设置行只显示「未配置/自定义」状态、不显示地址值；完整地址只在服务器地址页出现（输入框空起、无预填）——地址不是可以随便亮在会被旁人扫到的界面上的东西，也避免给用户「这是官方服务器」的错觉。

理由：内置默认地址等于把作者的服务器贡献给所有用户——流量、成本、滥用与合规风险都由作者背，App 还要为它可用性负责；不内置则 App 是一台完整的阅读器，服务端能力是可选插件。

## 被否方案

- **内置默认地址（发版指向作者的服务器）**：省一次配置，但作者要为所有用户的流量与滥用买单，且地址进了公开包就藏不住（反编译、抓包都可见），与「自部署」的产品定位相反。
- **仅 debug 内置默认（release 为空）**：开发省一次手填，但 debug/release 行为分叉（同一套状态语义出现两种真值），且开发机配置仍可能经构建脚本漏进 release。省下的一次手填不值得这条裂缝。

## 后果

- 未配置的用户用不了登录/注册/改密/评论（提示「未配置服务器地址」并指向设置入口），阅读与书源链路照常；设置行以「未配置」标注。
- 用户自填地址即一次换址：清会话、重启生效的机制不变（见 ADR-0052）；「清除配置」回到未配置，同样清会话并要重启。
- 开发流程变化：联调后端在 App 内手填一次（SP 记住），`local.properties` 不再有 `ebook.server.host`。
- 「隐藏默认地址」的诉求自动消解：没有默认值可藏，用户只看得见自己填的值。

锚点：`lib_ebook_api` 的 `ServerAddressStore`（`isConfigured`、`effective` 可空）/ `ServerAddressNotConfiguredException` / `UserNetwork`、`CommentNetwork`（未配置快速失败）/ `NetworkModule.provideAuthAllowedHosts`（未配置为空集）；`ServerAddressStoreTest`、`SettingViewModelTest`、`ServerAddressScreenRenderTest`（未配置进页无地址值）；`docs/architecture.md`「认证与会话」。
