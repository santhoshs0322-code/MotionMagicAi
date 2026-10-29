package com.motionmagic.ai.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Small, local metadata store. Media bytes are stored separately by the media layer.
 * No API keys, provider credentials, payment tokens, credit balances, or entitlements
 * belong here. Clearing this store does not erase files from the device gallery.
 */
class MotionStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "motionmagic_local_v1", Context.MODE_PRIVATE,
    )

    /** A recoverable read problem, suitable for an optional library warning. */
    @Volatile
    var lastReadWarning: String? = null
        private set

    @Synchronized
    fun projects(): List<Project> {
        lastReadWarning = null
        val raw = readString(PROJECTS, "[]")
        return try {
            decodeProjects(raw)
        } catch (_: JSONException) {
            lastReadWarning = "Some project information could not be read. Your media files have not been deleted."
            try {
                decodeProjects(readString(PROJECTS_BACKUP, "[]"))
            } catch (_: JSONException) {
                emptyList()
            }
        }
    }

    @Synchronized
    fun upsertProject(project: Project) {
        require(project.id.isNotBlank()) { "Project ID cannot be empty." }
        require(project.title.isNotBlank()) { "Project title cannot be empty." }
        require(project.path.isNotBlank()) { "A project must point to a media file." }
        require(project.createdAt >= 0 && project.duration >= 0) { "Invalid project metadata." }
        val updated = projects().filterNot { it.id == project.id } + project
        writeProjects(updated)
    }

    /** Deletes the library record only; the caller handles file removal separately. */
    @Synchronized
    fun deleteProject(id: String) {
        writeProjects(projects().filterNot { it.id == id })
    }

    @Synchronized
    fun settings(): AppSettings {
        val fallback = AppSettings()
        val json = readObject(SETTINGS) ?: return fallback
        return AppSettings(
            defaultAspect = json.optString("defaultAspect", fallback.defaultAspect)
                .takeIf { it in CreationValidation.supportedAspects } ?: fallback.defaultAspect,
            quality = json.optString("quality", fallback.quality).ifBlank { fallback.quality },
            notifications = json.optBoolean("notifications", fallback.notifications),
            autoSave = json.optBoolean("autoSave", fallback.autoSave),
        )
    }

    @Synchronized
    fun saveSettings(value: AppSettings) {
        require(value.defaultAspect in CreationValidation.supportedAspects) { "Unsupported default aspect ratio." }
        require(value.quality.isNotBlank()) { "Video quality cannot be blank." }
        val json = JSONObject()
            .put("defaultAspect", value.defaultAspect)
            .put("quality", value.quality)
            .put("notifications", value.notifications)
            .put("autoSave", value.autoSave)
        commit(preferences.edit().putString(SETTINGS, json.toString()))
    }

    @Synchronized
    fun draft(key: String = "video"): CreationDraft {
        val fallback = CreationDraft(aspect = settings().defaultAspect)
        val json = readObject(draftKey(key)) ?: return fallback
        return CreationDraft(
            // Keep the user's text intact, even if invalid, so validation can explain it.
            prompt = json.optString("prompt", ""),
            selectedStyle = json.optString("selectedStyle", fallback.selectedStyle)
                .ifBlank { fallback.selectedStyle },
            duration = json.optInt("duration", fallback.duration)
                .takeIf { it in CreationValidation.supportedDurations } ?: fallback.duration,
            aspect = json.optString("aspect", fallback.aspect)
                .takeIf { it in CreationValidation.supportedAspects } ?: fallback.aspect,
        )
    }

    @Synchronized
    fun saveDraft(value: CreationDraft, key: String = "video") {
        val json = JSONObject()
            .put("prompt", value.prompt)
            .put("selectedStyle", value.selectedStyle)
            .put("duration", value.duration)
            .put("aspect", value.aspect)
        commit(preferences.edit().putString(draftKey(key), json.toString()))
    }

    @Synchronized
    fun clearDraft(key: String = "video") {
        commit(preferences.edit().remove(draftKey(key)))
    }

    fun isTestMode(): Boolean = try {
        preferences.getBoolean(TEST_MODE, true)
    } catch (_: ClassCastException) {
        true
    }

    @Synchronized
    fun setTestMode(enabled: Boolean) {
        commit(preferences.edit().putBoolean(TEST_MODE, enabled))
    }

    private fun draftKey(key: String): String {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "Invalid draft key." }
        return "draft_$key"
    }

    private fun readObject(key: String): JSONObject? = try {
        val value = readString(key, "")
        if (value.isEmpty()) null else JSONObject(value)
    } catch (_: JSONException) {
        lastReadWarning = "Some saved preferences could not be read. Default options have been restored."
        null
    }

    private fun readString(key: String, default: String): String = try {
        preferences.getString(key, default) ?: default
    } catch (_: ClassCastException) {
        lastReadWarning = "Some saved information could not be read. Your media files have not been deleted."
        default
    }

    private fun decodeProjects(raw: String): List<Project> {
        val array = JSONArray(raw)
        val projects = buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index)
                val project = json?.let(::decodeProject)
                if (project != null) add(project)
                else lastReadWarning = "Some project records could not be read. Your media files have not been deleted."
            }
        }
        return projects.distinctBy { it.id }.sortedByDescending { it.createdAt }
    }

    private fun decodeProject(json: JSONObject): Project? {
        val id = json.optString("id", "")
        val title = json.optString("title", "")
        val path = json.optString("path", "")
        val kind = MediaKind.entries.firstOrNull { it.name == json.optString("kind") } ?: return null
        val createdAt = json.optLong("createdAt", -1)
        val duration = json.optInt("duration", 0)
        if (id.isBlank() || title.isBlank() || path.isBlank() || createdAt < 0 || duration < 0) return null
        return Project(id, title, path, kind, createdAt, duration,
            json.optString("aspect", "9:16"), json.optString("style", "Realistic"),
            json.optLong("durationMs", duration * 1000L), json.optString("mimeType", if(kind == MediaKind.AUDIO) "audio/wav" else "video/mp4"))
    }

    private fun writeProjects(projects: List<Project>) {
        val json = JSONArray()
        projects.sortedByDescending { it.createdAt }.forEach { project ->
            json.put(JSONObject()
                .put("id", project.id)
                .put("title", project.title)
                .put("path", project.path)
                .put("kind", project.kind.name)
                .put("createdAt", project.createdAt)
                .put("duration", project.duration)
                .put("aspect", project.aspect)
                .put("style", project.style).put("durationMs", project.durationMs).put("mimeType", project.mimeType))
        }
        val editor = preferences.edit()
        val previous = readString(PROJECTS, "[]")
        // Preserve damaged metadata for recovery instead of silently overwriting it.
        if (lastReadWarning != null) editor.putString(PROJECTS_RECOVERY, previous)
        else editor.putString(PROJECTS_BACKUP, previous)
        commit(editor.putString(PROJECTS, json.toString()))
    }

    private fun commit(editor: SharedPreferences.Editor) {
        if (!editor.commit()) throw StoreWriteException("Could not save your changes. Free some device storage and try again.")
    }

    private companion object {
        const val PROJECTS = "projects"
        const val PROJECTS_BACKUP = "projects_backup"
        const val PROJECTS_RECOVERY = "projects_recovery"
        const val SETTINGS = "settings"
        const val TEST_MODE = "test_mode"
    }
}
