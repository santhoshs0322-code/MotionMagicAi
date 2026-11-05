package com.gamixtv.MotionMagicAI.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.gamixtv.MotionMagicAI.auth.AuthConfig
import com.gamixtv.MotionMagicAI.data.CreationMode
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private val posters = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}
private val posterLoads = Semaphore(3)

@Composable
fun ShowcasePoster(template: Template, modifier: Modifier = Modifier, description: String = template.name, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val key = "${template.remoteId}:${template.mediaRevision}"
    val bitmap = produceState<Bitmap?>(synchronized(posters) { posters.get(key) }, key, template.posterUrl) {
        value = synchronized(posters) { posters.get(key) }
        if (value == null) {
            value = try { withContext(Dispatchers.IO) { posterLoads.withPermit {
                val asset = ImageTemplateCatalogue.bundledPath(context, template.bundledPoster, template.bundledPosterHash)
                if(asset != null) context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
                else template.posterUrl?.let { fetchPoster(it, if(template.remoteId?.startsWith("banner:") == true) 1280 else 640) }
            } } } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
            value?.let { image -> synchronized(posters) { posters.put(key, image) } }
        }
    }.value
    Box(modifier) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = contentScale,
            alignment = if(template.mode == CreationMode.CREATE_IMAGE) Alignment.TopCenter else Alignment.Center)
        else Art(template.art, Modifier.fillMaxSize(), description)
    }
}

private fun fetchPoster(address: String, maxEdge: Int): Bitmap? {
    val origin = URL(AuthConfig.fromBuildConfig().normalizedBaseUrl)
    val url = URL(URL(origin.toString() + "/"), address)
    val ownArtwork = url.host.equals(origin.host, true) && url.port == origin.port && url.path.startsWith("/api/v1/templates/artwork/")
    require(url.protocol == "https" && url.userInfo == null && (ownArtwork || (url.host.lowercase().endsWith(".amazonaws.com") && url.port in listOf(-1, 443))))
    val connection = url.openConnection() as HttpsURLConnection
    connection.connectTimeout = 8000
    connection.readTimeout = 10000
    connection.instanceFollowRedirects = false
    try {
        if (connection.responseCode != 200) return null
        val bytes = ByteArrayOutputStream()
        val deadline = System.nanoTime() + 15_000_000_000L
        connection.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > 5 * 1024 * 1024 || System.nanoTime() > deadline) return null
                bytes.write(buffer, 0, count)
            }
        }
        val data = bytes.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } finally { connection.disconnect() }
}

/** Exactly one player exists, only while its preview is on screen. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun ShowcaseVideo(template: Template, modifier: Modifier = Modifier) {
    val url = template.videoUrl ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = androidx.compose.runtime.remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
            if (event == Lifecycle.Event.ON_START) player.play()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply {
        this.player = player
        useController = true
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    } }, update = { it.player = player }, modifier = modifier)
}
