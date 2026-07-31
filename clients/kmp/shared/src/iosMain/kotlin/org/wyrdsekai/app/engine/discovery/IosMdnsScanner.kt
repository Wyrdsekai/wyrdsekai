@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package org.wyrdsekai.app.engine.discovery

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS mDNS scanner using Bonjour via NSNetServiceBrowser.
 *
 * Discovers _wyrdsekai._tcp services on the local network using Apple's
 * built-in Bonjour (zero-configuration networking). NSNetServiceBrowser
 * requires a run loop; we schedule it on the current run loop and use
 * suspendCancellableCoroutine to bridge to the calling coroutine.
 *
 * TXT record parsing: NSNetService provides TXT record data as NSData.
 * We use NSNetService.dictionaryFromTXTRecordData to parse it into
 * key-value pairs.
 *
 */
class IosMdnsScanner : MdnsScanner {

    private var browser: NSNetServiceBrowser? = null

    override suspend fun scan(
        serviceType: String,
        timeoutMs: Long,
    ): DiscoveredHousehold? {
        return withTimeoutOrNull(timeoutMs) {
            discoverAndResolve(serviceType)
        }
    }

    /**
     * Run Bonjour discovery and resolve the first found service.
     *
     * NSNetServiceBrowser callbacks arrive on the run loop thread.
     * We bridge to coroutines via suspendCancellableCoroutine.
     */
    private suspend fun discoverAndResolve(
        serviceType: String,
    ): DiscoveredHousehold? = suspendCancellableCoroutine { cont ->
        // NSNetServiceBrowser expects type and domain separately.
        // Our SERVICE_TYPE is "_wyrdsekai._tcp.local" — split into type and domain.
        val type = serviceType.removeSuffix(".local").removeSuffix(".") + "."
        val domain = "local."

        val serviceBrowser = NSNetServiceBrowser()
        browser = serviceBrowser

        val browserDelegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {

            override fun netServiceBrowser(
                browser: NSNetServiceBrowser,
                didFindService: NSNetService,
                moreComing: Boolean,
            ) {
                // Found a service — resolve it to get TXT records
                val service = didFindService
                val resolveDelegate = object : NSObject(), NSNetServiceDelegateProtocol {

                    override fun netServiceDidResolveAddress(sender: NSNetService) {
                        val household = extractHousehold(sender)
                        serviceBrowser.stop()
                        if (cont.isActive) {
                            cont.resume(household)
                        }
                    }

                    override fun netService(
                        sender: NSNetService,
                        didNotResolve: Map<Any?, *>,
                    ) {
                        // Resolution failed — keep scanning, timeout will handle it
                    }
                }

                service.delegate = resolveDelegate
                // Resolve with a 5-second timeout per service
                service.resolveWithTimeout(5.0)
            }

            override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
                if (cont.isActive) {
                    cont.resume(null)
                }
            }

            override fun netServiceBrowser(
                browser: NSNetServiceBrowser,
                didNotSearch: Map<Any?, *>,
            ) {
                if (cont.isActive) {
                    cont.resume(null)
                }
            }

            // Note: didRemoveService not overridden — it has the same Kotlin signature
            // as didFindService (NSNetServiceBrowser, NSNetService, Boolean) causing
            // a conflicting overloads error. The method is optional and unused anyway.
        }

        serviceBrowser.delegate = browserDelegate

        cont.invokeOnCancellation {
            serviceBrowser.stop()
        }

        // Schedule on run loop and start search
        serviceBrowser.scheduleInRunLoop(NSRunLoop.currentRunLoop(), forMode = NSRunLoopCommonModes)
        serviceBrowser.searchForServicesOfType(type, inDomain = domain)
    }

    /**
     * Extract household info from a resolved NSNetService.
     *
     * Reads TXT record data via NSNetService.dictionaryFromTXTRecordData().
     * The dictionary maps String keys to NSData values (UTF-8 encoded).
     */
    private fun extractHousehold(service: NSNetService): DiscoveredHousehold {
        val txtData = service.TXTRecordData()
        val attrs = if (txtData != null) {
            @Suppress("UNCHECKED_CAST")
            NSNetService.dictionaryFromTXTRecordData(txtData) as? Map<String, NSData>
                ?: emptyMap()
        } else {
            emptyMap()
        }

        fun attr(key: String): String? {
            val data = attrs[key] ?: return null
            return NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
        }

        val hostName = service.hostName ?: "127.0.0.1"
        val port = service.port.toInt()

        val natsWs = attr("nats_ws") ?: "ws://$hostName:$port"

        return DiscoveredHousehold(
            householdId = attr("household_id") ?: service.name,
            householdName = attr("household_name") ?: service.name,
            natsWsUrl = natsWs,
            relayUrl = attr("relay_url"),
            relayToken = attr("relay_token"),
            version = attr("version") ?: "1.0",
        )
    }

    /**
     * Stop any active discovery. Safe to call multiple times.
     */
    fun stopDiscovery() {
        browser?.stop()
        browser = null
    }
}
