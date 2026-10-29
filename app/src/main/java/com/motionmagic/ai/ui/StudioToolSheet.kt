@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.motionmagic.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmagic.ai.media.*
import java.util.Locale

@Composable
fun StudioToolSheet(session: StudioSession, onMusic: () -> Unit) {
    val original = session.document
    var draft by remember(session.tool) { mutableStateOf(original) }
    val clip = original.clips.getOrNull(original.selected) ?: return
    var start by remember(session.tool) { mutableStateOf(String.format(Locale.ROOT, "%.3f", clip.startMs / 1000.0)) }
    var end by remember(session.tool) { mutableStateOf(String.format(Locale.ROOT, "%.3f", clip.endMs / 1000.0)) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { session.tool = "" }, containerColor = StudioPanel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(session.tool, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            when(session.tool) {
                "Trim" -> {
                    Text("Clip ${original.selected + 1} · source ${studioTime(clip.durationMs)}", color = Muted, fontSize = 12.sp)
                    val startValue = start.toFloatOrNull()?.coerceIn(0f, clip.durationMs / 1000f) ?: 0f
                    val endValue = end.toFloatOrNull()?.coerceIn(startValue, clip.durationMs / 1000f) ?: clip.durationMs / 1000f
                    RangeSlider(startValue..endValue, onValueChange = {
                        if(it.endInclusive - it.start >= .25f) { start = String.format(Locale.ROOT, "%.3f", it.start); end = String.format(Locale.ROOT, "%.3f", it.endInclusive) }
                    }, valueRange = 0f..clip.durationMs / 1000f)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(start, { start = it.take(20) }, Modifier.weight(1f), label = { Text("Start (seconds)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(end, { end = it.take(20) }, Modifier.weight(1f), label = { Text("End (seconds)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    Text("Type exact seconds for long recordings. Trim and split keep the source file unchanged.", color = Muted, fontSize = 12.sp)
                }
                "Speed" -> {
                    StudioSlider("Playback speed", draft.speed, .25f..3f, "${String.format(Locale.ROOT, "%.2f", draft.speed)}×") { draft = draft.copy(speed = it) }
                    Text("Changes both video and audio timing; pitch stays independent.", color = Muted, fontSize = 12.sp)
                }
                "Volume" -> {
                    StudioSlider("Original audio", draft.volume, 0f..2f, "${(draft.volume * 100).toInt()}%") { draft = draft.copy(volume = it) }
                    Text("Preview is capped at 100%. Export applies gains above 100% with clipping protection.", color = Muted, fontSize = 11.sp)
                    if(draft.music != null) {
                        StudioSlider("Background music", draft.musicVolume, 0f..1f, "${(draft.musicVolume * 100).toInt()}%") { draft = draft.copy(musicVolume = it) }
                        Text(draft.musicName, fontSize = 12.sp)
                        TextButton(onClick = { draft = draft.copy(music = null, musicName = "") }) { Text("Remove background music") }
                    } else OutlinedButton(onClick = { session.tool = ""; onMusic() }) { Text("Add background music") }
                }
                "Fade" -> {
                    val maximum = minOf(30f, clip.lengthMs / 1000f / draft.speed)
                    StudioSlider("Fade in", (draft.fadeInMs / 1000f).coerceAtMost(maximum), 0f..maximum, "${draft.fadeInMs / 1000f}s") { draft = draft.copy(fadeInMs = (it * 1000).toLong()) }
                    StudioSlider("Fade out", (draft.fadeOutMs / 1000f).coerceAtMost(maximum), 0f..maximum, "${draft.fadeOutMs / 1000f}s") { draft = draft.copy(fadeOutMs = (it * 1000).toLong()) }
                    Text("Fades apply to the original audio at the start and end of each clip during export.", color = Muted, fontSize = 12.sp)
                }
                "Text" -> {
                    OutlinedTextField(draft.text, { draft = draft.copy(text = it.take(120)) }, Modifier.fillMaxWidth(), minLines = 2,
                        placeholder = { Text("MotionMagic AI") }, supportingText = { Text("${draft.text.length}/120 · bottom center") })
                }
                "Filters" -> StudioExporter.filters.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { name -> TextButton(onClick = { draft = draft.copy(filter = name) }, Modifier.weight(1f)) { Text(name, fontSize = 11.sp, color = if(draft.filter == name) Violet else Muted) } }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            GradientButton("Apply", {
                try {
                    if(session.tool == "Trim") {
                        val from = start.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= clip.durationMs / 1000.0 } ?: error("Enter a valid start time.")
                        val to = end.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= clip.durationMs / 1000.0 + .001 } ?: error("Enter a valid end time.")
                        draft = draft.copy(clips = draft.clips.mapIndexed { i, item -> if(i == draft.selected) item.copy(startMs = (from * 1000).toLong(), endMs = (to * 1000).toLong().coerceAtMost(clip.durationMs)) else item })
                    }
                    draft.validate(); session.update(draft); session.tool = ""
                } catch(e: Exception) { error = e.message ?: "Check these edit settings." }
            })
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun StudioSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, change: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontSize = 14.sp); Text(label, color = Violet, fontSize = 13.sp) }
        Slider(value.coerceIn(range), change, valueRange = range)
    }
}
