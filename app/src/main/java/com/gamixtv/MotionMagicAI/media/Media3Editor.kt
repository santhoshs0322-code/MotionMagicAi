package com.gamixtv.MotionMagicAI.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import java.io.File

/** Compatibility entry point for existing projects and callers. */
data class EditOptions(
    val startMs: Long = 0, val endMs: Long? = null, val text: String = "", val filter: String = "None",
    val musicUri: Uri? = null, val muteOriginal: Boolean = false, val musicVolume: Float = .7f,
)

@UnstableApi
object Media3Editor {
    val supportedFilters = listOf("None", "Grayscale", "Warm")
    suspend fun export(context: Context, source: File, destination: File, options: EditOptions): File {
        require(source.canonicalFile != destination.canonicalFile) { "Export to a new file to keep the original." }
        val info = MediaAccess.inspect(context, Uri.fromFile(source))
        val end = options.endMs ?: info.durationMs
        require(end <= info.durationMs + 50) { "The trim end is past the video." }
        return StudioExporter.export(context, EditorDocument(
            clips = listOf(EditorClip(source.path, info.durationMs, options.startMs, minOf(end, info.durationMs))),
            text = options.text, filter = options.filter, music = options.musicUri?.toString(), musicVolume = options.musicVolume,
            volume = if(options.muteOriginal) 0f else 1f, height = 0), destination)
    }
}
