package com.ebook.book.mvvm.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isOwnComment]（章节评论「仅本人可长按删除」门禁）的回归测试，纯 JVM。
 *
 * 锁住的缺陷：门禁此前比对的是**展示名**（`comment.username`，由
 * `com.ebook.common.mapper.toBookComment` 填成 `nickname.ifEmpty { username }`）与
 * `SP_USERNAME`（登录名）。邮箱才是登录主标识、昵称仅作展示且可重复（见 ADR-0009），
 * 因此任何设过昵称的用户永久删不掉自己的评论；当时的 mock 数据还刻意留空昵称来绕开这个失配。
 * 现改为按 `userId` 比对，并抽出本纯函数便于单测。
 *
 * 覆盖口径（含函数 KDoc 写明的两条设计理由）：
 * - userId 相等且当前用户 id > 0 → 本人
 * - 展示名相同不再构成「本人」；未登录（null）与 id <= 0 一律判否
 *   （路由参数组装的占位评论 `userId = 0`，不加 > 0 闸门会与自己比出假阳性）
 */
class IsOwnCommentTest {

    @Test
    fun `评论 userId 与当前登录 userId 相等且大于 0 时判定为本人`() {
        assertTrue(isOwnComment(commentUserId = 42L, currentUserId = 42L))
    }

    @Test
    fun `userId 不同时判定为非本人`() {
        assertFalse(isOwnComment(commentUserId = 42L, currentUserId = 43L))
    }

    @Test
    fun `未登录时 currentUserId 为 null 判定为非本人`() {
        assertFalse(isOwnComment(commentUserId = 42L, currentUserId = null))
        // 未登录 + 占位评论（路由参数拼出来的那条）同样不能判成本人
        assertFalse(isOwnComment(commentUserId = 0L, currentUserId = null))
    }

    @Test
    fun `当前用户 id 为 0 时判定为非本人避免占位评论假阳性`() {
        // 未登录时 addComment 取 0 落库/上屏；0 不是合法用户 id，不能与自己比出「本人」
        assertFalse(isOwnComment(commentUserId = 0L, currentUserId = 0L))
    }

    @Test
    fun `评论 userId 为 0 的占位评论即使已登录也判定为非本人`() {
        assertFalse(isOwnComment(commentUserId = 0L, currentUserId = 42L))
    }

    @Test
    fun `双方 id 为负数时判定为非本人`() {
        assertFalse(isOwnComment(commentUserId = -1L, currentUserId = -1L))
        assertFalse(isOwnComment(commentUserId = 42L, currentUserId = -42L))
    }
}
