package com.gamixtv.MotionMagicAI

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamixtv.MotionMagicAI.ui.MotionApp
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import com.gamixtv.MotionMagicAI.ui.StudioColors
import com.gamixtv.MotionMagicAI.data.MotionStore
import com.gamixtv.MotionMagicAI.data.ThemeMode
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if(MotionStore(applicationContext).settings().themeMode == ThemeMode.LIGHT) setTheme(R.style.Theme_MotionMagic_Light)
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MotionViewModel = viewModel()
            MotionTheme(vm.settings.themeMode) {
                val colors = StudioColors
                SideEffect {
                    val bars = if(colors.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    window.decorView.setBackgroundColor(colors.background.toArgb())
                }
                MotionApp(vm)
            }
        }
    }
}
