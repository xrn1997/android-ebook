# 取源建条目收单主：ShelfEntryFetcher 与「新条目必带 book_info」守卫

Status: accepted

「从书源解析出一条书架条目」原先在加书架与换源两处各自拼同一条链，而「新条目必带 book_info」的守卫只写在换源那一半——加书架遇到 parser 脏返回会落一条半截条目，随即被读路径当孤立记录清掉，用户丢书。决定把这条链收进单一主人 `ShelfEntryFetcher`，守卫内置且置于任何写之前，两处调用共用它；四表落库仍归 `ShelfEntryStore` 的同一笔写事务，不动。

## 背景

加书架（`BookShelfManager.addFromSearch`）与阅读中换源（`BookRepository.switchSource`）都要按书的 `tag` 取 parser、`getBookInfo` 再 `getChapterList`，两者逐字重复这条链。守卫「新条目必须自带 `book_info`」只存在于换源：换源紧接着会删旧条目的全部行，少这一行就是往库里塞一条「有书架、无书籍信息」的孤立行，`getAllBooksWithDetails` 每次启动把它当垃圾删掉。加书架路径没有这道守卫，同一份脏返回会写进半截条目，读路径同样把它清掉——用户刚加的书凭空消失。守卫分居两半正是缺陷成因：改一处守卫时另一处不会跟着变。

## 决定

1. **取源建条目收进单一主人 `ShelfEntryFetcher`**：按 `tag` 取 parser（`parserOrThrow` 四档翻译）→ stub（只用 `noteUrl` + `tag` 起头）→ `getBookInfo` → `getChapterList` → 守卫。加书架与换源都调它，构造方式不分叉（各自用已有的 `SourceResolver` 内部组装，不新增构造依赖），换源拉回来的书与「从搜索加书架」拉回来的一致。
2. **「新条目必带 book_info」守卫内置在 `fetch` 里、置于任何写之前**：`checkNotNull(data.bookInfo)`，失败抛 `IllegalStateException`。守卫失败 = 一行不写——脏条目根本到不了落库。换源特有的进度映射经 `carryOver` 回调在守卫**通过之后**才跑，不会在脏条目上算进度。守卫消息措辞**中性**（不含「换源」或「加书架」字样）：失败经 `Result.failure` 上抛，加书架路径会拿 `e.message` 经 `reportAddBookFailure` → `reportFailure` 直接上屏（toast），换源字样出现在加书架流程里是误导，故两路共用同一句。
3. **写事务不动**：`ShelfEntryFetcher` 只解析出合法条目、不落库；四表写入、事务边界与提交后发事件仍归 `ShelfEntryStore`（经 `BookRepository.addToShelf` / `switchSource`）。守卫挡在写之前，与「一笔写事务」互不替代——前者保证不写脏条目，后者保证写了的条目四表原子可见。

理由：守卫只有一份，改它两处同时生效；解析链只有一份，加书架与换源的行为不可能再分叉。取消（`CancellationException`）本类不吞，原样穿透到调用方各自的 catch（取消不是「加书架/换源失败」）。

## 边界

- `ShelfEntryFetcher` 不落库、不发事件——那是 `ShelfEntryStore` 的职责（一个书架条目由哪些行组成、何时提交、何时发事件）。两者一个管「解析出合法条目」、一个管「合法条目落库」。
- 四表写事务、事件在提交后发，语义不变（见 `docs/architecture.md` §4.1）。
- 阅读器/详情页等直接拿 `BookShelfEntity` 调 `addToShelf` 的路径不经 `ShelfEntryFetcher`（它们不经搜索/换源解析），守卫只守「取源建条目」这一条链。

## 后果

- 加书架从此与换源一样，parser 脏返回（没解出 `book_info`）即失败且一行不写；`BookShelfManagerTest` 新增用例钉住「失败 + 四表零行」。
- 换源行为逐字不变：`BookRepositorySwitchSourceTest` 全部断言零改动通过（守卫、进度映射、事务、事件）。
- 两处调用方各自的 catch 保留（`Result.failure` 转换、`CancellationException` 原样上抛）；守卫抛的 `IllegalStateException` 与源失效的 `BookSourceNotFoundException` 各自成类，调用方按类型处置。

## 被否方案

- **两个调用点各自补一份守卫**：被否。守卫分两半正是本次缺陷的成因——改一处另一处不跟着变，下次再加第三条「取源建条目」路径又得记得补第三份。收成单一主人后，守卫只有一处，新增调用方天然带上它。
- **把守卫下沉进 `ShelfEntryStore.writeEntry`**：被否。那会让守卫离「取源」太远：`addToShelf` 的调用方不止取源路径（阅读器/详情页直接交条目），在那里加守卫会误伤本就合法的直交条目；且守卫语义是「取源必须解出 book_info」，属解析正确性，不是落库正确性。

锚点：`lib_book_common` 的 `ShelfEntryFetcher`（`fetch` 与内置守卫）、`BookShelfManager.addFromSearch`、`BookRepository.switchSource`（`fetchEntryFromSource` 的 `carryOver`）/ `ShelfEntryStore`（四表写事务）；`BookShelfManagerTest` 的 `加书架时源没解出书籍信息则失败且一行都不写` 与两条「取消原样上抛」用例、`BookRepositorySwitchSourceTest`（守卫/映射/事务/事件断言零改动）；`docs/architecture.md`「数据与存储」。
