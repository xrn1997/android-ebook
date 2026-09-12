# 缓存管理页：cacheDir 三档可清、书籍内容不计入不删只单列呈现

缓存管理页原先只统计 `cacheDir`，而真正的大头是藏书（一本 2000 章约 6 MB，几本就压过整个缓存），用户看到的「缓存总占用 12 MB」与系统报告的「应用占用几百 MB」差一个量级。决定在本页把书籍内容**单列一行呈现**：不计入可清理总量、本页也不提供删除入口，测量归内容仓库 `BookStore.storageUsage()` 一次遍历给出，递归求和收成共用的 `File.treeSize()`。页面标题「缓存总占用」与清理确认的逐项列表本就把书籍排除在外——这不是页面说了假话，而是少给了一个用户真正想知道的数字，修法是补信息不是改措辞。

## 决策

1. **书籍行不参与清理，可点去书架管理**。不用共享列表项组件 `CommonListItem`：它把 `onClick` 定为必传，而这里存在「书架路由取不到 → 只展示不可点」的形态（独立运行时 `module_main` 不在依赖图里），硬套就会留下一个点了没反应的假入口；为「可选 onClick」去扩一个全仓共用的组件接口等于为一个调用点摊薄所有人，比在本页手写 30 行更浅。故按本页明细行的同款手写 `Row`，配色取 `surfaceVariant`/`onSurfaceVariant` 中性语义色，仅在可点时补一个右向箭头作可供性提示。独立运行时先 `matchRouteMap` 探测，取不到就退化为纯展示 + 指向书架的说明文案。
2. **不计入可清理总量**。`CacheUiState` 两组字段分开：`totalText`/`totalBytes` 只含 `cacheDir`（驱动「清理全部」按钮的可用态与确认弹窗），书籍侧是 `booksSizeText` + `bookCount` 单独一栏。混在一起会让用户以为一键清理能删掉藏书，也会让按钮因为"反正有数字"而常年可点。
3. **测量归所有者，且每个统计只走一遍树**。`BookStore.storageUsage()` 一次遍历同时给出 `bytes`（全部章文件，含 `.tmp` 与散落文件——它们确实占磁盘）与 `bookCount`（只数书目目录，半成品不算一本，否则缓存页会报出书架上不存在的册数）；`CacheManageViewModel` 注入 `BookStore` 只取这一个结果，不碰目录形状。分两个方法也能跑，但缓存页每次刷新都要这两个数，分开就是把整棵章文件树走两遍——几十本书 × 数千章文件时是能感知的 IO，故合并。同一轮里缓存侧也改成一次分档遍历（原来是「总量减图片与临时」的差值法，图片目录被走两遍，还要为并发写下的负差值钳位；分档累加后总量恒等于三档之和）。
4. **页面职责边界写进代码**：`CacheManageViewModel` 的类 KDoc 与页面注释都说明「书籍内容不是缓存，本页不删它」，避免下一个人顺手把它并进清理逻辑。删书的既有唯一入口是书架长按，且它连着内容仓库与书架记录的对账，在设置页另开一处只做一半（或做全套）都不划算。

## 权衡

- **跳转用现成的路由，不新增跨模块约定**：`MainActivity` 既没声明 `launchMode` 也不处理 intent extra，而它的 `startDestination` 就是书架，所以 `FLAG_ACTIVITY_CLEAR_TOP` 会重建它并落回书架，不需要任何「选哪个 Tab」的新约定。代价是本页与设置页一并出栈：从「去管理我的书」的语义看可接受。
- **书籍内容只读呈现，不就地删除、不另开「存储」页**：就地删除会在书架长按删除之外造出第二个删书入口（两处口径迟早不一致）；当下只有「缓存 + 书籍」两个租户，为一个数字新开一页属过度设计。

## 下游影响

- `lib_book_common`：新增 `File.treeSize()` 与 `BookStore.storageUsage()`（返回 `StorageUsage(bytes, bookCount)`）；`CacheModel` 删掉私有的递归求和改用 `treeSize`，且 `cacheBreakdown` 改为一次分档遍历——「占用」从此一份算法、一遍树。由 `TreeSizeTest`（3 例，纯 JVM 临时目录）与 `BookStoreTest` 新增 3 例（一次遍历给出字节与册数、空仓库归零、目录不存在不抛）锁住。`File.treeSize()` 是域无关件，真正的家是外部库 `lib_common`，等联动窗口上移。
- `module_me`：`CacheManageViewModel` 多注入 `BookStore`，`CacheUiState` 多 `booksSizeText` 与 `bookCount`；页面多一张「书籍内容」卡，值显示为「占用 · N 本」，文案四条（标题 / 值格式 / 可点版说明 / 无路由版说明）。由 `CacheManageViewModelTest`（3 例，Robolectric + 双临时目录）锁住：书籍数字单独呈现、不进可清理总量、`clearAll()` 后书籍文件字节数不变、空仓库显示 `0 B` 而不是空占位。测试等真实 IO 线程用 `awaitUntil` 轮询，不靠固定 sleep——`CacheModel`/`BookStore` 的遍历跑在 `Dispatchers.IO` 上，不受虚拟时钟控制。跳转在 Activity 侧（探测 + `CLEAR_TOP`），属视图层无 JVM 覆盖。
