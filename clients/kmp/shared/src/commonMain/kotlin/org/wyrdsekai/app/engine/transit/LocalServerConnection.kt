package org.wyrdsekai.app.engine.transit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.wyrdsekai.app.engine.PhoneNode
import org.wyrdsekai.app.engine.PhoneNodeEvent
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage

/**
 * In-process [ServerConnection] backed by the local [PhoneNode].
 *
 * the phone interface is a terminal that speaks the
 * C2S/S2C session protocol to *a node*. This is the LocalTransport: the same
 * protocol the zone's WebSocket server speaks, answered by the on-device node.
 *
 * Offline mode points the terminal here; remote modes point it at a WebSocket-
 * or tunnel-backed [ServerConnection]. The terminal cannot tell the difference —
 * that is the whole point. It never calls `phoneNode.*` directly.
 *
 * Inbound C2S envelopes are dispatched to PhoneNode methods; PhoneNode's
 * notification stream is mapped back to S2C envelopes for the terminal.
 */
class LocalServerConnection(
    private val node: PhoneNode,
    scope: CoroutineScope,
    private val playerId: String = "player",
    private val playerName: String = "You",
) : ServerConnection {

    override val isConnected: Boolean get() = true

    private val handlers = mutableListOf<(S2CMessage) -> Unit>()
    private var seq = 0L
    private val pumpJob: Job

    init {
        // Pump PhoneNode notifications → S2C envelopes, exactly as a remote
        // zone would stream S2C frames down the wire.
        pumpJob = scope.launch {
            node.notifications.collect { ev -> mapEvent(ev)?.let { emit(it) } }
        }
    }

    private fun nextSeq(): Long = ++seq

    private fun emit(msg: S2CMessage) {
        for (h in handlers.toList()) h(msg)
    }

    override suspend fun send(message: C2SMessage) {
        when (message) {
            is C2SMessage.Say -> node.say(playerId, playerName, message.text)
            is C2SMessage.Emote -> node.emote(playerId, playerName, message.text)
            is C2SMessage.Go -> node.go(playerId, playerName, message.direction)
            is C2SMessage.Take -> node.take(playerId, message.objectName)
            is C2SMessage.Drop -> node.drop(playerId, message.objectName)
            is C2SMessage.Use -> node.use(playerId, message.objectName, message.target)
            is C2SMessage.Look -> node.look()?.let { emit(S2CMessage.RoomState(nextSeq(), it)) }
            is C2SMessage.Command -> dispatchCommand(message)
            // hint_select / reconnect / set_preference / map_request / voice —
            // not meaningful against the local node yet; no-op (a real zone handles them).
            else -> {}
        }
    }

    /**
     * Generic command envelope — the terminal sends MUD verbs that have no
     * dedicated C2S type (examine / inventory / …) as a [C2SMessage.Command].
     * The local node answers them the same way the zone would.
     */
    private suspend fun dispatchCommand(cmd: C2SMessage.Command) {
        when (cmd.command.lowercase()) {
            "examine", "ex" -> {
                val target = cmd.args.joinToString(" ").trim()
                val r = node.examine(target)
                if (r != null) {
                    val body = if (r.description.isNotBlank()) "${r.name}\n${r.description}" else r.name
                    emit(prose("narrator", body))
                } else {
                    emit(prose("system", "There's nothing called $target here."))
                }
            }
            "inventory", "i" -> {
                val items = node.inventory()
                emit(prose("system",
                    if (items.isEmpty()) "You aren't carrying anything."
                    else "You are carrying:\n" + items.joinToString("\n") { "  - $it" }))
            }
            else -> node.say(playerId, playerName, (listOf(cmd.command) + cmd.args).joinToString(" "))
        }
    }

    private fun prose(speaker: String, text: String): S2CMessage.Prose =
        S2CMessage.Prose(seq = nextSeq(), speaker = speaker, text = text)

    private fun mapEvent(ev: PhoneNodeEvent): S2CMessage? = when (ev) {
        is PhoneNodeEvent.Prose -> S2CMessage.Prose(nextSeq(), ev.speaker, ev.text)
        is PhoneNodeEvent.RoomChanged -> S2CMessage.RoomState(nextSeq(), ev.snapshot)
        is PhoneNodeEvent.StateChanged -> S2CMessage.StateChange(nextSeq(), ev.description)
        is PhoneNodeEvent.Error -> S2CMessage.Error(nextSeq(), ev.code, ev.message)
        // Tier / Household / StudyAction / OpenBrowser / ServerRoom* are control
        // events handled out of band by the host, not session prose.
        else -> null
    }

    override fun onMessage(handler: (S2CMessage) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun remoteRoomIds(): Set<String> = node.activeRoomIds()

    /** Stop pumping node notifications. */
    fun close() {
        pumpJob.cancel()
        handlers.clear()
    }
}
