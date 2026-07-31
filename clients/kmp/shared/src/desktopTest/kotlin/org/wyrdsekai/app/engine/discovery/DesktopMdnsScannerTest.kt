package org.wyrdsekai.app.engine.discovery

import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for DesktopMdnsScanner's DNS query building and response parsing.
 *
 * These test the internal packet construction and parsing logic without
 * requiring network access. The actual mDNS multicast discovery is tested
 * in the E2E suite (T2+) against a real household server.
 */
class DesktopMdnsScannerTest {

    private val scanner = DesktopMdnsScanner()

    // ── Query building ────────────────────────────────────────────────────

    @Test
    fun buildMdnsQueryCreatesValidDnsPacket() {
        val query = scanner.buildMdnsQuery("_wyrdsekai._tcp.local.")

        // Minimum DNS packet: 12 byte header + question
        assertTrue(query.size >= 12, "Query too short: ${query.size}")

        val buf = ByteBuffer.wrap(query).order(ByteOrder.BIG_ENDIAN)

        // Header
        assertEquals(0, buf.getShort().toInt(), "ID should be 0 for mDNS")
        assertEquals(0, buf.getShort().toInt(), "Flags should be 0 for standard query")
        assertEquals(1, buf.getShort().toInt(), "Should have 1 question")
        assertEquals(0, buf.getShort().toInt(), "Should have 0 answers")
        assertEquals(0, buf.getShort().toInt(), "Should have 0 authority")
        assertEquals(0, buf.getShort().toInt(), "Should have 0 additional")

        // Question section: read labels
        val labels = mutableListOf<String>()
        while (buf.remaining() > 0) {
            val len = buf.get().toInt() and 0xFF
            if (len == 0) break
            val bytes = ByteArray(len)
            buf.get(bytes)
            labels.add(String(bytes))
        }

        assertEquals(listOf("_wyrdsekai", "_tcp", "local"), labels)

        // QTYPE and QCLASS
        assertEquals(12, buf.getShort().toInt(), "QTYPE should be PTR (12)")
        assertEquals(1, buf.getShort().toInt(), "QCLASS should be IN (1)")
    }

    @Test
    fun buildMdnsQueryWithDifferentServiceType() {
        val query = scanner.buildMdnsQuery("_http._tcp.local.")
        assertTrue(query.size >= 12)

        val buf = ByteBuffer.wrap(query).order(ByteOrder.BIG_ENDIAN)
        buf.position(12) // Skip header

        val labels = mutableListOf<String>()
        while (buf.remaining() > 0) {
            val len = buf.get().toInt() and 0xFF
            if (len == 0) break
            val bytes = ByteArray(len)
            buf.get(bytes)
            labels.add(String(bytes))
        }

        assertEquals(listOf("_http", "_tcp", "local"), labels)
    }

    // ── Response parsing ──────────────────────────────────────────────────

    @Test
    fun parseResponseRequiresMinimumLength() {
        val result = scanner.parseMdnsResponse(
            ByteArray(8), 8, InetAddress.getLoopbackAddress(), MdnsScanner.SERVICE_TYPE,
        )
        assertNull(result, "Should return null for packet shorter than DNS header")
    }

    @Test
    fun parseResponseRejectsQueryPacket() {
        // Build a query (not a response) — flags = 0x0000
        val packet = buildMinimalDnsPacket(flags = 0x0000)
        val result = scanner.parseMdnsResponse(
            packet, packet.size, InetAddress.getLoopbackAddress(), MdnsScanner.SERVICE_TYPE,
        )
        assertNull(result, "Should return null for query (non-response) packet")
    }

    @Test
    fun parseResponseWithTxtRecord() {
        val packet = buildResponseWithTxt(
            serviceName = "_wyrdsekai._tcp.local",
            txtEntries = mapOf(
                "household_id" to "hh-test-123",
                "household_name" to "Test Home",
                "nats_ws" to "ws://198.51.100.50:9222",
                "relay_url" to "wss://relay.example.com:443",
                "version" to "1.0",
            ),
        )

        val result = scanner.parseMdnsResponse(
            packet, packet.size, InetAddress.getLoopbackAddress(), MdnsScanner.SERVICE_TYPE,
        )

        assertNotNull(result)
        assertEquals("hh-test-123", result.householdId)
        assertEquals("Test Home", result.householdName)
        assertEquals("ws://198.51.100.50:9222", result.natsWsUrl)
        assertEquals("wss://relay.example.com:443", result.relayUrl)
        assertEquals("1.0", result.version)
    }

    @Test
    fun parseResponseFallsBackToSenderAddressWhenNoSrv() {
        val packet = buildResponseWithTxt(
            serviceName = "_wyrdsekai._tcp.local",
            txtEntries = mapOf(
                "household_id" to "hh-fallback",
                "household_name" to "Fallback Home",
            ),
        )

        val senderAddr = InetAddress.getByName("192.0.2.42")
        val result = scanner.parseMdnsResponse(
            packet, packet.size, senderAddr, MdnsScanner.SERVICE_TYPE,
        )

        assertNotNull(result)
        assertEquals("hh-fallback", result.householdId)
        // natsWsUrl should use sender address as fallback
        assertTrue(result.natsWsUrl.contains("192.0.2.42"), "Should fall back to sender address")
    }

    @Test
    fun parseResponseIgnoresUnrelatedService() {
        val packet = buildResponseWithTxt(
            serviceName = "_http._tcp.local",
            txtEntries = mapOf("path" to "/index.html"),
        )

        val result = scanner.parseMdnsResponse(
            packet, packet.size, InetAddress.getLoopbackAddress(), MdnsScanner.SERVICE_TYPE,
        )

        assertNull(result, "Should return null for non-wyrdsekai service")
    }

    @Test
    fun parseResponseWithEmptyTxtFallsBackToDefaults() {
        val packet = buildResponseWithTxt(
            serviceName = "_wyrdsekai._tcp.local",
            txtEntries = emptyMap(),
        )

        val result = scanner.parseMdnsResponse(
            packet, packet.size, InetAddress.getLoopbackAddress(), MdnsScanner.SERVICE_TYPE,
        )

        assertNotNull(result)
        assertEquals("unknown", result.householdId)
        assertEquals("Unknown Household", result.householdName)
        assertEquals("1.0", result.version)
        assertNull(result.relayUrl)
        assertNull(result.relayToken)
    }

    // ── Scan with short timeout ────────────────────────────────────────────

    @Test
    fun scanReturnsNullOnShortTimeout() = runTest {
        // With a 1ms timeout and no mDNS services on the test network,
        // this should return null quickly
        val result = scanner.scan(timeoutMs = 1)
        assertNull(result)
    }

    // ── stopDiscovery safety ──────────────────────────────────────────────

    @Test
    fun stopDiscoveryIsSafeToCallWithoutActiveScan() {
        // Should not throw
        scanner.stopDiscovery()
    }

    @Test
    fun stopDiscoveryIsSafeToCallMultipleTimes() {
        scanner.stopDiscovery()
        scanner.stopDiscovery()
        scanner.stopDiscovery()
        // No exception = pass
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Build a minimal DNS packet with just a header (no questions/answers).
     */
    private fun buildMinimalDnsPacket(flags: Int): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0)                  // ID
        buf.putShort(flags.toShort())    // Flags
        buf.putShort(0)                  // QDCOUNT
        buf.putShort(0)                  // ANCOUNT
        buf.putShort(0)                  // NSCOUNT
        buf.putShort(0)                  // ARCOUNT
        return buf.array()
    }

    /**
     * Build an mDNS response packet containing a TXT record for the given service.
     *
     * This constructs a valid DNS response with:
     * - Header: QR=1 (response), 1 answer
     * - Answer: TXT record with the given service name and key=value entries
     */
    private fun buildResponseWithTxt(
        serviceName: String,
        txtEntries: Map<String, String>,
    ): ByteArray {
        val buf = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN)

        // Header
        buf.putShort(0)                          // ID
        buf.putShort(0x8400.toShort())           // Flags: QR=1 (response), AA=1
        buf.putShort(0)                          // QDCOUNT
        buf.putShort(1)                          // ANCOUNT: 1 TXT record
        buf.putShort(0)                          // NSCOUNT
        buf.putShort(0)                          // ARCOUNT

        // Answer: TXT record
        encodeDnsName(buf, serviceName)
        buf.putShort(16)                         // TYPE: TXT
        buf.putShort(1)                          // CLASS: IN
        buf.putInt(120)                          // TTL

        // Build TXT RDATA: sequence of length-prefixed "key=value" strings
        val txtBuf = ByteBuffer.allocate(512)
        for ((key, value) in txtEntries) {
            val entry = "$key=$value"
            txtBuf.put(entry.length.toByte())
            txtBuf.put(entry.toByteArray(Charsets.UTF_8))
        }
        if (txtEntries.isEmpty()) {
            // Empty TXT record still needs at least one zero-length string
            txtBuf.put(0)
        }
        val txtLen = txtBuf.position()
        buf.putShort(txtLen.toShort())           // RDLENGTH
        txtBuf.flip()
        buf.put(txtBuf)

        val result = ByteArray(buf.position())
        buf.flip()
        buf.get(result)
        return result
    }

    /**
     * Encode a DNS name as length-prefixed labels.
     */
    private fun encodeDnsName(buf: ByteBuffer, name: String) {
        val labels = name.trimEnd('.').split('.')
        for (label in labels) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.UTF_8))
        }
        buf.put(0) // Root label
    }
}
