# 仓库指南（Agent 常驻规则）

本文件只放**每次会话都必须可见**的硬约束与指针。细节按 [`docs/AGENTS.md`](docs/AGENTS.md) 的分层规则放在各自的家；不要在这里复述。

## 1. 项目概述

安卓小说阅读器，100% Kotlin，MVVM，多模块 Gradle。界面全部 Jetpack Compose（含阅读器），异步统一 Kotlin Coroutines + Flow。应用**不内置任何书源**，书源由用户导入。

模块与依赖方向见 [`docs/architecture.md`](docs/architecture.md)；构建、测试与工具链见 [`docs/development.md`](docs/development.md)；领域术语见 [`CONTEXT.md`](CONTEXT.md)。

## 2. 命令速查

```bash
bash scripts/install-hooks.sh                  # 首次 clone 后安装 commit-msg 钩子
./gradlew :module_app:assembleMockDebug        # mock 数据源构建
./gradlew :module_app:assembleRealDebug        # 真实后端构建
./gradlew test                                 # 单元测试
./gradlew :module:assembleDebug                # 指定模块
bash scripts/check-docs.sh                     # 文档检查
```

Windows PowerShell 用 `.\gradlew`；完整命令见 [`docs/development.md`](docs/development.md)。

## 3. 常驻硬约束

**技术栈**

- 界面全部 Jetpack Compose；ViewBinding 与 XML 布局已移除，不再引入。异步只用 Coroutines + Flow；RxJava3 已移除，禁止重新引入。
- 图片只用 Coil，日志只用 `com.xrn1997.common.util.Logger`，不要引入 Glide 或直接调用 `android.util.Log`。
- Compose 用 `MaterialTheme.colorScheme` 语义色；只有阅读器正文层用阅读背景主题。

**模块与构建**

- 提交态 `isModule` 必须是 `false`；临时单模块独立运行直接改 `gradle.properties`，调试完改回、不提交；不要用 `./gradlew -PisModule=true` 覆盖。
- 功能模块只依赖 `lib_book_common`，互不依赖；跨模块导航用 TheRouter，服务经 `provider/` 暴露。页面需要宿主能力时用可空参数向下传，不用 `CompositionLocal` 或另开路由表达「切 Tab」。
- 持有 ViewModel 的页面继承 `BaseMvvmActivity`，ViewModel 继承 `BaseViewModel`/`BaseRefreshViewModel` 并经 Hilt 注入；无 Model 门面用 `NoOpModel()`，不得直继 `androidx.lifecycle.ViewModel`。Compose 业务页面继承 lib_common 的 `BaseActivity`，不裸包 `MaterialTheme`。
- `Application` 上不许有 eager `@Inject` 字段；`:js` 进程会在 `super.onCreate()` 内完成字段注入，重对象经 `EntryPointAccessors` 在用到时取。
- 依赖版本只走 `gradle/libs.versions.toml`，不硬编码；唯一例外是 `settings.gradle.kts` 的 foojay 插件版本。

**数据与领域**

- 应用不内置书源，`book_source` 表是清单唯一事实源；每本书按 `tag` 绑定归属源，解析一律跟书走，不按全局默认源。`getParserFor` 对脚本行永不返回 null；null 只表示「书源不存在/坏行」，两种失败不得混用。
- 脚本书源沙箱**永不在主线程调用**；外呼只用 `GuardedNetwork` 派生的守门客户端，不自己 new OkHttpClient；未装配执行器与执行失败是两种类型化异常，不得合并成「该源已失效」。
- Room 主键两套：自然键整行 REPLACE；自增键 upsert 必须查回旧行、用 `existing?.id ?: 0L` 回填主键。写书架条目（书架行 + 章节 + 书籍信息 + 关联键）必须在同一笔写事务里，事件在提交后才发。改实体必须接迁移链，禁止 `fallbackToDestructiveMigration`。
- access token 只驻内存、只对白名单 host 附加；书源与版本检查走不带 token 的 `@Named("source")`/`@Named("release")` 客户端。清会话一律只调 `userSessionManager.clearSession()`；会话过期统一经 `reportFailure` 上报，不手写 `isSessionExpiredHandled`。
- 下载一律经 `DownloadService.start(context, intent)`；先入库再拉服务；失败章在重试耗尽后出队，暂停中断不出队；`onTimeout` 内只做数秒收尾。
- 阅读器块高取排版实测值；页面跳转走 `gotoPage`；滚屏 item key 只由 `(章, 块)` 构成、锚点存语义值、单次上方插入不超过约 100 项；进度只在 `onPause` 落库，「异常关闭后恢复」看标记残留而不是进度。
- 本地书导入按 `comment_key` 判重；处置顺序先导入新条目、后处置旧条目；补章要求旧书章名序列是新书前缀，新索引取 `max(durChapterIndex) + 1`；书籍目录名由 `BookStore` 从 `note_url` 派生。

**文档、测试与提交**

- 代码、注释、文档同一改动内同步；架构级决定沉淀为 `docs/adr/` 的 ADR。一个事实只有一个家，文档改动后跑 `bash scripts/check-docs.sh`。
- 单元测试用 JUnit 4，类名 `<Subject>Test`，方法用反引号句子式描述；行为变更同步更新测试。
- 提交遵循 Conventional Commits，提交前先装 `commit-msg` 钩子；类型、scope、版本判据见 [`CONTRIBUTING.md`](CONTRIBUTING.md) 与 [`docs/versioning-practice.md`](docs/versioning-practice.md)。
- Agent 止于编译与静态检查；安装运行、打开页面确认由人工在提交前完成。未做装机验证时必须点名留给人工的页面、路径与现象，不能以「构建通过」暗示已验证。

## 4. 改哪里先读什么

| 改动面 | 先读 |
|---|---|
| 书源、脚本书源、解析器、沙箱 | [`docs/architecture.md`](docs/architecture.md)「书源与内容获取」；ADR-0016/0028/0029/0033；[`docs/specs/book-source-rule-grammar.md`](docs/specs/book-source-rule-grammar.md)；[`docs/book-source-rules.md`](docs/book-source-rules.md) |
| 下载、前台服务、下载中心 | [`docs/architecture.md`](docs/architecture.md)「下载与前台服务」；ADR-0018/0034/0035/0036 |
| 阅读器、翻页/滚屏、进度 | [`docs/architecture.md`](docs/architecture.md)「阅读器」；ADR-0012/0032/0037/0040 |
| 本地书导入、正文读取 | [`docs/architecture.md`](docs/architecture.md)「本地书导入」；ADR-0017/0023/0038；[`docs/specs/local-book-import-design.md`](docs/specs/local-book-import-design.md) |
| Room、实体、迁移 | [`docs/architecture.md`](docs/architecture.md)「数据与存储」；ADR-0003 |
| 认证、会话、token | [`docs/architecture.md`](docs/architecture.md)「认证与会话」；ADR-0008/0009/0010/0011/0025 |
| 版本更新检查 | [`docs/architecture.md`](docs/architecture.md)「版本更新检查」；ADR-0021 |
| 评论接口、评论标识 | [`docs/specs/comment-api-contract.md`](docs/specs/comment-api-contract.md)；ADR-0013 |
| 清单权限 | [`docs/architecture.md`](docs/architecture.md)「权限」；ADR-0022 |
| 混淆规则 | [`docs/development.md`](docs/development.md)「混淆」；ADR-0024 |
| 共享 Compose 组件 | [`docs/architecture.md`](docs/architecture.md)「共享 UI」；ADR-0006/0042 |
| 启动页、冷启动路由 | [`docs/architecture.md`](docs/architecture.md)「启动与路由」；ADR-0040 |
| 文档、提交、发版 | [`docs/AGENTS.md`](docs/AGENTS.md)；[`CONTRIBUTING.md`](CONTRIBUTING.md)；[`docs/adr/ADR-FORMAT.md`](docs/adr/ADR-FORMAT.md) |

## 5. 文档地图

| 文档 | 内容 |
|---|---|
| [`README.md`](README.md) | 项目入口与快速开始 |
| [`CONTEXT.md`](CONTEXT.md) | 领域术语与消歧 |
| [`docs/architecture.md`](docs/architecture.md) | 当前架构与不变量 |
| [`docs/development.md`](docs/development.md) | 构建、测试、工具链 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 提交与发版流程 |
| [`docs/adr/`](docs/adr/) | 决策记录（why） |
| [`docs/specs/`](docs/specs/) | 稳定契约与语法规格 |
| [`docs/superpowers/`](docs/superpowers/) | 进行中的计划/设计、历史评审/调研、归档 |
| [`CHANGELOG.md`](CHANGELOG.md) | 发布历史 |

`docs/superpowers/` 不是事实源；计划里的 `- [ ]` 只在「进行中」目录里有待办语义，归档后只作历史证据。`.qoder/repowiki/` 是生成快照，不手改、不作为架构判断依据。

## 6. 当前状态

- 版本与发布历史见 [`CHANGELOG.md`](CHANGELOG.md)；发版判据见 [`docs/versioning-practice.md`](docs/versioning-practice.md)。
- 测试缺口台账见 [`docs/test-coverage-todo.md`](docs/test-coverage-todo.md)。
- 文档分层与生命周期见 [`docs/AGENTS.md`](docs/AGENTS.md)。
