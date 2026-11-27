package com.networkanalyst.core.speed

import com.networkanalyst.core.model.SpeedTestResult
import com.networkanalyst.core.util.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Where and how to run a speed test. The defaults use Cloudflare's public speed test
 * endpoints, the same ones behind speed.cloudflare.com:
 *  - GET  {base}/__down?bytes=N  returns N bytes
 *  - POST {base}/__up            accepts and discards a request body
 * Any server exposing the same two endpoints can be substituted through [baseUrl].
 */
data class SpeedTestConfig(
    val baseUrl: String = "https://speed.cloudflare.com",
    val downloadPath: String = "/__down",
    val uploadPath: String = "/__up",
    val latencySamples: Int = 20,
    val downloadDurationMs: Long = 10_000,
    val uploadDurationMs: Long = 10_000,
    /**
     * Start of each transfer that is left out of the average. TCP needs a moment to ramp up,
     * and for uploads the first bytes only fill local buffers rather than crossing the line.
     */
    val downloadRampMs: Long = 2_000,
    val uploadRampMs: Long = 3_000,
    val initialStreams: Int = 4,
    val maxStreams: Int = 8,
    /** Above this rate more parallel streams are opened so a single TCP stream is not the limit. */
    val addStreamsAboveMbps: Double = 100.0,
    /** Size of each download request. Cloudflare refuses single downloads of 100 MB or more. */
    val downloadChunkBytes: Long = 25_000_000,
    /**
     * Upload requests start small and are resized so each takes about [uploadRequestTargetMs];
     * this is the upper limit for one request.
     */
    val uploadChunkBytes: Long = 25_000_000,
    val uploadInitialChunkBytes: Long = 64 * 1024,
    val uploadMinChunkBytes: Long = 32 * 1024,
    val uploadRequestTargetMs: Long = 1_000,
    /** How long uploads already in flight may run past the end so their bytes can be counted. */
    val uploadGraceMs: Long = 2_000,
    val loadedLatencyIntervalMs: Long = 400,
    val sampleIntervalMs: Long = 200,
    val runDownload: Boolean = true,
    val runUpload: Boolean = true,
    /** Stop a transfer early once this many bytes have moved (protects mobile data allowances). */
    val maxBytesPerDirection: Long = Long.MAX_VALUE,
    /**
     * End a transfer before its full duration once the rate has settled: a fast line can move
     * more than a gigabyte in ten seconds, and a steady reading does not get better by waiting.
     */
    val stopWhenStable: Boolean = true,
    /** Shortest steady-state measurement (after the ramp) before an early finish is allowed. */
    val minSteadyMs: Long = 3_000,
    /** Largest spread, as a fraction of the mean, for recent readings to count as settled. */
    val stableTolerance: Double = 0.08,
)

enum class SpeedPhase { LATENCY, DOWNLOAD, UPLOAD, DONE }

sealed interface SpeedTestProgress {
    data class PhaseStarted(val phase: SpeedPhase) : SpeedTestProgress
    data class LatencySample(val latencyMs: Double, val index: Int, val total: Int) : SpeedTestProgress
    data class Throughput(
        val phase: SpeedPhase,
        /** Rate over roughly the last second. */
        val currentMbps: Double,
        /** Fraction of this phase that has elapsed, 0..1. */
        val fraction: Double,
        val bytes: Long,
    ) : SpeedTestProgress
}

/**
 * Decides when a series of rate readings has stopped moving: the last [needed] readings,
 * taken at least [spacingNanos] apart, must lie within [tolerance] of their mean.
 */
internal class StabilityDetector(
    private val tolerance: Double,
    private val needed: Int = 4,
    private val spacingNanos: Long = 500_000_000L,
) {
    private val readings = ArrayDeque<Double>()
    private var lastAt = Long.MIN_VALUE

    /** Records a reading if enough time has passed; returns whether the series is stable now. */
    fun offer(atNanos: Long, value: Double): Boolean {
        if (lastAt == Long.MIN_VALUE || atNanos - lastAt >= spacingNanos) {
            lastAt = atNanos
            readings.addLast(value)
            if (readings.size > needed) readings.removeFirst()
        }
        if (readings.size < needed) return false
        val mean = readings.average()
        if (mean <= 0.0) return false
        return (readings.max() - readings.min()) / mean <= tolerance
    }
}

/**
 * Multi-connection HTTP speed test with latency-under-load measurement.
 *
 * Several parallel HTTP/1.1 connections (each its own TCP stream) transfer data for a fixed
 * time and the average is taken after an initial ramp-up window.
 *
 *  - Download counts bytes as they arrive, which is exact because this side is the receiver.
 *  - Upload counts only bytes the server has confirmed: each stream sends a series of requests
 *    and a request's bytes count once its response comes back. Counting bytes as they are
 *    written would also count data still queued in local and network buffers, which
 *    overstates slow uplinks badly.
 *
 * While each transfer runs, small latency probes are sent on a separate connection; how far
 * that latency rises above the idle value is the line's bufferbloat.
 */
class SpeedTestEngine(
    baseClient: OkHttpClient = OkHttpClient(),
    private val config: SpeedTestConfig = SpeedTestConfig(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        // HTTP/2 would multiplex every request over one TCP connection, which defeats the
        // purpose of parallel streams.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(config.maxStreams + 2, 30, TimeUnit.SECONDS))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .eventListenerFactory { call -> call.request().tag(RequestTiming::class.java) ?: EventListener.NONE }
        .build()

    private val uploadPayload = Random.nextBytes(64 * 1024)

    /** Per-request timestamps captured through OkHttp's event listener. */
    private inner class RequestTiming : EventListener() {
        @Volatile var requestStart = 0L
        @Volatile var requestBodyStart = 0L
        @Volatile var responseStart = 0L

        override fun requestHeadersStart(call: Call) {
            requestStart = nanoTime()
        }

        override fun requestBodyStart(call: Call) {
            requestBodyStart = nanoTime()
        }

        override fun responseHeadersStart(call: Call) {
            responseStart = nanoTime()
        }
    }

    private class ServerInfo(
        val colo: String?,
        val ip: String?,
        val asn: String?,
        val city: String?,
        val country: String?,
    )

    suspend fun run(onProgress: (SpeedTestProgress) -> Unit = {}): SpeedTestResult {
        val startedAt = System.currentTimeMillis()
        var info: ServerInfo? = null
        var error: String? = null

        // --- Idle latency ---
        onProgress(SpeedTestProgress.PhaseStarted(SpeedPhase.LATENCY))
        val idle = ArrayList<Double>()
        try {
            // The first request opens the TCP and TLS connection; its timing is not used.
            info = withContext(Dispatchers.IO) { latencyProbe() }.info
            repeat(config.latencySamples) { index ->
                val sample = withContext(Dispatchers.IO) { latencyProbe() }
                idle += sample.latencyMs
                onProgress(SpeedTestProgress.LatencySample(sample.latencyMs, index + 1, config.latencySamples))
            }
        } catch (e: IOException) {
            if (idle.isEmpty()) {
                return SpeedTestResult(
                    error = "Could not reach the speed test server: ${e.message ?: e.javaClass.simpleName}",
                    timestampMs = startedAt,
                )
            }
        }

        // --- Download ---
        var download: TransferOutcome? = null
        if (config.runDownload) {
            onProgress(SpeedTestProgress.PhaseStarted(SpeedPhase.DOWNLOAD))
            download = download(onProgress)
            download.error?.let { error = "Download test: $it" }
        }

        // --- Upload ---
        var upload: TransferOutcome? = null
        if (config.runUpload) {
            onProgress(SpeedTestProgress.PhaseStarted(SpeedPhase.UPLOAD))
            upload = upload(onProgress)
            upload.error?.let { error = listOfNotNull(error, "Upload test: $it").joinToString("; ") }
        }

        onProgress(SpeedTestProgress.PhaseStarted(SpeedPhase.DONE))
        client.connectionPool.evictAll()

        return SpeedTestResult(
            downloadMbps = download?.mbps,
            uploadMbps = upload?.mbps,
            idleLatencyMs = Stats.median(idle),
            idleJitterMs = Stats.jitter(idle),
            downloadLoadedLatencyMs = download?.let { Stats.median(it.loadedLatencyMs) },
            uploadLoadedLatencyMs = upload?.let { Stats.median(it.loadedLatencyMs) },
            bytesDownloaded = download?.bytes ?: 0,
            bytesUploaded = upload?.bytes ?: 0,
            serverLocation = info?.colo?.let { "Cloudflare $it" },
            publicIp = info?.ip,
            asn = info?.asn,
            city = info?.city,
            country = info?.country,
            error = error,
            timestampMs = startedAt,
        )
    }

    private class LatencyProbe(val latencyMs: Double, val info: ServerInfo)

    /**
     * One zero-byte download. Latency is the time from sending the request to the first byte
     * of the response, minus the processing time the server reports about itself.
     */
    private fun latencyProbe(): LatencyProbe {
        val timing = RequestTiming()
        val request = Request.Builder()
            .url("${config.baseUrl}${config.downloadPath}?bytes=0")
            .tag(RequestTiming::class.java, timing)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.bytes()
            val ttfbMs = (timing.responseStart - timing.requestStart) / 1_000_000.0
            if (timing.requestStart == 0L || timing.responseStart == 0L || ttfbMs < 0) throw IOException("no timing data")
            val serverMs = serverProcessingMs(response.headers("server-timing").joinToString(", "))
            var latency = ttfbMs - serverMs
            // If the server's own figure swallows the whole measurement it cannot be trusted.
            if (latency <= 0.0) latency = ttfbMs
            return LatencyProbe(latency, serverInfo(response))
        }
    }

    private fun serverInfo(response: Response): ServerInfo {
        fun header(name: String): String? =
            (response.header("cf-meta-$name") ?: response.header(name))?.takeIf { it.isNotBlank() }
        return ServerInfo(
            colo = header("colo"),
            ip = header("ip"),
            asn = header("asn")?.let { "AS$it" },
            city = header("city"),
            country = header("country"),
        )
    }

    private class TransferOutcome(
        val mbps: Double?,
        val bytes: Long,
        val loadedLatencyMs: List<Double>,
        val error: String?,
    )

    /**
     * True once the transfer has run its minimum steady time and recent readings agree.
     * Readings only start counting one second after the ramp so that the one-second rate
     * window they are computed over lies entirely in steady state.
     */
    private fun settled(detector: StabilityDetector, elapsedNanos: Long, rampMs: Long, currentMbps: Double): Boolean {
        if (!config.stopWhenStable) return false
        if (elapsedNanos < (rampMs + 1_000) * 1_000_000L) return false
        val stable = detector.offer(elapsedNanos, currentMbps)
        return stable && elapsedNanos >= (rampMs + config.minSteadyMs) * 1_000_000L
    }

    /** Sends latency probes on their own connection while a transfer saturates the line. */
    private fun CoroutineScope.launchLoadedLatencyProber(rampMs: Long, deadline: Long, into: MutableList<Double>): Job =
        launch(Dispatchers.IO) {
            delay(rampMs)
            while (isActive && nanoTime() < deadline) {
                try {
                    into += latencyProbe().latencyMs
                } catch (_: IOException) {
                    // A lost probe under load is expected now and then; skip it.
                }
                delay(config.loadedLatencyIntervalMs)
            }
        }

    private suspend fun download(onProgress: (SpeedTestProgress) -> Unit): TransferOutcome {
        val counter = AtomicLong()
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
        val failures = Collections.synchronizedList(ArrayList<String>())
        val loadedLatency = Collections.synchronizedList(ArrayList<Double>())
        val activeStreams = AtomicInteger()
        // (elapsed nanoseconds, total bytes) taken every sample interval.
        val samples = ArrayList<Pair<Long, Long>>()
        val start = nanoTime()
        val deadline = start + config.downloadDurationMs * 1_000_000L

        try {
            coroutineScope {
                val workers = ArrayList<Job>()
                fun addStream() {
                    activeStreams.incrementAndGet()
                    workers += launch(Dispatchers.IO) {
                        try {
                            downloadLoop(deadline, counter, calls, failures)
                        } finally {
                            activeStreams.decrementAndGet()
                        }
                    }
                }
                repeat(config.initialStreams) { addStream() }
                val prober = launchLoadedLatencyProber(config.downloadRampMs, deadline, loadedLatency)

                var scaledUp = false
                val stability = StabilityDetector(config.stableTolerance)
                try {
                    while (true) {
                        delay(config.sampleIntervalMs)
                        val now = nanoTime()
                        val bytes = counter.get()
                        samples += (now - start) to bytes
                        val current = recentMbps(samples, 1_000)
                        val fraction = ((now - start).toDouble() / (deadline - start)).coerceIn(0.0, 1.0)
                        onProgress(SpeedTestProgress.Throughput(SpeedPhase.DOWNLOAD, current, fraction, bytes))
                        if (settled(stability, now - start, config.downloadRampMs, current)) break

                        if (!scaledUp && (now - start) >= SCALE_UP_AFTER_NANOS && config.maxStreams > config.initialStreams) {
                            scaledUp = true
                            if (current >= config.addStreamsAboveMbps) {
                                repeat(config.maxStreams - config.initialStreams) { addStream() }
                            }
                        }
                        if (now >= deadline || bytes >= config.maxBytesPerDirection) break
                        // Every stream has given up (server unreachable or refusing requests).
                        if (activeStreams.get() == 0) break
                    }
                } finally {
                    // Abort in-flight requests so the blocked worker threads return immediately,
                    // also when the caller cancelled the test.
                    withContext(NonCancellable) {
                        prober.cancel()
                        workers.forEach { it.cancel() }
                        calls.toList().forEach { it.cancel() }
                    }
                }
            }
        } finally {
            calls.toList().forEach { it.cancel() }
        }

        val bytes = counter.get()
        if (bytes == 0L) {
            return TransferOutcome(null, 0, loadedLatency.toList(), failures.firstOrNull() ?: "no data was transferred")
        }
        return TransferOutcome(averageMbps(samples, config.downloadRampMs), bytes, loadedLatency.toList(), null)
    }

    private fun CoroutineScope.downloadLoop(
        deadline: Long,
        counter: AtomicLong,
        calls: MutableSet<Call>,
        failures: MutableList<String>,
    ) {
        val buffer = ByteArray(64 * 1024)
        var consecutiveFailures = 0
        while (isActive && nanoTime() < deadline && consecutiveFailures < 3) {
            val request = Request.Builder()
                .url("${config.baseUrl}${config.downloadPath}?bytes=${config.downloadChunkBytes}")
                .build()
            val call = client.newCall(request)
            calls += call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        failures += "server answered HTTP ${response.code}"
                        consecutiveFailures++
                        // Refused or rate limited: stop this stream rather than hammer the server.
                        if (response.code == 403 || response.code == 429) consecutiveFailures = 3
                        return@use
                    }
                    val stream = response.body.byteStream()
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        counter.addAndGet(read.toLong())
                    }
                    consecutiveFailures = 0
                }
            } catch (e: IOException) {
                if (call.isCanceled()) return
                failures += (e.message ?: e.javaClass.simpleName)
                consecutiveFailures++
            } finally {
                calls -= call
            }
        }
    }

    /** One upload request the server has acknowledged. Times are engine clock nanoseconds. */
    internal class UploadRequest(val startNanos: Long, val endNanos: Long, val bytes: Long)

    private class UploadStream {
        val completed: MutableList<UploadRequest> = Collections.synchronizedList(ArrayList())
        /** Start time of the request currently in flight, or 0 when there is none. */
        @Volatile var inFlightSince = 0L
        /** Rate of the most recently completed request, bytes per second. */
        @Volatile var lastBytesPerSecond = 0.0
        @Volatile var finished = false
    }

    private suspend fun upload(onProgress: (SpeedTestProgress) -> Unit): TransferOutcome {
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
        val failures = Collections.synchronizedList(ArrayList<String>())
        val loadedLatency = Collections.synchronizedList(ArrayList<Double>())
        val streams = Collections.synchronizedList(ArrayList<UploadStream>())
        val stop = AtomicBoolean(false)
        val start = nanoTime()
        val deadline = start + config.uploadDurationMs * 1_000_000L
        val rampEnd = start + config.uploadRampMs * 1_000_000L

        fun confirmedBytes(): Long = synchronized(streams) { streams.sumOf { stream -> synchronized(stream.completed) { stream.completed.sumOf { it.bytes } } } }

        try {
            coroutineScope {
                val workers = ArrayList<Job>()
                fun addStream() {
                    val stream = UploadStream()
                    streams += stream
                    workers += launch(Dispatchers.IO) {
                        try {
                            uploadLoop(stream, deadline, stop, calls, failures)
                        } finally {
                            stream.finished = true
                        }
                    }
                }
                repeat(config.initialStreams) { addStream() }
                val prober = launchLoadedLatencyProber(config.uploadRampMs, deadline, loadedLatency)

                var scaledUp = false
                val stability = StabilityDetector(config.stableTolerance)
                try {
                    while (true) {
                        delay(config.sampleIntervalMs)
                        val now = nanoTime()
                        val bytes = confirmedBytes()
                        val current = synchronized(streams) { streams.sumOf { it.lastBytesPerSecond } } * 8 / 1_000_000.0
                        val fraction = ((now - start).toDouble() / (deadline - start)).coerceIn(0.0, 1.0)
                        onProgress(SpeedTestProgress.Throughput(SpeedPhase.UPLOAD, current, fraction, bytes))
                        if (settled(stability, now - start, config.uploadRampMs, current)) break

                        if (!scaledUp && (now - start) >= SCALE_UP_AFTER_NANOS && config.maxStreams > config.initialStreams) {
                            scaledUp = true
                            if (current >= config.addStreamsAboveMbps) {
                                repeat(config.maxStreams - config.initialStreams) { addStream() }
                            }
                        }
                        if (now >= deadline || bytes >= config.maxBytesPerDirection) break
                        if (synchronized(streams) { streams.all { it.finished } }) break
                    }
                    // No new requests from here on, but let the ones already under way finish
                    // so their bytes count.
                    stop.set(true)
                    val graceEnd = nanoTime() + config.uploadGraceMs * 1_000_000L
                    while (nanoTime() < graceEnd && synchronized(streams) { streams.any { !it.finished } }) {
                        delay(20)
                    }
                } finally {
                    withContext(NonCancellable) {
                        prober.cancel()
                        workers.forEach { it.cancel() }
                        calls.toList().forEach { it.cancel() }
                    }
                }
            }
        } finally {
            calls.toList().forEach { it.cancel() }
        }

        val bytes = confirmedBytes()
        val windowEnd = minOf(deadline, nanoTime())
        val rates = synchronized(streams) {
            streams.map { stream ->
                streamBytesPerSecond(synchronized(stream.completed) { stream.completed.toList() }, rampEnd, windowEnd, stream.inFlightSince)
            }
        }
        val total = rates.filterNotNull().sum()
        if (bytes == 0L || total <= 0.0) {
            return TransferOutcome(
                null, bytes, loadedLatency.toList(),
                failures.firstOrNull() ?: "the connection was too slow to complete an upload in the time allowed",
            )
        }
        return TransferOutcome(total * 8 / 1_000_000.0, bytes, loadedLatency.toList(), null)
    }

    private fun CoroutineScope.uploadLoop(
        stream: UploadStream,
        deadline: Long,
        stop: AtomicBoolean,
        calls: MutableSet<Call>,
        failures: MutableList<String>,
    ) {
        var chunk = config.uploadInitialChunkBytes.coerceAtMost(config.uploadChunkBytes)
        var consecutiveFailures = 0
        while (isActive && !stop.get() && nanoTime() < deadline && consecutiveFailures < 3) {
            val size = chunk
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    var remaining = size
                    while (remaining > 0) {
                        val block = minOf(remaining, uploadPayload.size.toLong()).toInt()
                        sink.write(uploadPayload, 0, block)
                        remaining -= block
                    }
                }
            }
            val timing = RequestTiming()
            val request = Request.Builder()
                .url("${config.baseUrl}${config.uploadPath}")
                .post(body)
                .tag(RequestTiming::class.java, timing)
                .build()
            val call = client.newCall(request)
            calls += call
            val issuedAt = nanoTime()
            stream.inFlightSince = issuedAt
            try {
                call.execute().use { response ->
                    // The response only arrives once the server has read the whole body.
                    val end = timing.responseStart.takeIf { it > 0 } ?: nanoTime()
                    if (!response.isSuccessful) {
                        failures += "server answered HTTP ${response.code}"
                        consecutiveFailures++
                        if (response.code == 403 || response.code == 429) consecutiveFailures = 3
                        stream.inFlightSince = 0
                        return@use
                    }
                    response.body.bytes()
                    // Connection setup is not part of the transfer, so time from the first body byte.
                    val begin = timing.requestBodyStart.takeIf { it in 1 until end } ?: issuedAt
                    stream.completed += UploadRequest(begin, end, size)
                    stream.inFlightSince = 0
                    val bytesPerSecond = size / ((end - begin).coerceAtLeast(1) / 1_000_000_000.0)
                    stream.lastBytesPerSecond = bytesPerSecond
                    chunk = (bytesPerSecond * config.uploadRequestTargetMs / 1000.0).toLong()
                        .coerceIn(config.uploadMinChunkBytes, config.uploadChunkBytes.coerceAtLeast(config.uploadMinChunkBytes))
                    consecutiveFailures = 0
                }
            } catch (e: IOException) {
                // A cancelled request stays marked as in flight: nothing is known about its bytes.
                if (call.isCanceled()) return
                stream.inFlightSince = 0
                failures += (e.message ?: e.javaClass.simpleName)
                consecutiveFailures++
            } finally {
                calls -= call
            }
        }
    }

    companion object {
        private val CF_REQUEST_DURATION = Regex("""(?:^|,\s*)cfReq(?:uest)?Dur(?:ation)?;\s*dur=([0-9.]+)""", RegexOption.IGNORE_CASE)
        private val CF_SPEED_DURATION = Regex("""(?:^|,\s*)cfSpeed[a-zA-Z]*;\s*dur=([0-9.]+)""", RegexOption.IGNORE_CASE)

        /**
         * Server-side processing time in ms from a Server-Timing header, read the same way
         * Cloudflare's own speed test client does: a "cfRequestDuration" entry if present,
         * otherwise the sum of all "cfSpeed*" entries. Returns 0 when there is none.
         */
        fun serverProcessingMs(serverTiming: String?): Double {
            if (serverTiming.isNullOrBlank()) return 0.0
            CF_REQUEST_DURATION.find(serverTiming)?.groupValues?.get(1)?.toDoubleOrNull()?.let { if (it > 0.01) return it }
            return CF_SPEED_DURATION.findAll(serverTiming).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        }

        /** Rate over the trailing [windowMs] of the sample series, in Mbps. */
        internal fun recentMbps(samples: List<Pair<Long, Long>>, windowMs: Long): Double {
            if (samples.isEmpty()) return 0.0
            val last = samples.last()
            val windowStart = last.first - windowMs * 1_000_000L
            val first = samples.lastOrNull { it.first <= windowStart } ?: (0L to 0L)
            return mbps(last.second - first.second, last.first - first.first)
        }

        /**
         * Average rate after the ramp-up window. If the transfer was too short to leave at
         * least a second of steady state, the whole run is used instead.
         */
        internal fun averageMbps(samples: List<Pair<Long, Long>>, rampMs: Long): Double? {
            if (samples.isEmpty()) return null
            val last = samples.last()
            val rampNanos = rampMs * 1_000_000L
            val base = samples.firstOrNull { it.first >= rampNanos }
            if (base != null && last.first - base.first >= 1_000_000_000L) {
                return mbps(last.second - base.second, last.first - base.first)
            }
            return mbps(last.second, last.first)
        }

        /**
         * Average rate of one upload stream inside the window [windowStart, windowEnd], from its
         * acknowledged requests. A request that straddles a window edge is credited in
         * proportion to how much of it lies inside. If a request was still unfinished at the
         * end ([unfinishedSince] > 0) nothing is known from that moment on, so the window is
         * cut there. When that leaves too little to judge, everything the stream completed
         * is used instead. Returns bytes per second, or null if the stream confirmed nothing.
         */
        internal fun streamBytesPerSecond(
            completed: List<UploadRequest>,
            windowStart: Long,
            windowEnd: Long,
            unfinishedSince: Long,
        ): Double? {
            if (completed.isEmpty()) return null
            var from = windowStart
            val to = if (unfinishedSince > 0) minOf(windowEnd, unfinishedSince) else windowEnd
            if (to - from < MIN_UPLOAD_WINDOW_NANOS) from = completed.minOf { it.startNanos }
            val until = to
            if (until <= from) return null
            var credited = 0.0
            for (request in completed) {
                val duration = (request.endNanos - request.startNanos).coerceAtLeast(1)
                val overlap = minOf(request.endNanos, until) - maxOf(request.startNanos, from)
                if (overlap > 0) credited += request.bytes * (overlap.toDouble() / duration)
            }
            return credited / ((until - from) / 1_000_000_000.0)
        }

        private const val MIN_UPLOAD_WINDOW_NANOS = 500_000_000L
        private const val SCALE_UP_AFTER_NANOS = 1_500_000_000L

        private fun mbps(bytes: Long, nanos: Long): Double =
            if (nanos <= 0) 0.0 else bytes * 8.0 / (nanos / 1_000_000_000.0) / 1_000_000.0
    }
}
