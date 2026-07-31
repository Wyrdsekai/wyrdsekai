package org.wyrdsekai.core.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.room.StudyProvisioner;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * Materializes ward rows for Home rooms (Studies / agent Hearths) as Grants
 * on {@code home://{owner-did}/home-room}.
 *
 * <p>WardService remains the authority for {@code isAllowed} checks on *all*
 * rooms (foundation rooms, nexus, hand-built rooms). This sync only mirrors
 * Home-room grants/revokes so the unified grants API
 * ({@code /api/home/grants/issued}, Board/Mailbox furnishings) sees them.
 * Non-Home ward writes are ignored.</p>
 *
 * <p>Detection is via {@link StudyProvisioner#isStudyRoom}; agent Hearths
 * follow the same convention when they land. Override the
 * {@code roomIdToOwner} function to adapt.</p>
 */
public final class WardGrantSync {

    private static final Logger log = LoggerFactory.getLogger(WardGrantSync.class);

    private final HomeClient homeClient;
    private final Function<String, String> roomIdToOwner;

    public WardGrantSync(HomeClient homeClient) {
        this(homeClient, StudyProvisioner::playerIdFromStudy);
    }

    public WardGrantSync(HomeClient homeClient, Function<String, String> roomIdToOwner) {
        this.homeClient = homeClient;
        this.roomIdToOwner = roomIdToOwner;
    }

    /** Mirror a ward grant as a Grant on {@code home://{owner}/home-room}. */
    public void onGranted(String roomId, String principal, String permission, String grantedBy) {
        var owner = roomIdToOwner.apply(roomId);
        if (owner == null) return; // non-Home room — ignore
        // Only mirror gating permissions. Everything important to Home access
        // maps to capability `use` on the home-room resource.
        if (!isHomeGatingPermission(permission)) return;
        try {
            var subject = principalToDid(principal);
            var resource = ResourceUri.of(owner, ResourceTypeRegistry.HOME_ROOM);
            homeClient.issueOrReplace(
                owner, subject, resource, Capability.use,
                Map.of("ward", permission),
                null,
                "ward:" + permission + "@" + roomId);
        } catch (Exception e) {
            log.warn("WardGrantSync.onGranted {} {} {}: {}",
                roomId, principal, permission, e.getMessage());
        }
    }

    /** Revoke the mirror Grant. */
    public void onRevoked(String roomId, String principal, String permission) {
        var owner = roomIdToOwner.apply(roomId);
        if (owner == null) return;
        if (!isHomeGatingPermission(permission)) return;
        try {
            var subject = principalToDid(principal);
            var resource = ResourceUri.of(owner, ResourceTypeRegistry.HOME_ROOM);
            homeClient.revokeByKey(owner, subject, resource, Capability.use);
        } catch (Exception e) {
            log.warn("WardGrantSync.onRevoked {} {} {}: {}",
                roomId, principal, permission, e.getMessage());
        }
    }

    /** Revoke all mirror Grants for a room. */
    public void onCleared(String roomId) {
        var owner = roomIdToOwner.apply(roomId);
        if (owner == null) return;
        try {
            var resource = ResourceUri.of(owner, ResourceTypeRegistry.HOME_ROOM);
            var issued = homeClient.listIssuedBy(owner);
            for (var g : issued) {
                if (!g.resource().toString().equals(resource.toString())) continue;
                if (g.capability() != Capability.use) continue;
                if (g.scope() == null || !g.scope().containsKey("ward")) continue;
                if (g.isActive(Instant.now())) {
                    homeClient.revoke(g.id(), owner);
                }
            }
        } catch (Exception e) {
            log.warn("WardGrantSync.onCleared {}: {}", roomId, e.getMessage());
        }
    }

    /** Permissions that represent entry/interact gating (vs internal ops). */
    private static boolean isHomeGatingPermission(String permission) {
        return switch (permission) {
            case "enter", "speak", "take", "drop", "use", "build", "admin" -> true;
            default -> false;
        };
    }

    /** Translate WardService principal strings to DID-like subjects. */
    private static String principalToDid(String principal) {
        if (principal == null) return Grant.PUBLIC_SUBJECT;
        return switch (principal) {
            case "*" -> Grant.PUBLIC_SUBJECT;
            case "system" -> "did:system";
            default -> principal;
        };
    }
}
