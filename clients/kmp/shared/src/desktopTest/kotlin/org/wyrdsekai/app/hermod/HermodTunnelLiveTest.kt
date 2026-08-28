package org.wyrdsekai.app.hermod

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.wyrdsekai.app.engine.between.NatsBetweenClient
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.LocalInferenceProvider
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live e2e for the RELAY leg of the phone's hermod door: this JVM plays
 * an away-from-home phone — its ONLY connection is the relay's NATS
 * websocket (never the zone's HTTP, never the zone's local NATS). The
 * Doorman elects the tunnel, TunnelSessionHandler loopbacks it into
 * /ws/hermod, and a HermodProbe errand fired on the ZONE's own NATS
 * must arrive here as a knock and ride back answered — across two
 * NATS fabrics and the loopback.
 *
 * Requires: zone with WYRDSEKAI_RELAY_URL pointing at a bench relay
 * whose websocket is WYRDSEKAI_TEST_RELAY_WS, plus WYRDSEKAI_TEST_TOKEN
 * (a paired wyrd_dev_ token) and WYRDSEKAI_TEST_ZONE (scope id).
 * Skips quietly when unset.
 *
 * Run: ./gradlew :shared:desktopTest --tests '*HermodTunnelLiveTest*'
 */
class HermodTunnelLiveTest {

    private val relayWs = System.getenv("WYRDSEKAI_TEST_RELAY_WS")
    private val deviceToken = System.getenv("WYRDSEKAI_TEST_TOKEN")
    private val zoneId = System.getenv("WYRDSEKAI_TEST_ZONE") ?: "ferngrove"

    private class AnsweringProvider : LocalInferenceProvider {
        override val state: StateFlow<String> = MutableStateFlow("running")
        val prompts = mutableListOf<String>()
        override suspend fun completeLocal(
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            prompts.add(messages.last().content)
            return ChatResponse("over the relay: " + messages.last().content, 5, 7)
        }
    }

    @Test
    fun anErrandReachesTheAwayPhoneThroughTheTunnel() = runBlocking {
        if (relayWs.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            println("SKIP: set WYRDSEKAI_TEST_RELAY_WS + WYRDSEKAI_TEST_TOKEN")
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val between = NatsBetweenClient(scope)
        between.connectWithRetry(relayWs)
        val provider = AnsweringProvider()
        val doorman = HermodDoorman(
            scope = scope,
            local = provider,
            models = { listOf("fake-away-phone-model") },
            policy = { HermodListener.HermodPolicy(consented = true, charging = true, idle = true) },
            doors = {
                HermodDoorman.Doors(
                    deviceToken = deviceToken,
                    serverUrl = null, // AWAY: no LAN door exists
                    tunnel = HermodDoorman.TunnelDoor(between, zoneId),
                )
            },
            heartbeatMillis = 5_000,
        )
        doorman.start()
        try {
            var waited = 0
            while (doorman.state.value != "tunnel" && waited < 20_000) {
                delay(500); waited += 500
            }
            assertTrue(doorman.state.value == "tunnel",
                "doorman never elected the tunnel: state=${doorman.state.value}")
            println("LIVE: away-phone on the tunnel — waiting for a knock "
                + "(fire HermodProbe on the zone's NATS now)")

            waited = 0
            while (provider.prompts.isEmpty() && waited < 120_000) {
                delay(1_000); waited += 1_000
            }
            assertTrue(provider.prompts.isNotEmpty(),
                "no knock arrived through the tunnel within 120s")
            println("LIVE: tunnel knock received, prompt='${provider.prompts.first()}' — answered")
        } finally {
            doorman.stop()
            runCatching { between.disconnect() }
            scope.cancel()
        }
    }
}
