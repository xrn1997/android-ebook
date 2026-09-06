# 本地书籍多格式支持：第一阶段扩展 EPUB

当前本地书籍导入仅支持 TXT（扫描只认 `.txt` 扩展名，`BookImportViewModel.searchBook`；解析按"第x章"正则切纯文本章节，`BookImportManager.saveChapter`）。本 ADR 决定：**第一阶段新增 EPUB 支持**（复用现有"章节纯文本入库 → 阅读器流式渲染"架构，零新依赖）；PDF 因渲染路径独立、MOBI/AZW3 因私有格式与 DRM、FB2/CHM 因中文受众小，均暂不纳入（各留后续独立 ADR 空间）。

## 动机

- 本地导入是用户侧"离线书库"能力，主流格式（尤其 EPUB）是阅读 App 的基本盘；行业主流阅读 App 均以 TXT+EPUB 为底线。
- EPUB 是开放容器格式，解析不引入任何新依赖：ZIP 拆包用 `java.util.zip`，XHTML 正文提取复用项目已在书源解析中使用的 jsoup（`JsoupBookParser`），元数据映射到现有 `BookInfoEntity`，天然分章映射到 `ChapterListEntity`/`BookContentEntity`。
- EPUB 自带封面/作者/标题，能补足 TXT 导入的"佚名/无封面"缺陷，顺带提升书架展示质量。

## 决策

1. **新增 EPUB 导入链路**，与 TXT 并行：`BookImportManager` 内按扩展名分派到 `TxtBookParser` / `EpubBookParser` 两个解析策略（同文件内私有策略方法即可，不引入抽象框架）；`BookImportViewModel.searchBook` 扫描扩展名白名单扩为 `txt` / `epub`。MD5 去重、入库、回填关联逻辑（`BookShelfEntity`/`BookInfoEntity`/`ChapterListEntity`/`BookContentEntity`）完全复用现链路。

2. **EPUB 解析流程**（`java.util.zip` + jsoup，均为既有运行时能力）：
   - `ZipInputStream` 读包：取 `META-INF/container.xml` 定位 OPF（`rootfile/@full-path`）；
   - 解析 OPF：`metadata`（`dc:title`→书名、`dc:creator`→作者、`dc:language`）映射 `BookInfoEntity`；`manifest` 中 `properties="cover-image"`（或 EPUB2 的 `<meta name="cover">`）定位封面图，解码导出为本地文件并回填 `coverUrl`；
   - 按 `spine` 的 `itemref` 线性顺序逐个读取 XHTML 章节，jsoup 提取正文与标题（优先 `<h1>/<h2>`，缺省用 OPF 章节 title 或文件名），跳过 `nav.xhtml`/`toc.ncx` 等导航文档（`properties="nav"` / `application/x-dtbncx+xml`）；
   - 章节标题缺省时以正文首行为标题，对齐 TXT 现有回退行为。

3. **正文统一转纯文本，阅读器零改动**：EPUB 章节正文按块级标签（`<p>` 等）分段，**沿用 TXT 导入的排版约定**——每段首行补两个全角空格缩进、段落间以 `\n` 分隔（该约定是现有 `durChapterContent` 渲染分页的既有假设，见 `BookImportManager` 段落处理注释）。正文内嵌图片第一阶段忽略（见"被拒方案"）。

4. **编码与校验**：EPUB 的 XHTML 自带字符集声明，jsoup 按文档声明解析，不做启发式检测（区别于 TXT 的 `UniversalDetector`）；非 zip / 缺 `container.xml` / 无 OPF 的伪装文件走现有导入失败路径（`addErrorEvent`），不给书架留半成品。

## 被拒方案

- **PDF 一并纳入**：PDF 是固定排版文档，无法可靠转纯文本流式阅读（文本层提取会丢排版/图片/公式），必须新增独立渲染路径（逐页位图渲染 + 翻页），且书架/章节模型需区分"文本章节书"与"文档书"两种形态——改动面横跨书架、阅读器、章节模型，与现有纯文本架构是两条线。**单独决策、后续 ADR**，不混入本 ADR。
- **MOBI/AZW3 一并纳入**：Amazon 私有格式，Android 开源解析库少且维护差，含 DRM 的书无法解析；中文书流通少。暂缓。
- **FB2/CHM**：FB2 为 XML、解析成本虽低但中文受众小；CHM 为编译 HTML 帮助格式、Android 无成熟库。均暂缓。
- **引入统一多格式解析库（单一依赖解决全部格式）**：Android 上不存在维护良好、覆盖 TXT/EPUB/PDF/MOBI 的统一库；强行抽象出"通用解析器"接口属于过度设计，各格式的解析与渲染差异远超共性。
- **EPUB 正文富文本化（保留段落/图片/样式进正文模型）**：需改造 `BookContentEntity` 纯文本字段与阅读器分页渲染，破坏现有 TXT 排版约定，收益（插图阅读）与成本不匹配。插图支持留待正文模型演进时再评估。

## 下游影响

- `module_book`：`BookImportManager` 新增 EPUB 分派与解析逻辑；`BookImportViewModel.searchBook` 扩展名白名单扩 `epub`；`BookInfoEntity` 封面导出文件的管理（目录、清理）由导入链路负责。
- `lib_ebook_api`：无新依赖；jsoup 为书源解析既有依赖，EPUB 复用同一运行时能力（不新增第三方解析库）。
- 阅读器：EPUB 转纯文本后复用现有 `durChapterContent` 分页渲染，**无改动**。
- 文档：CONTEXT.md 领域术语若引入"本地书格式"概念需同步；本 ADR 落地状态待实现时更新。

## 落地状态

**已实现**（2026-09-05）。`EpubSourceReader`（`java.util.zip` + jsoup）走 `SourceReader` 接口，封面提取（EPUB3 `properties="cover-image"` / EPUB2 `<meta name="cover">`）经 `extractCover` 独立方法由 `LocalBookImporter` 在暂存目录调用；扫描白名单扩 `epub`（大小写不敏感）；`ContentStoreModule` 双 map 均注册 EPUB；`ReadBookActivity` 两份清单同步挂 `application/epub+zip` Intent 过滤器；9 个单元测试锁死元数据提取、spine 顺序、封面导出、空章跳过等关键行为。

**决策正文中的载体类已不存在**（补记 2026-09-06）：上文以 `BookImportManager` 为格式分派落点、以 `BookContentEntity` 为正文存储，两者都已在本地书内容基座重构中删除——分派改由 `ContentStoreModule` 的 `Map<BookFormat, SourceReader>` 承担，导入流水线是 `LocalBookImporter`，正文出 SQLite 改落 `BookStore` 章文件（迁移见 ADR-0003 的 v2→v4 补记）。读决策正文时按此对照，不要去找那两个类。
