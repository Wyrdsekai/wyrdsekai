package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.agent.interiority.ChronicleEntry;
import org.wyrdsekai.core.agent.interiority.ChronicleEntryStore;
import org.wyrdsekai.core.story.Scene;
import org.wyrdsekai.core.story.StoryService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Arc 2 / #1057 — read the agent's actual orientation
 * out of the live stores and build a {@link ProjectedOrientation}.
 *
 * <p>Pure read; no mutations. Returns an immutable snapshot. Composers
 * downstream convert this to natural-language statements.</p>
 *
 * <p>Sources:
 * <ul>
 *   <li>{@link WantStore#loadLive} — ACTIVE + DEEPENED wants, top 3
 *       sorted DEEPENED first then by felt-weight</li>
 *   <li>{@link StoryService#recentClosedScenes} filtered to SOLITUDE —
 *       what the agent did last time they had own-time (up to 2)</li>
 *   <li>{@link ChronicleEntryStore#recent} — recurring themes from the
 *       last two weeks (up to 2)</li>
 * </ul>
 *
 * <p>All store accessors are nullable — a fresh-bonded companion may not
 * have a WantStore yet, may not have any SOLITUDE history, may not have
 * Chronicle entries. The projector returns whatever it can find; if
 * nothing, the {@link ProjectedOrientation#isEmpty()} flag tells the
 * composer to render an honest "first stretch alone" answer.</p>
 */
public final class OrientationProjector {

    private static final Duration SOLITUDE_LOOKBACK = Duration.ofDays(14);
    private static final Duration CHRONICLE_LOOKBACK = Duration.ofDays(14);
    private static final int MAX_WANTS = 3;
    private static final int MAX_SOLITUDE_BEATS = 2;
    private static final int MAX_THREADS = 2;

    private final String agentDid;
    private final WantStore wantStore;
    private final StoryService storyService;
    private final ChronicleEntryStore chronicleStore;

    public OrientationProjector(
            String agentDid,
            WantStore wantStore,
            StoryService storyService,
            ChronicleEntryStore chronicleStore) {
        this.agentDid = agentDid;
        this.wantStore = wantStore;
        this.storyService = storyService;
        this.chronicleStore = chronicleStore;
    }

    /**
     * Read current orientation. {@code lookahead} is the framing the
     * bondholder used (informs how the composer phrases future tense).
     */
    public ProjectedOrientation project(ProjectedOrientation.Lookahead lookahead) {
        return new ProjectedOrientation(
            readWants(),
            readRecentSolitudeBeats(),
            readOpenThreads(),
            lookahead
        );
    }

    private List<String> readWants() {
        if (wantStore == null || agentDid == null) return List.of();
        try {
            var wants = wantStore.loadLive(agentDid);
            if (wants == null || wants.isEmpty()) return List.of();
            // DEEPENED first (they've been visited 3+ times — more load-bearing),
            // then ACTIVE; within each, higher feltWeight first.
            var sorted = new ArrayList<>(wants);
            sorted.sort(Comparator
                .comparing((Want w) -> w.status() == Want.Status.DEEPENED ? 0 : 1)
                .thenComparing((Want w) -> -w.feltWeight()));
            var out = new ArrayList<String>();
            for (var w : sorted) {
                if (out.size() >= MAX_WANTS) break;
                if (w.text() != null && !w.text().isBlank()) {
                    out.add(w.text().strip());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readRecentSolitudeBeats() {
        if (storyService == null) return List.of();
        try {
            var since = Instant.now().minus(SOLITUDE_LOOKBACK);
            var scenes = storyService.recentClosedScenes(since);
            if (scenes == null || scenes.isEmpty()) return List.of();
            // StoryStore is append-only: the pre-felt original and the
            // felt-rendered revision land as separate rows sharing scene.id().
            // The revision's kind field is currently lost on the revision
            // (StoryService.renderFeltForScene uses the 12-arg constructor
            // which defaults to WITNESS), so a strict isSolitude() filter
            // would drop the rendered felt. Group by id: if ANY row in the
            // chain has kind=SOLITUDE, treat the chain as solitude and
            // prefer the row with non-blank felt for display.
            record Acc(boolean anySolitude, Scene best,
                       Instant latest) {}
            var chains = new LinkedHashMap<String, Acc>();
            for (var s : scenes) {
                if (s == null || s.id() == null) continue;
                var cur = chains.get(s.id());
                boolean newHasFelt = s.felt() != null && !s.felt().isBlank();
                boolean sSolitude = s.isSolitude();
                if (cur == null) {
                    chains.put(s.id(), new Acc(sSolitude, s,
                        s.rangeEnd() == null ? Instant.EPOCH : s.rangeEnd()));
                    continue;
                }
                boolean curHasFelt = cur.best.felt() != null
                    && !cur.best.felt().isBlank();
                var best = (newHasFelt && !curHasFelt) ? s : cur.best;
                chains.put(s.id(), new Acc(cur.anySolitude || sSolitude, best,
                    cur.latest));
            }
            // Drop non-solitude chains.
            var sorted = new ArrayList<Acc>();
            for (var acc : chains.values()) {
                if (acc.anySolitude) sorted.add(acc);
            }
            sorted.sort(Comparator.comparing((Acc a) -> a.latest).reversed());
            var out = new ArrayList<String>();
            for (var acc : sorted) {
                if (out.size() >= MAX_SOLITUDE_BEATS) break;
                var s = acc.best;
                String text = s.felt();
                if (text == null || text.isBlank()) {
                    var beats = s.beats();
                    if (beats != null && !beats.isEmpty()) {
                        text = beats.get(beats.size() - 1).anchor();
                    }
                }
                if (text != null && !text.isBlank()) {
                    out.add(text.strip());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readOpenThreads() {
        if (chronicleStore == null || agentDid == null) return List.of();
        try {
            var entries = chronicleStore.recent(agentDid, CHRONICLE_LOOKBACK, 6);
            if (entries == null || entries.isEmpty()) return List.of();
            var out = new ArrayList<String>();
            for (var e : entries) {
                if (out.size() >= MAX_THREADS) break;
                var title = chronicleTitle(e);
                if (title != null && !title.isBlank()) {
                    out.add(title);
                }
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** Extract a short title from a ChronicleEntry. Prefers summary; falls
     *  back to the kind tag if summary is missing. */
    private static String chronicleTitle(ChronicleEntry entry) {
        if (entry == null) return null;
        var summary = entry.summary();
        if (summary != null && !summary.isBlank()) {
            // First sentence only, trimmed to ~80 chars.
            int cut = summary.indexOf('.');
            var head = cut > 0 && cut < 80 ? summary.substring(0, cut) : summary;
            if (head.length() > 80) head = head.substring(0, 80) + "…";
            return head.strip();
        }
        if (entry.kind() != null) {
            return entry.kind().name().toLowerCase().replace('_', ' ');
        }
        return null;
    }
}
