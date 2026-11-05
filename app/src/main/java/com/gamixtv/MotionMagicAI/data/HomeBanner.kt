package com.gamixtv.MotionMagicAI.data

data class HomeBanner(
    val id: String, val title: String, val destination: String, val displaySeconds: Int = 4,
    val imageUrl: String? = null, val bundledFile: String? = null, val bundledHash: String? = null,
    val revision: String = "",
)

/** Two sentinel pages make the wrap a forward slide; only count + 2 pages exist. */
internal fun bannerIndex(page: Int, count: Int): Int = if(count <= 1) 0 else Math.floorMod(page - 1, count)
internal fun bannerRecenter(page: Int, count: Int): Int? = when {
    count <= 1 -> null
    page == 0 -> count
    page == count + 1 -> 1
    else -> null
}
