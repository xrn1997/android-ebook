package com.ebook.source.script

/** 白名单能力的落点：执行器进程内算完，还是要回主进程 */
enum class JsApiTarget { COMPUTE, HOST }

/**
 * Host API 白名单（ADR-0028 决策 6 的定版）。
 *
 * deny-by-default 在这里的含义是**这份表之外的名字在脚本里不存在**：不是「存在后被禁用」，
 * 而是 QuickJS 的全局对象上根本没有那个属性。所以这张表是唯一的能力清单，
 * 审查「脚本能干什么」只需要读这一个文件。
 *
 * [minArgs]/[maxArgs] 由 JS 垫片就地校验（`maxArgs == 0` 表示不限上限）。校验放在 JS 侧，
 * 是因为元数不符在内核里只会拿到 `undefined` 参数、然后走进一条看着「正常失败」的路径，
 * 症状会从「你少传了一个参数」变成「这个站解不开」。
 */
enum class JsHostApi(
    val jsName: String,
    val target: JsApiTarget,
    val minArgs: Int,
    val maxArgs: Int = minArgs,
) {
    // —— 纯计算：摘要与编码族 ——
    MD5("md5", JsApiTarget.COMPUTE, 1),
    SHA1("sha1", JsApiTarget.COMPUTE, 1),
    SHA256("sha256", JsApiTarget.COMPUTE, 1),
    BASE64_ENCODE("base64Encode", JsApiTarget.COMPUTE, 1),
    BASE64_DECODE("base64Decode", JsApiTarget.COMPUTE, 1),
    BASE64_ENCODE_URL("base64EncodeUrl", JsApiTarget.COMPUTE, 1),
    BASE64_DECODE_URL("base64DecodeUrl", JsApiTarget.COMPUTE, 1),
    HEX_ENCODE("hexEncode", JsApiTarget.COMPUTE, 1),
    HEX_DECODE("hexDecode", JsApiTarget.COMPUTE, 1),

    // —— 纯计算：对称加解密（签名 URL 类源的前提）——
    AES_ENCODE("aesEncode", JsApiTarget.COMPUTE, 2),
    AES_DECODE("aesDecode", JsApiTarget.COMPUTE, 2),
    DES_ENCODE("desEncode", JsApiTarget.COMPUTE, 2),
    DES_DECODE("desDecode", JsApiTarget.COMPUTE, 2),

    // —— 纯计算：签名与通用加解密一族（2026-09-11 第 2 批，语料实测次数写在每一条后面）——
    // 名字取「本仓自己的口径」，上游那些同义异名（`java.md5Encode` / `java.encodeURI` / `java.HMacBase64`）
    // 由垫片的别名行接进来：表里一个能力一个名字，别名清单只住在 JsHostApiTest 的 alias 一处。
    URI_ENCODE("uriEncode", JsApiTarget.COMPUTE, 1, 2),                  // 语料 26 次（含 charset 变体）
    RANDOM_UUID("randomUUID", JsApiTarget.COMPUTE, 0),                   // 语料 12 次
    DIGEST_HEX("digestHex", JsApiTarget.COMPUTE, 2),                     // 语料 10 次，第二参是站点写的算法名
    HMAC_BASE64("hmacBase64", JsApiTarget.COMPUTE, 3),                   // 语料 4 次，(数据, 算法, 密钥)
    SYMMETRIC_CRYPTO("symmetricCrypto", JsApiTarget.COMPUTE, 5),         // 语料 91 次 createSymmetricCrypto 的落点
    BYTES_TO_STR("bytesToStr", JsApiTarget.COMPUTE, 1, 2),               // 语料 10 次，把字节标记按字符集还原

    // —— 纯计算：时间 ——
    TIMESTAMP("timestamp", JsApiTarget.COMPUTE, 0),
    FORMAT_DATE("FormatDate", JsApiTarget.COMPUTE, 2),

    // —— 网络：一律经主进程受限代理 ——
    AJAX("ajax", JsApiTarget.HOST, 1, 2),

    // 语料 42/42 全是 `post(url, body, headers)`：第三参是请求头对象，缺席即不发自定义头
    POST("post", JsApiTarget.HOST, 2, 3),
    LOAD("load", JsApiTarget.HOST, 1, 2),
    RESPONSE_CODE("responseCode", JsApiTarget.HOST, 1),

    // —— 变量（规格 §5.2：JS 内用 `java.put` / `java.get`，任务级作用域）——
    PUT_VAR("putVar", JsApiTarget.HOST, 2),

    // 语料 313 次 `java.get` 里有 263 次是「读变量」的单参形态，另有 48 次是 `get(url, headers)`
    // 这种「带请求头的 GET 并取回响应对象」（`.statusCode()` / `.header()` / `.body()`）。
    // 本仓**不在表里给它出口**：取文层 `ScriptTransport.execute` 只回文本，状态码与响应头在那一步
    // 已经被丢掉，登记一个拿不到内容的名字等于让能力表说谎。缺口与处方记在规格 §11-35。
    GET_VAR("getVar", JsApiTarget.HOST, 1),
    REMOVE_VAR("rmVar", JsApiTarget.HOST, 1),

    // —— 源级自定义变量：整串读写，与上面按键存的是两个形状 ——
    // 名字带 `source` 前缀，是因为表里的 jsName 就是脚本写进 `__call(...)` 的那个名字，
    // 而脚本侧的 `source.getVariable()`（零参、整串）与 `book.getVariable(k)`（一参、按键）**同名不同形**：
    // 不加前缀，这张表就没法同时表达这两个入口（语料里前者 262 次全为零参、后者 40 次全为一参）。
    // 装配点在垫片的 `__installFaces`，脚本侧看到的仍是上游的 `source.getVariable` / `setVariable`。
    GET_SOURCE_VARIABLE("sourceGetVariable", JsApiTarget.HOST, 0),
    SET_SOURCE_VARIABLE("sourceSetVariable", JsApiTarget.HOST, 1),

    // —— 递归规则求值（脚本把规则串交回解释器）——
    GET_ELEMENTS("getElements", JsApiTarget.HOST, 1),
    GET_ELEMENT("getElement", JsApiTarget.HOST, 1),
    QUERY_STRING("queryString", JsApiTarget.HOST, 1),
    PUT_TO_PAGE("putToPage", JsApiTarget.HOST, 2),

    // 改写「当前页」：脚本自己 ajax 取回一页后，让后续的 getElements/getString 定位到那一页
    // （语料 31 次，写法一律是 `java.setContent(取回的文本, 该页地址)`）
    SET_CONTENT("setContent", JsApiTarget.HOST, 1, 2),

    // —— 会话 cookie：脚本侧是 `cookie.getCookie/setCookie/removeCookie`（语料实测 27/18/104 次调用）——
    // 表里带 `cookie` 前缀与变量族同一口径：`get`/`set` 这类裸名进了协议帧太容易被误读，
    // 名字换算只住在垫片的 defOn 装配行与 JsHostApiTest 的转发比对里。
    // 落点是源级会话罐（SourceCookieJar），三条都不发外呼、因此不烧取页配额。
    COOKIE_GET("cookieGet", JsApiTarget.HOST, 1),
    COOKIE_SET("cookieSet", JsApiTarget.HOST, 2),
    COOKIE_REMOVE("cookieRemove", JsApiTarget.HOST, 1),

    // —— 日志：toast 一族映射成日志，不弹 UI ——
    TOAST("toast", JsApiTarget.HOST, 1),
    LOG("log", JsApiTarget.HOST, 1),
    ;

    companion object {

        /** 脚本侧可见的名字全集；分发与一致性单测都以它为准 */
        val NAMES: Set<String> = entries.map { it.jsName }.toSet()

        /** 按脚本侧名字取能力，表外名字返回 null（调用方必须按拒绝处置，不得折叠成 null 结果） */
        fun byJsName(jsName: String): JsHostApi? = entries.firstOrNull { it.jsName == jsName }
    }
}

/** 白名单外的 api，或参数/密钥坏到算不下去 */
class JsApiRejectedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
