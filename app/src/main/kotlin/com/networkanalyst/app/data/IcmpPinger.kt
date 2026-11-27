package com.networkanalyst.app.data

import android.net.Network
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.networkanalyst.core.ping.IcmpPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.IOException
import java.net.Inet4Address
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** ICMP could not be used at all (as opposed to "sent, but nobody answered"). */
class IcmpUnavailableException(message: String, val permissionDenied: Boolean = false) : IOException(message)

/**
 * Pings without root using a Linux "ping socket" (SOCK_DGRAM + IPPROTO_ICMP), the same
 * mechanism Android's own InetAddress.isReachable() uses. The kernel fills in the ICMP
 * identifier and checksum and only delivers echo replies meant for this socket.
 */
class IcmpPinger(private val network: Network?) {

    private fun open(): FileDescriptor {
        val fd = try {
            Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        } catch (e: ErrnoException) {
            throw IcmpUnavailableException("ICMP sockets are not available on this device (${e.message})", isPermissionError(e))
        }
        try {
            network?.bindSocket(fd)
        } catch (e: IOException) {
            closeQuietly(fd)
            throw IcmpUnavailableException("Could not bind the ICMP socket to the network (${e.message})")
        }
        return fd
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
        }
    }

    private fun isPermissionError(e: ErrnoException) = e.errno == OsConstants.EPERM || e.errno == OsConstants.EACCES

    /**
     * Sends [count] echo requests [intervalMs] apart and returns the round-trip time in ms of
     * each, in order, with null for those that got no reply within [timeoutMs].
     *
     * @throws IcmpUnavailableException if echo requests cannot be sent at all
     */
    suspend fun ping(address: Inet4Address, count: Int, intervalMs: Long = 200, timeoutMs: Long = 1_000): List<Double?> =
        withContext(Dispatchers.IO) {
            val fd = open()
            try {
                val nonce = Random.nextLong()
                val rtts = arrayOfNulls<Double>(count)
                val buffer = ByteArray(256)
                var sent = 0
                var lastError: ErrnoException? = null
                for (sequence in 0 until count) {
                    coroutineContext.ensureActive()
                    val sentAt = System.nanoTime()
                    val packet = IcmpPacket.buildEchoRequest(sequence, nonce, sentAt)
                    try {
                        Os.sendto(fd, packet, 0, packet.size, 0, address, 0)
                        sent++
                    } catch (e: ErrnoException) {
                        if (isPermissionError(e)) {
                            throw IcmpUnavailableException("Android blocked the ping (${e.message})", permissionDenied = true)
                        }
                        lastError = e
                    }
                    val last = sequence == count - 1
                    val waitUntil = sentAt + (if (last) timeoutMs else intervalMs) * 1_000_000L
                    receive(fd, buffer, waitUntil, nonce, stopWhenComplete = last) { reply, receivedAt ->
                        if (reply.sequence < count && rtts[reply.sequence] == null) {
                            rtts[reply.sequence] = (receivedAt - reply.sentAtNanos) / 1_000_000.0
                        }
                        rtts.all { it != null }
                    }
                }
                if (sent == 0) throw IcmpUnavailableException("No echo request could be sent (${lastError?.message})")
                rtts.toList()
            } finally {
                closeQuietly(fd)
            }
        }

    /**
     * Pings every address once (twice for those slow to answer) and returns the round-trip
     * time of each one that replied. Used to find which addresses on the subnet are alive.
     */
    suspend fun sweep(addresses: List<Inet4Address>, timeoutMs: Long = 1_500): Map<Inet4Address, Double> =
        withContext(Dispatchers.IO) {
            if (addresses.isEmpty()) return@withContext emptyMap()
            require(addresses.size <= 0xFFFF) { "too many addresses for one sweep" }
            val fd = open()
            try {
                val nonce = Random.nextLong()
                val buffer = ByteArray(256)
                val results = HashMap<Inet4Address, Double>()
                val onReply = { reply: IcmpPacket.EchoReply, receivedAt: Long ->
                    addresses.getOrNull(reply.sequence)?.let { address ->
                        results.putIfAbsent(address, (receivedAt - reply.sentAtNanos) / 1_000_000.0)
                    }
                    results.size == addresses.size
                }
                // Two passes: phones asleep on Wi-Fi often miss the first packet.
                for (pass in 0 until 2) {
                    var permissionChecked = pass > 0
                    addresses.forEachIndexed { index, address ->
                        if (address in results) return@forEachIndexed
                        coroutineContext.ensureActive()
                        val packet = IcmpPacket.buildEchoRequest(index, nonce, System.nanoTime())
                        try {
                            Os.sendto(fd, packet, 0, packet.size, 0, address, 0)
                        } catch (e: ErrnoException) {
                            if (!permissionChecked && isPermissionError(e)) {
                                throw IcmpUnavailableException("Android blocked local network access (${e.message})", permissionDenied = true)
                            }
                        }
                        permissionChecked = true
                        // Drain replies as we go so the receive buffer cannot overflow, and
                        // pace the sends a little so the Wi-Fi driver is not flooded.
                        if (index % 16 == 15) {
                            receive(fd, buffer, System.nanoTime() + 4_000_000L, nonce, stopWhenComplete = true, onReply)
                        }
                    }
                    val wait = if (pass == 0) timeoutMs / 3 else timeoutMs
                    receive(fd, buffer, System.nanoTime() + wait * 1_000_000L, nonce, stopWhenComplete = true, onReply)
                    if (results.size == addresses.size) break
                }
                results
            } finally {
                closeQuietly(fd)
            }
        }

    /**
     * Reads echo replies until [waitUntilNanos]. [onReply] returns true once nothing more is
     * expected; with [stopWhenComplete] that ends the wait early.
     */
    private fun receive(
        fd: FileDescriptor,
        buffer: ByteArray,
        waitUntilNanos: Long,
        nonce: Long,
        stopWhenComplete: Boolean,
        onReply: (IcmpPacket.EchoReply, Long) -> Boolean,
    ) {
        val pollFd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        val fds = arrayOf(pollFd)
        while (true) {
            val remainingMs = (waitUntilNanos - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) return
            try {
                pollFd.revents = 0
                if (Os.poll(fds, remainingMs.toInt().coerceAtLeast(1)) <= 0) continue
                val length = Os.recvfrom(fd, buffer, 0, buffer.size, 0, null)
                val receivedAt = System.nanoTime()
                val reply = IcmpPacket.parseEchoReply(buffer, length) ?: continue
                if (reply.nonce != nonce) continue
                if (onReply(reply, receivedAt) && stopWhenComplete) return
            } catch (e: ErrnoException) {
                // Interrupted system call or an ICMP error surfaced on the socket: keep waiting.
                if (e.errno != OsConstants.EINTR && e.errno != OsConstants.EAGAIN) {
                    // Avoid spinning if the socket keeps failing.
                    if (System.nanoTime() >= waitUntilNanos) return
                    Thread.sleep(1)
                }
            } catch (_: IOException) {
                return
            }
        }
    }
}
