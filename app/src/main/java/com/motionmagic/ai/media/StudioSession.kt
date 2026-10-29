package com.motionmagic.ai.media

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionmagic.ai.data.Project
import kotlinx.coroutines.*

/** Activity-owned session keeps undo history and edits when the screen rotates. */
class StudioSession(application: Application) : AndroidViewModel(application) {
    var document by mutableStateOf(EditorDocument()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var sourceInfo by mutableStateOf<SourceInfo?>(null); private set
    var tool by mutableStateOf("")
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private var history = EditorHistory(document)
    private var identity = ""
    private var load: Job? = null
    fun open(project: Project?, audio: Boolean) {
        val id = "${project?.id}:$audio"
        if (id == identity) return
        identity = id; load?.cancel(); error = null; sourceInfo = null; tool = ""
        document = EditorDocument(); history = EditorHistory(document); canUndo = false; canRedo = false
        if (project == null) { loading = false; return }
        loading = true
        load = viewModelScope.launch {
            try {
                val info = MediaAccess.inspect(getApplication(), MediaAccess.uri(project.path))
                require(if (audio) info.hasAudio else info.hasVideo) { "This media has no ${if(audio) "audio" else "video"} track. Import a different file." }
                ensureActive()
                sourceInfo = info
                document = EditorDocument(clips = listOf(EditorClip(project.path, info.durationMs, label = project.title)))
                history = EditorHistory(document)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Cannot open this file. Import it again." }
            finally { if (identity == id) loading = false }
        }
    }
    fun update(value: EditorDocument) { history.update(value); document = value; sync() }
    fun undo() { document = history.undo(); sync() }
    fun redo() { document = history.redo(); sync() }
    fun split(position: Long) { try { update(document.split(position)) } catch(e: Exception) { error = e.message } }
    fun removeSelected() {
        if (document.clips.size <= 1) { error = "Keep at least one clip, or import a different file."; return }
        update(document.copy(clips = document.clips.filterIndexed { i, _ -> i != document.selected }, selected = 0))
    }
    fun append(project: Project) {
        if (document.clips.size >= 32) { error = "This timeline supports up to 32 clips."; return }
        update(document.copy(clips = document.clips + EditorClip(project.path, project.durationMs, label = project.title), selected = document.clips.size))
    }
    fun dismissError() { error = null }
    private fun sync() { canUndo = history.canUndo; canRedo = history.canRedo }
}
