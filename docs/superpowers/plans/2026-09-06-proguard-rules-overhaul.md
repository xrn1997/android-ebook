# 混淆规则体系重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依据 spec（`docs/superpowers/specs/2026-09-06-proguard-rules-overhaul-design.md`）开启 release R8 混淆，把 9 份 proguard 规则文件重构为"一份文件、双声明、双态生效"的模块自治架构，重构掉 EncodingInterceptor 对 OkHttp 私有字段的反射。

**Architecture:** 每模块一份 `proguard-rules.pro`，同时挂 `consumerProguardFiles`（集成态随 AAR 传播给 module_app 的 R8）与 buildTypes.release `proguardFiles`（独立态自己是 R8 执行者）。规则按反射面归属：功能模块持 TheRouter 规则，module_app 持行号属性，lib 模块零手写规则（第三方自带规则已覆盖）。唯一业务代码改动是 EncodingInterceptor 去反射（TDD）。

**Tech Stack:** AGP 9.2.1（内置 Kotlin、R8 full mode 默认）、Gradle 9.4.1、Kotlin 2.4.10、OkHttp 5.3.0、TheRouter 1.4.0-rc1、kotlinx-serialization 1.11.0、JUnit 4。

---

## 执行环境须知（每个任务开始前先读）

- 仓库根：`D:\develop\GitHub\android-ebook`，分支 `test`，shell 为 Git Bash（Windows）。
- Gradle 调用一律在仓库根执行 `./gradlew ...`；守护进程 JVM 由 `gradle-daemon-jvm.properties` 自动拉取，无需本地 JDK。
- **工作树安全（硬约束）**：工作树里有用户未提交改动——`gradle.properties`（本地 `isModule=true`）、`gradle/libs.versions.toml`、`module_me/*` 若干文件。**这些一律不碰、不 stage**。每次 commit 只 `git add` 计划明确列出的文件。若构建因用户未提交的中间态失败，停下来向用户报告，不要自行修改用户的文件。
- commit-msg 钩子校验 Conventional Commits（type(scope): 中文动词开头描述）。
- `isModule` 当前为 `true`（用户本地态）。Task 1-10 依赖此值（feature 模块独立态构建验证）；Task 5 开始前若发现是 `false`，临时改回 `true` 并在 Task 11 结束时恢复为**本计划开始时读到的值**。

---

### Task 1: EncodingInterceptor 去反射（TDD）

**Files:**
- Create: `lib_ebook_api/src/test/java/com/ebook/api/intercepter/EncodingInterceptorTest.kt`
- Modify: `lib_ebook_api/src/main/java/com/ebook/api/intercepter/EncodingInterceptor.kt`（整文件重写）

- [ ] **Step 1: 写失败测试**

创建 `lib_ebook_api/src/test/java/com/ebook/api/intercepter/EncodingInterceptorTest.kt`：

```kotlin
package com.ebook.api.intercepter

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class EncodingInterceptorTest {

    private fun responseOf(contentType: String?, bytes: ByteArray): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/book").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(bytes.toResponseBody(contentType?.toMediaTypeOrNull()))
        .build()

    @Test
    fun `contentType 被强制为 rss+xml 且正文原样可读`() {
        val response = responseOf("text/html", "<html>第一章</html>".toByteArray())
        val out = response.withForcedContentType(
            "application/rss+xml;charset=UTF-8".toMediaTypeOrNull()
        )
        assertEquals("application/rss+xml;charset=UTF-8".toMediaTypeOrNull(), out.body.contentType())
        assertEquals("<html>第一章</html>", out.body.string())
    }

    @Test
    fun `原响应无 contentType 时同样强制并透传正文`() {
        val response = responseOf(null, "正文内容".toByteArray(StandardCharsets.UTF_8))
        val out = response.withForcedContentType(
            "application/rss+xml;charset=UTF-8".toMediaTypeOrNull()
        )
        assertEquals("application/rss+xml;charset=UTF-8".toMediaTypeOrNull(), out.body.contentType())
        assertEquals("正文内容", out.body.string())
    }

    @Test
    fun `未知 contentLength 保持未知不触发全量缓冲`() {
        // contentLength 为 -1（未知）时，包装后的 body 不得把 -1 变成 0 或实际长度
        val bytes = "长正文".toByteArray(StandardCharsets.UTF_8)
        val response = responseOf("text/html", bytes)
        val out = response.withForcedContentType(
            "application/rss+xml;charset=UTF-8".toMediaTypeOrNull()
        )
        assertEquals(-1L, out.body.contentLength())
        assertEquals("长正文", out.body.string())
    }
}
```

- [ ] **Step 2: 跑测试确认失败（RED）**

Run: `./gradlew :lib_ebook_api:testDebugUnitTest --tests "com.ebook.api.intercepter.EncodingInterceptorTest"`
Expected: **编译失败**，`unresolved reference: withForcedContentType`（函数尚不存在）。

- [ ] **Step 3: 实现最小代码**

整文件重写 `lib_ebook_api/src/main/java/com/ebook/api/intercepter/EncodingInterceptor.kt`：

```kotlin
package com.ebook.api.intercepter

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import java.io.IOException

/**
 * 编码拦截器：书源响应体的 Content-Type 由第三方站点给出，缺 charset 或声明错误时
 * 阅读端会按错误编码解码中文正文。本拦截器把响应体 contentType 强制改写为
 * application/rss+xml;charset=<encoding>，正文流式透传不缓冲。
 *
 * 历史实现经反射改写 OkHttp 私有字段 RealResponseBody.contentTypeString：该字段名
 * 不在任何兼容性承诺内，OkHttp 升级会改名、R8 混淆会重命名，任一发生都会让
 * getDeclaredField 抛 NoSuchFieldException → IOException → 全部书源请求失败。
 * 现改为 OkHttp 公开 API 等价实现（source 包装 + newBuilder），无需任何 keep 规则。
 */
class EncodingInterceptor(
    /**
     * 自定义编码
     */
    private val encoding: String
) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val response: Response = chain.proceed(chain.request())
        return response.withForcedContentType(
            "application/rss+xml;charset=$encoding".toMediaTypeOrNull()
        )
    }
}

/**
 * 把响应体 contentType 强制改写为 [contentType]，正文 source 流式透传、不缓冲、
 * contentLength 原样保留（-1 未知时保持未知）。OkHttp 5 的 body 非空（无 body 时
 * 是空 body），无需判空。
 */
internal fun Response.withForcedContentType(contentType: MediaType?): Response {
    val body = this.body
    return newBuilder()
        .body(body.source().asResponseBody(contentType, body.contentLength()))
        .build()
}
```

注：`asResponseBody` 是 OkHttp 5.3 公开 Kotlin 扩展（`okhttp3.ResponseBody.kt:267-279`，`@JvmName("create")`，非弃用）。若 import 意外解析失败，等价回退为 `ResponseBody.create(contentType, body.contentLength(), body.source())`——同一 JVM 方法。

- [ ] **Step 4: 跑测试确认通过（GREEN）**

Run: `./gradlew :lib_ebook_api:testDebugUnitTest --tests "com.ebook.api.intercepter.EncodingInterceptorTest"`
Expected: PASS（3 个测试全绿）。

- [ ] **Step 5: 模块全量测试回归**

Run: `./gradlew :lib_ebook_api:testDebugUnitTest`
Expected: 全绿（既有 mock 资产契约测试等不受影响）。

- [ ] **Step 6: Commit**

```bash
git add lib_ebook_api/src/main/java/com/ebook/api/intercepter/EncodingInterceptor.kt lib_ebook_api/src/test/java/com/ebook/api/intercepter/EncodingInterceptorTest.kt
git commit -m "$(cat <<'EOF'
refactor(lib_ebook_api): 重构 EncodingInterceptor 去除 OkHttp 私有字段反射

反射写 RealResponseBody.contentTypeString 在 R8 混淆下必抛
NoSuchFieldException，且字段名跨 OkHttp 版本不受兼容承诺；
改为 OkHttp 公开 API（source.asResponseBody + newBuilder）等价实现，
contentType 强制与流式透传行为不变，新增 JVM 单测锁行为。
EOF
)"
```

---

### Task 2: lib_ebook_db —— 清理死规则、删无效 buildTypes

**Files:**
- Modify: `lib_ebook_db/proguard-rules.pro`（整文件重写）
- Modify: `lib_ebook_db/build.gradle.kts`（删 buildTypes 块）

- [ ] **Step 1: 重写规则文件**

整文件替换 `lib_ebook_db/proguard-rules.pro` 为：

```proguard
# lib_ebook_db 混淆规则（一份文件、双声明；本模块恒为 library，规则经
# consumerProguardFiles 传播给消费方的 R8，本模块自己的 proguardFiles 不生效）。
# 当前无需任何手写 keep 规则：Room3（androidx.room3）运行时 AAR 自带 consumer
# 规则覆盖实体/DAO/生成代码；实体与 DAO 由 Room 编译期生成代码直接引用，
# 无运行时反射。原模板遗留的死规则（**$Properties、net.sqlcipher、rx.**）
# 已删除——本仓库无对应类与依赖。
# 新增反射面（Class.forName / getDeclaredField / kotlin-reflect 等）时，
# 把对应 keep 规则写进本文件并注明证据；禁止无证据的 -keep/-dontwarn。
```

- [ ] **Step 2: 删 buildTypes 块**

在 `lib_ebook_db/build.gradle.kts` 中，将：

```kotlin
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
```

替换为（只删 buildTypes 块，其余不动）：

```kotlin
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

依据：本模块恒为 library（`xrn1997.android.library`），无 application 身份，R8 永不在本模块执行，buildTypes 块是无效配置。

- [ ] **Step 3: 构建验证**

Run: `./gradlew :lib_ebook_db:assembleRelease`
Expected: BUILD SUCCESSFUL（library 无 R8 任务，验证脚本语法与 consumer 声明合并）。

- [ ] **Step 4: Commit**

```bash
git add lib_ebook_db/proguard-rules.pro lib_ebook_db/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(lib_ebook_db): 清理死混淆规则并移除无效 buildTypes 配置

删除模板遗留的 **$Properties、net.sqlcipher、rx.** 死规则（无对应类与依赖）；
本模块恒为 library 无 R8 任务，proguardFiles 声明无效，规则唯一入口是
既有 consumerProguardFiles。
EOF
)"
```

---

### Task 3: lib_ebook_api —— 规则占位、挂 consumer 声明、删 buildTypes

**Files:**
- Modify: `lib_ebook_api/proguard-rules.pro`（整文件重写）
- Modify: `lib_ebook_api/build.gradle.kts`（defaultConfig 加 consumer 声明、删 buildTypes 块）

- [ ] **Step 1: 重写规则文件**

整文件替换 `lib_ebook_api/proguard-rules.pro` 为：

```proguard
# lib_ebook_api 混淆规则（一份文件、双声明；本模块恒为 library，规则经
# consumerProguardFiles 传播给消费方的 R8）。
# 当前无需任何手写规则，依据：
# - DTO 反射式 serializer 查找（JsonUtils.parseJson 的 clazz.kotlin.serializer()）
#   由 kotlinx-serialization-core jar 内置的
#   META-INF/proguard/kotlinx-serialization-common.pro 覆盖（已核对该文件内容）；
# - Retrofit 接口反射由 retrofit jar 内置 META-INF/proguard/retrofit2.pro 覆盖；
# - EncodingInterceptor 已去除对 OkHttp 私有字段 contentTypeString 的反射，
#   改为公开 API 等价实现。
# 新增反射面时把 keep 规则写进本文件并注明证据；禁止无证据的 -keep/-dontwarn。
```

- [ ] **Step 2: 改构建脚本**

在 `lib_ebook_api/build.gradle.kts` 中，将：

```kotlin
    defaultConfig {
        buildConfigField("String", "EBOOK_SERVER_HOST", "\"$ebookServerHost\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
```

替换为（buildTypes 块删除，consumer 声明进 defaultConfig）：

```kotlin
    defaultConfig {
        buildConfigField("String", "EBOOK_SERVER_HOST", "\"$ebookServerHost\"")
        consumerProguardFiles("proguard-rules.pro")
    }
```

- [ ] **Step 3: 构建验证**

Run: `./gradlew :lib_ebook_api:assembleRelease`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add lib_ebook_api/proguard-rules.pro lib_ebook_api/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(lib_ebook_api): 规范混淆规则声明为 consumerProguardFiles

删除无效的 buildTypes.proguardFiles（library 无 R8 任务），规则统一经
consumerProguardFiles 传播；规则文件改写为反射面登记说明——现有反射点
均由 kotlinx-serialization 与 retrofit 自带规则覆盖。
EOF
)"
```

---

### Task 4: lib_book_common —— 挂 consumer 声明（buildTypes 保留 IS_DEBUG）

**Files:**
- Modify: `lib_book_common/proguard-rules.pro`（整文件重写）
- Modify: `lib_book_common/build.gradle.kts`（android{} 内新增 defaultConfig 块）

- [ ] **Step 1: 重写规则文件**

整文件替换 `lib_book_common/proguard-rules.pro` 为：

```proguard
# lib_book_common 混淆规则（一份文件、双声明；本模块恒为 library，规则经
# consumerProguardFiles 传播给消费方的 R8）。
# 当前无需任何 keep 规则，依据：Jsoup / juniversalchardet / PermissionX 无反射
# 需求；BookSourceManagerImpl 的 serializer 调用为编译期静态引用。
# 新增反射面时把 keep 规则写进本文件并注明证据；禁止无证据的 -keep/-dontwarn。
```

- [ ] **Step 2: 新增 defaultConfig 块**

在 `lib_book_common/build.gradle.kts` 中，将：

```kotlin
android {
    namespace = "com.ebook.common"
    buildTypes {
        debug {
            buildConfigField("boolean", "IS_DEBUG", "true")
        }
        release {
            buildConfigField("boolean", "IS_DEBUG", "false")
        }
    }
```

替换为（新增 defaultConfig，buildTypes 的 IS_DEBUG 保留不动）：

```kotlin
android {
    namespace = "com.ebook.common"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "IS_DEBUG", "true")
        }
        release {
            buildConfigField("boolean", "IS_DEBUG", "false")
        }
    }
```

依据：buildTypes 块承载 IS_DEBUG buildConfigField（debug/release 行为差异），必须保留；本模块此前没有任何规则声明入口（proguard-rules.pro 是孤儿文件）。

- [ ] **Step 3: 构建验证**

Run: `./gradlew :lib_book_common:assembleRelease`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add lib_book_common/proguard-rules.pro lib_book_common/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(lib_book_common): 挂载 consumer 混淆规则入口

此前 proguard-rules.pro 无任何声明引用（孤儿文件）；补上
consumerProguardFiles 使规则文件成为该模块反射面的正式登记处，
buildTypes 的 IS_DEBUG 字段不受影响。
EOF
)"
```

---

### Task 5: module_book —— 规则重写 + 双态构建改造 + 独立态 R8 验证

**Files:**
- Modify: `module_book/proguard-rules.pro`（整文件重写）
- Modify: `module_book/build.gradle.kts`（import、defaultConfig、buildTypes）

- [ ] **Step 0: 前置检查**

Run: `grep -E "^isModule" gradle.properties`
Expected: `isModule=true`（独立态构建验证依赖此值）。若为 `false`，临时改为 `true`（在 Task 11 统一恢复）。

- [ ] **Step 1: 重写规则文件**

整文件替换 `module_book/proguard-rules.pro` 为（本内容同样用于 Task 6-9 与 Task 10 的对应段落，五份一致）：

```proguard
# 本模块混淆规则（一份文件、双声明、双态生效）：
# - 集成态（isModule=false，本模块是 library）：经 consumerProguardFiles 随 AAR
#   传播进 module_app 的 R8；本模块的 proguardFiles 不生效（library 无 R8 任务）。
# - 独立态（isModule=true，本模块是 application）：buildTypes.release 的
#   proguardFiles 生效，R8 在本模块执行。
# 归属原则：只写本模块反射面需要的规则；第三方库自带的 consumer 规则不重复。
# TheRouter 规则依据：官方 README 要求（github.com/HuolalaTech/hll-wp-therouter-android）
# + 运行时 RouteMapKt 按类名字符串 Class.forName 的字节码证据。

# —— TheRouter（官方 README 全套）——
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * {*;}
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}
-keepclasseswithmembers class * {
    @com.therouter.router.Autowired <fields>;
}

# —— ServiceProvider 实现类 ——
# 运行时按类名字符串反射创建（RouteMapKt forName），且不在 manifest、无 aapt
# keep 兜底。@Route 的 Activity 由 manifest aapt 规则兜底，无需显式 keep。
-keep @com.therouter.inject.ServiceProvider class * {
    *;
}
```

- [ ] **Step 2: 改构建脚本**

在 `module_book/build.gradle.kts` 顶部 import 区（`import org.jetbrains.kotlin.gradle.dsl.JvmTarget` 之后）追加：

```kotlin
import com.android.build.api.dsl.LibraryDefaultConfig
```

将 defaultConfig 与 buildTypes：

```kotlin
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        named("debug") {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
        named("release") {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }
```

替换为（debug 块删除，release 开 R8，defaultConfig 加 consumer 守卫）：

```kotlin
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 集成态（library）：consumer 规则随 AAR 传播给 module_app 的 R8。
        // AGP 9 双态下 defaultConfig 接收器按模式是具体类型，须经 is
        // LibraryDefaultConfig 智能转换两种模式才都编译通过（同 module_me
        // versionCode 的 ApplicationDefaultConfig 先例）；
        // 独立态（application）无 consumer 概念，此声明不生效。
        if (this is LibraryDefaultConfig) {
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    buildTypes {
        release {
            // 集成态本标志对 library 无操作（AGP 只对 application 执行 R8）；
            // 独立态打 release 包时 R8 在本模块执行，模块级尽早暴露规则缺口
            //（见 ADR-0024）。
            isMinifyEnabled = true
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }
```

- [ ] **Step 3: 独立态 R8 构建验证**

Run: `./gradlew :module_book:assembleRelease`
Expected: BUILD SUCCESSFUL，构建日志含 `minifyReleaseWithR8` 任务（R8 真实执行），无 "Missing class" 错误。若有 R8 missing-class 告警，逐条分析补证据规则或 `-dontwarn`，不盲加。

- [ ] **Step 4: Commit**

```bash
git add module_book/proguard-rules.pro module_book/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(module_book): 重写混淆规则并开启独立态 release R8

规则收敛为 TheRouter 官方全套 + ServiceProvider keep（路由类名反射证据），
删除 EventBus 死规则；consumerProguardFiles + proguardFiles 双声明覆盖
集成/独立两种身份；release isMinifyEnabled=true 独立态生效、集成态对
library 无操作。
EOF
)"
```

---

### Task 6: module_find —— 同 Task 5 模式

**Files:**
- Modify: `module_find/proguard-rules.pro`（整文件重写，内容与 Task 5 Step 1 完全一致）
- Modify: `module_find/build.gradle.kts`

- [ ] **Step 0: 前置检查**

Run: `grep -E "^isModule" gradle.properties` → Expected: `isModule=true`。

- [ ] **Step 1: 重写规则文件**

内容与 Task 5 Step 1 的完整文本逐字一致（复制整块，含头注释、TheRouter 全套与 ServiceProvider keep）。

- [ ] **Step 2: 改构建脚本**

追加 import `com.android.build.api.dsl.LibraryDefaultConfig`（位置同 Task 5）。

将（module_find 当前 defaultConfig 与 buildTypes）：

```kotlin
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }
```

替换为（与 Task 5 Step 2 的目标态一致——defaultConfig 带守卫 + consumer 注释、buildTypes 只留 release 且 isMinifyEnabled=true；`if (this is LibraryDefaultConfig)` 块、两段注释文字逐字复制 Task 5 Step 2）。

- [ ] **Step 3: 独立态 R8 构建验证**

Run: `./gradlew :module_find:assembleRelease`
Expected: BUILD SUCCESSFUL，含 `minifyReleaseWithR8`。

- [ ] **Step 4: Commit**

```bash
git add module_find/proguard-rules.pro module_find/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(module_find): 重写混淆规则并开启独立态 release R8

规则收敛为 TheRouter 官方全套 + ServiceProvider keep，删除 EventBus 死规则；
consumerProguardFiles + proguardFiles 双声明覆盖集成/独立两种身份。
EOF
)"
```

---

### Task 7: module_login —— 同 Task 6 模式

**Files:**
- Modify: `module_login/proguard-rules.pro`（整文件重写，内容同 Task 5 Step 1）
- Modify: `module_login/build.gradle.kts`

- [ ] **Step 0: 前置检查**：`grep -E "^isModule" gradle.properties` → `isModule=true`。
- [ ] **Step 1: 重写规则文件**：内容与 Task 5 Step 1 逐字一致。
- [ ] **Step 2: 改构建脚本**：import 追加；defaultConfig/buildTypes 替换为 Task 5 Step 2 目标态（module_login 当前块与 Task 6 所引旧块形状一致：defaultConfig 仅 testInstrumentationRunner，buildTypes debug+release 双块 minify false）。复制时注释逐字保留。
- [ ] **Step 3: 独立态 R8 构建验证**：`./gradlew :module_login:assembleRelease` → BUILD SUCCESSFUL，含 `minifyReleaseWithR8`。
- [ ] **Step 4: Commit**

```bash
git add module_login/proguard-rules.pro module_login/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(module_login): 重写混淆规则并开启独立态 release R8

规则收敛为 TheRouter 官方全套 + ServiceProvider keep，删除 EventBus 死规则；
consumerProguardFiles + proguardFiles 双声明覆盖集成/独立两种身份。
EOF
)"
```

---

### Task 8: module_main —— 同 Task 6 模式

**Files:**
- Modify: `module_main/proguard-rules.pro`（整文件重写，内容同 Task 5 Step 1）
- Modify: `module_main/build.gradle.kts`

- [ ] **Step 0: 前置检查**：`grep -E "^isModule" gradle.properties` → `isModule=true`。
- [ ] **Step 1: 重写规则文件**：内容与 Task 5 Step 1 逐字一致。
- [ ] **Step 2: 改构建脚本**：import 追加；module_main 当前 defaultConfig 仅 testInstrumentationRunner，buildTypes debug+release 双块 minify false，替换为 Task 5 Step 2 目标态（注释逐字复制）。
- [ ] **Step 3: 独立态 R8 构建验证**：`./gradlew :module_main:assembleRelease` → BUILD SUCCESSFUL，含 `minifyReleaseWithR8`。
- [ ] **Step 4: Commit**

```bash
git add module_main/proguard-rules.pro module_main/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(module_main): 重写混淆规则并开启独立态 release R8

规则收敛为 TheRouter 官方全套 + ServiceProvider keep，删除 EventBus 死规则；
consumerProguardFiles + proguardFiles 双声明覆盖集成/独立两种身份。
EOF
)"
```

---

### Task 9: module_me —— 同 Task 6 模式 + 保留 versionCode 守卫

**Files:**
- Modify: `module_me/proguard-rules.pro`（整文件重写，内容同 Task 5 Step 1）
- Modify: `module_me/build.gradle.kts`

- [ ] **Step 0: 前置检查**：`grep -E "^isModule" gradle.properties` → `isModule=true`。
- [ ] **Step 1: 重写规则文件**：内容与 Task 5 Step 1 逐字一致。
- [ ] **Step 2: 改构建脚本**

追加 import `com.android.build.api.dsl.LibraryDefaultConfig`（`import com.android.build.api.dsl.ApplicationDefaultConfig` 之后）。

将（module_me 当前 defaultConfig + buildTypes）：

```kotlin
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 版本号仅对独立运行（isModule=true，application）有意义，供设置页「版本」展示与检查更新占位；
        // library 集成模式版本由宿主 module_app 提供。AGP 9 的 LibraryDefaultConfig 已移除
        // versionCode/versionName，须经 is ApplicationDefaultConfig 智能转换才能两种模式都编译通过。
        if (this is ApplicationDefaultConfig) {
            versionCode = 1
            versionName = "1.0.0"
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }
```

替换为（versionCode 块原样保留，追加 consumer 守卫；debug 块删除，release 开 R8）：

```kotlin
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 版本号仅对独立运行（isModule=true，application）有意义，供设置页「版本」展示与检查更新占位；
        // library 集成模式版本由宿主 module_app 提供。AGP 9 的 LibraryDefaultConfig 已移除
        // versionCode/versionName，须经 is ApplicationDefaultConfig 智能转换才能两种模式都编译通过。
        if (this is ApplicationDefaultConfig) {
            versionCode = 1
            versionName = "1.0.0"
        }
        // 集成态（library）：consumer 规则随 AAR 传播给 module_app 的 R8。
        // AGP 9 双态下 defaultConfig 接收器按模式是具体类型，须经 is
        // LibraryDefaultConfig 智能转换两种模式才都编译通过（与上方
        // ApplicationDefaultConfig 先例同构）；
        // 独立态（application）无 consumer 概念，此声明不生效。
        if (this is LibraryDefaultConfig) {
            consumerProguardFiles("proguard-rules.pro")
        }
    }
    buildTypes {
        release {
            // 集成态本标志对 library 无操作（AGP 只对 application 执行 R8）；
            // 独立态打 release 包时 R8 在本模块执行，模块级尽早暴露规则缺口
            //（见 ADR-0024）。
            isMinifyEnabled = true
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }
```

注意：`module_me` 有用户未提交改动，仅按上述精确 old/new 编辑这两个块，不触碰其余内容；commit 只 add 本任务列出的两个文件。

- [ ] **Step 3: 独立态 R8 构建验证**：`./gradlew :module_me:assembleRelease` → BUILD SUCCESSFUL，含 `minifyReleaseWithR8`。
- [ ] **Step 4: Commit**

```bash
git add module_me/proguard-rules.pro module_me/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(module_me): 重写混淆规则并开启独立态 release R8

规则收敛为 TheRouter 官方全套 + ServiceProvider keep，删除 EventBus 死规则；
consumerProguardFiles 守卫与 versionCode 的 ApplicationDefaultConfig 先例同构，
双声明覆盖集成/独立两种身份。
EOF
)"
```

---

### Task 10: module_app —— 开启 release 混淆 + 重写规则

**Files:**
- Modify: `module_app/proguard-rules.pro`（整文件重写）
- Modify: `module_app/build.gradle.kts`（release minify true）

- [ ] **Step 1: 重写规则文件**

整文件替换 `module_app/proguard-rules.pro` 为：

```proguard
# module_app 混淆规则（R8 唯一集成态执行者，release 已开启 isMinifyEnabled）。
# 规则来源与证据：
# - TheRouter 全套：官方 README 要求 + 运行时 RouteMapKt 按类名字符串
#   Class.forName 的字节码证据；@Route 的 Activity 由 manifest aapt 规则兜底，
#   无需显式 keep；ServiceProvider 实现类不在 manifest、无 aapt 兜底，必须显式 keep。
# - 行号属性：release 崩溃栈配合 build/outputs/mapping/<variant>/mapping.txt 还原；
#   renamesourcefileattribute 隐藏原始文件名（行号保留 + 不泄漏源文件名）。
# 功能模块的反射面规则在各模块自己的 proguard-rules.pro（经 consumer 规则传播）。
# 禁止无证据的 -keep/-dontwarn。

# —— 崩溃栈可读性 ——
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# —— TheRouter（官方 README 全套）——
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * {*;}
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}
-keepclasseswithmembers class * {
    @com.therouter.router.Autowired <fields>;
}

# —— ServiceProvider 实现类 ——
-keep @com.therouter.inject.ServiceProvider class * {
    *;
}
```

- [ ] **Step 2: 开启 minify**

在 `module_app/build.gradle.kts` 中，将：

```kotlin
    buildTypes {
        release {
            isMinifyEnabled = false
```

替换为：

```kotlin
    buildTypes {
        release {
            // 集成态 R8 执行者；崩溃排查依赖 build/outputs/mapping/ 的 mapping.txt（见 ADR-0024）
            isMinifyEnabled = true
```

- [ ] **Step 3: 构建脚本语法验证**

Run: `./gradlew :module_app:tasks --quiet | head -5`
Expected: 正常输出任务列表（当前 isModule=true 下 module_app 是空壳，不执行 assembleRelease，集成验证在 Task 11）。

- [ ] **Step 4: Commit**

```bash
git add module_app/proguard-rules.pro module_app/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(module_app): 开启 release 混淆并重写混淆规则

删除 EventBus 死规则与注释模板；保留 TheRouter 官方规则并补
ServiceProvider keep；新增行号属性使 release 崩溃栈可配合 mapping.txt
还原。release isMinifyEnabled=true，R8 在集成态真实执行。
EOF
)"
```

---

### Task 11: 集成态验证环（R8 全量执行 + mapping 抽查）—— 无 commit

**Files:**
- Modify: `gradle.properties`（临时 isModule=true → false，结束时恢复原值）

- [ ] **Step 1: 记录现场**

Run: `git status --short gradle.properties && grep -E "^isModule" gradle.properties`
记录输出（预期 `isModule=true`，文件处于用户本地已修改态）。Task 结束必须恢复到这个值。

- [ ] **Step 2: 临时切集成态**

编辑 `gradle.properties`：`isModule=true` → `isModule=false`（只动这一行，注释不动）。

- [ ] **Step 3: 集成态 real release 构建（R8 全量执行）**

Run: `./gradlew :module_app:assembleRealRelease`（耗时较长，可 run_in_background）
Expected: BUILD SUCCESSFUL，日志含 `:module_app:minifyRealReleaseWithR8`，无 R8 "Missing class" 失败。同时实证"library 上 isMinifyEnabled=true 无操作"（各 feature 模块无 R8 任务、构建通过）。

- [ ] **Step 4: 集成态 mock release 构建**

Run: `./gradlew :module_app:assembleMockRelease`
Expected: BUILD SUCCESSFUL，含 `:module_app:minifyMockReleaseWithR8`。

- [ ] **Step 5: R8 警告审查**

Run: `./gradlew :module_app:assembleRealRelease --rerun-tasks 2>&1 | grep -iE "missing|warning|error" | head -30`（若 Step 3 输出已完整可跳过重跑，直接审查其日志）
处置原则：不消警告乱加 `-dontwarn`；每条 missing class 都核对来源，确属第三方可选依赖才加带注释的 `-dontwarn`，否则停下分析。

- [ ] **Step 6: mapping.txt 抽查（规则生效证据）**

Run:
```bash
grep -F "com.ebook.login.provider.LoginProvider ->" module_app/build/outputs/mapping/realRelease/mapping.txt
grep -F "com.ebook.me.provider.MeProvider ->" module_app/build/outputs/mapping/realRelease/mapping.txt
grep -F "com.ebook.book.provider.BookProvider ->" module_app/build/outputs/mapping/realRelease/mapping.txt
grep -F "com.ebook.find.provider.FindProvider ->" module_app/build/outputs/mapping/realRelease/mapping.txt
grep -F "com.ebook.main.MainActivity ->" module_app/build/outputs/mapping/realRelease/mapping.txt
```
Expected: 每条输出形如 `原类名 -> 原类名:`（名字未被混淆 = keep 生效）。任何一条为空或映射到新名，即为规则缺口，回 Task 5/10 修规则并重跑 Step 3。

- [ ] **Step 7: 恢复工作树**

编辑 `gradle.properties` 恢复为 Step 1 记录的值（预期 `isModule=true`）。
Run: `git diff gradle.properties | grep -E "^[+-]isModule"`
Expected: 只有 `-isModule=false` / `+isModule=true` 一对（即恢复到了用户本来的本地态），其他行无变化。

---

### Task 12: ADR-0024 + AGENTS.md 归属约定 —— 文档同步

**Files:**
- Create: `docs/adr/0024-proguard-rules-ownership.md`
- Modify: `AGENTS.md`（「构建约定」节追加一条 bullet）

- [ ] **Step 1: 写 ADR**

创建 `docs/adr/0024-proguard-rules-ownership.md`：

```markdown
# 混淆规则按「一份文件、双声明」归属模块，release 开启 R8

本 ADR 决定混淆体系的三件事：规则文件的组织归属、release 是否开启 R8、以及
唯一一处为混淆而做的代码重构。

## 背景

排查发现全仓混淆从未开启：9 份 proguard-rules.pro 全部处于
`isMinifyEnabled = false` 之下，是不生效的死文件。其中 8 个模块用 `proguardFiles`
声明规则——该机制只在模块自己作为 application 构建时生效；集成态下 R8 只在
module_app 执行一次，这些规则根本不参与构建。仅 lib_ebook_db 用对了
`consumerProguardFiles`，内容却是模板死规则：`**$Properties`（仓库无此类）、
SQLCipher 与 RxJava 的 `-dontwarn`（无依赖）、EventBus keep（依赖早已移除，
事件总线是 SharedFlow，见 ADR-0004）。

同时本项目功能模块是组件化的：集成态是 library、独立态（isModule=true）是
application（`xrn1997.android.component` 按属性切换插件并附带 therouter 插件）。
两种身份对规则机制的要求相反：library 需要 consumer 规则传播给消费方，
application 需要 proguardFiles 自持——这也是单态思维下最容易想当然的地方。

## 决策

### 1. 一份文件、双声明、双态生效

每模块一份 proguard-rules.pro，同时挂 `consumerProguardFiles`（集成态随 AAR
传播）与 buildTypes.release `proguardFiles`（独立态自持）。`isMinifyEnabled = true`
不按 isModule 分支——AGP 只对 application 执行 R8，library 上的该标志无操作，
构建脚本保持零分支。AGP 9 双态下 `defaultConfig` 的接收器按模式是具体类型
（module_me 的 versionCode 已有 `is ApplicationDefaultConfig` 先例），因此
feature 模块的 consumer 声明须经 `is LibraryDefaultConfig` 智能转换。

### 2. 规则按反射面归属模块

- **功能模块（5 个）**：TheRouter 官方 README 全套 + `@ServiceProvider` keep。
  依据：TheRouter 运行时 `RouteMapKt` 按类名字符串 `Class.forName`（字节码证据）；
  @Route 的 Activity 有 manifest aapt 规则兜底，@ServiceProvider 实现类不在
  manifest、无兜底。
- **module_app**：同上 TheRouter 块 + 行号属性（`SourceFile,LineNumberTable` +
  `renamesourcefileattribute`），release 崩溃栈配合 mapping.txt 还原。
- **三个 lib 模块**：当前零手写规则，文件只作反射面登记说明。依据（逐项核实）：
  Retrofit / Room3 / Hilt / Coil 自带 consumer 规则；`JsonUtils` 的反射式
  serializer 查找由 kotlinx-serialization-core 内置规则覆盖；lib_common 的
  `FileUtil` 反射的是平台类（R8 不重命名）；`EncodingInterceptor` 的反射被移除
  （见第 4 条）。TheRouter 与 lib_common 的 AAR 均不发布任何规则（proguard.txt
  为空/不存在），所需规则必须本项目手写。

### 3. release 开启 R8

module_app（real/mock 两 flavor）与功能模块独立态 release 的
`isMinifyEnabled = true`。独立态开启是刻意的：每个模块自己打 release 包时 R8
先行过一遍自己的规则，缺口在模块级暴露，不等到集成组包。debug 构建不开；
`shrinkResources` 不开（另行评估）。

### 4. EncodingInterceptor 去反射

原实现对 OkHttp 私有字段 `RealResponseBody.contentTypeString` 反射写入，强制
书源响应的 contentType。该字段名不在任何兼容性承诺内：OkHttp 升级改名、R8
重命名，任一发生都让 `getDeclaredField` 抛 `NoSuchFieldException` →
IOException → 全部书源请求失败。改为 OkHttp 公开 API 等价实现
（`source.asResponseBody` + `newBuilder`），行为不变、无需 keep 规则。

### 5. 规则判据

每条 keep 必须有证据（框架官方文档要求，或运行时反射的字节码证据）；禁止
无证据的 `-keep`/`-dontwarn`；新增反射面前先查依赖是否已自带规则。

## 权衡

- **TheRouter 规则在 6 份文件重复**：规则是全局类名模式，按模块自治分发只能
  复制；若集中到 module_app，独立态模块将失去自持规则。重复块由文件头注释
  锚定官方来源，升级 TheRouter 时按注释溯源。
- **行号属性只在 module_app**：`keepattributes` 全局合并、一处声明即可；独立态
  将来若开 R8 需自行补充。
- **混淆后崩溃排查依赖 mapping.txt**：build/ 不入库，发版时人工归档，本设计
  不引入归档脚本。
- **规则正确性无法静态证明**：keep 规则的失效模式是运行时（类名对不上、注解
  被剥），构建只能证明不缺类。装机验证清单（spec §5）覆盖 ServiceProvider、
  @Autowired、书源、下载、更新检查等全部反射链路，人工执行不可省略。

## 验证

- 独立态：5 个功能模块 `assembleRelease` 各跑一次 R8（构建含
  `minifyReleaseWithR8`，无 missing class）。
- 集成态：`assembleRealRelease` / `assembleMockRelease` 通过；同时实证 library
  上 minify 标志无操作。
- `mapping.txt` 抽查：四个 ServiceProvider 实现类与 `MainActivity` 的类名保留
  （`原类名 -> 原类名:`）。
- `EncodingInterceptorTest`（3 用例）锁 contentType 强制、正文透传、
  contentLength 保留。
- **待人工装机验证**（Agent 止于编译与静态检查）：三主 Tab（ServiceProvider
  渲染）、登录/注册、详情页 @Autowired 参数、阅读器翻页、离线下载前台服务、
  书城/搜索（重构后的 EncodingInterceptor，注意中文站点编码）、版本更新检查、
  mock flavor 启动。

## 交叉引用

- spec `docs/superpowers/specs/2026-09-06-proguard-rules-overhaul-design.md`
- ADR-0004（事件总线：EventBus 死规则的删除依据）、ADR-0018（前台服务离线
  下载，装机清单项）、ADR-0021（版本更新检查 @Named("release") 客户端，装机
  清单项）、ADR-0014（认证客户端共享，lib_common 无反射面的核实背景）。
```

- [ ] **Step 2: AGENTS.md 补归属约定**

在 `AGENTS.md` 的「构建约定」节，`- 不要引入新的编译警告，提交代码应保持警告清洁` 这一条**之前**插入：

```markdown
- **混淆规则归属（见 ADR-0024）**：每模块一份 `proguard-rules.pro`、双声明——`consumerProguardFiles`（集成态随 AAR 传播）+ buildTypes.release `proguardFiles`（独立态自持）；`isMinifyEnabled = true` 不按 isModule 分支（library 上无操作；AGP 9 双态下 feature 模块的 consumer 声明须经 `is LibraryDefaultConfig` 智能转换）。规则按反射面归属：功能模块持 TheRouter 官方规则 + ServiceProvider keep，module_app 持行号属性，三个 lib 模块当前零手写规则（Retrofit/Room3/Hilt/kotlinx-serialization 自带规则已覆盖）。每条 keep 必须有证据（官方文档或反射字节码），禁止无证据的 `-keep`/`-dontwarn`；新增反射面前先查依赖是否自带规则。release 崩溃排查依赖 `build/outputs/mapping/` 的 mapping.txt，发版人工归档
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0024-proguard-rules-ownership.md AGENTS.md
git commit -m "$(cat <<'EOF'
docs: 新增混淆规则体系 ADR-0024 并同步 AGENTS.md 归属约定

沉淀双态规则机制（一份文件双声明）、模块归属原则、自带规则优先判据与
EncodingInterceptor 去反射理由；AGENTS.md 构建约定补混淆归属条目。
EOF
)"
```

---

### Task 13: 收尾汇报（无代码改动、无 commit）

- [ ] **Step 1: 汇总验证证据**

整理并汇报：各任务构建输出（5 个独立态 R8 + 2 个集成态 R8）、R8 警告审查结论、mapping.txt 抽查结果、`git log --oneline` 的 8 个提交、`git status` 确认用户未提交改动原样未动（`gradle.properties` 仍是本地 `isModule=true` 态）。

- [ ] **Step 2: 交代人工装机验证清单（未验证项显式声明）**

按仓库分工，以下 Agent 未验证，提交合并前必须人工装机：
1. 三主 Tab 渲染（ServiceProvider 创建页面，混淆后类名反射链路）
2. 登录/注册/忘记密码链路
3. 书籍详情页 @Autowired 参数注入（from/data/data_key）
4. 阅读器翻页与目录
5. 离线下载前台服务（拉起、暂停、完成通知）
6. 书城/搜索/分类页（书源客户端走重构后的 EncodingInterceptor，重点看中文站点正文编码）
7. 版本更新检查（@Named("release") 客户端）
8. mock flavor 启动（applicationId `com.ebook.mock`）
安装包：`module_app/build/outputs/apk/real/release/` 与 `mock/release/` 下的 release APK。

---

## 计划自审记录

- **Spec 覆盖**：spec §3.1 双声明（Task 5-10）、§3.2 minify（Task 5-10）、§3.3 文件归属（Task 2-10）、§3.4 EncodingInterceptor（Task 1）、§3.5 构建脚本清单（Task 2-10）、§4 文档同步（Task 12）、§5 验证（Task 5-11 + Task 13 人工清单）、§6 风险边界（Task 11 Step 1/7 工作树安全）——全覆盖。
- **占位符扫描**：无 TBD/TODO；Task 6-8 引用 Task 5 的文本均标注"逐字一致/逐字复制"且 Task 5 内含完整文本（writing-plans 允许引用+完整源在案的形态）。
- **类型一致性**：`withForcedContentType` 在 Task 1 测试与实现间一致；`LibraryDefaultConfig` import 与守卫在 Task 5-9 一致；规则文件中 `com.therouter.inject.ServiceProvider` 与源码 import（provider/*.kt:6）一致。
