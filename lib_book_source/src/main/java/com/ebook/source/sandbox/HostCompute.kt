package com.ebook.source.sandbox

import com.ebook.source.script.JsApiRejectedException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `COMPUTE` 分支（[com.ebook.source.script.JsApiTarget] 的一支）的实现，跑在**执行器进程内**（零跨进程往返）。
 *
 * 两条贯穿全文件的决定：
 *
 * 1. **一律 UTF-8 字节**做摘要/加密的输入，摘要一律小写十六进制输出。上游未规定，本仓定版，
 *    锁在测试里——改它会让「服务端签名对不上」变成看不出来的错。
 * 2. **失败一律抛 [JsApiRejectedException]**，不回 null。null 在垫片里的形态是
 *    「调用成功、值为 null」，脚本会拿它拼出 `url=null` 继续请求，那才是最难查的假成功。
 *    同一条理由要求失败**也必须是类型化的**：入参里混进对象/数组时不能任由
 *    `jsonPrimitive` 抛未类型化的 `IllegalStateException`——那会在 `.so` 的调用栈里炸掉整个
 *    `:js` 进程（同一进程还在跑别的书），而「参数形态不对」本是一句可以说清的话。
 *
 * 3. **字节进出边界只有字符串**，故第 2 批的「字节数组」类参数统一写成 `"<编码>:<文本>"` 材料标记
 *    （见 [materialToBytes]）。编码前缀只认 base64/hex/utf8/latin1，其余一律按 UTF-8 裸文本；
 *    算法名（`SHA-256`/`sha256`/`HMAC-SHA256`/`HmacSHA256`…）大小写与连字符不敏感，**认不出即拒绝**。
 *
 * 分组密码的密钥与 IV 派生是**本仓规定**（规格未核实上游语义）：key 的 UTF-8 字节按
 * ≥32/≥24/其余 取 256/192/128 位，不足补零、超出截断；AES 用 CBC 且 IV 取密钥字节前 16 字节，
 * DES 用 ECB。无随机 IV 是刻意的：脚本里 `aesEncode(x, k)` 的结果要被当 URL 参数复用，必须可复现。
 * 真实源若用别的派生方式，会在装机清单「签名 URL 类源」一项暴露，届时按源加选项、不改默认。
 */
internal object HostCompute {

    /**
     * 一次 host call 的入口。
     *
     * [api] 由执行器原样带过来（不在本表 COMPUTE 分支里的名字一律按拒绝），返回值是
     * 帧的 `data` 字段——这里恒为 JSON 字符串，脚本侧拿到什么形态由垫片决定。
     */
    fun invoke(api: String, args: JsonElement?): JsonElement = try {
        dispatch(api, argList(api, args))
    } catch (e: JsApiRejectedException) {
        // 拒绝消息必须带 api 名：host 回复只有一行 error 文本，「AES 解密失败」不说是哪个 api，
        // 脚本里同时用了 aes/des 两族时就只能靠猜。已经带上了的（未知 api、参数非标量）原样抛，
        // 不叠第二层前缀。
        if (e.message?.contains(api) == true) throw e
        throw JsApiRejectedException("$api 调用失败：${e.message}", e)
    }

    private fun dispatch(api: String, list: List<String>): JsonElement = when (api) {
        "md5" -> string(digest("MD5", list.at(0)))
        "sha1" -> string(digest("SHA-1", list.at(0)))
        "sha256" -> string(digest("SHA-256", list.at(0)))
        "base64Encode" -> string(encodeBase64(list.at(0).toByteArray(), urlSafe = false))
        "base64Decode" -> string(decodeBase64(list.at(0), urlSafe = false).decodeToString())
        "base64EncodeUrl" -> string(encodeBase64(list.at(0).toByteArray(), urlSafe = true))
        "base64DecodeUrl" -> string(decodeBase64(list.at(0), urlSafe = true).decodeToString())
        "hexEncode" -> string(hex(list.at(0).toByteArray()))
        "hexDecode" -> string(unhex(list.at(0)).decodeToString())
        "aesEncode" -> string(crypt(Cipher.ENCRYPT_MODE, AES, list.at(0), list.at(1)))
        "aesDecode" -> string(crypt(Cipher.DECRYPT_MODE, AES, list.at(0), list.at(1)))
        "desEncode" -> string(crypt(Cipher.ENCRYPT_MODE, DES, list.at(0), list.at(1)))
        "desDecode" -> string(crypt(Cipher.DECRYPT_MODE, DES, list.at(0), list.at(1)))
        "timestamp" -> string(System.currentTimeMillis().toString())
        "FormatDate" -> string(formatDate(list.at(0), list.at(1)))
        "uriEncode" -> string(uriEncode(list.at(0), list.getOrNull(1).orEmpty()))
        "randomUUID" -> string(UUID.randomUUID().toString())
        "digestHex" -> string(digest(digestAlgorithm(list.at(1)), list.at(0)))
        "hmacBase64" -> string(hmacBase64(list.at(0), list.at(1), list.at(2)))
        "symmetricCrypto" -> string(symmetricCrypto(list.at(0), list.at(1), list.at(2), list.at(3), list.at(4)))
        "bytesToStr" -> string(bytesToText(list.at(0), list.getOrNull(1).orEmpty()))
        else -> throw JsApiRejectedException("沙箱不提供计算能力 $api")
    }

    private val stringSerializer = String.serializer()
    private fun string(value: String): JsonElement = Json.encodeToJsonElement(stringSerializer, value)

    /**
     * 帧里的参数列表。
     *
     * 非数组（含 null）按「一个参数都没传」处理，让 [at] 去报「缺第几个参数」——比报「帧不是数组」
     * 更有指向性，因为脚本侧看不到帧，只看得到自己写了几个实参。
     * 数组元素必须是标量：`java.md5({})` 这种传对象的调用，把它当空串去摘要会算出一个「看起来成功」
     * 的签名，永不复现，所以按拒绝处置。
     */
    private fun argList(api: String, args: JsonElement?): List<String> = when (args) {
        is JsonArray -> args.map { element ->
            when (element) {
                // JsonNull 的 content 是字面量 "null"，所以必须走 contentOrNull 再折叠成空串：
                // 参数缺席与「传了个 null」在纯计算这一层都是「没有输入」，不能变成一串字符参与摘要
                is JsonPrimitive -> element.contentOrNull.orEmpty()
                else -> throw JsApiRejectedException("$api 的参数不能是对象或数组")
            }
        }

        else -> emptyList()
    }

    private fun List<String>.at(i: Int): String =
        elementAtOrNull(i) ?: throw JsApiRejectedException("参数个数不符：需要第 ${i + 1} 个参数")

    private fun digest(algorithm: String, input: String): String = runCatching {
        hex(MessageDigest.getInstance(algorithm).digest(input.toByteArray()))
    }.getOrElse { throw JsApiRejectedException("$algorithm 计算失败", it) }

    private fun encodeBase64(bytes: ByteArray, urlSafe: Boolean): String =
        (if (urlSafe) Base64.getUrlEncoder().withoutPadding() else Base64.getEncoder())
            .encodeToString(bytes)

    /**
     * base64 解码。标准形态走 MIME 解码器（源里的密文常被折行，MIME 对空白宽容），URL 安全形态走 URL 解码器。
     *
     * 「非空入参解出 0 字节」单独按拒绝：MIME 解码器会把「不是密文」这种整串都不合法的输入
     * 静默丢成空数组，AES 再把它解成空文本——脚本拿到的是「成功，正文为空」，
     * 这正是本文件第 2 条决定要防的假成功。
     */
    private fun decodeBase64(text: String, urlSafe: Boolean): ByteArray = runCatching {
        if (urlSafe) Base64.getUrlDecoder().decode(text) else Base64.getMimeDecoder().decode(text)
    }.getOrElse { throw JsApiRejectedException("base64 解码失败：${text.take(24)}", it) }
        .also {
            if (it.isEmpty() && text.isNotBlank()) throw JsApiRejectedException("不是合法的 base64 文本：${text.take(24)}")
        }

    /** 自己拼十六进制而不是用 `java.util.HexFormat`：后者要 API 31，本仓 minSdk 26 */
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { append((it.toInt() and 0xff).toString(16).padStart(2, '0')) }
    }

    private fun unhex(text: String): ByteArray {
        val clean = text.trim()
        if (clean.length % 2 != 0) throw JsApiRejectedException("hex 长度不是偶数：${clean.take(24)}")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte()
                ?: throw JsApiRejectedException("hex 含非十六进制字符：${clean.take(24)}")
        }
    }

    /**
     * 密文按 base64 文本进出。加密分支直接 `encodeBase64(doFinal(...))`——
     * **绝不**先把密文按字符串解一遍再编码：分组密码的输出不是文本，走字符串会不可逆地坏掉，
     * 症状是「一部分书能解、一部分永远报错」。
     */
    private fun crypt(mode: Int, algorithm: String, data: String, key: String): String {
        val keyBytes = keyMaterial(key, algorithm)
        val cipher = runCatching {
            Cipher.getInstance(if (algorithm == DES) "DES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding").apply {
                val spec = SecretKeySpec(keyBytes, algorithm)
                if (algorithm == DES) init(mode, spec) else init(mode, spec, IvParameterSpec(keyBytes.copyOf(16)))
            }
        }.getOrElse { throw JsApiRejectedException("$algorithm 初始化失败", it) }
        val produced = runCatching {
            if (mode == Cipher.ENCRYPT_MODE) {
                encodeBase64(cipher.doFinal(data.toByteArray()), urlSafe = false)
            } else {
                cipher.doFinal(decodeBase64(data, urlSafe = false)).decodeToString()
            }
        }.getOrElse {
            // 已经是类型化拒绝的（base64 那一步）原样上抛，不再套一层「AES 解密失败」：
            // 两层消息会把「密文根本不是 base64」说成「密钥不对」，把人支去换密钥。
            if (it is JsApiRejectedException) throw it
            val action = if (mode == Cipher.ENCRYPT_MODE) "加密" else "解密"
            throw JsApiRejectedException("$algorithm $action 失败", it)
        }
        return produced
    }

    private fun keyMaterial(key: String, algorithm: String): ByteArray {
        val raw = key.toByteArray()
        val size = when {
            algorithm == DES -> 8
            raw.size >= 32 -> 32
            raw.size >= 24 -> 24
            else -> 16
        }
        return ByteArray(size).also { raw.copyInto(it, 0, 0, minOf(raw.size, size)) }
    }

    private fun formatDate(millis: String, pattern: String): String {
        val ts = millis.toLongOrNull() ?: throw JsApiRejectedException("时间戳不是数字：${millis.take(20)}")
        return runCatching { SimpleDateFormat(pattern, Locale.US).format(Date(ts)) }
            .getOrElse { throw JsApiRejectedException("日期格式不可用：${pattern.take(24)}", it) }
    }

    /**
     * 表单百分号编码（`application/x-www-form-urlencoded`）。空格成 `+` 而不是 `%20`：
     * 语料里这一族全部用于拼查询串，站点按 form 解码，两者只在空格一处不同——
     * 而这一处足以让「剑来 1」这种带空格的关键词永远查不到书。
     */
    private fun uriEncode(text: String, charset: String): String = runCatching {
        URLEncoder.encode(text, charsetName(charset))
    }.getOrElse { throw JsApiRejectedException("uriEncode 失败", it) }

    /** 空串按 UTF-8：单参调用在语料里是多数形态，落到平台默认字符集会让同一份规则在两台机上不同结果 */
    private fun charsetName(charset: String): String =
        if (charset.isBlank()) "UTF-8"
        else runCatching { Charset.forName(charset).name() }
            .getOrElse { throw JsApiRejectedException("字符集不可用：${charset.take(24)}") }

    /**
     * 站点写的算法名归一化后查表。
     *
     * 大小写与连字符两种写法都有（`"SHA-256"` / `'sha-256'` / `"HMAC-SHA256"` / `"HmacSHA256"`），
     * 而 JCA 只认其中一个确切拼法。查不到就**报拒绝**：默默换成 MD5 会算出一串「看着像」的签名，
     * 症状推迟到站点回 401，比当场说一句「不支持 SM4」贵得多。
     */
    private fun digestAlgorithm(name: String): String =
        DIGESTS[normalize(name)] ?: throw JsApiRejectedException("不支持的摘要算法：${name.take(24)}")

    private fun hmacAlgorithm(name: String): String =
        MACS[normalize(name)] ?: throw JsApiRejectedException("不支持的 HMAC 算法：${name.take(24)}")

    private fun normalize(name: String): String = name.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    /**
     * HMAC 摘要按 base64 出。签名 URL 的常见形态（`data + "&sign=" + hmac(...)`），
     * 密钥直接用 UTF-8 字节、**不做**分组密码那套补零/截断——HMAC 对任意长度密钥都有定义，
     * 补零只会把「密钥写错了」变成一串永不复现的合法签名。
     */
    private fun hmacBase64(data: String, algorithm: String, key: String): String {
        val mac = runCatching { Mac.getInstance(hmacAlgorithm(algorithm)) }
            .getOrElse { throw JsApiRejectedException("HMAC 初始化失败", it) }
        return runCatching {
            mac.init(SecretKeySpec(key.toByteArray(), mac.algorithm))
            encodeBase64(mac.doFinal(data.toByteArray()), urlSafe = false)
        }.getOrElse { throw JsApiRejectedException("HMAC 计算失败", it) }
    }

    /**
     * 变换名形如 `算法/模式/填充` 的对称加解密（对应上游 `createSymmetricCrypto` 那一族）。
     *
     * 与文件头那套 [crypt] 的分别在于**这里一切由源自己写定**：密钥字节按材料原样使用、
     * IV 由第二个参数给出、填充与模式照变换名走。因此派生规则归源，本函数只负责不添油加醋。
     *
     * IV 是**严格**的：非 ECB 模式缺 IV 直接按拒绝，而不是让 JCE 抛那句「Parameters missing」——
     * 后者在 host 回复里只剩一行英文，看不出是源少写了一个参数。
     */
    private fun symmetricCrypto(op: String, transformation: String, key: String, iv: String, data: String): String {
        val decrypting = when (op) {
            "decryptStr", "decrypt" -> true
            "encrypt", "encryptBase64", "encryptHex" -> false
            else -> throw JsApiRejectedException("未知的对称加解密操作：${op.take(20)}")
        }
        val parts = transformation.split('/')
        if (parts.size != 3) {
            throw JsApiRejectedException("变换名不是 算法/模式/填充 三段：${transformation.take(32)}")
        }
        val algorithm = parts[0].uppercase(Locale.US)
        val mode = parts[1].uppercase(Locale.US)
        // PKCS7 与 PKCS5 在 16 字节块上同义，而 JDK 提供者只登记了 PKCS5Padding 这个名字
        val padding = if (normalize(parts[2]) == "pkcs7padding") "PKCS5Padding" else parts[2]
        val name = "$algorithm/$mode/$padding"
        val keyBytes = materialToBytes(key)
        val ivBytes = materialToBytes(iv)
        val produced = runCatching {
            val cipher = Cipher.getInstance(name)
            val spec = SecretKeySpec(keyBytes, algorithm)
            if (mode == "ECB") {
                cipher.init(if (decrypting) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, spec)
            } else {
                if (ivBytes.isEmpty()) throw JsApiRejectedException("$name 需要 iv")
                cipher.init(
                    if (decrypting) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
                    spec,
                    IvParameterSpec(ivBytes),
                )
            }
            cipher.doFinal(materialToBytes(data))
        }.getOrElse {
            if (it is JsApiRejectedException) throw it
            throw JsApiRejectedException("$name $op 失败", it)
        }
        return when (op) {
            "decryptStr" -> produced.decodeToString()
            "encryptHex" -> hex(produced)
            "encryptBase64" -> encodeBase64(produced, urlSafe = false)
            // encrypt / decrypt 的产物是字节不是文本，一律带 base64 标记出边界，
            // 由垫片就地重新包成字节令牌——直接 decodeToString 会把非 UTF-8 的密文坏成 U+FFFD
            else -> TAG_BASE64 + encodeBase64(produced, urlSafe = false)
        }
    }

    private fun bytesToText(value: String, charset: String): String = runCatching {
        String(materialToBytes(value), Charset.forName(charsetName(charset)))
    }.getOrElse {
        if (it is JsApiRejectedException) throw it
        throw JsApiRejectedException("bytesToStr 失败", it)
    }

    /**
     * 字节材料：`"<编码>:<文本>"` → 字节；前缀不认（含整串无冒号）一律按「这就是 UTF-8 文本」。
     *
     * 只在**已知前缀**上切冒号是刻意的：密文里出现 `https://…` 这类裸文本时，按第一个冒号切
     * 会得出前缀 `https`，于是整条 URL 被当成 hex/base64 去解，报一句指不到根因的错。
     * 边界上只有字符串（帧里不许出现对象），所以「字节」这个概念必须由前缀表达，
     * 这也让 JS 侧的 `base64DecodeToByteArray` 之类零往返地纯打标。
     */
    private fun materialToBytes(value: String): ByteArray {
        val sep = value.indexOf(':')
        if (sep <= 0) return value.toByteArray()
        val body = value.substring(sep + 1)
        return when (value.substring(0, sep).lowercase(Locale.US)) {
            "base64" -> decodeBase64(body, urlSafe = false)
            "hex" -> unhex(body)
            "utf8", "utf-8" -> body.toByteArray()
            "latin1", "iso-8859-1" -> body.toByteArray(Charsets.ISO_8859_1)
            else -> value.toByteArray()
        }
    }

    private const val TAG_BASE64 = "base64:"

    private val DIGESTS = mapOf(
        "md5" to "MD5",
        "sha1" to "SHA-1",
        "sha224" to "SHA-224",
        "sha256" to "SHA-256",
        "sha384" to "SHA-384",
        "sha512" to "SHA-512",
    )

    private val MACS = mapOf(
        "hmacmd5" to "HmacMD5",
        "hmacsha1" to "HmacSHA1",
        "hmacsha224" to "HmacSHA224",
        "hmacsha256" to "HmacSHA256",
        "hmacsha384" to "HmacSHA384",
        "hmacsha512" to "HmacSHA512",
    )

    private const val AES = "AES"
    private const val DES = "DES"
}
