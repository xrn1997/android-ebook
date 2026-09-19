package com.ebook.api.service.comment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [toCommentKeysParam] 的纯 JVM 契约测试：`comment_keys` 这条查询串的形状。
 *
 * 后端对 `comment_keys` 有**条数上限**（超出直接 A0400，见 model.MaxCommentFilterKeys），
 * 而一本书的别名键数量由书源脚本决定、客户端不设上界。这条截断一旦回退，症状是
 * 「某本书的评论区永远空白」——不炸、不报错，只静默拿不到数据，所以在这里钉死。
 */
class CommentKeysParamTest {

    @Test
    fun `多键按逗号连接，顺序保持调用方给的次序`() {
        assertEquals(
            "ck1:a#0,ck1:b#0",
            toCommentKeysParam(listOf("ck1:a#0", "ck1:b#0"))
        )
    }

    @Test
    fun `空列表翻译成 null，而不是一个会让后端落进全局列表的空参数`() {
        // 后端把 `?comment_keys=`（空串）按「未提供」处理 → 返回全站最新评论。
        // 章评论区绝不能收到那份并集，所以这里必须是 null（Retrofit 才会整个省掉查询参数）。
        assertNull(toCommentKeysParam(emptyList()))
        assertNull(toCommentKeysParam(listOf()))
    }

    @Test
    fun `重复键去重，不白占绑定位`() {
        assertEquals(
            "ck1:a#0,ck1:b#0",
            toCommentKeysParam(listOf("ck1:a#0", "ck1:a#0", "ck1:b#0", "ck1:a#0"))
        )
    }

    @Test
    fun `超过服务端上限时截到上限，而不是整页评论换不回数据`() {
        val keys = (1..51).map { "ck1:k$it#0" }

        val param = requireNotNull(toCommentKeysParam(keys))

        val sent = param.split(",")
        assertEquals(MAX_FILTER_KEYS, sent.size)
        // 截的是尾部，保留调用方给出的前 MAX_FILTER_KEYS 个键
        assertEquals(keys.take(MAX_FILTER_KEYS), sent)
    }

    @Test
    fun `去重发生在截断之前：重复键不会把真正要查的键挤出上限`() {
        val dupes = List(MAX_FILTER_KEYS) { "ck1:dup#0" }
        val keys = dupes + listOf("ck1:kept#0")

        assertEquals("ck1:dup#0,ck1:kept#0", toCommentKeysParam(keys))
    }

    private companion object {
        /** 与后端 model.MaxCommentFilterKeys 同值；生产侧的定义在本文件私有常量里 */
        const val MAX_FILTER_KEYS = 50
    }
}
