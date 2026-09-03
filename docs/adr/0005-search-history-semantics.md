# 搜索历史语义收敛：纯展示全量、去子串过滤

搜索页的历史面板统一为「纯展示全部历史」：进入页、插入搜索记录、清除后均刷新**该类型全量**历史，面板不做按关键词子串过滤；清除操作清空该类型全部历史。

## 动机

- 迁移前的 DAO 用 `content = :content` 精确匹配查询历史，导致进入页 `querySearchHistory("")` 永远查不到记录、面板首开空白；TextWatcher 的"输入过滤"同样因为精确匹配而几乎无效。
- 迁移过程中曾把 SELECT/DELETE 临时改为 `LIKE '%'||content||'%'` 以让"空串=全量"，但也顺带引入了子串过滤能力。梳理产品语义后确认：历史面板的角色是展示全部 + 点选快捷搜索，不需要"按内容过滤历史"（输入只用于发起新搜索）。因此主动放弃子串匹配，避免留有一个无人使用、又需处理 `%`/`_` 通配符转义的模糊查询。

## 权衡

- **放弃子串过滤**：未来若有"输入时快捷补全历史"需求，需重新引入过滤（届时必须处理 `%`/`_` 通配符转义与转义一致性）。当前不做，以及时性换取 `getByType`/`clearByType` 的全量干净语义。
- **upsert 查重保留精确匹配**：`findByTypeAndContent(type, content)` 仍为精确匹配，纯粹用于"已存在则更新时间戳、否则新增"的去重，非历史展示查询，与全量展示语义正交。
- **清除范围**：`clearByType(type)` 清空该类型全部历史（而非按输入框内容子集删除），保证用户点「清除」的直觉与爆炸动画的标签范围一致。

## 下游影响

- `SearchHistoryDao`：新增 `getByType` / `clearByType`；`searchByTypeAndContent` / `deleteByTypeAndContent` 移除 `LIKE` 过滤参数。
- `SearchHistoryRepository` / `SearchViewModel`：`querySearchHistory` / `cleanSearchHistory` 不再携带 content 参数，恒取该类型全量。
- `SearchActivity`：`onQueryChange` 不再触发历史查询；`onClean` 清空全部；清空后保留空面板标题行，清除按钮随列表为空自动隐藏。
- `SearchHistoryDaoTest`：锁定全量查询、全量清除、精确 upsert 查重与类型隔离。