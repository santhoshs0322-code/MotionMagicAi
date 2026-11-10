package com.gamixtv.MotionMagicAI.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.cloud.CloudRate

@Composable
fun LongStoryScreen(vm: MotionViewModel, pickPhoto: (Int?) -> Unit, pickVideo: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshCloudCatalog() }
    LongStoryContent(vm.longStoryDraft, vm.referencePhotoPaths, vm.referenceVideoPath, vm.cloudRates,
        vm::updateLongStory, pickPhoto, pickVideo, vm::removeReferencePhoto,
        { vm.removeReferenceVideo(keepPhotos = true) }, vm::prepareCloudGeneration, vm::refreshCloudCatalog)
}

@Composable
internal fun LongStoryContent(draft: CreationDraft, photos: List<String>, video: String?, rates: List<CloudRate>,
    update: (CreationDraft) -> Unit, pickPhoto: (Int?) -> Unit, pickVideo: () -> Unit, removePhoto: (Int) -> Unit,
    removeVideo: () -> Unit, create: () -> Unit, refresh: () -> Unit) {
    val colors = StudioColors
    val rate = rates.firstOrNull { it.matches(draft, false) }
    val price = rate?.priceFor(draft, false)
    val characterCount = photos.size.coerceAtMost(2) + if(video != null) 1 else 0
    val promptCharacters = if(characterCount == 0) "two friends" else (1..characterCount).joinToString(" and ") { "@Element$it" }
    Column(Modifier.fillMaxSize().imePadding().background(colors.background).testTag("long-story-screen")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("One idea. A whole story.", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("1–5 minutes · prompt only, photos, video or both · spoken dialogue", color = colors.muted, fontSize = 13.sp)
            StoryPanel {
                Text("Character photos · optional", fontWeight = FontWeight.Bold)
                Text("Create from your prompt alone, or add one or two photos. Photo 1 is @Element1; photo 2 is @Element2.", color = colors.muted, fontSize = 12.sp)
                photos.take(2).forEachIndexed { index, path ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProjectThumbnail(path, video = false)
                        Text("Character ${index + 1}", Modifier.weight(1f))
                        IconButton(onClick = { pickPhoto(index) }) { Icon(Icons.Rounded.Edit, "Replace character ${index + 1}") }
                        IconButton(onClick = { removePhoto(index) }) { Icon(Icons.Rounded.Close, "Remove character ${index + 1}") }
                    }
                }
                if(photos.size < 2) OutlinedButton(onClick = { pickPhoto(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("Add character photo")
                }
                if(photos.size > 2) Text("Remove extra photos before creating. Long Story uses up to two.", color = colors.error)
                if(photos.size > 2) TextButton(onClick = { removePhoto(photos.lastIndex) }) { Text("Remove last extra photo") }
            }
            StoryPanel {
                Text("Continue from a character video", fontWeight = FontWeight.Bold)
                Text("Optional MP4, 3–15 seconds. Use a video alone or with photos. It guides a character's identity. Your video is @Element${photos.size.coerceAtMost(2) + 1}.", color = colors.muted, fontSize = 12.sp)
                video?.let { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProjectThumbnail(path, video = true)
                        Text("Character reference", Modifier.weight(1f).padding(8.dp))
                        IconButton(onClick = removeVideo) { Icon(Icons.Rounded.Close, "Remove video reference") }
                    }
                }
                OutlinedButton(onClick = pickVideo, modifier = Modifier.fillMaxWidth()) { Text(if(video == null) "Add reference video" else "Replace reference video") }
            }
            StoryPanel {
                Text("Tell your story", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = draft.prompt, onValueChange = { update(draft.copy(prompt = it.take(1000))) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp).testTag("long-story-prompt"),
                    placeholder = { Text("Tell a story about $promptCharacters discovering a hidden city. They speak English. Start with a mystery, reveal the secret, and end with a hopeful twist…") },
                    supportingText = { Text("${draft.prompt.length}/1000 · include dialogue language and character voice descriptions") }, shape = RoundedCornerShape(16.dp))
            }
            StoryPanel {
                Text("Story length", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { minutes -> FilterChip(selected = draft.duration == minutes * 60,
                        onClick = { update(draft.copy(duration = minutes * 60)) }, label = { Text("$minutes min", fontSize = 12.sp) }, modifier = Modifier.weight(1f).testTag("story-minutes-$minutes")) }
                }
                Text("Frame", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("9:16", "16:9", "1:1").forEach { ratio -> FilterChip(selected = draft.aspect == ratio,
                        onClick = { update(draft.copy(aspect = ratio)) }, label = { Text(ratio) }) }
                }
                Text("Kling O3 Pro · 1080p · ${draft.duration / 15} scenes · native audio and lip sync", fontSize = 13.sp, color = colors.primary)
                Text("AI writes the scenes from your brief. Faces, voices and lip sync can vary between scenes. Generation runs in the cloud; you can leave this page. One finished film appears in My Projects.", color = colors.muted, fontSize = 12.sp)
                if(rate == null) {
                    Text("Long Story is not enabled on this server yet.", color = colors.warning)
                    TextButton(onClick = refresh) { Text("Refresh availability") }
                }
            }
        }
        Button(onClick = create, enabled = price != null,
            modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 56.dp).background(MagicGradient, RoundedCornerShape(16.dp)).testTag("create-long-story"),
            shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White)) {
            Icon(Icons.Rounded.AutoAwesome, null)
            Text("Create Story", Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Bold)
            Icon(Icons.Rounded.Bolt, null)
            Text(price?.let { "$it credits" } ?: "Unavailable")
        }
    }
}

@Composable
private fun StoryPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = StudioColors.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, StudioColors.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
