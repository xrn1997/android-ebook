# 版本号实践：规范依据与本仓落地方案

本文回答一个问题：**android-ebook 的版本号该怎么定、怎么打 tag、怎么算 versionCode。**
所有非显然的断言都尽量落到一手来源（规范原文 / 官方文档），并在行内给出出处；无法落地一手来源的，
在正文里显式标注为「社区惯例」或「本仓规定」。文末结合本仓实际历史给出**下一次发版的结论**与前向提案。

> 取材说明：`developer.android.com` 的 `.com` 域名在本环境不可达，Android 官方文档改用 Google 托管的
> 官方中文镜像 `developer.android.google.cn` 读取（同一份文档、同一发布者，仍是官方一手来源，已标注）。
> 其余来源均为规范/官方站点原文。

---

## 1. SemVer 2.0.0 规范

来源：SemVer 2.0.0 规范正文，`https://semver.org/spec/v2.0.0.html`；FAQ 在 `https://semver.org/#faq`。

### 1.1 三段式是强制形态

> “A normal version number **MUST** take the form `X.Y.Z` where X, Y, and Z are non-negative integers,
> and **MUST NOT** contain leading zeroes.”（spec §「Semantic Versioning Specification」第 2 条）

即 `MAJOR.MINOR.PATCH` 三段为强制；`1.1`（两段）与 `1`（一段）都**不是**合法 SemVer。
本仓历史 tag `V1.1` 因此不是 SemVer。

### 1.2 什么时候 MUST 升哪一段

规范把三段各自的递增条件写死（spec 第 6、7、8 条）：

- **MAJOR**：“Major version X (X.y.z | X > 0) **MUST** be incremented if any backward incompatible
  changes are introduced to the public API. It MAY include minor and patch level changes.”
- **MINOR**：“Minor version Y (x.Y.z | x > 0) **MUST** be incremented if new, backward compatible
  functionality is introduced to the public API. It **MUST** be incremented if any public API
  functionality is marked as deprecated. **It MAY be incremented if substantial new functionality or
  improvements are introduced within the private code.** It MAY include patch level changes.”
- **PATCH**：“Patch version Z (x.y.Z | x > 0) **MUST** be incremented if **only** backward compatible
  bug fixes are introduced.”

两处对本仓直接相关：MINOR 里那句 “**MAY be incremented if substantial new functionality or
improvements are introduced within the private code**”——内置了「应用没有对外 API 时，给内部实质新功能
升 MINOR」的空间；PATCH 的措辞是 “**only** backward compatible bug fixes」，即**只要不是「纯修 bug」
就不能只升 PATCH**。

### 1.3 预发布版优先级低于其正式版

> “When major, minor, and patch are equal, a pre-release version has lower precedence than a normal
> version: `1.0.0-alpha < 1.0.0`.”（spec §「Precedence」第 9 条示例）

预发布字段的比较规则（spec §「Precedence」第 9–11 条）：

1. 只含数字的标识符按数值比较；
2. 含字母或连字符的标识符按 ASCII 字典序比较；
3. 数字标识符的优先级**低于**非数字标识符；
4. 在前面标识符全等时，标识符**更多**的一方优先级更高。

因此 `1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-beta < 1.0.0-rc.1 < 1.0.0`。注意这条与第 4 节要讲的
「应用没有 public API」是两件事。

### 1.4 `0.y.z` 是初始开发期

> “Major version zero (0.y.z) is for initial development. Anything MAY change at any time. The public
> API SHOULD NOT be considered stable.”（spec 第 4 条）

FAQ 补充：“The simplest thing to do is start your initial development release at `0.1.0` and then
increment the minor version”（`https://semver.org/#faq`，条目
“How should I deal with revisions in the 0.y.z initial development phase?”）。
本仓当前是 `1.x`，已过初始开发期，`0.y.z` 的宽松条款**不再适用**。

### 1.5 规范**不**强制 `v` 前缀

> “**Is “v1.2.3” a semantic version?** No, “v1.2.3” is not a semantic version. However, prefixing a
> semantic version with a “v” is a common way to indicate it is a version number (often in a tag).”
> （`https://semver.org/#faq`）

规范正文的 BNF 也只描述 `X.Y.Z[-prerelease][+build]`，没有 `v`。FAQ 同段还给出
“`git tag v1.2.3 -m "Release version 1.2.3"`, in which case “v1.2.3” is a tag, not a semantic
version.”——**`v` 是「tag 名」层面的装饰，不属于版本号本身**。见第 2 节。

---

## 2. tag 前缀惯例：`v` / `V` / 裸版本号

### 2.1 规范立场：不强制、不禁止

如 §1.5 所述，SemVer **不**对 tag 前缀做任何规定：`v` 只是「这是版本号」的常见视觉提示。

### 2.2 生态里真正占主导的是**小写 `v`**

一手证据：SemVer FAQ 明确把 `v1.2.3` 当作“a common way”（`https://semver.org/#faq`）；GitHub 官方
文档在「管理 release」里给出的命令示例用的也是小写 `v` 前缀（`... v1.3.2`），并把 “Latest” 标签的
判定描述为 “assigned based on semantic versioning”
（`https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository`）。
裸版本号（无前缀）同样合法——GitHub 文档只要求“type a version number for your release
（`.../managing-releases-in-a-repository`）”，并不要求前缀。

结论：**小写 `v1.2.3` 是事实上的主流写法，大写 `V1.2.3` 少见。**

### 2.3 对本仓的直接后果

本仓现存 tag 全部是**大写 `V`**（见第 8 节）。因为 `V1.2.3` 不是合法 SemVer：

- 所有**按 SemVer 解析 tag 的工具/库**（semver 库、Go module、npm、release-please 的默认 tag 匹配、
  各类 changelog 生成器）都**读不出** `V1.2.3` 的版本号；它们要么报错、要么把它当普通字符串跳过。
- 但**本仓自己的 `AppVersion` 解析器不受影响**：它在解析前统一 `removePrefix("V").removePrefix("v")`
  （`module_me/src/main/java/com/ebook/me/util/AppVersion.kt`），大小写都吃得下。
  **因此「改用小写 `v`」不会破坏 App 内的版本比较逻辑**（详见 §8.4）。
- 反过来，若将来接入 release-please 等 SemVer 工具，**必须**先把 tag 规约成小写 `v` + 完整三段
  （或显式配置工具的 tag 模板），否则工具会把历史 tag 全部视为「非规范」而无法定位上一版（见 §5.3）。

---

## 3. Android 版本号约束：versionCode / versionName

来源：Android 官方文档「Version your app」`https://developer.android.com/studio/publish/versioning`
（本环境经官方镜像 `https://developer.android.google.cn/studio/publish/versioning` 读取，下同），
以及 Manifest `<manifest>` 元素参考 `https://developer.android.com/guide/topics/manifest/manifest-element`。

### 3.1 versionCode：正整数、且必须单调递增

> “`versionCode` — A **positive integer** used as an internal version number. This number helps
> determine whether one version is more recent than another… **However, make sure that each
> successive release of your app uses a greater value.**”（Version your app）

上一句话里的硬约束是**「每个后续发布必须用更大的值」＝单调递增**，与版本号语义（MAJOR/MINOR/PATCH）
**无关**——文档只要求「更大」，不要求它可推导。另有一条上限：

> “The greatest value Google Play allows for `versionCode` is **2100000000**.”（同上）

以及用于校验的朴素判据：

> “You can’t upload an APK to the Play Store with a `versionCode` you have already used for a
> previous version.”（同上）

Manifest 参考页对 `android:versionCode` 的措辞一致：“… set to a **positive integer** … greater
than 0 … you can define it however you like **as long as each successive release has a higher
number**”。

### 3.2 versionName：给人看的字符串，无格式约束

> “`versionName` — A string used as the version number shown to users.”（Version your app）
> Manifest 参考：“… the raw string or a reference to a string resource.”

即 `versionName` 只是展示串，**系统不解析其格式**。文档同页把语义化版本作为**建议**：

> “Many developers consider [semantic versioning](https://semver.org) a good basis for a versioning
> strategy.”（同上）

### 3.3 「从 versionName 算 versionCode」：**当前文档没有给公式**

一个常见说法是 Android 文档推荐 `MAJOR*10000 + MINOR*100 + PATCH` 这类编码。**本次核实（2025-08-20
版文档）在「Version your app」页面里找不到任何这类公式**：页面只给「正整数 + 递增 + 2100000000 上限」
三条，把版本号形态留给开发者自定。因此：

- **`MAJOR*10000+MINOR*100+PATCH` 是可用的自选方案，但不是官方强制、当前文档也未推荐**；本文第 10 节
  建议采用它，属于**本仓自定规约**，理由是可复现、可推导，而不是「官方要求」。
- 唯一不可违反的官方硬约束仍是 §3.1 的**单调递增**与 **≤ 2100000000**。

### 3.4 Google Play 与 AAB：Play **沿用**你在 base module 里填的 versionCode

任务里提到「Google Play 现在会为 AAB 自行分配 code」。核实结论是**文档说法与此不同**：官方
「Configure the base module」页（`https://developer.android.com/guide/app-bundle/configure-base`）说：

> “after you upload your app bundle, Google Play **assigns the version code from your base module**
> to all the APKs it generates from that bundle.” 并且“all split APKs for that app **share the same
> version code**.”

也就是说：**Play 并不替你发明 code，而是把你 base module 里那个 versionCode 复制给它为各设备配置
生成的每个 split APK**；你仍需自己保证这个值递增（“you must update the version code in your app’s
base module, and build a new, full app bundle”）。本仓的「发布」是 GitHub Releases 上的 **APK**，
不走 Play 的 AAB 分发，这段属于**知会性信息**，不构成本仓的技术约束。

---

## 4. 把 SemVer 的 MAJOR/MINOR/PATCH 映射到「应用」

### 4.1 规范只对「public API」说话

SemVer 全文以 **public API** 为锚点。规范开篇即要求声明它：

> “Software using Semantic Versioning **MUST** declare a public API.”（spec 第 1 条）
> “For this system to work, you first need to declare a public API… it is important that this API be
> clear and precise.”（spec §「Semantic Versioning Specification」引言）

**规范正文没有为「没有对外 API 的应用」单开一套规则**；FAQ 里也没有针对 App 的豁免条目（本次逐条核对
`https://semver.org/#faq`，未找到「应用/抽象」专门条目，只有 §1.4 的 `0.y.z` 宽松条款与
“How do I know when to go to 1.0.0?” 一类通用问答）。所以「App 的 MAJOR/MINOR/PATCH 怎么算」
**不是规范规定，而是社区/工具的约定**——本文明确区分。

### 4.2 应用场景下的通行做法（**社区惯例，不是规范**）

对一个终端 App，「对外契约」被约定放宽为「用户可感知的行为」：

| 变化性质 | 惯例 bump |
| --- | --- |
| 用户可感知的不兼容变化（数据/使用方式破坏性变更） | MAJOR |
| 新增用户可感知功能 | MINOR |
| 仅修复缺陷、无新功能 | PATCH |

这条映射与 §1.2 的规范**精神**一致（规范那句 “MAY be incremented if substantial new functionality
… within the private code” 正好给小版本留了口子），但**「MAJOR 用于破坏性用户变化」是惯例而非规范
条文**。工具层面对它的固化见 §5（Conventional Commits 的 `feat→MINOR / fix→PATCH / BREAKING→MAJOR`）。

---

## 5. 从提交信息推导版本号

### 5.1 Conventional Commits 的映射

来源：Conventional Commits 1.0.0 规范，`https://www.conventionalcommits.org/en/v1.0.0/`。

结构：`<type>[optional scope]: <description>` + 可选 body + 可选 footer。
规范把三者与 SemVer 一一对应（FAQ 段“How does this relate to SemVer?”）：

> “`fix` type commits should be translated to **PATCH** releases. `feat` type commits should be
> translated to **MINOR** releases.”
> “Commits with `BREAKING CHANGE` in the commits, regardless of type, should be translated to
> **MAJOR** releases.”

并且规范**只认这三个触发点**，其余 type 不隐含 bump：

> “Types other than `feat` and `fix` MAY be used in your commit messages … Additional types …
> **have no implicit effect in Semantic Versioning** (unless they include a BREAKING CHANGE)。”

这与本仓 `AGENTS.md` 的「语义版本映射」表完全一致（`feat→MINOR`、`fix→PATCH`、
`BREAKING CHANGE`/`!`→MAJOR、其余不 bump）。**该表是规范级正确的。**

### 5.2 两大一手工具

**semantic-release**（`https://semantic-release.gitbook.io`）

- 假设：跑在 CI 里、代码托管在 Git、默认采用 **Angular 提交约定**（“默认采用 Angular Commit Message
  Conventions 来判定变更影响”，见其文档）；发布动作（打 tag、发 npm dist-tags 等）由插件串起来。
- 版本判定：由 `@semantic-release/commit-analyzer` 的默认规则给出
  （`https://github.com/semantic-release/commit-analyzer` README）：
  - “Commits with a **breaking change** will be associated with a **major** release.”
  - “Commits with `type` **'feat'** will be associated with a **minor** release.”
  - “Commits with `type` **'fix'** will be associated with a **patch** release.”
- 对**一次被 squash 的多主题提交**：semantic-release 不做特殊处理——它就是分析区间内每个 commit 的
  类型；“squash 成一个 commit”只是让区间里少了一个 commit。**多主题信息要靠 footer 承载**（见下面对
  release-please 的说明，两者对 footer 的处理同源），否则一个 squash commit 只能表达一类 bump。

**release-please**（`https://github.com/googleapis/release-please`）

- 假设：README 明说 “Release **Please assumes you are using Conventional Commit messages**.”；
  它直接“parses git history”，需要**线性历史**与**按约定的 tag**；合并「Release PR」后由它“Tags the
  commit with the version number”（即你不需要手工打 tag）。
- 映射：README 逐字给出 “**fix:** … correlates to a **SemVer patch**”“**feat:** … correlates to a
  **SemVer minor**”“**feat!:** , or `fix!:` , `refactor!:` , etc., which represent a **breaking change**”。
- **一次提交承载多个主题**：README 明确支持——“**Release Please allows you to represent multiple
  changes in a single commit, using footers:**”，每个 footer 生成一条 changelog 条目。也就是说，一个
  squash 提交里写多个 `feat:`/`fix:` 段落或 footer，仍能被正确拆解——**前提是信息真的写进了提交体**，
  而不是被压成一句话。
- **历史 tag 不合规时怎么办**（关键，直指本仓）：README 说“Release Please looks at commits since your
  last release tag. **It may or may not be able to find your previous releases.**”；对没有合规上一版的
  仓库，官方指引是 “The easiest way to onboard your repository is to **bootstrap a manifest config**”，
  以及 bootstrap 相关的配置键：用 `bootstrap-sha`/`last-release-sha` 指定“the commit sha
  release-please will use from which to gather commits for the current release”；首次成功后该 bootstrap
  值即被忽略（“once a release-please generated PR has been merged, this config value will be ignored
  for all subsequent runs and can be removed”）。当 tag 模板与默认不符时（如本仓的 `V` 前缀），
  需要调整 `tagName`/`include-component-in-tag` 等配置去匹配，否则**搜不到上一版**。

一句话：**两个工具都把「提交历史」当作版本号的唯一事实源，都要求 tag 可被它们定位。** 本仓现有历史
（大写 `V`、`V1.1`、`V1.1.7alpha`）对它们都是「非法或不规范 tag」，接入前必须先处理（见 §10）。

---

## 6. 预发布标识符

### 6.1 合法文法

SemVer 的 BNF 把预发布定义为**由连字符引出的**一段（`https://semver.org/spec/v2.0.0.html`）：

```
<valid semver> ::= <version core> "-" <pre-release>
<pre-release>  ::= <pre-release identifier> | <pre-release identifier> "." <pre-release>
```

即预发布**必须**以 `-` 开头，标识符可由 `.` 分隔。所以：

- `1.2.3-alpha.1` ✅（数值段 + `-` + 标识符 `alpha` + `.` + 数值标识符 `1`）
- `1.2.3-alpha`、`1.2.3-rc.1`、`1.2.3-0.1.0` ✅

### 6.2 `1.1.7alpha`（无连字符）**不是**合法 SemVer

`V1.1.7alpha` / `1.1.7alpha` 中 `alpha` 没有 `-` 引出，**不匹配上述文法**，因此不是一个合法 SemVer；
它只能算「一个 tag 字符串」。规范亦重申 “No, 'v1.2.3' is not a semantic version.”——任何额外装饰
（含 `v`、含粘连字母）都会让它「不是一个语义版本」。

结论（明确）：**`1.1.7alpha` 无效；合法写法是 `1.1.7-alpha` 或 `1.1.7-alpha.1`。**

---

## 7. CHANGELOG 与 GitHub Releases

### 7.1 Keep a Changelog

来源：`https://keepachangelog.com/en/1.1.0/`。

- 明确建议与 SemVer 绑定：guidelines 要求 “**Mention whether you follow Semantic Versioning**”，
  并在示例头部写 “this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)”。
- 存在的理由：“**To make it easier for users and contributors to see precisely what notable changes
  have been made between each release.**” 以及 “When the software changes, people want to know why
  and how.”

即 CHANGELOG 是「面向人和贡献者的发布差异清单」，与 SemVer 配套使用。

### 7.2 GitHub Releases 与 `/releases/latest`

- Releases 基于 git tag：“Releases are based on Git tags, which mark a specific point in your
  repository's history.”（
  `https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases`）；
  创建 release 时“type a version number for your release, then click Create new tag”
  （`.../managing-releases-in-a-repository`）——**GitHub 不要求前缀**，其示例用小写 `v1.3.2`。
- **`GET /repos/{owner}/{repo}/releases/latest` 的确切语义**（
  `https://docs.github.com/en/rest/releases/releases`）：
  > “The latest release is the **most recent non-prerelease, non-draft release**, sorted by the
  > `created_at` attribute.”

  三点必须记住：
  1. **只返回「非预发布、非草稿」的 release**——被标记为 pre-release 的 tag（如真正的 `1.1.7-alpha`）
     永远不会出现在 `latest` 里；
  2. 排序依据是 **`created_at`（release 创建时间）**，不是版本号大小；
  3. 若把 release 手工标为 “Latest”，则该标记优先。

  **这正对本仓**：`module_me` 的 `ReleaseRepository` 读的就是
  `https://api.github.com/repos/xrn1997/android-ebook/releases/latest`
  （`module_me/src/main/java/com/ebook/me/repository/ReleaseRepository.kt`，另有 Gitcode 兜底）。
  因此「预发布 tag 不会污染 latest」这条是端点本身的保证，与 App 端的比较逻辑无关（印证
  `docs/adr/0021-release-update-check-ownership.md` 的取舍 5：比较层不必实现预发布序）。

---

## 8. 本仓现状核实（读代码 / git 得到的事实）

### 8.1 版号声明

`module_app/build.gradle.kts`：`versionCode = 13`、`versionName = "1.3.0"`。

### 8.2 现存 tag

`git tag -l`：`V1.1`、`V1.1.1` … `V1.1.7alpha`、`V1.2.0`、`V1.3.0`，**全部大写 `V`**，均存在于 origin。

- **非法 SemVer 的 tag**：`V1.1`（两段）、`V1.1.7alpha`（无连字符粘连；另 `V1.1.1` 等三段式虽合法，
  但有大写 `V` 前缀，按 §1.5 也不是「语义版本本身」）。
- **tag 对象类型不一**：`V1.1`、`V1.1.1`、`V1.1.5` 是**轻量 tag（lightweight）**，其余
  （`V1.1.2/3/4/6`、`V1.1.7alpha`、`V1.2.0`、`V1.3.0`）是**附注 tag（annotated）**。任务描述里
  “annotated” 只是大致成立，实测有 3 个轻量 tag。

### 8.3 versionCode 历史（只有「递增」成立，无公式）

逐个 tag 读 `module_app/build.gradle.kts`（早期为 `app/…`，路径已重命名）：

| tag | versionCode | versionName |
| --- | --- | --- |
| V1.1.5 | 4 | 1.1.5 |
| V1.1.6 | 4 | 1.1.6 |
| V1.1.7alpha | 1 | 1.1.7alpha |
| V1.2.0 | 10 | 1.2.0 |
| V1.3.0 | 13 | 1.3.0 |

可见 `1.1.7alpha→1` 相对前值 **4 是回退的**（说明当时根本没在意单调性），之后 1→10→13 才恢复递增。
**「单调递增」目前靠人肉，没有公式、也没有工具保证。**

### 8.4 App 内解析器接受哪些形态（`AppVersion.kt` + `AppVersionTest.kt`）

规则（`AppVersion.parse`）：`[Vv]?{数字段}(.{数字段})*{可选尾缀}`

- 前缀 `V` **或** `v` 都可省：`removePrefix("V").removePrefix("v")`，大小写通吃；
- **非末尾段必须是纯数字**（`toIntOrNull()` 否则整串判 null）；
- **末段**必须**以数字开头**，其后可挂一串字母数字尾缀（正则 `^(\d+)(.*)$`），
  如 `V1.2.3abcd` → `(1,2,3)+"abcd"`、`V1.2beta` → `(1,2)+"beta"`；
- 段数不限，比较时逐段、缺段补 0；数字段全等才比尾缀，**空尾缀 < 任意非尾缀**。

两个直接后果：

1. **「改用小写 `v`」不会破坏 App**：`v1.2.0` 与 `V1.2.0` 解析结果相同（测试用例
   `数字段按点分段解析，前缀 V 与 v 都可省` 已锁）。切前缀对客户端零风险。
2. **本仓解析器与 SemVer 在预发布序上相反**：`V1.2.0alpha > V1.2.0`（测试
   `数字段全等时才比尾缀，空尾缀按字典序更小` 显式锁定），而 SemVer 规定 `1.2.0-alpha < 1.2.0`。
   `AppVersion` KDoc 已说明这是**有意取舍**（依赖 `latest` 端点本身排除 prerelease）。
   **因此若将来真启用 SemVer 预发布 tag（`1.4.0-rc.1`），必须同步改造比较层**，否则会把 rc 当成
   「比正式版新」而误报更新。

### 8.5 发布检查端点

`ReleaseRepository` 读 GitHub `repos/xrn1997/android-ebook/releases/latest` + Gitcode 兜底；
「无 `.apk` 资产」不算源失败（`docs/adr/0021-…`）。全仓**无 `.github/workflows`**（`.github/` 不存在），
**无自动化**；版本 bump 是手写的 `build:` 提交，只改 `module_app/build.gradle.kts`。
**无 CHANGELOG 文件。**

### 8.6 历史 batch 的实际 bump（commit 计数）

| 区间 | commit 数 | 当时给了 |
| --- | --- | --- |
| `V1.1.5..V1.1.6` | 18 | PATCH（1.1.5→1.1.6） |
| `V1.1.6..V1.1.7alpha` | 26 | PATCH（1.1.6→1.1.7alpha） |
| `V1.1.7alpha..V1.2.0` | 8 | MINOR（含 `refactor(all)!:` 全量 Compose 迁移） |
| `V1.2.0..V1.3.0` | 10 | MINOR（脚本书源链路 + `:js` 沙箱新子系统） |

**关键观察**：前两个 PATCH batch 的提交用的是**旧格式**（`修复[…]:`、`更新[…]:`），**根本不是
Conventional Commits**——那时的 bump 与 `AGENTS.md` 的映射表无关。Conventional Commits 是到
`V1.2.0..V1.3.0` 一带才成为主流的。所以「历史给了 PATCH」不能拿来当映射表的反例，二者属于不同时期。

---

## 9. 结论：下一批改动是 1.3.1 还是 1.4.0

### 9.1 该批改动的构成

一个 squash 提交，含：

1. **新功能**：异常退出后恢复阅读（`docs/adr/0040-…`）；
2. **缺陷/不变量链**：章节目录单一事实源 + 归属门 + 写事务（`docs/adr/0039-…`）；
3. **两处 UI 改版**：等高三行书条目；空态/错误态统一到共享 `EmptyState`（`docs/adr/0042-…`）；
4. 一次**跨模块签名变更**（内部）；
5. Compose 预览测试基础设施（测试面）；
6. **两个真 fix**：评论聚合键去重、加书架单写事务。

### 9.2 按规范判：规范**不构成硬约束**，只排除误用

先把规范的句式看清（§1.2 已引原文）：三条 MUST 都是**充分条件句式**（“MUST be incremented **if** …”），
不是「不许例外」。

- **PATCH 的条件不触发**：PATCH 的 MUST 前提是 “if **only** backward compatible bug fixes are
  introduced”。本批含用户可感知的新功能（异常退出恢复阅读）与两处 UI 改版，不是「纯修 bug」，
  故该 MUST **不成立**。但要注意：**「不触发」不等于「禁止」**——规范没有写「含功能的发布 MUST NOT
  只升 PATCH」。把它读成禁止是过度解读。
- **MINOR 只给了 MAY**：MINOR 的 MUST 绑定在 “introduced to the **public API**” 上，而 App 没有对外
  API。真正适用的是同段那句 “It **MAY** be incremented if substantial new functionality or improvements
  are introduced within the private code”——**MAY 是自行裁量，不是强制**。
- 三段**总述**（“MINOR version when you add functionality in a backward compatible manner”）读起来
  偏向 MINOR，社区与 Conventional Commits 也都如此（`feat` → MINOR），但那是**惯例**，不是 MUST。
- **不构成 MAJOR**：MAJOR 的 MUST 是 “backward incompatible changes … to the public API”，本仓无对外
  API；内部签名调整用户不可感知（先例：`V1.2.0` 的 `refactor(all)!` 只升了 MINOR）。

**小结：对「没有 public API 的应用」，规范把「加了功能该升哪段」留给裁量。** 它唯一能确定的是
「纯修复不该升 MINOR」这一侧的误用。所以 1.3.1 与 1.4.0 **都能自圆其说**，取决于采用哪套惯例：

| 惯例 | 判据 | 本批结果 |
| --- | --- | --- |
| **A 社区主流 / Conventional Commits** | 向后兼容地**新增了功能** → MINOR（不看改得多大） | **1.4.0** |
| **B 本仓历史** | **根本性重建**才 MINOR，其余（哪怕含新功能）走 PATCH | **1.3.1** |

两套都可执行，取舍见 §9.3。

### 9.3 结论：这是政策选择，不是规范推导

> **规范不定档：`1.3.1` 与 `1.4.0` 都合规。** 取哪个取决于采用哪套惯例（§9.2 的表）。

- 选 **A（社区主流 / Conventional Commits）** → `1.4.0`。理由：本批向后兼容地新增了用户可感知功能。
  优点：判据**可判定、可自动化**（`feat`/`fix`/`!` 直接算出，release-please 这类工具就是干这个的）。
  代价：**「根本性重建」与「一个小功能」拿到同一个 MINOR 位**——版本号不再传达「改动分量」。
- 选 **B（本仓历史）** → `1.3.1`。理由：本批没有根本性重建。代价：判据**不可判定**（没人能从提交
  算出「根本性」），因此永远无法自动化、每次发版都要人拍板；PATCH 成为默认桶，功能更新会混在
  `1.3.1`/`1.3.2` 里，用户从版本号看不出「这版有没有新东西」；且**与 `AGENTS.md` 现存的映射表
  （`feat`→MINOR）直接冲突**，选 B 必须同步改表，否则文档与实操长期对不上。

无论选哪个，**都不动 MAJOR**（本批无对外契约破坏）。要让用户感知「这一版分量重」，靠的是
CHANGELOG / GitHub Release notes，而不是版本号里那一位。

**已采纳 A**（2026-10-02）：判据落成 §11 的三问并写进 `AGENTS.md`，本批按它取 **1.4.0**；B 栏
（「根本性才升 MINOR」）被否，理由见 §11.1——它没有 1.x 的落脚点。

### 9.4 与「历史实践」的对账

有人会拿「18/26 个提交都只给了 PATCH」反驳。**「提交数」确实不是判据**——SemVer 从不看提交数量，
18、26 个提交给 PATCH 与 1 个提交给 MINOR 并不矛盾。但**也不能反过来断言「历史实践与规范一致」**：

- 那两批用的是**旧提交格式**（`修复[…]:` / `更新[…]:`），**不是 Conventional Commits**，类型标签本身
  不可信（`V1.1.5..V1.1.6` 的 18 条里，既有 `更新[界面]：深色模式初步适配` 这类用户可见的界面能力变更，
  也有 `更新[lib_book_common]：重命名原模块，引入新的 lib_common`、全模块换基类、`ARouter→TheRouter`
  迁移这类结构性重构，还有一批纯修复）。**靠这些标签无法把批次可靠地归到某一档**，
  逐条「正确归类」只是事后推断，不能当成证据。
- 因此历史既**不能**当作「本批该走 PATCH」的反例，也**不能**当作「规范一直被遵守」的支持。
  判据只能是规范本身（§9.2）。
- 可以确认的一点：本仓的 MINOR（`1.2.0` 全量 Compose 迁移、`1.3.0` 新子系统）历来对应的是**大重建**，
  而不是「有没有新功能」——这是**惯例差异**，不是规范依据。采纳严格 SemVer 即等于改变既有做法。

唯一真正与规范有出入的成文条款是 `AGENTS.md` 映射表把 `BREAKING CHANGE` 一律写成 MAJOR，
而本仓 `1.2.0` 的 `refactor(all)!` 只给了 MINOR。见 §10.1 的处理建议。

---

## 10. 前向提案

### 10.1 `AGENTS.md` 的映射表：**已改为「用户视角三问」+ 手工判据**（2026-10-02 落地）

原表（`feat→MINOR / fix→PATCH / BREAKING→MAJOR`，其余不 bump）有三处与实际不符，已重写：

1. **示例列是 `0.y.z` 阶段的形态**（`PATCH (0.0.x)` / `MINOR (0.x.0)` / `MAJOR (x.0.0)`）：本仓已过 1.0，
   改为 `PATCH (x.y.Z)` / `MINOR (x.Y.z)` / `MAJOR (X.y.z)`。
2. **`BREAKING CHANGE`/`!` 不自动等于 MAJOR**：本仓无对外 API，内部契约破坏（跨模块接口、类名、导入路径）
   用户无感。改为「先答第 1 问：破坏的是不是**用户**的东西」。原文「任何 type 的 `!` 都触发 MAJOR」是错的。
   注意 `V1.2.0` 的 `refactor(all)!` 之所以是 MINOR，**不是**因为「内部破坏算 MINOR」，而是因为它顺带改了
   全仓观感（§11 第 2 问成立）——理由不同，结论相同。
3. **其余类型「不 bump」对 App 不成立**：每次发版都必须有新 `versionName`/`versionCode`，否则装不上、
   更新检查也认不出来。改为「这类提交本身不发版；一次发版里全是这类时，该次发版为 PATCH」。

另删掉「用于自动化版本发布」的措辞——仓库无 CI、无 release 工具，表是**手工判据**。
**长期可选**：接入 **release-please**（与 GitHub Releases 直连）把「合并 Release PR → 自动打 tag →
自动写 release notes」串起来；接入前必须先解决 tag 前缀（§10.2）与判档口径（§11），并按其 README 用
`bootstrap-sha`/`last-release-sha` 指定起点。这会首次引入 `.github/workflows`，属独立决策，建议单开 ADR。

### 10.2 tag 格式：改用小写 `v` + 完整三段；**历史 tag 一律不动**

- **新 tag 规约**：`vMAJOR.MINOR.PATCH`，全小写、完整三段、合法 SemVer（如 `v1.4.0`）。理由：小写 `v`
  是生态主流（§2.2），合法 SemVer 才能被工具识别（§2.3）。
- **历史 tag（含大写 `V`、`V1.1`、`V1.1.7alpha`）保持原样，不要重打/删除**：
  - 已推送到 origin，重写 tag 会破坏他人克隆、并可能与 App 侧 `ReleaseStateStore` 存的历史 tag 对照
    产生错位；
  - **本仓 `AppVersion` 大小写通吃**，旧 tag 继续可用（§8.4），没有「非改不可」的技术理由；
  - 在 CHANGELOG/README 里加一句「`V*` 为历史遗留、`v*` 为现行规约」即可。
- **注意端点不受影响**：`/releases/latest` 按 `created_at` 与「非预发布/非草稿」判定，与 `v`/`V` 前缀
  **无关**（§7.2）；切前缀纯粹是为了工具可解析与生态一致。

### 10.3 versionCode 的计算

- **确定性公式**：`versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH`（**各段上限 999**）。据此
  `1.3.0 → 1_003_000`、**`1.4.0 → 1_004_000`**、`1.4.1 → 1_004_001`、`1.10.0 → 1_010_000`、`2.0.0 → 2_000_000`。
- **为什么是三位进制、不是两位**：两位写法（`MAJOR*10000 + MINOR*100 + PATCH`）下
  **`1.100.0` 与 `2.0.0` 会算出同一个 20000**——`versionCode` 必须严格递增，撞车即发版事故。三位进制把
  各段上限抬到 999；而 `2100000000`（平台上限）除以 `1e6` 仍允许 MAJOR 到 2100，够用。
- **单调性**：切公式后首次为 `1_004_000`，远大于线上现有的 `13`（1.3.0），成立（§3.1 的硬约束）。
- **诚实标注**：这是**本仓自定规约**——当前 Android 文档**不推荐任何公式**（§3.3），官方硬约束只有
  「正整数 + 递增 + ≤ 2.1e9」。
- **落地方式**：`module_app/build.gradle.kts` 里按上式手算成字面量并留注释说明算式。若要彻底免手算，
  可由 `versionName` 反解 versionCode（属独立改进，**尚未做**）。

### 10.4 畸形历史 tag 的处理

- **不删除、不重命名、不重打**（原因同 §10.2）。
- 在 README/CHANGELOG 记一条「版本 tag 规约变更点」：`v1.4.0` 起生效，此前为历史遗留。
- 保 `AppVersion` 的宽容解析（大小写前缀、可选尾缀、任意段数）**不要收紧**——收紧会让线上 App 读不到
  自己的历史 tag。
- **若未来启用 SemVer 预发布 tag**（`v1.4.0-rc.1`），**必须先改比较层**：当前 `AppVersion` 认为
  `V1.2.0alpha > V1.2.0`（与 SemVer 相反，§8.4）。要么禁用带 `-` 的预发布 tag，要么让解析器识别
  `-` 并实现 SemVer 的预发布序。

### 10.5 CHANGELOG

- **建议新增 `CHANGELOG.md`**，采用 Keep a Changelog 格式并声明 “this project adheres to Semantic
  Versioning”（`https://keepachangelog.com/en/1.1.0/`）。
- **若长期接入 release-please**：由其自动维护（它按 conventional commit footer 生成条目，§5.2），人工
  不再手写。
- **短期**：每个 `build:` 发版提交里手写一个小节（Added/Fixed/Changed），来源是本次区间的提交标题——
  成本低、可读性高。配套在 GitHub Releases 里贴同一份说明，正好补齐 `releases/latest` 的展示内容。

## 11. 本仓判据：什么时候升哪一位（可执行流程）

**版本号只在发版时定，不在提交时定**；一批改动一个号，混着多种类型时取**最高档**。判据是**用户视角**
自上而下三问，**命中即停**：

1. 老用户升级后**不做任何额外动作**会出问题吗——丢书架/丢阅读进度、必须重新登录、已有功能被删或行为反转、设备不再被支持？→ **MAJOR** `X.y.z`
2. 这一版有老用户能拿到的**新东西**吗——新功能，或用户看得出变化的改版？→ **MINOR** `x.Y.z`
3. 都不是——只把已有的东西修对，或只做了用户完全无感的内部整理？→ **PATCH** `x.y.Z`

**「改动多大」不参与判档。** 这是最容易搞反的一条：版本号回答的是「升级要不要做准备」，不是「作者花了
多少力气」。所以**纯内部重构、用户无感 → PATCH**；而**改了观感的大重构 → MINOR**（第 2 问成立，
与「改得多」无关）。

### 11.1 为什么本仓不能像 `0.x` 项目那样「随便改」

`0.y.z` 是 SemVer 的**免责区**（规范第 4 条：“Anything MAY change at any time. The public API should not
be considered stable.”）。实测对照：`deepseek-ai/deepseek-harness` 的 24 个 release **全部是预发布**
（`dsh-v0.2.0-rc.2` 一类；版本核停在 `0.y.z`，破坏性变更走 MINOR），一个正式版都没有，
`/releases/latest` 因此返回 404。本仓已过 1.0、有真实用户与本地数据，**这个自由度不可用**：1.x 里破坏性
变更只能走 MAJOR。所以「根本性变化才升 MINOR」这种尺子**没有 1.x 的落脚点**，采纳它就等于宣布版本号与
SemVer 脱钩、改成本仓自定规约（§9.2 的 B 栏）。

### 11.2 六条边界

1. **大重构但用户能看出变化**（如全量 Compose 迁移改了观感）→ MINOR。
2. **纯内部重构 / 换依赖 / 换解析引擎，用户无感** → PATCH。
3. **性能优化**：无行为变化 → PATCH；顺带改了交互（如加了滚屏模式）→ MINOR。
4. **数据迁移**：写对了（老数据无损升级）→ 不因此升 MAJOR；**没写迁移**（丢书架/丢进度）→ MAJOR。
5. **权限 / 通知行为变化**（要用户重新授权一次）→ 倾向 MINOR（只是多一次弹窗），按「要用户做准备」
   也可判 MAJOR。**规范给不出依据，这条由维护者定。**
6. **预发布**（`-rc.1` / `-alpha.1`）不占正式号；`/releases/latest` 会屏蔽它们（§7.2，实测 404 已证），
   所以「先发 rc 给少数人」不需要服务端开关。

### 11.3 一个前置陷阱

本仓 `AppVersion` 目前认为 `V1.2.0alpha > V1.2.0`，与 SemVer 的预发布序**相反**（§8.4）。所以第 6 条的
rc 通道要真用起来，**必须先改比较器**——否则 App 会把 rc 判成比正式版更新。

---

*本文为决策依据，不修改任何代码；相关改动（若采纳）应各自单开提交，并按仓库约定在
`docs/adr/` 沉淀「接入 release-please / 改用 SemVer tag 规约」这类架构级决定。*
