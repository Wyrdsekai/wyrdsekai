package org.wyrdsekai.app.network

import java.time.Duration

/**
 * ZoneConnect — cross-relay AUTO-ATTEMPT login for a zone bank entry
 * ( routing + P2). KMP (Android) parity with the RN
 * `zoneConnect.ts`.
 *
 * "Tap a server → it just works." Given a [ZoneBankEntry], the held relays that
 * reach it (preference order), and the password, try each relay until one logs
 * us in — then report WHICH relay won so the caller can bump it to the front.
 *
 * Per relay, three steps with distinct meanings:
 *   1. connect() throws → relay down/unreachable          → try next relay
 *   2. probe() == null  → zone not homed on THIS relay     → try next relay
 *   3. login() throws   → we REACHED the zone; account verdict is definitive
 *                         (wrong password / no account)    → STOP
 *
 * Step 3 is definitive because every relay carrying the zone reaches the SAME
 * backend — the login verdict is identical, so retrying other relays after a
 * real auth rejection is pointless (and hammers the account).
 */
sealed interface ZoneConnectResult {
    data class Ok(
        val client: NatsServerClient,
        /** The relay that succeeded — caller bumps it to the front. */
        val relayUrl: String,
        val auth: ServerClient.AuthOk,
    ) : ZoneConnectResult

    data class Error(
        val error: String,
        /** true → reached the zone but the account was rejected (re-prompt);
         *  false → no held relay could reach the zone (network / wrong relays). */
        val authRejected: Boolean,
    ) : ZoneConnectResult
}

object ZoneConnect {

    suspend fun connectToZone(
        zone: ZoneBankEntry,
        relays: List<HeldRelay>,
        password: String,
        requestTimeout: Duration = Duration.ofSeconds(5),
    ): ZoneConnectResult = connectToZoneWith(zone, relays, requestTimeout) { client ->
        client.login(zone.username, password)
    }

    /**
     * The generic engine behind [connectToZone]: same three-step relay-attempt
     * ladder, with step 3 (the account decision) injectable. Registration
     * (2026-07-23, parity with RN connectToZoneWith) reuses steps 1-2 verbatim
     * — a named auth.register/auth.redeem is just as definitive as a login
     * once the zone is reached.
     */
    suspend fun connectToZoneWith(
        zone: ZoneBankEntry,
        relays: List<HeldRelay>,
        requestTimeout: Duration = Duration.ofSeconds(5),
        authenticate: suspend (NatsServerClient) -> ServerClient.AuthOk,
    ): ZoneConnectResult {
        if (relays.isEmpty()) {
            return ZoneConnectResult.Error(
                error = "This device holds no relay that reaches this server. Add the relay (scan/paste an invite) first.",
                authRejected = false,
            )
        }

        for (relay in relays) {
            val client = NatsServerClient(
                relayUrl = relay.wsUrl,
                zoneId = zone.zoneId,
                natsUser = relay.natsUser,
                natsPassword = relay.natsPass,
                requestTimeout = requestTimeout,
            )

            // 1. Relay reachable?
            try {
                client.connect()
            } catch (_: Exception) {
                safeDisconnect(client)
                continue
            }

            // 2. Zone homed on THIS relay? (null = no responder → try another.)
            val reachable = try {
                client.probe() != null
            } catch (_: Exception) {
                safeDisconnect(client)
                continue
            }
            if (!reachable) {
                safeDisconnect(client)
                continue
            }

            // 3. The account decision (login OR register/redeem) — definitive
            // once we've reached the zone.
            try {
                val auth = authenticate(client)
                return ZoneConnectResult.Ok(client, relay.wsUrl, auth)
            } catch (e: Exception) {
                safeDisconnect(client)
                return ZoneConnectResult.Error(
                    error = "${zone.displayName}: ${e.message ?: e::class.simpleName}",
                    authRejected = true,
                )
            }
        }

        return ZoneConnectResult.Error(
            error = "Could not reach ${zone.displayName} on any of your ${relays.size} relay(s).",
            authRejected = false,
        )
    }

    private suspend fun safeDisconnect(client: NatsServerClient) {
        try { client.disconnect() } catch (_: Exception) { /* idempotent */ }
    }
}
