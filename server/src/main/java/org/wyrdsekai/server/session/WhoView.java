package org.wyrdsekai.server.session;

import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.home.Residency;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.persistence.AuthService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Renders the {@code who} command output with zone-wide visibility +
 * permission-aware filtering.
 *
 * <p>Visibility tiers:</p>
 * <ul>
 *   <li><b>Steward</b>: sees all residents (online + offline), their rooms
 *       or offline state, agents, and a visitor count.</li>
 *   <li><b>Resident (member)</b>: sees other residents — online status,
 *       rooms if shared, children's rooms hidden.</li>
 *   <li><b>Visitor</b> (not a resident of this zone): only the current
 *       room's occupants; zone-wide roster is hidden.</li>
 *   <li><b>Child subject</b>: their room location is never shown to
 *       peers, only to stewards. Offline state still shown (names in the
 *       roster) but no location leak.</li>
 * </ul>
 */
public final class WhoView {

    private WhoView() {}

    public record Context(
        String observerId,
        String observerRole,
        String zoneId,
        String currentRoomId,
        List<String> currentRoomEntityNames,
        ClientConnectionRegistry connections,
        AuthService auth
    ) {}

    public static List<String> render(Context ctx) {
        var out = new ArrayList<String>();

        // Always show current room — orienting info for everyone.
        if (ctx.currentRoomEntityNames() != null && !ctx.currentRoomEntityNames().isEmpty()) {
            out.add("Here: " + String.join(", ", dedup(ctx.currentRoomEntityNames())));
        }

        var store = ResidencyStore.get();
        if (store == null || ctx.zoneId() == null) {
            // Minimal degrade: just session count.
            if (ctx.connections() != null) {
                out.add("Online: " + ctx.connections().size() + " session(s)");
            }
            return out;
        }

        boolean isSteward = "steward".equals(ctx.observerRole());
        boolean isResident = store.isResident(ctx.observerId(), ctx.zoneId());

        if (!isResident && !isSteward) {
            // Visitor path: hide zone-wide roster, just show session count.
            if (ctx.connections() != null) {
                out.add("(visiting " + ctx.zoneId() + " — zone roster hidden)");
            }
            return out;
        }

        var residents = store.listByZone(ctx.zoneId());
        if (!residents.isEmpty()) {
            out.add("");
            out.add("In " + ctx.zoneId() + ":");
            for (var r : residents) {
                out.add(renderResidentLine(r, ctx, isSteward));
            }
        }

        // Visitor count: sessions that aren't residents.
        if (ctx.connections() != null) {
            var residentIds = new HashSet<String>();
            for (var r : residents) residentIds.add(r.did());
            int visitors = 0;
            for (var c : ctx.connections().all()) {
                if (c.playerId() != null && !residentIds.contains(c.playerId())) visitors++;
            }
            if (visitors > 0) {
                out.add("Visitors from other zones: " + visitors);
            }
        }
        return out;
    }

    private static String renderResidentLine(Residency r, Context ctx, boolean observerIsSteward) {
        var isSelf = r.did().equals(ctx.observerId());
        var isChildSubject = Residency.ROLE_CHILD.equals(r.role());

        var session = ctx.connections() != null
            ? ctx.connections().findByPlayerId(r.did())
            : Optional.<ClientConnection>empty();
        var online = session.isPresent();

        var display = displayName(ctx.auth(), r.did());

        // Location: steward sees everything, peers see everything except
        // child subjects' location (hidden as a safety floor).
        String location;
        if (!online) {
            location = "offline";
        } else if (isChildSubject && !isSelf && !observerIsSteward) {
            location = "online";  // child's location hidden from peers
        } else {
            var entityRegistry = EntityRegistry.get();
            var roomOf = entityRegistry != null ? entityRegistry.roomOf(r.did()) : Optional.<String>empty();
            if (roomOf.isEmpty()) {
                location = "online";
            } else if (roomOf.get().equals(ctx.currentRoomId())) {
                location = "here";
            } else if (roomOf.get().startsWith("study-")) {
                location = "in their study";
            } else {
                // Show raw roomId — cheap, stable, no async name lookup.
                // Renderers can pretty-print if needed.
                location = roomOf.get();
            }
        }

        var marker = online ? "\u25CF" : "\u25CB";  // ●/○
        var tag = r.role() + (isSelf ? ", you" : "");
        return String.format("  %s %-16s (%s) \u2014 %s",
            marker, display, tag, location);
    }

    private static String displayName(AuthService auth, String did) {
        if (auth == null) return did;
        return auth.findUser(did)
            .map(u -> {
                var dn = u.displayName();
                return (dn == null || dn.isBlank()) ? u.username() : dn;
            })
            .orElse(did);
    }

    private static List<String> dedup(List<String> in) {
        var seen = new LinkedHashSet<String>();
        seen.addAll(in);
        return new ArrayList<>(seen);
    }
}
