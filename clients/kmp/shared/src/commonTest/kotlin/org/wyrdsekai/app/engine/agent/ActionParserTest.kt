package org.wyrdsekai.app.engine.agent

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.wyrdsekai.app.inference.NowLine

class ActionParserTest {

    @Test
    fun parseProsOnly() {
        val result = ActionParser.parseAll("Hello, welcome to the Nexus!")
        assertEquals("Hello, welcome to the Nexus!", result.prose)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun parseCreateRoom() {
        val text = """Welcome! Let me create a room for you.
```json
{"action": "create_room", "name": "Gallery", "description": "A bright gallery.", "exits": [{"direction": "south", "target": "nexus", "label": "Back to The Nexus"}]}
```
Enjoy your new space!"""

        val result = ActionParser.parseAll(text)
        assertTrue(result.prose.contains("Welcome"))
        // Note: extractProse returns text before first ```json block; "Enjoy" is after
        assertEquals(1, result.actions.size)
        val action = result.actions[0] as ActionParser.AgentAction.CreateRoom
        assertEquals("Gallery", action.name)
        assertEquals("A bright gallery.", action.description)
        assertEquals(1, action.exits.size)
        assertEquals("south", action.exits[0].direction)
    }

    @Test
    fun parseSuggestHints() {
        val text = """Here are some things you can do:
```json
{"action": "suggest_hints", "hints": [
  {"label": "Explore", "intent": "explore", "action": "say:explore"},
  {"label": "Rest", "intent": "rest", "action": "say:rest"}
]}
```"""

        val result = ActionParser.parseAll(text)
        assertEquals(1, result.actions.size)
        val action = result.actions[0] as ActionParser.AgentAction.SuggestHints
        assertEquals(2, action.hints.size)
        assertEquals("Explore", action.hints[0].label)
    }

    @Test
    fun parseMultipleActions() {
        val text = """Let me set things up.
```json
{"action": "create_room", "name": "Lab", "description": "A lab.", "exits": []}
```
And here are your options:
```json
{"action": "suggest_hints", "hints": [{"label": "Enter lab", "intent": "go", "action": "say:go north"}]}
```"""

        val result = ActionParser.parseAll(text)
        assertEquals(2, result.actions.size)
        assertTrue(result.actions[0] is ActionParser.AgentAction.CreateRoom)
        assertTrue(result.actions[1] is ActionParser.AgentAction.SuggestHints)
    }

    @Test
    fun parseMalformedJsonIgnored() {
        val text = """Here's something:
```json
{not valid json}
```
But this is fine."""

        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
        assertTrue(result.prose.contains("Here's something"))
    }

    @Test
    fun parseEmptyHintsIgnored() {
        val text = """
```json
{"action": "suggest_hints", "hints": []}
```"""
        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun parseUnknownActionIgnored() {
        val text = """
```json
{"action": "unknown_action", "data": "something"}
```"""
        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
    }

    // ── Emote action ──

    @Test
    fun parseEmoteAction() {
        val text = """I feel happy today!
```json
{"action": "emote", "text": "smiles warmly"}
```"""
        val result = ActionParser.parseAll(text)
        assertEquals(1, result.actions.size)
        val action = assertIs<ActionParser.AgentAction.Emote>(result.actions[0])
        assertEquals("smiles warmly", action.text)
        assertTrue(result.prose.contains("happy"))
    }

    @Test
    fun parseEmoteEmptyTextIgnored() {
        val text = """
```json
{"action": "emote", "text": ""}
```"""
        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun parseEmoteBlankTextIgnored() {
        val text = """
```json
{"action": "emote", "text": "   "}
```"""
        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
    }

    // ── Social action ──

    @Test
    fun parseSocialAction() {
        val text = """
```json
{"action": "social", "name": "nod"}
```"""
        val result = ActionParser.parseAll(text)
        assertEquals(1, result.actions.size)
        val action = assertIs<ActionParser.AgentAction.Social>(result.actions[0])
        assertEquals("nod", action.name)
    }

    @Test
    fun parseSocialEmptyNameIgnored() {
        val text = """
```json
{"action": "social", "name": ""}
```"""
        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
    }

    // ── WhisperTo action ──

    @Test
    fun parseWhisperToAction() {
        val text = """Let me tell you something privately.
```json
{"action": "whisper_to", "target": "player", "text": "hey there"}
```"""
        val result = ActionParser.parseAll(text)
        assertEquals(1, result.actions.size)
        val action = assertIs<ActionParser.AgentAction.WhisperTo>(result.actions[0])
        assertEquals("player", action.target)
        assertEquals("hey there", action.text)
    }

    @Test
    fun parseWhisperToEmptyTargetIgnored() {
        val text = """
```json
{"action": "whisper_to", "target": "", "text": "secret"}
```"""
        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun parseWhisperToEmptyTextIgnored() {
        val text = """
```json
{"action": "whisper_to", "target": "alice", "text": ""}
```"""
        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun parseWhisperToBothEmptyIgnored() {
        val text = """
```json
{"action": "whisper_to", "target": "", "text": ""}
```"""
        val result = ActionParser.parseAll(text)
        assertTrue(result.actions.isEmpty())
    }

    // ── Emote with other actions ──

    @Test
    fun parseEmoteTakesPriorityWhenFirst() {
        val text = """
```json
{"action": "emote", "text": "waves"}
```
```json
{"action": "social", "name": "nod"}
```"""
        val result = ActionParser.parseAll(text)
        // First action wins as primary
        assertEquals(1, result.actions.size)
        assertIs<ActionParser.AgentAction.Emote>(result.actions[0])
    }

    // ── The date line a request carries, repeated in her reply (2026-09-23) ──

    private val newYork = TimeZone.of("America/New_York")
    /** `[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]` */
    private val nowLine = NowLine.dateTimeText(Instant.parse("2026-09-23T14:05:00Z"), newYork)
    /** `[Asked: Wednesday 23 September 2026, 09:40 UTC-4]` */
    private val askedLine = NowLine.askedText(Instant.parse("2026-09-23T13:40:00Z"), newYork)

    @Test
    fun anEchoedDateLineIsNotHerWords() {
        assertEquals("[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]", nowLine)
        assertEquals("Morning. The tea is on.", ActionParser.stripNowLineEcho("$nowLine\nMorning. The tea is on."))
        assertEquals("Morning.", ActionParser.stripNowLineEcho("${nowLine.dropLast(1)}\nMorning."),
            "the closing bracket dropped")
        assertEquals("Yes.", ActionParser.stripNowLineEcho("$askedLine\nYes."))
        assertEquals("Yes.", ActionParser.stripNowLineEcho("${askedLine.dropLast(1)}\nYes."),
            "the closing bracket dropped")
        assertEquals("At noon.", ActionParser.stripNowLineEcho("$nowLine\n$askedLine\nAt noon."),
            "a replay's two lines")
        assertEquals("Morning.\nThe tea is on.", ActionParser.stripNowLineEcho("Morning.\n  $nowLine  \nThe tea is on."),
            "at the start of any line of hers")
        assertEquals("", ActionParser.stripNowLineEcho(nowLine), "the line was all there was")
    }

    @Test
    fun herOwnWordsThatStartWithNowStay() {
        val hers = "Now: the kettle, then the letters."
        assertSame(hers, ActionParser.stripNowLineEcho(hers))
        assertEquals(hers, ActionParser.parseAll(hers).prose)
        assertEquals("Asked: whether the tide turns at noon.", ActionParser.parseAll("Asked: whether the tide turns at noon.").prose)
    }

    @Test
    fun theProseOfEveryReplyLeavesWithoutTheDateLine() {
        assertEquals("Morning. The tea is on.", ActionParser.parseAll("$nowLine\nMorning. The tea is on.").prose)
        assertEquals("Morning.", ActionParser.parseAll("${nowLine.dropLast(1)}\nMorning.").prose)
        assertEquals("At noon.", ActionParser.parseAll("$nowLine\n$askedLine\nAt noon.").prose)

        val withAction = ActionParser.parseAll(
            "$nowLine\nLet me look.\n```json\n{\"action\": \"emote\", \"text\": \"looks up\"}\n```",
        )
        assertEquals("Let me look.", withAction.prose)
        assertIs<ActionParser.AgentAction.Emote>(withAction.primaryAction)

        assertEquals("", ActionParser.parseAll("$nowLine\n```json\n{\"action\": \"emote\", \"text\": \"nods\"}\n```").prose,
            "nothing of hers before the action")
    }
}
