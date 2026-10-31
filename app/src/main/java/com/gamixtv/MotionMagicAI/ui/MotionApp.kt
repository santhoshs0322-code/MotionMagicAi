package com.gamixtv.MotionMagicAI.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.auth.LoginGate
import com.gamixtv.MotionMagicAI.data.MediaKind
import com.gamixtv.MotionMagicAI.data.Project
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionApp(vm: MotionViewModel) {
    LoginGate(onExit = { vm.activateAccount(null) }) { accountId ->
        LaunchedEffect(accountId) { vm.activateAccount(accountId) }
        if (vm.activeAccountId == accountId) AuthenticatedMotionApp(vm)
        else Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Violet) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedMotionApp(vm: MotionViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val work by vm.work.collectAsState()
    var splash by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Project?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importPhoto) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importAudio) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importVideo) }
    val documentSaver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = if(result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null
        if(uri != null) pendingExport?.let { vm.saveDocument(it, uri) }
        pendingExport = null
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.saveSettings(vm.settings.copy(notifications = granted))
        if (!granted) vm.message("Notifications are off. You can enable them in Android Settings.")
    }
    val export: (Project) -> Unit = { project ->
        if (project.kind == MediaKind.VIDEO && Build.VERSION.SDK_INT >= 29) vm.exportToGallery(project)
        else {
            pendingExport = project
            val extension = when(project.mimeType) { "audio/mp4" -> "m4a"; "audio/wav" -> "wav"; "video/mp4" -> "mp4"
                else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(project.mimeType) ?: "media" }
            documentSaver.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(project.mimeType).putExtra(Intent.EXTRA_TITLE, "MotionMagic_${project.id.take(8)}.$extension"))
        }
    }
    LaunchedEffect(Unit) { delay(950); splash = false }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.screen != Screen.HOME && work == null) { vm.back() }
    if(splash) {
        SplashScreen()
        return
    }
    val roots = listOf(Screen.HOME, Screen.TEMPLATES, Screen.PROJECTS, Screen.PROFILE)
    val creating = vm.screen in listOf(Screen.IMAGE, Screen.TEXT, Screen.REELS, Screen.CREATE_IMAGE)
    Scaffold(containerColor = Ink, snackbarHost = {
        SnackbarHost(snackbar, Modifier.padding(bottom = if(creating) 128.dp else 0.dp))
    },
        topBar = {
            if(vm.screen !in listOf(Screen.EDITOR, Screen.AUDIO_EDITOR, Screen.HOME, Screen.TEMPLATES, Screen.IMAGE, Screen.TEXT, Screen.REELS, Screen.CREATE_IMAGE)) CenterAlignedTopAppBar(
                title = {
                    if(vm.screen == Screen.HOME) Text(buildAnnotatedString {
                        append("MotionMagic ")
                        withStyle(SpanStyle(color = Violet)) { append("AI") }
                    }, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    else Text(if(vm.screen in listOf(Screen.IMAGE, Screen.TEXT, Screen.REELS, Screen.CREATE_IMAGE)) "Create" else if(vm.screen == Screen.RESULT && vm.selectedProject?.kind == MediaKind.AUDIO) "Voice Result" else if(vm.screen == Screen.RESULT && vm.selectedProject?.kind == MediaKind.IMAGE) "Image Result" else vm.screen.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = { if(vm.screen == Screen.HOME) menu = true else vm.back() }) {
                        Icon(if(vm.screen == Screen.HOME) Icons.Rounded.Menu else Icons.Rounded.ArrowBackIosNew,
                            if(vm.screen == Screen.HOME) "Open menu" else "Back", Modifier.size(23.dp))
                    }
                },
                actions = {
                    if(vm.screen == Screen.HOME) {
                        TextButton(onClick = { vm.go(Screen.PLANS) }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF261800)),
                            modifier = Modifier.padding(end = 12.dp).height(30.dp).background(Color(0xFFFFC13C), RoundedCornerShape(9.dp)),
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp)) {
                            Icon(Icons.Rounded.WorkspacePremium, null, Modifier.size(18.dp))
                            Text(" SERVICES", fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                        }
                    }
                }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Ink)
            )
        },
        bottomBar = {
            if(vm.screen in roots) NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                listOf(Triple(Screen.HOME, Icons.Rounded.Home, "Create"),
                    Triple(Screen.TEMPLATES, Icons.Rounded.CollectionsBookmark, "Template"),
                    Triple(Screen.IMAGE, Icons.Rounded.Add, ""),
                    Triple(Screen.PROJECTS, Icons.Rounded.FolderOpen, "Projects"),
                    Triple(Screen.PROFILE, Icons.Rounded.PersonOutline, "Me")).forEach { (screen, icon, label) ->
                    if(screen == Screen.IMAGE) NavigationBarItem(selected = false, onClick = { vm.go(Screen.IMAGE) },
                        icon = { Box(Modifier.size(40.dp).background(ReferenceGradient, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { Icon(icon, "Create a new project", Modifier.size(27.dp), tint = Color.White) } },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent))
                    else
                    NavigationBarItem(selected = vm.screen == screen, onClick = { vm.tab(screen) },
                        icon = { Icon(icon, null, Modifier.size(24.dp)) }, label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White,
                            indicatorColor = Color.Transparent, unselectedTextColor = Muted, unselectedIconColor = Muted))
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when(vm.screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.IMAGE, Screen.TEXT, Screen.REELS, Screen.CREATE_IMAGE -> CreateScreen(vm, onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                Screen.VOICE -> VoiceScreen(vm)
                Screen.TEMPLATES -> TemplatesScreen(vm)
                Screen.PROJECTS -> ProjectsScreen(vm, onImport = { videoPicker.launch(arrayOf("video/*")) })
                Screen.RESULT -> ResultScreen(vm, export, onShare = { project ->
                    vm.share(project)?.let { intent ->
                        try { context.startActivity(Intent.createChooser(intent, "Share your creation")) }
                        catch (_: Exception) { vm.notice("Sharing unavailable", "No compatible sharing app is installed on this device.") }
                    }
                })
                Screen.EDITOR -> StudioEditorScreen(vm, false) { videoPicker.launch(arrayOf("video/*")) }
                Screen.AUDIO_EDITOR -> StudioEditorScreen(vm, true) { audioPicker.launch(arrayOf("audio/*", "video/*")) }
                Screen.PROFILE -> ProfileScreen(vm)
                Screen.PLANS -> PlansScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm) { enabled ->
                    if(enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.saveSettings(vm.settings.copy(notifications = enabled))
                }
            }
        }
    }
    if(menu) ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = Ink) {
        Column(Modifier.padding(bottom = 25.dp)) {
            Text("Your creative studio", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp))
            SettingRow(Icons.Rounded.FolderOpen, "My projects", onClick = { menu = false; vm.tab(Screen.PROJECTS) })
            SettingRow(Icons.Rounded.Settings, "Settings", onClick = { menu = false; vm.go(Screen.SETTINGS) })
            SettingRow(Icons.Rounded.WorkspacePremium, "Available services", onClick = { menu = false; vm.go(Screen.PLANS) })
            SettingRow(Icons.Rounded.HelpOutline, "Help & support", onClick = { menu = false; showHelp(vm) })
            TestNotice(modifier = Modifier.padding(16.dp))
        }
    }
    work?.let { state ->
        AlertDialog(onDismissRequest = {}, icon = { Icon(Icons.Rounded.AutoFixHigh, null, tint = Violet) },
            title = { Text(state.title, fontSize = 20.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if(state.progress > 0f) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = Violet)
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Violet)
                    Text(state.detail.ifBlank { "Please wait while we prepare your file." }, color = Muted)
                    if(state.progress > 0f) Text("${(state.progress * 100).toInt()}%", color = Color.White)
                }
            }, confirmButton = { TextButton(onClick = vm::cancelWork) { Text("Cancel") } }, containerColor = Panel)
    }
    vm.cloudQuote?.let { quote ->
        val enough = quote.availablePaise >= quote.chargePaise
        AlertDialog(onDismissRequest = vm::dismissCloudQuote, containerColor = Panel,
            title = { Text("Create with ${quote.title}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Maximum charge: ${com.gamixtv.MotionMagicAI.cloud.credits(quote.chargePaise)}", fontWeight = FontWeight.SemiBold)
                Text("Available balance: ${com.gamixtv.MotionMagicAI.cloud.credits(quote.availablePaise)}")
                Text("One creation. Credits are reserved when you confirm and released if generation fails. Closing progress does not cancel a submitted generation.", fontSize = 13.sp)
                if(!enough) Text("Add generation credits before continuing. Your account balance is managed by the server.", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = vm::confirmCloudGeneration, enabled = enough) { Text("Confirm & create") } },
            dismissButton = { TextButton(onClick = vm::dismissCloudQuote) { Text("Keep editing") } })
    }
    vm.failure?.let { error ->
        AlertDialog(onDismissRequest = vm::dismissFailure,
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFF8190)) },
            title = { Text("Couldn’t finish that") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = vm::dismissFailure) { Text("OK") } }, containerColor = Panel)
    }
    vm.info?.let { (title, body) ->
        AlertDialog(onDismissRequest = vm::dismissNotice, title = { Text(title) },
            text = { Text(body, Modifier.verticalScroll(rememberScrollState()), lineHeight = 22.sp) },
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("Got it") } }, containerColor = Panel)
    }
}

fun showHelp(vm: MotionViewModel) = vm.notice("Your creative studio", "Choose Image to Video, Text to Video or Create Image. Add your photo or prompt, then choose a model and settings. Available models depend on the server configuration. Tap Create to review the credit price before confirming. Use Check cloud generation to recover a submitted request or download after reconnecting. Kling and PixVerse image-to-video follow the source photo’s shape.\n\nUse Video Editor or Audio Editor to edit your own media. Device Voice uses your installed Android speech engine.")

@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Color(0xFF0B031D))))) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight(.42f).align(Alignment.BottomCenter)) {
            for(i in 0..3) {
                val y = size.height * (.05f + i * .12f)
                val wave = Path().apply {
                    moveTo(0f, y)
                    cubicTo(size.width * .35f, y + 30f, size.width * .35f, y + size.height * .6f, size.width, y + size.height * .05f)
                    lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(wave, Brush.linearGradient(listOf(Color(0xFF1687FA), Color(0xFF6F13FB), Color(0xFFB500EE))))
            }
        }
        Column(Modifier.align(Alignment.Center).padding(bottom = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MagicLogo(Modifier.size(122.dp))
            Spacer(Modifier.height(23.dp))
            Text(buildAnnotatedString { append("MotionMagic "); withStyle(SpanStyle(color = Violet)) { append("AI") } },
                color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Turn Your Ideas Into Amazing Videos", color = Color(0xFFBCBFCE), fontSize = 13.sp)
            Spacer(Modifier.height(45.dp))
            CircularProgressIndicator(Modifier.size(32.dp), color = Pink, strokeWidth = 3.dp)
        }
        Text("YOUR CREATIVE STUDIO", color = Color(0xFFBA9ED9), letterSpacing = 3.sp, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 45.dp))
    }
}

@Composable
fun MagicLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * .07f, size.height * .83f)
            lineTo(size.width * .2f, size.height * .12f)
            quadraticBezierTo(size.width * .22f, size.height * .04f, size.width * .28f, size.height * .12f)
            lineTo(size.width * .51f, size.height * .46f)
            lineTo(size.width * .75f, size.height * .12f)
            quadraticBezierTo(size.width * .82f, size.height * .04f, size.width * .85f, size.height * .14f)
            lineTo(size.width * .96f, size.height * .82f)
            quadraticBezierTo(size.width * .97f, size.height * .97f, size.width * .85f, size.height * .9f)
            lineTo(size.width * .68f, size.height * .61f)
            lineTo(size.width * .55f, size.height * .8f)
            quadraticBezierTo(size.width * .51f, size.height * .87f, size.width * .46f, size.height * .8f)
            lineTo(size.width * .32f, size.height * .6f)
            lineTo(size.width * .19f, size.height * .9f)
            quadraticBezierTo(size.width * .06f, size.height * .96f, size.width * .07f, size.height * .83f)
            close()
        }
        drawPath(path, Brush.linearGradient(listOf(Color(0xFFEA22F5), Color(0xFF741CFF), Color(0xFF20A6FF))))
    }
}
