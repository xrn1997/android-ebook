# 脚本书源地基（Plan 1/3）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 `lib_book_source` 模块并完成双格式地基——原生解析器迁入、`format` 列迁移、脚本书源可导入可存储（求值如实报「待实现」）、导入链路格式判别与警示。

**Architecture:** 本文件是「脚本书源」特性三阶段计划中的**第一阶段**。特性整体规格见 `docs/adr/0028-untrusted-js-sandbox.md`（不可信 JS 沙箱执行器）与 `docs/adr/0029-script-book-source-import.md`（双格式共存与导入），领域术语见 `CONTEXT.md`（「脚本书源」「原生规则书源」）。总体路线图见下一节。本计划不改任何解析行为：原生书源全链路行为不变；脚本书源在 Plan 1 只走通「导入 → 原始 JSON 入库 → 管理页可见 → 解析请求返回类型化异常」。

**Tech Stack:** Kotlin、AGP 9 内置 Kotlin（约定插件 `xrn1997.android.library`）、Room（迁移链）、kotlinx-serialization、Jsoup。

**术语红线（全程有效）：** 全仓禁止出现该生态项目名字样——代码、注释、KDoc、测试、文档、提交信息一律用「脚本书源 / 脚本书源格式」。每个 Task 的收尾步骤包含一次红线 grep；检查模式写作 `"[l]egado"`（方括号只作用于匹配语义，不改变匹配结果），使命令文本自身也不含该字样——红线检查完成后，全仓以「该生态项目名」为模式的不区分大小写检索恒为零命中。**本文件里每条红线 grep 都要带 `--exclude-dir=build`**：构建产物里合并进来的 Material3 西班牙语资源串（表「已展开」的那个过去分词，词尾恰好含同一串字母）会命中这个模式，不排除就会假报 VIOLATION；判据以只扫跟踪文件的 `git grep -in "[l]egado"` 为准。

---

## 总体路线图（三份计划，交接读者从这里看全流程）

要解决的问题：脚本书源格式（社区通用 JSON）与本项目原生格式不兼容，而其规则内嵌可执行 JS——直接执行不可信代码有安全边界问题，翻译成原生格式则覆盖不了（实测语料 62% 含 JS）。三份计划串行落地「接受该格式」的完整能力：

### Plan 1 —— 地基与双格式入库（本文件）

- **做什么**：新建 `lib_book_source` 模块并迁入原生解析器；`book_source` 表加 `format` 列（v6 迁移）；导入链路按 JSON 键集自动判别格式，脚本书源**原始 JSON 整块入库零翻译**，导入预览给出三类警示（可执行代码/依赖登录/非文本源）与管理页出身标记；Manager 解析器接缝升级为密封 `SourceDefinition` 双后端分发，脚本书源的求值请求命中**类型化桩**（`ScriptSourcePendingParser`）——与「书源不存在」语义严格分离。
- **落地后的状态**：原生书源全链路零变化；脚本书源能导入、能管理、能禁用，但还不能解析（桩异常），不参与聚合搜索。
- **退出判据**：全仓 `test` + `assembleDebug` 通过 + 文末人工装机清单通过。
- **执行结果（2026-09-08 收尾）**：Task 1、3~10 全部**代码完成**（Task 2 作废、Task 5 并入 Task 4），
  全仓 `./gradlew test` 与 `:module_app:assembleDebug`（real + mock 两个 flavor）均已跑绿、无新增编译警告，
  红线 grep 零命中。**剩下的只有文末 5 条人工装机项**——按 AGENTS.md 的分工，Agent 止于「能编译」，
  安装运行与打开页面确认归人工，故本计划的退出判据此刻**尚未全部满足**。

### Plan 2 —— 脚本书源解释器（Plan 1 落地后另写计划文档）

> **拆段（2026-09-08）**：本段实际落成四份计划，因为规格 `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md` 的难点集中在「规则串被切成什么」而非「切出来怎么求值」，词法层可以零网络、零 Jsoup 地单独做完并锁形：**2a 词法与解析**（已落地 2026-09-08：`2026-09-08-script-source-rule-lexer.md`，Task 1-8 全绿、`:lib_book_source` 134 例；边界是**只到词法层**——它回答「这条规则串被切成什么」，脚本行求值仍命中 `ScriptSourcePendingParser`）、**2b 求值器**（已落地 2026-09-09：`2026-09-08-script-source-evaluator.md`，Task 1-7 全绿、`:lib_book_source` 215 例；边界是 **HTML 侧**——链式/`@css:`/正则 AllInOne 三后端 + 组合符 + 取值器/索引 + `##` 替换 + `@put:`/`@get:` 与 `{{}}` 插值的声明式子集；JSONPath 未落地、求值时按类型化异常挡下，归 2c；脚本行求值仍命中 `ScriptSourcePendingParser`）、**2c URL 语义与网络**（已落地 2026-09-09：`2026-09-09-script-source-url-jsonpath.md`，Task 1-9 全绿、`:lib_book_source` 290 例；边界是**取文前的语义层与传输接缝**——JSONPath 子集后端 + `,{...}` 选项尾段 + URL 解析（插值/`<>` 页码/落位/选项）+ 取文层（`ScriptRequest`/`ScriptTransport` 接缝/OkHttp 实现/`ScriptPageFetcher`）+ 发现页条目切分 `ExploreUrlFormat`；OkHttp 传输的真实行为（headers/重试/charset/协程取消）由 2d 金标准 fixtures 兜住；脚本行求值仍命中 `ScriptSourcePendingParser`，无任何用户可达路径变化）、**2d `BookParser` 实现 + 翻页链 + 聚合搜索接入 + 金标准 fixtures**（已落地 2026-09-09：`2026-09-09-script-source-book-parser.md`，Task 1-9 全绿 + Task 10 文档同步、`:lib_book_source` 330 例；边界是**装配与接线**——`ScriptBookParser` 实现 `BookParser` 五面与 `ScriptContentParser` 正文接缝、桩解析器与桩异常删除、聚合搜索候选含脚本行、`JsoupSourceReader` 按 parser 类型分岔；**书城默认源接线与 `:js` 沙箱不在本段**，前者归后续小计划、后者为 Plan 3；真实语料 fixtures 以仓内合成源顶位，债见规格 §11-25）。2c~2d 在各自前置落地后编写。

- **做什么**：在 `lib_book_source` 内**清洁室自研**该格式的规则求值器（不复制其 GPL 引擎代码，按格式语法文档与行为规格重写）：规则分词与组合（`&&`/`||`/`%%`）、三种规则模式（链式 HTML 选择器 / 正则 / JSONPath）、索引语法、变量系统（`@put`/`@get`/`{{}}`）、URL 语义（`URL,{...}` 选项、POST、`{{page}}`）、目录与正文翻页链、发现页；并让脚本书源接入聚合搜索。`@js:` 代码块在求值器里经**可注入的 JS 求值接缝**调度——Plan 3 落地前该接缝指向「暂不可用」实现，含 JS 的规则段继续类型化报错。
- **验收锚点**：从 642 条语料挑 5 条（2 无 JS + 1 中等 + 1 重度 JS + 1 沙箱密集型）入仓做金标准 fixtures；XPath 与 webJs 两种规则模式明确延后（语料影响面 3/642）。
- **落地后的状态**：脚本书源的声明式规则路径（纯声明式源全量可用，含 JS 源的声明式部分可用）；JS 规则段仍待 Plan 3。

### Plan 3 —— 沙箱执行器（Plan 2 落地后另写计划文档）

- **做什么**：为 `@js:` 代码块提供不可信 JS 的执行引擎（规格全文见 ADR-0028，此处列交付物）：QuickJS C 源码 vendor 进 `lib_book_source` 的 `cpp/`（自有 C++ 桥接层：runtime 管理、绑定注册、限值控制，不用第三方绑定库）；`:js` Service 以 `android:isolatedProcess="true"` 零权限运行；Binder 双向协议（`execute(script, input, timeout)` + 回调通道）；Host API 白名单按语料频谱定版（计算类 md5/base64/对称加解密等原生注册、网络类经主进程受限代理、递归规则求值走回调）；资源限制（超时/堆/栈）与崩溃自动重启；build-logic 新增 NDK/CMake 约定插件（release 仅 ARM64，debug 附 x86_64）。
- **验收锚点**：ADR-0028 的验收清单（恶意脚本测试集 + benchmark）。
- **落地后的状态**：脚本书源全链路（搜索/详情/目录/正文/发现/含 JS 规则段）可用；脚本永不以应用权限进入主进程。

### 依赖与顺序

**1 → 2 → 3 严格串行**：Plan 2 的求值器需要 Plan 1 的入库形态与 `SourceDefinition` 接缝；Plan 3 的回调协议需要 Plan 2 的求值接缝与 `@js:` 分发点。Plan 2/3 的计划文档在各自前置落地后编写，落在本目录，并在 ADR-0029 的「落地状态」段补记。

---

## File Structure（本计划产出/改动的文件全景）

```
lib_ebook_api/
  src/main/java/com/ebook/api/entity/SourceFormat.kt        [新建] SourceFormat 枚举 + 格式判别器 + SourceDefinition
  src/main/java/com/ebook/api/entity/ScriptSourceRule.kt    [新建] 脚本书源最小模型（解码/校验用）
lib_book_source/                                            [新建模块]
  build.gradle.kts / consumer-rules.pro / src/main/AndroidManifest.xml
  src/main/java/com/ebook/source/analyze/{BookParser, JsoupBookParser,
      BookSourceNotFoundException, AggregateSearchEvent}.kt  [自 lib_book_common 迁入，4 个文件]
  src/main/java/com/ebook/source/analyze/{ScriptSourcePendingParser, ScriptInterpreterPendingException}.kt [新建]
  src/test/java/com/ebook/source/analyze/{TocPagerTest, ListPageUrlTest, ChapterPageMatcherTest}.kt [迁入]
lib_book_common/
  analyze/source/JsoupSourceReader.kt                        [保留：正文读取器依赖 BookStore/
                                                             ChapterReader/BookSourceManager，
                                                             迁入会与 lib_book_source 成环，见 Task 4 修正]
  analyze/source/{BookSourceManager, BookSourceManagerImpl, BookSourceItem}.kt      [保留+改：接口扩员、双后端分发]
  analyze/source/{LibraryDiskCache, LibraryCacheData}.kt     [保留：缓存载体属仓库侧，解析器不碰缓存，
                                                             故不随解析器迁入 lib_book_source]
  event/Constant.kt                                          [保留：libraryCacheKey 唯一消费者是
                                                             LibraryDiskCache，同模块内不成环]
  src/test/.../{LibraryCacheKeyTest, LibraryDiskCacheTest}.kt [保留]
  src/test/.../FakeBookSourceManager.kt                      [改：同步新接口成员]
lib_ebook_db/
  entity/BookSourceEntity.kt                                 [改：+format 列]
  di/DatabaseModule.kt                                       [改：version 6 + MIGRATION_5_6]
  schemas/com.ebook.db.AppDatabase/6.json                    [构建生成，提交]
module_me/
  domain/BookSourceValidator.kt                              [改：+脚本书源校验与警示]
  mvvm/viewmodel/BookSourceViewModel.kt                      [改：ImportPreviewItem 双格式化]
  view/BookSourceManageActivity.kt                           [改：预览/清单 UI 警示与出身标记]
  src/test/.../FakeBookSourceManager.kt                      [改：同步新接口成员]
module_find/module_book 各测试内联假件                        [改：同步新接口成员]
AGENTS.md / docs/adr/0029-script-book-source-import.md       [改：模块图 + 落地状态]
docs/superpowers/plans/2026-09-08-script-book-source-foundation.md   [本文件]
```

---

### Task 1: `lib_ebook_api` 契约模型（SourceFormat / SourceDefinition / ScriptSourceRule）

**Files:**
- Create: `lib_ebook_api/src/main/java/com/ebook/api/entity/SourceFormat.kt`
- Create: `lib_ebook_api/src/main/java/com/ebook/api/entity/ScriptSourceRule.kt`
- Test: `lib_ebook_api/src/test/java/com/ebook/api/entity/SourceFormatDetectorTest.kt`
- Modify: `lib_ebook_api/build.gradle.kts`（若 `testImplementation(libs.junit)` 缺失则补）

- [x] **Step 1: 确认 lib_ebook_api 测试依赖**

Run: `grep -n "testImplementation" lib_ebook_api/build.gradle.kts`
Expected: 有 `testImplementation(libs.junit)`；若无，在 dependencies 块追加：

```kotlin
testImplementation(libs.junit)
```

- [x] **Step 2: 写失败的判别器测试**

创建 `lib_ebook_api/src/test/java/com/ebook/api/entity/SourceFormatDetectorTest.kt`：

```kotlin
package com.ebook.api.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFormatDetectorTest {

    @Test
    fun `含 bookSourceUrl 键的 JSON 判为脚本书源格式`() {
        val keys = setOf("bookSourceName", "bookSourceUrl", "searchUrl", "ruleSearch")
        assertEquals(SourceFormat.SCRIPT, SourceFormat.detect(keys))
    }

    @Test
    fun `原生格式键集判为 native`() {
        val keys = setOf("name", "url", "searchUrl", "ruleSearch", "ruleToc")
        assertEquals(SourceFormat.NATIVE, SourceFormat.detect(keys))
    }

    @Test
    fun `空键集判为 native——判别只认脚本书源的特征键`() {
        assertEquals(SourceFormat.NATIVE, SourceFormat.detect(emptySet()))
    }
}
```

- [x] **Step 3: 运行确认编译失败**

Run: `./gradlew :lib_ebook_api:compileDebugUnitTestKotlin`
Expected: FAIL——`SourceFormat` / `SourceFormat.detect` 未定义。

- [x] **Step 4: 实现 SourceFormat.kt**

创建 `lib_ebook_api/src/main/java/com/ebook/api/entity/SourceFormat.kt`：

```kotlin
package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 书源格式出身。两种格式在同一张 `book_source` 表中并存，按 [SourceFormat] 路由到各自的求值路径
 * （决策见 docs/adr/0029-script-book-source-import.md）。
 */
enum class SourceFormat {
    /** 原生规则书源：本仓自己的 [BookSourceRule] 声明式格式 */
    NATIVE,

    /** 脚本书源：社区通用 JSON 格式，规则内嵌可执行脚本，原始 JSON 入库不翻译 */
    SCRIPT;

    companion object {
        /** 实体列存储值（列以 TEXT 存，避免 TypeConverter 链） */
        fun fromRaw(raw: String): SourceFormat =
            if (raw == SCRIPT.name) SCRIPT else NATIVE
    }
}

/**
 * 脚本书源格式判别：只认脚本书源的特征键。
 *
 * 两种格式都有 `searchUrl`/`ruleSearch` 同名键，**不能**用它们判别；`bookSourceUrl`
 * 是脚本书源格式的独有顶层键（原生格式的对应键是 `url`）。判别失败一律回落 NATIVE：
 * 未知 JSON 按原生格式走既有校验，报错信息对用户更有指向性。
 */
object SourceFormatDetector {
    private const val SCRIPT_MARKER_KEY = "bookSourceUrl"

    fun detect(topLevelKeys: Set<String>): SourceFormat =
        if (SCRIPT_MARKER_KEY in topLevelKeys) SourceFormat.SCRIPT else SourceFormat.NATIVE
}

/**
 * 已落库书源的求值输入：Manager 按实体 `format` 列构造，交给解析器工厂分发。
 * 密封类型保证新增格式时编译器逼出所有分支。
 */
sealed interface SourceDefinition {
    /** 原生规则书源：已解码的 [BookSourceRule] */
    data class Native(val rule: BookSourceRule) : SourceDefinition

    /** 脚本书源：原始 JSON 文本，解释器按其自身语义求值（Plan 2 落地前的求值请求得到类型化异常） */
    data class Script(val rawJson: String) : SourceDefinition
}

/**
 * 脚本书源最小模型：仅覆盖导入校验与落库所需的顶层字段。
 * 解码一律开 `ignoreUnknownKeys`——未声明的键（规则段等）原样保留在原始 JSON 里，
 * Plan 2 的解释器直接消费原始 JSON，不经本模型。
 */
@Serializable
data class ScriptSourceRule(
    /** 书源名称（脚本书源格式键名 `bookSourceName`） */
    val bookSourceName: String = "",
    /** 书源 URL（主键语义，同原生格式的 `url`） */
    val bookSourceUrl: String = "",
    /** 书源分组 */
    val bookSourceGroup: String = "",
    /** 0=文本，1=图片(漫画)，2=音频；非 0 时导入警示（本项目为文字阅读器） */
    val bookSourceType: Int = 0,
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 登录入口（非空表示该源依赖登录流程，v1 不支持，导入警示） */
    val loginUrl: String = "",
)
```

- [x] **Step 5: 运行测试通过**

Run: `./gradlew :lib_ebook_api:testDebugUnitTest --tests "com.ebook.api.entity.SourceFormatDetectorTest"`
Expected: PASS（3 个用例）。

- [x] **Step 6: 提交**

```bash
git add lib_ebook_api/src/main/java/com/ebook/api/entity/SourceFormat.kt lib_ebook_api/src/main/java/com/ebook/api/entity/ScriptSourceRule.kt lib_ebook_api/src/test/java/com/ebook/api/entity/SourceFormatDetectorTest.kt lib_ebook_api/build.gradle.kts
git commit -m "feat(lib_ebook_api): 新增书源格式判别与脚本书源最小模型

- SourceFormat 枚举 + 键集判别器（只认 bookSourceUrl 特征键，未知回落 native）
- SourceDefinition 密封求值输入，供 Manager 双后端分发
- ScriptSourceRule 仅声明导入校验所需顶层字段，规则段留给解释器直读原始 JSON" -m "Refs docs/adr/0029-script-book-source-import.md"
```

---

### Task 2: ~~`libraryCacheKey` 下沉到 `lib_ebook_api`~~ —— 已作废（2026-09-08 同日缓存重构）

**作废原因**：本 Task 存在的唯一前提是「`JsoupBookParser` 引用 `libraryCacheKey`，解析器迁入
`lib_book_source` 后会与 `lib_book_common` 互相依赖」。同日稍晚的书库缓存重构把缓存整体从解析器
上收到仓库层（`BookParser.fetchLibraryData()` 零参、不碰缓存），前提随之消失：`libraryCacheKey`
剩下的唯一消费者是 `lib_book_common` 自己的 `LibraryDiskCache`，同模块内引用不成环，无须下沉。

**取代它的现状**（后续 Task 以此为前提，不要再按下沉后的路径写 import）：

- `LIBRARY_CACHE_KEY` / `libraryCacheKey`：留在 `lib_book_common/src/main/java/com/ebook/common/event/Constant.kt`；
- `LibraryDiskCache`（含落盘信封）与 `LibraryCacheData`：留在 `lib_book_common/.../analyze/source/`，
  **不随解析器迁入 `lib_book_source`**——缓存载体服务仓库层（`module_find` 的 `BookSourceRepository`），
  与解析器不同侧，跟着解析器走反而会让 `module_find` 多依赖一个解析库；
- `LibraryCacheKeyTest`（2 例）与 `LibraryDiskCacheTest`（8 例）：留在 `lib_book_common` 测试目录。

Task 4 的迁移清单以「File Structure」一节为准。Task 编号保持连续、不重排，
后文出现「Task 2」一律指本节的作废说明。

---

### Task 3: 创建 `lib_book_source` 模块骨架

**Files:**
- Modify: `settings.gradle.kts`
- Create: `lib_book_source/build.gradle.kts`
- Create: `lib_book_source/consumer-rules.pro`
- Create: `lib_book_source/src/main/AndroidManifest.xml`

- [x] **Step 1: 注册模块**

`settings.gradle.kts` 的 include 块（现 56-64 行）追加一行，保持字母序放在 `:lib_book_common` 之后：

```kotlin
include(":lib_book_source")
```

- [x] **Step 2: 创建 build.gradle.kts**

创建 `lib_book_source/build.gradle.kts`：

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.library)
}

android {
    namespace = "com.ebook.source"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    // 解析器消费 api 层契约（BookSourceRule/实体模型）与 db 实体（BookParser 签名）
    api(project(":lib_ebook_api"))
    api(project(":lib_ebook_db"))
    api(libs.jsoup)
    api(libs.juniversalchardet)
    implementation(libs.common)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

注意：`implementation(libs.okhttp)` 若版本目录无 `okhttp` 别名，改用 `implementation(libs.okhttp3)` 或查 `gradle/libs.versions.toml` 中 OkHttp 的实际别名（`grep -n "okhttp" gradle/libs.versions.toml`）；BookSourceNetwork（api 层）已传递 OkHttp，此行为显式声明 JSOUP 解析器的构建期可见性，别名以 toml 为准。

- [x] **Step 3: 创建 consumer-rules.pro 与 Manifest**

`lib_book_source/consumer-rules.pro`：

```pro
# lib_book_source：书源解析与脚本书源解释器。
# 反射面规则按需登记（ADR-0024：无证据不写 keep）；当前迁入的解析器一族无新增反射面。
```

`lib_book_source/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [x] **Step 4: 构建验证**

Run: `./gradlew :lib_book_source:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [x] **Step 5: 红线 grep + 提交**

```bash
grep -ri --exclude-dir=build "[l]egado" lib_book_source settings.gradle.kts && echo "VIOLATION" || echo "CLEAN"
git add settings.gradle.kts lib_book_source
git commit -m "feat(lib_book_source): 新增书源解析模块骨架

挂接约定插件与 api/db/jsoup 依赖，承接原生解析器迁入与脚本书源解释器
（Plan 1），后续沙箱执行器与 cpp/ 亦归此模块（ADR-0028/0029）。" -m "Refs docs/adr/0028-untrusted-js-sandbox.md, docs/adr/0029-script-book-source-import.md"
```

---

### Task 4: 迁移原生解析器一族到 `lib_book_source`

**Files（git mv，保历史）:**
- `lib_book_common/src/main/java/com/ebook/common/analyze/source/{BookParser, JsoupBookParser, JsoupSourceReader, BookSourceNotFoundException, AggregateSearchEvent}.kt`
  → `lib_book_source/src/main/java/com/ebook/source/analyze/`
- 同目录**留下** `{LibraryDiskCache, LibraryCacheData}.kt`（缓存载体归仓库侧，解析器已不碰缓存，
  见 Task 2 作废说明）——它们与迁移后的解析器同包但不同模块，`package` 行不变
- 全仓 import 更新（下方清单，`com.ebook.common.analyze.source` → `com.ebook.source.analyze`）

**行为不变承诺**：本 Task 只动 package 与 import，不改任何逻辑；迁移后原生书源全链路行为与迁移前逐字节等价。

- [x] **Step 1: git mv 五个主代码文件**

```bash
mkdir -p lib_book_source/src/main/java/com/ebook/source/analyze
for f in BookParser JsoupBookParser JsoupSourceReader BookSourceNotFoundException AggregateSearchEvent; do
  git mv "lib_book_common/src/main/java/com/ebook/common/analyze/source/$f.kt" "lib_book_source/src/main/java/com/ebook/source/analyze/$f.kt"
done
```

- [x] **Step 2: 改写迁移文件的 package 行**

```bash
sed -i 's/^package com\.ebook\.common\.analyze\.source$/package com.ebook.source.analyze/' lib_book_source/src/main/java/com/ebook/source/analyze/*.kt
```

- [x] **Step 3: 全仓更新引用点**

```bash
grep -rl "com\.ebook\.common\.analyze\.source" --include="*.kt" lib_book_common module_book module_find module_me | xargs sed -i 's/com\.ebook\.common\.analyze\.source/com.ebook.source.analyze/g'
```

涉及文件（sed 前先 `grep -rl` 复核，应恰好命中以下 32 个）：
lib_book_common main：`BookRepository.kt`、`ErrorAnalyzeContentManager.kt`、`BookShelfManager.kt`、`ContentStoreModule.kt`、`AnalyzeModule.kt`；lib_book_common test：`LocalContentReadTest`、`BookRepositoryTest`、`BookRepositorySwitchSourceTest`、`BookShelfManagerTest`、`LocalImportCoordinatorTest`、`LocalBookImporterTest`、`BookSourceManagerImplTest`、`FakeBookSourceManager`；module_book main：`DownloadService`、`SourceSwitchFeedback`、`SourceSwitchViewModel`、`BookDetailViewModel`、`ReadBookActivity`；module_book test：`SourceSwitchFeedbackTest`、`SourceSwitchViewModelTest`、`BookDetailViewModelSourceTest`；module_find main：`BookSourceRepository`、`SearchViewModel`、`LibraryViewModel`、`ChoiceBookViewModel`；module_find test：`LibraryViewModelTest`、`BookSourceRepositoryNoSourceTest`、`SearchViewModelTest`；module_me main：`BookSourceManageActivity`、`SettingViewModel`、`BookSourceViewModel`；module_me test：`FakeBookSourceManager`。

Run: `grep -rn "com.ebook.common.analyze.source" --include="*.kt" .`
Expected: 仅剩 `lib_book_source` 内部 0 处 + `lib_book_common/.../analyze/source/` 目录下保留的 5 个 STAY 文件（它们自己的 package 行不变：`package com.ebook.common.analyze.source` 依然合法——目录还在）。注意：STAY 文件（BookSourceManager/BookSourceManagerImpl/BookSourceItem + LibraryDiskCache/LibraryCacheData）对迁移类的 import 行已被 Step 3 改为新包——这正是期望结果。

- [x] **Step 4: consumer-rules 反射面检查**

Run: `grep -n "JsoupBookParser\|BookParser\|JsoupSourceReader\|AggregateSearchEvent" lib_book_common/consumer-rules.pro`
Expected: 若命中任何 `-keep` 行，整段剪切到 `lib_book_source/consumer-rules.pro`（归属跟随类走）；若零命中，Step 2 写下的注释即最终状态。

- [x] **Step 5: 编译 + 相关模块单测**

Run: `./gradlew :lib_book_source:assembleDebug :lib_book_common:testDebugUnitTest :module_book:testDebugUnitTest :module_find:testDebugUnitTest :module_me:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部 PASS。任何 unresolved reference 都说明 Step 3 的清单有遗漏，按同样方式补 sed。

- [x] **Step 6: 红线 grep + 提交**

```bash
grep -ri --exclude-dir=build "[l]egado" lib_book_source lib_book_common module_book module_find module_me && echo "VIOLATION" || echo "CLEAN"
git add -A
git commit -m "refactor(all): 原生书源解析器一族迁入 lib_book_source

- JsoupBookParser/BookParser/JsoupSourceReader/AggregateSearchEvent/BookSourceNotFoundException 五文件 git mv 保历史
- 全仓 import 改写，Manager 与解析器经 lib_book_source 依赖，行为零变化
- 为脚本书源解释器与沙箱执行器腾出模块载体（ADR-0029 决策 5）" -m "Refs docs/adr/0029-script-book-source-import.md"
```

---

### Task 5: ~~迁移解析器单元测试~~ —— 已并入 Task 4（2026-09-08 执行期）

**并入原因**：`ListPageUrl`/`TocPageUrl`/`TocPager` 是 `JsoupBookParser` 的 `internal` 对象，Kotlin
的 `internal` 只在同模块（含同模块 test source set）可见，依赖方的测试源集不是 friend module——
测试留在 `lib_book_common/src/test` 直接编不过（Task 4 首次编译即报
`Unresolved reference 'TocPager'` 等 38 处）。三者的 `@Test` 计数（13/8/17=38）迁前迁后一致，
`lib_book_source` 的 38 个用例即其全部。`JsoupSourceReaderTest` 按 Task 4 修正留在
`lib_book_common`（它要 `FakeBookSourceManager`/`RecordingBookParser`/`BookStore`，均在本模块测试侧）。

<sub>以下为原计划内容，保留供追溯：</sub>

**这里的勾选记的是「这件事有没有了结」，不是「命令照抄跑过一遍」**：Step 1~3 只对
`TocPagerTest` / `ListPageUrlTest` / `ChapterPageMatcherTest` 三个纯逻辑测试类成立
（`JsoupSourceReaderTest` 按上文留在 `lib_book_common`），Step 4 那笔独立的
`test(lib_book_source)` 提交也不存在——迁移随 Task 4 的 `refactor(all)` 一并落地。

**Files:**
- Move: `lib_book_common/src/test/java/com/ebook/common/analyze/source/{TocPagerTest, ListPageUrlTest, ChapterPageMatcherTest, JsoupSourceReaderTest}.kt` → `lib_book_source/src/test/java/com/ebook/source/analyze/`

- [x] **Step 1: git mv 四个测试文件**

```bash
mkdir -p lib_book_source/src/test/java/com/ebook/source/analyze
for f in TocPagerTest ListPageUrlTest ChapterPageMatcherTest JsoupSourceReaderTest; do
  git mv "lib_book_common/src/test/java/com/ebook/common/analyze/source/$f.kt" "lib_book_source/src/test/java/com/ebook/source/analyze/$f.kt"
done
```

- [x] **Step 2: 改写 package 行**

```bash
sed -i 's/^package com\.ebook\.common\.analyze\.source$/package com.ebook.source.analyze/' lib_book_source/src/test/java/com/ebook/source/analyze/*.kt
```

- [x] **Step 3: 运行测试**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS——四个测试类全部通过（internal 对象 `ListPageUrl`/`TocPager`/`ChapterPageMatcher` 与测试同模块，可见性不变）。

- [x] **Step 4: 红线 grep + 提交**

```bash
grep -ri --exclude-dir=build "[l]egado" lib_book_source && echo "VIOLATION" || echo "CLEAN"
git add -A
git commit -m "test(lib_book_source): 解析器单测随实现迁入

TocPager/ListPageUrl/ChapterPageMatcher/JsoupSourceReader 四测试类随实现同迁，
包名对齐，测试逻辑零变化。" 
```

---

### Task 6: `format` 列与迁移链（version 5 → 6）

**Files:**
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/entity/BookSourceEntity.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/di/DatabaseModule.kt`（version + MIGRATION_5_6 + addMigrations）
- Commit: `lib_ebook_db/schemas/com.ebook.db.AppDatabase/6.json`（构建生成）

- [x] **Step 1: 实体加列**

`BookSourceEntity.kt` 的 `isUserImported` 字段后追加：

```kotlin
    /**
     * 书源格式出身（决策见 ADR-0029）：
     * `native` = 原生规则书源（rule_json 是本仓 `BookSourceRule` 的序列化）；
     * `script` = 脚本书源（rule_json 是社区通用格式的原始 JSON，整块存储不翻译）。
     * 列值与 `lib_ebook_api` 的 `SourceFormat.raw` 同字（小写，本模块依赖不到该类型故写成纯文本），
     * 由其 `SourceFormat.fromRaw` 还原；未知值按 native 处理。
     */
    @ColumnInfo(name = "format", defaultValue = "native")
    val format: String = "native",
```

- [x] **Step 2: 版本与迁移**

`AppDatabase.kt` L47 `version = 5` 改 `version = 6`。
`DatabaseModule.kt` 在 `MIGRATION_4_5` 之后追加，并把 `MIGRATION_5_6` 加进 L136 的 `addMigrations(...)` 列表：

```kotlin
    /** v5→v6：book_source 增加 format 列（书源格式出身，存量行全部为 native，见 ADR-0029 决策 1） */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE book_source ADD COLUMN format TEXT NOT NULL DEFAULT 'native'")
        }
    }
```

- [x] **Step 3: 构建生成 schema 并核对**

Run: `./gradlew :lib_ebook_db:assembleDebug`
Expected: 生成 `lib_ebook_db/schemas/com.ebook.db.AppDatabase/6.json`。打开核对 `book_source` 表含 `"format": {"defaultValue": "native", "notNull": true, "type": "TEXT"}`，且 entity hash 已随版本更新。

- [x] **Step 4: 运行 db 模块测试**

Run: `./gradlew :lib_ebook_db:testDebugUnitTest`
Expected: PASS。

- [x] **Step 5: 红线 grep + 提交**

```bash
grep -ri --exclude-dir=build "[l]egado" lib_ebook_db && echo "VIOLATION" || echo "CLEAN"
git add lib_ebook_db
git commit -m "feat(lib_ebook_db): book_source 增加 format 列并升级迁移链到 v6

- 实体列 format TEXT NOT NULL DEFAULT 'native'，存量行零感知
- MIGRATION_5_6 紧邻追加不跳版，提交 Room 生成的新 schema JSON（ADR-0003 约定）" -m "Refs docs/adr/0029-script-book-source-import.md"
```

---

### Task 7: 解析器接缝双后端化 + 脚本书源桩解析器

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptInterpreterPendingException.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptSourcePendingParser.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt`（parserFactory 签名 + getParserFor 分支）
- Test: `lib_book_common/src/test/java/com/ebook/common/analyze/source/BookSourceManagerImplTest.kt`

- [x] **Step 1: 读实现现场**

Read `BookSourceManagerImpl.kt` 的 110-135 行（双构造器）、195-230 行（LRU 缓存）、455-490 行（getParserFor）。后续步骤里的成员名（缓存字段、awaitSeeding 等）以实际代码为准，逻辑结构保持一致。

- [x] **Step 2: 写失败的 dispatch 测试**

在 `BookSourceManagerImplTest` 追加（沿用该类既有的 manager/dao fixture 搭法）：

```kotlin
    @Test
    fun `getParserFor 对 script 行返回桩解析器并记日志`() = runTest {
        // 准备：直接经 dao 插入一条脚本书源行（绕过 native 校验路径）
        dao.upsert(
            BookSourceEntity(
                url = "https://script.example.com",
                name = "脚本测试源",
                ruleJson = """{"bookSourceName":"脚本测试源","bookSourceUrl":"https://script.example.com"}""",
                format = "script",
            )
        )
        val parser = manager.getParserFor("https://script.example.com")
        assertTrue(parser is ScriptSourcePendingParser)
    }

    @Test
    fun `脚本书源不进入原生规则读面`() = runTest {
        dao.upsert(
            BookSourceEntity(
                url = "https://script.example.com",
                name = "脚本测试源",
                ruleJson = """{"bookSourceName":"脚本测试源","bookSourceUrl":"https://script.example.com"}""",
                format = "script",
            )
        )
        assertTrue(manager.getAllSources().isEmpty())
        assertTrue(manager.getEnabledSources().isEmpty())
        assertEquals(null, manager.getSourceByUrl("https://script.example.com"))
    }
```

（import 按需补：`com.ebook.db.entity.BookSourceEntity`、`com.ebook.source.analyze.ScriptSourcePendingParser`。若测试类无 `dao` 直用字段，仿类内既有用例的建库方式拿到 DAO。）

- [x] **Step 3: 运行确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.source.BookSourceManagerImplTest"`
Expected: 编译失败——`ScriptSourcePendingParser` 未定义；或断言失败。

- [x] **Step 4: 实现桩解析器与异常**

创建 `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptInterpreterPendingException.kt`：

```kotlin
package com.ebook.source.analyze

/**
 * 脚本书源解释器尚未落地（Plan 2）时，对该源的求值请求抛出的类型化异常。
 * 消息直接面向用户文案，不得出现外部生态项目名。
 */
class ScriptInterpreterPendingException(message: String) : RuntimeException(message)
```

创建 `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptSourcePendingParser.kt`：

```kotlin
package com.ebook.source.analyze

import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity

/**
 * 脚本书源求值桩（Plan 1 占位，Plan 2 由真实解释器替换）。
 *
 * 存在的理由：脚本书源在 Plan 1 即可导入入库，但求值引擎尚未落地——「源在库里、解析必失败」
 * 必须以**类型化异常**表达，而不是返回 null（null 在 [BookParser] 消费链上的语义是「书源不存在」，
 * 两者混用会让管理页与解析链路无法区分「待实现」与「已失效」）。
 * 每个方法的异常消息声明该源格式受支持但解释器未就绪，调用方按普通解析失败上报。
 */
internal object ScriptSourcePendingParser : BookParser {

    private val message = "脚本书源解释器尚未实现，该源的解析暂不可用（导入了原始规则，待引擎升级后自动生效）"

    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
        throw ScriptInterpreterPendingException(message)

    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
        throw ScriptInterpreterPendingException(message)

    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
        throw ScriptInterpreterPendingException(message)

    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
        throw ScriptInterpreterPendingException(message)

    override suspend fun fetchLibraryData(): LibraryEntity =
        throw ScriptInterpreterPendingException(message)
}
```

- [x] **Step 5: 接缝改造 BookSourceManagerImpl**

1. 构造器 `parserFactory` 签名改为：

```kotlin
        parserFactory: (SourceDefinition) -> BookParser = { definition ->
            when (definition) {
                is SourceDefinition.Native -> JsoupBookParser(definition.rule, okHttpClient)
                is SourceDefinition.Script -> ScriptSourcePendingParser
            }
        }
```

2. `getParserFor` 内「解析 ruleJson」的语句改为按 format 分支（保留既有 LRU/await 结构，成员名以 Step 1 读到的为准）：

```kotlin
            val parser = when (SourceFormat.fromRaw(entity.format)) {
                SourceFormat.SCRIPT -> {
                    Logger.w(TAG, "script 源 $url 求值请求命中待实现桩")
                    ScriptSourcePendingParser
                }
                SourceFormat.NATIVE -> {
                    val rule = storageJson.decodeFromString<BookSourceRule>(entity.ruleJson)
                    parserFactory(SourceDefinition.Native(rule))
                }
            }
```

3. 规则读面过滤（`getAllSources` / `getEnabledSources` / `getSourceByUrl` 的 entity→rule 映射处）：`format != native` 的行**跳过且记 INFO 日志**（不是 ERROR——那不是脏数据，是脚本书源对规则类型化读面天然不可见；脚本书源参与聚合搜索等链路在 Plan 2 接入）：

```kotlin
            if (SourceFormat.fromRaw(entity.format) != SourceFormat.NATIVE) {
                Logger.i(TAG, "skip script source in rule-typed read: ${entity.url}")
                return@mapNotNull null   // 或等价的跳过语法，以现有 map 结构为准
            }
```

同步快照 `currentSource`（assets 冷启动）全部为 native，无需改动，但加一行 KDoc 说明该不变式。

4. import 补齐：`com.ebook.api.entity.SourceFormat`、`com.ebook.api.entity.SourceDefinition`、`com.ebook.source.analyze.ScriptSourcePendingParser`。

- [x] **Step 6: 运行测试通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest`
Expected: 全部 PASS（新 2 用例 + 既有用例零回归）。

- [x] **Step 7: 红线 grep + 提交**

```bash
grep -ri --exclude-dir=build "[l]egado" lib_book_source lib_book_common && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source lib_book_common
git commit -m "feat(lib_book_common): 解析器接缝双后端化，脚本书源走类型化桩

- parserFactory 收 SourceDefinition（Native/Script 密封分发）
- script 行求值命中 ScriptSourcePendingParser，类型化异常与「书源不存在」语义分离
- 规则类型化读面跳过 script 行（INFO 记录，聚合搜索接入在 Plan 2）" -m "Refs docs/adr/0029-script-book-source-import.md"
```

---

### Task 8: Manager 扩员——`addScriptSource` / `getFormatByUrl` / `BookSourceItem.format`

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt`（接口 +2 成员）
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceItem.kt`（+format）
- Modify: `lib_book_common/src/test/java/com/ebook/common/analyze/source/FakeBookSourceManager.kt`
- Modify: `module_me/src/test/java/com/ebook/me/mvvm/viewmodel/FakeBookSourceManager.kt`
- Modify: `module_find/src/test/java/com/ebook/find/{mvvm/viewmodel/LibraryViewModelTest.kt, repository/BookSourceRepositoryNoSourceTest.kt, mvvm/viewmodel/SearchViewModelTest.kt}` 内联假件
- Modify: `module_book/src/test/java/com/ebook/book/{reader/SourceSwitchFeedbackTest.kt, mvvm/viewmodel/SourceSwitchViewModelTest.kt, mvvm/viewmodel/BookDetailViewModelSourceTest.kt}` 内联假件
- Test: `lib_book_common/src/test/java/com/ebook/common/analyze/source/BookSourceManagerImplTest.kt`

- [x] **Step 1: 写失败的接口测试**

`BookSourceManagerImplTest` 追加：

```kotlin
    @Test
    fun `addScriptSource 原样入库且 getFormatByUrl 返回 script`() = runTest {
        val raw = """{"bookSourceName":"脚本源A","bookSourceUrl":"https://a.example.com"}"""
        manager.addScriptSource(raw).getOrThrow()
        assertEquals(SourceFormat.SCRIPT, manager.getFormatByUrl("https://a.example.com"))
    }

    @Test
    fun `addScriptSource 重导同 URL 覆盖原始 JSON`() = runTest {
        val url = "https://a.example.com"
        manager.addScriptSource("""{"bookSourceName":"脚本源A","bookSourceUrl":"$url"}""").getOrThrow()
        manager.addScriptSource("""{"bookSourceName":"脚本源A改","bookSourceUrl":"$url"}""").getOrThrow()
        assertEquals(SourceFormat.SCRIPT, manager.getFormatByUrl(url))
    }

    @Test
    fun `getFormatByUrl 对不存在的源返回 null`() = runTest {
        assertEquals(null, manager.getFormatByUrl("https://no.where"))
    }
```

- [x] **Step 2: 运行确认编译失败**

Run: `./gradlew :lib_book_common:compileDebugUnitTestKotlin`
Expected: FAIL——接口无 `addScriptSource`/`getFormatByUrl`。

- [x] **Step 3: 接口扩员（带契约 KDoc）**

`BookSourceManager.kt` 在 `addSource` 之后追加：

```kotlin
    /**
     * 新增或覆盖脚本书源（主键 `url` 命中即 REPLACE），[rawJson] 原样落 `rule_json`，`format` 落 `script`。
     *
     * 与 [addSource] 同一套不变式：先等首启 seeding、成功后失效 parser 缓存、无默认源时提升为默认源。
     * **不做格式内容校验**——校验与警示归导入 UI（是否含可执行代码、依赖登录等），本方法只负责存储。
     * [rawJson] 必须含 `bookSourceUrl` 键（判别约定见 [com.ebook.api.entity.SourceFormatDetector]）；
     * 名称与 URL 从 JSON 顶层读取，缺任一即 [Result.failure]。
     */
    suspend fun addScriptSource(rawJson: String): Result<Unit>

    /**
     * 按已入库书源的 URL 查格式出身；不存在返回 null。
     * 导入预览的「将覆盖」判定与格式显示经此查询，**不要**用 [getSourceByUrl]（它只对 native 行有值）。
     */
    suspend fun getFormatByUrl(url: String): SourceFormat?
```

`BookSourceItem` 增加 `val format: SourceFormat` 字段（构造参数带默认 `SourceFormat.NATIVE`，减少既有测试构造点改动）。

- [x] **Step 4: 实现三个成员**

`BookSourceManagerImpl`（成员名以 Task 7 Step 1 读到的现场为准，`addScriptSource` 与 `addSource` 同构）：

```kotlin
    override suspend fun addScriptSource(rawJson: String): Result<Unit> = runCatching {
        awaitSeeding()                       // 与 addSource 相同的 seeding 等待入口
        val parsed = storageJson.decodeFromString<ScriptSourceRule>(rawJson)
        require(parsed.bookSourceUrl.isNotBlank()) { "书源 URL 为空" }
        require(parsed.bookSourceName.isNotBlank()) { "书源名称为空" }
        val existing = dao.getByUrl(parsed.bookSourceUrl)
        dao.upsert(
            existing?.copy(
                name = parsed.bookSourceName,
                ruleJson = rawJson,
                format = SourceFormat.SCRIPT.raw,
                enabled = parsed.enabled,
                group = parsed.bookSourceGroup.ifBlank { existing.group },
            ) ?: BookSourceEntity(
                url = parsed.bookSourceUrl,
                name = parsed.bookSourceName,
                ruleJson = rawJson,
                format = SourceFormat.SCRIPT.raw,
                enabled = parsed.enabled,
                group = parsed.bookSourceGroup.ifBlank { "社区" },
            )
        )
        evictParser(parsed.bookSourceUrl)
        promoteToDefaultIfAbsent(parsed.bookSourceUrl)   // 与 addSource 相同的默认源提升逻辑（成员名以现场为准）
    }

    override suspend fun getFormatByUrl(url: String): SourceFormat? {
        awaitSeeding()
        return dao.getByUrl(url)?.let { SourceFormat.fromRaw(it.format) }
    }
```

（`addSource` 内部的实体构造补 `format = SourceFormat.NATIVE.raw`——显式写，不依赖默认值，防未来默认值漂移。列值一律取 `SourceFormat.raw`（小写 `native`/`script`），**不要用 `SourceFormat.name`**：`name` 是大写枚举名，与 `MIGRATION_5_6` 的 `DEFAULT 'native'` 不同字，写进去会被 `fromRaw` 静默判成 native。）

`toItem`（实体→BookSourceItem 映射）带上 `format = SourceFormat.fromRaw(entity.format)`。

- [x] **Step 5: 同步全部测试替身（编译强制）**

两份 `FakeBookSourceManager` 与 6 处内联假件统一追加：

```kotlin
    private val scriptSources = mutableListOf<Pair<String, String>>() // url to rawJson

    override suspend fun addScriptSource(rawJson: String): Result<Unit> {
        val url = Regex("\"bookSourceUrl\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1) ?: ""
        if (url.isBlank()) return Result.failure(IllegalArgumentException("书源 URL 为空"))
        scriptSources.removeAll { it.first == url }
        scriptSources.add(url to rawJson)
        return Result.success(Unit)
    }

    override suspend fun getFormatByUrl(url: String): SourceFormat? = when {
        sources.any { it.url == url } -> SourceFormat.NATIVE
        scriptSources.any { it.first == url } -> SourceFormat.SCRIPT
        else -> null
    }
```

（假件内部字段名（`sources` 等）以各文件现状为准，逻辑保持：native 清单命中→NATIVE，脚本草稿命中→SCRIPT。`BookSourceItem` 构造点若无显式实参则吃默认值。）

- [x] **Step 6: 运行全量相关测试**

Run: `./gradlew :lib_book_common:testDebugUnitTest :module_me:testDebugUnitTest :module_find:testDebugUnitTest :module_book:testDebugUnitTest`
Expected: PASS，零编译错。

- [x] **Step 7: 红线 grep + 提交**

```bash
grep -ri --exclude-dir=build "[l]egado" lib_book_common lib_book_source module_me module_find module_book && echo "VIOLATION" || echo "CLEAN"
git add -A
git commit -m "feat(lib_book_common): Manager 扩员支持脚本书源入库与格式查询

- addScriptSource 原样入库（与 addSource 同构：seeding 等待/缓存失效/默认源提升）
- getFormatByUrl 供覆盖判定与出身显示，规则读面继续只对 native 行
- BookSourceItem 透出 format；两份 Fake 与六处内联假件同步" -m "Refs docs/adr/0029-script-book-source-import.md"
```

---

### Task 9: 导入链路——格式判别、脚本书源校验警示与 UI

**Files:**
- Modify: `module_me/src/main/java/com/ebook/me/domain/BookSourceValidator.kt`（+脚本书源校验）
- Modify: `module_me/src/main/java/com/ebook/me/mvvm/viewmodel/BookSourceViewModel.kt`（ImportPreviewItem 双格式化 + parseImportJson 分流 + confirm 分支）
- Modify: `module_me/src/main/java/com/ebook/me/view/BookSourceManageActivity.kt`（预览页警示 + 清单出身标记 + 脚本行隐藏导出）
- Test: `module_me/src/test/java/com/ebook/me/domain/BookSourceValidatorTest.kt`（无则新建）

- [x] **Step 1: 写失败的校验测试**

创建/追加 `BookSourceValidatorTest`：

```kotlin
    @Test
    fun `脚本书源缺名称或URL判无效`() {
        val result = BookSourceValidator.validateScript(
            ScriptSourceRule(bookSourceName = "", bookSourceUrl = "https://a.com"),
        )
        assertTrue(result.reasons.contains(ValidationReason.NAME_BLANK))
    }

    @Test
    fun `脚本书源URL非http判无效`() {
        val result = BookSourceValidator.validateScript(
            ScriptSourceRule(bookSourceName = "A", bookSourceUrl = "ftp://a.com"),
        )
        assertTrue(result.reasons.contains(ValidationReason.URL_NOT_HTTP))
    }

    @Test
    fun `含js代码的脚本书源给可执行代码警示`() {
        val result = BookSourceValidator.validateScript(
            ScriptSourceRule(bookSourceName = "A", bookSourceUrl = "https://a.com"),
            rawJson = """{"bookSourceName":"A","bookSourceUrl":"https://a.com","ruleContent":{"content":"<js>1</js>"}}""",
        )
        assertTrue(result.warnings.contains(ScriptWarning.HAS_EXECUTABLE_CODE))
        assertTrue(result.isValid)
    }

    @Test
    fun `依赖登录的脚本书源给登录警示`() {
        val result = BookSourceValidator.validateScript(
            ScriptSourceRule(bookSourceName = "A", bookSourceUrl = "https://a.com", loginUrl = "https://a.com/login"),
        )
        assertTrue(result.warnings.contains(ScriptWarning.NEEDS_LOGIN))
    }

    @Test
    fun `漫画或音频源给非文本警示`() {
        val result = BookSourceValidator.validateScript(
            ScriptSourceRule(bookSourceName = "A", bookSourceUrl = "https://a.com", bookSourceType = 1),
        )
        assertTrue(result.warnings.contains(ScriptWarning.NOT_TEXT_SOURCE))
    }
```

- [x] **Step 2: 实现脚本书源校验**

`BookSourceValidator.kt` 追加：

```kotlin
/** 脚本书源导入警示（不是错误——警示不阻止导入，只要求用户知情，见 ADR-0029 决策 6） */
enum class ScriptWarning { HAS_EXECUTABLE_CODE, NEEDS_LOGIN, NOT_TEXT_SOURCE }

data class ScriptValidation(
    val isValid: Boolean,
    val reasons: List<ValidationReason>,
    val warnings: List<ScriptWarning>,
)
```

`BookSourceValidator` object 内追加：

```kotlin
    /**
     * 脚本书源校验：有效性判定沿用原生四查的两条（名称/URL），入口判定按脚本书源键名；
     * 警示三项（可执行代码/依赖登录/非文本源）只提示不拦截。
     * [rawJson] 供全文扫描可执行代码标记（`<js>` 与 `@js:`）。
     */
    fun validateScript(
        source: ScriptSourceRule,
        rawJson: String = "",
    ): ScriptValidation {
        val reasons = buildList {
            if (source.bookSourceName.isBlank()) add(ValidationReason.NAME_BLANK)
            if (!source.bookSourceUrl.startsWith("http")) add(ValidationReason.URL_NOT_HTTP)
        }
        val warnings = buildList {
            if (rawJson.contains("<js>") || rawJson.contains("@js:")) add(ScriptWarning.HAS_EXECUTABLE_CODE)
            if (source.loginUrl.isNotBlank()) add(ScriptWarning.NEEDS_LOGIN)
            if (source.bookSourceType != 0) add(ScriptWarning.NOT_TEXT_SOURCE)
        }
        return ScriptValidation(reasons.isEmpty(), reasons, warnings)
    }
```

Run: `./gradlew :module_me:testDebugUnitTest --tests "com.ebook.me.domain.BookSourceValidatorTest"`
Expected: PASS。

- [x] **Step 3: ImportPreviewItem 双格式化与 parseImportJson 分流**

`BookSourceViewModel.kt`：

```kotlin
    data class ImportPreviewItem(
        val format: SourceFormat,
        val nativeRule: BookSourceRule?,
        val scriptRule: ScriptSourceRule?,
        val scriptWarnings: List<ScriptWarning>,
        val validation: ValidationResult,
        val willOverwrite: Boolean,
    ) {
        val isValid: Boolean get() = validation is ValidationResult.Valid
        val displayName: String get() = nativeRule?.name ?: scriptRule?.bookSourceName.orEmpty()
        val displayUrl: String get() = nativeRule?.url ?: scriptRule?.bookSourceUrl.orEmpty()
    }
```

`parseImportJson` 每个对象分流（org.json 现场结构保留）：

```kotlin
            val keys = jsonObject.keys().asSequence().toSet()
            when (SourceFormatDetector.detect(keys)) {
                SourceFormat.SCRIPT -> {
                    val raw = jsonObject.toString()
                    val script = Json { ignoreUnknownKeys = true }.decodeFromString(ScriptSourceRule.serializer(), raw)
                    val validation = BookSourceValidator.validateScript(script, raw)
                    ImportPreviewItem(
                        format = SourceFormat.SCRIPT,
                        nativeRule = null,
                        scriptRule = script,
                        scriptWarnings = validation.warnings,
                        validation = if (validation.isValid) ValidationResult.Valid
                        else ValidationResult.Invalid(validation.reasons),
                        willOverwrite = manager.getFormatByUrl(script.bookSourceUrl) != null,
                    )
                }
                SourceFormat.NATIVE -> /* 既有原生路径原样保留，仅包装成新 ImportPreviewItem */
            }
```

确认落库分支：`item.format == SCRIPT` 时调 `manager.addScriptSource(jsonObject.toString())`，否则既有 `addSource(rule)`。

- [x] **Step 4: 预览页与清单 UI**

1. `ImportPreviewSheet`（BookSourceManageActivity.kt:545 起）：每项标题旁按 format 显示出身——native 项不变，script 项显示 `Text("脚本", style = MaterialTheme.typography.labelSmall)`；`scriptWarnings` 非空时在有效性文案下追加警示行（复用 `validationReasonRes` 的行样式）：

```kotlin
            item.scriptWarnings.forEach { warning ->
                Text(
                    text = when (warning) {
                        ScriptWarning.HAS_EXECUTABLE_CODE -> "含可执行脚本代码（将在零权限隔离环境中运行）"
                        ScriptWarning.NEEDS_LOGIN -> "依赖登录流程，登录相关功能暂不可用"
                        ScriptWarning.NOT_TEXT_SOURCE -> "漫画/音频类书源，本项目为文字阅读器，可能无法正常阅读"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
```

2. 书源清单列表项：名称旁按 `item.format` 显示同款「脚本」小标（`item` 为 `BookSourceItem`，`format` 字段 Task 8 已透出）。
3. 导出按钮：`item.format == SourceFormat.SCRIPT` 时不渲染导出入口（脚本源的导出在 Plan 2 随解释器一起补，本计划遗留）。

- [x] **Step 5: 运行 module_me 全部测试**

Run: `./gradlew :module_me:testDebugUnitTest`
Expected: PASS。

- [x] **Step 6: 红线 grep + 提交**

```bash
grep -ri --exclude-dir=build "[l]egado" module_me && echo "VIOLATION" || echo "CLEAN"
git add module_me
git commit -m "feat(module_me): 导入链路支持脚本书源——判别、警示与出身标记

- parseImportJson 按键集判别分流，脚本行走 addScriptSource 原样入库
- 校验沿用名称/URL 两查，警示三项：可执行代码/依赖登录/非文本源
- 预览页与书源清单显示出身标记；脚本行暂隐藏导出（Plan 2 随解释器补）" -m "Refs docs/adr/0029-script-book-source-import.md"
```

---

### Task 10: 文档同步与全量验证

**Files:**
- Modify: `AGENTS.md`（模块清单与依赖方向）
- Modify: `docs/adr/0029-script-book-source-import.md`（追加「落地状态」）
- Modify: `CONTEXT.md`——核对无需改动（脚本书源/原生规则书源术语已在 grill 会话落稿）

- [x] **Step 1: AGENTS.md 模块架构更新**

「模块架构」代码块中依赖方向行改为：

```
依赖方向：**业务模块 → lib_book_common → lib_book_source → lib_ebook_api → lib_ebook_db**（lib_book_common 同时直接依赖 lib_ebook_api/lib_ebook_db；lib_book_source 为书源解析专用层：原生解析器 + 脚本书源解释器 + 沙箱执行器，见 ADR-0028/0029）
```

模块清单列表在 `lib_book_common` 行后插入：

```
lib_book_source   → 书源解析专用层：原生解析器（自 lib_book_common 迁入）、脚本书源解释器（Plan 2）、:js 沙箱执行器（Plan 3）
```

「Agent 实战建议」书源段落的「新增/改动 BookSourceManager 的成员要同步测试替身」一句保持不变（成员清单已含脚本书源方法）。

- [x] **Step 2: ADR-0029 追加落地状态**

文末追加：

```markdown
## 落地状态

- **2026-09-08（阶段一，Plan 1）**：`lib_book_source` 模块建立，原生解析器一族迁入；`format` 列与 v6 迁移落地；`addScriptSource`/`getFormatByUrl`/`BookSourceItem.format` 就位；导入链路完成格式判别、警示与出身标记。脚本书源求值当前命中类型化桩（`ScriptSourcePendingParser`），不参与聚合搜索。解释器（决策 2）与沙箱执行器（决策 3）尚未实现，落地后在此补记。
```

- [x] **Step 3: 全仓红线 grep**

Run: `grep -ri --exclude-dir=build --exclude-dir=.git "[l]egado" --include="*.kt" --include="*.md" --include="*.json" --include="*.pro" --include="*.xml" . || echo "CLEAN"`
Expected: CLEAN（全仓零命中，含本计划文件自身）。**`--exclude-dir=build` 是必需的**：Material3 的西班牙语资源里
表「已展开」的那个过去分词，词尾恰好含同一串字母，资源合并后落在 `*/build/intermediates/**/values-es/*.xml`，
不排除会得到二十来条假命中（本仓实测 22 条）。权威口径是只扫跟踪文件的 `git grep -in "[l]egado"`（恒为 0 命中），
上面这条命令只是给没有 git 环境的人一个等价兜底。

- [x] **Step 4: 全量测试 + 全量构建（按仓库约定「能编译」由 Agent 负责）**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL，全部模块测试通过。
Run: `./gradlew :module_app:assembleDebug`
Expected: BUILD SUCCESSFUL（real + mock 两个 flavor 都产出）。

- [x] **Step 5: 提交**

```bash
grep -ri --exclude-dir=build "[l]egado" AGENTS.md docs/adr/0029-script-book-source-import.md && echo "VIOLATION" || echo "CLEAN"
git add AGENTS.md docs/adr/0029-script-book-source-import.md
git commit -m "docs(adr): 记录脚本书源阶段一落地状态并同步仓库指南

- AGENTS.md 模块图纳入 lib_book_source 及依赖方向
- ADR-0029 落地状态补记（解释器与沙箱待 Plan 2/3）" -m "Refs docs/adr/0029-script-book-source-import.md"
```

---

## 人工装机验证清单（Agent 不做，提交前人工执行——涉及 UI 与迁移链）

> 这 5 项已登记进 `docs/test-coverage-todo.md` 的「人工装机验证清单（脚本书源阶段一，2026-09-08）」，
> 那边按设备项展开成 7 组（多出「混合包里一条脚本 JSON 字段类型错」与「同 URL 跨出身重导的文案」两条）。
> **执行与销账以那边为准**，
> 本清单不动、也不在此勾选。

1. **迁移链**：用旧版本 APK（v5 库）覆盖安装新版本——书架/书源管理页正常，内置笔趣阁行为不变（`format` 缺省 native）。
2. **导入脚本书源**：从测试语料挑一条含 `<js>` 的源导入——预览页显示「脚本」出身 + 「含可执行脚本代码」警示；确认入库。
3. **导入纯声明式脚本书源**：挑一条无 `<js>` 的源——预览仅显示「脚本」无警示；入库后管理页清单项带「脚本」小标，导出按钮不出现。
4. **原生源回归**：导入一份原生格式书源 JSON——预览与入库行为与改前一致；书城/搜索/阅读全链路正常（解析器迁移零行为变化）。
5. **脚本书源解析路径**：把脚本书源加入书架（或搜索该书源）——预期得到明确失败提示（解释器未实现），**不是**静默无响应，也不是「书源已失效」。

---

## 执行期修正记录（2026-09-08）

计划落稿时未对齐现场代码的几处，实现按下列口径完成，后续 Task 与 Plan 2/3 以此为前提：

- **Room 3 迁移签名**：链上既有迁移一律 `override suspend fun migrate(connection: SQLiteConnection)` + `connection.execSQL(...)`，本文件 Task 6 Step 2 写的 `migrate(db: SupportSQLiteDatabase)` 编不过，已按现场签名实现。
- **`format` 列值大小写**：列值与 `MIGRATION_5_6` 的 `DEFAULT 'native'`、实体默认值同为**小写**（ADR-0029 决策 1 的 `native`/`script`）。`SourceFormat` 因此自带 `raw` 属性承载列值，读写一律取 `SourceFormat.SCRIPT.raw` / `NATIVE.raw`，**禁止**用 `name`（大写枚举名）——`fromRaw(raw == SCRIPT.name)` 会把 `script` 静默判成 `native`，即「不闪退、内容错乱」那一类最难查的故障（`lib_ebook_api` 的 `SourceFormatTest` 以小写不变式测试钉住）。
- **跨模块可见性**：`internal` 只在同模块（含同模块测试源集）可见，依赖方的 test source set 不是 friend module。故 `ScriptSourcePendingParser` / `ScriptInterpreterPendingException` 为 `public`（`lib_book_common` 的分发与断言都要用），`JsoupBookParser.rule` 与 `ChapterPageMatcher` 亦随 Task 4 放开；`Task 5` 的三个测试因此并入 Task 4（见该节）。
- **既有 flake（非本特性引入）**：`module_find` 的 `LibraryViewModelTest` 在 `tearDown` 偶发 `Dispatchers.Main is used concurrently with setting it`（其 `awaitUntil` 与真实 `Dispatchers.IO` 线程竞争，取消收集器早于 `resetMain` 即可修）。Task 10 跑全量 `test` 时若命中，重跑确认即可，不要误判为本特性的回归。
- **Plan 2 待办一条**：`JsoupSourceReader` 的 `parser as? JsoupBookParser ?: throw IllegalStateException("该书源不支持抓取网络正文")` 会把桩的类型化语义吃掉。今天不可达（脚本源不进 `getEnabledSources`，无法绑书），但脚本书源接入聚合/换源时必须在此处给出「解释器未实现」的准确文案。
- **`addScriptSource` 的列覆盖策略**（Task 8 Step 4 只写了 `name`/`ruleJson`/`format`/`enabled`/`group`，落地时补齐）：
  `weight`、`addedAt`、`isUserImported` 一律**沿用库里旧行**（新行才吃默认值 `0` / 当前时间 / `true`）——
  REPLACE 是整行替换，不回填就等于用户重导一次书源，排序、列表位置连同「内置源不可删」的身份全被冲掉。
  `group` 的回落链是「JSON 声明 → 旧行 → 既有默认常量『小说』」，**没有**采用计划里新造的 `"社区"` 字面量：
  全仓找不到按 group 分组展示的消费方，凭空多一个默认值只会让「默认分组」有两种写法。
  `enabled` 以 JSON 为准（重导会把用户在管理页拨的开关重置）——这与 `addSource` 的行为逐字一致，
  「重导即更新规则、但不丢本机元数据」这条口径不能两种出身各说一套。
- **脚本行永远当不上默认源**（Task 7 Step 5 第 3 点的不变式落地形态）：默认源候选只在原生规则里算
  （`refreshDefaultFromRoom` 经 `toRule`），于是「本来无可用默认源 + 导入一条启用的脚本源」的结果是
  `currentSource` 与 `observeDefaultSource()` **同为 null**、书城进引导态，而不是立一条空壳规则当默认源。
  同步快照与解析面因此不裂成两套口径；反过来若放脚本行进默认源，书城首屏拿到的是 rule 全空的「当前源」，
  症状正是「页面不闪退、数据永远加载不出来」。
- **预览与落库共用一个 `Json`**：Task 9 Step 3 代码样本里的就地 `Json { ignoreUnknownKeys = true }` 未采用，
  解码器收在 `lib_book_common` 的 `SourceStorageJson`（内含 `storageJson`，原生与脚本两种 `rule_json` 都走它）。
  「预览说能导」与「确实存得下」必须是同一个接受集给同一个答案，两处各开一份就会漂移出
  「点确认才发现存不下」那种只有一行日志、页面毫无反应的故障。
- **脚本行进入书城切换器的泄漏已在 `fix(module_find)` 收口**：`LibraryViewModel.sources` 除 `enabled` 外
  还过 `format == NATIVE`，否则脚本行会在下拉里成为一条切不过去的死条目（管理页的启用数与书城下拉条数
  本就该不同）。Plan 2 解释器接入时再放开这一道过滤。
- **评审后的三处收口**（Task 9 Step 2/3 的代码样本已被现网实现取代，别照着抄）：`validateScript` 的
  `rawJson` **去掉默认空串**（空串语义是「扫过、没有代码」，与「没扫」同形，漏传就是静默关掉可执行代码警示）；
  `ImportPreviewItem.displayName`/`displayUrl` 由跨格式 `?:` 链改成 `when (format)`（出身与载荷不一致的项
  宁可显示空名，也不借用另一格式的名字把构造点的路由错误藏起来）；`ScriptSourceJson` 更名
  `SourceStorageJson`（那份实例同时服务原生与脚本的 `rule_json`，原名会让人以为原生解码另有实例）。
- **Task 10 Step 1 那句「测试替身清单保持不变」是错的**：`BookSourceManager` 的内联假件实为六处
  （`module_find` 四处 + `module_book` 两处，`SourceSwitchFeedbackTest` 不在其中），AGENTS.md 已按实况改正。
- **两项非阻断遗留 + 一处取舍**：① 导入预览那一簇 Compose 组件（`ImportPreviewSheet` 及其行组件）仍挤在
  `BookSourceManageActivity.kt` 里，可另开一抽，本轮不阻断；② `overwriteText` 与 `scriptWarningRes` 两处映射
  没有 compose-test 基建可断言，只由文末人工清单覆盖；③ `formatOf`（判「将覆盖」）是每批次按条走一次 Room
  主键查询，已在调用点注释为取舍——换来的是只有 `getFormatByUrl` 这一个对两种出身平等可见的读面，
  比让预览自己再持一份 URL 清单做事实源更便宜。
