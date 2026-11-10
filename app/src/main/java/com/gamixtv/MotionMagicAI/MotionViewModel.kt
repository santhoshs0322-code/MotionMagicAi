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
import com.gamixtv.MotionMagicAI.billing.playAccountId
import com.gamixtv.MotionMagicAI.auth.AuthSession
import com.gamixtv.MotionMagicAI.auth.AuthConfig
import org.json.JSONArray
import org.json.JSONObject
import com.gamixtv.MotionMagicAI.media.DeviceVoiceEngine
import com.gamixtv.MotionMagicAI.media.LocalMediaEngine
import com.gamixtv.MotionMagicAI.media.MediaAccess
import com.gamixtv.MotionMagicAI.ui.ArtRepository
import com.gamixtv.MotionMagicAI.ui.Artwork
import com.gamixtv.MotionMagicAI.ui.Template
import com.gamixtv.MotionMagicAI.ui.BlockedPreviewStore
import java.io.File
import java.io.IOException
import java.net.URLEncoder
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
    HOME("MotionMagic AI"), IMAGE("Image to Video"), STORY("Continue Story"), LONG_STORY("Long Story"), TEXT("Text to Video"), CREATE_IMAGE("Create Image"),
    REELS("AI Reels Maker"), VOICE("AI Voice Generator"), TEMPLATES("Templates"),
    PROJECTS("My Projects"), RESULT("Video Result"),
    PROFILE("My Account"), SETTINGS("Settings"), CREATION_SETTINGS("Video Settings"), PLANS("Add credits")
}

data class WorkState(val title: String, val progress: Float = 0f, val detail: String = "")
data class CompletedJobNotice(val projectId: String, val title: String, val kind: MediaKind, val chargedCredits: Long)

class MotionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = MotionStore(app)
    private val reviewStore = com.gamixtv.MotionMagicAI.reviews.ReviewStore(app)
    private var reviewPending = false
    private var reviewAccountRevision = 0L
    var reviewOpportunity by mutableLongStateOf(0L); private set
    private val history = mutableListOf<Screen>()
    var screen by mutableStateOf(Screen.HOME); private set
    var projects by mutableStateOf<List<Project>>(emptyList()); private set
    var settings by mutableStateOf(store.settings()); private set
    private val settingsWriteLock = Mutex()
    private var settingsRevision = 0L
    private var lastSavedSettings = settings
    var creationSettingsMode by mutableStateOf(CreationMode.IMAGE_TO_VIDEO); private set
    var creationSettingsStoryOnly by mutableStateOf(false); private set
    var draft by mutableStateOf(CreationDraft()); private set
    var imageDraft by mutableStateOf(CreationDraft()); private set
    var longStoryDraft by mutableStateOf(CreationDraft(modelId = "long-story", resolution = "1080p", duration = 60, generatedAudio = true)); private set
    private var longStorySave: Job? = null
    fun updateLongStory(value: CreationDraft) {
        val account = activeAccountId ?: return
        longStoryDraft = value.copy(modelId = "long-story", resolution = "1080p", generatedAudio = true)
        longStorySave?.cancel()
        val saved = longStoryDraft
        longStorySave = viewModelScope.launch {
            delay(250)
            try { withContext(Dispatchers.IO) { store.saveDraft(saved, "long-story", account) } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(activeAccountId == account) failure = "Could not save your story draft." }
        }
    }
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
    var referenceVideoHasAudio by mutableStateOf<Boolean?>(null); private set
    var referenceVideoDurationMs by mutableStateOf<Long?>(null); private set
    var referencePhotoPaths by mutableStateOf<List<String>>(emptyList()); private set
    private var storyRoot: String? = null
    private var storyPart: Int = 0
    var failure by mutableStateOf<String?>(null); private set
    var info by mutableStateOf<Pair<String, String>?>(null); private set
    var completedJobNotice by mutableStateOf<CompletedJobNotice?>(null); private set
    private val completionQueue = ArrayDeque<CompletedJobNotice>()
    val work = MutableStateFlow<WorkState?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private var draftSave: Job? = null
    private val pendingOutputs = ConcurrentHashMap.newKeySet<File>()
    private data class DownloadAttempt(val account: String, val jobId: String, val token: String = UUID.randomUUID().toString())
    private var activeDownload: DownloadAttempt? = null
    private val foreground = com.gamixtv.MotionMagicAI.work.ForegroundWorkController(
        viewModelScope, work,
        cleanup = { withContext(Dispatchers.IO) { pendingOutputs.forEach { it.delete() }; pendingOutputs.clear() } },
        onError = { failure = if(it is TimeoutCancellationException) "This operation took too long. Please retry." else friendlyError(it) },
    )
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
    private fun createVideoJobPreview(source: File, target: File) {
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(source.path)
            if(Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
            else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally { retriever.release() } ?: throw IOException("Could not create a video preview.")
        val factor = minOf(1f, 320f / maxOf(frame.width, frame.height))
        val bitmap = Bitmap.createScaledBitmap(frame, (frame.width * factor).toInt().coerceAtLeast(1), (frame.height * factor).toInt().coerceAtLeast(1), true)
        if(bitmap !== frame) frame.recycle()
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
    private val observedActiveJobIds = mutableSetOf<String>()
    var cloudJobs by mutableStateOf<List<CloudJob>>(emptyList()); private set
    var projectPageLoading by mutableStateOf(false); private set
    var projectPageError by mutableStateOf<String?>(null); private set
    var projectHasMore by mutableStateOf(false); private set
    private var projectNextCursor: String? = null
    private var projectHistoryLoaded = false
    private var projectPageJob: Job? = null
    var cloudRates by mutableStateOf<List<CloudRate>>(emptyList()); private set
    private val showcasePreferences = app.getSharedPreferences("motion_public_templates", Application.MODE_PRIVATE)
    private val showcaseCacheKey = AuthConfig.fromBuildConfig().normalizedBaseUrl
    private val savedShowcaseJson = runCatching { showcasePreferences.getString(showcaseCacheKey, null)?.let {
        if(it.trimStart().startsWith("[")) JSONObject().put("templates", JSONArray(it)) else JSONObject(it)
    } }.getOrNull()
    private val savedShowcase = runCatching { savedShowcaseJson?.optJSONArray("templates")?.let {
        com.gamixtv.MotionMagicAI.ui.ImageTemplateCatalogue.parse(it)
    } }.getOrNull()
    var showcaseTemplates by mutableStateOf(savedShowcase ?: com.gamixtv.MotionMagicAI.ui.ImageTemplateCatalogue.bundled(app)); private set
    var homeBanners by mutableStateOf(runCatching { savedShowcaseJson?.optJSONArray("banners")?.let {
        com.gamixtv.MotionMagicAI.ui.HomeBannerCatalogue.parse(it)
    } }.getOrNull() ?: emptyList()); private set
    private var showcaseCheckedAt = 0L
    private var showcaseLoading = false
    private var catalogCheckedAt = 0L
    var cloudCatalogStatus by mutableStateOf("Checking model availability…"); private set
    var cloudQuote by mutableStateOf<CloudQuote?>(null); private set
    var billingReady by mutableStateOf(false); private set
    var billingBusy by mutableStateOf(false); private set
    var buyingProductId by mutableStateOf<String?>(null); private set
    var billingLoading by mutableStateOf(false); private set
    var billingError by mutableStateOf<String?>(null); private set
    private var billingAccountId: String? = null
    private var billingRefreshJob: Job? = null
    private var walletRefreshJob: Job? = null
    private var billingCheckedAt = 0L
    private var walletCheckedAt = 0L
    var creditProducts by mutableStateOf<List<Pair<String, Long>>>(emptyList()); private set
    var walletCredits by mutableStateOf<Long?>(null); private set
    var monthlyCredits by mutableStateOf<Long?>(null); private set
    var topupCredits by mutableStateOf<Long?>(null); private set
    var freeCredits by mutableStateOf<Long?>(null); private set
    private var cloudWork = false

    fun refreshCloudCatalog() {
        viewModelScope.launch {
            try {
                ensureCloudCatalog()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                cloudRates = emptyList()
                cloudCatalogStatus = "Cannot check model availability. Reconnect and retry."
            }
        }
    }

    fun refreshShowcase(force: Boolean = false) {
        if(showcaseLoading || !force && System.currentTimeMillis() - showcaseCheckedAt < 30_000) return
        showcaseLoading = true
        viewModelScope.launch {
            try {
                val catalogue = cloud.request("GET", "/templates")
                val rows = catalogue.getJSONArray("templates")
                showcaseTemplates = com.gamixtv.MotionMagicAI.ui.ImageTemplateCatalogue.parse(rows)
                catalogue.optJSONArray("banners")?.let { homeBanners = com.gamixtv.MotionMagicAI.ui.HomeBannerCatalogue.parse(it) }
                // Persist the authoritative empty feed too: unpublished rows must not reappear offline.
                showcasePreferences.edit().putString(showcaseCacheKey, catalogue.toString()).apply()
                showcaseCheckedAt = System.currentTimeMillis()
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { /* Keep the last owner catalogue, or initial bundled previews. */ }
            finally { showcaseLoading = false }
        }
    }

    private suspend fun ensureCloudCatalog() {
        if (cloudRates.isNotEmpty() && System.currentTimeMillis() - catalogCheckedAt < 5 * 60_000) return
        cloudRates = cloud.catalog()
        catalogCheckedAt = System.currentTimeMillis()
        cloudCatalogStatus = if(cloudRates.isEmpty()) "Cloud models are not enabled on the server yet." else "Generation credits · review the price before you create"
    }

    fun refreshBilling(recoverPurchases: Boolean = true, force: Boolean = false) {
        val account = activeAccountId ?: return
        // Pack availability and wallet balance are independent requests. Start
        // both without making the pack list wait for the wallet response.
        refreshWallet(force = force)
        if(billingRefreshJob?.isActive == true || (!force && billingReady &&
                android.os.SystemClock.elapsedRealtime() - billingCheckedAt < 5 * 60_000)) return
        billingLoading = true
        billingError = null
        billingRefreshJob = viewModelScope.launch {
            try {
                val session = cloud.session()
                if (session.user.id != account || activeAccountId != account) return@launch
                val products = cloud.request("GET", "/billing/products", session).getJSONArray("products")
                if (activeAccountId != account) return@launch
                creditProducts = (0 until products.length()).map { index ->
                    products.getJSONObject(index).getString("sku") to products.getJSONObject(index).getLong("credits")
                }
                billingReady = true
                billingCheckedAt = android.os.SystemClock.elapsedRealtime()
                if (recoverPurchases) playBilling.recover()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (activeAccountId == account) {
                billingReady = false
                billingError = "Could not load credit packs. Check your connection and retry."
            } }
            finally { if(activeAccountId == account) billingLoading = false }
        }
    }

    fun refreshWallet(force: Boolean = true) {
        val account = activeAccountId ?: return
        if((!force && walletRefreshJob?.isActive == true) || (!force && walletCredits != null &&
                android.os.SystemClock.elapsedRealtime() - walletCheckedAt < 15_000)) return
        if(force) walletRefreshJob?.cancel()
        walletRefreshJob = viewModelScope.launch {
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
                walletCheckedAt = android.os.SystemClock.elapsedRealtime()
            } catch(e: CancellationException) { throw e
            } catch (_: Exception) { /* Keep the last known balance; Create checks the server again. */ }
        }
    }

    fun buy(activity: Activity, productId: String) {
        if (billingBusy) return
        val account = activeAccountId ?: run { message("Sign in before purchasing."); return }
        billingBusy = true
        buyingProductId = productId
        viewModelScope.launch {
            try {
                val session = cloud.session()
                require(session.user.id == account) { "Your account changed. Sign in again." }
                require(creditProducts.any { it.first == productId }) { "This credit pack is unavailable. Refresh the pack list." }
                // This is the same deterministic SHA-256 identity verified by
                // Django. It does not require a wallet request before checkout.
                val accountId = billingAccountId ?: playAccountId(account)
                if(activeAccountId != account) return@launch
                billingAccountId = accountId
                playBilling.buy(activity, productId, accountId) {
                    if(activeAccountId == account) { billingBusy = false; buyingProductId = null }
                }
            } catch (e: Exception) {
                if(activeAccountId == account) {
                    billingBusy = false; buyingProductId = null
                    message(e.message ?: "Sign in and reconnect before purchasing.")
                }
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
                if(activeAccountId == account) refreshBilling(recoverPurchases = false, force = true)
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
        val longStory = screen == Screen.LONG_STORY
        if(longStory && (longStoryDraft.prompt.isBlank() || referencePhotoPaths.size !in 0..2 || longStoryDraft.duration !in listOf(60,120,180,240,300))) {
            failure = "Enter a story prompt and choose 1 to 5 minutes. References are optional; you can add up to two photos and one video."
            return
        }
        if(!longStory && !validateCreationInput()) return
        val image = screen == Screen.CREATE_IMAGE
        val request = if(longStory) longStoryDraft else if(image) imageDraft else draft
        val withVideo = (longStory || !image && screen == Screen.STORY && VideoModelCatalog.isReference(request.modelId)) && referenceVideoPath != null
        val withPhoto = !longStory && !withVideo && screen != Screen.TEXT && screen != Screen.REELS && photo != null
        val sourceVideo = if(withVideo) referenceVideoPath else null
        val referencePhotos = if(withVideo || longStory) referencePhotoPaths.toList() else emptyList()
        if(!image && request.modelId == "kling-o3-edit" && referenceVideoDurationMs == null) {
            failure = "Choose your base video again so its length can be checked."
            return
        }
        if(!longStory && withVideo && request.generatedAudio && referenceVideoHasAudio == false) {
            failure = "This reference has no audio track. Turn off Use reference audio. Choose Kling O3 with a photo or text prompt to generate new sound."
            return
        }
        val selectedPhotoRevision = photoRevision
        startWork("Reviewing your price", "Getting your exact credit price. Your media uploads after confirmation.", replaceableOnCancel = true) {
            val account = checkNotNull(activeAccountId) { "Sign in before creating with AI." }
            val session = cloud.session()
            require(session.user.id == account && activeAccountId == account) { "Your account changed. Start this creation again." }
            if(withContext(Dispatchers.IO) { pendingCloudRequests(session).count { !it.has("job_id") } } >= 3) {
                error("Three requests need recovery. Open My Projects and refresh them before starting another.")
            }
            ensureCloudCatalog()
            val rate = cloudRates.firstOrNull { it.matches(request, image) }
                ?: error("This model and resolution are not enabled on the server yet. Choose an available setting or try again after setup.")
            require(request.aspect in rate.ratios) { "This model supports ${rate.ratios.joinToString()}. Change the aspect ratio in Settings." }
            require(image || request.duration in rate.durations) { "Choose a supported video duration." }
            require(referencePhotos.size <= rate.maxReferenceImages) { "Photo references are unavailable with this setting. Remove the photos or choose another model." }
            val payload = JSONObject().put("service", rate.id).put("aspect_ratio", request.aspect)
                .put("prompt", request.prompt.trim().ifBlank { "Animate this photo with gentle natural movement." })
            if(!image) payload.put("duration", request.duration).put("generated_audio", request.generatedAudio)
            request.templateId?.let { payload.put("template_id", it) }
            if(withVideo || longStory) payload.put("reference_image_count", referencePhotos.size)
            coroutineScope {
                val deferred = cloud.deferredInputQuotes
                // New servers quote the input type without transferring media.
                // Keep compatibility with servers deployed before this protocol.
                val walletCheck = if(deferred) null else async { cloud.request("GET", "/wallet", session) }
                if(deferred) payload.put("input_kind", if(withVideo) "video" else if(withPhoto) "image" else "text")
                else {
                    require(referencePhotos.isEmpty()) { "Multiple photo references are temporarily unavailable. Remove the photos or try again later." }
                    if(withPhoto) payload.put("asset_id", cloud.uploadImage(selectedPhotoFile, session, if(image) "image" else "video"))
                    if(withVideo) payload.put("asset_id", cloud.uploadReferenceVideo(File(checkNotNull(sourceVideo)), session))
                }
                val quote = cloud.request("POST", "/quotes", session, payload)
                val available = if(deferred) quote.getLong("available_credits") else checkNotNull(walletCheck).await().getLong("available_credits")
                currentCoroutineContext().ensureActive()
                if(activeAccountId != account) return@coroutineScope
                walletCredits = available
                cloudQuote = CloudQuote(quote.getString("id"), quote.getLong("maximum_charge_paise"), available,
                    quote.getString("title"), session, request.copy(outputCount = 1), image, withPhoto,
                    sourceVideo, if(withVideo && !longStory) storyRoot else null, if(withVideo && !longStory) storyPart else 0,
                    deferredInput = deferred, sourcePhotoRevision = selectedPhotoRevision, referencePhotoPaths = referencePhotos)
            }
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
            try {
                val session = cloud.session()
                require(session.user.id == quote.session.user.id && activeAccountId == session.user.id) { "Your account changed. Request a new price." }
                require(!quote.sourcePhoto || photoRevision == quote.sourcePhotoRevision) { "Your photo changed. Review the new creation price." }
                require((quote.sourceVideoPath == null || quote.sourceVideoPath == referenceVideoPath) && (quote.draft.modelId != "long-story" && quote.sourceVideoPath == null || quote.referencePhotoPaths == referencePhotoPaths)) { "Your references changed. Review your creation again." }
                var assetId: String? = null
                val referenceIds = JSONArray()
                if(quote.deferredInput && (quote.sourcePhoto || quote.sourceVideoPath != null)) {
                    val title = if(quote.sourcePhoto) "Uploading your photo" else "Uploading your reference video"
                    work.value = WorkState(title, detail = "Sending your reference securely. Generation starts after upload.")
                    val progress: suspend (Long, Long) -> Unit = { sent, total -> withContext(Dispatchers.Main) {
                        if(activeAccountId == session.user.id && work.value != null)
                            work.value = WorkState(title, (sent.toFloat() / total).coerceIn(0f, 1f), "Sending your reference securely.")
                    } }
                    assetId = if(quote.sourcePhoto) cloud.uploadImage(selectedPhotoFile, session, if(quote.image) "image" else "video", progress)
                        else cloud.uploadReferenceVideo(File(checkNotNull(quote.sourceVideoPath)), session, progress)
                }
                for((index, path) in quote.referencePhotoPaths.withIndex()) {
                    work.value = WorkState("Uploading reference photo ${index + 1} of ${quote.referencePhotoPaths.size}", detail = "Your video and photos are sent together to the selected model.")
                    referenceIds.put(cloud.uploadImage(File(path), session, "video"))
                }
                currentCoroutineContext().ensureActive()
                require(activeAccountId == session.user.id) { "Your account changed. Request a new price." }
                work.value = WorkState("Starting your creation", detail = "Reserving credits and adding your generation to My Projects.")
                val pending = JSONObject().put("quote_id", quote.id).put("key", UUID.randomUUID().toString())
                    .put("title", quote.draft.prompt.take(55).ifBlank { quote.title }).put("image", quote.image)
                    .put("duration", if(quote.image) 0 else quote.draft.duration).put("aspect", quote.draft.aspect)
                assetId?.let { pending.put("asset_id", it) }
                if(referenceIds.length() > 0) pending.put("reference_image_ids", referenceIds)
                if(quote.sourcePhoto && selectedPhotoFile.isFile) {
                    val preview = File(projectDir(session.user.id), "preview_${pending.getString("key")}.jpg")
                    val saved = withContext(Dispatchers.IO) { runCatching { createJobPreview(selectedPhotoFile, preview) }.isSuccess }
                    if(saved) pending.put("preview", preview.path).put("preview_temp", true)
                }
                quote.sourceVideoPath?.let { path ->
                    val preview = File(projectDir(session.user.id), "preview_${pending.getString("key")}.jpg")
                    val saved = withContext(Dispatchers.IO) { runCatching { createVideoJobPreview(File(path), preview) }.isSuccess }
                    if(saved) pending.put("preview", preview.path).put("preview_temp", true)
                }
                quote.storyRoot?.let { pending.put("story_root", it).put("story_part", quote.storyPart) }
                withContext(Dispatchers.IO) { updateCloudPending(session, pending, pending.getString("key")) }
                cloudWork = true
                val job = try {
                    val submission = JSONObject().put("quote_id", quote.id)
                    assetId?.let { submission.put("asset_id", it) }
                    if(referenceIds.length() > 0) submission.put("reference_image_ids", referenceIds)
                    cloud.request("POST", "/jobs", session, submission, pending.getString("key"))
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

    fun resumeCloudGeneration() = startWork("Refreshing projects", "Loading your saved generation status.") {
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
                    val submission = JSONObject().put("quote_id", request.getString("quote_id"))
                    request.optString("asset_id").takeIf { it.isNotBlank() }?.let { submission.put("asset_id", it) }
                    request.optJSONArray("reference_image_ids")?.let { submission.put("reference_image_ids", it) }
                    val job = cloud.request("POST", "/jobs", session, submission, request.getString("key"))
                    request.put("job_id", job.getString("id"))
                    withContext(Dispatchers.IO) { updateCloudPending(session, request, id) }
                } catch(e: CloudException) {
                    if(e.status in listOf(400, 402, 403, 404, 409, 429)) withContext(Dispatchers.IO) { updateCloudPending(session, null, id) }
                    else throw e
                }
            }
            // A project refresh reads saved database status only. Result media is
            // downloaded separately when the user taps Download to device.
            pollCloudJobs(session.user.id, includeRecent = true)
            tab(Screen.PROJECTS)
            if(cloudJobs.any { it.isGenerating }) startCloudMonitor(session.user.id, restart = true)
        } finally {
            cloudWork = false
            if(cloudJobMonitor == null && cloudJobs.any { it.isGenerating }) activeAccountId?.let { startCloudMonitor(it) }
        }
    }

    fun downloadCloudResult(jobId: String) = startWork("Downloading your creation",
        "Your result is saved to your account. Copying it to this device.", replaceableOnCancel = true) {
        val account = checkNotNull(activeAccountId) { "Sign in to save your creation." }
        if(projects.any { it.id == "cloud_$jobId" && File(it.path).isFile }) return@startWork
        val job = cloudJobs.firstOrNull { it.id == jobId && it.isReady } ?: error("This result is not ready to download.")
        val attempt = DownloadAttempt(account, jobId)
        activeDownload = attempt
        try {
            val session = cloud.session()
            require(session.user.id == account && activeAccountId == account) { "Your account changed. Sign in again." }
            downloadCloudJob(account, session, job, attempt)
        } catch(error: Exception) {
            if(activeAccountId == account && activeDownload === attempt) cloudJobs = cloudJobs.map {
                if(it.id == jobId) it.copy(state = "succeeded", progressPercent = 100) else it
            }
            throw error
        } finally { if(activeDownload === attempt) activeDownload = null }
    }

    private fun startCloudMonitor(account: String, restart: Boolean = false, includeRecentOnStart: Boolean = false) {
        if(cloudJobMonitor?.isActive == true && !restart) return
        cloudJobMonitor?.cancel()
        val generation = ++cloudMonitorGeneration
        cloudJobMonitor = viewModelScope.launch {
            var failures = 0
            var includeRecent = includeRecentOnStart
            try {
                while(isActive && activeAccountId == account) {
                    val active = try {
                        val result = pollCloudJobs(account, includeRecent = includeRecent)
                        includeRecent = false
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

    private suspend fun pollCloudJobs(account: String, includeRecent: Boolean = false): Boolean = cloudPollLock.withLock {
        if(activeAccountId != account) return@withLock false
        if(includeRecent) { projectPageLoading = true; projectPageError = null }
        try {
            val session = cloud.session()
            if(session.user.id != account || activeAccountId != account) return@withLock false
            val pinned = withContext(Dispatchers.IO) { pendingCloudRequests(session) }
            val previews = pinned.mapNotNull { request -> request.optString("job_id").takeIf { it.isNotBlank() }?.let { it to request } }.toMap()
            val trackedIds = (previews.keys + observedActiveJobIds).mapNotNull { rawId ->
                val id = runCatching { UUID.fromString(rawId).toString() }.getOrNull()
                if(id == null || projects.any { it.id == "cloud_$id" && File(it.path).isFile }) {
                    pinned.firstOrNull { it.optString("job_id") == rawId }?.let { request ->
                        withContext(Dispatchers.IO) { updateCloudPending(session, null, pendingId(request)) }
                        if(request.optBoolean("preview_temp")) request.optString("preview").takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { File(it).delete() } }
                    }
                    observedActiveJobIds.remove(rawId)
                    null
                } else id
            }.distinct().take(12)
            val path = "/jobs?scope=${if (includeRecent) "recent" else "active"}" +
                if(trackedIds.isEmpty()) "" else "&tracked=${trackedIds.joinToString(",")}"
            val response = cloud.request("GET", path, session)
            val rows = response.getJSONArray("results")
            if(activeAccountId != account) return@withLock false
            val latest = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val serverJob = CloudJob.from(row).copy(previewPath = previews[row.getString("id")]?.optString("preview")?.ifBlank { null },
                    previewVideo = previews[row.getString("id")]?.optBoolean("preview_video") ?: false)
                cloudJobs.firstOrNull { it.id == serverJob.id && it.state == "downloading" && serverJob.isReady } ?: blockedPreviewFallback(serverJob, account)
            }
            val latestById = latest.associateBy { it.id }
            for(id in trackedIds.filterNot { it in latestById }.takeIf { response.optBoolean("tracked_supported") }.orEmpty()) {
                pinned.firstOrNull { it.optString("job_id") == id }?.let { request -> withContext(Dispatchers.IO) {
                    updateCloudPending(session, null, pendingId(request))
                    if(request.optBoolean("preview_temp")) request.optString("preview").takeIf { it.isNotBlank() }?.let { File(it).delete() }
                } }
                observedActiveJobIds.remove(id)
            }
            val previouslyActive = cloudJobs.filter { it.isGenerating }.map { it.id }.toSet() + observedActiveJobIds
            observedActiveJobIds.addAll(latest.filter { it.isGenerating }.map { it.id })
            val downloaded = projects.filter { it.id.startsWith("cloud_") && File(it.path).isFile }.map { it.id.removePrefix("cloud_") }.toSet()
            cloudJobs = mergeCloudJobs(cloudJobs, latest, downloaded)
            if(includeRecent && !projectHistoryLoaded) {
                projectNextCursor = response.optString("next_cursor").takeUnless { it.isBlank() || it == "null" }
                projectHasMore = projectNextCursor != null
                projectHistoryLoaded = true
            }
            var terminalChanged = false
            for(job in latest.filter { it.state in setOf("succeeded", "failed", "cancelled") && it.id in previouslyActive }) {
                terminalChanged = true
                if(job.state == "succeeded") {
                    val notice = CompletedJobNotice("cloud_${job.id}", job.title,
                        if(job.kind == "image") MediaKind.IMAGE else MediaKind.VIDEO, job.chargedPaise)
                    if(completedJobNotice == null) completedJobNotice = notice else completionQueue.addLast(notice)
                }
            }
            for(job in latest.filter { it.state == "failed" || it.state == "cancelled" }) {
                if(job.isContentBlocked) {
                    try {
                        val blurred = BlockedPreviewStore.save(app, account, job.id, job.previewPath, job.previewVideo)
                        if(blurred != null && activeAccountId == account) cloudJobs = cloudJobs.map {
                            if(it.id == job.id) it.copy(previewPath = blurred, previewVideo = false) else it
                        }
                    } catch(e: CancellationException) { throw e }
                    catch(_: Exception) { /* A missing preview must not delay credit release or status updates. */ }
                }
                pinned.firstOrNull { it.optString("job_id") == job.id }?.let { request ->
                    withContext(Dispatchers.IO) { updateCloudPending(session, null, pendingId(request)) }
                }
                if(previews[job.id]?.optBoolean("preview_temp") == true) previews[job.id]?.optString("preview")?.takeIf { it.isNotBlank() }?.let {
                    withContext(Dispatchers.IO) { File(it).delete() }
                }
            }
            observedActiveJobIds.removeAll(latest.filter { it.state in setOf("succeeded", "failed", "cancelled") }.map { it.id }.toSet())
            if(terminalChanged) refreshWallet()
            cloudJobs.any { it.isGenerating }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            if(includeRecent && activeAccountId == account) projectPageError = "Could not load projects. Tap retry."
            throw e
        } finally { if(includeRecent && activeAccountId == account) projectPageLoading = false }
    }

    private fun blockedPreviewFallback(job: CloudJob, account: String): CloudJob =
        if(job.isContentBlocked && job.previewPath == null) job.copy(previewPath = BlockedPreviewStore.path(app, account, job.id), previewVideo = false) else job

    fun loadMoreProjects() {
        val account = activeAccountId ?: return
        if(projectPageLoading || projectPageJob?.isActive == true) return
        if(!projectHistoryLoaded) {
            startCloudMonitor(account, restart = true, includeRecentOnStart = true)
            return
        }
        if(projectNextCursor == null) return
        projectPageLoading = true; projectPageError = null
        projectPageJob = viewModelScope.launch {
            try {
                cloudPollLock.withLock {
                    if(activeAccountId != account) return@withLock
                    val cursor = projectNextCursor ?: return@withLock
                    val session = cloud.session()
                    if(session.user.id != account) return@withLock
                    // Only send the opaque cursor to our fixed API path, never follow a supplied URL.
                    val response = cloud.request("GET", "/jobs?scope=recent&cursor=${URLEncoder.encode(cursor, "UTF-8")}", session)
                    if(activeAccountId != account) return@withLock
                    val rows = response.getJSONArray("results")
                    val downloaded = projects.filter { it.id.startsWith("cloud_") && File(it.path).isFile }.map { it.id.removePrefix("cloud_") }.toSet()
                    val page = (0 until rows.length()).map { blockedPreviewFallback(CloudJob.from(rows.getJSONObject(it)), account) }
                    cloudJobs = mergeCloudJobs(cloudJobs, page, downloaded, retainMissingActive = true)
                    projectNextCursor = response.optString("next_cursor").takeUnless { it.isBlank() || it == "null" }
                    projectHasMore = projectNextCursor != null
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(activeAccountId == account) {
                    if(e is CloudException && e.status == 400) {
                        projectHistoryLoaded = false; projectNextCursor = null; projectHasMore = false
                    }
                    projectPageError = "Could not load more projects. Tap retry."
                }
            }
            finally { if(activeAccountId == account) projectPageLoading = false }
        }
    }

    private suspend fun downloadCloudJob(account: String, session: AuthSession, job: CloudJob, attempt: DownloadAttempt) {
        val id = "cloud_${job.id}"
        val output = File(projectDir(account), "$id.media")
        val temporary = File(projectDir(account), "$id.${attempt.token}.part")
        cloudJobs = cloudJobs.map { if(it.id == job.id) it.copy(state = "downloading", progressPercent = 0) else it }
        try {
            withContext(Dispatchers.IO) { temporary.delete() }
            val mime = cloud.download(checkNotNull(job.outputAssetId), session, temporary) { transferred, total ->
                withContext(Dispatchers.Main.immediate) {
                    if(activeAccountId == account && activeDownload === attempt) cloudJobs = cloudJobs.map {
                        if(it.id == job.id) it.copy(progressPercent = (transferred * 100 / total).toInt().coerceIn(0, 100)) else it
                    }
                    if(activeAccountId == account && activeDownload === attempt) work.value = work.value?.copy(progress = (transferred.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            currentCoroutineContext().ensureActive()
            require(activeAccountId == account && activeDownload === attempt) { "Download was stopped." }
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
            withContext(Dispatchers.IO) {
                pendingCloudRequests(session).firstOrNull { it.optString("job_id") == job.id }?.let {
                    updateCloudPending(session, null, pendingId(it))
                }
            }
            cloudJobs = cloudJobs.filterNot { it.id == job.id }
            if(pending?.optBoolean("preview_temp") == true) job.previewPath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
            observedActiveJobIds.remove(job.id)
            autoSave(project)
            message("${job.title} downloaded to this device.")
        } finally { withContext(NonCancellable + Dispatchers.IO) { temporary.delete() } }
    }

    fun activateAccount(accountId: String?) {
        if (activeAccountId == accountId) return
        reviewAccountRevision++
        reviewPending = false
        foreground.cancel()
        activeDownload = null
        billingRefreshJob?.cancel(); walletRefreshJob?.cancel()
        billingRefreshJob = null; walletRefreshJob = null
        cloudJobMonitor?.cancel(); cloudJobMonitor = null; cloudMonitorGeneration++
        projectPageJob?.cancel(); projectPageJob = null
        projectPageLoading = false; projectPageError = null; projectHasMore = false
        projectNextCursor = null; projectHistoryLoaded = false
        cloudJobs = emptyList(); observedActiveJobIds.clear()
        projects = emptyList()
        completedJobNotice = null; completionQueue.clear()
        draftSave?.cancel(); imageDraftSave?.cancel(); longStorySave?.cancel()
        photoRevision++
        removeReferenceVideo()
        activeAccountId = accountId
        photo = null; photoName = ""; referenceVideoPath = null; referenceVideoName = ""; referenceVideoHasAudio = null; storyRoot = null; storyPart = 0; cloudQuote = null
        referencePhotoPaths = emptyList(); referenceVideoDurationMs = null
        selectedProject = null; selectedArt = Artwork.Garden; failure = null; info = null
        currentPlan = PlanTier.FREE; billingReady = false; billingBusy = false; billingAccountId = null; creditProducts = emptyList()
        buyingProductId = null; billingLoading = false; billingError = null; billingCheckedAt = 0L; walletCheckedAt = 0L
        walletCredits = null; monthlyCredits = null; topupCredits = null; freeCredits = null
        history.clear(); screen = Screen.HOME
        creationSettingsStoryOnly = false
        draft = accountId?.let { store.draft("video", it) } ?: CreationDraft()
        imageDraft = accountId?.let { store.draft("image-generation", it) } ?: CreationDraft()
        val savedLong = accountId?.let { store.draft("long-story", it) }
        longStoryDraft = if(savedLong?.modelId == "long-story") savedLong else CreationDraft(modelId = "long-story", resolution = "1080p", duration = 60, generatedAudio = true)
        voiceText = accountId?.let { store.draft("voice", it).prompt }.orEmpty()
        if (accountId != null) {
            refreshCloudCatalog()
            refreshBilling(recoverPurchases = false)
            playBilling.recover()
            viewModelScope.launch {
                val (saved, warning) = withContext(Dispatchers.IO) { store.projects(accountId) to store.lastReadWarning }
                if(activeAccountId == accountId) {
                    projects = saved
                    if(warning != null) failure = warning
                    startCloudMonitor(accountId, includeRecentOnStart = true)
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
        alignVideoWorkflow(destination)
        refreshProjectsOnEntry()
    }
    fun tab(destination: Screen) { history.clear(); screen = destination; alignVideoWorkflow(destination); refreshProjectsOnEntry() }
    private fun alignVideoWorkflow(destination: Screen) {
        if(destination !in listOf(Screen.IMAGE, Screen.TEXT, Screen.REELS, Screen.STORY)) return
        val story = destination == Screen.STORY
        if(VideoModelCatalog.isReference(draft.modelId) == story) return
        val options = VideoModelCatalog.forWorkflow(story)
        val enabled = options.filter { model -> cloudRates.any { it.kind == "video" && it.modelId == model.id } }
        val preferred = if(story) "kling-o3-reference" else "pixverse-v6"
        val model = enabled.firstOrNull { it.id == preferred } ?: enabled.firstOrNull()
            ?: options.first { it.id == preferred }
        selectVideoModel(model)
        if(story) updateDraft(draft.copy(templateId = null, requiresReference = false))
    }
    fun back() { screen = if (history.isEmpty()) Screen.HOME else history.removeAt(history.lastIndex); alignVideoWorkflow(screen); refreshProjectsOnEntry() }
    private fun refreshProjectsOnEntry() {
        if(screen == Screen.PROJECTS && !cloudWork && !projectHistoryLoaded && !projectPageLoading) activeAccountId?.let {
            startCloudMonitor(it, includeRecentOnStart = true)
        }
    }
    fun open(project: Project) {
        if(!ownsProject(project)) { failure = "This project does not belong to the signed-in account."; return }
        if (!MediaAccess.external(project.path) && !File(project.path).isFile) {
            failure = "This project’s file is missing. Import the original again, or remove the project from your library."
            return
        }
        selectedProject = project
        go(Screen.RESULT)
    }
    fun recordViewedProject(project: Project) {
        if(project.kind != MediaKind.AUDIO && ownsProject(project)) recordReviewCreation(project.id)
    }
    fun recordViewedCloudCreation(jobId: String) {
        if(cloudJobs.any { it.id == jobId && it.isReady && it.kind in setOf("image", "video") }) recordReviewCreation(jobId)
    }
    private fun recordReviewCreation(id: String) {
        val account = activeAccountId ?: return
        val revision = reviewAccountRevision
        viewModelScope.launch {
            val eligible = withContext(Dispatchers.IO) { runCatching { reviewStore.recordViewed(account, id) }.getOrDefault(false) }
            if(eligible && activeAccountId == account && revision == reviewAccountRevision) {
                reviewPending = true
                reviewOpportunity++
            }
        }
    }
    fun reviewPromptAllowed(account: String): Boolean = activeAccountId == account && screen == Screen.HOME &&
        work.value == null && cloudQuote == null && failure == null && info == null && completedJobNotice == null && !billingBusy

    suspend fun claimAutomaticReview(account: String): Boolean {
        if(!reviewPending || !reviewPromptAllowed(account)) return false
        reviewPending = false
        return withContext(Dispatchers.IO) { runCatching { reviewStore.claimAttempt(account) }.getOrDefault(false) }
    }
    fun recordRatingStoreVisit() {
        reviewPending = false
        viewModelScope.launch(Dispatchers.IO) { runCatching { reviewStore.recordStoreVisit() } }
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
        referenceVideoHasAudio = null
        referenceVideoDurationMs = null
        referencePhotoPaths = emptyList()
        photo = null; photoName = ""
        updateDraft(draft.copy(modelId = "kling-o3-reference", resolution = "1080p", duration = if(60 - total < 10) 5 else 10,
            generatedAudio = true, aspect = project.aspect,
            prompt = "Continue this story from @Video1. Keep the characters, setting and visual style consistent. "))
        tab(Screen.STORY)
        val account = activeAccountId
        viewModelScope.launch {
            val hasAudio = readReferenceAudio(project.path)
            if(activeAccountId == account && referenceVideoPath == project.path) {
                referenceVideoHasAudio = hasAudio
                if(hasAudio == false && VideoModelCatalog.isReference(draft.modelId)) updateDraft(draft.copy(generatedAudio = false))
            }
        }
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
        val saved = if(value.modelId == "kling-o3-edit" && referenceVideoDurationMs != null)
            value.copy(duration = referenceBillableSeconds(checkNotNull(referenceVideoDurationMs))) else value
        draft = saved
        draftSave?.cancel()
        draftSave = viewModelScope.launch {
            delay(250)
            try { withContext(Dispatchers.IO) { store.saveDraft(saved, accountId = account) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e.message ?: "Could not save your draft." }
        }
    }
    fun selectVideoModel(model: VideoModelOption) {
        if(!VideoModelCatalog.isReference(model.id)) removeReferenceVideo()
        val options = cloudRates.filter { it.kind == "video" && it.modelId == model.id }
        val chosen = options.firstOrNull { it.size == draft.resolution && it.audio == draft.generatedAudio }
            ?: options.firstOrNull { it.size == draft.resolution } ?: options.firstOrNull()
        updateDraft(draft.copy(
            modelId = model.id,
            duration = draft.duration.takeIf { it in (chosen?.durations ?: model.durations) } ?: chosen?.durations?.firstOrNull() ?: model.durations.min(),
            resolution = chosen?.size ?: draft.resolution.takeIf { it in model.resolutions } ?: model.resolutions.first(),
            generatedAudio = (chosen?.audio ?: (draft.generatedAudio && model.supportsGeneratedAudio)) && !(VideoModelCatalog.isReference(model.id) && referenceVideoHasAudio == false),
            aspect = draft.aspect.takeIf { chosen == null || it in chosen.ratios } ?: chosen?.ratios?.firstOrNull() ?: "9:16",
        ))
    }

    fun switchCreationMode(mode: CreationMode) {
        screen = when(mode) {
            CreationMode.IMAGE_TO_VIDEO -> Screen.IMAGE
            CreationMode.TEXT_TO_VIDEO -> Screen.TEXT
            CreationMode.CREATE_IMAGE -> Screen.CREATE_IMAGE
        }
        alignVideoWorkflow(screen)
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
    fun removeReferenceVideo(keepPhotos: Boolean = false) {
        discardDraftVideo(referenceVideoPath)
        referenceVideoPath = null; referenceVideoName = ""; referenceVideoHasAudio = null; storyRoot = null; storyPart = 0
        referenceVideoDurationMs = null
        if(!keepPhotos) {
            referencePhotoPaths.forEach { File(it).delete() }
            referencePhotoPaths = emptyList()
        }
        cloudQuote = null
    }

    private fun discardDraftVideo(path: String?) {
        val account = activeAccountId ?: return
        if(path == null) return
        runCatching {
            val file = File(path).canonicalFile
            val sourceDir = File(app.filesDir, "sources/${accountHash(account)}").canonicalFile
            if(file.parentFile != sourceDir || file.extension != "mp4") return@runCatching
            // Retain legacy requests whose saved preview still points to the clip.
            val raw = cloudPending.getString(AuthConfig.fromBuildConfig().normalizedBaseUrl + "|" + account, null)
            val pending = if(raw.isNullOrBlank()) JSONArray() else if(raw.trimStart().startsWith("[")) JSONArray(raw) else JSONArray().put(JSONObject(raw))
            if((0 until pending.length()).none { pending.getJSONObject(it).optString("preview") == path }) file.delete()
        }
    }

    fun removeReferencePhoto(index: Int) {
        val path = referencePhotoPaths.getOrNull(index) ?: return
        referencePhotoPaths = referencePhotoPaths.filterIndexed { i, _ -> i != index }
        File(path).delete()
        cloudQuote = null
        message("Photo removed. Check the @Image numbers in your prompt.")
    }

    fun validateCreationInput(): Boolean {
        val input = if(screen == Screen.CREATE_IMAGE) imageDraft else draft
        val errors = buildList {
            if(screen == Screen.IMAGE && photo == null) add("Add a starting photo.")
            if(screen == Screen.STORY && !VideoModelCatalog.isReference(input.modelId)) add("Choose a story model.")
            if(VideoModelCatalog.isReference(input.modelId) && screen != Screen.CREATE_IMAGE && (screen != Screen.STORY || referenceVideoPath == null)) add("Choose a base video in Continue Story for this model.")
            if(VideoModelCatalog.isReference(input.modelId) && screen != Screen.CREATE_IMAGE && input.prompt.isBlank()) add("Describe how to use @Video1 and your reference photos.")
            if(screen != Screen.IMAGE && input.prompt.isBlank()) add("Describe what you want to create.")
            if(input.requiresReference && photo == null) add("Upload your own photo to recreate this template.")
            if(input.prompt.length > CreationValidation.MAX_PROMPT_LENGTH) add("Keep your prompt to ${CreationValidation.MAX_PROMPT_LENGTH} characters or fewer.")
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
                    mode: CreationMode = CreationMode.IMAGE_TO_VIDEO, aspect: String = "9:16", duration: Int = 5,
                    modelId: String? = null, resolution: String? = null, generatedAudio: Boolean = false,
                    templateId: String? = null, requiresReference: Boolean = false) {
        selectedArt = art
        if(mode == CreationMode.CREATE_IMAGE) updateImageDraft(imageDraft.copy(selectedStyle = title, prompt = prompt, aspect = aspect,
            imageModelId = modelId?.takeIf { id -> ImageModelCatalog.models.any { it.id == id } } ?: imageDraft.imageModelId,
            imageQuality = resolution ?: imageDraft.imageQuality, templateId = templateId, requiresReference = requiresReference))
        else {
            val model = VideoModelCatalog.find(modelId ?: draft.modelId)
            updateDraft(draft.copy(selectedStyle = title, prompt = prompt, aspect = aspect, modelId = model.id,
                resolution = resolution?.takeIf { it in model.resolutions }
                    ?: draft.resolution.takeIf { it in model.resolutions } ?: model.resolutions.first(),
                generatedAudio = generatedAudio && model.supportsGeneratedAudio,
                duration = duration.takeIf { it in model.durations } ?: model.durations.min(), templateId = templateId, requiresReference = requiresReference))
        }
        val target = when(mode) {
            CreationMode.IMAGE_TO_VIDEO -> if(VideoModelCatalog.isReference(draft.modelId)) Screen.STORY else Screen.IMAGE
            CreationMode.TEXT_TO_VIDEO -> Screen.TEXT
            CreationMode.CREATE_IMAGE -> Screen.CREATE_IMAGE
        }
        if(screen != target) go(target) else alignVideoWorkflow(target)
    }
    fun saveSettings(value: AppSettings) {
        val revision = ++settingsRevision
        settings = value
        viewModelScope.launch {
            settingsWriteLock.withLock {
                try {
                    withContext(Dispatchers.IO) { store.saveSettings(value) }
                    lastSavedSettings = value
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    if(settingsRevision == revision) settings = lastSavedSettings
                    message("Your preference could not be saved. Please try again.")
                }
            }
        }
    }

    fun toggleTheme() = saveSettings(settings.copy(themeMode = if(settings.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK))

    fun openCreationSettings(mode: CreationMode) {
        creationSettingsMode = mode
        creationSettingsStoryOnly = screen == Screen.STORY
        go(Screen.CREATION_SETTINGS)
    }

    private suspend fun normalizedPhoto(account: String, uri: Uri, target: File): Bitmap = withContext(Dispatchers.IO) {
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

    fun importPhoto(uri: Uri) = startWork("Opening photo") {
        val account = checkNotNull(activeAccountId)
        val bitmap = normalizedPhoto(account, uri, photoFile(account))
        if(activeAccountId != account) { bitmap.recycle(); return@startWork }
        photoRevision++
        photo = bitmap
        photoName = "Your photo"
        // Creating/editing an image keeps the separate video draft and references.
        if(screen != Screen.CREATE_IMAGE) removeReferenceVideo()
        message("Photo added")
    }

    fun importReferencePhotos(uris: List<Uri>, replaceIndex: Int? = null) = startWork("Opening reference photos") {
        val maximum = if(screen == Screen.LONG_STORY) 2 else 4
        val account = checkNotNull(activeAccountId)
        require(VideoModelCatalog.isReference(draft.modelId) && referenceVideoPath != null) { "Add a base video first." }
        require(uris.isNotEmpty() && (replaceIndex == null && referencePhotoPaths.size + uris.size <= maximum ||
            replaceIndex != null && replaceIndex in referencePhotoPaths.indices && uris.size == 1)) { "You can use up to four reference photos." }
        val added = mutableListOf<String>()
        try {
            for(uri in uris) {
                val file = File(app.filesDir, "sources/${accountHash(account)}/reference_${UUID.randomUUID()}.png")
                added.add(file.path)
                normalizedPhoto(account, uri, file).recycle()
                require(file.length() in 1..10L * 1024 * 1024) { "Choose a smaller reference photo (10 MB maximum)." }
            }
            require(activeAccountId == account) { "Your account changed. Choose the photos again." }
            if(replaceIndex == null) referencePhotoPaths = referencePhotoPaths + added
            else {
                val previous = referencePhotoPaths[replaceIndex]
                referencePhotoPaths = referencePhotoPaths.toMutableList().apply { set(replaceIndex, added.single()) }
                File(previous).delete()
            }
            added.clear()
            cloudQuote = null
            message("Reference photos ready. Use @Image1, @Image2 in your prompt.")
        } finally { added.forEach { File(it).delete() } }
    }

    fun importReferenceVideo(uri: Uri) = startWork("Opening reference video") {
        val longStory = screen == Screen.LONG_STORY
        val account = checkNotNull(activeAccountId) { "Sign in before choosing a video." }
        val target = File(app.filesDir, "sources/${accountHash(account)}/${UUID.randomUUID()}.mp4")
        var hasAudio = false
        var durationMs = 0L
        withContext(Dispatchers.IO) {
            val temporary = copyUri(account, uri, 200L * 1024 * 1024, ".mp4")
            try {
                val retriever = MediaMetadataRetriever()
                val duration = try {
                    retriever.setDataSource(temporary.path)
                    hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    require(width in 720..3840 && height in 720..3840) { "Choose an MP4 with width and height between 720 and 3840 pixels." }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?: error("This video has no readable duration.")
                } finally { retriever.release() }
                require(duration in 3_000..15_000) { "Choose a reference MP4 between 3 and 15 seconds." }
                durationMs = duration
                require(app.contentResolver.getType(uri) in setOf("video/mp4", "application/mp4")) { "Choose an MP4 reference video." }
                target.parentFile?.mkdirs()
                if(target.exists() && !target.delete()) error("Could not replace the previous reference video.")
                if(!temporary.renameTo(target)) error("Could not save the reference video.")
                pendingOutputs.remove(temporary)
            } finally { temporary.delete() }
        }
        if(activeAccountId != account) return@startWork
        discardDraftVideo(referenceVideoPath)
        referenceVideoPath = target.path; referenceVideoName = "Reference video"
        referenceVideoHasAudio = hasAudio
        referenceVideoDurationMs = durationMs
        cloudQuote = null
        if(longStory) {
            message("Character video added. Your story will generate new dialogue.")
            return@startWork
        }
        photo = null; photoName = ""; storyRoot = null; storyPart = 0
        val modelId = if(VideoModelCatalog.isReference(draft.modelId)) draft.modelId
            else cloudRates.firstOrNull { VideoModelCatalog.isReference(it.modelId) }?.modelId ?: "kling-o3-reference"
        val keepAudio = hasAudio && cloudRates.any { it.modelId == modelId && it.audio == true }
        updateDraft(draft.copy(modelId = modelId, resolution = "1080p", duration = if(modelId == "kling-o3-edit") referenceBillableSeconds(durationMs) else 10, generatedAudio = keepAudio,
            prompt = draft.prompt.ifBlank { if(modelId == "kling-o3-edit") "Edit @Video1. Keep the original movement and camera, with warm cinematic lighting."
                else "Create the next story scene from @Video1. Keep the same characters and style." }))
        message(if(hasAudio) "Reference video added · existing audio can be kept" else "Silent reference added · this model does not generate new audio")
    }

    private suspend fun readReferenceAudio(path: String): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            } finally { retriever.release() }
        }.getOrNull()
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
        if(work.value == null) return
        val submitted = cloudWork
        activeDownload?.let { attempt ->
            if(activeAccountId == attempt.account) cloudJobs = cloudJobs.map {
                if(it.id == attempt.jobId) it.copy(state = "succeeded", progressPercent = 100) else it
            }
            activeDownload = null
        }
        if(foreground.cancel()) cloudQuote = null
        message(if(submitted) "Progress closed. Submitted generations continue on the server." else "Operation cancelled.")
    }

    private fun startWork(title: String, detail: String = "", replaceableOnCancel: Boolean = false, action: suspend () -> Unit) {
        if(!foreground.start(WorkState(title, detail = detail), replaceableOnCancel, action)) {
            message("Please wait for the current operation to finish.")
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
