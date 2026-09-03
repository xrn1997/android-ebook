package com.ebook.common.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserSessionManagerTest {

    private lateinit var manager: FakeUserSessionManager

    @Before
    fun setup() {
        manager = FakeUserSessionManager()
    }

    @Test
    fun `initial state should be logged out`() {
        assertFalse(manager.isLoggedIn.value)
        assertNull(manager.currentUser.value)
        assertNull(manager.getToken())
    }

    @Test
    fun `saveSession should update state and persist`() = runTest {
        val session = createTestSession()

        manager.saveSession(session, "refresh-token-123")

        assertTrue(manager.isLoggedIn.value)
        assertEquals(session, manager.currentUser.value)
        assertEquals("test-token", manager.getToken())
        assertEquals(1, manager.savedSessions.size)
        assertEquals(session, manager.savedSessions[0].first)
        assertEquals("refresh-token-123", manager.savedSessions[0].second)
    }

    @Test
    fun `clearSession should reset all state`() = runTest {
        val session = createTestSession()
        manager.saveSession(session, "refresh-token-123")

        manager.clearSession()

        assertFalse(manager.isLoggedIn.value)
        assertNull(manager.currentUser.value)
        assertNull(manager.getToken())
        assertEquals(1, manager.clearCount)
    }

    @Test
    fun `saveSession should overwrite previous session`() = runTest {
        val session1 = createTestSession(userId = 1L, username = "user1")
        val session2 = createTestSession(userId = 2L, username = "user2")

        manager.saveSession(session1, "refresh-token-1")
        manager.saveSession(session2, "refresh-token-2")

        assertEquals(session2, manager.currentUser.value)
        assertEquals(2, manager.savedSessions.size)
    }

    @Test
    fun `saveSession should sync token to TokenHolder`() = runTest {
        manager.saveSession(createTestSession(token = "abc-token"), "refresh-token-123")

        assertEquals("abc-token", manager.getToken())
    }

    @Test
    fun `saveSession with empty token should clear TokenHolder`() = runTest {
        manager.saveSession(createTestSession(token = "old-token"), "refresh-token-123")
        manager.saveSession(createTestSession(token = ""), "refresh-token-456")

        // TokenHolder 将空串归一化为 null（"无 token"只有一种表示）
        assertNull(manager.getToken())
    }

    @Test
    fun `reset should clear all state`() = runTest {
        val session = createTestSession()
        manager.saveSession(session, "refresh-token-123")
        manager.clearSession()

        manager.reset()

        assertFalse(manager.isLoggedIn.value)
        assertNull(manager.currentUser.value)
        assertTrue(manager.savedSessions.isEmpty())
        assertEquals(0, manager.clearCount)
    }

    @Test
    fun `getRefreshToken should return saved refresh token when logged in`() = runTest {
        val session = createTestSession()
        manager.saveSession(session, "refresh-token-123")

        assertEquals("refresh-token-123", manager.getRefreshToken())
    }

    @Test
    fun `getRefreshToken should return null when not logged in`() {
        assertNull(manager.getRefreshToken())
    }

    @Test
    fun `getRefreshToken should return null after logout`() = runTest {
        val session = createTestSession()
        manager.saveSession(session, "refresh-token-123")
        manager.clearSession()

        assertNull(manager.getRefreshToken())
    }

    @Test
    fun `rotateCredentials should only rotate tokens and preserve identity`() = runTest {
        val session = createTestSession(userId = 7L, nickname = "保留我", avatar = "old-avatar")
        manager.saveSession(session, "refresh-token-old")

        manager.rotateCredentials("new-access", "refresh-token-new")

        // token 已轮换
        assertEquals("new-access", manager.getToken())
        assertEquals("refresh-token-new", manager.getRefreshToken())
        // 身份字段原样保留（这正是 (c)1/ADR-0011 修复的核心：轮换不得重建/抹空身份）
        val kept = manager.currentUser.value
        assertNotNull(kept)
        assertEquals(7L, kept?.userId)
        assertEquals("保留我", kept?.nickname)
        assertEquals("old-avatar", kept?.avatar)
        assertEquals(1, manager.rotated.size)
    }

    @Test
    fun `rotateCredentials when not logged in should be a no-op`() = runTest {
        manager.rotateCredentials("new-access", "refresh-token-new")

        assertNull(manager.getToken())
        assertNull(manager.currentUser.value)
        assertTrue(manager.rotated.isEmpty())
    }

    private fun createTestSession(
        userId: Long = 1L,
        username: String = "testuser",
        nickname: String = "Test User",
        avatar: String = "https://example.com/avatar.jpg",
        token: String = "test-token"
    ) = UserSession(
        userId = userId,
        username = username,
        nickname = nickname,
        avatar = avatar,
        token = token
    )
}
