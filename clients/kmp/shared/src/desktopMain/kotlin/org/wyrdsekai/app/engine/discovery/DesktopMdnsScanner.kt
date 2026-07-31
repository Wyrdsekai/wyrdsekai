package org.wyrdsekai.app.engine.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Desktop mDNS scanner using raw multicast UDP.
 *
 * Sends an mDNS query for _wyrdsekai._tcp.local to the multicast group
 * 224.0.0.251:5353 and parses DNS responses for SRV and TXT records.
 *
 * This is a minimal mDNS implementation — sufficient for LAN discovery
 * of a known service type. It does not implement the full DNS-SD spec
 * (no caching, no conflict resolution, no continuous browsing). For our
 * use case (find one household server on the LAN), this is adequate.
 *
 * Alternative approaches considered:
 * - JmDNS: Pure Java, full-featured, but adds an external dependency.
 * - System commands (dns-sd / avahi-browse): Platform-specific, requires
 *   parsing command output, may not be installed.
 * - Raw multicast: No dependencies, works on all JVM platforms, simple
 *   enough for our single-service-type use case.
 *
 */
class DesktopMdnsScanner : MdnsScanner {

    @Volatile
    private var running = false

    override suspend fun scan(
        serviceType: String,
        timeoutMs: Long,
    ): DiscoveredHousehold? {
        running = true
        return try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    queryMdns(serviceType, timeoutMs)
                }
            }
        } finally {
            running = false
        }
    }

    /**
     * Send an mDNS query and listen for responses.
     *
     * The mDNS multicast address is 224.0.0.251, port 5353.
     * We send a PTR query for _wyrdsekai._tcp.local and parse
     * responses for SRV (host/port) and TXT (attributes) records.
     */
    private fun queryMdns(serviceType: String, timeoutMs: Long): DiscoveredHousehold? {
        val mdnsGroup = InetAddress.getByName(MDNS_ADDR)
        val mdnsPort = MDNS_PORT

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = minOf(timeoutMs.toInt(), 2000) // Poll interval

            // Build and send PTR query
            val queryName = serviceType.removeSuffix(".") + "."
            val queryPacket = buildMdnsQuery(queryName)
            socket.send(DatagramPacket(queryPacket, queryPacket.size, mdnsGroup, mdnsPort))

            // Listen for responses
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (running && System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)

                    val household = parseMdnsResponse(
                        packet.data, packet.length, packet.address, serviceType,
                    )
                    if (household != null) {
                        return household
                    }
                } catch (_: SocketTimeoutException) {
                    // Re-send query periodically in case the first was lost
                    if (System.currentTimeMillis() + 1000 < deadline) {
                        socket.send(DatagramPacket(queryPacket, queryPacket.size, mdnsGroup, mdnsPort))
                    }
                }
            }
            return null
        } catch (_: Exception) {
            // Network errors (no network, permission denied, etc.)
            return null
        } finally {
            socket?.close()
        }
    }

    /**
     * Build a minimal DNS query packet for a PTR record.
     *
     * DNS packet format:
     * - Header (12 bytes): ID, flags, counts
     * - Question section: QNAME + QTYPE(PTR=12) + QCLASS(IN=1)
     */
    internal fun buildMdnsQuery(name: String): ByteArray {
        val buf = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)

        // Header
        buf.putShort(0x0000)    // ID (0 for mDNS)
        buf.putShort(0x0000)    // Flags: standard query
        buf.putShort(0x0001)    // QDCOUNT: 1 question
        buf.putShort(0x0000)    // ANCOUNT
        buf.putShort(0x0000)    // NSCOUNT
        buf.putShort(0x0000)    // ARCOUNT

        // Question: encode name as DNS labels
        encodeDnsName(buf, name)
        buf.putShort(0x000C)    // QTYPE: PTR
        buf.putShort(0x0001)    // QCLASS: IN

        val result = ByteArray(buf.position())
        buf.flip()
        buf.get(result)
        return result
    }

    /**
     * Encode a DNS name as a sequence of length-prefixed labels.
     * "example._tcp.local." -> [7]example[4]_tcp[5]local[0]
     */
    private fun encodeDnsName(buf: ByteBuffer, name: String) {
        val labels = name.trimEnd('.').split('.')
        for (label in labels) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.UTF_8))
        }
        buf.put(0) // Root label
    }

    /**
     * Parse an mDNS response and extract household info from TXT + SRV records.
     *
     * Returns null if the response doesn't contain our service or is unparseable.
     * This is intentionally lenient — we'd rather miss a malformed response
     * and retry than crash.
     */
    internal fun parseMdnsResponse(
        data: ByteArray,
        length: Int,
        senderAddress: InetAddress,
        serviceType: String,
    ): DiscoveredHousehold? {
        if (length < 12) return null // Too short for DNS header

        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

        // Header
        buf.getShort() // ID
        val flags = buf.getShort().toInt() and 0xFFFF
        val isResponse = (flags and 0x8000) != 0
        if (!isResponse) return null // Not a response

        val qdCount = buf.getShort().toInt() and 0xFFFF
        val anCount = buf.getShort().toInt() and 0xFFFF
        val nsCount = buf.getShort().toInt() and 0xFFFF
        val arCount = buf.getShort().toInt() and 0xFFFF

        // Skip questions
        for (i in 0 until qdCount) {
            skipDnsName(buf) ?: return null
            if (buf.remaining() < 4) return null
            buf.getShort() // QTYPE
            buf.getShort() // QCLASS
        }

        // Parse all answer + authority + additional records
        val totalRecords = anCount + nsCount + arCount
        var srvHost: String? = null
        var srvPort: Int? = null
        val txtAttrs = mutableMapOf<String, String>()
        var foundService = false

        for (i in 0 until totalRecords) {
            if (buf.remaining() < 1) break

            val name = readDnsName(buf, data) ?: break
            if (buf.remaining() < 10) break

            val type = buf.getShort().toInt() and 0xFFFF
            buf.getShort() // CLASS (ignore cache-flush bit)
            buf.getInt()   // TTL
            val rdLength = buf.getShort().toInt() and 0xFFFF

            if (buf.remaining() < rdLength) break
            val rdStart = buf.position()

            val normalizedName = name.lowercase()
            val normalizedType = serviceType.removeSuffix(".").lowercase()

            if (normalizedName.contains(normalizedType.removeSuffix(".local"))) {
                foundService = true
            }

            when (type) {
                DNS_TYPE_SRV -> {
                    if (rdLength >= 6) {
                        buf.getShort() // priority
                        buf.getShort() // weight
                        srvPort = buf.getShort().toInt() and 0xFFFF
                        srvHost = readDnsName(buf, data)
                    }
                }
                DNS_TYPE_TXT -> {
                    parseTxtRecord(data, rdStart, rdLength, txtAttrs)
                }
            }

            // Advance to end of RDATA regardless of what we parsed
            buf.position(rdStart + rdLength)
        }

        if (!foundService) return null

        val host = srvHost?.trimEnd('.') ?: senderAddress.hostAddress ?: "127.0.0.1"
        val port = srvPort ?: 9222

        val natsWs = txtAttrs["nats_ws"] ?: "ws://$host:$port"

        return DiscoveredHousehold(
            householdId = txtAttrs["household_id"] ?: "unknown",
            householdName = txtAttrs["household_name"] ?: "Unknown Household",
            natsWsUrl = natsWs,
            relayUrl = txtAttrs["relay_url"],
            relayToken = txtAttrs["relay_token"],
            version = txtAttrs["version"] ?: "1.0",
        )
    }

    /**
     * Parse TXT record data: sequence of length-prefixed "key=value" strings.
     */
    private fun parseTxtRecord(
        data: ByteArray,
        offset: Int,
        length: Int,
        attrs: MutableMap<String, String>,
    ) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val txtLen = data[pos].toInt() and 0xFF
            pos++
            if (txtLen == 0 || pos + txtLen > end) break
            val txt = String(data, pos, txtLen, Charsets.UTF_8)
            val eqIdx = txt.indexOf('=')
            if (eqIdx > 0) {
                attrs[txt.substring(0, eqIdx)] = txt.substring(eqIdx + 1)
            }
            pos += txtLen
        }
    }

    /**
     * Read a DNS name from the buffer, handling compression pointers.
     * Returns null if the name is malformed.
     */
    private fun readDnsName(buf: ByteBuffer, fullData: ByteArray): String? {
        val parts = mutableListOf<String>()
        var jumps = 0
        var savedPos = -1

        while (buf.remaining() > 0) {
            val labelLen = buf.get().toInt() and 0xFF
            if (labelLen == 0) break // End of name

            if ((labelLen and 0xC0) == 0xC0) {
                // Compression pointer
                if (buf.remaining() < 1) return null
                val offset = ((labelLen and 0x3F) shl 8) or (buf.get().toInt() and 0xFF)
                if (savedPos == -1) savedPos = buf.position()
                if (offset >= fullData.size) return null
                buf.position(offset)
                jumps++
                if (jumps > 10) return null // Prevent infinite loops
                continue
            }

            if (buf.remaining() < labelLen) return null
            val label = ByteArray(labelLen)
            buf.get(label)
            parts.add(String(label, Charsets.UTF_8))
        }

        if (savedPos != -1) buf.position(savedPos)
        return parts.joinToString(".")
    }

    /**
     * Skip a DNS name in the buffer (for question section parsing).
     * Returns true on success, null on failure.
     */
    private fun skipDnsName(buf: ByteBuffer): Boolean? {
        while (buf.remaining() > 0) {
            val labelLen = buf.get().toInt() and 0xFF
            if (labelLen == 0) return true
            if ((labelLen and 0xC0) == 0xC0) {
                // Compression pointer — skip the second byte
                if (buf.remaining() < 1) return null
                buf.get()
                return true
            }
            if (buf.remaining() < labelLen) return null
            buf.position(buf.position() + labelLen)
        }
        return null
    }

    /**
     * Stop any active scan. Safe to call multiple times.
     */
    fun stopDiscovery() {
        running = false
    }

    companion object {
        private const val MDNS_ADDR = "224.0.0.251"
        private const val MDNS_PORT = 5353

        // DNS record types
        private const val DNS_TYPE_SRV = 33
        private const val DNS_TYPE_TXT = 16
    }
}
