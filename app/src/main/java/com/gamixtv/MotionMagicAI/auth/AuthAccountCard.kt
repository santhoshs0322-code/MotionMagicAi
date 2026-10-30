package com.gamixtv.MotionMagicAI.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AuthAccountCard(auth: AuthViewModel = viewModel()) {
    val state by auth.state.collectAsState()
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.user?.name ?: "Your MotionMagic account", style = MaterialTheme.typography.titleMedium)
            state.user?.email?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(when (state.status) {
                AuthStatus.RESTORING -> "Checking saved sign-in…"
                AuthStatus.SIGNING_IN -> "Connecting to Google…"
                AuthStatus.VERIFYING -> "Verifying your account…"
                AuthStatus.SIGNED_IN -> "Signed in with Google"
                AuthStatus.OFFLINE -> "Saved account • verification unavailable"
                AuthStatus.GUEST -> "Sign in with Google or continue as a guest."
            })
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.status == AuthStatus.GUEST) {
                OutlinedButton(onClick = { context.activity()?.let(auth::signIn) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
            } else if (!state.busy) {
                if (state.status == AuthStatus.OFFLINE)
                    OutlinedButton(onClick = auth::retryRestore) { Text("Retry verification") }
                TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
            }
            Text("Projects stay on this device and are shared by everyone using this local workspace. Sign-in does not enable paid AI or cloud sync.",
                style = MaterialTheme.typography.bodySmall)
            state.message?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = auth::dismissMessage) { Text("Dismiss") }
            }
        }
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out?") },
        text = { Text("Your local projects stay on this device. Other users of this workspace can still open them.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; auth.signOut() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } })
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
