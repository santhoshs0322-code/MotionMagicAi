package com.gamixtv.MotionMagicAI.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

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
    fun projects(accountId: String): List<Project> {
        lastReadWarning = null
        val raw = readString(projectKey(accountId), "[]")
        return try {
            decodeProjects(raw)
        } catch (_: JSONException) {
            lastReadWarning = "Some project information could not be read. Your media files have not been deleted."
            try {
                decodeProjects(readString(projectKey(accountId, "backup"), "[]"))
            } catch (_: JSONException) {
                emptyList()
            }
        }
    }

    @Synchronized
    fun upsertProject(accountId: String, project: Project) {
        require(project.id.isNotBlank()) { "Project ID cannot be empty." }
        require(project.title.isNotBlank()) { "Project title cannot be empty." }
        require(project.path.isNotBlank()) { "A project must point to a media file." }
        require(project.createdAt >= 0 && project.duration >= 0) { "Invalid project metadata." }
        val updated = projects(accountId).filterNot { it.id == project.id } + project
        writeProjects(accountId, updated)
    }

    /** Deletes the library record only; the caller handles file removal separately. */
    @Synchronized
    fun deleteProject(accountId: String, id: String) {
        writeProjects(accountId, projects(accountId).filterNot { it.id == id })
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
            themeMode = runCatching { ThemeMode.valueOf(json.optString("themeMode", fallback.themeMode.name)) }.getOrDefault(fallback.themeMode),
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
            .put("themeMode", value.themeMode.name)
        commit(preferences.edit().putString(SETTINGS, json.toString()))
    }

    @Synchronized
    fun draft(key: String = "video", accountId: String? = null): CreationDraft {
        val fallback = CreationDraft(aspect = settings().defaultAspect)
        val json = accountId?.let { readObject(draftKey(key, it)) } ?: return fallback
        if(key == "long-story") return CreationDraft(
            modelId = "long-story", resolution = "1080p", generatedAudio = true,
            prompt = json.optString("prompt", ""),
            duration = json.optInt("duration", 60).takeIf { it in listOf(60,120,180,240,300) } ?: 60,
            aspect = json.optString("aspect", "9:16").takeIf { it in listOf("9:16", "16:9", "1:1") } ?: "9:16",
        )
        val model = VideoModelCatalog.find(json.optString("modelId", fallback.modelId))
        val imageModel = ImageModelCatalog.find(json.optString("imageModelId", fallback.imageModelId))
        return CreationDraft(
            // Keep the user's text intact, even if invalid, so validation can explain it.
            prompt = json.optString("prompt", ""),
            selectedStyle = json.optString("selectedStyle", fallback.selectedStyle)
                .ifBlank { fallback.selectedStyle },
            duration = json.optInt("duration", fallback.duration)
                .takeIf { it in model.durations } ?: model.durations.min(),
            aspect = json.optString("aspect", fallback.aspect)
                .takeIf { it in (if(key == "image-generation") ImageModelCatalog.aspects else CreationValidation.supportedAspects) } ?: fallback.aspect,
            modelId = model.id,
            resolution = json.optString("resolution", fallback.resolution)
                .takeIf { it in model.resolutions } ?: model.resolutions.first(),
            generatedAudio = json.optBoolean("generatedAudio", fallback.generatedAudio) && model.supportsGeneratedAudio,
            imageQuality = json.optString("imageQuality", "1K").takeIf { it in imageModel.resolutions } ?: imageModel.resolutions.first(),
            audioPrompt = json.optString("audioPrompt", ""),
            imageModelId = imageModel.id,
            outputCount = json.optInt("outputCount", 1).coerceIn(1, 4),
            templateId = json.optString("templateId").takeIf { it.isNotBlank() && it != "null" },
            requiresReference = json.optBoolean("requiresReference"),
        )
    }

    @Synchronized
    fun saveDraft(value: CreationDraft, key: String = "video", accountId: String) {
        val json = JSONObject()
            .put("prompt", value.prompt)
            .put("selectedStyle", value.selectedStyle)
            .put("duration", value.duration)
            .put("aspect", value.aspect)
            .put("modelId", value.modelId)
            .put("resolution", value.resolution)
            .put("generatedAudio", value.generatedAudio)
            .put("imageQuality", value.imageQuality)
            .put("audioPrompt", value.audioPrompt)
            .put("imageModelId", value.imageModelId)
            .put("outputCount", value.outputCount)
            .put("templateId", value.templateId)
            .put("requiresReference", value.requiresReference)
        commit(preferences.edit().putString(draftKey(key, accountId), json.toString()))
    }

    @Synchronized
    fun clearDraft(key: String = "video", accountId: String) {
        commit(preferences.edit().remove(draftKey(key, accountId)))
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

    private fun draftKey(key: String, accountId: String): String {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "Invalid draft key." }
        return "draft_${accountHash(accountId)}_$key"
    }

    private fun projectKey(accountId: String, suffix: String = "") =
        "projects_${accountHash(accountId)}" + if(suffix.isEmpty()) "" else "_$suffix"

    private fun accountHash(accountId: String): String {
        require(accountId.isNotBlank()) { "Sign in before accessing saved projects." }
        return MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()).joinToString("") { "%02x".format(it) }
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
            json.optLong("durationMs", duration * 1000L), json.optString("mimeType", if(kind == MediaKind.AUDIO) "audio/wav" else "video/mp4"),
            json.optString("storyRoot").takeUnless { it.isBlank() || it == "null" }, json.optInt("storyPart", 0))
    }

    private fun writeProjects(accountId: String, projects: List<Project>) {
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
                .put("style", project.style).put("durationMs", project.durationMs).put("mimeType", project.mimeType)
                .put("storyRoot", project.storyRoot).put("storyPart", project.storyPart))
        }
        val editor = preferences.edit()
        val key = projectKey(accountId)
        val previous = readString(key, "[]")
        // Preserve damaged metadata for recovery instead of silently overwriting it.
        if (lastReadWarning != null) editor.putString(projectKey(accountId, "recovery"), previous)
        else editor.putString(projectKey(accountId, "backup"), previous)
        commit(editor.putString(key, json.toString()))
    }

    private fun commit(editor: SharedPreferences.Editor) {
        if (!editor.commit()) throw StoreWriteException("Could not save your changes. Free some device storage and try again.")
    }

    private companion object {
        const val SETTINGS = "settings"
        const val TEST_MODE = "test_mode"
    }
}
