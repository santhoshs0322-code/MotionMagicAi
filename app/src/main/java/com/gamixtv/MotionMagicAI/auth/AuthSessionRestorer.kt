package com.gamixtv.MotionMagicAI.auth

/** Checks a saved session with the server before it can open the studio. */
class AuthSessionRestorer(
    private val backend: AuthBackend,
    private val store: AuthSessionStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun restore(saved: AuthSession): AuthSession {
        var current = saved
        val refreshedBeforeCheck = saved.expiresAtEpochSeconds <= clock() + 3600
        if (refreshedBeforeCheck) current = renew(saved)

        val user = try {
            backend.me(current.token)
        } catch (error: AuthException) {
            // The access token can be revoked or expire between the local check and /me.
            if (error.failure != AuthFailure.UNAUTHORIZED || refreshedBeforeCheck) throw error
            current = renew(saved)
            backend.me(current.token)
        }
        if (user.id != saved.user.id) throw AuthException(AuthFailure.UNAUTHORIZED, "Account changed. Sign in again.")
        return current.copy(user = user)
    }

    private suspend fun renew(saved: AuthSession): AuthSession {
        val renewed = backend.refresh(saved.refreshToken)
        if (renewed.user.id != saved.user.id) throw AuthException(AuthFailure.UNAUTHORIZED, "Account changed. Sign in again.")
        store.write(renewed)
        return renewed
    }
}
