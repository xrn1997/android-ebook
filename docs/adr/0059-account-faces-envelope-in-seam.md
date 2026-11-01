# 账号接口在缝内消化信封并按调用方切窄面

Status: accepted

`UserDataSource` 的每个方法都回 `RespDTO` 信封；若让调用方各自消化，「比业务码 → 掏 `data` → 不然抛」就会在每个调用点各抄一份，一处漏改就是另一处的行为。决定把**信封消化收进缝内**（唯一出口 `UserEnvelope.kt`），面上只出现类型化结论；并按调用方把这组方法切成 `AccountSignIn` / `AccountPassword` / `AccountProfile` / `SessionRefresh` 四个窄面，宽接口整体删除。

## 背景

- **副本不是样板，是不变量散落**：业务码归谁判在调用图上完全看不出来——原先 `UserRepository` 的方法各写一遍 `resp.data ?: Unit`，`ModifyRepository` 的方法各写一遍 `resp.data ?: throw Exception(getString(...))`，新加一处只会照抄旧处，包括抄错的那处。
- **回执与载荷两档混在一句里**：`data: null` 对 `RespDTO<Unit>` 是服务端的正常形态（登出、发码、注册都这么出货），对 `RespDTO<User>` 才是「没给东西」。调用方写 `resp.data ?: Unit` 时并不知道自己在替一档语义做兜底。
- **刷新那条链本来就自己判了一遍码**：`SessionTokenRefresher` 原先内联 `if (resp.code == SUCCESS) resp.data else null`——因为它**必须**绕开 `CoroutineAdapter`（ADR-0010）。同一件事因此在业务面与刷新面各有一份写法。
- **宽接口把测试替身也撑宽**：只想测单飞守卫的 `SessionTokenRefresherTest` 不得不按整张宽面接一个假件，其中绝大部分方法只是 `error("unused in this test")` 占位。

## 决策

1. **信封只消化一次，两个实现共用同一出口**：`lib_ebook_api` 的 `UserEnvelope.kt` 是账号域唯一读 `RespDTO` 的地方，真实后端（`UserNetwork`）与 mock（`UserNetworkTest`）都经它——两处的差别只在「信封从哪来」，「怎么折成结论」必须是一份，否则 mock flavor 演练的是另一套失败语义。
2. **三面回 `Result<载荷>`，失败用既有异常词汇**：业务码非成功仍是 `CoroutineAdapter.ApiException`（服务端文案与五位码都留着，ADR-0007），A0230 的单飞静默刷新与重放一次仍由 `CoroutineAdapter` 负责、`SessionExpiredException` 出口不变——所以 `reportFailure` 的口径保持不变。这一条是刻意选择的：账号面**不**另造一套密封失败层级，否则会跟 [ADR-0045](0045-source-binding-typed-result.md) 给取源定的词汇并行长成第二套错误语言。
3. **成功码却没有载荷是一档独立失败（`MissingPayloadException`）**，不许与服务端明确报出来的业务码混成一句话：前者排查方向是契约/序列化，后者是用户真的做错了什么。它**不带用户可见文案**——「这次失败该怎么说」只有调用方知道（`R.string.modify_nickname_failed` 之类的页面文案不能上移到共享库），文案翻译收成一个函数 `missingPayloadAs`，而不是在每个调用点重写 `data ?: throw`。
4. **回执型端点走另一条兜底方向**：`RespDTO<Unit>` 的 `data: null` 折成成功（`acknowledged`），只有回载荷的端点判缺载荷（`concluding`）。两条不许互换：把回执当缺载荷会让每个 `data: null` 的成功端点永远报错；把载荷端点当回执会让页面拿 null 装成功。
5. **`uploadAvatar` 收字节 + 文件名**：`MultipartBody.Part`、multipart 字段名 `avatar` 与媒体类型都是传输细节，归适配器；结论直接是可访问 URL，空串按缺载荷处置。
6. **会话刷新面是唯一的例外，回密封的 `TokenRefresh`（`Rotated` / `Rejected` / `NotEstablished`）而不是 `Result`**：这一面天生不经 `safeApiCall`，所以它的失败词汇不能是「`Result` 里那条会被判成 A0230 的异常」。失败按成因分两档，判据是**服务端有没有把「不给续」作为答复内容说出来**：业务码非成功才是 `Rejected`（`code` 因此总带值，作排查线索）；压根没打通（传输异常、服务端地址未配置）与「成功码却没有 access token」（刷新端的载荷缺失，结论未定）同归 `NotEstablished`。两档对续期仍是同一个处置——没有新凭证可用，网络层发一次会话过期，分档只服务归因与日志，不改变行为；把两者合成一句「刷新被拒」就是让日志替两件排查方向相反的事背书（`CONTEXT.md`「续期结论」的 `_Avoid_` 钉的正是这条）。`Rotated.refreshToken` 可空：「缺 `refresh_token` 时保留旧凭据」是持久层的处置（ADR-0011），面只如实说服务端没给。`CancellationException` 不折进任何失败档而是原样上抛——取消不是「会话救不回来」，吞掉会误发 `SessionExpired` 把用户踢到登录页。
7. **切分判据是调用方**（同 [ADR-0055](0055-book-repository-narrow-faces.md)、ADR-0047）：`UserRepository` 注 `AccountSignIn` + `AccountPassword` 两面，`ModifyRepository` 只注 `AccountProfile`，`SessionTokenRefresher` 只注 `SessionRefresh`；每面 KDoc 点名自己的消费者。**找回密码与已登录改密合成一张 `AccountPassword`**：它们的消费者、页面与失败口径都是 `ModifyPwdViewModel` 那条双路径，按调用方切的判据在此只给出一个面。**`logout` 留在登录注册面而不是刷新面**：它是用户**主动**作废全部 refresh token，与「过期后续期」是两件事（ADR-0019），放进刷新面就把「救不回来的会话」和「不想再要了」混成同一张能力表。
8. **聚合宽接口删除，四面各一条 `@Binds @Singleton` 指向同一个实现**：账号域没有任何生产或测试消费者需要整张面，留一条聚合绑定就等于给宽接口留一条注入口（与 ADR-0055 唯一的差别：这里连聚合类型都不存在）。拆实例会静默复制基址与 mock 内存态，故四面必同单例。
9. **`Lazy<SessionRefresh>` 断开构造环**：刷新面与网络适配器是同一个单例，而适配器要用 `TokenRefresher` 处置 A0230——直连即成 `UserNetwork → CoroutineAdapter → SessionTokenRefresher → UserNetwork` 的环。推迟到真要刷新时再取（首次刷新前 `CoroutineAdapter` 必然已装配完），运行时不自引用。
10. **mock flavor 补「非成功码」可达路径**：`UserNetworkTest` 原先只回放成功资产，「业务码非成功」那一档在无后端构建里永远走不到——失败文案上屏与「刷新被拒 → 会话过期 → 跳登录」整条链都得连真后端才验得动。现在任一文本入参等于 `fail`（`REJECT_PROBE`）即回放 `user_rejected.json`；开关只在 mock flavor 与独立调试宿主有意义，真实后端不看这些值。

## 边界

- **时序语义保持不变**：单飞互斥、A0230 重放一次即止、会话过期只发一次、`rotateCredentials` 只换凭证不重建身份；`SessionTokenRefresherTest` 的时序断言照旧覆盖这些口径（假件只实现刷新面这一个方法、以结论档位替代信封，失败两档各有「送达的是哪一档」的断言），(d) 组把两条出口**直接**钉在生产装配上。
- **评论域不在本决策内**：`CommentRepository` 里同形状的拆信封与 `CommentDataSource` 那张面保持原状。
- `UserService`（Retrofit 契约）、`RespDTO` 形态与 ADR-0007 的客户端契约保持不变——本决策只决定**谁来读它**。
- `REJECT_PROBE` 不是服务端契约的一部分，不许把它当真实错误码体系的一部分去推断后端行为。

## 后果

- 注入点即意图：`UserRepository` 的构造参数写着它服务登录注册与密码两条流程，`ModifyRepository` 只写着资料面，`SessionTokenRefresher` 只写着刷新面。新增账号端点必须先决定归哪一面——没有「先塞进宽接口、以后再分」的路径。
- 拆信封副本全部消失：`UserRepository` 除 `login` 外的方法塌成一行直转（`Result<Unit>` 原样透出），`login` 只剩「换域类型 + 挑一句自己的文案」；`ModifyRepository` 同理。
- 测试替身按面接：`SessionTokenRefresherTest` 的 `error("unused in this test")` 占位实现全部消失。
- `RespDTO` 不再出现在账号域任何公开签名里，`module_login` / `module_me` 不再需要为了「看业务码」而依赖 `lib_common` 的信封类型。

## 被否方案

- **让窄面继续回 `RespDTO`，只在 `UserRepository` 里抽一个 `unwrap()` 复用**：被否。那是把散在各调用点的副本搬成一处副本，接缝上仍然到处流着信封；「谁来判业务码」在调用图上依旧看不出来。
- **按调用方只切两面**（`UserRepository` 一面、`ModifyRepository` 一面）：被否。消费者是三个而不是两个，且 `SessionTokenRefresher` 要恰恰是唯一那条不走 `safeApiCall` 的能力——混进任一业务面就会有人顺手把它接进 `Result` 管道，把死循环守卫写没。
- **把 `TokenRefresh` 也统一成 `Result<T>`**：被否。见决策 6：这一面的失败不是一句异常能表达的，且它必须显式地不走那条把异常折成会话过期的管道。
- **把 `MissingPayload` 做成 sealed 结论族（仿 `SourceBinding` 四档）**：被否。取源那四档之所以要密封，是因为 null 同时代表四种成因；账号面剩下的只有「载荷没给」这一档，为它开一层密封会把 `Result` + 类型化异常这套已在用的语言拆成两套。
- **给 `MissingPayloadException` 造一句通用文案**：被否。页面文案上移到共享库就回不去了，且通用文案会把「这次是改昵称还是换头像」糊成一团（文案归页面见 [ADR-0061](0061-semantic-notice-layering.md)）。
- **四面各一个实现类**：被否。它们要各自持有一份 lazy Retrofit 与基址快照，而基址的唯一读取口本就该是进程内一份（ADR-0052/0053）。
- **`uploadAvatar` 继续收 `MultipartBody.Part`**：被否。字段名 `avatar` 是服务端契约，调用方拼 Part 等于把契约抄进 `module_me`，日后换上传端点要改两个模块。

锚点：`lib_ebook_api` 的 `UserFaces.kt`（四面声明与逐面 KDoc、`TokenRefresh`、`MissingPayloadException`、`missingPayloadAs`）、`UserEnvelope.kt`（唯一信封消化出口）、`UserNetwork.kt`（真实实现与 multipart 归位）、`UserNetworkTest.kt`（`REJECT_PROBE` 非成功码开关）。

`lib_book_common` 的 `SessionTokenRefresher`（只消费结论 + `Lazy` 断环）。绑定见 `module_app/src/real`、`module_app/src/mock` 的 `NetworkModule` 与 `debug-host` 的 `MockNetworkModule`，各四条 `@Binds @Singleton`。

读数：`UserFacesDigestTest`（三档兜底方向 + 刷新面两档失败的成因分档）、`UserNetworkTestTest`（mock 资产契约 + 非成功码可达）、`AvatarPartTest`（头像 multipart 的字段名与媒体类型——`UserService.uploadAvatar` 收裸 `Part`，没有别处在校验这两个值）、`SessionTokenRefresherTest`（单飞与刷新面两条出口）。

相关章节：`docs/architecture.md` §5、`CONTEXT.md`「账号窄面」「信封消化」「载荷缺失」「续期结论」、`docs/development.md` §3「mock 的非成功码开关」。
