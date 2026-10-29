package com.motionmagic.ai.auth

import com.motionmagic.ai.BuildConfig
import java.net.URI

/** Public configuration only. Neither this file nor BuildConfig contains a client secret. */
data class AuthConfig(
    val baseUrl: String,
    val googleWebClientId: String,
    val appId: String = "motionmagic",
) {
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')
    val isConfigured: Boolean get() = validationError() == null

    fun validationError(): String? {
        if (appId != "motionmagic") return "MotionMagic requires its own login app registration."
        if (baseUrl.isBlank()) {
            return "Google sign-in is not configured yet. Add the HTTPS login server and Google Web client ID. You can continue using local previews."
        }
        val uri = runCatching { URI(normalizedBaseUrl) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port !in listOf(-1, 443) || uri.path.contains("..")) {
            return "The login server must use an HTTPS address without credentials, query parameters or fragments."
        }
        if (googleWebClientId.isNotEmpty() && (!googleWebClientId.trim().endsWith(".apps.googleusercontent.com") || googleWebClientId.any { it.isWhitespace() })) {
            return "Configure the Google Web OAuth client ID for this login server."
        }
        return null
    }

    companion object {
        fun fromBuildConfig() = AuthConfig(BuildConfig.AUTH_BASE_URL, BuildConfig.GOOGLE_WEB_CLIENT_ID)
    }
}

data class AuthUser(val id: String, val name: String, val email: String, val picture: String = "")

data class AuthSession(val token: String, val user: AuthUser, val expiresAtEpochSeconds: Long) {
    // Prevent accidental session-token disclosure when a state object is inspected or logged.
    override fun toString(): String = "AuthSession(userId=${user.id}, expiresAt=$expiresAtEpochSeconds, token=<redacted>)"
}

enum class AuthStatus { GUEST, RESTORING, SIGNING_IN, VERIFYING, SIGNED_IN, OFFLINE }
enum class AuthFailure { CONFIGURATION, UNAUTHORIZED, NETWORK, RATE_LIMIT, SERVER, INVALID_RESPONSE, STORAGE, CANCELLED }
class AuthException(val failure: AuthFailure, message: String) : Exception(message)

data class AuthUiState(
    val status: AuthStatus = AuthStatus.GUEST,
    val user: AuthUser? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val configured: Boolean = false,
) {
    /** An offline cached name never counts as verified authentication. */
    val isSignedIn: Boolean get() = status == AuthStatus.SIGNED_IN
}

interface AuthBackend {
    suspend fun signIn(idToken: String): AuthSession
    suspend fun me(token: String): AuthUser
    suspend fun logout(token: String)
}

interface AuthSessionStore {
    suspend fun read(): AuthSession?
    suspend fun write(session: AuthSession)
    suspend fun clear()
}
