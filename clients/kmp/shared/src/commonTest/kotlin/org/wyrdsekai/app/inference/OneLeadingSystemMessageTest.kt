package org.wyrdsekai.app.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.wyrdsekai.app.engine.agent.AgentProfile
import org.wyrdsekai.app.engine.agent.FullPromptAssembler
import org.wyrdsekai.app.engine.agent.VitalityState
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.protocol.Entity
import org.wyrdsekai.app.protocol.RoomSnapshot

/**
 * A Qwen chat template on a raw llama-server rejects a system message anywhere
 * but index 0, and FullPromptAssembler builds one per layer. What leaves the
 * phone's send points has ONE system message, at index 0, with the layers joined
 * in order, and the Now line still lands where NowLine puts it, once. Port of
 * core's PromptAssembler.mergeConsecutiveSystemMessages.
 */
class OneLeadingSystemMessageTest {

    private val at = Instant.parse("2026-09-23T14:05:00Z")

    private fun msg(role: String, content: String) = ChatMessage(role, content)

    /** What the template accepts: a system message only at index 0. */
    private fun assertOneLeadingSystem(messages: List<ChatMessage>) {
        assertEquals(listOf(0), messages.indices.filter { messages[it].role == "system" },
            "one system message, at index 0: ${messages.map { it.role }}")
    }

    // ── The merge ─────────────────────────────────────────────────────────

    @Test
    fun the_layers_become_one_system_message_in_layer_order() {
        val out = consolidateSystemMessages(listOf(
            msg("system", "You are Mia."), msg("system", "Current location: Hearth"),
            msg("system", "[Current state: Hearth]"), msg("user", "operator says: hi"),
            msg("assistant", "hello"), msg("user", "operator says: the tide?"),
        ))
        assertEquals(listOf(
            msg("system", "You are Mia.\n\nCurrent location: Hearth\n\n[Current state: Hearth]"),
            msg("user", "operator says: hi"), msg("assistant", "hello"), msg("user", "operator says: the tide?"),
        ), out)
    }

    @Test
    fun a_system_message_after_the_conversation_is_folded_never_left_mid_list() {
        val out = consolidateSystemMessages(listOf(
            msg("system", "You are Mia."), msg("user", "operator says: hi"),
            msg("system", "Answer in one line."), msg("system", "No lists."),
            msg("assistant", "hello"), msg("system", "Stay warm."),
        ))
        assertOneLeadingSystem(out)
        assertEquals(listOf(
            msg("system", "You are Mia."),
            msg("user", "operator says: hi\n\n[system note: Answer in one line.\n\nNo lists.]"),
            msg("assistant", "hello\n\n[system note: Stay warm.]"),
        ), out)
    }

    @Test
    fun a_list_with_nothing_to_merge_is_returned_as_it_is() {
        val one = listOf(msg("system", "You are Mia."), msg("user", "hi"))
        assertSame(one, consolidateSystemMessages(one))
        val none = listOf(msg("user", "hi"))
        assertSame(none, consolidateSystemMessages(none))

        val input = listOf(msg("system", "a"), msg("system", "b"), msg("user", "hi"))
        consolidateSystemMessages(input)
        assertEquals(msg("system", "b"), input[1], "the caller's list is never changed")
    }

    // ── What actually leaves ──────────────────────────────────────────────

    private val profile = AgentProfile(
        name = "Mia",
        entityId = "companion-mia",
        entityType = "agent",
        description = "A test companion",
        systemPrompt = "You are Mia.",
        contextWindowTokens = 8192,
        maxResponseTokens = 128,
        temperature = 0.7,
    )

    /** Her full prompt, as the COMPLEX raw-remote path builds it: a system message per layer. */
    private fun fullPrompt(): List<ChatMessage> {
        val snapshot = RoomSnapshot("hearth", "The Hearth", "A warm room.", "home",
            emptyList(), listOf(Entity("p1", "operator", "player", "")), emptyList(), emptyList())
        val now = Clock.System.now()
        val earlier = WorldEvent.Said("hearth", now - 3.hours, "p1", "operator", "good night")
        val trigger = WorldEvent.Said("hearth", now, "p1", "operator", "the tide?")
        val assembled = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = snapshot,
            recentSaid = listOf(earlier, trigger),
            triggerEvent = trigger,
            vitality = VitalityState.initial(),
            additionalContext = "Tools: none.",
            memoryBuffer = "She slept well.",
            bondContext = "## Relationship Context\nGrowing familiarity.",
        )
        assertTrue(assembled.count { it.role == "system" } >= 5, "the layers are separate when assembled")
        return assembled
    }

    private class WireClient : InferenceClient() {
        val sent = mutableListOf<List<ChatMessage>>()
        override suspend fun send(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            sent += messages
            return ChatResponse("ok", 1, 1)
        }
    }

    private class WireLocal : LocalInferenceProvider {
        override val state: StateFlow<String> = MutableStateFlow("running")
        val sent = mutableListOf<List<ChatMessage>>()
        override suspend fun completeLocal(
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            sent += messages
            return ChatResponse("ok", 1, 1)
        }
    }

    @Test
    fun her_full_prompt_leaves_the_client_with_one_system_message_and_the_now_line_on_her_last_turn() = runTest {
        val assembled = fullPrompt()
        val client = WireClient()
        client.complete("http://test", assembled, CompletionOptions(now = NowLine.dateTime(at)))

        val sent = client.sent.single()
        assertOneLeadingSystem(sent)
        assertEquals(assembled.filter { it.role == "system" }.joinToString("\n\n") { it.content }, sent[0].content)
        assertEquals(assembled.filter { it.role != "system" }.dropLast(1), sent.drop(1).dropLast(1))
        assertEquals(NowLine.dateTimeText(at, NowLine.zone()) + "\nmasumi says: the tide?", sent.last().content)
        assertEquals(1, sent.count { it.content.contains("[Now: ") }, "stamped once")
    }

    @Test
    fun a_date_heads_the_one_system_message_once() = runTest {
        val client = WireClient()
        client.complete("http://test", fullPrompt(), CompletionOptions(now = NowLine.date(at)))

        val sent = client.sent.single()
        assertOneLeadingSystem(sent)
        assertTrue(sent[0].content.startsWith(NowLine.dateText(at, NowLine.zone()) + "\nYou are Mia.\n\n"),
            sent[0].content)
        assertEquals(1, sent.sumOf { it.content.split("Today is ").size - 1 }, "stamped once")
    }

    @Test
    fun the_router_sends_one_system_message_to_the_device_and_to_the_remote() = runTest {
        val assembled = fullPrompt()
        val local = WireLocal()
        val remote = WireClient()
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://remote")

        router.complete(assembled, CompletionOptions(now = NowLine.dateTime(at)))
        router.complete(assembled, CompletionOptions(now = NowLine.dateTime(at)), role = ModelRole.DRIVE)

        for (sent in listOf(local.sent.single(), remote.sent.single())) {
            assertOneLeadingSystem(sent)
            assertEquals(1, sent.count { it.content.contains("[Now: ") }, "stamped once")
            assertEquals(NowLine.dateTimeText(at, NowLine.zone()) + "\nmasumi says: the tide?", sent.last().content)
        }
        assertEquals(local.sent.single(), remote.sent.single(), "the same copy on either path")

        // The client merges again after the router stamped: nothing moves, nothing doubles.
        router.complete(assembled, CompletionOptions(now = NowLine.date(at)), role = ModelRole.DRIVE)
        val dated = remote.sent[1]
        assertOneLeadingSystem(dated)
        assertTrue(dated[0].content.startsWith(NowLine.dateText(at, NowLine.zone()) + "\nYou are Mia.\n\n"))
        assertEquals(1, dated.sumOf { it.content.split("Today is ").size - 1 }, "stamped once")
    }
}
