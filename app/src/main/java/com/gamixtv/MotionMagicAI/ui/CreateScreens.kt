package com.gamixtv.MotionMagicAI.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.data.*
import kotlin.math.roundToInt

val ReferenceGradient = Brush.horizontalGradient(listOf(Color(0xFFFF964A), Color(0xFFF34CBC), Color(0xFF793EFF)))
private val RefField = Color(0xFF151022)
private val RefTile = Color(0xFF241A38)
private val RefSelected = Color(0xFF6936B4)
private val RefMuted = Color(0xFFB6ACC9)
private val CreatorGradient = Brush.horizontalGradient(listOf(Color(0xFFD73BFF), Color(0xFF894BFF), Color(0xFF268DFF)))
private val CreatorBorder = Color(0xFF5D377F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: MotionViewModel, onPickPhoto: () -> Unit, onPickVideo: () -> Unit) {
    val mode = when(vm.screen) {
        Screen.CREATE_IMAGE -> CreationMode.CREATE_IMAGE
        Screen.TEXT, Screen.REELS -> CreationMode.TEXT_TO_VIDEO
        else -> CreationMode.IMAGE_TO_VIDEO
    }
    val still = mode == CreationMode.CREATE_IMAGE
    val draft = if(still) vm.imageDraft else vm.draft
    val video = vm.selectedVideoModel
    val image = ImageModelCatalog.find(draft.imageModelId)
    val modelName = if(still) image.title else video.title
    val selectedRate = vm.cloudRates.firstOrNull { it.matches(draft, still) }
    val estimatedCredits = selectedRate?.priceFor(draft, still)
    val audioAvailable = vm.cloudRates.any { it.kind == "video" && it.modelId == draft.modelId && it.size == draft.resolution && it.audio == true }
    val update: (CreationDraft) -> Unit = { if(still) vm.updateImageDraft(it) else vm.updateDraft(it) }
    val focus = LocalFocusManager.current
    var modes by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var gallery by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<Template?>(null) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode == Activity.RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if(!words.isNullOrBlank()) update(draft.copy(prompt = (draft.prompt.trimEnd() + " " + words).trim().take(500)))
        }
    }
    LaunchedEffect(modes, settings, gallery, selectedTemplate) {
        if(modes || settings || gallery || selectedTemplate != null) focus.clearFocus()
    }
    val hasInput = if(mode == CreationMode.IMAGE_TO_VIDEO) vm.photo != null || vm.referenceVideoPath != null else draft.prompt.isNotBlank()
    val ready = hasInput && estimatedCredits != null
    val create: () -> Unit = {
        focus.clearFocus()
        vm.prepareCloudGeneration()
    }
    LaunchedEffect(Unit) { vm.refreshCloudCatalog() }
    Column(Modifier.fillMaxSize().imePadding().background(Ink)) {
        Row(Modifier.fillMaxWidth().height(74.dp).background(Brush.horizontalGradient(listOf(Color(0xFF221035), Color(0xFF101428)))).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.Rounded.Close, "Back", Modifier.size(25.dp)) }
            Row(Modifier.weight(1f).clickable { modes = true }.testTag("creation-mode-selector"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("MOTIONMAGIC STUDIO", color = Color(0xFFDCA5FF), fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                    Text(mode.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
                }
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = RefMuted)
            }
            IconButton(onClick = { gallery = true }) { Icon(Icons.Rounded.AutoAwesomeMotion, "Templates", Modifier.size(24.dp), tint = Color(0xFFF0A9FF)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if(mode != CreationMode.TEXT_TO_VIDEO) {
                ReferencePhotoInput(vm, still, onPickPhoto, onPickVideo)
            }
            Surface(color = Color.Transparent, shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, CreatorBorder), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF251333), Color(0xFF141429)))).padding(12.dp)) {
                    Row(Modifier.padding(horizontal = 5.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = Color(0xFFECA7FF))
                        Text("Your prompt", Modifier.weight(1f).padding(start = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("${draft.prompt.length}/500", color = if(draft.prompt.length > 500) Color(0xFFFF8190) else RefMuted, fontSize = 11.sp)
                    }
                    TextField(draft.prompt, { update(draft.copy(prompt = it.take(501))) },
                        placeholder = { Text(if(still) "Imagine the image. Describe your subject, style and light…"
                            else if(mode == CreationMode.IMAGE_TO_VIDEO) "Bring your photo to life. Describe the movement, mood and camera…"
                            else "A cinematic scene at golden hour, a slow camera move, soft light…",
                            color = RefMuted, fontSize = 14.sp, lineHeight = 23.sp) },
                        modifier = Modifier.heightIn(min = 157.dp, max = 230.dp).fillMaxWidth().testTag("creation-prompt"),
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 24.sp),
                        isError = draft.prompt.length > 500,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallPromptAction(Icons.Rounded.Lightbulb, "Prompt idea") { update(draft.copy(prompt = templates.first().promptFor(mode))) }
                        Spacer(Modifier.width(8.dp))
                        SmallPromptAction(Icons.Rounded.Mic, "Dictate prompt") {
                            runCatching { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
                                .onFailure { vm.notice("Voice input unavailable", "Install or enable a speech recognition service on this device, or type your prompt.") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { update(draft.copy(prompt = "")) }) { Text("Clear", color = RefMuted, fontSize = 12.sp) }
                    }
                }
            }
            if(selectedRate == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if(vm.cloudRates.isEmpty()) vm.cloudCatalogStatus else "Choose an available model and setting below.", color = RefMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if(vm.cloudRates.isEmpty() && !vm.cloudCatalogStatus.startsWith("Checking"))
                    TextButton(onClick = vm::refreshCloudCatalog) { Text("Retry", fontSize = 12.sp) }
            }
        }
        ReferenceComposerBar(draft, still, modelName, ready, estimatedCredits, false, audioAvailable, { settings = true }, update, create)
    }
    if(modes) CreationModeSheet(mode, { modes = false }) { chosen ->
        vm.switchCreationMode(chosen); modes = false
    }
    if(settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = RefField, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * .87f).dp
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()) {
            ReferenceSettings(draft, still, video, image, update, vm::selectVideoModel, { settings = false },
                vm.cloudRates, mode == CreationMode.IMAGE_TO_VIDEO && vm.referenceVideoPath == null, Modifier.weight(1f, fill = false))
            ReferenceComposerBar(draft, still, modelName, ready, estimatedCredits, true, audioAvailable, { settings = false }, update, { settings = false; create() })
        }
    }
    if(gallery) ModalBottomSheet(onDismissRequest = { gallery = false }, containerColor = Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val galleryTemplates = vm.showcaseTemplates.filter { it.mode == mode }.ifEmpty { templates }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Templates", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text("Choose a look to start with", color = RefMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 490.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(galleryTemplates.chunked(2)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { item -> Box(Modifier.weight(1f)) { TemplatePosterCard(item, Modifier.fillMaxWidth().height(190.dp)) { gallery = false; selectedTemplate = item } } }
                    }
                }
            }
        }
    }
    selectedTemplate?.let { item ->
        val templateMode = if(item.remoteId == null) mode else item.mode
        TemplatePreviewSheet(item, templateMode, { selectedTemplate = null }) {
        vm.useTemplate(item.art, item.name, item.promptFor(templateMode), templateMode, item.aspect, item.duration,
            item.modelId, item.resolution, item.generatedAudio); selectedTemplate = null
    } }
}

@Composable
private fun SmallPromptAction(icon: ImageVector, description: String, action: () -> Unit) {
    Surface(onClick = action, color = Color(0xFF39234E), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF67417F))) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, description, Modifier.size(17.dp), tint = Color(0xFFF0B5FF))
            Text(if(description == "Prompt idea") "Inspire" else "Dictate", color = Color(0xFFF1DFFF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ReferencePhotoInput(vm: MotionViewModel, still: Boolean, onPick: () -> Unit, onPickVideo: () -> Unit) {
    val photo = vm.photo
    val reference = if(still) null else vm.referenceVideoPath
    val referenceAvailable = vm.cloudRates.any { it.modelId == "kling-o3-reference" }
    val shape = RoundedCornerShape(23.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(Color(0xFF31133F), Color(0xFF191933), Color(0xFF112841))))
        .drawBehind { drawRoundRect(CreatorBorder, cornerRadius = CornerRadius(23.dp.toPx()), style = Stroke(1.dp.toPx())) }) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if(reference != null) Icons.Rounded.VideoLibrary else Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp), tint = Color(0xFFF2AEFF))
        Text(if(reference != null) "Story reference" else "Reference image", Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(if(still) "Optional" else "Your starting frame", color = RefMuted, fontSize = 10.sp)
    }
    Box(Modifier.fillMaxWidth().height(if(photo != null || reference != null) 148.dp else 110.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(15.dp)).background(Color(0x44110B22))
        .drawBehind {
            drawRoundRect(Color(0xFF9B58C1), cornerRadius = CornerRadius(15.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
        }.clickable(onClick = if(reference == null) onPick else onPickVideo).testTag("upload-photo"), contentAlignment = Alignment.Center) {
        if(reference != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectThumbnail(reference, true, 88.dp)
                Column { Text("Reference video selected", color = Color.White, fontWeight = FontWeight.Bold); Text("Use it to create the next story scene", color = RefMuted, fontSize = 11.sp) }
            }
            IconButton(onClick = vm::removeReferenceVideo, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(29.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(8.dp))) {
                Icon(Icons.Rounded.Close, "Remove reference video", Modifier.size(17.dp))
            }
        } else if(photo == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(47.dp).background(CreatorGradient, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, Modifier.size(26.dp), tint = Color.White)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Add your photo", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if(still) "Guide the look of your image" else "Turn a still moment into a scene", color = RefMuted, fontSize = 11.sp)
            }
        } else {
            Image(photo.asImageBitmap(), "Your selected photo", Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
            IconButton(onClick = vm::removePhoto, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(29.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(8.dp))) {
                Icon(Icons.Rounded.Close, "Remove photo", Modifier.size(17.dp))
            }
            Text("Replace", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(6.dp)).padding(7.dp))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPick) { Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Photo", fontSize = 11.sp) }
        if(!still) TextButton(onClick = onPickVideo, enabled = referenceAvailable) {
            Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Reference video", fontSize = 11.sp)
        }
    }
    }
}

@Composable
private fun ReferenceComposerBar(draft: CreationDraft, still: Boolean, model: String, ready: Boolean, estimatedCredits: Long?, expanded: Boolean, audioAvailable: Boolean, settings: () -> Unit, update: (CreationDraft) -> Unit, create: () -> Unit) {
    Surface(color = Color(0xFF160F24), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), border = BorderStroke(1.dp, Color(0xFF432957))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if(!expanded) Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ComposerPill(model.replace("Gemini ", "").replace("PixVerse ", ""), Modifier.testTag("model-selector"), settings)
                    ComposerPill(if(still) draft.imageQuality.uppercase() else draft.resolution.uppercase(), onClick = settings)
                    if(!still) ComposerPill("${draft.duration}s", onClick = settings)
                    if(still) ComposerPill(draft.aspect, onClick = settings)
                }
                IconButton(onClick = settings, modifier = Modifier.size(34.dp).testTag("settings-toggle")) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Settings", tint = RefMuted)
                }
            }
            if(!still && !expanded) AudioChoice(draft, audioAvailable, compact = true) { update(draft.copy(generatedAudio = it)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = create, enabled = ready, shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
                    modifier = Modifier.weight(1f).height(53.dp).background(CreatorGradient, RoundedCornerShape(15.dp)).testTag("create-button"),
                    contentPadding = PaddingValues(horizontal = 15.dp)) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = if(ready) 1f else .4f))
                    Spacer(Modifier.width(8.dp))
                    Text("Create", color = if(ready) Color.White else Color.White.copy(alpha = .4f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp), tint = Color(0xFFFFD272).copy(alpha = if(ready) 1f else .4f))
                    Text(if(estimatedCredits == null) " Unavailable" else " $estimatedCredits credits", color = Color.White.copy(alpha = if(ready) .95f else .4f), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ComposerPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color(0xFF2B1A41), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Color(0xFF603C7A)), modifier = modifier) {
        Text(text, fontSize = 11.sp, color = Color(0xFFEEDAFF), modifier = Modifier.padding(horizontal = 11.dp, vertical = 11.dp), maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreationModeSheet(mode: CreationMode, dismiss: () -> Unit, choose: (CreationMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 25.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Creation Mode", Modifier.fillMaxWidth().padding(vertical = 9.dp), textAlign = TextAlign.Center, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ModeTile("Create Image", Icons.Rounded.Image, mode == CreationMode.CREATE_IMAGE, Modifier.weight(1f)) { choose(CreationMode.CREATE_IMAGE) }
                ModeTile("Image to Video", Icons.Rounded.SmartDisplay, mode == CreationMode.IMAGE_TO_VIDEO, Modifier.weight(1f)) { choose(CreationMode.IMAGE_TO_VIDEO) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ModeTile("Text to Video", Icons.Rounded.Title, mode == CreationMode.TEXT_TO_VIDEO, Modifier.weight(1f)) { choose(CreationMode.TEXT_TO_VIDEO) }
                Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeTile(title: String, icon: ImageVector, selected: Boolean, modifier: Modifier, action: () -> Unit) {
    val tone = studioTone(title)
    Surface(onClick = action, color = Color.Transparent, shape = RoundedCornerShape(18.dp), border = BorderStroke(if(selected) 2.dp else 1.dp, if(selected) Color.White else tone.accent.copy(alpha = .3f)), modifier = modifier.height(112.dp).testTag("mode-$title")) {
        Box(Modifier.fillMaxSize().background(tone.gradient)) {
            if(selected) Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.align(Alignment.TopEnd).padding(9.dp).size(17.dp), tint = Color.White)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, null, Modifier.size(30.dp), tint = Color.White)
                Spacer(Modifier.height(11.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, letterSpacing = (-.2).sp)
            }
        }
    }
}

@Composable
private fun ReferenceSettings(draft: CreationDraft, still: Boolean, video: VideoModelOption, image: PremiumWorkflowOption, update: (CreationDraft) -> Unit, selectVideo: (VideoModelOption) -> Unit, close: () -> Unit,
    rates: List<com.gamixtv.MotionMagicAI.cloud.CloudRate>, sourceRatio: Boolean, modifier: Modifier = Modifier) {
    val modelRates = rates.filter { it.modelId == if(still) image.id else video.id }
    val activeRate = modelRates.firstOrNull { it.matches(draft, still) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(23.dp), tint = Color(0xFFE1A1FF))
            Text("Studio settings", fontWeight = FontWeight.SemiBold, fontSize = 21.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
            IconButton(onClick = close, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Close settings", tint = RefMuted) }
        }
        Text(when {
            activeRate == null -> "This model or setting is unavailable on the server. Choose an available option."
            activeRate.priceFor(draft, still) == null -> "Choose a supported duration and aspect ratio"
            else -> "${activeRate.priceFor(draft, still)} credits · review before creating"
        }, color = Color(0xFFE6BBFF), fontSize = 12.sp)
        Text("Model", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if(still) items(ImageModelCatalog.models, key = { it.id }) { option ->
                val available = rates.any { it.kind == "image" && it.modelId == option.id }
                ModelSquare(option.title, option.id == image.id, available, "model-option-${option.title}") {
                    val options = rates.filter { it.kind == "image" && it.modelId == option.id }
                    val chosen = options.firstOrNull { it.size == draft.imageQuality } ?: options.first()
                    update(draft.copy(imageModelId = option.id, imageQuality = chosen.size,
                        aspect = draft.aspect.takeIf { it in chosen.ratios } ?: chosen.ratios.first()))
                }
            } else items(VideoModelCatalog.models, key = { it.id }) { option ->
                val available = rates.any { it.kind == "video" && it.modelId == option.id }
                ModelSquare(option.title, option.id == video.id, available, "model-option-${option.title}") { selectVideo(option) }
            }
        }
        Text("Resolution", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (if(still) image.resolutions else video.resolutions.toList()).forEach { resolution ->
                val selected = resolution == if(still) draft.imageQuality else draft.resolution
                val available = rates.any { it.kind == (if(still) "image" else "video") &&
                    it.modelId == (if(still) image.id else video.id) && it.size == resolution }
                Surface(onClick = {
                    val choices = modelRates.filter { it.size == resolution }
                    val chosen = choices.firstOrNull { still || it.audio == draft.generatedAudio } ?: choices.first()
                    update(if(still) draft.copy(imageQuality = resolution) else draft.copy(resolution = resolution,
                        generatedAudio = chosen.audio ?: false, duration = draft.duration.takeIf { it in chosen.durations } ?: chosen.durations.first()))
                },
                    enabled = available, color = if(selected && available) RefSelected else RefTile,
                    border = BorderStroke(1.dp, if(selected && available) Color(0xFFC877FF) else Color(0xFF513460)),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).testTag("resolution-$resolution")) {
                    Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if(available) resolution.uppercase() else "${resolution.uppercase()} · Unavailable", fontSize = 11.sp,
                                color = if(selected && available) Color.White else RefMuted)
                        }
                    }
                }
            }
        }
        if(!still) {
            val audioAvailable = modelRates.any { it.size == draft.resolution && it.audio == true }
            AudioChoice(draft, audioAvailable) { sound ->
                val variant = modelRates.firstOrNull { it.size == draft.resolution && it.audio == sound }
                update(draft.copy(generatedAudio = sound, duration = draft.duration.takeIf { variant == null || it in variant.durations }
                    ?: checkNotNull(variant).durations.first()))
            }
            ModelDurationPicker(activeRate?.durations.orEmpty(), draft.duration) { update(draft.copy(duration = it)) }
        }
        Text(if(sourceRatio) "Aspect ratio follows your photo" else "Aspect Ratio", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if(!sourceRatio) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items(activeRate?.ratios ?: if(still) ImageModelCatalog.aspects else listOf("16:9", "1:1", "9:16")) { ratio ->
                RatioSquare(ratio, ratio == draft.aspect) { update(draft.copy(aspect = ratio)) }
            }
        }
        }
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun AudioChoice(draft: CreationDraft, available: Boolean, compact: Boolean = false, change: (Boolean) -> Unit) {
    Surface(color = Color.Transparent, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Color(0xFF78449C))) {
        Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF381B50), Color(0xFF18284A))))
            .padding(start = 12.dp, end = 6.dp, top = if(compact) 0.dp else 6.dp, bottom = if(compact) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(if(compact) 22.dp else 27.dp), tint = Color(0xFFF1ACFF))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if(draft.modelId == "kling-o3-reference") "Keep reference audio" else "Generate Audio", fontSize = if(compact) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold)
                if(!compact) Text(if(available) "Create sound with your scene" else "Unavailable with this model and resolution", fontSize = 11.sp, color = RefMuted)
            }
            Text(if(draft.generatedAudio) "ON" else "OFF", fontSize = 10.sp, color = Color(0xFFECC6FF), fontWeight = FontWeight.Bold)
            Switch(draft.generatedAudio, change, enabled = draft.generatedAudio || available,
                modifier = Modifier.testTag("generated-audio-toggle"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF9A45E8),
                    uncheckedThumbColor = Color(0xFFD7C5E8), uncheckedTrackColor = Color(0xFF332A49), uncheckedBorderColor = Color(0xFF8F6DAA)))
        }
    }
}

@Composable
private fun ModelDurationPicker(options: List<Int>, selected: Int, choose: (Int) -> Unit) {
    val durations = options.filter { it > 0 }.distinct().sorted()
    val supported = if(durations.isNotEmpty() && durations.size == durations.last() - durations.first() + 1)
        "${durations.first()}–${durations.last()}" else durations.joinToString(", ")
    var entering by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Duration", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Surface(onClick = { entering = true }, enabled = durations.isNotEmpty(), color = RefTile, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, CreatorBorder)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selected}s", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.Edit, "Type duration", Modifier.size(16.dp), tint = Color(0xFFE5A7FF))
                }
            }
        }
        if(durations.size > 1) Slider(value = durations.indexOf(selected).coerceAtLeast(0).toFloat(),
            onValueChange = { value -> durations[value.roundToInt().coerceIn(durations.indices)].takeIf { it != selected }?.let(choose) },
            valueRange = 0f..durations.lastIndex.toFloat(), steps = (durations.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = Color(0xFFF0B4FF), activeTrackColor = Color(0xFFBD55F4), inactiveTrackColor = Color(0xFF3B284D)),
            modifier = Modifier.fillMaxWidth().testTag("duration-slider"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(durations) { seconds ->
                Surface(onClick = { choose(seconds) }, color = if(selected == seconds) RefSelected else RefTile,
                    shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if(selected == seconds) Color(0xFFC877FF) else CreatorBorder),
                    modifier = Modifier.testTag("duration-$seconds")) {
                    Text("${seconds}s", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), fontSize = 13.sp)
                }
            }
        }
        Text(if(durations.isEmpty()) "Choose an available model and audio setting to see durations."
            else "Swipe to adjust, or tap the number to type. Supported: ${supported}s.", color = RefMuted, fontSize = 11.sp)
    }
    if(entering) {
        var text by remember(selected) { mutableStateOf(selected.toString()) }
        val number = text.toIntOrNull()
        val valid = number != null && number in durations
        AlertDialog(onDismissRequest = { entering = false }, containerColor = RefField, shape = RoundedCornerShape(24.dp),
            title = { Text("Video duration") },
            text = { OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, label = { Text("Seconds") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = !valid,
                supportingText = { Text("This model supports $supported seconds.") }, modifier = Modifier.testTag("duration-input")) },
            confirmButton = { TextButton(onClick = { choose(checkNotNull(number)); entering = false }, enabled = valid) { Text("Apply", color = Color(0xFFE8A6FF)) } },
            dismissButton = { TextButton(onClick = { entering = false }) { Text("Cancel", color = RefMuted) } })
    }
}

@Composable
private fun ModelSquare(title: String, selected: Boolean, available: Boolean, tag: String, choose: () -> Unit) {
    val tone = studioTone(title)
    Column(Modifier.width(85.dp).clickable(enabled = available, onClick = choose).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = Color.Transparent, shape = RoundedCornerShape(19.dp), border = BorderStroke(if(selected) 2.dp else 1.dp, if(selected) Color(0xFFE2A1FF) else tone.accent.copy(alpha = .4f))) {
        Box(Modifier.size(78.dp).background(tone.gradient), contentAlignment = Alignment.Center) {
            Text(when { title.startsWith("GPT") -> "GPT"; title.startsWith("Kling") -> "K"; title.startsWith("Seed") -> "S"; title.startsWith("Qwen") -> "Q"; else -> "P" },
                fontSize = if(title.startsWith("GPT")) 23.sp else 34.sp, fontWeight = FontWeight.Bold)
            if(!available) Icon(Icons.Rounded.Block, "Unavailable", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(13.dp))
        }
        }
        Text(title, color = Color(0xFFE5DCED), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 13.sp,
            textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 30.dp).padding(top = 4.dp))
        if(!available) Text("Unavailable", color = RefMuted, fontSize = 9.sp)
    }
}

@Composable
private fun RatioSquare(ratio: String, selected: Boolean, choose: () -> Unit) {
    val values = ratio.split(":").map(String::toFloat)
    val factor = values[0] / values[1]
    Surface(onClick = choose, color = if(selected) RefSelected else Color.Transparent, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.width(55.dp).height(67.dp).testTag("ratio-$ratio")) {
        Column(Modifier.fillMaxSize().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(if(factor >= 1) 22.dp else (22 * factor).dp, if(factor <= 1) 22.dp else (22 / factor).dp)
                .drawBehind { drawRoundRect(Color.White, cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx())) })
            Text(ratio, fontSize = 9.sp, color = Color(0xFFC2C5CF))
        }
    }
}

@Composable
fun VoiceScreen(vm: MotionViewModel) {
    var voice by rememberSaveable { mutableStateOf("Natural") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        OutlinedTextField(vm.voiceText, { vm.updateVoiceText(it.take(501)) }, Modifier.fillMaxWidth().heightIn(min = 165.dp),
            placeholder = { Text("Enter your text here…\nExample: Welcome to my channel!", color = Muted) }, shape = PanelShape,
            minLines = 4, isError = vm.voiceText.length > 500,
            supportingText = { Text("${vm.voiceText.length}/500", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End) })
        Text("Select Voice", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Surface(shape = PanelShape, color = Panel) {
            Column {
                listOf("Natural" to "Clear and balanced", "Warm" to "Lower pitch, relaxed pace", "Bright" to "Lighter pitch, lively pace", "Narrator" to "Steady storytelling", "Calm" to "Slow and gentle").forEach { (name, subtitle) ->
                    Surface(onClick = { voice = name }, color = Color.Transparent, shape = RoundedCornerShape(12.dp),
                        border = if(voice == name) BorderStroke(1.dp, Violet) else null) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(25.dp), tint = if(voice == name) Violet else Muted)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(name, fontSize = 14.sp); Text(subtitle, fontSize = 11.sp, color = Muted) }
                            if(voice == name) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = Violet)
                        }
                    }
                }
            }
        }
        TestNotice("Voice samples use your Android speech engine and installed voices. This is synthesized speech, not a human recording or a live AI model.")
        GradientButton("Generate Voice Sample", { vm.generateVoice(voice) }, icon = Icons.Rounded.MusicNote)
        Text("WAV audio · Saved to My Projects", color = Muted, fontSize = 12.sp)
    }
}
