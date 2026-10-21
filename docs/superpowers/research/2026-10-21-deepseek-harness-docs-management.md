# deepseek-harness 的文档与规划管理调研

> **重构后记（2026-10-21）**：本文的对照表记录的是文档分层改造前的状态。改造已落地：根 `AGENTS.md` 从 33,547 字符降到约 5,400 字符，稳定规格迁入 `docs/specs/`；历史 plans/specs 经价值筛选，保留 3 份于 `docs/superpowers/archive/`，其余 24 份删除。现行事实源见 [`docs/AGENTS.md`](../../AGENTS.md) 与 [ADR-0043](../../adr/0043-docs-layering-and-single-source-of-truth.md)。

- 调研对象：https://github.com/deepseek-ai/deepseek-harness（"Everything is a Plugin" 的 Cordis agent harness）
- 快照锚点：`master` @ `687f282e8625c6b8d11ff58211a90524be2e4317`（提交时间 2026-10-17T07:22:43Z）
- 调研日期：2026-10-21
- 取证方式：`git clone --depth 1` 因 github.com:443 连接超时失败，改用 GitHub REST API（`git/trees?recursive=1` 共 18,758 个路径、`contents`、`git/blobs`）读取；下文行号均指该快照下 API 返回的文件内容。未全量下载仓库。

> 结论先行：它的文档体系是「一层常驻规则（AGENTS.md）+ 一层机械校验（doc-sync）+ 一层有生命周期的决策记录（Agent Notes）」，规划不是独立文档，而是 Agent Note 的 `proposed/` 状态，落地时整篇改写成现在时。

## 1. 文档全景

### 1.1 目录结构（只列文档相关）

```
/
├── README.md / README.zh.md              77 行，只做入口索引
├── AGENTS.md                             185 行 / 2,015 词，常驻规则（CLAUDE.md 软链到它）
├── CONTRIBUTING.md                       23 行，明确暂不接受外部 PR
├── SAFETY.md / BRAND_GUIDELINES.md / THIRD_PARTY_NOTICES.md
├── docs/                                 243 篇英文 + 238 篇中文，合计 481 篇
│   ├── AGENTS.md                         77 行，文档标准（不双语）
│   ├── architecture.md                   165 行 / 2,403 词，改 packages/ 前必读
│   ├── subsystems/                       67 篇，均值 17.7 KB，含生成区 Cordis API
│   ├── cookbook/ 12 篇；cordis-tutorial/ 8 篇；cordis-api/ 6 篇
│   ├── defensive-patterns/ 6 篇（评审强制规则，正反例）
│   ├── postmortem/ 4 篇事故复盘 + README（编号 0001–0004）
│   ├── persistence-changes/              49 篇英文 + schema.json（按日期命名）
│   ├── upgrade-guide/v<version>/<item>/guide.md   6 个版本目录，42 篇英文
│   ├── user/                             20 篇，仅这部分发布到文档站
│   ├── i18n/ 5 篇（配对契约、术语、翻译规则）
│   └── 生成参考：persistence-catalog 355 KB、config-catalog 227 KB、module-graph 129 KB、
│       tool-catalog 104 KB、cli-help.md
├── .agents/notes/                        英文笔记 proposed 42 / implemented 293 / rejected 8 / archived 924
│   └── 每篇另有 .zh.md 与 .i18n.yaml（笔记目录内 1,268 篇中文、1,268 个 sidecar；全仓 sidecar 1,940）
├── .agents/skills/                       19 个技能，SKILL.md + references/ + templates/
├── .github/                              ISSUE_TEMPLATE、pull_request_template、issue-management、
│                                         review-ownership、25 个 workflow
└── packages/                             397 个包 README（英中各 397）
```

全仓 4,507 个 Markdown 文件（`.agents` 2,577、`packages` 828、`docs` 481、`snapshots` 276），文档体量与代码同量级。产品文档与项目管理文档的边界很清楚：`docs/user/**`、包 README、`website/` 投影是产品的一部分，对外发布；`AGENTS.md`、`docs/AGENTS.md`、`.agents/notes/**`、`.github/issue-management/**` 是内部协作文档，不随产品发布（站点白名单只收 21 页，全部来自 `docs/user/**` 与 `cordis-primer.md`，来源：website/docs.ts 的 source 条目）。

### 1.2 文档类别

| 类别 | 位置 | 命名 / 模板 | 性质 |
|---|---|---|---|
| 产品与用户文档 | `docs/user/**`、`README.md`、包 README | VitePress 路由 + `kind` frontmatter | 产品的一部分，公开 |
| 架构与参考 | `docs/architecture.md`、`docs/subsystems/**`、`docs/cordis-*` | kebab-case 主题名 | 当前状态事实源 |
| 生成参考 | `docs/{persistence,config,tool}-catalog.md`、`module-graph.md`、`cli-help.md` | 生成器写入的 region | 只读投影，`--check` 保鲜 |
| 流程与规范 | `docs/AGENTS.md`、`development.md`、`testing.md`、`cookbook/**`、`defensive-patterns/**` | 教程 / 参考二分 | 贡献者规则 |
| 决策记录 | `.agents/notes/{proposed,implemented,rejected,archived}/<class>/` | `yyyy-mm-dd-topic.md` + `Status:` 行 | 有生命周期，等价于 RFC/ADR |
| 事故复盘 | `docs/postmortem/NNNN-*.md` | 顺序编号 + Executive summary | 只记录失败与护栏 |
| 版本迁移 | `docs/upgrade-guide/v<version>/<item>/guide.md` | 版本目录 + item，`guide.md/zh/i18n.yaml` 三件套 | 按版本冻结 |
| 持久化历史 | `docs/persistence-changes/` | 日期 + `.schema.json` | 历史类型证据 |
| Agent 指令 | 根与 17 个子树 `AGENTS.md`、`.agents/skills/**/SKILL.md` | 分层，词数封顶 | 给 agent 的常驻规则 / 流程 |
| 治理 | `CONTRIBUTING.md`、`.github/{ISSUE_TEMPLATE,issue-management,review-ownership}` | Issue Type + Label + Project | 内部协作机制 |

不存在 ADR 目录、RFC 目录、ROADMAP、CHANGELOG、milestone 文件。决策记录统一叫 Agent Note，自我描述是 “effectively RFCs written by agents”（来源：.agents/notes/AGENTS.md:3）。

## 2. 规划机制

### 2.1 提案：Agent Note 的 proposed 状态

- 路径即状态：`{lifecycle}/{class}/yyyy-mm-dd-topic-title.md`，lifecycle 只有 `proposed/`、`implemented/`、`rejected/`（外加冻结的 `archived/`），class 限定 `feature|bug-fix|simplification|architecture|process|testing`；未知目录、深度不对、文件名缺日期都由 `walkAgentNoteTree()` 报错（来源：.agents/notes/README.md:9、scripts/agent-note-tree.ts:15-24）。
- 提案模板是强制的：`## Problem` 开头，随后 `## Proposal`、自由技术章节、`## Alternatives considered`、`## Acceptance criteria`、`## Risks`（来源：.agents/notes/README.md:80-91）。`verify-agent-note-format` 逐篇检查首行 `# Agent Note: <title>`、第 3 行 `Status:` 与目录一致、必需章节齐全（来源：scripts/verify-agent-note-format.ts:17-39、62-72）。
- `Status:` 行只允许三种形态：`proposed`、`implemented`、`rejected — <一句话理由>`；状态行不带日期与括号补充，日期在文件名、其余在 git（来源：.agents/notes/README.md:60-74）。把被拒理由放在状态行，是因为按该文件的说法，读者来看的就是 rejected 的结论（来源：.agents/notes/README.md:74）。
- 落地不是“另写一份计划”，而是同一文件迁移目录并整篇改写：`proposed/` → `implemented/` 要把 `## Proposal` 改成现在时的 `## Decision`，把 Acceptance criteria 与 Risks 折进 `## Consequences`，gate 不通过则迁移失败（来源：.agents/notes/README.md:119-121）。
- `implemented/` 里禁止出现 `## Proposal`、`## Plan`、`## Migration plan`、`## Acceptance criteria`——正是为了不让“计划口吻”残留成过期文档（来源：scripts/verify-agent-note-format.ts:36、.agents/notes/README.md:103）。
- 提案用条目式 Acceptance criteria，不用 checkbox；抽样笔记中 `- [ ]` 计数为 0（来源：.agents/notes/proposed/architecture/2026-09-10-session-capability-protocols.md:86-91）。

### 2.2 从提议到落地再到历史

- 判据：只有“代码、测试与现有文档都解释不了的持久决策理由”才写笔记；机械改动、局部 UI 改动豁免；已有笔记覆盖同一决策就更新它而不是新建（来源：.agents/notes/README.md:44-48）。
- 状态流转：implemented 保持与已发布现实同步（只改事实，不改决策）；被新决策取代时新建笔记并互链；完全吸收后才可合并删除（来源：.agents/notes/README.md:48-52）。rejected 只在“还能挡住一个诱人的错误”时保留，否则整组删除（来源：.agents/notes/README.md:14）。
- 归档：仅“已完结、理由不太可能再指导未来工作、但仍有历史价值”的 implemented 笔记进 `archived/`，且只能整组（英/中/sidecar）移动、插入 `Archived: YYYY-MM-DD`，之后永久冻结（来源：.agents/notes/README.md:38-42）。归档用哈希清单封存，`verify-archived-agent-notes --write` 只能追加（来源：scripts/verify-archived-agent-notes.ts:1-36、.agents/notes/archived/AGENTS.md:1-7）。

### 2.3 Issue / PR 侧的规划

- Issue 模板用 GitHub Issue Type 区分 Bug / Feature / Task（frontmatter `type:`），正文只有 Summary/Reproduction 或 Motivation/Behavior 或 Summary/Deliverables（来源：.github/ISSUE_TEMPLATE/bug.md:1-5、feature.md:1-5、task.md:1-5）。
- issue policy 要求非草稿人工 PR 至少引用一个同仓 Issue、恰好一个 `kind/*`、至少一个 `area/*`、至多一个 `p0`–`p3`；`Fixes/Closes/Resolves` 型引用还要求 PR 的 Priority 与 Issue 最高优先级一致（来源：.github/issue-management/README.md:27-35）。
- Project 状态机为 `Inbox → Backlog → Ready → In progress → In review → Done / No action`；PR opened/reopened 推到 `In progress`，请求评审推到 `In review`，changes-requested 退回 `In progress`（来源：.github/issue-management/config.json:1-19、README.md:41-48）。
- PR 模板固定 Motivation / Changes / Testing，每个测试项下挂 `<details><summary>Proof</summary>` 放可复核证据（来源：.github/pull_request_template.md:1-21）。
- 提交信息事实使用 Conventional Commits 形态（快照中可见 `feat(web):`、`fix(llm):`、`release(dsh):`），但仓库内没有 commitlint / commit-msg 钩子；强制点是 PR 标签与 Issue 引用，不是提交头（来源：提交列表、lefthook.yml:5-16）。
- 没有 roadmap 或 milestone 文档；路线信息只存在于 Issue/Project 与 `proposed/` 笔记中（来源：全仓路径清单中无 roadmap/milestone 文件）。
- 重要限制：公开仓 `has_issues=false`、`has_projects=false`，而配置指向另一个组织 `deepseek-harness/deepseek-harness`（来源：GitHub API repo 元数据、.github/issue-management/config.json:2-5）。因此这套 Issue/Project 流程在本快照里只能读到配置，无法观察实际运行。

## 3. Agent 协作约定

- 根 `AGENTS.md` 只有 185 行 / 2,015 词，定位是 “standing orders: rules an agent needs in context in every session, one to three lines each, linking its home”，上限 2,080 词（来源：docs/AGENTS.md:21、scripts/doc-budgets.manifest.json:2）。章节为：预发布 API、仓库布局、命令、约定（35 条）、防御模式、类型与文档、如何修改本文件、vendor 政策。
- 分层：17 个真实子树 `AGENTS.md`（`docs/`、`packages/`、`.github/`、`scripts/`、`snapshots/`、`vendor/`、`website/`、`.agents/notes/` 等；另有 10 份位于 snapshots 的测试夹具工作区，不属指令层），子树规则只写本子树事务，不重复根规则；子树预算 600 词，`packages/` 例外 810 词，`docs/AGENTS.md` 自身 1,430 词（来源：docs/AGENTS.md:22、59）。`CLAUDE.md` 是 git 软链（mode 120000，目标 `AGENTS.md`），不是副本（来源：git tree 元数据、AGENTS.md:181）。
- 技能层：19 个 `.agents/skills/*`，每个 `SKILL.md` 带 YAML `name/description` frontmatter，复杂技能再拆 `references/` 与 `templates/`；`.claude/skills` 软链到 `.agents/skills`（来源：.agents/skills/dsh-doc/SKILL.md:1-4、.claude/skills 符号链接元数据）。
- 文档技能可执行：`dsh-doc` 规定工作流、页面分类（tutorial/reference）、`kind` → 模板映射、事实核查（“Run every claimed operation against the current checkout”）、预算与站点发布（来源：.agents/skills/dsh-doc/SKILL.md:26-62、99-101）。
- 硬约束“文档随代码”：根规则写明 “Docs accompany every code change: update affected README and JSDoc contracts together”；文档标准规定改类型必须同改所属 subsystems 页；CI 的 `doc-sync` 兜底（来源：AGENTS.md:177、docs/AGENTS.md:44、.github/workflows/ci.yml:107）。
- 写作纪律：一段一个物理行（`verify-md-wrap`）；禁止隐喻；`Dev Note` 是唯一的“草稿区”，其余章节必须是打磨过的当前状态（来源：docs/AGENTS.md:42、.agents/skills/dsh-doc/SKILL.md:70）。
- 没有 `.cursorrules`，也没有每个目录都放 AGENTS.md；本地钩子故意很窄，只查翻译配对与归档笔记（来源：lefthook.yml:5-16、docs/development.md:145-150）。

## 4. 文档的事实源与生命周期

- 分层判据是一张表：“Each fact has one home: the tier whose job it is; elsewhere, link there.” 表里逐层写明“该层负责什么、不属于该层什么”，例如 architecture.md 不放类型定义、不放决策理由、不放实现状态注解；Agent Notes 不放迁移计划与验收清单（来源：docs/AGENTS.md:15-36）。
- 历史只允许待在指定层：commits/PR/Agent Note/postmortem/带范围的持久化记录；普通文档只写当前状态，禁止 “implemented!”“future: …” 之类的状态注解（来源：docs/AGENTS.md:40、61-73）。
- 冻结与解冻边界清楚：`archived/` 永久冻结、文档 gate 跳过归档源文件、但活跃文档仍可链入引用历史；`implemented/` 必须随代码改事实；决策反转必须新写一篇而不是改旧篇（来源：.agents/notes/README.md:42、.agents/notes/implemented/AGENTS.md:5-11）。
- 交叉引用是强制的且可检查：笔记之间必须用相对 Markdown 链接、禁止裸编号；`verify-md-links` 同时校验目标文件与 `#fragment` 对应的真实 heading slug 或 `<a id>`，覆盖 notes/docs/README/skills（来源：.agents/notes/README.md:17、scripts/verify-md-links.ts:1-33）。这与 android-ebook “ADR 不交叉引用”的取向相反。
- 单一状态权威页：`docs/session-format-status.md` 明确“其它文档链到这里而不是复述这些值”，把 writer 常量、已发布格式、兼容基线拆成三个来源（来源：docs/session-format-status.md:9-19）。
- 反例（自证“一个事实一个家”很难）：`docs/AGENTS.md:59` 写 architecture ≤ 2,400 词、testing ≤ 1,300 词、`examples/AGENTS.md` 310 词，而机器权威 `scripts/doc-budgets.manifest.json` 是 2,410 / 1,350，且 `examples/AGENTS.md` 在树中不存在；实测 architecture 2,403 词、testing 1,347 词。说明连他们自己也会让人读的数字漂移，真正生效的是 manifest。

## 5. 自动化与工程化

- `pnpm run doc-sync` 是文档门禁总入口，共 43 个 gate（来源：scripts/run-gates.ts:815-875）：
  - 结构：`verify-md-links`（文件+锚点）、`verify-md-wrap`（段落不硬换行）、`verify-doc-budgets`（词数上限）、`verify-repository-references`（拒绝真实 commit id 与不允许的组织 URL）。
  - 语义：`verify-agent-note-format` / `verify-agent-note-classification` / `verify-archived-agent-notes`、`verify-type-equiv`（粘贴的类型声明与源一致）、`verify-subsystem-pages`、`verify-export-jsdoc`、`verify-package-readme-{summaries,model-experience,limitations}`、`verify-upgrade-guides`。
  - 生成物保鲜：`verify-{config,tool,persistence,session-format}-catalog`、`verify-cli-help`、`verify-module-graph`、`verify-doc-graphs`、`verify-mermaid`；`doc-typecheck` 编译文档里的 `ts` 代码块。
  - 站点：`docs:build`（VitePress，兼做死链检查）。
- CI 每次 PR 跑 `pnpm run check:ci:static`，其中包含整组 `docSyncLeafGates` 与站点构建（来源：.github/workflows/ci.yml:107、scripts/run-gates.ts:487-502）；master 的 Windows 车道再构建一次站点（来源：.github/workflows/ci-master.yml:392-395）。
- 文档站发布是手动 `workflow_dispatch`，只允许从 `dsh-v*` tag 发布，故意不作为 PR check（来源：.github/workflows/docs-pages.yml:1-14、71-79）。
- 站点是“投影”而非第二份副本：`website/docs.ts` 是显式白名单（21 个 source，全部在 `docs/user/**` 与 `cordis-primer.md`），`scripts/project-doc-site.ts` 生成一次性 `website/.generated/`，禁止手改（来源：website/docs.ts:1-6、.agents/skills/dsh-doc/SKILL.md:99-101）。
- pre-commit 只做两件文档事：staged `*.i18n.yaml` 的配对校验、`archived/**` 的封存校验，外加 whitespace；测试/构建/文档全量都留给 CI（来源：lefthook.yml:5-25、docs/development.md:139-150）。
- 门禁本身也有测试：`scripts/doc-standard.spec.ts` 等与各 `verify-*.spec.ts` 一起在 `doc-quick` 里跑，避免“检查器悄悄失效”（来源：scripts/run-gates.ts:858-869）。
- 双语不是“翻译文件”，而是三件套 `foo.md + foo.zh.md + foo.i18n.yaml`，sidecar 按 heading 段落存两侧哈希；gate 只能证明“这些内容上确认过一致”，明确声明不能判断语义是否一致（来源：docs/i18n/README.md:9-22、42-44）。

## 6. 值得借鉴的具体做法

1. **决策记录带生命周期，计划落地即改写为现在时**（来源：.agents/notes/README.md:80-121、scripts/verify-agent-note-format.ts:30-39）。proposed 允许未来时与验收标准，implemented 只允许现在时事实，gate 拒绝 `Plan`/`Acceptance criteria` 残留。可直接借鉴：它从机制上消灭了“已完成的计划文档还以计划口吻存在”的问题。
2. **根 AGENTS.md 只放常驻规则，细节下沉到子树 AGENTS 与 skills**（来源：docs/AGENTS.md:19-34、AGENTS.md 全文 185 行）。并用词数预算（2,080）防止回涨，超预算的处理顺序是“搬迁 → 压缩 → 提高上限并说明理由”（来源：docs/AGENTS.md:49-59）。注意预算口径是空白分隔的英文词，中文仓库需换成字符数或汉字数，否则量不出真实体量。
3. **“一个事实一个家”配机械校验**：链接与锚点、粘贴类型、生成目录、README 摘要长度都有 gate（来源：scripts/run-gates.ts:815-875）。可直接借鉴其“最小集”：Markdown 链接/锚点检查 + 生成物 `--check` + 关键文件词数上限。
4. **单一状态权威页**：版本/格式状态只在一个页面维护，其它文档只链接（来源：docs/session-format-status.md:9-19）。适合 android-ebook 的 Room schema 版本、书源格式版本、release 状态。
5. **破坏性变更在引入当次写 upgrade guide**，按版本目录归档、两节固定（`## Change` / `## Migration`）、500 词上限、版本 bump 后冻结（来源：.agents/skills/dsh-create-upgrade-guide/SKILL.md:24-46）。这比“发版时回填 CHANGELOG”更不容易漏。
6. **把评审经验固化成 `defensive-patterns/`**：每条规则写“必须成立什么、评审看什么条件、例外谁批准、正例与反例”，并按改动面分页（来源：docs/defensive-patterns/README.md:3-20）。

依赖其“插件化产品”特性、不可直接照搬的部分：`kind` → 包 README 模板体系（`package-reference/bundle/library`）与 397 个包 README（来源：.agents/skills/dsh-doc/SKILL.md:51-60）；从源码生成的 Cordis API region 与 catalog（来源：docs/AGENTS.md:33）；英文/中文等权 + 哈希 sidecar（来源：docs/i18n/README.md:9-24）；Issue Type / ProjectV2 自动化（来源：.github/issue-management/README.md）；VitePress 站点投影（来源：website/docs.ts）。

## 与 android-ebook 的对照

| 维度 | deepseek-harness | android-ebook（本仓实测） | 判定 |
|---|---|---|---|
| 根指令 | `AGENTS.md` 185 行 / 18.7k 字符（budget 按英词 2,080 计） | `AGENTS.md` 414 行 / 33.5k 字符（中文，`wc -w` 口径不适用） | 需改造：先设字符数上限，再把操作流程下沉 |
| 决策记录 | Agent Notes 1,267 篇（英文），路径编码状态，模板由 gate 强制 | `docs/adr/` 42 篇 + ADR-FORMAT，单篇自足、禁止互引 | 可直接借鉴“状态字段/生命周期”，不必复制四层目录 |
| 交叉引用 | 强制相对链接，链接与锚点有 gate | 明确禁止 ADR 互相引用，靠自足描述 | 取向相反；本仓无链接/锚点检查，移动文件或改标题不会报错 |
| 计划文档 | 无 plans 目录；proposed 笔记落地即改写 | `docs/superpowers/archive/plans/` 19 篇，checkbox 明确不可信 | 可直接借鉴“落地即改写/归档”，治 checkbox 语义失效 |
| 历史与状态 | 普通文档只写当前状态，禁止状态注解 | AGENTS.md 允许在 plan 头部追加状态说明 | 需改造：把状态说明收进固定字段 |
| 自动化 | `doc-sync` 43 个 gate，PR 必跑 | 无 `.github`/CI 配置，只有 `scripts/commit-msg` 提交信息钩子（需 `install-hooks.sh` 安装） | 需改造：先做链接/锚点与字数的最小脚本 |
| 双语 | 英中等权 + sidecar 哈希 | 中文单语 | 不适用 |
| 术语表 | `docs/glossary.md`，每个词条可锚定 | `CONTEXT.md` 323 行，纯术语 | 可直接借鉴锚点化，便于文档引用 |
| 版本历史 | GitHub Releases + `docs/upgrade-guide/v*/` | `CHANGELOG.md` + `docs/versioning-practice.md` | 需改造：在 CHANGELOG 固定“升级注意”小节或新建 upgrade-guide |
| 事故复盘 | `docs/postmortem/NNNN-*` 4 篇，要求 Executive summary 与护栏 | 无 postmortem 目录；`docs/superpowers/reviews/` 仅 1 篇 | 可直接借鉴（低成本的 0001 起编号） |

可直接借鉴：Agent Note 的状态与改写规则、根 AGENTS 词数预算、单一状态权威页、upgrade guide 时点、defensive-patterns 的“规则+正反例”写法。

需改造：生命周期目录（本仓 ADR 已是既成事实，建议只加 `Status:`/`Superseded by` 字段与 `docs/adr/archived/`，不迁目录）；机械校验（无 CI，先做成 Gradle task 或独立脚本，挂到 `check` 或提交钩子）；子树 AGENTS 拆分（需先确认所用 agent 工具是否读取嵌套 AGENTS.md，本仓指南未涉及）。

不适用：包 README kind 体系、Cordis 生成目录、双语配对与哈希、Issue Type/ProjectV2 自动化、VitePress 站点投影。

## 未确认事项

- 未能浅克隆（github.com:443 超时），全部通过 GitHub API 读取；未逐篇阅读 243 篇 `docs/`，结构与模板遵守度靠抽样（4 篇 implemented、1 篇 proposed、1 篇 rejected、1 篇 archived）确认。
- 公开仓关闭了 Issues 与 Projects，Issue Type/Project 状态机只能从配置与 workflow 推断，未观察真实运行；内部是否另有规划工具（Linear、飞书等）仓库内无证据。
- 未核对其 25 个 workflow 的全部文档相关分支，只读了 `ci.yml`、`ci-master.yml`、`docs-pages.yml`。
- `docs/AGENTS.md` 与 `doc-budgets.manifest.json` 的数值漂移是否有意（如先写目标、后放宽）无法判断；只确认 gate 读 manifest。
- 提交信息事实符合 Conventional Commits（`feat(web):`、`fix(llm):`），但未找到 commitlint / commit-msg 钩子等强制手段，无法确认是否为硬约定。
- 未统计 `docs/` 总行数，只有文件数、字节数与抽样行数（architecture 165 行、AGENTS 77 行、subsystems 均值 17.7 KB）。
