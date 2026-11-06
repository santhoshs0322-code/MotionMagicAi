package com.gamixtv.MotionMagicAI.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamixtv.MotionMagicAI.ui.StudioColors
import com.gamixtv.MotionMagicAI.ui.MagicGradient
import com.gamixtv.MotionMagicAI.ui.MagicLogo
import com.gamixtv.MotionMagicAI.ui.SplashScreen

/** Full-screen gate: no studio content is composed until server sign-in succeeds. */
@Composable
fun LoginGate(auth: AuthViewModel = viewModel(), onExit: () -> Unit = {}, content: @Composable (String) -> Unit) {
    val state by auth.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(state.status) {
        if (state.status == AuthStatus.GUEST || state.status == AuthStatus.OFFLINE) onExit()
    }
    val accountId = state.user?.id
    when {
        state.status == AuthStatus.SIGNED_IN && accountId != null -> content(accountId)
        state.status == AuthStatus.RESTORING -> SplashScreen("Checking your saved sign-in")
        state.status == AuthStatus.OFFLINE -> SplashScreen(state.message ?: "Could not verify your session", auth::retryRestore)
        else -> LoginScreen(state, auth::signIn, auth::retryRestore, auth::dismissMessage)
    }
}

@Composable
internal fun LoginScreen(state: AuthUiState, signIn: (Activity) -> Unit, retry: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = StudioColors
    val message = state.message?.takeIf { it.isNotBlank() }
    val notice = message ?: if(!state.configured && !state.busy) "Sign-in is being configured. Please try again shortly." else null
    val noticeColor = when {
        message == null -> colors.warning
        state.messageIsError -> colors.error
        else -> colors.primary
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.background, colors.background, colors.field)))
        .semantics { testTag = "login-screen" }) {
        MagicLoginBackdrop(Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 720.dp
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(Modifier.semantics { testTag = "login-brand" }, verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = colors.selected, shape = CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(9.dp).size(17.dp), tint = colors.primary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(buildAnnotatedString { append("MotionMagic "); withStyle(SpanStyle(color = colors.primary)) { append("AI") } }, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(if(compact) 24.dp else 40.dp))
            Box(Modifier.size(if(compact) 104.dp else 124.dp).background(Brush.radialGradient(listOf(colors.primary.copy(alpha = .22f), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) {
                MagicLogo(Modifier.size(if(compact) 80.dp else 92.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Your ideas,\nmade magical.", Modifier.fillMaxWidth().semantics { testTag = "login-heading" },
                color = colors.text, style = MaterialTheme.typography.displaySmall, fontSize = 32.sp, lineHeight = 40.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("Sign in to create, save and share your next AI masterpiece.", Modifier.fillMaxWidth(), color = colors.muted,
                fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth().semantics { testTag = "login-card" }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(color = colors.selected, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Rounded.Lock, null, Modifier.padding(10.dp).size(20.dp), tint = colors.primary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Welcome to your studio", Modifier.fillMaxWidth(), color = colors.text, fontWeight = FontWeight.Bold,
                                fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
                            Text("Sign in securely with Google", Modifier.fillMaxWidth(), color = colors.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.border)
                        Text(when(state.status) {
                            AuthStatus.SIGNING_IN -> "Opening Google sign-in…"
                            AuthStatus.VERIFYING -> "Signing you in…"
                            else -> "Please wait…"
                        }, Modifier.fillMaxWidth(), color = colors.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    Surface(onClick = { context.activity()?.let(signIn) }, enabled = !state.busy && state.configured,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { testTag = "google-sign-in" }, shape = RoundedCornerShape(16.dp), color = Color(0xFFF9F8FC),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE7E2EF))) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Surface(shape = CircleShape, color = Color(0xFFF0EAFF)) { Text("G", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFF5D26CD), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
                            Spacer(Modifier.width(11.dp)); Text("Continue with Google", Modifier.weight(1f, fill = false), color = Color(0xFF201B29), fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                        }
                    }
                    if(notice != null) Surface(Modifier.fillMaxWidth().semantics { testTag = "login-notice"; liveRegion = LiveRegionMode.Polite },
                        shape = RoundedCornerShape(14.dp), color = noticeColor.copy(alpha = .08f)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(notice, Modifier.fillMaxWidth().semantics { testTag = "login-message" }, color = noticeColor,
                                fontSize = 12.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                            if(message != null) TextButton(onClick = dismiss) { Text("Dismiss", color = noticeColor) }
                        }
                    }
                    if (!state.busy && state.status != AuthStatus.GUEST) TextButton(onClick = retry) { Text("Try again", color = colors.primary) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Private by design. Your account keeps creations and credits secure.", Modifier.fillMaxWidth(),
                color = colors.muted, fontSize = 10.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("By continuing, you agree to use media you have permission to upload.", Modifier.fillMaxWidth().semantics { testTag = "login-footer" },
                color = colors.muted, fontSize = 10.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
        }
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
