# ObjectBox → Room：数据库切换与 ID 策略

将本地数据库从 ObjectBox 5.0.1 整体切换到 Room（初迁 2.7.1，后随依赖升级至 3.0.0、群组迁移为 `androidx.room3`，移除已并入 runtime 的 `room-ktx`，改用 `BundledSQLiteDriver()`）。lib_ebook_db 重写为 `@Entity`/`@Database` + DAO（`AppDatabase`，初迁 version = 1，exportSchema = true；**当前已随功能演进到 version = 2**，见「Schema 演进」），移除 ObjectBoxManager。

## 动机

- **协程/Flow 集成**：Room 查询原生返回 `Flow`/`suspend`，与 RxJava→Coroutines 迁移主线一致；ObjectBox 的响应式 API 是 RxJava 风格，与迁移方向相悖
- **统一技术栈**：lib_common（android-practice）用 Room，联动开发下减少维护成本
- **官方标准**：Room 是 Android 官方 ORM，文档与技能生态成熟
- **ID 复用问题**：ObjectBox 的「删除后重插失败」已知问题不再存在——自然键设计从根本上规避

## ID 策略

| 实体 | 主键 |
|------|------|
| BookShelf / BookInfo / ChapterList / BookContent | 自然键：`note_url` / `dur_chapter_url`（无 autoGenerate） |
| DownloadChapter / SearchHistory | 自增键：`id` autoGenerate |

自然键天然去重（同一 URL 的书只存一份）且 upsert 语义清晰；流水型数据（下载记录、搜索历史）用自增 ID。ObjectBox 时代的 note_url 唯一性语义得到保留。

## 数据迁移

**不迁移**（仅指 ObjectBox 遗留数据）：初迁以 version = 1 空库开始，升级后旧 ObjectBox 数据（书架/缓存/下载记录）清空，用户重新添加。当时处于开发阶段（0.2.x）用户基数小，为这次跨引擎迁移写代码成本高、收益低。**该豁免只适用于 ObjectBox → Room 这一次**：Room 之后各版本之间的表结构变更一律走 `Migration`，不得再清库（理由与做法见「Schema 演进」）。

## Schema 导出

exportSchema = true。schema JSON 经 `room.schemaDirectory` 配置并提交入库（`schemas/com.ebook.db.AppDatabase/1.json`、`2.json`），每次发版的前一版本 JSON 是写 `Migration` 的唯一依据（比对 `createSql` 与列序）。Room 3.0 升级本身未改表结构（`identityHash` 与 2.x 相同），但 **v1 → v2 已因新列改变 `identityHash`**（`6b3aa0e4…` → `f4a08a74…`），详见下节。

## Schema 演进（v1 → v2，落地补记 2026-09-03）

「数据迁移」一节写就时库确实停在 version = 1、无 Migration；下载入口合并「强制刷新缓存」能力后已升到 **version = 2**，本节记录实际接出来的迁移链，避免后人按旧描述以为无需 Migration。

- **变更内容**：`download_chapter` 新增 `force_refresh`（`Boolean` → `INTEGER NOT NULL`），承载「命中已有缓存也重抓：先删旧正文再重新下载」的任务级标记；旧行取默认 `0`，未带标记的存量任务仍是「命中即跳过」，语义不变（服务侧判据见 `module_book` 的 `DownloadService`）。
- **实现位置**：`DatabaseModule.MIGRATION_1_2`（`ALTER TABLE download_chapter ADD COLUMN force_refresh INTEGER NOT NULL DEFAULT 0`），由 `provideAppDatabase` 的 `addMigrations(MIGRATION_1_2)` 注册；**不启用** `fallbackToDestructiveMigration`。
- **为什么显式 `ALTER TABLE` 而不是清库**：开发期库里已有真实验证成本（书架、已缓存正文、未跑完的下载任务），破坏性迁移会让「覆盖安装」等于重下一遍；且一旦在开发期养成清库的习惯，进稳定期带数据上线就再也回不了头。
- **后续约束（真实义务）**：再改动实体必须三件事同时做——`@Database.version` +1、在迁移链上**追加**紧邻的 `MIGRATION_n_n+1`（不得跳版、不得只保留最新一条）、提交 Room 生成的新 schema JSON 入库。`docs/multi-source-plan.md` 的 `book_source` 表即按此规则预留为 v3（`addMigrations(MIGRATION_1_2, MIGRATION_2_3)`）。
- **验证状态**：列追加在表末尾，与 `2.json` 的 `createSql` 列序一致、主键与索引未变，理论上通过 Room 打开时的 schema 校验；**v1 库覆盖安装升级到 v2 的路径未做装机验证**，需人工在设备上确认（旧版本装数据 → 装新版本 → 书架与下载列表非空、发起一次下载不抛 `IllegalStateException: A migration from ... was necessary but could not be verified`）。

## 被拒绝的选项

- 保留 ObjectBox：维持 RxJava 风格响应式 API，与 Coroutines 迁移方向冲突，且 ID 复用问题持续存在
- 数据迁移后切换：开发阶段成本高收益低，推迟到正式版前再评估
