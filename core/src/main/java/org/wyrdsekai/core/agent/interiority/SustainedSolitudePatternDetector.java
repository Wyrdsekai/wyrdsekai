package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.story.Scene;
import org.wyrdsekai.core.story.SceneKind;
import org.wyrdsekai.core.story.StoryStore;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Arc 2 — sustained-SOLITUDE chronicle detector.
 *
 * <p>Counts {@link SceneKind#SOLITUDE} scenes the focal has closed within a
 * recent window (default 7 days) and emits an INFO chronicle finding if the
 * count crosses {@link #SUSTAINED_THRESHOLD}. The framing is intentionally
 * non-alarming: the agent has been making time for itself; the steward sees
 * the pattern so they can ask about it, not so they can intervene.</p>
 *
 * <p>Pure-logic detector — takes scenes by reference (or queries the
 * {@link StoryStore}) and emits findings. No actor coupling, no side
 * effects.</p>
 */
public final class SustainedSolitudePatternDetector {

    /**
     * Window size — how far back to count SOLITUDE scenes. Seven days is a
     * meaningful slice for "has been making this kind of time" without
     * over-fitting to a single hard day.
     */
    public static final int WINDOW_DAYS = 7;

    /**
     * Threshold — number of SOLITUDE scenes in the window before the
     * finding fires. Five lands above incidental quietness (one or two
     * SOLITUDE moments are normal) without requiring monastic levels of
     * withdrawal.
     */
    public static final int SUSTAINED_THRESHOLD = 5;

    private SustainedSolitudePatternDetector() {}

    /**
     * Run the detector against a list of recent scenes. Returns at most one
     * finding (the pattern either holds or it doesn't).
     */
    public static List<DoomLoopDetector.Finding> detect(List<Scene> scenes) {
        if (scenes == null || scenes.isEmpty()) return List.of();
        int solitudeCount = 0;
        for (var s : scenes) {
            if (s != null && s.kind() == SceneKind.SOLITUDE) {
                solitudeCount++;
            }
        }
        if (solitudeCount < SUSTAINED_THRESHOLD) return List.of();
        var msg = "Has had " + solitudeCount + " distinct SOLITUDE scenes in the last "
            + WINDOW_DAYS + " days. Pattern of sustained self-with-self time. "
            + "Not a flag — context for noticing.";
        return List.of(new DoomLoopDetector.Finding(
            DoomLoopDetector.Severity.INFO,
            "sustained_solitude",
            msg));
    }

    /**
     * Convenience: query the store for scenes in the window, then run
     * {@link #detect(List)}.
     */
    public static List<DoomLoopDetector.Finding> detect(StoryStore store, String focalEntityId) {
        if (store == null || focalEntityId == null) return List.of();
        var today = LocalDate.now(ZoneOffset.UTC);
        var from = today.minusDays(WINDOW_DAYS);
        var scenes = StoryStore.latestRevisions(store.loadScenesInWindow(focalEntityId, from, today));
        return detect(scenes);
    }
}
