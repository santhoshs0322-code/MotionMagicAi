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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.data.*
import kotlin.math.roundToInt

val ReferenceGradient = MagicGradient
private val RefField: Color @Composable get() = StudioColors.surface
private val RefTile: Color @Composable get() = StudioColors.field
private val RefMuted: Color @Composable get() = StudioColors.muted
private val CreatorAccent: Color @Composable get() = StudioColors.primary
private val CreatorBorder: Color @Composable get() = StudioColors.border
private val CreatorTileGradient: Brush @Composable get() = Brush.horizontalGradient(listOf(StudioColors.surface, StudioColors.field))
private val CreatorSelectionGradient: Brush @Composable get() = Brush.horizontalGradient(listOf(StudioColors.selected, StudioColors.selected))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: MotionViewModel, onPickPhoto: () -> Unit, onPickVideo: () -> Unit, refreshCatalog: () -> Unit = vm::refreshCloudCatalog,
    onPickReferencePhotos: (Int?) -> Unit = {}) {
    val mode = when(vm.screen) {
        Screen.CREATE_IMAGE -> CreationMode.CREATE_IMAGE
        Screen.TEXT, Screen.REELS -> CreationMode.TEXT_TO_VIDEO
        else -> CreationMode.IMAGE_TO_VIDEO
    }
    val still = mode == CreationMode.CREATE_IMAGE
    val story = vm.screen == Screen.STORY
    val draft = if(still) vm.imageDraft else vm.draft
    val update: (CreationDraft) -> Unit = { if(still) vm.updateImageDraft(it) else vm.updateDraft(it) }
    val rate = vm.cloudRates.firstOrNull { it.matches(draft, still) }
    val price = if(!still && draft.modelId == "kling-o3-edit" && vm.referenceVideoDurationMs == null) null else rate?.priceFor(draft, still)
    val model = if(still) ImageModelCatalog.find(draft.imageModelId).title else vm.selectedVideoModel.title
    val colors = StudioColors
    val focus = LocalFocusManager.current
    var modes by remember { mutableStateOf(false) }
    var gallery by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<Template?>(null) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode == Activity.RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if(!words.isNullOrBlank()) update(draft.copy(prompt = (draft.prompt.trimEnd() + " " + words).trim().take(CreationValidation.MAX_PROMPT_LENGTH)))
        }
    }
    val hasInput = if(mode == CreationMode.IMAGE_TO_VIDEO) {
        if(VideoModelCatalog.isReference(draft.modelId)) vm.referenceVideoPath != null else vm.photo != null
    } else draft.prompt.isNotBlank() && (!draft.requiresReference || vm.photo != null)
    LaunchedEffect(Unit) { refreshCatalog() }
    Column(Modifier.fillMaxSize().imePadding().background(Ink)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.Rounded.ArrowBackIosNew, "Back", Modifier.size(22.dp)) }
            Row(Modifier.weight(1f).clickable { focus.clearFocus(); modes = true }.testTag("creation-mode-selector"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(if(story) "Continue Story" else mode.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
                Icon(Icons.Rounded.ExpandMore, "Change creation mode", Modifier.size(18.dp), tint = colors.muted)
            }
            IconButton(onClick = { vm.notice("Your creation", "Add a reference or describe your scene, customize the settings, then review the credit price before creating.") }) {
                Icon(Icons.Rounded.Info, "Creation help", Modifier.size(23.dp))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(draft.requiresReference) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your photo is required for this look", Modifier.weight(1f), color = colors.primary, fontSize = 12.sp)
                TextButton(onClick = { update(draft.copy(templateId = null, requiresReference = false)) }) { Text("Clear template", fontSize = 11.sp) }
            }
            if(story) {
                Button(onClick = { vm.go(Screen.LONG_STORY) }, modifier = Modifier.fillMaxWidth().testTag("open-long-story")) {
                    Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Create a 1–5 minute story")
                }
                VideoReferenceInput(draft.modelId, vm.cloudRates.map { it.modelId }.distinct(), vm.referenceVideoPath, vm.referenceVideoDurationMs,
                    vm.referencePhotoPaths, rate?.maxReferenceImages ?: 0, { vm.selectVideoModel(VideoModelCatalog.find(it)) }, onPickVideo,
                    { vm.removeReferenceVideo() }, onPickReferencePhotos, vm::removeReferencePhoto)
                TextButton(onClick = { vm.go(Screen.PROJECTS) }, modifier = Modifier.testTag("story-saved-video")) {
                    Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Use a saved video")
                }
            } else if(mode != CreationMode.TEXT_TO_VIDEO) ReferencePhotoInput(vm, still, onPickPhoto) { focus.clearFocus(); gallery = true }
            StudioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp), tint = colors.primary)
                    Text("Your Prompt", Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${draft.prompt.length}/${CreationValidation.MAX_PROMPT_LENGTH}", color = if(draft.prompt.length > CreationValidation.MAX_PROMPT_LENGTH) colors.error else colors.muted, fontSize = 11.sp)
                }
                OutlinedTextField(draft.prompt, { update(draft.copy(prompt = it.take(CreationValidation.MAX_PROMPT_LENGTH + 1))) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 225.dp).testTag("creation-prompt"),
                    placeholder = { Text(if(still) "Describe your image, style and light…" else if(draft.modelId == "kling-o3-edit") "Replace the subject in @Video1 using @Image1. Keep the camera movement…" else if(vm.referenceVideoPath != null) "Create a new scene from @Video1 using @Image1…"
                        else "Describe the movement, scene and camera…", fontSize = 13.sp, color = colors.muted) },
                    shape = RoundedCornerShape(14.dp), isError = draft.prompt.length > CreationValidation.MAX_PROMPT_LENGTH,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 22.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.field, unfocusedContainerColor = colors.field,
                        focusedBorderColor = colors.primary, unfocusedBorderColor = colors.border))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    PromptAction("Inspire", Icons.Rounded.AutoAwesome) { update(draft.copy(prompt = if(!still && VideoModelCatalog.isReference(draft.modelId)) {
                        if(draft.modelId == "kling-o3-edit") "Edit @Video1. ${if(vm.referencePhotoPaths.isNotEmpty()) "Use @Image1 for the main subject's appearance. " else "Change the setting to a golden sunset. "}Keep the original motion and camera."
                        else "Create a new scene guided by @Video1. ${if(vm.referencePhotoPaths.isNotEmpty()) "Keep the subject's appearance from @Image1. " else "Keep the visual style consistent. "}Use natural movement and cinematic lighting."
                    } else templates.first().promptFor(mode))) }
                    PromptAction("Voice Input", Icons.Rounded.Mic) {
                        try { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
                        catch(_: Exception) { vm.message("Voice input isn't available on this device.") }
                    }
                    PromptAction("Clear", Icons.Rounded.Close) { update(draft.copy(prompt = "")) }
                }
            }
            Surface(onClick = { focus.clearFocus(); vm.openCreationSettings(mode) }, color = colors.surface, shape = PanelShape,
                border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth().testTag("settings-toggle")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Settings, null, Modifier.size(20.dp))
                        Text(if(still) "Image Settings" else if(story) "Story Settings" else "Video Settings", Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Customize", color = colors.primary, fontSize = 11.sp)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = colors.primary)
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.field).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(model, Modifier.weight(1.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        VerticalDivider(Modifier.height(30.dp).padding(horizontal = 8.dp), color = colors.border)
                        Column(Modifier.weight(1f)) {
                            Text(if(still) draft.imageQuality.uppercase() else draft.resolution.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Resolution", color = colors.muted, fontSize = 10.sp)
                        }
                        VerticalDivider(Modifier.height(30.dp).padding(horizontal = 8.dp), color = colors.border)
                        Column(Modifier.weight(.8f)) {
                            Text(if(still) draft.aspect else "${draft.duration}s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(if(still) "Ratio" else "Duration", color = colors.muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            if(!still) AudioChoice(draft, vm.cloudRates.any { it.modelId == draft.modelId && it.size == draft.resolution && it.audio == true },
                vm.referenceVideoHasAudio) { update(draft.copy(generatedAudio = it)) }
            if(rate == null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if(vm.cloudRates.isEmpty()) vm.cloudCatalogStatus else "Choose an available model and settings.", color = colors.muted,
                    fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::refreshCloudCatalog) { Text("Retry") }
            }
            TextButton(onClick = { focus.clearFocus(); gallery = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Rounded.CollectionsBookmark, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Browse templates", fontSize = 12.sp)
            }
        }
        CreationButton(still, price, hasInput && price != null) { focus.clearFocus(); vm.prepareCloudGeneration() }
    }
    if(modes) CreationModeSheet(mode, story, { modes = false }, { vm.go(Screen.STORY); modes = false }) { chosen -> vm.switchCreationMode(chosen); modes = false }
    if(gallery) ObserveTemplateCatalogue(vm)
    LaunchedEffect(vm.showcaseTemplates) {
        selectedTemplate?.remoteId?.let { id -> selectedTemplate = vm.showcaseTemplates.firstOrNull { it.remoteId == id } }
    }
    if(gallery) ModalBottomSheet(onDismissRequest = { gallery = false }, containerColor = colors.surface) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Templates", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(max = 450.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.showcaseTemplates.filter { it.mode == mode }) { item ->
                    Row(Modifier.fillMaxWidth().clickable { gallery = false; selectedTemplate = item }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShowcasePoster(item, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
                        Text(item.name, Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
        }
    }
    selectedTemplate?.let { item ->
        val templateMode = if(item.remoteId == null) mode else item.mode
        TemplatePreviewSheet(item, templateMode, { selectedTemplate = null }) {
            vm.useTemplate(item.art, item.name, item.promptFor(templateMode), templateMode, item.aspect, item.duration, item.modelId, item.resolution, item.generatedAudio, item.remoteId, item.requiresReference)
            selectedTemplate = null
        }
    }
}

@Composable
private fun StudioCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = StudioColors.surface, shape = PanelShape, border = BorderStroke(1.dp, StudioColors.border),
        shadowElevation = if(StudioColors.dark) 0.dp else 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun PromptAction(label: String, icon: ImageVector, action: () -> Unit) {
    TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 9.dp), colors = ButtonDefaults.textButtonColors(contentColor = StudioColors.text)) {
        Icon(icon, null, Modifier.size(17.dp), tint = if(label == "Inspire") StudioColors.primary else StudioColors.text)
        Spacer(Modifier.width(5.dp)); Text(label, fontSize = 11.sp)
    }
}

@Composable
private fun ReferencePhotoInput(vm: MotionViewModel, still: Boolean, pickPhoto: () -> Unit, templates: () -> Unit) {
    val photo = vm.photo
    val colors = StudioColors
    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Image, null, Modifier.size(20.dp))
            Text("Reference Image", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(if(still && vm.imageDraft.requiresReference) "  (Your photo required)" else if(still) "  (Optional)" else "  (Starting Frame)", fontSize = 10.sp, color = colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if(photo != null) Box(Modifier.weight(1f).height(112.dp).clip(RoundedCornerShape(14.dp)).background(colors.field)) {
                Image(photo.asImageBitmap(), "Selected reference image", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                IconButton(onClick = vm::removePhoto, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                    Box(Modifier.size(25.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Remove reference", Modifier.size(17.dp), tint = Color.White)
                    }
                }
            }
            Surface(onClick = pickPhoto, color = colors.field, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, colors.border), modifier = Modifier.weight(1f).heightIn(min = 112.dp).testTag("upload-photo")) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(29.dp))
                    Text(if(photo != null) "Add or Replace Image" else "Add your photo", fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("JPG, PNG, WebP", color = colors.muted, fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = templates, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Rounded.Layers, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Templates", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun CreationButton(still: Boolean, price: Long?, enabled: Boolean, create: () -> Unit) {
    Surface(color = Ink, modifier = Modifier.fillMaxWidth()) {
        Button(onClick = create, enabled = enabled,
            modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 56.dp).background(MagicGradient, RoundedCornerShape(16.dp)).testTag("create-button"),
            shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .55f))) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(if(still) "Create Image" else "Create Video", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            VerticalDivider(Modifier.height(25.dp), color = Color.White.copy(alpha = .35f)); Spacer(Modifier.width(9.dp))
            Icon(Icons.Rounded.Bolt, null, Modifier.size(19.dp), tint = Color(0xFFFFD458))
            Text(if(price == null) "Unavailable" else "$price Credits", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreationModeSheet(mode: CreationMode, story: Boolean, dismiss: () -> Unit, chooseStory: () -> Unit, choose: (CreationMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Panel) {
        Column(Modifier.padding(16.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Creation Mode", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            CreationMode.entries.forEach { option ->
                Surface(onClick = { choose(option) }, color = if(!story && mode == option) StudioColors.selected else StudioColors.field,
                    shape = PanelShape, border = BorderStroke(1.dp, if(!story && mode == option) StudioColors.primary else StudioColors.border),
                    modifier = Modifier.fillMaxWidth().testTag("mode-${option.title}")) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(option.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if(!story && mode == option) Icon(Icons.Rounded.CheckCircle, "Selected", tint = StudioColors.primary)
                    }
                }
            }
            Surface(onClick = chooseStory, color = if(story) StudioColors.selected else StudioColors.field,
                shape = PanelShape, border = BorderStroke(1.dp, if(story) StudioColors.primary else StudioColors.border),
                modifier = Modifier.fillMaxWidth().testTag("mode-Continue Story")) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Continue Story", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if(story) Icon(Icons.Rounded.CheckCircle, "Selected", tint = StudioColors.primary)
                }
            }
        }
    }
}

@Composable
fun CreationSettingsScreen(vm: MotionViewModel) {
    val still = vm.creationSettingsMode == CreationMode.CREATE_IMAGE
    val draft = if(still) vm.imageDraft else vm.draft
    CreationSettingsPanel(draft, still, vm.cloudRates,
        !vm.creationSettingsStoryOnly && vm.creationSettingsMode == CreationMode.IMAGE_TO_VIDEO && vm.photo != null,
        { if(still) vm.updateImageDraft(it) else vm.updateDraft(it) }, vm::selectVideoModel, vm::back, vm.referenceVideoHasAudio,
        storyModelsOnly = vm.creationSettingsStoryOnly, referenceDurationMs = vm.referenceVideoDurationMs)
}

@Composable
internal fun CreationSettingsPanel(draft: CreationDraft, still: Boolean, rates: List<com.gamixtv.MotionMagicAI.cloud.CloudRate>,
    sourceRatio: Boolean, update: (CreationDraft) -> Unit, selectVideo: (VideoModelOption) -> Unit,
    close: () -> Unit, referenceHasAudio: Boolean? = null, storyModelsOnly: Boolean = false, referenceDurationMs: Long? = null) {
    val colors = StudioColors
    val focus = LocalFocusManager.current
    val finish = { focus.clearFocus(); close() }
    val activeRate = rates.firstOrNull { it.matches(draft, still) }
    Column(Modifier.fillMaxSize().imePadding().background(Ink)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = finish) { Icon(Icons.Rounded.ArrowBackIosNew, "Close settings", Modifier.size(22.dp)) }
            Text(if(still) "Image Settings" else if(storyModelsOnly) "Story Settings" else "Video Settings", Modifier.weight(1f).padding(end = 48.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        Text(if(still) "Configure model, quality and aspect ratio" else "Configure model, quality, aspect ratio and duration",
            fontSize = 11.sp, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp))
        HorizontalDivider(color = colors.border)
        ReferenceSettings(draft, still, update, selectVideo, rates, sourceRatio, referenceHasAudio, Modifier.weight(1f), storyModelsOnly, referenceDurationMs)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = colors.surface, shape = PanelShape, border = BorderStroke(1.dp, colors.border)) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Toll, null, Modifier.size(21.dp)); Text("Estimated cost", Modifier.weight(1f).padding(start = 9.dp), fontSize = 13.sp)
                    Icon(Icons.Rounded.Bolt, null, tint = Color(0xFFFFBD32), modifier = Modifier.size(22.dp))
                    Text((if(draft.modelId == "kling-o3-edit" && referenceDurationMs == null && !still) null else activeRate?.priceFor(draft, still))?.let { "$it Credits" }
                        ?: if(!still && draft.modelId == "kling-o3-edit" && referenceDurationMs == null) "Add base video" else "Unavailable", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            GradientButton("Done", finish, Modifier.testTag("settings-done"), icon = Icons.Rounded.Check)
        }
    }
}

@Composable
private fun ReferenceSettings(draft: CreationDraft, still: Boolean, update: (CreationDraft) -> Unit, selectVideo: (VideoModelOption) -> Unit,
    rates: List<com.gamixtv.MotionMagicAI.cloud.CloudRate>, sourceRatio: Boolean, referenceHasAudio: Boolean?, modifier: Modifier, storyModelsOnly: Boolean, referenceDurationMs: Long?) {
    val colors = StudioColors
    val id = if(still) draft.imageModelId else draft.modelId
    val title = if(still) ImageModelCatalog.find(id).title else VideoModelCatalog.find(id).title
    val modelRates = rates.filter { it.modelId == id && it.kind == (if(still) "image" else "video") }
    val activeRate = modelRates.firstOrNull { it.matches(draft, still) }
    var choosing by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel("AI Model", Icons.Rounded.Layers)
            Box {
                Surface(onClick = { choosing = true }, color = colors.surface, shape = PanelShape, border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth().testTag("model-picker")) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Art(if(still) Artwork.Beach else Artwork.Fantasy, Modifier.size(42.dp), null)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if(!still && id == "kling-o3-edit") "Edit existing clip · video + up to 4 photos" else if(!still && id == "kling-o3-reference") "New scene · video + up to 4 photos" else if(still) "Create from your words or photo" else "Create from text or a starting frame", color = colors.muted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        Icon(Icons.Rounded.ExpandMore, "Choose model", Modifier.size(20.dp))
                    }
                }
                DropdownMenu(choosing, { choosing = false }, containerColor = colors.surface,
                    modifier = Modifier.width((LocalConfiguration.current.screenWidthDp - 32).dp).heightIn(max = 380.dp)) {
                    val options = if(still) ImageModelCatalog.models.map { it.id to it.title } else VideoModelCatalog.forWorkflow(storyModelsOnly).map { it.id to it.title }
                    val enabled = options.filter { (modelId, _) -> rates.any { it.modelId == modelId && it.kind == (if(still) "image" else "video") } }
                    enabled.forEach { (modelId, name) ->
                        DropdownMenuItem(text = { Text(name, fontSize = 13.sp) }, modifier = Modifier.testTag("model-option-$name"),
                            trailingIcon = { if(modelId == id) Icon(Icons.Rounded.Check, "Selected", tint = colors.primary) },
                            onClick = {
                                if(still) {
                                    val available = rates.filter { it.modelId == modelId && it.kind == "image" }
                                    val chosen = available.firstOrNull { it.size == draft.imageQuality } ?: available.first()
                                    update(draft.copy(imageModelId = modelId, imageQuality = chosen.size, aspect = draft.aspect.takeIf { it in chosen.ratios } ?: chosen.ratios.first()))
                                } else selectVideo(VideoModelCatalog.find(modelId))
                                choosing = false
                            })
                    }
                    if(enabled.isEmpty()) DropdownMenuItem(text = { Text("No models available") }, onClick = {}, enabled = false)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel("Output Quality", Icons.Rounded.HighQuality)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modelRates.map { it.size }.distinct().forEach { resolution ->
                    val selected = resolution == (if(still) draft.imageQuality else draft.resolution)
                    SettingTile(resolution.uppercase(), when(resolution) { "720p" -> "Fast"; "1080p" -> "High Quality"; "1K" -> "Standard"; else -> "High Detail" },
                        Icons.Rounded.HighQuality, selected, true, Modifier.weight(1f).testTag("resolution-$resolution"), showIcon = false) {
                        val choices = modelRates.filter { it.size == resolution }
                        val chosen = choices.firstOrNull { still || it.audio == draft.generatedAudio } ?: choices.first()
                        update(if(still) draft.copy(imageQuality = resolution, aspect = draft.aspect.takeIf { it in chosen.ratios } ?: chosen.ratios.first())
                            else draft.copy(resolution = resolution, generatedAudio = chosen.audio ?: false,
                                duration = draft.duration.takeIf { it in chosen.durations } ?: chosen.durations.first(),
                                aspect = draft.aspect.takeIf { it in chosen.ratios } ?: chosen.ratios.first()))
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel("Aspect Ratio", Icons.Rounded.Crop)
            if(!still && draft.modelId == "kling-o3-edit") {
                Text("Matches your base video's frame", color = colors.muted, fontSize = 13.sp)
            } else if(sourceRatio && !still) {
                Text("Matches your reference photo", color = colors.muted, fontSize = 13.sp)
            } else {
                val ratios = activeRate?.ratios ?: modelRates.firstOrNull()?.ratios.orEmpty()
                val columns = if(LocalConfiguration.current.fontScale > 1.3f) 2 else if(ratios.size <= 3) 3 else 4
                ratios.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { ratio -> RatioSquare(ratio, draft.aspect == ratio, Modifier.weight(1f)) { update(draft.copy(aspect = ratio)) } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if(!still) {
            if(draft.modelId == "kling-o3-edit") {
                SettingsLabel("Clip duration", Icons.Rounded.Timer)
                Text(referenceDurationMs?.let { "${"%.2f".format(java.util.Locale.US, it / 1000.0)}s source · billed as ${draft.duration}s (rounded up)" }
                    ?: "Add a base video to calculate credits. The edit keeps its length.", color = colors.muted, fontSize = 12.sp, modifier = Modifier.testTag("source-duration"))
            } else ModelDurationPicker(activeRate?.durations.orEmpty(), draft.duration) { update(draft.copy(duration = it)) }
            AudioOptions(draft, modelRates, referenceHasAudio, update)
        }
        if(activeRate == null) Text("Choose an available model and settings to see the credit estimate.", color = colors.muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsLabel(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, Modifier.size(22.dp)); Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingTile(title: String, subtitle: String, icon: ImageVector, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    showIcon: Boolean = true, click: () -> Unit) {
    val colors = StudioColors
    Surface(onClick = click, enabled = enabled, color = if(selected) colors.selected else colors.surface, shape = RoundedCornerShape(15.dp),
        border = BorderStroke(if(selected) 1.5.dp else 1.dp, if(selected) colors.primary else colors.border), modifier = modifier) {
        Box {
            Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if(showIcon) Icon(icon, null, Modifier.size(23.dp), tint = if(enabled) colors.text else colors.muted)
                Text(title, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    color = if(enabled && selected) colors.primary else if(enabled) colors.text else colors.muted)
                if(subtitle.isNotBlank()) Text(subtitle, color = if(selected) colors.primary else colors.muted, fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
            }
            if(selected) Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp), tint = colors.primary)
        }
    }
}

@Composable
private fun RatioSquare(ratio: String, selected: Boolean, modifier: Modifier = Modifier, choose: () -> Unit) {
    val colors = StudioColors
    val values = ratio.split(":").map(String::toFloat)
    val factor = values[0] / values[1]
    Surface(onClick = choose, color = if(selected) colors.selected else colors.surface, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if(selected) 1.5.dp else 1.dp, if(selected) colors.primary else colors.border), modifier = modifier.testTag("ratio-$ratio")) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(if(factor >= 1) 22.dp else (22 * factor).dp, if(factor <= 1) 22.dp else (22 / factor).dp)
                    .drawBehind { drawRoundRect(if(selected) colors.primary else colors.text, cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(1.6.dp.toPx())) })
            }
            Text(ratio, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, color = if(selected) colors.primary else colors.text)
            Text(when(ratio) { "16:9" -> "Landscape"; "9:16" -> "Portrait"; "1:1" -> "Square"; "4:5" -> "Social"; else -> "Frame" },
                fontSize = 9.sp, lineHeight = 13.sp, color = if(selected) colors.primary else colors.muted)
        }
    }
}

@Composable
private fun AudioChoice(draft: CreationDraft, available: Boolean, hasAudio: Boolean?, change: (Boolean) -> Unit) {
    val colors = StudioColors
    val reference = VideoModelCatalog.isReference(draft.modelId)
    val silent = reference && hasAudio == false
    Surface(color = colors.surface, shape = PanelShape, border = BorderStroke(1.dp, colors.border)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if(reference) "Keep reference audio" else "Generate Audio", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if(reference) Text(if(silent) "No audio track. This model cannot generate new sound." else "Keeps existing sound only. Silent video stays silent.", fontSize = 10.sp, color = colors.muted)
            }
            Switch(draft.generatedAudio && !silent, change, enabled = !silent && (available || draft.generatedAudio), modifier = Modifier.testTag("generated-audio-toggle"))
        }
    }
}

@Composable
private fun AudioOptions(draft: CreationDraft, rates: List<com.gamixtv.MotionMagicAI.cloud.CloudRate>, hasAudio: Boolean?, update: (CreationDraft) -> Unit) {
    val reference = VideoModelCatalog.isReference(draft.modelId)
    val silent = reference && hasAudio == false
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsLabel("Audio", Icons.Rounded.GraphicEq)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true, false).filter { sound -> rates.any { it.size == draft.resolution && it.audio == sound } }.forEach { sound ->
                SettingTile(if(sound && reference) "Keep Reference\nAudio" else if(sound) "Generate\nAudio" else "Mute\n(No Audio)", "",
                    if(sound && reference) Icons.Rounded.VideoLibrary else if(sound) Icons.Rounded.MusicNote else Icons.Rounded.VolumeOff,
                    draft.generatedAudio == sound && !(sound && silent), !(sound && silent),
                    Modifier.weight(1f).testTag(if(sound && reference) "audio-keep" else if(sound) "audio-generate" else "audio-mute")) {
                    val variant = rates.first { it.size == draft.resolution && it.audio == sound }
                    update(draft.copy(generatedAudio = sound, duration = draft.duration.takeIf { it in variant.durations } ?: variant.durations.first()))
                }
            }
        }
        if(reference) Text(if(silent) "Your reference has no audio track. This model cannot generate new sound."
            else "Keeps existing sound only. Silent video stays silent.", color = StudioColors.muted, fontSize = 11.sp)
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
            Text("Duration", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = RefMuted, modifier = Modifier.weight(1f))
            Surface(onClick = { entering = true }, enabled = durations.isNotEmpty(), color = RefTile, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, CreatorBorder)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selected}s", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.Edit, "Type duration", Modifier.size(16.dp), tint = CreatorAccent)
                }
            }
        }
        if(durations.size > 1) Slider(value = durations.indexOf(selected).coerceAtLeast(0).toFloat(),
            onValueChange = { value -> durations[value.roundToInt().coerceIn(durations.indices)].takeIf { it != selected }?.let(choose) },
            valueRange = 0f..durations.lastIndex.toFloat(), steps = (durations.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = CreatorAccent, activeTrackColor = StudioColors.primary, inactiveTrackColor = StudioColors.border,
                activeTickColor = StudioColors.primary, inactiveTickColor = StudioColors.muted),
            modifier = Modifier.fillMaxWidth().testTag("duration-slider"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOfNotNull(durations.firstOrNull(), 5, 10, durations.lastOrNull(), selected).distinct().filter { it in durations }.sorted()) { seconds ->
                Surface(onClick = { choose(seconds) }, color = Color.Transparent,
                    shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if(selected == seconds) CreatorAccent else CreatorBorder),
                    modifier = Modifier.width(52.dp).height(38.dp).testTag("duration-$seconds")) {
                    Box(Modifier.fillMaxSize().background(if(selected == seconds) CreatorSelectionGradient else CreatorTileGradient), contentAlignment = Alignment.Center) { Text("${seconds}s", fontSize = 12.sp) }
                }
            }
        }
        Text(if(durations.isEmpty()) "Choose an available model and audio setting to see durations."
            else "${supported}s · swipe or tap the number to enter", color = RefMuted, fontSize = 11.sp)
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
            confirmButton = { TextButton(onClick = { choose(checkNotNull(number)); entering = false }, enabled = valid) { Text("Apply", color = CreatorAccent) } },
            dismissButton = { TextButton(onClick = { entering = false }) { Text("Cancel", color = RefMuted) } })
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
