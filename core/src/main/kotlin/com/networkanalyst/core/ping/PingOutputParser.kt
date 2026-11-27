package com.networkanalyst.core.ping

/**
 * Parses the text printed by the `ping` command-line tool. Handles both implementations
 * found on Linux-based systems: iputils ("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms")
 * and toybox (same reply line, different header and summary).
 */
object PingOutputParser {
    data class Reply(val from: String, val sequence: Int, val ttl: Int?, val timeMs: Double)

    /** An ICMP error reported by a router on the way, e.g. "Time to live exceeded". */
    data class RouterError(val from: String, val sequence: Int?, val message: String) {
        val isTtlExceeded: Boolean get() = message.contains("Time to live exceeded", ignoreCase = true)
        val isFragmentationNeeded: Boolean get() = message.contains("Frag needed", ignoreCase = true)
    }

    data class Parsed(
        val replies: List<Reply>,
        val errors: List<RouterError>,
        val transmitted: Int?,
        val received: Int?,
    )

    // "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms"
    // "64 bytes from one.one.one.one (1.1.1.1): icmp_seq=1 ttl=57 time=12.3 ms"
    private val replyRegex = Regex(
        """^\d+ bytes from (\S+?)(?: \(([^)]+)\))?: .*?icmp_seq=(\d+)(?:.*?ttl=(\d+))?.*?time[=<]\s*([\d.]+)\s*ms""",
    )

    // "From 192.168.1.1 icmp_seq=1 Time to live exceeded"   (iputils, ping socket)
    // "From 192.168.1.1: icmp_seq=1 Time to live exceeded"  (iputils, raw socket)
    // "From router.lan (192.168.1.1) icmp_seq=1 Destination Host Unreachable"
    private val errorRegex = Regex("""^From (\S+?)(?: \(([^)]+)\))?:?\s+(?:icmp_seq=(\d+)\s+)?(.+)$""")

    private val summaryRegex = Regex("""(\d+) packets transmitted, (\d+) (?:packets )?received""")

    fun parse(output: String): Parsed {
        val replies = mutableListOf<Reply>()
        val errors = mutableListOf<RouterError>()
        var transmitted: Int? = null
        var received: Int? = null
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            replyRegex.find(line)?.let { m ->
                replies += Reply(
                    from = m.groupValues[2].ifEmpty { m.groupValues[1] },
                    sequence = m.groupValues[3].toInt(),
                    ttl = m.groupValues[4].toIntOrNull(),
                    timeMs = m.groupValues[5].toDouble(),
                )
                return@let
            } ?: errorRegex.find(line)?.let { m ->
                errors += RouterError(
                    from = m.groupValues[2].ifEmpty { m.groupValues[1] },
                    sequence = m.groupValues[3].toIntOrNull(),
                    message = m.groupValues[4].trim(),
                )
            } ?: summaryRegex.find(line)?.let { m ->
                transmitted = m.groupValues[1].toInt()
                received = m.groupValues[2].toInt()
            }
        }
        return Parsed(replies, errors, transmitted, received)
    }
}
