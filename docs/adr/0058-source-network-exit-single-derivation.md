# 书源出网客户端收单一派生点：SourceNetworkExit

Status: accepted

取文与沙箱外呼此前各派各的客户端——沙箱那路经守门派生，取文那路（含 URL 选项 `js` 改写出的地址）干脆不派生、直接把注入的 `@Named("source")` 客户端包进传输层。决定：两条路都只从 `SourceNetworkExit` 一个对象派生，首合并**行为不变**；私网/DNS 判定是否延伸到取文，另立决策。

## 背景

- ADR-0028 的遗留第 3 条登记过：白名单与私网拒绝只覆盖脚本自己发起的外呼，URL 选项 `js` 改写出的地址由取文层按原路径发出。但那条遗留只说了「守门没覆盖」，没说的是**取文那路连派生都没有**——「这台设备上书源请求长什么样」要读两个文件才拼得出来，改 base 出处或凭证口径时取文那路会静默漏掉。
- 一步把守门（`GuardedDns` 的私网/DNS 重绑拒绝、host 白名单）延伸到取文并不可行：LAN 与 IP 直连的书源今天能用，挂上判定即把它们掐死（见被否方案）。

## 决定

1. **新建 `internal object SourceNetworkExit`（`sandbox/SourceNetworkExit.kt`），两路派生都从它出**：`sandboxClientFor(base, guard, cookies)` 是原守门派生原样迁入（`GuardedDns` + 可选源级会话罐）；`contentClientFor(base)` 是取文派生。调用点（`ScriptBookParser` 的取文 transport 与 `sandboxGateway`、`JsSandboxHost` 的探测客户端）一律改经此对象，仓内不再有第二处 `newBuilder()` 派生。
2. **两路共用同一个 base**：base 必须是 `@Named("source")` 纯净客户端（无 token），`newBuilder()` 派生共享连接池与线程池。「永不附凭证」的口径因此只在这一处成立一次，由 `SourceNetworkExitTest` 钉住：两路派生都不得往拦截器链上添任何东西。
3. **首合并行为不变**：`contentClientFor` 是等价派生——不挂 `GuardedDns`、不查白名单。它的价值是具名出口、同一份 base 出处与可测接缝，不是新增拒绝。

## 边界

- 私网/DNS 重绑判定与 host 白名单**仍只在沙箱外呼那一路**；「取文也过守门」不是当前事实，陈述口径见 `CONTEXT.md`「受限代理」。
- 收紧取文（挂 `GuardedDns` 或白名单）是未来**单独的决策**，落点即 `contentClientFor` 这一个函数；本篇不预设它的判据。
- base 的出处与 token 口径不归本篇：那是 `@Named("source")` 客户端装配面的既有约束。

## 被否方案

- **一步收紧：取文也挂 `GuardedDns`（私网/DNS 重绑拒绝）**——会掐死 LAN/IP 直连书源，而这类源今天可用；用户裁决首合并只收派生点、不动行为，收紧另立决策再带上真实源失败率的数据。

锚点：`SourceNetworkExit`；`SourceNetworkExitTest`
