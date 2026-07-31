package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.room.RoomAuthority;
import org.wyrdsekai.scripting.api.McpGrantAdminProvider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Steward-facing administration of MCP-tool grants ( MCP_TOOL).
 *
 * <p>The grantable resource is household-owned — {@code home://{ownerDid}/
 * mcp-tool/{serviceId}} — so the steward (the resource owner) issues grants on
 * an agent's behalf. Grants are service-level: a grant on a service authorizes
 * all of its tools. {@code subject = "everyone"} maps to the {@code public}
 * subject (all household agents). Pairs with {@link McpGrantCheck#stewardOwned}:
 * the check that {@code McpGatewayService}/{@code McpServerManager} consult reads
 * exactly the grants this admin writes.</p>
 *
 * <p>Every mutating (and listing) call is gated on household-administrator
 * authority via {@link RoomAuthority} — the steward is granted it at boot.</p>
 */
public final class McpGrantAdmin implements McpGrantAdminProvider {

    private static final Logger log = LoggerFactory.getLogger(McpGrantAdmin.class);

    private static volatile McpGrantAdminProvider instance;

    /** Install the process-wide grant admin (server startup, after HomeClient + steward resolve). */
    public static void install(McpGrantAdminProvider provider) {
        instance = provider;
    }

    /** The installed grant admin, or null before server startup wires it. */
    public static McpGrantAdminProvider installed() {
        return instance;
    }

    private final HomeClient homeClient;
    private final String ownerDid;
    private final McpServiceRegistry registry;

    public McpGrantAdmin(HomeClient homeClient, String ownerDid, McpServiceRegistry registry) {
        this.homeClient = homeClient;
        this.ownerDid = ownerDid;
        this.registry = registry;
    }

    @Override
    public List<Map<String, Object>> services(String actorId) {
        if (!isAdmin(actorId)) return List.of();
        var issued = safeListIssued();
        var out = new ArrayList<Map<String, Object>>();
        for (var id : registry.serviceIds()) {
            var cfg = registry.get(id).orElse(null);
            var grantedSubjects = issued.stream()
                .filter(this::isMcpGrant)
                .filter(g -> id.equals(g.resource().id()))
                .map(Grant::subject)
                .distinct()
                .toList();
            var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("enabled", cfg != null && cfg.enabled());
            row.put("transport", cfg != null && cfg.transport() != null ? cfg.transport() : "");
            row.put("granted", grantedSubjects);
            // Pre-joined for GraalJS display (foreign lists have no Array.join).
            row.put("grantedText", grantedSubjects.isEmpty()
                ? "— none —" : String.join(", ", grantedSubjects));
            out.add(row);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> grants(String actorId) {
        if (!isAdmin(actorId)) return List.of();
        return safeListIssued().stream()
            .filter(this::isMcpGrant)
            .map(g -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("subject", g.subject());
                m.put("service", g.resource().id());
                return (Map<String, Object>) m;
            })
            .toList();
    }

    @Override
    public Map<String, Object> grant(String actorId, String subject, String serviceId) {
        var gate = requireAdmin(actorId);
        if (gate != null) return gate;
        var subj = normalizeSubject(subject);
        if (subj == null) return err("missing subject (an agent's name, or 'everyone')");
        if (serviceId == null || serviceId.isBlank()) return err("missing service id");
        if (registry.get(serviceId).isEmpty()) return err("unknown service: " + serviceId);
        try {
            var resource = ResourceUri.of(ownerDid, ResourceTypeRegistry.MCP_TOOL, serviceId);
            homeClient.issueOrReplace(ownerDid, subj, resource, Capability.use,
                Map.of(), null, "steward MCP grant via Study");
            log.info("MCP grant issued: subject={} service={} (owner={})", subj, serviceId, ownerDid);
            return ok(subj, serviceId, "granted");
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    @Override
    public Map<String, Object> revoke(String actorId, String subject, String serviceId) {
        var gate = requireAdmin(actorId);
        if (gate != null) return gate;
        var subj = normalizeSubject(subject);
        if (subj == null || serviceId == null || serviceId.isBlank()) {
            return err("need both a subject and a service id");
        }
        try {
            var resource = ResourceUri.of(ownerDid, ResourceTypeRegistry.MCP_TOOL, serviceId);
            boolean removed = homeClient.revokeByKey(ownerDid, subj, resource, Capability.use);
            log.info("MCP grant revoke: subject={} service={} removed={}", subj, serviceId, removed);
            var m = ok(subj, serviceId, removed ? "revoked" : "no matching grant");
            m.put("ok", removed);
            return m;
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    // ── helpers ──

    private boolean isMcpGrant(Grant g) {
        // Active only — listIssuedBy returns revoked grants too, and a revoked
        // grant must not show as still-held in the Tool Warden view.
        return g.resource() != null
            && ResourceTypeRegistry.MCP_TOOL.equals(g.resource().type())
            && g.capability() == Capability.use
            && g.isActive(java.time.Instant.now());
    }

    private boolean isAdmin(String actorId) {
        return ownerDid != null && !ownerDid.isBlank()
            && RoomAuthority.canManageMcpGrants(actorId);
    }

    private Map<String, Object> requireAdmin(String actorId) {
        if (ownerDid == null || ownerDid.isBlank()) {
            return err("no household steward is configured to own MCP grants");
        }
        if (!RoomAuthority.canManageMcpGrants(actorId)) {
            return err("only the household steward may manage MCP-tool grants");
        }
        return null;
    }

    private List<Grant> safeListIssued() {
        try {
            return homeClient.listIssuedBy(ownerDid);
        } catch (Exception e) {
            log.debug("listIssuedBy failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String normalizeSubject(String s) {
        if (s == null) return null;
        var t = s.trim();
        if (t.isEmpty()) return null;
        if (t.equalsIgnoreCase("everyone") || t.equalsIgnoreCase("all")
                || t.equalsIgnoreCase("public") || t.equalsIgnoreCase("*")) {
            return Grant.PUBLIC_SUBJECT;
        }
        return t;
    }

    private static LinkedHashMap<String, Object> ok(String subject, String service, String status) {
        var m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("subject", subject);
        m.put("service", service);
        m.put("status", status);
        return m;
    }

    private static Map<String, Object> err(String message) {
        var m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("error", message == null ? "error" : message);
        return m;
    }
}
