package com.gamixtv.MotionMagicAI.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val studioArtwork = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}
private val studioDecode = Mutex()

/** Bundled, bounded images shared by all cards. Decode off the UI thread, without network IO. */
@Composable
internal fun StudioArtwork(name: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null, description: String? = null) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(synchronized(studioArtwork) { studioArtwork.get(name) }, name) {
        value = synchronized(studioArtwork) { studioArtwork.get(name) } ?: withContext(Dispatchers.IO) {
            studioDecode.withLock {
                synchronized(studioArtwork) { studioArtwork.get(name) } ?: context.assets.open("studio/$name").use { BitmapFactory.decodeStream(it) }?.also {
                    synchronized(studioArtwork) { studioArtwork.put(name, it) }
                }
            }
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), description, modifier, contentScale = contentScale, colorFilter = colorFilter) }
}
