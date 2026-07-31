package org.wyrdsekai.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.engine.between.PresenceManager
import org.wyrdsekai.app.engine.between.PresenceState
import org.wyrdsekai.app.engine.discovery.ConnectivityState
import org.wyrdsekai.app.engine.discovery.HouseholdConnector
import org.wyrdsekai.app.engine.discovery.SavedHouseholdConfig

/**
 * View model for the household Between connection and presence.
 *
 * Delegates to [HouseholdConnector] for connectivity and exposes
 * household state for the UI. On platforms where household features
 * are not configured, the UI should check [isAvailable] and hide controls.
 */
class HouseholdViewModel(
    private val scope: CoroutineScope,
    private val connector: HouseholdConnector = HouseholdConnector(),
) {
    /** Current connectivity state (DISCOVERING, CONNECTED_LAN, etc.). */
    val connectivityState: StateFlow<ConnectivityState> = connector.state

    /** Household ID from the last successful connection. */
    private val _householdId = MutableStateFlow<String?>(null)
    val householdId: StateFlow<String?> = _householdId.asStateFlow()

    /** Connected nodes in the household (nodeId -> presence). */
    private val _connectedNodes = MutableStateFlow<List<PresenceState>>(emptyList())
    val connectedNodes: StateFlow<List<PresenceState>> = _connectedNodes.asStateFlow()

    /** Error message from the last connection attempt, if any. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Whether a connection attempt is in progress. */
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    /** Relay URL for manual configuration. */
    private val _relayUrl = MutableStateFlow(connector.getSavedConfig()?.relayUrl ?: "")
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    /** Household NATS URL for manual configuration. */
    private val _householdUrl = MutableStateFlow(connector.getSavedConfig()?.natsWsUrl ?: "")
    val householdUrl: StateFlow<String> = _householdUrl.asStateFlow()

    /** Whether auto-discovery (mDNS) is enabled. */
    private val _autoDiscover = MutableStateFlow(true)
    val autoDiscover: StateFlow<Boolean> = _autoDiscover.asStateFlow()

    /** Whether household features are available on this platform. */
    val isAvailable: Boolean = true

    private var betweenClient: BetweenClient? = null
    private var presenceManager: PresenceManager? = null

    /**
     * Attempt to connect to the household via the connectivity cascade.
     */
    fun connect() {
        scope.launch {
            _isConnecting.value = true
            _error.value = null
            try {
                val client = connector.connect()
                betweenClient = client

                // Update household ID from discovered or saved config
                val hhId = connector.lastDiscovered?.householdId
                    ?: connector.getSavedConfig()?.householdId
                _householdId.value = hhId
            } catch (e: Exception) {
                _error.value = e.message ?: "Connection failed"
            } finally {
                _isConnecting.value = false
            }
        }
    }

    /**
     * Disconnect from the household Between network.
     */
    fun disconnect() {
        scope.launch {
            presenceManager?.announce("offline")
            presenceManager?.stopListening()
            presenceManager = null

            try {
                betweenClient?.disconnect()
            } catch (_: Exception) {
                // Disconnect failure is non-fatal
            }
            betweenClient = null
            _householdId.value = null
            _connectedNodes.value = emptyList()
        }
    }

    /**
     * Refresh the list of connected nodes from the presence manager.
     */
    fun refreshNodes() {
        val pm = presenceManager ?: return
        _connectedNodes.value = pm.getHouseholdPresence().values.toList()
    }

    /**
     * Update the relay URL for manual configuration.
     */
    fun setRelayUrl(url: String) {
        _relayUrl.value = url
        val saved = connector.getSavedConfig()
        if (saved != null) {
            connector.updateSavedConfig(saved.copy(relayUrl = url))
        }
    }

    /**
     * Update the saved household NATS URL for manual configuration.
     */
    fun setSavedHouseholdUrl(url: String) {
        _householdUrl.value = url
        val existing = connector.getSavedConfig()
        if (existing != null) {
            connector.updateSavedConfig(existing.copy(natsWsUrl = url))
        } else {
            connector.updateSavedConfig(
                SavedHouseholdConfig(
                    householdId = "",
                    householdName = "",
                    natsWsUrl = url,
                    relayUrl = _relayUrl.value.ifBlank { null },
                )
            )
        }
    }

    /**
     * Toggle auto-discovery (mDNS).
     */
    fun setAutoDiscover(enabled: Boolean) {
        _autoDiscover.value = enabled
    }
}
