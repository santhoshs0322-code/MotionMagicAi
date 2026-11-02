package com.gamixtv.MotionMagicAI

import android.app.Application
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamixtv.MotionMagicAI.data.*
import com.gamixtv.MotionMagicAI.cloud.*
import com.gamixtv.MotionMagicAI.billing.PlayBilling
import com.gamixtv.MotionMagicAI.auth.AuthSession
import com.gamixtv.MotionMagicAI.auth.AuthConfig
import org.json.JSONArray
import org.json.JSONObject
import com.gamixtv.MotionMagicAI.media.DeviceVoiceEngine
import com.gamixtv.MotionMagicAI.media.LocalMediaEngine
import com.gamixtv.MotionMagicAI.media.MediaAccess
import com.gamixtv.MotionMagicAI.media.StoryExporter
import com.gamixtv.MotionMagicAI.ui.ArtRepository
import com.gamixtv.MotionMagicAI.ui.Artwork
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Screen(val title: String) {
    HOME("MotionMagic AI"), IMAGE("Image to Video"), TEXT("Text to Video"), CREATE_IMAGE("Create Image"),
    REELS("AI Reels Maker"), VOICE("AI Voice Generator"), TEMPLATES("Templates"),
    PROJECTS("My Projects"), RESULT("Video Result"),
    PROFILE("My Account"), SETTINGS("Settings"), PLANS("Plans & pricing")
}

data class WorkState(val title: String, val progress: Float = 0f, val detail: String = "")
data class CompletedJobNotice(val projectId: String, val title: String, val kind: MediaKind, val chargedCredits: Long)

class MotionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = MotionStore(app)
    private val history = mutableListOf<Screen>()
    var screen by mutableStateOf(Screen.HOME); private set
    var projects by mutableStateOf<List<Project>>(emptyList()); private set
    var settings by mutableStateOf(store.settings()); private set
    var draft by mutableStateOf(CreationDraft()); private set
    var imageDraft by mutableStateOf(CreationDraft()); private set
    private var imageDraftSave: Job? = null
    var currentPlan by mutableStateOf(PlanTier.FREE); private set
    val selectedVideoModel: VideoModelOption get() = VideoModelCatalog.find(draft.modelId)
    var voiceText by mutableStateOf(""); private set
    var activeAccountId by mutableStateOf<String?>(null); private set
    var selectedProject by mutableStateOf<Project?>(null); private set
    var selectedArt by mutableStateOf(Artwork.Garden); private set
    var photo by mutableStateOf<Bitmap?>(null); private set
    var photoName by mutableStateOf(""); private set
    var referenceVideoPath by mutableStateOf<String?>(null); private set
    var referenceVideoName by mutableStateOf(""); private set
    private var storyRoot: String? = null
    private var storyPart: Int = 0
    var failure by mutableStateOf<String?>(null); private set
    var info by mutableStateOf<Pair<String, String>?>(null); private set
    var completedJobNotice by mutableStateOf<CompletedJobNotice?>(null); private set
    private val completionQueue = ArrayDeque<CompletedJobNotice>()
    val work = MutableStateFlow<WorkState?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private var activeJob: Job? = null
    private var draftSave: Job? = null
    private val pendingOutputs = ConcurrentHashMap.newKeySet<File>()
    private fun accountHash(accountId: String): String {
        require(accountId.isNotBlank()) { "Sign in before accessing saved media." }
        return MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun projectDir(accountId: String) = File(app.filesDir, "projects/${accountHash(accountId)}").also { it.mkdirs() }
    private fun photoFile(accountId: String): File {
        return File(app.filesDir, "sources/${accountHash(accountId)}.png")
    }
    private fun createJobPreview(source: File, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Could not create a preview.")
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it)) }
        } finally { bitmap.recycle() }
    }
    private val selectedPhotoFile: File get() = photoFile(checkNotNull(activeAccountId) { "Sign in before selecting a photo." })
    private var photoRevision = 0
    private val cloud = CloudApi(app)
    private val playBilling = PlayBilling(app, ::verifyPlayPurchase, ::message)
    private val cloudPending = app.getSharedPreferences("motion_cloud_jobs", Application.MODE_PRIVATE)
    private val cloudPendingLock = Any()
    private val cloudPollLock = Mutex()
    private var cloudJobMonitor: Job? = null
    private var cloudMonitorGeneration = 0
    private val downloadFailures = mutableSetOf<String>()
    private val observedActiveJobIds = mutableSetOf<String>()
    var cloudJobs by mutableStateOf<List<CloudJob>>(emptyList()); private set
    var cloudRates by mutableStateOf<List<CloudRate>>(emptyList()); private set
    var cloudCatalogStatus by mutableStateOf("Checking model availability…"); private set
    var cloudQuote by mutableStateOf<CloudQuote?>(null); private set
    var billingReady by mutableStateOf(false); private set
    var billingBusy by mutableStateOf(false); private set
    private var billingAccountId: String? = null
    var creditProducts by mutableStateOf<List<Pair<String, Long>>>(emptyList()); private set
    var walletCredits by mutableStateOf<Long?>(null); private set
    var monthlyCredits by mutableStateOf<Long?>(null); private set
    var topupCredits by mutableStateOf<Long?>(null); private set
    var freeCredits by mutableStateOf<Long?>(null); private set
    private var cloudWork = false

    fun refreshCloudCatalog() {
        viewModelScope.launch {
            try {
                cloudRates = cloud.catalog()
                cloudCatalogStatus = if(cloudRates.isEmpty()) "Cloud models are not enabled on the server yet." else "Generation credits · review the price before you create"
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { cloudCatalogStatus = "Cannot check model availability. Reconnect and try Create again." }
        }
    }

    fun refreshBilling(recoverPurchases: Boolean = true) {
        viewModelScope.launch {
            val account = activeAccountId ?: return@launch
            try {
                val session = cloud.session()
                if (session.user.id != account || activeAccountId != account) return@launch
                val wallet = cloud.request("GET", "/wallet", session)
                if (activeAccountId != account) return@launch
                billingAccountId = wallet.getString("billing_account_id")
                walletCredits = wallet.getLong("available_credits")
                monthlyCredits = wallet.optLong("monthly_credits", 0)
                topupCredits = wallet.optLong("topup_credits", 0)
                freeCredits = wallet.optLong("free_credits", 0)
                val products = cloud.request("GET", "/billing/products", session).getJSONArray("products")
                if (activeAccountId != account) return@launch
                creditProducts = (0 until products.length()).map { index ->
                    products.getJSONObject(index).getString("sku") to products.getJSONObject(index).getLong("credits")
                }
                billingReady = true
                if (recoverPurchases) playBilling.recover()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (activeAccountId == account) billingReady = false }
        }
    }

    fun refreshWallet() {
        viewModelScope.launch {
            val account = activeAccountId ?: return@launch
            try {
                val session = cloud.session()
                if(session.user.id != account || activeAccountId != account) return@launch
                val wallet = cloud.request("GET", "/wallet", session)
                if(activeAccountId != account) return@launch
                billingAccountId = wallet.getString("billing_account_id")
                walletCredits = wallet.getLong("available_credits")
                monthlyCredits = wallet.optLong("monthly_credits", 0)
                topupCredits = wallet.optLong("topup_credits", 0)
                freeCredits = wallet.optLong("free_credits", 0)
            } catch (_: Exception) { /* Keep the last known balance; Create checks the server again. */ }
        }
    }

    fun buy(activity: Activity, productId: String) {
        if (billingBusy) return
        billingBusy = true
        viewModelScope.launch {
            try {
                val account = checkNotNull(activeAccountId) { "Sign in before purchasing." }
                val session = cloud.session()
                require(session.user.id == account) { "Your account changed. Sign in again." }
                val accountId = billingAccountId ?: cloud.request("GET", "/wallet", session).getString("billing_account_id")
                if(activeAccountId != account) { billingBusy = false; return@launch }
                billingAccountId = accountId
                playBilling.buy(activity, productId, accountId) { billingBusy = false }
            } catch (e: Exception) {
                billingBusy = false
                message(e.message ?: "Sign in and reconnect before purchasing.")
            }
        }
    }

    private fun verifyPlayPurchase(productId: String, token: String) {
        viewModelScope.launch {
            try {
                val account = checkNotNull(activeAccountId) { "Sign in to verify this purchase." }
                val session = cloud.session()
                require(session.user.id == account && activeAccountId == account) { "Your account changed. Sign in again." }
                require(productId in setOf(PlayBilling.TOPUP_500, PlayBilling.PACK_2500, PlayBilling.PACK_5000, PlayBilling.PACK_10000)) { "This credit pack is unavailable." }
                cloud.request("POST", "/billing/verify", session, JSONObject().put("sku", productId).put("purchase_token", token))
                if(activeAccountId == account) message("Credits added to your wallet.")
                if(activeAccountId == account) refreshBilling(recoverPurchases = false)
            } catch (e: Exception) { message(e.message ?: "Google Play payment completed. Tap Restore purchases after reconnecting.") }
        }
    }

    fun dismissCloudQuote() { cloudQuote = null }
    private fun cloudKey(session: AuthSession) = AuthConfig.fromBuildConfig().normalizedBaseUrl + "|" + session.user.id
    private fun pendingId(request: JSONObject): String = request.optString("key").ifBlank { request.optString("job_id") }
    private fun pendingCloudRequests(session: AuthSession): List<JSONObject> = synchronized(cloudPendingLock) {
        val raw = cloudPending.getString(cloudKey(session), null) ?: return@synchronized emptyList()
        val rows = if(raw.trimStart().startsWith("[")) JSONArray(raw) else JSONArray().put(JSONObject(raw))
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }
    private fun updateCloudPending(session: AuthSession, request: JSONObject?, requestId: String) = synchronized(cloudPendingLock) {
        require(requestId.isNotBlank()) { "Saved generation has no request ID." }
        val rows = pendingCloudRequests(session).filterNot { pendingId(it) == requestId }.toMutableList()
        if(request != null) rows.add(request)
        val edit = cloudPending.edit()
        if(rows.isEmpty()) edit.remove(cloudKey(session)) else edit.putString(cloudKey(session), JSONArray(rows).toString())
        check(edit.commit()) { "Could not save generation recovery details. Free some device storage and retry." }
    }

    fun prepareCloudGeneration() {
        if(!validateCreationInput()) return
        val image = screen == Screen.CREATE_IMAGE
        val request = if(image) imageDraft else draft
        val withVideo = !image && screen == Screen.IMAGE && request.modelId == "kling-o3-reference" && referenceVideoPath != null
        val withPhoto = !withVideo && screen != Screen.TEXT && screen != Screen.REELS && photo != null
        startWork("Checking your creation", "Preparing a price. Generation starts only after you confirm.") {
            val account = checkNotNull(activeAccountId) { "Sign in before creating with AI." }
            val session = cloud.session()
            require(session.user.id == account && activeAccountId == account) { "Your account changed. Start this creation again." }
            if(withContext(Dispatchers.IO) { pendingCloudRequests(session).count { !it.has("job_id") } } >= 3) {
                error("Three requests need recovery. Open My Projects and refresh them before starting another.")
            }
            cloudRates = cloud.catalog()
            val rate = cloudRates.firstOrNull { it.matches(request, image) }
                ?: error("This model and resolution are not enabled on the server yet. Choose an available setting or try again after setup.")
            require(request.aspect in rate.ratios) { "This model supports ${rate.ratios.joinToString()}. Change the aspect ratio in Settings." }
            require(image || request.duration in rate.durations) { "Choose a supported video duration." }
            val wallet = cloud.request("GET", "/wallet", session)
            if(activeAccountId == account) walletCredits = wallet.getLong("available_credits")
            val payload = JSONObject().put("service", rate.id).put("aspect_ratio", request.aspect)
                .put("prompt", request.prompt.trim().ifBlank { "Animate this photo with gentle natural movement." })
            if(!image) payload.put("duration", request.duration).put("generated_audio", request.generatedAudio)
            if(withPhoto) payload.put("asset_id", cloud.uploadImage(selectedPhotoFile, session, if(image) "image" else "video"))
            if(withVideo) payload.put("asset_id", cloud.uploadReferenceVideo(File(checkNotNull(referenceVideoPath)), session))
            val quote = cloud.request("POST", "/quotes", session, payload)
            cloudQuote = CloudQuote(quote.getString("id"), quote.getLong("maximum_charge_paise"), wallet.getLong("available_paise"),
                quote.getString("title"), session, request.copy(outputCount = 1), image, withPhoto,
                if(withVideo) referenceVideoPath else null, if(withVideo) storyRoot else null, if(withVideo) storyPart else 0)
        }
    }

    fun confirmCloudGeneration() {
        val quote = cloudQuote ?: return
        if(work.value != null) return
        if(cloudJobs.count { it.isGenerating } >= 3) {
            cloudQuote = null
            failure = "Three generations are already running. Wait for one to finish before confirming another."
            return
        }
        cloudQuote = null
        startWork("Starting your creation", "Your credits are reserved while the server generates your media.") {
            cloudWork = true
            try {
                val session = cloud.session()
                require(session.user.id == quote.session.user.id) { "Your account changed. Request a new price." }
                val pending = JSONObject().put("quote_id", quote.id).put("key", UUID.randomUUID().toString())
                    .put("title", quote.draft.prompt.take(55).ifBlank { quote.title }).put("image", quote.image)
                    .put("duration", if(quote.image) 0 else quote.draft.duration).put("aspect", quote.draft.aspect)
                if(quote.sourcePhoto && selectedPhotoFile.isFile) {
                    val preview = File(projectDir(session.user.id), "preview_${pending.getString("key")}.jpg")
                    val saved = withContext(Dispatchers.IO) { runCatching { createJobPreview(selectedPhotoFile, preview) }.isSuccess }
                    if(saved) pending.put("preview", preview.path).put("preview_temp", true)
                }
                quote.sourceVideoPath?.let { pending.put("preview", it).put("preview_video", true) }
                quote.storyRoot?.let { pending.put("story_root", it).put("story_part", quote.storyPart) }
                withContext(Dispatchers.IO) { updateCloudPending(session, pending, pending.getString("key")) }
                val job = try {
                    cloud.request("POST", "/jobs", session, JSONObject().put("quote_id", quote.id), pending.getString("key"))
                } catch(e: CloudException) {
                    if(e.status in listOf(400, 402, 403, 404, 409, 429)) withContext(Dispatchers.IO) {
                        updateCloudPending(session, null, pending.getString("key"))
                        if(pending.optBoolean("preview_temp")) pending.optString("preview").takeIf { it.isNotBlank() }?.let { File(it).delete() }
                    }
                    throw e
                }
                pending.put("job_id", job.getString("id"))
                withContext(Dispatchers.IO) { updateCloudPending(session, pending, pending.getString("key")) }
                observedActiveJobIds.add(job.getString("id"))
                cloudJobs = (listOf(CloudJob.from(job).copy(previewPath = pending.optString("preview").ifBlank { null }, previewVideo = pending.optBoolean("preview_video"))) + cloudJobs).distinctBy { it.id }
                refreshWallet()
                tab(Screen.PROJECTS)
                startCloudMonitor(session.user.id, restart = true)
            } finally { cloudWork = false }
        }
    }

    fun resumeCloudGeneration() = startWork("Refreshing projects", "Checking generation jobs and saved media.") {
        cloudWork = true
        try {
            val session = cloud.session()
            require(session.user.id == activeAccountId) { "Your account changed. Sign in again." }
            // A previous background download may hold the poll lock for a large file.
            cloudJobMonitor?.cancel()
            cloudJobMonitor = null
            cloudMonitorGeneration++
            val pending = withContext(Dispatchers.IO) { pendingCloudRequests(session) }
            for(request in pending) {
                if(request.has("job_id")) continue
                val id = pendingId(request)
                try {
                    val job = cloud.request("POST", "/jobs", session, JSONObject().put("quote_id", request.getString("quote_id")), request.getString("key"))
                    request.put("job_id", job.getString("id"))
                    withContext(Dispatchers.IO) { updateCloudPending(session, request, id) }
                } catch(e: CloudException) {
                    if(e.status in listOf(400, 402, 404, 409, 429)) withContext(Dispatchers.IO) { updateCloudPending(session, null, id) }
                    else throw e
                }
            }
            downloadFailures.clear()
            // Show the refreshed job list before fetching potentially large media files.
            pollCloudJobs(session.user.id, downloadOutputs = false)
            tab(Screen.PROJECTS)
            startCloudMonitor(session.user.id, restart = true, downloadReadyOnStart = true)
        } finally {
            cloudWork = false
            if(cloudJobMonitor == null) activeAccountId?.let { startCloudMonitor(it) }
        }
    }

    private fun startCloudMonitor(account: String, restart: Boolean = false, downloadReadyOnStart: Boolean = false) {
        if(cloudJobMonitor?.isActive == true && !restart) return
        cloudJobMonitor?.cancel()
        val generation = ++cloudMonitorGeneration
        cloudJobMonitor = viewModelScope.launch {
            var failures = 0
            var downloadReady = downloadReadyOnStart
            try {
                while(isActive && activeAccountId == account) {
                    val active = try {
                        val result = pollCloudJobs(account, forceDownloads = downloadReady)
                        downloadReady = false
                        failures = 0
                        result
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        failures++
                        if(failures == 1) message("Could not refresh generation status. My Projects will retry.")
                        if(failures >= 3 && cloudJobs.none { it.isActive }) break
                        true
                    }
                    if(!active) break
                    delay(30_000)
                }
            } finally { if(generation == cloudMonitorGeneration) cloudJobMonitor = null }
        }
    }

    private suspend fun pollCloudJobs(account: String, forceDownloads: Boolean = false, downloadOutputs: Boolean = true): Boolean = cloudPollLock.withLock {
        val session = cloud.session()
        if(session.user.id != account || activeAccountId != account) return@withLock false
        val rows = cloud.request("GET", "/jobs", session).getJSONArray("results")
        if(activeAccountId != account) return@withLock false
        val pinned = withContext(Dispatchers.IO) { pendingCloudRequests(session) }
        val previews = pinned.mapNotNull { request -> request.optString("job_id").takeIf { it.isNotBlank() }?.let { it to request } }.toMap()
        val recent = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            CloudJob.from(row).copy(previewPath = previews[row.getString("id")]?.optString("preview")?.ifBlank { null },
                previewVideo = previews[row.getString("id")]?.optBoolean("preview_video") ?: false)
        }
        val latestById = recent.associateByTo(LinkedHashMap()) { it.id }
        for(request in pinned) {
            val rawId = request.optString("job_id")
            if(rawId.isBlank()) continue
            val id = runCatching { UUID.fromString(rawId).toString() }.getOrNull()
            if(id == null || projects.any { it.id == "cloud_$id" && File(it.path).isFile }) {
                withContext(Dispatchers.IO) { updateCloudPending(session, null, pendingId(request)) }
                if(request.optBoolean("preview_temp")) request.optString("preview").takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { File(it).delete() } }
                continue
            }
            if(id !in latestById) {
                try { latestById[id] = CloudJob.from(cloud.request("GET", "/jobs/$id", session)).copy(
                    previewPath = previews[id]?.optString("preview")?.ifBlank { null }, previewVideo = previews[id]?.optBoolean("preview_video") ?: false) }
                catch(e: CloudException) {
                    if(e.status == 404) withContext(Dispatchers.IO) {
                        updateCloudPending(session, null, pendingId(request))
                        if(request.optBoolean("preview_temp")) request.optString("preview").takeIf { it.isNotBlank() }?.let { File(it).delete() }
                    }
                    else throw e
                }
            }
        }
        val latest = latestById.values.toList()
        observedActiveJobIds.addAll(latest.filter { it.isActive }.map { it.id })
        val newIds = latestById.keys
        cloudJobs = (latest + cloudJobs.filter { it.isGenerating && it.id !in newIds })
            .filterNot { job -> projects.any { it.id == "cloud_${job.id}" && File(it.path).isFile } }
        for(job in latest.filter { it.state == "failed" || it.state == "cancelled" }) {
            pinned.firstOrNull { it.optString("job_id") == job.id }?.let { request ->
                withContext(Dispatchers.IO) { updateCloudPending(session, null, pendingId(request)) }
                refreshWallet()
            }
            if(previews[job.id]?.optBoolean("preview_temp") == true) job.previewPath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
        }
        for(job in cloudJobs.filter { downloadOutputs && it.isReady && it.id !in downloadFailures && (forceDownloads || it.id in observedActiveJobIds) }) {
            if(activeAccountId != account) break
            try { downloadCloudJob(account, session, job) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                downloadFailures.add(job.id)
                cloudJobs = cloudJobs.map { if(it.id == job.id) it.copy(state = "succeeded", progressPercent = 90) else it }
                message("${job.title}: ready on the server, but download failed. Tap Refresh projects to retry.")
            }
        }
        cloudJobs.any { it.isGenerating }
    }

    private suspend fun downloadCloudJob(account: String, session: AuthSession, job: CloudJob) {
        val id = "cloud_${job.id}"
        val output = File(projectDir(account), "$id.media")
        val temporary = File(projectDir(account), "$id.part")
        cloudJobs = cloudJobs.map { if(it.id == job.id) it.copy(state = "downloading", progressPercent = 90) else it }
        try {
            withContext(Dispatchers.IO) { temporary.delete() }
            val mime = cloud.download(checkNotNull(job.outputAssetId), session, temporary) { transferred, total ->
                withContext(Dispatchers.Main.immediate) {
                    if(activeAccountId == account) cloudJobs = cloudJobs.map {
                        if(it.id == job.id) it.copy(progressPercent = 90 + (transferred * 9 / total).toInt().coerceIn(0, 9)) else it
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            require(activeAccountId == account) { "Your account changed. Download was stopped." }
            val image = mime.startsWith("image/")
            val duration = if(image) 0L else withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(temporary.path); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: job.duration * 1000L }
                finally { retriever.release() }
            }
            withContext(Dispatchers.IO) {
                if(output.exists() && !output.delete()) throw IOException("Could not replace an incomplete download.")
                if(!temporary.renameTo(output)) throw IOException("Could not save the downloaded creation.")
            }
            val pending = withContext(Dispatchers.IO) { pendingCloudRequests(session).firstOrNull { it.optString("job_id") == job.id } }
            val project = Project(id = id, title = job.title, path = output.path,
                kind = if(image) MediaKind.IMAGE else MediaKind.VIDEO, duration = (duration / 1000).toInt(), durationMs = duration,
                aspect = job.aspect, mimeType = mime,
                storyRoot = pending?.optString("story_root")?.ifBlank { null }, storyPart = pending?.optInt("story_part") ?: 0)
            persistProject(account, project)
            val completion = CompletedJobNotice(project.id, project.title, project.kind, job.chargedPaise)
            if(completedJobNotice == null) completedJobNotice = completion else completionQueue.addLast(completion)
            withContext(Dispatchers.IO) {
                pendingCloudRequests(session).firstOrNull { it.optString("job_id") == job.id }?.let {
                    updateCloudPending(session, null, pendingId(it))
                }
            }
            cloudJobs = cloudJobs.filterNot { it.id == job.id }
            refreshWallet()
            if(pending?.optBoolean("preview_temp") == true) job.previewPath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
            observedActiveJobIds.remove(job.id)
            autoSave(project)
            message("${job.title} saved · ${credits(job.chargedPaise)} charged")
        } finally { withContext(NonCancellable + Dispatchers.IO) { temporary.delete() } }
    }

    fun activateAccount(accountId: String?) {
        if (activeAccountId == accountId) return
        activeJob?.cancel()
        cloudJobMonitor?.cancel(); cloudJobMonitor = null; cloudMonitorGeneration++
        cloudJobs = emptyList(); downloadFailures.clear(); observedActiveJobIds.clear()
        projects = emptyList()
        completedJobNotice = null; completionQueue.clear()
        draftSave?.cancel(); imageDraftSave?.cancel()
        photoRevision++
        activeAccountId = accountId
        photo = null; photoName = ""; referenceVideoPath = null; referenceVideoName = ""; storyRoot = null; storyPart = 0; cloudQuote = null
        selectedProject = null; selectedArt = Artwork.Garden; failure = null; info = null
        currentPlan = PlanTier.FREE; billingReady = false; billingBusy = false; billingAccountId = null; creditProducts = emptyList()
        walletCredits = null; monthlyCredits = null; topupCredits = null; freeCredits = null
        history.clear(); screen = Screen.HOME
        draft = accountId?.let { store.draft("video", it) } ?: CreationDraft()
        imageDraft = accountId?.let { store.draft("image-generation", it) } ?: CreationDraft()
        voiceText = accountId?.let { store.draft("voice", it).prompt }.orEmpty()
        if (accountId != null) {
            refreshWallet()
            playBilling.recover()
            viewModelScope.launch {
                val (saved, warning) = withContext(Dispatchers.IO) { store.projects(accountId) to store.lastReadWarning }
                if(activeAccountId == accountId) {
                    projects = saved
                    if(warning != null) failure = warning
                    startCloudMonitor(accountId)
                }
            }
            val revision = photoRevision
            val file = photoFile(accountId)
            viewModelScope.launch {
                val restored = withContext(Dispatchers.IO) { if (file.isFile) BitmapFactory.decodeFile(file.path) else null }
                if (activeAccountId == accountId && photoRevision == revision && photo == null && restored != null) {
                    photo = restored; photoName = "Your photo"
                }
            }
        }
    }

    fun go(destination: Screen) {
        if (screen == destination) return
        history.add(screen)
        screen = destination
    }
    fun tab(destination: Screen) { history.clear(); screen = destination }
    fun back() { screen = if (history.isEmpty()) Screen.HOME else history.removeAt(history.lastIndex) }
    fun open(project: Project) {
        if(!ownsProject(project)) { failure = "This project does not belong to the signed-in account."; return }
        if (!MediaAccess.external(project.path) && !File(project.path).isFile) {
            failure = "This project’s file is missing. Import the original again, or remove the project from your library."
            return
        }
        selectedProject = project
        go(Screen.RESULT)
    }
    fun continueStory(project: Project) {
        if(!ownsProject(project) || project.kind != MediaKind.VIDEO || !File(project.path).isFile) {
            failure = "Open one of your saved videos to continue its story."
            return
        }
        val root = project.storyRoot ?: project.id
        val clips = projects.filter { it.id == root || it.storyRoot == root }
        val total = clips.sumOf { it.duration }
        if(total >= 60 || clips.size >= 6) {
            failure = "This story has reached one minute. Start a new story for the next chapter."
            return
        }
        if(clips.maxByOrNull { it.storyPart }?.id != project.id) {
            failure = "Continue from the latest scene to keep this story in order."
            return
        }
        storyRoot = root
        storyPart = (clips.maxOfOrNull { it.storyPart } ?: 0) + 1
        referenceVideoPath = project.path
        referenceVideoName = "Part ${storyPart - 1} reference"
        photo = null; photoName = ""
        updateDraft(draft.copy(modelId = "kling-o3-reference", resolution = "1080p", duration = if(60 - total < 10) 5 else 10,
            generatedAudio = true, aspect = project.aspect,
            prompt = "Continue this story from @Video1. Keep the characters, setting and visual style consistent. "))
        tab(Screen.IMAGE)
    }
    fun exportStory(project: Project) = startWork("Exporting your story", "Combining scenes into a shareable video on this device.") {
        require(ownsProject(project) && project.kind == MediaKind.VIDEO) { "Choose one of your saved story scenes." }
        val account = checkNotNull(activeAccountId)
        val root = project.storyRoot ?: project.id
        val clips = projects.filter { it.id == root || it.storyRoot == root }.sortedBy { it.storyPart }
        require(clips.size in 2..6 && clips.all { ownsProject(it) && File(it.path).isFile }) { "Finish at least two saved scenes before exporting a story." }
        val duration = clips.sumOf { it.duration }
        require(duration <= 60) { "Stories can be up to one minute long." }
        val output = File(projectDir(account), "story_${UUID.randomUUID()}.mp4")
        val requiredSpace = clips.sumOf { File(it.path).length() } + 32L * 1024 * 1024
        require(android.os.StatFs(output.parentFile!!.path).availableBytes > requiredSpace) {
            "Free some device storage before exporting this story. Your scenes are safe in My Projects."
        }
        pendingOutputs.add(output)
        try {
            StoryExporter.export(app, clips.map { File(it.path) }, output)
            require(activeAccountId == account) { "Your account changed. Export was stopped." }
            persistProject(account, Project(title = "${duration}s story", path = output.path, kind = MediaKind.VIDEO,
                duration = duration, durationMs = duration * 1000L, aspect = clips.first().aspect))
            pendingOutputs.remove(output)
            message("Your ${duration}s story is ready in My Projects.")
        } finally { if(output in pendingOutputs) output.delete() }
    }
    private fun ownsProject(project: Project): Boolean {
        val account = activeAccountId ?: return false
        if(projects.none { it.id == project.id && it.path == project.path }) return false
        return runCatching { File(project.path).canonicalPath.startsWith(projectDir(account).canonicalPath + File.separator) }.getOrDefault(false)
    }
    fun notice(title: String, body: String) { info = title to body }
    fun dismissNotice() { info = null }
    fun dismissCompletedJobNotice() {
        completedJobNotice = if(completionQueue.isEmpty()) null else completionQueue.removeFirst()
    }
    fun viewCompletedJob() {
        val id = completedJobNotice?.projectId ?: return
        dismissCompletedJobNotice()
        val project = projects.firstOrNull { it.id == id }
        if(project != null) open(project) else tab(Screen.PROJECTS)
    }
    fun dismissFailure() { failure = null }
    fun message(text: String) { _messages.tryEmit(text) }

    fun updateDraft(value: CreationDraft) {
        val account = activeAccountId ?: return
        draft = value
        draftSave?.cancel()
        draftSave = viewModelScope.launch {
            delay(250)
            try { withContext(Dispatchers.IO) { store.saveDraft(value, accountId = account) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e.message ?: "Could not save your draft." }
        }
    }
    fun selectVideoModel(model: VideoModelOption) {
        if(model.id != "kling-o3-reference") { referenceVideoPath = null; referenceVideoName = ""; storyRoot = null; storyPart = 0 }
        updateDraft(draft.copy(
            modelId = model.id,
            duration = draft.duration.takeIf { it in model.durations } ?: model.durations.min(),
            resolution = draft.resolution.takeIf { it in model.resolutions } ?: model.resolutions.first(),
            generatedAudio = draft.generatedAudio && model.supportsGeneratedAudio,
            aspect = if(model.id == "gemini-omni-1.1-flash" && draft.aspect == "1:1") "9:16" else draft.aspect,
        ))
    }

    fun switchCreationMode(mode: CreationMode) {
        screen = when(mode) {
            CreationMode.IMAGE_TO_VIDEO -> Screen.IMAGE
            CreationMode.TEXT_TO_VIDEO -> Screen.TEXT
            CreationMode.CREATE_IMAGE -> Screen.CREATE_IMAGE
        }
    }

    fun updateImageDraft(value: CreationDraft) {
        val account = activeAccountId ?: return
        imageDraft = value
        imageDraftSave?.cancel()
        imageDraftSave = viewModelScope.launch {
            delay(250)
            try { withContext(Dispatchers.IO) { store.saveDraft(value, "image-generation", account) } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { failure = e.message ?: "Could not save your image draft." }
        }
    }

    fun removePhoto() {
        photoRevision += 1
        photo = null
        photoName = ""
        selectedPhotoFile.delete()
    }
    fun removeReferenceVideo() {
        referenceVideoPath = null; referenceVideoName = ""; storyRoot = null; storyPart = 0
    }

    fun validateCreationInput(): Boolean {
        val input = if(screen == Screen.CREATE_IMAGE) imageDraft else draft
        val errors = buildList {
            if(screen == Screen.IMAGE && photo == null && referenceVideoPath == null) add("Add a photo or reference video.")
            if(screen == Screen.IMAGE && input.modelId == "kling-o3-reference" && referenceVideoPath == null) add("Choose a reference video for this model.")
            if(screen != Screen.IMAGE && input.prompt.isBlank()) add("Describe what you want to create.")
            if(input.prompt.length > 500) add("Keep your prompt to 500 characters or fewer.")
        }
        if(errors.isNotEmpty()) failure = errors.joinToString("\n")
        return errors.isEmpty()
    }

    fun generationValidation(): ValidationResult = CreationValidation.validateVideo(
        draft = draft,
        model = selectedVideoModel,
        plan = currentPlan,
        requirePrompt = screen == Screen.TEXT || screen == Screen.REELS,
    )

    fun showGenerationErrors(): Boolean {
        val validation = generationValidation()
        if (!validation.isValid) failure = validation.message
        return validation.isValid
    }
    fun updateVoiceText(text: String) {
        val account = activeAccountId ?: return
        voiceText = text
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.saveDraft(CreationDraft(prompt = text), "voice", account) }
                .onFailure { _messages.tryEmit("Voice draft could not be saved.") }
        }
    }
    fun useTemplate(art: Artwork, title: String, prompt: String = "A $title scene with a slow cinematic camera movement",
                    mode: CreationMode = CreationMode.IMAGE_TO_VIDEO, aspect: String = "9:16", duration: Int = 5) {
        selectedArt = art
        if(mode == CreationMode.CREATE_IMAGE) updateImageDraft(imageDraft.copy(selectedStyle = title, prompt = prompt, aspect = aspect))
        else updateDraft(draft.copy(selectedStyle = title, prompt = prompt, aspect = aspect,
            duration = duration.takeIf { it in selectedVideoModel.durations } ?: selectedVideoModel.durations.min()))
        val target = when(mode) {
            CreationMode.IMAGE_TO_VIDEO -> Screen.IMAGE
            CreationMode.TEXT_TO_VIDEO -> Screen.TEXT
            CreationMode.CREATE_IMAGE -> Screen.CREATE_IMAGE
        }
        if(screen !in listOf(Screen.IMAGE, Screen.TEXT, Screen.REELS, Screen.CREATE_IMAGE)) go(target) else switchCreationMode(mode)
    }
    fun saveSettings(value: AppSettings) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.saveSettings(value) }; settings = value }
            catch(e: Exception) { failure = e.message ?: "Settings could not be saved." }
        }
    }

    fun importPhoto(uri: Uri) = startWork("Opening photo") {
        val account = checkNotNull(activeAccountId)
        val target = photoFile(account)
        val bitmap = withContext(Dispatchers.IO) {
            val temporary = copyUri(account, uri, 20L * 1024 * 1024, ".image")
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temporary.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This file is not a supported photo. Choose a JPG, PNG or WebP image." }
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                val decoded = BitmapFactory.decodeFile(temporary.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("Could not open this photo. Try another image.")
                val orientation = runCatching { androidx.exifinterface.media.ExifInterface(temporary.path).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
                val matrix = Matrix().apply {
                    when(orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(-90f); postScale(-1f, 1f) }
                        8 -> setRotate(-90f)
                    }
                }
                val oriented = if(orientation == 1) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if(it !== decoded) decoded.recycle() }
                target.parentFile?.mkdirs()
                val staged = File(target.parentFile, "${UUID.randomUUID()}.tmp")
                try {
                    staged.outputStream().use { require(oriented.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not save this photo." } }
                    require(staged.renameTo(target)) { "Could not save this photo. Free some storage and try again." }
                } finally { staged.delete() }
                oriented
            } finally { temporary.delete() }
        }
        if (activeAccountId != account) return@startWork
        photoRevision++
        photo = bitmap
        photoName = "Your photo"
        referenceVideoPath = null; referenceVideoName = ""; storyRoot = null; storyPart = 0
        message("Photo added")
    }

    fun importReferenceVideo(uri: Uri) = startWork("Opening reference video") {
        val account = checkNotNull(activeAccountId) { "Sign in before choosing a video." }
        val target = File(app.filesDir, "sources/${accountHash(account)}.mp4")
        withContext(Dispatchers.IO) {
            val temporary = copyUri(account, uri, 200L * 1024 * 1024, ".mp4")
            try {
                val retriever = MediaMetadataRetriever()
                val duration = try {
                    retriever.setDataSource(temporary.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?: error("This video has no readable duration.")
                } finally { retriever.release() }
                require(duration in 3_000..15_000) { "Choose a reference MP4 between 3 and 15 seconds." }
                require(app.contentResolver.getType(uri) in setOf("video/mp4", "application/mp4")) { "Choose an MP4 reference video." }
                target.parentFile?.mkdirs()
                if(target.exists() && !target.delete()) error("Could not replace the previous reference video.")
                if(!temporary.renameTo(target)) error("Could not save the reference video.")
                pendingOutputs.remove(temporary)
            } finally { temporary.delete() }
        }
        if(activeAccountId != account) return@startWork
        referenceVideoPath = target.path; referenceVideoName = "Reference video"
        photo = null; photoName = ""; storyRoot = null; storyPart = 0
        updateDraft(draft.copy(modelId = "kling-o3-reference", resolution = "1080p", duration = 10, generatedAudio = true,
            prompt = draft.prompt.ifBlank { "Create the next story scene from @Video1. Keep the same characters and style." }))
        message("Reference video added")
    }

    private suspend fun copyUri(account: String, uri: Uri, maxBytes: Long, extension: String): File {
        val destination = File(projectDir(account), "${UUID.randomUUID()}$extension")
        pendingOutputs.add(destination)
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if(count < 0) break
                        total += count
                        require(total <= maxBytes) { "This file is too large. Choose a file smaller than ${maxBytes / (1024 * 1024)} MB." }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "This file is empty. Choose another file." }
                }
            } ?: error("Permission to open this file was lost. Please select it again.")
            return destination
        } catch (e: Exception) { destination.delete(); throw e }
    }

    fun generate() {
        val account = activeAccountId ?: return
        val request = draft
        val validation = generationValidation()
        if (!validation.isValid) { failure = validation.message; return }
        val source = photo ?: ArtRepository.bitmap(app, selectedArt)
        startWork("Creating your preview", "Animating the photo on this device. No AI credits are used.") {
            val output = File(projectDir(account), "motion_${UUID.randomUUID()}.mp4")
            pendingOutputs.add(output)
            val bitmap = withContext(Dispatchers.Default) { styleBitmap(source, request.selectedStyle) }
            try {
                coroutineScope {
                    // EGL stays on its rendering thread. Only bounded progress messages cross
                    // to Main; suspending the renderer could move its EGL context to another thread.
                    val updates = Channel<Float>(Channel.CONFLATED)
                    val observer = launch(Dispatchers.Main.immediate) {
                        for (progress in updates) {
                            work.value = WorkState("Creating your preview", progress, "Rendering a local photo animation · ${request.duration}s")
                        }
                    }
                    try {
                        LocalMediaEngine.createVideo(app, bitmap, request.duration, request.aspect, output) { updates.trySend(it) }
                    } finally { updates.close(); observer.join() }
                }
                val project = Project(title = request.prompt.trim().take(55).ifBlank { "${request.selectedStyle} photo animation" },
                    path = output.path, kind = MediaKind.VIDEO, duration = request.duration, aspect = request.aspect, style = request.selectedStyle)
                try { persistProject(account, project) } catch(e: Exception) { output.delete(); throw e }
                if(activeAccountId != account) return@startWork
                selectedProject = project
                go(Screen.RESULT)
                autoSave(project)
            } finally { if (bitmap !== source) bitmap.recycle() }
        }
    }

    fun generateVoice(style: String) {
        val account = activeAccountId ?: return
        if (voiceText.isBlank() || voiceText.length > 500) { failure = "Enter between 1 and 500 characters for your voiceover."; return }
        val text = voiceText.trim()
        startWork("Creating voice sample", "Using your device’s speech engine. This is a test voice.") {
            val output = File(projectDir(account), "voice_${UUID.randomUUID()}.wav")
            pendingOutputs.add(output)
            DeviceVoiceEngine.synthesize(app, text, style, output)
            val project = Project(title = text.take(55), path = output.path, kind = MediaKind.AUDIO, style = style)
            try { persistProject(account, project) } catch(e: Exception) { output.delete(); throw e }
            if(activeAccountId != account) return@startWork
            selectedProject = project
            go(Screen.RESULT)
        }
    }

    private suspend fun persistProject(account: String, project: Project) {
        require(activeAccountId == account) { "Your account changed. Sign in again." }
        require(File(project.path).canonicalPath.startsWith(projectDir(account).canonicalPath + File.separator)) { "Project storage does not belong to this account." }
        withContext(Dispatchers.IO) { store.upsertProject(account, project) }
        pendingOutputs.remove(File(project.path))
        val ownProjects = withContext(Dispatchers.IO) { store.projects(account) }
        if(activeAccountId != account) throw CancellationException("Account changed while saving a project.")
        projects = ownProjects
        if(settings.notifications) runCatching {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("creations", "Finished creations", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(project.id.hashCode(), NotificationCompat.Builder(app, "creations")
                .setSmallIcon(android.R.drawable.ic_menu_slideshow).setContentTitle("Your creation is ready")
                .setContentText(project.title).setContentIntent(open).setAutoCancel(true).build())
        }
    }
    private suspend fun autoSave(project: Project) {
        if (ownsProject(project) && settings.autoSave && Build.VERSION.SDK_INT >= 29 && project.kind == MediaKind.VIDEO) {
            try { LocalMediaEngine.exportToGallery(app, MediaAccess.uri(project.path), "MotionMagic_${project.id.take(8)}.mp4", project.mimeType); message("Saved to your gallery") }
            catch(e: Exception) { message("Project saved. Gallery export failed; use Download to try again.") }
        }
    }
    fun exportToGallery(project: Project) = startWork("Saving to gallery") {
        require(ownsProject(project)) { "This project does not belong to the signed-in account." }
        LocalMediaEngine.exportToGallery(app, MediaAccess.uri(project.path), "MotionMagic_${project.id.take(8)}.mp4", project.mimeType)
        message("Saved to Movies / MotionMagic AI")
    }
    fun saveDocument(project: Project, uri: Uri) = startWork("Saving file") {
        require(ownsProject(project)) { "This project does not belong to the signed-in account." }
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri)?.use { out -> MediaAccess.open(app, MediaAccess.uri(project.path)).use { MediaAccess.copy(it, out) } }
                ?: throw IOException("Could not write to the selected location.")
        }
        message("File saved")
    }
    fun share(project: Project): Intent? = try {
        require(ownsProject(project)) { "This project does not belong to the signed-in account." }
        require(MediaAccess.external(project.path) || File(project.path).isFile) { "This media file is no longer available." }
        val uri = if(MediaAccess.external(project.path)) Uri.parse(project.path) else FileProvider.getUriForFile(app, "${app.packageName}.files", File(project.path))
        LocalMediaEngine.shareIntent(uri).apply { type = project.mimeType }
    } catch(e: Exception) { failure = e.message ?: "Could not share this file."; null }

    fun delete(project: Project) {
        val account = activeAccountId ?: return
        if(projects.none { it.id == project.id && it.path == project.path }) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(activeAccountId == account) { "Your account changed. Sign in again." }
                    require(store.projects(account).any { it.id == project.id && it.path == project.path }) { "Project does not belong to this account." }
                    val file = File(project.path)
                    if(!MediaAccess.external(project.path)) {
                    require(file.canonicalPath.startsWith(projectDir(account).canonicalPath + File.separator)) { "This file is outside this account's project folder." }
                    if(file.exists() && !file.delete()) throw IOException("Could not delete this file. Close its player and try again.")
                    }
                    store.deleteProject(account, project.id)
                }
                val ownProjects = withContext(Dispatchers.IO) { store.projects(account) }
                if(activeAccountId != account) return@launch
                projects = ownProjects
                if (selectedProject?.id == project.id) { selectedProject = null; tab(Screen.PROJECTS) }
                message("Project deleted. Exported gallery copies are kept.")
            } catch(e: Exception) { failure = e.message ?: "Could not delete the project." }
        }
    }

    fun cancelWork() {
        val submitted = cloudWork
        activeJob?.cancel()
        message(if(submitted) "Progress closed. Submitted generations continue on the server; open My Projects and refresh later." else "Operation cancelled.")
    }

    private fun startWork(title: String, detail: String = "", action: suspend () -> Unit) {
        if (work.value != null) return
        work.value = WorkState(title, detail = detail)
        activeJob = viewModelScope.launch {
            try { action() }
            catch(e: TimeoutCancellationException) { failure = "This operation took too long. Try a shorter clip or remove some effects, then retry." }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { failure = friendlyError(e) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { pendingOutputs.forEach { it.delete() }; pendingOutputs.clear() }
                work.value = null; activeJob = null
            }
        }
    }

    private fun friendlyError(error: Exception): String = when(error) {
        is SecurityException -> "File permission was denied or expired. Select the file again and allow access."
        else -> error.message?.take(300) ?: "Something went wrong. Your existing projects are safe. Please try again."
    }

    private fun styleBitmap(source: Bitmap, style: String): Bitmap {
        if (style == "Realistic") return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix()
        when(style) {
            "Cinematic", "Love", "Love Story" -> matrix.set(floatArrayOf(1.12f,0f,0f,0f,8f, 0f,1.02f,0f,0f,2f, 0f,0f,.88f,0f,0f, 0f,0f,0f,1f,0f))
            "Anime", "3D Cartoon", "Fantasy" -> matrix.setSaturation(1.55f)
            else -> matrix.setSaturation(1.15f)
        }
        Canvas(result).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return result
    }
}
