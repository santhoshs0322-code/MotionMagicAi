package com.gamixtv.MotionMagicAI.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.gamixtv.MotionMagicAI.data.MediaKind
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*

data class SourceInfo(val uri: Uri, val name: String, val durationMs: Long, val width: Int,
    val height: Int, val hasVideo: Boolean, val hasAudio: Boolean, val size: Long, val mime: String)

object MediaAccess {
    fun uri(path: String): Uri = if (path.startsWith("content://") || path.startsWith("file://")) Uri.parse(path) else Uri.fromFile(File(path))
    fun external(path: String) = path.startsWith("content://")
    fun open(context: Context, source: Uri): InputStream = context.contentResolver.openInputStream(source)
        ?: error("This file is unavailable. Select it again.")

    suspend fun inspect(context: Context, source: Uri): SourceInfo = withContext(Dispatchers.IO) {
        require(source.scheme in listOf("content", "file")) { "Choose a local media document." }
        var name = source.lastPathSegment?.substringAfterLast('/') ?: "Imported media"
        var size = -1L
        if (source.scheme == "content") runCatching {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) }
            }
        } else size = File(source.path!!).length()
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, source)
            fun value(key: Int) = reader.extractMetadata(key)
            val duration = value(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0) { "This file is empty, protected or unsupported. Choose another media file." }
            var width = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) { val old = width; width = height; height = old }
            SourceInfo(source, name.take(160), duration, width, height,
                value(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                value(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes", size,
                context.contentResolver.getType(source) ?: value(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "application/octet-stream")
        } finally { reader.release() }
    }

    /** Persist access instead of copying GB-sized files. Non-persistable providers use bounded streaming. */
    suspend fun import(context: Context, source: Uri, kind: MediaKind): SourceInfo = withContext(Dispatchers.IO) {
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.contentResolver.persistedUriPermissions.any { it.uri == source && it.isReadPermission }
        }.getOrDefault(false)
        if (persisted) return@withContext inspect(context, source).also { validate(it, kind) }
        val folder = File(context.filesDir, "projects").apply { mkdirs() }
        val output = File(folder, "import_${UUID.randomUUID()}.media")
        var originalName = source.lastPathSegment?.substringAfterLast('/') ?: "Imported media"
        if(source.scheme == "content") runCatching {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if(it.moveToFirst()) originalName = it.getString(0) ?: originalName
            }
        }
        try {
            open(context, source).use { input -> output.outputStream().use { copy(input, it, folder) } }
            inspect(context, Uri.fromFile(output)).copy(name = originalName.take(160)).also { validate(it, kind) }
        } catch (e: Throwable) { output.delete(); throw e }
    }
    private fun validate(info: SourceInfo, kind: MediaKind) {
        require(if (kind == MediaKind.VIDEO) info.hasVideo else info.hasAudio) {
            if (kind == MediaKind.VIDEO) "This file has no video track." else "This file has no audio track."
        }
    }
    suspend fun copy(input: InputStream, output: OutputStream, storage: File? = null) {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        var nextSpaceCheck = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (storage != null && count >= nextSpaceCheck) {
                require(StatFs(storage.path).availableBytes > 16 * 1024 * 1024) { "Not enough storage. Free some space and retry." }
                nextSpaceCheck = count + 4 * 1024 * 1024
            }
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read); count += read
        }
        require(count > 0) { "The selected file is empty." }
        output.flush()
    }
}
