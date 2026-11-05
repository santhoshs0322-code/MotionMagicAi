package com.gamixtv.MotionMagicAI.data

/** Whole input seconds reserved for an edit; the worker independently verifies the actual file. */
fun referenceBillableSeconds(durationMs: Long): Int {
    require(durationMs in 3_000L..15_000L) { "Choose a reference MP4 between 3 and 15 seconds." }
    return ((durationMs + 999L) / 1000L).toInt()
}
