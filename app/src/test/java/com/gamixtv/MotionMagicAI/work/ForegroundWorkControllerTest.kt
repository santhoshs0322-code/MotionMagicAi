package com.gamixtv.MotionMagicAI.work

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ForegroundWorkControllerTest {
    @Test fun cancelDuringDispatcherChangingCleanupDoesNotKeepTheBusySlot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = MutableStateFlow<String?>(null)
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val errors = mutableListOf<Exception>()
        val work = ForegroundWorkController(scope, state, cleanup = {
            withContext(Dispatchers.Default) { cleaned.complete(Unit) }
        }, onError = { errors.add(it) })
        assertTrue(work.start("download") { started.complete(Unit); awaitCancellation() })
        started.await()
        work.cancel()
        withTimeout(2_000) {
            cleaned.await()
            while(!work.start("next") {}) yield()
        }
        assertTrue(errors.isEmpty())
        scope.cancel()
    }

    @Test fun replacingACancelledDownloadCannotClearOrFailTheNextOperation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = MutableStateFlow<String?>(null)
        val oldSocket = CompletableDeferred<Unit>()
        val newSocket = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val errors = mutableListOf<Exception>()
        var cleanups = 0
        val work = ForegroundWorkController(scope, state, cleanup = { cleanups++ }, onError = { errors.add(it) })
        work.start("old", replaceableOnCancel = true) {
            try { withContext(NonCancellable) { oldSocket.await(); throw java.io.IOException("Old socket closed") } }
            finally { oldFinished.complete(Unit) }
        }
        assertTrue(work.cancel())
        assertTrue(work.start("new", replaceableOnCancel = true) { newSocket.await() })
        oldSocket.complete(Unit)
        oldFinished.await()
        yield()
        assertEquals("new", state.value)
        assertEquals(0, cleanups)
        assertTrue(errors.isEmpty())
        newSocket.complete(Unit)
        withTimeout(2_000) { while(state.value != null) yield() }
        assertEquals(1, cleanups)
        scope.cancel()
    }
}
