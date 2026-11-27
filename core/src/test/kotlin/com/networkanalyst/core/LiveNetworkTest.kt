package com.networkanalyst.core

import com.networkanalyst.core.dns.DnsBenchmark
import com.networkanalyst.core.dns.DnsServer
import com.networkanalyst.core.speed.SpeedPhase
import com.networkanalyst.core.speed.SpeedTestConfig
import com.networkanalyst.core.speed.SpeedTestEngine
import com.networkanalyst.core.speed.SpeedTestProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests that talk to real servers on the internet. They are skipped unless the build is run
 * with `-PliveTests`, e.g. `./gradlew :core:test -PliveTests --tests '*LiveNetworkTest*'`.
 */
class LiveNetworkTest {
    @Before
    fun onlyWhenRequested() {
        assumeTrue("live tests not requested", System.getProperty("liveTests") == "true")
    }

    @Test
    fun `speed test against the real Cloudflare endpoints`() = runBlocking {
        val config = SpeedTestConfig(latencySamples = 10, downloadDurationMs = 6_000, uploadDurationMs = 6_000)
        var peakDown = 0.0
        var peakUp = 0.0
        val result = SpeedTestEngine(config = config).run { progress ->
            if (progress is SpeedTestProgress.Throughput) {
                if (progress.phase == SpeedPhase.DOWNLOAD) peakDown = maxOf(peakDown, progress.currentMbps)
                if (progress.phase == SpeedPhase.UPLOAD) peakUp = maxOf(peakUp, progress.currentMbps)
            }
        }
        println(
            "LIVE speed: down=%.1f Mbps (peak %.1f) up=%.1f Mbps (peak %.1f) idle=%.1f ms jitter=%.1f ms dl-loaded=%s ul-loaded=%s".format(
                result.downloadMbps, peakDown, result.uploadMbps, peakUp, result.idleLatencyMs, result.idleJitterMs,
                result.downloadLoadedLatencyMs, result.uploadLoadedLatencyMs,
            ),
        )
        println("LIVE speed: server=${result.serverLocation} asn=${result.asn} country=${result.country} bytesDown=${result.bytesDownloaded} bytesUp=${result.bytesUploaded} error=${result.error}")
        assertNull(result.error)
        assertTrue(result.downloadMbps!! > 0.5)
        assertTrue(result.uploadMbps!! > 0.1)
        assertTrue(result.idleLatencyMs!! > 0)
        assertTrue(result.serverLocation!!.startsWith("Cloudflare "))
        assertNotNull(result.publicIp)
        assertNotNull(result.downloadLoadedLatencyMs)
        assertNotNull(result.uploadLoadedLatencyMs)
    }

    @Test
    fun `dns benchmark against real resolvers`() = runBlocking {
        val results = DnsBenchmark().run(DnsBenchmark.PUBLIC_SERVERS + DnsServer("Google secondary", "8.8.4.4"))
        for (r in results) {
            println("LIVE dns: %-16s %-15s queries=%d failures=%d median=%s ms".format(r.name, r.address, r.queries, r.failures, r.medianMs))
        }
        assertTrue("at least one public resolver must answer", results.any { it.usable })
    }
}
