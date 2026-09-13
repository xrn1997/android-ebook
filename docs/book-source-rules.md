# 书源规则说明

本文档说明书源的两种格式、JSON 结构、导入方式与常见问题。应用**不随包携带任何书源**（ADR-0033），书源全部由用户自行导入。

> **注意**：应用只保证书源解析引擎（规则词法/求值/取文 + JS 沙箱执行器）的正确性，不保证用户导入的具体书源能正常加载。书源规则由第三方社区维护，站点改版或规则编写错误可能导致解析失败。

## 目录

- [书源是什么](#书源是什么)
- [两种格式](#两种格式)
- [导入书源](#导入书源)
- [原生规则格式（Native）](#原生规则格式native)
- [脚本书源格式（Script）](#脚本书源格式script)
- [管理书源](#管理书源)
- [常见问题](#常见问题)

---

## 书源是什么

书源是一份 JSON 配置，描述「如何从一个小说网站抓取内容」。它告诉应用：

- 搜索书籍时请求哪个 URL、结果列表怎么选
- 书籍详情页怎么解析（书名、作者、封面、简介）
- 章节目录怎么取（列表选择器、分页方式）
- 正文内容怎么提取（容器选择器、清理规则）

一份书源对应一个站点。应用支持**多书源共存**（ADR-0016）——书架上来自不同站点的书同时存在，每本书绑定自己的书源各自解析。

## 两种格式

应用支持两种书源格式（ADR-0029），在同一张 `book_source` 表中并存：

| 格式 | 存储列值 | 说明 |
|------|---------|------|
| **原生规则（Native）** | `native` | 本应用自有的声明式 JSON 格式，规则里没有可执行代码 |
| **脚本书源（Script）** | `script` | 社区通用 JSON 格式，规则内嵌可执行 JavaScript 脚本 |

格式由导入时自动判别：JSON 顶层含 `bookSourceUrl` 键 → 脚本格式，否则 → 原生格式。

## 导入书源

**路径**：我的 → 设置 → 书源管理 → 导入

支持的导入方式：
- **本地文件**：从设备存储选择 JSON 文件
- **JSON 文本**：直接粘贴 JSON 内容

导入时应用会自动：
1. 判别格式（原生 / 脚本）
2. 校验 JSON 结构
3. 检查是否与已有书源重复（按 URL 判重）
4. 逐条报告导入结果（成功/失败/警告）

**批量导入**：JSON 文件可以是单个对象或数组，数组时逐条处理。

---

## 原生规则格式（Native）

原生规则是纯声明式的 JSON 配置，不含可执行代码。以下是一个完整示例：

```json
{
  "name": "示例书源",
  "url": "https://example.com",
  "enabled": true,
  "group": "小说",
  "weight": 0,
  "charset": "utf-8",
  "method": "GET",
  "searchUrl": "https://example.com/search?keyword={{keyword}}&page={{page}}",
  "searchMethod": "",
  "searchBody": "",
  "ruleSearch": {
    "list": ".search-item",
    "name": ".book-name a",
    "author": ".book-author",
    "kind": ".book-category",
    "lastChapter": ".book-last-chapter a",
    "coverUrl": ".book-cover img@src",
    "bookUrl": ".book-name a@href",
    "intro": ".book-intro"
  },
  "ruleBookInfo": {
    "name": "h1.book-title",
    "author": ".book-author",
    "coverUrl": ".book-cover img@src",
    "intro": ".book-intro",
    "kind": ".book-category",
    "lastChapter": ".book-last-chapter a",
    "tocUrl": "",
    "reverseToc": false
  },
  "ruleToc": {
    "list": ".chapter-list li",
    "name": "a",
    "url": "a@href",
    "pageUrl": "",
    "nextPage": "",
    "reverse": false
  },
  "ruleContent": {
    "content": "#content",
    "nextPage": ".next-page@href",
    "replaceRules": [
      {
        "pattern": "广告内容",
        "replacement": "",
        "enabled": true
      }
    ],
    "image": ""
  },
  "ruleFind": {
    "url": "https://example.com/category/{{kind}}/{{page}}",
    "kinds": [
      { "title": "玄幻", "url": "xuanhuan" },
      { "title": "都市", "url": "dushi" }
    ],
    "ruleSearch": {
      "list": ".book-item",
      "name": ".book-name a",
      "author": ".book-author",
      "coverUrl": ".book-cover img@src",
      "bookUrl": ".book-name a@href"
    }
  }
}
```

### 字段详解

#### 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 书源名称，显示在书源列表 |
| `url` | string | 是 | 书源站点 URL（唯一标识，主键） |
| `enabled` | boolean | 否 | 是否启用，默认 `true` |
| `group` | string | 否 | 分组名，默认 `"小说"` |
| `weight` | int | 否 | 排序权重，数字越小越靠前，默认 `0` |
| `charset` | string | 否 | 字符编码，默认 `"utf-8"` |
| `headers` | object | 否 | 自定义请求头 |
| `method` | string | 否 | 默认请求方法 `"GET"` / `"POST"` |
| `body` | string | 否 | POST 请求体模板 |

#### 搜索规则（ruleSearch）

| 字段 | 说明 |
|------|------|
| `list` | 搜索结果列表的 CSS 选择器 |
| `name` | 书名选择器（相对于列表项） |
| `author` | 作者选择器 |
| `kind` | 分类选择器 |
| `lastChapter` | 最新章节选择器 |
| `coverUrl` | 封面图片 URL 选择器（取 `src` 属性用 `@src`） |
| `bookUrl` | 详情页 URL 选择器（取 `href` 属性用 `@href`） |
| `intro` | 简介选择器 |

#### 搜索 URL 模板（searchUrl）

支持占位符：
- `{{keyword}}` — 搜索关键词（自动 URL 编码）
- `{{page}}` — 页码

分页模板以 `/{{page}}` 结尾时，首页自动裁掉页码段。例如：
- 模板 `https://example.com/search/{{keyword}}/{{page}}`
- 首页渲染为 `https://example.com/search/关键词`（不带 `/1`）
- 第 2 页渲染为 `https://example.com/search/关键词/2`

#### 书籍详情规则（ruleBookInfo）

| 字段 | 说明 |
|------|------|
| `name` | 书名选择器 |
| `author` | 作者选择器 |
| `coverUrl` | 封面 URL 选择器 |
| `intro` | 简介选择器 |
| `kind` | 分类选择器 |
| `lastChapter` | 最新章节选择器 |
| `tocUrl` | 目录页 URL 选择器（为空则使用详情页 URL） |
| `authorPrefix` | 作者文本前缀（用于去除，如 `"作者："`） |
| `introPrefix` | 简介文本前缀 |
| `reverseToc` | 是否反转章节顺序，默认 `false` |

#### 目录规则（ruleToc）

| 字段 | 说明 |
|------|------|
| `list` | 章节列表的 CSS 选择器（支持多级，用 `\|\|` 分隔） |
| `name` | 章节名选择器 |
| `url` | 章节 URL 选择器 |
| `pageUrl` | 分页 URL 模板（支持 `{{page}}`），相对路径按目录页 URL 解析 |
| `nextPage` | 「下一页」链接选择器（优先于 `pageUrl`） |
| `reverse` | 是否反转章节顺序，默认 `false` |

**分页模式**（二选一，`nextPage` 优先）：
- `nextPage`：从页面中提取「下一页」链接（适用于有翻页链接的站点）
- `pageUrl`：URL 模板按页码递增（适用于 URL 有规律的站点）

#### 正文规则（ruleContent）

| 字段 | 说明 |
|------|------|
| `content` | 正文容器 CSS 选择器（支持多页，用 `\|\|` 分隔） |
| `nextPage` | 正文「下一页」URL 选择器（为空则不分页） |
| `replaceRules` | 正文清理规则数组 |
| `image` | 正文图片选择器（适用于图片站） |

**replaceRules**（正文清理）：

| 字段 | 说明 |
|------|------|
| `pattern` | 匹配正则 |
| `replacement` | 替换文本（空串 = 删除） |
| `enabled` | 是否启用，默认 `true` |

#### 发现/分类规则（ruleFind）

| 字段 | 说明 |
|------|------|
| `url` | 分类页 URL 模板（**必须包含 `{{page}}`**） |
| `kinds` | 分类列表（`title` = 分类名，`url` = 替换 `{{kind}}` 的值） |
| `ruleSearch` | 分类结果的解析规则（复用 SearchRule 结构） |

### CSS 选择器语法

原生规则使用 Jsoup CSS 选择器语法，常用模式：

| 模式 | 说明 | 示例 |
|------|------|------|
| `.class` | 类选择器 | `.book-name` |
| `#id` | ID 选择器 | `#content` |
| `tag` | 标签选择器 | `a`、`div` |
| `tag.class` | 标签+类 | `div.chapter` |
| `parent child` | 后代选择器 | `.list a` |
| `parent > child` | 直接子元素 | `.list > li` |
| `@attr` | 取属性值 | `img@src`、`a@href` |
| `.a.0` | 取第一个匹配 | `.list a.0` |
| `\|\|` | 多选择器（取第一个有结果的） | `.content\|\|.article` |

---

## 脚本书源格式（Script）

脚本书源使用社区通用 JSON 格式，规则内嵌可执行 JavaScript。原始 JSON 入库不做翻译转换，解析按该格式自己的语义求值。

### 格式判别

JSON 顶层含 `bookSourceUrl` 键 → 脚本格式。

### 与原生格式的区别

| 特性 | 原生规则 | 脚本书源 |
|------|---------|---------|
| 规则表达 | 纯声明式 CSS 选择器 | 链式规则 + CSS + JSONPath + 正则 + JS |
| 可执行代码 | 无 | 规则内嵌 `@js:` / `<js>` 段 |
| 执行环境 | 主进程 | 零权限隔离进程（`:js` 沙箱） |
| 存储方式 | 解码为 `BookSourceRule` 对象 | 原始 JSON 文本入库 |
| 格式标识 | `format = "native"` | `format = "script"` |

### 规则串语法

脚本书源的规则是一串表达式，支持以下模式标志：

| 标志 | 说明 | 示例 |
|------|------|------|
| `@css:` | CSS 选择器 | `@css:.book-name@text` |
| `@json:` | JSONPath | `@json:$.data.books[0].name` |
| `@xpath:` | XPath（暂不支持） | — |
| `@js:` | JavaScript 表达式 | `@js:result.title` |
| 无前缀 | 链式规则（默认） | `.book-name@tag.a.0@text` |

### 链式规则

链式规则由 `@` 分隔的多段组成，每段对上一段的结果进一步提取：

```
选择器@属性@索引##替换
```

- **选择器**：CSS 选择器
- **属性**：`text`（文本）、`src`、`href` 等
- **索引**：`.0` 取第一个、`.1` 取第二个、`-1` 取最后一个
- **替换**：`##正则##替换文本`

### 组合符

多条规则可用组合符连接：

| 组合符 | 说明 |
|--------|------|
| `&&` | 两条规则都执行，结果拼接 |
| `\|\|` | 先执行第一条，无结果时执行第二条 |
| `%%` | 两条规则的结果交替排列 |

### 变量与插值

- `@put:key=value` — 存储变量
- `@get:key` — 读取变量
- `{{key}}` — 插值（在 URL 模板中使用）

### JS 沙箱

规则中的 `@js:` / `<js>` 段在零权限隔离进程中执行：
- 有超时限制（默认 10 秒）
- 有内存上限
- 崩溃不传染主进程
- 可发起受限网络请求（经 `@Named("source")` 守门客户端，白名单过滤）

### 能力限制

脚本书源格式不支持的能力（导入时逐条明示）：
- 登录态维持
- 浏览器自动化
- Cookie 操作

---

## 管理书源

**路径**：我的 → 设置 → 书源管理

### 可用操作

- **启用/禁用**：禁用不切断已有书籍的归属（该书仍可正常解析）
- **设为默认**：设置书城浏览所用的书源
- **删除**：删除后，绑定该源的书籍会显示「书源已失效」
- **导出**：导出为 JSON 文件
- **导入**：从文件或文本导入

### 多书源共存

- 书架上来自不同书源的书同时存在，各自按自己的归属解析
- 每本书按 `tag`（书源 URL）绑定归属
- 同名同作者但来自两个源的两本书**刻意都留着**（阅读中换源的候选）
- 聚合搜索同时发给全部启用中的书源，结果边收边并入

### 默认书源

- 书城顶部切换器选中的那个源
- 「新加入的书绑哪个源」的答案
- 无可用源时书城进入引导态
- 事实源是 Room 数据库，SP 只记用户上次选择

---

## 常见问题

### Q: 导入书源后书城没有内容？

A: 检查以下几点：
1. 书源是否已启用（书源管理页查看）
2. 书源是否被设为默认源
3. 书源规则是否正确（站点可能已改版）
4. 网络连接是否正常

### Q: 书籍显示「书源已失效」？

A: 该书绑定的书源已被删除。解决方案：
1. 重新导入该书源
2. 换一个书源（阅读中换源功能）
3. 如果是本地书，检查文件是否完整

### Q: 搜索没有结果？

A: 检查以下几点：
1. 是否有启用的书源（聚合搜索发给全部启用源）
2. 单个源搜索失败不影响其他源
3. 全部源都失败才算搜索失败
4. 关键词是否正确

### Q: 如何创建自己的书源？

A: 原生规则格式：
1. 参考上方「原生规则格式」章节
2. 最小配置只需 `name`、`url`、`searchUrl`、`ruleSearch`
3. 使用浏览器开发者工具（F12）查看页面元素的 CSS 选择器
4. 逐步添加详情、目录、正文规则

脚本书源格式：
1. 使用社区书源编辑器
2. 参考社区书源模板
3. 注意脚本能力限制（无登录、无浏览器自动化）

### Q: 书源规则不生效？

A: 可能的原因：
1. CSS 选择器写错（用浏览器 F12 验证）
2. 站点页面结构已改版
3. 字符编码不匹配（检查 `charset` 字段）
4. 脚本书源的 JS 语法错误
5. 请求被站点反爬机制拦截

### Q: 如何获取书源？

A: 书源由用户自行准备，常见来源：
1. 社区书源分享（搜索「阅读书源」）
2. 自己编写（参考本文档）
3. 从其他阅读应用导出（格式需兼容）

---

## 相关文档

- [ADR-0016：多书源架构](adr/0016-multi-book-source-architecture.md)
- [ADR-0028：不可信 JS 沙箱](adr/0028-untrusted-js-sandbox.md)
- [ADR-0029：脚本书源导入](adr/0029-script-book-source-import.md)
- [ADR-0033：应用零内置书源](adr/0033-app-ships-no-book-source.md)
- [脚本书源语法规格](superpowers/specs/2026-09-08-script-source-rule-grammar.md)（高级参考）
