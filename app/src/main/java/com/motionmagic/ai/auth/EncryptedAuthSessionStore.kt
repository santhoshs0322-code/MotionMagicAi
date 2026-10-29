package com.motionmagic.ai.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Keystore encryption binds each saved session to this server and app. Backups are disabled. */
class EncryptedAuthSessionStore(context: Context, config: AuthConfig) : AuthSessionStore {
    private val prefs = context.getSharedPreferences("motion_ai_auth", Context.MODE_PRIVATE)
    private val alias = "motion_ai_session_v1"
    private val binding = "${config.normalizedBaseUrl}|${config.appId}".toByteArray()
    private val mutex = Mutex()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun read(): AuthSession? = withContext(Dispatchers.IO) { mutex.withLock {
        val data = prefs.getString("session", null) ?: return@withLock null
        try {
            val parts = data.split('.')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                updateAAD(binding)
            }
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            val user = AuthUser(json.getString("id"), json.getString("name"), json.getString("email"))
            val token = json.getString("token")
            AuthSession(token, user, SessionClaims.expiry(token, "motionmagic", user.id))
        } catch (_: Exception) {
            prefs.edit().remove("session").commit()
            throw AuthException(AuthFailure.STORAGE, "Your saved sign-in could not be restored. Please sign in again.")
        }
    } }
    override suspend fun write(session: AuthSession) = withContext(Dispatchers.IO) { mutex.withLock {
        try {
            val json = JSONObject().put("token", session.token).put("id", session.user.id)
                .put("name", session.user.name).put("email", session.user.email)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(binding) }
            val encrypted = cipher.doFinal(json.toString().toByteArray())
            val data = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            if (!prefs.edit().putString("session", data).commit()) throw IllegalStateException()
        } catch (_: Exception) { throw AuthException(AuthFailure.STORAGE, "Secure session storage is unavailable. Please retry.") }
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        if (!prefs.edit().remove("session").commit()) throw AuthException(AuthFailure.STORAGE, "Could not clear saved sign-in. Please retry signing out.")
    } }
}
