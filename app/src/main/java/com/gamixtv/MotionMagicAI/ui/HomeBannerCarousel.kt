package com.gamixtv.MotionMagicAI.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.data.HomeBanner
import com.gamixtv.MotionMagicAI.data.bannerIndex
import com.gamixtv.MotionMagicAI.data.bannerRecenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun HomeBannerCarousel(banners: List<HomeBanner>, active: Boolean = true, navigate: (Screen) -> Unit) {
    if(banners.isEmpty()) return
    // Owner updates reset the sequence; periodic feed refreshes with the same revisions do not.
    key(banners.map { Triple(it.id, it.revision, it.displaySeconds) }) { BannerPages(banners, active, navigate) }
}

@Composable
private fun BannerPages(banners: List<HomeBanner>, active: Boolean, navigate: (Screen) -> Unit) {
    val count = banners.size
    val pager = rememberPagerState(initialPage = if(count > 1) 1 else 0, pageCount = { if(count > 1) count + 2 else 1 })
    val selected = bannerIndex(pager.settledPage, count)
    val interaction = remember { MutableInteractionSource() }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val owner = LocalLifecycleOwner.current
    val accessibility = LocalAccessibilityManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(pager, count) {
        snapshotFlow { if(pager.isScrollInProgress) null else pager.settledPage }.collect { page ->
            if(page != null) bannerRecenter(page, count)?.let { pager.scrollToPage(it) }
        }
    }
    LaunchedEffect(active, dragged, pressed, pager.settledPage, owner) {
        if(active && !dragged && !pressed && count > 1) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(isActive) {
                val timeout = banners[bannerIndex(pager.settledPage, count)].displaySeconds * 1000L
                delay(accessibility?.calculateRecommendedTimeoutMillis(timeout, containsIcons = true, containsText = true, containsControls = true) ?: timeout)
                if(!pager.isScrollInProgress) pager.animateScrollToPage(pager.currentPage + 1)
            }
        }
    }
    Column(Modifier.testTag("home-banner-carousel").semantics { stateDescription = "Banner ${selected + 1} of $count" }) {
        HorizontalPager(pager, userScrollEnabled = count > 1, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 12.dp,
            modifier = Modifier.semantics { collectionInfo = CollectionInfo(1, count) }) { page ->
            val index = bannerIndex(page, count)
            val banner = banners[index]
            val artwork = Template(banner.title, Artwork.Banner, "Banner", remoteId = "banner:${banner.id}", posterUrl = banner.imageUrl,
                mediaRevision = banner.revision, bundledPoster = banner.bundledFile, bundledPosterHash = banner.bundledHash)
            ShowcasePoster(artwork, Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(18.dp))
                .background(StudioColors.surface).testTag("home-banner-page-$page")
                .semantics { collectionItemInfo = CollectionItemInfo(0, 1, index, 1) }
                .clickable(interactionSource = interaction, indication = null) {
                    navigate(when(banner.destination) {
                        "create_image" -> Screen.CREATE_IMAGE
                        "image_to_video" -> Screen.IMAGE
                        "text_to_video" -> Screen.TEXT
                        else -> Screen.TEMPLATES
                    })
                }, "${banner.title}. Banner ${index + 1} of $count", ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if(count > 1) banners.forEachIndexed { index, _ ->
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(index + 1) } },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Show banner ${index + 1}"; this.selected = index == selected }) {
                    Box(Modifier.size(if(index == selected) 16.dp else 6.dp, 6.dp).background(
                        if(index == selected) StudioColors.primary else StudioColors.muted.copy(alpha = .4f), CircleShape))
                }
            }
            Text("${selected + 1} / $count", fontSize = 10.sp, color = StudioColors.muted, modifier = Modifier.padding(start = 8.dp).testTag("home-banner-position"))
        }
    }
}
