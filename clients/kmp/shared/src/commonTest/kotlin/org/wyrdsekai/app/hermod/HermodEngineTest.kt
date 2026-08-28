package org.wyrdsekai.app.hermod

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.LocalInferenceProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Admission is the phone's own: everything the engine won't run comes
 * back as an honest decline (the router just tries the next device),
 * and an executor blow-up is a completed-but-failed result — the two
 * must never blur, or the mesh retries work that actually ran.
 */
class HermodEngineTest {

    private class FakeLocalProvider(
        initialState: String = "running",
        private val reply: String = "the drafted line",
        private val throwOnComplete: Boolean = false,
    ) : LocalInferenceProvider {
        val stateFlow = MutableStateFlow(initialState)
        override val state: StateFlow<String> get() = stateFlow
        var lastMessages: List<ChatMessage>? = null
        var lastOptions: CompletionOptions? = null
        override suspend fun completeLocal(
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            lastMessages = messages
            lastOptions = options
            if (throwOnComplete) error("model fell over")
            return ChatResponse(reply, 10, 20)
        }
    }

    private val now = kotlin.time.Instant.parse("2026-08-14T12:00:00Z").toEpochMilliseconds()

    private fun knock(
        taskType: String = "inference.chat",
        dataDomain: String = "none",
        params: Map<String, String> = mapOf("prompt" to "hi"),
        tokenBudget: Long = 256,
        expiresAt: String = "2026-08-14T12:01:00Z",
    ) = HermodMessage.Knock("k1", EnvelopeDto(
        envelopeId = "env-1", householdId = "hh1", originDeviceId = "origin",
        taskType = taskType, dataDomain = dataDomain, capabilityClass = "llm.phone",
        params = params, tokenBudget = tokenBudget,
        issuedAt = "2026-08-14T12:00:00Z", expiresAt = expiresAt))

    @Test
    fun aChatErrandRunsOnTheLocalModel() = runTest {
        val local = FakeLocalProvider()
        val engine = HermodEngine(local) { now }
        val answer = engine.answer(knock(params = mapOf(
            "prompt" to "hi", "system" to "be brief")), eligible = true)
        assertEquals("k1", answer.knockId)
        assertTrue(answer.answer.completed)
        assertTrue(answer.answer.ok)
        assertEquals("the drafted line", answer.answer.output)
        assertEquals(listOf("system", "user"), local.lastMessages?.map { it.role })
        assertEquals(256, local.lastOptions?.maxTokens)
    }

    @Test
    fun ineligibilityDeclinesBeforeAnythingRuns() = runTest {
        val local = FakeLocalProvider()
        val answer = HermodEngine(local) { now }.answer(knock(), eligible = false)
        assertFalse(answer.answer.completed)
        assertNull(local.lastMessages, "nothing may run without eligibility")
    }

    @Test
    fun unsupportedTaskTypesDecline() = runTest {
        val answer = HermodEngine(FakeLocalProvider()) { now }
            .answer(knock(taskType = "inference.chat.full"), eligible = true)
        assertFalse(answer.answer.completed)
        assertTrue(answer.answer.declineReason!!.contains("inference.chat.full"))
    }

    @Test
    fun domainScopedTasksDeclineUntilGrantsShipOnDevice() = runTest {
        val answer = HermodEngine(FakeLocalProvider()) { now }
            .answer(knock(dataDomain = "photos"), eligible = true)
        assertFalse(answer.answer.completed)
        assertTrue(answer.answer.declineReason!!.contains("grant"))
    }

    @Test
    fun expiredEnvelopesDecline() = runTest {
        val answer = HermodEngine(FakeLocalProvider()) { now }
            .answer(knock(expiresAt = "2026-08-14T11:59:00Z"), eligible = true)
        assertFalse(answer.answer.completed)
        assertEquals("envelope expired", answer.answer.declineReason)
    }

    @Test
    fun anUnloadedModelDeclinesHonestly() = runTest {
        val answer = HermodEngine(FakeLocalProvider(initialState = "stopped")) { now }
            .answer(knock(), eligible = true)
        assertFalse(answer.answer.completed)
        assertEquals("no local model loaded", answer.answer.declineReason)
    }

    @Test
    fun anExecutorFailureIsCompletedNotDeclined() = runTest {
        val answer = HermodEngine(FakeLocalProvider(throwOnComplete = true)) { now }
            .answer(knock(), eligible = true)
        // It RAN and failed — the mesh must not silently re-place it.
        assertTrue(answer.answer.completed)
        assertFalse(answer.answer.ok)
        assertTrue(answer.answer.error!!.contains("model fell over"))
    }

    @Test
    fun tokenBudgetIsClampedToSomethingAPhoneCanServe() = runTest {
        val local = FakeLocalProvider()
        HermodEngine(local) { now }.answer(knock(tokenBudget = 500_000), eligible = true)
        assertEquals(2048, local.lastOptions?.maxTokens)
    }
}
