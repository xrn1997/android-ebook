# 文档骨架、ADR 格式与文档门收口到一份规范

Status: accepted

本仓文档的骨架、ADR 格式与文档门收口到 `docs/AGENTS.md`、`docs/adr/ADR-FORMAT.md` 与 `scripts/check-docs.sh` 三处：核心文件固定、每篇 ADR 带 `Status` 与 `锚点：`、索引与文件一一对应，由脚本机械检查。本仓保留 `docs/specs/` 稳定规格层；既有 `docs/superpowers/` 已删除，长期成立的部分在 ADR、architecture 或 specs，旧版本留在 git 历史。原 `ADR-0043` 的分层结论保留，其 ADR 格式与过程工件条款由本篇取代。

## 背景

ADR-0043 把文档按职责分了层，但格式与生命周期仍靠散落的约定：ADR 没有统一索引、状态与锚点写法，AGENTS 预算口径不一致，过程文档归属不明确。规则没有机械门，文件移动会静默断链，同一事实容易在多个家里复述。这套规范要求可机械校验：固定核心文件、每篇 ADR 的 `Status` 与 `锚点：`、单一索引，以及检查这些项的门脚本。

## 决定

1. **核心骨架统一**：`AGENTS.md`（常驻规则）、`README.md`（用户事实）、`CONTEXT.md`（术语 + `_Avoid_`，新/改词条点明实现符号）、`docs/AGENTS.md`（文档规范）、`docs/architecture.md`（当前形状与主人清单）、`docs/development.md`（构建、门、发版）、`CONTRIBUTING.md`（提交与发版边界）、`CHANGELOG.md`（发布历史）、`docs/adr/README.md` + `docs/adr/ADR-FORMAT.md`（决策与索引）。
2. **ADR 格式统一**：每篇紧跟标题写 `Status: accepted` 或 `Status: superseded by NNNN`；每篇在索引恰好出现一次；文末必须有 `锚点：`；正文只写当前真相，日期留在 git；互引允许但开篇自足；整篇翻案新写一篇并标旧篇。
3. **文档门统一**：`bash scripts/check-docs.sh` 继续检查根 AGENTS 字符预算（≤ 8,000）、相对链接、已废章节引用；增加 ADR 索引与文件一一对应、ADR `Status`、`锚点：`、核心文件在场与过程归档目录不存在的检查。仓库无 CI，提交前手动跑；判据仍住脚本，不写死在散文里。
4. **根 AGENTS 只放常驻规则**：文档分层、放置判据、写作规则与生命周期移到 `docs/AGENTS.md`；根文件保留先读表、硬约束、门与文档指针。
5. **稳定规格层保留**：`docs/specs/` 只放长期成立、跨版本仍要遵守的外部契约与语法/行为规格；代码或测试已经是唯一主人的契约不再另写规格。新增规格必须有 `状态：生效（YYYY-MM-DD）` 头。
6. **过程工件**：计划、审查、调研、一次性脚本只住本机 `.superpowers/`；仓库不设 `docs/superpowers/`、plans/specs/archive。需要长期保留的结论提炼进 ADR、architecture 或 specs；旧版本留在 git 历史。
7. **迁移**：`docs/AGENTS.md` 与 `docs/adr/ADR-FORMAT.md` 对齐统一骨架；存量 ADR 补 `Status` 与 `锚点：`；新增 `docs/adr/README.md` 索引；`ADR-0043` 标为被本篇取代；删除 `docs/superpowers/` 及其全部文件；源码 KDoc 里的旧入链删除，文档里的入链改指 ADR 或现行事实源。

## 被否方案

- **只补判据、不固定骨架与门**：ADR-0043 已经证明，只靠散文判据，格式、预算与文档门会继续分叉；判据本身也需要一个检查入口才能长期成立。
- **新建独立标准仓，本仓引用同一份正文与检查器**：AGENTS.md 的约定是离被改文件最近的指令生效，外部标准仓不在 agent 的 checkout 里；本仓规模也撑不起额外仓库的版本维护。检查器以后可以抽成共享包，规范正文必须留在仓内。
- **继续把文档纪律放在根 AGENTS**：根文件已经承担先读表、硬约束与门；再装分层、生命周期与 ADR 格式会让每次会话都为细节付费，且没有单一入口回答「这条内容该放哪」。
- **照搬大体量多 agent 项目的文档机器（多层目录 + 整套门）**：那套机器服务于大量文档与多人协作；本仓 ADR 体量小、单人维护，抄目录只会多出空壳与维护成本。只取常驻规则、单一文档规范、决策生命周期与最小机械门。
- **把存量过程归档冻结保留**：让 `main` 继续背一份第二真相；需要长期保留的结论应提炼进 ADR/specs，旧版本留在 git 历史，不留在仓库。

## 后果

- 文档多了一层 `docs/AGENTS.md` 与 `ADR-FORMAT.md`，但每份文件只回答一类问题；根 AGENTS 变短，细节可按需打开。
- 存量 ADR 需要维护 `Status`、索引与 `锚点：`；`check-docs.sh` 会把漏项、断链、悬空 `§N` 与预算超限一次抓住。
- 稳定规格与代码契约的分工更明确：`docs/specs/` 仍只在外部契约没有代码主人时存在；代码里的 DTO 与 Room 实体不另写规格。
- `docs/superpowers/` 已删除；旧版本与当时的过程证据在 git 历史里。`ADR-0043` 仍可回查当时的取舍；本仓当前文档规范以本篇与 `docs/AGENTS.md` 为准。

锚点：`docs/AGENTS.md`；`docs/adr/ADR-FORMAT.md`；`docs/adr/README.md`；`scripts/check-docs.sh`；`AGENTS.md`；`docs/development.md`；`docs/specs/`；`ADR-0043`。
