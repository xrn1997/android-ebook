# Copilot 使用说明 - android-ebook

这是一个 **100% Kotlin** 开发的安卓小说阅读器应用，采用 MVVM 架构、Jetpack Compose（部分迁移）和多模块结构。

## 构建与测试命令

### 构建项目
```bash
# 构建整个项目
./gradlew build

# 构建指定模块
./gradlew :module_app:assembleDebug

# 清理构建
./gradlew clean build

# 生成 APK
./gradlew :module_app:assembleRelease
```

### 测试
```bash
# 运行单元测试
./gradlew test

# 运行集成测试（需要连接设备/模拟器）
./gradlew connectedAndroidTest

# 运行指定模块的测试
./gradlew :module_book:testDebugUnitTest
```

### 代码检查
```bash
# 运行 lint 检查
./gradlew lint

# 生成 lint 报告
./gradlew lintDebug

# 检查所有模块
./gradlew lintRelease
```

## 模块化开发模式

项目支持**模块化开发**，可以独立测试各个功能模块：

- **配置文件**: `gradle.properties` 中设置 `isModule=true` 启用独立模块模式
- **默认值**: `isModule=false`（包含所有模块）
- **启用后**: `module_app` 会排除功能模块依赖，各模块可独立运行
- **使用方法**: 修改 `gradle.properties` 中的属性值，然后同步 Gradle
- **应用场景**: 单独调试某个功能模块，加快编译速度

## 项目架构

### 多模块结构

项目采用模块化架构，职责清晰分离：

```
android-ebook/
├── module_app/          # 主应用模块（入口、Hilt 初始化）
├── module_main/         # 主页/启动页
├── module_book/         # 书籍阅读、管理、评论
├── module_find/         # 书城发现、搜索、书库浏览
├── module_me/           # 个人中心、昵称/头像修改、评论管理
├── module_login/        # 登录认证、注册、密码修改（Compose）
├── lib_book_common/     # 共享库：UI 组件、工具类、基类
├── lib_ebook_api/       # 网络层：Retrofit、数据模型、错误处理
├── lib_ebook_db/        # 数据库层：ObjectBox 实体定义
└── build-logic/         # 自定义 Gradle 约定插件
```

### 各层职责

- **功能模块** (`module_*`): UI（Activity/Fragment/Compose）、ViewModel、功能特定逻辑
- **库模块** (`lib_*`): 可复用组件、数据访问、API 客户端
- **build-logic**: 自定义 Gradle 插件，统一配置各模块（避免重复配置）

### MVVM 模式

所有功能模块遵循 MVVM 架构：
- **View**: Activity/Fragment（ViewBinding）或 Composable（Jetpack Compose）
- **ViewModel**: 继承 `BaseViewModel` 或 `BaseRefreshViewModel`，使用 Hilt 注入
- **Model**: 各模块 `mvvm/model/` 目录中的数据层（Repository 模式）

**数据流**：
- Model 层处理业务逻辑和数据访问
- ViewModel 通过 `LiveData`/`StateFlow` 暴露 UI 状态
- View 层观察状态变化并更新 UI

**注意**: 项目正在从 RxJava3 迁移至 Kotlin Coroutines：
- 新代码（`LoginViewModel`、`CommentViewModel`）使用 `viewModelScope.launch` + `suspend` 函数
- 旧代码（`SearchViewModel`、`BookListViewModel`）仍使用 RxJava3
- 保持一致性：新功能优先使用 Coroutines + Flow

## 技术栈

### 核心技术
- **语言**: Kotlin 2.2.20（100% Kotlin，无 Java 源代码）
- **构建工具**: Gradle 8.13.0（需要 Android Studio 2025.1.3+）
- **编译 SDK**: 36
- **目标 SDK**: 36（Android 15）
- **最低 SDK**: 26（Android 8.0）
- **JDK**: 17

### 主要依赖

**依赖注入**：
- Hilt 2.57.2（全项目使用 `@HiltViewModel` 和 `@Inject`）
- Dagger 2.57.2（Hilt 底层）

**路由导航**：
- TheRouter 1.3.0（已替换停止维护的 ARouter）
- 使用 `@Route(path = "...")` 注解实现模块间解耦导航

**网络请求**：
- Retrofit 3.0.0 + OkHttp 5.3.0
- Gson 2.13.2、FastJSON2 2.0.60
- Kotlinx Serialization 1.9.0

**响应式编程**：
- **新代码**: Kotlin Coroutines 1.10.2 + Flow
- **旧代码**: RxJava3 3.1.12 + RxAndroid 3.0.2
- RxBinding 4.0.0、RxBus 3.0.0（事件总线）

**数据库**：
- ObjectBox 5.0.1（NoSQL 数据库，已知问题见下文）
- Kotlin Parcelize（序列化）

**UI 框架**：
- **Compose**: AndroidX Compose BOM 2025.10.01、Material3
- **ViewBinding**: 传统 View 系统（正在迁移至 Compose）
- Material Design 1.13.0、ConstraintLayout 2.2.1

**图片加载**：
- Glide 5.0.5（主要）
- Coil 2.7.0（Compose）

**其他工具**：
- JSoup 1.21.2（HTML 解析，书源）
- PermissionX 1.8.1（权限请求）
- TinyPinyin 2.0.3（中文拼音）

### 约定插件

项目在 `build-logic/` 中使用自定义 Gradle 约定插件：
- `xrn1997.android.application` - 应用模块配置（compileSdk=36, targetSdk=36, minSdk=26）
- `xrn1997.android.library` - 库模块配置
- `xrn1997.android.component` - 功能组件配置
- `xrn1997.android.library.compose` - Compose 库配置
- `xrn1997.hilt` - Hilt 依赖注入配置
- `xrn1997.android.lint` - Lint 配置（xmlReport, checkDependencies）

**优势**：避免在每个模块重复配置，统一管理构建逻辑。

## 编码规范

### 依赖注入
- **统一使用 Hilt**：`@HiltViewModel`、`@Inject`
- ViewModel 构造函数注入 `Application` 和 `Model`（Repository）
- 不要手动创建 ViewModel，使用 `by viewModels()` 委托

### 响应式编程
- **新功能**: 优先使用 Kotlin Coroutines + Flow
  ```kotlin
  viewModelScope.launch {
      val result = repository.fetchData()  // suspend function
      _uiState.value = result
  }
  ```
- **现有代码**: RxJava3（Observable、Single、Flowable）
  ```kotlin
  repository.getData()
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(...)
  ```
- **迁移策略**: 逐步将 RxJava3 代码重构为 Coroutines

### UI 开发
- **新界面**: 使用 Jetpack Compose + Material3
- **现有界面**: ViewBinding（已移除 DataBinding）
- **深色模式**: 遵循 Material Design 3 规范，支持自动切换（阅读界面除外）
- **状态栏**: 使用 EdgeToEdge API 实现沉浸式状态栏

### 数据库（ObjectBox）
- 实体类在 `lib_ebook_db/` 中定义（使用 `@Entity`）
- 使用 `ObjectBoxManager` 单例访问数据库
- **已知问题**: 
  - 打开书籍会自动写入数据库
  - 删除书籍后无法重新插入同一对象（ObjectBox ID 限制）
  - **建议**: 新功能考虑使用 Room（项目计划中）

### 路由导航
- 使用 TheRouter 注解：`@Route(path = "/module/activity")`
- 模块间导航通过路由实现，避免直接依赖
- 每个模块有 `provider/` 目录暴露服务接口（用于跨模块通信）

### 书源解析
- 当前书源：www.shuangliusc.com（搜索功能已失效，非项目问题）
- 使用 JSoup 进行 HTML 解析
- 书源接口在 `lib_ebook_api` 中定义

## 常见任务

### 添加新功能模块
1. 在 `settings.gradle.kts` 中添加模块：`include(":module_new")`
2. 创建模块目录并添加 `build.gradle.kts`
3. 应用 `xrn1997.android.component` 约定插件
4. 在 `module_app/build.gradle.kts` 中添加依赖（根据 `isModule` 参数条件引入）
5. 添加 TheRouter 路由定义

### 添加新依赖
1. 在 `gradle/libs.versions.toml` 的 `[versions]` 中定义版本号
2. 在 `[libraries]` 中添加依赖声明
3. 在模块的 `build.gradle.kts` 中引用：`implementation(libs.your.library)`
4. 运行 `./gradlew build` 验证配置

### 定义新的自定义 View
- **位置**: `lib_book_common/src/main/java/com/ebook/common/view/`
- **基类**: 继承现有 View 或自定义基类（如 `BaseView`）
- **自定义属性**: 在 `lib_book_common/res/values/attrs.xml` 中定义 `<declare-styleable>`
- **重要提示**: **避免在子类直接初始化父类属性**（会导致初始化顺序问题）
  ```kotlin
  // ❌ 错误：子类字段会覆盖父构造函数中的赋值
  class MyView : BaseView {
      var customAttr: Int = -1  // 覆盖父类构造函数中的赋值
  }
  
  // ✅ 正确：使用哨兵值 + init 块检查
  class MyView : BaseView {
      var customAttr: Int = 0
      init {
          if (customAttr == 0) customAttr = -1
      }
  }
  ```
  - **原因**: Kotlin 初始化顺序为：父构造函数 → 子类字段初始化 → 子类 init 块
  - **示例**: `MHorProgressBar.kt` 第 35-48 行

### 创建新的 ViewModel
```kotlin
@HiltViewModel
class MyViewModel @Inject constructor(
    application: Application,
    private val myModel: MyModel
) : BaseViewModel(application, myModel) {
    
    // 使用 Coroutines（推荐）
    fun fetchData() {
        viewModelScope.launch {
            val result = myModel.getData()
            _uiState.value = result
        }
    }
}
```

### 使用 TheRouter 导航
```kotlin
// 定义路由（在目标 Activity）
@Route(path = "/book/detail")
class BookDetailActivity : BaseActivity() { ... }

// 导航到目标页面
TheRouter.build("/book/detail")
    .withString("bookId", "123")
    .navigation(context)
```

### 访问数据库（ObjectBox）
```kotlin
// 获取 Box
val bookBox = ObjectBoxManager.getBoxStore().boxFor(Book::class.java)

// 查询
val books = bookBox.query()
    .equal(Book_.bookId, bookId)
    .build()
    .find()

// 插入/更新
bookBox.put(book)

// 删除（注意已知问题）
bookBox.remove(book)
```

## 已知问题与解决方案

### 1. ObjectBox 数据库问题
**症状**:
- 打开书籍会自动写入数据库
- 删除书籍后无法重新插入同一对象（报错 ID 冲突）

**原因**: ObjectBox 使用内部 ID（`@Id`），删除后 ID 被回收，重新插入同一对象会失败

**临时方案**:
- 删除前清除对象 ID：`book.id = 0L`
- 使用唯一业务 ID（如 `bookId`）进行查询，避免依赖 ObjectBox 内部 ID

**长期计划**: 迁移至 Room 数据库

### 2. RxJava 与 Coroutines 共存
**症状**: 代码风格不一致，错误处理混乱

**原因**: 项目正在从 RxJava3 迁移至 Kotlin Coroutines

**建议**:
- **新代码**: 始终使用 Coroutines + Flow
- **现有代码**: 如需维护，保持 RxJava 风格，不要混合使用
- **迁移优先级**: 
  1. ViewModel 层（使用 `viewModelScope.launch`）
  2. Repository 层（网络请求 + 数据库操作）
  3. 工具类

### 3. 自定义 View 属性初始化问题
**症状**: XML 定义的属性值在运行时被覆盖为默认值

**原因**: Kotlin 子类字段初始化在父构造函数之后执行，覆盖了父构造函数中从 XML 读取的值

**解决方案**: 
- 使用哨兵值（如 `0`）初始化，在 `init` 块中检查并设置默认值
- 参考 `MHorProgressBar.kt` 和 `MVerProgressBar.kt` 的实现（第 35-48 行）

### 4. 性能问题
**已知热点** (基于代码审查):
- `SearchViewModel.searchBooks()`: 主线程网络请求（应使用 `subscribeOn(Schedulers.io())`）
- 部分 ViewModel 直接访问数据库，绕过 Repository 层

**优化建议**:
- 所有耗时操作（网络、数据库）必须在后台线程/协程中执行
- 严格遵循 MVVM 分层，ViewModel 只通过 Repository 访问数据

### 5. 书源问题
**当前书源**: www.shuangliusc.com
- 搜索功能失效（书源网站问题，非项目 bug）
- 计划学习"阅读" App 的书源机制

### 6. 其他已知问题
1. **深色模式**: 阅读界面尚未完全适配，不支持跟随系统切换
2. **章节顺序 Bug**: 从书城直接阅读会导致章节乱序（建议先加入书架）
3. **文本解析**: 无法解析从"阅读"App 下载的 TXT 文件（自然段解析失效）
4. **无服务器**: 旧服务器已过期（2022 年），用户系统不可用
5. **忘记密码功能**: 登录模块的密码重置存在已知 Bug

## 注意事项

### Maven 仓库
项目配置了国内镜像（`settings.gradle.kts`）：
- Aliyun（阿里云）
- Tencent（腾讯云）
- 适用于中国大陆开发者，加速依赖下载

### 编译要求
- **Android Studio**: >= 2025.1.3（支持 Gradle 8.13）
- **JDK**: 17（在 `gradle.properties` 中配置）
- **Gradle**: 8.13.0（wrapper 自动下载）

### Compose 编译器配置
- 稳定性配置: `compose_compiler_config.conf`
- 支持 Material3 非稳定 API

### Debug 工具
- **LeakCanary**: 自动检测内存泄漏（Debug 版本）
- **Stetho**: Chrome DevTools 调试（已集成但需手动启用）

## 开发工作流

1. **新功能**: 在相应的功能模块（`module_*`）中创建
2. **共享组件**: 添加到 `lib_book_common`
3. **API 变更**: 更新 `lib_ebook_api`
4. **数据库**: 在 `lib_ebook_db` 中修改实体
5. **Gradle 配置**: 使用/扩展 `build-logic` 中的约定插件

## 迁移策略

项目正在积极迁移：
- Java → Kotlin ✅（**100% 完成，229 个 .kt 文件，0 个 .java 源文件**）
- MVP → MVVM ✅（已完成，全项目使用 MVVM）
- DataBinding → ViewBinding + Compose ⏳（进行中，登录模块已用 Compose）
- RxJava → Coroutines ⏳（进行中，新 ViewModel 使用 Coroutines）
- Dagger → Hilt ✅（已完成，全项目使用 `@HiltViewModel`）
- ObjectBox → Room? 🔮（未来考虑，因性能和 ID 问题）

**新代码请使用**: Kotlin、MVVM、Compose、Coroutines 和 Hilt。

## 贡献指南

### 代码风格
- 遵循 Kotlin 官方风格指南
- 使用 Android Studio 默认格式化（Ctrl+Alt+L）
- 命名规范：
  - Activity: `XxxActivity`
  - Fragment: `XxxFragment`
  - ViewModel: `XxxViewModel`
  - Composable: `XxxScreen` 或 `XxxContent`

### Commit 规范
- 使用中文描述（项目为中文注释）
- 清晰说明修改内容和原因
- 示例：
  - `修复进度条宽度异常（Kotlin 属性初始化顺序问题）`
  - `迁移 SearchViewModel 至 Coroutines`
  - `添加 XXX 功能模块`

### Pull Request
- 提交前运行 `./gradlew lint` 确保无严重警告
- 运行 `./gradlew test` 确保测试通过
- 描述清楚修改的内容、原因和测试方法

### Issue 提交
- 描述清楚问题（复现步骤、预期行为、实际行为）
- 提供日志（Logcat 输出）
- 提供设备信息（Android 版本、设备型号）
- 无效或乱提交的 Issue 会被直接关闭

---

**最后更新**: 2026-02-11  
**项目进度**: 100% Kotlin 迁移完成，Compose 迁移进行中  
**主要维护者**: [xrn1997](https://github.com/xrn1997)
