# 分页游标与信号时序收进 PageSession 单主

Status: accepted

两个翻页宿主（书评区 `BookCommentsViewModel`、分类选书 `ChoiceBookViewModel`）原先各在 ViewModel 裸字段里养一套分页状态机，「先停刷新再同步 hasMore」这类信号顺序靠调用点上的注释与记性维持。决定把游标、在途闸门与信号顺序收进 `lib_book_common` 的纯 JVM 会话 `PageSession`：宿主只把自己的「到底」判据算成 boolean 交进去，再按序 apply 会话返回的信号列表——与 [ADR-0048](0048-single-owner-derivation-extraction.md) 的「派生口径单一所有者化」同族。

## 背景

- 书评区宿主持有 `nextPage`/`hasMoreData`/`loadMoreInProgress` 三个裸字段，分类页宿主持有 `page` 字段；「触底连发只放行一笔」「到底后不再发请求」这些闸门语义各写一份，新增第三个翻页宿主要么再抄一份、要么抄漏。
- 信号顺序是一条**没写进类型**的约束：基类刷新状态机在收到刷新结束信号时会自动把 hasMore 复位为 true，显式「到底」结论必须排在 StopRefresh 之后才能覆盖复位值。书评区靠一段注释（「先结束刷新再同步 hasMore」）维持这个顺序——这是修过的真实 bug，顺序一反，到底结论就被复位吞掉，列表继续触底发请求。
- 「到底」判据与状态机织在一起：判据本身（服务端 `hasMore` / `mergeBookPage` 是否非 null）是域知识，但游标推进、在途闸门与信号先后不是，它们被裹在各自的 ViewModel 里测不到（需要 Robolectric + 假仓库才能凑出接线用例）。

## 决定

1. **`PageSession(startPage)` 收下游标、在途闸门与到底置位**（`lib_book_common`，纯 Kotlin/JVM，无 Android 依赖）：`startRefresh()` 重置游标到起始页并清在途（刷新永远放行）；`requestLoadMore()` 在途或已到底时返回 null；`onLoadMoreSuccess`/`onRefreshResult` 推进游标、按调用方交入的 boolean 置到底；`onLoadMoreFailure()` 释放闸门、游标不动（失败页原样重试）。
2. **信号顺序由会话的返回值给出**：`onRefreshResult` 返回 `[StopRefresh, HasMore]`——先停刷新再同步结论，顺序承重（基类复位盖结论，见「背景」第二条），`PageSessionTest` 把它锁死；`onLoadMoreSuccess` 返回 `[HasMore, StopLoadMore(true)]`；`onLoadMoreFailure` 返回 `[StopLoadMore(false)]`。信号种类只有 `StopRefresh`/`HasMore`/`StopLoadMore` 三种，宿主按序 `when` 转发到既有的 `updateStopRefresh`/`updateHasMoreData`/`updateStopLoadMore`，不新增第五种。
3. **「到底」判据留在宿主**：书评区传服务端 `page.hasMore`；分类页传 `mergeBookPage(list, page) != null`（「本页无新条目 = 到底」，越界页以 HTTP 200 重复返回首页书目，只看空页判不到底）。两个纯合并函数（`mergeCommentPage`/`mergeBookPage`）原地不动，会话不碰列表内容。
4. **宿主删裸字段**：书评区 `PageSession(startPage = 2)`（首页 1 走刷新，会话不管取数），分类页 `PageSession(startPage = 1)`（刷新即首页）。init 里自置 page 的一行一并删除——刷新路径经 `startRefresh()` 复位游标。

## 边界

- 刷新的取数与首页码仍归宿主：`startRefresh()` 的返回值只是「会话游标已复位到哪」，书评区刷新固定请求第 1 页、不消费该返回值。
- 列表合并、去重、排序仍是宿主的域知识（两个 merge 函数），会话只收 boolean、只管时序；「到底判据」不进会话。
- 失败路径的用户可见提示（`reportFailure`、书源失效文案）仍是宿主的事，会话只管闸门与游标。

## 下游影响

- `BookCommentsViewModel` 与 `ChoiceBookViewModel` 改为持有 `PageSession`，各删 1~3 个裸分页字段；`mergeCommentPage`/`mergeBookPage` 与其测试零改动。
- 新增 `PageSessionTest`（11 例，纯 JVM）锁协议本身；两宿主各一条接线用例（`BookCommentsViewModelTest`、`ChoiceBookViewModelTest`，Robolectric + 假仓库）。
- 将来的第三个翻页宿主（如「我的评论」接入分页）直接持会话，不再自养状态机。

## 被否方案

- **留在宿主、只补接线测试**：被否。顺序约束依旧只活在注释里，新宿主没有编译期或类型层面的提醒；且判据裹在 ViewModel 里，测它必须搭 Robolectric + 假仓库全套，纯 JVM 测不到（同 [ADR-0048](0048-single-owner-derivation-extraction.md) 拒绝「留在宿主只补测试」的理由）。
- **把顺序做进基类（改 lib_common 的刷新状态机）**：被否。lib_common 是跨仓依赖（android-practice），改动窗口与本仓不同步；且基类信号是通用通道，把「谁先谁后」焊死在基类会让其它宿主失去按自己状态机排序的余地。顺序收在会话这一层，恰好只约束本仓这两个宿主。
- **会话直接持有列表并合并**：被否。合并判据是域知识（评论按服务端 hasMore、分类按 noteUrl 去重），塞进通用会话会把两种口径搅成一个「策略枚举」，判据分叉的风险换个地方复发。

锚点：`lib_book_common` 的 `PageSession`（游标/闸门/信号顺序的唯一主人）、`PageSignal`（三种信号的 sealed interface）、`PageSessionTest`（顺序锁 `[StopRefresh, HasMore]` 的读数）；`BookCommentsViewModel.pageSession`（startPage=2）、`ChoiceBookViewModel.pageSession`（startPage=1）、`BookCommentsViewModelTest`（刷新后触底取第 2 页）、`ChoiceBookViewModelTest`（连点触底只发一笔请求）；[ADR-0048](0048-single-owner-derivation-extraction.md)（同族先例）。
