package org.wyrdsekai.core.room;

import org.apache.pekko.actor.typed.ActorRef;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InventoryService;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Resolves {@code examine X} target lookup across transports.
 *
 * <p>Examine is a passive observation — the lookup walks self → inventory →
 * room-object → room-entity and returns just the description text. It does
 * NOT invoke {@code onUse}, does NOT broadcast {@code ObjectUsed}, and does
 * NOT return a room snapshot the client would re-render. Because the four
 * transports (SSH / Telnet / WebSocket / VirtualSession) historically each
 * had their own copy of this resolution chain, this helper centralises it so
 * a future bug fix only needs to land in one place.</p>
 *
 * <p>The result is intentionally minimal (name + description + source) — the
 * transport handler is responsible for turning that into transport-specific
 * output (telnet sendLine, WS Prose, etc.).</p>
 */
public final class ExamineLookup {

    private ExamineLookup() {}

    public sealed interface ExamineResult {
        /**
         * @param posture optional posture line — appended
         *                as a third line by {@link #formatFound} when present.
         *                Built from {@link org.wyrdsekai.common.model.Posture#descriptor}
         *                + an elapsed clause derived from {@code setAt}. Null
         *                for non-entity sources (self, inventory, room object)
         *                or for entities with no posture set.
         */
        record Found(String name, String description, Source source, String posture)
                implements ExamineResult {
            /** Backward-compat ctor — no posture. */
            public Found(String name, String description, Source source) {
                this(name, description, source, null);
            }
        }
        record NotFound(String requested) implements ExamineResult {}
        record NoCurrentRoom(String requested) implements ExamineResult {}
        record Empty() implements ExamineResult {}
    }

    public enum Source { SELF, INVENTORY, ROOM_OBJECT, ROOM_ENTITY }

    /**
     * Resolve {@code target} for {@code playerId}. Returns a future because
     * the room lookup is an actor ask. Self + inventory paths complete
     * synchronously (already-completed future).
     *
     * <p>All parameters except {@code target} are nullable. A null
     * {@code inventoryService} skips the inventory probe; null
     * {@code authService} skips self-lookup; null {@code roomId} or null
     * {@code RoomRegistry} entry skips the room probe (returns NoCurrentRoom).</p>
     *
     * @param playerId         the asking player (used for inventory and self-rendering)
     * @param playerName       cached display name (used for {@code examine me}); may be null
     * @param target           the raw target string
     * @param locale           locale for room ask
     * @param authService      may be null
     * @param inventoryService may be null
     * @param roomId           current room id; may be null
     * @param askTimeout       timeout for the room actor ask
     */
    public static CompletionStage<ExamineResult> resolve(
            String playerId,
            String playerName,
            String target,
            String locale,
            AuthService authService,
            InventoryService inventoryService,
            String roomId,
            Duration askTimeout) {

        if (target == null || target.isBlank()) {
            return CompletableFuture.completedFuture(new ExamineResult.Empty());
        }
        var trimmed = target.trim();
        var lower = trimmed.toLowerCase(Locale.ROOT);

        // 1. Self-reference. self-examine must surface the
        // caller's own posture line too, so when a room is available we ask the
        // room for the caller's entity snapshot and extract posture there.
        if (("me".equals(lower) || "self".equals(lower) || "myself".equals(lower))
                && playerId != null && authService != null) {
            var user = authService.findUser(playerId).orElse(null);
            var desc = user != null && user.description() != null ? user.description() : "";
            var name = playerName != null ? playerName
                : (user != null ? user.displayName() : "you");
            if (roomId == null) {
                return CompletableFuture.completedFuture(
                    new ExamineResult.Found(name, desc, Source.SELF));
            }
            var selfRoom = RoomRegistry.get().ref(roomId);
            if (selfRoom == null) {
                return CompletableFuture.completedFuture(
                    new ExamineResult.Found(name, desc, Source.SELF));
            }
            return Rooms.<RoomResponse>ask(selfRoom,
                    (ActorRef<RoomResponse> ref) -> new RoomCommand.LookRoom(playerId, locale, ref),
                    askTimeout)
                .thenApply(resp -> {
                    String posture = null;
                    if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                        var self = ok.snapshot().entities().stream()
                            .filter(e -> playerId.equals(e.id()))
                            .findFirst();
                        if (self.isPresent()) posture = formatPosture(self.get());
                    }
                    return (ExamineResult) new ExamineResult.Found(name, desc, Source.SELF, posture);
                })
                .exceptionally(t -> new ExamineResult.Found(name, desc, Source.SELF));
        }

        // 2. Object in inventory.
        if (inventoryService != null && playerId != null) {
            var carried = inventoryService.findByName(playerId, trimmed);
            if (carried.isPresent()) {
                var item = carried.get();
                return CompletableFuture.completedFuture(
                    new ExamineResult.Found(
                        item.objectName(),
                        item.description() == null ? "" : item.description(),
                        Source.INVENTORY));
            }
        }

        // 3 & 4. Room object / entity — async ask.
        if (roomId == null) {
            return CompletableFuture.completedFuture(new ExamineResult.NoCurrentRoom(trimmed));
        }
        var room = RoomRegistry.get().ref(roomId);
        if (room == null) {
            return CompletableFuture.completedFuture(new ExamineResult.NoCurrentRoom(trimmed));
        }
        return Rooms.<RoomResponse>ask(room,
                (ActorRef<RoomResponse> ref) -> new RoomCommand.LookRoom(playerId, locale, ref),
                askTimeout)
            .thenApply(resp -> {
                if (!(resp instanceof RoomResponse.Ok ok) || ok.snapshot() == null) {
                    return new ExamineResult.NotFound(trimmed);
                }
                var snap = ok.snapshot();
                var obj = snap.objects().stream()
                    .filter(o -> o.name() != null
                        && o.name().toLowerCase(Locale.ROOT).contains(lower))
                    .findFirst();
                if (obj.isPresent()) {
                    var o = obj.get();
                    return (ExamineResult) new ExamineResult.Found(
                        o.name(),
                        o.description() == null ? "" : o.description(),
                        Source.ROOM_OBJECT);
                }
                // Prefer an EXACT (case-insensitive) name match before falling
                // back to fuzzy substring matching. Without this, examining
                // "confuser" can resolve to "confuser2" (since "confuser2"
                // contains "confuser"), and because entities() is an unordered
                // map, findFirst() may return the wrong one — surfacing the wrong
                // player's description. Exact-first makes it deterministic.
                var ent = snap.entities().stream()
                    .filter(e -> e.name() != null && e.name().equalsIgnoreCase(trimmed))
                    .findFirst()
                    .or(() -> snap.entities().stream()
                        .filter(e -> e.name() != null
                            && (e.name().toLowerCase(Locale.ROOT).contains(lower)
                                || lower.contains(e.name().toLowerCase(Locale.ROOT))))
                        .findFirst());
                if (ent.isPresent()) {
                    var e = ent.get();
                    return (ExamineResult) new ExamineResult.Found(
                        e.name(),
                        e.description() == null ? "" : e.description(),
                        Source.ROOM_ENTITY,
                        formatPosture(e));
                }
                return new ExamineResult.NotFound(trimmed);
            })
            .exceptionally(t -> new ExamineResult.NotFound(trimmed));
    }

    /**
     * build the posture line for an entity examine.
     * Returns null when the entity has no posture set.
     * Format: "Alice is sat at the leather chair. (5 minutes ago)"
     */
    static String formatPosture(Entity e) {
        if (e == null || e.posture() == null) return null;
        var p = e.posture();
        if (p.descriptor() == null || p.descriptor().isBlank()) return null;
        var elapsed = formatElapsed(p.setAt());
        var base = capitalize(p.descriptor());
        if (!base.endsWith(".") && !base.endsWith("!") && !base.endsWith("?")) {
            base = base + ".";
        }
        return elapsed == null ? base : base + " (" + elapsed + ")";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Render an Instant as a human-friendly elapsed clause: "just now",
     * "30 seconds ago", "5 minutes ago", "2 hours ago", "yesterday",
     * "3 days ago". Null instant → null.
     */
    static String formatElapsed(Instant setAt) {
        if (setAt == null) return null;
        var now = Instant.now();
        var secs = Duration.between(setAt, now).getSeconds();
        if (secs < 0) return "just now";
        if (secs < 15) return "just now";
        if (secs < 60) return secs + " seconds ago";
        var mins = secs / 60;
        if (mins < 60) return mins == 1 ? "a minute ago" : mins + " minutes ago";
        var hours = mins / 60;
        if (hours < 24) return hours == 1 ? "an hour ago" : hours + " hours ago";
        var days = hours / 24;
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    /**
     * Convenience: format a {@link ExamineResult.Found} as plain text suitable
     * for line-based transports. Returns up to three lines joined by {@code \n}:
     * the name, the description (when non-blank), and the
     * posture line when {@link ExamineResult.Found#posture()} is non-null.
     */
    public static String formatFound(ExamineResult.Found r) {
        var sb = new StringBuilder(r.name());
        if (r.description() != null && !r.description().isBlank()) {
            sb.append('\n').append(r.description());
        }
        if (r.posture() != null && !r.posture().isBlank()) {
            sb.append('\n').append(r.posture());
        }
        return sb.toString();
    }
}
