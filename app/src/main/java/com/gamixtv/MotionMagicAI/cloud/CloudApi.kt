package com.gamixtv.MotionMagicAI.cloud

import android.content.Context
import com.gamixtv.MotionMagicAI.auth.AuthConfig
import com.gamixtv.MotionMagicAI.auth.AuthSession
import com.gamixtv.MotionMagicAI.auth.EncryptedAuthSessionStore
import com.gamixtv.MotionMagicAI.auth.MotionMagicApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Authenticated Django API only. Provider credentials never enter the Android application. */
class CloudApi(context: Context, private val config: AuthConfig = AuthConfig.fromBuildConfig()) {
    companion object { private val renewalLock = Mutex() }
    private val sessions = EncryptedAuthSessionStore(context, config)
    suspend fun session(): AuthSession = renewalLock.withLock {
        val saved = sessions.read() ?: throw CloudException(401, "Sign in to create with AI.")
        if (saved.expiresAtEpochSeconds > System.currentTimeMillis() / 1000 + 3600) return@withLock saved
        val renewed = MotionMagicApi(config).refresh(saved.refreshToken)
        if (renewed.user.id != saved.user.id) throw CloudException(401, "Account changed. Sign in again.")
        sessions.write(renewed)
        renewed
    }

    suspend fun catalog(): List<CloudRate> {
        val rows = request("GET", "/capabilities").getJSONArray("cloud")
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i); val settings = row.getJSONObject("config")
            val ratios = settings.optJSONArray("aspect_ratios")
            val durations = settings.optJSONArray("durations")
            CloudRate(row.getString("id"), row.getString("title"), settings.optString("app_model_id"), row.getString("kind"),
                settings.optString("image_size", settings.optString("resolution")),
                (0 until (ratios?.length() ?: 0)).map { ratios!!.getString(it) },
                (0 until (durations?.length() ?: 0)).map { durations!!.getInt(it) },
                if(settings.optString("provider") == "fal" && settings.has("generated_audio")) settings.getBoolean("generated_audio") else null,
                settings.optString("image_aspect_ratio") == "source", row.optLong("minimum_charge_paise"), row.optInt("minimum_units", 1))
        }
    }

    suspend fun request(method: String, path: String, session: AuthSession? = null, body: JSONObject? = null, key: String? = null): JSONObject = withContext(Dispatchers.IO) {
        config.validationError()?.let { throw CloudException(0, it) }
        require(path.startsWith("/") && !path.contains(".."))
        val connection = connection(config.normalizedBaseUrl + "/api/v1" + path)
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            session?.let { connection.setRequestProperty("Authorization", "Bearer ${it.token}") }
            key?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if(body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if(status !in 200..299) {
                val message = runCatching { connection.errorStream?.use { JSONObject(readBounded(it, 32 * 1024)).optString("message") } }.getOrNull()
                throw CloudException(status, when(status) {
                    401 -> "Your session expired. Sign in from My Account and check your generation again."
                    402 -> "You do not have enough generation credits. No new generation was started."
                    429 -> "Generation capacity is busy. Wait a moment and try again."
                    else -> message?.take(250)?.takeIf { it.isNotBlank() } ?: "The generation server is unavailable. Please retry."
                })
            }
            val result = connection.inputStream.use { JSONObject(readBounded(it, 512 * 1024)) }
            if(!result.optBoolean("ok")) throw CloudException(0, "The server could not complete this request.")
            result
        } catch(e: IOException) { throw CloudException(0, "Connection interrupted. Use Check cloud generation to recover any submitted job.") }
        finally { connection.disconnect() }
    }

    suspend fun uploadImage(file: File, session: AuthSession): String {
        require(file.length() in 1..10L * 1024 * 1024) { "Choose a photo smaller than 10 MB." }
        val prepared = request("POST", "/assets", session, JSONObject().put("mime", "image/png").put("size", file.length()))
        val id = prepared.getJSONObject("asset").getString("id")
        val upload = prepared.getJSONObject("upload")
        withContext(Dispatchers.IO) {
            val boundary = "MotionMagic" + UUID.randomUUID().toString().replace("-", "")
            val fields = upload.getJSONObject("fields")
            val prefix = buildString {
                fields.keys().forEach { name ->
                    require(!name.contains('"') && !name.contains('\r') && !name.contains('\n'))
                    append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n${fields.getString(name)}\r\n")
                }
                append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"source.png\"\r\nContent-Type: image/png\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            val suffix = "\r\n--$boundary--\r\n".toByteArray()
            val connection = storageConnection(upload.getString("url"))
            try {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.setFixedLengthStreamingMode(prefix.size + file.length() + suffix.size)
                connection.outputStream.use { output ->
                    output.write(prefix)
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if(count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.write(suffix)
                }
                if(connection.responseCode !in 200..299) throw CloudException(0, "Photo upload failed. Try again.")
            } catch(e: IOException) { throw CloudException(0, "Photo upload was interrupted. Please retry.") }
            finally { connection.disconnect() }
        }
        request("POST", "/assets/$id", session, JSONObject())
        return id
    }

    suspend fun download(assetId: String, session: AuthSession, destination: File): String {
        val result = request("GET", "/assets/$assetId", session)
        val asset = result.getJSONObject("asset")
        val mime = asset.getString("mime"); val size = asset.getLong("size")
        require(mime in listOf("video/mp4", "image/png", "image/jpeg", "image/webp") && size in 1..512L * 1024 * 1024) { "Unsupported generated media." }
        withContext(Dispatchers.IO) {
            require(destination.parentFile!!.usableSpace > size + 20 * 1024 * 1024) { "Free some storage before downloading your creation." }
            val connection = storageConnection(result.getString("url"))
            try {
                if(connection.responseCode != 200) throw CloudException(0, "Download expired. Check your cloud generation to try again.")
                connection.inputStream.use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if(count < 0) break
                        total += count; require(total <= size) { "Downloaded media exceeds its declared size." }
                        output.write(buffer, 0, count)
                    }
                    require(total == size) { "Download was incomplete. Check your cloud generation to retry." }
                } }
            } catch(e: IOException) { throw CloudException(0, "Download interrupted. Your creation is saved on the server; check it again to download.") }
            finally { connection.disconnect() }
        }
        return mime
    }

    private fun connection(address: String): HttpsURLConnection {
        val url = URL(address)
        require(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443))
        return (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = false; useCaches = false
        }
    }
    private fun storageConnection(address: String): HttpsURLConnection {
        val host = URL(address).host.lowercase()
        require(host.endsWith(".amazonaws.com")) { "Unsupported media storage address." }
        return connection(address) // Never attach app or provider authorization to S3.
    }
    private suspend fun readBounded(input: java.io.InputStream, limit: Int): String {
        val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer); if(count < 0) break
            require(bytes.size() + count <= limit) { "Server response exceeded its limit." }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString("UTF-8")
    }
}
