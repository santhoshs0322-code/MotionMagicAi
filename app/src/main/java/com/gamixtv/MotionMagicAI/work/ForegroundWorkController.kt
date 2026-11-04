package com.gamixtv.MotionMagicAI.work

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Main-thread coordinator. Completion always releases the slot, even when cleanup is cancelled. */
internal class ForegroundWorkController<T>(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<T?>,
    private val cleanup: suspend () -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private var active: Job? = null
    private var replaceable = false

    fun start(value: T, replaceableOnCancel: Boolean = false, action: suspend () -> Unit): Boolean {
        if(active != null) return false
        state.value = value
        replaceable = replaceableOnCancel
        val task = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext().job
            try { action() }
            catch(error: TimeoutCancellationException) { if(active === self) onError(error) }
            catch(error: CancellationException) { throw error }
            catch(error: Exception) { if(active === self) onError(error) }
            finally {
                try { if(active === self) withContext(NonCancellable) { cleanup() } }
                catch(error: Exception) { if(active === self && !self.isCancelled) onError(error) }
                finally { release(self) }
            }
        }
        active = task
        // A cancelled lazy coroutine may never enter its body/finally.
        task.invokeOnCompletion { release(task) }
        task.start()
        return true
    }

    fun cancel(): Boolean {
        val task = active ?: return false
        val detach = replaceable
        if(detach) release(task) else state.value = null
        task.cancel()
        return detach
    }

    private fun release(task: Job) {
        if(active === task) { state.value = null; active = null; replaceable = false }
    }
}
