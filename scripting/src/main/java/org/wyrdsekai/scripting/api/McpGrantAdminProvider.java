package org.wyrdsekai.scripting.api;

import java.util.List;
import java.util.Map;

/**
 * Steward-facing administration of MCP-tool grants for room scripts, via the
 * Study's "Tool Warden" furnishing (world.mcpServices / world.mcpGrant / …).
 *
 * <p>Defined in the scripting module to avoid a circular dependency (scripting
 * cannot reference core). Core provides the implementation wrapping
 * {@code McpGrantAdmin} (HomeClient-backed grants on the household-owned
 * {@code home://{steward}/mcp-tool/{service}} resource).</p>
 *
 * <p>Every method takes {@code actorId} — the acting entity — and the
 * implementation refuses unless the actor holds household-administrator
 * authority (the steward). {@code subject} is {@code "everyone"} to grant all
 * household agents at once, otherwise an agent's entity id.</p>
 */
public interface McpGrantAdminProvider {

    /** List configured MCP services with enabled/granted state. */
    List<Map<String, Object>> services(String actorId);

    /** List active MCP-tool grants (subject → service). */
    List<Map<String, Object>> grants(String actorId);

    /** Grant {@code subject} use of {@code serviceId}. Result: { ok, error }. */
    Map<String, Object> grant(String actorId, String subject, String serviceId);

    /** Revoke {@code subject}'s use of {@code serviceId}. Result: { ok, error }. */
    Map<String, Object> revoke(String actorId, String subject, String serviceId);
}
