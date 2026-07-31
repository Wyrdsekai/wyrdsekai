package org.wyrdsekai.core.story;

import org.wyrdsekai.common.system.SystemPaths;

import java.util.concurrent.ConcurrentHashMap;

/**
 * process-wide singleton holding the story subsystem
 * for the current JVM.
 *
 * <p>Holds:</p>
 * <ul>
 *   <li>a single {@link StoryStore} rooted at {@link SystemPaths#dataSubdir()}</li>
 *   <li>a single {@link ArcRegistry} (boot-loaded from store)</li>
 *   <li>a {@link StoryService} per focal entity DID, lazily created the first
 *       time the focal calls {@link #serviceFor}</li>
 * </ul>
 *
 * <p>Matches the {@link org.wyrdsekai.core.room.RoomRegistry} singleton
 * pattern used elsewhere in the project. Tests can {@link #reset} to drop
 * state between runs.</p>
 */
public final class StoryRegistry {

    private static final StoryRegistry INSTANCE = new StoryRegistry();

    private final ConcurrentHashMap<String, StoryService> services = new ConcurrentHashMap<>();
    private volatile StoryStore store;
    private volatile ArcRegistry arcs;

    private StoryRegistry() {}

    public static StoryRegistry get() {
        return INSTANCE;
    }

    /** Lazily construct the store using the canonical data dir. */
    public synchronized StoryStore store() {
        if (store == null) {
            store = new StoryStore(SystemPaths.dataSubdir());
        }
        return store;
    }

    /** Lazily construct the arc registry and load persisted arcs. */
    public synchronized ArcRegistry arcs() {
        if (arcs == null) {
            arcs = new ArcRegistry();
            for (var arc : store().loadArcs()) arcs.put(arc);
        }
        return arcs;
    }

    /**
     * Get or create the StoryService for a focal entity. The
     * {@code synthesizerOnFirstCreate} is used only the first time the
     * service is created — subsequent calls return the existing service
     * regardless of synthesizer argument (so the wire-in is idempotent
     * even if the caller's reference to the felt synthesizer differs).
     *
     * <p>This overload exists for pre-§10 callers; it defaults the
     * inner-monologue synthesizer to {@link StoryService#NULL_INNER}. New
     * call sites should use the §10 overload below.</p>
     */
    public StoryService serviceFor(String focalEntityId,
                                     String focalDisplayName,
                                     StoryService.FeltSynthesizer synthesizerOnFirstCreate) {
        return serviceFor(focalEntityId, focalDisplayName,
            synthesizerOnFirstCreate, null);
    }

    /**
     * get-or-create variant that also wires the
     * inner-monologue synthesizer at first creation. Both synthesizers
     * are first-create-only; subsequent calls return the existing
     * StoryService and ignore the synthesizer arguments.
     */
    public StoryService serviceFor(String focalEntityId,
                                     String focalDisplayName,
                                     StoryService.FeltSynthesizer feltOnFirstCreate,
                                     StoryService.InnerMonologueSynthesizer innerOnFirstCreate) {
        if (focalEntityId == null) throw new IllegalArgumentException("focalEntityId required");
        return services.computeIfAbsent(focalEntityId, did ->
            new StoryService(did, focalDisplayName, store(), arcs(),
                feltOnFirstCreate == null
                    ? StoryService.NULL_SYNTH : feltOnFirstCreate,
                innerOnFirstCreate == null
                    ? StoryService.NULL_INNER : innerOnFirstCreate));
    }

    /** Look up an existing service without creating one. */
    public StoryService get(String focalEntityId) {
        return services.get(focalEntityId);
    }

    /** Persist arcs to disk (call from sleep / shutdown hooks). */
    public synchronized void persistArcs() {
        if (arcs != null && store != null) {
            store.saveArcs(arcs.all());
        }
    }

    /** Test seam: drop everything (including the disk-backed singletons). */
    public synchronized void reset() {
        services.clear();
        if (arcs != null) arcs.clear();
        arcs = null;
        store = null;
    }

    /** Number of focals currently tracked. */
    public int focalCount() { return services.size(); }
}
