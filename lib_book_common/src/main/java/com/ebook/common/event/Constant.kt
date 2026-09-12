package com.ebook.common.event

const val FROM_BOOKSHELF = 1
const val FROM_SEARCH = 2

const val LIBRARY_CACHE_KEY: String = "cache_library"

/**
 * 书库磁盘缓存的 key：按书源分区为 `"$LIBRARY_CACHE_KEY:<书源 URL>"`（见 ADR-0016 决策 7）。
 *
 * **为什么必须分区**：书库数据（各分类的首屏书目）完全来自该书源自己的 `ruleFind` 规则，
 * 多个书源共用一个 key 时，把 A 源解析出的书目存进去、B 源页面就会原样读到它——用户切了源
 * 却看到 A 站的书，而且**没有任何异常与日志**（缓存命中是成功路径，比崩溃难查得多）。
 *
 * **为什么收成这一个函数**：读写两侧各自拼字符串时，只要两侧写法有一点差别（分隔符、大小写、
 * 拼 `name` 还是 `url`），结果就是「写完永远读不到」——表现为每次进书城都在重拉网络，
 * 而功能上看不出任何毛病。缓存重构后读写虽已收在 `LibraryDiskCache` 一个类里（落盘文件名取
 * 本函数结果的 MD5），key 的**形态**仍只由这一处决定，不挪进缓存类：挪进去等于把「谁都能
 * 自己拼一份」的旧格局换个地方长回来。
 *
 * 用 URL 而不是书源名做后缀：`url` 是 `book_source` 表主键、也是 `tag` 的取值，用户可改名
 * 但改不了归属；名字进了 key 会让「重命名书源」变成缓存集体失效。
 */
fun libraryCacheKey(sourceUrl: String): String = "$LIBRARY_CACHE_KEY:$sourceUrl"