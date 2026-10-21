# 进行中的施工计划

本目录只放**正在进行**的施工计划。计划不是事实源；完成、废弃或不再维护后，在 30 天内移入 [`../archive/plans/`](../archive/plans/)。

每份计划开头必须有：

```md
> 状态：进行中（YYYY-MM-DD）
> 现行事实源：docs/architecture.md 的「相关章节」/ docs/adr/NNNN-*.md
```

规则：

- `- [ ]` 只表示尚未执行的步骤；归档后不再有「待办」语义。
- 完成时：长期事实提炼进 [`docs/specs/`](../../specs/) 或 [`docs/architecture.md`](../../architecture.md)；否则 `git mv` 到归档目录，把状态改为 `已归档（原状态：已完成/已废弃，YYYY-MM-DD）`，正文冻结。
- 存量计划已统一归档在 [`../archive/plans/`](../archive/plans/)；照旧计划动工前先核对代码实况。
- 文档分层与生命周期见 [`docs/AGENTS.md`](../../AGENTS.md)。
