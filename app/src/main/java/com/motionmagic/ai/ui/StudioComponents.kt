package com.motionmagic.ai.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmagic.ai.media.EditorDocument
import java.util.Locale

val StudioPanel = Color(0xFF0D1527)
val StudioLine = Color(0xFF253149)
fun studioTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
fun StudioTool(label: String, icon: ImageVector, selected: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
    Surface(onClick = action, enabled = enabled, color = StudioPanel, shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if(selected) Violet else StudioLine), modifier = Modifier.width(74.dp).height(76.dp)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(25.dp), tint = if(enabled) Color.White else Muted.copy(alpha = .45f))
            Spacer(Modifier.height(7.dp)); Text(label, fontSize = 10.sp, maxLines = 2, color = if(enabled) Color.White else Muted)
        }
    }
}

@Composable
fun StudioPill(label: String, selected: Boolean, action: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(30.dp)).background(if(selected) MagicGradient else Brush.horizontalGradient(listOf(StudioPanel, StudioPanel)))
        .clickable(onClick = action).heightIn(min = 36.dp).padding(horizontal = 17.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun Waveform(peaks: FloatArray, position: Float, modifier: Modifier = Modifier, seek: ((Float) -> Unit)? = null) {
    Canvas(modifier.clip(RoundedCornerShape(13.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF1A102C), Color(0xFF07142B))))
        .then(if (seek != null) Modifier.pointerInput(peaks) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } } else Modifier)) {
        val brush = Brush.horizontalGradient(listOf(Pink, Violet, Color(0xFF5151FF), Color(0xFF00C9FF)))
        for (i in 0..16) drawLine(StudioLine.copy(alpha = .45f), Offset(size.width * i / 16, 0f), Offset(size.width * i / 16, size.height), 1f)
        val count = (size.width / 3).toInt().coerceIn(1, 512)
        for (i in 0 until count) {
            val point = i.toFloat() / count * (peaks.size - 1).coerceAtLeast(0)
            val low = point.toInt(); val blend = point - low
            val amplitude = if(peaks.isEmpty()) 0f else peaks[low] * (1-blend) + peaks[(low + 1).coerceAtMost(peaks.lastIndex)] * blend
            val height = (amplitude * size.height * .44f).coerceAtLeast(1f)
            val x = size.width * i / count
            drawLine(brush, Offset(x, size.height / 2 - height), Offset(x, size.height / 2 + height), 2f)
        }
        if (position >= 0) {
            val x = size.width * position.coerceIn(0f, 1f)
            drawLine(Color.White, Offset(x, 5f), Offset(x, size.height - 5f), 2f)
            drawCircle(Color.White, 4f, Offset(x, 6f))
        }
    }
}

@Composable
fun StudioTimeline(doc: EditorDocument, position: Long, thumbnails: List<Bitmap>, peaks: FloatArray,
    audio: Boolean, select: (Int) -> Unit, seek: (Long) -> Unit, add: () -> Unit, addMusic: () -> Unit, remove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0..4) Text(studioTime(doc.durationMs * i / 4), color = Muted, fontSize = 9.sp)
        }
        Box(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    LazyRow(Modifier.weight(1f).height(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(doc.clips) { index, clip ->
                            Row(Modifier.width(if(doc.clips.size == 1) 245.dp else 115.dp).fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp)).border(1.dp, if(index == doc.selected) Violet else StudioLine, RoundedCornerShape(7.dp))
                                .clickable { select(index); seek(doc.clips.take(index).sumOf { it.lengthMs }) }) {
                                if (!audio && thumbnails.isNotEmpty() && index == doc.selected) thumbnails.take(6).forEach { bitmap ->
                                    Image(bitmap.asImageBitmap(), null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                                } else Box(Modifier.fillMaxSize().background(StudioPanel), contentAlignment = Alignment.Center) {
                                    Text("${index + 1} · ${studioTime(clip.lengthMs)}", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = add, contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(8.dp)) { Text("+ Add Clip", fontSize = 10.sp) }
                }
                if (doc.text.isNotBlank()) TrackRow(Icons.Rounded.Title, doc.text, Brush.horizontalGradient(listOf(Color(0xFF8430F4), Color(0xFF4D11BB))))
                if (audio) Box(Modifier.fillMaxWidth().height(37.dp)) {
                    Waveform(peaks, -1f, Modifier.fillMaxSize())
                    Text(doc.clips.getOrNull(doc.selected)?.label ?: "Audio track", fontSize = 10.sp, modifier = Modifier.padding(9.dp))
                }
                if (doc.music != null) TrackRow(Icons.Rounded.MusicNote, doc.musicName.ifBlank { "Background audio" }, Brush.horizontalGradient(listOf(Color(0xFF0062FF), Color(0xFF0141CF))))
                OutlinedButton(onClick = addMusic, Modifier.fillMaxWidth().height(36.dp), shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(4.dp)) { Text(if(doc.music == null) "+ Add Audio" else "Change background audio", fontSize = 11.sp) }
                if (!audio) TrackRow(Icons.Rounded.AutoAwesome, "Effects · ${doc.filter}", Brush.horizontalGradient(listOf(Color(0xFF7C17EC), Color(0xFF181035))))
            }
            if(doc.clips.isNotEmpty()) Canvas(Modifier.matchParentSize()) {
                val fraction = position.toFloat() / doc.durationMs.coerceAtLeast(1)
                val x = (fraction * (size.width - 76.dp.toPx())).coerceIn(3.dp.toPx(), size.width - 3.dp.toPx())
                drawLine(Color.White.copy(alpha = .9f), Offset(x, 3.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                drawCircle(Color.White, 2.5.dp.toPx(), Offset(x, 3.dp.toPx()))
            }
        }
        // This scrubber is proportional to time even when clip tiles have a minimum touch width.
        val fraction = if(doc.durationMs > 0) position.toFloat() / doc.durationMs else 0f
        Canvas(Modifier.fillMaxWidth().height(30.dp)
            .semantics { contentDescription = "Timeline position"; progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { if(doc.clips.isEmpty()) false else { seek((it.coerceIn(0f, 1f) * doc.durationMs).toLong()); true } } }
            .pointerInput(doc.durationMs) { detectTapGestures { seek((it.x / size.width * doc.durationMs).toLong()) } }
            .pointerInput(doc.durationMs) { detectHorizontalDragGestures { change, _ -> change.consume(); seek((change.position.x / size.width * doc.durationMs).toLong()) } }) {
            val y = size.height / 2; val inset = 6.dp.toPx()
            val x = inset + fraction.coerceIn(0f,1f) * (size.width - 2 * inset)
            drawLine(Color(0xFF414358), Offset(inset,y), Offset(size.width-inset,y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(MagicGradient, Offset(inset,y), Offset(x,y), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(Color(0xFFDFC1F5), 5.dp.toPx(), Offset(x,y))
        }
        if (doc.clips.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Clip ${doc.selected + 1} / ${doc.clips.size}", color = Muted, fontSize = 11.sp)
            TextButton(onClick = remove) { Text("Remove selected clip", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun TrackRow(icon: ImageVector, name: String, brush: Brush) {
    Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(7.dp)).background(brush).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, Modifier.size(17.dp)); Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
    }
}

/** Small crops from the user's reference serve only as catalog artwork, never as fake media frames. */
@Composable
fun StudioCatalogArt(index: Int, music: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(index, music) {
        context.assets.open("editors-reference.png").use { input ->
            @Suppress("DEPRECATION") val decoder = android.graphics.BitmapRegionDecoder.newInstance(input, false)!!
            try {
                val left = if(music) 823 + index * 98 else 911 + index * 90
                val top = if(music) 907 else 730
                decoder.decodeRegion(android.graphics.Rect(left, top, left + if(music) 85 else 78, top + if(music) 55 else 68), BitmapFactory.Options())
            } finally { decoder.recycle() }
        }
    }
    Image(bitmap.asImageBitmap(), null, modifier.clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Crop)
}
