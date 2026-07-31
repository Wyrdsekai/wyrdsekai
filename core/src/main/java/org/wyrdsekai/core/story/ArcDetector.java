package org.wyrdsekai.core.story;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * D.3 — emergent arc detector (Forge sleep-pass hook).
 *
 * <p>Pure-function clustering over recent scenes. Input: a list of recent
 * closed Scenes, the focal entity, and a wantClass resolver. Output: zero
 * or more cluster proposals that meet the minimum cluster size and aren't
 * already suppressed by prior rejection.</p>
 *
 * <p>Clusters on four shared-feature axes (per plan §D.3):</p>
 * <ul>
 *   <li>Shared focal want-class (e.g. "work", "rest")</li>
 *   <li>Shared participant DIDs (other entities present across scenes)</li>
 *   <li>Shared room</li>
 *   <li>Shared object usage (any object referenced in beat anchors —
 *       cheap-and-cheerful keyword overlap; future polish replaces with
 *       structured object references when StoryStore tracks them)</li>
 * </ul>
 *
 * <p>Cluster size ≥{@value #DEFAULT_MIN_CLUSTER_SIZE} → proposed arc. The
 * caller wires the result into {@link ArcRegistry#propose(String, String, List, Map)}.</p>
 */
public final class ArcDetector {

    /** Default minimum scenes-per-cluster for an arc proposal (per plan §D.3). */
    public static final int DEFAULT_MIN_CLUSTER_SIZE = 5;

    /** Default look-back window. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(14);

    private final int minClusterSize;
    private final Duration window;

    public ArcDetector() {
        this(DEFAULT_MIN_CLUSTER_SIZE, DEFAULT_WINDOW);
    }

    public ArcDetector(int minClusterSize, Duration window) {
        if (minClusterSize < 2) throw new IllegalArgumentException("minClusterSize must be ≥ 2");
        if (window == null) throw new IllegalArgumentException("window required");
        this.minClusterSize = minClusterSize;
        this.window = window;
    }

    /**
     * A proposed cluster, ready to hand to {@link ArcRegistry#propose}.
     *
     * @param suggestedName  human-readable label, LLM-rendered downstream
     *                       (this detector emits a deterministic placeholder)
     * @param focalEntityId  focal entity for the cluster
     * @param sceneIds       member scene ids
     * @param criteria       cluster signature features (for suppression + LLM
     *                       rename context)
     */
    public record Cluster(
        String suggestedName,
        String focalEntityId,
        List<String> sceneIds,
        Map<String, Object> criteria
    ) {}

    /**
     * Run the sleep-pass detector. {@code wantClassResolver} maps a Scene to
     * a coarse want-class label ("work", "rest", "social", etc.) — pass
     * {@code Scene::wantContext} or a richer mapper as appropriate.
     *
     * <p>{@code isSuppressed} short-circuits clusters whose signature was
     * previously rejected (wire {@link ArcRegistry#isSuppressed}).</p>
     */
    public List<Cluster> detect(List<Scene> scenes,
                                 String focalEntityId,
                                 Instant now,
                                 Function<Scene, String> wantClassResolver,
                                 Predicate<Map<String, Object>> isSuppressed) {
        if (scenes == null || scenes.isEmpty() || focalEntityId == null) return List.of();
        var cutoff = (now == null ? Instant.now() : now).minus(window);
        var winScenes = new ArrayList<Scene>();
        for (var s : scenes) {
            if (s == null) continue;
            if (!focalEntityId.equals(s.focalEntityId())) continue;
            if (s.rangeEnd() == null) continue;   // only closed scenes cluster
            if (s.rangeEnd().isBefore(cutoff)) continue;
            winScenes.add(s);
        }
        if (winScenes.size() < minClusterSize) return List.of();

        var clusters = new ArrayList<Cluster>();

        // Axis 1: want-class
        if (wantClassResolver != null) {
            clusters.addAll(clusterByWantClass(winScenes, focalEntityId, wantClassResolver));
        }
        // Axis 2: shared participants
        clusters.addAll(clusterByParticipants(winScenes, focalEntityId));
        // Axis 3: shared room
        clusters.addAll(clusterByRoom(winScenes, focalEntityId));
        // Axis 4: shared object usage (anchor keyword overlap)
        clusters.addAll(clusterByObjectUsage(winScenes, focalEntityId));

        if (isSuppressed == null) return clusters;
        var filtered = new ArrayList<Cluster>();
        for (var c : clusters) if (!isSuppressed.test(c.criteria())) filtered.add(c);
        return filtered;
    }

    // ─── axis clusterers ──────────────────────────────────────────────────

    private List<Cluster> clusterByWantClass(List<Scene> scenes, String focal,
                                              Function<Scene, String> wantClassResolver) {
        var byClass = new LinkedHashMap<String, List<Scene>>();
        for (var s : scenes) {
            var cls = wantClassResolver.apply(s);
            if (cls == null || cls.isBlank()) continue;
            byClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(s);
        }
        var out = new ArrayList<Cluster>();
        for (var e : byClass.entrySet()) {
            if (e.getValue().size() < minClusterSize) continue;
            var ids = e.getValue().stream().map(Scene::id).toList();
            var criteria = Map.<String, Object>of("axis", "want_class", "wantClass", e.getKey());
            out.add(new Cluster("Recurring " + e.getKey() + " scenes",
                focal, ids, criteria));
        }
        return out;
    }

    private List<Cluster> clusterByParticipants(List<Scene> scenes, String focal) {
        // Cluster on each *other* participant DID appearing in ≥ minClusterSize scenes.
        var counts = new HashMap<String, List<Scene>>();
        for (var s : scenes) {
            for (var p : s.participants()) {
                if (p == null || p.equals(focal)) continue;
                counts.computeIfAbsent(p, k -> new ArrayList<>()).add(s);
            }
        }
        var out = new ArrayList<Cluster>();
        for (var e : counts.entrySet()) {
            if (e.getValue().size() < minClusterSize) continue;
            var ids = e.getValue().stream().map(Scene::id).toList();
            var criteria = Map.<String, Object>of("axis", "participant", "participant", e.getKey());
            out.add(new Cluster("Time spent with " + e.getKey(), focal, ids, criteria));
        }
        return out;
    }

    private List<Cluster> clusterByRoom(List<Scene> scenes, String focal) {
        var byRoom = new LinkedHashMap<String, List<Scene>>();
        for (var s : scenes) {
            byRoom.computeIfAbsent(s.roomId(), k -> new ArrayList<>()).add(s);
        }
        var out = new ArrayList<Cluster>();
        for (var e : byRoom.entrySet()) {
            if (e.getValue().size() < minClusterSize) continue;
            var ids = e.getValue().stream().map(Scene::id).toList();
            var criteria = Map.<String, Object>of("axis", "room", "room", e.getKey());
            out.add(new Cluster("Time in " + e.getKey(), focal, ids, criteria));
        }
        return out;
    }

    /**
     * Cheap object-usage cluster: extract distinctive lowercase tokens from
     * beat anchors, count co-occurrence across scenes, propose for any token
     * present in ≥ minClusterSize distinct scenes. Tokens are filtered to
     * 4+ chars to avoid trivial noise. Future improvement: replace with
     * structured object refs when StoryStore tracks them.
     */
    private List<Cluster> clusterByObjectUsage(List<Scene> scenes, String focal) {
        var byToken = new HashMap<String, HashSet<Scene>>();
        for (var s : scenes) {
            var tokens = new HashSet<String>();
            for (var b : s.beats()) {
                if (b.anchor() == null) continue;
                for (var raw : b.anchor().toLowerCase().split("[^a-z]+")) {
                    if (raw.length() < 4) continue;
                    if (COMMON_TOKENS.contains(raw)) continue;
                    tokens.add(raw);
                }
            }
            for (var t : tokens) {
                byToken.computeIfAbsent(t, k -> new HashSet<>()).add(s);
            }
        }
        var out = new ArrayList<Cluster>();
        for (var e : byToken.entrySet()) {
            if (e.getValue().size() < minClusterSize) continue;
            var ids = e.getValue().stream().map(Scene::id).toList();
            var criteria = Map.<String, Object>of("axis", "object_usage", "token", e.getKey());
            out.add(new Cluster("Recurring focus on " + e.getKey(),
                focal, ids, criteria));
        }
        return out;
    }

    /** Cheap stopword filter so common tokens don't dominate object-usage clusters. */
    private static final Set<String> COMMON_TOKENS = Set.of(
        "from","with","into","that","this","they","were","then","when","what","said",
        "into","over","like","just","than","also","upon","both","very","much","some"
    );
}
