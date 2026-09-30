package org.wyrdsekai.app.engine.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.InetSocketAddress
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.wyrdsekai.app.engine.InMemoryEventJournal
import org.wyrdsekai.app.engine.between.BudDelegation
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.room.RoomEngine
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.i18n.UiStrings
import org.wyrdsekai.app.i18n.fillTemplate
import org.wyrdsekai.app.i18n.uiStringsFor
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient

/**
 * Her emotes and the lines the phone speaks for her follow the language the app
 * is set to (`wyrdsekai.locale`, published by WyrdApp). They were English
 * literals in CompanionEngine, and in ProactivityJudgment for her unprompted
 * lines, whatever the language.
 *
 * Desktop-only: the locale and the inference URL are JVM system properties, set
 * and cleared around each test, and the last test reads the source tree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionNarratesInTheAppLanguageTest {

    private val profile = AgentProfile(
        name = "Wyrd",
        entityId = "companion-wyrd",
        entityType = "agent",
        description = "A test companion",
        systemPrompt = "You are Wyrd, a test companion.",
        contextWindowTokens = 4096,
        maxResponseTokens = 128,
        temperature = 0.7,
    )

    private val ja = uiStringsFor("ja")
    private val es = uiStringsFor("es")
    private val en = uiStringsFor("en")

    @AfterTest
    fun tearDown() {
        System.clearProperty("wyrdsekai.locale")
        System.clearProperty("wyrdsekai.inference.url")
    }

    private class CannedClient(private val reply: String?) : InferenceClient() {
        override suspend fun send(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse = ChatResponse(reply ?: error("the model is unreachable"), 1, 1)
    }

    private suspend fun room(scope: kotlinx.coroutines.CoroutineScope): Pair<RoomEngine, InMemoryEventJournal> {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("nexus", journal, null, null, scope)
        engine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        return engine to journal
    }

    private fun hers(journal: InMemoryEventJournal) =
        journal.allEvents("nexus").filter {
            (it is WorldEvent.Said && it.entityId == profile.entityId) ||
                (it is WorldEvent.Emoted && it.entityId == profile.entityId)
        }.map { if (it is WorldEvent.Said) it.text else (it as WorldEvent.Emoted).text }

    @Test
    fun her_quick_turn_emotes_and_her_action_is_narrated_in_japanese() = runTest {
        System.setProperty("wyrdsekai.locale", "ja")
        val (roomEngine, journal) = room(backgroundScope)
        val client = CannedClient("""Let me look.
```json
{"action": "examine", "target": "the lantern"}
```""")
        val companion = CompanionEngine(profile, roomEngine, client, "http://test", null, backgroundScope)
        companion.start()
        advanceTimeBy(200)

        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "hello"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val lines = hers(journal)
        assertTrue(ja.narrationConsiders in lines, "the thinking emote: $lines")
        assertTrue(fillTemplate(ja.narrationExamines, "the lantern") in lines, "the examine action: $lines")
        assertTrue(en.narrationConsiders !in lines, "no English left: $lines")

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun when_she_cannot_think_deeply_she_says_so_in_spanish() = runTest {
        System.setProperty("wyrdsekai.locale", "es")
        val (roomEngine, journal) = room(backgroundScope)
        val companion = CompanionEngine(profile, roomEngine, CannedClient(null), "http://test", null, backgroundScope)
        companion.start()
        advanceTimeBy(200)

        // > 8 words with a question mark: heuristic COMPLEX. No delegation, no remote
        // URL, and the acknowledgement fails too: the phone speaks for her.
        val text = "Can you help me organize all my photos from the last three years into albums?"
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", text))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val lines = hers(journal)
        assertTrue(es.narrationThinkingDeeply in lines, "the deep-thinking emote: $lines")
        assertTrue(es.narrationMentalNote in lines, "the queueing emote: $lines")
        assertTrue(es.narrationThinkLater in lines, "the fallback line: $lines")

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun a_replayed_answer_is_introduced_in_japanese() = runTest {
        System.setProperty("wyrdsekai.locale", "ja")
        val household = household("""{"choices":[{"message":{"role":"assistant","content":"At noon."}}]}""")
        try {
            System.setProperty("wyrdsekai.inference.url", "http://127.0.0.1:${household.address.port}")
            val (roomEngine, journal) = room(backgroundScope)
            val companion = CompanionEngine(profile, roomEngine, CannedClient(null), "http://test", null, backgroundScope)
            // No such directory: nothing is written, the queue keeps its copy in memory.
            val queue = OfflineQueue("/nonexistent/wyrdsekai-narration-test")
            queue.enqueue("When does the tide turn?", "Alice", "nexus")
            companion.offlineQueue = queue

            companion.replayOfflineQueue()

            val lines = hers(journal)
            assertEquals(listOf(ja.narrationCatchesUp, "「When does the tide turn?」について — At noon."), lines)
        } finally {
            household.stop(0)
        }
    }

    @Test
    fun every_language_carries_every_narration_line_with_the_same_placeholders() {
        val getters = UiStrings::class.java.methods.filter { it.name.startsWith("getNarration") }
        assertTrue(getters.size >= 80, "found ${getters.size}")
        for (getter in getters) {
            val english = getter.invoke(en) as String
            for ((locale, strings) in listOf("ja" to ja, "es" to es)) {
                val translated = getter.invoke(strings) as String
                assertTrue(translated.isNotBlank(), "$locale ${getter.name}")
                assertNotEquals(english, translated, "$locale ${getter.name} is still English")
                assertEquals(placeholders(english), placeholders(translated), "$locale ${getter.name}: $translated")
            }
        }
    }

    @Test
    fun a_template_fills_in_order_by_position_and_only_once() {
        assertEquals("*gives a lantern to Alice*", fillTemplate(en.narrationGives, "a lantern", "Alice"))
        assertEquals("*Aliceにランタンを渡す*", fillTemplate(ja.narrationGives, "ランタン", "Alice"))
        assertEquals("Found 3 entries:\n- tide", fillTemplate(en.narrationJournalFound, 3, "- tide"))
        assertEquals("Note saved: 100%s sure", fillTemplate(en.narrationNoteSaved, "100%s sure"))
        assertEquals("About \"the tide\" —", fillTemplate(en.narrationReplayAbout, "the tide"), "English is unchanged")
    }

    @Test
    fun her_unprompted_lines_follow_the_app_language() {
        fun act(drives: DriveState, tier: Int, confidence: Double = 0.5, lastHuman: kotlin.time.Instant? = null) =
            (ProactivityJudgment.evaluate(ProactivityJudgment.Context(
                drives = drives,
                vitality = VitalityState.initial().withConfidence(confidence),
                remainingBudget = 3.0,
                lastProactiveAction = null,
                lastHumanSpeech = lastHuman,
                agentEntityId = profile.entityId,
                tier = tier,
                strings = ja,
            )) as ProactivityJudgment.JudgmentResult.Act).action

        fun text(action: ProactiveAction) = when (action) {
            is ProactiveAction.Ambient -> action.emoteText
            is ProactiveAction.Observation -> action.speechText
            is ProactiveAction.Initiative -> action.description
        }

        val none = DriveState.initial()
        val longAgo = Clock.System.now() - 40.minutes
        val said = listOf(
            ja.narrationNoticedSomething to act(none.copy(curiosity = 0.75), tier = 0),
            ja.narrationExploringSomething to act(none.copy(curiosity = 0.8), tier = 2),
            ja.narrationQuietCheckIn to act(none.copy(care = 0.9), tier = 0),
            ja.narrationConcernedGlance to act(none.copy(care = 0.75), tier = 0),
            ja.narrationAnythingOnYourMind to act(none.copy(social = 0.9), tier = 0),
            ja.narrationBeenAWhile to act(none.copy(social = 0.9), tier = 0, lastHuman = longAgo),
            ja.narrationShiftsThoughtfully to act(none.copy(social = 0.55), tier = 1),
            ja.narrationMeaningToFollowUp to act(none.copy(achievement = 0.75), tier = 0),
            ja.narrationActingOnCommitment to act(none.copy(achievement = 0.8), tier = 1),
            ja.narrationPatternsShifted to act(none.copy(alertness = 0.8), tier = 0),
            // An initiative with too little confidence is said as an observation.
            ja.narrationNoticedSomething to act(none.copy(curiosity = 0.8), tier = 2, confidence = 0.2),
        )
        for ((expected, action) in said) assertEquals(expected, text(action), action.toString())

        // The engine hands the judgment its own strings, per tick.
        val engine = File(srcRoot(), "commonMain/kotlin/org/wyrdsekai/app/engine/agent/CompanionEngine.kt").readText()
        assertTrue(engine.contains("strings = strings(),"), "CompanionEngine passes strings() to ProactivityJudgment")
    }

    @Test
    fun what_the_household_did_is_narrated_with_spanish_words() = runTest {
        System.setProperty("wyrdsekai.locale", "es")
        val server = household(
            """{"requestId":"r1","text":"Vamos.","actions":[""" +
                """{"type":"room_navigated","data":{"newRoomId":"garden","direction":"north"}},""" +
                """{"type":"room_navigated","data":{"newRoomId":"vault","direction":"the old stair"}},""" +
                """{"type":"notification","data":{"message":"La marea sube.","priority":"high"}}]}""",
            path = "/api/companion/ask",
        )
        try {
            val (roomEngine, journal) = room(backgroundScope)
            val companion = CompanionEngine(profile, roomEngine, CannedClient(null), "http://test", null, backgroundScope)
            companion.budDelegation = BudDelegation(
                between = null, nodeId = "phone", familyId = "family",
                serverUrl = "http://127.0.0.1:${server.address.port}", deviceToken = "device-token",
            )
            companion.start()
            advanceTimeBy(200)

            // Delegation is real I/O: wait for her answer, then for what follows it.
            val answered = backgroundScope.async { companion.companionSpeech.first { it == "Vamos." } }
            runCurrent()
            val text = "Can you help me organize all my photos from the last three years into albums?"
            roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", text))
            advanceTimeBy(5000)
            answered.await()
            advanceTimeBy(1000)

            val emotes = journal.allEvents("nexus").filterIsInstance<WorldEvent.Emoted>().map { it.text }
            // They came out as "se dirige hacia north" and "*notificación (high)*: …".
            assertTrue(fillTemplate(es.narrationHeads, "el norte") in emotes, emotes.toString())
            assertTrue(fillTemplate(es.narrationHeads, "the old stair") in emotes, "an exit's own name: $emotes")
            assertTrue(fillTemplate(es.narrationNotificationPriority, "alta", "La marea sube.") in emotes, emotes.toString())

            companion.shutdown()
            roomEngine.shutdown()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun every_language_has_the_same_direction_and_priority_words() {
        for ((locale, strings) in listOf("ja" to ja, "es" to es)) {
            assertEquals(en.directionWords.keys, strings.directionWords.keys, "$locale directionWords")
            assertEquals(en.priorityWords.keys, strings.priorityWords.keys, "$locale priorityWords")
        }
        assertEquals(en.directionWords.keys, en.directionWords.values.toSet(), "English says the code itself")
        assertTrue(ja.directionWords.values.none { Regex("[A-Za-z]").containsMatchIn(it) }, ja.directionWords.toString())
        assertTrue(ja.priorityWords.values.none { Regex("[A-Za-z]").containsMatchIn(it) }, ja.priorityWords.toString())
        assertEquals("el norte", es.directionWords["north"])
    }

    @Test
    fun the_engine_emotes_and_speaks_no_english_literal() {
        val src = File(srcRoot(), "commonMain/kotlin/org/wyrdsekai/app/engine/agent/CompanionEngine.kt").readText()
        val offenders = mutableListOf<String>()
        for (call in listOf("speak(", "handleInferenceSuccess(", "EmoteInRoom(")) {
            var at = src.indexOf(call)
            while (at >= 0) {
                val args = arguments(src, at + call.length - 1)
                // What is said or emoted: speak's own argument, EmoteInRoom's text
                // (its ids, "narrator", and prefixes like removePrefix("say:") are not).
                val literals = if (call == "EmoteInRoom(") textLiteral.findAll(args) else literal.findAll(args)
                for (m in literals) {
                    val words = m.groupValues[1].replace(interpolation, "")
                    if (Regex("[A-Za-z]{2,}").containsMatchIn(words)) offenders += "line ${line(src, at)}: ${m.value}"
                }
                at = src.indexOf(call, at + 1)
            }
        }
        assertEquals(emptyList(), offenders, "narration goes through UiStrings (strings().narration…)")

        // Her unprompted lines: what ProactivityJudgment puts in an action's text.
        val judgment = File(srcRoot(), "commonMain/kotlin/org/wyrdsekai/app/engine/agent/ProactivityJudgment.kt").readText()
        val unprompted = Regex("""(?:speechText|emoteText|description) = "((?:[^"\\]|\\.)*)"""")
            .findAll(judgment).map { "line ${line(judgment, it.range.first)}: ${it.value}" }.toList()
        assertEquals(emptyList(), unprompted, "her unprompted lines go through UiStrings (ctx.strings.narration…)")
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** A local endpoint that answers every request at [path] with [reply]. */
    private fun household(reply: String, path: String = "/v1/chat/completions"): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            exchange.requestBody.readBytes()
            val bytes = reply.encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private val literal = Regex("""^\(\s*"((?:[^"\\]|\\.)*)"""")
    private val textLiteral = Regex("""text = "((?:[^"\\]|\\.)*)"""")
    private val interpolation = Regex("""\$\{[^}]*}|\$\w+""")
    private val placeholder = Regex("""%(?:(\d+)\$)?s""")

    /** The argument positions a template reads, in position order. */
    private fun placeholders(template: String): List<Int> {
        var next = 0
        return placeholder.findAll(template).map { it.groupValues[1].toIntOrNull() ?: ++next }.sorted().toList()
    }

    private fun srcRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "src"), File(dir, "shared/src"))) {
                if (File(candidate, "commonMain/kotlin").isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("shared/src not found from ${System.getProperty("user.dir")}")
    }

    /** The call's argument list from its open paren to the matching close, skipping strings. */
    private fun arguments(src: String, open: Int): String {
        var depth = 0
        var j = open
        while (j < src.length) {
            val c = src[j]
            if (c == '"') {
                j++
                while (j < src.length && src[j] != '"') {
                    if (src[j] == '\\') j++
                    j++
                }
            } else if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
                if (depth == 0) return src.substring(open, j + 1)
            }
            j++
        }
        return src.substring(open)
    }

    private fun line(src: String, at: Int): Int = src.substring(0, at).count { it == '\n' } + 1
}
