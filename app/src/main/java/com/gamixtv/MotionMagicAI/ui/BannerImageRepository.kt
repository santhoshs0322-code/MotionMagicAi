package com.gamixtv.MotionMagicAI.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import com.gamixtv.MotionMagicAI.auth.AuthConfig
import com.gamixtv.MotionMagicAI.data.HomeBanner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal interface BannerImageLoader {
    fun peek(banner: HomeBanner): Bitmap?
    suspend fun load(banner: HomeBanner): Bitmap?
}

/** Public marketing images only. Signed URL renewal and text/timing edits do not invalidate bytes. */
internal class BannerImageRepository(
    context: Context,
    private val origin: String = AuthConfig.fromBuildConfig().normalizedBaseUrl,
    private val directory: File = File(context.cacheDir, "home-banners-v1"),
    private val download: suspend (String) -> ByteArray? = { fetchBanner(it) },
) : BannerImageLoader {
    private val app = context.applicationContext
    private val locks = Array(16) { Mutex() }
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failures = LruCache<String, Pair<String?, Long>>(40)

    override fun peek(banner: HomeBanner): Bitmap? = synchronized(memory) { memory.get(key(banner)) }

    private fun key(banner: HomeBanner): String {
        val image = if(!banner.bundledFile.isNullOrBlank() && !banner.bundledHash.isNullOrBlank())
            "bundled:${banner.bundledFile}:${banner.bundledHash}" else banner.imageUrl?.let {
                runCatching { URL(URL("$origin/"), it).let { url -> "${url.protocol}://${url.authority}${url.path}" } }.getOrNull()
            } ?: "missing:${banner.id}:${banner.revision}"
        return MessageDigest.getInstance("SHA-256").digest("$origin|$image".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override suspend fun load(banner: HomeBanner): Bitmap? = withContext(Dispatchers.IO) {
        val key = key(banner)
        peek(banner)?.let { return@withContext it }
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            peek(banner)?.let { return@withLock it }
            val file = File(directory, "$key.img")
            if(file.isFile && file.length() in 1..MAX_BYTES.toLong()) {
                runCatching { decode(file.readBytes()) }.getOrNull()?.let {
                    file.setLastModified(System.currentTimeMillis())
                    synchronized(memory) { memory.put(key, it) }
                    return@withLock it
                }
                file.delete()
            }
            val failed = synchronized(failures) { failures.get(key) }
            if(failed != null && failed.first == banner.imageUrl && SystemClock.elapsedRealtime() - failed.second < 15_000) return@withLock null
            try {
                val bytes = downloads.withPermit {
                    val bundled = ImageTemplateCatalogue.bundledPath(app, banner.bundledFile, banner.bundledHash)
                    if(bundled != null) app.assets.open(bundled).use { it.readBytes() }
                    else banner.imageUrl?.let { download(allowedUrl(it).toString()) }
                } ?: throw IOException("Banner is not available")
                currentCoroutineContext().ensureActive()
                val bitmap = decode(bytes) ?: throw IOException("Invalid banner image")
                // Cache writes can fail on a full device; the fetched image can still be displayed.
                try {
                    writes.withLock {
                        check(directory.isDirectory || directory.mkdirs())
                        val temporary = File.createTempFile("banner-", ".tmp", directory)
                        try {
                            temporary.writeBytes(bytes)
                            currentCoroutineContext().ensureActive()
                            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            var used = directory.listFiles()?.filter { it.extension == "img" }?.sumOf { it.length() } ?: 0L
                            directory.listFiles()?.filter { it.extension == "img" }?.sortedBy { it.lastModified() }?.forEach {
                                val size = it.length()
                                if(used > 32L * 1024 * 1024 && it != file && it.delete()) used -= size
                            }
                        } finally { temporary.delete() }
                    }
                } catch(error: CancellationException) { throw error }
                catch(_: Exception) { /* Memory caching remains available. */ }
                synchronized(memory) { memory.put(key, bitmap) }
                synchronized(failures) { failures.remove(key) }
                bitmap
            } catch(error: CancellationException) { throw error }
            catch(_: Exception) {
                synchronized(failures) { failures.put(key, banner.imageUrl to SystemClock.elapsedRealtime()) }
                null
            }
        }
    }

    private fun allowedUrl(address: String): URL {
        val base = URL(origin)
        val url = URL(URL("$origin/"), address)
        val artwork = url.host.equals(base.host, true) && url.port == base.port && url.path.startsWith("/api/v1/templates/artwork/")
        val catalog = url.host.lowercase().endsWith(".amazonaws.com") && url.port in listOf(-1, 443) && url.path.contains("/catalog/templates/")
        require(url.protocol == "https" && url.userInfo == null && (artwork || catalog))
        return url
    }

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        val downloads = Semaphore(2)
        val writes = Mutex()

        fun decode(bytes: ByteArray): Bitmap? {
            if(bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if(bounds.outWidth !in 1..16000 || bounds.outHeight !in 1..16000 || bounds.outWidth.toLong() * bounds.outHeight > 24_000_000) return null
            var sample = 1
            while(maxOf(bounds.outWidth, bounds.outHeight) / sample > 1500 ||
                bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 750_000) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        suspend fun fetchBanner(address: String): ByteArray? {
            val connection = URL(address).openConnection() as HttpsURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            try {
                if(connection.responseCode != 200 || connection.contentLengthLong > MAX_BYTES ||
                    connection.contentType?.substringBefore(';') !in listOf("image/jpeg", "image/png", "image/webp")) return null
                val bytes = ByteArrayOutputStream()
                val deadline = SystemClock.elapsedRealtime() + 15_000
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if(count < 0) break
                        if(bytes.size() + count > MAX_BYTES || SystemClock.elapsedRealtime() > deadline) return null
                        bytes.write(buffer, 0, count)
                    }
                }
                return bytes.toByteArray()
            } finally { connection.disconnect() }
        }
    }
}

internal object HomeBannerImages {
    @Volatile private var repository: BannerImageRepository? = null
    fun get(context: Context): BannerImageRepository = repository ?: synchronized(this) {
        repository ?: BannerImageRepository(context).also { repository = it }
    }
}
