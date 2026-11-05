package com.gamixtv.MotionMagicAI.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One base clip and an ordered set of appearance/style references, never starting frames. */
@Composable
internal fun VideoReferenceInput(modelId: String, enabledModels: List<String>, videoPath: String?, durationMs: Long?,
    photos: List<String>, maxPhotos: Int, selectModel: (String) -> Unit, pickVideo: () -> Unit, removeVideo: () -> Unit,
    pickPhotos: (Int?) -> Unit, removePhoto: (Int) -> Unit) {
    val colors = StudioColors
    val editing = modelId == "kling-o3-edit"
    Surface(color = colors.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.border)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Video + photo references", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("kling-o3-edit" to "Edit this video", "kling-o3-reference" to "Create a new scene")
                    .filter { it.first in enabledModels }.forEach { (id, title) ->
                        FilterChip(id == modelId, { selectModel(id) }, { Text(title, fontSize = 12.sp) },
                            modifier = Modifier.testTag("workflow-$id"))
                    }
            }
            Text(if(editing) "Change the subject, look or setting of your existing clip. Length and frame follow the base video."
                else "Create a new scene guided by the base video's motion and camera. Choose the output length in settings.",
                color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp)
            Text("Base video · @Video1", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if(videoPath != null) Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(colors.field), contentAlignment = Alignment.Center) {
                    ProjectThumbnail(videoPath, true, 88.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(pickVideo, modifier = Modifier.fillMaxWidth().testTag("pick-base-video")) {
                        Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if(videoPath == null) "Add base video" else "Replace video", fontSize = 12.sp)
                    }
                    Text(durationMs?.let { "${"%.2f".format(java.util.Locale.US, it / 1000.0)}s · MP4" } ?: "MP4 · 3–15s · up to 200 MB", color = colors.muted, fontSize = 11.sp)
                }
                if(videoPath != null) IconButton(removeVideo, Modifier.testTag("remove-base-video")) { Icon(Icons.Rounded.Close, "Remove base video") }
            }
            Text("Reference photos · ${photos.size}/$maxPhotos (optional)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Tap a photo to replace it. Use @Image1, @Image2… in your prompt to identify each subject or style.",
                color = colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEachIndexed { index, path ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(onClick = { pickPhotos(index) }, shape = RoundedCornerShape(12.dp), color = colors.field,
                            modifier = Modifier.size(88.dp).testTag("reference-photo-$index").semantics { contentDescription = "Replace reference photo ${index + 1}" }) {
                            Box(contentAlignment = Alignment.Center) { ProjectThumbnail(path, false, 88.dp) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("@Image${index + 1}", fontSize = 11.sp, color = colors.primary)
                            IconButton({ removePhoto(index) }, Modifier.size(48.dp).testTag("remove-reference-photo-$index")) {
                                Icon(Icons.Rounded.Close, "Remove photo ${index + 1}", Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if(photos.size < maxPhotos) OutlinedButton({ pickPhotos(null) }, enabled = videoPath != null,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 88.dp).testTag("add-reference-photos")) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null); Text("Add photos", fontSize = 11.sp)
                    }
                }
            }
            if(maxPhotos == 0) Text("Photo references are unavailable for this model right now.", color = colors.muted, fontSize = 11.sp)
            Text("Photos are included in this model's video rate. Credits are reserved first and charged only on success.",
                color = colors.primary, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}
