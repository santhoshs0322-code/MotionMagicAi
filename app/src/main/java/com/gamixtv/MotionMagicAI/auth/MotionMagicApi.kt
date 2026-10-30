package com.gamixtv.MotionMagicAI.auth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MotionMagic's dedicated Django authentication adapter.
 * All credentials and account decisions remain on the server.
 */
class MotionMagicApi(
    private val config: AuthConfig,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : AuthBackend {
    suspend fun googleClientId(): String {
        val result = request("GET", "/auth/motion-ai/config")
        val clientId = result.optString("googleWebClientId")
        if (result.optString("appId") != config.appId || !result.optBoolean("enabled") ||
            !Regex("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com").matches(clientId))
            throw AuthException(AuthFailure.CONFIGURATION, "MotionMagic Google sign-in is not enabled on the server yet.")
        return clientId
    }
    override suspend fun signIn(idToken: String): AuthSession {
        if (idToken.isBlank() || idToken.length > 10_000) throw AuthException(AuthFailure.INVALID_RESPONSE, "Google did not return a usable identity. Please sign in again.")
        val result = request("POST", "/auth/motion-ai/google/native", JSONObject().put("app", config.appId).put("idToken", idToken))
        val token = result.optString("token")
        val user = parseUser(result.optJSONObject("user"))
        val expiry = SessionClaims.expiry(token, config.appId, user.id)
        if (expiry <= clock() + 30) throw AuthException(AuthFailure.UNAUTHORIZED, "The login session has expired. Please sign in again.")
        return AuthSession(token, user, expiry)
    }

    override suspend fun me(token: String): AuthUser {
        val result = request("GET", "/auth/motion-ai/me", token = token)
        if (result.optString("appId") != config.appId) throw AuthException(AuthFailure.UNAUTHORIZED, "This session belongs to a different app. Sign in to MotionMagic again.")
        return parseUser(result.optJSONObject("user"))
    }

    override suspend fun logout(token: String) {
        request("POST", "/auth/motion-ai/logout", JSONObject(), token)
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        config.validationError()?.let { throw AuthException(AuthFailure.CONFIGURATION, it) }
        if (token != null && (token.isBlank() || token.length > 4096 || token.any { it == '\r' || it == '\n' })) {
            throw AuthException(AuthFailure.UNAUTHORIZED, "The saved session is invalid. Sign in again.")
        }
        val connection = URL(config.normalizedBaseUrl + path).openConnection() as HttpsURLConnection
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-store")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw responseError(status, path)
                val data = connection.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        if (!continuation.isActive) return@suspendCancellableCoroutine
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 64 * 1024) throw AuthException(AuthFailure.INVALID_RESPONSE, "The login server returned an unexpected response. Please retry.")
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                }
                val result = runCatching { JSONObject(data) }.getOrElse {
                    throw AuthException(AuthFailure.INVALID_RESPONSE, "The login server returned an unreadable response. Please retry.")
                }
                if (!result.optBoolean("ok", false)) throw AuthException(AuthFailure.INVALID_RESPONSE, "The login server could not complete this request. Please retry.")
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                val safe = when(error) {
                    is AuthException -> error
                    is SocketTimeoutException -> AuthException(AuthFailure.NETWORK, "The login server took too long to respond. Check your connection and retry.")
                    is IOException -> AuthException(AuthFailure.NETWORK, "Could not reach the login server securely. Check your connection and retry.")
                    else -> AuthException(AuthFailure.SERVER, "Sign-in is unavailable right now. Please try again.")
                }
                if (continuation.isActive) continuation.resumeWithException(safe)
            } finally { connection.disconnect() }
        }
    }

    private fun responseError(status: Int, path: String): AuthException = when(status) {
        400 -> AuthException(AuthFailure.CONFIGURATION, "MotionMagic login is not enabled correctly on the server. Check the app registration and Google client setup.")
        404 -> AuthException(AuthFailure.CONFIGURATION, "Deploy the MotionMagic login module on the server first.")
        401, 403 -> if(path == "/auth/motion-ai/google/native") AuthException(AuthFailure.UNAUTHORIZED, "Google sign-in could not be verified. Try again or check the MotionMagic login setup.")
            else AuthException(AuthFailure.UNAUTHORIZED, "Your session is no longer valid. Please sign in again.")
        429 -> AuthException(AuthFailure.RATE_LIMIT, "Too many sign-in attempts. Wait a little before trying again.")
        in 300..399 -> AuthException(AuthFailure.CONFIGURATION, "The login server redirected this request. Configure its final HTTPS address.")
        else -> AuthException(AuthFailure.SERVER, "The login service is temporarily unavailable. Please retry later.")
    }

    private fun parseUser(json: JSONObject?): AuthUser {
        val id = json?.optString("id").orEmpty()
        if (id.isBlank() || id.length > 128) throw AuthException(AuthFailure.INVALID_RESPONSE, "The login server returned an invalid account. Please sign in again.")
        val email = json?.optString("email").orEmpty().take(320)
        val name = json?.optString("name").orEmpty().take(200).ifBlank { email.substringBefore('@').ifBlank { "MotionMagic creator" } }
        val picture = json?.optString("picture").orEmpty().take(2048).takeIf { it.startsWith("https://") }.orEmpty()
        return AuthUser(id, name, email, picture)
    }
}

/** Reads expiry and app binding for local UX only. The backend performs signature verification. */
object SessionClaims {
    fun expiry(token: String, appId: String, userId: String): Long {
        try {
            if(token.length !in 20..4096) throw IllegalArgumentException()
            val pieces = token.split('.')
            if(pieces.size != 3) throw IllegalArgumentException()
            val payload = JSONObject(String(Base64.getUrlDecoder().decode(pieces[1]), Charsets.UTF_8))
            if(payload.optString("iss") != "motionmagic-ai" || payload.optString("appId") != appId ||
                payload.optString("aud") != appId || payload.optString("sub") != userId || payload.optString("jti").isBlank()) throw IllegalArgumentException()
            return payload.optLong("exp", 0).takeIf { it > 0 } ?: throw IllegalArgumentException()
        } catch (_: Exception) {
            throw AuthException(AuthFailure.INVALID_RESPONSE, "The server returned a session for an unexpected app or account. Please sign in again.")
        }
    }
}
