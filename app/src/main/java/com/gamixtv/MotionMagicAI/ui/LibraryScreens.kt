package com.gamixtv.MotionMagicAI.ui

import android.os.Build
import android.app.Activity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.BuildConfig
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.cloud.CloudJob
import com.gamixtv.MotionMagicAI.data.MediaKind
import com.gamixtv.MotionMagicAI.data.Project
import com.gamixtv.MotionMagicAI.data.CreationMode
import com.gamixtv.MotionMagicAI.data.ImageModelCatalog
import com.gamixtv.MotionMagicAI.data.VideoModelCatalog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Template(val name: String, val art: Artwork, val category: String, val aspect: String = "9:16", val duration: Int = 5,
    val remoteId: String? = null, val posterUrl: String? = null, val videoUrl: String? = null,
    val exampleInputUrl: String? = null, val mediaRevision: String = "", val prompt: String? = null,
    val mode: CreationMode = CreationMode.IMAGE_TO_VIDEO, val modelId: String? = null,
    val resolution: String? = null, val generatedAudio: Boolean = false, val featured: Boolean = false) {
    fun promptFor(mode: CreationMode): String {
        if (!prompt.isNullOrBlank()) return prompt
        val scene = when(name) {
            "Cinematic" -> "A sunlit portrait with warm golden light, shallow depth of field and a soft cinematic atmosphere"
            "Anime" -> "An expressive anime character in a vivid illustrated world, soft light, detailed hand-painted scenery"
            "Love Story" -> "Two people sharing a quiet moment at sunset, romantic warm tones and a dreamy atmosphere"
            "Travel" -> "A traveler by a turquoise coastline, bright summer light, ocean views and a relaxed holiday mood"
            "Birthday" -> "A joyful birthday portrait with a colorful cake, soft glowing lights and festive decorations"
            else -> "A playful 3D character with expressive eyes, colorful surroundings and soft studio lighting"
        }
        return if(mode == CreationMode.CREATE_IMAGE) "$scene. Carefully composed, detailed illustration or photography."
        else if(mode == CreationMode.IMAGE_TO_VIDEO) "Preserve the subject from the photo. ${when(name) {
            "Travel" -> "A slow panoramic camera movement with a gentle sea breeze."
            "Birthday" -> "A gentle camera push-in as festive lights shimmer softly."
            else -> "Slow cinematic push-in, natural subtle movement and soft shifting light."
        }}"
        else "$scene. A slow camera push-in with gentle, natural motion."
    }
}
val templates = listOf(Template("Cinematic", Artwork.Cinematic, "Reels"), Template("Anime", Artwork.Anime, "Reels"),
    Template("Love Story", Artwork.Love, "Love"), Template("Travel", Artwork.Travel, "Travel"),
    Template("Birthday", Artwork.Birthday, "Birthday"), Template("3D Cartoon", Artwork.Cartoon, "Reels"))

@Composable
fun HomeScreen(vm: MotionViewModel) = ReferenceCatalogScreen(vm, templatePage = false)

@Composable
fun TemplatesScreen(vm: MotionViewModel) = ReferenceCatalogScreen(vm, templatePage = true)

@Composable
fun ProjectsScreen(vm: MotionViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var deletion by remember { mutableStateOf<Project?>(null) }
    val saved = vm.projects.sortedByDescending { it.createdAt }.distinctBy { it.id }
        .filter { it.title.contains(query, ignoreCase = true) }
    val savedCloudIds = vm.projects.mapNotNull { it.id.removePrefix("cloud_").takeIf { id -> it.id.startsWith("cloud_") && id.isNotBlank() } }.toSet()
    val pending = vm.cloudJobs.distinctBy { it.id }
        .filter { it.id !in savedCloudIds && it.title.contains(query, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Made by you", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("${vm.projects.size} saved · ${vm.cloudJobs.count { it.isGenerating }} of 3 generating", color = Muted, fontSize = 13.sp)
                }
            }
        }
        item { OutlinedButton(onClick = vm::resumeCloudGeneration, modifier = Modifier.fillMaxWidth()) { Text("Refresh projects") } }
        if(vm.projects.isEmpty() && vm.cloudJobs.isEmpty()) {
            item { EmptyState(Icons.Rounded.VideoLibrary, "Your ideas live here", "Create your first image, video or voice clip.", "Create a video") { vm.go(Screen.IMAGE) } }
        } else {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search projects") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = PanelShape)
            }
            if(saved.isEmpty() && pending.isEmpty()) item { Text("No projects match “$query”.", color = Muted, modifier = Modifier.padding(vertical = 30.dp)) }
            items(pending, key = { "job-${it.id}" }) { job ->
                CloudJobCard(job, vm::resumeCloudGeneration, { vm.downloadCloudResult(job.id) })
            }
            items(saved, key = { "project-${it.id}" }) { project ->
                Surface(onClick = { vm.open(project) }, color = Panel, shape = PanelShape) {
                    Column {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProjectThumbnail(project.path, project.kind == MediaKind.VIDEO)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(project.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(if(!com.gamixtv.MotionMagicAI.media.MediaAccess.external(project.path) && !File(project.path).isFile) "File missing" else "${when(project.kind) { MediaKind.VIDEO -> "${project.duration}s · ${project.aspect}"; MediaKind.IMAGE -> "Image"; else -> "Voice sample" }} · ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(project.createdAt))}",
                                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                            if(project.storyPart > 0) Text("Story scene ${project.storyPart + 1}", color = Color(0xFFFFBDE4), fontSize = 11.sp)
                        }
                        IconButton(onClick = { deletion = project }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${project.title}", tint = Muted, modifier = Modifier.size(21.dp)) }
                    }
                    if(project.kind == MediaKind.VIDEO) Row(Modifier.fillMaxWidth().padding(start = 78.dp, end = 12.dp, bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { vm.continueStory(project) }) { Text("Continue story", color = Color(0xFFFFBDE4), fontSize = 11.sp) }
                        if(vm.projects.count { it.id == (project.storyRoot ?: project.id) || it.storyRoot == (project.storyRoot ?: project.id) } >= 2)
                            TextButton(onClick = { vm.exportStory(project) }) { Text("Export story", color = Color(0xFF75DFFF), fontSize = 11.sp) }
                    }
                    }
                }
            }
        }
    }
    deletion?.let { project -> DeleteDialog(project.title, { deletion = null }) { deletion = null; vm.delete(project) } }
}

@Composable
private fun CloudJobCard(job: CloudJob, onRefresh: () -> Unit, onDownload: () -> Unit) {
    Surface(color = Panel, shape = PanelShape, border = BorderStroke(1.dp, Color(0xFF6942A5))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectThumbnail(job.previewPath, job.previewVideo, 72.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(if(job.kind == "image") "Creating your image" else "Creating your video", color = Violet, fontSize = 11.sp)
                }
            }
            Text(job.statusLabel, color = Color(0xFFDAC8F2), fontSize = 12.sp)
            if(job.isActive || job.isReady) {
                LinearProgressIndicator(progress = { job.progressPercent / 100f }, modifier = Modifier.fillMaxWidth(), color = Violet, trackColor = Color(0xFF322645))
                Text(if(job.isReady) "100% · generation completed" else "${job.progressPercent}% · estimated progress; updates after each stage", color = Muted, fontSize = 10.sp)
            }
            if(job.isReady) TextButton(onClick = onDownload) { Text("Save to My Projects") }
            else if(job.isActive) TextButton(onClick = onRefresh) { Text("Check status") }
        }
    }
}

@Composable
fun DeleteDialog(title: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete project?") },
        text = { Text("“$title” will be removed from this app. Copies already saved to your gallery will remain.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFFF8190)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep project", color = Color(0xFFDCA8FF)) } },
        shape = RoundedCornerShape(24.dp), containerColor = Color(0xFF1D112D))
}

@Composable
fun ProfileScreen(vm: MotionViewModel) {
    LaunchedEffect(vm.activeAccountId) { vm.refreshBilling() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(70.dp).clip(CircleShape).background(MagicGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, null, Modifier.size(38.dp))
            }
            Column(Modifier.padding(start = 17.dp)) {
                Text("MotionMagic Studio", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Your studio, on this device", color = Muted, fontSize = 12.sp)
            }
        }
        Column(Modifier.fillMaxWidth().clip(PanelShape).background(Brush.horizontalGradient(listOf(Color(0xFF641B88), Color(0xFF2723A6)))).padding(18.dp)) {
            Text("CURRENT WORKSPACE", fontSize = 10.sp, letterSpacing = 1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Local Studio", fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("Only available tools are listed", fontSize = 11.sp, color = Color(0xFFDBCEEF)) }
                TextButton(onClick = { vm.go(Screen.PLANS) }) { Text("Services", color = Color.White) }
            }
            Text("${vm.walletCredits?.toString() ?: "…"} credits available", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Free: ${vm.freeCredits ?: "…"} · Purchased: ${vm.topupCredits ?: "…"}" +
                if((vm.monthlyCredits ?: 0) > 0) " · Previous membership: ${vm.monthlyCredits}" else "",
                color = Color(0xFFE5D4F5), fontSize = 12.sp)
        }
        com.gamixtv.MotionMagicAI.auth.AuthAccountCard()
        Surface(color = Panel, shape = PanelShape) {
            Column {
                SettingRow(Icons.Rounded.FolderOpen, "My Projects", "${vm.projects.size} saved locally", { vm.tab(Screen.PROJECTS) })
                SettingRow(Icons.Rounded.Settings, "Settings", onClick = { vm.go(Screen.SETTINGS) })
            }
        }
        Surface(color = Panel, shape = PanelShape) {
            Column {
                SettingRow(Icons.Rounded.HelpOutline, "Help & Support", onClick = { showHelp(vm) })
                SettingRow(Icons.Rounded.StarOutline, "Rate Us", onClick = { vm.notice("Thanks for trying MotionMagic", "This is a local test build and has no Google Play listing yet.") }, tint = Color(0xFFFFBF46))
                SettingRow(Icons.Rounded.FolderOpen, "Local workspace", onClick = { vm.notice("Local workspace", "Saved projects on this device are shown only for the signed-in account. Cloud generations belong to that account and can be recovered from My Projects. Device-only previews and voice clips are not uploaded or synced.") })
            }
        }
        TestNotice("Sign in for AI creation. Device Voice uses your installed speech engine.")
    }
}

@Composable
fun SettingsScreen(vm: MotionViewModel, onNotifications: (Boolean) -> Unit) {
    var ratioDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preferences", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Surface(color = Color(0xFF211E2A), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFF3B3547))) {
            Column {
                SettingRow(Icons.Rounded.Language, "Language", "English", { vm.notice("Language", "The interface is available in English in this build. Device voice availability depends on installed speech data.") })
                SettingRow(Icons.Rounded.HighQuality, "Video Quality", vm.settings.quality, { vm.notice("Video quality", "On-device photo animations use a 540-pixel short edge. AI video resolution is selected on the creation screen from the available provider options.") })
                SettingRow(Icons.Rounded.AspectRatio, "Default Aspect Ratio", vm.settings.defaultAspect, { ratioDialog = true })
                SettingRow(Icons.Rounded.NotificationsNone, "Notifications", onClick = { onNotifications(!vm.settings.notifications) }, trailing = {
                    Switch(vm.settings.notifications, onNotifications)
                })
                SettingRow(Icons.Rounded.SaveAlt, "Auto Save to Gallery", if(Build.VERSION.SDK_INT < 29) "Use Download on Android 8–9" else null,
                    onClick = { if(Build.VERSION.SDK_INT >= 29) vm.saveSettings(vm.settings.copy(autoSave = !vm.settings.autoSave))
                    else vm.notice("Save using Download", "On this Android version, use Download to choose where each video is saved.") }, trailing = {
                    Switch(vm.settings.autoSave && Build.VERSION.SDK_INT >= 29, { vm.saveSettings(vm.settings.copy(autoSave = it)) }, enabled = Build.VERSION.SDK_INT >= 29)
                })
            }
        }
        Text("Help and information", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        Surface(color = Color(0xFF211E2A), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFF3B3547))) {
            Column {
                SettingRow(Icons.Rounded.SupportAgent, "Help & Support", onClick = { showHelp(vm) })
                SettingRow(Icons.Rounded.PrivacyTip, "Privacy", onClick = { vm.notice("Your media and cloud generation", "Local projects are saved on your device. For AI creation, your chosen photo is uploaded to private cloud storage while preparing a quote. After you confirm generation, your prompt and reference photo are sent through the server to fal.ai and the selected model provider. Generated media is saved in cloud storage and downloaded to your device.\n\nGoogle sign-in shares your account ID, name, email and picture with the login server. Session tokens are encrypted with Android Keystore. Signing out clears the saved session but does not delete server records.\n\nDeleting a local project removes its local copy; cloud outputs, uploaded references and gallery copies remain. Sharing sends media to the app you choose. Device Voice uses the selected Android speech engine, whose processing depends on its installed voices and settings.") })
                SettingRow(Icons.Rounded.Description, "About this build", onClick = { vm.notice("MotionMagic AI", "Version ${BuildConfig.VERSION_NAME}\n\nChoose an enabled AI model to create images or videos. Review the server price before confirming. Device Voice does not use AI generation credits. Use media you have permission to upload and publish.") })
            }
        }
    }
    if(ratioDialog) AlertDialog(onDismissRequest = { ratioDialog = false }, title = { Text("Default aspect ratio") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("9:16", "16:9", "1:1").forEach { ratio -> ChoiceChip(ratio, vm.settings.defaultAspect == ratio, Modifier.fillMaxWidth()) {
                vm.saveSettings(vm.settings.copy(defaultAspect = ratio)); vm.updateDraft(vm.draft.copy(aspect = ratio)); ratioDialog = false
            } }
        }
    }, confirmButton = { TextButton(onClick = { ratioDialog = false }) { Text("Close", color = Color(0xFFDCA8FF)) } },
        shape = RoundedCornerShape(24.dp), containerColor = Color(0xFF1D112D))
}

@Composable
fun PlansScreen(vm: MotionViewModel) {
    val activity = LocalContext.current as? Activity
    val accents = listOf(Color(0xFF86EDC9), Color(0xFFD8ACFF))
    LaunchedEffect(Unit) { vm.refreshCloudCatalog(); vm.refreshBilling() }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B102B), Ink, Ink)))
        .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(32.dp).background(MagicGradient, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = Color.White)
            }
            Text("MOTIONMAGIC STUDIO", color = Color(0xFFD5B4F7), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Create your way", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text("Free tools. Pay for each AI creation.", color = Color(0xFFCCBCD9), fontSize = 14.sp)
        }
        Surface(color = Color(0xFF241238), shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, Color(0xFFAD42E4))) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = Color(0xFFFFD052), modifier = Modifier.size(30.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text("${vm.walletCredits?.toString() ?: "…"} credits", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("200 free credits on your first sign-in · purchased credits never expire", color = Color(0xFFD7BCEB), fontSize = 11.sp)
                }
            }
        }
        Text("Credit packs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Choose any enabled model. The exact credit price appears before generation. Google Play shows the pack price before payment.", color = Muted, fontSize = 12.sp)
        vm.creditProducts.forEach { (sku, amount) ->
            val mostPopular = sku == com.gamixtv.MotionMagicAI.billing.PlayBilling.TOPUP_500
            Surface(color = if (mostPopular) Color(0xFF391452) else Color(0xFF2A1740), shape = RoundedCornerShape(18.dp), border = BorderStroke(if (mostPopular) 2.dp else 1.dp, if (mostPopular) Color(0xFFF454D7) else Color(0xFFA840D9))) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = Color(0xFFFFD052))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("${"%,d".format(amount)} credits", fontWeight = FontWeight.Bold)
                        if (mostPopular) Text("★ Most popular", color = Color(0xFFFFD052), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("One-time purchase · credits never expire", color = Color(0xFFCDB8E2), fontSize = 11.sp)
                    }
                    TextButton(enabled = vm.billingReady && !vm.billingBusy, onClick = { if(activity == null) vm.message("Open the Android app to buy credits.") else vm.buy(activity, sku) }) { Text(if(vm.buyingProductId == sku) "Opening Play…" else "Buy") }
                }
            }
        }
        if(vm.billingLoading && vm.creditProducts.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFFC1A6E8))
                Text("Loading credit packs…", color = Muted, fontSize = 12.sp)
            }
            repeat(4) {
                Surface(color = Color(0xFF211C2B), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(88.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(130.dp).height(14.dp).background(Color(0xFF3A3149), RoundedCornerShape(5.dp)))
                        Box(Modifier.width(200.dp).height(10.dp).background(Color(0xFF30293D), RoundedCornerShape(5.dp)))
                    }
                }
            }
        }
        vm.billingError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.refreshBilling(force = true) }) { Text("Retry") }
            }
        }
        if(vm.billingReady && vm.creditProducts.isEmpty()) Text("Credit packs are currently unavailable.", color = Muted, fontSize = 12.sp)
        Text("Available AI models", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if(vm.cloudRates.isEmpty()) Text(vm.cloudCatalogStatus, color = Muted, fontSize = 12.sp)
        vm.cloudRates.groupBy { it.modelId }.values.forEach { variants ->
            val cheapest = variants.minBy { it.minimumPricePaise }
            Surface(color = Color(0xFF20122F), shape = PanelShape) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(cheapest.kind == "image") ImageModelCatalog.find(cheapest.modelId).title else VideoModelCatalog.find(cheapest.modelId).title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(variants.map { it.size.uppercase() }.distinct().joinToString(" · "), color = Muted, fontSize = 11.sp)
                        Text("Available with enough credits", color = Color(0xFFFFCA67), fontSize = 10.sp)
                    }
                    Text("From ${com.gamixtv.MotionMagicAI.cloud.credits(cheapest.minimumPricePaise)}\n${if(cheapest.kind == "image") "per image" else "per ${cheapest.minimumUnits}s video"}", color = accents[1], fontSize = 12.sp, textAlign = TextAlign.End)
                }
            }
        }
        Text("The final quote depends on the model, resolution, duration and generated sound. Review it before confirming. Play verifies every payment on the server.", color = Color(0xFFBAA6CC), fontSize = 12.sp, lineHeight = 19.sp)
        GradientButton("Back to studio", { vm.tab(Screen.HOME) }, icon = Icons.Rounded.Home)
    }
}

@Composable
private fun PlanPurchaseCard(title: String, price: String, credits: String, color: Color, current: Boolean, modifier: Modifier, buy: () -> Unit) {
    Surface(modifier, color = Color(0xFF1B112B), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, color.copy(alpha = .5f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Star, null, tint = color, modifier = Modifier.size(20.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(price, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(credits, color = Muted, fontSize = 10.sp, lineHeight = 13.sp)
            TextButton(onClick = buy, enabled = !current, contentPadding = PaddingValues(0.dp)) { Text(if(current) "Current plan" else "Choose") }
        }
    }
}

@Composable
private fun PlanHeader(name: String, accent: Color, current: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxHeight().background(accent.copy(alpha = .055f)).padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)) {
            Box(Modifier.size(31.dp).background(accent.copy(alpha = .16f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(if(name == "Free") Icons.Rounded.CheckCircle else Icons.Rounded.Star, "$name plan", tint = accent, modifier = Modifier.size(21.dp))
            }
            Text(name, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = (-.3).sp)
            Text(if(current) "INCLUDED" else "PAY AS YOU CREATE", color = accent.copy(alpha = .9f), fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = .3.sp)
        }
}

@Composable
private fun PricingCell(value: String, color: Color, modifier: Modifier, label: Boolean = false) {
        Box(modifier.fillMaxHeight().background(if(label) Color.Transparent else color.copy(alpha = .035f)).padding(horizontal = if(label) 12.dp else 4.dp, vertical = 8.dp), contentAlignment = if(label) Alignment.CenterStart else Alignment.Center) {
            if(value == "✓") {
                Box(Modifier.size(25.dp).background(color.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, "Included", Modifier.size(16.dp), tint = color)
                }
            } else Text(value, color = if(value == "—") Color(0xFF997DAE) else color, fontSize = if(label) 11.sp else 12.sp,
                lineHeight = 16.sp, fontWeight = if(label) FontWeight.Medium else FontWeight.SemiBold)
        }
}
