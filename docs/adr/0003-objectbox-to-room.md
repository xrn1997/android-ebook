# ObjectBox → Room：数据库切换与 ID 策略

本地数据库原使用 ObjectBox 5.0.1，其响应式 API 是 RxJava 风格，与项目向 Coroutines + Flow 的统一方向冲突。决定整体切换到 Room（初迁 2.7.1，后随依赖升级至 3.0.0、群组迁移为 `androidx.room3`，移除已并入 runtime 的 `room-ktx`，改用 `BundledSQLiteDriver()`）。lib_ebook_db 重写为 `@Entity`/`@Database` + DAO（`AppDatabase`，`version = 6`，`exportSchema = true`），移除 ObjectBoxManager。

## 动机

- **协程/Flow 集成**：Room 查询原生返回 `Flow`/`suspend`，与 RxJava→Coroutines 迁移主线一致；ObjectBox 的响应式 API 是 RxJava 风格，与迁移方向相悖。
- **统一技术栈**：上游通用库 lib_common 用 Room，联动开发下减少维护成本。
- **官方标准**：Room 是 Android 官方 ORM，文档与技能生态成熟。
- **ID 复用问题**：ObjectBox 的「删除后重插失败」已知问题不再存在——自然键设计从根本上规避。

## ID 策略

| 实体 | 主键 |
|------|------|
| BookShelf / BookInfo | 自然键：`note_url`（无 autoGenerate） |
| ChapterList | 自然键：`content_ref`（内容定位符，v3 前叫 `dur_chapter_url`；本地书存章文件相对路径、网络书存章节 URL） |
| BookGroup | 复合自然键：`comment_key` + `note_url` |
| BookSource | 自然键：`url`（书源站点根地址；本地书的 `tag` 常量 `loc_book` 在本表永无对应行） |
| DownloadChapter / SearchHistory | 自增键：`id` autoGenerate |

自然键天然去重（同一定位符的行只存一份）且 upsert 语义清晰；流水型数据（下载记录、搜索历史）用自增 ID。ObjectBox 时代的 note_url 唯一性语义得到保留。（`book_content` 表及其 DAO 已在 v4 删除——正文出 DB 进章文件，本库不再有该实体。）

**两种主键的 upsert 写法不通用，不要互相抄**：

- 自然键表一律 `@Insert(onConflict = OnConflictStrategy.REPLACE)`，主键即匹配键，整行替换。
- 自增键表先按业务列查回旧行的 `id` 再带 `id` 写入（`DownloadRepository.addTasks` 的 `existing?.id ?: 0L`、`SearchHistoryDao.findByTypeAndContent` → `insert`），才算原地覆盖；传 `0` 是让 SQLite 分配新行。**漏回填的后果两张表不一样**：`download_chapter` 的 `dur_chapter_url` 带唯一索引，重复业务键会撞索引而走 REPLACE 的「先删后插」（行号被换掉）；`search_history` **没有**业务列唯一索引，传 `0` 不报错、只是静静多出一行重复词条——「同一词条不重复记录」在这张表上没有任何数据库约束兜底，全靠 Repository 先查重。两张表的 DAO 都写着 `OnConflictStrategy.REPLACE`，但**自增主键上的 REPLACE 只在带旧 id 写回时才生效**（那时冲突键就是 `id`，等于原地覆盖），传 `0` 无冲突可解决、结果就是一行新记录。REPLACE 还是整行替换，重投时未回填的字段会被实体默认值冲掉（`DownloadRepository.addTasks` 因此逐字段回填旧值）。

## 数据迁移

**不迁移**（仅指 ObjectBox 遗留数据）：初迁以 version = 1 空库开始，升级后旧 ObjectBox 数据（书架/缓存/下载记录）清空，用户重新添加。当时处于开发阶段（0.2.x）用户基数小，为这次跨引擎迁移写代码成本高、收益低。**该豁免只适用于 ObjectBox → Room 这一次**：Room 之后各版本之间的表结构变更一律走 `Migration`，不得再清库（理由见「Schema 演进」）。

## Schema 导出

`exportSchema = true`，schema 目录由约定插件 `xrn1997.android.room` 指向模块的 `schemas/`，每个版本一份 JSON 且全部提交入库（现为 `schemas/com.ebook.db.AppDatabase/` 下 `1.json` … `7.json`）。每次发版的前一版本 JSON 是写 `Migration` 的唯一依据（比对 `createSql` 与列序）。Room 3.0 升级本身未改表结构（`identityHash` 与 2.x 相同）。

## Schema 演进

链上现有 `DatabaseModule.MIGRATION_1_2` … `MIGRATION_6_7` 六条，全部由 `provideAppDatabase` 的 `addMigrations(...)` 注册；**不启用** `fallbackToDestructiveMigration`。

各版做了什么：

- **v1 → v2**：`download_chapter` 新增 `force_refresh`（`Boolean` → `INTEGER NOT NULL DEFAULT 0`），承载「命中已有缓存也重抓：先删旧正文再重新下载」的任务级标记；未带标记的存量任务仍是「命中即跳过」，语义不变。
- **v2 → v3**：新建 `book_group(comment_key, note_url, is_primary)`；`book_shelf` 新增 `book_format`/`text_charset`/`match_name`/`match_author` 四列；`chapter_list.dur_chapter_url` 改名 `content_ref`（`ALTER TABLE … RENAME COLUMN`，实体字段同步改名）；删除本地书（`tag='loc_book'`）的 `book_content` 行与 `chapter_list`/`book_info`/`book_shelf` 书架行。
- **v3 → v4**：删除 `book_content` 表（网络书正文缓存改落章文件，正文彻底出 DB）与 `chapter_list.has_cache` 列（缓存存在性改由章文件存在性判定，不再有第二个真相源）。
- **v4 → v5**：只新增 `book_source(url, name, rule_json, enabled, weight, group_name, is_user_imported, added_at)`，主键 `url`（自然键：一个站点的解析规则只应有一份，重复导入即更新）。无破坏性变更——老用户覆盖安装后书架/章节/下载数据全保留。表初建时为**空**，且**此后不会有任何代码往里灌数据**：应用不随包携带任何书源，书源一律由用户自行导入，因此升级后业务表里原有的 `tag` 会暂时指向不存在的行，按既有语义显示「书源已失效」、由用户导入该书源后恢复。迁移语句不去读 assets（本模块依赖不到书源规则模型，在迁移里硬编码站点与规则等于把业务配置抄进 schema 层），且当时 assets 里那份内置清单现已删除。
- **v5 → v6**：`book_source` 增加判别列 `format`，让原生规则书源与脚本书源在同一张表里并存、按出身各走各的求值路径。列值是小写的 `native`/`script`（读写一律取 `SourceFormat.raw`，拿枚举 `name` 当列值不会报错，只会让脚本行静默按原生规则解、拉回不相干的内容）。
- **v6 → v7**：`book_source` 删 `is_user_imported` 列，**先**按该列清掉内置行（`is_user_imported = 0` 只可能由随包清单种下）、**再** `DROP COLUMN`——顺序不可颠倒：列一删就再也分不出哪些行是内置的，而两条语句对调后 `DELETE` 会以 `no such column` 失败。删除保护随之整体下线，任何一行都可删。该迁移声明为 `internal`，由同模块测试在**生产同款**的 bundled 引擎上直接驱动 `migrate(connection)`，语句顺序与效果因此有回归覆盖；`DROP COLUMN` 自 SQLite 3.35 起进语法表，而随包引擎是 3.50.1。

**为什么显式 `ALTER TABLE` 而不是清库**：开发期库里已有真实验证成本（书架、已缓存正文、未跑完的下载任务），破坏性迁移会让「覆盖安装」等于重下一遍；且一旦在开发期养成清库的习惯，进稳定期带数据上线就再也回不了头。可再生数据（本地书的索引与正文）另当别论——v2→v3 对本地书是删除而非搬运，因为旧正文被清洗过（行内空格被删光、全角缩进写进正文），搬进章文件等于把损毁固化成新基座。

**后续约束（真实义务）**：改动实体必须三件事同时做——`@Database.version` +1、在迁移链上**追加**紧邻的 `MIGRATION_n_n+1`（不得跳版、不得只保留最新一条）、提交 Room 生成的新 schema JSON 入库。

**`DEFAULT` 的写法纪律**：迁移里的建表/加列语句必须与 Room 为全新安装生成的语句**逐字对齐**，判据只有一条——**实体声明了什么就写什么**。列上带 Kotlin 默认值不等于 SQL 侧有默认值：

- 实体无 `@ColumnInfo(defaultValue = ...)` 时**不写** `DEFAULT`（v4→v5 建 `book_source`、v2→v3 建 `book_group` 皆此写法）。
- 实体声明了 `defaultValue` 时**必须写**且取值一致（v5→v6 的 `format TEXT NOT NULL DEFAULT 'native'`，同时给存量行补列，故不需要额外回填语句）。

理由：Room 的 schema 校验只比对实体声明过的东西，多写或少写一份默认值都不会被查出来，却会让「覆盖安装」与「全新安装」两侧表结构漂移；漂移的后果不是崩溃而是行为分叉——同一条漏列的裸 `INSERT` 在一侧成功、在另一侧违约束失败（或静默落成 0，在页面上显示成 1970 年）。要改默认值改实体，别在 SQL 里补。

**列名避开 SQL 保留字**：`book_source` 的分组列叫 `group_name`——`group` 是 SQL 关键字，拿它当列名会踩保留字；实体属性仍叫 `group`，由 `@ColumnInfo` 做映射。

## 被拒方案

- **保留 ObjectBox**：维持 RxJava 风格响应式 API，与 Coroutines 迁移方向冲突，且 ID 复用问题持续存在。
- **数据迁移后切换**：开发阶段（0.2.x）用户基数小，跨引擎数据迁移代码成本高、收益低，推迟到正式版前再评估——该判断仅适用于 ObjectBox → Room 这一次，后续 Room 版本间变更一律走 Migration。
