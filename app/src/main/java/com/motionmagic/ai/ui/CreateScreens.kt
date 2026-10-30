package com.motionmagic.ai.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmagic.ai.MotionViewModel
import com.motionmagic.ai.Screen
import com.motionmagic.ai.data.*

val ReferenceGradient = Brush.horizontalGradient(listOf(Color(0xFFFF964A), Color(0xFFF34CBC), Color(0xFF793EFF)))
private val RefField = Color(0xFF1D1E22)
private val RefTile = Color(0xFF261B3B)
private val RefSelected = Color(0xFF6936B4)
private val RefMuted = Color(0xFF8A8E9B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: MotionViewModel, onPickPhoto: () -> Unit) {
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
    val ready = if(mode == CreationMode.IMAGE_TO_VIDEO) vm.photo != null else draft.prompt.isNotBlank()
    LaunchedEffect(Unit) { vm.refreshCloudCatalog() }
    val create: () -> Unit = {
        focus.clearFocus()
        vm.prepareCloudGeneration()
    }
    Column(Modifier.fillMaxSize().imePadding().background(Ink)) {
        Row(Modifier.fillMaxWidth().height(40.dp).background(Brush.horizontalGradient(listOf(Color(0xFF160926), Color(0xFF30084A)))).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            MagicLogo(Modifier.size(25.dp))
            Text("Unleash your creativity", Modifier.weight(1f), textAlign = TextAlign.Center, color = Color(0xFFDFD3E9), fontSize = 14.sp)
            Spacer(Modifier.width(25.dp))
        }
        Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.Rounded.Close, "Back", Modifier.size(25.dp)) }
            Row(Modifier.weight(1f).clickable { modes = true }.testTag("creation-mode-selector"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(mode.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = RefMuted)
            }
            IconButton(onClick = { gallery = true }) { Icon(Icons.Rounded.MenuBook, "Templates", Modifier.size(25.dp)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).padding(top = 3.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if(mode != CreationMode.TEXT_TO_VIDEO) ReferencePhotoInput(vm, still, onPickPhoto)
            Surface(color = RefField, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().padding(5.dp)) {
                    TextField(draft.prompt, { update(draft.copy(prompt = it.take(501))) },
                        placeholder = { Text(if(still) "Describe the scene and the image you imagine. Add details about the style and lighting."
                            else if(mode == CreationMode.IMAGE_TO_VIDEO) "Optional: describe how the photo should move, including camera movement, lighting and mood."
                            else "Describe the scene you imagine. Include style, lighting and camera movement.",
                            color = Color(0xFF717888), fontSize = 13.sp, lineHeight = 21.sp) },
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("creation-prompt"),
                        isError = draft.prompt.length > 500,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${draft.prompt.length}/500", color = RefMuted, fontSize = 12.sp)
                        IconButton(onClick = { update(draft.copy(prompt = "")) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Backspace, "Clear prompt", Modifier.size(17.dp), tint = RefMuted)
                        }
                        Spacer(Modifier.weight(1f))
                        SmallPromptAction(Icons.Rounded.Lightbulb, "Prompt idea") { update(draft.copy(prompt = templates.first().promptFor(mode))) }
                        Spacer(Modifier.width(6.dp))
                        SmallPromptAction(Icons.Rounded.Mic, "Dictate prompt") {
                            runCatching { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
                                .onFailure { vm.notice("Voice input unavailable", "Install or enable a speech recognition service on this device, or type your prompt.") }
                        }
                    }
                }
            }
        }
        Text(vm.cloudCatalogStatus, color = RefMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
        TextButton(onClick = vm::resumeCloudGeneration, modifier = Modifier.fillMaxWidth()) { Text("Check cloud generation", fontSize = 12.sp) }
        ReferenceComposerBar(draft, still, modelName, ready, false, { settings = true }, update, create)
    }
    if(modes) CreationModeSheet(mode, { modes = false }) { chosen ->
        vm.switchCreationMode(chosen); modes = false
    }
    if(settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = RefField, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth()) {
            ReferenceSettings(draft, still, video, image, vm.currentPlan, update, vm::selectVideoModel, { settings = false },
                vm.cloudRates, mode == CreationMode.IMAGE_TO_VIDEO && video.id != "gemini-omni-1.1-flash")
            ReferenceComposerBar(draft, still, modelName, ready, true, { settings = false }, update, { settings = false; create() })
        }
    }
    if(gallery) ModalBottomSheet(onDismissRequest = { gallery = false }, containerColor = Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Templates", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text("Choose a look to start with", color = RefMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 490.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(templates.chunked(2)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { item -> Box(Modifier.weight(1f)) { TemplatePosterCard(item, Modifier.fillMaxWidth().height(190.dp)) { gallery = false; selectedTemplate = item } } }
                    }
                }
            }
        }
    }
    selectedTemplate?.let { item -> TemplatePreviewSheet(item, mode, { selectedTemplate = null }) {
        vm.useTemplate(item.art, item.name, item.promptFor(mode), mode, item.aspect, item.duration); selectedTemplate = null
    } }
}

@Composable
private fun SmallPromptAction(icon: ImageVector, description: String, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(34.dp).background(Color(0xFF2B2F38), RoundedCornerShape(10.dp))) {
        Icon(icon, description, Modifier.size(19.dp), tint = Color(0xFFC8CDD8))
    }
}

@Composable
private fun ReferencePhotoInput(vm: MotionViewModel, still: Boolean, onPick: () -> Unit) {
    val photo = vm.photo
    val shape = RoundedCornerShape(17.dp)
    Box(Modifier.fillMaxWidth().height(126.dp).clip(shape).background(Color(0xFF101115))
        .drawBehind {
            drawRoundRect(Color(0xFF555966), cornerRadius = CornerRadius(17.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
        }.clickable(onClick = onPick).testTag("upload-photo"), contentAlignment = Alignment.Center) {
        if(photo == null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(26.dp), tint = RefMuted)
            Text(if(still) "Add reference image" else "Add an Image", color = RefMuted, fontSize = 14.sp)
        } else {
            Image(photo.asImageBitmap(), "Your selected photo", Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
            IconButton(onClick = vm::removePhoto, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(29.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(8.dp))) {
                Icon(Icons.Rounded.Close, "Remove photo", Modifier.size(17.dp))
            }
            Text("Replace", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(6.dp)).padding(7.dp))
        }
    }
}

@Composable
private fun ReferenceComposerBar(draft: CreationDraft, still: Boolean, model: String, ready: Boolean, expanded: Boolean, settings: () -> Unit, update: (CreationDraft) -> Unit, create: () -> Unit) {
    Surface(color = Color(0xFF17181D), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if(!expanded) Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ComposerPill(model.replace("Gemini ", "").replace("PixVerse ", ""), Modifier.testTag("model-selector"), settings)
                    ComposerPill(if(still) draft.imageQuality.uppercase() else draft.resolution.uppercase(), onClick = settings)
                    if(!still) ComposerPill("${draft.duration}s", onClick = settings)
                    if(still) ComposerPill(draft.aspect, onClick = settings)
                    else ComposerPill(if(draft.generatedAudio) "Sound ON" else "Sound OFF", onClick = settings)
                }
                IconButton(onClick = settings, modifier = Modifier.size(34.dp).testTag("settings-toggle")) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Settings", tint = RefMuted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("1 creation", color = RefMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                Button(onClick = create, enabled = ready, shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
                    modifier = Modifier.weight(1f).height(42.dp).background(ReferenceGradient, RoundedCornerShape(13.dp)).testTag("create-button"),
                    contentPadding = PaddingValues(horizontal = 15.dp)) {
                    Spacer(Modifier.weight(1f))
                    Text("Create", color = if(ready) Color.White else Color.White.copy(alpha = .4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp), tint = Color(0xFFFFD272).copy(alpha = if(ready) 1f else .4f))
                    Text(" —", color = Color.White.copy(alpha = if(ready) .8f else .3f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ComposerPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color(0xFF1C1D22), shape = RoundedCornerShape(11.dp), modifier = modifier) {
        Text(text, fontSize = 10.sp, color = Color(0xFFCACBD1), modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp), maxLines = 1)
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
private fun ReferenceSettings(draft: CreationDraft, still: Boolean, video: VideoModelOption, image: PremiumWorkflowOption, currentPlan: PlanTier, update: (CreationDraft) -> Unit, selectVideo: (VideoModelOption) -> Unit, close: () -> Unit,
    rates: List<com.motionmagic.ai.cloud.CloudRate>, sourceRatio: Boolean) {
    val modelRates = rates.filter { it.modelId == if(still) image.id else video.id }
    val activeRate = modelRates.firstOrNull { it.matches(draft, still) }
    Column(Modifier.fillMaxWidth().heightIn(max = 465.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Model", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = close, modifier = Modifier.size(25.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Close settings", tint = RefMuted) }
        }
        Text(if(activeRate != null) "Available · price shown before generation" else "This selection is not enabled on the server yet", color = RefMuted, fontSize = 11.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if(still) items(ImageModelCatalog.models, key = { it.id }) { option ->
                ModelSquare(option.title, option.id == image.id, !currentPlan.includes(option.requiredPlan), "model-option-${option.title}") {
                    update(draft.copy(imageModelId = option.id, imageQuality = draft.imageQuality.takeIf { it in option.resolutions } ?: option.resolutions.first()))
                }
            } else items(VideoModelCatalog.models, key = { it.id }) { option ->
                ModelSquare(option.title, option.id == video.id, !currentPlan.includes(option.requiredPlan), "model-option-${option.title}") { selectVideo(option) }
            }
        }
        Text("Resolution", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (if(still) image.resolutions else video.resolutions.toList()).forEach { resolution ->
                val selected = resolution == if(still) draft.imageQuality else draft.resolution
                Surface(onClick = { update(if(still) draft.copy(imageQuality = resolution) else draft.copy(resolution = resolution)) },
                    color = if(selected) RefSelected else RefTile, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).testTag("resolution-$resolution")) {
                    Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) { Text(resolution.uppercase(), fontSize = 12.sp, color = if(selected) Color.White else RefMuted) }
                }
            }
        }
        Text(if(sourceRatio) "Aspect ratio follows your photo" else "Aspect Ratio", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if(!sourceRatio) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items(activeRate?.ratios ?: if(still) ImageModelCatalog.aspects else if(video.id == "gemini-omni-1.1-flash") listOf("16:9", "9:16") else listOf("16:9", "1:1", "9:16")) { ratio ->
                RatioSquare(ratio, ratio == draft.aspect) { update(draft.copy(aspect = ratio)) }
            }
        }
        }
        if(!still) {
            Text("Duration", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                video.durations.sorted().forEach { seconds ->
                    Surface(onClick = { update(draft.copy(duration = seconds)) }, color = if(draft.duration == seconds) RefSelected else RefTile,
                        shape = RoundedCornerShape(11.dp), modifier = Modifier.weight(1f).testTag("duration-$seconds")) {
                        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text("${seconds}s", fontSize = 12.sp) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Generate Audio", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(draft.generatedAudio, { update(draft.copy(generatedAudio = it)) }, enabled = video.supportsGeneratedAudio)
            }
        }
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun ModelSquare(title: String, selected: Boolean, paid: Boolean, tag: String, choose: () -> Unit) {
    val tone = studioTone(title)
    Column(Modifier.width(69.dp).clickable(onClick = choose).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = Color.Transparent, shape = RoundedCornerShape(15.dp), border = BorderStroke(if(selected) 2.dp else 1.dp, if(selected) Color.White else tone.accent.copy(alpha = .3f))) {
        Box(Modifier.size(66.dp).background(tone.gradient), contentAlignment = Alignment.Center) {
            Text(when { title.startsWith("Nano") -> "G"; title.startsWith("Gemini") -> "✦"; title.startsWith("Kling") -> "K"; title.startsWith("Seed") -> "|||"; title.startsWith("Qwen") -> "✧"; else -> "P" },
                fontSize = 34.sp, fontWeight = FontWeight.Bold)
            if(paid) Icon(Icons.Rounded.Star, null, tint = Color(0xFFDF78ED), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp))
        }
        }
        Text(title, color = Color(0xFFE5DCED), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 13.sp,
            textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 30.dp).padding(top = 4.dp))
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
