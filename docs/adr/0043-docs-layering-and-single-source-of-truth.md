# 文档分层：常驻规则、当前事实源与过程工件

Status: superseded by 0044

2026-10-21

> 历史注记：本篇中的 `docs/superpowers/` 路径已随 ADR-0044 删除；现状见 ADR-0044 与 `docs/AGENTS.md`。

根 `AGENTS.md` 从首版到 2026-10-02 行数只增 7.5%，字符数从 17,298 涨到 33,547（+94%），同时承担项目介绍、架构、开发手册、提交规范、领域索引与 ADR 规则六种角色。过程工件没有生命周期，同一事实在多处复述。本决策把文档按职责分层，并用最小机械检查防止回涨。

## 背景

- 根 AGENTS 的四个大章节占 80.7% 字符；每次会话都为全部细节付费。
- 19 篇 plan 中 18 篇仍有 `- [ ]`，16 篇靠手写「状态说明」；脚本书源语法规格被指定为事实源，却放在「一次性工件」目录且自称「草案」；两份历史文档留在 `docs/` 根目录。
- 仓库没有 CI，文档规则只靠 AGENTS 里的句子和人的记忆；文件移动会静默断链。
- 参考仓 deepseek-harness 用「根 AGENTS 只放 standing orders、细节下沉、文档带生命周期、门禁机械校验」维持了 4,507 篇 Markdown 的可读性，但其 43 个 gate、双语三件套与 Agent Notes 四层生命周期依赖其产品形态与 CI，不能直接照搬。

## 决策

1. **分层**：根 `AGENTS.md` 只放常驻规则；当前架构、开发手册、提交规范分别落在 `docs/architecture.md`、`docs/development.md`、`CONTRIBUTING.md`；决策在 `docs/adr/`；稳定契约在 `docs/specs/`；一次性计划/设计在 `docs/superpowers/{plans,specs}/`；历史工件在 `docs/superpowers/archive/`。
2. **一个事实一个家**：当前事实源只写现在时；历史只进 ADR、CHANGELOG、archive 或 git；别处只链接，不复述。
3. **根 AGENTS 预算 ≤ 8,000 字符**（`wc -m`）。超限按「搬迁 → 压缩 → 提额并说明」处理，提额必须在提交信息里写理由。
4. **过程工件有生命周期**：`plans/`、`specs/` 只放进行中；完成后长期成立的部分提炼为稳定事实源，否则移入 `archive/` 并冻结；`- [ ]` 只在进行中目录有待办语义。
5. **机械检查**：`scripts/check-docs.sh` 检查 AGENTS 预算、当前文档的相对链接、plans/specs/archive 的状态头。仓库无 CI，提交前手动运行。
6. **存量处理**：2026-10-21 之前的 plans/specs 与两份 docs 根目录历史文档先统一迁入 `archive/` 并加状态头，再在提交前按价值筛选：保留仍有历史价值的 3 份，纯施工记录、已被现行事实源覆盖且无独有内容的 24 份删除；正文不重写，照旧计划动工前先核对代码。进入 archive 的工件提交后冻结。

## 被拒方案

- **继续单文件 AGENTS**：每次会话都付费，且没有机制阻止继续增长；拆分后根文件仍保留全部硬约束，只把操作细节移到指针后面。
- **照搬参考仓的 Agent Notes 与 43 个 gate**：本仓约 100 篇文档、单人维护、无 CI，维护成本高于收益；保留 ADR 体系，只取最小的三项检查。
- **全量重写归档计划**：计划正文含历史施工细节与当时的取舍，重写会丢失证据；只加状态头并冻结，历史价值保留。
- **给 ADR 加 `Superseded by` 链接**：与本仓「ADR 单篇自足、正文禁互引」的规则冲突；取代关系由 git 与现行事实源表达。
- **一次移动所有文档并重写全部链接**：链接数量大、且归档文件中的相对路径会随目录深度变化；采用「稳定规格迁出、历史工件归档、归档不参与链接检查」的边界，降低一次性风险。

## 下游影响

- 新增 `docs/AGENTS.md`、`docs/architecture.md`、`docs/development.md`、`CONTRIBUTING.md`；`README.md` 保留原内容并补新文档指针。
- 根 `AGENTS.md` 从 33,547 字符降到约 5,400 字符；四类大章节按域迁入架构/开发/贡献文档。
- 稳定规格迁入 `docs/specs/`；历史 plans/specs 与两份 docs 根目录文档经价值筛选后，保留 3 份（多源路线图、登录旧规格、JS 沙箱计划）迁入 `docs/superpowers/archive/`，其余 24 份删除。
- 代码 KDoc 与文档内链同步更新；`scripts/check-docs.sh` 成为文档改动的检查入口。
- 新增文档不改变任何运行时代码；验证止于文档检查与链接检查，不涉及装机。

锚点：`docs/AGENTS.md`；`docs/adr/ADR-FORMAT.md`；`ADR-0044`；`scripts/check-docs.sh`
