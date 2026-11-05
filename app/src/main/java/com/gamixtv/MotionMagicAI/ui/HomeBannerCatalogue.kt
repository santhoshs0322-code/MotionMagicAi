package com.gamixtv.MotionMagicAI.ui

import android.content.Context
import com.gamixtv.MotionMagicAI.data.HomeBanner
import org.json.JSONArray

internal object HomeBannerCatalogue {
    fun bundled(context: Context): List<HomeBanner> = parse(JSONArray(context.assets.open("home_banners/catalog.json").bufferedReader().use { it.readText() }))
    fun parse(rows: JSONArray): List<HomeBanner> = (0 until minOf(rows.length(), 20)).map { index ->
        val row = rows.getJSONObject(index)
        val bundled = row.optJSONObject("bundled_image") ?: row.takeIf { it.has("file") && it.has("sha256") }
        HomeBanner(row.getString("id"), row.getString("title"), row.optString("destination", "templates"),
            row.optInt("display_seconds", 4).coerceIn(3, 15),
            row.optString("image_url").takeIf { it.isNotBlank() && it != "null" },
            bundled?.optString("file"), bundled?.optString("sha256"), row.optString("updated_at", row.optString("sha256")))
    }
}
