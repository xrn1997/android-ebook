# 本地书籍导入第一阶段新增 EPUB：统一来源抽象，零新依赖

Status: accepted

本地书导入支持 TXT 与 EPUB 两种格式，正文按章落文件、不进 SQLite；两种格式共用 `SourceReader` 抽象。本 ADR 记下新增 EPUB 的范围与路径：解析只用项目既有的 `java.util.zip` 与 jsoup，不引入新依赖；PDF、MOBI/AZW3、FB2/CHM 暂不纳入。

## 动机

- 本地导入是用户侧「离线书库」能力，TXT + EPUB 是阅读 App 的基本盘。
- EPUB 是开放容器格式，解析不引入新依赖：ZIP 拆包用 `java.util.zip`，XHTML 正文提取复用书源解析已在用的 jsoup。
- EPUB 自带封面、作者与标题，能补足 TXT 导入的「佚名/无封面」缺陷。
- 正文出 SQLite 进章文件后，TXT 与 EPUB 的差异只剩「怎么把内容读出来」，因此共用一层来源抽象。

## 决策

1. **EPUB 与 TXT 并行，统一经 `SourceReader` 读取**：`LocalBookImporter` 按扩展名从 `Map<BookFormat, SourceReader>` 取 reader——TXT 用 `TxtSourceReader`，EPUB 用 `EpubSourceReader`；发现类能力（搜索、分类、书城）不属于这层，仍留在 `BookParser`。MD5 去重、写库与关联键复用同一条导入流水线。不引入第三方统一解析库：两种格式的解析差异远大于共性，抽象只服务本地书的「读元数据 / 切章 / 读正文」三件事。

2. **EPUB 解析流程**（`java.util.zip.ZipFile` + jsoup，均为既有运行时能力）：
   - `META-INF/container.xml` 的 `rootfile/@full-path` 定位 OPF；
   - OPF 的 `metadata`（`dc:title`、`dc:creator`）产出 `LocalBookMeta`；`manifest` 中 `properties="cover-image"`（或 EPUB2 的 `<meta name="cover">`）定位封面，由 `extractCover` 写入暂存目录；
   - 按 `spine` 的 `itemref` 顺序读取 XHTML，jsoup 提取章节标题与正文段落，跳过 `properties="nav"` / `application/x-dtbncx+xml` 导航文档；没有正文的章跳过。

3. **正文按段落落章文件，读取时规范化**：`buildChapters` 把每章段落写入 `BookStore` 的章文件，`ChapterEntry.contentRef` 指向该文件；`book_content` 表与 `BookContentEntity` 已不存在。存储保留原文，`BookRepository` 在读取时经 `TextNormalizer.cleanParagraphs` 统一换行、去 BOM、把行内连续空白折叠为一个空格；段首缩进由渲染层补，不进存储。正文内嵌图片第一阶段忽略，不转富文本。

4. **编码与校验**：EPUB 的 XHTML 按文档声明解析，不做启发式检测（区别于 TXT 的 `UniversalDetector`）；非 zip、缺 `container.xml`、无 OPF 或切不出任何章节的文件走导入失败，暂存目录作废，不给书架留半成品。

## 被拒方案

- **PDF 一并纳入**：PDF 是固定排版文档，无法可靠转纯文本流式阅读，必须新增独立渲染路径；书架与章节模型也要区分「文本章节书」与「文档书」，与现有段落模型是两条线。需单独决策。
- **MOBI/AZW3 一并纳入**：Amazon 私有格式，Android 开源解析库少且维护差，含 DRM 的书无法解析；中文书流通少。
- **FB2/CHM**：FB2 为 XML、解析成本虽低但中文受众小；CHM 为编译 HTML 帮助格式、Android 无成熟库。
- **引入统一多格式解析库**：Android 上不存在维护良好、覆盖 TXT/EPUB/PDF/MOBI 的统一库；为本地书强行抽象出通用解析器属于过度设计。
- **EPUB 正文富文本化**：需改造 `ChapterContent` 的段落模型与阅读器分页渲染，收益（插图阅读）与成本不匹配。插图支持留待正文模型演进时再评估。

## 下游影响

- `lib_book_common`：`LocalBookImporter` 负责导入流水线；`SourceReader` 的两个实现（`TxtSourceReader`、`EpubSourceReader`）由 `ContentStoreModule` 装配；`BookStore` 管理章文件与封面；`TextNormalizer` 在读取侧规范化。
- `module_book`：扩展名白名单与外部打开 Intent 过滤器包含 `epub`；阅读器经 `Map<BookFormat, ChapterReader>` 取正文，不直接依赖 EPUB 解析类。
- `lib_ebook_api`：无新依赖；jsoup 是书源解析的既有依赖。

锚点：`LocalBookImporter`；`TxtSourceReader`；`EpubSourceReader`；`SourceReader`；`BookStore`；`TextNormalizer`；`BookRepository`；`ChapterEntry`
