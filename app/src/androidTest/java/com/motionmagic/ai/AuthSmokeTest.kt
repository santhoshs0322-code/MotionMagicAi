package com.motionmagic.ai

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.util.Base64
import com.motionmagic.ai.auth.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuthSmokeTest {
    private fun token(app: String = "motionmagic", issuer: String = "motionmagic-ai"): String {
        val payload = JSONObject().put("iss", issuer).put("appId", app).put("aud", app)
            .put("sub", "012345678901234567890123").put("jti", "test-id").put("exp", 2000000000)
        return "testheader." + Base64.encodeToString(payload.toString().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) + ".testsignature"
    }
    @Test fun localClaimsRejectOtherAppsAndIssuers() {
        assertEquals(2000000000L, SessionClaims.expiry(token(), "motionmagic", "012345678901234567890123"))
        listOf(token("candycraze"), token(issuer = "central-game-auth"), "malformed").forEach {
            try { SessionClaims.expiry(it, "motionmagic", "012345678901234567890123"); fail("Unexpected session accepted") }
            catch (_: AuthException) { }
        }
    }
    @Test fun encryptedStorageRoundTripClearAndServerBinding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedAuthSessionStore(context, AuthConfig("https://example.test", ""))
        store.clear()
        try {
            val session = AuthSession(token(), AuthUser("012345678901234567890123", "Creator", "test@example.test"), 2000000000)
            store.write(session)
            assertEquals(session, store.read())
            val stored = context.getSharedPreferences("motion_ai_auth", Context.MODE_PRIVATE).getString("session", "")!!
            assertFalse(stored.contains(session.token)); assertFalse(stored.contains(session.user.email))
            val otherServer = EncryptedAuthSessionStore(context, AuthConfig("https://other.example.test", ""))
            try { otherServer.read(); fail("Server binding must reject the session") } catch (_: AuthException) { }
            assertNull(store.read())
            store.write(session); store.clear(); assertNull(store.read())
        } finally { store.clear() }
    }
}
