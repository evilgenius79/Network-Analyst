package com.networkanalyst.core.speed

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stand-in for the speed test service that behaves like a line of fixed capacity: every
 * connection shares one token bucket per direction, so opening more streams does not create
 * more bandwidth. Optionally answers latency probes more slowly while a transfer is running,
 * which is what bufferbloat looks like from the client.
 */
class FakeSpeedServer(
    downBytesPerSecond: Long,
    upBytesPerSecond: Long,
    private val baseLatencyMs: Long = 5,
    private val loadedExtraLatencyMs: Long = 0,
    private val downloadStatus: Int = 200,
    private val uploadStatus: Int = 200,
) : AutoCloseable {
    private class Pacer(private val bytesPerSecond: Long) {
        private var nextFree = System.nanoTime()

        fun pace(bytes: Int) {
            val waitNanos = synchronized(this) {
                val now = System.nanoTime()
                val start = maxOf(now, nextFree)
                nextFree = start + bytes * 1_000_000_000L / bytesPerSecond
                start - now
            }
            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        }
    }

    private val downPacer = Pacer(downBytesPerSecond)
    private val upPacer = Pacer(upBytesPerSecond)
    private val activeTransfers = AtomicInteger()
    val bytesReceived = AtomicLong()
    val bytesSent = AtomicLong()
    private val activeDownloads = AtomicInteger()
    private val activeUploads = AtomicInteger()
    /** Most requests of each kind being served at the same moment. */
    val peakDownloads = AtomicInteger()
    val peakUploads = AtomicInteger()

    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64).apply {
        createContext("/__down") { exchange -> safely(exchange) { download(exchange) } }
        createContext("/__up") { exchange -> safely(exchange) { upload(exchange) } }
        executor = this@FakeSpeedServer.executor
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun safely(exchange: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Client went away mid-transfer, which is how every timed test ends.
        } finally {
            exchange.close()
        }
    }

    private fun addHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.add("Server-Timing", "cfSpeedEdge;dur=1, cfSpeedWorker;dur=2")
        exchange.responseHeaders.add("cf-meta-colo", "TST")
        exchange.responseHeaders.add("cf-meta-ip", "203.0.113.9")
        exchange.responseHeaders.add("asn", "64500")
        exchange.responseHeaders.add("city", "Testville")
        exchange.responseHeaders.add("country", "ZZ")
    }

    /**
     * Counts one request as active. [leave] must run before the client can learn the request
     * is over (before the last byte or the response goes out); otherwise the client's next
     * request could be counted while this one still is, overstating the peak.
     */
    private class Slot(private val all: AtomicInteger, private val kind: AtomicInteger, peak: AtomicInteger) {
        private var left = false

        init {
            all.incrementAndGet()
            kind.incrementAndGet().also { n -> peak.updateAndGet { maxOf(it, n) } }
        }

        fun leave() {
            if (left) return
            left = true
            kind.decrementAndGet()
            all.decrementAndGet()
        }
    }

    private fun download(exchange: HttpExchange) {
        val bytes = Regex("bytes=(\\d+)").find(exchange.requestURI.query ?: "")?.groupValues?.get(1)?.toLong() ?: 0
        addHeaders(exchange)
        if (bytes == 0L) {
            // Latency probe: 3 ms of this delay is declared as server time in the header.
            val extra = if (activeTransfers.get() > 0) loadedExtraLatencyMs else 0
            Thread.sleep(baseLatencyMs + 3 + extra)
            exchange.sendResponseHeaders(200, -1)
            return
        }
        if (downloadStatus != 200) {
            exchange.sendResponseHeaders(downloadStatus, -1)
            return
        }
        val slot = Slot(activeTransfers, activeDownloads, peakDownloads)
        try {
            exchange.sendResponseHeaders(200, bytes)
            val chunk = ByteArray(8 * 1024)
            var remaining = bytes
            exchange.responseBody.use { out ->
                while (remaining > 0) {
                    val size = minOf(remaining, chunk.size.toLong()).toInt()
                    downPacer.pace(size)
                    if (remaining - size == 0L) slot.leave()
                    out.write(chunk, 0, size)
                    bytesSent.addAndGet(size.toLong())
                    remaining -= size
                }
            }
        } finally {
            slot.leave()
        }
    }

    private fun upload(exchange: HttpExchange) {
        addHeaders(exchange)
        if (uploadStatus != 200) {
            exchange.sendResponseHeaders(uploadStatus, -1)
            return
        }
        val slot = Slot(activeTransfers, activeUploads, peakUploads)
        try {
            val chunk = ByteArray(8 * 1024)
            exchange.requestBody.use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    // Charge the bucket for what actually arrived, then hold the reader back.
                    upPacer.pace(read)
                    bytesReceived.addAndGet(read.toLong())
                }
            }
            slot.leave()
            exchange.sendResponseHeaders(200, -1)
        } finally {
            slot.leave()
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
