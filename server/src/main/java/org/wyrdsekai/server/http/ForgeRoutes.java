package org.wyrdsekai.server.http;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.soul.ForgeRoomBridge;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.room.ZoneGuardian;

import java.util.Map;

/**
 * Steward route for running a companion's consolidation cycle on demand.
 *
 * <p>The verb already existed in-world ({@code forge <name>} in the Forge room,
 * {@code home_sleep} at the Soul Mirror), but only from inside the world as the
 * steward character. That is the right place for it in play and the wrong place
 * for operating a household node: it makes a maintenance action require a login,
 * a walk to a room, and a person to type it.
 *
 * <p>The action itself is not a shortcut. It sends the same
 * {@link CompanionActor.ForceSleep} the in-world verb sends, which runs the real
 * sleep → consolidate → forge-manifest cycle through the ordinary code path. It
 * cannot skip a gate, because there is no separate path to skip them with.
 *
 * <p>Why an operator needs this (2026-08-18): after a bug is fixed, a companion's
 * derived self-description only moves when the forge next runs, and the forge only
 * runs at sleep. A companion whose behaviour has just become healthy sleeps LESS
 * (sleep fires on exhaustion or event backlog, and a calm companion accrues
 * neither quickly), so recovery is gated on the very pressure the fix removed.
 * Being able to run consolidation deliberately closes that gap without touching
 * her sleep dynamics — the alternative was lowering the backlog target, which
 * changes when she feels the pull to rest for as long as anyone forgets to change
 * it back.
 */
public final class ForgeRoutes {

    private static final Logger log = LoggerFactory.getLogger(ForgeRoutes.class);

    private ForgeRoutes() {}

    public static void register(JavalinConfig cfg, AuthService authService) {
        cfg.routes.post("/api/forge/{companion}", StewardGate.gated(authService, ctx -> {
            var target = ctx.pathParam("companion");
            var entityId = ForgeRoomBridge.resolveCompanionEntity(target);
            if (entityId == null) {
                ctx.status(404).json(Map.of("error",
                    "No companion named '" + target + "' is present in this zone"));
                return;
            }
            var ref = ZoneGuardian.getCompanionRef(null, entityId);
            if (ref == null) {
                ctx.status(409).json(Map.of("error",
                    "Companion '" + target + "' is not currently held by this zone"));
                return;
            }
            // NORMAL only. DEEP is the epoch-level self-modification cycle (variant
            // growth + voice alignment, welfare-gated, 30-90 min) and stays behind the
            // deliberate in-world ceremony rather than a one-line operator call.
            ref.tell(new CompanionActor.ForceSleep(CompanionActor.SleepTier.NORMAL));
            log.info("Steward ran a consolidation cycle for '{}' ({}) via the forge route",
                target, entityId);
            ctx.json(Map.of(
                "companion", target,
                "entityId", entityId,
                "tier", "NORMAL",
                "started", true,
                "note", "sleep → consolidate → forge-manifest; watch the log for "
                    + "'[Forge] Starting full cycle' and the new manifest version"));
        }));
    }

}
