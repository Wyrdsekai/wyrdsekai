package org.wyrdsekai.app.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2C protocol conformance tests.
 * Validates deserialization of all 9 S2C message types against protocol-tests/fixtures/.
 */
class S2CConformanceTest {

    // --- RoomState ---

    @Test
    fun deserializeRoomState() {
        val json = """
        {
          "type": "room_state",
          "seq": 1,
          "room": {
            "roomId": "nexus",
            "name": "The Nexus",
            "description": "A vast crystalline chamber hums with quiet energy. Corridors branch in every direction.",
            "zone": "home",
            "exits": [
              { "direction": "north", "targetRoom": "terminal", "label": "A corridor leads north to the Terminal" },
              { "direction": "down", "targetRoom": "vault", "label": "Stone steps spiral downward" }
            ],
            "entities": [
              { "id": "agent-guide-01", "name": "Guide", "type": "agent", "description": "A patient guide with kind eyes" }
            ],
            "objects": [
              { "id": "obj-scroll-01", "name": "scroll", "description": "An ancient scroll with faded writing", "takeable": true },
              { "id": "obj-pedestal-01", "name": "pedestal", "description": "A stone pedestal with a circular indentation", "takeable": false }
            ],
            "hints": [
              { "label": "Talk to the Guide", "intent": "greet_guide", "action": "say", "labelKey": "hint.greet_guide" },
              { "label": "Read the scroll", "intent": "read_scroll", "action": "use", "labelKey": null },
              { "label": "Go north", "intent": "go_terminal", "action": "go", "labelKey": null }
            ]
          },
          "inventory": [
            { "id": "obj-key-01", "name": "brass key", "description": "A small brass key, warm to the touch", "takeable": true }
          ]
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.RoomState>(msg)
        assertEquals(1L, msg.seq)
        assertEquals("nexus", msg.room.roomId)
        assertEquals("The Nexus", msg.room.name)
        assertEquals("home", msg.room.zone)
        assertEquals(2, msg.room.exits.size)
        assertEquals("north", msg.room.exits[0].direction)
        assertEquals("terminal", msg.room.exits[0].targetRoom)
        assertEquals(1, msg.room.entities.size)
        assertEquals("Guide", msg.room.entities[0].name)
        assertEquals("agent", msg.room.entities[0].type)
        assertEquals(2, msg.room.objects.size)
        assertTrue(msg.room.objects[0].takeable)
        assertEquals("scroll", msg.room.objects[0].name)
        assertEquals(3, msg.room.hints.size)
        assertEquals("hint.greet_guide", msg.room.hints[0].labelKey)
        assertNull(msg.room.hints[1].labelKey)

        assertNotNull(msg.inventory)
        assertEquals(1, msg.inventory!!.size)
        assertEquals("brass key", msg.inventory!![0].name)
    }

    @Test
    fun deserializeRoomStateEmpty() {
        val json = """
        {
          "type": "room_state",
          "seq": 1,
          "room": {
            "roomId": "void",
            "name": "Empty Room",
            "description": "Nothing here.",
            "zone": "home",
            "exits": [],
            "entities": [],
            "objects": [],
            "hints": []
          },
          "inventory": null
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.RoomState>(msg)
        assertEquals(0, msg.room.exits.size)
        assertEquals(0, msg.room.entities.size)
        assertNull(msg.inventory)
    }

    // --- Prose ---

    @Test
    fun deserializeProse() {
        val json = """
        {
          "type": "prose",
          "seq": 2,
          "speaker": "Guide",
          "text": "Welcome, traveler. The Nexus connects all rooms in this zone.",
          "hints": [
            { "label": "Ask about rooms", "intent": "ask_rooms", "action": "say", "labelKey": null }
          ],
          "structured": null,
          "priority": "normal",
          "lang": "en",
          "isAiGenerated": true,
          "blocks": []
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Prose>(msg)
        assertEquals(2L, msg.seq)
        assertEquals("Guide", msg.speaker)
        assertEquals("normal", msg.priority)
        assertEquals("en", msg.lang)
        assertTrue(msg.isAiGenerated)
        assertEquals(1, msg.hints.size)
        assertEquals("Ask about rooms", msg.hints[0].label)
        assertEquals("say", msg.hints[0].action)
        assertNull(msg.structured)
        assertEquals(0, msg.blocks.size)
    }

    @Test
    fun deserializeProseCritical() {
        val json = """
        {
          "type": "prose",
          "seq": 5,
          "speaker": "system",
          "text": "Warning: Server restart in 5 minutes.",
          "hints": [],
          "structured": null,
          "priority": "critical",
          "lang": null,
          "isAiGenerated": false,
          "blocks": []
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Prose>(msg)
        assertEquals("critical", msg.priority)
        assertNull(msg.lang)
        assertEquals(false, msg.isAiGenerated)
    }

    @Test
    fun deserializeProseAmbient() {
        val json = """
        {
          "type": "prose",
          "seq": 6,
          "speaker": "narrator",
          "text": "A gentle breeze stirs the curtains.",
          "hints": [],
          "structured": null,
          "priority": "ambient",
          "lang": "en",
          "isAiGenerated": true,
          "blocks": []
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Prose>(msg)
        assertEquals("ambient", msg.priority)
        assertTrue(msg.isAiGenerated)
    }

    @Test
    fun deserializeProseMinimal() {
        // Minimal prose — only required fields, no optional fields
        val json = """
        {
          "type": "prose",
          "seq": 3,
          "speaker": "narrator",
          "text": "The room is quiet.",
          "hints": [],
          "structured": null,
          "priority": "normal"
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Prose>(msg)
        assertEquals("narrator", msg.speaker)
        // Optional fields should have defaults
        assertNull(msg.lang)
        assertEquals(false, msg.isAiGenerated)
        assertEquals(0, msg.blocks.size)
    }

    @Test
    fun deserializeProseWithBlocks() {
        val json = """
        {
          "type": "prose",
          "seq": 42,
          "speaker": "Agent",
          "text": "Code review complete — 3 issues found in auth.js",
          "hints": [
            { "label": "Approve changes", "intent": "approve", "action": "command", "labelKey": null }
          ],
          "structured": null,
          "priority": "normal",
          "lang": "en",
          "isAiGenerated": true,
          "blocks": [
            {
              "format": "codeplane.diff",
              "data": { "filePath": "auth.js", "additions": 12, "deletions": 5 },
              "fallback": "auth.js: +12 -5 lines changed"
            },
            {
              "format": "codeplane.cost",
              "data": { "tokensIn": 4200, "tokensOut": 850, "estimatedUSD": 0.03 },
              "fallback": "Cost: $0.03 (4.2K in, 850 out)"
            }
          ]
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Prose>(msg)
        assertEquals(42L, msg.seq)
        assertEquals(2, msg.blocks.size)
        assertEquals("codeplane.diff", msg.blocks[0].format)
        assertEquals("auth.js: +12 -5 lines changed", msg.blocks[0].fallback)
        assertEquals("codeplane.cost", msg.blocks[1].format)
        assertEquals("Cost: \$0.03 (4.2K in, 850 out)", msg.blocks[1].fallback)
    }

    @Test
    fun deserializeProseWithStructured() {
        val json = """
        {
          "type": "prose",
          "seq": 8,
          "speaker": "narrator",
          "text": "You look around the room.",
          "hints": [],
          "structured": {
            "name": "The Nexus",
            "description": "A hub of corridors.",
            "exits": [{"direction": "north", "targetRoom": "terminal", "label": "North"}],
            "entities": [],
            "objects": [],
            "hints": [],
            "properties": {"atmosphere": "calm"},
            "zone": "home"
          },
          "priority": "normal",
          "lang": "en",
          "isAiGenerated": false,
          "blocks": []
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Prose>(msg)
        assertNotNull(msg.structured)
        assertEquals("The Nexus", msg.structured!!.name)
        assertEquals("home", msg.structured!!.zone)
    }

    // --- AgentAction ---

    @Test
    fun deserializeAgentAction() {
        val json = """
        {
          "type": "agent_action",
          "seq": 7,
          "agentName": "Guide",
          "action": "emote",
          "description": "smiles warmly and gestures toward the corridor"
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.AgentAction>(msg)
        assertEquals(7L, msg.seq)
        assertEquals("Guide", msg.agentName)
        assertEquals("emote", msg.action)
        assertEquals("smiles warmly and gestures toward the corridor", msg.description)
    }

    // --- StateChange ---

    @Test
    fun deserializeStateChange() {
        val json = """
        {
          "type": "state_change",
          "seq": 5,
          "description": "A door opens.",
          "structured": null,
          "blocks": []
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.StateChange>(msg)
        assertEquals(5L, msg.seq)
        assertEquals("A door opens.", msg.description)
        assertNull(msg.structured)
        assertEquals(0, msg.blocks.size)
    }

    @Test
    fun deserializeStateChangeWithBlocks() {
        val json = """
        {
          "type": "state_change",
          "seq": 20,
          "description": "Board updated",
          "structured": null,
          "blocks": [
            {
              "format": "codeplane.board_card",
              "data": { "boardId": "board-7", "name": "Auth refactor", "status": "in_progress" },
              "fallback": "Card: Auth refactor → in_progress"
            }
          ]
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.StateChange>(msg)
        assertEquals(1, msg.blocks.size)
        assertEquals("codeplane.board_card", msg.blocks[0].format)
    }

    // --- ReplayDone ---

    @Test
    fun deserializeReplayDone() {
        val json = """
        {
          "type": "replay_done",
          "seq": 6,
          "fromSeq": 3,
          "toSeq": 5,
          "count": 2
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.ReplayDone>(msg)
        assertEquals(6L, msg.seq)
        assertEquals(3L, msg.fromSeq)
        assertEquals(5L, msg.toSeq)
        assertEquals(2, msg.count)
    }

    // --- Error ---

    @Test
    fun deserializeError() {
        val json = """
        {
          "type": "error",
          "seq": 10,
          "code": "no_exit",
          "message": "There is no exit in that direction.",
          "requestId": "msg-002"
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Error>(msg)
        assertEquals(10L, msg.seq)
        assertEquals("no_exit", msg.code)
        assertEquals("There is no exit in that direction.", msg.message)
        assertEquals("msg-002", msg.requestId)
    }

    // --- Notification ---

    @Test
    fun deserializeNotification() {
        val json = """
        {
          "type": "notification",
          "seq": 11,
          "level": "info",
          "title": "New message",
          "message": "You have a new message in the Terminal."
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Notification>(msg)
        assertEquals("info", msg.level)
        assertEquals("New message", msg.title)
    }

    @Test
    fun deserializeNotificationWarning() {
        val json = """
        {
          "type": "notification",
          "seq": 12,
          "level": "warning",
          "title": "Low storage",
          "message": "You are running low on zone storage."
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Notification>(msg)
        assertEquals("warning", msg.level)
    }

    // --- Transit ---

    @Test
    fun deserializeTransit() {
        val json = """
        {
          "type": "transit",
          "seq": 13,
          "targetZoneId": "neighbor-zone",
          "targetUrl": "wss://neighbor.example.com/ws",
          "transitToken": "tt-a1b2c3d4-e5f6-7890-abcd-ef1234567890",
          "message": "Departing for neighbor-zone through the Gate. Your transit token expires in 1 hour."
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Transit>(msg)
        assertEquals(13L, msg.seq)
        assertEquals("neighbor-zone", msg.targetZoneId)
        assertEquals("wss://neighbor.example.com/ws", msg.targetUrl)
        assertNotNull(msg.transitToken)
        assertTrue(msg.message.contains("transit token"))
    }

    // --- TokenStream ---

    @Test
    fun deserializeTokenStream() {
        val json = """
        {
          "type": "token_stream",
          "seq": 14,
          "source": "Guide",
          "token": "The ancient",
          "done": false,
          "context": null
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.TokenStream>(msg)
        assertEquals(14L, msg.seq)
        assertEquals("Guide", msg.source)
        assertEquals("The ancient", msg.token)
        assertEquals(false, msg.done)
        assertNull(msg.context)
    }

    @Test
    fun deserializeTokenStreamDone() {
        val json = """
        {
          "type": "token_stream",
          "seq": 17,
          "source": "Guide",
          "token": ".",
          "done": true,
          "context": null
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.TokenStream>(msg)
        assertTrue(msg.done)
    }

    @Test
    fun deserializeTokenStreamWithContext() {
        val json = """
        {
          "type": "token_stream",
          "seq": 15,
          "source": "Agent",
          "token": "Processing",
          "done": false,
          "context": "board-7"
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.TokenStream>(msg)
        assertEquals("board-7", msg.context)
    }

    // --- Scenario: Token Stream Assembly ---

    @Test
    fun tokenStreamAssembly() {
        val messages = listOf(
            """{"type":"token_stream","seq":10,"source":"Guide","token":"The ancient ","done":false,"context":null}""",
            """{"type":"token_stream","seq":11,"source":"Guide","token":"scroll reads: ","done":false,"context":null}""",
            """{"type":"token_stream","seq":12,"source":"Guide","token":"'Welcome, ","done":false,"context":null}""",
            """{"type":"token_stream","seq":13,"source":"Guide","token":"traveler.'","done":true,"context":null}""",
        )

        val buffer = StringBuilder()
        var lastSeq = 0L

        for (raw in messages) {
            val msg = parseS2CMessage(raw)
            assertIs<S2CMessage.TokenStream>(msg)
            assertTrue(msg.seq > lastSeq, "Seq must be monotonically increasing")
            lastSeq = msg.seq
            buffer.append(msg.token)
        }

        assertEquals("The ancient scroll reads: 'Welcome, traveler.'", buffer.toString())
    }

    // --- Scenario: Unknown Content Block ---

    @Test
    fun unknownContentBlockDoesNotCrash() {
        val json = """
        {
          "type": "prose",
          "seq": 50,
          "speaker": "Agent",
          "text": "Here are the results.",
          "hints": [],
          "structured": null,
          "priority": "normal",
          "lang": "en",
          "isAiGenerated": true,
          "blocks": [
            {
              "format": "future.unknown_format",
              "data": { "someField": "someValue" },
              "fallback": "Results: 42 items processed, 3 errors"
            }
          ]
        }
        """.trimIndent()

        // Must not throw
        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Prose>(msg)
        assertEquals(1, msg.blocks.size)
        assertEquals("future.unknown_format", msg.blocks[0].format)
        assertEquals("Results: 42 items processed, 3 errors", msg.blocks[0].fallback)
    }

    // --- Forward Compatibility ---

    @Test
    fun unknownFieldsAreIgnored() {
        val json = """
        {
          "type": "prose",
          "seq": 99,
          "speaker": "narrator",
          "text": "Hello.",
          "hints": [],
          "structured": null,
          "priority": "normal",
          "futureField": "should be ignored",
          "anotherFutureField": 42
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.Prose>(msg)
        assertEquals("Hello.", msg.text)
    }

    // --- MapData ---

    /**
     * Regression (2026-07-24): `map`/`where`/etc reply as map_data with a
     * populated topology. Exit labels are frequently null on the wire (most
     * exits carry no human label), and room zones may be absent — MapEdge.label
     * and MapNode.zone MUST be nullable or kotlinx throws, parseS2CMessage
     * (which catches+drops) returns null, and `map` renders NOTHING on the phone
     * in any real topology room. This is exactly what happened live.
     */
    @Test
    fun deserializeMapDataWithNullEdgeLabel() {
        val json = """
        {
          "type": "map_data",
          "seq": 0,
          "command": "map",
          "textMap": "[* The Nexus]\n├── east->[The Docks]",
          "topology": {
            "centerRoomId": "nexus",
            "nodes": [
              {"roomId":"nexus","name":"The Nexus","zone":"hearth","current":true,"visited":true,"hopsFromCenter":0},
              {"roomId":"docks","name":null,"current":false,"visited":false,"hopsFromCenter":1}
            ],
            "edges": [
              {"fromRoomId":"nexus","toRoomId":"docks","direction":"east","label":null,"hasReturn":true}
            ]
          },
          "path": null
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.MapData>(msg)
        assertEquals("map", msg.command)
        assertTrue(msg.textMap.isNotBlank())
        assertNotNull(msg.topology)
        assertEquals(1, msg.topology!!.edges.size)
        assertNull(msg.topology!!.edges[0].label)
        assertNull(msg.topology!!.nodes[1].zone)
    }

    /**
     * Regression (2026-07-24): a steward Study's room_state carries RoomObjects
     * with a `state` MAP (values sometimes contain literal braces like {actor})
     * and `aliases`, an `inventory` array, room-level `aliases`, and Entities
     * with did/aliases/posture. The KMP models were missing all of those, so
     * kotlinx had to SKIP the unknown nested structures — which mis-tracked on
     * the big frame ("Expected EOF") and the WHOLE room_state was dropped: a
     * steward saw the generic Study on the phone, not their real furnishings.
     * Modelling the fields to match the wire fixes it. This pins the shape.
     */
    @Test
    fun deserializeRichRoomStateWithObjectStateAndInventory() {
        val json = """
        {
          "type": "room_state",
          "seq": 3,
          "room": {
            "roomId": "study-abc",
            "name": "operator's Study",
            "description": "Your private quarters.",
            "zone": "hearth",
            "aliases": [],
            "exits": [{"direction":"out","targetRoom":"nexus","label":"Step out"}],
            "entities": [
              {"id":"ent-1","name":"Wyrd","type":"agent","description":"a companion",
               "did":"did:key:z6Mk","aliases":["wyrd"],"posture":"sitting"}
            ],
            "objects": [
              {"id":"study-chair","name":"leather chair","description":"A worn chair.",
               "takeable":false,"visible":true,"cloneable":false,
               "aliases":["chair","worn leather chair"],
               "state":{"sittable":"true","sitBodyLanguage":"The chair creaks as {actor} leans back.","embodiment.silent":"false"}}
            ],
            "hints": [
              {"label":"Go out","intent":"navigate_out","action":"go:out","labelKey":"ui.go"}
            ]
          },
          "inventory": []
        }
        """.trimIndent()

        val msg = parseS2CMessage(json)
        assertIs<S2CMessage.RoomState>(msg)
        assertEquals("operator's Study", msg.room.name)
        assertEquals(1, msg.room.objects.size)
        // the state map (with a brace-in-string value) decodes intact
        assertEquals("true", msg.room.objects[0].state["sittable"])
        assertTrue(msg.room.objects[0].state["sitBodyLanguage"]!!.contains("{actor}"))
        assertEquals(1, msg.room.entities.size)
        assertEquals("sitting", msg.room.entities[0].posture)
        assertNotNull(msg.inventory)
    }
}
