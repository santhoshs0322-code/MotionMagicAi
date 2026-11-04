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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import androidx.compose.ui.platform.LocalContext
import com.gamixtv.MotionMagicAI.cloud.CloudThumbnailSource
import com.gamixtv.MotionMagicAI.cloud.ProjectThumbnailRepository

private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
private val localPreviewDecoders = Semaphore(2)

/** Decode one small thumbnail only while its lazy-list card is visible. */
@Composable
internal fun ProjectThumbnail(path: String?, video: Boolean, size: Dp = 64.dp, modifier: Modifier = Modifier.size(size), remote: CloudThumbnailSource? = null) {
    val context = LocalContext.current
    val bitmap = produceState<Bitmap?>(null, path, video, remote) {
        value = null
        value = try {
            if(remote != null) ProjectThumbnailRepository.load(context, remote)
            else withContext(Dispatchers.IO) { localPreviewDecoders.withPermit { decodeThumbnail(path, video) } }
        } catch(error: CancellationException) { throw error }
        catch(_: Exception) { null }
    }.value
    Box(
        modifier.clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFB522DF), Color(0xFF5444FF), Color(0xFF13B9F0)))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if(video || bitmap == null) Icon(if (video) Icons.Rounded.PlayCircle else Icons.Rounded.AutoAwesome, null,
            tint = Color.White, modifier = Modifier.size(if (bitmap == null) 30.dp else 25.dp))
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
