package com.motionmagic.ai.auth

import org.junit.Assert.*
import org.junit.Test

class AuthConfigTest {
    @Test fun acceptsHttpsWithServerProvidedPublicClientId() {
        assertNull(AuthConfig("https://api.motionmagic.example", "").validationError())
    }
    @Test fun rejectsUnsafeUrlsAndWrongApp() {
        listOf("http://example.com", "https://name:password@example.com", "https://example.com?token=123",
            "https://example.com#fragment", "https://example.com:8080", "https://example.com/../bad").forEach {
            assertNotNull(it, AuthConfig(it, "").validationError())
        }
        assertNotNull(AuthConfig("https://example.com", "", "candycraze").validationError())
    }
    @Test fun offlineCachedAccountIsNotVerifiedSignIn() {
        assertFalse(AuthUiState(AuthStatus.OFFLINE, AuthUser("123", "Creator", "test@example.test")).isSignedIn)
    }
    @Test fun sessionStringDoesNotExposeBearer() {
        assertFalse(AuthSession("private-bearer-value", AuthUser("123", "Creator", "test@example.test"), 1).toString().contains("private-bearer-value"))
    }
}
