package com.gamixtv.MotionMagicAI.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Streams short scenes into one shareable MP4 without loading the videos into memory. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object StoryExporter {
    suspend fun export(context: Context, clips: List<File>, output: File) = withContext(Dispatchers.Main.immediate) {
        require(clips.size in 2..6 && clips.all { it.isFile }) { "Choose two to six saved story scenes." }
        require(!output.exists()) { "The story export file already exists." }
        val items = clips.map { EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it))).build() }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
        suspendCancellableCoroutine<Unit> { continuation ->
            val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if(continuation.isActive) continuation.resume(Unit)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if(continuation.isActive) continuation.resumeWithException(IOException("Could not combine these scenes on this device. Try exporting fewer clips.", exportException))
                }
            }).build()
            continuation.invokeOnCancellation { transformer.cancel(); output.delete() }
            try { transformer.start(composition, output.absolutePath) }
            catch(error: Exception) { if(continuation.isActive) continuation.resumeWithException(error) }
        }
    }
}
