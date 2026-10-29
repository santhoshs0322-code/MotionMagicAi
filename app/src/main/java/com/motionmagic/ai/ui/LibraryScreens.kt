package com.motionmagic.ai.ui

import android.os.Build

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmagic.ai.MotionViewModel
import com.motionmagic.ai.BuildConfig
import com.motionmagic.ai.Screen
import com.motionmagic.ai.data.MediaKind
import com.motionmagic.ai.data.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Template(val name: String, val art: Artwork, val category: String)
val templates = listOf(Template("Cinematic", Artwork.Cinematic, "Reels"), Template("Anime", Artwork.Anime, "Reels"),
    Template("Love Story", Artwork.Love, "Love"), Template("Travel", Artwork.Travel, "Travel"),
    Template("Birthday", Artwork.Birthday, "Birthday"), Template("3D Cartoon", Artwork.Cartoon, "Reels"))

@Composable
fun HomeScreen(vm: MotionViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(314f / 140f).clip(PanelShape).clickable { vm.go(Screen.IMAGE) }) {
            Art(Artwork.Banner, Modifier.fillMaxSize(), "Turn Photos Into Stunning Videos. Create, Edit, Animate with AI. Open image to video.")
        }
        val tools = listOf(
            Triple("Image to Video", Icons.Rounded.AddPhotoAlternate, Pair(Screen.IMAGE, Color(0xFFFA31A7))),
            Triple("Prompt Preview", Icons.Rounded.SmartDisplay, Pair(Screen.TEXT, Color(0xFF7445FF))),
            Triple("Photo Reels", Icons.Rounded.MotionPhotosOn, Pair(Screen.REELS, Color(0xFFFF763D))),
            Triple("Device Voice", Icons.Rounded.MicNone, Pair(Screen.VOICE, Color(0xFF149DFC))),
            Triple("Video Editor", Icons.Rounded.ContentCut, Pair(Screen.EDITOR, Color(0xFF05C487))),
            Triple("Templates", Icons.Rounded.GridView, Pair(Screen.TEMPLATES, Color(0xFF7243FE)))
        )
        tools.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                row.forEach { (label, icon, destination) ->
                    Surface(onClick = { vm.go(destination.first) }, modifier = Modifier.weight(1f).height(106.dp),
                        shape = RoundedCornerShape(18.dp), color = destination.second) {
                        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .05f), Color.Transparent))).padding(8.dp),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(icon, null, Modifier.size(35.dp), tint = Color.White)
                            Spacer(Modifier.height(11.dp))
                            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { vm.go(Screen.AUDIO_EDITOR) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.GraphicEq, null); Spacer(Modifier.width(8.dp)); Text("Audio Editor") }
        SectionTitle("Trending Templates", "See All") { vm.tab(Screen.TEMPLATES) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            templates.take(4).forEach { template ->
                Column(Modifier.weight(1f).clickable { vm.useTemplate(template.art, template.name) }) {
                    Art(template.art, Modifier.fillMaxWidth().aspectRatio(.85f), template.name)
                    Text(template.name, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 7.dp), overflow = TextOverflow.Ellipsis)
                }
            }
        }
        TestNotice()
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun TemplatesScreen(vm: MotionViewModel) {
    var category by rememberSaveable { mutableStateOf("All") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All", "Reels", "Love", "Travel", "Birthday")) { label -> ChoiceChip(label, category == label) { category = label } }
        }
        val filtered = templates.filter { category == "All" || it.category == category }
        filtered.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                row.forEach { template ->
                    Column(Modifier.weight(1f).clickable { vm.useTemplate(template.art, template.name) }) {
                        Art(template.art, Modifier.fillMaxWidth().aspectRatio(.85f), template.name)
                        Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(template.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Icon(Icons.Rounded.NorthEast, null, Modifier.size(17.dp), tint = Muted)
                        }
                    }
                }
                if(row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        TestNotice("Sample artwork from your design reference. Templates create local photo animations in this build.")
    }
}

@Composable
fun ProjectsScreen(vm: MotionViewModel, onImport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var deletion by remember { mutableStateOf<Project?>(null) }
    val items = vm.projects.sortedByDescending { it.createdAt }.filter { it.title.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Made by you", fontSize = 23.sp, fontWeight = FontWeight.Bold); Text("${vm.projects.size} saved creations", color = Muted, fontSize = 13.sp) }
            FilledTonalIconButton(onClick = onImport) { Icon(Icons.Rounded.FileUpload, "Import video") }
        }
        if(vm.projects.isEmpty()) {
            EmptyState(Icons.Rounded.VideoLibrary, "Your ideas live here", "Create your first video or import one to start editing.", "Create a video") { vm.go(Screen.IMAGE) }
            OutlinedButton(onClick = onImport, Modifier.fillMaxWidth()) { Text("Import a video") }
        } else {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search projects") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = PanelShape)
            if(items.isEmpty()) Text("No projects match “$query”.", color = Muted, modifier = Modifier.padding(vertical = 30.dp))
            items.forEach { project ->
                Surface(onClick = { vm.open(project) }, color = Panel, shape = PanelShape) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF29203F)), contentAlignment = Alignment.Center) {
                            Icon(if(project.kind == MediaKind.AUDIO) Icons.Rounded.GraphicEq else Icons.Rounded.PlayCircle,
                                null, Modifier.size(30.dp), tint = Violet)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(project.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(if(!com.motionmagic.ai.media.MediaAccess.external(project.path) && !File(project.path).isFile) "File missing" else "${if(project.kind == MediaKind.VIDEO) "${project.duration}s · ${project.aspect}" else "Voice sample"} · ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(project.createdAt))}",
                                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                        IconButton(onClick = { deletion = project }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${project.title}", tint = Muted, modifier = Modifier.size(21.dp)) }
                    }
                }
            }
        }
    }
    deletion?.let { project -> DeleteDialog(project.title, { deletion = null }) { deletion = null; vm.delete(project) } }
}

@Composable
fun DeleteDialog(title: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete project?") },
        text = { Text("“$title” will be removed from this app. Copies already saved to your gallery will remain.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFFF8190)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep project") } }, containerColor = Panel)
}

@Composable
fun ProfileScreen(vm: MotionViewModel) {
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
        }
        com.motionmagic.ai.auth.AuthAccountCard()
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
                SettingRow(Icons.Rounded.FolderOpen, "Local workspace", onClick = { vm.notice("Local workspace", "Projects stay on this device, regardless of the signed-in account. Google sign-in does not upload or sync projects.") })
            }
        }
        TestNotice("No account or API key is needed for this test build.")
    }
}

@Composable
fun SettingsScreen(vm: MotionViewModel, onNotifications: (Boolean) -> Unit) {
    var ratioDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(color = Panel, shape = PanelShape) {
            Column {
                SettingRow(Icons.Rounded.Language, "Language", "English", { vm.notice("Language", "The interface is available in English in this build. Device voice availability depends on installed speech data.") })
                SettingRow(Icons.Rounded.HighQuality, "Video Quality", vm.settings.quality, { vm.notice("Test video quality", "On-device previews use a 540-pixel short edge: 540×960, 960×540 or 540×540. Imported videos retain their source dimensions during editing. HD AI generation will require a live provider.") })
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
        Surface(color = Panel, shape = PanelShape) {
            Column {
                SettingRow(Icons.Rounded.SupportAgent, "Help & Support", onClick = { showHelp(vm) })
                SettingRow(Icons.Rounded.PrivacyTip, "Privacy", onClick = { vm.notice("Privacy in this test build", "Photos, videos, voice files and project metadata are stored on your device. This build does not send prompts or media to an AI provider. Optional Google sign-in sends a Google identity token to your login server, which stores your Google account ID, name, email and profile picture. The app encrypts its session token with Android Keystore. Signing out clears the saved sign-in; it does not delete the server account or local media.\n\nSelecting files uses Android’s file picker. Download creates a separate copy. Sharing sends the file only to the app you choose. Device speech uses installed offline voices when available; your selected system speech engine controls its processing.\n\nDeleting a project removes its internal media file. Gallery and shared copies remain. Uninstalling removes the app’s private projects and settings.\n\nA production privacy policy and data controls are required before enabling cloud processing.") })
                SettingRow(Icons.Rounded.Description, "About this build", onClick = { vm.notice("MotionMagic AI · Test Build", "Version ${BuildConfig.VERSION_NAME}\n\nLocal previews are photo animations; they are not AI-generated scenes. The app lists only completed local services.\n\nGoogle sign-in requires the dedicated Django API. Cloud generation and credit products stay hidden until their server configuration is verified. Use your own or licensed media for publishing.") })
            }
        }
        TestNotice("All generation runs in test mode. No paid AI requests are made.")
    }
    if(ratioDialog) AlertDialog(onDismissRequest = { ratioDialog = false }, title = { Text("Default aspect ratio") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("9:16", "16:9", "1:1").forEach { ratio -> ChoiceChip(ratio, vm.settings.defaultAspect == ratio, Modifier.fillMaxWidth()) {
                vm.saveSettings(vm.settings.copy(defaultAspect = ratio)); vm.updateDraft(vm.draft.copy(aspect = ratio)); ratioDialog = false
            } }
        }
    }, confirmButton = { TextButton(onClick = { ratioDialog = false }) { Text("Close") } }, containerColor = Panel)
}

@Composable
fun PlansScreen(vm: MotionViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MagicLogo(Modifier.size(70.dp)) }
        Text("Available now", fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("This build lists only services it can complete.", color = Muted, fontSize = 14.sp)
        listOf(
            Triple("Photo animation", "On device", "Create a 5, 10 or 15 second MP4 from a photo"),
            Triple("Video and audio editing", "On device", "Trim, split, speed, volume, text, filters, music and export"),
            Triple("Device voice", "On device", "Create speech using an installed Android voice")
        ).forEach { (name, availability, description) ->
            Surface(color = Panel, shape = PanelShape, border = BorderStroke(1.dp, Color(0xFF2A303C))) {
                Row(Modifier.fillMaxWidth().padding(19.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text(name, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text(description, color = Muted, fontSize = 12.sp) }
                    Text(availability, color = Violet, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
        TestNotice("Cloud generation and credit packs stay hidden until the Django API reports a configured provider, budget and verified Play products.")
        GradientButton("Back to studio", { vm.tab(Screen.HOME) }, icon = Icons.Rounded.Home)
    }
}
