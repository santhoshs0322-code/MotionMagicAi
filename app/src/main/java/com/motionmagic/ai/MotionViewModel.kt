package com.motionmagic.ai

import android.app.Application
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
import com.motionmagic.ai.data.*
import com.motionmagic.ai.media.DeviceVoiceEngine
import com.motionmagic.ai.media.LocalMediaEngine
import com.motionmagic.ai.media.Media3Editor
import com.motionmagic.ai.media.EditOptions
import com.motionmagic.ai.media.MediaAccess
import com.motionmagic.ai.media.EditorDocument
import com.motionmagic.ai.media.StudioExporter
import com.motionmagic.ai.ui.ArtRepository
import com.motionmagic.ai.ui.Artwork
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class Screen(val title: String) {
    HOME("MotionMagic AI"), IMAGE("Image to Video"), TEXT("Text to Video"),
    REELS("AI Reels Maker"), VOICE("AI Voice Generator"), TEMPLATES("Templates"),
    PROJECTS("My Projects"), RESULT("Video Result"), EDITOR("Video Editor"), AUDIO_EDITOR("Audio Editor"),
    PROFILE("My Account"), SETTINGS("Settings"), PLANS("Available services")
}

data class WorkState(val title: String, val progress: Float = 0f, val detail: String = "")

class MotionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = MotionStore(app)
    private val history = mutableListOf<Screen>()
    var screen by mutableStateOf(Screen.HOME); private set
    var projects by mutableStateOf(store.projects()); private set
    var settings by mutableStateOf(store.settings()); private set
    var draft by mutableStateOf(store.draft()); private set
    var voiceText by mutableStateOf(store.draft("voice").prompt); private set
    var selectedProject by mutableStateOf<Project?>(null); private set
    var selectedArt by mutableStateOf(Artwork.Garden); private set
    var photo by mutableStateOf<Bitmap?>(null); private set
    var photoName by mutableStateOf("Sample photo"); private set
    var failure by mutableStateOf<String?>(null); private set
    var info by mutableStateOf<Pair<String, String>?>(null); private set
    val work = MutableStateFlow<WorkState?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private var activeJob: Job? = null
    private var draftSave: Job? = null
    private val projectDir = File(app.filesDir, "projects").also { it.mkdirs() }
    private val pendingOutputs = ConcurrentHashMap.newKeySet<File>()
    private val selectedPhotoFile = File(app.filesDir, "sources/selected.png")

    init {
        val referenced = projects.map { it.path }.toSet()
        val abandoned = if(store.lastReadWarning == null) projectDir.listFiles()?.filter { it.path !in referenced } else null
        viewModelScope.launch {
            val restoredPhoto = withContext(Dispatchers.IO) {
                abandoned?.forEach { it.delete() }
                if(selectedPhotoFile.isFile) BitmapFactory.decodeFile(selectedPhotoFile.path) else null
            }
            if(restoredPhoto != null && photo == null) { photo = restoredPhoto; photoName = "Your photo" }
        }
        store.lastReadWarning?.let { failure = it }
    }

    fun go(destination: Screen) {
        if (screen == destination) return
        history.add(screen)
        screen = destination
    }
    fun tab(destination: Screen) { history.clear(); screen = destination }
    fun back() { screen = if (history.isEmpty()) Screen.HOME else history.removeAt(history.lastIndex) }
    fun open(project: Project) {
        if (!MediaAccess.external(project.path) && !File(project.path).isFile) {
            failure = "This project’s file is missing. Import the original again, or remove the project from your library."
            return
        }
        selectedProject = project
        go(Screen.RESULT)
    }
    fun edit(project: Project) { selectedProject = project; go(if(project.kind == MediaKind.AUDIO) Screen.AUDIO_EDITOR else Screen.EDITOR) }
    fun notice(title: String, body: String) { info = title to body }
    fun dismissNotice() { info = null }
    fun dismissFailure() { failure = null }
    fun message(text: String) { _messages.tryEmit(text) }

    fun updateDraft(value: CreationDraft) {
        draft = value
        draftSave?.cancel()
        draftSave = viewModelScope.launch {
            delay(250)
            try { withContext(Dispatchers.IO) { store.saveDraft(value) } }
            catch (e: Exception) { failure = e.message ?: "Could not save your draft." }
        }
    }
    fun updateVoiceText(text: String) {
        voiceText = text
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.saveDraft(CreationDraft(prompt = text), "voice") }
                .onFailure { _messages.tryEmit("Voice draft could not be saved.") }
        }
    }
    fun useTemplate(art: Artwork, title: String) {
        selectedArt = art
        photo = null
        photoName = "$title sample"
        selectedPhotoFile.delete()
        updateDraft(draft.copy(selectedStyle = title, prompt = "A $title scene with a slow cinematic camera movement"))
        go(Screen.IMAGE)
    }
    fun saveSettings(value: AppSettings) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.saveSettings(value) }; settings = value }
            catch(e: Exception) { failure = e.message ?: "Settings could not be saved." }
        }
    }

    fun importPhoto(uri: Uri) = startWork("Opening photo") {
        val bitmap = withContext(Dispatchers.IO) {
            val temporary = copyUri(uri, 20L * 1024 * 1024, ".image")
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
                selectedPhotoFile.parentFile?.mkdirs()
                val staged = File(selectedPhotoFile.parentFile, "selected.tmp")
                try {
                    staged.outputStream().use { require(oriented.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not save this photo." } }
                    require(staged.renameTo(selectedPhotoFile)) { "Could not save this photo. Free some storage and try again." }
                } finally { staged.delete() }
                oriented
            } finally { temporary.delete() }
        }
        photo = bitmap
        photoName = "Your photo"
        message("Photo added")
    }

    fun importVideo(uri: Uri) = importMedia(uri, MediaKind.VIDEO)
    fun importAudio(uri: Uri) = importMedia(uri, MediaKind.AUDIO)
    private fun importMedia(uri: Uri, kind: MediaKind) = startWork("Opening media", "Reading the selected document without loading it into memory.") {
        edit(retainEditorSource(uri, kind))
    }

    suspend fun retainEditorSource(uri: Uri, kind: MediaKind): Project {
        val source = MediaAccess.import(app, uri, kind)
        val path = if(source.uri.scheme == "file") source.uri.path!! else source.uri.toString()
        val project = Project(title = source.name, path = path, kind = kind,
            duration = (source.durationMs / 1000).toInt(), durationMs = source.durationMs, mimeType = source.mime,
            aspect = if(source.width > source.height) "16:9" else if(source.width == source.height) "1:1" else "9:16")
        try { persistProject(project) }
        catch(e: Exception) { if(!MediaAccess.external(path)) File(path).delete(); throw e }
        return project
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun exportStudio(document: EditorDocument, audioOnly: Boolean) {
        val original = selectedProject ?: return
        startWork("Exporting your edit", "Keep MotionMagic open. Your original file stays unchanged.") {
            val output = File(projectDir, "edit_${UUID.randomUUID()}.${if(audioOnly) "m4a" else "mp4"}")
            pendingOutputs.add(output)
            StudioExporter.export(app, document, output, audioOnly) {
                work.value = WorkState("Exporting your edit", it, "${(it * 100).toInt()}% · ${if(audioOnly) "AAC audio" else "H.264 video"}")
            }
            val project = original.copy(id = UUID.randomUUID().toString(), title = "${original.title.take(45)} · edited",
                path = output.path, kind = if(audioOnly) MediaKind.AUDIO else MediaKind.VIDEO,
                durationMs = document.exportDurationMs, duration = (document.exportDurationMs / 1000).toInt(),
                mimeType = if(audioOnly) "audio/mp4" else "video/mp4", createdAt = System.currentTimeMillis())
            try { persistProject(project) } catch(e: Exception) { output.delete(); throw e }
            selectedProject = project; go(Screen.RESULT); autoSave(project)
        }
    }

    private suspend fun copyUri(uri: Uri, maxBytes: Long, extension: String): File {
        val destination = File(projectDir, "${UUID.randomUUID()}$extension")
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
        val request = draft
        val needsPrompt = screen == Screen.TEXT || screen == Screen.REELS
        val validation = CreationValidation.validate(request.prompt, request.duration, request.aspect, needsPrompt)
        if (!validation.isValid) { failure = validation.message; return }
        val source = photo ?: ArtRepository.bitmap(app, selectedArt)
        startWork("Creating your preview", "Animating the photo on this device. No AI credits are used.") {
            val output = File(projectDir, "motion_${UUID.randomUUID()}.mp4")
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
                try { persistProject(project) } catch(e: Exception) { output.delete(); throw e }
                selectedProject = project
                go(Screen.RESULT)
                autoSave(project)
            } finally { if (bitmap !== source) bitmap.recycle() }
        }
    }

    fun generateVoice(style: String) {
        if (voiceText.isBlank() || voiceText.length > 500) { failure = "Enter between 1 and 500 characters for your voiceover."; return }
        val text = voiceText.trim()
        startWork("Creating voice sample", "Using your device’s speech engine. This is a test voice.") {
            val output = File(projectDir, "voice_${UUID.randomUUID()}.wav")
            pendingOutputs.add(output)
            DeviceVoiceEngine.synthesize(app, text, style, output)
            val project = Project(title = text.take(55), path = output.path, kind = MediaKind.AUDIO, style = style)
            try { persistProject(project) } catch(e: Exception) { output.delete(); throw e }
            selectedProject = project
            go(Screen.RESULT)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun exportEdit(options: EditOptions) {
        val original = selectedProject ?: return
        startWork("Exporting your edit", "Keep the app open while your video is rendered.") {
            val output = File(projectDir, "edit_${UUID.randomUUID()}.mp4")
            pendingOutputs.add(output)
            Media3Editor.export(app, File(original.path), output, options)
            val project = original.copy(id = UUID.randomUUID().toString(), title = "${original.title.take(45)} · edited",
                path = output.path, createdAt = System.currentTimeMillis(),
                duration = (((options.endMs ?: original.duration * 1000L) - options.startMs) / 1000).toInt().coerceAtLeast(1))
            try { persistProject(project) } catch(e: Exception) { output.delete(); throw e }
            selectedProject = project
            go(Screen.RESULT)
            autoSave(project)
        }
    }

    private suspend fun persistProject(project: Project) {
        withContext(Dispatchers.IO) { store.upsertProject(project) }
        pendingOutputs.remove(File(project.path))
        projects = withContext(Dispatchers.IO) { store.projects() }
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
        if (settings.autoSave && Build.VERSION.SDK_INT >= 29 && project.kind == MediaKind.VIDEO) {
            try { LocalMediaEngine.exportToGallery(app, MediaAccess.uri(project.path), "MotionMagic_${project.id.take(8)}.mp4", project.mimeType); message("Saved to your gallery") }
            catch(e: Exception) { message("Project saved. Gallery export failed; use Download to try again.") }
        }
    }
    fun exportToGallery(project: Project) = startWork("Saving to gallery") {
        LocalMediaEngine.exportToGallery(app, MediaAccess.uri(project.path), "MotionMagic_${project.id.take(8)}.mp4", project.mimeType)
        message("Saved to Movies / MotionMagic AI")
    }
    fun saveDocument(project: Project, uri: Uri) = startWork("Saving file") {
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri)?.use { out -> MediaAccess.open(app, MediaAccess.uri(project.path)).use { MediaAccess.copy(it, out) } }
                ?: throw IOException("Could not write to the selected location.")
        }
        message("File saved")
    }
    fun share(project: Project): Intent? = try {
        require(MediaAccess.external(project.path) || File(project.path).isFile) { "This media file is no longer available." }
        val uri = if(MediaAccess.external(project.path)) Uri.parse(project.path) else FileProvider.getUriForFile(app, "${app.packageName}.files", File(project.path))
        LocalMediaEngine.shareIntent(uri).apply { type = project.mimeType }
    } catch(e: Exception) { failure = e.message ?: "Could not share this file."; null }

    fun delete(project: Project) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = File(project.path)
                    if(!MediaAccess.external(project.path)) {
                    require(file.canonicalPath.startsWith(projectDir.canonicalPath + File.separator)) { "This file is outside the project folder." }
                    if(file.exists() && !file.delete()) throw IOException("Could not delete this file. Close its player and try again.")
                    }
                    store.deleteProject(project.id)
                }
                projects = withContext(Dispatchers.IO) { store.projects() }
                if (selectedProject?.id == project.id) { selectedProject = null; tab(Screen.PROJECTS) }
                message("Project deleted. Exported gallery copies are kept.")
            } catch(e: Exception) { failure = e.message ?: "Could not delete the project." }
        }
    }

    fun cancelWork() { activeJob?.cancel(); message("Cancelled. No credits were used.") }

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
