package com.gamixtv.MotionMagicAI.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.cloud.CloudThumbnailSource
import com.gamixtv.MotionMagicAI.data.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(vm: MotionViewModel) {
    val account = vm.activeAccountId
    var limit by rememberSaveable(account) { mutableIntStateOf(30) }
    var query by rememberSaveable(account) { mutableStateOf("") }
    var filter by rememberSaveable(account) { mutableStateOf(ProjectFilter.ALL) }
    var selectedKey by remember(account) { mutableStateOf<String?>(null) }
    var deletion by remember(account) { mutableStateOf<Project?>(null) }
    val local = remember(vm.projects) { vm.projects.distinctBy { it.id }.sortedByDescending { it.createdAt } }
    val localPage = remember(local, limit) { local.take(limit) }
    val available = remember(localPage) { localPage.filter { File(it.path).isFile }.map { it.id }.toSet() }
    val entries = remember(localPage, vm.cloudJobs, available) { projectLibrary(localPage, vm.cloudJobs, available) }
    val visible = remember(entries, query, filter) { entries.filter { it.matches(filter, query) } }
    val more = vm.projectHasMore || limit < local.size
    ProjectLibraryGrid(visible, query, { query = it }, filter, { filter = it }, account,
        vm.cloudJobs.count { it.isGenerating }, vm.projectPageLoading, vm.projectPageError, more,
        onRefresh = vm::resumeCloudGeneration,
        onLoadMore = { limit += 30; vm.loadMoreProjects() },
        onOpen = { item -> if(item is LibraryItem.Device) vm.open(item.project) else selectedKey = item.key },
        onOptions = { selectedKey = it.key }, onCreate = { vm.go(Screen.IMAGE) })

    entries.firstOrNull { it.key == selectedKey }?.let { item ->
        ModalBottomSheet(onDismissRequest = { selectedKey = null }, containerColor = Panel) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(item.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val job = (item as? LibraryItem.Cloud)?.job
                val project = (item as? LibraryItem.Device)?.project
                ProjectThumbnail(project?.path ?: job?.previewPath, item.kind == MediaKind.VIDEO,
                    modifier = Modifier.fillMaxWidth().height(240.dp), remote = item.remoteThumbnail(account))
                Text(job?.statusLabel ?: "Saved on this device", color = Muted, fontSize = 12.sp)
                when {
                    project != null -> {
                        GradientButton("Open creation", { selectedKey = null; vm.open(project) }, icon = Icons.Rounded.OpenInNew)
                        if(project.kind == MediaKind.VIDEO) OutlinedButton(onClick = { selectedKey = null; vm.continueStory(project) }, Modifier.fillMaxWidth()) { Text("Continue story") }
                        TextButton(onClick = { selectedKey = null; deletion = project }) { Text("Delete local copy", color = StudioColors.error) }
                    }
                    job?.isReady == true -> GradientButton("Download to device", { selectedKey = null; vm.downloadCloudResult(job.id) }, icon = Icons.Rounded.Download)
                    job?.isGenerating == true -> Text("Generation continues on the server. You can leave this screen.", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
    deletion?.let { project -> DeleteDialog(project.title, { deletion = null }) { deletion = null; vm.delete(project) } }
}

@Composable
internal fun ProjectLibraryGrid(items: List<LibraryItem>, query: String, changeQuery: (String) -> Unit,
    filter: ProjectFilter, changeFilter: (ProjectFilter) -> Unit, account: String?, active: Int, loading: Boolean,
    error: String?, hasMore: Boolean, onRefresh: () -> Unit, onLoadMore: () -> Unit,
    onOpen: (LibraryItem) -> Unit, onOptions: (LibraryItem) -> Unit, onCreate: () -> Unit) {
    val colors = StudioColors
    val columns = if(LocalConfiguration.current.fontScale > 1.3f) 2 else 3
    LazyVerticalGrid(GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("projects-grid"),
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your creations", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if(active > 0) "$active of 3 generating" else "Images, videos and voice · made by you", fontSize = 11.sp, color = colors.muted)
                }
                IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Rounded.Refresh, "Refresh projects") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ProjectFilter.entries) { option ->
                    FilterChip(selected = option == filter, onClick = { changeFilter(option) }, label = { Text(option.label) },
                        modifier = Modifier.testTag("filter-${option.name}"),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.selected,
                            selectedLabelColor = colors.text, selectedLeadingIconColor = colors.primary),
                        leadingIcon = if(option == filter) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(query, changeQuery, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Search loaded projects", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if(query.isNotEmpty()) IconButton(onClick = { changeQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") } })
        }
        if(items.isEmpty() && !loading) item(span = { GridItemSpan(maxLineSpan) }) {
            if(hasMore || filter != ProjectFilter.ALL || query.isNotBlank()) {
                Text("No matching creations in the loaded projects." + if(hasMore) " Load more to see older results." else "",
                    color = colors.muted, modifier = Modifier.padding(vertical = 30.dp, horizontal = 8.dp), fontSize = 13.sp)
            } else if(error == null) EmptyState(Icons.Rounded.Collections, "Your ideas live here", "Create your first image, video or voice clip.", "Create a video", onCreate)
        }
        items(items, key = { it.key }) { item -> ProjectGridTile(item, account, { onOpen(item) }, { onOptions(item) }) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if(loading) CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                error?.let { Text(it, color = colors.error, fontSize = 12.sp) }
                if(hasMore || error != null) OutlinedButton(onClick = onLoadMore, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if(error == null) "Load more projects" else "Retry loading projects")
                }
                else if(items.isNotEmpty() && !loading) Text("You're all caught up", color = colors.muted, fontSize = 11.sp)
            }
        }
    }
}

private fun LibraryItem.remoteThumbnail(account: String?): CloudThumbnailSource? {
    val job = (this as? LibraryItem.Cloud)?.job ?: return null
    val output = job.outputAssetId ?: return null
    if(account == null || job.state !in setOf("succeeded", "downloading")) return null
    return CloudThumbnailSource(output, account, job.thumbnailUrl, job.thumbnailState)
}

@Composable
private fun ProjectGridTile(item: LibraryItem, account: String?, open: () -> Unit, options: () -> Unit) {
    val job = (item as? LibraryItem.Cloud)?.job
    val project = (item as? LibraryItem.Device)?.project
    val colors = StudioColors
    Column(Modifier.testTag("project-${item.key}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(.8f).clip(RoundedCornerShape(12.dp)).clickable(onClick = open)) {
            ProjectThumbnail(project?.path ?: job?.previewPath, item.kind == MediaKind.VIDEO,
                modifier = Modifier.fillMaxSize(), remote = item.remoteThumbnail(account))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
            Text(when(item.kind) { MediaKind.IMAGE -> "IMAGE"; MediaKind.VIDEO -> "VIDEO"; MediaKind.AUDIO -> "VOICE" },
                color = Color.White, fontSize = 8.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = .4f), RoundedCornerShape(5.dp)).padding(4.dp))
            IconButton(onClick = options, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Icon(Icons.Rounded.MoreVert, "Options for ${item.title}", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when {
                    job?.state == "downloading" -> "Saving · ${job.progressPercent}%"
                    job?.isGenerating == true -> "Creating · ${job.progressPercent}%"
                    job?.state == "failed" -> "Failed · credits released"
                    job?.state == "cancelled" -> "Cancelled"
                    job?.isReady == true && job.thumbnailState in setOf("pending", "processing") -> "Saved · preview preparing"
                    job?.isReady == true && job.thumbnailState == "failed" -> "Saved · preview unavailable"
                    else -> "Saved"
                }, color = Color.White, fontSize = 9.sp, lineHeight = 13.sp)
                if(job?.isActive == true) LinearProgressIndicator(progress = { job.progressPercent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Color(0xFFD9A7FF), trackColor = Color.White.copy(alpha = .25f))
            }
        }
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, lineHeight = 15.sp,
            color = colors.text, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp))
    }
}
