package com.networkanalyst.core.speed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class SpeedTestEngineTest {
    private fun quickConfig(baseUrl: String) = SpeedTestConfig(
        baseUrl = baseUrl,
        latencySamples = 6,
        downloadDurationMs = 3_500,
        uploadDurationMs = 4_500,
        downloadRampMs = 1_000,
        uploadRampMs = 2_000,
        sampleIntervalMs = 100,
        loadedLatencyIntervalMs = 150,
        stopWhenStable = false,
    )

    @Test
    fun `server timing header is read like the reference client`() {
        assertEquals(27.0, SpeedTestEngine.serverProcessingMs("cfSpeedEdge;dur=2, cfSpeedWorker;dur=25"), 1e-9)
        assertEquals(
            27.0,
            SpeedTestEngine.serverProcessingMs("cfSpeedEdge;dur=2, cfSpeedWorker;dur=25, cfL4;desc=\"?proto=TCP&rtt=9657\""),
            1e-9,
        )
        assertEquals(12.5, SpeedTestEngine.serverProcessingMs("cfRequestDuration;dur=12.5, cfSpeedWorker;dur=99"), 1e-9)
        assertEquals(0.0, SpeedTestEngine.serverProcessingMs(null), 1e-9)
        assertEquals(0.0, SpeedTestEngine.serverProcessingMs("cache;desc=hit, origin;dur=40"), 1e-9)
    }

    @Test
    fun `average excludes the ramp-up window`() {
        val second = 1_000_000_000L
        // 10 MB burst in the first second (buffers filling), then a steady 1 MB/s.
        val samples = listOf(1 * second to 10_000_000L, 2 * second to 11_000_000L, 3 * second to 12_000_000L, 4 * second to 13_000_000L)
        assertEquals(8.0, SpeedTestEngine.averageMbps(samples, rampMs = 1_000)!!, 1e-6)
        // Too short to have a steady window: fall back to the whole run.
        val short = listOf(second / 2 to 500_000L)
        assertEquals(8.0, SpeedTestEngine.averageMbps(short, rampMs = 2_000)!!, 1e-6)
        assertNull(SpeedTestEngine.averageMbps(emptyList(), 1_000))
    }

    @Test
    fun `upload stream rate credits only confirmed bytes inside the window`() {
        val s = 1_000_000_000L
        fun req(from: Double, to: Double, bytes: Long) = SpeedTestEngine.UploadRequest((from * s).toLong(), (to * s).toLong(), bytes)
        // Steady 1 MB/s in back-to-back one second requests from t=0 to t=6; window is 2..5.
        val steady = (0 until 6).map { req(it.toDouble(), it + 1.0, 1_000_000) }
        assertEquals(1_000_000.0, SpeedTestEngine.streamBytesPerSecond(steady, 2 * s, 5 * s, 0)!!, 1.0)
        // A request straddling the window start is credited for the part inside only.
        val straddle = listOf(req(1.0, 3.0, 4_000_000), req(3.0, 5.0, 4_000_000))
        assertEquals(2_000_000.0, SpeedTestEngine.streamBytesPerSecond(straddle, 2 * s, 5 * s, 0)!!, 1.0)
        // A request left unfinished at t=4 cuts the window there: 2 MB confirmed over 2..4.
        val cut = listOf(req(2.0, 3.0, 1_000_000), req(3.0, 4.0, 1_000_000))
        assertEquals(1_000_000.0, SpeedTestEngine.streamBytesPerSecond(cut, 2 * s, 5 * s, 4 * s)!!, 1.0)
        // Very slow stream: its only finished request ended before the window even began, and
        // the next one never finished. Fall back to what it did confirm.
        val slow = listOf(req(0.0, 1.9, 64_000))
        assertEquals(64_000 / 1.9, SpeedTestEngine.streamBytesPerSecond(slow, 2 * s, 5 * s, (1.9 * s).toLong())!!, 1.0)
        assertNull(SpeedTestEngine.streamBytesPerSecond(emptyList(), 2 * s, 5 * s, 0))
    }

    @Test
    fun `stability detector waits for readings to agree`() {
        val ms = 1_000_000L
        val detector = StabilityDetector(tolerance = 0.08)
        // Still climbing: not stable.
        assertTrue(!detector.offer(0 * ms, 50.0))
        assertTrue(!detector.offer(500 * ms, 80.0))
        assertTrue(!detector.offer(1000 * ms, 90.0))
        assertTrue(!detector.offer(1500 * ms, 100.0))
        // Readings closer together than the spacing are ignored.
        assertTrue(!detector.offer(1600 * ms, 100.0))
        assertTrue(!detector.offer(2000 * ms, 101.0))
        assertTrue(!detector.offer(2500 * ms, 99.0))
        // Last four are 100, 101, 99, 100: within 8 percent.
        assertTrue(detector.offer(3000 * ms, 100.0))
        // A sudden drop breaks stability again.
        assertTrue(!detector.offer(3500 * ms, 60.0))
        // Zero throughput never counts as settled.
        val idle = StabilityDetector(tolerance = 0.08)
        repeat(6) { assertTrue(!idle.offer(it * 500 * ms, 0.0)) }
    }

    @Test
    fun `steady line finishes early and unsteady settings run the full time`() = runBlocking {
        FakeSpeedServer(downBytesPerSecond = 5_000_000, upBytesPerSecond = 1_000_000).use { server ->
            val config = quickConfig(server.baseUrl).copy(
                runUpload = false, downloadDurationMs = 12_000, downloadRampMs = 1_000, minSteadyMs = 2_000, stopWhenStable = true,
            )
            val started = System.nanoTime()
            val result = SpeedTestEngine(config = config).run()
            val seconds = (System.nanoTime() - started) / 1e9
            println("early finish: ${"%.1f".format(seconds)}s of a 12s limit, measured ${result.downloadMbps}")
            assertEquals(40.0, result.downloadMbps!!, 40.0 * 0.05)
            assertTrue("took ${seconds}s", seconds < 6.0)
        }
    }

    @Test
    fun `recent rate looks at the trailing window only`() {
        val second = 1_000_000_000L
        val samples = listOf(second to 1_000_000L, 2 * second to 2_000_000L, 3 * second to 12_000_000L)
        assertEquals(80.0, SpeedTestEngine.recentMbps(samples, windowMs = 1_000), 1e-6)
        assertEquals(0.0, SpeedTestEngine.recentMbps(emptyList(), 1_000), 1e-9)
    }

    @Test
    fun `measures a rate-limited line in both directions`() = runBlocking {
        // 5 MB/s down (40 Mbps) and 1.5 MB/s up (12 Mbps), shared across all connections.
        FakeSpeedServer(downBytesPerSecond = 5_000_000, upBytesPerSecond = 1_500_000, baseLatencyMs = 10).use { server ->
            val phases = ArrayList<SpeedPhase>()
            var throughputUpdates = 0
            val result = SpeedTestEngine(config = quickConfig(server.baseUrl)).run { progress ->
                when (progress) {
                    is SpeedTestProgress.PhaseStarted -> phases += progress.phase
                    is SpeedTestProgress.Throughput -> throughputUpdates++
                    is SpeedTestProgress.LatencySample -> Unit
                }
            }
            println("fake line 40/12 Mbps -> measured down=${result.downloadMbps} up=${result.uploadMbps} idle=${result.idleLatencyMs}ms")
            assertNull(result.error)
            assertEquals(40.0, result.downloadMbps!!, 40.0 * 0.05)
            assertEquals(12.0, result.uploadMbps!!, 12.0 * 0.08)
            // 13 ms of server delay, 3 ms of which is declared in Server-Timing and subtracted.
            assertTrue("idle latency ${result.idleLatencyMs}", result.idleLatencyMs!! in 9.0..25.0)
            assertNotNull(result.idleJitterMs)
            assertEquals("Cloudflare TST", result.serverLocation)
            assertEquals("203.0.113.9", result.publicIp)
            assertEquals("AS64500", result.asn)
            assertEquals("Testville", result.city)
            assertEquals(listOf(SpeedPhase.LATENCY, SpeedPhase.DOWNLOAD, SpeedPhase.UPLOAD, SpeedPhase.DONE), phases)
            assertTrue(throughputUpdates > 20)
            assertTrue(result.bytesDownloaded > 10_000_000)
            assertTrue(result.bytesUploaded > 3_000_000)
            // Only bytes the server really received may be reported as uploaded.
            assertTrue("reported ${result.bytesUploaded}, server got ${server.bytesReceived.get()}", result.bytesUploaded <= server.bytesReceived.get())
            assertEquals("four parallel download streams", 4, server.peakDownloads.get())
            assertEquals("four parallel upload streams", 4, server.peakUploads.get())
        }
    }

    @Test
    fun `latency under load exposes bufferbloat`() = runBlocking {
        FakeSpeedServer(
            downBytesPerSecond = 2_000_000,
            upBytesPerSecond = 2_000_000,
            baseLatencyMs = 10,
            loadedExtraLatencyMs = 120,
        ).use { server ->
            val result = SpeedTestEngine(config = quickConfig(server.baseUrl)).run()
            println("bufferbloat sim -> idle=${result.idleLatencyMs} dl-loaded=${result.downloadLoadedLatencyMs} ul-loaded=${result.uploadLoadedLatencyMs}")
            assertTrue(result.idleLatencyMs!! < 30)
            assertTrue("download loaded ${result.downloadLoadedLatencyMs}", result.downloadLoadedLatencyMs!! in 120.0..190.0)
            assertTrue("upload loaded ${result.uploadLoadedLatencyMs}", result.uploadLoadedLatencyMs!! in 120.0..190.0)
            assertTrue(result.bufferbloatMs!! in 100.0..170.0)
            assertEquals(BufferbloatGrade.C, BufferbloatGrade.of(result.bufferbloatMs!!))
        }
    }

    @Test
    fun `more streams are opened on a fast line`() = runBlocking {
        FakeSpeedServer(downBytesPerSecond = 40_000_000, upBytesPerSecond = 1_000_000).use { server ->
            val config = quickConfig(server.baseUrl).copy(runUpload = false, addStreamsAboveMbps = 100.0)
            val result = SpeedTestEngine(config = config).run()
            println("fast line 320 Mbps -> measured down=${result.downloadMbps}, peak connections ${server.peakDownloads.get()}")
            assertEquals(320.0, result.downloadMbps!!, 320.0 * 0.05)
            assertEquals(8, server.peakDownloads.get())
            assertNull(result.uploadMbps)
        }
    }

    @Test
    fun `refused downloads are reported and upload still runs`() = runBlocking {
        FakeSpeedServer(downBytesPerSecond = 5_000_000, upBytesPerSecond = 2_000_000, downloadStatus = 403).use { server ->
            val started = System.nanoTime()
            val result = SpeedTestEngine(config = quickConfig(server.baseUrl)).run()
            assertNull(result.downloadMbps)
            assertTrue(result.error!!.contains("HTTP 403"))
            assertNotNull(result.uploadMbps)
            // The refused download phase must end early instead of waiting out its full duration.
            assertTrue((System.nanoTime() - started) / 1e9 < 7.5)
        }
    }

    @Test
    fun `unreachable server gives a clear error quickly`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val started = System.nanoTime()
        val result = SpeedTestEngine(config = quickConfig("http://127.0.0.1:$closedPort")).run()
        assertTrue(result.error!!.startsWith("Could not reach the speed test server"))
        assertNull(result.downloadMbps)
        assertNull(result.idleLatencyMs)
        assertTrue((System.nanoTime() - started) / 1e9 < 5)
    }

    @Test
    fun `byte cap stops a transfer early`() = runBlocking {
        FakeSpeedServer(downBytesPerSecond = 5_000_000, upBytesPerSecond = 1_000_000).use { server ->
            val config = quickConfig(server.baseUrl).copy(runUpload = false, maxBytesPerDirection = 4_000_000)
            val result = SpeedTestEngine(config = config).run()
            assertTrue("downloaded ${result.bytesDownloaded}", result.bytesDownloaded in 4_000_000..6_500_000)
            assertNotNull(result.downloadMbps)
        }
    }

    @Test
    fun `cancelling stops the test promptly`() = runBlocking {
        FakeSpeedServer(downBytesPerSecond = 1_000_000, upBytesPerSecond = 1_000_000).use { server ->
            val config = quickConfig(server.baseUrl).copy(downloadDurationMs = 30_000)
            val job = async(Dispatchers.Default) { SpeedTestEngine(config = config).run() }
            delay(1_500)
            val started = System.nanoTime()
            job.cancelAndJoin()
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue("cancel took ${seconds}s", seconds < 2.0)
            assertTrue(job.isCancelled)
        }
    }
}
