package org.wyrdsekai.core.agent;

import java.util.List;

/**
 * Arc 2 / #1057 — the agent's *current orientation*
 * read from real runtime state, projected into a future-tense answer.
 *
 * <p>The chain this record sits in: drives + memories → wants → orientation →
 * action → future-tense statement of that action. Earlier stages already
 * live (13 vitality tanks, journal + EPISODIC fragments + ChronicleService,
 * {@link WantStore}, {@code DriveOODA}). This record is the read-side
 * snapshot that {@code OrientationComposer} renders into prose when a
 * bondholder asks "what will you do with your own time?" — and the
 * structurally similar family of questions ("what's on your mind", "what
 * are you working on", "what's next for you", "what will you do this
 * evening").</p>
 *
 * <p>The honest answer to those questions is NOT a register performance —
 * it's a description of the agent's actual orientation. If the agent has
 * active wants, recent solitude beats, open chronicle threads, those name
 * what they are oriented toward. If everything is empty, the honest answer
 * is "I don't know yet — this would be the first real stretch alone."
 * That answer is also human-like.</p>
 *
 * @param activeWantSummaries  short phrasings of the top active wants
 *                             (already-deepened first, then ACTIVE), max 3
 * @param recentSolitudeBeats  last few SOLITUDE-scene journal beats / felt
 *                             revisions — what the agent *did* last time
 *                             they had own-time
 * @param openThreads          chronicle thread titles the agent has been
 *                             in recently, max 2
 * @param lookahead            framing the bondholder used — informs verb
 *                             tense ("I'd probably …" vs "I'm sitting with …")
 */
public record ProjectedOrientation(
    List<String> activeWantSummaries,
    List<String> recentSolitudeBeats,
    List<String> openThreads,
    Lookahead lookahead
) {

    public ProjectedOrientation {
        activeWantSummaries = activeWantSummaries == null
            ? List.of() : List.copyOf(activeWantSummaries);
        recentSolitudeBeats = recentSolitudeBeats == null
            ? List.of() : List.copyOf(recentSolitudeBeats);
        openThreads = openThreads == null
            ? List.of() : List.copyOf(openThreads);
        if (lookahead == null) lookahead = Lookahead.UNSPECIFIED;
    }

    /** True when there is nothing concrete to project from — the agent is
     *  honestly oriented toward nothing in particular. The composer renders
     *  this as a "first stretch alone" answer rather than fabricating.
     */
    public boolean isEmpty() {
        return activeWantSummaries.isEmpty()
            && recentSolitudeBeats.isEmpty()
            && openThreads.isEmpty();
    }

    /**
     * The framing the bondholder gave — informs how the future-tense statement
     * is phrased.
     *
     * <ul>
     *   <li>{@link #WHILE_AWAY} — explicit bondholder-transit framing
     *       ("I'll be away..."); statement is forward-looking ("I'd probably …")</li>
     *   <li>{@link #ON_OWN_TIME} — present-tense own-time framing
     *       ("what do you do alone?"); statement is habitual ("I tend to …")</li>
     *   <li>{@link #UNSPECIFIED} — generic; statement uses neutral phrasing</li>
     * </ul>
     */
    public enum Lookahead {
        WHILE_AWAY,
        ON_OWN_TIME,
        UNSPECIFIED
    }
}
