package com.gamixtv.MotionMagicAI.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamixtv.MotionMagicAI.ui.Ink
import com.gamixtv.MotionMagicAI.ui.MagicLogo
import com.gamixtv.MotionMagicAI.ui.Muted
import com.gamixtv.MotionMagicAI.ui.Pink
import com.gamixtv.MotionMagicAI.ui.Violet

/** Full-screen gate: no studio content is composed until server sign-in succeeds. */
@Composable
fun LoginGate(auth: AuthViewModel = viewModel(), content: @Composable () -> Unit) {
    val state by auth.state.collectAsState()
    if (state.status == AuthStatus.SIGNED_IN) content() else LoginScreen(state, auth::signIn, auth::retryRestore, auth::dismissMessage)
}

@Composable
private fun LoginScreen(state: AuthUiState, signIn: (Activity) -> Unit, retry: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Color(0xFF100522)))).semantics { testTag = "login-screen" }) {
        Column(Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MagicLogo(Modifier.size(100.dp))
            Spacer(Modifier.height(22.dp))
            Text(buildAnnotatedString { append("MotionMagic "); withStyle(SpanStyle(color = Violet)) { append("AI") } }, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(9.dp))
            Text("Create images and videos with AI", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(44.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF171326))) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Sign in to continue", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Your account keeps generations, credits and cloud projects secure.", color = Muted, fontSize = 13.sp)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pink)
                    Button(onClick = { context.activity()?.let(signIn) }, enabled = !state.busy && state.configured,
                        modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                        Icon(Icons.Rounded.Login, null); Spacer(Modifier.width(9.dp)); Text("Continue with Google", fontWeight = FontWeight.Bold)
                    }
                    if (!state.configured) Text("Sign-in is being configured. Please try again shortly.", color = Color(0xFFFFC13C), fontSize = 12.sp)
                    if (state.status == AuthStatus.RESTORING) Text("Checking your saved sign-in…", color = Muted, fontSize = 12.sp)
                    state.message?.let { message ->
                        Text(message, color = Color(0xFFFFA6B0), fontSize = 13.sp)
                        TextButton(onClick = dismiss) { Text("Dismiss") }
                    }
                    if (!state.busy && state.status != AuthStatus.GUEST) TextButton(onClick = retry, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Try again") }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("By continuing, you agree to use only media you have permission to upload and publish.", color = Muted, fontSize = 11.sp)
        }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
