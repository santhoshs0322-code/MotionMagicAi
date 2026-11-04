package com.gamixtv.MotionMagicAI.cloud

import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CancellableConnectionTest {
    private class Socket(private val block: Boolean) : HttpsURLConnection(URL("https://example.s3.amazonaws.com")) {
        val reading = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        override fun disconnect() { closed.countDown() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                if(!block) return 1
                check(closed.await(3, TimeUnit.SECONDS)) { "Socket was never disconnected" }
                throw IOException("Socket closed")
            }
        }
    }

    @Test fun cancellingABlockedReadClosesTheSocketAndAllowsAnotherDownload() = runBlocking {
        val first = Socket(true)
        val download = launch(Dispatchers.IO) {
            withCancellableConnection(first) {
                try { first.inputStream.read() }
                catch(error: IOException) { currentCoroutineContext().ensureActive(); throw error }
            }
        }
        assertTrue(first.reading.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { download.cancelAndJoin() }
        val second = Socket(false)
        assertEquals(1, withCancellableConnection(second) { second.inputStream.read() })
    }
}
