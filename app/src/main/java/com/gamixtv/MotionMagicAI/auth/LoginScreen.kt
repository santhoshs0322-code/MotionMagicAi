package com.gamixtv.MotionMagicAI.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.gamixtv.MotionMagicAI.ui.MagicGradient
import com.gamixtv.MotionMagicAI.ui.MagicLogo
import com.gamixtv.MotionMagicAI.ui.Muted
import com.gamixtv.MotionMagicAI.ui.Pink
import com.gamixtv.MotionMagicAI.ui.Violet

/** Full-screen gate: no studio content is composed until server sign-in succeeds. */
@Composable
fun LoginGate(auth: AuthViewModel = viewModel(), onExit: () -> Unit = {}, content: @Composable (String) -> Unit) {
    val state by auth.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(state.status) {
        if (state.status != AuthStatus.SIGNED_IN) onExit()
    }
    val accountId = state.user?.id
    if (state.status == AuthStatus.SIGNED_IN && accountId != null) content(accountId)
    else LoginScreen(state, auth::signIn, auth::retryRestore, auth::dismissMessage)
}

@Composable
private fun LoginScreen(state: AuthUiState, signIn: (Activity) -> Unit, retry: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF07080D), Ink, Color(0xFF100422)))).semantics { testTag = "login-screen" }) {
        MagicLoginBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0x241C9DFF), shape = CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x553CBBFF))) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(9.dp).size(17.dp), tint = Color(0xFFE0C4FF))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(buildAnnotatedString { append("MotionMagic "); withStyle(SpanStyle(color = Violet)) { append("AI") } }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(132.dp).background(Brush.radialGradient(listOf(Color(0x4AB83BFF), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) {
                MagicLogo(Modifier.size(92.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Your ideas,\nmade magical.", color = Color.White, style = MaterialTheme.typography.displaySmall, lineHeight = 40.sp)
            Spacer(Modifier.height(10.dp))
            Text("Sign in to create, save and share\nyour next AI masterpiece.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(34.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(25.dp), colors = CardDefaults.cardColors(containerColor = Color(0xE6161720)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30253F))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color(0xFF251537), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Rounded.Lock, null, Modifier.padding(9.dp).size(18.dp), tint = Color(0xFFDFAAFF))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text("Welcome to your studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Sign in securely with Google", color = Muted, fontSize = 12.sp)
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pink, trackColor = Color(0xFF30253F))
                    Surface(onClick = { context.activity()?.let(signIn) }, enabled = !state.busy && state.configured,
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFFF9F8FC),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE7E2EF))) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Surface(shape = CircleShape, color = Color(0xFFF0EAFF)) { Text("G", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFF5D26CD), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
                            Spacer(Modifier.width(11.dp)); Text("Continue with Google", color = Color(0xFF201B29), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    if (!state.configured) Text("Sign-in is being configured. Please try again shortly.", color = Color(0xFFFFC13C), fontSize = 12.sp)
                    if (state.status == AuthStatus.RESTORING) Text("Checking your saved sign-in…", color = Muted, fontSize = 12.sp)
                    state.message?.let { message ->
                        Text(message, color = Color(0xFFFFA6B0), fontSize = 13.sp)
                        TextButton(onClick = dismiss) { Text("Dismiss") }
                    }
                    if (!state.busy && state.status != AuthStatus.GUEST) TextButton(onClick = retry, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Try again", color = Color(0xFFDEA5FF)) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Private by design · Your account keeps creations and credits secure", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Text("By continuing, you agree to use media you have permission to upload.", color = Color(0xFF737784), fontSize = 10.sp)
        }
    }
}

@Composable
private fun MagicLoginBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Brush.radialGradient(listOf(Color(0x452062FF), Color.Transparent)), radius = size.minDimension * .7f, center = androidx.compose.ui.geometry.Offset(size.width * .96f, size.height * .08f))
        drawCircle(Brush.radialGradient(listOf(Color(0x35E923DC), Color.Transparent)), radius = size.minDimension * .63f, center = androidx.compose.ui.geometry.Offset(size.width * .03f, size.height * .78f))
        val wave = Path().apply {
            moveTo(-20f, size.height * .82f)
            cubicTo(size.width * .28f, size.height * .68f, size.width * .55f, size.height * 1.02f, size.width + 20f, size.height * .77f)
        }
        drawPath(wave, MagicGradient, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
