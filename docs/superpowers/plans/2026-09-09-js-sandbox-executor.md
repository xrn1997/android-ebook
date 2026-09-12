# 脚本书源 JS 沙箱执行器（Plan 3）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 ADR-0028 裁决的 `:js` 沙箱执行器——零权限隔离进程内跑自集成的 QuickJS，用 Binder + JSON 控制面与双向回调通道替掉五处 `JsEvaluationPendingException` 抛点，让脚本书源里含 `@js:` / `<js></js>` / `{{JS 表达式}}` / `js` / `bodyJs` / `init` JS 分支的规则段能真求值。

**Architecture:** 三层，每层只有一个职责：**内核**（`third_party/quickjs`，vendored、不改一行）→ **桥**（`lib_book_source/src/main/cpp/bridge/js_bridge.cpp`，唯一的 C++ 文件：runtime 生命周期 + 四项限值的内核侧开关 + 一个泛型 host call 出口，不知道 Binder 也不知道白名单内容）→ **协议与能力**（Kotlin：`sandbox/` 包内的帧编解码、白名单表、主进程受限网络代理、递归规则求值回调）。白名单的全部语义在两份可测的 Kotlin/JS 文本里（`JsHostApi` 表 + `QuickJsPrelude` 垫片），加一个能力不碰 C++。

**Tech Stack：** AGP 9 + NDK r28 + CMake 3.22.1、QuickJS 2026-06-04（MIT）、Kotlin 协程、kotlinx-serialization、JDK `MessageDigest`/`Cipher`/`Base64`/`HexFormat`。

---

## 本计划的边界

**做什么**

1. Vendored QuickJS + 本仓首个 C++ 模块（build-logic 新增 NDK/CMake 约定插件）。
2. `:js` 隔离进程 Service + 手写 `Binder.onTransact` 控制面（JSON 帧，不用 AIDL，理由见「偏差 3」）。
3. 双向回调通道：脚本执行中回调主进程做**递归规则求值**与**受限网络代理**两件事，外加变量存取与 toast→日志。
4. 四项限值（wall-clock / 堆 / JS 栈 / 报文大小）+ runtime 按任务复用、任务边界强制重置 + 断连自动重启。
5. Host API 白名单按 ADR-0028 决策 6 定版（见 Task 4 的表）。
6. 五处 `JsEvaluationPendingException` 抛点经可注入接缝接上执行器；**未接线/不可用时行为与 2d 完全一致**（继续抛「需脚本沙箱执行器」），所以本计划每一步落地中途都不会让现有 330 例变红。

**不做什么**（各有明确去处，不是「以后再说」）

- **书城默认源接线**：脚本行仍不会被选成默认源（`BookSourceManagerImplTest` 三条锁形用例守着），发现面仍不到 UI → 归 2e 书城接线计划。
- **`contentBatch` / `jsLib` / `coverDecodeJs` / XPath / webJs**：规格 §1.4/§1.5 里标 ❌ 延后，本计划不新增语法（`jsLib` 需要「源级脚本库下载 + 注入」，与 §11-25 的真实语料债一起处置）。
- **浏览器自动化、登录流、验证码族、`cookie`/`cache`/`source` 持久变量**：ADR-0028 明定不支持；命中即 `UnsupportedRuleFeatureException`。
- **CPU 时间计量、白名单宽松度调校**：ADR-0028 遗留项，保持遗留。

## 关键事实：内核是 QuickJS 2026-06-04（不是网上的 2021 版）

vendored 提交 `04be246001599f5da...`（`04be246001599f5995fa2f2d8c91a0f198d3f34c`），`VERSION` 文件 `2026-06-04`。**这份头文件与流传的教程差异很大**，桥接层照下表写，别照博客写：

| 事项 | 本内核的实际形态 |
|---|---|
| 内核源文件 | `quickjs.c libregexp.c libunicode.c cutils.c dtoa.c` 五个；**没有 `libbf.c`**（`CONFIG_BIGNUM` 一族已上游删除），**不编 `quickjs-libc.c`**（POSIX/os.\* 层，正是我们要拒绝的能力面） |
| `-DCONFIG_VERSION` | **必须定义**：`JS_DumpMemoryUsage()` 无条件字符串拼接它（`quickjs.c:7226`），漏定义那一行编不过。无 `CONFIG_VERSION_NICK` |
| JSON 写出 | **`JS_WriteJSON` 不存在** → `JS_JSONStringify()` 拿 JSValue，再 `JS_ToCString()` |
| 全局对象 | 用 `JS_GetGlobalObject(ctx)`（`JS_GetGlobalThis` 在部分版本形态不同，不依赖） |
| 中断器 | **`JS_NewInterruptHandler` 不存在** → `JS_SetInterruptHandler(rt, cb, opaque)`，`typedef int JSInterruptHandler(JSRuntime*, void*)`。回调**不在定时器上**：只在解释器轮询点被调用，所以超时判定逻辑（拿单调钟比 deadline）在回调里自己写；且**原生函数阻塞期间不会被中断**——host call 的超时必须由主进程侧兜 |
| 栈 | 无 `JS_SetMaxStackDepth`，用 `JS_SetMaxStackSize(rt, bytes)`（`CONFIG_STACK_CHECK` 在 Android 上默认开）+ 换线程时 `JS_UpdateStackTop(rt)` |
| 原生函数取参 | **没有 `JS_GetArgumentCFunction`**：`argc/argv/this_val` 是 `JSCFunction*` 的直接入参 |
| 异常 | `JS_IsException(v)` 判、`JS_GetException(ctx)` 取（拿走所有权）、`JS_FreeValue` 释放 |
| Atomics | `CONFIG_ATOMICS` 自动开 → **必须链接 `pthread`**（bionic 提供）。同时 `JS_SetCanBlock(rt, 0)` 禁掉 `Atomics.wait`，不让脚本无限占住执行线程 |
| 上游 CFLAGS 里的行为项 | `-fwrapv`（有符号溢出必须回绕，内核依赖）、`-funsigned-char` 保留；`-Werror` 关掉（NDK clang + bionic 会因额外警告直接编不过，上游也只有 CI 才开） |
| 可见性 | 内核静态库不需要；`-fvisibility=hidden` 只加在自有 `.cpp` 上（`JNIEXPORT` 自带 default 可见性，不受影响） |

**核心 API 摘录（逐字来自本内核 `quickjs.h`）**

```c
JSRuntime *JS_NewRuntime(void);
void JS_SetMemoryLimit(JSRuntime *rt, size_t limit);
void JS_SetMaxStackSize(JSRuntime *rt, size_t stack_size);
void JS_UpdateStackTop(JSRuntime *rt);
void JS_SetCanBlock(JSRuntime *rt, JS_BOOL can_block);
void JS_SetRuntimeOpaque(JSRuntime *rt, void *opaque);
void JS_SetInterruptHandler(JSRuntime *rt, JSInterruptHandler *cb, void *opaque);
void JS_RunGC(JSRuntime *rt);
void JS_FreeRuntime(JSRuntime *rt);              /* 无 leak-check 变体 */

JSContext *JS_NewContext(JSRuntime *rt);
void JS_FreeContext(JSContext *s);
void JS_SetContextOpaque(JSContext *ctx, void *opaque);
JSValue JS_GetGlobalObject(JSContext *ctx);

JSValue JS_Eval(JSContext *ctx, const char *input, size_t input_len,
                const char *filename, int eval_flags);   /* input[input_len] 必须是 '\0' */
JSValue JS_ParseJSON(JSContext *ctx, const char *buf, size_t buf_len, const char *filename);
JSValue JS_JSONStringify(JSContext *ctx, JSValueConst obj,
                         JSValueConst replacer, JSValueConst space0);
JSValue JS_NewCFunctionData(JSContext *ctx, JSCFunctionData *func,
                            int length, int magic, int data_len, JSValueConst *data);
int JS_SetPropertyStr(JSContext *ctx, JSValueConst this_obj, const char *prop, JSValue val);
const char *JS_ToCString(JSContext *ctx, JSValueConst val);
void JS_FreeCString(JSContext *ctx, const char *ptr);
JSValue JS_GetException(JSContext *ctx);
JS_BOOL JS_IsException(JSValueConst v);
JSValue JS_ThrowInternalError(JSContext *ctx, const char *fmt, ...);
void JS_FreeValue(JSContext *ctx, JSValue v);
```

## 与 ADR-0028 的偏差与需补记的口径（先声明，Task 11 就地补记进 ADR）

偏差 1~3 是对 ADR 决策的**实际偏离**（改了决策里点名的落点/范围），偏差 4~7 是 ADR **打开而未定**、本计划必须锁死的口径。两类都要在 Task 11 进 ADR 正文，不允许只活在计划文件里。

**偏差 1：纯计算类 Host API 落在「执行器进程内的 Kotlin」，不是「执行器进程的 C++」。**
ADR-0028 决策 6 的原话是「纯计算类做成执行器 C++ 原生 API（md5、base64 族、对称加解密、hex、时间格式化），零 IPC」。本计划改成：JS 侧名字完全不变，但实现走 JNI 回到**同进程**的 Kotlin，用 JDK 的 `MessageDigest`/`Cipher`/`Base64`/`HexFormat`/`DateTimeFormatter`。
理由三条：(a) 零 IPC 的目标达成——同进程 JNI trampoline 是微秒级，不跨进程边界；(b) 隔离进程本来就零权限，JDK 调用与 C++ 调用在能力面上**完全等价**，安全属性一点没动；(c) 手写 AES/MD5 的 C 代码约 900 行且**在本仓不可测**（JVM 测不到 C、设备测不便覆盖全部向量），而 JDK 实现有平台审计，且改成 Kotlin 后这些 API 在 JVM 上就能用真实向量单测锁住（Task 4 有）。
代价：白名单的纯计算分支多一次 JNI 往返。若将来 benchmark 显示这是热点，替换成 C++ 实现只需改 `HostDispatcher` 的一侧，JS 名字与协议不变。

**偏差 2：`java.get` 只实现变量读取语义（规格 §5.2），不实现上游的 `java.get(url, charset)` 网络形态。**
`java.get(key)` 与 `java.get(url)` 在上游是按重载区分的，规格 §5.2 只给了变量语义。用一元参数字数分派会变成「同一个名字两种行为」的排查地狱。脚本要发请求走 `java.ajax` / `java.post` / `java.load`。落地后记进规格 §11-27。

**偏差 3：控制面用 `Binder.onTransact` 手写，不写 AIDL。**
帧内容本来就是一条 JSON 字符串，AIDL 能表达的只有「一个 int + 两个 String」，收益为零，代价是多一套生成物与 AGP 9 下 `buildFeatures.aidl` 的额外不确定性（本仓此前无 AIDL 先例）。协议常量集中在 `SandboxContract` 一处，两侧共用。

**偏差 4（口径）：「任务边界」在本实现里 = 一次沙箱调用，不是一本书的解析任务。**
ADR 决策 5 说「runtime 按任务复用、任务边界强制重置」。计划把重置粒度定在 `execute` 之前（`nativeReset` 每次 `JS_FreeContext` + `JS_NewContext`）。
理由：一次解析任务会跑几十段 JS（`init`/`bookUrlPattern`/每章的 `content`/`nextContentUrl`…），只要有一段往 `globalThis` 留了脏东西，同任务后续全部串味，而这种污染**没有任何可观测信号**；context 重建是百微秒量级，相对同一次调用里必然发生的跨进程往返与网络可以忽略。
代价：上游靠 `globalThis` 跨段缓存的写法在本仓失效（同一本书第二次求值拿不到第一次的全局量）。这条要写进规格 §11-27，别让用户去猜。

**偏差 5（口径）：递归求值回调里的嵌套规则不再执行 JS。**
ADR 决策 4 只说回调通道支持「递归规则求值（脚本调用解释器解析规则）」，没说嵌套层自己遇到 `@js:`/`<js>` 时怎么办。计划锁死为**不允许**：`JsCallbackProxy` 的 `evaluateNested` 用 `ScriptRuleEvaluator(ctx.withoutJs())`。
理由：嵌套一次就是一帧跨进程调用里再入一帧，深度没有天然上界（脚本可以让规则里再套规则），而超时与堆限是**按帧**记账的——再入会让「一帧的账」变成「一帧 + 未知子树」，Task 6 的 `runOnce` 超时打不断一棵还在跑的子树。变量表按引用共享（嵌套里 `@put:` 外层 `@get:` 拿得到），只有 `baseUrl`/`key`/`page` 快照，因此嵌套翻页不会移动外层基线。
代价：嵌套层若真需要 JS，会得到 `JsEvaluationPendingException`——这是本仓明确不支持的能力，不是「还没实现」。

**偏差 6（口径）：执行器同时只有一帧在飞。**
ADR 决策 5 的四件套限的是单帧资源，没定并发。计划在 `JsRuntimeBridge` 一侧用一把锁串行化（`SandboxTaskRunner`），`JsSandboxClient` 侧同一时刻只允许一个 `execute` 持有通道。
理由：一个 `JSRuntime` 不是线程安全的，多帧并发要么每条 binder 线程各持一个 runtime（内存上限×线程数，决策 5 的堆限因此失去「进程总量」含义），要么串行化。选后者：脚本执行的瓶颈是网络，不是 CPU，读一本书的章节本来就是顺序的，并行度收益远低于失控的内存记账风险。
代价：聚合搜索里多本书/多源并行时，沙箱是串行点。将来要提并发，改的是「每线程一个 runtime」这一处，协议与 JS 侧不变。

**偏差 7（口径）：网络代理回调在主进程的 binder 线程池上 `runBlocking`。**
ADR 决策 6 说「网络类一律经主进程受限代理」，没定线程模型。现实约束是 `HostHandler.handle` 必须同步返回（binder 事务语义），而 `ScriptTransport.execute` 是 `suspend`。
计划：在 `onTransact` 落进来的那条 binder 线程上 `runBlocking`，transport 内部一律 `withContext(Dispatchers.IO)`，所以真正阻塞的是 IO 池线程，binder 线程只是等结果。**前提是永不在主线程调沙箱**——Task 6 的主线程守卫守的正是这条链路（主线程调沙箱 → 回调要回主进程 → 主线程在等 → 自锁）。
代价：一条 binder 线程在最坏情况下被一次网络请求占住（受 `timeoutMs` 上界约束）。binder 线程池默认至多 15 条，而偏差 6 已保证同时只有一帧，因此占用恒为 1。

## 文件结构

```
third_party/quickjs/                         ← 新增（vendored，15 个源/头 + LICENSE + VERSION）
  README.md PIN.sha256                       ← 出处与漂移校验（Task 2）
.gitattributes                               ← 新增：vendored C 源禁用换行归一化

build-logic/convention/src/main/kotlin/AndroidNativeConventionPlugin.kt   ← 新增（Task 1）
build-logic/convention/build.gradle.kts                                   ← 注册 androidNative
gradle/libs.versions.toml                                                 ← 加 xrn1997-android-native

lib_book_source/
  build.gradle.kts                            ← 应用 native 插件 + cmake 路径（Task 2）；instrumented runner 与依赖（Task 7）
  consumer-rules.pro                          ← .so/JNI 名的 keep 规则（Task 7）
  src/main/cpp/CMakeLists.txt                 ← 新增（Task 2）
  src/main/cpp/bridge/js_bridge.cpp           ← 唯一 C++ 文件（Task 7）
  src/main/AndroidManifest.xml                ← <service> :js + isolatedProcess + 进程门（Task 8）
  src/main/java/com/ebook/source/sandbox/
    JsProtocol.kt        帧编解码 + 状态映射（纯 JVM 可测）      Task 3
    JsLimits.kt          四项限值、报文上限与 bindTimeoutMs       Task 3、8
    HostCompute.kt       纯计算实现（JDK，JVM 上真跑）            Task 4
    QuickJsPrelude.kt    JS 垫片源码（白名单的 JS 侧）            Task 4
    JsNetworkGuard.kt    scheme/私网/白名单/大小判定（含 GuardedDns、AddressPolicy）  Task 5
    SourceHostAllowlist.kt  从源 JSON 导出 host 白名单            Task 5
    JsChannel.kt         传输抽象（Android 类型不进签名）         Task 6
    JsSandboxClient.kt   连接/复用/断连重启/主线程闸门            Task 6
    SandboxContract.kt   进程间协议常量                           Task 6
    QuickJsNative.kt     external fun 声明                        Task 7
    HostDispatcher.kt    api → 纯计算 / 主进程回调 的唯一路由      Task 7
    JsRuntimeBridge.kt   执行器进程内的 runtime 持有者 + 分发     Task 7
    SandboxTaskRunner.kt 单帧串行化与超时看门狗                   Task 8
    SandboxProcess.kt    隔离判据的唯一公开入口（自检/进程门/设备断言共用）  Task 8
    SandboxService.kt    :js 进程 Service                         Task 8
    BinderJsChannel.kt   IBinder 实现（只编译，不在 JVM 测）      Task 8
    JsSandboxConnector.kt 主进程侧 bind/解绑与连接器生命周期      Task 8
    JsCallbackProxy.kt   主进程侧回调实现（递归求值 + 网络代理）   Task 9
      伴生：HeadStatusProbe（HEAD 探测）/ GuardedNetwork（派生客户端唯一来源）  Task 9
      HostCallbackRouter 上浮 public 并持当前 HostHandler（装配缝，Task 10）
    JsSandboxHost.kt     公开装配门面：proxy → execute 的唯一入口  Task 10
  src/main/java/com/ebook/source/script/
    JsHostApi.kt         白名单表（名字/元数/落点）               Task 4
    JsInterpreter.kt     执行器侧对上层暴露的求值接口             Task 6
    ScriptJsBridge.kt    五类 JS 求值点的接缝（SEGMENT/EXPR/URL/BODY/INIT）  Task 10
    SandboxScriptJs.kt   接缝的沙箱实现：包装脚本 + 绑定表 + 失败分类  Task 10
    ScriptRuleExceptions.kt   新增两个类型化异常                  Task 3；KDoc 收窄 Task 10
    EvalContext.kt       js 挂载位 + variables 进构造 + withoutJs()  Task 10
    ScriptRuleEvaluator.kt / Interpolation.kt / ScriptUrlOption.kt / ScriptHttp.kt
    ScriptRuleSet.kt / analyze/ScriptBookParser.kt                Task 10 接缝
  src/test/java/com/ebook/source/sandbox/…Test.kt                 Task 3~6、8、9
  src/test/java/com/ebook/source/script/SandboxScriptJsTest.kt    Task 10
  src/androidTest/java/com/ebook/source/sandbox/                  Task 7、8
    QuickJsBridgeTest.kt      真内核：求值/限值/中断
    SandboxConnectionTest.kt  真跨进程：bind → execute → 回调 → 断连重启

lib_book_common/src/main/java/com/ebook/common/di/SandboxModule.kt  Hilt 装配（Task 10）
lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt  Task 10
lib_book_common/src/main/java/com/ebook/common/BookApplication.kt                        Task 8
module_app/src/main/java/com/ebook/MyApplication.kt                                      Task 8
```

> **`SandboxModule.kt` 为何在 `lib_book_common` 而不是 `lib_book_source`**：Hilt 的 `@Module` 需要 `xrn1997.hilt` 插件与 KSP，`lib_book_source` 两者都没有（它只依赖 jsoup/serialization/OkHttp，见其 `build.gradle.kts`）。为一个新的 DI 模块给解析层引入 KSP 不划算，且 `@Named("source")` 的纯净客户端本来就住在 `lib_book_common`——装配放离依赖最近的一侧。代价是 `JsSandboxHost` 与 `HostCallbackRouter` 必须从 `internal` 上浮为 public（跨模块可见性），`JsCallbackProxy`/`SandboxScriptJs`/`HeadStatusProbe`/`GuardedNetwork` 保持 internal。

分层的判据：**能脱离 Android 与 C 跑的都在 JVM 测**（协议、限值映射、白名单表、纯计算、网络守门、客户端编排、回调代理），**只有真内核与真跨进程留给设备**（Task 7/8 的 androidTest + 人工装机清单）。

## 提交授权

本计划每个 Task 的最后一个 Step 都是「提交（需授权）」。**授权不是默认的**：

- Agent 执行到提交步骤时**停下来报告**（改了什么、验证跑到哪条命令、结果如何），等用户明确说「提交」才执行 `git commit`。连续多个 Task 的提交可以攒成一次授权，但「用户没有回应」不等于授权。
- 未获授权期间**继续往下一 Task 做**是允许的（工作树累积改动即可），前提是下一 Task 不依赖被卡住的构建产物能安装到设备——本计划里只有 Task 8 之后的 androidTest 有该依赖，届时改为停下来说明卡点。
- 一次授权只覆盖当次说明的提交范围。`git push`、开 PR、删分支、改 `gradle.properties` 的 `isModule` 一律单独再问。

---

### Task 1：NDK/CMake 约定插件 `xrn1997.android.native`

**Files:**
- Create: `build-logic/convention/src/main/kotlin/AndroidNativeConventionPlugin.kt`
- Modify: `build-logic/convention/build.gradle.kts`（`gradlePlugin { plugins { } }` 块）
- Modify: `gradle/libs.versions.toml`（`[plugins]` 的本仓插件段）

- [x] **Step 1: 版本目录登记插件 ID**

在 `gradle/libs.versions.toml` 的 `# Plugins defined by this project` 段末尾追加一行：

```toml
xrn1997-android-native = { id = "xrn1997.android.native" }
```

- [x] **Step 2: 写约定插件**

创建 `build-logic/convention/src/main/kotlin/AndroidNativeConventionPlugin.kt`：

```kotlin
package com.xrn1997.convention

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.assign

/**
 * 含 NDK/CMake 原生代码的模块共用的构建约定（ADR-0028 决策 8）。
 *
 * 为什么单独成插件而不是在模块里写一遍：ABI 口径是**安全与产品语义的一部分**而不只是构建细节——
 * release 只出 arm64-v8a（脚本书源只在真机上跑，多余 ABI 等于多份可被研究的内核副本），
 * debug 额外带 x86_64（模拟器验证用）。这类策略必须一处生效、一处审查。
 *
 * ndkVersion 钉死而不是用 AGP 默认：默认值随 AGP 小版本漂移，会让「同一份 C++ 在不同人机器上
 * 编出不同 .so」变成不可复现的问题。升级 NDK 是一次显式改动（需重跑 Task 7 的设备用例）。
 */
class AndroidNativeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            extensions.findByType(CommonExtension::class.java)?.apply {
                ndkVersion = NDK_VERSION
                defaultConfig.ndk.abiFilters += RELEASE_ABIS
                externalNativeBuild.cmake {
                    path = file("src/main/cpp/CMakeLists.txt")
                    version = CMAKE_VERSION
                }
            }
        }
    }

    private companion object {
        const val NDK_VERSION = "28.2.13676358"
        const val CMAKE_VERSION = "3.22.1"
        val RELEASE_ABIS = setOf("arm64-v8a")
    }
}
```

`debug` 的 x86_64 在 Task 2 的模块脚本里按 `buildTypes` 追加——约定插件只定「最小集」，放宽发生在具体模块，读代码时一眼能看出哪个模块多带了 ABI。

> 若 `defaultConfig.ndk.abiFilters += ...` 在该 AGP 版本上类型不匹配（`abiFilters` 是 `MutableSet<String>`，`+=` 需 `assign`），退化成 `defaultConfig.ndk.abiFilters = RELEASE_ABIS`。两种写法编译结果同，取编译通过的那个，不留注释。

> **执行记录（2026-09-09 · 复核轮）**：此处片段与落地的文件有两处出入，都按事实改在这里而不是留在会话里。
> ① **两个 `const` 版本号已挪进版本目录**（`libs.versions.toml` 的 `androidNdk` / `cmake`），插件里改读
> `extensions.getByType(VersionCatalogsExtension::class.java).named("libs")` — 仓规要求「依赖版本仅通过版本
> 目录管理、构建脚本不硬编码版本号」，`build-logic/settings.gradle.kts` 本就把同一份目录挂成 `libs`，
> 没有理由豁免工具版本。`RELEASE_ABIS` 是策略集合不是版本，仍留在这里。
> ② 落地文件**没有** `package com.xrn1997.convention` 声明（与本 Step 片段不同，和同目录其余约定插件一致：
> `gradlePlugin { implementationClass }` 里写的就是裸类名）。
> 另记一条坑：Gradle 9 的 `VersionCatalogsExtension` 在 `org.gradle.api.artifacts` 包下，
> 按老记忆写成 `org.gradle.api.plugins.catalog.*` 会编译期 `Unresolved reference`。
> 证据与详情见「执行期修正记录」第 39 条。

- [x] **Step 3: 注册插件**

在 `build-logic/convention/build.gradle.kts` 的 `gradlePlugin { plugins { } }` 块里，`register("androidComponent")` 之后追加：

```kotlin
        register("androidNative") {
            id = libs.plugins.xrn1997.android.native.get().pluginId
            implementationClass = "AndroidNativeConventionPlugin"
        }
```

- [x] **Step 4: 验证（此步只验证插件能被解析，尚无模块应用它）**

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL`，无 `Plugin with id 'xrn1997.android.native' not found` 之外的新警告。

Run: `./gradlew :build-logic:convention:validatePlugins`
Expected: `BUILD SUCCESSFUL`（该任务配了 `failOnWarning = true`，元数据注解漏了会当场失败）

- [ ] **Step 5: 提交（需用户授权，见「提交授权」段）**

```bash
git add gradle/libs.versions.toml build-logic/convention/build.gradle.kts \
        build-logic/convention/src/main/kotlin/AndroidNativeConventionPlugin.kt
git commit -m "build(build-logic): 新增 NDK/CMake 约定插件收口原生构建口径"
```

---

### Task 2：vendored QuickJS + CMake 把内核编进 `.so`

验收锚点是**链接通过**：`libebook_js.so` 里真含有 `JS_Eval` 符号。此时还没有任何 Kotlin 用它。

**Files:**
- Create: `third_party/quickjs/`（15 个上游文件 + `LICENSE` + `VERSION` + `README.md` + `PIN.sha256`）
- Create: `.gitattributes`
- Create: `lib_book_source/src/main/cpp/CMakeLists.txt`
- Modify: `lib_book_source/build.gradle.kts`

- [x] **Step 1: 以钉死形态取内核**

```bash
cd /tmp && rm -rf quickjs-vendor && git clone --depth 1 https://github.com/bellard/quickjs quickjs-vendor
cd quickjs-vendor && git fetch --depth 1 origin 04be246001599f5995fa2f2d8c91a0f198d3f34c && git checkout FETCH_HEAD
git rev-parse HEAD          # 必须逐字等于 04be246001599f5995fa2f2d8c91a0f198d3f34c
cat VERSION                 # 必须是 2026-06-04
```

Expected: 两行输出与上面一致。不一致就是上游动了，**停下来**——本计划全部 C 侧代码按这一版写。

- [x] **Step 2: 只拷必需文件**

```bash
cd /d/develop/GitHub/android-ebook && mkdir -p third_party/quickjs
SRC=/tmp/quickjs-vendor
for f in quickjs.c libregexp.c libunicode.c cutils.c dtoa.c \
         quickjs.h quickjs-atom.h quickjs-opcode.h cutils.h list.h \
         libregexp.h libregexp-opcode.h libunicode.h libunicode-table.h dtoa.h \
         LICENSE VERSION; do cp "$SRC/$f" third_party/quickjs/; done
ls third_party/quickjs | wc -l      # 17
grep -c setjmp "$SRC/quickjs.c"     # 只读检查：内核五个 .c 不该有 setjmp/signal 依赖
```

Expected: `17`。**没有** `quickjs-libc.c`、`libbf.c`、`repl.js`、`unicode_gen.c`——拷进来就是白送一个能力面（libc 层带 `os.*`/worker/信号处理）。

- [x] **Step 3: 生成漂移锁与出处说明**

```bash
cd /d/develop/GitHub/android-ebook/third_party/quickjs && sha256sum *.c *.h > PIN.sha256 && wc -l PIN.sha256
```

创建 `third_party/quickjs/README.md`：

```markdown
# QuickJS（vendored）

上游：<https://github.com/bellard/quickjs>，提交 `04be246001599f5995fa2f2d8c91a0f198d3f34c`，
`VERSION` 文件记为 `2026-06-04`。许可证 `LICENSE`（MIT），随源码一并保留。

**这些文件是上游原文，未经修改。** 校验：`sha256sum -c PIN.sha256`。

## 为什么是 vendored 而不是子模块或预编译产物

执行陌生 JavaScript 是本仓唯一的「远程代码执行」面，内核必须在审查时当场可读、构建时不依赖
外网可达性（见 ADR-0028 决策 1）。子模块的 checkout 内容随远端移动，预编译 `.a` 无法审计。

## 为什么只有这 15 个文件

`quickjs.c libregexp.c libunicode.c cutils.c dtoa.c` 是内核的全部必需编译单元；其余为它们
include 的头（`libunicode-table.h` 是上游签入的生成物，**不需要任何代码生成步骤**）。
刻意排除：

- `quickjs-libc.c`——POSIX/`os.*`/worker/信号处理层。沙箱的前提就是脚本看不到这些能力。
- `libbf.c`——本版上游已删除 bigfloat/`CONFIG_BIGNUM`，不存在可选形态。

## 升级内核

一次显式改动：取新提交 → 重拷同一份文件清单 → 重新生成 `PIN.sha256` → 重跑
`lib_book_source/src/androidTest` 的沙箱用例（限值、中断、异常档案三项）。
构建期需要的宏见 `lib_book_source/src/main/cpp/CMakeLists.txt`。
```

- [x] **Step 4: 禁止 vendored C 源码被换行归一化改写**

创建 `.gitattributes`（根目录）：

```
# vendored 的第三方 C 源码：必须与上游逐字节相同，否则 PIN.sha256 校验失去意义
# （Windows 上 core.autocrlf 会把这些文件的 LF 改成 CRLF）
third_party/quickjs/** -text
```

> **执行记录（2026-09-09）**：本 Step 单独做**不够**，计划漏了一环。`-text` 只挡将来的转换，修不掉
> 已经被本地 `core.autocrlf=true` 在检出时转成 CRLF 的现有工作树字节；而 Step 3 的 `PIN.sha256`
> 正是从那份 CRLF 算出来的，于是「`sha256sum -c` 通过」证的其实是自己。落地必须再补一步：
> **按上游逐字节核过内容 → 把工作树换成 LF → 从 LF 重算 `PIN.sha256`**（本仓在第 38 条补齐，
> 含 17 个文件的独立上游比对）。根 `.gitattributes` 的最终注释比此处片段完整，以文件为事实源。

- [x] **Step 5: CMake 构建脚本**

创建 `lib_book_source/src/main/cpp/CMakeLists.txt`：

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(ebook_js C CXX)

# vendored 内核与本模块的距离只有构建脚本自己知道，不复制到别处（改路径只改这一行）
set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/quickjs)

add_library(quickjs STATIC
        ${QJS_DIR}/quickjs.c
        ${QJS_DIR}/libregexp.c
        ${QJS_DIR}/libunicode.c
        ${QJS_DIR}/cutils.c
        ${QJS_DIR}/dtoa.c)
target_include_directories(quickjs PUBLIC ${QJS_DIR})
# CONFIG_VERSION 必须定义：JS_DumpMemoryUsage() 无条件拼接它，漏了那一行编不过
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE CONFIG_VERSION="2026-06-04")
# -fwrapv / -funsigned-char 是内核依赖的行为，不是风格选项；-Werror 刻意不开
# （NDK clang 对 bionic 头会给出上游没有的额外警告）
target_compile_options(quickjs PRIVATE
        -O2 -fwrapv -funsigned-char -Wall
        -Wno-sign-compare -Wno-missing-field-initializers -Wno-unused-parameter)

add_library(ebook_js SHARED bridge/js_bridge.cpp)
target_compile_options(ebook_js PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
find_library(log-lib log)
# pthread：CONFIG_ATOMICS 在 Android 上自动开；dl/m 是内核既有依赖
target_link_libraries(ebook_js quickjs ${log-lib} pthread dl m)
```

- [x] **Step 6: 模块先只挂构建脚本与一个最小 JNI 翻译单元**

创建 `lib_book_source/src/main/cpp/bridge/js_bridge.cpp`（Task 7 会整体替换它；此处只为让链接发生）：

```cpp
#include <jni.h>
#include "quickjs.h"

// 占位：Task 7 写真正的桥接层。本文件存在的唯一目的是让链接器当场证明
// vendored 内核可用——JS_Eval 能被解析就说明五个编译单元齐全、宏定义正确。
extern "C" JNIEXPORT jlong JNICALL JNI_OnLoad_check_symbols(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(&JS_Eval);
}
```

修改 `lib_book_source/build.gradle.kts`：在 `plugins { }` 块里 `alias(libs.plugins.xrn1997.android.library)` 之后加一行，并在 `android { }` 块内追加 debug 的额外 ABI。

```kotlin
plugins {
    alias(libs.plugins.xrn1997.android.library)
    alias(libs.plugins.xrn1997.android.native)
}
```

```kotlin
android {
    // 约定插件给的是 release 最小集（只 arm64-v8a）；模拟器调试在这里显式放宽，
    // 放宽点写在模块里而不是插件里，是为了让「谁多带了一份内核」当场可见
    buildTypes.getByName("debug").ndk.abiFilters += "x86_64"
    // …其余既有内容不动
}
```

- [x] **Step 7: 构建并当场核对链接结果**

Run: `./gradlew :lib_book_source:assembleDebug`
Expected: `BUILD SUCCESSFUL`，日志出现 `> Task :lib_book_source:buildCMakeDebug[arm64-v8a]`。

```bash
SO=lib_book_source/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libebook_js.so
ls -la $SO && nm -D --defined-only $SO | grep -c JS_Eval
```

Expected: 文件存在且大小 > 200 KB（内核真在里面，不是一个空壳）；`grep -c` 输出 `1`。
若 `nm` 在本机不可用，用 `C:/software/Android/AndroidSDK/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe`。

- [x] **Step 8: 校验 PIN 与红线**

```bash
cd /d/develop/GitHub/android-ebook/third_party/quickjs && sha256sum -c PIN.sha256 | tail -3
cd /d/develop/GitHub/android-ebook && git grep -in "[l]egado" -- . | wc -l
```

Expected: 全部 `OK`；红线 `0`。

- [ ] **Step 9: 提交（需授权）**

```bash
git add .gitattributes third_party/quickjs lib_book_source/build.gradle.kts \
        lib_book_source/src/main/cpp
git commit -m "build(lib_book_source): 自集成 QuickJS 内核并接入 NDK/CMake 构建" -m \
"以 2026-06-04 提交 04be246 原文 vendored 五个编译单元与配套头，刻意排除 quickjs-libc.c
（POSIX/os.\* 能力面）；链接产物含 JS_Eval，证明内核可用。"
```

---

### Task 3：控制面帧协议 `JsProtocol` + 限值 `JsLimits` + 两个类型化异常

这一层没有任何 Android/原生依赖，是整套东西里最该被测透的一块：**所有失败原因的归类都在这一个文件里**，归错了用户就看到假话。

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsLimits.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsProtocol.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt`
- Create: `lib_book_source/src/test/java/com/ebook/source/sandbox/JsProtocolTest.kt`

- [x] **Step 1: 先写失败的测试**

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/JsProtocolTest.kt`：

```kotlin
package com.ebook.source.sandbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧协议的往返与**失败归类**。
 *
 * 归类表是本文件的真价值：桥接层回给 Kotlin 的只有 `kind`/`errorName`/`errorMessage`/`timedOut`
 * 四个字段，「超时」「内存超限」「栈溢出」三种失败在用户侧必须是三句不同的话——
 * 说错的那句会把用户支去做一件没用的事（重导一条源、或者关掉沙箱）。
 */
class JsProtocolTest {

    @Test
    fun `请求帧往返保留模式、脚本与绑定，null 绑定表示该量不可用`() {
        val inv = JsInvocation(
            mode = JsMode.SEGMENT,
            source = "result.replace(/\\s/g, '')",
            bindings = mapOf("result" to "正文", "baseUrl" to null, "page" to "3"),
        )
        val back = JsProtocol.decodeRequest(JsProtocol.encodeRequest(inv, deadlineMonoMs = 1234L))
        assertEquals(JsMode.SEGMENT, back.invocation.mode)
        assertEquals(inv.source, back.invocation.source)
        assertEquals("正文", back.invocation.bindings["result"])
        assertNull(back.invocation.bindings["baseUrl"])
        assertEquals("3", back.invocation.bindings["page"])
        assertEquals(1234L, back.deadlineMonoMs)
    }

    @Test
    fun `成功响应带 JSON 数据时原样解出，不带时为 null`() {
        val ok = JsProtocol.decodeOutcome(JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, stringJson("章节名"))))
        assertEquals(JsStatus.OK, ok.status)
        assertEquals("章节名", ok.text)

        val undefined = JsProtocol.decodeOutcome(
            JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, data = null)),
        )
        assertEquals(JsStatus.OK, undefined.status)
        assertNull(undefined.text)
    }

    @Test
    fun `脚本完成值是标量时 text 取字面量，是对象时按没有值处置`() {
        // `result = 2` 这类翻页规则在内核里就是 number；旧写法按 String 严格解码会静默给出 null
        val number = JsProtocol.decodeOutcome(JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, JsonPrimitive(42))))
        assertEquals("42", number.text)
        val boolean = JsProtocol.decodeOutcome(JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, JsonPrimitive(true))))
        assertEquals("true", boolean.text)

        val obj = JsProtocol.decodeOutcome(
            JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, Json.parseToJsonElement("""{"a":1}""")))),
        )
        assertNull(obj.text)
        assertEquals(Json.parseToJsonElement("""{"a":1}"""), obj.data)
    }

    @Test
    fun `桥的 exception 帧按 errorName 与 errorMessage 归类为超时、内存、栈、语法、运行时`() {
        // 超时：中断器置位，与「脚本自己抛了个异常」是两件事
        assertEquals(
            JsStatus.TIMEOUT,
            JsProtocol.mapStatus(kind = "exception", errorName = "InternalError", errorMessage = "", timedOut = true),
        )
        // 内核 OOM 与栈溢出都是 InternalError，只有 message 能分开
        assertEquals(
            JsStatus.MEMORY,
            JsProtocol.mapStatus("exception", "InternalError", "out of memory", false),
        )
        assertEquals(
            JsStatus.STACK,
            JsProtocol.mapStatus("exception", "InternalError", "stack overflow", false),
        )
        assertEquals(
            JsStatus.SYNTAX,
            JsProtocol.mapStatus("exception", "SyntaxError", "unexpected token", false),
        )
        assertEquals(
            JsStatus.RUNTIME,
            JsProtocol.mapStatus("exception", "TypeError", "not a function", false),
        )
    }

    @Test
    fun `未识别的 kind 一律按运行时失败，绝不折叠成成功`() {
        assertEquals(
            JsStatus.RUNTIME,
            JsProtocol.mapStatus(kind = "nonsense", errorName = "", errorMessage = "", timedOut = false),
        )
        assertFalse(JsOutcome(JsStatus.RUNTIME, error = "x").isSuccess)
    }

    @Test
    fun `响应报文超上限时解码即判 TOO_LARGE，不把超限的原文交给调用方`() {
        // 上限用「调小限值」来测，不靠造几 MB 的字符串：造大帧只会让这条用例自己吃掉几十 MB
        val frame = JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, stringJson("x".repeat(100))))
        val decoded = JsProtocol.decodeOutcome(frame, JsLimits(maxOutcomeBytes = 40))
        assertEquals(JsStatus.TOO_LARGE, decoded.status)
        assertNull(decoded.data)
        assertTrue(decoded.error!!.contains("上限"))
    }

    @Test
    fun `报文长度按 UTF-8 字节数算而非 UTF-16 码元数`() {
        // String.length 是码元数，拿它当字节数会把汉字源少算三倍；代理对若按两码元各算三字节又会多算。
        // emoji 用转义写，用例判定不随源文件编码漂移。
        assertEquals(1L, utf8Length("a"))
        assertEquals(2L, utf8Length("é"))
        assertEquals(3L, utf8Length("字"))
        assertEquals(4L, utf8Length("\uD83D\uDE00"))
        assertEquals(8L, utf8Length("a字\uD83D\uDE00"))
        assertEquals("x字".toByteArray().size.toLong(), utf8Length("x字"))
    }

    @Test
    fun `上限判的是整帧字节数：等于帧长放行、小一字节即拒`() {
        val frame = JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, stringJson("汉字汉字")))
        val size = utf8Length(frame)
        assertEquals(JsStatus.OK, JsProtocol.decodeOutcome(frame, JsLimits(maxOutcomeBytes = size.toInt())).status)
        assertEquals(
            JsStatus.TOO_LARGE,
            JsProtocol.decodeOutcome(frame, JsLimits(maxOutcomeBytes = (size - 1).toInt())).status,
        )
    }

    @Test
    fun `host 回调帧往返保留 api 与参数，回复帧的 ok=false 带得出错误`() {
        val (api, args) = JsProtocol.decodeHostCall(JsProtocol.encodeHostCall("ajax", """{"url":"https://x"}"""))
        assertEquals("ajax", api)
        assertTrue(args.contains("https://x"))

        val reply = JsProtocol.decodeHostReply(JsProtocol.encodeHostReply(ok = false, data = null, error = "私网地址"))
        assertFalse(reply.ok)
        assertEquals("私网地址", reply.error)
    }

    @Test
    fun `绑定值不是合法 JSON 文本时按字符串兜住，绝不让解码抛出去`() {
        // 桥接层解码抛出去 = .so 里拿到一个未定义形态，症状是崩进程而不是报错
        val frame = """{"mode":"SEGMENT","source":"x","bindings":{"result":不是JSON}}"""
        val decoded = runCatching { JsProtocol.decodeRequest(frame) }
        assertTrue("解码失败必须返回类型化失败而不是抛：${decoded.exceptionOrNull()}", decoded.isFailure)
        assertTrue(decoded.exceptionOrNull() is SandboxProtocolException)
    }

    private fun stringJson(s: String): JsonElement =
        Json.parseToJsonElement(Json.encodeToString(String.serializer(), s))
}
```

需要这些 import：`kotlinx.serialization.builtins.serializer`、`kotlinx.serialization.json.Json`、`kotlinx.serialization.json.JsonElement`、`kotlinx.serialization.json.JsonPrimitive`。

- [x] **Step 2: 跑一遍确认它失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsProtocolTest*"`
Expected: **编译失败**（`JsProtocol`/`JsLimits`/`SandboxProtocolException` 未定义）。这就是本步的预期红。

- [x] **Step 3: 写限值**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsLimits.kt`：

```kotlin
package com.ebook.source.sandbox

/**
 * 沙箱的四项资源限制（ADR-0028 决策 5）的唯一来源。
 *
 * 集中一处是为了「调校有地方调」：这些数字将来一定按真实源的失败率改（正文里含整页 HTML 的
 * 源会先撞上报文上限，而不是堆上限），散在五个文件里改一次就漏一次。
 *
 * 三个数各自要防的攻击不同：wall-clock 防死循环，堆防指数分配，栈防无限递归。
 * 报文上限防的是另一件事——**跨进程与解析 JSON 的内存**：脚本可以合法地算出一个 200 MB 的字符串，
 * 堆限不住它（那是响应侧的账），所以响应文本必须在进主进程前就被判掉。
 */
data class JsLimits(
    /** 单次执行的挂钟上限；由执行器线程的中断器逐轮比对，**不保证**打断阻塞中的 host call */
    val wallClockMs: Long = 5_000L,
    /** QuickJS 堆上限：8 MB。一本小说正文的字符串远小于此，留的是正则回溯的余量 */
    val heapBytes: Long = 8L * 1024 * 1024,
    /** JS 栈上限：1 MB。低于内核默认，让深递归在撞 native 栈之前就以内核异常结束 */
    val stackBytes: Long = 1024L * 1024,
    /**
     * 请求帧（含整页 HTML 绑定时）上限：900 KB。
     *
     * 这三个字节上限的尺子**不是内存，是 binder 事务缓冲**：Android 给每个进程的缓冲约 1 MB，
     * 且被该进程所有在飞的事务共用。写 8 MB 只会让超限的帧在现场抛 `TransactionTooLargeException`
     * ——一个未类型化、且只在真机上出现的失败；判在本地才换得到一句「这页太大」的人话。
     *
     * 留出 100 KB 余量的前提是**同一时刻只有一帧在飞**：`JsSandboxClient` 用一把锁串行化 execute
     * （Task 6），执行器侧也只有一个工作线程（Task 8）。两处任一放开并发，这个数就要跟着减半。
     */
    val maxRequestBytes: Int = 900_000,
    /** 响应帧上限：900 KB。正文一页的 UTF-8 字节数远小于此（汉字页 20 KB 文本约 60 KB 字节） */
    val maxOutcomeBytes: Int = 900_000,
    /** 单次 host 回调（网络代理返回的整页）上限：900 KB，同受 binder 缓冲约束 */
    val maxHostReplyBytes: Int = 900_000,
    /** 回调重入深度上限：脚本里套规则、规则里再套脚本，超深即失败，不指望内核栈限来兜 */
    val maxCallbackDepth: Int = 8,
    /**
     * 单任务网络代理请求次数上限：〔ADR-0028 决策 6〕的「限流上限」。
     * 由主进程侧计数（`JsCallbackProxy`，Task 9），执行器与守门器都不持计数器。
     * 40 的口径来自语料：一轮正文解析通常是「取文 + 若干 CDN 图片 + 一次翻页探测」，
     * 聚合搜索里单源单页最多十几次；超出这个量级的脚本几乎必然是 `while(true) ajax()`。
     */
    val maxRequestsPerTask: Int = 40,
)
```

- [x] **Step 4: 写异常**

在 `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt` 末尾追加（**不动**既有四类；`JsEvaluationPendingException` 的 KDoc 在 Task 10 一并改口径）：

```kotlin
/**
 * 沙箱回了一帧读不出来的东西。
 *
 * 必须与「规则解不动」分开：帧坏掉意味着执行器与主进程的代码版本不一致（或 .so 与 Kotlin 不同步），
 * 是**本仓的接线缺陷**，不是作者写坏了规则。把两者混成一条消息，排查时会去改一条根本没错的规则。
 */
class SandboxProtocolException(message: String, cause: Throwable? = null) : ScriptRuleException(
    "沙箱响应无法解析：${message.take(120)}",
    cause,
)

/**
 * 沙箱此刻不可用（未绑定上、进程被杀、加载 .so 失败）。
 *
 * 与 [JsEvaluationPendingException] 的区别是时机性的：后者是「这条规则含 JS，而本仓没有 JS 能力」，
 * 前者是「能力在，这一次没跑起来」。只有前者会稳定复现，只有后者值得让用户去重导源。
 */
class SandboxUnavailableException(reason: String, cause: Throwable? = null) : ScriptRuleException(
    "脚本沙箱执行器当前不可用：${reason.take(120)}",
    cause,
)
```

- [x] **Step 5: 写协议**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsProtocol.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.SandboxProtocolException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 执行哪种 JS 载荷：决定桥接层如何取「结果值」，以及主进程侧把哪些绑定喂进去 */
enum class JsMode {
    /** 整条规则段（`@js:` / `<js></js>`）：程序的完成值 */
    SEGMENT,

    /** `{{…}}` 里的表达式：包成表达式取单个字符串 */
    EXPRESSION,

    /** URL 选项 `js`：脚本改写 `url`/`headers`，回传对象 */
    URL_OPTION,

    /** URL 选项 `bodyJs`：对响应体做二次处理，回传新文本 */
    BODY_JS,

    /** `init` 的 JS 分支：必须回传一个对象，各字段按对象键取值（规格 §1.4） */
    INIT,
}

/** 执行器回给调用方的失败/成功归类。枚举值的顺序无意义，但**每个值都对应一句不同的用户话术** */
enum class JsStatus {
    OK,
    TIMEOUT,
    MEMORY,
    STACK,
    SYNTAX,
    RUNTIME,
    /** 脚本调了白名单外的能力（`cache`、`cookie`、Java 类桥等） */
    UNSUPPORTED_API,

    /** 响应报文超过 [JsLimits.maxOutcomeBytes] */
    TOO_LARGE,

    /** 执行器没连上 / 中途断掉 / 加载失败 */
    UNAVAILABLE,
}

/** 一次求值的输入。[bindings] 值为 null 表示「该量本场景不可用」，垫片会把它显式设成 `undefined` */
data class JsInvocation(
    val mode: JsMode,
    val source: String,
    val bindings: Map<String, String?> = emptyMap(),
)

/** 一次求值的输出。[data] 是脚本完成值的 JSON 形态，[error] 只在与 [JsStatus.OK] 同时出现时为 null */
data class JsOutcome(
    val status: JsStatus,
    val data: JsonElement? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = status == JsStatus.OK

    /**
     * 完成值按字符串取（`@js:` 产文本、`bodyJs` 产新页面文本都走这里）。
     *
     * 判据是 **JSON 标量**：数字与布尔也取得出文本（`result = 2` 这类翻页规则在内核里就是
     * number），只有对象与数组取不出——那是「规则要字符串而脚本给了个结构」，按没有值处置。
     * 不用「先按 String 严格解码、解不动就 null」：number 那一支会静默失败，症状正是
     * 「这条源解不出正文」而一行报错都没有，是本项目最忌讳的一类坏消息。
     */
    val text: String? get() = (data as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
}

/**
 * 主进程回给脚本的一次 host call 结果。
 *
 * public 而非 internal：Task 6 的 `HostHandler`（公开签名，`lib_book_common` 的 Hilt 装配要构造它）
 * 以此为返回类型，公开签名上不许出现 internal 类型。
 */
data class HostReply(val ok: Boolean, val data: JsonElement?, val error: String?)

@Serializable
private data class RequestFrame(
    val mode: String,
    val source: String,
    val bindings: Map<String, String?>,
    @SerialName("deadline") val deadlineMonoMs: Long,
)

@Serializable
private data class OutcomeFrame(
    val status: String,
    val data: JsonElement? = null,
    val error: String? = null,
)

/**
 * 控制面的帧编解码 + 失败归类。
 *
 * 为什么单独一层（而不是让桥接层直接回 Kotlin 对象）：协议是**跨进程边界上的唯一契约**，
 * 它的兼容性只由这里的 JSON 形态决定。两侧共用这一个文件，就不会出现「改了字段只改了一侧」
 * 这种当场看不出来、跑起来才静默丢值的错。
 *
 * 解码一律不往外抛未类型化异常：坏帧在设备上的另一条路径是在 `.so` 的调用栈里炸出去
 * （整个 `:js` 进程随之没），所以「读不出来」必须是一种有名字的失败。
 */
internal object JsProtocol {

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encodeRequest(invocation: JsInvocation, deadlineMonoMs: Long): String =
        json.encodeToString(
            RequestFrame.serializer(),
            RequestFrame(invocation.mode.name, invocation.source, invocation.bindings, deadlineMonoMs),
        )

    fun decodeRequest(frame: String): DecodedRequest =
        parse(frame, RequestFrame.serializer()).let {
            DecodedRequest(
                JsInvocation(JsMode.valueOf(it.mode), it.source, it.bindings),
                it.deadlineMonoMs,
            )
        }

    data class DecodedRequest(val invocation: JsInvocation, val deadlineMonoMs: Long)

    fun encodeOutcome(outcome: JsOutcome): String =
        json.encodeToString(OutcomeFrame.serializer(), OutcomeFrame(outcome.status.name, outcome.data, outcome.error))

    /**
     * 解码响应帧。
     *
     * 大小检查**先于** JSON 解析：一个 200 MB 的响应如果先进了 `String`，报文上限就只是在
     * 保护「解析之后的内存」，而攻击要的正是「解析这一步」。
     */
    fun decodeOutcome(frame: String, limits: JsLimits = JsLimits()): JsOutcome {
        if (utf8Length(frame) > limits.maxOutcomeBytes) {
            return JsOutcome(
                JsStatus.TOO_LARGE,
                error = "沙箱响应超出 ${limits.maxOutcomeBytes} 字节上限",
            )
        }
        return parse(frame, OutcomeFrame.serializer()).let {
            JsOutcome(mapStatus(it.status), it.data, it.error)
        }
    }

    /**
     * 归类桥接层的一条异常。
     *
     * 判定次序是刻意的：**先看中断标志，再看 message 关键字**。内核在超时后会抛
     * `InternalError("interrupted")`，此时 message 里什么都可能带上（脚本正好在分配大对象时被打断），
     * 按 message 归类就会把「超时」误报成「内存超限」，用户会去关一条根本没那么吃内存的源。
     *
     * 关键字匹配是**能有的最好办法**：本内核的异常对象不带稳定的错误码字段
     * （`JS_ThrowOutOfMemory` 与 `JS_ThrowStackOverflow` 抛的都是 `InternalError` + 固定 message）。
     */
    fun mapStatus(kind: String, errorName: String, errorMessage: String, timedOut: Boolean): JsStatus = when {
        kind == "ok" && timedOut -> JsStatus.TIMEOUT   // 完成值拿到了但已超时：仍然算超时，半截值不可信
        kind == "ok" -> JsStatus.OK
        timedOut -> JsStatus.TIMEOUT
        kind == "unsupported" -> JsStatus.UNSUPPORTED_API
        kind == "exception" -> when {
            errorName == "SyntaxError" -> JsStatus.SYNTAX
            errorMessage.contains("out of memory", ignoreCase = true) -> JsStatus.MEMORY
            errorMessage.contains("stack overflow", ignoreCase = true) ||
                errorMessage.contains("too deep", ignoreCase = true) -> JsStatus.STACK

            else -> JsStatus.RUNTIME
        }

        else -> JsStatus.RUNTIME
    }

    private fun mapStatus(statusName: String): JsStatus =
        // 未知状态名不能折叠成 OK——那是「把失败说成没有结果」
        runCatching { JsStatus.valueOf(statusName) }.getOrDefault(JsStatus.RUNTIME)

    fun encodeHostCall(api: String, argsJson: String): String =
        json.encodeToString(HostCallFrame.serializer(), HostCallFrame(api, argsJson))

    fun decodeHostCall(frame: String): Pair<String, String> =
        parse(frame, HostCallFrame.serializer()).let { it.api to it.args }

    fun encodeHostReply(ok: Boolean, data: JsonElement?, error: String?): String =
        json.encodeToString(HostReplyFrame.serializer(), HostReplyFrame(ok, data, error))

    fun decodeHostReply(frame: String): HostReply = parse(frame, HostReplyFrame.serializer())
        .let { HostReply(it.ok, it.data, it.error) }

    @Serializable
    private data class HostCallFrame(val api: String, val args: String)

    @Serializable
    private data class HostReplyFrame(val ok: Boolean, val data: JsonElement? = null, val error: String? = null)

    /** 所有解码的唯一入口：把 kotlinx 的 `SerializationException` 换成 [SandboxProtocolException] */
    private fun <T> parse(frame: String, serializer: KSerializer<T>): T =
        runCatching { json.decodeFromString(serializer, frame) }.getOrElse {
            throw SandboxProtocolException("帧形态不符：${frame.take(60)}", it)
        }
}

/**
 * UTF-8 编码后的字节数，**不分配中间数组**。
 *
 * 写成 `text.toByteArray().size` 也能得到正确答案，但「检查大小」这一步自己就要先把整帧复制一遍：
 * 一个 200 MB 的响应会当场多出 200 MB，而报文上限想防的正是这一笔。纯算术零分配，
 * 代价是必须自己认代理对（增补平面字符 4 字节、在 Java 里占 2 个 UTF-16 码元）。
 *
 * 三个上限（请求 / 响应 / host 回复）都取这一个口径，否则同一帧会在两侧算出两个长度。
 */
internal fun utf8Length(text: String): Long {
    var i = 0
    var total = 0L
    while (i < text.length) {
        val cp = text.codePointAt(i)
        total += when {
            cp < 0x80 -> 1L
            cp < 0x800 -> 2L
            cp < 0x10000 -> 3L
            else -> 4L
        }
        i += Character.charCount(cp)
    }
    return total
}
```

文件顶部还需要 `import kotlinx.serialization.KSerializer`。

- [x] **Step 6: 跑测试**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsProtocolTest*"`
Expected: 11 例全绿。

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: 全模块绿（`ScriptRuleExceptions.kt` 只是追加，既有 330 例不受影响）。

- [ ] **Step 7: 提交（需授权）**

```bash
git add lib_book_source/src/main/java/com/ebook/source/sandbox/JsProtocol.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsLimits.kt \
        lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt \
        lib_book_source/src/test/java/com/ebook/source/sandbox/JsProtocolTest.kt
git commit -m "feat(lib_book_source): 定义沙箱控制面帧协议与失败归类"
```

---

### Task 4：Host API 白名单定版（表 + 纯计算实现 + JS 垫片）

ADR-0028 决策 6 要求白名单「按语料频谱定版」。定版必须落在**一处可审查的清单**上，而不是一散在 C++、JS、Kotlin 三处的名字。本任务给出三件东西并且互相校验：

- `JsHostApi`：能力表（名字 / 元数 / 落点 `COMPUTE`|`HOST`）。
- `HostCompute`：`COMPUTE` 分支的实现，跑在**执行器进程内的 Kotlin**（偏差 1）。JDK 密码学在 JVM 上就能用真实向量测透。
- `QuickJsPrelude`：脚本看见的那层 JS 垫片。

一致性由一条单测守住：表里每个名字必须在垫片里出现，垫片里登记的每个名字必须在表里。加一个能力只改两处，漏一处当场红。

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/JsHostApi.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/HostCompute.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/QuickJsPrelude.kt`
- Create: `lib_book_source/src/test/java/com/ebook/source/sandbox/HostComputeTest.kt`
- Create: `lib_book_source/src/test/java/com/ebook/source/sandbox/JsHostApiTest.kt`

- [x] **Step 1: 先写失败的测试（真实密码学向量）**

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/HostComputeTest.kt`：

```kotlin
package com.ebook.source.sandbox

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯计算类 Host API 的实现。
 *
 * 这一层刻意留在 Kotlin 而不是 C++（ADR-0028 偏差 1），**为的就是这个文件能跑**：
 * 摘要与分组密码的正确性只有拿已知向量当场比才对得起「能解开签名 URL」这句话，
 * 而 C++ 实现要等到设备上才看得到结果。
 */
class HostComputeTest {

    private fun args(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(it) } }

    private fun compute(api: String, vararg values: String): String =
        Json.decodeFromJsonElement(String.serializer(), HostCompute.invoke(api, args(*values)))

    private fun dataOf(api: String, vararg values: String): JsonElement = HostCompute.invoke(api, args(*values))

    @Test
    fun `三种摘要对已知向量`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", compute("md5", "abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", compute("sha1", "abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            compute("sha256", "abc"),
        )
        // 空串是签名 URL 里常见的一段，字节序错了整站解不开
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", compute("md5", ""))
    }

    @Test
    fun `摘要按 UTF-8 字节参与计算且输出小写十六进制`() {
        // 非 ASCII：UTF-8 字节序列参与摘要，与「先转 latin1」是两种结果，锁住
        assertEquals("9e1e6dd6dc475100867c2490d848c6a3", compute("md5", "小说"))
        assertTrue(compute("sha256", "x").all { it.isLowerCase() || it.isDigit() })
    }

    @Test
    fun `base64 族编解码与 URL 安全形态`() {
        assertEquals("YWJj", compute("base64Encode", "abc"))
        assertEquals("abc", compute("base64Decode", "YWJj"))
        // 标准形态与 URL 安全形态必须可区分：签名 URL 里的 base64 会被 +/= 转义吃掉
        assertEquals("-_8", compute("base64DecodeUrl", compute("base64EncodeUrl", String(byteArrayOf(-3, -2)))))
        assertTrue(compute("base64EncodeUrl", String(byteArrayOf(-3, -2))).none { "+/=".contains(it) })
    }

    @Test
    fun `hex 编解码往返且不被负字节符号扩展污染`() {
        assertEquals("616263", compute("hexEncode", "abc"))
        assertEquals("abc", compute("hexDecode", "616263"))
        // 0xff 若按 Byte 直接格式化会变成 8 位（ffffffxx），这里必须恰好 2 位
        assertEquals("ff", compute("hexEncode", String(byteArrayOf(-1))))
    }

    @Test
    fun `AES 往返且同键同文明文必得同密文`() {
        val key = "0123456789abcdef"
        val plain = "第一章 山村"
        val cipher = compute("aesEncode", plain, key)
        assertEquals(plain, compute("aesDecode", cipher, key))
        // 没有随机 IV（IV 由 key 派生），所以密文可复现。这条断言锁的是
        // 「将来有人顺手改成随机 IV」——那会让源里 aesEncode 的结果不再能当 URL 参数复用。
        assertEquals(cipher, compute("aesEncode", plain, key))
    }

    @Test
    fun `短密钥补零到块长且长密钥截断，都不抛`() {
        val short = compute("aesEncode", "abc", "k")
        assertEquals("abc", compute("aesDecode", short, "k"))
        val longKey = "0123456789abcdefghijklmnopqrstuvwxyz"
        val long = compute("aesEncode", "abc", longKey)
        assertEquals("abc", compute("aesDecode", long, longKey))
    }

    @Test
    fun `DES 往返`() {
        val cipher = compute("desEncode", "abc", "12345678")
        assertEquals("abc", compute("desDecode", cipher, "12345678"))
    }

    @Test
    fun `密文不是合法 base64 时按拒绝报错而不是返回坏值`() {
        val e = runCatching { HostCompute.invoke("aesDecode", args("不是密文", "0123456789abcdef")) }
            .exceptionOrNull()
        assertTrue("必须抛类型化拒绝：$e", e is JsApiRejectedException)
        assertTrue(e!!.message!!.contains("aesDecode"))
    }

    @Test
    fun `时间格式化产出形状正确，时间戳是数字文本`() {
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(compute("FormatDate", "0", "yyyy-MM-dd")))
        assertTrue(compute("timestamp").all { it.isDigit() })
    }

    @Test
    fun `未知 api 一律拒绝，绝不静默回 null`() {
        // null 在垫片里的形态是「调用成功、值为 null」，把不存在的 api 折叠成 null
        // 等于让脚本以为这次调用真的生效了
        val e = runCatching { HostCompute.invoke("nope", args()) }.exceptionOrNull()
        assertTrue(e is JsApiRejectedException)
    }

    @Test
    fun `参数缺失按拒绝报出缺第几个参数`() {
        val e = runCatching { dataOf("FormatDate", "0") }.exceptionOrNull()
        assertTrue(e is JsApiRejectedException)
        assertTrue(e!!.message!!.contains("第 2 个参数"))
    }
}
```

- [x] **Step 2: 跑一遍确认它失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*HostComputeTest*"`
Expected: 编译失败（`HostCompute` / `JsApiRejectedException` 未定义）。

- [x] **Step 3: 写能力表**

创建 `lib_book_source/src/main/java/com/ebook/source/script/JsHostApi.kt`（与 `ScriptRuleExceptions.kt` 同包，白名单的类型化拒绝异常也与既有异常同处一档）：

```kotlin
package com.ebook.source.script

/** 白名单能力的落点：执行器进程内算完，还是要回主进程 */
enum class JsApiTarget { COMPUTE, HOST }

/**
 * Host API 白名单（ADR-0028 决策 6 的定版）。
 *
 * deny-by-default 在这里的含义是**这份表之外的名字在脚本里不存在**：不是「存在后被禁用」，
 * 而是 QuickJS 的全局对象上根本没有那个属性。所以这张表是唯一的能力清单，
 * 审查「脚本能干什么」只需要读这一个文件。
 *
 * [minArgs]/[maxArgs] 由 JS 垫片就地校验（`maxArgs == 0` 表示不限上限）。校验放在 JS 侧，
 * 是因为元数不符在内核里只会拿到 `undefined` 参数、然后走进一条看着「正常失败」的路径，
 * 症状会从「你少传了一个参数」变成「这个站解不开」。
 */
enum class JsHostApi(
    val name: String,
    val target: JsApiTarget,
    val minArgs: Int,
    val maxArgs: Int = minArgs,
) {
    // —— 纯计算：摘要与编码族 ——
    MD5("md5", JsApiTarget.COMPUTE, 1),
    SHA1("sha1", JsApiTarget.COMPUTE, 1),
    SHA256("sha256", JsApiTarget.COMPUTE, 1),
    BASE64_ENCODE("base64Encode", JsApiTarget.COMPUTE, 1),
    BASE64_DECODE("base64Decode", JsApiTarget.COMPUTE, 1),
    BASE64_ENCODE_URL("base64EncodeUrl", JsApiTarget.COMPUTE, 1),
    BASE64_DECODE_URL("base64DecodeUrl", JsApiTarget.COMPUTE, 1),
    HEX_ENCODE("hexEncode", JsApiTarget.COMPUTE, 1),
    HEX_DECODE("hexDecode", JsApiTarget.COMPUTE, 1),

    // —— 纯计算：对称加解密（签名 URL 类源的前提）——
    AES_ENCODE("aesEncode", JsApiTarget.COMPUTE, 2),
    AES_DECODE("aesDecode", JsApiTarget.COMPUTE, 2),
    DES_ENCODE("desEncode", JsApiTarget.COMPUTE, 2),
    DES_DECODE("desDecode", JsApiTarget.COMPUTE, 2),

    // —— 纯计算：时间 ——
    TIMESTAMP("timestamp", JsApiTarget.COMPUTE, 0),
    FORMAT_DATE("FormatDate", JsApiTarget.COMPUTE, 2),

    // —— 网络：一律经主进程受限代理 ——
    AJAX("ajax", JsApiTarget.HOST, 1, 2),
    POST("post", JsApiTarget.HOST, 2),
    LOAD("load", JsApiTarget.HOST, 1, 2),
    RESPONSE_CODE("responseCode", JsApiTarget.HOST, 1),

    // —— 变量（规格 §5.2：JS 内用 `java.put` / `java.get`，任务级作用域）——
    PUT_VAR("putVar", JsApiTarget.HOST, 2),
    GET_VAR("getVar", JsApiTarget.HOST, 1),
    REMOVE_VAR("rmVar", JsApiTarget.HOST, 1),

    // —— 递归规则求值（脚本把规则串交回解释器）——
    GET_ELEMENTS("getElements", JsApiTarget.HOST, 1),
    GET_ELEMENT("getElement", JsApiTarget.HOST, 1),
    QUERY_STRING("queryString", JsApiTarget.HOST, 1),
    PUT_TO_PAGE("putToPage", JsApiTarget.HOST, 2),

    // —— 日志：toast 一族映射成日志，不弹 UI ——
    TOAST("toast", JsApiTarget.HOST, 1),
    LOG("log", JsApiTarget.HOST, 1),
    ;

    companion object {
        val NAMES: Set<String> = entries.map { it.name }.toSet()
    }
}

/** 白名单外的 api，或参数/密钥坏到算不下去 */
class JsApiRejectedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- [x] **Step 4: 写纯计算实现**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/HostCompute.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.JsApiRejectedException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `JsApiTarget.COMPUTE` 分支的实现，跑在**执行器进程内**（零跨进程往返）。
 *
 * 两条贯穿全文件的决定：
 *
 * 1. **一律 UTF-8 字节**做摘要/加密的输入，摘要一律小写十六进制输出。上游未规定，本仓定版，
 *    锁在测试里——改它会让「服务端签名对不上」变成看不出来的错。
 * 2. **失败一律抛 [JsApiRejectedException]**，不回 null。null 在垫片里的形态是
 *    「调用成功、值为 null」，脚本会拿它拼出 `url=null` 继续请求，那才是最难查的假成功。
 *
 * 分组密码的密钥与 IV 派生是**本仓规定**（规格未核实上游语义）：key 的 UTF-8 字节按
 * ≥32/≥24/其余 取 256/192/128 位，不足补零、超出截断；AES 用 CBC 且 IV 取密钥字节前 16 字节，
 * DES 用 ECB。无随机 IV 是刻意的：脚本里 `aesEncode(x, k)` 的结果要被当 URL 参数复用，必须可复现。
 * 真实源若用别的派生方式，会在装机清单「签名 URL 类源」一项暴露，届时按源加选项、不改默认。
 */
internal object HostCompute {

    fun invoke(api: String, args: JsonElement?): JsonElement {
        val list = (args as? JsonArray)?.map { it.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
        return when (api) {
            "md5" -> string(digest("MD5", list.at(0)))
            "sha1" -> string(digest("SHA-1", list.at(0)))
            "sha256" -> string(digest("SHA-256", list.at(0)))
            "base64Encode" -> string(encodeBase64(list.at(0).toByteArray(), urlSafe = false))
            "base64Decode" -> string(decodeBase64(list.at(0), urlSafe = false).decodeToString())
            "base64EncodeUrl" -> string(encodeBase64(list.at(0).toByteArray(), urlSafe = true))
            "base64DecodeUrl" -> string(decodeBase64(list.at(0), urlSafe = true).decodeToString())
            "hexEncode" -> string(hex(list.at(0).toByteArray()))
            "hexDecode" -> string(unhex(list.at(0)).decodeToString())
            "aesEncode" -> string(crypt(Cipher.ENCRYPT_MODE, AES, list.at(0), list.at(1)))
            "aesDecode" -> string(crypt(Cipher.DECRYPT_MODE, AES, list.at(0), list.at(1)))
            "desEncode" -> string(crypt(Cipher.ENCRYPT_MODE, DES, list.at(0), list.at(1)))
            "desDecode" -> string(crypt(Cipher.DECRYPT_MODE, DES, list.at(0), list.at(1)))
            "timestamp" -> string(System.currentTimeMillis().toString())
            "FormatDate" -> string(formatDate(list.at(0), list.at(1)))
            else -> throw JsApiRejectedException("沙箱不提供计算能力 $api")
        }
    }

    private val stringSerializer = String.serializer()
    private fun string(value: String): JsonElement = Json.encodeToJsonElement(stringSerializer, value)

    private fun List<String>.at(i: Int): String =
        elementAtOrNull(i) ?: throw JsApiRejectedException("参数个数不符：需要第 ${i + 1} 个参数")

    private fun digest(algorithm: String, input: String): String = runCatching {
        hex(MessageDigest.getInstance(algorithm).digest(input.toByteArray()))
    }.getOrElse { throw JsApiRejectedException("$algorithm 计算失败", it) }

    private fun encodeBase64(bytes: ByteArray, urlSafe: Boolean): String =
        (if (urlSafe) Base64.getUrlEncoder().withoutPadding() else Base64.getEncoder())
            .encodeToString(bytes)

    private fun decodeBase64(text: String, urlSafe: Boolean): ByteArray =
        runCatching {
            if (urlSafe) Base64.getUrlDecoder().decode(text) else Base64.getMimeDecoder().decode(text)
        }.getOrElse { throw JsApiRejectedException("base64 解码失败：${text.take(24)}", it) }

    /** 自己拼十六进制而不是用 `java.util.HexFormat`：后者要 API 31，本仓 minSdk 26 */
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { append((it.toInt() and 0xff).toString(16).padStart(2, '0')) }
    }

    private fun unhex(text: String): ByteArray {
        val clean = text.trim()
        if (clean.length % 2 != 0) throw JsApiRejectedException("hex 长度不是偶数：${clean.take(24)}")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte()
                ?: throw JsApiRejectedException("hex 含非十六进制字符：${clean.take(24)}")
        }
    }

    /**
     * 密文按 base64 文本进出。加密分支直接 `encodeBase64(doFinal(...))`——
     * **绝不**先把密文按字符串解一遍再编码：分组密码的输出不是文本，走字符串会不可逆地坏掉，
     * 症状是「一部分书能解、一部分永远报错」。
     */
    private fun crypt(mode: Int, algorithm: String, data: String, key: String): String {
        val keyBytes = keyMaterial(key, algorithm)
        val cipher = runCatching {
            Cipher.getInstance(if (algorithm == DES) "DES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding").apply {
                val spec = SecretKeySpec(keyBytes, algorithm)
                if (algorithm == DES) init(mode, spec) else init(mode, spec, IvParameterSpec(keyBytes.copyOf(16)))
            }
        }.getOrElse { throw JsApiRejectedException("$algorithm 初始化失败", it) }
        val produced = runCatching {
            if (mode == Cipher.ENCRYPT_MODE) {
                encodeBase64(cipher.doFinal(data.toByteArray()), urlSafe = false)
            } else {
                cipher.doFinal(decodeBase64(data, urlSafe = false)).decodeToString()
            }
        }.getOrElse { throw JsApiRejectedException("$algorithm ${if (mode == Cipher.ENCRYPT_MODE) "加密" else "解密"}失败", it) }
        return produced
    }

    private fun keyMaterial(key: String, algorithm: String): ByteArray {
        val raw = key.toByteArray()
        val size = when {
            algorithm == DES -> 8
            raw.size >= 32 -> 32
            raw.size >= 24 -> 24
            else -> 16
        }
        return ByteArray(size).also { raw.copyInto(it, 0, 0, minOf(raw.size, size)) }
    }

    private fun formatDate(millis: String, pattern: String): String {
        val ts = millis.toLongOrNull() ?: throw JsApiRejectedException("时间戳不是数字：${millis.take(20)}")
        return runCatching { SimpleDateFormat(pattern, Locale.US).format(Date(ts)) }
            .getOrElse { throw JsApiRejectedException("日期格式不可用：${pattern.take(24)}", it) }
    }

    private const val AES = "AES"
    private const val DES = "DES"
}
```

- [x] **Step 5: 跑绿**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*HostComputeTest*"`
Expected: 11 例全绿。`hexEncode` 那例若红，先查 `and 0xff` 有没有写丢——负字节的症状正是「8 位十六进制」。

- [x] **Step 6: 写 JS 垫片**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/QuickJsPrelude.kt`：

```kotlin
package com.ebook.source.sandbox

/**
 * 沙箱里唯一的全局装配点：脚本看见的 `java.*`、顶层摘要别名、以及每次执行的绑定，
 * 全由这段文本决定。**能力清单的正面在这里，负面是「别处也不存在」**——
 * 没有 `cache`/`cookie`/`source`/`Java`/`org`/`okhttp3` 这些名字，脚本碰到的就是 `ReferenceError`。
 *
 * 三段职责：
 * 1. [SOURCE] 在 runtime 创建时求值一次，装好 `java` 与纯计算别名。
 * 2. [BOOTSTRAP] 每次执行时前置在用户脚本之前，把 `__bindings` 落成全局量。
 *    分开是因为绑定是**每次执行**的量、垫片是 **runtime 级**的量；合在一起会让用户脚本的完成值
 *    变成绑定赋值的返回值（QuickJS 的完成值是最后一条语句的值）。
 * 3. `__UNSUPPORTED__:` 前缀是 `JsStatus.UNSUPPORTED_API` 的唯一来源——
 *    桥接层不需要懂白名单，只搬运一个前缀约定。
 *
 * 文本里刻意不出现美元符（Kotlin 原始字符串的模板起点）与三引号，最后一条有单测锁。
 */
internal object QuickJsPrelude {

    /** 每次执行前置的一行；`__bindings` 由桥接层在求值前挂到 globalThis */
    const val BOOTSTRAP: String = "__bind(__bindings);"

    val SOURCE: String = """
// ==== 沙箱垫片：QuickJS 内唯一被信任的 JS，随包冻结、不接受书源覆盖 ====

var __call = function (api, argList) {
  var reply = __host_call(api, JSON.stringify(argList));
  if (!reply || reply.ok !== true) {
    throw new Error(reply && reply.error ? reply.error : (api + ' 调用失败'));
  }
  return reply.data;
};

// 绑定：每次执行由 BOOTSTRAP 调一次。缺失与 null 都落成 undefined，
// 让脚本里的 `typeof result` 判空真的生效（落成字符串 'null' 会让净化逻辑把 'null' 当正文）。
var __bind = function (b) {
  var names = ['result', 'baseUrl', 'src', 'key', 'page', 'title'];
  var obj = b || {};
  for (var i = 0; i < names.length; i++) {
    globalThis[names[i]] = obj[names[i]] === undefined ? undefined : String(obj[names[i]]);
  }
  globalThis.book = obj.bookJson === undefined ? undefined : JSON.parse(obj.bookJson);
  globalThis.chapter = obj.chapterJson === undefined ? undefined : JSON.parse(obj.chapterJson);
};

var java = {};

// 白名单登记：名字必须出现在 JsHostApi 表里（JsHostApiTest 逐条比对）。
// arity 校验在此侧做，理由见 JsHostApi 的 KDoc。
var def = function (name, minArgs, maxArgs, fn) {
  java[name] = function () {
    var n = arguments.length;
    if (n < minArgs || (maxArgs > 0 && n > maxArgs)) {
      throw new Error(name + ' 参数个数不符（需要 ' + minArgs + ' 到 ' + (maxArgs || minArgs) + ' 个）');
    }
    var a = [];
    for (var i = 0; i < n; i++) a.push(arguments[i]);
    return fn.apply(null, a);
  };
};

// —— 纯计算：顶层别名与 java. 两个入口并存（语料两种写法都有），指向同一实现 ——
var computeNames = ['md5', 'sha1', 'sha256', 'base64Encode', 'base64Decode',
  'base64EncodeUrl', 'base64DecodeUrl', 'hexEncode', 'hexDecode',
  'aesEncode', 'aesDecode', 'desEncode', 'desDecode', 'timestamp', 'FormatDate'];
computeNames.forEach(function (n) {
  def(n, 0, 0, function () { return __call(n, Array.prototype.slice.call(arguments)); });
  globalThis[n] = java[n];
});

// —— 网络：全部经主进程的受限代理（scheme / 私网 / 白名单 / 大小 / 限流都在那一侧判）——
def('ajax', 1, 2, function (u, c) { return __call('ajax', c === undefined ? [u] : [u, c]); });
def('load', 1, 2, function (u, c) { return __call('load', c === undefined ? [u] : [u, c]); });
def('post', 2, 2, function (u, body) { return __call('post', [u, body]); });
def('responseCode', 1, 1, function (u) { return __call('responseCode', [u]); });

// —— 变量（规格 §5.2：JS 内用 java.put / java.get；`@get:` 在 JS 里不可用）——
// 注意 java.get 只有「读变量」一义，不重载成网络取页（偏差 2）。
def('put', 2, 2, function (k, v) { __call('putVar', [String(k), String(v)]); });
def('get', 1, 1, function (k) { return __call('getVar', [String(k)]); });
def('rmKey', 1, 1, function (k) { __call('rmVar', [String(k)]); });

// —— 递归规则求值：脚本把规则串交回解释器，输入是当前页面 ——
def('getElements', 1, 1, function (r) { return __call('getElements', [r]); });
def('getElement', 1, 1, function (r) { return __call('getElement', [r]); });
def('queryString', 1, 1, function (r) { return __call('queryString', [r]); });
def('putToPage', 2, 2, function (k, v) { __call('putToPage', [String(k), String(v)]); });

// —— 日志：toast 一族映射成日志，不弹 UI ——
def('toast', 1, 1, function (m) { __call('toast', [String(m)]); });
def('log', 1, 1, function (m) { __call('log', [String(m)]); });

// —— 明确不支持的能力：给一句可诊断的话，而不是让它撞进 ReferenceError ——
var reject = function (name) {
  return function () { throw new Error('__UNSUPPORTED__:' + name + ' 属本项目不支持的能力（登录/浏览器/持久层一族）'); };
};
['connect', 'getConnect', 'response', 'req', 'assets', 'files', 'Files', 'bookSource', 'cookieManager']
  .forEach(function (n) { def(n, 0, 0, reject(n)); });
""".trimIndent()
}
```

- [x] **Step 7: 写表↔垫片一致性测试**

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/JsHostApiTest.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.JsApiTarget
import com.ebook.source.script.JsHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单的两处事实源必须一致：`JsHostApi` 表与 [QuickJsPrelude.SOURCE]。
 *
 * 这条测试是「加一个能力只改了一处」这类事故的拦网。表里有、垫片里没有 → 脚本调不到，
 * 静默少一项能力；垫片里有、表里没有 → 分发时按未知 api 拒绝，同样是静默失败。
 * 两者都是当场看不出来、只在真实源上表现成「解不开」的错。
 */
class JsHostApiTest {

    /** 垫片里显式 `def('name'…` 登记的名字 */
    private val declared: Set<String> =
        Regex("""def\('([A-Za-z0-9_]+)'""").findAll(QuickJsPrelude.SOURCE).map { it.groupValues[1] }.toSet()

    /** 垫片里由 `computeNames` 循环登记的名字（没有独立的 `def('x'` 字面量） */
    private val compute: Set<String> = QuickJsPrelude.SOURCE
        .substringAfter("var computeNames = [")
        .substringBefore("];")
        .let { block -> Regex("""'([A-Za-z0-9_]+)'""").findAll(block).map { it.groupValues[1] }.toSet() }

    /** `reject(...)` 一族：故意不入表——它们不该被分发，只该在 JS 侧就报错 */
    private val rejectedStub = setOf(
        "connect", "getConnect", "response", "req", "assets", "files", "Files", "bookSource", "cookieManager",
    )

    @Test
    fun `能力表里每一项都在垫片里登记`() {
        val missing = JsHostApi.NAMES - declared - compute
        assertTrue("这些 api 在表里、垫片里没有，脚本调不到：$missing", missing.isEmpty())
    }

    @Test
    fun `垫片登记的白名单名字全在表里`() {
        val unknown = declared + compute - JsHostApi.NAMES - rejectedStub
        assertTrue("垫片登记了表外的名字：$unknown", unknown.isEmpty())
    }

    @Test
    fun `垫片里 def 登记的名字与表的 jsName 一一对应（变量族的重命名不静默）`() {
        // java.put / java.get / java.rmKey 是上游名字，表里是 putVar / getVar / rmVar：
        // 这条映射只在这一处定义，改名必须同时改测试，否则会出现「表与垫片各说各话」
        val alias = mapOf("put" to "putVar", "get" to "getVar", "rmKey" to "rmVar")
        alias.forEach { (js, table) ->
            assertTrue("$js 必须在垫片里登记：$declared", js in declared)
            assertTrue("$table 必须在表里：${JsHostApi.NAMES}", table in JsHostApi.NAMES)
        }
    }

    @Test
    fun `HOST 类能力正好覆盖网络变量递归求值与日志四族`() {
        assertEquals(
            setOf(
                "ajax", "post", "load", "responseCode",
                "putVar", "getVar", "rmVar",
                "getElements", "getElement", "queryString", "putToPage",
                "toast", "log",
            ),
            JsHostApi.entries.filter { it.target == JsApiTarget.HOST }.map { it.name }.toSet(),
        )
    }

    @Test
    fun `垫片不含美元符号，避免 Kotlin 原始字符串的模板起点`() {
        assertTrue("出现模板起点会让垫片静默被插值破坏", !QuickJsPrelude.SOURCE.contains('$'))
    }

    @Test
    fun `桥的出口与绑定入口各只有一处定义`() {
        assertEquals(1, Regex("""var __call = function""").findAll(QuickJsPrelude.SOURCE).count())
        assertEquals(1, Regex("""var __bind = function""").findAll(QuickJsPrelude.SOURCE).count())
    }
}
```

> `def('ajax'…` 这类字面量同时被 `declared` 与表比对；`compute` 那段依赖 `var computeNames = [` 与 `];`
> 两个锚点，改垫片时保留它们（一致性测试靠锚点取集合，锚点没了这条测试红，不会静默放行）。

- [x] **Step 8: 跑绿**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsHostApiTest*" --tests "*HostComputeTest*"`
Expected: 17 例全绿。
垫片在 JVM 上只被当字符串比对——它的**语法**正确性由 Task 7 的设备用例（真内核求值一次垫片）负责，两者不可互替。

- [ ] **Step 9: 提交（需授权）**

```bash
git add lib_book_source/src/main/java/com/ebook/source/script/JsHostApi.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/HostCompute.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/QuickJsPrelude.kt \
        lib_book_source/src/test/java/com/ebook/source/sandbox/JsHostApiTest.kt \
        lib_book_source/src/test/java/com/ebook/source/sandbox/HostComputeTest.kt
git commit -m "feat(lib_book_source): 定版脚本沙箱 Host API 白名单与纯计算实现" -m \
"能力清单收敛为一张表与一段 JS 垫片，二者由一致性单测互锁；摘要与分组密码走执行器进程内的 \
JDK 实现（零跨进程往返），真实向量、同键同文明文必得同密文、以及密文不经字符串中转三条不变式在 \
JVM 上锁住。"
```

---

### Task 5：受限网络代理的守门（host 白名单 + 地址归属 + DNS 挂载点）

**谁来用**：Task 9 的 `JsCallbackProxy`——主进程侧唯一真正发网络请求的地方。本任务的三个类型全部**无状态、零 Android 依赖**，所以整条准入判定能在 JVM 上逐形态测干净（不需要设备、不需要真 DNS，OkHttp 也不需要真的连出去）。本任务不接任何调用方，用户可达行为不变。

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/SourceHostAllowlist.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsNetworkGuard.kt`（同文件三个类型：`JsNetworkGuard` / `GuardedDns` / `AddressPolicy`）
- Test: `lib_book_source/src/test/java/com/ebook/source/sandbox/SourceHostAllowlistTest.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/sandbox/JsNetworkGuardTest.kt`

- [x] **Step 1: 写白名单的失败测试**

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/SourceHostAllowlistTest.kt`：

```kotlin
package com.ebook.source.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单导出的口径测试。它锁的不是「实现能不能跑」，而是**哪些 host 会被算进本源**——
 * 那同时决定了两件相反的事：真实源会不会被误拒、以及脚本能借道去哪里。两头都有代价，逐形态锁死。
 */
class SourceHostAllowlistTest {

    @Test
    fun `书源 URL 与规则文本里的 host 一起进白名单`() {
        val allowlist = SourceHostAllowlist.of(
            sourceUrl = "https://Manager.Example.com/api",
            ruleTexts = listOf(
                "https://www.baidu.com/s?wd={{key}}",
                "http://img.cdn.com/cover.jpg",
                "##[|第]|章",             // 正则替换段：没有 URL，不该产出 host
                "novel.example.com/path", // 漏协议的裸 host：不是绝对 URL，同样不产出
            ),
        )
        assertTrue(allowlist.allows("manager.example.com"))
        assertTrue(allowlist.allows("www.baidu.com"))
        assertTrue(allowlist.allows("img.cdn.com"))
        assertFalse(allowlist.allows("evil.com"))
        assertFalse(allowlist.allows("novel.example.com"))
    }

    @Test
    fun `导出时按 host 归一化：大小写、端口、userinfo、根点、IPv6 方括号`() {
        val allowlist = SourceHostAllowlist.of(
            sourceUrl = "",
            ruleTexts = listOf(
                "HTTPS://User:Pass@Novel.Example:8443/list",
                "https://Biquge.Example./toc",
                "https://[2001:DB8::1]:8080/x",
            ),
        )
        assertTrue(allowlist.allows("novel.example"))
        assertTrue(allowlist.allows("biquge.example"))
        assertTrue(allowlist.allows("2001:db8::1"))
        assertEquals(3, allowlist.entries.size)
    }

    @Test
    fun `子域不自动放行——近似域是主要风险形态`() {
        val allowlist = SourceHostAllowlist.of("", listOf("https://example.com"))
        assertTrue(allowlist.allows("example.com"))
        assertFalse(allowlist.allows("cdn.example.com"))
        assertFalse(allowlist.allows("evil-example.com"))
    }

    @Test
    fun `拼接与 JS 片段里的伪 URL 不误进白名单`() {
        val allowlist = SourceHostAllowlist.of(
            sourceUrl = "",
            ruleTexts = listOf(
                "var u = \"http://\" + host + \"/chapter\"",
                "{{baseUrl}}/list",
                "@js:result.replace(/https?:\\\\/\\\\//, '')",
            ),
        )
        assertTrue(
            "「://」后紧跟引号、或压根没有绝对协议：三条都该什么都不产出",
            allowlist.entries.isEmpty(),
        )
    }

    @Test
    fun `normalize 对无 host 的授权段回 null`() {
        listOf("", ":8080", "/", "[", "user@").forEach { authority ->
            assertNull("授权段「$authority」不该解出 host", SourceHostAllowlist.normalize(authority))
        }
        // zone id 只在归一化这一层剥；守门器的 check() 更早一步就把含 % 的授权段整体拒了
        assertEquals("fe80::1", SourceHostAllowlist.normalize("[fe80::1%eth0]"))
    }
}
```

- [x] **Step 2: 跑红**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*SourceHostAllowlistTest*"`
Expected: 编译失败，`SourceHostAllowlist` 未定义。

- [x] **Step 3: 实现白名单导出**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/SourceHostAllowlist.kt`：

```kotlin
package com.ebook.source.sandbox

import java.util.Locale

/**
 * 「按源导出可解析 host 白名单」〔ADR-0028 决策 6〕：书源自身 URL 的 host，加上它的规则文本里
 * 出现过的 host。
 *
 * 为什么从规则文本导出、而不是让作者填一个字段：脚本书源是社区格式，导入侧没有任何字段声明
 * 「这条源会访问哪些域名」，而 URL 规则本身就是答案的可靠来源——正文地址、图片地址、翻页地址
 * 都写在那里。导出的是「规则已经声明会去的地方」，脚本临时改道去别处才需要判。
 *
 * **默认严格，不自动放行子域**：`https://example.com` 只放行 `example.com` 本身，不放行
 * `cdn.example.com`。子域通配等于把一个域的持有者范围放大成「所有以它为后缀的域名」，而真实
 * 风险形态恰恰是 `attacker.example.com` 这类近似域；放行的收益（少数源把内容挂在另一子域）
 * 远小于代价。导出漏了时的后果是「这一源的内容请求被拒」，按〔ADR-0028 决策 7〕与该源本轮
 * 解析失败同口径处置——不串内容、不崩溃，而且拒绝日志带着 host。〔ADR-0028 遗留〕明说白名单
 * 的宽松度将来要按真实源失败率调校，那批调校数据就是这些日志。
 */
internal class SourceHostAllowlist private constructor(val entries: Set<String>) {

    /** [host] 必须是 [JsNetworkGuard] 归一化后的形态（小写、无端口、无方括号、无尾点） */
    fun allows(host: String): Boolean = host in entries

    companion object {

        /**
         * 规则文本里的绝对 URL 授权段。字符类排掉 `/ ? #` 空白 引号 `<` `>` 反斜杠，因此只吃
         * 「scheme://」后紧跟的那一段：`"http://" + host` 这类拼接里 `://` 后面是引号，匹配不到；
         * 正则字面量里的 `https?:\/\/` 同理不产 host。
         */
        private val URL_AUTHORITY = Regex("""https?://([^/\s"'<>\\?#]+)""", RegexOption.IGNORE_CASE)

        /** [sourceUrl] 是书源自身 URL；[ruleTexts] 是该源所有 URL 形态规则的原文 */
        fun of(sourceUrl: String, ruleTexts: List<String>): SourceHostAllowlist {
            val hosts = LinkedHashSet<String>()
            (ruleTexts + sourceUrl).forEach { text ->
                URL_AUTHORITY.findAll(text).forEach { match ->
                    normalize(match.groupValues[1])?.let(hosts::add)
                }
            }
            return SourceHostAllowlist(hosts)
        }

        /**
         * 归一化：剥 userinfo、剥端口、剥 IPv6 方括号与 zone id、去尾点、转小写。
         * 解不出 host 的授权段（空、只有端口、只有 `[`）回 null，由调用方判拒。
         */
        internal fun normalize(authority: String): String? {
            val withoutUserInfo = authority.substringAfterLast('@', authority)
            val hostPart = if (withoutUserInfo.startsWith('[')) {
                withoutUserInfo.substringAfter('[').substringBefore(']')
            } else {
                withoutUserInfo.substringBefore(':')
            }
            return hostPart
                .substringBefore('%')
                .trimEnd('.')
                .lowercase(Locale.US)
                .takeIf { it.isNotEmpty() }
        }
    }
}
```

> `normalize` 挂在 `companion object` 上且为 `internal`：白名单导出与守门器比对**必须共用同一份
> 归一化**。各写一次就会长成「存进去的是 A 形态、比的是 B 形态」而永远匹配不上——症状不是报错，
> 是所有真实源都被拒。

- [x] **Step 4: 跑绿**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*SourceHostAllowlistTest*"`
Expected: 5 例全绿。

- [x] **Step 5: 写守门器的失败测试**

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/JsNetworkGuardTest.kt`（一份完整文件，14 例）：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.JsApiRejectedException
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络代理的准入判据。这是整个沙箱唯一**能碰到内网**的接缝，逐形态锁死：
 * 判错一个方向是断掉内容站点（可恢复、有日志、按单源失败处置）；判错另一个方向是让一台
 * 零权限设备变成可读的 HTTP 客户端（云元数据服务、路由器后台、本机服务）。
 *
 * 地址判定一律喂**原始字节**给 `AddressPolicy.isBlocked(ByteArray)`，不走
 * `InetAddress.getByAddress`：Android 与 JDK 对「IPv4 映射的 IPv6 地址」处理不一致
 * （Android 会就地转成 `Inet4Address`），走平台路径会让同一用例在两边解成不同地址族，
 * 测到的就不是判据本身。
 */
class JsNetworkGuardTest {

    private fun guardFor(vararg ruleTexts: String): JsNetworkGuard =
        JsNetworkGuard(SourceHostAllowlist.of("", ruleTexts.toList()))

    /** 拒绝不只要拒，还要把被拒 URL 带进消息——白名单的调校数据全靠它 */
    private fun assertRejected(guard: JsNetworkGuard, url: String) {
        val e = assertThrows("$url 必须被拒", JsApiRejectedException::class.java) { guard.check(url) }
        assertTrue("拒绝消息要带被拒 URL：${e.message}", e.message!!.contains(url.take(80)))
    }

    /** 4 位十六进制组 → 字节数组（地址用例按原始字节构造，绕开平台地址族转换） */
    private fun bytes(vararg groups: String): ByteArray =
        groups.flatMap { group -> group.chunked(2).map { hex -> hex.toInt(16).toByte() } }.toByteArray()

    private fun v4(text: String): ByteArray = text.split('.').map { it.toInt().toByte() }.toByteArray()

    // ---------- 纯字符串段：check() ----------

    @Test
    fun `仅 http(s) 通过，其余协议一律拒绝`() {
        val guard = guardFor("https://novel.example/list")
        assertEquals("novel.example", guard.check("https://novel.example/list"))
        assertEquals("novel.example", guard.check("http://novel.example/list"))
        assertEquals("novel.example", guard.check("HTTPS://novel.example/list"))
        listOf(
            "file:///android_asset/x",
            "gopher://novel.example/",
            "jar:http://novel.example!/entry",
            "data:text/html,hi",
            "ftp://novel.example/",
            "javascript:alert(1)",
        ).forEach { url -> assertRejected(guard, url) }
    }

    @Test
    fun `相对地址与缺协议的地址被拒`() {
        val guard = guardFor("https://novel.example/")
        listOf("//novel.example/x", "novel.example/x", "/a/b", "https:/novel.example/x", "")
            .forEach { url -> assertRejected(guard, url) }
    }

    @Test
    fun `内嵌凭证、空 host 与含非法字符的授权段被拒`() {
        val guard = guardFor("https://novel.example/")
        listOf(
            "https://u:p@novel.example/x", // 凭证既不内嵌也不附加
            "https:///x",                  // 空 host
            "https://:8080/x",             // 空 host 带端口
            "https://no vel.example/x",    // 授权段含空白
            "https://%31%32%37.0.0.1/x",   // 授权段含百分号编码
            "https://nov\u0000.example/x", // 授权段含控制字符
        ).forEach { url -> assertRejected(guard, url) }
    }

    @Test
    fun `URL 长度超上限被拒`() {
        val guard = JsNetworkGuard(
            SourceHostAllowlist.of("", listOf("https://novel.example/")),
            maxUrlLength = 40,
        )
        assertRejected(guard, "https://novel.example/" + "a".repeat(60))
    }

    @Test
    fun `白名单外的 host 被拒且消息带归一化后的 host`() {
        val guard = guardFor("https://novel.example/list")
        val e = assertThrows(JsApiRejectedException::class.java) { guard.check("https://other.example/x") }
        assertTrue("拒绝消息要带 host 供调校：${e.message}", e.message!!.contains("other.example"))
    }

    @Test
    fun `host 归一化后才比对：大小写、端口、根点、IPv6 方括号`() {
        val guard = guardFor("https://[2001:db8::1]:8443/x", "https://novel.example/list")
        assertEquals("2001:db8::1", guard.check("https://[2001:DB8::1]:8443/x"))
        assertEquals("novel.example", guard.check("https://Novel.Example.:80/list"))
    }

    // ---------- 地址归属段：AddressPolicy ----------

    @Test
    fun `IPv4 内网与保留段逐条拒绝`() {
        mapOf(
            "0.0.0.0" to "这一网络",
            "0.255.255.255" to "0/8 上界",
            "10.1.2.3" to "RFC1918 /8",
            "127.0.0.1" to "环回",
            "127.9.9.9" to "环回整个 /8",
            "169.254.169.254" to "云元数据",
            "172.16.0.1" to "RFC1918 /12 下界",
            "172.31.255.255" to "RFC1918 /12 上界",
            "192.168.1.1" to "RFC1918 /16",
            "192.0.0.1" to "IETF 保留",
            "100.64.0.1" to "CGNAT 下界",
            "100.127.255.255" to "CGNAT 上界",
            "198.18.0.1" to "基准测试",
            "224.0.0.1" to "组播",
            "239.255.255.255" to "组播上界",
            "240.0.0.1" to "保留",
            "255.255.255.255" to "广播",
        ).forEach { (text, why) ->
            assertTrue("$text（$why）该被拒", AddressPolicy.isBlocked(v4(text)))
        }
    }

    @Test
    fun `IPv4 紧邻内网段的公网地址放行`() {
        listOf(
            "93.184.216.34",
            "8.8.8.8",
            "172.32.0.1",      // 172.16/12 之外
            "100.128.0.1",     // 100.64/10 之外
            "169.255.0.1",     // 169.254/16 之外
            "192.0.1.1",       // 192.0.0/24 之外
            "197.255.255.255", // 198.18/15 之外
            "223.255.255.255", // 组播之前
        ).forEach { text ->
            assertFalse("$text 是公网，不该被误拒", AddressPolicy.isBlocked(v4(text)))
        }
    }

    @Test
    fun `IPv6 环回、ULA、链路本地与组播拒绝，全球单播放行`() {
        mapOf(
            "0000 0000 0000 0000 0000 0000 0000 0000" to "未指定 ::",
            "0000 0000 0000 0000 0000 0000 0000 0001" to "环回 ::1",
            "0000 0000 0000 0000 0000 0001 0000 0000" to "::/96 里的非常规形态",
            "fe80 0000 0000 0000 0000 0000 0000 0001" to "链路本地",
            "fd12 3456 0000 0000 0000 0000 0000 0001" to "ULA fc00::/7",
            "ff02 0000 0000 0000 0000 0000 0000 0001" to "组播",
        ).forEach { (text, why) ->
            val groups = text.split(" ").toTypedArray()
            assertTrue("$text（$why）该被拒", AddressPolicy.isBlocked(bytes(*groups)))
        }
        assertFalse(
            AddressPolicy.isBlocked(bytes("2001", "4860", "8000", "0000", "0000", "0000", "0000", "8888")),
        )
    }

    @Test
    fun `IPv4 映射与兼容形态按内嵌的 IPv4 判`() {
        // ::ffff:127.0.0.1
        assertTrue(AddressPolicy.isBlocked(bytes("0000", "0000", "0000", "0000", "0000", "ffff", "7f00", "0001")))
        // ::169.254.169.254 —— IPv4 兼容形态的云元数据
        assertTrue(AddressPolicy.isBlocked(bytes("0000", "0000", "0000", "0000", "0000", "0000", "a9fe", "a9fe")))
        // ::ffff:93.184.216.34 —— 映射的公网地址该放行
        assertFalse(AddressPolicy.isBlocked(bytes("0000", "0000", "0000", "0000", "0000", "ffff", "5db8", "d822")))
    }

    @Test
    fun `6to4 就地解出内嵌 IPv4，未知长度一律拒绝`() {
        // 2002:7f00:0001:: —— 6to4 内嵌 127.0.0.1
        assertTrue(AddressPolicy.isBlocked(bytes("2002", "7f00", "0001", "0000", "0000", "0000", "0000", "0000")))
        // 2002:5db8:d822:: —— 6to4 内嵌公网地址
        assertFalse(AddressPolicy.isBlocked(bytes("2002", "5db8", "d822", "0000", "0000", "0000", "0000", "0000")))
        assertTrue(AddressPolicy.isBlocked(ByteArray(3)))
        assertTrue(AddressPolicy.isBlocked(ByteArray(8)))
    }

    // ---------- DNS 挂载点：GuardedDns ----------

    @Test
    fun `多 A 记录里混入内网就整体拒绝`() {
        val public = InetAddress.getByAddress(byteArrayOf(93.toByte(), 184, 216, 34))
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val dns = GuardedDns(guardFor("https://novel.example/"), resolver = { listOf(public, loopback) })
        val e = assertThrows(UnknownHostException::class.java) { dns.lookup("novel.example") }
        assertTrue("消息要指出是哪个地址被拒：${e.message}", e.message!!.contains("127.0.0.1"))
    }

    @Test
    fun `解析结果为空按解析失败处理`() {
        val dns = GuardedDns(guardFor("https://novel.example/"), resolver = { emptyList() })
        assertThrows(UnknownHostException::class.java) { dns.lookup("novel.example") }
    }

    @Test
    fun `公网地址原样交回 OkHttp`() {
        val addresses = listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184, 216, 34)))
        val dns = GuardedDns(guardFor("https://novel.example/")) { addresses }
        assertEquals(addresses, dns.lookup("novel.example"))
    }
}
```

- [x] **Step 6: 跑红**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsNetworkGuardTest*"`
Expected: 编译失败，`JsNetworkGuard` / `AddressPolicy` / `GuardedDns` 未定义。

- [x] **Step 7: 实现守门器**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsNetworkGuard.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.JsApiRejectedException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import okhttp3.Dns

/**
 * 网络代理的准入判定。**无状态**：只回答「这个 URL / 这个地址能不能碰」。
 * 每任务请求次数上限（〔ADR-0028 决策 6〕的「限流」）由持有 [JsLimits.maxRequestsPerTask] 的
 * Task 9 `JsCallbackProxy` 计数——守门器不持计数器，同一个实例才能在一轮任务里被反复问、
 * 也能跨任务复用。
 *
 * 判定分两段，**位置不同是刻意的**：
 * 1. [check] 纯字符串（长度 → 协议 → 结构 → 白名单），零 DNS；
 * 2. [GuardedDns] 把地址判定挂在 OkHttp 真正解析的那一次上。
 *
 * 第 2 点不是「顺手也能放进第 1 点」的事：若在 [check] 里解析一次、OkHttp 连接时再解析一次，
 * 攻击者把 TTL 设成 0 就能让两次解析给出不同结果（一次公网、一次 127.0.0.1），检查与使用
 * 就不是同一个地址——DNS 重绑。同理，`http://2130706433/`（十进制字面量即 127.0.0.1）与
 * `http://anything.127.0.0.1.nip.io/` 这类形态**不需要**在字符串层特判：平台解析完就是环回地址，
 * [GuardedDns] 必然撞上 [AddressPolicy]。
 */
internal class JsNetworkGuard(
    private val allowlist: SourceHostAllowlist,
    /** 通用 URL 长度口径：防的是脚本拼出一个 10 MB 的「URL」这种无意义请求 */
    private val maxUrlLength: Int = 4_096,
) {

    /**
     * 通过则回归一化后的 host（供日志与统计），拒绝抛 [JsApiRejectedException]。
     * 每条消息都带被拒 URL 前 80 字符——〔ADR-0028 遗留〕的白名单调校就靠这批 host。
     */
    fun check(url: String): String {
        val brief = url.take(80)
        if (url.length > maxUrlLength) {
            throw JsApiRejectedException("URL 长度 ${url.length} 超上限 $maxUrlLength：$brief")
        }
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) throw JsApiRejectedException("必须是带协议的绝对 URL：$brief")
        val scheme = url.substring(0, schemeEnd).lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw JsApiRejectedException("仅代理 http(s)，拒绝 $scheme：$brief")
        }
        val authority = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.any { it.code < 0x21 || it == '%' }) {
            // 空白/控制字符不是合法授权段；百分号编码的 host 还原不出真实目标，一并拒
            throw JsApiRejectedException("URL 的 host 段含非法字符：$brief")
        }
        if (authority.contains('@')) throw JsApiRejectedException("禁止在 URL 里内嵌凭证：$brief")
        val host = SourceHostAllowlist.normalize(authority)
            ?: throw JsApiRejectedException("URL 的 host 为空：$brief")
        if (!allowlist.allows(host)) {
            throw JsApiRejectedException(
                "host $host 不在本源可解析白名单内（白名单 ${allowlist.entries.size} 项）：$brief",
            )
        }
        return host
    }

    /**
     * 拒绝时抛 [UnknownHostException]：`okhttp3.Dns.lookup` 的契约声明的就是它
     * （源码里 `@Throws(UnknownHostException::class)`），换别的类型会让连接栈走非预期分支。
     */
    fun rejectIfBlocked(address: InetAddress, hostname: String) {
        if (AddressPolicy.isBlocked(address)) {
            throw UnknownHostException("拒绝访问内网/保留地址 ${address.hostAddress}（host $hostname）")
        }
    }
}

/**
 * 地址判定挂在 OkHttp 的解析点上：连接用的就是这次解析的结果，检查与使用同址，不留 DNS 重绑窗口。
 *
 * 被拒时对脚本的表现与「真实网络失败」一致：Task 9 的代理把 [UnknownHostException]（它是
 * `IOException`）转成失败回包，垫片抛成 JS 异常——脚本里自己 `try/catch` 的写法照常生效。
 * 这是刻意的：静默放行会让沙箱只剩约定没有边界，而报一个脚本看不懂的「安全策略」错误码
 * 又会让源作者以为站点挂了。
 */
internal class GuardedDns(
    private val guard: JsNetworkGuard,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it) },
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver(hostname)
        if (addresses.isEmpty()) throw UnknownHostException("DNS 无解析结果：$hostname")
        // 多 A 记录一律全查、有一个内网就整体拒绝：挑哪个地址连接是 OkHttp 的事，我们控制不了
        addresses.forEach { address -> guard.rejectIfBlocked(address, hostname) }
        return addresses
    }
}

/**
 * 内网、保留与不可路由地址的**字节级**判据。
 *
 * 不用 [InetAddress.isLoopbackAddress] / [InetAddress.isSiteLocalAddress] 那一族谓词：
 * 它们对「IPv4 映射的 IPv6 地址」的处理在 JDK 与 Android 上不一致（Android 的
 * `getByAddress` 会就地转成 `Inet4Address`），且 `isSiteLocalAddress` 只覆盖 RFC1918 三段，
 * **不含**云元数据所在的 169.254/16 与运营商级 NAT 的 100.64/10。这里是安全边界，
 * 判据必须写死在一处，并且能被单测按原始字节喂进来。
 */
internal object AddressPolicy {

    fun isBlocked(address: InetAddress): Boolean = isBlocked(address.address)

    fun isBlocked(raw: ByteArray): Boolean = when (raw.size) {
        4 -> isBlockedIpv4(raw)
        16 -> isBlockedIpv6(raw)
        else -> true // 平台给了没见过的长度：deny-by-default
    }

    private fun isBlockedIpv4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        val a2 = b[2].toInt() and 0xff
        return when {
            a0 == 0 -> true                         // 0.0.0.0/8 这一网络
            a0 == 10 -> true                        // RFC1918
            a0 == 127 -> true                       // 环回整个 /8，不只 127.0.0.1
            a0 == 169 && a1 == 254 -> true          // 链路本地；云元数据 169.254.169.254 在此段
            a0 == 172 && a1 in 16..31 -> true       // RFC1918
            a0 == 192 && a1 == 168 -> true          // RFC1918
            a0 == 192 && a1 == 0 && a2 == 0 -> true // 192.0.0.0/24 IETF 保留
            a0 == 100 && a1 in 64..127 -> true      // 100.64.0.0/10 CGNAT：运营商级 NAT 与不少云内网
            a0 == 198 && a1 in 18..19 -> true       // 198.18.0.0/15 基准测试
            a0 in 224..239 -> true                  // 组播
            a0 >= 240 -> true                       // 保留段 + 255.255.255.255 广播
            else -> false
        }
    }

    private fun isBlockedIpv6(b: ByteArray): Boolean {
        val b0 = b[0].toInt() and 0xff
        val b1 = b[1].toInt() and 0xff
        if (b1 == 0 && (2..9).all { b[it].toInt() == 0 }) {
            // 字节 0..9 全 0 即 ::/96：未指定、环回、IPv4 兼容与映射形态都住这里，整体不可路由
            val hi = b[10].toInt() and 0xff
            val lo = b[11].toInt() and 0xff
            val embedded = b.copyOfRange(12, 16)
            if (hi == 0xff && lo == 0xff) return isBlockedIpv4(embedded) // ::ffff:a.b.c.d
            if (hi == 0 && lo == 0) return isBlockedIpv4(embedded)       // ::a.b.c.d
            return true
        }
        return when {
            b0 == 0xfe && (b1 and 0xc0) == 0x80 -> true // fe80::/10 链路本地
            (b0 and 0xfe) == 0xfc -> true               // fc00::/7 唯一本地地址
            b0 == 0xff -> true                          // ff00::/8 组播
            b0 == 0x20 && b1 == 0x02 -> isBlockedIpv4(b.copyOfRange(2, 6)) // 2002::/16 6to4：内嵌 IPv4 就地解
            else -> false
        }
        // 刻意**不**解 Teredo（2001::/32 里异或混淆的 IPv4）：Android 不跑 Teredo 客户端，
        // 该形态在装机环境里根本发不出包，为它加分支只增加判据复杂度。已知边界，非静默放行。
    }
}
```

> 上面的 `::/96` 判定写成 `b1 == 0 && (2..9).all { … }` 而不是 `(0..9).all { … }`，因为
> `b[0]` 已经取成 `b0` 给后续分支用。改小的时候注意两者等价（`b1 == 0` 就是 `b[1]` 为 0），
> **别把它并入下面的 `when`**——`::ffff:93.184.216.34` 这类映射的公网地址要先落到这一段
> 才能按内嵌 IPv4 放行，走 `when` 会被当成未知形态整体拒掉（症状：合法的 IPv6 字面量源全挂）。

- [x] **Step 8: 跑绿**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsNetworkGuardTest*" --tests "*SourceHostAllowlistTest*"`
Expected: 19 例全绿（守门器 14 + 白名单 5）。

- [x] **Step 9: 跑全模块**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: 全模块绿。本任务纯新增文件、不接调用方，既有各例不受影响（Task 3 起累计新增 8 + 17 + 19 例）。

- [ ] **Step 10: 提交（需授权）**

```bash
git add lib_book_source/src/main/java/com/ebook/source/sandbox/SourceHostAllowlist.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsNetworkGuard.kt \
        lib_book_source/src/test/java/com/ebook/source/sandbox/SourceHostAllowlistTest.kt \
        lib_book_source/src/test/java/com/ebook/source/sandbox/JsNetworkGuardTest.kt
git commit -m "feat(lib_book_source): 新增脚本沙箱网络代理的准入守门" -m \
"按源导出 host 白名单、http(s) 与 URL 形态判定、内网与保留地址的字节级判据，以及挂在 OkHttp \
解析点上的 GuardedDns；地址判定不走平台谓词族（对 IPv4 映射地址两平台不一致、且不覆盖 \
169.254/16 与 100.64/10），19 例逐形态在 JVM 上锁死。"
```

---

### Task 6：跨进程契约与主进程客户端（`SandboxContract` / `JsChannel` / `JsSandboxClient`）

这条接缝之上是「策略」，之下是「传输」。传输（Binder、`isolatedProcess`、Parcel）只能在设备上测，策略（重连不重连、重放不重放、什么时候算不可用、回调往哪儿交）**在设备上恰恰最难测**——要制造断连就得杀进程。所以本任务把策略全放在一个零 Android 依赖的客户端里，用一个假通道钉死。

三条不成文但决定形状的规则：

1. `JsChannel` 不 import 任何 `android.*`。真实现（Task 8 的 `BinderJsChannel`）只是它的一个适配器。
2. **重放只在「本次调用还没有副作用」时做**。脚本一次 `ajax()` 就已经往第三方站点发了请求，重放等于同一页请求两次——聚合搜索里那会写成重复条目。
3. **host 回调里再发起 `execute` 一律立刻失败**，不去等锁：唯一的 runtime 正攥在回调的发起方手里，等下去是双向死锁到超时为止（这条与 ADR 的偏差在 Task 11 一并补记）。

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxContract.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsChannel.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxClient.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/script/JsInterpreter.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt`
- Create: `lib_book_source/src/test/java/com/ebook/source/sandbox/JsSandboxClientTest.kt`

- [x] **Step 1: 先写失败的测试**

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/JsSandboxClientTest.kt`（本步一次写全，Step 2 直接跑）：

```kotlin
package com.ebook.source.sandbox

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 客户端的四种「不体面」路径都在这张表里：断连重放、拒绝重放、重入自锁、主线程阻塞。
 *
 * 真通道在 JVM 上不可用，因此全部用例用 [FakeChannel] 顶替——被测的是策略不是传输，
 * 这正是把 [JsChannel] 定成零 Android 依赖的理由。
 */
class JsSandboxClientTest {

    /** 记录每一帧；行为由构造它的 lambda 脚本化（可以回调 handler、可以抛死、可以回指定帧） */
    private class FakeChannel(
        private val behaviour: (String, (String) -> String) -> String,
    ) : JsChannel {
        val frames = mutableListOf<String>()
        var closed = false
        override val isOpen: Boolean get() = !closed
        override fun execute(requestFrame: String, hostCallback: (String) -> String): String {
            frames += requestFrame
            return behaviour(requestFrame, hostCallback)
        }

        override fun close() {
            closed = true
        }
    }

    /** 造客户端：第 n 次连接取 [behaviours] 的第 n 个行为，用尽后复用最后一个（重连用例不必铺满） */
    private fun clientWith(
        vararg behaviours: (String, (String) -> String) -> String,
        limits: JsLimits = JsLimits(),
        handler: HostHandler = HostHandler { _, _ -> HostReply(ok = true, data = null, error = null) },
        isMainThread: () -> Boolean = { false },
    ): Pair<JsSandboxClient, MutableList<FakeChannel>> {
        val opened = mutableListOf<FakeChannel>()
        val client = JsSandboxClient(
            openChannel = {
                val behaviour = behaviours[opened.size.coerceAtMost(behaviours.lastIndex)]
                FakeChannel(behaviour).also { opened += it }
            },
            limits = limits,
            clock = { 5_000L },
            hostHandler = handler,
            isMainThread = isMainThread,
        )
        return client to opened
    }

    private fun inv() = JsInvocation(JsMode.SEGMENT, "result", mapOf("result" to "正文"))

    private fun okFrame(text: String) = JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, stringJson(text)))

    private fun stringJson(s: String): JsonElement =
        Json.parseToJsonElement(Json.encodeToString(String.serializer(), s))

    @Test
    fun `成功执行：请求帧带得出模式与截止日期，回帧解成 OK 与文本`() {
        val (client, opened) = clientWith({ _, _ -> okFrame("第三章") })
        val outcome = client.execute(inv())
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("第三章", outcome.text)
        val sent = JsProtocol.decodeRequest(opened.single().frames.single())
        assertEquals(JsMode.SEGMENT, sent.invocation.mode)
        // 截止日期由主进程算好随帧带出：执行器不猜「什么时候该停」，两侧共用一个机器级单调钟
        assertEquals(5_000L + JsLimits().wallClockMs, sent.deadlineMonoMs)
    }

    @Test
    fun `请求帧超上限：不发往沙箱，直接判 TOO_LARGE`() {
        val (client, opened) = clientWith({ _, _ -> error("不该发出") }, limits = JsLimits(maxRequestBytes = 64))
        val outcome = client.execute(JsInvocation(JsMode.SEGMENT, "字".repeat(200), emptyMap()))
        assertEquals(JsStatus.TOO_LARGE, outcome.status)
        assertTrue("超限的帧根本不该占用一次跨进程调用", opened.isEmpty())
    }

    @Test
    fun `响应帧超上限：在解析前就判 TOO_LARGE，不把超限原文交给调用方`() {
        val huge = """{"status":"OK","data":"${"x".repeat(500)}"}"""
        val (client, _) = clientWith({ _, _ -> huge }, limits = JsLimits(maxOutcomeBytes = 120))
        assertEquals(JsStatus.TOO_LARGE, client.execute(inv()).status)
    }

    @Test
    fun `通道死亡且本次未回调过：重连一次并重放同一帧`() {
        var first = true
        val (client, opened) = clientWith({ _, _ ->
            if (first) {
                first = false
                throw ChannelDeadException("DeadObjectException")
            }
            okFrame("重放成功")
        })
        val outcome = client.execute(inv())
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("重放成功", outcome.text)
        assertEquals(2, opened.size)
        assertTrue("旧通道必须被关闭，否则泄漏一条 Binder 连接", opened[0].closed)
        assertEquals(opened[0].frames, opened[1].frames)
    }

    @Test
    fun `重连后仍死亡：报 UNAVAILABLE 且不再试第三次`() {
        val (client, opened) = clientWith({ _, _ -> throw ChannelDeadException("一直死") })
        val outcome = client.execute(inv())
        assertEquals(JsStatus.UNAVAILABLE, outcome.status)
        assertTrue(outcome.error!!.contains("不可用"))
        assertEquals("只重连一次：第三次连接多半是环境问题，重试只会把解析拖长", 2, opened.size)
    }

    @Test
    fun `本次已转发过 host 回调时断连不重放`() {
        var relayed = 0
        val (client, opened) = clientWith({ _, relay ->
            relay(JsProtocol.encodeHostCall("ajax", """{"url":"https://a"}"""))
            relayed++
            throw ChannelDeadException("回调之后断连")
        })
        assertEquals(JsStatus.UNAVAILABLE, client.execute(inv()).status)
        assertEquals(1, relayed)
        assertEquals("已经替脚本发过一次网络请求，重放就是同一页请求两次", 1, opened.size)
    }

    @Test
    fun `执行器回的失败状态原样透传，既不折叠成 RUNTIME 也不折叠成 OK`() {
        for (status in listOf(JsStatus.TIMEOUT, JsStatus.MEMORY, JsStatus.STACK, JsStatus.UNSUPPORTED_API, JsStatus.SYNTAX)) {
            val (client, _) = clientWith({ _, _ -> JsProtocol.encodeOutcome(JsOutcome(status, error = "细节")) })
            val outcome = client.execute(inv())
            assertEquals(status, outcome.status)
            assertEquals("细节", outcome.error)
        }
    }

    @Test
    fun `host 调用帧解出 api 与 args 交给 handler，其回复编成帧带回通道`() {
        var relayedBack = ""
        val seen = mutableListOf<Pair<String, String>>()
        val (client, _) = clientWith(
            { _, relay ->
                relayedBack = relay(JsProtocol.encodeHostCall("ajax", """{"url":"https://a"}"""))
                okFrame("done")
            },
            handler = HostHandler { api, args ->
                seen += api to args
                HostReply(ok = true, data = stringJson("<html>正文</html>"), error = null)
            },
        )
        assertEquals(JsStatus.OK, client.execute(inv()).status)
        assertEquals("ajax", seen.single().first)
        assertTrue(seen.single().second.contains("https://a"))
        val reply = JsProtocol.decodeHostReply(relayedBack)
        assertTrue(reply.ok)
        assertEquals("<html>正文</html>", reply.data?.jsonPrimitive?.content)
    }

    @Test
    fun `handler 自己抛出时回 ok=false，绝不让异常穿进执行器`() {
        var relayedBack = ""
        val (client, _) = clientWith(
            { _, relay ->
                relayedBack = relay(JsProtocol.encodeHostCall("ajax", "{}"))
                okFrame("done")
            },
            handler = HostHandler { _, _ -> throw IllegalStateException("代理内部炸了") },
        )
        assertEquals("脚本自身成功与否由它自己决定，主进程代理坏了不算脚本失败", JsStatus.OK, client.execute(inv()).status)
        val reply = JsProtocol.decodeHostReply(relayedBack)
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("代理内部炸了"))
    }

    @Test
    fun `host 回调里再发起 execute 立刻失败，不去等一把永远还不来的锁`() {
        val opened = mutableListOf<FakeChannel>()
        var nested: JsOutcome? = null
        lateinit var client: JsSandboxClient
        client = JsSandboxClient(
            openChannel = {
                FakeChannel { _, relay ->
                    relay(JsProtocol.encodeHostCall("evaluateRule", "{}"))
                    okFrame("外层完成")
                }.also { opened += it }
            },
            limits = JsLimits(),
            clock = { 5_000L },
            hostHandler = HostHandler { _, _ ->
                nested = client.execute(inv())
                HostReply(ok = false, data = null, error = "嵌套脚本不支持")
            },
        )
        assertEquals(JsStatus.OK, client.execute(inv()).status)
        assertEquals(JsStatus.UNAVAILABLE, nested?.status)
        assertTrue(nested!!.error!!.contains("嵌套"))
        assertEquals("嵌套请求不该再去开一条通道", 1, opened.size)
    }

    @Test
    fun `重放预算按本次调用算，上一次的回调不污染这一次`() {
        var relayThenDie = true
        val (client, opened) = clientWith({ _, relay ->
            if (relayThenDie) {
                relay(JsProtocol.encodeHostCall("ajax", "{}"))
                relayThenDie = false
            }
            throw ChannelDeadException("每次都死")
        })
        // 第一次：回调过 → 不重放，只连了一条通道
        assertEquals(JsStatus.UNAVAILABLE, client.execute(inv()).status)
        assertEquals(1, opened.size)
        // 第二次：本次没回调 → 重连并重放一次，共三条通道，然后仍报不可用
        assertEquals(JsStatus.UNAVAILABLE, client.execute(inv()).status)
        assertEquals(3, opened.size)
    }

    @Test
    fun `主线程调用直接抛，不进入最长 wallClockMs 的跨进程阻塞`() {
        val (client, opened) = clientWith({ _, _ -> okFrame("x") }, isMainThread = { true })
        val thrown = runCatching { client.execute(inv()) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue("守卫必须在连接之前生效，否则连主线程都在等的通道已经建起来了", opened.isEmpty())
    }
}
```

- [x] **Step 2: 跑一遍确认它失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsSandboxClientTest*"`
Expected: **编译失败**（`JsSandboxClient`、`JsChannel`、`HostHandler`、`ChannelDeadException` 未定义）。这是本步的预期红。

- [x] **Step 3: 写契约与通道抽象**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxContract.kt`：

```kotlin
package com.ebook.source.sandbox

/**
 * 主进程与 `:js` 执行器之间**线上形态**的唯一事实源：接口标识、协议版本、事务码。
 *
 * 为什么手写 Binder 而不用 AIDL：协议只有一对方法，而它站在安全关键路径上——
 * 生成的桩里看不见的东西没法审计，多一步代码生成就多一处「改了 .aidl 忘了重编」的缝隙。
 * 把线上形态收在这一个纯 Kotlin 文件里，任何改动都会在这里留下痕迹。
 *
 * 本文件刻意不含 `android.*`：事务码就是两个小整数，两侧对齐即可，不必引
 * `Binder.FIRST_CALL_TRANSACTION`，于是它在 JVM 单测里能直接加载。
 */
object SandboxContract {

    /** 事务数据的第一字段。对端不是本服务时当场判不匹配，而不是把随机内存当帧读 */
    const val INTERFACE_TOKEN: String = "com.ebook.source.sandbox.IJsExecutor"

    /** 控制面协议版本：Parcel 布局一变就 +1，两侧不一致当场报不可用，绝不去猜对方的字段顺序 */
    const val PROTOCOL_VERSION: Int = 1

    /**
     * 一次 execute。
     * 写：token、version、requestFrame、host 回调 binder（可 null）。
     * 回：token、version、outcomeFrame。
     */
    const val TX_EXECUTE: Int = 1

    /**
     * 探活：只验 token 与版本，不建 runtime。用来把「服务在但协议不合」与「服务不在」分开。
     * 回：token、version、ready（1=桥接层能跑，0=`.so` 没装上）。不回帧体——探活不该付一次内核调用的代价
     */
    const val TX_PING: Int = 2

    /**
     * 反方向（执行器 → 主进程）的接口标识。
     *
     * 单独一个 token 而不是复用 [INTERFACE_TOKEN]：两端的实现方不同（一个是执行器、一个是主进程），
     * 传错 binder 时 `enforceInterface` 要能当场拒掉，而不是把对方的帧当成自己的读下去。
     */
    const val CALLBACK_TOKEN: String = "com.ebook.source.sandbox.IHostCallback"

    /**
     * 一次 host 调用。事务码在 [CALLBACK_TOKEN] 这个接口上编号，与 [TX_EXECUTE] 分属两个空间。
     * 写：token、version、callFrame。回：token、version、replyFrame。
     */
    const val TX_HOST_CALL: Int = 1
}
```

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsChannel.kt`：

```kotlin
package com.ebook.source.sandbox

/**
 * 一条能跑 JS 的通道。**禁止出现任何 android 类型**——真实现（Task 8 的 `BinderJsChannel`）
 * 只是它的一个适配器，于是重连、重放、回调中继这些只在设备上难造的失败路径全能在 JVM 上测。
 */
interface JsChannel {

    /** 只报告本地状态，不探测远端（探测要发一次 binder 调用，那不属于「便宜」） */
    val isOpen: Boolean

    /**
     * 同步执行一帧。
     *
     * @param hostCallback 执行器跑脚本期间回调主进程的唯一入口；入参是 host 调用帧，返回是 host 回复帧
     * @throws ChannelDeadException 通道断了（对端死、进程被杀、连接失效）——与「脚本跑坏了」两回事
     */
    fun execute(requestFrame: String, hostCallback: (String) -> String): String

    fun close()
}

/** 通道死亡：可以重连，在没有副作用时可以重放；脚本自己抛的异常绝不会走到这里 */
class ChannelDeadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 主进程侧处理一次 host 调用。
 *
 * 契约：**不得抛出**是实现方（Task 9 的代理）的责任，客户端只是兜底。兜底存在的原因不是礼貌：
 * 异常若从回调里穿出去，落在执行器进程的 JNI 栈上，症状是整个 `:js` 进程没。
 *
 * 回复类型用 [HostReply]（Task 3 定义为 public）：`JsSandboxClient` 要能被 `lib_book_common`
 * 的 Hilt 模块构造，公开签名上不许出现 internal 类型。
 */
fun interface HostHandler {
    fun handle(api: String, argsJson: String): HostReply
}
```

- [x] **Step 4: 写解释器接缝与「脚本跑坏了」异常**

在 `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt` 末尾追加（Task 3 已追加两类，本类是第三类；仍留在基类的同包同文件里——`sealed` subclass 的这条约束是编译期的）：

```kotlin
/**
 * 脚本被执行了，但没有给出结果：超时、内存/栈越限、脚本自己抛异常、调了白名单外的能力。
 *
 * 与 [JsEvaluationPendingException] 的分界是「有没有真跑」：后者是能力缺失（本机构造里没沙箱），
 * 前者是能力在场而这一次失败。混起来的代价是把「这条源的脚本有 bug」说成「本项目不支持脚本」，
 * 用户会去等一个永远不会来的功能，而不是去换一条源。
 */
internal class JsExecutionFailedException(
    val status: JsStatus,
    detail: String,
) : ScriptRuleException("脚本执行失败（${status.name}）：${detail.take(120)}")
```

它引用 `com.ebook.source.sandbox.JsStatus`——同模块，直接 import 即可。

创建 `lib_book_source/src/main/java/com/ebook/source/script/JsInterpreter.kt`：

```kotlin
package com.ebook.source.script

import com.ebook.source.sandbox.JsInvocation
import com.ebook.source.sandbox.JsOutcome

/**
 * 解释器与沙箱之间唯一的接缝。
 *
 * public 而非 internal：装配点在 `lib_book_common`（`BookSourceManagerImpl` 造 `ScriptBookParser`
 * 时把它注入进去），而 Kotlin 的 internal 只在同模块可见——依赖方的 test source set 也不是 friend module。
 *
 * 只有一个执行方法：[JsInvocation] 已经把「跑哪种载荷、带哪些变量、按哪种模式取结果」说全了，
 * 再铺四个便捷入口就是四个会各自漂移的口径。取文本的口径统一放在下面的 [requireText]。
 * 也不设 `isAvailable`：「没接线」由调用方手里的 null 表达，而「接了但这次跑不起来」只在结果里
 * 才有意义——真探测本身就是一次跨进程调用，为一个布尔值付它不划算。
 */
interface JsInterpreter {

    /** 执行一次。实现**不得抛未类型化异常**：一切失败都进 [JsOutcome.status]，由调用方分类处置 */
    fun execute(invocation: JsInvocation): JsOutcome
}

/**
 * 取文本结果，并把「失败」与「没取到值」分开。
 *
 * 这条区分是 2b 定下的口径：null 在求值层是 `RuleResult.Miss`（这条规则没值，可以参与 `||` 短路），
 * 而超时、脚本抛异常、执行器被杀都是**这一轮解析失败**。把后者折叠成 null 等于对用户撒谎
 * 「这个源没有这一项」，症状与真没有这一项完全一样，只有排查时才知道是跑崩了。
 */
internal fun JsInterpreter.requireText(invocation: JsInvocation): String? {
    val outcome = execute(invocation)
    if (!outcome.isSuccess) {
        throw JsExecutionFailedException(outcome.status, outcome.error ?: "沙箱未给出原因")
    }
    return outcome.text
}
```

- [x] **Step 5: 写客户端**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxClient.kt`：

```kotlin
package com.ebook.source.sandbox

import android.os.Looper
import android.os.SystemClock
import com.ebook.source.script.JsInterpreter
import com.ebook.source.script.SandboxProtocolException
import com.xrn1997.common.util.Logger

/**
 * 主进程侧的沙箱客户端：[JsInterpreter] 的真实现，也是全部传输策略的唯一落点。
 *
 * 只做四件事：
 *
 * 1. **算截止日期**。时钟取 `SystemClock.elapsedRealtime()`——机器级单调钟，跨进程同一读数，
 *    所以主进程算出的期限在执行器里可比。`System.nanoTime()` 的原点是各进程随机的，
 *    用它就会变成「主进程以为自己还剩 3 秒、执行器以为还剩 10 秒」，超时形同虚设。
 * 2. **决定要不要重放**。只在本次调用还没转发过 host 回调时重放（见 [afterDisconnect]）。
 * 3. **中继 host 回调**。把执行器要的两类事（求值一条规则、代取一个 URL）交给 [HostHandler]，
 *    并且绝不把异常抛回执行器——那条异常会落在 `.so` 的调用栈上，症状是整个 `:js` 进程没。
 * 4. **挡住两类自伤**：主线程调用（阻塞至多 [JsLimits.wallClockMs] 毫秒，表现是 ANR 而不是解析失败）
 *    与回调里重入（双向死锁）。
 *
 * 一把非重入锁把 execute 串行化：一个执行器进程只有一个 runtime，多个源并发解析时排队比互抢干净；
 * 它同时保证「同一时刻只有一帧在飞」，[JsLimits.maxRequestBytes] 那 900 KB 的取值前提就是这条。
 * 排队的代价由截止日期兜住：排在别人后面的请求会带着同一个期限进场，等不到就报 TIMEOUT——
 * 这是真话，比让它悄悄多跑五秒要好。
 */
class JsSandboxClient(
    /** 建一条通道。真实现是 Task 8 的连接器；测试里给假通道，这就是本类能在 JVM 上测透的原因 */
    private val openChannel: () -> JsChannel,
    private val limits: JsLimits = JsLimits(),
    /** 单调毫秒钟，跨进程可比（见类 KDoc 第 1 条） */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val hostHandler: HostHandler = HostHandler { _, _ ->
        HostReply(ok = false, data = null, error = "主进程未接线 host 代理")
    },

    /**
     * 主线程判据。取「两个 Looper 同一实例」而不是 `myLooper() == null` 之类的近似：
     * 后者在没 Looper 的线程上会把后台线程误判成主线程，那等于把所有解析都禁掉。
     */
    private val isMainThread: () -> Boolean = {
        val main = Looper.getMainLooper()
        main != null && main === Looper.myLooper()
    },
) : JsInterpreter {

    private val tag = "JsSandboxClient"

    /** execute 串行锁，同时护住 [channel] 字段（两侧都在同一把锁里读写，不需要第二把） */
    private val inFlight = Any()

    private var channel: JsChannel? = null

    /** 深度 0 = 不在回调里。用 ThreadLocal 而不是全局计数：回调在 binder 线程上跑，与发起 execute 的线程不同 */
    private val insideHostCall = ThreadLocal<Int>()

    override fun execute(invocation: JsInvocation): JsOutcome {
        check(!isMainThread()) {
            "脚本沙箱不能在主线程执行：一次执行最长阻塞 ${limits.wallClockMs} ms，症状会是 ANR 而不是解析失败"
        }
        // 重入守卫必须在拿锁之前。唯一的 runtime 正攥在回调的发起方手里，
        // 这里等下去是「我等它返回、它等我完成」的双向死锁，只能靠超时收场。
        if ((insideHostCall.get() ?: 0) > 0) {
            return unavailable("不支持嵌套执行：host 回调里又发起了一次沙箱调用")
        }
        val frame = JsProtocol.encodeRequest(invocation, clock() + limits.wallClockMs)
        if (utf8Length(frame) > limits.maxRequestBytes) {
            return JsOutcome(
                JsStatus.TOO_LARGE,
                error = "沙箱请求超出 ${limits.maxRequestBytes} 字节上限：绑定里的整页太大",
            )
        }
        val attempt = Attempt()
        return try {
            synchronized(inFlight) { runOnce(frame, attempt) }
        } catch (protocol: SandboxProtocolException) {
            // 帧读不出来 = 两侧代码不同步（.so 与 Kotlin 不是同一次构建）。这是接线缺陷，
            // 不能降级成「不可用」——那会把排查方向支到设备上，而根因在构建。
            synchronized(inFlight) { dropChannel() }
            throw protocol
        } catch (dead: ChannelDeadException) {
            synchronized(inFlight) { dropChannel() }
            afterDisconnect(frame, attempt, dead)
        } catch (t: Throwable) {
            // 通道实现抛出了没预料的东西（Parcel 写失败、对端 SDK 里的 RuntimeException）。
            // 接缝的契约是「不抛未类型化异常」，所以这里收下、换成一次可命名的不可用。
            Logger.e(tag, "沙箱通道抛出了未预期的异常", t)
            synchronized(inFlight) { dropChannel() }
            unavailable("通道异常：${t.message}")
        }
    }

    /** 一次 execute 的私有账本：重放预算只看它。看客户端全局的话，并发时会把 A 的副作用算到 B 头上 */
    private class Attempt {
        var relayed: Int = 0
    }

    /** 断连处置：本次没有副作用就重连重放一次，有副作用就停手 */
    private fun afterDisconnect(frame: String, attempt: Attempt, dead: ChannelDeadException): JsOutcome {
        if (attempt.relayed > 0) {
            Logger.w(tag, "沙箱在第 ${attempt.relayed} 次 host 回调之后断连，本次不重放：${dead.message}")
            return unavailable("断连且本次已转发 ${attempt.relayed} 次主进程回调，重放会让同一页被请求两次")
        }
        return try {
            Logger.w(tag, "沙箱通道已断，重连重放一次：${dead.message}")
            synchronized(inFlight) { runOnce(frame, attempt) }
        } catch (again: ChannelDeadException) {
            synchronized(inFlight) { dropChannel() }
            Logger.w(tag, "沙箱重连后仍不可用：${again.message}")
            unavailable("断连且重连失败")
        } catch (protocol: SandboxProtocolException) {
            throw protocol
        } catch (t: Throwable) {
            synchronized(inFlight) { dropChannel() }
            unavailable("重连后通道异常：${t.message}")
        }
    }

    /** 一次投递：拿通道（必要时新建）、执行、按上限解帧。只允许在 [inFlight] 锁内调用 */
    private fun runOnce(frame: String, attempt: Attempt): JsOutcome {
        val live = channel
        val ch = if (live != null && live.isOpen) live else openChannel().also { channel = it }
        val replyFrame = ch.execute(frame) { hostFrame -> relay(hostFrame, attempt) }
        return JsProtocol.decodeOutcome(replyFrame, limits)
    }

    /** 丢掉这条通道。close() 自己抛（对端已经没了）不能把处置打断，所以包在 runCatching 里 */
    private fun dropChannel() {
        val stale = channel
        channel = null
        runCatching { stale?.close() }
    }

    /**
     * 转发一次 host 调用。
     *
     * [attempt] 在这里加一：从这一刻起本次调用**有了不可重来的副作用**（脚本已经看到了一页内容、
     * 主进程已经替它请求过一次 URL），断连之后的重放窗口就此关掉。
     *
     * 回调期间给本线程打标记：Task 9 的代理会在这段里求值嵌套规则，那条路径可能又想要一次 JS——
     * [execute] 开头的守卫读的就是它。
     */
    private fun relay(callFrame: String, attempt: Attempt): String {
        attempt.relayed++
        val depth = (insideHostCall.get() ?: 0) + 1
        insideHostCall.set(depth)
        val reply = try {
            val (api, args) = JsProtocol.decodeHostCall(callFrame)
            hostHandler.handle(api, args)
        } catch (t: Throwable) {
            Logger.e(tag, "主进程 host 代理失败，已按 ok=false 回给脚本", t)
            HostReply(ok = false, data = null, error = "主进程处理失败：${t.message}")
        } finally {
            insideHostCall.set(depth - 1)
        }
        val frame = JsProtocol.encodeHostReply(reply.ok, reply.data, reply.error)
        return if (utf8Length(frame) > limits.maxHostReplyBytes) {
            Logger.w(tag, "host 回复超出 ${limits.maxHostReplyBytes} 字节上限，按失败回给脚本")
            JsProtocol.encodeHostReply(ok = false, data = null, error = "主进程回复超出字节上限")
        } else {
            frame
        }
    }

    private fun unavailable(reason: String): JsOutcome =
        JsOutcome(JsStatus.UNAVAILABLE, error = "沙箱执行器当前不可用：${reason.take(120)}")
}
```

**成功路径之后没有任何收尾动作**：TIMEOUT / MEMORY / STACK 之后 runtime 该不该重置是执行器侧的事（Task 8 在回帧之前做），客户端不持状态，就不会出现「两侧都以为对方负责清理」。

- [x] **Step 6: 跑测试**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsSandboxClientTest*"`
Expected: 12 例全绿。

Run: `./gradlew :lib_book_source:testDebugUnitTest --rerun-tasks`
Expected: 全模块绿且**零编译警告**（`--rerun-tasks` 才会把警告重新打出来，UP-TO-DATE 的任务不重放输出）。

- [ ] **Step 7: 提交（需授权）**

```bash
git add lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxContract.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsChannel.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxClient.kt \
        lib_book_source/src/main/java/com/ebook/source/script/JsInterpreter.kt \
        lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt \
        lib_book_source/src/test/java/com/ebook/source/sandbox/JsSandboxClientTest.kt
git commit -m "feat(lib_book_source): 新增沙箱主进程客户端与跨进程契约" -m \
  "- 通道抽象零 Android 依赖：断连重放、重放预算、回调中继、重入与主线程守卫全在 JVM 上锁死
  - 重放只在本次调用未转发过 host 回调时做，避免同一页被请求两次写进重复条目
  - 帧上限改由 binder 事务缓冲定尺（900 KB），超限在本地判 TOO_LARGE 而不是现场抛异常
  - 截止日期取 elapsedRealtime：机器级单调钟，跨进程同一读数"
```

---

### Task 7：桥接层（`js_bridge.cpp` + `QuickJsNative` + `HostDispatcher` + `JsRuntimeBridge`）

C++ 只回答一个问题：「怎么在内核里跑一段脚本并把结果说清楚」。它不知道 Binder、不知道白名单里有哪些能力、不知道规则语法——这些全在 Kotlin 侧，而 Kotlin 侧可测。

四条决定形状的事实，写代码前先读：

1. **内核暴露给脚本的只有一个函数：`__host_call(api, argsJson)`**。白名单不进 C++（加一个能力不碰原生代码，这是 ADR-0028「桥接层可审计」的实际含义）。
   但**脚本可以直接调 `__host_call` 绕过 `java.*` 垫片**——它是 globalThis 上的一个属性。所以能力表必须在 Kotlin 侧**再判一次**：`HostDispatcher` 收到不在 `JsHostApi` 里的名字就回 `ok=false`。垫片那一层的 arity 校验是给人看的，这一层才是边界。
2. **截止日期用 `CLOCK_BOOTTIME` 比**。C++ 侧若用 `CLOCK_MONOTONIC`，休眠期间不计时：用户锁屏十分钟再回来，主进程以为期限早过了、内核以为还剩五秒，超时形同虚设。Task 6 的 `elapsedRealtime()` 正是 BOOTTIME 口径。
3. **中断器不在定时器上**（见「关键事实」表）：只在解释器轮询点被调用，因此原生函数阻塞期间打不断——host call 自身的时限只能由主进程侧兜（Task 9 的网络代理有 connect/read 超时）。
4. **栈基线每次执行前刷新**：`JS_SetMaxStackSize` 判的是「距 `stack_top` 的字节数」，而 `stack_top` 在创建时抓取。执行器线程换人（进程重启后由别的 binder 线程来）不刷新，就会拿一条不相干的栈去比限值，症状是「深递归时报或不报」。

**结果描述符**是 C++ 与 `JsRuntimeBridge` 之间的私有约定（**不出进程**，与 Task 3 的 `OutcomeFrame` 是两件事）。C++ 只产出前三行：

```
{"kind":"ok","data":<JSON 值或 null>}
{"kind":"exception","timedOut":true|false,"errorName":"...","errorMessage":"..."}
{"kind":"unsupported","errorMessage":"..."}
```

`ok` 刻意**不带** `text`：脚本完成值是字符串时文本就是它，是标量时文本是它的字面量，两件事都由主进程侧的 `JsOutcome.text` 从 `data` 现场导出。描述符里再存一份就有「`data` 改了、`text` 没改」的那一天，而且两份不一致时没人能看出哪份是真的。

完成值是 `undefined` 或 `null` 时，`data` **是 JSON 的 null**：既不是缺字段（stringify 会丢掉值为 `undefined` 的属性），也不是字符串 `"undefined"`（`String(undefined)` 的值）。这两条各自都合理，叠起来就会把「一条忘了给 `result` 赋值的规则」变成「正文是 undefined 这四个字母」——Step 2 的 `ok_descriptor` 就地挡住，Step 7 有一例锁住它。

描述符里**没有** `tooLarge` 这一种。报文超限是执行器服务（Task 8）在**写跨进程响应帧之前**判的，那一刻内核的 `data` 已经 stringify 完、正躺在一个字符串里，判大小的正是那个字符串——判完直接换成一帧 `OutcomeFrame`，其 `status` 名是 `TOO_LARGE`，走的是 `mapStatus(statusName)` 那条重载，与描述符无关。给描述符补一种 `tooLarge` 就是在本段末尾反对的那件事上开口子：协议里留一个没有生产者的分支。

`kind` 三个取值与 `JsProtocol.mapStatus` 的 kind 分支一一对应，**两侧都不许多出一种**：协议里留一个没有生产者的分支，读代码的人会去猜是哪一侧漏了。`data` 由 `JS_JSONStringify` 产出（描述符本身就是一个 JS 对象，见 Step 2）：转义交给内核，C++ 不自己拼字符串——一个手写的 JSON 转义器是这种代码里最容易留下坏帧的地方。

**Files:**
- Create: `lib_book_source/src/main/cpp/bridge/js_bridge.cpp`（整体替换 Task 2 的占位文件）
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/QuickJsNative.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/HostDispatcher.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsRuntimeBridge.kt`
- Modify: `lib_book_source/consumer-rules.pro`
- Modify: `lib_book_source/build.gradle.kts`（补 instrumented 测试的 runner 与依赖）
- Modify: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsProtocol.kt`（`mapStatus` 去掉没有生产者的 `syntax` 分支）
- Modify: `lib_book_source/src/test/java/com/ebook/source/sandbox/JsProtocolTest.kt`（补标量 `text` 一例）
- Create: `lib_book_source/src/test/java/com/ebook/source/sandbox/JsRuntimeBridgeTest.kt`
- Create: `lib_book_source/src/androidTest/java/com/ebook/source/sandbox/QuickJsBridgeTest.kt`

- [x] **Step 1: 写 Kotlin 的 native 声明**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/QuickJsNative.kt`：

```kotlin
package com.ebook.source.sandbox

/**
 * `js_bridge.cpp` 的 Kotlin 侧影子。四个函数名与 C++ 里的
 * `Java_com_ebook_source_sandbox_QuickJsNative_nativeXxx` 一一对应。
 *
 * 刻意是 `object` 的**实例** external 函数（不是 `@JvmStatic`）：JNI 的第二参数因此是 `jobject`
 * 而不是 `jclass`，两侧都按这个来。这个选择不是风格——`external` 与 `@JvmStatic` 的组合在 Kotlin
 * 里生成不出可解析的静态 native 方法，写错了要等到设备上第一次调用才炸
 * （`UnsatisfiedLinkError` 在编译期与链接期都不露头）。
 *
 * 加载失败不在这里抛：整条沙箱链路的要求是「失败是有名字的结果」。`loaded=false` 时
 * [JsRuntimeBridge] 直接回 [JsStatus.UNAVAILABLE]，一次 `external` 调用都不会发出。
 */
internal object QuickJsNative {

    val loaded: Boolean = runCatching { System.loadLibrary("ebook_js") }.isSuccess

    /** 建 runtime 与 context，装好四项限值和 `__host_call`。失败返回 0（句柄一律当 int64 传） */
    external fun nativeCreate(heapBytes: Long, stackBytes: Long): Long

    /** 丢掉 context 重建并重放垫片：任务边界的强制重置（ADR-0028 决策 5），runtime 本身留着 */
    external fun nativeReset(handle: Long, preludeSource: String): Boolean

    /**
     * 跑一次。
     *
     * @param source 已含 `QuickJsPrelude.BOOTSTRAP` 前缀的完整脚本文本
     * @param bindingsJson 绑定的 JSON 对象；C++ 把它挂成 `__bindings` 交给 BOOTSTRAP 去落成全局量
     * @param deadlineMonoMs `CLOCK_BOOTTIME` 毫秒绝对值，与 `SystemClock.elapsedRealtime()` 同口径
     * @return 结果描述符（见 Task 7 开头），任何情况下都是合法 JSON
     */
    external fun nativeEval(
        handle: Long,
        bindingsJson: String,
        source: String,
        deadlineMonoMs: Long,
    ): String

    external fun nativeDestroy(handle: Long)
}
```

- [x] **Step 2: 写桥接层**

整体替换 `lib_book_source/src/main/cpp/bridge/js_bridge.cpp`（Task 2 的占位文件只为证明链接可用，此刻删掉）：

```cpp
// 沙箱桥接层：内核与主进程之间唯一的原生代码。
//
// 它只做四件事：建 runtime 并按限值管住它、把绑定挂成全局量、跑一段脚本文本、
// 把结果说清楚（描述符）。它不认识白名单、不认识 Binder、不认识规则语法。
//
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <ctime>
#include <string>
#include <vector>

#include "quickjs.h"

#define LOG_TAG "EbookJs"
// 原生侧的日志只用于「接线坏了」这一类当场没法变成返回值的情况（Kotlin 侧的日志统一走 lib_common）
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr char DISPATCHER_CLASS[] = "com/ebook/source/sandbox/HostDispatcher";
constexpr char DISPATCHER_METHOD[] = "handle";
constexpr char DISPATCHER_SIG[] = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

// 微任务轮数上限。中断器只管解释器，`while (true) Promise.resolve().then(f)` 这类自驱动的
// 微任务队列要在这里断，否则 wallClockMs 形同虚设。
constexpr int MAX_PENDING_JOBS = 10000;

// 垫片里 reject() 抛出的前缀（Task 4）。桥接层不认识白名单，只搬运这一条前缀约定。
constexpr char UNSUPPORTED_PREFIX[] = "__UNSUPPORTED__:";

// 三句兜底描述符写成编译期常量：走到这些分支时内核可能已经处于 OOM 状态，
// 再走一遍「建对象 + stringify」正是最不可靠的路，而返回值绝不能是 null。
constexpr char DESCRIPTOR_UNSERIALIZABLE[] =
    "{\"kind\":\"exception\",\"timedOut\":false,\"errorName\":\"InternalError\","
    "\"errorMessage\":\"脚本的完成值无法序列化成 JSON（循环引用或 BigInt）\"}";
constexpr char DESCRIPTOR_BRIDGE_BROKEN[] =
    "{\"kind\":\"exception\",\"timedOut\":false,\"errorName\":\"InternalError\","
    "\"errorMessage\":\"沙箱桥接层未初始化\"}";
constexpr char DESCRIPTOR_UNDELIVERABLE[] =
    "{\"kind\":\"exception\",\"timedOut\":false,\"errorName\":\"InternalError\","
    "\"errorMessage\":\"结果字符串无法送达主进程\"}";

JavaVM *g_vm = nullptr;
jclass g_dispatcher = nullptr;    // 全局引用：本地引用在回调发生时早没了
jmethodID g_handle_mid = nullptr;
jmethodID g_get_bytes_mid = nullptr;  // String.getBytes(String)
jstring g_utf8_name = nullptr;        // 全局引用的 "UTF-8"，免得每次回调建一个

// 执行器进程内一份：runtime 常驻，context 每个任务重建（ADR-0028 决策 5）。
struct Bridge {
    JSRuntime *rt = nullptr;
    JSContext *ctx = nullptr;
    std::atomic<int64_t> deadline_ms{INT64_MAX};
    std::atomic<int> interrupted{0};
};

// 机器级单调钟：与主进程的 SystemClock.elapsedRealtime() 同口径（CLOCK_MONOTONIC 休眠时停表）
int64_t boot_time_ms() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

// 内核中断器：返回非 0 就让当前求值以 InternalError("interrupted") 结束。
// 只在解释器轮询点被调用，因此打不断一次阻塞中的原生调用——host call 的时限由主进程侧兜。
int on_interrupt(JSRuntime *, void *opaque) {
    auto *b = static_cast<Bridge *>(opaque);
    if (boot_time_ms() >= b->deadline_ms.load(std::memory_order_relaxed)) {
        b->interrupted.store(1, std::memory_order_relaxed);
        return 1;
    }
    return 0;
}

// 期限的作用域就是这一次执行。离开时放回 INT64_MAX，否则任务之间任何一次求值
// （下一次 reset 重放垫片）都会被上一条规则的死线打断。
struct DeadlineScope {
    Bridge *b;
    DeadlineScope(Bridge *bridge, int64_t deadline) : b(bridge) {
        b->interrupted.store(0, std::memory_order_relaxed);
        b->deadline_ms.store(deadline, std::memory_order_relaxed);
    }
    DeadlineScope(const DeadlineScope &) = delete;
    DeadlineScope &operator=(const DeadlineScope &) = delete;
    ~DeadlineScope() { b->deadline_ms.store(INT64_MAX, std::memory_order_relaxed); }
};

// JS 值的作用域包装。nativeEval 有五六条出口，手工配对的 JS_FreeValue 必漏一条——
// 漏了不报错，只在 JS_FreeContext 时留一行没人看的 leak 日志。
// 释放 JS_EXCEPTION 是安全的（内核的 JS_FreeValue 对无引用标签直接返回）。
struct ValueGuard {
    JSContext *ctx;
    JSValue v;
    ValueGuard(JSContext *c, JSValue value) : ctx(c), v(value) {}
    ValueGuard(const ValueGuard &) = delete;
    ValueGuard &operator=(const ValueGuard &) = delete;
    ~ValueGuard() { JS_FreeValue(ctx, v); }
    JSValueConst get() const { return v; }
    void reset(JSValue next) {
        JS_FreeValue(ctx, v);
        v = next;
    }
};

// jstring → UTF-8 字节。刻意不走 GetStringUTFChars：那一路给的是「修改版 UTF-8」，
// 增补平面字符（emoji、CJK 扩展 B 的生僻字）是两段三字节代理对，内核的 unicode_from_utf8
// 认不出、逐个替换成 U+FFFD——症状是「正文里的 emoji 全变乱码」且只在真机上出现。
// 交给 JDK 的 getBytes("UTF-8")，编码由它负责。
bool jstring_to_utf8(JNIEnv *env, jstring text, std::vector<char> &out) {
    if (!text || !g_get_bytes_mid) return false;
    auto bytes = reinterpret_cast<jbyteArray>(env->CallObjectMethod(text, g_get_bytes_mid, g_utf8_name));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (!bytes) return false;
    const jsize n = env->GetArrayLength(bytes);
    jbyte *raw = env->GetByteArrayElements(bytes, nullptr);
    if (!raw) {
        env->DeleteLocalRef(bytes);
        return false;
    }
    out.assign(reinterpret_cast<char *>(raw), reinterpret_cast<char *>(raw) + n);
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);  // 只读：不回写、不重复释放
    env->DeleteLocalRef(bytes);
    return true;
}

// 值 → JSON 文本。转义、嵌套、代理对全交给内核（它的 stringify 会把 < 0x20 和代理对
// 写成 \uXXXX，所以产物里没有裸控制字符，过 NewStringUTF 安全）。
// 失败返回空串让调用方换兜底常量。
std::string to_json(JSContext *ctx, JSValueConst v) {
    JSValue text = JS_JSONStringify(ctx, v, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(text)) {
        JS_FreeValue(ctx, text);
        JS_FreeValue(ctx, JS_GetException(ctx));  // 必须取走：挂着的异常会让下一次求值当场失败
        return {};
    }
    std::string out;
    const char *c = JS_ToCString(ctx, text);
    if (c) {
        out = c;
        JS_FreeCString(ctx, c);
    }
    JS_FreeValue(ctx, text);
    return out;
}

// 装一个字段。描述符对象是当场新建、脚本没机会冻结的，装不上只可能是内核 OOM——
// 那种情况取走异常、少一个字段（Kotlin 侧字段全有默认值），但不允许把异常留给下一次求值。
void put(JSContext *ctx, JSValueConst obj, const char *key, JSValue v) {
    if (JS_IsException(v)) {
        JS_FreeValue(ctx, v);
        JS_FreeValue(ctx, JS_GetException(ctx));
        return;
    }
    if (JS_SetPropertyStr(ctx, obj, key, v) < 0) JS_FreeValue(ctx, JS_GetException(ctx));
}

void put_str(JSContext *ctx, JSValueConst obj, const char *key, const char *value) {
    put(ctx, obj, key, JS_NewString(ctx, value));
}

std::string finish(JSContext *ctx, JSValue desc) {
    std::string out = to_json(ctx, desc);
    JS_FreeValue(ctx, desc);
    return out.empty() ? std::string(DESCRIPTOR_UNSERIALIZABLE) : out;
}

std::string ok_descriptor(JSContext *ctx, JSValueConst value) {
    // undefined 与 null 必须显式落成 null，两个理由各致命一次：
    // stringify 会**丢掉**值为 undefined 的属性（`data` 就没了），而 `String(undefined)` 是 "undefined"。
    // 叠加的结果是「一条忘了给 result 赋值的规则产出词『undefined』当正文」——
    // 正是 2b 定的口径里禁止的「把没有值说成有值」。
    const bool nothing = JS_IsUndefined(value) || JS_IsNull(value);
    JSValue d = JS_NewObject(ctx);
    put_str(ctx, d, "kind", "ok");
    put(ctx, d, "data", nothing ? JS_NULL : JS_DupValue(ctx, value));
    // 没有 text 字段：文本由主进程侧的 JsOutcome.text 从 data 现场导出（见 Task 7 开头）
    return finish(ctx, d);
}

// 每种 kind 只发文档里那几个字段：JsRuntimeBridge 解描述符用的是与跨进程帧同样的严格解码，
// 多一个字段就是协议不符。
std::string exception_descriptor(JSContext *ctx, const std::string &name, const std::string &message,
                                 bool timed_out) {
    JSValue d = JS_NewObject(ctx);
    put_str(ctx, d, "kind", "exception");
    put(ctx, d, "timedOut", JS_NewBool(ctx, timed_out));
    put_str(ctx, d, "errorName", name.c_str());
    put_str(ctx, d, "errorMessage", message.c_str());
    return finish(ctx, d);
}

std::string unsupported_descriptor(JSContext *ctx, const std::string &message) {
    JSValue d = JS_NewObject(ctx);
    put_str(ctx, d, "kind", "unsupported");
    put_str(ctx, d, "errorMessage", message.c_str());
    return finish(ctx, d);
}

std::string read_string_prop(JSContext *ctx, JSValueConst obj, const char *prop, const char *fallback) {
    JSValue v = JS_GetPropertyStr(ctx, obj, prop);
    if (JS_IsException(v)) {
        // `throw null` / `throw undefined`：读属性这一步自己就抛了
        JS_FreeValue(ctx, v);
        JS_FreeValue(ctx, JS_GetException(ctx));
        return fallback;
    }
    std::string out = fallback;
    if (!JS_IsUndefined(v) && !JS_IsNull(v)) {
        const char *c = JS_ToCString(ctx, v);
        if (c) {
            out = c;
            JS_FreeCString(ctx, c);
        } else {
            JS_FreeValue(ctx, JS_GetException(ctx));  // getter 自己抛的
        }
    }
    JS_FreeValue(ctx, v);
    return out;
}

// 内核当前挂着的异常 → 描述符。取走异常是这一步的副作用，调用方不必再管。
std::string take_exception_descriptor(JSContext *ctx, Bridge *b) {
    JSValue ex = JS_GetException(ctx);
    std::string name = read_string_prop(ctx, ex, "name", "Error");
    std::string message = read_string_prop(ctx, ex, "message", "");
    if (message.empty()) {
        // `throw 42` 这类非对象抛出：用它的字符串形态，别空着
        const char *c = JS_ToCString(ctx, ex);
        if (c) {
            message = c;
            JS_FreeCString(ctx, c);
        } else {
            JS_FreeValue(ctx, JS_GetException(ctx));
            message = "（异常信息本身取不出来）";
        }
    }
    JS_FreeValue(ctx, ex);

    if (message.rfind(UNSUPPORTED_PREFIX, 0) == 0) {
        return unsupported_descriptor(ctx, message.substr(sizeof(UNSUPPORTED_PREFIX) - 1));
    }
    const bool timed_out = b->interrupted.load(std::memory_order_relaxed) != 0 ||
        boot_time_ms() >= b->deadline_ms.load(std::memory_order_relaxed);
    return exception_descriptor(ctx, name, message, timed_out);
}

// 排空微任务队列。返回 false 表示这一轮已经失败、out 里装好了失败描述符。
bool drain_jobs(JSContext *ctx, Bridge *b, std::string &out) {
    JSContext *job_ctx = ctx;
    for (int i = 0; i < MAX_PENDING_JOBS; i++) {
        const int ret = JS_ExecutePendingJob(b->rt, &job_ctx);
        if (ret == 0) return true;  // 队列空了：脚本里的 await 与 .then 到这一刻才算跑完
        if (ret < 0) {
            // 内核按 runtime 取 job，抛错的可能不是本 context，描述符要问那个 context
            out = take_exception_descriptor(job_ctx, b);
            return false;
        }
    }
    if (JS_IsJobPending(b->rt)) {
        out = unsupported_descriptor(ctx, "微任务数量超过上限，脚本里大概有自驱动的 Promise 循环");
        return false;
    }
    return true;
}

// 沙箱里没有事件循环：一个等待中的 Promise 永远不会落定。取 `then` 判一下，拒掉——
// 折叠成「没有值」就是骗脚本说这页没内容（2b 的口径）
bool is_thenable(JSContext *ctx, JSValueConst v) {
    if (!JS_IsObject(v)) return false;
    JSValue then = JS_GetPropertyStr(ctx, v, "then");
    const bool broken = JS_IsException(then);  // 自定义 getter 抛的
    const bool yes = !broken && JS_IsFunction(ctx, then);
    JS_FreeValue(ctx, then);
    if (broken) JS_FreeValue(ctx, JS_GetException(ctx));
    return yes;
}

// 描述符过 JNI。**绝不返回 null**：Kotlin 侧签名是非空 String，JNI 边界上凭空出现的空值
// 会变成一条没有消息的 NPE，比任何一种失败描述都难查。
jstring deliver(JNIEnv *env, const std::string &text) {
    jstring result = env->NewStringUTF(text.c_str());
    if (!result) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        result = env->NewStringUTF(DESCRIPTOR_UNDELIVERABLE);
    }
    return result;
}

// 脚本可见的唯一原生入口。白名单不在这里判——HostDispatcher 在 Kotlin 侧判第二次。
JSValue host_call(JSContext *ctx, JSValueConst, int argc, JSValueConst *argv) {
    if (argc != 2) return JS_ThrowTypeError(ctx, "__host_call 需要 2 个参数");
    JNIEnv *env = nullptr;
    if (!g_vm || g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JS_ThrowInternalError(ctx, "%s", "host 回调：取不到 JNIEnv");
    }
    if (!g_dispatcher || !g_handle_mid) return JS_ThrowInternalError(ctx, "%s", "host 回调：主进程入口未就绪");

    // 出向用 cesu8=1：那正是 JNI「修改版 UTF-8」，代理对能无损过 NewStringUTF
    const char *api = JS_ToCStringLen2(ctx, nullptr, argv[0], 1);
    if (!api) return JS_EXCEPTION;
    const char *args = JS_ToCStringLen2(ctx, nullptr, argv[1], 1);
    if (!args) {
        JS_FreeCString(ctx, api);
        return JS_EXCEPTION;
    }
    jstring japi = env->NewStringUTF(api);
    jstring jargs = env->NewStringUTF(args);
    JS_FreeCString(ctx, api);
    JS_FreeCString(ctx, args);
    if (!japi || !jargs) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (japi) env->DeleteLocalRef(japi);
        if (jargs) env->DeleteLocalRef(jargs);
        return JS_ThrowInternalError(ctx, "%s", "host 回调：参数送不进主进程");
    }

    auto jreply = reinterpret_cast<jstring>(
        env->CallStaticObjectMethod(g_dispatcher, g_handle_mid, japi, jargs));
    env->DeleteLocalRef(japi);
    env->DeleteLocalRef(jargs);
    if (env->ExceptionCheck()) {  // dispatcher 内部已经兜住自己的异常；走到这里说明是 JVM 级问题
        env->ExceptionDescribe();
        env->ExceptionClear();
        return JS_ThrowInternalError(ctx, "%s", "host 回调在主进程抛了异常");
    }
    if (!jreply) return JS_ThrowInternalError(ctx, "%s", "host 回调返回了 null");

    std::vector<char> reply;
    const bool decoded = jstring_to_utf8(env, jreply, reply);
    env->DeleteLocalRef(jreply);
    if (!decoded || reply.empty()) return JS_ThrowInternalError(ctx, "%s", "host 回调的应答读不出来");

    JSValue value = JS_ParseJSON(ctx, reply.data(), reply.size(), "<host_reply>");
    if (JS_IsException(value)) {
        JS_FreeValue(ctx, value);
        JS_FreeValue(ctx, JS_GetException(ctx));
        return JS_ThrowInternalError(ctx, "%s", "主进程回的应答不是合法 JSON");
    }
    return value;
}

// __host_call 挂在 globalThis 上，而 globalThis 是 **context 级**的：重建 context 之后必须再装一次
bool install_host_call(JSContext *ctx) {
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue fn = JS_NewCFunction(ctx, host_call, "__host_call", 2);
    const bool ok = JS_SetPropertyStr(ctx, global, "__host_call", fn) >= 0;
    if (!ok) {
        JS_FreeValue(ctx, JS_GetException(ctx));
        LOGE("install __host_call failed");
    }
    JS_FreeValue(ctx, global);
    return ok;
}

// FindClass 必须在「被应用类调进来」的时刻做，不能放在 JNI_OnLoad：后者的 FindClass 用系统类加载器，
// 找不到应用类，症状是执行器一启动就 NoClassDefFoundError（Android JNI 的经典坑）。
bool ensure_dispatcher(JNIEnv *env) {
    if (g_dispatcher && g_handle_mid && g_get_bytes_mid && g_utf8_name) return true;

    jclass local = env->FindClass(DISPATCHER_CLASS);
    if (!local) {
        env->ExceptionClear();
        LOGE("FindClass(%s) failed", DISPATCHER_CLASS);
        return false;
    }
    g_dispatcher = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!g_dispatcher) return false;
    g_handle_mid = env->GetStaticMethodID(g_dispatcher, DISPATCHER_METHOD, DISPATCHER_SIG);

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class) {
        g_get_bytes_mid = env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B");
        env->DeleteLocalRef(string_class);
    } else {
        env->ExceptionClear();
    }
    jstring utf8 = env->NewStringUTF("UTF-8");
    if (utf8) {
        g_utf8_name = reinterpret_cast<jstring>(env->NewGlobalRef(utf8));
        env->DeleteLocalRef(utf8);
    }
    if (!g_handle_mid || !g_get_bytes_mid || !g_utf8_name) {
        LOGE("dispatcher/JNI method lookup incomplete: handle=%p getBytes=%p", (void *) g_handle_mid,
             (void *) g_get_bytes_mid);
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ebook_source_sandbox_QuickJsNative_nativeCreate(JNIEnv *env, jobject, jlong heap_bytes,
                                                         jlong stack_bytes) {
    if (!ensure_dispatcher(env)) return 0;
    auto *b = new Bridge();
    b->rt = JS_NewRuntime();
    if (!b->rt) {
        delete b;
        return 0;
    }
    // 堆限不住一次 host call 拿回来的 900 KB 字符串（那是主进程的账），它管的是脚本自己的分配
    JS_SetMemoryLimit(b->rt, static_cast<size_t>(heap_bytes));
    // 栈限低于 native 线程栈，让深递归先以内核异常结束，而不是把进程撞没
    JS_SetMaxStackSize(b->rt, static_cast<size_t>(stack_bytes));
    JS_SetCanBlock(b->rt, 0);  // Atomics.wait 一类阻塞原语直接失败：执行器线程上不能有待不起的等待
    JS_SetInterruptHandler(b->rt, on_interrupt, b);
    JS_SetRuntimeOpaque(b->rt, b);
    return reinterpret_cast<jlong>(reinterpret_cast<intptr_t>(b));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ebook_source_sandbox_QuickJsNative_nativeReset(JNIEnv *env, jobject, jlong handle,
                                                        jstring prelude_source) {
    auto *b = reinterpret_cast<Bridge *>(reinterpret_cast<intptr_t>(handle));
    if (!b || !b->rt) return JNI_FALSE;
    if (b->ctx) {
        JS_FreeContext(b->ctx);  // 任务边界：脚本留下的全局量、闭包、待办微任务一起没了
        b->ctx = nullptr;
    }
    b->ctx = JS_NewContext(b->rt);
    if (!b->ctx) return JNI_FALSE;
    if (!install_host_call(b->ctx)) return JNI_FALSE;

    std::vector<char> src;
    if (!jstring_to_utf8(env, prelude_source, src) || src.empty()) {
        LOGE("prelude source unreadable");
        return JNI_FALSE;
    }
    // 垫片随包冻结、永不来自书源，所以它的失败就是本仓的缺陷：写进日志，不让它静默
    JSValue r = JS_Eval(b->ctx, src.data(), src.size(), "prelude.js", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(r)) {
        JSValue ex = JS_GetException(b->ctx);
        const char *msg = JS_ToCString(b->ctx, ex);
        LOGE("prelude eval failed: %s", msg ? msg : "(unreadable)");
        if (msg) JS_FreeCString(b->ctx, msg);
        JS_FreeValue(b->ctx, ex);
        JS_FreeValue(b->ctx, r);
        return JNI_FALSE;
    }
    JS_FreeValue(b->ctx, r);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ebook_source_sandbox_QuickJsNative_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *b = reinterpret_cast<Bridge *>(reinterpret_cast<intptr_t>(handle));
    if (!b) return;
    if (b->ctx) JS_FreeContext(b->ctx);
    if (b->rt) JS_FreeRuntime(b->rt);
    delete b;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ebook_source_sandbox_QuickJsNative_nativeEval(JNIEnv *env, jobject, jlong handle,
                                                       jstring bindings_json, jstring source,
                                                       jlong deadline_mono_ms) {
    auto *b = reinterpret_cast<Bridge *>(reinterpret_cast<intptr_t>(handle));
    if (!b || !b->ctx) return deliver(env, DESCRIPTOR_BRIDGE_BROKEN);
    DeadlineScope scope(b, deadline_mono_ms);

    std::vector<char> bindings_utf8;
    std::vector<char> source_utf8;
    if (!jstring_to_utf8(env, source, source_utf8) || source_utf8.empty() ||
        !jstring_to_utf8(env, bindings_json, bindings_utf8)) {
        return deliver(env, DESCRIPTOR_BRIDGE_BROKEN);
    }

    // 栈基线每次执行前刷新：JS_SetMaxStackSize 判的是「距 stack_top 的字节数」，
    // 而 binder 线程每次可能换人（见 Task 7 开头第 4 条）
    JS_UpdateStackTop(b->rt);

    // 绑定必须是**值**，不是拼进脚本文本的字符串：一段含引号与反斜杠的 HTML 会把整次求值变成语法错误
    ValueGuard global(b->ctx, JS_GetGlobalObject(b->ctx));
    JSValue bindings = JS_ParseJSON(b->ctx, bindings_utf8.data(), bindings_utf8.size(), "<bindings>");
    if (JS_IsException(bindings)) {
        JS_FreeValue(b->ctx, bindings);
        JS_FreeValue(b->ctx, JS_GetException(b->ctx));
        // 空绑定 = 所有绑定都是 undefined，脚本会自己报出缺哪一个，比回一句「帧读不出来」有用
        bindings = JS_NewObject(b->ctx);
    }
    if (JS_SetPropertyStr(b->ctx, global.get(), "__bindings", bindings) < 0) {
        JS_FreeValue(b->ctx, JS_GetException(b->ctx));
    }

    ValueGuard value(b->ctx,
                     JS_Eval(b->ctx, source_utf8.data(), source_utf8.size(), "rule.js", JS_EVAL_TYPE_GLOBAL));
    std::string out;
    if (JS_IsException(value.get())) {
        out = take_exception_descriptor(b->ctx, b);
    } else if (drain_jobs(b->ctx, b, out)) {
        // 语料里多数规则写成 `result = '正文' + xxx`，完成值是 undefined：改取全局 result
        if (JS_IsUndefined(value.get())) value.reset(JS_GetPropertyStr(b->ctx, global.get(), "result"));
        out = is_thenable(b->ctx, value.get())
            ? unsupported_descriptor(b->ctx, "脚本返回了 Promise，沙箱不等待异步完成")
            : ok_descriptor(b->ctx, value.get());
    }
    return deliver(env, out);
}
```

`nativeEval` 里没有一处手工 `JS_FreeValue`：`global` 与 `value` 各由一个 `ValueGuard` 管住，期限由 `DeadlineScope` 复位，出口由 `deliver` 收口。这样写不是因为 `goto` 或早退难看，而是这个函数有五条出口（句柄无效、文本送不进、编译失败、微任务失败、正常完成），手工配对释放漏一条就是一次真实泄漏——而泄漏的唯一症状是 `JS_FreeContext` 时一行没人看的 `leak` 日志。

两处**不能顺手简化**的地方，改动前请先读完：

1. **入向一律 `getBytes("UTF-8")`、出向一律 `cesu8=1` + `NewStringUTF`**。看起来两边都是「字符串转字节」，其实两个方向上的编码不同：JNI 的字符串接口用「修改版 UTF-8」，内核用标准 UTF-8。走错方向的后果不是报错而是替换字符——`result = '𠀋'` 在设备上变成 `??`。Step 7 的 `增补平面字符进出都要原样回来` 一例锁住这条。
2. **`JS_GetException` 必须成对出现在每一条异常路径上**。QuickJS 的异常是 context 上的挂起标志，取走它才算处置；留在原地，下一次 `JS_Eval` 会带着旧异常直接返回失败——症状是「第一条规则报错之后，整个执行器后面全坏」，而现场看起来是每个任务各自失败。

- [x] **Step 3: 编译一次，确认原生侧干净**

Run: `./gradlew :lib_book_source:assembleDebug`
Expected: `BUILD SUCCESSFUL`，且 CMake 输出里 `js_bridge.cpp` **零警告**（该 target 开着 `-Wall -Wextra`；`quickjs` target 的警告是上游的，见 Task 2 刻意不开 `-Werror` 的说明）。

常见报错与根因：

- `undefined reference to 'JS_...'` → 内核少了编译单元或宏不对（回 Task 2）
- `format not a string literal and no format arguments` → 给 `JS_ThrowInternalError`/`JS_ThrowTypeError` 传了变量当格式串。消息来自变量的一律写成 `JS_ThrowInternalError(ctx, "%s", msg)`
- `'CLOCK_BOOTTIME' undeclared` → NDK 版本过低（本计划要求 28.x；`CLOCK_BOOTTIME` 从 API 29 起在 `<time.h>` 暴露）

- [x] **Step 4: 写 Kotlin 侧的白名单第二道判**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/HostDispatcher.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.HostCompute
import com.ebook.source.script.JsApiTarget
import com.ebook.source.script.JsHostApi
import kotlinx.serialization.json.Json

/**
 * 脚本能力的唯一入口，跑在 `:js` 进程里。
 *
 * **白名单在这里判第二次不是重复劳动**：垫片里的 `java.md5(...)` 只是给人看的表面，
 * `__host_call` 本身就是 globalThis 上的一个属性，脚本可以 `__host_call('ajax','["http://…"]')`
 * 直接绕过去。C++ 侧刻意不认识能力表（加一个能力不碰原生代码），所以边界必须落在 Kotlin、
 * 且必须落在这一层——判表改表都不需要重编 `.so`。
 *
 * 名字必须是 `handle` 且带 `@JvmStatic`：`js_bridge.cpp` 以字符串常量写死
 * `GetStaticMethodID("handle", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")`，
 * R8 看不见这种引用（Step 6 的 keep 就是为它写的），改签名两侧必须同步。
 */
internal object HostDispatcher {

    /** HOST 类能力的落点：由 Task 8 的执行器服务装进来（要回主进程的那一路只有它认识） */
    @Volatile
    private var relay: ((api: String, argsJson: String) -> HostReply)? = null

    fun installRelay(handler: (api: String, argsJson: String) -> HostReply) {
        // 装两次意味着两个服务在抢同一个执行器进程：那会让前一半请求走进已死的通道，静默失败
        check(relay == null) { "host 中继只能安装一次" }
        relay = handler
    }

    /** 测试与 Task 8 的重启用路径用：进程内重建执行器时把中继摘掉重装 */
    fun clearRelay() {
        relay = null
    }

    /**
     * JNI 入口。异常一律就地兜住。
     *
     * 这条边界上没有「让它抛出去」这个选项：`handle` 是被 C++ 调的，异常从 JNI 冒出去最坏的情况
     * 是带走整个 `:js` 进程，而脚本作者看到的会是「这条源解不开」。
     */
    @JvmStatic
    fun handle(api: String, argsJson: String): String = handleReply(api, argsJson).let {
        JsProtocol.encodeHostReply(it.ok, it.data, it.error)
    }

    private fun handleReply(api: String, argsJson: String): HostReply = runCatching {
        val capability = JsHostApi.entries.firstOrNull { it.name == api }
            ?: return@runCatching HostReply(false, null, "沙箱里没有「$api」这个能力")
        when (capability.target) {
            JsApiTarget.COMPUTE -> HostCompute.invoke(api, Json.parseToJsonElement(argsJson))
            JsApiTarget.HOST -> relay?.invoke(api, argsJson)
                ?: HostReply(false, null, "执行器尚未接上主进程，$api 这类能力暂时不可用")
        }
    }.getOrElse {
        // 走到这里只剩三种可能：argsJson 不是合法 JSON、内核 OOM、compute 实现自己抛了。
        // 三种都值得让脚本看见原文，因为它们的处方完全不同（分别是「规则写坏了」「源太重」「本仓有缺陷」）
        HostReply(false, null, "沙箱侧处理失败：${it.message?.take(120) ?: it.javaClass.simpleName}")
    }
}
```

`HostCompute.invoke` 的入参是 `JSON.stringify(argList)` 解出来的**数组**，与 Task 4 里 `fun invoke(api: String, args: JsonElement?): JsonElement` 的契约一致；arity 由 JS 垫片判、名字由这里判，两件事各自一处。

- [x] **Step 5: 写进程内的执行入口**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsRuntimeBridge.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.SandboxProtocolException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 内核的执行入口：句柄生命周期、描述符解码、失败之后的重置。
 *
 * 与 [JsSandboxClient] 的分工是**一进程一侧**：客户端管跨进程（连接、串行化、重放、断连），
 * 这一层管进程内（runtime、context、四项限值、描述符）。两者都不认识规则语法——
 * 那是 `lib_book_source` 解释器的事。
 *
 * 不是线程安全的，也不需要是：Task 8 的执行器只有一个工作线程，而 `JsSandboxClient` 已经把
 * `execute` 串行化了。两处任一放开并发，这里的 `handle` 就得改成锁保护。
 */
internal class JsRuntimeBridge(private val limits: JsLimits = JsLimits()) {

    /**
     * 桥接层描述符（Task 7 开头）。字段全有默认值，解码不开 `ignoreUnknownKeys`：
     * 生产者与消费者出自同一次构建，这里出现未知键就是两侧不同步，那种错位必须当场响。
     */
    @Serializable
    private data class NativeDescriptor(
        val kind: String,
        val timedOut: Boolean = false,
        val errorName: String = "",
        val errorMessage: String = "",
        val data: JsonElement? = null,
    )

    private var handle = 0L

    /** 供 Task 8 的 `PING` 用：不建 runtime，只回答「.so 在不在」 */
    val isReady: Boolean get() = QuickJsNative.loaded

    fun evaluate(invocation: JsInvocation, deadlineMonoMs: Long): JsOutcome {
        if (!QuickJsNative.loaded) {
            return JsOutcome(
                JsStatus.UNAVAILABLE,
                error = "libebook_js.so 未加载（缺本设备的 ABI，或 .so 与 Kotlin 不同步）",
            )
        }
        if (!ensureRuntime()) {
            return JsOutcome(JsStatus.UNAVAILABLE, error = "沙箱 runtime 创建失败（堆上限 ${limits.heapBytes} 字节）")
        }
        val descriptor = QuickJsNative.nativeEval(
            handle,
            bindingsJson(invocation.bindings),
            // 垫片与绑定落在全局量上之后才是用户脚本；两段同一份文本一次求值，省一次 context 往返
            QuickJsPrelude.BOOTSTRAP + "\n" + invocation.source,
            deadlineMonoMs,
        )
        val parsed = runCatching { Json.decodeFromString(NativeDescriptor.serializer(), descriptor) }
            .getOrElse { throw SandboxProtocolException("桥接层描述符形态不符：${descriptor.take(120)}", it) }
        val status = JsProtocol.mapStatus(parsed.kind, parsed.errorName, parsed.errorMessage, parsed.timedOut)
        if (status == JsStatus.TIMEOUT || status == JsStatus.MEMORY || status == JsStatus.STACK) reset()
        return JsOutcome(
            status,
            parsed.data.takeIf { status == JsStatus.OK },
            if (status == JsStatus.OK) null else parsed.errorMessage.ifBlank { "脚本以 ${parsed.errorName} 结束" },
        )
    }

    /**
     * 任务边界的强制重置（ADR-0028 决策 5）。
     *
     * 超时、堆耗尽、栈耗尽之后，context 自身的状态内核都不再保证：复用它可能让下一条规则拿到
     * 上一条的残留，或者在「栈已经耗到边界」的姿势上以同样的方式失败——那种失败看起来完全像
     * 「这条规则自己有问题」，而真话是它借用了别人的残局。重建的代价是一次 context 分配，
     * 摊在一次的正文请求旁边不构成瓶颈。
     */
    fun reset() {
        if (handle == 0L) return
        if (!QuickJsNative.nativeReset(handle, QuickJsPrelude.SOURCE)) {
            // 重建失败就是整个 runtime 不能用了：销毁并在下次调用时新建，不带着坏 context 继续跑
            QuickJsNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    fun close() {
        if (handle != 0L) {
            QuickJsNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun ensureRuntime(): Boolean {
        if (handle != 0L) return true
        handle = QuickJsNative.nativeCreate(limits.heapBytes, limits.stackBytes)
        if (handle == 0L) return false
        if (QuickJsNative.nativeReset(handle, QuickJsPrelude.SOURCE)) return true
        QuickJsNative.nativeDestroy(handle)
        handle = 0L
        // 垫片随包冻结、永不来自书源：装不上就是本仓的缺陷，抛出去，不伪装成「这条规则解不开」
        throw SandboxProtocolException("沙箱垫片装载失败：QuickJsPrelude 与 js_bridge 不匹配")
    }

    /**
     * 绑定以 **JSON 值**过边界，脚本侧由 `__bind` 落成全局量。
     *
     * 值为 null 的绑定直接不放进对象：垫片的 `__bind` 把「属性不存在」与「属性是 null」都落成
     * `undefined`，这样脚本里的 `typeof result` 判空才真的生效。
     */
    private fun bindingsJson(bindings: Map<String, String?>): String = buildJsonObject {
        bindings.forEach { (key, value) -> if (value != null) put(key, JsonPrimitive(value)) }
    }.toString()
}
```

`buildJsonObject` 的 `put` 收的是 `String`/`Number`/`Boolean` 重载，`JsonPrimitive(value)` 走的是 `JsonElement` 那条——两者产物相同，取后者是为了不依赖 DSL 的隐式转换。`JsonElement.toString()` 由内核序列化器自己产出合法 JSON，这里不需要再持一个 `Json` 实例去 encode。

- [x] **Step 6: 登记 JNI 反射面的混淆规则**

在 `lib_book_source/consumer-rules.pro` 末尾追加（AGP 默认规则已保留所有带 `native` 修饰符的方法，故 `QuickJsNative` 不用写）：

```pro
# JNI 的反射面：js_bridge.cpp 用 FindClass + GetStaticMethodID 按名字与签名找 handle，
# 这种以字符串常量写死的引用 R8 看不见。不 keep 的后果不是崩溃而是执行器一起来就
# 「host 回调：主进程入口未就绪」，且只出现在开了混淆的 release 包上。
-keep class com.ebook.source.sandbox.HostDispatcher { *; }
```

- [x] **Step 7: 写真机侧的桥接层用例**

先在 `lib_book_source/build.gradle.kts` 补 instrumented 测试的依赖与 runner：`android { defaultConfig { } }` 里加
`testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`，`dependencies { }` 里加两条
`androidTestImplementation`——`libs.androidx.test.ext.junit`（本任务的 `AndroidJUnit4`）与
`libs.androidx.test.core`（Task 8 的 `SandboxConnectionTest` 要用 `ApplicationProvider` 拿 Context 去
`bindService`，本模块原先没有 androidTest 依赖，一次补齐免得下个任务再回来改同一个文件）。

创建 `lib_book_source/src/androidTest/java/com/ebook/source/sandbox/QuickJsBridgeTest.kt`：

```kotlin
package com.ebook.source.sandbox

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 桥接层的真机用例：这一层是本计划唯一「JVM 上跑不了」的部分，所以它锁住的全是**只能在设备上暴露**的坑——
 * JNI 边界的编码方向、内核中断器与栈/堆限值的真实行为、`__host_call` 的往返通路、context 重置的隔离性。
 *
 * 协议、限值、白名单表、纯计算实现都不在这里测（Task 3/4 已用 JVM 单测锁住，那里能测得更好、更快）。
 */
@RunWith(AndroidJUnit4::class)
class QuickJsBridgeTest {

    private fun bridge() = JsRuntimeBridge()

    private fun seg(source: String, vararg bindings: Pair<String, String?>) =
        JsInvocation(JsMode.SEGMENT, source, mapOf(*bindings))

    private fun deadlineAfter(ms: Long) = SystemClock.elapsedRealtime() + ms

    @Test
    fun 字符串字面量能原样回来() {
        assumeTrue(QuickJsNative.loaded)
        val outcome = bridge().evaluate(seg("result = '第一章 风起了'"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("第一章 风起了", outcome.text)
    }

    @Test
    fun 增补平面字符进出都要原样回来() {
        // 锁住 JNI 字符串的双向编码：入向走 getBytes("UTF-8")、出向走 cesu8 + NewStringUTF。
        // 任一方向走错，𠀋 与 emoji 都会变成 ? 或替换字符，而这是「正文偶尔乱码」级别、
        // 只在真机上出现的症状，JVM 单测永远抓不到
        assumeTrue(QuickJsNative.loaded)
        val text = "生僻字 𠀋 与 emoji 😀 混排"
        val outcome = bridge().evaluate(seg("result = '$text'"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals(text, outcome.text)

        val bound = bridge().evaluate(seg("result = result", "result" to text), deadlineAfter(5_000))
        assertEquals(text, bound.text)
    }

    @Test
    fun 垫片里的纯计算能力走通一次进程内回调() {
        assumeTrue(QuickJsNative.loaded)
        val outcome = bridge().evaluate(seg("result = java.md5('abc')"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", outcome.text)
    }

    @Test
    fun 忘给 result 赋值时拿到的是没有值而不是词 undefined() {
        assumeTrue(QuickJsNative.loaded)
        val outcome = bridge().evaluate(seg("var ignored = 1 + 1"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertNull(outcome.data)
        assertNull(outcome.text)
    }

    @Test
    fun 死循环在挂钟期限内被打断成超时且下一个任务仍然可用() {
        assumeTrue(QuickJsNative.loaded)
        val started = SystemClock.elapsedRealtime()
        val bridge = bridge()
        val outcome = bridge.evaluate(seg("while (true) {}"), deadlineAfter(400))
        assertEquals(JsStatus.TIMEOUT, outcome.status)
        // 中断发生在解释器轮询点：400 毫秒的期限不该跑成 5 秒；下界锁的是「没等到期就返回」，
        // 那是期限算错（比如两边时钟口径不同）的症状
        val elapsed = SystemClock.elapsedRealtime() - started
        assertTrue("实际耗时 ${elapsed}ms", elapsed in 300..3_000)
        // 同一个实例续跑：超时后 evaluate 内部会重置 context，下一条规则拿到的必须是新值。
        // 换成新建 bridge 就测不到这件事——那是一个干净的 runtime，本来就一定能跑
        val next = bridge.evaluate(seg("result = 'ok'"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, next.status)
        assertEquals("ok", next.text)
    }

    @Test
    fun 深递归以栈耗尽结束而不是撞掉进程() {
        assumeTrue(QuickJsNative.loaded)
        val outcome = bridge().evaluate(seg("function f(n) { return f(n + 1) + 1 } result = f(1)"), deadlineAfter(5_000))
        assertEquals(JsStatus.STACK, outcome.status)
    }

    @Test
    fun 堆超限按内存失败而不是把进程撞没() {
        assumeTrue(QuickJsNative.loaded)
        val outcome = bridge().evaluate(
            seg("var s = ''; while (true) { s += new Array(1024).join('x'); if (s.length > 1e12) break } result = s"),
            deadlineAfter(5_000),
        )
        assertEquals(JsStatus.MEMORY, outcome.status)
    }

    @Test
    fun 语法错误归语法而不是运行时() {
        assumeTrue(QuickJsNative.loaded)
        val outcome = bridge().evaluate(seg("result = "), deadlineAfter(5_000))
        assertEquals(JsStatus.SYNTAX, outcome.status)
    }

    @Test
    fun 绕过垫片直呼白名单外的能力会被就地拒绝() {
        assumeTrue(QuickJsNative.loaded)
        // __host_call 是脚本可见的全局属性，任何 js 源都能直呼它——所以白名单必须在 Kotlin 再判一次
        val outcome = bridge().evaluate(seg("result = JSON.stringify(__host_call('cache', '[]'))"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertTrue(outcome.text!!.contains("\"ok\":false"))
        assertTrue(outcome.text!!.contains("cache"))
    }

    @Test
    fun 明确拒绝的能力归不支持而不是运行时错误() {
        assumeTrue(QuickJsNative.loaded)
        val outcome = bridge().evaluate(seg("java.cookieManager('a')"), deadlineAfter(5_000))
        assertEquals(JsStatus.UNSUPPORTED_API, outcome.status)
        assertTrue(outcome.error!!.contains("cookieManager"))
    }

    @Test
    fun 返回 Promise 的脚本按不支持处置而不是折叠成空值() {
        assumeTrue(QuickJsNative.loaded)
        val outcome = bridge().evaluate(seg("result = Promise.resolve('x')"), deadlineAfter(5_000))
        assertEquals(JsStatus.UNSUPPORTED_API, outcome.status)
    }

    @Test
    fun 任务边界的重置清掉上一个任务留下的全局量() {
        assumeTrue(QuickJsNative.loaded)
        val bridge = bridge()
        val first = bridge.evaluate(seg("globalThis.leftover = 1"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, first.status)
        // 成功结束不触发自动重置，所以这里显式走一次任务边界——Task 8 的执行器在每次
        // execute 的开头做的就是这个动作
        bridge.reset()
        val second = bridge.evaluate(seg("result = typeof leftover"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, second.status)
        assertEquals("undefined", second.text)
        // 同一个实例还能跑：reset 换的是 context，runtime 与它的四项限值都留着
        assertTrue(bridge.isReady)
    }
}
```

- [x] **Step 7b: 把「桥未装载」这一支放回 JVM**

`.so` 缺失时的行为是**在 JVM 上真实成立**的：`System.loadLibrary("ebook_js")` 在单元测试的虚拟机里必然抛 `UnsatisfiedLinkError`，被 `QuickJsNative.loaded` 的 `runCatching` 吃成 `false`——于是这一支不需要伪造、不需要 Robolectric，也不需要设备。反过来，它在真机上永远走不到（`.so` 随包存在），这就是它原先被写成一条名实不符的重复用例的根因。

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/JsRuntimeBridgeTest.kt`：

```kotlin
package com.ebook.source.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「桥接层不可用」必须是一个有名字的结果，而不是一次崩溃。
 *
 * 这条只能在 JVM 单测里锁：单元测试的虚拟机里没有 `libebook_js.so`，`QuickJsNative.loaded`
 * 因此货真价实地是 false——不是伪造出来的替身。真机上 `.so` 随包存在，这一支永远走不到。
 */
class JsRuntimeBridgeTest {

    @Test
    fun `桥未装载时回不可用而不是抛出 UnsatisfiedLinkError`() {
        assertFalse(QuickJsNative.loaded)
        val outcome = JsRuntimeBridge().evaluate(
            JsInvocation(JsMode.SEGMENT, "result = 1", emptyMap()),
            deadlineMonoMs = 1_000L,
        )
        assertEquals(JsStatus.UNAVAILABLE, outcome.status)
        // 错误文案要能指到根因（缺 ABI / .so 与 Kotlin 不同步），只写「不可用」等于把
        // 「用户被支去重导一条本来好的源」的下一步省给了读日志的人
        assertTrue(outcome.error!!.contains("libebook_js.so"))
    }
}
```

构造 `JsRuntimeBridge()` 不碰 native：`limits` 只是数据，`handle` 到 `ensureRuntime()` 才第一次调 `nativeCreate`，而 `evaluate` 在 `loaded == false` 时当场返回，一次 `external` 调用都不发出。

真机用例里多数桥是各建一个：句柄属于 runtime，跨用例复用会把上一条规则的栈/堆残局带进下一条，而第 6、7 例（深递归、堆超限）想证明的正是**单条规则**耗尽限值时的行为。只有两例刻意共用同一个实例——`死循环…下一个任务仍然可用` 与 `任务边界的重置清掉上一个任务留下的全局量`：这两件事（超时后的原地重置、context 重建后 runtime 仍在）换一个新实例就自动成立，测出来的是假绿。测试类里没有 `@Before` 装配 Hilt：桥接层零依赖，不需要注入。

- [x] **Step 8: 跑一遍能跑的部分**

> **执行记录（2026-09-09）**：`testDebugUnitTest` 全绿；`assembleDebug` 与 `assembleDebugAndroidTest`
> 均 `BUILD SUCCESSFUL`（androidTest APK 已落 `lib_book_source/build/outputs/apk/androidTest/debug/`），
> 且强制重编 `compileDebugKotlin` / `compileDebugUnitTestKotlin` / `compileDebugAndroidTestKotlin`
> 三套源零 `w:` 警告。`connectedDebugAndroidTest` 的 12 例需要设备，本 Agent 未跑，
> 已落在 `docs/test-coverage-todo.md` 的装机清单。

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: 全绿，含 Step 7b 新增的 `JsRuntimeBridgeTest`（1 例）。JVM 侧跑不到内核求值——`nativeEval` 在 `loaded == false` 时根本不被调用，所以这一层的其余行为只能等 Step 8 末的真机跑。新增的 `androidTestImplementation` 与 runner 配置不影响这个任务。

Run: `./gradlew :lib_book_source:assembleDebug :lib_book_source:assembleDebugAndroidTest`
Expected: 两个都 `BUILD SUCCESSFUL`。`androidTest` 的 APK 编出来只证明测试源可编译，**用例本身要人连设备跑**：

Run: `./gradlew :lib_book_source:connectedDebugAndroidTest`
Expected: 12 例全绿。**这一条 Agent 无法代劳**（需要设备或模拟器），交给装机验证；重点是 `增补平面字符进出都要原样回来` 与 `死循环在挂钟期限内被打断成超时且下一个任务仍然可用` 两例——它们锁的是只在真机上暴露的两个坑。

- [ ] **Step 9: 提交（需授权）**

```bash
git add lib_book_source/src/main/cpp/bridge/js_bridge.cpp \
        lib_book_source/src/main/java/com/ebook/source/sandbox/QuickJsNative.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/HostDispatcher.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsRuntimeBridge.kt \
        lib_book_source/src/test/java/com/ebook/source/sandbox/JsRuntimeBridgeTest.kt \
        lib_book_source/src/androidTest/java/com/ebook/source/sandbox/QuickJsBridgeTest.kt \
        lib_book_source/consumer-rules.pro lib_book_source/build.gradle.kts \
        lib_book_source/src/test/java/com/ebook/source/sandbox/JsProtocolTest.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsProtocol.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 接入 QuickJS 桥接层与进程内执行入口

C++ 只负责跑脚本与产出描述符，白名单在 Kotlin 侧判第二次；
入向编码走 getBytes("UTF-8")、出向走 cesu8，锁住增补平面字符的往返。
EOF
)"
```

---

### Task 8：执行器进程（`SandboxService` + `BinderJsChannel` + 连接器 + 进程门）

到这里为止，内核能在一个进程里跑脚本，主进程知道怎么跟一条抽象通道说话。Task 8 把两者接起来：**`:js` 是一个隔离进程，它唯一的职责是「拿一帧、跑一次、回一帧」**。

五条事实决定形状，写代码前先读完。前两条是查出来的，不是推测的——它们各自否决一种看起来更省事的写法：

1. **隔离进程没有网络、也读不到本应用的数据目录**。`ActivityThread.handleBindApplication` 里的原话（本机 SDK：`sources/android-35/android/app/ActivityThread.java:7413`）：

   > `// For backward compatibility, TrafficStats needs static access to the application context.`
   > `// But for isolated apps which cannot access network related services, service discovery`
   > `// is restricted. Hence, calling this would result in NPE.`

   同处还有 `if (!Process.isIsolated()) { setupGraphicsSupport(appContext) }`（:7395）。**这不是「建议不要」，是能力边界**：执行器里发不出去的请求不是 bug，所以取文一律经 host 回调让主进程代做（Task 9），执行器里也不许碰 SharedPreferences、Room、`filesDir`。反过来，这条边界正是选隔离进程的理由——脚本再也拿不到用户 token、本机书架与任何私有文件。

2. **App 的 `Application` 类会在隔离进程里被实例化**。`handleBindApplication` 无条件调 `data.info.makeApplicationInner(data.restrictedBackupMode, null)`（:7458），**外面没有 `isIsolated()` 的判断**——框架只在隔离进程上跳过 `setupGraphicsSupport`、`TrafficStats.init` 与字体预加载（:7395/:7416/:7518）。所以 `MyApplication` 与 `BookApplication` 的启动逻辑必须在自己的开头自己判、自己跳过，框架不会替我们做（Step 8 的进程门）。判据取 `Process.isIsolated()`（本机 SDK `android-stubs-src.jar` 里它是公开方法，`data/api-versions.xml:52543` 记 `since="28"`）而不是比进程名字符串：进程名由系统生成、随版本变，而「是不是隔离的」正是我们要问的那件事；**判错的代价也不对称**——名字比对万一在主进程误判，产出的是「整个 App 起来但没初始化」，比沙箱多跑几行初始化严重得多。一个约束随之而来：`since="28"` 而本仓 minSdk 26，**直接调会在 26/27 上 NoSuchMethodError**，所以判据外面必须套 `Build.VERSION.SDK_INT` 门槛，且这个门槛只写一处（Step 6 的 `SandboxProcess`，四个调用点共用）——各写一遍就会漏写一遍，漏一处就是 26 设备上沙箱进程起来即崩。

3. **回调不换线程，也不构成死锁**。`__host_call` 在 C++ 里是同步的，`HostDispatcher` 的 relay 也在**同一条 binder 线程**上把事务发回主进程；主进程的那一侧由**它自己的 binder 线程池**（默认至多 15 条）受理，不会去抢那条已经阻塞在 `transact` 里的调用线程。这条链路能跑通的前提就是「不用主线程调沙箱」——Task 6 的主线程守卫守的正是它。

4. **`.so` 能不能在隔离进程里加载，是本计划能否成立的前提，而它无法在 JVM 上验证**。按目录权限推断可以（`/data/app/…/lib/<abi>/` 与 APK 同标签、全局可读，Chrome 的 `sandboxed_process0` 就是这么加载 `libchrome` 的），但推断不是证据：**Step 9 的连通性用例就是这个前提的验证**，它排在策略代码之后、接线之前。若它失败，退路是去掉 `android:isolatedProcess` 只保留 `android:process=":js"`——那等于回 ADR-0028 重议决策 2，必须先改 ADR 再改代码，不许就地换实现。

5. **报文闸门放在「写响应帧之前」，所以它是执行器侧的事**。C++ 拿到 `data` 时并不知道自己会占多少 binder 缓冲（限值住在 Kotlin 侧），而执行器在编码回帧的那一刻既拿到了完整帧、又还没把它交出去——判在这里，超限时才只多花一次 stringify、且超限原文永远不会进主进程。

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxTaskRunner.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxProcess.kt`（隔离判据的唯一公开入口：Step 6 的 `onBind` 自检、Step 8 的两道进程门、Step 9 的设备断言共用它）
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxService.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/BinderJsChannel.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxConnector.kt`
- Create: `lib_book_source/src/main/AndroidManifest.xml`（当前只有一行 `<manifest />`）
- Modify: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsLimits.kt`（补 `bindTimeoutMs`）
- Modify: `lib_book_common/src/main/java/com/ebook/common/BookApplication.kt`（进程门）
- Modify: `module_app/src/main/java/com/ebook/MyApplication.kt`（进程门 + 去掉 Application 上的 eager 注入）
- Create: `lib_book_source/src/test/java/com/ebook/source/sandbox/SandboxTaskRunnerTest.kt`
- Create: `lib_book_source/src/androidTest/java/com/ebook/source/sandbox/SandboxConnectionTest.kt`

- [x] **Step 1: 先写策略层的失败测试**

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/SandboxTaskRunnerTest.kt`：

```kotlin
package com.ebook.source.sandbox

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行器侧「一帧进、一帧出」的裁决。
 *
 * 这一层独立存在的唯一理由：`Parcel` 与 `Binder` 在 JVM 单测里是「Method ... not mocked」的桩，
 * 于是真正会咬人的三件事——期限已过还进不进内核、回帧超限时给不给原文、任务边界是不是每次都过——
 * 若写在 `onTransact` 里就只有连上设备才看得见。分出来之后它们各有名字、各自可测。
 */
class SandboxTaskRunnerTest {

    private var now = 10_000L
    private val events = mutableListOf<String>()
    private var outcome = JsOutcome(JsStatus.OK, stringJson("第一章"))

    private fun stringJson(s: String): JsonElement =
        Json.parseToJsonElement(Json.encodeToString(String.serializer(), s))

    private fun request(deadline: Long) =
        JsProtocol.encodeRequest(JsInvocation(JsMode.SEGMENT, "result = 1", emptyMap()), deadline)

    private fun run(
        frame: String?,
        limits: JsLimits = JsLimits(),
        eval: (JsInvocation, Long) -> JsOutcome = { _, _ -> events += "eval"; outcome },
    ): String = runSandboxTask(
        requestFrame = frame,
        limits = limits,
        now = { now },
        eval = eval,
        onTaskBoundary = { events += "boundary" },
    )

    @Test
    fun `期限已过时根本不进内核`() {
        val decoded = JsProtocol.decodeOutcome(run(request(deadline = now - 1)))
        assertEquals(JsStatus.TIMEOUT, decoded.status)
        assertTrue("排队到期限之后的任务再进内核，抢的是下一条规则的时限", events.isEmpty())
        assertTrue(decoded.error!!.contains("未进内核"))
    }

    @Test
    fun `请求帧解不出来时回一帧读得出来的失败而不是把异常穿过 binder`() {
        // 少字段的帧：decodeRequest 会抛 SandboxProtocolException
        val frame = run("""{"mode":"SEGMENT","source":"x"}""")
        val decoded = JsProtocol.decodeOutcome(frame)
        assertEquals(JsStatus.UNAVAILABLE, decoded.status)
        assertTrue("穿过 binder 的异常到对面只剩一句 DeadObjectException", decoded.error!!.contains("协议不合"))
    }

    @Test
    fun `事务里没有请求帧时回不可用而不是空指针`() {
        val decoded = JsProtocol.decodeOutcome(run(null))
        assertEquals(JsStatus.UNAVAILABLE, decoded.status)
        assertTrue(decoded.error!!.contains("请求帧"))
    }

    @Test
    fun `回帧超上限时只回一句太大，不带超限原文`() {
        outcome = JsOutcome(JsStatus.OK, stringJson("字".repeat(4_000)))
        val limits = JsLimits(maxOutcomeBytes = 1_000)
        val frame = run(request(now + 5_000), limits)
        val decoded = JsProtocol.decodeOutcome(frame, limits)
        assertEquals(JsStatus.TOO_LARGE, decoded.status)
        assertFalse("原文一旦进了回帧，主进程就得先解它一遍才知道要丢", frame.contains("字字字"))
        assertTrue(frame.contains("字节"))
    }

    @Test
    fun `回帧在上限内时状态与文本原样带回`() {
        val decoded = JsProtocol.decodeOutcome(run(request(now + 5_000)))
        assertEquals(JsStatus.OK, decoded.status)
        assertEquals("第一章", decoded.text)
    }

    @Test
    fun `每个任务的最开头都过一次任务边界`() {
        run(request(now + 5_000))
        run(request(now + 5_000))
        assertEquals(listOf("boundary", "eval", "boundary", "eval"), events)
    }

    @Test
    fun `内核抛出未类型化异常时换成一帧不可用，绝不让它穿过 binder`() {
        val frame = run(request(now + 5_000), eval = { _, _ -> throw IllegalStateException("描述符坏掉") })
        val decoded = JsProtocol.decodeOutcome(frame)
        assertEquals(JsStatus.UNAVAILABLE, decoded.status)
        assertTrue(decoded.error!!.contains("描述符坏掉"))
    }

    @Test
    fun `内核回的失败状态原样编码，不折叠成 RUNTIME`() {
        for (status in listOf(JsStatus.SYNTAX, JsStatus.MEMORY, JsStatus.STACK, JsStatus.UNSUPPORTED_API)) {
            outcome = JsOutcome(status, error = "细节")
            val decoded = JsProtocol.decodeOutcome(run(request(now + 5_000)))
            assertEquals(status, decoded.status)
            assertEquals("细节", decoded.error)
        }
    }
}
```

最后两例看着像重复，各锁一件事：倒数第二例锁「执行器不许把没见过的失败折叠成 `RUNTIME`」（那是把内核的真话改掉），最后一例锁「状态是透传的，`error` 也是」。合起来才是「主进程看到的失败与内核给出的失败是同一句话」。

- [x] **Step 2: 跑一遍确认它失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*SandboxTaskRunnerTest*"`
Expected: **编译失败**（`runSandboxTask` 未定义）。这是本步的预期红。

- [x] **Step 3: 写策略层**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxTaskRunner.kt`：

```kotlin
package com.ebook.source.sandbox

/**
 * 执行器侧一次事务的裁决：进不进内核、回什么帧、什么时候走任务边界。
 *
 * 参数取两个函数（[eval]、[onTaskBoundary]）而不是一个 `JsRuntimeBridge`，为的是让这一层
 * 在内核不在场时也能被完整测到——真机上「内核抛出未类型化异常」这种路径造不出来，
 * 在这里只是一个 lambda。
 *
 * [requestFrame] 可空是照搬 `Parcel.readString()` 的形态：对端写空是合法的，
 * 这一层必须给出一种有名字的失败，而不是在解引用处抛 NPE。
 */
internal fun runSandboxTask(
    requestFrame: String?,
    limits: JsLimits,
    now: () -> Long,
    eval: (JsInvocation, Long) -> JsOutcome,
    onTaskBoundary: () -> Unit,
): String {
    if (requestFrame == null) {
        return JsProtocol.encodeOutcome(
            JsOutcome(JsStatus.UNAVAILABLE, error = "execute 事务里没有请求帧"),
        )
    }
    val request = runCatching { JsProtocol.decodeRequest(requestFrame) }.getOrElse {
        // 请求帧是主进程自己编的，解不出来就是两侧不是同一次构建。
        // 抛出去只会让主进程收到一句事务失败，真话留在被丢掉的栈里，所以换成一帧读得出来的失败
        return JsProtocol.encodeOutcome(
            JsOutcome(JsStatus.UNAVAILABLE, error = "沙箱与主进程协议不合：请求帧解不出来"),
        )
    }
    if (now() >= request.deadlineMonoMs) {
        return JsProtocol.encodeOutcome(
            JsOutcome(JsStatus.TIMEOUT, error = "排队到期限之后才开始执行（本次未进内核）"),
        )
    }
    // 边界放在每一次执行的最前面：这样「上一个任务留下了什么」根本不构成问题，
    // 不需要记住哪些失败要清、哪些不用（那是最容易漏一条的写法）
    onTaskBoundary()
    val outcome = runCatching { eval(request.invocation, request.deadlineMonoMs) }.getOrElse { error ->
        return JsProtocol.encodeOutcome(
            JsOutcome(
                JsStatus.UNAVAILABLE,
                error = "执行器侧失败：${error.message?.take(120) ?: error.javaClass.simpleName}",
            ),
        )
    }
    return gateOutcomeSize(outcome, limits)
}

/**
 * 回帧大小闸门。
 *
 * 超限时多付一次 stringify（编码完再丢），换到的是「闸门只有一处」与「超限原文绝不进主进程」。
 * 这里防的是 binder 事务缓冲与主进程的内存，**不防执行器自己**：执行器由 8 MB 堆限兜住，
 * 所以 `heapBytes` 绝不能跟着帧上限一起放大——那样超限前先在执行器里堆出一份巨串，
 * 而现场只会看到 `:js` 进程没。
 */
private fun gateOutcomeSize(outcome: JsOutcome, limits: JsLimits): String {
    val frame = JsProtocol.encodeOutcome(outcome)
    val bytes = utf8Length(frame)
    if (bytes <= limits.maxOutcomeBytes) return frame
    return JsProtocol.encodeOutcome(
        JsOutcome(
            JsStatus.TOO_LARGE,
            error = "沙箱响应超出 ${limits.maxOutcomeBytes} 字节上限（实际 $bytes 字节）",
        ),
    )
}
```

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*SandboxTaskRunnerTest*"`
Expected: 8 例全绿。

- [x] **Step 4: 给限值补一个绑定等待上限**

`JsSandboxConnector`（Step 7）要等一次异步的 `bindService` 握手，而那个等待既不属于「脚本执行时间」也不属于「报文大小」。在 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsLimits.kt` 的 `maxRequestsPerTask` 之前插入：

```kotlin
    /**
     * 等 `:js` 进程连上的上限。单列一个数是因为它跟挂钟期限不是一回事：
     * 绑定失败要**尽快**报不可用（好让调用方走「这条源今天解不出来」的话术），
     * 而不是让一次冷启动的进程创建吃掉整条规则的 5 秒预算。
     */
    val bindTimeoutMs: Long = 2_000L,
```

- [x] **Step 5: 声明隔离服务**

`lib_book_source/src/main/AndroidManifest.xml` 现在只有一行 `<manifest />`，整文件替换为：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  沙箱执行器（ADR-0028）。写在库清单里而不是 module_app：整条沙箱链路的组成件应当随模块走，
  应用模块不需要知道 :js 进程存在。合并结果要到 module_app/build/intermediates/merged_manifests/
  下核对——isolatedProcess 这类属性不会被库清单"默默丢掉"，但 process 名字若与别的模块撞了
  只能从合并结果里看出来。
-->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
        <!--
          isolatedProcess：进程无网络、读不到本应用数据目录（见 ActivityThread.handleBindApplication
          对 Process.isIsolated() 的三处跳过），因此脚本能碰到的东西以内核为限。
          exported=false：只有本应用自己绑得到，而协议只有一对方法、没有任何鉴权。
          只绑不启：隔离服务不支持 startService，用 Context.bindService + BIND_AUTO_CREATE 拉起。
        -->
        <service
            android:name="com.ebook.source.sandbox.SandboxService"
            android:exported="false"
            android:isolatedProcess="true"
            android:process=":js" />
    </application>
</manifest>
```

Run: `./gradlew :module_app:assembleDebug`
Expected: `BUILD SUCCESSFUL`。随后核对合并结果：

Run: `grep -A3 "SandboxService" module_app/build/intermediates/merged_manifests/debug/AndroidManifest.xml`
Expected: 三条属性（`android:process=":js"`、`android:isolatedProcess="true"`、`android:exported="false"`）都在，`android:name` 未被改名。

- [x] **Step 6: 写执行器服务**

这一步落两个文件：`SandboxProcess.kt`（隔离判据的唯一入口）与 `SandboxService.kt`（`:js` 里唯一的组件）。
先写前者，因为后者的 `onBind` 要用它。

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxProcess.kt`：

```kotlin
package com.ebook.source.sandbox

import android.os.Build
import android.os.Process

/**
 * 「当前进程是不是被系统隔离的沙箱进程」的唯一入口。
 *
 * 单独一个文件是因为判据有四个调用点、分属三个模块（这里的执行器自检、Step 8 的 `BookApplication`
 * 与 `MyApplication` 进程门、Step 9 的设备用例）。`API 28` 门槛写四遍，漏一遍就是 minSdk 26 的设备上
 * 一句 `NoSuchMethodError`；写成 public 对象是因为 `internal` 跨不到别的模块（AGENTS.md 的可见性口径：
 * 依赖方的 source set 不是 friend module）。
 *
 * 短路的次序是刻意的：`SDK_INT` 在前、`Process.isIsolated()` 在后。后者在 API 28 以下不存在，
 * 顺序写反等于在 26/27 的设备上第一次调用即崩。`SDK_INT` 是静态字段，在 JVM 单测里读作 0，
 * 于是整条判据在单测里恒为 false——**不会**去碰 mockable jar 里那些「Method … not mocked」的桩，
 * Step 8 改过的 `Application` 因此仍能在 JVM 上被实例化。
 */
object SandboxProcess {

    /** true 只可能出现在 manifest 上带 `android:isolatedProcess="true"` 的进程里 */
    val isInIsolatedProcess: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Process.isIsolated()
}
```

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxService.kt`：

```kotlin
package com.ebook.source.sandbox

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.xrn1997.common.util.Logger

/**
 * `:js` 隔离进程里唯一的组件：一次 binder 事务换成一次内核执行。
 *
 * 它刻意薄到只有三件事：读 Parcel、把 [runSandboxTask] 的判断结果写回 Parcel、把 host 调用
 * 中继给主进程。策略在 [runSandboxTask]（JVM 可测），内核在 `JsRuntimeBridge`（真机可测），
 * 三者不混在 `onTransact` 里——混在一起的后果是那两条真正会咬人的路径只能在设备上碰运气。
 *
 * **不抛异常出去**：`onTransact` 抛出的东西到了主进程只剩一次事务失败，真话（版本不合、
 * 帧坏掉、内核炸了）留在被丢掉的栈里。所以每一条出口都必须是 `[runSandboxTask]` 那样的帧。
 *
 * 线程模型：binder 有自己的线程池（默认至多 15 条），**同时只有一帧在飞的保证来自
 * `JsSandboxClient` 的 `inFlight` 锁（Task 6），不是来自这里**。那是 [currentCallback]
 * 能用一个 ThreadLocal 而不用队列 ID 的前提——两侧任一放开并发，这里就得改成按事务记账。
 * host 回调发生在发起它的那条 binder 线程上（`nativeEval` 里同步 transact），
 * 所以 ThreadLocal 读得到；主进程那一侧由它自己的线程池受理，不会与阻塞中的调用线程相抢。
 */
class SandboxService : Service() {

    private val tag = "SandboxService"
    private val limits = JsLimits()
    private val bridge = JsRuntimeBridge(limits)

    /** 本次 execute 带回主进程的回调通道；只在 onTransact 期间有值 */
    private val currentCallback = ThreadLocal<IBinder?>()

    private val executor = object : Binder() {

        /**
         * binder 的管家事务必须在 token 检查**之前**分诊出去。
         *
         * `DUMP_TRANSACTION`、`INTERFACE_TRANSACTION` 的数据里不带我们的接口标识，先 `enforceInterface`
         * 就会把它们一起拒掉；而本机 SDK 里 `Binder.onTransact` 的默认实现是处理这两个码的
         * （`sources/android-35/android/os/Binder.java:1012-1032`）。覆写而不转 `super`，等于顺手丢掉
         * `dumpsys` 与 `queryLocalInterface`——前者是排查沙箱时唯一能从进程外看到它的入口。
         *
         * `PING_TRANSACTION` 相反：Java 侧的默认实现**不**处理它，`pingBinder()` 拿到的就是子类的返回值。
         * 它的契约是「宿主进程没了才 false」（`IBinder.java:207-215`：otherwise the result, always by
         * default true），而执行器活着，所以自己回 true，不去猜 native 那层怎么兜。
         */
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            IBinder.PING_TRANSACTION -> true
            IBinder.DUMP_TRANSACTION, IBinder.INTERFACE_TRANSACTION -> super.onTransact(code, data, reply, flags)
            else -> handleContractCall(code, data, reply)
        }
    }

    /**
     * 两个自有事务码的入口：先认接口标识与协议版本，再按码分派。
     *
     * 读的次序必须与 [SandboxContract] 各事务码注释里写的写字次序逐字一致。两侧不一致时这里
     * 读到的是垃圾值，所以标识与版本先判、内容后读——版本不合连帧体都不去看。
     */
    private fun handleContractCall(code: Int, data: Parcel, reply: Parcel?): Boolean {
        // token 不匹配说明递错了 binder：拒掉这一次，不去猜对方的字段次序
        val token = runCatching { data.enforceInterface(SandboxContract.INTERFACE_TOKEN) }
        if (token.isFailure) {
            Logger.w(tag, "沙箱事务的接口标识不符，已拒绝：${token.exceptionOrNull()?.message}")
            return false
        }
        val version = data.readInt()
        if (version != SandboxContract.PROTOCOL_VERSION) {
            Logger.w(tag, "沙箱协议版本不合：对端 $version，本地 ${SandboxContract.PROTOCOL_VERSION}")
            writeFrameReply(reply, JsProtocol.encodeOutcome(JsOutcome(JsStatus.UNAVAILABLE, error = "沙箱协议版本不合")))
            return true
        }
        return when (code) {
            SandboxContract.TX_EXECUTE -> handleExecute(data.readString(), data.readStrongBinder(), reply)
            SandboxContract.TX_PING -> handlePing(reply)
            else -> false
        }
    }

    private fun handleExecute(requestFrame: String?, callback: IBinder?, reply: Parcel?): Boolean {
        currentCallback.set(callback)
        val frame = try {
            runSandboxTask(
                requestFrame = requestFrame,
                limits = limits,
                now = { SystemClock.elapsedRealtime() },
                eval = { invocation, deadline -> bridge.evaluate(invocation, deadline) },
                onTaskBoundary = { bridge.reset() },
            )
        } finally {
            currentCallback.set(null)
        }
        writeFrameReply(reply, frame)
        return true
    }

    /**
     * 探活：只回一个 ready 位，不建 runtime、不跑内核。
     *
     * 存在的理由是把「服务在但桥接层没就绪」（`.so` 加载失败）与「服务不在」分开——主进程对前者的
     * 话术是「这台设备的沙箱装不上」，对后者是「重连一次」。回帧里不写 outcome 帧：探活不该付一次
     * JSON 编解码的代价，字段次序见 [SandboxContract.TX_PING] 的注释。
     */
    private fun handlePing(reply: Parcel?): Boolean {
        // 单向事务（FLAG_ONEWAY）下 reply 是 null，任何往它写的代码都必须先判空——这条对两个事务同样成立
        if (reply != null) {
            reply.writeInterfaceToken(SandboxContract.INTERFACE_TOKEN)
            reply.writeInt(SandboxContract.PROTOCOL_VERSION)
            reply.writeInt(if (bridge.isReady) 1 else 0)
        }
        return true
    }

    /** 回帧的统一写法：token、version、outcomeFrame（次序与 [SandboxContract.TX_EXECUTE] 的注释一致） */
    private fun writeFrameReply(reply: Parcel?, frame: String) {
        if (reply == null) return
        reply.writeInterfaceToken(SandboxContract.INTERFACE_TOKEN)
        reply.writeInt(SandboxContract.PROTOCOL_VERSION)
        reply.writeString(frame)
    }

    override fun onCreate() {
        super.onCreate()
        // 执行器侧只有一类能力需要回主进程；COMPUTE 类由 HostDispatcher 就地算，不经 binder
        HostDispatcher.installRelay { api, argsJson -> requestHost(api, argsJson) }
    }

    override fun onDestroy() {
        HostDispatcher.clearRelay()
        currentCallback.remove()
        bridge.close()
        super.onDestroy()
    }

    /**
     * 不给不隔离的进程发通道。
     *
     * 「这个进程真的被隔离了吗」唯一的保证来自 manifest 上那一个属性，而它一旦被改掉（例如 Step 5
     * 说的退路落地成 `android:process` 却没有回头改这里），执行器就带着「脚本能读私有目录」跑起来：
     * 功能上一切正常，安全边界整片没了——这类静默失效必须自己判掉。
     * 返回 null 意味着主进程拿不到可用 binder，最终只会归成一次连接超时（[JsSandboxConnector]，Step 7），
     * 也就是宁可「沙箱不可用」，也不在不设防的进程里跑别人的脚本。
     */
    override fun onBind(intent: Intent?): IBinder? {
        if (!SandboxProcess.isInIsolatedProcess) {
            Logger.e(tag, "SandboxService 运行在非隔离进程中，拒绝提供通道")
            return null
        }
        return executor
    }

    /**
     * 把脚本的一次 host 调用同步搬到主进程。
     *
     * 返回 [HostReply] 而不是帧字符串：`HostDispatcher` 是唯一认识「回复长什么样」的地方，
     * 这里只管搬运。任何异常都换成一帧 `ok=false`——异常若继续外抛，落点是执行器进程的
     * JNI 栈，整个 `:js` 会当场没，而主进程只会把它归成一次断连。
     *
     * 两个方向的报文各用与自己同名的限值：发出去的是**请求**（[JsLimits.maxRequestBytes]，
     * 装的是一个 URL 与请求头），收回来的是**整页 HTML**（[JsLimits.maxHostReplyBytes]）。
     */
    private fun requestHost(api: String, argsJson: String): HostReply {
        val callback = currentCallback.get()
            ?: return HostReply(ok = false, data = null, error = "本次执行没有带回主进程通道")
        val callFrame = JsProtocol.encodeHostCall(api, argsJson)
        if (utf8Length(callFrame) > limits.maxRequestBytes) {
            return HostReply(ok = false, data = null, error = "host 调用帧超出 ${limits.maxRequestBytes} 字节上限")
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SandboxContract.CALLBACK_TOKEN)
            data.writeInt(SandboxContract.PROTOCOL_VERSION)
            data.writeString(callFrame)
            if (!callback.transact(SandboxContract.TX_HOST_CALL, data, reply, 0)) {
                return HostReply(ok = false, data = null, error = "主进程未受理 host 回调")
            }
            val token = runCatching { reply.enforceInterface(SandboxContract.CALLBACK_TOKEN) }
            if (token.isFailure) {
                return HostReply(ok = false, data = null, error = "主进程回调通道标识不符")
            }
            if (reply.readInt() != SandboxContract.PROTOCOL_VERSION) {
                return HostReply(ok = false, data = null, error = "主进程协议版本不合")
            }
            val frame = reply.readString()
                ?: return HostReply(ok = false, data = null, error = "主进程回了空帧")
            // 主进程侧已按同一口径判过一次（Task 6 的回帧闸门），这里再判是消费侧的自保：
            // 限值的意义是「不让超限的字节进解析」，只信发送侧等于把这句话挂在对端的版本上
            val bytes = utf8Length(frame)
            if (bytes > limits.maxHostReplyBytes) {
                return HostReply(
                    ok = false,
                    data = null,
                    error = "主进程回复超出 ${limits.maxHostReplyBytes} 字节上限（实际 $bytes 字节）",
                )
            }
            runCatching { JsProtocol.decodeHostReply(frame) }
                .getOrElse { HostReply(ok = false, data = null, error = "主进程回复解不出来") }
        } catch (t: Throwable) {
            Logger.w(tag, "host 回调失败：$api", t)
            HostReply(ok = false, data = null, error = "主进程回调失败：${t.message?.take(120)}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
```

`onBind` 那道自检（而不是在 `onCreate` 里 `stopSelf()`）值得说一句：本服务是**绑定**拉起的，只要还有
一个客户端绑着，`stopSelf()` 就不会摧毁它——想真正拒绝服务只能不给 binder。而 `onBind` 返回 null
之后主进程看到什么，取决于 ROM 把 null 投递成 `onServiceConnected(name, null)` 还是 `onBindingDied`，
两条路都不给可用通道；Step 7 的连接器对这两种一律按「连接失败」处置，不赌具体是哪条。

四个出口的归类，逐条对应主进程的一句人话：

| 现场 | 执行器回什么 | 主进程怎么说 |
| --- | --- | --- |
| 接口标识不符 | `onTransact` 返回 false | 一次事务失败 → 通道判死 → 重连 |
| 协议版本不合 | 一帧 `UNAVAILABLE`「沙箱协议版本不合」 | 「本仓两侧版本不一致」（重装 APK 即好，不叫用户重导源） |
| 请求帧解不出来 | 一帧 `UNAVAILABLE`「沙箱与主进程协议不合」 | 同上，且执行器侧 `Logger.w` 留现场 |
| 期限已过（排队久了） | 一帧 `TIMEOUT`「本次未进内核」 | 「这一页太复杂或沙箱太忙」，按 Step 3 的口径不重放 |

两个方向的报文闸门各用与自己同名的限值，不共用一个数：发出去的 host 调用帧是一种**请求**（装的是一个
URL 与请求头），归 [JsLimits.maxRequestBytes]；收回来的是**整页 HTML**，归 [JsLimits.maxHostReplyBytes]。
收侧这一判放在 `decodeFromString` **之前**：主进程已按同一口径判过一次（Task 6 的回帧闸门），这里再判
不是重复，而是消费侧的自保——限值的意义是「不让超限的字节进解析」，只信发送侧等于把这句话挂在对端的版本上。

最后交代一句测试口径：**本步没有 JVM 单测**。`Parcel` 与 `Binder` 在单测里是「Method … not mocked」
的桩，`onTransact` 的字段读写次序只能在设备上验（Step 9）；硬要在 JVM 上测就得自己造一个假 Parcel，
而假 Parcel 与真 Parcel 的次序不一致正是这类测试唯一会漏掉的东西。真正值得测的判断已在 Step 1 锁住
（`runSandboxTask`），本步的 `onTransact` 只留下「按码分诊 + 搬字节」。编译层面的自证并到 Step 10。


- [x] **Step 7: 写主进程侧的通道与连接器**

`JsSandboxClient`（Task 6）拿到的是一个抽象 `JsChannel` 和一句 `openChannel: () -> JsChannel`。这一步把
那一句填实：`BinderJsChannel` 是适配器，`JsSandboxConnector` 负责 `bindService` 与等待 binder。

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/BinderJsChannel.kt`：

```kotlin
package com.ebook.source.sandbox

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.ebook.source.script.SandboxProtocolException
import com.xrn1997.common.util.Logger

/**
 * [JsChannel] 的 binder 实现：一条连上的执行器 + 一次事务一帧。
 *
 * public 而非 internal：Task 11 的 Hilt 装配在 `lib_book_common`，跨模块看不见 internal 类型。
 *
 * 失败归类只有一条判据：**「还能不能指望这条通道」而不是「谁错了」**。
 * 传输层的失败（对端没、缓冲炸了）一律 [ChannelDeadException]，客户端据此重连；
 * 帧形态不对（标识/版本不合）一律 [SandboxProtocolException]，重连不会改变它，客户端直接上抛。
 * 把后者混进前者，现场会变成「每次解析都要先断连重连一次再失败」，把根因（两侧代码不同步）埋掉。
 */
class BinderJsChannel(
    private val executor: IBinder,
    /** 关掉通道时的收尾：连接器给的解绑。多次调用幂等由本类保证，解绑自己抛（已经死了）不外泄 */
    private val onClose: () -> Unit = {},
) : JsChannel {

    private val tag = "BinderJsChannel"

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed && executor.isBinderAlive

    /**
     * 一帧进、一帧出。
     *
     * 回调 binder **每次执行新建**：它捕获的是这一次 execute 的账本（`JsSandboxClient` 里的
     * `attempt.relayed` 与回调深度标记），复用会让下一次的回调打到上一次的账本上——那正是
     * 「重放预算被上一次污染」那条用例锁住的东西，只是这次跨了进程。
     */
    override fun execute(requestFrame: String, hostCallback: (String) -> String): String {
        if (closed) throw ChannelDeadException("通道已关闭")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SandboxContract.INTERFACE_TOKEN)
            data.writeInt(SandboxContract.PROTOCOL_VERSION)
            data.writeString(requestFrame)
            data.writeStrongBinder(HostCallbackBinder(hostCallback))
            if (!executor.transact(SandboxContract.TX_EXECUTE, data, reply, 0)) {
                throw ChannelDeadException("execute 事务未被执行器受理")
            }
            readReplyFrame(reply)
        } catch (remote: RemoteException) {
            // DeadObjectException（执行器被杀）与 TransactionTooLargeException（缓冲被别的在飞事务占满）
            // 都是 RemoteException 的子类，一起按「这条通道不能再用」判。刻意不分别 catch：
            // android-31 起 TransactionTooLargeException 的直接父类是 hidden 的 TransactionFailedException，
            // 在依赖方按类型 catch 它会引入「超类型不可见」的编译告警，而这里的处置两者相同。
            Logger.w(tag, "沙箱事务失败，通道判死：${remote.message}")
            throw ChannelDeadException("执行器不可达：${remote.message}", remote)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { onClose() }
    }

    /** 读次序与 [SandboxContract.TX_EXECUTE] 的写次序逐字对应；读不动一律判协议不合，不降级成断连 */
    private fun readReplyFrame(reply: Parcel): String {
        val token = runCatching { reply.enforceInterface(SandboxContract.INTERFACE_TOKEN) }
        if (token.isFailure) {
            throw SandboxProtocolException("执行器回帧的接口标识不符", token.exceptionOrNull())
        }
        val version = reply.readInt()
        if (version != SandboxContract.PROTOCOL_VERSION) {
            throw SandboxProtocolException("执行器协议版本不合：对端 $version，本地 ${SandboxContract.PROTOCOL_VERSION}")
        }
        return reply.readString() ?: throw SandboxProtocolException("执行器回了空帧")
    }
}

/**
 * 一次 execute 期间的反向通道：执行器把 host 调用送到这里，主进程中继给 [JsSandboxClient] 的回调。
 *
 * 文件级 private：只有 [BinderJsChannel.execute] 构造它，露出去只会多出一条「绕开 execute 自己绑一个」
 * 的错误用法。它与 execute 事务里 `writeStrongBinder` 写进去的是同一个对象，生命周期同步。
 */
private class HostCallbackBinder(private val relay: (String) -> String) : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        // 与执行器侧同一套分诊：管家事务先给框架，否则 enforceInterface 会把它们一起拒掉
        IBinder.PING_TRANSACTION -> true
        IBinder.DUMP_TRANSACTION, IBinder.INTERFACE_TRANSACTION -> super.onTransact(code, data, reply, flags)
        SandboxContract.TX_HOST_CALL -> handleCall(data, reply)
        else -> false
    }

    private fun handleCall(data: Parcel, reply: Parcel?): Boolean {
        val token = runCatching { data.enforceInterface(SandboxContract.CALLBACK_TOKEN) }
        if (token.isFailure) {
            Logger.w("HostCallbackBinder", "host 回调的接口标识不符，已拒绝：${token.exceptionOrNull()?.message}")
            return false
        }
        if (data.readInt() != SandboxContract.PROTOCOL_VERSION) {
            writeCallReply(reply, failure("主进程与执行器协议版本不合"))
            return true
        }
        val callFrame = data.readString()
        if (callFrame == null) {
            writeCallReply(reply, failure("host 调用帧为空"))
            return true
        }
        // relay（JsSandboxClient.relay）自己已经吞掉一切异常；这里再兜一层是因为契约的下一任实现
        // 未必守得住，而异常从这条线程穿出去落在执行器的 JNI 栈上——整个 :js 会当场没
        writeCallReply(reply, runCatching { relay(callFrame) }.getOrElse { failure("主进程中继失败：${it.message?.take(120)}") })
        return true
    }

    private fun failure(message: String): String = JsProtocol.encodeHostReply(ok = false, data = null, error = message)

    private fun writeCallReply(reply: Parcel?, frame: String) {
        if (reply == null) return
        reply.writeInterfaceToken(SandboxContract.CALLBACK_TOKEN)
        reply.writeInt(SandboxContract.PROTOCOL_VERSION)
        reply.writeString(frame)
    }
}
```

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxConnector.kt`：

```kotlin
package com.ebook.source.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import com.ebook.source.script.SandboxUnavailableException
import com.xrn1997.common.util.Logger

/**
 * 建一条通道：`bindService` 拉起 `:js`，等 binder，交给 [BinderJsChannel]。
 *
 * 每次 [open] 都是一次独立的绑定，解绑挂在返回通道的 `close()` 上——**不把 Connection 存在本类字段里**：
 * 客户端重连时旧的 Connection 必须与旧通道一起作废，否则旧 binder 的解绑会顺手把新绑定的连接掐掉。
 *
 * 回调投递在调用方的主线程上（`bindService` 带 Executor 的重载是 API 29 才有，本仓 minSdk 26）。
 * 这不构成新风险：[JsSandboxClient] 已有主线程守卫，绝不会有 execute 停在主线程上等这次回调。
 * 代价是主线程长时间占住时连接会等到 [JsLimits.bindTimeoutMs] 超时，症状是一句可命名的「不可用」，
 * 而不是无声卡住。
 */
class JsSandboxConnector(
    context: Context,
    private val limits: JsLimits = JsLimits(),
) {

    private val appContext = context.applicationContext
    private val tag = "JsSandboxConnector"

    /**
     * 连不上时抛 [SandboxUnavailableException] 而不是 [ChannelDeadException]：
     * 客户端对后者会「重连并重放一次」，而一次都没连上的调用没有可重放的副作用，
     * 重放只是再等一遍 [JsLimits.bindTimeoutMs]——把一个 2 秒的坏消息变成 4 秒。
     */
    fun open(): JsChannel {
        val connection = Connection()
        val started = runCatching {
            appContext.bindService(
                Intent(appContext, SandboxService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrElse {
            Logger.e(tag, "绑定 :js 执行器时抛异常", it)
            throw SandboxUnavailableException("绑定脚本沙箱执行器失败：${it.message?.take(120)}", it)
        }
        if (!started) {
            // 服务不存在/被禁用时系统不会回调任何一次 onServiceConnected，所以这里必须自己收口
            throw SandboxUnavailableException("系统拒绝绑定脚本沙箱执行器（服务不存在或被禁用）")
        }
        val binder = try {
            connection.await(limits.bindTimeoutMs)
        } catch (t: Throwable) {
            unbind(connection)
            throw t
        }
        return BinderJsChannel(binder) { unbind(connection) }
    }

    private fun unbind(connection: Connection) {
        runCatching { appContext.unbindService(connection) }
            .onFailure { Logger.w(tag, "解绑 :js 执行器失败（连接已作废，忽略）：${it.message}") }
    }

    /** 一次绑定的账本。四个回调都可能不来，所以等待必须有期限而不是无限 wait */
    private class Connection : ServiceConnection {

        private val lock = Any()
        private var binder: IBinder? = null
        private var failure: String? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            // service 声明成可空：onNullBinding 是 API 28 才有的投递方式，在 26/27 上 onBind 返回 null
            // 走的是这里，参数为 null。两条路径必须给同一句话术，否则同一台设备的坏消息会随版本变。
            synchronized(lock) {
                if (service == null) {
                    failure = "执行器拒绝提供通道（多半是当前进程未被隔离）"
                } else {
                    binder = service
                }
                lock.notifyAll()
            }
        }

        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                failure = "执行器拒绝提供通道（多半是当前进程未被隔离）"
                lock.notifyAll()
            }
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) {
                failure = "执行器的绑定已死（服务被系统回收）"
                lock.notifyAll()
            }
        }

        /**
         * 进程死了但连接还留着：不翻脸、不清 binder。
         *
         * 这里的 binder 已经成了 dead object，下一次 transact 会抛 DeadObjectException，
         * 客户端据此判死并重连——那条路径已经有用例锁住。在此处置空只会让「谁杀的进程」这件事
         * 从两条互相矛盾的日志里出现，反而更难判。
         */
        override fun onServiceDisconnected(name: ComponentName) {
            Logger.w("JsSandboxConnector", ":js 执行器进程断开，交给下一次事务去判死")
        }

        fun await(timeoutMs: Long): IBinder {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            synchronized(lock) {
                while (binder == null && failure == null) {
                    val left = deadline - SystemClock.elapsedRealtime()
                    if (left <= 0L) break
                    runCatching { lock.wait(left) }
                }
                binder?.let { return it }
                throw SandboxUnavailableException(
                    failure ?: "等待 :js 执行器连接超时（${timeoutMs} ms）",
                )
            }
        }
    }
}
```

`Logger.w` 在 [Connection] 里那句需要一个可空标签——`Connection` 是嵌套类、拿不到外类的 `tag` 字段，
所以直接写字面量 `"JsSandboxConnector"`（不要为了让它复用外类字段而把嵌套类改成 inner：`ServiceConnection`
的实现一旦是 inner，就能碰到外类的 `appContext`，解绑责任会重新变得含糊）。

- [x] **Step 8: 给 Application 加进程门**

事实 2 说过：隔离进程里框架照样实例化 `MyApplication`。两处改动都是为了让沙箱进程**不建应用级 DI 图**。

先改 `lib_book_common/src/main/java/com/ebook/common/BookApplication.kt`——在 `super.onCreate()` 之后、
主题装配之前插一道门，并在文件里补两个 import（`com.ebook.source.sandbox.SandboxProcess`、
`com.xrn1997.common.util.Logger`）与一个伴生 `TAG`：

```kotlin
    override fun onCreate() {
        super.onCreate()
        // 进程门：隔离进程里框架仍会实例化本类（ActivityThread 在隔离进程上只跳过 setupGraphicsSupport、
        // TrafficStats.init 与字体预加载，不跳过 makeApplicationInner）。下面两件事在沙箱里既没用处也做不成：
        // 主题装配不为一个不画 UI 的进程服务，而 ThemeModeManager 的持久化要读 SharedPreferences——
        // 隔离进程读不到本应用的数据目录，留它就是一次纯粹的 IO 失败与一串看不懂的日志。
        // 反过来，主进程这一侧绝不能被误判跳过：那等于整个 App 起来但没初始化。
        if (SandboxProcess.isInIsolatedProcess) {
            Logger.i(TAG, "沙箱进程：跳过应用级初始化")
            return
        }
        // ……既有主题装配代码原样保留……
    }

    private companion object {
        const val TAG = "BookApplication"
    }
```

再改 `module_app/src/main/java/com/ebook/MyApplication.kt`。**必须去掉那个 eager 的 `@Inject bookRepository`**：
`@HiltAndroidApp` 生成的 `Hilt_MyApplication.onCreate()` 正是 `MyApplication.super.onCreate()` 那一步，
字段注入发生在任何自定义门之前，而 `BookRepository` 里挂着 Room——隔离进程一开库就是 IO 失败。
取仓库改到协程里经 `EntryPointAccessors`（与 `BookApplication` 取 `ThemeModeManager` 同一套路）：

```kotlin
package com.ebook

import com.ebook.common.BookApplication
import com.ebook.common.interceptor.LoginInterceptor
import com.ebook.common.repository.BookRepository
import com.ebook.source.sandbox.SandboxProcess
import com.therouter.router.addRouterReplaceInterceptor
import com.xrn1997.common.util.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MyApplication : BookApplication() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // super 必须留着且放在最前：它里面是 Hilt 的注入与 lib_common 基类的初始化，跳过 super 是拿
        // 「不跑注入」去换一次框架契约的破坏。真正要防的是注入的**内容**，所以下面不再有 eager @Inject。
        super.onCreate()
        if (SandboxProcess.isInIsolatedProcess) return
        // 登录拦截
        addRouterReplaceInterceptor(LoginInterceptor())
        // 内容仓库对账：删书与导入中断留下的无主目录只有这一处回收入口。
        // 时机不变式不变——一个进程只跑一次，导入进行中跑会误删正在写入的目录。
        appScope.launch {
            runCatching {
                // 晚到这一刻才建 DI 图：隔离进程在上面那道门就 return 了，永远走不到这里
                EntryPointAccessors.fromApplication(
                    this@MyApplication,
                    ContentStoreEntryPoint::class.java,
                ).bookRepository().reconcileContentStore()
            }.onFailure { Logger.e(TAG, "内容仓库对账失败（不影响启动）: ", it) }
        }
    }

    /**
     * Hilt entry point：与 BookApplication 的 ThemeModeManagerEntryPoint 同法。
     *
     * 存在的唯一理由是把 BookRepository 的构造从「Application 字段注入」挪进「用到它的那一刻」——
     * 字段注入在 `:js` 里也会跑，而那个进程读不到 Room 的库文件。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ContentStoreEntryPoint {
        fun bookRepository(): BookRepository
    }

    private companion object {
        const val TAG = "MyApplication"
    }
}
```

改动只有两处 import 层面的：`javax.inject.Inject` 整行删除（不再有注入字段），新增 `dagger.hilt.EntryPoint`、
`dagger.hilt.InstallIn`、`dagger.hilt.android.EntryPointAccessors`、`dagger.hilt.components.SingletonComponent`
四行与 `com.ebook.source.sandbox.SandboxProcess` 一行。

一条要写进注释里的**留给将来的规则**（Step 8 的代码注释已含，此处复述给评审看）：Application 上不许再有
eager `@Inject` 字段。门在 `super.onCreate()` 之后，而注入在 `super.onCreate()` 里面——今天这句话只对
`MyApplication` 成立，明天有人加第二个注入字段就会让沙箱进程起来即崩，而崩的现场在 binder 连接超时里看不出来。

- [x] **Step 9: 连通性用例（本计划的地基验证）**

创建 `lib_book_source/src/androidTest/java/com/ebook/source/sandbox/SandboxConnectionTest.kt`：

```kotlin
package com.ebook.source.sandbox

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ebook.source.script.JsMode
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `:js` 执行器的连通性用例——**本计划能否成立就看它**。
 *
 * 不测策略（策略在 `SandboxTaskRunnerTest` 里全绿），只测三件在 JVM 上根本不存在的事：
 *
 * 1. `.so` 能在隔离进程里加载并跑出一条真脚本（Task 8 事实 4：这条只是推断，此处换成证据）。
 * 2. 双向事务真的双向：脚本 → host 回调 → 主进程 → 回脚本，同一线程进去同一线程出来。
 * 3. 拉起的那个进程确实被系统隔离——`onBind` 里那道自检不是纸面规则，`isolatedProcess` 也不是装饰。
 *
 * 用例之间不共享通道：每次都重新 `open()`，因为「重连能不能拿到一个新执行器」本身就是要验的事。
 */
@RunWith(AndroidJUnit4::class)
class SandboxConnectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val limits = JsLimits()

    @Test
    fun 连上执行器并算出一条表达式() {
        val channel = JsSandboxConnector(context, limits).open()
        val invocation = JsInvocation(JsMode.SEGMENT, "result = 1 + 1", emptyMap())
        val outcome = JsProtocol.decodeOutcome(
            channel.execute(JsProtocol.encodeRequest(invocation, deadline())) { error("不该有 host 调用") },
            limits,
        )
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("2", outcome.text)
        channel.close()
    }

    @Test
    fun 脚本里的 host 调用拿得到主进程回的页面() {
        var seenApi = ""
        var seenArgs = ""
        val channel = JsSandboxConnector(context, limits).open()
        val invocation = JsInvocation(JsMode.SEGMENT, "result = java.ajax('https://example.com/').length", emptyMap())
        val outcome = JsProtocol.decodeOutcome(
            channel.execute(JsProtocol.encodeRequest(invocation, deadline())) { callFrame ->
                val (api, args) = JsProtocol.decodeHostCall(callFrame)
                seenApi = api
                seenArgs = args
                JsProtocol.encodeHostReply(ok = true, data = JsonPrimitive("abc"), error = null)
            },
            limits,
        )
        channel.close()
        // 白名单在垫片里、边界在 HostDispatcher 的表里（Task 7），两者都放行才可能到这里
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("3", outcome.text)
        assertEquals("ajax", seenApi)
        assertTrue("参数应当是 argsJson 数组：$seenArgs", seenArgs.startsWith("[\"https://"))
    }

    @Test
    fun 两次执行之间不留上一个任务的全局量() {
        val channel = JsSandboxConnector(context, limits).open()
        channel.execute(JsProtocol.encodeRequest(JsInvocation(JsMode.SEGMENT, "var leaked = 42; result = 'a'", emptyMap()), deadline())) { error("不该有 host 调用") }
        val second = JsProtocol.decodeOutcome(
            channel.execute(
                JsProtocol.encodeRequest(JsInvocation(JsMode.SEGMENT, "result = typeof leaked", emptyMap()), deadline()),
            ) { error("不该有 host 调用") },
            limits,
        )
        channel.close()
        assertEquals("undefined", second.text)
    }

    @Test
    fun 拉起来的执行器进程确实被系统隔离() {
        // 先留一条开着的通道，保证 :js 活着且正被绑定着再去做枚举
        val channel = JsSandboxConnector(context, limits).open()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val jsProcess = manager.runningAppProcesses?.firstOrNull { it.processName.endsWith(":js") }
        assertTrue("没找到 :js 进程：执行器没被拉起来，或 android:process 没生效", jsProcess != null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue("uid ${jsProcess!!.uid} 不在隔离区间：isolatedProcess 没生效", Process.isIsolatedUid(jsProcess.uid))
        }
        // isIsolatedUid 是 API 34 才有的公开判据；34 以下这里退化成「有第二个进程」，
        // 剩下的隔离性由 onBind 那道自检兜住（不隔离就不给 binder，前三个用例会全红）
        channel.close()
    }

    private fun deadline(): Long = SystemClock.elapsedRealtime() + limits.wallClockMs
}
```

**Agent 跑不了这个文件**：它需要连着的设备或模拟器（Step 10 里标注清楚）。三条失败路径各自的处置：

- 前三个用例全红、第四个说「没找到 :js 进程」→ `bindService` 就没成，先查 merged manifest 里
  `isolatedProcess` 与 `:js`（Step 5 的那条 grep）。
- `连上执行器并算出一条表达式` 红在 `JsStatus.UNAVAILABLE`、日志里有 `dlopen failed` → 事实 4 的推断被证伪。
  **不许就地摘掉 `android:isolatedProcess`**：那是回 ADR-0028 重议决策 2，先改 ADR 再改代码。
- 只有第二个用例红、报「主进程未受理 host 回调」→ 反向通道没接上，查 `HostDispatcher.installRelay`
  是否在 `onCreate` 里装了、以及 `TX_HOST_CALL` 的 token 两侧是否同一个。

- [x] **Step 10: 验证**

> **执行记录（2026-09-09）**：四条命令已跑，`testDebugUnitTest` 全绿、`:module_app:assembleDebug`
> 与 `:lib_book_source:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`、`git grep -in "[l]egado"` 输出 0。
> merged manifest 另核到隔离服务真的在：`com.ebook.source.sandbox.SandboxService`
> 带 `android:isolatedProcess="true"` 与 `android:process=":js"`。
> 下面「未验证项」那四条 androidTest 用例当时未跑（写这条时无设备）；该状态已被 Task 11 Step 2
> 的设备执行记录撤销，16 例已全绿。

```bash
# JVM：策略层与协议层（Task 3/6/7/8 的全部纯 Kotlin 用例）
./gradlew :lib_book_source:testDebugUnitTest
# 应用侧：Hilt 生成、清单合并（隔离服务必须出现在 merged manifest）、KSP 全链路
./gradlew :module_app:assembleDebug
# androidTest APK 能编出来（不代表跑过）
./gradlew :lib_book_source:assembleDebugAndroidTest
# 红线
git grep -in "[l]egado" | wc -l
```

Expected: 单测全绿；两条 assemble 成功且**零新增警告**；红线输出 0。

**未验证项（必须交代给人工）**：Step 9 的四个用例是 `./gradlew :lib_book_source:connectedDebugAndroidTest`
在真机/模拟器上跑的，Agent 到此无法代劳。装机时同时确认两件事——① App 正常冷启动（书架、详情、阅读页
都能打开，证明进程门没有把主进程的初始化一起挡掉）；② `adb logcat -s SandboxService JsSandboxConnector`
里 `:js` 的 pid 与主进程不同，且没有 Room/SharedPreferences 的 IO 报错。

- [ ] **Step 11: 提交（需授权）**

```bash
git add lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxTaskRunner.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxProcess.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxService.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/BinderJsChannel.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxConnector.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsLimits.kt \
        lib_book_source/src/main/AndroidManifest.xml \
        lib_book_source/src/test/java/com/ebook/source/sandbox/SandboxTaskRunnerTest.kt \
        lib_book_source/src/androidTest/java/com/ebook/source/sandbox/SandboxConnectionTest.kt \
        lib_book_common/src/main/java/com/ebook/common/BookApplication.kt \
        module_app/src/main/java/com/ebook/MyApplication.kt
git commit -m "feat(lib_book_source): 新增 :js 隔离进程沙箱执行器

`:js` 只做「拿一帧、跑一次、回一帧」，策略收在纯 Kotlin 的 runSandboxTask 里可 JVM 测；
主进程侧由 BinderJsChannel + JsSandboxConnector 建通道，host 调用经每次执行新建的反向 binder 中继。
隔离进程读不到网络与本应用数据目录（脚本能碰的东西以内核为限），故取文一律走主进程代理。

框架在隔离进程里仍会实例化 Application，因此 MyApplication 去掉 eager 注入、
BookApplication 与 MyApplication 各加一道进程门，沙箱进程不再建应用级 DI 图。

人工装机验证项：SandboxConnectionTest 四例已在模拟器跑过（见 Task 11 Step 2），仍归人工的是
release 包在真机上的 .so 加载、真实站点的脚本行为与沙箱进程功耗。"
```

---

### Task 9：主进程侧回调代理 `JsCallbackProxy`（受限网络 + 递归求值 + 变量表）

**执行者**：完全能在 JVM 上测完。本任务落的是〔ADR-0028 决策 6〕「网络与能力受限于主进程代理」那一半——执行器侧（Task 7/8）只把请求中继回来，一点判定都不做，这里才是边界。

**前置**：Task 3（`JsLimits` / `HostReply` / `utf8Length`）、Task 4（`JsHostApi` 表）、Task 5（`JsNetworkGuard` / `SourceHostAllowlist` / `GuardedDns`）、Task 6（`HostHandler` / `HostReply` / 客户端的 `hostHandler` 构造参数）、2c 的 `ScriptTransport` / `ScriptRequest` / `ScriptUrlOption`。

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsCallbackProxy.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/sandbox/JsCallbackProxyTest.kt`

---

**关键事实（写代码前先读，四条决定了形状）**

1. **`HostHandler.handle` 是同步的，而 `ScriptTransport.execute` 是 `suspend` 的**（`lib_book_source/src/main/java/com/ebook/source/script/ScriptHttp.kt:31`）。接上两者的唯一办法是 `runBlocking`，它落在**主进程的 binder 线程池线程**上（Task 8 的 `HostCallbackBinder.onTransact` 调进来），不是主线程；transport 内部一律 `withContext(Dispatchers.IO)`，所以真正阻塞的是 IO 池线程，`runBlocking` 只是在 binder 线程上等结果。这条不写清楚，将来会有人「顺手」把代理改成回主线程发请求——那书源请求就挂在主线程上了。

2. **`JsSandboxClient` 的 `hostHandler` 在构造期定死**（一条长连接一份策略），而回调代理的状态**必须按任务新建**：外呼配额、回调深度、当前页面基准、`java.put` 变量表都是「一本书的一轮求值」级别的（规格 §5.2 定的作用域）。两者的缝由本任务的 `HostCallbackRouter` 承担，Task 10 装配时只写 `router.withProxy(proxy) { client.execute(invocation) }`。

3. **白名单在主进程判第三次不是重复劳动**。第一次是 JS 垫片（给人看的），第二次是 `HostDispatcher`（Task 7，防脚本直接调 `__host_call`），第三次是这里——**帧是从另一个进程来的**。代理不认表，就等于把「主进程能做什么」交给执行器进程决定：一个改坏的 `.so` 或一条版本错位的帧，就能让主进程去请求任意 host、把任意页面当当前页回给脚本。名字与元数都按 `JsHostApi` 判；越界的 `args[i]` 在 Kotlin 侧是 `IndexOutOfBoundsException`，那样回给脚本的话会变成「代理内部炸了」而不是「参数个数不符」。

4. **`maxCallbackDepth` 在阶段一正常路径上恒为 1，它的身份是绊线而不是限流**（这条把 Task 3 定义的限值接上了生产者）。`getElements` 一族的嵌套求值走构造进来的 `evaluateNested`，而 Task 10 **必须给它一个关掉 JS 的求值器**——沙箱里只有一个 runtime，此刻正攥在外层 `execute` 手上，嵌套再要一次 JS 就是双向死锁（Task 6 已把那条路做成立刻失败）。这条要求编译期传不出来，所以留一道深度计数：撞到上限说明有人把带 JS 的求值口接进了回调路径，绊线把「等满 `wallClockMs` 后一个看不出根因的 TIMEOUT」换成当场可诊断的一句人话。

---

- [x] **Step 1: 写失败测试**

创建 `lib_book_source/src/test/java/com/ebook/source/sandbox/JsCallbackProxyTest.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.RuleResult
import com.ebook.source.script.RuleSyntaxException
import com.ebook.source.script.RuleValue
import com.ebook.source.script.ScriptRequest
import com.ebook.source.script.ScriptTransport
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

/**
 * 回调代理的策略面：准入、限流、参数形态、递归求值、变量作用域、日志出口。
 *
 * 全部跑在 JVM 上：网络与求值都从构造参数进来（`ScriptTransport` 是 2c 就有的假件接缝，
 * `statusProbe` / `evaluateNested` 同理），所以这条链上没有一处需要设备或真 DNS。
 * `GuardedNetwork` 那条用例只锁「守门 DNS 确实挂上了客户端」，真实拦截由 Task 5 的
 * `JsNetworkGuardTest` 与 Task 8 的设备用例负责。
 */
class JsCallbackProxyTest {

    private class FakeTransport(private val reply: String = "<html>第二页</html>") : ScriptTransport {
        val sent = mutableListOf<ScriptRequest>()
        override suspend fun execute(request: ScriptRequest): String {
            sent += request
            return reply
        }
    }

    private fun proxyFor(
        transport: ScriptTransport = FakeTransport(),
        statusProbe: suspend (String) -> Int = { 200 },
        variables: MutableMap<String, String> = linkedMapOf(),
        limits: JsLimits = JsLimits(),
        evaluateNested: (String, RuleValue) -> RuleResult = { _, _ -> RuleResult.Miss },
        logs: MutableList<String> = mutableListOf(),
    ): JsCallbackProxy = JsCallbackProxy(
        // 白名单只含 novel.example：本文件里所有「被拒」用例靠的就是这条边界
        guard = JsNetworkGuard(SourceHostAllowlist.of("", listOf("https://novel.example/"))),
        transport = transport,
        statusProbe = statusProbe,
        variables = variables,
        evaluateNested = evaluateNested,
        limits = limits,
        logSink = { channel, message -> logs += "$channel:$message" },
        initialPage = "<html>入口页</html>",
        initialUrl = "https://novel.example/entry",
    )

    private fun data(reply: HostReply): String? = (reply.data as? JsonPrimitive)?.content

    // —— 网络：基准、准入、限流、参数形态 ——

    @Test
    fun `ajax 取回白名单内的页面，并把它当作下一次定位的基准`() {
        val transport = FakeTransport("<html>第二页</html>")
        var seenPage = ""
        var seenUrl = ""
        val proxy = proxyFor(transport = transport, evaluateNested = { _, input ->
            val page = input as RuleValue.Page
            seenPage = page.source
            seenUrl = page.baseUrl
            RuleResult.Texts(listOf("第一章"))
        })
        assertTrue(proxy.handle("ajax", """["https://novel.example/p2"]""").ok)
        assertEquals("<html>第二页</html>", seenPage)
        assertEquals("https://novel.example/p2", seenUrl)
    }

    @Test
    fun `没有任何外呼时，嵌套求值的基准是任务带进来的入口页`() {
        var seenPage = ""
        val proxy = proxyFor(evaluateNested = { _, input ->
            seenPage = (input as RuleValue.Page).source
            RuleResult.Miss
        })
        proxy.handle("getElements", """[".//a"]""")
        assertEquals("<html>入口页</html>", seenPage)
    }

    @Test
    fun `白名单外的 host 被拒且根本不打网络，被拒的请求不烧配额`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport, limits = JsLimits(maxRequestsPerTask = 1))
        val rejected = proxy.handle("ajax", """["https://evil.example/x"]""")
        assertFalse(rejected.ok)
        assertTrue(rejected.error!!.contains("白名单"))
        assertEquals(0, transport.sent.size)
        // 配额一点没动：一条写坏的规则不该把这一轮的外呼机会吃光
        assertTrue(proxy.handle("ajax", """["https://novel.example/ok"]""").ok)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun `外呼次数用满上限后拒绝，且消息说得出上限与目标 host`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport, limits = JsLimits(maxRequestsPerTask = 2))
        repeat(2) { assertTrue(proxy.handle("ajax", """["https://novel.example/p$it"]""").ok) }
        val third = proxy.handle("ajax", """["https://novel.example/p3"]""")
        assertFalse(third.ok)
        assertTrue(third.error!!.contains("2 次外呼上限"))
        assertTrue(third.error!!.contains("novel.example"))
        assertEquals("拒绝必须发生在发请求之前，否则上限只是报账而不是拦截", 2, transport.sent.size)
    }

    @Test
    fun `ajax 的第 2 个参数是对象时按 URL 选项解析，与规则尾段共用一套键`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        val reply = proxy.handle(
            "ajax",
            """["https://novel.example/x",{"method":"GET","headers":{"X-T":"1"},"charset":"gbk","timeout":3000,"retry":2}]""",
        )
        assertTrue(reply.ok)
        val request = transport.sent.single()
        assertEquals("GET", request.method)
        assertEquals(mapOf("X-T" to "1"), request.headers)
        assertEquals("gbk", request.charset)
        assertEquals(3_000L, request.timeoutMs)
        assertEquals(2, request.retry)
    }

    @Test
    fun `声明了 POST 却不给 body 时当场拒绝，不让传输层抛未类型化的 IllegalArgumentException`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        val reply = proxy.handle("ajax", """["https://novel.example/x",{"method":"POST"}]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("没给 body"))
        assertEquals(0, transport.sent.size)
    }

    @Test
    fun `ajax 的第 2 个参数是裸字符串时按 charset 处理（本仓规定）`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        assertTrue(proxy.handle("load", """["https://novel.example/gbk","gbk"]""").ok)
        assertEquals("gbk", transport.sent.single().charset)
        assertEquals("GET", transport.sent.single().method)
    }

    @Test
    fun `选项里的 webView 照规则尾段同口径拒绝，且发生在外呼之前`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        val reply = proxy.handle("ajax", """["https://novel.example/x",{"webView":true}]""")
        assertFalse(reply.ok)
        assertEquals("带 webView 的请求不会发出去", 0, transport.sent.size)
    }

    @Test
    fun `post 的对象体按表单体发送，键排序让同一脚本调用永远发同一份请求体`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        assertTrue(proxy.handle("post", """["https://novel.example/s",{"b":"2","a":"斗破"}]""").ok)
        val request = transport.sent.single()
        assertEquals("POST", request.method)
        assertEquals("a=%E6%96%97%E7%A0%B4&b=2", request.body)
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun `post 的字符串体原样发出，不猜 Content-Type`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        assertTrue(proxy.handle("post", """["https://novel.example/s","raw=payload"]""").ok)
        val request = transport.sent.single()
        assertEquals("raw=payload", request.body)
        assertTrue(request.headers.isEmpty())
    }

    @Test
    fun `responseCode 走探测且同样过守门与限流`() {
        var probed = 0
        val proxy = proxyFor(
            statusProbe = { probed++; 404 },
            limits = JsLimits(maxRequestsPerTask = 1),
        )
        val reply = proxy.handle("responseCode", """["https://novel.example/x"]""")
        assertTrue(reply.ok)
        assertEquals("404", data(reply))
        assertEquals(1, probed)
        assertFalse(proxy.handle("responseCode", """["https://novel.example/y"]""").ok)
        assertFalse(proxy.handle("responseCode", """["https://evil.example/x"]""").ok)
        assertEquals("白名单外的探测不该发出去", 1, probed)
    }

    @Test
    fun `响应文本超单次回调上限时报告过大，且不推进页面基准`() {
        val transport = FakeTransport("<html>这一页超大</html>")
        var seenPage = ""
        val proxy = proxyFor(
            transport = transport,
            limits = JsLimits(maxHostReplyBytes = 8),
            evaluateNested = { _, input ->
                seenPage = (input as RuleValue.Page).source
                RuleResult.Miss
            },
        )
        val reply = proxy.handle("ajax", """["https://novel.example/big"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("超单次回调上限"))
        proxy.handle("getElements", """[".//a"]""")
        assertEquals("过大的一页不能成为当前页", "<html>入口页</html>", seenPage)
    }

    @Test
    fun `请求超时回的是请求失败的话术，异常不穿出代理边界`() {
        val proxy = proxyFor(
            transport = ScriptTransport { delay(200); "body" },
            limits = JsLimits(wallClockMs = 50L),
        )
        val reply = proxy.handle("ajax", """["https://novel.example/slow"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("没回结果"))
    }

    @Test
    fun `代理用的客户端把 DNS 挂在守门判定点上，且不污染注入的源客户端`() {
        val base = okhttp3.OkHttpClient()
        val guarded = GuardedNetwork.clientFor(base, JsNetworkGuard(SourceHostAllowlist.of("", emptyList())))
        assertTrue(guarded.dns is GuardedDns)
        assertNotSame(base.dns, guarded.dns)
    }

    // —— 变量：与 `@put:` / `{{}}` 同一张表 ——

    @Test
    fun `putVar getVar rmVar 操作的就是传进来的那张变量表，写完插值层立刻读得到`() {
        val variables = linkedMapOf<String, String>()
        val proxy = proxyFor(variables = variables)
        assertTrue(proxy.handle("putVar", """["k","v"]""").ok)
        assertEquals("v", variables["k"])
        assertEquals("v", data(proxy.handle("getVar", """["k"]""")))
        assertTrue(proxy.handle("rmVar", """["k"]""").ok)
        assertFalse(variables.containsKey("k"))
    }

    @Test
    fun `读未设置的变量是「没值」而不是失败`() {
        val reply = proxyFor().handle("getVar", """["never-set"]""")
        assertTrue(reply.ok)
        assertEquals(JsonNull, reply.data)
    }

    @Test
    fun `putToPage 与 putVar 同落任务变量表（阶段一没有页缓存持久层）`() {
        val variables = linkedMapOf<String, String>()
        val proxy = proxyFor(variables = variables)
        assertTrue(proxy.handle("putToPage", """["tocHtml","<ul></ul>"]""").ok)
        assertEquals("<ul></ul>", variables["tocHtml"])
    }

    // —— 递归规则求值 ——

    @Test
    fun `getElements 回的是外形态 HTML，脚本可以再把它喂回去定位`() {
        val nodes = RuleResult.Nodes(Jsoup.parse("<div><p>A</p><p>B</p></div>").select("p"))
        val proxy = proxyFor(evaluateNested = { _, _ -> nodes })
        val reply = proxy.handle("getElements", """[".//p"]""")
        assertTrue(reply.ok)
        val items = (reply.data as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("<p>A</p>", "<p>B</p>"), items)
    }

    @Test
    fun `queryString 回纯文本，与 getElement 的外形态 HTML 各管一件事`() {
        val nodes = RuleResult.Nodes(Jsoup.parse("<div><p>A</p><p>B</p></div>").select("p"))
        val proxy = proxyFor(evaluateNested = { _, _ -> nodes })
        assertEquals("A", data(proxy.handle("queryString", """[".//p"]""")))
        assertEquals("<p>A</p>", data(proxy.handle("getElement", """[".//p"]""")))
    }

    @Test
    fun `嵌套求值没命中：列表回空数组、单值回 null，都不算失败`() {
        val proxy = proxyFor(evaluateNested = { _, _ -> RuleResult.Miss })
        val list = proxy.handle("getElements", """[".//x"]""")
        assertTrue(list.ok)
        assertEquals(0, (list.data as JsonArray).size)
        val single = proxy.handle("getElement", """[".//x"]""")
        assertTrue(single.ok)
        assertEquals(JsonNull, single.data)
    }

    @Test
    fun `嵌套求值抛类型化错误时把原文回给脚本，不折叠成空`() {
        val proxy = proxyFor(evaluateNested = { _, _ -> throw RuleSyntaxException(".//p[text()]") })
        val reply = proxy.handle("getElements", """[".//p"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains(".//p[text()]"))
    }

    @Test
    fun `嵌套求值里再要一次嵌套：绊线当场拒绝而不是撑到超时`() {
        lateinit var proxy: JsCallbackProxy
        var inner: HostReply? = null
        proxy = proxyFor(
            limits = JsLimits(maxCallbackDepth = 1),
            evaluateNested = { _, _ ->
                inner = proxy.handle("getElements", """[".//p"]""")
                RuleResult.Miss
            },
        )
        assertTrue("外层自己该照常跑完", proxy.handle("getElements", """[".//a"]""").ok)
        assertFalse(inner!!.ok)
        assertTrue(inner!!.error!!.contains("深度上限"))
    }

    // —— 日志与边界 ——

    @Test
    fun `toast 与 log 只落注入的日志出口，不弹 UI、不回值`() {
        val logs = mutableListOf<String>()
        val proxy = proxyFor(logs = logs)
        val toast = proxy.handle("toast", """["正文里有广告"]""")
        assertTrue(toast.ok)
        assertEquals(JsonNull, toast.data)
        assertTrue(proxy.handle("log", """["debug info"]""").ok)
        assertEquals(listOf("toast:正文里有广告", "log:debug info"), logs)
    }

    @Test
    fun `表外的能力名一律拒绝，消息与执行器侧同一条`() {
        val reply = proxyFor().handle("files", "[]")
        assertFalse(reply.ok)
        assertEquals("沙箱里没有「files」这个能力", reply.error)
    }

    @Test
    fun `纯计算能力不该出现在主进程：拒绝而不是自己再算一遍`() {
        val reply = proxyFor().handle("md5", """["x"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("纯计算"))
    }

    @Test
    fun `实参不是 JSON 数组时拒绝，异常绝不穿出 handle`() {
        val reply = proxyFor().handle("ajax", """{"url":"https://novel.example/x"}""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("JSON 数组"))
    }

    @Test
    fun `元数越界报的是参数个数不符，不是下标越界`() {
        val reply = proxyFor().handle("post", """["https://novel.example/x"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("参数个数不符"))
    }

    // —— 换挡器 ——

    @Test
    fun `换挡器没挂上任务代理时回一句可诊断的话`() {
        val reply = HostCallbackRouter().handle("ajax", """["https://novel.example/x"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("没有正在进行的脚本任务"))
    }

    @Test
    fun `任务代理只在 withProxy 期间生效，返回即摘掉`() {
        val router = HostCallbackRouter()
        val variables = linkedMapOf<String, String>()
        assertFalse(router.handle("putVar", """["k","v"]""").ok)
        val inside = router.withProxy(proxyFor(variables = variables)) {
            router.handle("putVar", """["k","v"]""")
        }
        assertTrue(inside.ok)
        assertEquals("v", variables["k"])
        assertFalse(
            "任务结束后必须摘掉代理，否则下一个任务的回调会写进上一个任务的变量表",
            router.handle("getVar", """["k"]""").ok,
        )
    }
}
```

- [x] **Step 2: 跑红**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsCallbackProxyTest*"`
Expected: 编译失败，`JsCallbackProxy` / `HostCallbackRouter` / `GuardedNetwork` 未定义（`JsNetworkGuard`、`SourceHostAllowlist`、`GuardedDns`、`JsHostApi`、`JsLimits`、`HostReply` 已由 Task 3~5 提供）。

- [x] **Step 3: 写实现**

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsCallbackProxy.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.AccessorKind
import com.ebook.source.script.JsApiRejectedException
import com.ebook.source.script.JsApiTarget
import com.ebook.source.script.JsHostApi
import com.ebook.source.script.RuleResult
import com.ebook.source.script.RuleValue
import com.ebook.source.script.ScriptRequest
import com.ebook.source.script.ScriptTransport
import com.ebook.source.script.ScriptUrlOption
import com.ebook.source.script.ScriptUrlOptions
import com.ebook.source.script.jsonText
import com.ebook.source.script.mapToTexts
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale

private const val TAG = "JsCallbackProxy"

/** `post(url, {对象})` 的编码口径；键序见 [JsCallbackProxy.formBody] 的注释 */
private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"

/** 必须有请求体的方法：OkHttp 对 `method("POST", null)` 抛未类型化的 IllegalArgumentException */
private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")

/**
 * 长连接的客户端与「本次任务的代理」之间唯一的换挡处。
 *
 * 存在的理由是一条类型冲突：`JsSandboxClient` 的 `hostHandler` 在构造时定死（它是一条 binder
 * 连接上的策略，连接活多久它活多久），而回调代理的状态必须是任务级的。两者不能是同一个对象，
 * 又不能按线程找（回调跑在 binder 线程池线程上，与发起 `execute` 的协程线程不同），
 * 所以「现在该交给谁」只能显式挂着。
 *
 * 「同一时刻只有一个任务在飞」由 `JsSandboxClient` 的串行锁保证，这里就不必 CAS。
 * 将来谁放开那把锁的并发，这里必须换成按调用身份分发的表——出错的症状不是变慢，
 * 是**两个源的脚本互相看到对方的变量与当前页面**。
 */
internal class HostCallbackRouter : HostHandler {

    @Volatile
    private var current: JsCallbackProxy? = null

    override fun handle(api: String, argsJson: String): HostReply =
        current?.handle(api, argsJson)
            ?: HostReply(false, null, "主进程没有正在进行的脚本任务，$api 无处受理")

    /** 在 [proxy] 任职期间执行 [block]（通常就是一次 `client.execute`），回块的结果 */
    fun <T> withProxy(proxy: JsCallbackProxy, block: () -> T): T {
        check(current == null) { "上一次任务的代理还没收尾：回调里再发起执行不受支持" }
        current = proxy
        return try {
            block()
        } finally {
            current = null
        }
    }
}

/**
 * 主进程侧受理 host 回调的代理：脚本能对外界做的所有事都收在这一个类里。
 *
 * **[HostHandler] 的契约是「不得抛出」**：调用栈的另一头是 `:js` 进程里 `.so` 的 JNI 帧，异常
 * 穿出去最坏是带走整个执行器进程（Task 6 的客户端外面还兜了一层，两层都要在——少任何一层，
 * 症状都只是「这一源解不开、沙箱进程没」）。所以本类的失败一律是 `ok=false` 加一句可诊断的话。
 *
 * **实例作用域 = 单次解析任务**（一本书的一轮求值）：外呼计数、回调深度、当前页面基准都活在这里。
 * 做成源级复用，症状是「读完第一本书之后所有书的外呼配额已被用光」；做成进程级单例更糟——
 * `java.put` / `@put:` 的作用域会静默从任务级变成全局级，而规格 §5.2 定的就是任务级。
 *
 * **嵌套求值的 JS 闸门在调用方身上**：[evaluateNested] 必须收到一个**关掉 JS** 的求值器
 * （Task 10 装配时给）。沙箱只有一个 runtime 且此刻正攥在外层 `execute` 手上，嵌套再要一次 JS
 * 就是双向死锁；这条要求编译期传不出来，所以 [limits.maxCallbackDepth] 是它的绊线（正常恒为 1）。
 *
 * 可变字段一律 `@Volatile`：初值写在调用 `execute` 的线程上（构造时），之后的读写发生在受理回调的
 * binder 线程，而 binder 线程池**不保证连续两次回调落在同一条线程**。跨过这条边界的是 binder
 * 事务而不是本进程的 monitor，JMM 的 happens-before 不在我们手里。不加原子性是因为
 * 「同一时刻只有一帧在飞」（客户端的锁 + 执行器单工作线程）已经排除了并发读-改-写。
 *
 * 可见性：`internal` 够用——装配点（Task 10 的 `SandboxModule`）就在本模块。将来若把装配上浮到
 * `lib_book_common`，本类要连同 `JsNetworkGuard`、`SourceHostAllowlist`、`GuardedDns` 一起上浮，
 * 那是**三处**，别只改本类的 `internal`（Kotlin 的 internal 只在同模块可见，依赖方的 test source
 * set 也不是 friend module）。
 */
internal class JsCallbackProxy(
    /** 源级复用件：无状态，只答「这个 URL / 这个地址能不能碰」 */
    private val guard: JsNetworkGuard,
    /** 取体接缝：2c 起就是假件接缝，所以本类能在 JVM 上测透；实现按选项 charset 显式字节解码 */
    private val transport: ScriptTransport,
    /** `responseCode` 的落点：生产接线给 [HeadStatusProbe.status]，测试给常量 */
    private val statusProbe: suspend (String) -> Int,
    /** 与 `EvalContext.variables` **同一个实例**：`java.put` 写进去的量 `{{k}}` 要立刻读得到 */
    private val variables: MutableMap<String, String>,
    /** 关掉 JS 的嵌套求值口：(规则串, 输入) → 结果，由 Task 10 提供实现 */
    private val evaluateNested: (String, RuleValue) -> RuleResult,
    private val limits: JsLimits = JsLimits(),
    /** 日志出口：测试注入收集器；真实现按级别走 Logger（toast 是 info、log 是 debug） */
    private val logSink: (channel: String, message: String) -> Unit = { channel, message ->
        if (channel == "toast") Logger.i(TAG, message) else Logger.d(TAG, message)
    },
    initialPage: String = "",
    initialUrl: String = "",
) : HostHandler {

    @Volatile
    private var requests = 0

    @Volatile
    private var depth = 0

    @Volatile
    private var currentText = initialPage

    @Volatile
    private var currentUrl = initialUrl

    /** 嵌套求值回传的形态：整份列表还是首个条目（单值时取文本还是外形态由 accessor 定） */
    private enum class NestedShape { LIST, SINGLE }

    override fun handle(api: String, argsJson: String): HostReply {
        val capability = JsHostApi.entries.firstOrNull { it.name == api }
            ?: return HostReply(false, null, "沙箱里没有「$api」这个能力")
        if (capability.target == JsApiTarget.COMPUTE) {
            return refuse("「$api」是执行器进程内的纯计算能力，不经主进程")
        }
        // runBlocking：handle 是同步的（HostHandler 契约）而传输层是 suspend。
        // 阻塞的是受理回调的 binder 线程，IO 在 Dispatchers.IO 上，不碰主线程。
        return runCatching { runBlocking { dispatch(capability, argsOf(capability, argsJson)) } }
            .getOrElse { failure ->
                if (failure is JsApiRejectedException) {
                    // 自己写的拒绝话术已经说过人话，原样回，不再套一层类型名
                    refuse(failure.message ?: "能力调用被主进程拒绝")
                } else {
                    refuse(
                        failure.message?.takeIf { it.isNotBlank() }
                            ?.let { "${failure.javaClass.simpleName}：${it.take(120)}" }
                            ?: "主进程代理失败（${failure.javaClass.simpleName}）",
                    )
                }
            }
    }

    private fun dispatch(capability: JsHostApi, args: JsonArray): HostReply = when (capability) {
        JsHostApi.AJAX, JsHostApi.LOAD -> {
            // 选项解析刻意在 send 之前完成：webView/proxy/POST-无-body 这类「根本不该发出去」的
            // 参数错误必须发生在准入与计数之前，否则一条写坏的规则就能白烧配额
            val url = args[0].jsonText()
            val options = optionsOf(capability, args.getOrNull(1))
            send(capability.name, url, options)
        }

        JsHostApi.POST -> send(capability.name, args[0].jsonText(), postOptions(args[1]))
        JsHostApi.RESPONSE_CODE -> probe(args[0].jsonText())
        JsHostApi.PUT_VAR -> writeVar(args[0].jsonText(), args[1].jsonText())
        JsHostApi.GET_VAR -> HostReply(
            true,
            variables[args[0].jsonText()]?.let { JsonPrimitive(it) } ?: JsonNull,
            null,
        )

        JsHostApi.REMOVE_VAR -> {
            variables.remove(args[0].jsonText())
            HostReply(true, JsonNull, null)
        }

        // 上游 putToPage 的落点是「本页缓存」，本仓阶段一没有页缓存持久层（`cache` 一族明确不支持），
        // 故与 putVar 同落任务变量表。留着这个名字只为让抄来的脚本不撞 ReferenceError。
        JsHostApi.PUT_TO_PAGE -> writeVar(args[0].jsonText(), args[1].jsonText())
        JsHostApi.GET_ELEMENTS -> nested(args[0].jsonText(), NestedShape.LIST, AccessorKind.ALL)
        JsHostApi.GET_ELEMENT -> nested(args[0].jsonText(), NestedShape.SINGLE, AccessorKind.ALL)
        JsHostApi.QUERY_STRING -> nested(args[0].jsonText(), NestedShape.SINGLE, AccessorKind.TEXT)
        JsHostApi.TOAST, JsHostApi.LOG -> {
            logSink(capability.name, args[0].jsonText())
            HostReply(true, JsonNull, null)
        }

        else -> refuse("「${capability.name}」不由主进程受理")
    }

    // —— 网络：准入 → 限流 → 外呼 → 换基准 ——

    /**
     * 准入与限流都在真正外呼**之前**：被守门器拒掉的请求不该发出去（那是白名单的意义），
     * 也不该烧配额（否则一条写坏的规则会把这一轮的外呼机会吃光，用户看到的却是「请求太多」）。
     */
    private fun admit(api: String, url: String): String {
        val host = guard.check(url)
        if (requests >= limits.maxRequestsPerTask) {
            throw JsApiRejectedException(
                "本轮任务已用满 ${limits.maxRequestsPerTask} 次外呼上限（$api → $host），后续请求被拒",
            )
        }
        requests++
        return host
    }

    private suspend fun send(api: String, url: String, options: ScriptUrlOptions): HostReply {
        val request = requestOf(url, options)
        if (request.body == null && request.method.uppercase(Locale.US) in BODY_REQUIRED_METHODS) {
            // 交给传输层的话会撞 OkHttp 的未类型化 IllegalArgumentException（"method POST must
            // have a request body"），那句话在脚本看来跟「代理坏了」没区别
            throw JsApiRejectedException("「$api $url」声明了 ${request.method} 却没给 body，代理不代发")
        }
        val host = admit(api, url)
        val text = await(api, host) { transport.execute(request) }
        val bytes = utf8Length(text)
        if (bytes > limits.maxHostReplyBytes) {
            // 基准不推进：把「过大」的一页认作当前页，随后的 getElements 就会定位到脚本没见过的页面
            throw JsApiRejectedException(
                "「$api $host」的响应 $bytes 字节，超单次回调上限 ${limits.maxHostReplyBytes}",
            )
        }
        currentText = text
        currentUrl = url
        return HostReply(true, JsonPrimitive(text), null)
    }

    private suspend fun probe(url: String): HostReply {
        val host = admit("responseCode", url)
        val code = await("responseCode", host) { statusProbe(url) }
        return HostReply(true, JsonPrimitive(code), null)
    }

    /**
     * 一次外呼的等待。`withTimeout` 兜的是**没配 timeoutMs 的请求**：`ScriptTransport` 现在还不
     * 响应协程取消（2d 已登记为待办），阻塞中的 OkHttp 调用不会被这里打断，真正的上限是客户端
     * 自己的 connect/read 超时。这条 await 保证的是**回调线程不被无限期占住**——否则执行器那个
     * 唯一的工作线程也跟着卡在 binder 调用上，后面排队的源会一起等。
     */
    private suspend fun <T> await(api: String, host: String, block: suspend () -> T): T =
        try {
            withTimeout(limits.wallClockMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw JsApiRejectedException(
                "「$api $host」在 ${limits.wallClockMs}ms 内没回结果（超时按请求失败处置）",
            )
        }

    private fun requestOf(url: String, options: ScriptUrlOptions) = ScriptRequest(
        url = url,
        method = options.method,
        headers = options.headers,
        body = options.body,
        charset = options.charset,
        timeoutMs = options.timeoutMs,
        retry = options.retry,
    )

    /**
     * `ajax`/`load` 的第 2 个参数。**裸字符串按 charset 处理**是本仓规定（上游文档没给这一项答案，
     * 而 `java.ajax(url, 'gbk')` 这种写法在语料里真实存在）；对象则整份交给 [ScriptUrlOption.parse]，
     * 与规则尾段 `{...}` **共用同一套键与同一份拒绝集**——两处各解一遍就会长出口径差，
     * 那类差别的症状是「URL 里不认的选项到脚本里就认了」。
     */
    private fun optionsOf(api: JsHostApi, arg: JsonElement?): ScriptUrlOptions = when (arg) {
        null, is JsonNull -> ScriptUrlOptions.DEFAULT
        is JsonPrimitive -> ScriptUrlOptions(charset = arg.jsonText())
        is JsonObject -> ScriptUrlOption.parse(arg, "${api.name} 的第 2 个参数")
        else -> throw JsApiRejectedException(
            "「${api.name}」的第 2 个参数只能是选项对象或 charset 字符串",
        )
    }

    private fun postOptions(body: JsonElement): ScriptUrlOptions = when (body) {
        is JsonObject -> ScriptUrlOptions(
            method = "POST",
            body = formBody(body),
            headers = mapOf("Content-Type" to FORM_CONTENT_TYPE),
        )

        // 字符串体不猜 Content-Type：上游与语料在这一支上两种写法都有（JSON 与表单），
        // 猜错的表现是站点回 400 而不是本地报错，比让脚本自己写头更难查
        else -> ScriptUrlOptions(method = "POST", body = body.jsonText())
    }

    /**
     * 表单体：键**按字典序**排，而不是保留 JSON 里的声明顺序。
     *
     * 排序换来的是「同一份脚本、同一次调用永远发同一份请求体」——服务端签名与缓存键常对请求体
     * 字节序敏感，不排序会让偶发的键序变化长成难以复现的「这一源时好时坏」。代价是极少数按键序
     * 区分语义的站点解不对，那种源要的是手写 body，本来就不走这一支。
     */
    private fun formBody(fields: JsonObject): String =
        fields.entries.sortedBy { it.key }.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value.jsonText())}"
        }

    /** 用 `encode(String, String)` 重载：`Charset` 版在低 API 上不保证可用 */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // —— 递归规则求值 ——

    /**
     * 把一条规则串交回解释器，输入是当前页面。
     *
     * [shape] 与 [accessor] 一起定形态：`getElements` 是「全部条目 × 外形态 HTML」——脚本拿到条目
     * 后最常见的动作是再定位一次，压成纯文本就选不中了；`getElement` 同样给外形态但只回首个；
     * `queryString` 要的是值，所以取纯文本。文本/JSON 形态的结果本身已是值，accessor 不去假装影响它们。
     *
     * 「解不动」（[evaluateNested] 抛类型化异常）与「没命中」（`Miss`）分开：前者回 `ok=false` 带原文，
     * 后者回空数组 / null。把前者折叠成空等于对脚本撒谎，也就让它那句 `if (res.length > 0)` 悄悄走了
     * 另一支——与 2b 定下的「未取到值必须是 Miss」是同一条口径的两端。
     */
    private fun nested(rule: String, shape: NestedShape, accessor: AccessorKind): HostReply {
        if (depth >= limits.maxCallbackDepth) {
            throw JsApiRejectedException(
                "嵌套规则求值已到深度上限 ${limits.maxCallbackDepth}：嵌套求值不得再要 JS（阶段一刻意关掉）",
            )
        }
        depth++
        val result = try {
            evaluateNested(rule, RuleValue.Page(currentText, currentUrl))
        } finally {
            depth--
        }
        val texts = result.texts(accessor)
        return when (shape) {
            NestedShape.LIST -> HostReply(true, JsonArray(texts.map { JsonPrimitive(it) }), null)
            NestedShape.SINGLE -> HostReply(true, texts.firstOrNull()?.let { JsonPrimitive(it) } ?: JsonNull, null)
        }
    }

    private fun RuleResult.texts(accessor: AccessorKind): List<String> = when (this) {
        is RuleResult.Nodes -> mapToTexts(accessor).values
        is RuleResult.Texts -> values
        is RuleResult.Jsons -> items.map { it.jsonText() }
        is RuleResult.Matches -> items.mapNotNull { it.firstOrNull() }
        RuleResult.Miss -> emptyList()
    }

    // —— 变量与兜底 ——

    private fun writeVar(key: String, value: String): HostReply {
        variables[key] = value
        return HostReply(true, JsonNull, null)
    }

    private fun refuse(message: String): HostReply = HostReply(false, null, message)

    /**
     * 实参解码 + 元数校验。
     *
     * 元数在这里**再判一次**不是重复垫片的活：垫片里的 arity 检查只在走 `java.xxx` 时生效，
     * 而 `__host_call` 是 globalThis 上的普通属性，脚本可以直接调它（Task 7 为此让 `HostDispatcher`
     * 也判一遍表名）。不判元数的后果是 `args[1]` 抛 `IndexOutOfBoundsException`，回给脚本的是
     * 「主进程代理失败」这种把作者引向错误方向的话。
     */
    private fun argsOf(api: JsHostApi, argsJson: String): JsonArray {
        val parsed = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
        if (parsed !is JsonArray) {
            throw JsApiRejectedException("「${api.name}」的实参不是 JSON 数组：${argsJson.take(60)}")
        }
        if (parsed.size < api.minArgs || parsed.size > api.maxArgs) {
            throw JsApiRejectedException(
                "「${api.name}」参数个数不符（需要 ${api.minArgs} 到 ${api.maxArgs} 个，实到 ${parsed.size}）",
            )
        }
        return parsed
    }
}

/**
 * `responseCode` 的落点：一次 HEAD 探测，只看状态码。
 *
 * 与上游的差别（阶段一，Task 11 记进规格）：上游读的是「上一次请求留在那儿的状态码」，那需要一个
 * 跨回调存活的状态槽。这里改成「对目标 URL 探一次」——脚本要状态码，问的就是「这个地址能不能访问」，
 * 而无状态意味着它不受回调顺序影响。准入与限流复用同一条 [admit]（在代理那一侧），所以探测既出不了
 * 白名单，也不比取页多花一次配额。
 *
 * 非 2xx 不是异常：状态码本身就是脚本要的答案。网络层真失败时 `IOException` 原样往上走，由代理
 * 换成 `ok=false`，与「真实网络失败」同一条路径。
 */
internal class HeadStatusProbe(private val client: OkHttpClient) {

    suspend fun status(url: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).method("HEAD", null).build()
        client.newCall(request).execute().use { response -> response.code }
    }
}

/**
 * 主进程侧「带守门的网络」的唯一构造点。
 *
 * `newBuilder()` 共享连接池与线程池、只换 DNS。取体与探测**必须用同一份派生客户端**：Task 5 把
 * 地址判定挂在 OkHttp 真正解析的那一次上（不留 DNS 重绑窗口），若两条路径各建各的客户端，
 * 就会出现「探测放行、取体被拒」这种半截防线。
 *
 * 传入的 `base` 必须是 `@Named("source")` 那个纯净客户端——第三方站点永远拿不到用户 token。
 */
internal object GuardedNetwork {

    fun clientFor(base: OkHttpClient, guard: JsNetworkGuard): OkHttpClient =
        base.newBuilder().dns(GuardedDns(guard)).build()
}
```

- [x] **Step 4: 跑绿**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*JsCallbackProxyTest*" --tests "*JsNetworkGuardTest*" --tests "*JsSandboxClientTest*" --tests "*JsHostApiTest*"`
Expected: 全绿。后三个是回归——本任务不改它们的实现，只是让它们**终于有了消费者**。

Run: `./gradlew :lib_book_source:assembleDebug`
Expected: `BUILD SUCCESSFUL`，且新文件不带来任何编译警告（`NestedShape` / `await` / `HeadStatusProbe` 都有使用者；若报 unused，说明 `dispatch` 少接了一个分支而不是写多了代码）。

- [ ] **Step 5: 提交（需授权）**

```bash
git add lib_book_source/src/main/java/com/ebook/source/sandbox/JsCallbackProxy.kt \
        lib_book_source/src/test/java/com/ebook/source/sandbox/JsCallbackProxyTest.kt
```

提交信息（**提交动作需用户明示授权**）：

```
feat(lib_book_source): 新增主进程侧脚本回调代理

受理 ajax/load/post/responseCode 的受限外呼（白名单准入 + 每任务
配额 + 复用显式字节解码的传输接缝），getElements/getElement/
queryString 的递归规则求值（关掉 JS 的求值口 + 深度绊线），
putVar/getVar/rmVar 与任务变量表同址，toast/log 落 Logger。
策略面全在 JVM 单测跑通，无需设备。
```

---

### Task 10：接线——把执行器挂到五处「JS 待执行」抛点上

到这一步，内核能跑、进程能连、代理能受理，但规则层仍然在五个地方直接抛「需脚本沙箱执行器」。Task 10 是那五个抛点唯一改口的地方，也是整个计划里**唯一一次让用户可达路径发生变化**的改动：装好沙箱的设备上，含 `@js:` / `{{JS 表达式}}` / `js` / `bodyJs` / JS 形态 `init` 的规则从此真求值。

五条关键事实，写代码前先读完：

1. **挂载点是 `EvalContext`，不是 `ScriptRuleEvaluator`。** 求值器与上下文各有五个构造点，看着对称，实则不然：`js`/`bodyJs` 两个选项必须在**取文层**消费（`ScriptPageFetcher` → `ScriptUrlResolver`，两者都是无状态的 `object`/类字段，拿不到求值器），而取文层一路能摸到的可变对象只有 `ctx`。把桥挂在求值器上就得给 `resolve`/`fetchPage` 各加一个参数、并让五个求值器构造点全部改签名，一个也省不掉；挂在 `ctx` 上则求值层与取文层从同一个位置读，`null` 的语义也只有一处判据。

2. **「没有沙箱」与「脚本跑坏了」必须是两种结果**（2b 定下的口径，Task 6 的 `JsInterpreter` KDoc 又重申一次）。判据只有 `ctx.js == null` 一个：null 时**继续抛 `JsEvaluationPendingException`，原消息一个字不改**，于是本任务落地中途现有 330 例全绿、装配失败或未接线的设备上行为与 2d 完全一致；非 null 而这一轮失败才是 `JsExecutionFailedException`。不设 `isAvailable`——真探测本身就是一次跨进程调用。

3. **`JsMode` 由主进程消费，桥接层不看模式。** Task 7 的 `JsRuntimeBridge.evaluate` 把 `BOOTSTRAP + "\n" + source` 直接喂 `nativeEval`，完成值取不到时回落 `globalThis.result`；因此模式差异**全部表达在主进程拼出的 wrapper 脚本里**，帧上的 `mode` 只是诊断字段。这么定的收益是加一种模式不碰 C++、也不碰垫片：`__bind` 只提升 `result`/`baseUrl`/`src`/`key`/`page`/`title`/`book`/`chapter` 这八个名字，wrapper 自己读 `__bindings` 原始对象拿 `url`/`headersJson`。**代价**是同一段脚本在 `js` 选项与 `@js:` 段里看到的变量集不同——写进 KDoc，别让下一个人去猜。

4. **代理按「一次沙箱调用」建，不按「一次解析任务」建。** Task 9 的代理带着 `currentText` 基准与 `requests` 配额两样可变状态：基准必须等于**本次调用的输入页**（`@js:` 跑在正文页、`bodyJs` 跑在响应体上、`init` 跑在详情页上，同一轮解析任务里三者不同），复用单实例会让第二次调用拿着上一次的页面去受理 `getElements`。于是配额的实际含义是「每次沙箱调用最多 N 次外呼」——比按任务算更严格，也更好对得上话术（消息里就写「本轮」）。变量表**按引用**共用（Task 9 的同一实例约定），跨调用不丢。

5. **`ScriptUrlOptions` 长出 `js`/`bodyJs` 两栏是刻意的「先记下、晚一点跑」。** 选项解析层只负责识别键与拒绝不该有的键；跑脚本是取文层的事。若在 `ScriptUrlOption.parse` 里就地执行，`a||b` 兜底支的 URL 选项也会被跑（副作用前置，脚本可能已经发过一次请求），而 `ScriptUrlResolver` 是 `object`、根本拿不到传输层。

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptJsBridge.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/script/SandboxScriptJs.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxHost.kt`
- Create: `lib_book_source/src/test/java/com/ebook/source/script/SandboxScriptJsTest.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/EvalContext.kt`（`js` 挂载位 + `variables` 进构造 + `withoutJs()`）
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt:162`（`RuleMode.JS` 分支）与新增 `evaluateInitObject`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/Interpolation.kt:131-137`（`resolve` 回落 JS）
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptUrlOption.kt`（`js`/`bodyJs` 改为携带 + `ScriptUrlOptions` 两个新字段）
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptHttp.kt`（`fetchPage` 跑 `js` → 请求 → 跑 `bodyJs`）
- Modify: `lib_book_source/src/main/java/com/ebook/source/sandbox/JsCallbackProxy.kt`（`HostCallbackRouter` 公开化、`current` 改持 `HostHandler`）
- Modify: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt`（`jsHost` 注入、`newContext` 装配、`init` 的 JS 分支）
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt`（`JsEvaluationPendingException` 的 KDoc 收窄含义，**消息不动**）
- Create: `lib_book_common/src/main/java/com/ebook/common/di/SandboxModule.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt`（注入 `jsHost` 并传给脚本分支）
- Modify: `lib_book_source/src/test/java/com/ebook/source/script/ScriptUrlOptionTest.kt:85-86`、`InterpolationTest.kt:63-76`、`ScriptUrlResolverTest.kt:52`、`ScriptContentPagerTest.kt:100`、`ScriptRuleEvaluatorCombinatorTest.kt:119`（改口径，见 Step 5）
- Modify: `lib_book_source/src/test/java/com/ebook/source/script/ScriptHttpTest.kt`（`js`/`bodyJs` 两例）
- Modify: `docs/superpowers/plans/2026-09-09-js-sandbox-executor.md` 的「文件结构」段（`SandboxModule.kt` 落 `lib_book_common`，理由见 Step 6）

> **Hilt 装配为什么落在 `lib_book_common` 而不是计划原先写的 `lib_book_source`**：`lib_book_source` 今天没有 Hilt/KSP 依赖（`build.gradle.kts` 只有 library 约定插件），为一个 `@Provides` 给它加两行插件 + KSP 配置不值；而装配点需要的三样东西（`@ApplicationContext`、`@Named("source")` 纯净客户端、`JsSandboxConnector`）在 `lib_book_common` 全都现成可见（它已应用 `xrn1997.hilt`，`di/` 包下已有五个模块）。代价是 `JsSandboxHost`/`HostCallbackRouter` 两个类从 `internal` 上浮为 public——它们本身就是装配面，上浮是对的；`JsCallbackProxy`、`HeadStatusProbe`、`SandboxScriptJs` 全部留在 internal。

- [x] **Step 1: 写失败测试**

创建 `lib_book_source/src/test/java/com/ebook/source/script/SandboxScriptJsTest.kt`。这张表锁的是**五种模式的包装与解包**，以及「代理按调用建、基准随调用输入走、变量表跨调用共用」这条 Task 9 与 Task 10 之间的契约——它是本任务唯一能在 JVM 上验的东西，剩下的都要设备。

```kotlin
package com.ebook.source.script

import com.ebook.source.sandbox.HostHandler
import com.ebook.source.sandbox.JsCallbackProxy
import com.ebook.source.sandbox.JsInvocation
import com.ebook.source.sandbox.JsMode
import com.ebook.source.sandbox.JsNetworkGuard
import com.ebook.source.sandbox.JsOutcome
import com.ebook.source.sandbox.JsStatus
import com.ebook.source.sandbox.SourceHostAllowlist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主进程侧「模式包装 + 结果解包」的锁形，以及沙箱回调代理的挂载时序。
 *
 * `executeTask` 是唯一的接缝：真的接线里它等于 `JsSandboxHost::call`（挂代理 + 走客户端），
 * 这里换成记录器，于是五种模式各自的 wrapper 文本、绑定表、以及 `JsOutcome` 到 `RuleResult`
 * 的映射全在 JVM 上可断言。代理是**实现内部造的**（Task 9 的 `JsCallbackProxy`），
 * 所以记录器顺手把 handler 交出来，用 `handle(...)` 直接驱动它验递归与网络。
 */
class SandboxScriptJsTest {

    private val invocations = mutableListOf<JsInvocation>()
    private val handlers = mutableListOf<HostHandler>()
    private var outcome = JsOutcome(JsStatus.OK, JsonPrimitive("文本"))

    private val transport = object : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        var reply = "响应体"
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return reply
        }
    }

    private val guard = JsNetworkGuard(
        SourceHostAllowlist.of("https://example.com/book/1.html", emptyList()),
    )

    private val ctx = EvalContext(baseUrl = "https://example.com/ch/1.html", key = "斗破", page = 3)

    private val bridge = SandboxScriptJs(
        executeTask = { handler, invocation ->
            handlers += handler
            invocations += invocation
            outcome
        },
        ctx = ctx,
        guard = guard,
        transport = transport,
        staticBindings = mapOf("bookJson" to """{"name":"书"}""", "chapterJson" to null),
    )

    private fun segment(text: String) = bridge.runSegment("result", RuleValue.Page(text, "https://example.com/ch/1.html"))

    private fun proxy(): JsCallbackProxy = handlers.last() as JsCallbackProxy

    @Test
    fun `段执行原样送脚本并带上当下页面与量表绑定`() {
        val result = segment("第一章 开局")
        assertEquals(RuleResult.Texts(listOf("文本")), result)
        val inv = invocations.single()
        assertEquals(JsMode.SEGMENT, inv.mode)
        // SEGMENT 不套 wrapper：完成值口径由桥接层负责，加一层反而会吃掉「最后一条语句即结果」
        assertEquals("result", inv.source)
        assertEquals("第一章 开局", inv.bindings["result"])
        assertEquals("https://example.com/ch/1.html", inv.bindings["baseUrl"])
        assertEquals("斗破", inv.bindings["key"])
        assertEquals("3", inv.bindings["page"])
        assertEquals("""{"name":"书"}""", inv.bindings["bookJson"])
        // 明确不提供 src（源规则原文）与 title（章节名）：一个占报文预算、一个要查库才拿得到
        assertNull(inv.bindings["src"])
        assertNull(inv.bindings["title"])
        assertNull(inv.bindings["chapterJson"])
    }

    @Test
    fun `数组完成值展开成多条文本`() {
        outcome = JsOutcome(JsStatus.OK, JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
        assertEquals(RuleResult.Texts(listOf("a", "b")), segment("[]"))
    }

    @Test
    fun `对象完成值按 JSON 文本回填`() {
        outcome = JsOutcome(
            JsStatus.OK,
            JsonObject(mapOf("k" to JsonPrimitive("v"))),
        )
        assertEquals(RuleResult.Texts(listOf("""{"k":"v"}""")), segment("{}"))
    }

    @Test
    fun `脚本跑成 undefined 是没取到值而不是失败`() {
        outcome = JsOutcome(JsStatus.OK, null)
        assertEquals(RuleResult.Miss, segment("undefined"))
    }

    @Test
    fun `数组里全是对象时按没取到值处置而不是空文本`() {
        outcome = JsOutcome(JsStatus.OK, JsonArray(listOf(JsonObject(emptyMap()), JsonNull)))
        assertEquals(RuleResult.Miss, segment("[]"))
    }

    @Test
    fun `脚本失败抛类型化异常绝不折叠成 Miss`() {
        outcome = JsOutcome(JsStatus.TIMEOUT, error = "超过 4000ms")
        val error = runCatching { segment("while(true);") }.exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is JsExecutionFailedException)
        assertEquals(JsStatus.TIMEOUT, (error as JsExecutionFailedException).status)
        assertTrue(error.message!!.contains("TIMEOUT"))
    }

    @Test
    fun `表达式模式取标量文本且数字也认`() {
        outcome = JsOutcome(JsStatus.OK, JsonPrimitive(40))
        assertEquals("40", bridge.runExpression("(page-1)*20"))
        assertEquals(JsMode.EXPRESSION, invocations.single().mode)
        assertEquals("(page-1)*20", invocations.single().source)
        // 展开发生在切分之前，URL 类调用点此时还没有页面文本：result 显式 null，不猜一个基准
        assertNull(invocations.single().bindings["result"])
    }

    @Test
    fun `表达式求值为空按失败报而不是把 undefined 拼进 URL`() {
        outcome = JsOutcome(JsStatus.OK, null)
        val error = runCatching { bridge.runExpression("book.name") }.exceptionOrNull() as JsExecutionFailedException
        assertTrue(error.message!!, error.message!!.contains("求值结果为空"))
    }

    @Test
    fun `js 选项的 wrapper 读绑定里的 url 与 headers 并回传对象`() {
        outcome = JsOutcome(
            JsStatus.OK,
            JsonObject(
                mapOf(
                    "url" to JsonPrimitive("https://example.com/a?t=1"),
                    "headers" to JsonObject(mapOf("Referer" to JsonPrimitive("https://example.com/"))),
                ),
            ),
        )
        val rewritten = bridge.runUrlJs(
            "url = url + '?t=1'; headers['Referer'] = 'https://example.com/';",
            "https://example.com/a",
            mapOf("User-Agent" to "UA"),
        )
        val inv = invocations.single()
        assertEquals(JsMode.URL_OPTION, inv.mode)
        assertTrue(inv.source, inv.source.startsWith("var url = __bindings.url;"))
        assertTrue(inv.source, inv.source.contains("var headers = JSON.parse(__bindings.headersJson);"))
        assertTrue(inv.source, inv.source.endsWith("result = ({url: url, headers: headers});"))
        // 用户脚本夹在中间：两头都是我们拼的
        assertTrue(inv.source, inv.source.contains("url = url + '?t=1'"))
        assertEquals("https://example.com/a", inv.bindings["url"])
        assertEquals("""{"User-Agent":"UA"}""", inv.bindings["headersJson"])
        assertEquals("https://example.com/a?t=1", rewritten.url)
        assertEquals("https://example.com/", rewritten.headers["Referer"])
        assertEquals("UA", rewritten.headers["User-Agent"])
    }

    @Test
    fun `js 选项没给出 url 时当场失败而不是回退原地址`() {
        outcome = JsOutcome(JsStatus.OK, JsonObject(mapOf("headers" to JsonObject(emptyMap()))))
        val error = runCatching { bridge.runUrlJs("1", "https://example.com/a", emptyMap()) }.exceptionOrNull() as JsExecutionFailedException
        // 回退原 URL = 发出一个作者明确不要的请求，比解析失败更糟
        assertTrue(error.message!!, error.message!!.contains("没有给出 url"))
    }

    @Test
    fun `js 选项回传标量时按运行失败报并说清看到的是什么`() {
        outcome = JsOutcome(JsStatus.OK, JsonPrimitive("https://example.com/a"))
        val error = runCatching { bridge.runUrlJs("1", "https://example.com/a", emptyMap()) }.exceptionOrNull() as JsExecutionFailedException
        assertTrue(error.message!!, error.message!!.contains("实到 标量"))
    }

    @Test
    fun `bodyJs 以 result 变量进出并回新文本`() {
        outcome = JsOutcome(JsStatus.OK, JsonPrimitive("净化后"))
        assertEquals("净化后", bridge.runBodyJs("result = result + ''", "https://example.com/x", "原始正文"))
        val inv = invocations.single()
        assertEquals(JsMode.BODY_JS, inv.mode)
        assertTrue(inv.source, inv.source.endsWith("\n;result"))
        assertEquals("原始正文", inv.bindings["result"])
        // `baseUrl` 绑定恒等于 ctx 的页面基准；本次请求的地址在 `url` 绑定里（两者不是一回事）
        assertEquals("https://example.com/ch/1.html", inv.bindings["baseUrl"])
        assertEquals("https://example.com/x", inv.bindings["url"])
    }

    @Test
    fun `init 的 JS 分支回对象时字段按键取`() {
        outcome = JsOutcome(
            JsStatus.OK,
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("斗破"),
                    "author" to JsonPrimitive("天蚕土豆"),
                    "intro" to JsonNull,
                    "tags" to JsonArray(listOf(JsonPrimitive("玄幻"))),
                ),
            ),
        )
        val fields = bridge.runInit("({name:'斗破'})", RuleValue.Page("详情页", "https://example.com/b/1.html"))
        assertEquals(mapOf("name" to "斗破", "author" to "天蚕土豆", "intro" to "", "tags" to """["玄幻"]"""), fields)
        assertEquals(JsMode.INIT, invocations.single().mode)
        assertEquals("详情页", invocations.single().bindings["result"])
    }

    @Test
    fun `init 回传标量按运行失败报而不是当成没有预处理`() {
        outcome = JsOutcome(JsStatus.OK, JsonPrimitive("x"))
        val error = runCatching { bridge.runInit("@js:x", RuleValue.Page("页")) }.exceptionOrNull() as JsExecutionFailedException
        assertTrue(error.message!!, error.message!!.contains("必须回传对象"))
    }

    @Test
    fun `每次调用挂一个新代理且执行完就摘掉`() {
        // 第一次调用的代理只看得到第一页；第二次的基准跟着第二次的输入走（关键事实 4）
        segment("<p>第一页</p>")
        segment("<p>第二页</p>")
        assertEquals(2, invocations.size)
        assertNotSame(handlers[0], handlers[1])
        val first = handlers[0].handle("getElement", """["p"]""")
        assertTrue(first.error.toString(), first.ok)
        assertEquals("<p>第一页</p>", (first.data as JsonPrimitive).content)
        val second = handlers[1].handle("getElement", """["p"]""")
        assertEquals("<p>第二页</p>", (second.data as JsonPrimitive).content)
    }

    @Test
    fun `沙箱里 java_put 写的变量外层 get 读得到`() {
        segment("页")
        assertTrue(proxy().handle("putVar", """["token","abc123"]""").ok)
        assertEquals(
            "abc123",
            ScriptRuleEvaluator(ctx).evaluate("@get:token", RuleValue.Page("页")).firstText(),
        )
    }

    @Test
    fun `代理内的递归求值不再要 JS`() {
        segment("页")
        val reply = proxy().handle("getElements", """["@js:1+1"]""")
        assertFalse(reply.ok)
        // 这就是 Task 9 深度绊线之外的那道闸：嵌套求值器是「没有桥的上下文」
        assertTrue(reply.error.toString(), reply.error!!.contains("需脚本沙箱执行器"))
    }

    @Test
    fun `ajax 经注入的传输代发并过守门`() {
        segment("页")
        val reply = proxy().handle("ajax", """["https://example.com/x"]""")
        assertTrue(reply.error.toString(), reply.ok)
        assertEquals("响应体", (reply.data as JsonPrimitive).content)
        assertEquals("https://example.com/x", transport.requests.single().url)
    }

    @Test
    fun `白名单外的 host 被拒且不发出请求`() {
        segment("页")
        val reply = proxy().handle("ajax", """["https://evil.com/x"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error.toString(), reply.error!!.contains("不在本源可解析白名单"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `嵌套规则的语法错误原文回传不改写成泛泛失败`() {
        segment("页")
        // 括号不配对是 2a 定的 RuleSyntaxException 形态（不是 XPath——XPath 会走 Unsupported）
        val reply = proxy().handle("getElement", """["tag.a[href"]""")
        assertFalse(reply.ok)
        // 语法错误原文回传（Task 9 口径），代理不把它改写成一句泛泛的失败
        assertTrue(reply.error.toString(), reply.error!!.contains("规则语法无法解释"))
    }
}
```

- [x] **Step 2: 跑一遍确认它编译失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "*SandboxScriptJsTest*"`
Expected: 编译失败——未解析引用 `SandboxScriptJs`、`JsMode`、`JsOutcome`、`JsNetworkGuard`、`SourceHostAllowlist`、`JsCallbackProxy`、`HostHandler`（后四个来自 Task 4/5/9 的产物，若本任务在它们之前跑就先补那几步；`JsMode`/`JsOutcome`/`HostHandler` 来自 Task 3/6）。

- [x] **Step 3: 加规则层看到的桥接口**

创建 `lib_book_source/src/main/java/com/ebook/source/script/ScriptJsBridge.kt`：

```kotlin
package com.ebook.source.script

/**
 * 规则层看到的「会跑 JS 的东西」（规格 §5.4/§6.2/§1.4 的执行面）。
 *
 * 五个入口对应五个原本抛 [JsEvaluationPendingException] 的位置，**刻意不做成一个泛化的
 * `run(mode, source)`**：五处的输入（有没有页面）、期望产物（文本 / 标量 / 对象 / 字段表）
 * 和「跑成了但没值」该怎么处置各不相同，收成一个方法就会在调用点各写一遍解包逻辑，
 * 而解包口径正是最容易各写各的地方。
 *
 * internal：只有本模块的求值层与取文层实现它、调它。跨模块（`lib_book_common`）只经
 * `JsSandboxHost` 拿装配好的实例，看不见这个接口。
 *
 * 失败口径与 2b 一致：跑坏了抛 [JsExecutionFailedException]，**不返回 null 冒充「没取到值」**。
 * 唯一的例外是 [runSegment] 的 `undefined`——脚本被合法地跑完而完成值是 `undefined`，
 * 那是「这条规则没有值」，回 [RuleResult.Miss] 让 `||` 兜底支照常生效。
 */
internal interface ScriptJsBridge {

    /** `@js:` / `<js></js>` 整段：完成值按文本/文本表取，`undefined` 为 Miss */
    fun runSegment(source: String, input: RuleValue): RuleResult

    /** `{{…}}` 里的表达式：必须给出一个标量文本，空值按失败报（插值拼不出「半个 URL」） */
    fun runExpression(expr: String): String

    /** URL 选项 `js`：改写 `url` 与 `headers` 后回传（§6.2） */
    fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl

    /** URL 选项 `bodyJs`：对响应体做二次处理，回新文本（§6.2） */
    fun runBodyJs(script: String, url: String, body: String): String

    /** `init` 的 JS 分支：脚本回传一个对象，字段名即键（§1.4） */
    fun runInit(script: String, input: RuleValue): Map<String, String>
}

/** [ScriptJsBridge.runUrlJs] 的产物。[headers] 为脚本未改动时按原请求头回传，调用侧不必判空 */
internal data class JsRewrittenUrl(val url: String, val headers: Map<String, String>)
```

- [x] **Step 4: 实现桥**

创建 `lib_book_source/src/main/java/com/ebook/source/script/SandboxScriptJs.kt`：

```kotlin
package com.ebook.source.script

import com.ebook.source.sandbox.HostHandler
import com.ebook.source.sandbox.JsCallbackProxy
import com.ebook.source.sandbox.JsInvocation
import com.ebook.source.sandbox.JsLimits
import com.ebook.source.sandbox.JsMode
import com.ebook.source.sandbox.JsNetworkGuard
import com.ebook.source.sandbox.JsOutcome
import com.ebook.source.sandbox.JsStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [ScriptJsBridge] 的真实现：把规则层的五种载荷拼成能跑的脚本、送进沙箱、把回帧解成规则层的值。
 *
 * 三个入口参数各管一件事，缺一不可：
 * - [executeTask] 是唯一的外呼口（真接线 = `JsSandboxHost::call`：挂代理 + 走客户端）。
 *   收函数而不是收 `JsSandboxHost` 对象，是为了让这层最要紧的**包装与解包口径**能在 JVM 上测透
 *   （跨进程那部分已经由 Task 6 的假通道测过，这里再测一遍是重复）。
 * - [ctx] 现取绑定：`baseUrl`/`page` 会随翻页推进，构造时快照会在第二页拿到第一页的基准。
 * - [guard]/[transport] 交给每次调用新建的代理——沙箱里的 `java.ajax` 走的是**主进程代发**，
 *   必须过同一套白名单与限流，而不是执行器自己发（隔离进程根本没有网络）。
 *
 * 模式与 wrapper 的关系见计划开头第 3 条关键事实：`mode` 只是帧上的诊断字段，真正决定
 * 「脚本看见哪些变量、结果怎么取」的是下面拼出的文本。`staticBindings` 里的 `null` 值原样过边界，
 * 垫片把缺失与 null 都落成 `undefined`。
 */
internal class SandboxScriptJs(
    private val executeTask: (HostHandler, JsInvocation) -> JsOutcome,
    private val ctx: EvalContext,
    private val guard: JsNetworkGuard,
    private val transport: ScriptTransport,
    private val staticBindings: Map<String, String?> = emptyMap(),
    private val limits: JsLimits = JsLimits(),
    /** `responseCode` 的探测口。默认值只在未接线的构造里存在，被调到就说明装配漏传了 */
    private val statusProbe: suspend (String) -> Int = {
        throw JsApiRejectedException("responseCode 探测未接线（装配时未提供守门客户端）")
    },
) : ScriptJsBridge {

    private companion object {
        /** `js` 选项：上游给脚本的是两个可写变量，成品从 `url`/`headers` 取（不是完成值） */
        const val URL_JS_HEAD =
            "var url = __bindings.url;\nvar headers = JSON.parse(__bindings.headersJson);\n"

        /** 括号是必须的：`{...}` 在语句位置会被读成代码块 */
        const val URL_JS_TAIL = "\nresult = ({url: url, headers: headers});"

        /**
         * `bodyJs` 取的是**变量**而不是最后一条语句的值：上游这类脚本的写法就是改 `result`，
         * 而它的最后一行常常是一个 `if` 或赋值语句，完成值口径会让人摸不到结果。
         */
        const val BODY_JS_TAIL = "\n;result"
    }

    override fun runSegment(source: String, input: RuleValue): RuleResult =
        when (val data = requireOk(call(JsMode.SEGMENT, source, input, null), "`@js:` 段").data) {
            null, is JsonNull -> RuleResult.Miss
            is JsonPrimitive -> RuleResult.Texts(listOf(data.content))
            // 数组是 `@js:` 常见的多值产出（一次切出好几段正文），逐标量展开；
            // 数组里裹对象则整段按没取到值处置——拼成 JSON 文本会得到一条「看着像正文的垃圾」
            is JsonArray -> {
                val texts = data.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p != JsonNull }?.content }
                if (texts.isEmpty()) RuleResult.Miss else RuleResult.Texts(texts)
            }
            // 单对象按 JSON 文本回填：`init` 之外的字段确实有作者这么写（把结构塞进一个字段），
            // 而 §6.3 已规定对象/数组以 JSON 文本形态使用，此处不另立口径
            is JsonObject -> RuleResult.Texts(listOf(data.toString()))
        }

    override fun runExpression(expr: String): String {
        val data = requireOk(call(JsMode.EXPRESSION, expr, null, null), "表达式「$expr」").data
        return (data as? JsonPrimitive)
            ?.takeIf { it != JsonNull }
            ?.content
            // 「跑成了但没值」不映射成 Miss：插值层的返回值类型是 String，
            // 让调用方拿 null 去拼 URL 就会把 `undefined` 的字面量送进请求（本仓最忌的那类坏消息）
            ?: throw JsExecutionFailedException(JsStatus.RUNTIME, "表达式「${expr.take(60)}」求值结果为空，无法作为文本回填")
    }

    override fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl {
        val data = requireOk(
            call(JsMode.URL_OPTION, URL_JS_HEAD + script + URL_JS_TAIL, null, url, headers),
            "URL 选项 js",
        ).data
        val obj = data as? JsonObject
            ?: throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "URL 选项 js 必须改写 url/headers 变量，实到 ${shapeOf(data)}",
            )
        val newUrl = (obj["url"] as? JsonPrimitive)
            ?.takeIf { it != JsonNull && it.content.isNotBlank() }
            ?.content
            ?: throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "URL 选项 js 没有给出 url（不回退原地址：那会发出一个作者不想要的请求）",
            )
        return JsRewrittenUrl(
            url = newUrl,
            headers = (obj["headers"] as? JsonObject)?.let { jsonHeaders(it) } ?: headers,
        )
    }

    override fun runBodyJs(script: String, url: String, body: String): String {
        // 响应体同时是 `result` 绑定与代理的页面基准：脚本在这里 `java.getElements` 取的就是这份响应
        val data = requireOk(
            call(JsMode.BODY_JS, script + BODY_JS_TAIL, RuleValue.Texts(listOf(body)), url),
            "URL 选项 bodyJs",
        ).data
        return (data as? JsonPrimitive)
            ?.takeIf { it != JsonNull }
            ?.content
            ?: throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "bodyJs 必须把新正文写回 result 变量，实到 ${shapeOf(data)}",
            )
    }

    override fun runInit(script: String, input: RuleValue): Map<String, String> {
        val data = requireOk(call(JsMode.INIT, script, input, null), "init 的 JS").data
        val obj = data as? JsonObject
            ?: throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "init 的 JS 必须回传对象（字段名=键），实到 ${shapeOf(data)}",
            )
        return obj.entries.associate { (key, value) -> key to scalarText(value) }
    }

    /**
     * 一次调用 = 一个新代理（理由见计划开头第 4 条关键事实）。
     * [url] 非空时它既是绑定里的 `url`，也是代理推进 `baseUrl` 的初值——脚本在 `js` 选项里
     * 递归取元素时，相对地址应当相对**这次要请求的那个地址**，而不是页面基准。
     */
    private fun call(
        mode: JsMode,
        source: String,
        input: RuleValue?,
        url: String?,
        headers: Map<String, String> = emptyMap(),
    ): JsOutcome {
        val proxy = JsCallbackProxy(
            guard = guard,
            transport = transport,
            statusProbe = statusProbe,
            variables = ctx.variables,
            evaluateNested = { rule, nestedInput ->
                ScriptRuleEvaluator(ctx.withoutJs()).evaluate(rule, nestedInput)
            },
            limits = limits,
            initialPage = input?.let(::textOf).orEmpty(),
            initialUrl = url ?: ctx.baseUrl,
        )
        return executeTask(proxy, JsInvocation(mode, source, bindingsOf(input, url, headers)))
    }

    /**
     * 静态绑定在前、当下实现在后：`bookJson`/`chapterJson` 由解析层给出（本阶段只有 book 有值），
     * 五个提升名每次调用现取。值为 null 表示「本场景没有这个量」，垫片落成 `undefined`。
     */
    private fun bindingsOf(input: RuleValue?, url: String?, headers: Map<String, String>): Map<String, String?> =
        staticBindings + mapOf(
            "result" to input?.let(::textOf),
            "baseUrl" to ctx.baseUrl,
            "key" to ctx.key,
            "page" to ctx.page.toString(),
            "src" to null,
            "title" to null,
            "url" to url,
            "headersJson" to if (url == null) null else JsonObject(headers.entries.associate { (k, v) -> k to JsonPrimitive(v) }).toString(),
        )

    /** 把任意输入收敛成脚本侧的 `result` 文本。做不到的形态回 null（undefined），不硬凑空串 */
    private fun textOf(input: RuleValue): String? = when (input) {
        is RuleValue.Page -> input.source
        is RuleValue.Texts -> input.values.firstOrNull()
        is RuleValue.Nodes -> if (input.elements.isEmpty()) null else input.elements.joinToString("\n") { it.outerHtml() }
        is RuleValue.Json -> input.element.toString()
    }

    private fun requireOk(outcome: JsOutcome, what: String): JsOutcome {
        if (!outcome.isSuccess) {
            throw JsExecutionFailedException(outcome.status, outcome.error ?: "$what 未给出失败原因")
        }
        return outcome
    }

    private fun jsonHeaders(obj: JsonObject): Map<String, String> =
        obj.entries.mapNotNull { (key, value) ->
            if (value == JsonNull) null else key to scalarText(value)
        }.toMap()

    private fun scalarText(value: JsonElement): String = when {
        value == JsonNull -> ""
        value is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun shapeOf(data: JsonElement?): String = when (data) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonPrimitive -> "标量"
        is JsonArray -> "数组"
        is JsonObject -> "对象"
    }
}
```

改 `EvalContext.kt`——`variables` 进构造参数（`withoutJs()` 要按引用共用它），`js` 是一次性赋值：

```kotlin
internal class EvalContext(
    var baseUrl: String = "",
    var key: String = "",
    var page: Int = 1,
    /**
     * 变量表。默认每次新建；[withoutJs] 走**传引用**这条路，让嵌套求值里 `@put:` 写的量
     * 外层 `@get:` 立刻读得到（Task 9 的同一实例约定）。
     */
    val variables: MutableMap<String, String> = linkedMapOf(),
) {
    /**
     * 本次任务的 JS 桥。null = 没有沙箱，规则层的 JS 段继续按 [JsEvaluationPendingException] 报。
     *
     * 为什么是 `var` 而不是构造参数：桥的构造要收 ctx（`baseUrl`/`page` 现取、变量表共用），
     * ctx 又要持有桥，做成构造参数就是一个环。赋值点**全仓只有一处**
     * （`ScriptBookParser.newContext`，先建 ctx 再回填），别处不要写它。
     */
    var js: ScriptJsBridge? = null

    /**
     * 关掉 JS 的上下文视图，供沙箱回调里的嵌套规则求值使用（规格 §5.4：递归求值不得再要 JS）。
     *
     * 变量表**按引用共用**，三个标量取当下的快照：嵌套求值里若改了 `baseUrl`/`page`，
     * 外层翻页链的基准不能跟着动——那会让下一页从内层最后一次修改的地方长出来。
     */
    fun withoutJs(): EvalContext = EvalContext(baseUrl, key, page, variables)

    fun builtin(name: String): String? = when (name) {
        "key" -> key
        "page" -> page.toString()
        "baseUrl" -> baseUrl
        else -> null
    }
}
```

> 改法是**把原来类体里的 `val variables: MutableMap<String, String> = linkedMapOf()` 一行挪进构造参数**并补 KDoc，其余引用（`ctx.variables`）一个字不用动。类 KDoc 末尾加一句：「Plan 3 起本类还挂一个 `js` 桥；`withoutJs()` 是给沙箱回调用的 JS 关闭视图」。

- [x] **Step 5: 接上五个抛点**

**(a)** `ScriptRuleEvaluator.kt:162` 的 `RuleMode.JS` 分支换成：

```kotlin
            // 没有桥 = 本机没有沙箱，原样抛待执行（消息与 2d 一致，现有锁形用例不受影响）；
            // 有桥 = 真跑，跑坏了由桥抛 JsExecutionFailedException，两种失败不混（关键事实 2）
            RuleMode.JS -> ctx.js
                ?.runSegment(expansion.fill(node.body), input)
                ?.let { if (node.reverse) it.reversedResult() else it }
                ?: throw JsEvaluationPendingException(expansion.fill(node.body))
```

同文件加一个私有扩展（§2.5 的前导 `-` 对 JS 后端只有「把结果表倒过来」一义，元素集在 JS 侧已经不存在）：

```kotlin
    /** §2.5 反序：JS 后端的产出已是文本/结构，整体倒转就是它能表达的倒序 */
    private fun RuleResult.reversedResult(): RuleResult = when (this) {
        is RuleResult.Texts -> RuleResult.Texts(values.reversed())
        is RuleResult.Nodes -> RuleResult.Nodes(elements.reversed())
        is RuleResult.Matches -> RuleResult.Matches(items.reversed())
        is RuleResult.Jsons -> RuleResult.Jsons(items.reversed())
        RuleResult.Miss -> this
    }
```

同文件再加 `init` 的 JS 分支入口（放在 `evaluateOnJsonItem` 之后，公开在求值入口这一层，`ScriptBookParser` 只多调一个方法）：

```kotlin
    /**
     * `ruleBookInfo.init` 的 JS 分支（规格 §1.4）：脚本回传一个对象，字段名即键。
     *
     * 回 null 表示「init 不是 JS 形态」，调用方回落到 AllInOne 首个匹配的老路径——
     * 判定放在这里而不是解析器里，因为只有本层能安全地看出「这条规则是不是单个 JS 段」
     * （`RuleMode.of` 会被 `||`/`&&` 组合规则骗过去，而 `@js:a||b` 的 `||` 是脚本内容的一部分）。
     */
    fun evaluateInitObject(rule: String, input: RuleValue): Map<String, String>? {
        if (rule.isBlank()) return null
        val (expansion, masked) = Interpolation.expand(rule, ctx) { inner ->
            evaluateNode(RuleSplitter.parse(inner).root, input, Expansion.EMPTY)
        }
        val leaf = RuleSplitter.parse(masked).root as? RuleNode.Leaf ?: return null
        if (leaf.mode != RuleMode.JS) return null
        val bridge = ctx.js ?: throw JsEvaluationPendingException(expansion.fill(leaf.body))
        return bridge.runInit(expansion.fill(leaf.body), input)
    }
```

**(b)** `Interpolation.kt` 的 `resolve`（131-137）换成：

```kotlin
    private fun resolve(expr: String, ctx: EvalContext, evaluateInner: (String) -> RuleResult): String {
        if (expr.startsWith("@@")) return evaluateInner(expr).firstText()
        if (IDENTIFIER.matches(expr)) {
            // 内置量与变量表都查得到就先查它们：`{{key}}` 没有理由为了一个字符串去趟沙箱
            ctx.builtin(expr)?.let { return it }
            ctx.variables[expr]?.let { return it }
        }
        // 到这一支的一律是「声明式子集解不了」：非标识符（算术、函数调用）或标识符但表里没有
        // （`book.name` 走这里）。有桥就交给 JS，没桥才是「待执行」
        return ctx.js?.runExpression(expr) ?: throw JsEvaluationPendingException(expr)
    }
```

类 KDoc 第二段同步改口径（「其余一律抛 [JsEvaluationPendingException]」→「其余交给 `ctx.js` 求值，未装配沙箱时才抛」），并保留原来那句「不原样保留、也不回退成空」的理由——它现在解释的是为什么 `runExpression` 里空值要抛。

**(c)** `ScriptUrlOption.kt:77-78` 两行抛点改成携带：

```kotlin
        // js/bodyJs 不再在此抛待执行（关键事实 5）：识别归这里，执行归 `ScriptPageFetcher`。
        // 「本机没有沙箱」的报错也在取文层发，两条路径的话术因此与 2d 完全一致。
        val js = (obj["js"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content?.trim().orEmpty()
            .takeIf { it.isNotBlank() }
        val bodyJs = (obj["bodyJs"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content?.trim().orEmpty()
            .takeIf { it.isNotBlank() }
```

并在文件末尾的 `ScriptUrlOptions` 加两栏：

```kotlin
    /** URL 选项 `js`：请求前改写 url/headers（由取文层执行）。null = 未声明 */
    val js: String? = null,

    /** URL 选项 `bodyJs`：对响应体做二次处理（由取文层执行）。null = 未声明 */
    val bodyJs: String? = null,
```

`parse` 的 `return ScriptUrlOptions(...)` 里补 `js = js, bodyJs = bodyJs`。KDoc 那句「`js`/`bodyJs` 是 JS，抛待执行等 Plan 3」改成「已改为携带，由 `ScriptPageFetcher` 执行」。

**(d)** `ScriptHttp.kt` 的 `fetchPage` 改成（`fetch` 不动，它转发本方法）：

```kotlin
    suspend fun fetchPage(
        ruleUrl: String,
        ctx: EvalContext,
        evaluateInner: (String) -> RuleResult,
    ): ScriptPage {
        val bridge = ctx.js
        var resolved = ScriptUrlResolver.resolve(ruleUrl, ctx, sourceRoot, evaluateInner)
        resolved.options.js?.let { script ->
            // 没沙箱时在这里抛待执行：位置与 2d 的解析期抛点差一步，但话术与类型都不变，
            // 而副作用次序变了才是真差异——2d 在解析选项时就抛，压根不会发出请求
            val rewritten = (bridge ?: throw JsEvaluationPendingException(script))
                .runUrlJs(script, resolved.url, resolved.options.headers)
            resolved = resolved.copy(
                url = rewritten.url,
                options = resolved.options.copy(headers = rewritten.headers),
            )
        }
        val text = transport.execute(
            ScriptRequest(
                url = resolved.url,
                method = resolved.options.method,
                headers = resolved.options.headers,
                body = resolved.options.body,
                charset = resolved.options.charset,
                timeoutMs = resolved.options.timeoutMs,
                retry = resolved.options.retry,
            ),
        )
        val bodyJs = resolved.options.bodyJs
        return ScriptPage(
            url = resolved.url,
            text = bodyJs?.let { script -> (bridge ?: throw JsEvaluationPendingException(script)).runBodyJs(script, resolved.url, text) }
                ?: text,
        )
    }
```

**(e)** 改既有测试的口径（这些用例锁的是「未装配沙箱」的行为，**保留**并补「装配了沙箱」的对偶用例）：

- `ScriptUrlOptionTest.kt:85-86`：`js`/`bodyJs` 不再抛，改成
  ```kotlin
        // 选项层只识别不执行（关键事实 5）：解析后带在身上，执行在取文层
        assertEquals("java.ajax('x')", parse("""{"js":"java.ajax('x')"}""").js)
        assertEquals("result", parse("""{"bodyJs":"result"}""").bodyJs)
        assertNull(parse("""{"js":""}""").js)      // 空串=未声明（§1.1 默认列口径）
        assertNull(parse("""{"js":""}""").bodyJs)
  ```
- `InterpolationTest.kt:63-76` 三条保留（无桥 → 仍抛待执行），加一条对偶：给 `ctx` 装一个假桥（`object : ScriptJsBridge { ... }` 只实现 `runExpression` 返回 `"40"`，其余 `error("不该走到")`），断言 `{{(page-1)*20}}` 展开成 `40`、`{{book.name}}` 也走桥。
- `ScriptUrlResolverTest.kt:52`、`ScriptContentPagerTest.kt:100`、`ScriptRuleEvaluatorCombinatorTest.kt:119`：三条断言的是**没有桥**的路径，不用改；各补一行注释「本机未装配沙箱」说明为什么这里还期望待执行。
- `ScriptHttpTest.kt` 加三例（复用该文件已有的 `RecordingTransport` 与 `fetcher(...)` 辅助，`runTest`）：
  ```kotlin
    @Test
    fun `js 选项先改写 URL 再请求`() = runTest {
        val transport = RecordingTransport()
        val ctx = EvalContext(baseUrl = "https://root.com/")
        val bridge = RecordingBridge(urlJs = "https://root.com/t?a=1")
        ctx.js = bridge
        val page = fetcher(transport).fetchPage("""https://root.com/t,{"js":"url = url + '?a=1'"}""", ctx) { RuleResult.Miss }
        assertEquals(listOf("https://root.com/t?a=1"), transport.requests.map { it.url })
        assertEquals("https://root.com/t?a=1", page.url)
        // 次序锁：改写发生在请求之前，且送进去的是解析后的绝对地址
        assertEquals(listOf("js"), bridge.calls)
    }

    @Test
    fun `bodyJs 拿到的是响应体原文、回的是新文本`() = runTest {
        val transport = RecordingTransport().apply { response = "原始正文<广告>" }
        val ctx = EvalContext(baseUrl = "https://root.com/")
        val bridge = RecordingBridge(bodyJsResult = "净化后")
        ctx.js = bridge
        val page = fetcher(transport)
            .fetchPage("""https://root.com/ch,{"bodyJs":"result = '净化后'"}""", ctx) { RuleResult.Miss }
        assertEquals("净化后", page.text)
        assertTrue(bridge.calls.single(), bridge.calls.single().contains("原始正文<广告>"))
    }

    @Test
    fun `未装配沙箱时 js 选项仍按待执行如实报且不发请求`() = runTest {
        val transport = RecordingTransport()
        val error = runCatching {
            fetcher(transport).fetchPage("""https://root.com/t,{"js":"url"}""", EvalContext()) { RuleResult.Miss }
        }.exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is JsEvaluationPendingException)
        // 关键事实 5 的副作用口径：没有沙箱就一个字节也不该发出去，与 2d 同形
        assertTrue(transport.requests.isEmpty())
    }
  ```

  桥的假件落在该测试文件底部（同模块同测试源集，`internal` 可见性够用）：

  ```kotlin
  /**
   * 只答 `js`/`bodyJs` 两问的桥。其余三个入口 `error(...)` 而不是返回空值——
   * 取文层若多调了一个入口（例如把 `runSegment` 也拿来算 URL），这里当场炸出来，
   * 而不是悄悄拿一个默认值跑绿。
   */
  private class RecordingBridge(
      val urlJs: String? = null,
      val bodyJsResult: String? = null,
  ) : ScriptJsBridge {
      val calls = mutableListOf<String>()

      override fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl {
          calls += "js"
          return JsRewrittenUrl(requireNotNull(urlJs), headers)
      }

      override fun runBodyJs(script: String, url: String, body: String): String {
          calls += "bodyJs:$url:$body"
          return requireNotNull(bodyJsResult)
      }

      override fun runSegment(source: String, input: RuleValue): RuleResult = error("取文层不该跑 @js: 段")
      override fun runExpression(expr: String): String = error("取文层不该跑表达式")
      override fun runInit(script: String, input: RuleValue): Map<String, String> = error("取文层不该跑 init")
  }
  ```

- [x] **Step 6: 加公开装配面并接上解析器**

把 `JsCallbackProxy.kt` 里的 `HostCallbackRouter` 上浮（关键：`current` 改持公开的 `HostHandler`，`JsCallbackProxy` 留在 internal）：

```kotlin
/**
 * 长生命周期的客户端处理器 → 短生命周期的任务代理之间的接缝。
 *
 * public 而非 internal：`lib_book_common` 的 Hilt 模块要先造它、再把它塞进 `JsSandboxClient`
 * 的 `hostHandler`，而依赖方的 test source set 不是 friend module，internal 类型出不去。
 * 本类只搬运，不含策略——所有拒绝与判定在 [HostHandler] 的实现里。
 */
class HostCallbackRouter : HostHandler {
    @Volatile private var current: HostHandler? = null
    ...
    fun <T> withProxy(proxy: HostHandler, block: () -> T): T { ... }
}
```

创建 `lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxHost.kt`：

```kotlin
package com.ebook.source.sandbox

import com.ebook.source.script.EvalContext
import com.ebook.source.script.ScriptJsBridge
import com.ebook.source.script.ScriptTransport
import com.ebook.source.script.SandboxScriptJs
import okhttp3.OkHttpClient

/**
 * 主进程侧的沙箱装配面：一条进程级长命的客户端 + 每任务短命的回调代理，两者的持有关系在这里收口。
 *
 * 为什么要这么一层（而不是让解析器直接持有 `JsSandboxClient`）：客户端的 `hostHandler` 是**构造期
 * 定死**的（Task 6），而代理的状态是任务级的，两者之间必须有一个可换的转子（`HostCallbackRouter`）。
 * 转子藏在构造期，客户端与它就不会被装配代码各配一次——配错的形态是「代理挂上了但客户端读的是
 * 另一个转子」，症状是每一次 `java.ajax` 都回「主进程没有正在进行的脚本任务」，在设备上极难查。
 *
 * public：出现在 `ScriptBookParser` 与 `BookSourceManagerImpl` 的公开构造签名上。
 * 里面**不预建任何连接**——第一次 `call` 才 bind（Task 6 的策略），所以本类可以在任何进程、
 * 任何线程上安全构造，包括没有 `.so` 的 JVM 单测。
 */
class JsSandboxHost(
    private val client: JsSandboxClient,
    /** `@Named("source")` 纯净客户端：沙箱发起的请求由它派生出守门客户端后代发，永远不带用户 token */
    val baseClient: OkHttpClient,
    internal val limits: JsLimits = JsLimits(),
    private val router: HostCallbackRouter = HostCallbackRouter(),
) {

    /** 挂载本任务的回调代理并执行一次。代理与 `client` 的时序由 [router] 保证，返回即摘除 */
    fun call(proxy: HostHandler, invocation: JsInvocation): JsOutcome =
        router.withProxy(proxy) { client.execute(invocation) }

    /**
     * 为一次解析任务造桥（规则层唯一的入口）。
     *
     * internal 成员可以收 internal 类型：`EvalContext`/`ScriptJsBridge` 都不出模块，
     * `lib_book_common` 只负责把本类的实例递给 `ScriptBookParser`。
     * [transport] 必须是**过守门的**传输（`GuardedNetwork.clientFor(baseClient, guard)` 派生出的那条），
     * 否则脚本拼出来的 URL 会绕过 DNS 重绑防护；调用点只有 `ScriptBookParser.sandboxGateway` 一处。
     */
    internal fun bridgeFor(
        ctx: EvalContext,
        guard: JsNetworkGuard,
        transport: ScriptTransport,
        bindings: Map<String, String?> = emptyMap(),
    ): ScriptJsBridge = SandboxScriptJs(
        executeTask = { proxy, invocation -> call(proxy, invocation) },
        ctx = ctx,
        guard = guard,
        transport = transport,
        staticBindings = bindings,
        limits = limits,
        statusProbe = HeadStatusProbe(GuardedNetwork.clientFor(baseClient, guard))::status,
    )
}
```

改 `analyze/ScriptBookParser.kt`（五处 `EvalContext(...)` 全部换成 `newContext(...)`；两处 `bookShelf` 在手的调用点带上 book 绑定）：

```kotlin
class ScriptBookParser internal constructor(
    private val rawJson: String,
    transport: ScriptTransport,
    /** null = 本机未装配沙箱（单测、装配失败、以及不接 Hilt 的独立运行路径）：JS 段照 2d 抛待执行 */
    private val jsHost: JsSandboxHost? = null,
    /** 测试构造里让沙箱外呼也打到假 transport（假件不接 DNS，字符串层的白名单判定照样测得到） */
    private val sandboxTransportOverride: ScriptTransport? = null,
) : BookParser, ScriptContentParser {

    constructor(rawJson: String, okHttpClient: OkHttpClient?, jsHost: JsSandboxHost? = null) : this(
        rawJson,
        OkHttpScriptTransport(okHttpClient ?: error("okHttpClient 未注入（生产接线必须传真客户端）")),
        jsHost,
    )

    ...

    /** 本源可解析 host 白名单：从规则原文导出（Task 5），首次用到才算（`rules` 是惰性的） */
    private val allowlist by lazy {
        SourceHostAllowlist.of(
            sourceUrl = rules.sourceUrl,
            ruleTexts = rules.objects.values.flatMap { it.values } +
                listOfNotNull(rules.searchUrl, rules.exploreUrl),
        )
    }

    private val guard by lazy { JsNetworkGuard(allowlist) }

    /** 沙箱外呼用的传输：生产走带 `GuardedDns` 的派生客户端，测试给假件（见主构造参数注释） */
    private val sandboxGateway: ScriptTransport by lazy {
        sandboxTransportOverride
            ?: OkHttpScriptTransport(GuardedNetwork.clientFor(jsHost!!.baseClient, guard))
    }

    /**
     * 造一次解析任务的上下文（五处 `EvalContext(...)` 唯一的诞生地）。
     *
     * **顺序是刻意的**：先有 ctx、再把它交给 host 造桥、回填 `ctx.js`——桥要现读 ctx 的
     * `baseUrl`/`page`/变量表，ctx 又要持有桥，做成构造参数就是个环，用一次赋值打断
     * （`EvalContext.js` 因此是 `var`，赋值点全仓只有这一处）。
     *
     * [bindings] 只给当前调用点真拿得出的量。`chapterJson`/`title` 在本阶段恒缺：正文链路的
     * 入口只有一个 `contentRef` 字符串，章名要查 Room 才有，而本层禁止碰持久层（§5.1 分工）——
     * 显式传 null 让脚本里 `chapter` 是 `undefined`，比给一个空对象让脚本以为「这本书没有章节」好。
     */
    private fun newContext(
        baseUrl: String,
        key: String = "",
        page: Int = 1,
        bindings: Map<String, String?> = emptyMap(),
    ): EvalContext {
        val ctx = EvalContext(baseUrl = baseUrl, key = key, page = page)
        jsHost?.let { host ->
            ctx.js = host.bridgeFor(
                ctx = ctx,
                guard = guard,
                transport = sandboxGateway,
                bindings = mapOf("chapterJson" to null, "title" to null) + bindings,
            )
        }
        return ctx
    }

    /** 详情页/目录页在手的调用点把书实体灌进绑定，让 `{{book.name}}` 与脚本里的 `book` 有值 */
    private fun bookBindings(bookShelf: BookShelfEntity): Map<String, String?> = mapOf(
        "bookJson" to bookShelf.bookInfo?.let { info ->
            buildJsonObject {
                put("name", info.name.orEmpty())
                put("author", info.author.orEmpty())
                put("intro", info.introduce.orEmpty())
                put("coverUrl", info.coverUrl.orEmpty())
                put("tocUrl", info.chapterUrl.orEmpty())
                put("kind", info.kind.orEmpty())
                put("lastChapter", info.lastChapter.orEmpty())
            }.toString()
        },
    )
```

`getBookInfo` 里的 init 段换成两条路径并存（JS 分支优先，非 JS 保持 2d 的 AllInOne 首个匹配）：

```kotlin
            val ctx = newContext(stripTail(bookShelf.noteUrl), bindings = bookBindings(bookShelf))
            ...
            val initRule = rules.rule(RuleObjectKind.BOOK_INFO, "init").orEmpty()
            // init 的 JS 分支（§1.4）：脚本回传对象，**字段名即键、字段规则整体被忽略**
            // （本仓规定，登记规格 §11-21——上游也是这么读的，但文档没写；与 AllInOne 那条
            // 「字段规则以 $n 取组」的路径互斥，二者只会走一条）
            val initFields = evaluator.evaluateInitObject(initRule, input)
            val initItem = if (initFields != null) null else initRule.takeIf { it.isNotBlank() }
                ?.let { evaluator.evaluate(it, input) }
                ?.let { it as? RuleResult.Matches }
                ?.items?.firstOrNull()
                ?.let { ScriptFieldExtractor.ItemContext.GroupsCtx(it) }

            fun field(name: String): String = when {
                initFields != null -> initFields[name].orEmpty()
                initItem != null -> extractor.fieldText(initItem, rules.rule(RuleObjectKind.BOOK_INFO, name).orEmpty())
                else -> evaluator.evaluate(rules.rule(RuleObjectKind.BOOK_INFO, name).orEmpty(), input).firstText()
            }
```

> 方法 KDoc 的 init 段补一句：「JS 形态的 init 走『字段=对象键』，字段规则被忽略；两种 init 的判定在 `ScriptRuleEvaluator.evaluateInitObject` 里做（那里才看得出是不是**单个** JS 段）」。

其余四处 `EvalContext(` → `newContext(`：`searchBook`（带 `key`/`page`，`bindings = emptyMap()`，此时还没有书实体）、`getChapterList`（`bindings = bookBindings(bookShelf)`——目录在详情之后跑，`bookShelf.bookInfo` 已在）、`loadExplorePage`、`fetchChapterText`。

**`ScriptUnsupported.JS` 的登记保持不动。** `ScriptRuleSet` 是纯词法层，不知道本机有没有沙箱，导入报告里「这条源含 JS」的提示因此仍然成立（现在它的意思从「解不了」变成「会执行，注意它会外呼」）；文案改动归 Task 11 的文档同步，本任务不碰 UI 字符串。

创建 `lib_book_common/src/main/java/com/ebook/common/di/SandboxModule.kt`：

```kotlin
package com.ebook.common.di

import android.content.Context
import com.ebook.source.sandbox.HostCallbackRouter
import com.ebook.source.sandbox.JsLimits
import com.ebook.source.sandbox.JsSandboxClient
import com.ebook.source.sandbox.JsSandboxConnector
import com.ebook.source.sandbox.JsSandboxHost
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * 脚本沙箱的进程级装配。
 *
 * 落在 `lib_book_common` 而不是 `lib_book_source`：`lib_book_source` 今天没有 Hilt/KSP 依赖，
 * 为一个 `@Provides` 给它上两行插件不值，而这里三样输入（App 上下文、`@Named("source")` 纯净
 * 客户端、连接器）全现成。**客户端不在这儿预热**——第一次真执行才 bind 沙箱进程，
 * 于是冷启动不付任何跨进程代价，没用到脚本的源连不上沙箱也不算失败。
 *
 * 注入方一律用**可空**参数接它（`BookSourceManagerImpl` 的 `jsHost: JsSandboxHost?`）：
 * 生产恒有值，测试与独立运行路径没有 binding 时按 null 走「JS 待执行」的原行为。
 */
@Module
@InstallIn(SingletonComponent::class)
object SandboxModule {

    @Provides
    @Singleton
    fun provideJsSandboxHost(
        @ApplicationContext context: Context,
        @Named("source") sourceClient: OkHttpClient,
    ): JsSandboxHost {
        val limits = JsLimits()
        // 转子必须在客户端之前建好：`hostHandler` 是构造期定死的（Task 6），
        // 而它指向的那个代理每次调用都在换——顺序写反就是「客户端读的是另一个转子」
        val router = HostCallbackRouter()
        return JsSandboxHost(
            client = JsSandboxClient(
                openChannel = { JsSandboxConnector(context, limits).open() },
                limits = limits,
                hostHandler = router,
            ),
            baseClient = sourceClient,
            limits = limits,
            router = router,
        )
    }
}
```

> 若 `JsSandboxHost` 的构造参数默认值使 `router` 可省，仍要**显式传**上面这一份：默认值会另起一个转子，而客户端手里的是参数里那一个，两者不是同一个对象时每一次回调都会回「主进程没有正在进行的脚本任务」。这条是 `provideJsSandboxHost` 唯一真正的坑，测试锁不住（它是 Hilt 接线），Task 11 的人工装机清单里有一条专门验它。

改 `BookSourceManagerImpl.kt`：主构造在 `okHttpClient` 之后加一个带默认值的参数，@Inject 次构造把它接进来，脚本分支递下去。

```kotlin
    // 主构造（internal）
    private val okHttpClient: OkHttpClient,
    /** null = 未装配沙箱（测试、独立运行）：脚本书源的 JS 段按「待执行」如实报，行为与 2d 一致 */
    private val jsHost: JsSandboxHost? = null,
    private val parserFactory: (SourceDefinition) -> BookParser = { definition ->
        when (definition) {
            is SourceDefinition.Native -> JsoupBookParser(definition.rule, okHttpClient)
            is SourceDefinition.Script -> ScriptBookParser(definition.rawJson, okHttpClient, jsHost)
        }
    },
```

```kotlin
    // @Inject 次构造
    constructor(
        @ApplicationContext context: Context,
        dao: BookSourceDao,
        @Named("source") okHttpClient: OkHttpClient,
        jsHost: JsSandboxHost?,
    ) : this(context, dao, okHttpClient, jsHost, CoroutineScope(SupervisorJob() + Dispatchers.IO))
```

`jsHost` 参数**可空但不加 `@Nullable` 注解**：Hilt 对可空引用类型的注入要求图上有一个 `@Provides` 返回可空类型或提供非空值——`SandboxModule` 提供非空实例即可满足可空注入点（Dagger 会把非空绑定用于可空字段）。若编译期 Dagger 报「cannot be provided」，改成 `jsHost: JsSandboxHost` 非空参数 + 测试内部构造传 null 的路径走 `internal` 主构造的默认值（两条都能走通，取编译器认的那条，别为凑可空加 `@Nullable`）。

改 `ScriptRuleExceptions.kt:25` 的 KDoc（**消息串一个字不动**——`JsoupSourceReader` 与它的测试都按这句话做前缀判断，且这句话在两种情况下都为真）：

```kotlin
/**
 * 规则含 JS 段，而**这一次求值拿不到沙箱**。
 *
 * Plan 3 接线之后它的含义从「本仓还没实现解释器」收窄成两条具体场景：
 * ① 主进程没装配 `JsSandboxHost`（单测、Hilt 图里没有 binding、独立运行路径）；
 * ② 沙箱回调里的嵌套规则求值——阶段一刻意关掉 JS（Task 9 的深度绊线与 `EvalContext.withoutJs()`）。
 * 与 [JsExecutionFailedException] 的分界始终是「有没有真跑过」：跑过而失败是后者。
 *
 * 消息串保持不变是有意的：`JsoupSourceReader` 侧按「类型化错误原样上报」的口径处理它，
 * 而其测试用同一句话做前缀断言。
 */
```

- [x] **Step 7: 跑绿 + 回归 + 零警告**

Run:
```bash
./gradlew :lib_book_source:testDebugUnitTest --tests "*SandboxScriptJsTest*" \
  --tests "*InterpolationTest*" --tests "*ScriptUrlOptionTest*" --tests "*ScriptUrlResolverTest*" \
  --tests "*ScriptHttpTest*" --tests "*ScriptContentPagerTest*" --tests "*ScriptRuleEvaluatorCombinatorTest*" \
  --tests "*ScriptBookParserTest*" --tests "*ScriptSourceEndToEndTest*" --tests "*JsCallbackProxyTest*" \
  --tests "*JsNetworkGuardTest*" --tests "*JsSandboxClientTest*" --tests "*JsHostApiTest*"
```
Expected: 全绿。`ScriptSourceEndToEndTest` 与 `ScriptBookParserTest` 用的都是**不带 jsHost 的构造**，因此它们对「待执行」的断言必须原样通过——这正是关键事实 2 要的锁。

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "*BookSourceManagerImplTest*"`
Expected: 全绿（Hilt 之外的构造路径都走 internal 主构造的默认 null）。**若这条红了且报的是 Dagger 图缺 binding**，说明 `module_app` 的 mock/real flavor 里某个测试组件也要求 `JsSandboxHost`——按 Step 6 末段处理成非空注入点，不要加测试专用 binding。

Run: `./gradlew :lib_book_source:assembleDebug :lib_book_common:assembleDebug :module_app:assembleRealDebug`
Expected: 三个模块零警告（AGENTS.md「不要引入新的编译警告」）。

- [ ] **Step 8: 提交（需授权）**

```bash
git add lib_book_source/src/main/java/com/ebook/source/script/ScriptJsBridge.kt \
        lib_book_source/src/main/java/com/ebook/source/script/SandboxScriptJs.kt \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsSandboxHost.kt \
        lib_book_source/src/test/java/com/ebook/source/script/SandboxScriptJsTest.kt \
        lib_book_source/src/main/java/com/ebook/source/script/ \
        lib_book_source/src/main/java/com/ebook/source/sandbox/JsCallbackProxy.kt \
        lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt \
        lib_book_source/src/test/java/com/ebook/source/script/ \
        lib_book_common/src/main/java/com/ebook/common/di/SandboxModule.kt \
        lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 接通脚本沙箱执行器到五处 JS 求值点

把 `@js:`、`{{JS 表达式}}`、URL 选项 `js`/`bodyJs` 与 JS 形态的 `init`
接到主进程侧的执行器装配面（`JsSandboxHost`），五种载荷的模式包装与
结果解包收在 `SandboxScriptJs` 一处。

- 桥挂在 `EvalContext` 上，取文层与求值层读同一判据：`js == null` 时
  仍抛「需脚本沙箱执行器」，未装配沙箱的行为与 2d 逐字一致
- 回调代理按一次沙箱调用新建，页面基准随本次输入走、变量表按引用共用
- `js`/`bodyJs` 从「解析期抛」改为「解析期携带、取文期执行」，
  副作用次序不再前置于 `||` 兜底支
- 沙箱外呼经 `GuardedNetwork` 派生的守门客户端代发，host 白名单按源规则原文导出

Hilt 装配落 `lib_book_common`（`lib_book_source` 无 Hilt/KSP 插件），
代价是 `JsSandboxHost`/`HostCallbackRouter` 上浮为 public。
EOF
)"
```

---

### Task 11：全量验证、ADR 与文档补记、人工装机清单

**关键事实（先读完再动手，本 Task 是唯一允许改 ADR/规格/AGENTS.md 的地方）**

1. **本 Task 不写新功能代码**。它的产出是「验证证据 + 文档与已落地代码逐条对齐」。文档里写「计划打算怎么做」而不写「代码现在怎么做」就是新的文档债——AGENTS.md 的「文档同步」条款要的是后者。因此每个 Step 都要求**先读实现再落笔**（下面每个 Step 都点名要读的文件与行）。
2. **ADR 就地补记、不新开文件**（AGENTS.md「ADR 独立完整与更新」：允许补事实、纠处方、标日期）。偏差 1~3 改决策正文，偏差 4~7 落进决策 4/5/6 的括注与「权衡」段——不要把七条偏差整段贴进 ADR，那会把它长成计划文件的副本。
3. **规格 §10 的能力矩阵是「哪些语法可用」的唯一事实源**。Plan 3 落地后五行必须从 ❌ 改 ✅，且「不实现的后果」列要换成真实的**残余限制**，留空等于把「已支持」说成「已完全对齐上游」。
4. **有一条正确性只能靠装机验**：`provideJsSandboxHost` 里转子（`HostCallbackRouter`）与客户端必须是同一个对象（Task 10 Step 6 的注释）。它是 Hilt 接线，JVM 测锁不住，装错的症状是**每一条含 JS 的源都报「主进程没有正在进行的脚本任务」**——所以 Step 9 的人工清单不是走过场。
5. **失败口径的三条红线在本 Task 复查**：① `ctx.js == null`（未装配）与「真跑了但失败」（`JsExecutionFailedException`）不得混成一种提示；② 沙箱外呼一律走 `GuardedNetwork` 派生的守门客户端，**不得**触碰带 token 的默认客户端（`AuthInterceptor` 的 host 白名单是最后一道，不是唯一一道）；③ `:js` 进程零权限是内核强制的，任何「为方便在 `:js` 里读 SP/文件」的改动都直接违反 ADR-0028 决策 2。

**Files:**
- Modify: `docs/adr/0028-untrusted-js-sandbox.md`（决策 4/5/6 + 权衡 + 遗留）
- Modify: `docs/adr/0029-script-book-source-import.md`（「落地状态」段就地补记）
- Modify: `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（§9 尾、§10 矩阵 5 行 + 引言、§11 新增 27~33）
- Modify: `CONTEXT.md`（「脚本执行器」与「受限代理」两个术语）
- Modify: `AGENTS.md`（书源段的脚本书源小追加一段 Plan 3 口径）
- Modify: `docs/test-coverage-todo.md`（新增「人工装机验证清单（:js 沙箱执行器，2026-09-XX）」段）
- Modify: `docs/superpowers/plans/2026-09-09-js-sandbox-executor.md`（勾选本计划 + 「执行期修正记录」）

- [x] **Step 1: 全量 JVM 回归**

> **执行记录（2026-09-09）**：`./gradlew :lib_book_source:testDebugUnitTest :lib_book_common:testDebugUnitTest --rerun-tasks`
> → `BUILD SUCCESSFUL`；XML 报告汇总 `lib_book_source` 464 例 / `lib_book_common` 281 例，
> 两者 `failures=0 errors=0 skipped=0`。`./gradlew test --rerun-tasks` → 331 个 task 全部重新执行、
> `BUILD SUCCESSFUL`，7 条 `w:` 警告全部落在既有文件（`TreeSizeTest` 与 TheRouter KSP 生成码），
> 新增代码零警告。用例数已按本 Step 要求写进 Step 10 提交信息的最后一行。
> **复核轮更新**：第 38 条给 `JsProtocol` 补了一例锁形，`lib_book_source` 464 → **465**（重跑为 0 失败
> 0 跳过，见第 38d 条），Step 10 的草稿随之改成 465。全项目 `test --rerun-tasks` 的 331-task 数字属
> 464 时代，**提交前须再跑一次**才算当前事实。

Run:
```bash
./gradlew :lib_book_source:testDebugUnitTest :lib_book_common:testDebugUnitTest
```
Expected: 全绿、**零跳过**。把 `lib_book_source` 的最终用例数记进 Step 10 提交信息的最后一行（Plan 2d 的基线是 330 例，Task 3~10 各加一批；报数字不报「一堆」）。

Run:
```bash
./gradlew test
```
Expected: 全项目绿。这条会顺带跑 native 编译，第一次慢（QuickJS 全量编译），增量后由 Gradle 缓存兜住。任一模块红都必须在 Step 10 之前修掉，**不得**用 `--continue` 或 `excludeTests` 绕过。

- [x] **Step 2: 真内核与真跨进程（设备）**

> **执行记录（2026-09-09）**：`Pixel_8(AVD) - 17`（x86_64，`emulator-5554`），
> `Starting 16 tests on Pixel_8(AVD) - 17 / Finished 16 tests`，`BUILD SUCCESSFUL`——
> `QuickJsBridgeTest` 12 例 + `SandboxConnectionTest` 4 例，零失败零跳过。
> 这一跑抓到两个 JVM 侧推断不出的缺陷（详见「执行期修正记录」第 35、36 条）：
> ① 栈限值只在创建时定一次，`nativeEval` 换到 binder 线程池线程后实际栈更深，深递归用例直接把
> `:js` 进程打成 SIGSEGV（不是可控的 `STACK` 失败）——改为每次执行前按本线程剩余栈现算天花板；
> ② 堆到限时内核那句 `out of memory` 自己也要分配，分配被拒后异常退化成 `String(null)` 的 `"null"`，
> 只按 message 关键词分类会把「内存超限」报成 `RUNTIME`——改由分配器自己投票（本次执行拒过分配）
> 加上「这条异常说不出任何独立信息」两个条件共同判定。中间一轮加的「分配宽限期」闸门已被设备实测
> 否证（异常沿栈展开时临时值已释放，读数必然偏低），代码里没有它。
> ③ 隔离性一侧：`ActivityManager.getRunningAppProcesses()` 按调用方 uid 过滤，主进程**结构性**看不见
> isolated 进程，原判据换成 host 回调里读 `Binder.getCallingUid()` + `Process.isIsolatedUid`。
>
> 下面「没有设备时」那段处置**不适用**——本计划已在设备上跑过。

前置：`adb devices` 有一台 arm64 设备或模拟器。

Run:
```bash
./gradlew :lib_book_source:connectedDebugAndroidTest
```
Expected: `QuickJsBridgeTest`（死循环被中断、堆超限、深递归、求值结果）与 `SandboxConnectionTest`（bind → execute → 回调 → 断连自动重启）**全绿**。

**没有设备时**：不许静默跳过。把「本计划未在设备上验证」写进 Step 10 的提交信息 body 与 `docs/test-coverage-todo.md` 的新清单段首（AGENTS.md「Agent 止于第一步」分工的落地方式就是显式交代卡点）。androidTest 覆盖不到的东西一条都不能靠 JVM 测「推断通过」。

- [x] **Step 3: 构建矩阵与 .so 落包**

> **执行记录（2026-09-09，桥接层最终代码之后重跑）**：`assembleRealDebug assembleMockDebug assembleRealRelease`
> → `BUILD SUCCESSFUL in 4m 8s`，日志里 `^w: `（Kotlin）与 `^Warning:`（R8 混淆）计数均为 0（新增代码零警告）。
> `.so` 落包按变体核对：debug APK 内 `lib/arm64-v8a/libebook_js.so`(1 326 616 B) 与
> `lib/x86_64/libebook_js.so`(1 406 552 B) 两条，release APK 内只有 `lib/arm64-v8a/`(1 314 512 B) 一条
> （Task 1 约定插件按 build type 分 ABI 成立）；模块侧中间产物三份 `cxx/Debug/*/arm64-v8a`、
> `cxx/Debug/*/x86_64`、`cxx/RelWithDebInfo/*/arm64-v8a`。四个变体的 `packaged_manifests/**/AndroidManifest.xml`
> 全部含 `android:isolatedProcess="true"`。
> release 混淆面的名字复核（对最终代码重跑）：`classes.dex` 内 `nativeCreate`/`nativeEval`/`nativeReset`/
> `nativeDestroy` 与 `Lcom/ebook/source/sandbox/QuickJsNative;`、`Lcom/ebook/source/sandbox/HostDispatcher;`
> 各命中 1 次；`mapping.txt` 里 `HostDispatcher` 类名未改且 `handle(String,String)String` 仍是 `-> handle`。
> **一处易误读**：四个 native 方法在 `mapping.txt` 里查不到——AGP 自带的
> `-keepclasseswithmembernames class * { native <methods>; }` 不改名就不写映射行，「mapping 里没有」
> 不等于「被删了」，判据是 dex 里的名字。
> **产物不可直接装机**：`module_app/build.gradle.kts` 没有 release 的 `signingConfig`，
> `assembleRelease` 出的是 `module_app-real-release-unsigned.apk`。装机清单第 1 条已补自签方式。

Run:
```bash
./gradlew :module_app:assembleRealDebug :module_app:assembleMockDebug :module_app:assembleRelease
```
Expected: 三个产物构建成功、**零编译警告**（AGENTS.md「不要引入新的编译警告」）。release 走混淆（`module_app` release 恒开，见 ADR-0024），因此 `consumer-rules.pro` 里 JNI keep 规则的缺口会在这里以运行期崩溃的形式提前暴露——构建过了不等于 `.so` 找得到，Step 9 第 1 条专门验它。

Run:
```bash
find lib_book_source/build/intermediates -name "libebook_js.so" -printf "%p\n"
```
Expected: debug 侧出现 `arm64-v8a` 与 `x86_64` 两条，release 侧只出现 `arm64-v8a` 一条（Task 1 约定插件按 build type 分 ABI）。命令没有输出说明 `mergeJniLibFolders` 没拾到 CMake 产物——回去查 `lib_book_source/build.gradle.kts` 的 `externalNativeBuild.cmake.path`，不要在打包脚本里手工塞 `.so`。若 `find -printf` 不被支持（Git Bash 的 find 是 GNU 版，正常支持），退化成 `find ... -name "libebook_js.so"` 用眼睛看路径里的 ABI 段。

- [x] **Step 4: 安全红线复查（四条 grep，逐条看结果不许凭印象）**

> **执行记录（2026-09-09 重跑）**：四条命令逐条实跑。
> 红线 1 `git grep -in "[l]egado"` → 0 命中；红线 2 `git grep -n "TokenHolder\|AuthInterceptor"`
> 限 `lib_book_source` 与 `SandboxModule.kt` → 0 命中；红线 3 → `AndroidManifest.xml:20`
> `android:isolatedProcess="true"`；红线 4 `sandbox/` 包内 `addHeader` → 0 命中。
> `<service>` 声明整块只有 `android:name`/`exported="false"`/`isolatedProcess="true"`/`process=":js"`
> 四个属性，无 `android:permission` 之外的能力开关，也没有 `startService` 面（只绑不启）。
> 真机 `dumpsys` 那条已由设备用例替代：`SandboxConnectionTest` 的「拉起来的执行器进程确实被系统隔离」
> 在 host 回调里读 `Binder.getCallingUid()` 并用 `Process.isIsolatedUid` 判定（进程枚举对 isolated
> 进程结构性失明，见「执行期修正记录」第 36 条），已在模拟器上跑绿；`dumpsys` 只留给非测试机型的退化路径。

Run:
```bash
git grep -in "[l]egado" -- . || echo "红线 1 干净：全仓无生态项目名"
git grep -n "TokenHolder\|AuthInterceptor" -- lib_book_source lib_book_common/src/main/java/com/ebook/common/di/SandboxModule.kt || echo "红线 2 干净：执行器与装配层不认识 token"
git grep -n "isolatedProcess" -- lib_book_source/src/main/AndroidManifest.xml
git grep -n "addHeader" -- lib_book_source/src/main/java/com/ebook/source/sandbox/
```
Expected:
- 红线 1：无输出（ADR-0029 决策 7 的命名红线，含注释与 fixture 文件名）。
- 红线 2：`lib_book_source` 与 `SandboxModule.kt` 里找不到 `TokenHolder`/`AuthInterceptor` 的引用——沙箱外呼用的是 `@Named("source")` 纯净客户端。
- 红线 3：`<service>` 上有 `android:isolatedProcess="true"`，且同一条声明**没有** `android:permission` 之外的额外能力开关。
- 红线 4：`sandbox/` 包里没有 `addHeader` 调用（凭证注入的唯一入口在网络层拦截器，执行器侧加头就等于开了第二条）。若 `GuardedNetwork` 为了 UA 之类加头，逐条确认加的**不是**认证头，并在 `GuardedNetwork` 的 KDoc 上写明。

Run: `adb shell dumpsys package com.ebook | grep -A3 "isIsolated\|User"`（真机上）
Expected: `:js` 进程跑在独立 uid 下（`u0_aXX` 之外的 isolated uid 段）。机型不认 isolated 时的退化路径（ADR-0028 决策 2）要求**日志可见**：`SandboxProcess` 的自检结果必须落在日志里，不许静默降级。

- [x] **Step 5: 补记 ADR-0028**

先读 `docs/adr/0028-untrusted-js-sandbox.md` 的决策 4/5/6 与「权衡」，再读实现：`HostDispatcher.kt`（纯计算落点）、`JsRuntimeBridge.kt` + `SandboxTaskRunner.kt`（并发与重置粒度）、`JsCallbackProxy.kt`（嵌套求值与网络代理线程）、`js_bridge.cpp` 的 `nativeReset`。改四处：

1. **决策 5** 的「runtime 按任务复用、任务边界强制重置」后面补一句自足描述（不引计划文件）：

> 任务边界取「一次沙箱调用」而非「一次解析任务」：每次 `execute` 前重建 `JSContext`，脚本经 `globalThis` 留下的东西活不过一帧。代价是跨段的全局缓存写法失效，换来的是「一段污染毒到整本书」不可达——污染没有可观测信号，只能靠边界粒度消掉。

2. **决策 4** 的「递归规则求值（脚本调用解释器解析规则）」后面补：

> 递归求值的嵌套层**不再执行 JS**：一帧之内再入一帧会让按帧记账的超时与堆限失去含义（打不断一棵还在跑的子树）。变量表跨嵌套按引用共享，页面基准按快照传递。

3. **决策 6** 的「纯计算类做成执行器 C++ 原生 API」改成事实：

> 纯计算类注册为 JS 白名单 API，实现经 JNI 落**执行器进程内的 Kotlin/JDK**（`MessageDigest`/`Cipher`/`Base64`/`HexFormat`/`DateTimeFormatter`），仍然零 IPC。手写 AES/MD5 的 C 实现约 900 行且在本仓不可测（JVM 测不到 C），换成 JDK 后可用真实向量在 JVM 上锁住；JS 侧名字与协议不变，将来 benchmark 认定是热点时只改分发一侧。

4. **决策 6** 网络类补线程模型一句，并在「权衡」新增一条、在「遗留」新增两条：

> 代理回调在主进程的 binder 线程池线程上同步返回（`runBlocking` 等 `suspend` transport 的结果，transport 内部切 `Dispatchers.IO`）。这条链路成立的前提是**永不在主线程调沙箱**，执行器客户端侧有主线程守卫把它挡在抛异常那一步。

- 权衡新增：**单帧串行 vs 每线程一个 runtime**——选串行（堆限保住「进程总量」含义，解析路径本身顺序、瓶颈在网络不在 CPU），代价是多源并行时执行器是串行点。
- 遗留新增：**并发度未调校**（聚合搜索若实测卡在串行点，改成每线程一个 runtime）；**白名单宽松度按真实源失败率调校**这一条仍在，Plan 3 只是把「可解析 host」从源规则导出，没改变它偏严的可能。

- [x] **Step 6: 补记 ADR-0029 的「落地状态」**

把该段末尾那条「**未落地**：`:js` 沙箱执行器（决策 3）、Host API 白名单定版与语料 fixtures（决策 4/8），脚本源的导出同样留白」改成三条，逐条对齐代码实况（先读 `JsHostApi.kt`、`SourceHostAllowlist.kt`、`fixtures` 目录确认）：

```markdown
- **2026-09-XX（阶段二之 Plan 3，`:js` 沙箱执行器）**：决策 3 与决策 4 落地——QuickJS 源码 vendored
  进 `third_party/quickjs/`（含 `PIN.sha256` 漂移校验）、自有 C++ 桥、`isolatedProcess` 零权限执行器、
  Binder + JSON 双向控制面、按源导出的 host 白名单与守门客户端。五类 JS 载荷（`@js:`/`<js>` 段、
  `{{}}` 表达式、URL 选项 `js`/`bodyJs`、JS 形态的 `init`）经 `EvalContext` 上的桥接面接进求值链，
  未装配执行器的路径行为与 2d 逐字一致（照实报「需脚本沙箱执行器」）。
  与决策 3/4 原话的偏差（纯计算落 Kotlin、任务边界=一次调用、嵌套层禁 JS、单帧串行、binder 线程
  `runBlocking`）就地记进 ADR-0028，本段不复述。
- **仍未落地**：决策 8 的真实语料金标准 fixtures 与恶意脚本测试集（语料文件缺失，见规格 §11-25；
  恶意脚本一侧目前只有 JVM 与 androidTest 的限值用例，没有成集的语料级攻击样本）。
  `jsLib`（源级脚本库）、`webJs`、XPath 与登录/浏览器自动化族仍按 ❌ 处置。
- **留白**：脚本源的导出（把原始 JSON 写回生态格式）未实现，与执行器无关。
```

- [x] **Step 7: 更新规格（§9 尾、§10 矩阵、§11 清单）**

**§9** 那段流程总览的最后两行（`@js: / <js> / webJs / jsLib 在任何一步出现 → Plan 2 抛类型化「JS 求值待 Plan 3」`）改成：

```
 · @js: / <js> → 交执行器（零权限隔离进程）；未装配执行器时抛类型化「需脚本沙箱执行器」，
   装配了而运行失败抛「脚本执行失败」并带内核原文——两者不得合并成一句提示
 · webJs / jsLib 出现 → 仍抛类型化「本项目不支持」（能力缺口，不是还没接线）
```

**§10** 引言第 2 段末尾补一句：「Plan 3（执行器）落地后本表 ❌ 列**只减少不新增**：矩阵说的是语法能力，某个源因白名单/超时跑不出来属运行期失败，不改本表口径。」

五行按实况改（「不实现的后果」列改成**残余限制**，不留 `—`）：

| 语法特性 | 改成 |
|---|---|
| `<js></js>` 内联段 | ✅（经执行器）；`jsLib` 仍 ❌ ⇒ 依赖全局库函数的脚本段会以内核 ReferenceError 失败 |
| `@js:` | ✅（经执行器，覆盖率上限从 38% 解开）；限制：脚本内不能再套一层 `@js:`（嵌套求值禁 JS），单帧超时/堆限 |
| 变量 `@put:`/`@get:`/`java.put`/`java.get` | ✅（`java.put`/`java.get` 走回调通道，落 `EvalContext.variables`；`java.get` 只实现变量读取语义） |
| `{{}}` 插值 | ✅（JS 表达式分支经执行器求值，纯字面量分支不落进程） |
| `js` / `bodyJs` 选项 | ✅（解析期携带、取文期执行）；`js` 改写出的 URL 与请求头同样过 host 白名单与私网拒绝 |

**§11** 追加 7 条（编号接在 26 之后）。每条都要点明「这是本仓规定还是上游实证」——这七条**全是本仓规定**，上游文档没有答案：

```markdown
27. **`java.get` 不实现网络形态**（Plan 3 本仓规定）：上游按重载区分 `java.get(key)` 与
    `java.get(url)`，规格 §5.2 只给了变量语义。本仓只实现变量读取，脚本要发请求走
    `java.ajax`/`java.post`/`java.load`。同名两种行为是排查地狱，宁缺。
28. **Host API 返回裸字符串时的编码**（Plan 3 本仓规定）：`java.ajax` 一族拿回的是按**该请求
    charset 显式字节解码**后的文本，未声明 charset 按 UTF-8——与取文层同一条口径
    （`body.string()` 会被 `EncodingInterceptor` 强改的 UTF-8 吃掉，gbk 站必乱码）。
    上游是否对裸串响应再猜编码未核实，须真实源验证；失败证据形态是「ajax 拿回的 gbk 页面
    解出乱码而同一 URL 的声明式规则正常」。
29. **页面级变量（`java.putToPage`/`getPage` 一族）落同一张变量表**（Plan 3 本仓规定）：
    上游区分「请求级/页面级/全局」三档作用域，本仓只有一张按**一次沙箱调用**为界的表
    （随 `EvalContext.variables` 走，`@put:`/`@get:` 与 `java.put`/`java.get` 同表）。
    跨章缓存因此失效——真需要时按上游写法会拿到 null，而不是拿到脏值（可接受的方向）。
30. **`responseCode` 无状态**（Plan 3 本仓规定）：每次调用现做一次 HEAD 探测，不缓存上一请求的
    状态码。上游是否记录最近一次请求未核实；取 HEAD 而非复用 GET 响应的代价是多一次请求，
    收益是「不依赖宿主侧隐式会话状态」这条实现能单测锁住。
31. **`src`/`title`/`chapter` 绑定阶段一恒 null**（Plan 3 本仓规定，正文侧限制不是执行器限制）：
    正文链路手上只有 `contentRef`，查 Room 取章节标题/书籍字段属仓库层，解析层不得伸手。
    因此只在这些字段上取值的脚本段拿到 null。落地时在本条登记，接入章节快照后改写。
32. **JS 形态 `init` 的「字段=对象键」**（Plan 3，对齐 §11-21 的另一半）：脚本回传对象时字段按
    对象键取值；声明式 AllInOne 形态仍是「首个匹配为条目上下文 + `$n` 组引用」（§11-21）。
    两种形态同一条规则里不混用（`evaluateInitObject` 只在「单个 JS 叶子」时命中）。
33. **变量表与 `globalThis` 的作用域 = 一次沙箱调用**（Plan 3 本仓规定，随 ADR-0028 决策 5 的
    重置粒度）：同一次调用内跨 API 调用可见，跨调用一律为空。上游靠全局量跨段缓存的写法
    在本仓失效，症状是「第二次求值拿到 null 而第一次正常」。
```

- [x] **Step 8: 更新 CONTEXT.md 与 AGENTS.md**

**CONTEXT.md**（紧挨现有「脚本书源」术语，格式照抄该条目的缩进与粒度，纯术语无实现细节）新增两条：

```markdown
**脚本执行器（Script Executor）**:
承载不可信脚本段的零权限隔离进程（`isolatedProcess`），内含自集成的 JS 引擎与自有桥接层。
主进程永不加载引擎；执行器崩溃不传染业务进程，只让该源本轮请求按失败处置。

**受限代理（Restricted Proxy）**:
脚本发起网络请求的唯一途径——由主进程代发，仅 http(s)、拒绝私网、按源规则原文导出可解析 host
白名单、大小/超时/限流上限齐备，**永不附加用户凭证**。脚本自己不存在开 socket 的能力。
```

**AGENTS.md** 在「脚本书源（ADR-0029 阶段一）已在仓」那条 bullet 的末尾（2c/2d 那几句话之后）追加一段，只写**别人重新发现会浪费时间的四条**：

```markdown
**脚本书源的执行器（ADR-0028/0029 阶段二，Plan 3）已在仓**，本仓首个 C++ 模块（`third_party/quickjs/`
vendored + `lib_book_source/src/main/cpp/`，build-logic 的 `xrn1997.android.native`）。四条口径：
**(a)** **永不在主线程调沙箱**——脚本回调要回主进程，主线程调过去就是自锁；执行器客户端有主线程守卫，
改它之前先想清楚回调线程模型（`HostHandler.handle` 同步、transport 是 suspend，靠 binder 线程池上
`runBlocking` 接起来）。**(b)** 未装配执行器与执行失败是两件事：`ctx.js == null` 抛
`JsEvaluationPendingException`（消息与 2d 逐字一致，`JsoupSourceReader` 及其测试按这句做前缀判断，
**改消息会红别人的测试**），真跑了而失败抛 `JsExecutionFailedException` 带内核原文；把两者合并成
「该源已失效」会把用户支去重导一条好源。**(c)** 沙箱外呼只用 `GuardedNetwork` 派生的守门客户端，
host 白名单由 `SourceHostAllowlist` 从源规则原文导出——自己 new 一个 OkHttpClient 就等于绕过
私网拒绝与白名单，而 `:js` 进程零权限因此只剩一半。**(d)** Hilt 装配在 `lib_book_common`
（`lib_book_source` 无 Hilt/KSP），`JsSandboxHost`/`HostCallbackRouter` 为此上浮 public：
`provideJsSandboxHost` 里转子必须**先建、再传进客户端**，两边不是同一个对象时每条回调都报
「主进程没有正在进行的脚本任务」，这一条 JVM 测锁不住、只有装机能验。
```

- [x] **Step 9: 写人工装机验证清单**

在 `docs/test-coverage-todo.md` 末尾新增一段，标题格式对齐既有段落（`## 人工装机验证清单（脚本书源阶段二，2026-09-09）`）：

```markdown
## 人工装机验证清单（:js 沙箱执行器，2026-09-XX）

JVM 与 androidTest 覆盖不到的东西：真机 isolated 边界、release 混淆下的 .so 加载、
Hilt 接线、真实站点的脚本行为与请求频度。逐条给「做什么 / 看什么现象」。

### 1. release 包里 .so 能加载（混淆 + 仅 ARM64）
装 `assembleRelease` 产物，打开一条含 `@js:` 的脚本源并搜索。
期望出结果。失败形态有两种，必须分清：`UnsatisfiedLinkError`/脚本段全报执行失败 = keep 规则缺；
报「需脚本沙箱执行器」= Hilt 没装配上（第 3 条）。

### 2. 恶意/病态脚本不打穿 App
自己写一条源：`content` 规则为 `@js:while(true){}`、再一条 `@js:let a=[];while(1)a.push(a);`、
再一条 `@js:function f(){f()}f()`。
期望：分别得到「执行超时」「内存超限」「栈溢出」三类失败提示，App 不闪退、书架与其余源照常可用。
`adb logcat` 里能看到 `:js` 进程被销毁后重新拉起。

### 3. 装配转子是同一个对象（JVM 测锁不住的那条）
在含 JS 的源上跑通一次详情→目录→正文。
期望：**没有**「主进程没有正在进行的脚本任务」这句。出现它即 `provideJsSandboxHost` 里转子与
客户端不是同一份（Task 10 Step 6）。

### 4. 沙箱请求不带凭证、不出私网
抓包（或 `adb shell dumpsys netstats`）看脚本 `java.ajax` 发出的请求。
期望：无 `Authorization` 头、无本 App 的 cookie；对 `127.0.0.1`/`192.168.x`/`10.x` 的脚本请求
被拒且日志给出拒绝原因。顺带确认源 host 白名单：脚本请求一个规则里没出现过的第三方 host，
应被拒并明示。

### 5. 真实源覆盖：62% 含 JS 的那一批到底能跑多少
挑 5~10 条**已知含 JS** 的社区源（登录类除外）逐个搜索 + 打开一本书读正文。
记录：成功数、失败原因分布（超时/内存/白名单/内核 ReferenceError——`jsLib` 缺失会落在这里）。
这是 ADR-0029 决策 4 白名单定版的唯一实测反馈来源，结论回填规格 §11 对应条目。

### 6. 进程与功耗
读 20 章正文，观察 `:js` 进程是否常驻、有无反复冷启动（`dumpsys activity processes`）。
若冷启动开销在慢机型上明显，登记为「执行器复用策略待调校」，不就地改成常驻。
```

- [ ] **Step 10: 提交（需授权）**

先跑 `git status` 核对改动面：本 Task 应当**只有文档**变动。若出现代码改动，说明验证过程中顺手修了东西——把它们单独一次提交（type 按性质取 `fix`/`test`），不要和文档混在一条里。

```bash
git add docs/adr/0028-untrusted-js-sandbox.md docs/adr/0029-script-book-source-import.md \
        docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md \
        docs/superpowers/plans/2026-09-09-js-sandbox-executor.md \
        CONTEXT.md AGENTS.md docs/test-coverage-todo.md
git commit -m "$(cat <<'EOF'
docs: 补记脚本沙箱执行器落地口径与装机清单

- ADR-0028：纯计算落点、重置粒度、嵌套层禁 JS、单帧串行、binder 线程模型
- ADR-0029：阶段二执行器落地状态与仍缺的语料 fixtures
- 规格 §9/§10/§11：五行能力矩阵转 ✅，新增 7 条本仓规定
- AGENTS.md/CONTEXT.md：执行器与受限代理术语、四条重发现代价
- 装机清单：.so 加载、病态脚本、装配转子、凭证与私网、真实源覆盖、进程复用

验证：lib_book_source 465 例 / lib_book_common 281 例零失败零跳过，全项目 test 绿，
设备侧 connectedDebugAndroidTest 16 例绿（Pixel_8 AVD / Android 17）。
EOF
)"
```

---

## 退出判据

1. Step 1~4 全部有**命令输出为证**（用例数、androidTest 结果、三个 APK 的 ABI 列表、四条 grep 结果）。缺任何一条证据即未退出。
2. 含 `@js:` 的源在设备上能跑通搜索→详情→目录→正文，且病态脚本不打穿 App（清单第 2 条）。
3. 五份文档（两份 ADR、规格、AGENTS.md、CONTEXT.md）与代码逐条对得上，且 ADR 里没有「见计划文件」这类外部锚点。
4. `docs/test-coverage-todo.md` 的新清单里，JVM/androidTest 覆盖不到的每一项都有人做或明确标注未做。

## 留给后续计划的接口

- **书城接线**（2e）：脚本行成为默认源的载体改造，与本计划无关，接缝在 `BookSourceManagerImpl.currentSource`。
- **`jsLib` 源级脚本库**：唯一会让 §10 的 `@js:` 行「残余限制」消失的东西——大量脚本用 `@import` 声明的全局函数。实现落点是把垫片多塞一次（`QuickJsPrelude.kt` 的装载位）加按源解析 `jsLib` 列表，**不改协议**。
- **并发度调校**：偏差 6 的串行锁是单点，实测卡在它时改成「每线程一个 runtime」，改的是 `SandboxTaskRunner` 与 `JsRuntimeBridge` 的持有方式。
- **白名单宽松度**：ADR-0028 的遗留，靠清单第 5 条的实测反馈调；调的是 `SourceHostAllowlist` 的导出规则，不改网络层。
- **脚本源导出**：把原始 JSON 写回生态格式，独立小计划。
- **CPU 时间计量**：ADR-0028 遗留未变，wall-clock 之外仍无进程级 CPU 账。

## 执行期修正记录

（执行时逐条追加：计划写的与代码实况不一致、口径因真实源反馈而改、验证暴露的设计缺陷。**不删原计划文字**，在本段记「原计划 X，落地改成 Y，因为 Z」。）

**2026-09-09 · Task 1**

1. 原计划给 `AndroidNativeConventionPlugin.kt` 写了 `package com.xrn1997.convention`，落地删掉。
   该文件与同目录其余约定插件一样在默认包（只有 `libs`/`configureXxx` 这类工具在 `com/` 子目录下带包名），
   而注册用的是 `implementationClass = "AndroidNativeConventionPlugin"`（无包名 FQN）——保留包声明会让
   implementationClass 指不到类。
2. 原计划 Step 2 写 `path = "src/main/cpp/CMakeLists.txt"`，编译报
   `Assignment type mismatch: actual type is 'String', but 'File?' was expected`。
   **AGP 9 的 `Cmake.path` 是 `File?`**（旧 DSL 是 String），落地改成 `file("src/main/cpp/CMakeLists.txt")`。
   同一次编译顺带证实：`defaultConfig.ndk.abiFilters += SET` 与 `version = STRING` 在 AGP 9 上类型正确，
   Step 2 的退路注释没有触发。
3. 验证命令的可寻址形式：根构建里 `:build-logic:convention:validatePlugins` 不可用（build-logic 是
   `includeBuild` 复合构建），实跑用 `./gradlew -p build-logic :convention:validatePlugins`。

**2026-09-09 · Task 2**

4. Step 2 的只读检查 `grep -c setjmp "$SRC/quickjs.c"` 前提不成立：`quickjs.c` 命中 1 处是
   `pthread_cond_signal`（`signal` 子串误命中），`dtoa.c:33` 有 `#include <setjmp.h>` 但全文无
   `setjmp`/`longjmp`/`jmp_buf` 使用——上游死 include。结论仍是**内核不走 setjmp 控制流**，
   但两件事被查实并写进 `third_party/quickjs/README.md`：`quickjs.c` 含 Atomics.wait 的 pthread
   等待队列（约 15 处 `pthread_*`，靠 `JS_SetCanBlock(rt, 0)` 在运行时废掉，不属 `os.*` 能力面）。
5. Step 5 的 `target_link_libraries(... pthread ...)` 在 Android 上链接失败：
   `ld.lld: error: unable to find library -lpthread`。**bionic 自 API 23 起把 pthread 实现在 libc 内**，
   NDK sysroot 没有 `libpthread.so`。落地删 `pthread`，`dl m` 保留。
6. Step 5 的 `target_include_directories(quickjs PUBLIC ...)` 改成 `SYSTEM PUBLIC`：
   内核头的 `static inline` 函数（`JS_DupValue`/`JS_IsBigInt` 等）在 `ebook_js` 的 TU 里实例化时，
   按本模块的 `-Wall -Wextra` 吐出 8 条 upstream `unused-parameter` 警告。vendored 原文不改、
   警告也不该由桥接层背，故标 SYSTEM。
7. Step 7 的期望值 `nm -D --defined-only $SO | grep -c JS_Eval` = `1` 是笔误：子串匹配会连带
   `JS_EvalFunction`/`JS_EvalThis`，实测 `3`。精确核对用
   `llvm-nm -D --defined-only $SO | grep -E "JS_Eval$"` → `0000000000053f5c T JS_Eval`。
   arm64 产物 5,701,672 字节、x86_64 6,241,240 字节，`.so` 已进 `merged_native_libs`/`stripped_native_libs`；
   release 变体只有 `arm64-v8a` 一条（ABI 口径按设计生效）。
8. Step 4 说「创建 `.gitattributes`」，实际**仓库根已有该文件**（`* text=auto eol=lf` + `*.bat/*.cmd eol=crlf`）。
   落地是在末尾追加 `third_party/quickjs/** -text`（路径规则比 `*` 具体，故覆盖全仓策略），
   并用 `git check-attr text -- third_party/quickjs/quickjs.c` 验得 `text: unset`。

**2026-09-09 · Task 3**

9. 原计划没写跨进程三个失败的可见性，落地全是 `internal`（`SandboxProtocolException`/
   `SandboxUnavailableException`/`JsExecutionFailedException`）：基类 `ScriptRuleException` 是
   `internal sealed`，Kotlin 不允许子类放宽可见性，写了直接编译不过。跨进程表达失败靠帧里的
   `status`/`error` 文本，公开面本就不需要它们——代码里留了这条注释，别再「顺手补 public」。

**2026-09-09 · Task 4**

10. 原计划给 `JsHostApi` 的脚本侧字段起名 `name`，落地改 `jsName`。Kotlin 里成员属性遮蔽 `Enum.name`
    且不报错，判表时读到的是常量名（`AJAX`）而不是脚本写的 `ajax`——这一条在 Task 9 才真爆出来（见 19）。
11. 原计划用 `Base64.getMimeDecoder()`，落地改 `getDecoder()` 并显式拒绝非法字符：MIME 解码器会
    **静默丢弃**坏字符，对畸形输入给出「解成功了」的假结果，测试里看不出差别。
12. 原计划 `md5("小说")` 的期望向量是错的，落地按 JDK 实算替换为 `1fb52965b3af8e431578f16d6c31a5d5`
    （Task 11 复查用 `printf '小说' | md5sum` 再次核对一致）。
13. 原计划把两段 Kotlin 原始字符串直接相邻拼垫片，`""""""` 会被词法切成「空串 + 未闭合」而报错，
    落地在拼接处分行写。
14. 原垫片的 `__bind` 用 `String(v)` 把 null 落成字符串 `"null"`，落地改成与缺失同落 `undefined`：
    正文净化会把 `"null"` 当正文，比空值更糟。`src`/`title`/`chapter` 恒缺这一层原因记进规格 §11-31。
15. 原计划用 `String(byteArrayOf(...))` 造含负字节的输入做往返断言，落地一律显式传 charset：
    不带 charset 的那一路按**平台默认字符集**解，同一份测试在 Windows 与 CI 上能一边绿一边红。

**2026-09-09 · Task 7**

16. 原计划的消费者 include 路径直接挂 `${QJS_DIR}`，落地改成只挂 `third_party` **父目录**、
    引头写 `#include "quickjs/quickjs.h"`：那里面有上游签入的 `VERSION` 文件，而 Windows 文件系统
    大小写不敏感——libc++ 的 `#include <version>` 会命中它，症状是在完全没碰过的 STL 头链里报
    「expected unqualified-id」，且只在 Windows 上出现。理由写进了 `CMakeLists.txt` 的注释。

**2026-09-09 · Task 8**

17. 原计划用 `wait/notifyAll` 做 bind 等待，落地用 `CountDownLatch`：那两个是 `java.lang.Object` 的方法，
    Kotlin 的 `Any` 上没有，编译直接不过。
18. 原计划认为「在 `onCreate` 开头加进程门」就够了，落地不够：Hilt 生成的 `Hilt_MyApplication.onCreate()`
    在业务 `onCreate` **之前**完成字段注入，`MyApplication` 原有的 eager `@Inject lateinit var bookRepository`
    会在隔离进程里初始化 Room 与网络。落地改成 `EntryPointAccessors` 按需取，并把
    「Application 上不许再有 eager `@Inject` 字段」写进该类 KDoc——门拦不住注入，只能拦住注入之后干什么。

**2026-09-09 · Task 9**

19. 原计划的 `handle` 用 `JsHostApi.entries.firstOrNull { it.name == api }` 查表，落地改 `byJsName(api)`：
    过边界的是脚本里写的那个名字（`ajax`/`base64Encode`），常量名是大写蛇形，按 `name` 比会把**每一条真能力**
    判成「沙箱里没有」——症状是全量 JS 段失败，而不指向根因。`dispatch` 的能力标签与 `logSink` 的 channel
    同步改用 `jsName`（用 `capability.name` 会 emit `"TOAST:…"`，toast 落不进 `channel == "toast"` 的级别路由）。
20. 原计划的 `dispatch` 写成普通 `private fun`，落地加 `suspend`：它的分支调用 suspend 的 `send`/`probe`。
    `handle` 侧的同步契约仍由那一处 `runBlocking` 桥接（关键事实 1），没有因此改动。
21. 原计划的一例测试「ajax 取回白名单内的页面，并把它当作下一次定位的基准」从没触发 `getElements`，
    基准无人消费、断言永远不可能变绿。落地在 ajax 之后补一次 `getElements`，与用例标题对齐。

**2026-09-09 · Task 10**

22. 原计划的 `runUrlJs` 用脚本回传的头部**整体替换**选项头部，落地改成合并（脚本侧同键覆盖）：
    计划的实现会连 `User-Agent` 一起丢掉，而计划自己的测试断言它仍然存在——两处矛盾时按测试的意图改实现。
23. 原计划给 `bookBindings` 写了 7 个键，落地只有 5 个：`kind` 与 `lastChapter` 不在 `BookInfoEntity` 上
    （它们是 `SearchBookEntity` 的字段），硬编造值等于给脚本假数据。缺的两个键在 KDoc 里写明原因。
24. 原计划 `SandboxScriptJsTest` 里两例规则串在本仓 DSL 下取不到值，落地换写法：`"p"` 会落进 §3.2 的
    「@任意属性名」取值器而返回空（改 `tag.p`，要的正是 `AccessorKind.ALL` 的外形态）；链式段名里
    未闭合的方括号被原样保留成标签名（Miss 而非语法错），改 `@css:.foo[` 才真到 `SelectorParseException`。
    断言本身一个字没动。
25. `BookSourceManagerImplTest` 不在计划的文件清单里但必须改：新增的 `jsHost` 参数让位置实参错位，
    两处改具名参数。这类「计划清单漏一侧」的下一步是编译失败，不是静默行为差。
26. 原 `JsCallbackProxy` 类 KDoc 写「装配点就在本模块」，与 Step 6 把 `SandboxModule` 落
    `lib_book_common` 冲突，落地按事实改写并列出仍留 internal 的六个同类。
27. 计划里引用的行号在落地时普遍漂移（`ScriptRuleEvaluator.kt:162`→182-185、
    `Interpolation.kt:131-137`→143）。按构造名定位而不是按行号，别再回改成行号。

**2026-09-09 · Task 11**

28. Step 2（设备）**未执行**：写文档时 `adb devices` 为空，`QuickJsBridgeTest` 与 `SandboxConnectionTest`
    一次都没跑过。已把「本计划未在设备上验证」写在 `docs/test-coverage-todo.md` 新清单段首，
    Step 10 的提交信息 body 必须带上同一句（AGENTS.md「Agent 止于第一步」的落地方式）。
    **该状态已被第 35 条撤销**（设备首跑已执行并抓出两个坑）；提交信息 body 那句也随之作废，不得再照抄。
29. 有一处**范围差**必须显式记账，不能只留在会话里：ADR-0029 决策 4 的 v1 必做清单含 `ajaxAll` 与
    `connect`，而 Plan 3 的 28 能力表没有它们——批量外呼与「跟随重定向取真实地址」在 JS 侧没有入口
    （这句已被第 33 条订正：两者现在各有可诊断的拒绝桩）。
    这是计划编写时定下的收窄（不是实现打折），已就地记进 ADR-0029 的落地状态与人工清单第 5 条。
30. 守门覆盖面按代码实况写成「只覆盖**脚本自己发起**的外呼」：URL 选项 `js` 改写出的地址与请求头仍由
    取文层按原路径发出，不过 host 白名单与私网判定。这条缝隙原计划文本没有，落地写进 ADR-0028 的遗留
    与规格 §10 的 `js`/`bodyJs` 行——把它说成「全部沙箱网络都过守门」就是文档撒谎。

**2026-09-09 · 退出判据审计**

31. 按退出判据第 3 条把五份文档逐条对代码核了一遍，六处陈述与实现不符，均按事实就地改：
    ADR-0029 影响面里的「两份 Manifest 同步 `:js` Service 声明」（`lib_book_source` 是纯 library，
    全仓只有一份库清单声明该服务）；ADR-0028 决策 4 的协议签名 `execute(script, input, timeoutMs)`
    （帧里是模式/脚本/绑定三样，超时由主进程算成绝对期限随帧带过去）；ADR-0028 决策 2 的「.so 加载
    异常退化为普通 `:js` 进程」（今天只有 `UNAVAILABLE` 一条失败处置，去隔离必须先改本文，与 Task 8
    里那条禁令对齐——禁令原文两处把它写成「重议决策 3」，隔离进程其实是决策 2，计划正文已就地纠正）；
    规格 §10 变量行写「表的作用域是**一次沙箱调用**、跨调用取回 null」——与它自己
    引的 §11-33 和代码（代理与 `EvalContext.variables` 同一实例）正相反，是唯一会让人照着改错代码的一条；
    `BookSourceManagerImpl` 类 KDoc 仍说脚本行「路由到桩解析器」（2d 已删桩）；`docs/test-coverage-todo.md`
    第 5 条对 `ajaxAll`/`connect` 的失败描述同步跟上第 33 条。规格 §10 那一行的根因在计划自己身上：
    Task 11 要求写进规格的 §11-33 把「变量表」与「`globalThis`」并成一句「作用域 = 一次沙箱调用」，
    落地时 §11-33 已拆成两件事、§10 行却照抄了合并版——同一份文档里两个粒度必须分开写。
32. ADR 正文里三处未定义的外部锚点（「债见规格 §11-25」「即 §11-5」「见 §11-25」）按仓库规则改写成
    自足描述。裸 § 号与「见计划文件」是同一类毛病：打开 ADR 的人无从自查，被引用的事实必须就地重述。
33. `ajaxAll` 的待遇与 `connect` 不一致是**漏登记**，不是设计：垫片拒绝族自己的口径是「给一句可诊断的话，
    而不是让它撞进 ReferenceError」，`connect` 在名单里而 `ajaxAll` 不在。按 TDD 先加一条 JVM 用例锁住
    「拒绝桩名单在垫片与测试两侧同集合、且含 `ajaxAll`」（红）→ 再补垫片数组与 `rejectedStub`（绿），
    `lib_book_source` 463 → 464。拒绝文案的类别列举补了「批量外呼」一族；设备侧那两例断言的是能力名
    而不是文案（`QuickJsBridgeTest` 用 `contains("cookieManager")`），扩类别词不动它们。
34. 复核数字：`:lib_book_source` 464 / `:lib_book_common` 281，0 失败 0 跳过；`./gradlew test` 全绿
    （**后续一次全量跑闪了一例**，根因与本轮无关，见第 37 条）；
    `lib_book_source` 三套 Kotlin 源 `--rerun` 零警告；红线 `git grep` 在含未跟踪文件的全文检索下仍为 0。

**2026-09-09 · 设备首跑（撤销第 28 条的「未执行」）**

35. `:lib_book_source:connectedDebugAndroidTest` 16 例（`QuickJsBridgeTest` 12 + `SandboxConnectionTest` 4）
    在 Pixel_8 AVD / Android 17 上全绿。首跑抓出两个**只有真机才出现**的坑，都已修并各留一条设备锁形：
    - **深递归把 native 栈撞穿**：配置值 1 MB 大于跑内核那条线程的实际剩余栈，症状不是某条用例失败，
      而是整个 instrumentation 进程消失、后面的用例一起没了。改成每次执行前按本线程现算预算、配置值退成
      天花板（ADR-0028 决策 5 的补记即此）。用例除断 `status == STACK` 外还追加一次真求值断言「进程还在」——
      只看 status 锁不住「没撞穿」这件事。
    - **堆到限被报成普通运行时失败**：内核要说那句 `out of memory` 得先建错误对象、再建 message，
      那一笔分配同样落在限值之外。第一版处方（宽限窗口 + 「此刻仍贴着限值」双闸门）被**第二次设备跑否掉**：
      异常沿栈展开时临时值已释放，分类时刻的读数必然偏低；窗口则先被脚本自己那轮越限分配吃光，
      等错误路径要分配时已无余额。终版只按两个条件判：分配器拒过（实测被拒那一刻
      `used=8387144 / limit=8388608`，距限值仅 1.4 KB）+ 这条异常说不出独立信息（裸 `null`、
      stringify 也失败、或只回了自己的类名）。协议、帧编解码与 `JsProtocol.mapStatus` 的词汇表都没动，
      归类仍走内核本来的措辞 `InternalError("out of memory")`。
36. 隔离性的设备判据换了 instrument：`ActivityManager.getRunningAppProcesses()` 自 Android 5 起按调用方
    uid 过滤，**隔离进程在主进程侧结构性不可见**（补权限也不会可见），原先「枚举到 `:js` 就算隔离」
    是一条永假断言。改成在 host 回调里读 `Binder.getCallingUid()`（回调确实由 `:js` 发起才拿得到），
    API 34+ 用 `Process.isIsolatedUid` 判、以下退化为「不是本进程 uid」。顺带把「回调走通了」与
    「隔离生效」锁在同一条用例上——前者不成立时后者无从谈起。
37. `./gradlew test` 一次全量跑里 `module_find` 的 `SearchViewModelTest` 以
    `UncaughtExceptionsBeforeTest` 失败一次：一条跑在真实 `Dispatchers.IO` 上的 `withContext`
    在 `Dispatchers.resetMain()` 之后才恢复回 Main。**与本轮改动无关**（module_find 与其测试源集本轮
    零改动，该竞态在测试类注释里早已登记，打法是 `awaitUntil`），且单跑该类三次、整模块连跑五次都不复现——
    泄漏者更可能是同一 worker 里先跑的类。按仓规登记进 `docs/test-coverage-todo.md` 待办，
    不在本分支里顺手改他人测试基建。

**2026-09-09 · 复核（Standards/Spec 双轴评审之后逐条验真）**

38. 评审的 Standards 轴里有一条**完整性缺陷**，是本轮最值钱的一击：`third_party/quickjs/` 的 17 个上游
    文件在工作树里全是 **CRLF**，而 `PIN.sha256` 正是从这份 CRLF 算出来的——校验当然永远通过，因为它
    证的是自己。根 `.gitattributes` 的 `third_party/quickjs/** -text` 又把「提交 CRLF 字节」固定下来，
    于是 README 与 ADR-0029 那句「与上游逐字节相同」当场为假。根因是**本地** `core.autocrlf=true`
    在 clone 检出时做的转换，上游本身是纯 LF：`VERSION` 上游 11 字节 / 0 个 `\r`，我们 12 字节 / 1 个；
    `cutils.h` 上游 11 162 字节 / 457 行，我们 11 619 = 11 162 + 457。测量口径 `tr -dc '\r' < f | wc -c`
    （`grep -c $'\r'` 会每行都算命中，给出的数字荒谬且无用）。
    修复前先做一次**独立**验证而不是自我循环：把 17 个文件逐个 `tr -d '\r'` 后与按 pin 提交
    `04be246` 从 raw.githubusercontent 拉下来的原件比 sha256，17/17 相同——**内容一个字没错，错的只是行尾**。
    随后把工作树换成 LF 字节、从 LF 重算 `PIN.sha256`，并顺手把覆盖面从 15 个扩到 17 个
    （`LICENSE`/`VERSION` 此前不在校验清单里，而 README 那句「这些文件是上游原文」把它们也算进去了）。
    `.gitattributes` 与 README 的根因描述按事实改写：不写「上游含 CRLF」，写「上游是 LF、本地 autocrlf
    会转成 CRLF、`-text` 让本路径正反向都不转换」。
    38b. 同轮修两处**读代码**坐实的缺陷：`js_bridge.cpp` 截止钟那行注释写 `CLOCK_MONOTONIC 休眠时停表`
    而下一行代码取的是 `CLOCK_BOOTTIME`（不休眠停表，与 `SystemClock.elapsedRealtime()` 同口径），
    注释与 `QuickJsNative.kt` 的参数文档互相打脸；`JsProtocol.decodeRequest` 用裸 `JsMode.valueOf`
    解模式名，未知名字以 `IllegalArgumentException` 漏出，违反本类 KDoc 那条「解码一律不往外抛未类型化
    异常」——改成 `parseMode` 抛 `SandboxProtocolException`（**不**折叠成默认模式：那等于拿一段 `@js:`
    按 `init` 的取法去执行，产出的是一个看似合理的答案）。
    38c. **教训记一条给将来的自己**：为 38b 第二项写的第一版锁形用例**假绿**了。帧里少写 `deadline`
    字段（`RequestFrame` 四字段全无默认值），失败落在「缺字段」那一支，压根没走到模式查表，
    而那一支抛的正是我断言的 `SandboxProtocolException`。补齐 `"deadline":1` 才见到预期的红
    （`expected SandboxProtocolException but was IllegalArgumentException`）。断言异常类型的用例，
    必须另外确认失败**发生在哪一步**——否则锁住的是另一件事。
    38d. 重验：`sha256sum -c PIN.sha256` 17/17 OK 且残留 `\r` 为零；`:lib_book_source:testDebugUnitTest`
    464 → **465** 例 0 失败 0 跳过；`assembleDebug` 下 `buildCMakeDebug[arm64-v8a]` 与
    `[x86_64]` 双双**从 LF 字节重新编译**，日志里 `^w:` 与 `Warning:` 均为 0；
    `:lib_book_source:connectedDebugAndroidTest` 重跑 `Starting 16 tests … Finished 16 tests` 全绿
    （`packageDebugAndroidTest` 因 `.so` 变更确实重新打包装机，非缓存）。
39. Standards 轴另一条命中，且是**仓规字面违反**：`AndroidNativeConventionPlugin` 里
    `const val NDK_VERSION = "28.2.13676358"` / `CMAKE_VERSION = "3.22.1"` 两个硬编码版本号。
    「工具链版本不算依赖版本」这个说法在本仓不成立——`build-logic/settings.gradle.kts` 早就把
    同一份 `gradle/libs.versions.toml` 挂成 `libs`（`from(files("../gradle/libs.versions.toml"))`），
    目录里也已经有 `androidGradlePlugin` / `androidTools` 这一族「AGP and tools」，AGENTS.md 写的
    结构性豁免只有 `settings.gradle.kts` 的 `plugins {}` 块一处。落地改成
    `extensions.getByType(VersionCatalogsExtension::class.java).named("libs")` 读
    `findVersion("androidNdk")` / `findVersion("cmake")`，两处条目按目录既有的字母序插入并各带一句
    「为何钉死」的注释；`RELEASE_ABIS` 不动（策略集合，不是版本）。
    一个坑值得单记：Gradle 9 的 `VersionCatalogsExtension` 在 `org.gradle.api.artifacts` 下，
    写成 `org.gradle.api.plugins.catalog.*`（那是 `VersionCatalogPlugin` 的包）编译期直接
    `Unresolved reference`。另证实该扩展在**子项目**上即可 `getByType` 拿到，不需要绕 `rootProject`。
    验证：`:lib_book_source:assembleDebug` 下 `configureCMakeDebug`/`buildCMakeDebug` 两个 ABI 全部
    重新执行并通过，`.cxx` 的 CMake 缓存里 NDK 仍是 `28.2.13676358`（值没漂），日志 `^w:` / `Warning:` 为 0。

