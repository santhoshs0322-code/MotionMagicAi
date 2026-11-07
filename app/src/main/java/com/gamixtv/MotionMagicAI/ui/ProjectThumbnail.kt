package com.gamixtv.MotionMagicAI.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import android.content.Context
import java.security.MessageDigest
import com.gamixtv.MotionMagicAI.auth.AuthConfig
import androidx.compose.ui.platform.LocalContext
import com.gamixtv.MotionMagicAI.cloud.CloudThumbnailSource
import com.gamixtv.MotionMagicAI.cloud.ProjectThumbnailRepository

private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
private val localPreviewDecoders = Semaphore(2)

/** Decode one small thumbnail only while its lazy-list card is visible. */
@Composable
internal fun ProjectThumbnail(path: String?, video: Boolean, size: Dp = 64.dp, modifier: Modifier = Modifier.size(size), remote: CloudThumbnailSource? = null, obscured: Boolean = false) {
    val context = LocalContext.current
    val bitmap = produceState<Bitmap?>(null, path, video, remote) {
        value = null
        value = try {
            if(remote != null) ProjectThumbnailRepository.load(context, remote)
            else withContext(Dispatchers.IO) { localPreviewDecoders.withPermit { decodeThumbnail(path, video) } }
        } catch(error: CancellationException) { throw error }
        catch(_: Exception) { null }
    }.value
    // Downsampling before rendering also obscures previews on Android versions without RenderEffect.
    val display = remember(bitmap, obscured) { bitmap?.let { if(obscured) obscurePreview(it) else it } }
    Box(
        modifier.clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFB522DF), Color(0xFF5444FF), Color(0xFF13B9F0)))),
        contentAlignment = Alignment.Center,
    ) {
        if (display != null) Image(display.asImageBitmap(), null, Modifier.fillMaxSize().then(if(obscured) Modifier.blur(18.dp) else Modifier), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        if(!obscured && (video || bitmap == null)) Icon(if (video) Icons.Rounded.PlayCircle else Icons.Rounded.AutoAwesome, null,
            tint = Color.White, modifier = Modifier.size(if (bitmap == null) 30.dp else 25.dp))
    }
}

private fun obscurePreview(bitmap: Bitmap): Bitmap {
    val edge = maxOf(bitmap.width, bitmap.height)
    if(edge <= 12) return bitmap
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * 12 / edge).coerceAtLeast(1),
        (bitmap.height * 12 / edge).coerceAtLeast(1), true)
}

/** Retain only a tiny blurred source preview, never a rejected provider output or the full upload. */
internal object BlockedPreviewStore {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun path(context: Context, account: String, job: String): String =
        File(context.cacheDir, "blocked-previews/${hash(AuthConfig.fromBuildConfig().normalizedBaseUrl + "|" + account)}/${hash(job)}.jpg").path

    suspend fun save(context: Context, account: String, job: String, source: String?, video: Boolean): String? = withContext(Dispatchers.IO) {
        val target = File(path(context, account, job))
        if(target.isFile) return@withContext target.path
        val bitmap = localPreviewDecoders.withPermit { decodeThumbnail(source, video) } ?: return@withContext null
        val small = obscurePreview(bitmap)
        val folder = checkNotNull(target.parentFile).apply { mkdirs() }
        val temporary = File.createTempFile("preview-", ".tmp", folder)
        try {
            temporary.outputStream().use { check(small.compress(Bitmap.CompressFormat.JPEG, 75, it)) }
            check(temporary.renameTo(target))
            // Each image is at most 12 pixels on its longest edge; cap disk usage per account too.
            folder.listFiles()?.filter { it.extension == "jpg" && it != target }?.sortedByDescending { it.lastModified() }?.drop(127)?.forEach { it.delete() }
            target.path
        } finally {
            temporary.delete()
            if(small !== bitmap) small.recycle()
        }
    }
}

private fun decodeThumbnail(path: String?, video: Boolean): Bitmap? {
    if (path.isNullOrBlank() || !File(path).isFile) return null
    val key = "$path:${File(path).lastModified()}:$video"
    synchronized(thumbnailCache) { thumbnailCache.get(key) }?.let { return it }
    if (video) {
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(path)
            if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
            else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                val scale = 320f / maxOf(frame.width, frame.height)
                val scaled = Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== frame) frame.recycle()
                scaled
            }
        } finally { retriever.release() }
        if (frame != null) synchronized(thumbnailCache) { thumbnailCache.put(key, frame) }
        return frame
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > 640) sample *= 2
    val image = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    if (image != null) synchronized(thumbnailCache) { thumbnailCache.put(key, image) }
    return image
}
