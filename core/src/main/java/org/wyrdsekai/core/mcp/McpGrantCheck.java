package org.wyrdsekai.core.mcp;

import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.home.HomeClient;

import java.util.Map;

/**
 * Authorization gate for MCP tool invocations, backed by Grants on
 * {@code home://{caller}/mcp-tool/{server}/{tool}} ( MCP_TOOL).
 *
 * <p>The caller is the Home owner (user or agent) on whose authority the MCP
 * call runs. Grants are self-issued by the owner — each Home owner decides
 * which MCP tools their Home may invoke on their behalf.</p>
 */
@FunctionalInterface
public interface McpGrantCheck {

    /** Does {@code callerDid} hold a use-grant on {@code {server}/{tool}}? */
    boolean canUse(String callerDid, String serverId, String toolName);

    /**
     * HomeClient-backed implementation. When no grants have ever been issued
     * for MCP tools, {@code strictMode=false} allows access by default
     * (open world); {@code strictMode=true} requires explicit grants.
     */
    static McpGrantCheck homeClientBacked(HomeClient homeClient, boolean strictMode) {
        return (callerDid, serverId, toolName) -> {
            // Permissive mode: always allow (grants are optional audit scaffolding).
            if (!strictMode) return true;
            if (callerDid == null || callerDid.isBlank()) return false;
            var resource = ResourceUri.of(callerDid, ResourceTypeRegistry.MCP_TOOL,
                serverId + "/" + toolName);
            try {
                return homeClient.check(callerDid, resource, Capability.use, Map.of());
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * Steward-controlled implementation (2026-07-20). The grantable resource is
     * owned by the HOUSEHOLD ({@code home://{ownerDid}/mcp-tool/{server}}), not
     * by the calling agent — so the steward (resource owner) can issue grants on
     * an agent's behalf, and a {@code public}-subject grant enables every
     * household agent at once. Service-level (not per-tool): a grant on a service
     * authorizes all of its tools.
     *
     * <p>{@code strict=false} keeps the open-world default (audit only). This is
     * the factory the Study "Tool Warden" issues against; see {@code McpGrantAdmin}.
     */
    static McpGrantCheck stewardOwned(HomeClient homeClient, String ownerDid, boolean strictMode) {
        return (callerId, serverId, toolName) -> {
            if (!strictMode) return true;
            if (callerId == null || callerId.isBlank()) return false;
            if (ownerDid == null || ownerDid.isBlank()) return false;
            var resource = ResourceUri.of(ownerDid, ResourceTypeRegistry.MCP_TOOL, serverId);
            try {
                // check() also matches public-subject grants on the same resource.
                return homeClient.check(callerId, resource, Capability.use, Map.of());
            } catch (Exception e) {
                return false;
            }
        };
    }
}
