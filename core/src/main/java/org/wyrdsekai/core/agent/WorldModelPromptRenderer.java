package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * renders the household world
 * model into a structured prompt section that {@link MentalSimulator} (M3)
 * and {@code M2PlanScorer} prepend to Drive-9B inference calls.
 *
 * <p>Three blocks:
 * <ol>
 *   <li><b>ZONE STATE MAP</b> — every room with exits, key objects, valid
 *       actions. Rendered from a {@link Collection} of {@link RoomSnapshot}
 *       supplied by the caller (typically {@code CompanionActor} which has
 *       Pekko actor refs).</li>
 *   <li><b>ACTION CONSEQUENCES</b> — observed patterns from
 *       {@link WorldModel#transitionsSnapshot()}. Aggregated by action type
 *       (not state-action key) so a frequently-seen action type gets one
 *       readable line, not 50 state-specific rows.</li>
 *   <li><b>KNOWN PATTERNS</b> — canned antipatterns (loop, premature done)
 *       plus any action types observed in the recent loop-window.</li>
 * </ol>
 *
 * <p>Stateless with optional in-memory cache. Pass the same inputs and you
 * get the same string back without re-rendering. The fingerprint covers
 * room identities + transition table size; a topology change or +10 new
 * transitions invalidates the cache.</p>
 *
 * <p>Token budget target: ≤ 4000 tokens for a 20-room household. Spec §3.</p>
 */
public final class WorldModelPromptRenderer {

    private static final Logger log = LoggerFactory.getLogger(WorldModelPromptRenderer.class);

    /** Cap on consequence lines so the prompt stays bounded. */
    private static final int MAX_CONSEQUENCE_LINES = 30;

    /** Cap on objects listed per room. Excess summarized as "+N more". */
    private static final int MAX_OBJECTS_PER_ROOM = 5;

    /** Action types with fewer than this many observations get aggregated as "rare". */
    private static final int CONSEQUENCE_OBSERVATION_FLOOR = 3;

    /** Inputs to the renderer. Pure-function shape so caching is straightforward. */
    public record Inputs(
        Collection<RoomSnapshot> rooms,
        Map<String, List<WorldModel.Transition>> transitions,
        Collection<String> canonicalActionVerbs
    ) {}

    /** Output bundle — both the rendered text and a token estimate for callers. */
    public record Rendered(String text, int approxTokens) {}

    // ── Cache ─────────────────────────────────────────────────

    private volatile Rendered cached;
    private volatile long cachedFingerprint = -1L;

    /** Reset cache. Tests + topology-change observers call this. */
    public synchronized void invalidate() {
        cached = null;
        cachedFingerprint = -1L;
    }

    /** Render with caching keyed on a fingerprint of the inputs. */
    public Rendered render(Inputs inputs) {
        var fp = fingerprint(inputs);
        var current = cached;
        if (current != null && cachedFingerprint == fp) {
            return current;
        }
        synchronized (this) {
            if (cached != null && cachedFingerprint == fp) {
                return cached;
            }
            var fresh = doRender(inputs);
            cached = fresh;
            cachedFingerprint = fp;
            log.debug("WorldModelPromptRenderer rebuilt: rooms={} transitions={} approxTokens={}",
                inputs.rooms().size(), inputs.transitions().size(), fresh.approxTokens());
            return fresh;
        }
    }

    // ── Rendering ────────────────────────────────────────────

    private Rendered doRender(Inputs inputs) {
        var sb = new StringBuilder(2048);
        renderZoneStateMap(sb, inputs.rooms());
        sb.append('\n');
        renderActionConsequences(sb, inputs.transitions());
        sb.append('\n');
        renderKnownPatterns(sb, inputs.transitions());
        var text = sb.toString();
        return new Rendered(text, approxTokenCount(text));
    }

    private void renderZoneStateMap(StringBuilder sb, Collection<RoomSnapshot> rooms) {
        sb.append("ZONE STATE MAP\n");
        if (rooms == null || rooms.isEmpty()) {
            sb.append("(no rooms loaded yet)\n");
            return;
        }
        // Stable ordering — alphabetic by name for determinism.
        var ordered = new ArrayList<>(rooms);
        ordered.sort(Comparator.comparing(r -> r.name() == null ? "" : r.name()));
        for (var room : ordered) {
            renderRoomLine(sb, room);
        }
    }

    private void renderRoomLine(StringBuilder sb, RoomSnapshot room) {
        if (room == null || room.roomId() == null) return;
        sb.append(room.name() == null ? room.roomId() : room.name())
          .append(" (").append(room.roomId()).append(")\n");

        // Exits
        if (room.exits() != null && !room.exits().isEmpty()) {
            sb.append("  exits: ");
            var first = true;
            for (Exit e : room.exits()) {
                if (!first) sb.append(", ");
                sb.append(e.direction() == null ? "?" : e.direction())
                  .append(" → ")
                  .append(e.targetRoom() == null ? "?" : e.targetRoom());
                first = false;
            }
            sb.append('\n');
        }

        // Objects (capped)
        if (room.objects() != null && !room.objects().isEmpty()) {
            sb.append("  contains: ");
            var objects = room.objects();
            var shown = Math.min(objects.size(), MAX_OBJECTS_PER_ROOM);
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(", ");
                var obj = objects.get(i);
                sb.append(obj.name() == null ? obj.id() : obj.name());
            }
            if (objects.size() > shown) {
                sb.append(" (+").append(objects.size() - shown).append(" more)");
            }
            sb.append('\n');
        }

        // Entities (only listed if any — usually transient)
        if (room.entities() != null && !room.entities().isEmpty()) {
            sb.append("  entities present: ").append(room.entities().size()).append('\n');
        }
    }

    private void renderActionConsequences(StringBuilder sb, Map<String, List<WorldModel.Transition>> transitions) {
        sb.append("ACTION CONSEQUENCES (observed)\n");
        if (transitions == null || transitions.isEmpty()) {
            sb.append("(no transitions observed yet — companion is fresh)\n");
            return;
        }

        // Aggregate by actionType: count, success_rate, state_change_rate, recent outcome text.
        Map<String, Aggregate> agg = new LinkedHashMap<>();
        for (var entry : transitions.entrySet()) {
            for (var t : entry.getValue()) {
                if (t.actionType() == null) continue;
                agg.computeIfAbsent(t.actionType(), Aggregate::new).accept(t);
            }
        }

        // Sort by observation count desc, drop bottom (rare) types.
        var lines = new ArrayList<Aggregate>(agg.values());
        lines.sort((a, b) -> Integer.compare(b.count, a.count));

        var rendered = 0;
        var rareCount = 0;
        for (var a : lines) {
            if (rendered >= MAX_CONSEQUENCE_LINES) break;
            if (a.count < CONSEQUENCE_OBSERVATION_FLOOR) {
                rareCount++;
                continue;
            }
            sb.append(a.toLine()).append('\n');
            rendered++;
        }
        if (rareCount > 0) {
            sb.append("(+").append(rareCount).append(" rarely-observed action types omitted)\n");
        }
    }

    private void renderKnownPatterns(StringBuilder sb, Map<String, List<WorldModel.Transition>> transitions) {
        sb.append("KNOWN PATTERNS\n");
        // Canned antipatterns — always emitted so the simulator knows what to avoid.
        sb.append("loop antipattern: same action repeated 3+ times without state change → AVOID\n");
        sb.append("premature done: goal_done called before content delivered (tell_agent / write_journal) → AVOID\n");
        sb.append("missing prereq: read_content with no prior search → AVOID (search first)\n");

        // Empirical: any action type that appears with high failure rate in observed history.
        if (transitions != null) {
            for (var entry : transitions.entrySet()) {
                int total = entry.getValue().size();
                if (total < 5) continue; // need enough data
                int fails = (int) entry.getValue().stream().filter(t -> !t.success()).count();
                if (fails * 2 >= total) {
                    var first = entry.getValue().get(0);
                    sb.append("high-failure: ").append(first.actionType())
                      .append("(").append(first.actionTarget()).append(") — fails ")
                      .append(fails).append("/").append(total).append(" observed\n");
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private static long fingerprint(Inputs inputs) {
        long fp = 1469598103934665603L; // FNV-1a 64-bit basis
        for (var room : inputs.rooms()) {
            if (room == null || room.roomId() == null) continue;
            fp ^= room.roomId().hashCode();
            fp *= 1099511628211L;
            // Object count + entity count are part of identity — capture topology shifts.
            fp ^= (room.objects() == null ? 0 : room.objects().size());
            fp *= 1099511628211L;
        }
        // Coarse: total transitions count covers "10 new transitions" invalidation.
        var txTotal = 0;
        for (var v : inputs.transitions().values()) txTotal += v.size();
        // Quantize to buckets of 10 so frequent small additions don't thrash the cache.
        fp ^= (txTotal / 10);
        fp *= 1099511628211L;
        return fp;
    }

    /** Rough token estimate: 1 token ≈ 4 chars (English-ish + structure). */
    private static int approxTokenCount(String s) {
        return Math.max(1, s.length() / 4);
    }

    /** Aggregator for ACTION CONSEQUENCES rendering. */
    private static final class Aggregate {
        final String actionType;
        int count = 0;
        int successes = 0;
        int stateChanges = 0;
        String lastOutcome = "";

        Aggregate(String actionType) { this.actionType = actionType; }

        void accept(WorldModel.Transition t) {
            count++;
            if (t.success()) successes++;
            if (t.outcomeStateKey() != null) stateChanges++;
            if (t.outcomeText() != null && !t.outcomeText().isBlank()) {
                lastOutcome = t.outcomeText();
            }
        }

        String toLine() {
            var sr = count == 0 ? 0 : (int) Math.round(100.0 * successes / count);
            var changeStr = stateChanges == 0
                ? "no state change"
                : stateChanges == count
                    ? "always changes state"
                    : "sometimes changes state";
            var sb = new StringBuilder(80);
            sb.append(actionType).append(" → ");
            if (!lastOutcome.isBlank()) {
                var trimmed = lastOutcome.length() > 60 ? lastOutcome.substring(0, 57) + "..." : lastOutcome;
                sb.append(trimmed).append("; ");
            }
            sb.append(changeStr).append("; success ").append(sr).append("% (n=").append(count).append(")");
            return sb.toString();
        }
    }
}
