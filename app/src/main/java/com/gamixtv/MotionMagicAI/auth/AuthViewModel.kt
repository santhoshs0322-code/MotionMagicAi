package com.gamixtv.MotionMagicAI.auth

import android.app.Activity
import android.app.Application
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val config = AuthConfig.fromBuildConfig()
    private val backend = MotionMagicApi(config)
    private val store = EncryptedAuthSessionStore(application, config)
    private val credentials = CredentialManager.create(application)
    private val mutable = MutableStateFlow(AuthUiState(configured = config.isConfigured))
    val state = mutable.asStateFlow()
    private var session: AuthSession? = null
    private var operation: Job? = null
    init { retryRestore() }

    fun retryRestore() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutable.value = mutable.value.copy(status = AuthStatus.RESTORING, busy = true, message = null)
            try {
                val saved = session ?: store.read()
                if (saved == null) { mutable.value = AuthUiState(configured = config.isConfigured); return@launch }
                session = saved
                if (saved.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000) {
                    store.clear(); session = null
                    mutable.value = AuthUiState(configured = config.isConfigured, message = "Your session expired. Sign in again.")
                    return@launch
                }
                val user = backend.me(saved.token)
                ensureActive()
                if (user.id != saved.user.id) throw AuthException(AuthFailure.UNAUTHORIZED, "Account changed. Sign in again.")
                session = saved.copy(user = user)
                mutable.value = AuthUiState(AuthStatus.SIGNED_IN, user, configured = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is AuthException && e.failure == AuthFailure.UNAUTHORIZED) {
                    runCatching { store.clear() }; session = null
                }
                mutable.value = AuthUiState(if (session != null) AuthStatus.OFFLINE else AuthStatus.GUEST,
                    session?.user, safeMessage(e), configured = config.isConfigured)
            }
        }
    }

    fun signIn(activity: Activity) {
        if (operation?.isActive == true || session != null) return
        operation = viewModelScope.launch {
            mutable.value = AuthUiState(AuthStatus.SIGNING_IN, busy = true, configured = config.isConfigured)
            var created: AuthSession? = null
            try {
                val clientId = backend.googleClientId()
                val request = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build()).build()
                val result = credentials.getCredential(activity, request).credential
                if (result !is CustomCredential || result.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                    throw AuthException(AuthFailure.INVALID_RESPONSE, "Google returned an unexpected sign-in response.")
                val idToken = GoogleIdTokenCredential.createFrom(result.data).idToken
                mutable.value = mutable.value.copy(status = AuthStatus.VERIFYING)
                created = backend.signIn(idToken)
                ensureActive()
                store.write(created)
                ensureActive()
                session = created
                mutable.value = AuthUiState(AuthStatus.SIGNED_IN, created.user, configured = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                created?.let { runCatching { backend.logout(it.token) } }
                mutable.value = AuthUiState(message = safeMessage(e), configured = config.isConfigured)
            }
        }
    }

    fun signOut() {
        operation?.cancel()
        val previous = session
        session = null
        mutable.value = AuthUiState(busy = true, configured = config.isConfigured)
        operation = viewModelScope.launch {
            var message = "Signed out. Projects remain on this device."
            try {
                store.clear()
                runCatching { credentials.clearCredentialState(ClearCredentialStateRequest()) }
                if (previous != null) try { backend.logout(previous.token) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "Signed out on this device. The server could not be reached to revoke the previous session." }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = safeMessage(e) }
            mutable.value = AuthUiState(message = message, configured = config.isConfigured)
        }
    }
    fun dismissMessage() { mutable.value = mutable.value.copy(message = null) }
    private fun safeMessage(e: Exception) = when (e) {
        is AuthException -> e.message ?: "Sign-in unavailable. Retry shortly."
        is GetCredentialCancellationException -> "Sign-in cancelled. You can continue as a guest."
        is NoCredentialException -> "Add a Google account to this device and try again."
        else -> "Google sign-in could not finish. Check your connection and the app's Google OAuth registration."
    }
}
