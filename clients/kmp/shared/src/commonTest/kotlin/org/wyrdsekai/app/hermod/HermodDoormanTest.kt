package org.wyrdsekai.app.hermod

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.LocalInferenceProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The doorman keeps ONE door open and moves it as the phone moves:
 * no identity → no mesh; LAN preferred when home answers; tunnel
 * otherwise; and while away, home is re-probed so returning closes the
 * tunnel leg and re-elects LAN. The zone sees only channel supersedes.
 */
class HermodDoormanTest {

    private class FakeProvider : LocalInferenceProvider {
        override val state: StateFlow<String> = MutableStateFlow("running")
        override suspend fun completeLocal(
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ) = ChatResponse("ok", 1, 1)
    }

    private fun doorman(
        scope: CoroutineScope,
        doors: () -> HermodDoorman.Doors,
        lanProbe: suspend (String) -> Boolean = { false },
        reprobeMillis: Long = 100,
    ) = HermodDoorman(
        scope = scope,
        local = FakeProvider(),
        models = { listOf("m") },
        policy = { HermodListener.HermodPolicy(consented = true, charging = true, idle = true) },
        doors = doors,
        heartbeatMillis = 50,
        reprobeMillis = reprobeMillis,
        lanProbe = lanProbe,
    )

    private suspend fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!cond()) delay(20)
        }
    }

    @Test
    fun noIdentityMeansNoMesh() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val dm = doorman(scope, doors = {
            HermodDoorman.Doors(deviceToken = null, serverUrl = "http://zone:7070",
                tunnel = HermodDoorman.TunnelDoor(between, "zone1"))
        }, lanProbe = { true })
        dm.start()
        try {
            awaitTrue { dm.state.value == "waiting" }
            // Nothing may touch the wire without a device identity.
            assertTrue(between.published.isEmpty())
        } finally {
            dm.stop(); scope.cancel()
        }
    }

    @Test
    fun withoutLanTheTunnelCarriesTheDoor() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val dm = doorman(scope, doors = {
            HermodDoorman.Doors(deviceToken = "wyrd_dev_abc", serverUrl = null,
                tunnel = HermodDoorman.TunnelDoor(between, "zone1"))
        })
        dm.start()
        try {
            awaitTrue { dm.state.value == "tunnel" }
            awaitTrue {
                runCatching {
                    between.published.any { (s, d) ->
                        s.endsWith(".open") && d.decodeToString().contains("\"door\":\"hermod\"")
                    }
                }.getOrDefault(false)
            }
            // The session protocol runs over the pipe: heartbeats ride .up.
            awaitTrue {
                runCatching {
                    between.published.any { (s, d) ->
                        s.endsWith(".up") && d.decodeToString().contains("\"type\":\"heartbeat\"")
                    }
                }.getOrDefault(false)
            }
        } finally {
            dm.stop(); scope.cancel()
        }
    }

    @Test
    fun comingHomeClosesTheTunnelAndReElectsLan() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val home = MutableStateFlow(false)
        val dm = doorman(scope, doors = {
            HermodDoorman.Doors(deviceToken = "wyrd_dev_abc", serverUrl = "http://127.0.0.1:1",
                tunnel = HermodDoorman.TunnelDoor(between, "zone1"))
        }, lanProbe = { home.value }, reprobeMillis = 100)
        dm.start()
        try {
            // Away: the tunnel carries the door.
            awaitTrue { dm.state.value == "tunnel" }
            home.value = true // the phone walks in the front door
            // The roam-watch notices, closes the tunnel leg...
            awaitTrue {
                runCatching {
                    between.published.any { (s, _) -> s.endsWith(".close") }
                }.getOrDefault(false)
            }
            // ...and the loop re-elects LAN (the connect itself then fails —
            // dead test URL — but the ELECTION is what roaming is).
            awaitTrue { dm.state.value == "lan" || dm.state.value == "backoff" }
        } finally {
            dm.stop(); scope.cancel()
        }
    }
}
