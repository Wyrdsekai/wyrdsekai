package org.wyrdsekai.app.engine.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android mDNS scanner using NsdManager (API 16+).
 *
 * Discovers _wyrdsekai._tcp services on the local network and extracts
 * TXT records for household connection info (natsUrl, relayUrl, householdId).
 *
 * Thread safety: NsdManager callbacks arrive on a binder thread. We use
 * suspendCancellableCoroutine to bridge to the calling coroutine. Discovery
 * is stopped on timeout, cancellation, or after the first service is resolved.
 *
 */
class AndroidMdnsScanner(private val context: Context) : MdnsScanner {

    private var nsdManager: NsdManager? = null
    private var activeListener: NsdManager.DiscoveryListener? = null

    override suspend fun scan(
        serviceType: String,
        timeoutMs: Long,
    ): DiscoveredHousehold? {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return null
        nsdManager = manager

        return withTimeoutOrNull(timeoutMs) {
            discoverAndResolve(manager, serviceType)
        }
    }

    /**
     * Run mDNS discovery, resolve the first found service, and extract TXT records.
     *
     * Bridges NsdManager's callback-based API to a suspend function via
     * suspendCancellableCoroutine. On cancellation, stops the active discovery.
     */
    private suspend fun discoverAndResolve(
        manager: NsdManager,
        serviceType: String,
    ): DiscoveredHousehold? = suspendCancellableCoroutine { cont ->
        // NsdManager expects the service type WITHOUT ".local" suffix — it adds the domain itself.
        // Our MdnsScanner.SERVICE_TYPE is "_wyrdsekai._tcp.local" so we strip ".local".
        val nsdServiceType = serviceType.removeSuffix(".local").removeSuffix(".")

        // Use a holder so the inner ResolveListener can reference the outer DiscoveryListener
        // for stopping discovery after resolution completes.
        var discoveryListener: NsdManager.DiscoveryListener? = null

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                // Discovery started successfully
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Found a service — resolve it to get host/port/TXT
                try {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            // Resolution failed for this service; keep scanning for others.
                            // The timeout will eventually fire if nothing else is found.
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val household = extractHousehold(info)
                            discoveryListener?.let { safeStopDiscovery(manager, it) }
                            if (cont.isActive) {
                                cont.resume(household)
                            }
                        }
                    })
                } catch (e: Exception) {
                    // IllegalArgumentException if resolve is called while another resolve is
                    // in progress — safe to ignore, the first resolve will complete.
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Service went away — no action needed
            }

            override fun onDiscoveryStopped(regType: String) {
                // Discovery stopped — if we haven't resumed yet, return null
                if (cont.isActive) {
                    cont.resume(null)
                }
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                if (cont.isActive) {
                    cont.resume(null)
                }
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                // Best effort — nothing to do
            }
        }

        discoveryListener = listener
        activeListener = listener

        cont.invokeOnCancellation {
            safeStopDiscovery(manager, listener)
        }

        try {
            manager.discoverServices(nsdServiceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            // SecurityException if NSD permission is missing, or IllegalArgumentException
            if (cont.isActive) {
                cont.resume(null)
            }
        }
    }

    /**
     * Extract household connection info from resolved NsdServiceInfo TXT records.
     *
     * Android API 21+ exposes TXT records via NsdServiceInfo.getAttributes().
     * Expected keys: nats_ws, relay_url, relay_token, household_id, household_name, version.
     */
    private fun extractHousehold(info: NsdServiceInfo): DiscoveredHousehold {
        val attrs = info.attributes

        fun attr(key: String): String? {
            val bytes = attrs[key] ?: return null
            return String(bytes, Charsets.UTF_8)
        }

        val host = info.host?.hostAddress ?: "127.0.0.1"
        val port = info.port

        // nats_ws TXT record contains the full WebSocket URL, or we construct from host/port
        val natsWs = attr("nats_ws") ?: "ws://$host:$port"

        return DiscoveredHousehold(
            householdId = attr("household_id") ?: info.serviceName ?: "unknown",
            householdName = attr("household_name") ?: info.serviceName ?: "Unknown Household",
            natsWsUrl = natsWs,
            relayUrl = attr("relay_url"),
            relayToken = attr("relay_token"),
            version = attr("version") ?: "1.0",
        )
    }

    /**
     * Stop mDNS discovery, ignoring errors if already stopped.
     */
    private fun safeStopDiscovery(manager: NsdManager, listener: NsdManager.DiscoveryListener) {
        try {
            manager.stopServiceDiscovery(listener)
        } catch (_: IllegalArgumentException) {
            // Already stopped or never started — safe to ignore
        }
    }

    /**
     * Stop any active discovery. Safe to call multiple times.
     */
    fun stopDiscovery() {
        val manager = nsdManager ?: return
        val listener = activeListener ?: return
        activeListener = null
        safeStopDiscovery(manager, listener)
    }
}
