# 进行中的变更设计

本目录只放**正在进行**的变更设计（一次性设计方案）。设计不是事实源；实现完成后，长期成立的部分提炼进 [`docs/specs/`](../../specs/) 或 [`docs/architecture.md`](../../architecture.md)，其余移入 [`../archive/specs/`](../archive/specs/)。

每份设计开头必须有：

```md
> 状态：进行中（YYYY-MM-DD）
> 现行事实源：docs/architecture.md 的「相关章节」/ docs/adr/NNNN-*.md
```

当前进行中的设计以本目录下的文件为准，不在这里维护清单。

规则：

- 稳定契约、语法/行为规格不要放这里，放 [`docs/specs/`](../../specs/)。
- 完成后按 [`docs/AGENTS.md`](../../AGENTS.md) 的生命周期处理；归档后正文冻结。
- 存量设计已统一归档在 [`../archive/specs/`](../archive/specs/)。
