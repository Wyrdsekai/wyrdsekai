package org.wyrdsekai.app.engine.transit

import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.Hint
import org.wyrdsekai.app.protocol.RoomSnapshot
import org.wyrdsekai.app.protocol.S2CMessage

/**
 * THE live-session input contract — one pure function from a typed line to the
 * C2S frame (or client-local behavior) a terminal must produce over a live zone
 * session (relay tunnel / direct WS).
 *
 * This is the KMP half of the EXECUTABLE parity contract in
 * `clients/parity/parity.json`: the RN twin is
 * `clients/rn/src/engine/transit/sessionInputMapper.ts`, and both are driven by
 * the same JSON table from their test suites (ParityConformanceTest.kt /
 * parity-conformance.test.ts). Behavior changes go TABLE-FIRST; a client that
 * drifts fails its build. Born 2026-07-25 after a week of the two hand-written
 * input layers drifting apart one bug at a time (chips sending Say("go out"),
 * silent tunneled `look`, number-select wrapping hints in Say, …).
 *
 * Scope: LIVE-SESSION ONLY. Offline paths legitimately differ (they drive the
 * local node's APIs directly). Study commands (journal/library) are intercepted
 * by the screens BEFORE this mapper when a ServerClient is present.
 */
sealed interface MappedInput {
    /**
     * Send this frame over the session, after rendering [echo] as a muted
     * system line. The echo is TERMINAL-style ("> <input>"), never a speech
     * bubble — echoing commands as "You: l" made every command read as the
     * player SAYING it (operator, 2026-07-25). Part of the parity contract
     * (parity.json "echoPolicy").
     */
    data class Send(val frame: C2SMessage, val echo: String) : MappedInput

    /** Render client-local text (help/socials/actions menu) — nothing is sent. */
    data class LocalText(val speaker: String, val text: String) : MappedInput

    /** Swallow the input (blank emote, empty line). */
    data object Ignore : MappedInput
}

object SessionInputMapper {

    /** Client-side help — documents CLIENT input syntax, so it stays local. */
    const val HELP_TEXT: String =
        "Commands:\n" +
        "  say <text> or '<text> or \"<text>  -- Say something\n" +
        "  emote <action> or :<action> or ;<action>  -- Perform an action\n" +
        "  tell <name> <text> or ><name> <text>  -- Send a private message\n" +
        "  whisper <name> <text>  -- Whisper to someone nearby\n" +
        "  look or l  -- Look around\n" +
        "  go <direction>  -- Move to another room\n" +
        "  take <object>  -- Pick up an object\n" +
        "  drop <object>  -- Drop an object\n" +
        "  use <object>  -- Use an object\n" +
        "  /inventory or /i  -- Check your inventory\n" +
        "  /socials  -- List social emotes\n" +
        "  /help  -- Show this help"

    const val SOCIALS_TEXT: String =
        "Social emotes (type the word to perform):\n" +
        "  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n" +
        "  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n" +
        "  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n" +
        "  hug, thank, agree, disagree, salute, welcome"

    private val directionAliases: Map<String, String> = mapOf(
        "n" to "north", "s" to "south", "e" to "east", "w" to "west",
        "u" to "up", "d" to "down",
        "ne" to "northeast", "nw" to "northwest", "se" to "southeast", "sw" to "southwest",
        "北" to "north", "南" to "south", "東" to "east", "西" to "west",
        "上" to "up", "下" to "down",
    )

    private val bareDirections: Set<String> =
        setOf(
            "north", "south", "east", "west", "up", "down",
            "northeast", "northwest", "southeast", "southwest",
            "out", "back",
        ) + directionAliases.keys

    fun resolveDirection(raw: String): String {
        val t = raw.trim()
        return directionAliases[t] ?: directionAliases[t.lowercase()] ?: t.lowercase()
    }

    /** The numbered actions menu (SSH parity). Exact copy is part of the contract. */
    fun actionsMenu(hints: List<Hint>): String =
        if (hints.isEmpty()) {
            "Nothing to do here right now."
        } else {
            buildString {
                append("Things to do here:")
                hints.forEachIndexed { i, h -> append("\n  [${i + 1}] ${h.label}") }
                append("\n(type a number to choose)")
            }
        }

    private val examineRe = Regex("^(?:examine|exam|ex|inspect|x)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val lookAtRe = Regex("^l(?:ook)?\\s+at\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val goRe = Regex("^(?:go|move)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val takeRe = Regex("^(?:take|get)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val pickUpRe = Regex("^pick\\s+up\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val dropRe = Regex("^(?:drop|put\\s+down)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val useRe = Regex("^use\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val sayRe = Regex("^say\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val emoteRe = Regex("^emote\\s+(.+)$", RegexOption.IGNORE_CASE)

    fun map(raw: String, hints: List<Hint>, nextId: () -> String): MappedInput {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return MappedInput.Ignore
        val lower = trimmed.lowercase()
        // Terminal-style echo for everything that goes over the wire.
        fun send(frame: C2SMessage) = MappedInput.Send(frame, "> $trimmed")

        // Slash commands. /help + /socials document CLIENT syntax → local text;
        // /actions mirrors the bare word; /inventory normalizes; everything else
        // strips the slash and forwards — the zone re-parses through the SAME
        // CommandParser SSH uses (never wrap in Say: the zone does NOT re-parse
        // say text as commands, it just speaks it).
        if (trimmed.startsWith("/")) {
            val parts = trimmed.substring(1).split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return MappedInput.Ignore
            return when (parts[0].lowercase()) {
                "help" -> MappedInput.LocalText("system", HELP_TEXT)
                "socials" -> MappedInput.LocalText("system", SOCIALS_TEXT)
                "actions" -> MappedInput.LocalText("system", actionsMenu(hints))
                "inventory", "i" -> send(C2SMessage.Command(nextId(), "inventory", parts.drop(1)))
                else -> send(C2SMessage.Command(nextId(), parts[0], parts.drop(1)))
            }
        }

        // Numbered actions menu + selection (SSH parity).
        if (lower == "actions") return MappedInput.LocalText("system", actionsMenu(hints))
        trimmed.toIntOrNull()?.let { n ->
            if (n in 1..hints.size) {
                // Canonical index-based select — the zone dispatches the hint's
                // OWN intent. Never round-trip through a guessed verb or Say.
                return send(C2SMessage.HintSelect(nextId(), "", n - 1))
            }
            // Out-of-range → forward like any unknown word (server decides).
        }

        // examine family BEFORE bare look, so "look at X" isn't a room render.
        (examineRe.find(trimmed) ?: lookAtRe.find(trimmed))?.let {
            return send(C2SMessage.Examine(nextId(), "", it.groupValues[1].trim()))
        }
        if (lower == "look" || lower == "l") {
            return send(C2SMessage.Look(nextId(), ""))
        }

        goRe.find(trimmed)?.let {
            return send(C2SMessage.Go(nextId(), "", resolveDirection(it.groupValues[1])))
        }
        if (lower in bareDirections || trimmed in bareDirections) {
            return send(C2SMessage.Go(nextId(), "", resolveDirection(trimmed)))
        }

        (takeRe.find(trimmed) ?: pickUpRe.find(trimmed))?.let {
            return send(C2SMessage.Take(nextId(), "", it.groupValues[1].trim()))
        }
        dropRe.find(trimmed)?.let {
            return send(C2SMessage.Drop(nextId(), "", it.groupValues[1].trim()))
        }
        useRe.find(trimmed)?.let {
            return send(C2SMessage.Use(nextId(), "", it.groupValues[1].trim(), null))
        }

        // Say shorthands: ' or " (leading only — the tail is kept verbatim).
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            return send(C2SMessage.Say(nextId(), "", trimmed.substring(1)))
        }

        // Emote prefixes.
        if (trimmed.startsWith(":") || trimmed.startsWith(";")) {
            val text = trimmed.substring(1).trim()
            return if (text.isEmpty()) MappedInput.Ignore
            else send(C2SMessage.Emote(nextId(), "", text))
        }

        // tell/whisper/> ride as Say(raw): the zone's session parser turns
        // "tell <name> <msg>" into a directed tell (handleWebSocketTell) and
        // echoes ui.tell_sent back.
        if (trimmed.startsWith(">") || lower.startsWith("tell ") || lower.startsWith("whisper ")) {
            return send(C2SMessage.Say(nextId(), "", trimmed))
        }

        emoteRe.find(trimmed)?.let {
            return send(C2SMessage.Emote(nextId(), "", it.groupValues[1].trim()))
        }
        sayRe.find(trimmed)?.let {
            return send(C2SMessage.Say(nextId(), "", it.groupValues[1]))
        }

        // Default: forward as a generic Command — the zone re-parses it through
        // the SAME CommandParser SSH/CLI uses (map/where/nearby/rooms/path/
        // exits/score/… behave exactly like ssh; truly unknown verbs fall to
        // room speech THERE, by the zone's rules, not the client's guess).
        val parts = trimmed.split(Regex("\\s+"))
        return send(C2SMessage.Command(nextId(), parts[0], parts.drop(1)))
    }
}

/**
 * The live-session S2C render contract — what a frame must paint into the
 * prose stream. The other half of `clients/parity/parity.json`.
 */
object SessionS2CRenderer {
    data class Render(
        val prose: List<Pair<String, String>>,
        /** Non-null → the screen must adopt this room (header/exits/entities/hints). */
        val room: RoomSnapshot?,
    )

    fun render(msg: S2CMessage): Render = when (msg) {
        is S2CMessage.RoomState ->
            // PRINT the room, not just the header — a silent room_state made a
            // tunneled `look` produce nothing visible (2026-07-25).
            Render(listOf("narrator" to "${msg.room.name}\n${msg.room.description}"), msg.room)
        is S2CMessage.Prose -> Render(listOf(msg.speaker to msg.text), null)
        is S2CMessage.StateChange -> Render(listOf("narrator" to msg.description), null)
        is S2CMessage.Error -> Render(listOf("system" to "Error: ${msg.message}"), null)
        is S2CMessage.MapData ->
            if (msg.textMap.isNotBlank()) Render(listOf("system" to msg.textMap), null)
            else Render(emptyList(), null)
        else -> Render(emptyList(), null)
    }
}
