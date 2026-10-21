# 文档规范

本文件定义本仓每份文档的职责、写作规则与生命周期。它只回答「这条内容该放哪、什么时候更新、什么时候归档」，不重复业务规则。

> 唯一判据：**一个事实只有一个家**；其他地方只链接，不复述。根 `AGENTS.md` 只放常驻规则（standing orders），细节经指针到达。

## 1. 分层与职责

| 层 | 文档 | 只负责 | 不负责 | 事实源 |
|---|---|---|---|---|
| 常驻规则 | `AGENTS.md` | 跨会话必须可见的硬约束、仓库地图、命令速查、分支指针 | 架构细节、教程、历史坑、完整提交规范 | 是（仅规则） |
| 项目入口 | `README.md` | 是什么、模块图、快速开始 | 规则、决策、历史 | 否 |
| 领域词汇 | `CONTEXT.md` | 术语、语义、`_Avoid_` | 实现步骤、决策理由 | 是（词汇） |
| 当前架构 | `docs/architecture.md` | 模块依赖、核心接缝、各域不变量 | 为什么（→ ADR）、操作步骤（→ development）、历史 | 是 |
| 开发手册 | `docs/development.md` | 构建、运行、测试、工具链、mock/独立模块、混淆、日志 | 架构理由、业务规则 | 是（操作） |
| 贡献指南 | `CONTRIBUTING.md` | 提交规范、钩子、发版流程、提交前验证 | 架构、构建细节 | 是（流程） |
| 决策记录 | `docs/adr/` | 一个决策的 why、替代方案、后果 | 当前架构复述、计划、验收清单 | 是（why） |
| 稳定规格 | `docs/specs/` | 外部契约、语法/行为规格等长期事实源 | 一次性设计方案、施工步骤 | 是 |
| 书源参考 | `docs/book-source-rules.md` | 书源格式、导入、FAQ（面向用户/规则作者） | 引擎实现、沙箱细节 | 是（用法） |
| 版本政策 | `docs/versioning-practice.md` | SemVer、三问判据、versionCode、tag | 发布历史、提交类型 | 是（政策） |
| 测试缺口 | `docs/test-coverage-todo.md` | 未覆盖项的工作台账 | 测试规范、已完成细节 | 否 |
| 过程工件 | `docs/superpowers/{plans,specs}/` | 进行中的计划/设计 | 长期事实、决策 | 否 |
| 历史记录 | `docs/superpowers/{reviews,research}/`、`docs/superpowers/archive/` | 当时发生了什么 | 当前事实 | 否 |
| 发布历史 | `CHANGELOG.md` | 每个版本的变更 | 版本政策 | 是（发布记录） |
| 生成快照 | `.qoder/repowiki/` | 工具生成的知识快照 | 手工维护 | 否 |

## 2. 放置判据

- 每次会话都必须看到、且不看会做错的事 → 根 `AGENTS.md`。
- 当前代码/运行时的结构、机制、不变量 → `docs/architecture.md`。
- 怎么构建、运行、测试、配置工具链 → `docs/development.md`。
- 怎么提交、钩子怎么装、版本怎么定 → `CONTRIBUTING.md`（版本判据细节在 `docs/versioning-practice.md`）。
- 为什么选这个方案、放弃了什么 → `docs/adr/`。
- 跨仓库契约、语法/行为规格 → `docs/specs/`。
- 一次性的施工计划、变更设计、评审、调研 → `docs/superpowers/`。
- 领域词义 → `CONTEXT.md`；发布历史 → `CHANGELOG.md`；测试缺口 → `docs/test-coverage-todo.md`。

拿不准时按「读者在什么场景会打开它」选层：改代码时读 architecture，搭环境时读 development，写提交时读 CONTRIBUTING，问「当初为什么」时读 ADR。

本项目为单上下文仓库，无 `CONTEXT-MAP.md`；领域词义只查 [`CONTEXT.md`](../CONTEXT.md)。

## 3. 写作规则

1. **当前事实源只写现在时**。历史、变更过程、旧方案只进 ADR、CHANGELOG、`docs/superpowers/` 或 git；不在 architecture/development 里写「已改为」「曾经」「下一步计划」。
2. **当前事实源里一个事实只有一个家**。同一结论在两份当前事实源里出现就是错误：保留更合适的那个，另一个改成链接。根 `AGENTS.md` 的 standing-order 摘要与 `docs/architecture.md` 的详细机制是「摘要 + 详情」关系，不算重复；摘要不得复述机制。
3. **链接用相对 Markdown**，不要写裸路径；指向标题时用 GitHub 生成的 slug。移动文件必须同步更新入链。
4. **代码与配置是最终事实源**。文档写「为什么、不变量、入口、不能怎么做」，不逐行复述实现；具体类名/签名可能变化时用链接而非粘贴。
5. **`AGENTS.md` 预算 ≤ 8,000 字符**（`wc -m`，中文不要按词数算）。超限时按「搬迁 → 压缩 → 提额并说明」处理；提额必须在提交信息里写理由。
6. **生成物不手改**。`.qoder/repowiki/` 生成时点早于多书源/脚本书源落地，整棵树没有 `lib_book_source` 模块，另有一些与代码相反的描述；需要架构判断时以代码与 [`docs/architecture.md`](architecture.md) 为准。更新时重新生成整棵树，不要逐文件修——改完下次生成照样覆盖。
7. **ADR 保持单篇自足**。ADR 正文不交叉引用其他 ADR；需要背景就写成自足描述。这里的「链接不复述」针对事实源，不改变 ADR 的自足规则。
8. **新增/修改文档后跑 `bash scripts/check-docs.sh`**，检查 AGENTS 预算、相对链接存在性、过程工件的状态头。

## 4. 生命周期

### ADR

- 位置 `docs/adr/NNNN-slug.md`，编号连续、不复用；写作判据与章节见 [`docs/adr/ADR-FORMAT.md`](adr/ADR-FORMAT.md)。
- 默认都是「已采纳」，不设独立状态字段；现行结论以正文为准，历史由 git 保存。写作、独立性与取代规则见 `docs/adr/ADR-FORMAT.md`。

### 稳定规格（docs/specs/）

- 只放**长期成立、跨版本仍要遵守**的契约/语法/行为规格；具体文件以目录为准，不在这里维护清单。
- 每份开头必须有 `状态：生效（YYYY-MM-DD）`；行为变更时同步改规格与实现，不允许「代码已改、规格仍是旧描述」。
- 一次性设计方案不要放这里，放 `docs/superpowers/specs/`。

### 计划与变更设计（docs/superpowers/）

- `plans/` 只放**进行中**的施工计划；`specs/` 只放**进行中**的变更设计。每份开头必须有：

  ```md
  > 状态：进行中（YYYY-MM-DD）
  > 现行事实源：docs/architecture.md#... / docs/adr/...
  ```

- 计划里的 `- [ ]` 只表示「尚未执行的步骤」；完成、废弃或不再维护时，在 30 天内处理：
  - 产出仍是长期事实 → 提炼进 `docs/specs/` 或 `docs/architecture.md`；
  - 否则 `git mv` 到 `docs/superpowers/archive/{plans,specs}/`，把状态改为 `已归档（原状态：已完成/已废弃，YYYY-MM-DD）`，正文冻结、不再编辑。
- **归档前先筛选**：只有仍有历史价值、独特理由或被拒方案的工件才进入 archive；纯施工记录、已被现行事实源覆盖且无独有内容的直接删除。归档一经提交即冻结。
- `reviews/`、`research/` 是历史记录，写完后不随代码更新；它们永远不是事实源。
- `archive/` 中的文件永久冻结：不编辑、不删除（除非涉及敏感信息）；新决策写新文档或在现行事实源里更新。

### 存量工件

- 存量 plans/specs 经价值筛选后，保留的已归档在 `docs/superpowers/archive/`，只作历史证据；照旧计划动工前必须先核对代码实况。
- `plans/`、`specs/` 下的 README 说明目录用途；不要把已完成的工件留在里面。

## 5. 评审清单

文档改动提交前自查：

- 这条事实的「家」是不是唯一？别处是否还有旧副本？
- 当前事实源里有没有混进历史/计划口吻？
- 根 AGENTS 是否仍在预算内？新增规则是否附了指针？
- 移动/重命名后，入链是否全部更新？`scripts/check-docs.sh` 是否通过？
- 过程工件是否有状态头？完成后是否已归档？
