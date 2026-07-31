package org.wyrdsekai.app.network

/**
 * Minimal transport-agnostic surface for routing companion commands from a
 * phone screen to its household server. The two impls are:
 *
 *   - [ServerClient]      — legacy HTTP/REST (commonMain). Pending deletion
 *                            once Phase 6 lands; kept temporarily so tests
 *                            that construct it directly keep compiling.
 *   - NatsServerClient    — NATS WebSocket+TLS over the household relay
 *                            (androidMain only — jnats is JVM-only).
 *
 * [LocalRoomScreen] consumes only this interface, so the choice of transport
 * lives entirely in [NodeManager.start].
 *
 */
interface PhoneRemoteClient {
    /**
     * Deliver an in-zone or cross-zone tell. Cross-zone routing is decided
     * server-side via CrossZoneTellService. The reply text on success is a
     * short ack ("Delivered to ...") and the screen renders it as narrator
     * prose.
     */
    suspend fun tell(target: String, message: String): ServerClient.McpResult

    /**
     * Run a free-form command. The HTTP path posts to /api/mcp/do with the
     * raw text. The NATS path parses the verb (`say library search …`,
     * `say journal …`, etc.) and fans out to the right NATS subject.
     */
    suspend fun doCommand(command: String): ServerClient.McpResult
}
