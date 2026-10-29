@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.motionmagic.ai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.motionmagic.ai.MotionViewModel
import com.motionmagic.ai.Screen
import com.motionmagic.ai.data.MediaKind
import com.motionmagic.ai.media.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun StudioEditorScreen(vm: MotionViewModel, audio: Boolean, onImport: () -> Unit) {
    val session: StudioSession = viewModel(key = if(audio) "audio-studio" else "video-studio")
    val project = vm.selectedProject?.takeIf { audio || it.kind == MediaKind.VIDEO }
    LaunchedEffect(project?.id, audio) { session.open(project, audio) }
    val doc = session.document
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = rememberStudioPlayback(doc, audio)
    var quality by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val canEdit = doc.clips.isNotEmpty() && !session.loading && !adding
    val addClip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            adding = true
            try { session.append(vm.retainEditorSource(uri, if(audio) MediaKind.AUDIO else MediaKind.VIDEO)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { importError = e.message ?: "Could not add this clip." }
            finally { adding = false }
        }
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            adding = true
            try {
                val source = vm.retainEditorSource(uri, MediaKind.AUDIO)
                session.update(session.document.copy(music = source.path, musicName = source.title))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { importError = e.message ?: "Cannot open this audio file." }
            finally { adding = false }
        }
    }
    val source = doc.clips.getOrNull(doc.selected)
    val sourceOffset = doc.clips.take(doc.selected).sumOf { it.lengthMs }
    val wavePosition = ((playback.position - sourceOffset).toFloat() / (source?.lengthMs ?: 1)).coerceIn(0f, 1f)
    val waveSeek: (Float) -> Unit = { playback.seek(sourceOffset + (it * (source?.lengthMs ?: 0)).toLong()) }
    val thumbnails by produceState<List<android.graphics.Bitmap>>(emptyList(), source, audio) {
        value = emptyList()
        if (!audio && source != null) try { value = MediaAnalysis.thumbnails(context, source.source, source.durationMs, source.startMs, source.endMs) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* Playback still works without thumbnails. */ }
    }
    var analyzing by remember { mutableStateOf(false) }
    val peaks by produceState(FloatArray(0), source, audio) {
        value = FloatArray(0)
        if (audio && source != null) {
            analyzing = true
            try { value = MediaAnalysis.waveform(context, source.source, source.durationMs, source.startMs, source.endMs) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Never block editing on analysis. */ }
            finally { analyzing = false }
        }
    }
    val export = {
        playback.pause()
        vm.exportStudio(doc, audio)
    }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.Rounded.ArrowBackIosNew, "Back", Modifier.size(21.dp)) }
            Text(buildAnnotatedString { append(if(audio) "Audio " else "Video "); withStyle(SpanStyle(color = Violet)) { append("Editor") } },
                Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (audio) OutlinedButton(onClick = { playback.pause(); onImport() }, contentPadding = PaddingValues(10.dp), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Rounded.FileUpload, null, Modifier.size(16.dp)); Text("Import", fontSize = 11.sp)
            } else Box {
                OutlinedButton(onClick = { quality = true }, contentPadding = PaddingValues(9.dp), shape = RoundedCornerShape(10.dp)) {
                    Text(if(doc.height == 0) "Source" else "${doc.height}P", fontSize = 11.sp); Icon(Icons.Rounded.ExpandMore, null, Modifier.size(15.dp))
                }
                DropdownMenu(quality, { quality = false }) {
                    listOf(480, 720, 1080, 0).forEach { height -> DropdownMenuItem(text = { Text(if(height == 0) "Source size" else "${height}p") },
                        onClick = { session.update(doc.copy(height = height)); quality = false }) }
                }
            }
            Spacer(Modifier.width(7.dp))
            Button(onClick = export, enabled = canEdit, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(10.dp),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if(canEdit) MagicGradient else Brush.horizontalGradient(listOf(Panel, Panel))),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) {
                Icon(Icons.Rounded.IosShare, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("Export", fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.weight(1f).imePadding().testTag("studio-content"), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item {
                if (doc.clips.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(190.dp).clip(PanelShape).background(Brush.verticalGradient(listOf(Color(0xFF24133C), StudioPanel))), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(if(audio) Icons.Rounded.GraphicEq else Icons.Rounded.MovieCreation, null, Modifier.size(44.dp), tint = Violet)
                            Text(if(session.loading) "Opening your media…" else if(audio) "Make every sound yours" else "Your story starts here", fontWeight = FontWeight.SemiBold)
                            if(session.loading) CircularProgressIndicator(Modifier.size(24.dp))
                            else OutlinedButton(onClick = onImport) { Text(if(audio) "Import audio" else "Import a video") }
                        }
                    }
                } else if(audio) {
                    Waveform(peaks, wavePosition, Modifier.fillMaxWidth().height(145.dp), waveSeek)
                    Text(if(analyzing) "Preparing waveform preview…" else "Sampled waveform · selected clip ${doc.selected + 1}", color = Muted, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 5.dp), maxLines = 1)
                } else if(!fullscreen) StudioVideo(playback, Modifier.fillMaxWidth().aspectRatio(1.78f), { fullscreen = true })
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton(onClick = playback::toggle, enabled = canEdit, shape = CircleShape, modifier = Modifier.size(46.dp)) {
                        if (playback.buffering) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                        else Icon(if(playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if(playback.playing) "Pause" else "Play", Modifier.size(29.dp))
                    }
                    Text("${studioTime(playback.position)} / ${studioTime(doc.durationMs)}", fontSize = 11.sp, modifier = Modifier.padding(start = 9.dp).weight(1f))
                    IconButton(onClick = { playback.seek((playback.position - 5000).coerceAtLeast(0)) }, enabled = canEdit) { Icon(Icons.Rounded.SkipPrevious, "Back 5 seconds", Modifier.size(21.dp)) }
                    IconButton(onClick = { session.undo() }, enabled = session.canUndo) { Icon(Icons.Rounded.Undo, "Undo", Modifier.size(21.dp)) }
                    IconButton(onClick = { session.redo() }, enabled = session.canRedo) { Icon(Icons.Rounded.Redo, "Redo", Modifier.size(21.dp)) }
                    IconButton(onClick = { fullscreen = true }, enabled = canEdit) { Icon(Icons.Rounded.Fullscreen, "Fullscreen", Modifier.size(22.dp)) }
                }
            }
            if (session.error != null || playback.error != null || importError != null) item {
                Text(session.error ?: playback.error ?: importError.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                TextButton(onClick = { session.dismissError(); importError = null; onImport() }) { Text("Choose another file") }
            }
            if (adding) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Opening media…", color = Muted, fontSize = 12.sp) }
            item {
                StudioTimeline(doc, playback.position, thumbnails, peaks, audio,
                    select = { session.update(doc.copy(selected = it)) }, seek = playback::seek,
                    add = { if(!canEdit) onImport() else if(doc.clips.size < 32) addClip.launch(arrayOf(if(audio) "audio/*" else "video/*")) else vm.notice("Clip limit", "This timeline supports up to 32 clips.") },
                    addMusic = { if(canEdit) musicPicker.launch(arrayOf("audio/*")) else onImport() }, remove = session::removeSelected)
            }
            item {
                if (audio) Text("Audio Tools", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 9.dp))
                val tools = if(audio) listOf("Trim" to Icons.Rounded.ContentCut, "Split" to Icons.Rounded.VerticalSplit,
                    "Volume" to Icons.Rounded.VolumeUp, "Fade" to Icons.Rounded.GraphicEq, "Speed" to Icons.Rounded.Speed)
                else listOf("Trim" to Icons.Rounded.ContentCut, "Split" to Icons.Rounded.VerticalSplit, "Speed" to Icons.Rounded.Speed,
                    "Volume" to Icons.Rounded.VolumeUp, "Text" to Icons.Rounded.Title, "Filters" to Icons.Rounded.FilterVintage)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(tools) { (name, icon) ->
                    StudioTool(name, icon, session.tool == name, canEdit) {
                        playback.pause()
                        if(name == "Split") session.split(playback.position) else session.tool = name
                    }
                } }
            }
            if (!audio) {
                item { StudioFilters(doc, thumbnails.firstOrNull(), canEdit) { session.update(doc.copy(filter = it)) } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onImport) { Text("Replace video", fontSize = 12.sp) }
                    TextButton(onClick = { playback.pause(); vm.go(Screen.AUDIO_EDITOR) }, enabled = canEdit) { Text("Open audio editor", fontSize = 12.sp) }
                } }
            } else {
                item { StudioMusicShelf(canEdit, doc.musicName) { index ->
                    scope.launch {
                        adding = true
                        try { val file = StudioMusic.file(context, index); session.update(session.document.copy(music = file.path, musicName = StudioMusic.names[index])) }
                        catch (e: CancellationException) { throw e } catch(e: Exception) { importError = e.message }
                        finally { adding = false }
                    }
                } }
            }
            if(canEdit) item {
                Text("${if(audio) "AAC · M4A" else "H.264 · MP4"} · ${studioTime(doc.exportDurationMs)} export · Original kept",
                    color = Muted, fontSize = 11.sp)
                Text(if(audio) "Preview includes speed, pitch and background music. Fades, tone controls and noise reduction apply on export."
                    else "Preview includes color, text and speed. Export applies the selected resolution.", color = Muted, fontSize = 10.sp)
            }
        }
    }
    if(session.tool.isNotEmpty() && canEdit) StudioToolSheet(session, onMusic = { musicPicker.launch(arrayOf("audio/*")) })
    if(fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if(audio) Waveform(peaks, wavePosition, Modifier.fillMaxWidth().height(240.dp), waveSeek)
            else StudioVideo(playback, Modifier.fillMaxSize()) { fullscreen = false }
            IconButton(onClick = { fullscreen = false }, Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Icon(Icons.Rounded.Close, "Close fullscreen") }
        }
    }
}

@Composable
private fun StudioVideo(playback: StudioPlayback, modifier: Modifier, fullscreen: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(13.dp)).background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { player = playback.player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
            update = { it.player = playback.player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
        if(!playback.playing) FilledIconButton(onClick = playback::toggle, Modifier.align(Alignment.Center).size(53.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .5f))) { Icon(Icons.Rounded.PlayArrow, "Play video", Modifier.size(34.dp)) }
        IconButton(onClick = fullscreen, Modifier.align(Alignment.BottomEnd)) { Icon(Icons.Rounded.Fullscreen, "Expand preview") }
    }
}

@Composable
private fun StudioFilters(doc: EditorDocument, bitmap: android.graphics.Bitmap?, enabled: Boolean, choose: (String) -> Unit) {
    var category by rememberSaveable { mutableStateOf("Trending") }
    val names = when(category) { "Cinematic" -> listOf("Cinematic", "Warm", "Moody", "Sunset"); "Retro" -> listOf("Warm", "B&W"); "Vlog" -> listOf("None", "Cool", "Vivid"); else -> StudioExporter.filters }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(listOf("Trending", "Cinematic", "Retro", "Vlog")) { StudioPill(it, category == it) { category = it } } }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) { items(names) { name ->
            Column(Modifier.width(67.dp).clickable(enabled = enabled) { choose(name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(67.dp, 76.dp).clip(RoundedCornerShape(9.dp)).border(if(doc.filter == name) 2.dp else 0.dp, Violet, RoundedCornerShape(9.dp))) {
                    if(bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        colorFilter = when(name) {
                            "B&W" -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                            "Warm", "Sunset", "Cinematic" -> ColorFilter.tint(Color(0xFFFFAA55).copy(alpha = .2f), BlendMode.Overlay)
                            "Cool" -> ColorFilter.tint(Color(0xFF55AAFF).copy(alpha = .2f), BlendMode.Overlay)
                            "Moody" -> ColorFilter.tint(Color.Black.copy(alpha = .2f), BlendMode.SrcAtop)
                            "Vivid" -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.3f) })
                            else -> null
                        })
                    else Art(Artwork.Cinematic, Modifier.fillMaxSize())
                }
                Text(name, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
            }
        } }
    }
}

@Composable
private fun StudioMusicShelf(enabled: Boolean, selected: String, choose: (Int) -> Unit) {
    var category by remember { mutableStateOf("Trending") }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Background Music", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(listOf("Trending", "Cinematic", "Happy", "Chill")) { StudioPill(it, it == category) { category = it } } }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { itemsIndexed(StudioMusic.names) { index, label ->
            if(category == "Trending" || category == "Cinematic" && index in listOf(0,2,5) || category == "Happy" && index in listOf(1,6) || category == "Chill" && index in listOf(3,4))
            Column(Modifier.width(88.dp).clickable(enabled = enabled) { choose(index) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.border(if(selected == label) 2.dp else 0.dp, Violet, RoundedCornerShape(9.dp))) {
                    StudioCatalogArt(index, true, Modifier.size(88.dp, 59.dp))
                    Icon(if(selected == label) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircle, "Add $label", Modifier.align(Alignment.Center).size(26.dp))
                }
                Text(label, fontSize = 10.sp, maxLines = 1); Text("00:30", color = Muted, fontSize = 9.sp)
            }
        } }
        Text("Original synthesized loops · tap to add, then Play to hear the mix", color = Muted, fontSize = 10.sp)
    }
}
