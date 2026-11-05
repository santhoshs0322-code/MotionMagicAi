package com.gamixtv.MotionMagicAI.ui

import android.content.Context
import com.gamixtv.MotionMagicAI.data.CreationMode
import org.json.JSONArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gamixtv.MotionMagicAI.MotionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun ObserveTemplateCatalogue(vm: MotionViewModel) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, vm) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refreshShowcase(force = true)
            while(isActive) { delay(30_000); vm.refreshShowcase() }
        }
    }
}

/** Bundled rows are only initial previews. A successful server feed, even empty, replaces them. */
internal object ImageTemplateCatalogue {
    @Volatile private var hashes: Map<String, Pair<String, String>>? = null
    fun bundledPath(context: Context, filename: String?, hash: String?): String? {
        if(filename == null || hash == null) return null
        val known = hashes ?: synchronized(this) {
            hashes ?: listOf("image_templates", "home_banners").flatMap { folder ->
                val rows = JSONArray(context.assets.open("$folder/catalog.json").bufferedReader().use { it.readText() })
                (0 until rows.length()).map { i -> rows.getJSONObject(i).let { it.getString("file") to ("$folder/${it.getString("file")}" to it.getString("sha256")) } }
            }.toMap().also { hashes = it }
        }
        return known[filename]?.takeIf { it.second == hash }?.first
    }
    fun bundled(context: Context): List<Template> = parse(JSONArray(context.assets.open("image_templates/catalog.json").bufferedReader().use { it.readText() }))

    fun parse(rows: JSONArray): List<Template> = (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        val mode = when(row.optString("mode")) {
            "create_image" -> CreationMode.CREATE_IMAGE
            "image_to_video" -> CreationMode.IMAGE_TO_VIDEO
            "text_to_video" -> CreationMode.TEXT_TO_VIDEO
            else -> return@mapNotNull null
        }
        val category = row.optString("category", "Trending")
        val art = when {
            category.contains("anime", true) -> Artwork.Anime
            category.contains("travel", true) -> Artwork.Travel
            category.contains("wedding", true) -> Artwork.Love
            else -> Artwork.Cinematic
        }
        val bundled = row.optJSONObject("bundled_poster") ?: row.takeIf { it.has("file") && it.has("sha256") }
        val filename = bundled?.optString("file")?.takeIf { it.matches(Regex("[0-9]{2}_[a-z_]+\\.jpg")) }
        Template(row.getString("title"), art, category, row.optString("aspect_ratio", "9:16"), row.optInt("duration", 0),
            remoteId = row.getString("id"), posterUrl = row.optString("poster_url").takeIf { it.isNotBlank() && it != "null" },
            videoUrl = row.optString("video_url").takeIf { it.isNotBlank() && it != "null" },
            exampleInputUrl = row.optString("example_input_url").takeIf { it.isNotBlank() && it != "null" },
            mediaRevision = row.optString("updated_at", row.optString("sha256")), prompt = row.getString("prompt"), mode = mode,
            modelId = row.optString("model_id", "qwen-image"), resolution = row.optString("resolution", "720p"),
            generatedAudio = row.optBoolean("generated_audio"), featured = row.optBoolean("featured", row.optInt("position", -1) == 0),
            requiresReference = row.optBoolean("requires_reference"), bundledPoster = filename, bundledPosterHash = bundled?.optString("sha256"))
    }
}
