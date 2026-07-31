package org.wyrdsekai.app.engine.discovery

/**
 * Default no-op mDNS scanner for platforms without mDNS support.
 *
 * Always returns null (no household discovered). This keeps the code
 * compiling on all KMP targets. The HouseholdConnector gracefully
 * falls through to saved config or relay when mDNS returns null.
 *
 * Override with platform-specific implementation:
 * - Android: NsdManager (API 16+, built-in)
 * - iOS: NSNetServiceBrowser (Bonjour, built-in)
 * - Desktop: JmDNS (pure Java library)
 *
 */
class DefaultMdnsScanner : MdnsScanner {
    override suspend fun scan(serviceType: String, timeoutMs: Long): DiscoveredHousehold? {
        // No mDNS available on this platform.
        // HouseholdConnector will fall through to saved config or relay.
        return null
    }
}
