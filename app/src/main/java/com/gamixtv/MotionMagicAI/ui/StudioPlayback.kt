@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.gamixtv.MotionMagicAI.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.gamixtv.MotionMagicAI.media.*
import kotlinx.coroutines.delay

class StudioPlayback(private val context: Context) {
    private fun player() = ExoPlayer.Builder(context.applicationContext)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1500, 6000, 500, 1000)
            .setTargetBufferBytes(16 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
    val player = player()
    private val music = player()
    var document = EditorDocument()
    var position by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun pause() { player.pause(); music.pause() }
    fun syncMusic(playing: Boolean) { music.playWhenReady = playing && document.music != null }
    fun toggle() {
        if (player.isPlaying) pause() else {
            if (player.playbackState == Player.STATE_ENDED) seek(0)
            if (player.playbackState == Player.STATE_IDLE && document.clips.isNotEmpty()) player.prepare()
            player.play(); music.playWhenReady = document.music != null
        }
    }
    fun seek(positionMs: Long) {
        var offset = positionMs.coerceIn(0, (document.durationMs - 1).coerceAtLeast(0))
        document.clips.forEachIndexed { index, clip ->
            if (offset < clip.lengthMs || index == document.clips.lastIndex) {
                player.seekTo(index, offset); position = positionMs.coerceIn(0, document.durationMs)
                if (music.duration > 0) music.seekTo(position % music.duration)
                return
            }
            offset -= clip.lengthMs
        }
    }
    fun musicChanged() {
        music.pause(); music.clearMediaItems()
        document.music?.let {
            music.setMediaItem(MediaItem.fromUri(MediaAccess.uri(it))); music.repeatMode = Player.REPEAT_MODE_ONE
            music.volume = document.musicVolume; music.prepare(); music.playWhenReady = player.isPlaying
        }
    }
    fun volumeChanged() { player.volume = document.volume.coerceIn(0f, 1f); music.volume = document.musicVolume; music.playbackParameters = PlaybackParameters(document.speed) }
    fun release() { player.release(); music.release() }
}

@Composable
fun rememberStudioPlayback(document: EditorDocument, audio: Boolean): StudioPlayback {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember { StudioPlayback(context) }
    state.document = document
    LaunchedEffect(document.clips) {
        val position = state.position
        val wasPlaying = state.player.isPlaying
        state.player.setMediaItems(document.clips.map { clip -> MediaItem.Builder().setUri(MediaAccess.uri(clip.source))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build() })
        state.error = null
        if (document.clips.isNotEmpty()) { state.player.prepare(); state.seek(position); state.player.playWhenReady = wasPlaying }
    }
    LaunchedEffect(document.speed, document.pitch, document.volume, document.musicVolume) {
        state.player.playbackParameters = PlaybackParameters(document.speed, document.pitch); state.volumeChanged()
    }
    LaunchedEffect(document.filter, document.text, document.sticker, audio) {
        state.player.setVideoEffects(if (audio) emptyList() else StudioExporter.videoEffects(document, export = false))
    }
    LaunchedEffect(document.music) { state.musicChanged() }
    LaunchedEffect(state) {
        while (true) {
            val index = state.player.currentMediaItemIndex.coerceAtLeast(0)
            state.position = (state.document.clips.take(index).sumOf { it.lengthMs } + state.player.currentPosition.coerceAtLeast(0)).coerceAtMost(state.document.durationMs)
            state.playing = state.player.isPlaying
            state.buffering = state.player.playbackState == Player.STATE_BUFFERING
            delay(100)
        }
    }
    DisposableEffect(state, lifecycle) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { state.error = "Cannot preview this file. Check its access or import a supported file."; state.pause() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.syncMusic(isPlaying) }
        }
        state.player.addListener(listener)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.player.removeListener(listener); state.release() }
    }
    return state
}
