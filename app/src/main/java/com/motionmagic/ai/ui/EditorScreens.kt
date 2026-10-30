package com.motionmagic.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.motionmagic.ai.MotionViewModel
import com.motionmagic.ai.Screen
import com.motionmagic.ai.data.MediaKind
import com.motionmagic.ai.data.Project
import com.motionmagic.ai.media.EditOptions
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun MediaPreview(project: Project, modifier: Modifier = Modifier, onError: (String) -> Unit = {}) {
    if(project.kind == MediaKind.IMAGE) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, project.path) {
            value = withContext(Dispatchers.IO) {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(project.path, bounds)
                var sample = 1
                while(bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                android.graphics.BitmapFactory.decodeFile(project.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
        Box(modifier.clip(PanelShape), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), project.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                ?: Text("Opening image…")
        }
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentError by rememberUpdatedState(onError)
    val player = remember(project.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(com.motionmagic.ai.media.MediaAccess.uri(project.path)))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { currentError("This file could not be played. It may be missing, corrupt or use an unsupported format.") }
            })
            prepare()
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply {
        this.player = player
        useController = true
        controllerAutoShow = true
        controllerShowTimeoutMs = 0
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowNextButton(false)
        setShowPreviousButton(false)
        setBackgroundColor(android.graphics.Color.BLACK)
    } }, update = { it.player = player }, modifier = modifier.clip(PanelShape))
}

@Composable
fun ResultScreen(vm: MotionViewModel, onDownload: (Project) -> Unit, onShare: (Project) -> Unit) {
    val project = vm.selectedProject
    var deleting by remember { mutableStateOf(false) }
    var playbackError by remember(project?.id) { mutableStateOf<String?>(null) }
    if(project == null) {
        EmptyState(Icons.Rounded.MovieCreation, "Nothing to preview yet", "Create or open a project to see it here.", "Open projects") { vm.tab(Screen.PROJECTS) }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        if(project.kind == MediaKind.AUDIO) {
            Box(Modifier.fillMaxWidth().height(180.dp).clip(PanelShape).background(MagicGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(100.dp), tint = Color.White)
            }
        }
        MediaPreview(project, Modifier.fillMaxWidth().height(if(project.kind == MediaKind.AUDIO) 110.dp else if(project.aspect == "9:16") 400.dp else 230.dp)) { playbackError = it }
        playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Text(project.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(if(project.kind == MediaKind.IMAGE) "Saved image · ${project.mimeType.substringAfter('/').uppercase()}" else if(project.kind == MediaKind.AUDIO) "Audio · ${if(project.mimeType == "audio/mp4") "AAC / M4A" else project.mimeType.substringAfter('/')}" else "${project.duration}s · ${project.aspect} · ${if(project.title.endsWith("· edited")) "Edited video" else "Saved video"}",
            color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("Download", Icons.Rounded.Download, { onDownload(project) }),
                Triple("Share", Icons.Rounded.Share, { onShare(project) }),
                Triple("Delete", Icons.Rounded.DeleteOutline, { deleting = true })).forEach { (label, icon, action) ->
                Surface(onClick = action, color = Panel, shape = PanelShape, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(vertical = 17.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, Modifier.size(26.dp)); Spacer(Modifier.height(8.dp)); Text(label, fontSize = 12.sp)
                    }
                }
            }
        }
        if(project.kind == MediaKind.VIDEO) GradientButton("Edit Further", { vm.edit(project) }, icon = Icons.Rounded.ContentCut)
        else if(project.kind == MediaKind.AUDIO) GradientButton("Edit Audio", { vm.edit(project) }, icon = Icons.Rounded.GraphicEq)
        OutlinedButton(onClick = { vm.tab(Screen.PROJECTS) }, Modifier.fillMaxWidth()) { Text("My Projects") }
    }
    if(deleting) DeleteDialog(project.title, { deleting = false }) { deleting = false; vm.delete(project) }
}

