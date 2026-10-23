# 贡献指南

本仓库遵循 [Conventional Commits](https://www.conventionalcommits.org/)。本文件是提交信息、钩子与发版流程的事实源；构建与测试命令见 [`docs/development.md`](docs/development.md)，文档放置规则见 [`docs/AGENTS.md`](docs/AGENTS.md)。

## 提交信息

```
<type>(<scope>): <description>

[body]

[footer]
```

### Type（标准类型，不得自定义）

| Type       | 说明                        |
| ---------- | ------------------------- |
| `feat`     | 新功能                       |
| `fix`      | Bug 修复                    |
| `build`    | 构建系统 / 依赖变更（Gradle、版本目录等） |
| `refactor` | 重构（不改变外部行为）               |
| `docs`     | 文档变更                      |
| `test`     | 测试变更                      |
| `perf`     | 性能优化                      |
| `chore`    | 杂务（CI 配置、脚本、工具链等）         |
| `revert`   | 回退提交                      |

### Scope

Scope 优先使用模块目录名：

- **业务模块**：`module_app` / `module_main` / `module_book` / `module_find` / `module_me` / `module_login`
- **共享/基础库**：`lib_book_common` / `lib_book_source` / `lib_ebook_api` / `lib_ebook_db`
- **横切关注点**：`build` / `docs` / `scripts` / `adr`
- **跨模块**：`all`（改动覆盖多个模块且无法归到单一模块时使用）
- **领域切片**：`reader` / `search` / `download` / `import` 等，改动集中在一个功能切片、跨该切片的 UI/VM/领域层时使用；能用模块目录名表达时仍优先模块名。
- **可省略**：改动仅涉及根目录文件（如 `.gitignore`、`AGENTS.md`）或与 scope 无关的杂务时，可省略 scope

### Description

- 使用中文，不超过 72 个字符
- **动词前置**：以动词开头（新增…、修复…、重构…、升级…），禁止名词性短语（如「关于…的修改」「xxx 的变更」）
- **英文缩写保持原样**：TheRouter、MVVM、Hilt、Room 等专有名词保持原始大小写，不强行翻译
- **禁止模糊表述**：不允许「更新了 xxx」「优化了一下」等无信息量描述
- **禁止嵌入 issue 编号**：编号放在 footer（`Closes #123`），不要写在 description 里
- **不加句号**

### Body

- **何时写**：单行 description 不足以解释动机或影响时，用 body 补充 why / how
- **格式**：与 header 之间空一行；正文优先用列表分条目（每个条目一个改动点，条目按行换行、续行缩进两空格），每行不超过 100 字符，禁止一整段超长单行；条目组之间空一行
- **语言**：与 description 一致（中文）

### Footer

- **Breaking Change**：在 footer 中声明 `BREAKING CHANGE: <描述>`，或在 type 后加 `!`（如 `feat!: ...`）
- **关联 Issue**：`Closes #123` / `Refs #456`

## 版本号

**版本号只在发版时定，不在提交时定**；单个提交不携带版本号。定档判据是「用户视角三问」：升级会不会让老用户出问题 → MAJOR；有没有用户拿得到的新东西 → MINOR；只是把已有东西修对 → PATCH。自上而下问、命中即停，一批改动取最高档。

提交类型是近似而非判据：`fix` 顺带改交互/观感是 MINOR，`feat` 纯内部能力是 PATCH；`BREAKING CHANGE` / `!` 不自动定档——破坏用户的东西才是 MAJOR，只是代码内部契约则落回第 2/3 问。本仓无对外 API，内部破坏不构成 MAJOR。

完整判据、六条边界、`versionCode` 进制（`MAJOR*1_000_000 + MINOR*1_000 + PATCH`）与例外表见 [`docs/versioning-practice.md`](docs/versioning-practice.md)。

**没有自动化**：本仓无 CI、无 release 工具，bump 与打 tag 都由发布者本地手工执行（一个 `build:` 提交 + annotated tag）。

## Revert 格式

Revert 有特殊格式约定，`description` 为被回退提交的完整 header，body 中必须包含 `This reverts commit <hash>`：

```
revert: feat(module_me): 新增个人中心编辑资料入口

This reverts commit abc1234.
原因: 导致内存泄漏，待修复后重新合入。
```

## 示例

```
feat(module_me): 新增个人中心编辑资料入口

引入头像上传与昵称修改能力，
支持登录用户维护个人标识信息。

Closes #42
```

```
fix(lib_ebook_api): 修复书源请求误携带认证 token

书源请求改用 @Named("source") 纯净客户端，
避免第三方网站读取到用户 token。
```

```
build: 升级 Gradle 到 9.4.1
```

Breaking change：

```
refactor!: 阅读进度改为章节页号并重排 dur_chapter_page

老用户升级后书架上的「读至」会整体错位，必须重新定位。

BREAKING CHANGE: dur_chapter_page 语义变更，历史进度不迁移
```

## 提交前验证

- **首次 clone 后**执行 `bash scripts/install-hooks.sh`，把 `commit-msg` 校验钩子装进 `.git/hooks/`（钩子实现在 `scripts/commit-msg`）。`.git/hooks/` 里是**复制件而非软链**，故改完 `scripts/commit-msg` 必须重跑该脚本，否则本机仍按旧版钩子拦截提交。
- 钩子**强制**的只有四项：header 的 `type(scope)!: description` 结构、`type` 在白名单内、revert 形态的 body 必须含 `This reverts commit <hash>`、description 不以中文句号结尾且不超 72 字符。**动词前置、禁嵌 issue 编号、body 分行与语言等约定不受脚本强制**，靠本文件与评审约束——钩子放行不等于合规。它只校验**作者手写的消息**：git 自动生成或临时的形态（merge、`merge --squash`、`fixup!`/`squash!`/`amend!`）一律放行，模板注释/前导空行也不当作 header。
- 运行 `./gradlew test`，并对涉及模块执行 `./gradlew :module:assembleDebug`。
- **「能编译、能安装、能打开页面」三步缺一不可，但分工明确**：Agent 止于第一步（编译与静态检查），第二、三步（安装运行、打开页面确认）由人工在提交前完成。涉及 UI/启动链路（Activity、Compose 页面、drawable 资源）的改动一律适用此分工。
- **构建通过不等于可运行**：`painterResource` 加载 NinePatch（.9.png）、selector 等资源类型只会在运行时抛异常（`ResourceResolutionException`），启动不闪退与页面正常渲染只能在设备或模拟器上确认（`adb logcat -b crash` 无 FATAL EXCEPTION）。Agent 未做装机验证时**必须显式交代**：留给人工的验证项是什么——打开哪个页面、走哪条路径、看什么现象，并标注该步骤未验证。禁止以「构建通过」暗示改动已验证。
- 文档改动跑 `bash scripts/check-docs.sh`。
