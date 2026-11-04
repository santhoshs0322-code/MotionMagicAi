package com.gamixtv.MotionMagicAI.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class MotionColors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val field: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val primary: Color,
    val selected: Color,
    val error: Color,
    val warning: Color,
)

val DarkMotionColors = MotionColors(true, Color(0xFF111216), Color(0xFF212429), Color(0xFF1B1D22),
    Color(0xFFF8F7FC), Color(0xFFB7B9C4), Color(0xFF3D3F48), Color(0xFFC18AFA), Color(0xFF392C4D),
    Color(0xFFFFA5B0), Color(0xFFFFD16A))
val LightMotionColors = MotionColors(false, Color(0xFFF2F3FA), Color(0xFFFCFCFF), Color(0xFFF2F3F8),
    Color(0xFF17151F), Color(0xFF62626F), Color(0xFFDCDCE7), Color(0xFF8427D5), Color(0xFFF0E3FC),
    Color(0xFFB32146), Color(0xFF805000))
val LocalMotionColors = staticCompositionLocalOf { DarkMotionColors }
val StudioColors: MotionColors @Composable get() = LocalMotionColors.current
