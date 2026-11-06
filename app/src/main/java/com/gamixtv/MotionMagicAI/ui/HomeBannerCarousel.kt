package com.gamixtv.MotionMagicAI.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.data.HomeBanner
import com.gamixtv.MotionMagicAI.data.bannerIndex
import com.gamixtv.MotionMagicAI.data.bannerRecenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class BannerFrame(val banner: HomeBanner, val bitmap: Bitmap)

@Composable
internal fun HomeBannerCarousel(banners: List<HomeBanner>, active: Boolean = true, navigate: (Screen) -> Unit,
    imageLoader: BannerImageLoader? = null) {
    val context = LocalContext.current
    val loader = imageLoader ?: remember(context.applicationContext) { HomeBannerImages.get(context) }
    var lastFrame by remember { mutableStateOf<BannerFrame?>(null) }
    if(banners.isEmpty()) return
    // Keep a loaded image across catalogue updates, but never retain an unpublished banner.
    val fallback = lastFrame?.takeIf { frame -> banners.any { it.id == frame.banner.id } }
    key(banners.map { Triple(it.id, it.revision, it.displaySeconds) }) {
        BannerPages(banners, active, navigate, loader, fallback) { lastFrame = it }
    }
}

@Composable
private fun BannerPages(banners: List<HomeBanner>, active: Boolean, navigate: (Screen) -> Unit,
    loader: BannerImageLoader, fallback: BannerFrame?, shown: (BannerFrame) -> Unit) {
    val count = banners.size
    val initialIndex = banners.indexOfFirst { it.id == fallback?.banner?.id }.coerceAtLeast(0)
    val first = banners[initialIndex]
    val owner = LocalLifecycleOwner.current
    val firstFrame by produceState(loader.peek(first)?.let { BannerFrame(first, it) }, first.id, first.revision, first.imageUrl, loader, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(value == null && isActive) {
                loader.load(first)?.let { value = BannerFrame(first, it) }
                if(value == null) delay(15_000)
            }
        }
    }
    val available = firstFrame ?: fallback
    // On the first visit show no stock artwork or colored placeholder while the actual image loads.
    if(available == null) return

    val pager = rememberPagerState(initialPage = if(count > 1) initialIndex + 1 else 0,
        pageCount = { if(count > 1) count + 2 else 1 })
    val selected = bannerIndex(pager.settledPage, count)
    val interaction = remember { MutableInteractionSource() }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val accessibility = LocalAccessibilityManager.current
    val currentBanners by rememberUpdatedState(banners)
    val currentFrame = fallback ?: available

    LaunchedEffect(pager, count) {
        snapshotFlow { if(pager.isScrollInProgress) null else pager.settledPage }.collect { page ->
            if(page != null) bannerRecenter(page, count)?.let { pager.scrollToPage(it) }
        }
    }
    // Fetch the next image while the current one is being viewed, independently of template posters.
    LaunchedEffect(active, pager.settledPage, owner) {
        if(active && count > 1) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loader.load(currentBanners[(bannerIndex(pager.settledPage, count) + 1) % count])
        }
    }
    LaunchedEffect(active, dragged, pressed, pager.settledPage, owner) {
        if(active && !dragged && !pressed && count > 1) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(isActive) {
                val timeout = currentBanners[bannerIndex(pager.settledPage, count)].displaySeconds * 1000L
                val recommended = accessibility?.calculateRecommendedTimeoutMillis(timeout, containsIcons = true, containsText = true, containsControls = true) ?: timeout
                delay(recommended)
                // A slow or failed download keeps the current image on screen, with no blank slide.
                val next = currentBanners[(bannerIndex(pager.settledPage, count) + 1) % count]
                if(!pager.isScrollInProgress && loader.load(next) != null) pager.animateScrollToPage(pager.currentPage + 1)
            }
        }
    }
    HorizontalPager(pager, userScrollEnabled = count > 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(3f)
            .clip(RoundedCornerShape(18.dp)).testTag("home-banner-carousel")
            .semantics { stateDescription = "Banner ${selected + 1} of $count"; collectionInfo = CollectionInfo(1, count) }) { page ->
        val index = bannerIndex(page, count)
        val banner = banners[index]
        BannerSlide(banner, loader, currentFrame, page == pager.settledPage, shown,
            Modifier.fillMaxSize().testTag("home-banner-page-$page")
                .semantics { collectionItemInfo = CollectionItemInfo(0, 1, index, 1) }, interaction, navigate)
    }
}

@Composable
private fun BannerSlide(banner: HomeBanner, loader: BannerImageLoader, fallback: BannerFrame, selected: Boolean,
    shown: (BannerFrame) -> Unit, modifier: Modifier, interaction: MutableInteractionSource, navigate: (Screen) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val bitmap by produceState(loader.peek(banner), banner.id, banner.revision, banner.imageUrl, loader, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(value == null && isActive) {
                loader.load(banner)?.let { value = it }
                if(value == null) delay(15_000)
            }
        }
    }
    LaunchedEffect(bitmap, selected, banner) {
        if(selected) bitmap?.let { shown(BannerFrame(banner, it)) }
    }
    val frame = bitmap?.let { BannerFrame(banner, it) } ?: fallback
    Image(frame.bitmap.asImageBitmap(), frame.banner.title, modifier.clickable(interactionSource = interaction, indication = null) {
        navigate(when(frame.banner.destination) {
            "create_image" -> Screen.CREATE_IMAGE
            "image_to_video" -> Screen.IMAGE
            "text_to_video" -> Screen.TEXT
            else -> Screen.TEMPLATES
        })
    }, contentScale = ContentScale.Crop)
}
