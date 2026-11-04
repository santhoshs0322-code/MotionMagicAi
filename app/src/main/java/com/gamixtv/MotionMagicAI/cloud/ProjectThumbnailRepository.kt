package com.gamixtv.MotionMagicAI.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.gamixtv.MotionMagicAI.auth.AuthConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

internal data class CloudThumbnailSource(val assetId: String, val accountId: String, val url: String?, val state: String)

/** Private, bounded caches. URLs expire; cache identity is account + API + immutable output ID. */
internal object ProjectThumbnailRepository {
    private val downloads = Semaphore(3)
    private val writes = Mutex()
    private val images = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, source: CloudThumbnailSource): Bitmap? = withContext(Dispatchers.IO) {
        val identity = AuthConfig.fromBuildConfig().normalizedBaseUrl + "|" + source.accountId + "|" + source.assetId
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        synchronized(images) { images.get(key) }?.let { return@withContext it }
        run {
            synchronized(images) { images.get(key) }?.let { return@run it }
            val folder = File(context.cacheDir, "project-thumbnails").apply { mkdirs() }
            val file = File(folder, "$key.jpg")
            if(file.isFile && file.length() <= 256 * 1024) {
                decode(file.readBytes())?.let { bitmap ->
                    file.setLastModified(System.currentTimeMillis())
                    synchronized(images) { images.put(key, bitmap) }
                    return@run bitmap
                }
                file.delete()
            }
            if(source.state in setOf("none", "failed") && source.url == null) return@run null
            try {
                val api = CloudApi(context)
                var bytes: ByteArray? = null
                for(attempt in 0..2) {
                    try { bytes = downloads.withPermit { api.thumbnail(source.assetId, source.accountId, source.url) }; break }
                    catch(error: CloudException) {
                        if(error.status != 409 || attempt == 2) throw error
                        delay(if(attempt == 0) 2_000 else 30_000)
                    }
                }
                val data = checkNotNull(bytes)
                val bitmap = downloads.withPermit { decode(data) } ?: return@run null
                writes.withLock {
                    val temporary = File.createTempFile("preview-", ".tmp", folder)
                    try { temporary.writeBytes(data); if(!temporary.renameTo(file)) temporary.delete() }
                    finally { temporary.delete() }
                    var used = folder.listFiles()?.sumOf { it.length() } ?: 0L
                    folder.listFiles()?.sortedBy { it.lastModified() }?.forEach {
                        val size = it.length()
                        if(used > 32L * 1024 * 1024 && it != file && it.delete()) used -= size
                    }
                }
                synchronized(images) { images.put(key, bitmap) }
                bitmap
            } catch(error: CancellationException) { throw error }
            catch(_: Exception) { null }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if(bytes.size > 256 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if(bounds.outWidth !in 1..480 || bounds.outHeight !in 1..480) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
