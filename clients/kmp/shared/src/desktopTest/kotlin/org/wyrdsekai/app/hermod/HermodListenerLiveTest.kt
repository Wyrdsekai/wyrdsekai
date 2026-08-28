package org.wyrdsekai.app.hermod

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.LocalInferenceProvider
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live e2e for the phone side of hermod against a RUNNING zone:
 * this JVM plays the phone — real WebSocket to /ws/hermod, real device
 * token, heartbeats gossiped by the zone on our behalf — and a
 * HermodProbe (server module) fires a real errand through NATS that
 * must arrive here as a knock and ride back as an answer.
 *
 * Requires: zone on WYRDSEKAI_TEST_SERVER (default http://localhost:7070)
 * with hermod live, and WYRDSEKAI_TEST_TOKEN = a paired wyrd_dev_ token.
 * Skips quietly when unset (same convention as WebSocketServerConnectionLiveTest).
 *
 * Run: ./gradlew :shared:desktopTest --tests '*HermodListenerLiveTest*'
 */
class HermodListenerLiveTest {

    private val serverUrl = System.getenv("WYRDSEKAI_TEST_SERVER") ?: "http://localhost:7070"
    private val deviceToken = System.getProperty("wyrdsekai.test.token")
        ?: System.getenv("WYRDSEKAI_TEST_TOKEN")

    private class AnsweringProvider : LocalInferenceProvider {
        override val state: StateFlow<String> = MutableStateFlow("running")
        val prompts = mutableListOf<String>()
        override suspend fun completeLocal(
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            prompts.add(messages.last().content)
            return ChatResponse("the phone answers: " + messages.last().content, 5, 7)
        }
    }

    @Test
    fun aRealErrandKnocksAndIsAnswered() = runBlocking {
        if (deviceToken.isNullOrBlank()) {
            println("SKIP: No device token. Set WYRDSEKAI_TEST_TOKEN env")
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
            .trimEnd('/') + "/ws/hermod?device_token=$deviceToken"
        val provider = AnsweringProvider()
        val listener = HermodListener(
            wsUrl = wsUrl,
            scope = scope,
            local = provider,
            models = { listOf("fake-desktop-model") },
            policy = { HermodListener.HermodPolicy(consented = true, charging = true, idle = true) },
            capabilityClass = "llm.phone",
            heartbeatMillis = 5_000,
        )
        listener.start()
        try {
            // Reach the zone's door.
            var waited = 0
            while (listener.state.value != "listening" && waited < 20_000) {
                delay(500); waited += 500
            }
            assertTrue(listener.state.value == "listening",
                "listener never reached the zone: state=${listener.state.value}")
            println("LIVE: listening as llm.phone — waiting for a knock "
                + "(run HermodProbe against this scope now)")

            // The knock arrives from a REAL probe errand riding NATS→proxy→WS.
            waited = 0
            while (provider.prompts.isEmpty() && waited < 120_000) {
                delay(1_000); waited += 1_000
            }
            assertTrue(provider.prompts.isNotEmpty(),
                "no knock arrived within 120s — did HermodProbe run?")
            println("LIVE: knock received, prompt='${provider.prompts.first()}' — answered")
        } finally {
            listener.stop()
            scope.cancel()
        }
    }
}
