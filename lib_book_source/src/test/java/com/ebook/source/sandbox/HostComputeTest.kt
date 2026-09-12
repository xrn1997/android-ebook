package com.ebook.source.sandbox

import com.ebook.source.script.JsApiRejectedException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯计算类 Host API 的实现。
 *
 * 这一层刻意留在 Kotlin 而不是 C++（ADR-0028 偏差 1），**为的就是这个文件能跑**：
 * 摘要与分组密码的正确性只有拿已知向量当场比才对得起「能解开签名 URL」这句话，
 * 而 C++ 实现要等到设备上才看得到结果。
 */
class HostComputeTest {

    private fun args(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(it) } }

    private fun compute(api: String, vararg values: String): String =
        Json.decodeFromJsonElement(String.serializer(), HostCompute.invoke(api, args(*values)))

    private fun dataOf(api: String, vararg values: String): JsonElement = HostCompute.invoke(api, args(*values))

    @Test
    fun `三种摘要对已知向量`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", compute("md5", "abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", compute("sha1", "abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            compute("sha256", "abc"),
        )
        // 空串是签名 URL 里常见的一段，字节序错了整站解不开
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", compute("md5", ""))
    }

    @Test
    fun `摘要按 UTF-8 字节参与计算且输出小写十六进制`() {
        // 非 ASCII：UTF-8 字节序列参与摘要，与「先转 latin1」是两种结果，锁住
        // （期望值由 `printf '\345\260\217\350\257\264' | md5sum` 与 `openssl dgst -md5` 两处独立复算）
        assertEquals("1fb52965b3af8e431578f16d6c31a5d5", compute("md5", "小说"))
        assertTrue(compute("sha256", "x").all { it.isLowerCase() || it.isDigit() })
    }

    @Test
    fun `base64 族编解码与 URL 安全形态`() {
        assertEquals("YWJj", compute("base64Encode", "abc"))
        assertEquals("abc", compute("base64Decode", "YWJj"))
        // 标准形态与 URL 安全形态必须可区分：签名 URL 里的 base64 会被 +/= 转义吃掉。
        // "????" 的三字节组正好产出索引 63（标准表 '/'、URL 表 '_'）与尾部 "=="，
        // 一个向量同时锁住「换表」与「去填充」两处差别。
        assertEquals("Pz8/Pw==", compute("base64Encode", "????"))
        assertEquals("Pz8_Pw", compute("base64EncodeUrl", "????"))
        assertEquals("????", compute("base64DecodeUrl", "Pz8_Pw"))
        assertTrue(compute("base64EncodeUrl", "????").none { "+/=".contains(it) })
        // 非 ASCII 走的是 UTF-8 字节（"ÿþ" → c3bfc3be），与摘要同一套字节口径
        assertEquals("w7/Dvg==", compute("base64Encode", "ÿþ"))
        assertEquals("w7_Dvg", compute("base64EncodeUrl", "ÿþ"))
        assertEquals("ÿþ", compute("base64DecodeUrl", compute("base64EncodeUrl", "ÿþ")))
    }

    @Test
    fun `hex 编解码往返且不被负字节符号扩展污染`() {
        assertEquals("616263", compute("hexEncode", "abc"))
        assertEquals("abc", compute("hexDecode", "616263"))
        // 0xff 若按 Byte 直接格式化会变成 8 位（ffffffxx），这里必须恰好 2 位。
        // 入参统一是字符串，所以取「UTF-8 编码后含负字节」的文本：ÿ → c3 bf、小说 → e5 b0 8f e8 af b4，
        // 每字节恰好 2 个十六进制位，符号扩展一漏就当场多出一截 ffffff。
        assertEquals("c3bf", compute("hexEncode", "ÿ"))
        assertEquals("e5b08fe8afb4", compute("hexEncode", "小说"))
        assertEquals("ÿ", compute("hexDecode", "c3bf"))
    }

    @Test
    fun `AES 往返且同键同文明文必得同密文`() {
        val key = "0123456789abcdef"
        val plain = "第一章 山村"
        val cipher = compute("aesEncode", plain, key)
        assertEquals(plain, compute("aesDecode", cipher, key))
        // 没有随机 IV（IV 由 key 派生），所以密文可复现。这条断言锁的是
        // 「将来有人顺手改成随机 IV」——那会让源里 aesEncode 的结果不再能当 URL 参数复用。
        assertEquals(cipher, compute("aesEncode", plain, key))
    }

    @Test
    fun `短密钥补零到块长且长密钥截断，都不抛`() {
        val short = compute("aesEncode", "abc", "k")
        assertEquals("abc", compute("aesDecode", short, "k"))
        val longKey = "0123456789abcdefghijklmnopqrstuvwxyz"
        val long = compute("aesEncode", "abc", longKey)
        assertEquals("abc", compute("aesDecode", long, longKey))
    }

    @Test
    fun `DES 往返`() {
        val cipher = compute("desEncode", "abc", "12345678")
        assertEquals("abc", compute("desDecode", cipher, "12345678"))
    }

    @Test
    fun `密文不是合法 base64 时按拒绝报错而不是返回坏值`() {
        val e = runCatching { HostCompute.invoke("aesDecode", args("不是密文", "0123456789abcdef")) }
            .exceptionOrNull()
        assertTrue("必须抛类型化拒绝：$e", e is JsApiRejectedException)
        assertTrue(e!!.message!!.contains("aesDecode"))
    }

    @Test
    fun `时间格式化产出形状正确，时间戳是数字文本`() {
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(compute("FormatDate", "0", "yyyy-MM-dd")))
        assertTrue(compute("timestamp").all { it.isDigit() })
    }

    @Test
    fun `未知 api 一律拒绝，绝不静默回 null`() {
        // null 在垫片里的形态是「调用成功、值为 null」，把不存在的 api 折叠成 null
        // 等于让脚本以为这次调用真的生效了
        val e = runCatching { HostCompute.invoke("nope", args()) }.exceptionOrNull()
        assertTrue(e is JsApiRejectedException)
    }

    @Test
    fun `参数缺失按拒绝报出缺第几个参数`() {
        val e = runCatching { dataOf("FormatDate", "0") }.exceptionOrNull()
        assertTrue(e is JsApiRejectedException)
        assertTrue(e!!.message!!.contains("第 2 个参数"))
    }

    // ==== 第 2 批：签名与加解密一族 ====
    //
    // 期望值一律由 OpenSSL 3.5.6 独立复算（`printf 'hello' | openssl enc -aes-128-cbc -K … -iv … -a -A`
    // 与 `openssl dgst -sha256 -hmac k3y -binary | openssl base64 -A`），不取本仓实现自产的值：
    // 自产向量只能证明「今天与昨天一致」，证明不了「能解开真实站点的签名」。

    @Test
    fun `uriEncode 按表单百分号编码且可指定字符集`() {
        // 语料里 java.encodeURI(key) 是拼查询串用的，URLEncoder 的「空格成 +」正是站点要的形态
        assertEquals("%E5%89%91%E6%9D%A5+1", compute("uriEncode", "剑来 1"))
        // 少数老站按 GBK 解关键词，第二参给出字符集就有出口（语料 4 处 'gb2312'/'GBK'）
        assertEquals("%D0%A1%CB%B5", compute("uriEncode", "小说", "GBK"))
    }

    @Test
    fun `randomUUID 给出标准形状`() {
        assertTrue(
            "必须是 8-4-4-4-12 的小写十六进制：${compute("randomUUID")}",
            Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""").matches(compute("randomUUID")),
        )
    }

    @Test
    fun `digestHex 认站点写的算法名且大小写与连字符不敏感`() {
        // 已知向量：printf 'signStr' | openssl dgst -sha256 -r
        assertEquals(
            "7eaf40143843e9fba344c8b6d3b4bc087eb8ad6033fc625da236903bae57152a",
            compute("digestHex", "signStr", "SHA-256"),
        )
        // 语料两种写法都有（"SHA-256" 与 'sha-256'），而 JCA 只认后者去掉连字符再首字母大写的形态
        assertEquals(compute("digestHex", "signStr", "SHA-256"), compute("digestHex", "signStr", "sha-256"))
        assertEquals(compute("digestHex", "signStr", "SHA-256"), compute("digestHex", "signStr", "SHA256"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", compute("digestHex", "abc", "md5"))
        // 认不出来的算法必须报拒绝：默默换成 MD5 会算出一个「看着像」的签名，站点回 401 时才查
        val e = runCatching { compute("digestHex", "abc", "SM4") }.exceptionOrNull()
        assertTrue(e is JsApiRejectedException)
    }

    @Test
    fun `hmacBase64 对 OpenSSL 已知向量`() {
        // 已知向量：printf 'hello' | openssl dgst -sha256 -hmac 'k3y' -binary | openssl base64 -A
        assertEquals(
            "h252YE1oF96/yse9qX9halZBF4lBIH6dVXvqyeRLwlo=",
            compute("hmacBase64", "hello", "HmacSHA256", "k3y"),
        )
        // 语料里算法名有 "HmacSHA256" 与 "HMAC-SHA256" 两种写法，指同一个 Mac 算法
        assertEquals(
            compute("hmacBase64", "hello", "HmacSHA256", "k3y"),
            compute("hmacBase64", "hello", "HMAC-SHA256", "k3y"),
        )
    }

    @Test
    fun `symmetricCrypto 解 OpenSSL 产出的 AES-CBC 密文`() {
        // 已知向量：key/iv 是 ASCII 文本，其 UTF-8 字节即 openssl 的 -K/-iv 十六进制
        // 30313233343536373839616263646566 / 61626364656630313233343536373839
        assertEquals(
            "hello",
            compute(
                "symmetricCrypto", "decryptStr", "AES/CBC/PKCS5Padding",
                "0123456789abcdef", "abcdef0123456789", "base64:/8uAvsJDf8LjCw7wqOxbZA==",
            ),
        )
        assertEquals(
            "base64:/8uAvsJDf8LjCw7wqOxbZA==",
            compute(
                "symmetricCrypto", "encrypt", "AES/CBC/PKCS5Padding",
                "0123456789abcdef", "abcdef0123456789", "utf8:hello",
            ),
        )
        // PKCS7 与 PKCS5 在 16 字节块上同义，而 JDK 提供者只认 PKCS5Padding：归一之后同一条向量
        assertEquals(
            "hello",
            compute(
                "symmetricCrypto", "decryptStr", "AES/CBC/PKCS7Padding",
                "0123456789abcdef", "abcdef0123456789", "base64:/8uAvsJDf8LjCw7wqOxbZA==",
            ),
        )
    }

    @Test
    fun `symmetricCrypto 的密钥与密文可按 base64 或 hex 标记进字节`() {
        // 语料里 key/iv 常是 java.base64DecodeToByteArray(KEY_BASE64) 的结果，字节面必须由同一条口径表达
        assertEquals(
            "hello",
            compute(
                "symmetricCrypto", "decryptStr", "AES/CBC/PKCS5Padding",
                "hex:30313233343536373839616263646566", "hex:61626364656630313233343536373839",
                "base64:/8uAvsJDf8LjCw7wqOxbZA==",
            ),
        )
        // 裸文本按 UTF-8 字节处理，与上面「hex:」形态必须给出同一条密文的两种表达
        assertEquals(
            "hello",
            compute(
                "symmetricCrypto", "decryptStr", "AES/CBC/PKCS5Padding",
                "0123456789abcdef", "abcdef0123456789", "base64:/8uAvsJDf8LjCw7wqOxbZA==",
            ),
        )
    }

    @Test
    fun `symmetricCrypto 对坏输入按拒绝而不是回坏值`() {
        // ECB 不消费 IV；带 IV 的变换没给 IV 时报可诊断的拒绝，而不是 JCE 那句 IV required
        val noIv = runCatching {
            compute("symmetricCrypto", "decryptStr", "AES/CBC/PKCS5Padding", "0123456789abcdef", "", "base64:AAAA")
        }.exceptionOrNull()
        assertTrue("缺 IV 必须类型化拒绝：$noIv", noIv is JsApiRejectedException)
        val badOp = runCatching {
            compute("symmetricCrypto", "decryptWeird", "AES/CBC/PKCS5Padding", "k", "iv", "utf8:x")
        }.exceptionOrNull()
        assertTrue(badOp is JsApiRejectedException)
        val badMode = runCatching {
            compute("symmetricCrypto", "decryptStr", "RC4/None/None", "k", "iv", "utf8:x")
        }.exceptionOrNull()
        assertTrue(badMode is JsApiRejectedException)
    }

    @Test
    fun `bytesToStr 把字节标记按指定字符集还原成文本`() {
        assertEquals("hello", compute("bytesToStr", "base64:aGVsbG8=", "UTF-8"))
        assertEquals("hello", compute("bytesToStr", "hex:68656c6c6f", "ISO-8859-1"))
        // 缺席第二参按 UTF-8：语料 6 处单参调用，猜成平台默认字符集会让同一份规则在两台机上不同结果
        assertEquals("hello", compute("bytesToStr", "utf8:hello"))
    }
}
