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
import com.gamixtv.MotionMagicAI.data.VideoModelCatalog

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
    var menu by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(vm.activeAccountId) { pendingExport = null; snackbar.currentSnackbarData?.dismiss() }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importPhoto) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importReferenceVideo) }
    var referencePick by remember { mutableStateOf<Pair<String, Int?>?>(null) }
    val referencePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val pending = referencePick; referencePick = null
        if(uri != null && pending != null && vm.activeAccountId == pending.first) vm.importReferencePhotos(listOf(uri), pending.second)
    }
    val referencePhotosPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia((4 - vm.referencePhotoPaths.size).coerceAtLeast(2))) { uris ->
        val pending = referencePick; referencePick = null
        if(uris.isNotEmpty() && pending != null && vm.activeAccountId == pending.first) vm.importReferencePhotos(uris)
    }
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
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.screen != Screen.HOME && work == null) { vm.back() }
    val roots = listOf(Screen.HOME, Screen.TEMPLATES, Screen.PROJECTS, Screen.PROFILE)
    val creating = vm.screen in listOf(Screen.IMAGE, Screen.STORY, Screen.TEXT, Screen.REELS, Screen.CREATE_IMAGE)
    Scaffold(containerColor = Ink, snackbarHost = {
        SnackbarHost(snackbar, Modifier.padding(bottom = if(creating) 128.dp else 0.dp))
    },
        topBar = {
            if(vm.screen !in listOf(Screen.HOME, Screen.TEMPLATES, Screen.IMAGE, Screen.STORY, Screen.TEXT, Screen.REELS, Screen.CREATE_IMAGE, Screen.CREATION_SETTINGS)) CenterAlignedTopAppBar(
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
                    TextButton(onClick = { vm.go(Screen.PLANS) }, colors = ButtonDefaults.textButtonColors(contentColor = StudioColors.text),
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
                    Triple(Screen.TEMPLATES, Icons.Rounded.CollectionsBookmark, "Templates"),
                    Triple(Screen.IMAGE, Icons.Rounded.Add, ""),
                    Triple(Screen.PROJECTS, Icons.Rounded.FolderOpen, "Projects"),
                    Triple(Screen.PROFILE, Icons.Rounded.PersonOutline, "Me")).forEach { (screen, icon, label) ->
                    if(screen == Screen.IMAGE) NavigationBarItem(selected = false, onClick = { vm.go(Screen.IMAGE) },
                        icon = { Box(Modifier.size(40.dp).background(ReferenceGradient, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { Icon(icon, "Create a new project", Modifier.size(27.dp), tint = Color.White) } },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent))
                    else
                    NavigationBarItem(selected = vm.screen == screen, onClick = { vm.tab(screen) },
                        icon = { Icon(icon, null, Modifier.size(24.dp)) }, label = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(label, fontSize = 11.sp)
                                Box(Modifier.width(28.dp).height(2.dp).background(if(vm.screen == screen) StudioColors.primary else Color.Transparent))
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = StudioColors.primary, selectedTextColor = StudioColors.primary,
                            indicatorColor = Color.Transparent, unselectedTextColor = Muted, unselectedIconColor = Muted))
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when(vm.screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.LONG_STORY -> LongStoryScreen(vm,
                    pickVideo = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    pickPhoto = { index -> vm.activeAccountId?.let { account ->
                        referencePick = account to index
                        referencePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } })
                Screen.CREATION_SETTINGS -> CreationSettingsScreen(vm)
                Screen.IMAGE, Screen.STORY, Screen.TEXT, Screen.REELS, Screen.CREATE_IMAGE -> CreateScreen(vm,
                    onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickVideo = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    onPickReferencePhotos = { index ->
                        vm.activeAccountId?.let { account ->
                            referencePick = account to index
                            if(index != null || vm.referencePhotoPaths.size >= 3) referencePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            else referencePhotosPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    })
                Screen.VOICE -> VoiceScreen(vm)
                Screen.TEMPLATES -> TemplatesScreen(vm)
                Screen.PROJECTS -> ProjectsScreen(vm)
                Screen.RESULT -> ResultScreen(vm, export, onShare = { project ->
                    vm.share(project)?.let { intent ->
                        try { context.startActivity(Intent.createChooser(intent, "Share your creation")) }
                        catch (_: Exception) { vm.notice("Sharing unavailable", "No compatible sharing app is installed on this device.") }
                    }
                })
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
        AlertDialog(onDismissRequest = vm::cancelWork, icon = { Icon(Icons.Rounded.AutoFixHigh, null, tint = StudioColors.primary) },
            title = { Text(state.title, fontSize = 20.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if(state.progress > 0f) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = Violet)
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Violet)
                    Text(state.detail.ifBlank { "Please wait while we prepare your file." }, color = Muted)
                    if(state.progress > 0f) Text("${(state.progress * 100).toInt()}%", color = StudioColors.text)
                }
            }, confirmButton = { TextButton(onClick = vm::cancelWork) { Text("Cancel", color = StudioColors.primary) } },
            shape = RoundedCornerShape(24.dp), containerColor = Panel)
    }
    vm.cloudQuote?.let { quote ->
        val enough = quote.availablePaise >= quote.chargePaise
        AlertDialog(onDismissRequest = vm::dismissCloudQuote, containerColor = Panel, shape = RoundedCornerShape(24.dp),
            icon = { Box(Modifier.size(52.dp).background(ReferenceGradient, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Bolt, null, tint = Color.White) } },
            title = { Text("Create with ${quote.title}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Maximum charge: ${com.gamixtv.MotionMagicAI.cloud.credits(quote.chargePaise)}", fontWeight = FontWeight.SemiBold)
                Text("Available balance: ${com.gamixtv.MotionMagicAI.cloud.credits(quote.availablePaise)}")
                if(quote.draft.modelId == "long-story") Text("${quote.draft.duration / 60} minute story · ${quote.draft.duration / 15} scenes · character dialogue with native lip sync. Voices and faces may vary between scenes. The finished film is saved to your account before completion.", fontSize = 12.sp)
                if(VideoModelCatalog.isReference(quote.draft.modelId) && !quote.image) {
                    Text(if(quote.draft.modelId == "kling-o3-edit") "Edit this video · ${quote.draft.duration} billed seconds · ${quote.draft.resolution}"
                        else "Create a new scene · ${quote.draft.duration}s output · ${quote.draft.resolution}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${quote.referencePhotoPaths.size} reference photos included. ${if(quote.draft.modelId == "kling-o3-edit") "Source length is rounded up to a whole second and verified before generation." else "Credits are based on the selected output length."}", fontSize = 12.sp)
                    Text(
                    if(quote.draft.generatedAudio) "Audio: keeps the reference's existing sound. A silent reference stays silent; this model does not generate new audio."
                    else "Audio: off. This model does not generate new sound. Use Kling O3 with a photo or text prompt for generated audio.",
                    color = StudioColors.warning, fontSize = 12.sp)
                }
                Text(if(quote.deferredInput && (quote.sourcePhoto || quote.sourceVideoPath != null))
                    "After confirmation, your reference uploads with progress, then your generation appears in My Projects. Credits are reserved while it runs and charged only on success."
                    else "After confirmation, your generation appears in My Projects. You can run up to three generations at once. Credits are reserved while it runs and charged only on success.", fontSize = 13.sp)
                if(!enough) Text("Add ${com.gamixtv.MotionMagicAI.cloud.credits(quote.chargePaise - quote.availablePaise)} to create this video or image.", color = StudioColors.warning)
            } },
            confirmButton = { Button(onClick = { if(enough) vm.confirmCloudGeneration() else { vm.dismissCloudQuote(); vm.go(Screen.PLANS) } },
                modifier = Modifier.background(ReferenceGradient, RoundedCornerShape(12.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White)) { Text(if(enough) "Confirm & create" else "Add credits") } },
            dismissButton = { TextButton(onClick = vm::dismissCloudQuote) { Text("Keep editing") } })
    }
    if(work == null && vm.cloudQuote == null && vm.failure == null && vm.info == null) vm.completedJobNotice?.let { notice ->
        val savedProject = vm.projects.firstOrNull { it.id == notice.projectId }
        val cloudResult = vm.cloudJobs.firstOrNull { "cloud_${it.id}" == notice.projectId }
        val preview = cloudResult?.outputAssetId?.let { output -> vm.activeAccountId?.let { account ->
            com.gamixtv.MotionMagicAI.cloud.CloudThumbnailSource(output, account, cloudResult.thumbnailUrl, cloudResult.thumbnailState)
        } }
        AlertDialog(
            onDismissRequest = vm::dismissCompletedJobNotice,
            icon = {
                Box(Modifier.size(58.dp).background(ReferenceGradient, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(32.dp), tint = Color.White)
                }
            },
            title = { Text("Your ${if(notice.kind == MediaKind.IMAGE) "image" else "video"} is ready", fontSize = 21.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectThumbnail(savedProject?.path, notice.kind == MediaKind.VIDEO,
                        modifier = Modifier.fillMaxWidth().height(170.dp), remote = preview)
                    Text(notice.title, color = StudioColors.text, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text("${if(vm.projects.any { it.id == notice.projectId }) "Saved on this device" else "Saved to your account"} · ${notice.chargedCredits} credits used", color = Muted, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(onClick = vm::viewCompletedJob,
                    modifier = Modifier.background(ReferenceGradient, RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) {
                    Text("View creation", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissCompletedJobNotice) { Text("Later", color = StudioColors.primary) } },
            shape = RoundedCornerShape(24.dp), containerColor = Panel
        )
    }
    vm.failure?.let { error ->
        AlertDialog(onDismissRequest = vm::dismissFailure,
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = StudioColors.error) },
            title = { Text("Couldn’t finish that") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = vm::dismissFailure) { Text("OK", color = StudioColors.primary) } },
            shape = RoundedCornerShape(24.dp), containerColor = Panel)
    }
    vm.info?.let { (title, body) ->
        AlertDialog(onDismissRequest = vm::dismissNotice, title = { Text(title) },
            text = { Text(body, Modifier.verticalScroll(rememberScrollState()), lineHeight = 22.sp) },
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("Got it", color = StudioColors.primary) } },
            shape = RoundedCornerShape(24.dp), containerColor = Panel)
    }
}

fun showHelp(vm: MotionViewModel) = vm.notice("Your creative studio", "Choose Image to Video, Text to Video or Create Image. Add your photo or prompt, then choose a model and settings. Tap Create to review the credit price before confirming. Submitted jobs appear in My Projects with a stage estimate. Tap Refresh projects to recover a request or download after reconnecting. Up to three generations can run at once. Kling and PixVerse image-to-video follow the source photo’s shape.\n\nDevice Voice uses your installed Android speech engine. Saved creations can be played, downloaded and shared from My Projects.")

@Composable
fun SplashScreen(message: String = "Opening your creative studio", onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, StudioColors.field)))) {
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
                color = StudioColors.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Turn Your Ideas Into Amazing Videos", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(45.dp))
            if (onRetry == null) CircularProgressIndicator(Modifier.size(32.dp), color = Pink, strokeWidth = 3.dp)
            else TextButton(onClick = onRetry) { Text("Try again", color = StudioColors.primary) }
            Spacer(Modifier.height(14.dp))
            Text(message, color = Muted, fontSize = 13.sp)
        }
        Text("YOUR CREATIVE STUDIO", color = Muted, letterSpacing = 3.sp, fontSize = 10.sp,
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
