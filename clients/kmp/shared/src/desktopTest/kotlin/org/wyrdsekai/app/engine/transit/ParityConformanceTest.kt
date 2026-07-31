package org.wyrdsekai.app.engine.transit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.protocol.Hint
import org.wyrdsekai.app.protocol.WireJson
import org.wyrdsekai.app.protocol.parseS2CMessage
import org.wyrdsekai.app.protocol.toJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * EXECUTABLE client-parity contract (clients/parity/parity.json).
 *
 * Drives SessionInputMapper + SessionS2CRenderer straight from the shared
 * table — the SAME file RN's parity-conformance.test.ts consumes. When the two
 * clients drift on the live-session interaction layer, one of these suites
 * fails instead of operator finding it on a phone (2026-07-25).
 */
class ParityConformanceTest {

    private val table: JsonObject by lazy {
        val file = findParityJson()
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun findParityJson(): File {
        // Walk up from the working dir (varies: shared/, clients/kmp/, repo root).
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "clients/parity/parity.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("clients/parity/parity.json not found above ${System.getProperty("user.dir")}")
    }

    private fun hintsFixture(): List<Hint> =
        table.getValue("hintsFixture").jsonArray.map {
            val o = it.jsonObject
            Hint(
                label = o.getValue("label").jsonPrimitive.content,
                intent = o.getValue("intent").jsonPrimitive.content,
                action = o.getValue("action").jsonPrimitive.content,
            )
        }

    /** Strip client-filled/empty fields so semantic frames compare cleanly. */
    private fun normalize(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(
            el.entries
                .filter { (k, v) ->
                    k != "id" && k != "roomId" && k != "seq" &&
                        v != JsonNull &&
                        !(v is JsonObject && v.isEmpty()) &&
                        !(v is JsonArray && v.isEmpty())
                }
                .associate { (k, v) -> k to normalize(v) },
        )
        is JsonArray -> JsonArray(el.map { normalize(it) })
        else -> el
    }

    @Test
    fun inputMappingMatchesParityTable() {
        val hints = hintsFixture()
        val failures = mutableListOf<String>()
        for (case in table.getValue("input").jsonArray) {
            val c = case.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val input = c.getValue("input").jsonPrimitive.content
            val caseHints = if (c["hints"]?.jsonPrimitive?.content == "none") emptyList() else hints
            val expect = c.getValue("expect").jsonObject

            val mapped = SessionInputMapper.map(input, caseHints) { "test-id" }

            val expectedFrame = expect["frame"]?.jsonObject
            val expectedLocal = expect["local"]?.jsonObject
            when {
                expectedFrame != null -> {
                    if (mapped !is MappedInput.Send) {
                        failures += "$name: expected frame ${expectedFrame["type"]}, got $mapped"
                        continue
                    }
                    val produced = normalize(Json.parseToJsonElement(mapped.frame.toJson()))
                    val expected = normalize(expectedFrame)
                    if (produced != expected) {
                        failures += "$name: frame mismatch\n  expected $expected\n  produced $produced"
                    }
                    // echoPolicy: every send echoes "> <trimmed input>".
                    val expectedEcho = "> ${input.trim()}"
                    if (mapped.echo != expectedEcho) {
                        failures += "$name: echo '${mapped.echo}' != '$expectedEcho'"
                    }
                }
                expectedLocal != null -> when (expectedLocal.getValue("kind").jsonPrimitive.content) {
                    "ignore" -> if (mapped != MappedInput.Ignore) {
                        failures += "$name: expected Ignore, got $mapped"
                    }
                    "text" -> {
                        if (mapped !is MappedInput.LocalText) {
                            failures += "$name: expected LocalText, got $mapped"
                        } else {
                            val speaker = expectedLocal.getValue("speaker").jsonPrimitive.content
                            if (mapped.speaker != speaker) {
                                failures += "$name: speaker ${mapped.speaker} != $speaker"
                            }
                            val exact = expectedLocal["text"]?.jsonPrimitive?.content
                            val prefix = expectedLocal["textStartsWith"]?.jsonPrimitive?.content
                            if (exact != null && mapped.text != exact) {
                                failures += "$name: text mismatch\n  expected: $exact\n  produced: ${mapped.text}"
                            }
                            if (prefix != null && !mapped.text.startsWith(prefix)) {
                                failures += "$name: text does not start with '$prefix': ${mapped.text.take(40)}"
                            }
                        }
                    }
                    else -> failures += "$name: unknown local kind"
                }
                else -> failures += "$name: case has neither frame nor local expectation"
            }
        }
        assertTrue(failures.isEmpty(), "parity drift (KMP):\n" + failures.joinToString("\n"))
    }

    @Test
    fun s2cRenderMatchesParityTable() {
        val failures = mutableListOf<String>()
        for (case in table.getValue("s2cRender").jsonArray) {
            val c = case.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            // Decode through the client's OWN wire decoder — also proves the
            // decoder itself accepts the canonical frame shapes.
            val msg = parseS2CMessage(WireJson.encodeToString(JsonElement.serializer(), c.getValue("frame")))
            val render = SessionS2CRenderer.render(msg)

            val expect = c.getValue("expect").jsonObject
            val expectedProse = expect.getValue("prose").jsonArray.map {
                val o = it.jsonObject
                o.getValue("speaker").jsonPrimitive.content to o.getValue("text").jsonPrimitive.content
            }
            val expectedRoom = expect.getValue("roomUpdate").jsonPrimitive.content.toBoolean()

            if (render.prose != expectedProse) {
                failures += "$name: prose mismatch\n  expected $expectedProse\n  produced ${render.prose}"
            }
            if ((render.room != null) != expectedRoom) {
                failures += "$name: roomUpdate ${(render.room != null)} != $expectedRoom"
            }
        }
        assertTrue(failures.isEmpty(), "parity drift (KMP render):\n" + failures.joinToString("\n"))
    }

    @Test
    fun tableHasCases() {
        assertEquals(1, table.getValue("version").jsonPrimitive.content.toInt())
        assertTrue(table.getValue("input").jsonArray.size >= 40, "input table shrank — did someone delete cases?")
        assertTrue(table.getValue("s2cRender").jsonArray.size >= 6)
    }
}
