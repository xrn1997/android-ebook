package com.ebook.source.sandbox

import com.ebook.source.script.JsApiTarget
import com.ebook.source.script.JsHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单的两处事实源必须一致：`JsHostApi` 表与 [QuickJsPrelude.SOURCE]。
 *
 * 这条测试是「加一个能力只改了一处」这类事故的拦网。表里有、垫片里没有 → 脚本调不到，
 * 静默少一项能力；垫片里有、表里没有 → 分发时按未知 api 拒绝，同样是静默失败。
 * 两者都是当场看不出来、只在真实源上表现成「解不开」的错。
 */
class JsHostApiTest {

    /** 垫片里显式 `def('name'…` 登记的名字 */
    private val declared: Set<String> =
        Regex("""def\('([A-Za-z0-9_]+)'""").findAll(QuickJsPrelude.SOURCE).map { it.groupValues[1] }.toSet()

    /** 垫片里带字面量元数登记的名字 → 元数（`def('x', min, max` 三处一一对应） */
    private val declaredArity: Map<String, Pair<Int, Int>> =
        Regex("""def\('([A-Za-z0-9_]+)', *(\d+), *(\d+)""")
            .findAll(QuickJsPrelude.SOURCE)
            .associate { it.groupValues[1] to (it.groupValues[2].toInt() to it.groupValues[3].toInt()) }

    /** 垫片里由 `computeNames` 循环登记的名字（没有独立的 `def('x'` 字面量） */
    private val compute: Set<String> = QuickJsPrelude.SOURCE
        .substringAfter("var computeNames = [")
        .substringBefore("];")
        .let { block -> Regex("""'([A-Za-z0-9_]+)'""").findAll(block).map { it.groupValues[1] }.toSet() }

    /**
     * `javaAliases` 那一族：`['脚本侧名字', '表里的 api']`（第三项是缺席时的默认实参，不参与比对）。
     *
     * 不经 `def(` 字面量登记，所以 [declaredArity] 看不见它们——不是漏，是这一族的名字与表里的 api
     * **不同名**（`md5Encode` ↔ `md5`），按字面量登记会让每条改名都在「表里查无此名」上撞红。
     * 换了装配方式就得换一种锁法：本属性按对解析，比的是「转发目标在表里」。
     */
    private val javaAliases: List<Pair<String, String>> = QuickJsPrelude.SOURCE
        .substringAfter("var javaAliases = [")
        .substringBefore("];")
        .let { block ->
            Regex("""\['([A-Za-z0-9_]+)', *'([A-Za-z0-9_]+)'""")
                .findAll(block)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
        }

    /** `faceApi` 对象上的方法名：只存在于脚本侧的门面（把一次调用包成对象或一步），表里没有同名项 */
    private val faceNames: Set<String> = QuickJsPrelude.SOURCE
        .substringAfter("var faceApi = {")
        .substringBefore("\n};")
        .let { block -> Regex("""([A-Za-z0-9_]+): function""").findAll(block).map { it.groupValues[1] }.toSet() }

    /**
     * `defOn` 那一族的装配行：`(目标对象, 脚本侧方法名, 最少, 最多, 转发到哪个 api)`。
     *
     * 刻意**不并进** [declaredArity] 的比对：那里第一个字面量就是 api 名，而这一族的第一个字面量是
     * 脚本侧的名字（`source.put` 转发到 `putVar`），两者不同名，混在一起比对会让每条转发都「表里查无此名」。
     * 单列一条按行解析的测试，锁「转发不改形状」这条真正的不变式。
     */
    private val forwarded: List<Triple<String, Pair<Int, Int>, String>> =
        Regex("""defOn\(([A-Za-z]+), '([A-Za-z0-9_]+)', (\d+), (\d+), [A-Za-z]+\('([A-Za-z0-9_]+)'\)\);""")
            .findAll(QuickJsPrelude.SOURCE)
            .map { Triple(it.groupValues[1], it.groupValues[3].toInt() to it.groupValues[4].toInt(), it.groupValues[5]) }
            .toList()

    /** 只由 `source` 一族经 `fwdVar` 转发的变量 api：不经 `def(` 登记，也不挂 globalThis */
    private val sourceOnly = setOf("sourceGetVariable", "sourceSetVariable")

    /**
     * 一切「只经 `defOn` 转发行过边界」的 api：`source` 变量族与 `cookie` 族都是这个形状。
     *
     * 它们没有 `def('name'` 字面量，也不挂到 globalThis（脚本侧的名字与表里的 api 不同名），
     * 所以「表里每项都在垫片里」那条比对必须把它们减去——不是漏登记，是登记在装配行上。
     */
    private val forwardedOnly: Set<String> = forwarded.map { it.third }.toSet()

    /** 垫片里 `__call('name'` 出现的字面量名字（`__call(n, ...)` 那种变量形态不在此列，由 computeNames 锁） */
    private val calledDirectly: Set<String> =
        Regex("""__call\('([A-Za-z0-9_]+)'""")
            .findAll(QuickJsPrelude.SOURCE)
            .map { it.groupValues[1] }
            .toSet()

    /** `reject(...)` 一族：故意不入表——它们不该被分发，只该在 JS 侧就报错 */
    private val rejectedStub = setOf(
        "connect", "getConnect", "response", "req", "assets", "files", "Files", "bookSource", "cookieManager",
        "ajaxAll",
    )

    /** 垫片里 `reject(...)` 那一族的数组字面量，须与 [rejectedStub] 逐字同集合 */
    private val rejectedInPrelude: Set<String> = QuickJsPrelude.SOURCE
        .substringAfter("var reject = function")
        .substringBefore(".forEach")
        .substringAfter("[")
        .let { block -> Regex("""'([A-Za-z0-9_]+)'""").findAll(block).map { it.groupValues[1] }.toSet() }

    /**
     * `source` 对象上的拒绝桩：登录态与「刷新 / 批量」一族（ADR-0028 决策 6 里本仓不做的能力）。
     *
     * 与 [rejectedStub] 分列两条，是因为它们挂在不同对象上（那一条在 `java.*`、这一条在 `source`），
     * 装配写法也不同（`def` 与 `defOn`）——合成一个集合就锁不住「哪一侧漏了」。
     */
    private val sourceRejectStub = setOf(
        "getLoginInfoMap", "getLoginInfo", "putLoginInfo", "putLoginInfoMap", "removeLoginInfo",
        "getLoginHeader", "getLoginHeaderMap", "putLoginHeader", "removeLoginHeader",
        "refreshExplore", "refreshJSLib", "putConcurrent",
    )

    /**
     * 变量族的重命名：脚本侧沿用上游客体（`java.put` / `java.get` / `java.rmKey`），
     * 表与协议里是 `putVar` / `getVar` / `rmVar`（`get`/`put` 这类裸名进了协议帧就太容易被误读）。
     * 这条映射只在本文件定义一次，两侧比对都先过它。
     */
    private val alias = mapOf("put" to "putVar", "get" to "getVar", "rmKey" to "rmVar")

    /** 垫片登记的名字按别名折算成表里的名字 */
    private val tableNamesInPrelude: Set<String> =
        (declared + compute).map { alias[it] ?: it }.toSet()

    @Test
    fun `能力表里每一项都在垫片里登记`() {
        val missing = JsHostApi.NAMES - tableNamesInPrelude - forwardedOnly
        assertTrue("这些 api 在表里、垫片里没有，脚本调不到：$missing", missing.isEmpty())
    }

    @Test
    fun `垫片里每个 __call 的 api 名字都在能力表里`() {
        // 名字过边界的方式有两种（`def(` 直接登记、`defOn` 经 fwdVar 转发），两种都在表里查得到才算数：
        // 只对上一半的话，另一半改名会全绿通过，症状是脚本调到主进程只得到「沙箱里没有这个能力」
        val unknown = calledDirectly - JsHostApi.NAMES
        assertTrue("垫片调了表外的 api：$unknown", unknown.isEmpty())
        assertTrue(
            "source 族的能力走 defOn 转发（改了实现方式就要同步改本测试的排除集）：$forwarded",
            sourceOnly.all { api -> forwarded.any { it.third == api } },
        )
    }

    @Test
    fun `对象面的转发逐条对得上能力表`() {
        // 这一族的形状是「脚本侧名字 ≠ api 名」（`source.put` → `putVar`、`cookie.getCookie` → `cookieGet`），
        // 所以不能并入 def 那条比对；但「转发不改形状」是同一类不变式：目标只可能是那几个对象，
        // api 必须在表里、元数必须同值。
        assertTrue("垫片里没解析到任何 defOn 转发行（装配行的写法被改动了？）", forwarded.isNotEmpty())
        val faces = setOf("source", "book", "chapter", "cookie", "java")
        val problems = forwarded.mapNotNull { (target, arity, api) ->
            val entry = JsHostApi.byJsName(api)
            when {
                target !in faces -> "$target 不是本仓装配的对象面之一（$faces）"
                entry == null -> "$target.$api 转发了表外的 api：$api"
                (entry.minArgs to entry.maxArgs) != arity ->
                    "$target.$api 转发的 $api 元数与表不符（垫片 $arity，表 ${entry.minArgs}..${entry.maxArgs}）"
                else -> null
            }
        }
        assertTrue("对象面与能力表不一致：$problems", problems.isEmpty())
    }

    @Test
    fun `source 的拒绝桩名单在垫片与测试两侧同集合`() {
        val block = QuickJsPrelude.SOURCE
            .substringAfter("var sourceReject = [")
            .substringBefore("];")
        val inPrelude = Regex("""'([A-Za-z0-9_]+)'""").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(sourceRejectStub, inPrelude)
        // 名单里的名字必须真的在表外：进了表就意味着这项能力已落地，拒绝桩却还留着，
        // 症状是「脚本明明能用」的源在沙箱里被一句话挡掉
        val landed = sourceRejectStub.filter { it in JsHostApi.NAMES }
        assertTrue("拒绝桩名单里有已实现的能力，该删桩接真分发：$landed", landed.isEmpty())
        // 光有名单不够：名单必须真的被装配成方法，否则脚本调它拿到的是 undefined 而不是那句可诊断的话
        assertTrue(
            "拒绝桩必须以 defOn(source, ...) 的形式装配",
            QuickJsPrelude.SOURCE.contains("defOn(source, sourceReject[i], 0, 0, reject(sourceReject[i]))"),
        )
    }

    @Test
    fun `垫片登记的白名单名字全在表里`() {
        val unknown = tableNamesInPrelude - JsHostApi.NAMES - rejectedStub
        assertTrue("垫片登记了表外的名字：$unknown", unknown.isEmpty())
    }

    @Test
    fun `拒绝桩一族在垫片与测试两侧同名单`() {
        // 名单漂移的症状是「脚本调它得到 ReferenceError 而不是可诊断的那句话」，
        // 两侧任一处改名或漏改，这条立刻红
        assertEquals(rejectedStub, rejectedInPrelude)
        assertTrue(
            "批量外呼在导入侧被点名不支持，必须有拒绝桩而不是让脚本撞进 ReferenceError",
            "ajaxAll" in rejectedInPrelude,
        )
    }

    @Test
    fun `垫片里 def 登记的名字与表的 jsName 一一对应（变量族的重命名不静默）`() {
        // java.put / java.get / java.rmKey 是上游名字，表里是 putVar / getVar / rmVar：
        // 这条映射只在这一处定义，改名必须同时改测试，否则会出现「表与垫片各说各话」
        alias.forEach { (js, table) ->
            assertTrue("$js 必须在垫片里登记：$declared", js in declared)
            assertTrue("$table 必须在表里：${JsHostApi.NAMES}", table in JsHostApi.NAMES)
        }
        // 反方向同样不许静默：表里的变量族只能靠这三条别名对上
        assertEquals(setOf("putVar", "getVar", "rmVar"), alias.values.toSet())
    }

    @Test
    fun `HOST 类能力正好覆盖网络变量递归求值 cookie 与日志五族`() {
        assertEquals(
            setOf(
                "ajax", "post", "load", "responseCode",
                "putVar", "getVar", "rmVar",
                "sourceGetVariable", "sourceSetVariable",
                "getElements", "getElement", "queryString", "putToPage",
                "setContent",
                "cookieGet", "cookieSet", "cookieRemove",
                "toast", "log",
            ),
            JsHostApi.entries.filter { it.target == JsApiTarget.HOST }.map { it.jsName }.toSet(),
        )
    }

    @Test
    fun `COMPUTE 类能力正好覆盖摘要编码加解密与时间族`() {
        assertEquals(
            setOf(
                "md5", "sha1", "sha256",
                "base64Encode", "base64Decode", "base64EncodeUrl", "base64DecodeUrl",
                "hexEncode", "hexDecode",
                "aesEncode", "aesDecode", "desEncode", "desDecode",
                "timestamp", "FormatDate",
                "uriEncode", "randomUUID", "digestHex", "hmacBase64", "symmetricCrypto", "bytesToStr",
            ),
            JsHostApi.entries.filter { it.target == JsApiTarget.COMPUTE }.map { it.jsName }.toSet(),
        )
        // 纯计算的名字必须真的由 computeNames 循环挂到 globalThis（语料里顶层裸调 md5(...) 很常见）
        assertEquals(
            JsHostApi.entries.filter { it.target == JsApiTarget.COMPUTE }.map { it.jsName }.toSet(),
            compute,
        )
    }

    @Test
    fun `改名族的转发目标都在表里`() {
        // 这一族的元数刻意是 0,0（不限）：它转发的 COMPUTE 能力在表里统一按「不限」登记，
        // 而 HOST 那一支（getString）由目标 api 自己的字面量 def 守住元数。
        assertTrue("垫片里没解析到任何 javaAliases 行（装配写法被改动了？）", javaAliases.isNotEmpty())
        val unknown = javaAliases.filter { it.second !in JsHostApi.NAMES }.map { "${it.first}→${it.second}" }
        assertTrue("这些脚本侧名字转发到表外的 api：$unknown", unknown.isEmpty())
        // 同名项不属于「改名」：它该走 def( 字面量登记，好让元数那条比对看得见它
        val sameName = javaAliases.filter { it.first == it.second }.map { it.first }
        assertTrue("改名族里有同名的项，该改走 def( 登记：$sameName", sameName.isEmpty())
        assertEquals(
            setOf("md5Encode", "hexDecodeToString", "HMacBase64", "encodeURI", "getString", "timeFormat", "longToast"),
            javaAliases.map { it.first }.toSet(),
        )
        assertTrue(
            "改名族必须以 defOn(java, p[0], ...) 的形式装配，否则脚本侧根本没有这个名字",
            QuickJsPrelude.SOURCE.contains("defOn(java, p[0], 0, 0, function ()"),
        )
    }

    @Test
    fun `门面族只在脚本侧存在且名单不漂移`() {
        // 门面的产物是对象或多步调用（createSymmetricCrypto 建对象、aesBase64DecodeToString 一步到底），
        // 表里那些「一次调用一个 api」的条目表达不了，所以它们天生没有同名表项——
        // 反过来说，一旦某个门面进了表，它就是被当成能力重复登记了两次，症状是两处元数口径各说各话
        assertEquals(
            setOf(
                "createSymmetricCrypto", "aesBase64DecodeToString",
                "base64DecodeToByteArray", "hexDecodeToByteArray", "strToBytes",
            ),
            faceNames,
        )
        val landed = faceNames.filter { it in JsHostApi.NAMES }
        assertTrue("门面族里有已进表的名字，该改走 def( 或 computeNames：$landed", landed.isEmpty())
        assertTrue(
            "faceApi 的名字必须真的挂到 java 面上，否则脚本调它得到 undefined 而不是可诊断的那句话",
            QuickJsPrelude.SOURCE.contains("Object.keys(faceApi).forEach(function (n) { java[n] = faceApi[n]; });"),
        )
    }

    @Test
    fun `HOST 族的元数在表与垫片两侧同值`() {
        // 上限为 0 在表里表示「不限」，而纯计算族统一走 def(n, 0, 0 循环（无字面量元数），
        // 所以这里只比对带字面量的 HOST 族：两侧任一处改了元数，另一处当场对不上
        val mismatches = declaredArity
            .filterKeys { it !in rejectedStub }
            .mapKeys { (js, _) -> alias[js] ?: js }
            .filter { (table, arity) ->
                val entry = JsHostApi.byJsName(table) ?: return@filter true
                entry.target == JsApiTarget.HOST && (entry.minArgs to entry.maxArgs) != arity
            }
        assertTrue("表与垫片的元数不一致：$mismatches", mismatches.isEmpty())
        // HOST 族过边界的出口有两种形态：`def('name', …` 的字面量登记与 `defOn(目标, …, fwdVar('api'))`
        // 的转发。两者合起来必须恰好盖住表里的 HOST 族——多了说明有新名字没进表，
        // 少了说明表里某项能力在垫片上根本没有出口
        assertEquals(
            JsHostApi.entries.filter { it.target == JsApiTarget.HOST }.map { it.jsName }.toSet(),
            declaredArity.keys.map { alias[it] ?: it }.toSet() +
                forwarded.map { it.third } +
                javaAliases.map { it.second }.filter { JsHostApi.byJsName(it)?.target == JsApiTarget.HOST }.toSet(),
        )
    }

    @Test
    fun `垫片不含美元符号，避免 Kotlin 原始字符串的模板起点`() {
        assertTrue("出现模板起点会让垫片静默被插值破坏", !QuickJsPrelude.SOURCE.contains('$'))
    }

    @Test
    fun `桥的出口与绑定入口各只有一处定义`() {
        assertEquals(1, Regex("""var __call = function""").findAll(QuickJsPrelude.SOURCE).count())
        assertEquals(1, Regex("""var __bind = function""").findAll(QuickJsPrelude.SOURCE).count())
    }

    @Test
    fun `表按脚本侧名字查得到能力，表外名字查不到而不是回一个默认值`() {
        // byJsName 回 null 只表示「不在这张表里」，分发方必须据此拒绝；
        // 若将来有人把它改成 firstOrNull { ... } ?: MD5 之类，这条会红
        assertEquals(JsHostApi.MD5, JsHostApi.byJsName("md5"))
        assertEquals(JsHostApi.AJAX, JsHostApi.byJsName("ajax"))
        assertEquals("putVar", JsHostApi.PUT_VAR.jsName)
        assertNull(JsHostApi.byJsName("cache"))
        assertNull(JsHostApi.byJsName("Java"))
        assertEquals(JsHostApi.entries.size, JsHostApi.NAMES.size)
    }
}
