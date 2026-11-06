package com.gamixtv.MotionMagicAI.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.reviews.requestPlayReview
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ReviewPromptHost(vm: MotionViewModel, enabled: Boolean) {
    val activity = LocalContext.current.reviewActivity() ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val account = vm.activeAccountId ?: return
    val work by vm.work.collectAsState()
    val idleAtHome = enabled && work == null && !WindowInsets.isImeVisible && vm.reviewPromptAllowed(account)
    val opportunity = vm.reviewOpportunity
    LaunchedEffect(account, lifecycle, activity, idleAtHome, opportunity) {
        if(!idleAtHome || opportunity == 0L) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Let the Home transition finish. Never add network work to login, Create or downloads.
            delay(1_000)
            if(vm.reviewPromptAllowed(account) && vm.claimAutomaticReview(account)) {
                requestPlayReview(activity, canLaunch = {
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && vm.reviewPromptAllowed(account)
                })
            }
        }
    }
}

private tailrec fun Context.reviewActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.reviewActivity()
    else -> null
}
