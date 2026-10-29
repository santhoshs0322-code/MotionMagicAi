package com.motionmagic.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmagic.ai.MotionViewModel
import com.motionmagic.ai.Screen

@Composable
fun CreateScreen(vm: MotionViewModel, onPickPhoto: () -> Unit) {
    val isImage = vm.screen == Screen.IMAGE
    val draft = vm.draft
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if(isImage) {
            Box(Modifier.fillMaxWidth().height(236.dp).clip(PanelShape)) {
                val photo = vm.photo
                if(photo != null) Image(photo.asImageBitmap(), "Selected photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Art(vm.selectedArt, Modifier.fillMaxSize(), "Sample photo")
                Text(vm.photoName, Modifier.align(Alignment.TopStart).padding(11.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(7.dp)).padding(7.dp), fontSize = 10.sp)
                FilledTonalButton(onClick = onPickPhoto, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.Black.copy(alpha = .7f), contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Change Photo", fontSize = 11.sp)
                }
            }
        } else {
            OutlinedTextField(draft.prompt, { vm.updateDraft(draft.copy(prompt = it.take(501))) },
                Modifier.fillMaxWidth().heightIn(min = 160.dp), placeholder = { Text("A girl walking on a beach at sunset, cinematic style, golden light…", color = Muted) },
                shape = PanelShape, supportingText = { Text("${draft.prompt.length}/500", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End) },
                isError = draft.prompt.length > 500, minLines = 4)
        }
        SectionTitle(if(isImage) "Choose Style" else "Select Style", "See All") { vm.go(Screen.TEMPLATES) }
        val styles = listOf("Realistic" to Artwork.Portrait, "Anime" to Artwork.Anime, "3D Cartoon" to Artwork.Cartoon, "Cinematic" to Artwork.Cinematic, "Fantasy" to Artwork.Fantasy)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(styles) { (label, art) ->
                Column(Modifier.width(77.dp).clickable { vm.updateDraft(draft.copy(selectedStyle = label)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = RoundedCornerShape(13.dp), border = BorderStroke(2.dp, if(draft.selectedStyle == label) Violet else Color.Transparent)) {
                        Art(art, Modifier.size(77.dp, 88.dp).padding(2.dp), label)
                    }
                    Text(label, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp), color = if(draft.selectedStyle == label) Color.White else Muted)
                }
            }
        }
        Text("Video Duration", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(5, 10, 15).forEach { seconds -> ChoiceChip("${seconds}s", draft.duration == seconds, Modifier.weight(1f)) { vm.updateDraft(draft.copy(duration = seconds)) } }
        }
        Text("Aspect Ratio", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("9:16" to "Reels", "16:9" to "YouTube", "1:1" to "Instagram").forEach { (ratio, name) ->
                Surface(onClick = { vm.updateDraft(draft.copy(aspect = ratio)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp), color = Panel,
                    border = BorderStroke(1.dp, if(draft.aspect == ratio) Violet else Color(0xFF262C38))) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if(ratio == "9:16") Icons.Rounded.StayCurrentPortrait else if(ratio == "16:9") Icons.Rounded.StayCurrentLandscape else Icons.Rounded.CropSquare,
                            null, Modifier.size(26.dp), tint = if(draft.aspect == ratio) Violet else Color.White)
                        Spacer(Modifier.height(5.dp)); Text(ratio, fontSize = 12.sp); Text(name, fontSize = 10.sp, color = Muted)
                    }
                }
            }
        }
        TestNotice(if(isImage) "Test preview: a gentle photo animation with a colour treatment. Anime and 3D styles do not redraw the subject in this build."
            else "Test preview uses sample artwork and saves your prompt. It does not generate a new scene from the text.")
        GradientButton("Generate Test Video", vm::generate)
        Text("540p preview · No AI credits used", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun VoiceScreen(vm: MotionViewModel) {
    var voice by rememberSaveable { mutableStateOf("Natural") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        OutlinedTextField(vm.voiceText, { vm.updateVoiceText(it.take(501)) }, Modifier.fillMaxWidth().heightIn(min = 165.dp),
            placeholder = { Text("Enter your text here…\nExample: Welcome to my channel!", color = Muted) }, shape = PanelShape,
            minLines = 4, isError = vm.voiceText.length > 500,
            supportingText = { Text("${vm.voiceText.length}/500", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End) })
        Text("Select Voice", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Surface(shape = PanelShape, color = Panel) {
            Column {
                listOf("Natural" to "Clear and balanced", "Warm" to "Lower pitch, relaxed pace", "Bright" to "Lighter pitch, lively pace", "Narrator" to "Steady storytelling", "Calm" to "Slow and gentle").forEach { (name, subtitle) ->
                    Surface(onClick = { voice = name }, color = Color.Transparent, shape = RoundedCornerShape(12.dp),
                        border = if(voice == name) BorderStroke(1.dp, Violet) else null) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(25.dp), tint = if(voice == name) Violet else Muted)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(name, fontSize = 14.sp); Text(subtitle, fontSize = 11.sp, color = Muted) }
                            if(voice == name) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = Violet)
                        }
                    }
                }
            }
        }
        TestNotice("Voice samples use your Android speech engine and installed voices. This is synthesized speech, not a human recording or a live AI model.")
        GradientButton("Generate Voice Sample", { vm.generateVoice(voice) }, icon = Icons.Rounded.MusicNote)
        Text("WAV audio · Saved to My Projects", color = Muted, fontSize = 12.sp)
    }
}
