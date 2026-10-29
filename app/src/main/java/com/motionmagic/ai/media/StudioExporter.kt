package com.motionmagic.ai.media

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.StatFs
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.AbsoluteSizeSpan
import androidx.media3.common.*
import androidx.media3.common.audio.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
object StudioExporter {
    val filters = listOf("None", "Cinematic", "Warm", "Cool", "Vivid", "Moody", "Sunset", "B&W")
    fun videoEffects(doc: EditorDocument, width: Int = 1080, export: Boolean = true): List<Effect> = buildList {
        when (doc.filter) {
            "B&W", "Grayscale" -> add(RgbFilter.createGrayscaleFilter())
            "Warm" -> add(RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.01f).setBlueScale(.88f).build())
            "Cool" -> add(RgbAdjustment.Builder().setRedScale(.9f).setBlueScale(1.15f).build())
            "Cinematic" -> { add(Contrast(.16f)); add(RgbAdjustment.Builder().setRedScale(1.06f).setBlueScale(.9f).build()) }
            "Vivid" -> add(HslAdjustment.Builder().adjustSaturation(25f).build())
            "Moody" -> { add(Brightness(-.12f)); add(Contrast(.12f)) }
            "Sunset" -> add(RgbAdjustment.Builder().setRedScale(1.2f).setGreenScale(1.04f).setBlueScale(.78f).build())
        }
        if (doc.text.isNotBlank() || doc.sticker.isNotBlank()) {
            val label = (doc.sticker + " " + doc.text).trim().chunked(30).joinToString("\n")
            val text = SpannableString(label).apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan((width * .045f).toInt().coerceIn(20, 72)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(text, StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, -.65f).setOverlayFrameAnchor(0f, -1f).build()))))
        }
        if (export && doc.height > 0) add(Presentation.createForHeight(doc.height))
    }

    suspend fun export(context: Context, doc: EditorDocument, destination: File, audioOnly: Boolean = false,
        onProgress: (Float) -> Unit = {}): File {
        doc.validate()
        if (!audioOnly && doc.clips.size > 1 && doc.speed != 1f) {
            // Media3 1.6's video composition speed restarts timestamps at clip boundaries.
            // Flatten only this combination, then change speed once on the continuous timeline.
            // Both passes stream frames; temporary disk space is released on success or cancellation.
            require(!destination.exists()) { "Choose a new export destination." }
            val stage = File(destination.absoluteFile.parentFile, "stage_${java.util.UUID.randomUUID()}.mp4")
            try {
                exportPass(context, doc.copy(speed = 1f), stage, false, doc.speed) { onProgress(it * .6f) }
                val info = MediaAccess.inspect(context, Uri.fromFile(stage))
                return exportPass(context, EditorDocument(clips = listOf(EditorClip(stage.path, info.durationMs)),
                    speed = doc.speed, height = 0), destination, false, 1f) { onProgress(.6f + it * .4f) }
            } finally { withContext(NonCancellable + Dispatchers.IO) { stage.delete() } }
        }
        return exportPass(context, doc, destination, audioOnly, 1f, onProgress)
    }

    private suspend fun exportPass(context: Context, doc: EditorDocument, destination: File, audioOnly: Boolean,
        laterSpeed: Float, onProgress: (Float) -> Unit): File {
        doc.validate()
        require(doc.filter in filters || doc.filter == "Grayscale") { "Unsupported filter." }
        require(!destination.exists()) { "Choose a new export destination." }
        val sources = withContext(Dispatchers.IO) {
            val unique = doc.clips.map { it.source }.distinct().associateWith { MediaAccess.inspect(context, MediaAccess.uri(it)) }
            doc.clips.forEach { clip ->
                val info = unique.getValue(clip.source)
                require(if (audioOnly) info.hasAudio else info.hasVideo) { "A selected clip has no ${if(audioOnly) "audio" else "video"} track." }
                require(clip.endMs <= info.durationMs + 50) { "Trim exceeds the source duration. Reopen this media." }
                require(MediaAccess.uri(clip.source) != Uri.fromFile(destination)) { "Keep the original media; choose a new output." }
            }
            doc.music?.let { require(MediaAccess.inspect(context, MediaAccess.uri(it)).hasAudio) { "The music file has no audio." } }
            destination.parentFile?.mkdirs()
            val bitRate = if (audioOnly) 256_000L else if (doc.height == 480) 4_000_000L else if (doc.height == 720) 8_000_000L else 16_000_000L
            val estimate = (doc.exportDurationMs / 1000.0 * bitRate / 8 * 1.25).toLong() + 32 * 1024 * 1024L
            require(StatFs(destination.parent!!).availableBytes > estimate) { "Free at least ${estimate / (1024 * 1024)} MB for this export, or reduce resolution/length." }
            unique
        }
        var transformer: Transformer? = null
        var completed = false
        try {
            withContext(Dispatchers.Main.immediate) {
                val items = doc.clips.map { clip ->
                    val media = MediaItem.Builder().setUri(MediaAccess.uri(clip.source))
                        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs)
                            .setEndPositionMs(minOf(clip.endMs, sources.getValue(clip.source).durationMs)).build()).build()
                    val audio = mutableListOf<AudioProcessor>()
                    if (doc.pitch != 1f) audio.add(SonicAudioProcessor().apply { setPitch(doc.pitch) })
                    if (doc.volume != 1f || doc.fadeInMs > 0 || doc.fadeOutMs > 0 || doc.bass != 1f || doc.treble != 1f || doc.noiseGate)
                        audio.add(StudioAudioProcessor(doc.volume, clip.lengthMs, (doc.fadeInMs * doc.speed * laterSpeed).toLong(), (doc.fadeOutMs * doc.speed * laterSpeed).toLong(), doc.bass, doc.treble, doc.noiseGate))
                    // An absent audio track must not contribute its unmodified duration to a sped-up video.
                    EditedMediaItem.Builder(media).setRemoveVideo(audioOnly).setRemoveAudio(!audioOnly && !sources.getValue(clip.source).hasAudio)
                        .setEffects(Effects(audio, if (audioOnly) emptyList() else videoEffects(doc, sources.getValue(clip.source).width))).build()
                }
                val sequences = mutableListOf(EditedMediaItemSequence.Builder(items).build())
                doc.music?.let {
                    val mixer = ChannelMixingAudioProcessor().apply {
                        for (n in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.create(n, n).scaleBy(doc.musicVolume))
                    }
                    sequences.add(EditedMediaItemSequence.Builder(EditedMediaItem.Builder(MediaItem.fromUri(MediaAccess.uri(it)))
                        .setRemoveVideo(true).setEffects(Effects(listOf(mixer), emptyList())).build()).setIsLooping(true).build())
                }
                // Apply speed after arranging/mixing all tracks. Loop boundaries then share one clock.
                val composition = Composition.Builder(sequences).setEffects(if(doc.speed == 1f) Effects.EMPTY else Effects(
                    listOf(SonicAudioProcessor().apply { setSpeed(doc.speed) }),
                    if(audioOnly) emptyList() else listOf(SpeedChangeEffect(doc.speed)))).build()
                val timeout = maxOf(180_000L, (doc.exportDurationMs.toDouble() * 20 + 120000).coerceAtMost(86_400_000.0).toLong())
                withTimeout(timeout) {
                    coroutineScope {
                        val progress = launch {
                            val holder = ProgressHolder()
                            while (isActive) {
                                if (transformer?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                                delay(500)
                            }
                        }
                        try {
                            suspendCancellableCoroutine<Unit> { continuation ->
                                transformer = Transformer.Builder(context.applicationContext)
                                    .setAudioMimeType(MimeTypes.AUDIO_AAC).setVideoMimeType(MimeTypes.VIDEO_H264)
                                    .addListener(object : Transformer.Listener {
                                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                            if (continuation.isActive) continuation.resume(Unit)
                                        }
                                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(
                                                "Export failed (${exportException.errorCodeName}). Check storage, choose a lower resolution or another source.", exportException))
                                        }
                                    }).build()
                                try { transformer!!.start(composition, destination.path) }
                                catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                            }
                        } finally { progress.cancel() }
                    }
                }
            }
            require(destination.length() > 0) { "The exporter did not produce a file." }
            completed = true
            return destination
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { transformer?.cancel() }
            if (!completed) withContext(NonCancellable + Dispatchers.IO) { destination.delete() }
        }
    }
}
