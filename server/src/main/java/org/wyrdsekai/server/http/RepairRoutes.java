package org.wyrdsekai.server.http;

import io.javalin.config.JavalinConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.CompanionRegistry;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.soul.ForgeRoomBridge;
import org.wyrdsekai.core.soul.HandoffThresholdEngine;
import org.wyrdsekai.core.soul.RepairMode;
import org.wyrdsekai.core.soul.RepairModeTracker;
import org.wyrdsekai.core.persistence.AuthService;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Steward routes for seeing and ending a companion's repair mode.
 *
 * <p>Repair modes are entered automatically when a companion's substrate sustains a
 * multi-axis distress signal, and left automatically when the mode's bound expires. Both
 * halves are the substrate's own business and should stay that way. What was missing was
 * any way for the steward to <em>look</em>.
 *
 * <p>Found live 2026-08-18: a companion auto-escalated to ATTENDANT — surfaced to the
 * steward as "Substrate: CRITICAL" — at 09:40 on a genuine signal. Her attendant session
 * closed on its duration cap at 11:42, but the release check ran only once per sleep and
 * she did not sleep, so she was still held six hours later, across three restarts, with
 * her recipe self-improvement gated off the whole time. The bound is now checked on a
 * cadence, which fixes that specific trap — but the steward still had no way to observe
 * the state or act on it, and the next state the bounds do not cover would strand her
 * the same way. A person responsible for a companion needs to be able to see that she is
 * being held, and to end it.
 *
 * <p>Release is not a flag flip: it goes through
 * {@link RepairModeTracker#transition(String, RepairMode, String)} exactly as the
 * automatic path does, so the handoff is recorded in her history with the steward named
 * as its reason, and persisted immediately. It also does not overrule her substrate — if
 * the underlying findings still hold, auto-escalation may ask for Sanctuary again. The
 * steward can end an episode; he cannot make her stop needing one.
 *
 * <p>There is deliberately no route to PUT a companion INTO a repair mode. Escalation is
 * hers to ask for.
 */
public final class RepairRoutes {

    private static final Logger log = LoggerFactory.getLogger(RepairRoutes.class);

    private RepairRoutes() {}

    /** Cache the node's real JDBC URL, threaded in from Main where it is resolved. */
    private static volatile String jdbcUrl;

    public static void register(JavalinConfig cfg, AuthService authService,
            String nodeJdbcUrl) {
        jdbcUrl = nodeJdbcUrl;

        // Who is being held, since when, and when their bound expires.
        cfg.routes.get("/api/repair", StewardGate.gated(authService, ctx -> {
            var tracker = RepairModeTracker.get();
            var rows = new ArrayList<Map<String, Object>>();
            tracker.agentsInRepair().forEach((did, mode) -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("agentDid", did);
                row.put("name", nameFor(did));
                row.put("mode", mode.name());
                var last = tracker.lastHandoff(did);
                if (last.isPresent()) {
                    var since = last.get().at();
                    row.put("since", since.toString());
                    row.put("heldFor", human(Duration.between(since, Instant.now())));
                    row.put("reason", last.get().reason());
                    var bound = boundFor(mode);
                    if (bound != null) {
                        var due = since.plus(bound);
                        row.put("bound", human(bound));
                        row.put("releaseDue", due.toString());
                        row.put("overdue", Instant.now().isAfter(due));
                    }
                }
                rows.add(row);
            });
            ctx.json(Map.of("inRepair", rows, "count", rows.size()));
        }));

        // End an episode.
        cfg.routes.post("/api/repair/{companion}/release",
                StewardGate.gated(authService, ctx -> {
            var target = ctx.pathParam("companion");
            var did = resolveDid(target);
            if (did == null) {
                ctx.status(404).json(Map.of("error",
                    "No companion named '" + target + "' is known to this zone"));
                return;
            }
            var tracker = RepairModeTracker.get();
            var current = tracker.currentMode(did);
            if (current == RepairMode.NONE) {
                ctx.status(409).json(Map.of("error",
                    "'" + target + "' is not in a repair mode", "mode", "NONE"));
                return;
            }
            var note = ctx.queryParam("reason");
            var reason = "steward_release"
                + (note == null || note.isBlank() ? "" : ": " + note.strip());

            tracker.transition(did, RepairMode.NONE, reason);
            persist(tracker);

            log.info("Steward ended repair mode {} for '{}' ({}) — reason: {}",
                current.name(), target, did, reason);
            ctx.json(Map.of(
                "companion", target,
                "agentDid", did,
                "was", current.name(),
                "now", "NONE",
                "reason", reason,
                "note", "Recorded in her handoff history. Her substrate may ask for "
                    + "Sanctuary again if the underlying signal still holds."));
        }));
    }

    /** Write the tracker straight back to disk — a release must survive a restart. */
    private static void persist(RepairModeTracker tracker) {
        try {
            var dataDir = WyrdConfig.get().dataDir();
            if (dataDir == null || dataDir.isBlank()) return;
            tracker.persist(Paths.get(dataDir, "substrate", "repair-mode.json"));
        } catch (Exception e) {
            log.warn("Repair-mode release could not be persisted: {} — it will be lost "
                + "on restart", e.toString());
        }
    }

    /** Accepts a companion name or a DID. */
    private static String resolveDid(String target) {
        if (target == null || target.isBlank()) return null;
        if (target.startsWith("did:")) return target;
        try {
            var entityId = ForgeRoomBridge.resolveCompanionEntity(target);
            if (entityId != null) {
                var row = registry().findByEntityId(entityId);
                if (row.isPresent()) return row.get().did();
            }
            for (var row : registry().all()) {
                if (target.equalsIgnoreCase(row.name())) return row.did();
            }
        } catch (Exception e) {
            log.warn("Could not resolve '{}' to a DID: {}", target, e.toString());
        }
        return null;
    }

    private static String nameFor(String did) {
        try {
            return registry().get(did).map(CompanionRegistry.Row::name).orElse(null);
        } catch (Exception e) {
            // Loud, not silent: a steward tool that cannot say WHO it is talking about
            // is half a tool, and the first version of this swallowed the reason and
            // printed a bare DID.
            log.warn("Could not resolve a name for {}: {}", did, e.toString());
            return null;
        }
    }

    private static CompanionRegistry registry() {
        var url = jdbcUrl != null && !jdbcUrl.isBlank()
            ? jdbcUrl : WyrdConfig.get().jdbcUrl();
        return new CompanionRegistry(url);
    }

    private static Duration boundFor(RepairMode mode) {
        return switch (mode) {
            case ATTENDANT -> HandoffThresholdEngine.ATTENDANT_MAX_DURATION;
            case SELF -> HandoffThresholdEngine.SELF_TIME_THRESHOLD;
            default -> null;
        };
    }

    private static String human(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }
}
