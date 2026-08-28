package org.wyrdsekai.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C2S protocol conformance tests.
 * Validates serialization roundtrip of all 10 C2S message types.
 */
class C2SConformanceTest {

    @Test
    fun roundtripSay() {
        val msg = C2SMessage.Say(id = "msg-001", roomId = "nexus", text = "Hello everyone!")
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("say", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("msg-001", parsed["id"]?.jsonPrimitive?.content)
        assertEquals("nexus", parsed["roomId"]?.jsonPrimitive?.content)
        assertEquals("Hello everyone!", parsed["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripGo() {
        val msg = C2SMessage.Go(id = "msg-002", roomId = "nexus", direction = "north")
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("go", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("north", parsed["direction"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripTake() {
        val msg = C2SMessage.Take(id = "msg-003", roomId = "nexus", objectName = "scroll")
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("take", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("scroll", parsed["objectName"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripDrop() {
        val msg = C2SMessage.Drop(id = "msg-004", roomId = "nexus", objectName = "scroll")
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("drop", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("scroll", parsed["objectName"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripUse() {
        val msg = C2SMessage.Use(id = "msg-005", roomId = "nexus", objectName = "key", target = "chest")
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("use", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("key", parsed["objectName"]?.jsonPrimitive?.content)
        assertEquals("chest", parsed["target"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripUseNoTarget() {
        val msg = C2SMessage.Use(id = "msg-005b", roomId = "nexus", objectName = "scroll", target = null)
        val json = msg.toJson()

        // Target should be null or absent
        val reparsed = WireJson.decodeFromString(C2SMessage.serializer(), json)
        assertTrue(reparsed is C2SMessage.Use)
        assertEquals(null, (reparsed as C2SMessage.Use).target)
    }

    @Test
    fun roundtripLook() {
        val msg = C2SMessage.Look(id = "msg-006", roomId = "nexus")
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("look", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("nexus", parsed["roomId"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripHintSelect() {
        val msg = C2SMessage.HintSelect(id = "msg-007", roomId = "nexus", index = 0)
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("hint_select", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("0", parsed["index"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripReconnect() {
        val msg = C2SMessage.Reconnect(id = "msg-008", roomId = "nexus", lastSeenSeq = 42)
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("reconnect", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("42", parsed["lastSeenSeq"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripCommand() {
        val msg = C2SMessage.Command(id = "msg-009", command = "inventory")
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("command", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("inventory", parsed["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundtripCommandNamespaced() {
        val msg = C2SMessage.Command(
            id = "msg-009b",
            command = "codezaiku.approve",
            args = emptyList(),
            payload = mapOf("eventId" to "evt-42", "decision" to "approve"),
        )
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("codezaiku.approve", parsed["command"]?.jsonPrimitive?.content)
        assertNotNull(parsed["payload"])
    }

    @Test
    fun roundtripCommandWithArgs() {
        val msg = C2SMessage.Command(
            id = "msg-009c",
            command = "whisper",
            args = listOf("Guide", "Hello there"),
        )
        val json = msg.toJson()

        // Re-parse as typed message
        val reparsed = WireJson.decodeFromString(C2SMessage.serializer(), json)
        assertTrue(reparsed is C2SMessage.Command)
        val cmd = reparsed as C2SMessage.Command
        assertEquals(2, cmd.args.size)
        assertEquals("Guide", cmd.args[0])
        assertEquals("Hello there", cmd.args[1])
    }

    @Test
    fun roundtripSetPreference() {
        val msg = C2SMessage.SetPreference(id = "msg-010", key = "lang", value = "ja")
        val json = msg.toJson()
        val parsed = WireJson.decodeFromString<JsonObject>(json)

        assertEquals("set_preference", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("lang", parsed["key"]?.jsonPrimitive?.content)
        assertEquals("ja", parsed["value"]?.jsonPrimitive?.content)
    }

    // --- Full roundtrip: serialize → deserialize back to typed ---

    @Test
    fun fullRoundtripAllTypes() {
        val messages: List<C2SMessage> = listOf(
            C2SMessage.Say("rt-1", "room1", "hello"),
            C2SMessage.Go("rt-2", "room1", "north"),
            C2SMessage.Take("rt-3", "room1", "key"),
            C2SMessage.Drop("rt-4", "room1", "key"),
            C2SMessage.Use("rt-5", "room1", "key", "door"),
            C2SMessage.Look("rt-6", "room1"),
            C2SMessage.HintSelect("rt-7", "room1", 2),
            C2SMessage.Reconnect("rt-8", "room1", 10),
            C2SMessage.Command("rt-9", "who"),
            C2SMessage.SetPreference("rt-10", "lang", "es"),
        )

        for (original in messages) {
            val json = original.toJson()
            val reparsed = WireJson.decodeFromString(C2SMessage.serializer(), json)
            assertEquals(original, reparsed, "Roundtrip failed for ${original::class.simpleName}")
        }
    }
}
