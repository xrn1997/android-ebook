# 书库缓存：策略上收仓库层，cacheDir 文件缓存 + TTL + SWR + 下拉强刷

书城数据缓存曾有三个互为表里的缺陷：下拉刷新被解析器内的缓存命中吞掉（转一圈拿回同一份，刷新是假的）、SharedPreferences 缓存永不过期（写入即永久定格）、SP 文件落在缓存管理页的 cacheDir 口径之外（用户永远清不掉）。决定把缓存策略整体从解析器上收到仓库层（`BookSourceRepository`），载体换成 cacheDir 下的文件缓存（`lib_book_common` 的 `LibraryDiskCache`），语义分两档：进页/换源走 stale-while-revalidate（缓存秒开、过期再补一次网络重抓），下拉刷新强制走网络并覆盖回写。

## 动机

三个缺陷同源于一个架构错位：**缓存读写长在解析器里**（`BookParser.getLibraryData(aCache, sourceUrl)`），而「要不要用缓存」明明是编排层的事。策略与执行挤在一层的结果是没人说得清「刷新」与「缓存命中」谁该赢——赢的是缓存，于是刷新成了假的。

还有一条测试裂缝助它存活：`LibraryViewModelTest` 的假 parser 从不写缓存，「下拉刷新会重拉」在测试里恒真、真机上却被缓存吞掉。缓存路径不进测试，这类缺陷单测永远抓不住。

「书库一次加载是每源 N 个分类页的串行请求、耗时数秒」是选 SWR 的现实约束：白屏等网络不可取，但也不能永远只吃缓存——内容（新书上榜/排序）以天为粒度变化，缓存需要一个过期机制。

## 决策

1. **解析器纯净化**：`getLibraryData(aCache, sourceUrl)` 改为零参的 `fetchLibraryData()`——解析器只做「逐分类抓首页 + 拼装」，不知道缓存的存在；顺带删除主代码零调用的 `analyzeLibraryData`。零参是因为旧参数唯一的用途就是生成缓存 key，parser 手上本就有自己的 `rule`。
2. **策略归仓库，API 用 Flow 承载双发射**：`getLibraryData(sourceUrl, policy): Flow<LibraryEntity>`，`LibraryLoadPolicy` 两档——`StaleWhileRevalidate`（先发缓存、新鲜即止、过期则在**同一次收集内**接着重抓再发一次——收集被取消，重抓一并中止，不设脱离页面生命周期的后台任务）与 `ForceNetwork`（跳过缓存读、成功后覆盖回写）。下拉手势的语义契约就是「给我最新的」，命中缓存直接返回等于刷新是假的。返回 Flow 而非单值，正是为 SWR 的「先旧屏秒开、新屏顶上」留形状，调用方逐发射上屏即可。
3. **取 parser 先于读缓存，坏源不 emit 缓存**：书源坏没坏（`BookSourceNotFoundException`）要在每次进页时检出，不能被一份新鲜缓存掩盖——页面据此说「换源/重导」那句话。检出后**直接抛、不先发缓存**：书城页在非 Ready 档位整片换引导语、不渲染列表，先发缓存既不可见，还会让档位先闪 Ready 再落回失效。
4. **载体换 cacheDir 文件缓存**：`LibraryDiskCache(dir: File)`（构造只收目录，`Context → File` 转换只在 DI 装配点发生一次，类本身纯 JVM 可测），文件名取 `libraryCacheKey(sourceUrl)` 的 MD5——key 生成仍唯一收在那一个函数（读写各拼一次就会漂移出「写完永远读不到」），URL 明文不适合当文件名。信封带 `savedAtMillis`（时间戳与数据同文件，不取文件 mtime——它会被同步/备份改写）。写入走「同目录临时文件 + `Files.move(REPLACE_EXISTING)`」：`File.renameTo` 在 Windows 上对已存在目标是失败而非覆盖；临时文件取**确定名**（目标名 + `.tmp`）而非 `createTempFile` 的随机后缀——随机名每在 move 前被杀就留下一个 `read` 永远看不见的文件（它只匹配 `<md5>.json`），确定名则天然自限为一源一份、且被下次写入消费掉；坏文件读取按未命中处理并顺手删除自愈。
5. **TTL 是策略不是存储**：6 小时常量住仓库层，`LibraryDiskCache.write` 的 `savedAtMillis` 参数是测试接缝（生产默认当前时间，测试造确定年龄的条目）。书库一次刷新是每源 6~10 次串行请求，TTL 太短等于把第三方站点当爬虫打；分类首页内容以天为粒度变化，6 小时在「够新」与「克制」之间取中。
6. **回写守卫**：至少一个分类有书才落缓存。真解析器对网络故障**不抛异常**（单分类失败按空区块兜底），「区块在、书全空」就是站点挂了/规则失效的可见形态——这种结果冻进 TTL，用户每次进页都秒开一个空书城，比白屏更难懂。放行不缓存，下次进页自然重试。
7. **SP 迁移清理**：旧 `ACache` 类删除（它唯一的用途就是书库缓存），DI provider 里一次性 `deleteSharedPreferences("ACache")` 清掉迁移残留——不清就成了几百 KB 无人能读也无人能删的暗占用。

## 权衡

- **不做 HTTP 层缓存**：书源是第三方 HTML 站点，响应头不受我们控制（多数无标准缓存头）；且解析发生在应用层（HTML→实体），HTTP 缓存最多省「原文重复传输」，救不了「解析结果复用」。应用层自管缓存是唯一可行层。
- **不用 Room 存缓存**：为一份纯缓存引入 schema 迁移与主键设计不值；cacheDir 本就是系统认定的缓存家（低存储可整体回收，缓存管理页的「其他」档天然统计并清理 `library_cache/` 子目录，无需该页任何改动）。
- **不只加 `forceRefresh` 参数、不动架构**：那修得了假刷新，修不了「永不过期」与「不可清理」；且策略仍散在解析器与调用方两层，下一次改缓存语义还得再猜一遍。
- **SWR 重抓失败不惊动页面**：过期数据已上屏，重抓失败保留旧屏即可（只记日志）；没有缓存可兜底的首拉失败才把异常交给调用方。取消（`CancellationException`）**必须先于**吞异常分支放行——吞掉它只会让这次收集以「正常结束」收场，调用方就分不开「重抓失败、旧屏兜住了」与「这次加载已被取消」两件事（换源后并不会多收到一次旧数据：往已取消的收集者 `emit` 本身就抛）。这条顺序由 `BookSourceRepositoryLibraryCacheTest` 的一例锁住。
- **分类选书页（ChoiceBook）与聚合搜索刻意不加缓存**：前者每页直连网络、`noteUrl` 去重，翻页语义与「首页样张」不同；后者本就要求实时性。两处都没有「首屏秒开 + 内容慢变」的矛盾，缓存只会添乱。

## 下游影响

- `lib_book_common`：`BookParser` 接口改签名并删一方法；`JsoupBookParser` 去掉全部缓存逻辑；新增 `LibraryDiskCache`（含信封 DTO）。测试侧：`LibraryDiskCacheTest`（8 例，纯 JVM 临时目录）接棒「按源分区」的锁（原 `LibraryCacheKeyTest` 的 Robolectric 端到端两测随「解析器按 key 读写」的前提消失而移除，key 形态两测保留），其中一例锁「残留的确定名 `.tmp` 被下次写入消费掉」——随机后缀写法下这条会红；六个测试替身跟随新签名。
- `module_find`：`BookSourceRepository` 构造改 `(BookSourceManager, LibraryDiskCache)`（不再吃 Context）、书库方法改 policy Flow；新增 `di/LibraryCacheModule`（含 SP 迁移清理）；`LibraryViewModel.refreshData` 显式选 `ForceNetwork`、`loadLibrary` 逐发射上屏（collect 内禁止提前 return——非局部返回会终止收集，SWR 第二次发射就到不了）。测试侧：`BookSourceRepositoryLibraryCacheTest`（10 例，策略主战场——含一例锁「重抓遇取消原样上抛，不吞成成功」）、`BookSourceRepositoryNoSourceTest` 重构（Flow 化 + 临时目录，`FakeCacheContext`/`stubSharedPreferences` 随 SP 缓存一起删除）、`LibraryViewModelTest` 配真文件缓存并新增 2 例（切回已缓存源不发请求、刷新失败保留旧列表不误报源失效）。
- `lib_ebook_api`：删除 `ACache.kt`（编译器兜底确认无其他引用方）。
- `module_me`：无改动。缓存管理页的 `cacheBreakdown` 按目录分档，`library_cache/` 自然落入「其他」档——计入占用、可单独清理、可随「清理全部」删除。
- `module_book`：仅两个测试替身跟随签名，主代码无改动。
