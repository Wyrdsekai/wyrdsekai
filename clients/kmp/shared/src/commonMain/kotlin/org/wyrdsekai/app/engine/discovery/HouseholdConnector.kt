package org.wyrdsekai.app.engine.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.engine.between.NatsBetweenClient

/**
 * Connectivity cascade for discovering and connecting to the household.
 *
 * Implements the 4-level cascade from §19.2:
 * 1. mDNS discovery (LAN)
 * 2. Saved config (from last successful mDNS)
 * 3. Cloud relay (from mDNS TXT or saved config)
 * 4. Offline (companion works locally)
 *
 * On connection loss, retries with exponential backoff and falls
 * through cascade levels after max attempts per level.
 *
 */
class HouseholdConnector(
    private val mdnsScanner: MdnsScanner = DefaultMdnsScanner(),
    private val betweenClientFactory: BetweenClientFactory = DefaultBetweenClientFactory(),
    private var savedConfig: SavedHouseholdConfig? = null,
) {
    private val _state = MutableStateFlow(ConnectivityState.DISCOVERING)

    /** Current connectivity state. */
    val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    /** The last successfully discovered household, if any. */
    var lastDiscovered: DiscoveredHousehold? = null
        private set

    /**
     * Execute the connectivity cascade and return a connected BetweenClient.
     *
     * Tries each cascade level in order. On success, saves the configuration
     * for future fallback. On total failure, sets state to OFFLINE and throws.
     *
     * @throws HouseholdUnreachableException if all cascade levels fail
     */
    suspend fun connect(): BetweenClient {
        _state.value = ConnectivityState.DISCOVERING

        // Level 1: mDNS discovery
        val discovered = mdnsScanner.scan()
        if (discovered != null) {
            lastDiscovered = discovered
            try {
                val client = betweenClientFactory.create()
                client.connect(discovered.natsWsUrl)
                savedConfig = SavedHouseholdConfig.fromDiscovered(
                    discovered,
                    currentTimeMillis(),
                )
                _state.value = ConnectivityState.CONNECTED_LAN
                return client
            } catch (_: Exception) {
                // Fall through to next level
            }
        }

        // Level 2: Saved config (LAN URL)
        val saved = savedConfig
        if (saved != null) {
            try {
                val client = betweenClientFactory.create()
                client.connect(saved.natsWsUrl)
                _state.value = ConnectivityState.CONNECTED_LAN
                return client
            } catch (_: Exception) {
                // Fall through to next level
            }
        }

        // Level 3: Cloud relay
        val relayUrl = discovered?.relayUrl ?: saved?.relayUrl
        val relayToken = discovered?.relayToken ?: saved?.relayToken
        if (relayUrl != null) {
            try {
                val client = betweenClientFactory.create()
                // a wyrdphone:// invite saved NATS
                // credentials; they ride the CONNECT message. The legacy
                // ?token= query form remains for pre-invite configs.
                val urlWithAuth = if (saved?.natsUser != null) {
                    client.setCredentials(saved.natsUser, saved.natsPassword)
                    relayUrl
                } else if (relayToken != null) {
                    "$relayUrl?token=$relayToken"
                } else {
                    relayUrl
                }
                client.connect(urlWithAuth)
                _state.value = ConnectivityState.CONNECTED_RELAY
                return client
            } catch (_: Exception) {
                // Fall through to offline
            }
        }

        // Level 4: Offline
        _state.value = ConnectivityState.OFFLINE
        throw HouseholdUnreachableException("All cascade levels failed")
    }

    /**
     * Reconnect with exponential backoff, falling through cascade levels.
     *
     * @param maxAttemptsPerLevel Maximum retry attempts at each cascade level
     * @return Connected BetweenClient
     * @throws HouseholdUnreachableException if all levels and attempts exhausted
     */
    suspend fun reconnect(maxAttemptsPerLevel: Int = MAX_ATTEMPTS_PER_LEVEL): BetweenClient {
        _state.value = ConnectivityState.RECONNECTING

        for (attempt in 0 until maxAttemptsPerLevel) {
            try {
                return connect()
            } catch (_: HouseholdUnreachableException) {
                if (attempt < maxAttemptsPerLevel - 1) {
                    delay(backoffDelayMs(attempt))
                }
            }
        }

        _state.value = ConnectivityState.OFFLINE
        throw HouseholdUnreachableException("Reconnection failed after $maxAttemptsPerLevel attempts")
    }

    /**
     * Update the saved configuration (e.g., after manual entry or QR scan).
     */
    fun updateSavedConfig(config: SavedHouseholdConfig) {
        savedConfig = config
    }

    /**
     * Get the current saved configuration.
     */
    fun getSavedConfig(): SavedHouseholdConfig? = savedConfig

    companion object {
        const val MAX_ATTEMPTS_PER_LEVEL = 5
        private const val MAX_BACKOFF_MS = 16_000L

        /**
         * Exponential backoff: 1s, 2s, 4s, 8s, 16s (capped).
         */
        internal fun backoffDelayMs(attempt: Int): Long {
            val base = 1000L
            val delay = base shl attempt
            return minOf(delay, MAX_BACKOFF_MS)
        }

        internal fun currentTimeMillis(): Long {
            return kotlin.time.Clock.System.now().toEpochMilliseconds()
        }
    }
}

/**
 * Connectivity states for the household connection.
 *
 */
enum class ConnectivityState {
    /** Scanning mDNS for household server. */
    DISCOVERING,
    /** Connected via household LAN (lowest latency). */
    CONNECTED_LAN,
    /** Connected via cloud relay (higher latency, works anywhere). */
    CONNECTED_RELAY,
    /** Lost connection, attempting reconnect with backoff. */
    RECONNECTING,
    /** No connectivity. Companion works locally, messages queue for sync. */
    OFFLINE,
}

/**
 * Factory for creating BetweenClient instances.
 * Abstracted for testability (inject mock factory in tests).
 */
interface BetweenClientFactory {
    fun create(): BetweenClient
}

/**
 * Default factory that creates NatsBetweenClient instances.
 *
 * @param scope The CoroutineScope for the NatsBetweenClient's receive loop and
 *              reconnection jobs. If not provided, creates a default supervisor scope.
 * @param autoReconnect Whether the created clients should automatically reconnect
 *                      on connection loss (default true).
 */
class DefaultBetweenClientFactory(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val autoReconnect: Boolean = true,
) : BetweenClientFactory {
    override fun create(): BetweenClient {
        val client = NatsBetweenClient(scope)
        client.autoReconnect = autoReconnect
        return client
    }
}

/**
 * Thrown when all cascade levels fail and the household is unreachable.
 */
class HouseholdUnreachableException(message: String) : Exception(message)
