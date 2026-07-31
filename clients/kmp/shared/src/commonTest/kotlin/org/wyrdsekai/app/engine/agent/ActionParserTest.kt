package org.wyrdsekai.app.engine.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
}
