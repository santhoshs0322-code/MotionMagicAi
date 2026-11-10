package com.gamixtv.MotionMagicAI.data

object StoryDurations {
    val seconds = (60..300 step 30).toList()
    fun label(seconds: Int): String = if(seconds % 60 == 0) "${seconds / 60} min" else "${seconds / 60}.5 min"
}
