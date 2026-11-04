package com.gamixtv.MotionMagicAI.cloud

import kotlinx.coroutines.*
import javax.net.ssl.HttpsURLConnection

/** Interrupt a blocked response/read/upload on cancellation without attaching any credentials. */
internal suspend fun <T> withCancellableConnection(connection: HttpsURLConnection, block: suspend () -> T): T = coroutineScope {
    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { connection.disconnect() }
    }
    try { block() } finally { closer.cancel() }
}
