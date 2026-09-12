package com.ebook.common.analyze.source

import com.ebook.common.event.libraryCacheKey
import com.ebook.db.entity.LibraryEntity
import com.xrn1997.common.util.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 书库数据的磁盘缓存：`cacheDir/library_cache/` 下按书源分区的 JSON 文件。
 *
 * ## 为什么是文件而不是 SharedPreferences
 *
 * 旧实现（ACache）把整份书库 JSON 塞进 SP：SP 全文件常驻内存、本就不适合大字符串，更致命的是
 * **缓存管理页清不到它**——该页的「缓存」口径是 cacheDir（ADR-0026），SP 文件落在口径之外，
 * 于是书库缓存成了用户永远删不掉的暗占用。落回 cacheDir 后两件事自然成立：缓存管理页的
 * 「其他」档能统计并清掉 `library_cache/` 子目录，系统存储紧张时也能整体回收。
 *
 * ## 职责边界：只做存取，不做策略
 *
 * 本类只回答「这个源现在存着什么、是什么时候存的」；**要不要用缓存、过期了怎么办**是编排层
 * （`BookSourceRepository`）的策略——所以 [read] 如实带回 [Entry.savedAtMillis] 而不是自己判
 * 新鲜度，TTL 常量也不在这里。读写时机若各处判一次年龄，就会长出两个口径不同的「缓存策略」。
 *
 * ## 构造只收 File 的原因
 *
 * 照 module_me `CacheModule` 给 `CacheModel` 传 `cacheDir` 的手法：`Context → File` 的转换
 * 只在 DI 装配点发生一次，本类不碰 Android API——纯 JVM 单测里 `Files.createTempDirectory`
 * 就能构造出被测对象，不必引入 Robolectric。
 */
class LibraryDiskCache(private val dir: File) {

    /**
     * 读出的缓存条目：数据 + 写入时刻。
     *
     * 把 [savedAtMillis] 一并带出而不是只在类内判断，是为了让调用方（仓库层）能自己定义
     * 「多旧算过期」而不必把 TTL 传进来——存储层与策略层解耦的唯一接缝就是这个字段。
     */
    data class Entry(val data: LibraryEntity, val savedAtMillis: Long)

    /**
     * 读 [sourceUrl] 那个源的书库缓存；null = 未命中（没写过、文件被清、或内容损坏）。
     *
     * 损坏文件按未命中处理并**顺手删除**：留着它每次进书城都要白 parse 一遍再失败，
     * 删掉之后下次加载自然走网络重建——自愈优先于报错。
     */
    fun read(sourceUrl: String): Entry? {
        val file = fileFor(sourceUrl)
        return try {
            if (!file.isFile) return null
            val envelope = json.decodeFromString(LibraryCacheEnvelope.serializer(), file.readText())
            Entry(envelope.data.toLibrary(), envelope.savedAtMillis)
        } catch (e: Exception) {
            Logger.w(TAG, "书库缓存读取失败，按未命中处理: sourceUrl=$sourceUrl", e)
            runCatching { file.delete() }
            null
        }
    }

    /**
     * 写 [sourceUrl] 那个源的书库缓存（覆盖旧文件）。
     *
     * [savedAtMillis] 暴露成参数是**测试接缝**：生产调用走默认的当前时间，测试用它造出
     * 「已过期 / 仍新鲜」的确定年龄条目，不必注入时钟。
     *
     * 写入失败只记日志不抛：缓存是加速件不是数据源，磁盘写不进（满盘/权限）不该让
     * 一次成功的书城加载失败。
     */
    fun write(sourceUrl: String, entity: LibraryEntity, savedAtMillis: Long = System.currentTimeMillis()) {
        val target = fileFor(sourceUrl)
        // 临时文件用**确定名**（目标名 + .tmp），不用 createTempFile 的随机后缀：随机名下每在
        // move 前被杀就泄漏一个永不回收的文件——read 只匹配 `<md5>.json`，谁也不会再去读它，
        // cacheDir 又只在系统回收/用户清理时才被动。确定名让残骸天然自限为一源一份，
        // 且下次写同一条目时被 move 直接消费掉
        val tmp = File(dir, "${target.name}.tmp")
        try {
            dir.mkdirs()
            // 先写同目录临时文件再原子覆盖：写一半被杀（进程崩溃/系统回收）不会留下截断的
            // JSON 让后续每次读取都走损坏分支。用 Files.move 而非 File.renameTo——后者在
            // Windows 上对已存在的目标是失败而非覆盖，单测就跑不过
            val envelope = LibraryCacheEnvelope(savedAtMillis, LibraryCacheData.fromLibrary(entity))
            tmp.writeText(json.encodeToString(LibraryCacheEnvelope.serializer(), envelope))
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // 失败顺手删掉半截临时文件：残骸虽会被下次写入消费，但这条源若从此不再进书城，
            // 它就是一笔无人认领的占用
            runCatching { tmp.delete() }
            Logger.w(TAG, "书库缓存写入失败（不影响本次加载）: sourceUrl=$sourceUrl", e)
        }
    }

    /**
     * 缓存文件路径：文件名取 `libraryCacheKey(sourceUrl)` 的 MD5 十六进制。
     *
     * key 的生成仍唯一收在 [libraryCacheKey]（仓内铁律：读写两侧各拼一次字符串就会漂移出
     * 「写完永远读不到」的错位）；URL 明文不适合直接当文件名（非法字符/超长），MD5 把它
     * 变成稳定的文件系统安全串——这与 OkHttp 磁盘缓存对 URL 做 MD5 的做法一致。
     */
    private fun fileFor(sourceUrl: String): File =
        File(dir, md5Hex(libraryCacheKey(sourceUrl)) + ".json")

    private fun md5Hex(key: String): String =
        MessageDigest.getInstance("MD5").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val TAG = "LibraryDiskCache"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * 落盘信封：载荷 + 写入时刻。
 *
 * 时间戳与数据同文件存放（而不是取文件 mtime）：mtime 会被同步工具/备份恢复/某些文件系统
 * 改写，作为 TTL 依据不可靠；显式字段写入后就是我们自己的事实。
 */
@Serializable
private data class LibraryCacheEnvelope(
    val savedAtMillis: Long,
    val data: LibraryCacheData,
)
