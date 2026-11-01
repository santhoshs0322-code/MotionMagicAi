package com.gamixtv.MotionMagicAI.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AuthSessionRestorerTest {
    private val user = AuthUser("account-1", "Creator", "creator@example.test")
    private val saved = AuthSession("old-access", user, 9_000, "old-refresh")

    private class MemoryStore : AuthSessionStore {
        var saved: AuthSession? = null
        override suspend fun read() = saved
        override suspend fun write(session: AuthSession) { saved = session }
        override suspend fun clear() { saved = null }
    }

    private class FakeBackend(private val user: AuthUser) : AuthBackend {
        var refreshCalls = 0
        var meCalls = 0
        var rejectOldAccess = false
        var rejectRefresh = false
        override suspend fun signIn(idToken: String): AuthSession = error("Not used")
        override suspend fun refresh(refreshToken: String): AuthSession {
            refreshCalls++
            if (rejectRefresh) throw AuthException(AuthFailure.UNAUTHORIZED, "Session expired")
            return AuthSession("new-access", user, 20_000, "new-refresh")
        }
        override suspend fun me(token: String): AuthUser {
            meCalls++
            if (token == "old-access" && rejectOldAccess) throw AuthException(AuthFailure.UNAUTHORIZED, "Access expired")
            return user
        }
        override suspend fun logout(token: String) = Unit
    }

    @Test fun validSavedSessionOpensWithoutGoogleSignIn() = runBlocking {
        val backend = FakeBackend(user)
        val store = MemoryStore()
        val result = AuthSessionRestorer(backend, store) { 1_000 }.restore(saved)
        assertSame(user, result.user)
        assertEquals("old-access", result.token)
        assertEquals(0, backend.refreshCalls)
        assertEquals(1, backend.meCalls)
    }

    @Test fun expiredAccessRefreshesBeforeOpeningAndPersistsRotatedToken() = runBlocking {
        val backend = FakeBackend(user)
        val store = MemoryStore()
        val result = AuthSessionRestorer(backend, store) { 9_000 }.restore(saved)
        assertEquals("new-access", result.token)
        assertEquals("new-refresh", store.saved?.refreshToken)
        assertEquals(1, backend.refreshCalls)
        assertEquals(1, backend.meCalls)
    }

    @Test fun accessRejectedDuringCheckRefreshesOnce() = runBlocking {
        val backend = FakeBackend(user).apply { rejectOldAccess = true }
        val store = MemoryStore()
        val result = AuthSessionRestorer(backend, store) { 1_000 }.restore(saved)
        assertEquals("new-access", result.token)
        assertEquals(1, backend.refreshCalls)
        assertEquals(2, backend.meCalls)
    }

    @Test fun expiredRefreshDoesNotOpenTheApp() = runBlocking {
        val backend = FakeBackend(user).apply { rejectRefresh = true }
        try {
            AuthSessionRestorer(backend, MemoryStore()) { 9_000 }.restore(saved)
            fail("Expired session must be rejected")
        } catch (error: AuthException) {
            assertEquals(AuthFailure.UNAUTHORIZED, error.failure)
            assertEquals(0, backend.meCalls)
        }
    }
}
