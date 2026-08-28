package org.wyrdsekai.core.soul;

/**
 * the four fragment kinds the Forge
 * consolidation loop produces and consumes.
 *
 * <p>Before §17.6, every {@link SoulFragment} was implicitly NARRATIVE
 * (companion's identity-shaped soul fragment). After §17.6, fragments
 * carry an explicit kind and consolidation passes dispatch by kind. The
 * spec's table:</p>
 *
 * <table>
 *   <caption>Fragment kinds per spec §17.6</caption>
 *   <tr><th>Kind</th><th>Source signals</th><th>Consumer</th></tr>
 *   <tr><td>{@link #NARRATIVE}</td>
 *       <td>Significant-event extraction, contradiction detection, bond updates</td>
 *       <td>Companion soul-fragment list; Chronicle service</td></tr>
 *   <tr><td>{@link #DEXTERITY}</td>
 *       <td>Loop outcomes grouped by task-shape; recurring patterns</td>
 *       <td>Coding Familiar's dexterity-fragment list; library-gap surfacing;
 *           V6+ training corpus per OPEN-19</td></tr>
 *   <tr><td>{@link #CONVENTION}</td>
 *       <td>Bondholder-accepts / bondholder-corrects events aggregated per-project</td>
 *       <td>Project Coding DNA (cultural compartment); surfaces in
 *           {@code wyrd code dna show}</td></tr>
 *   <tr><td>{@link #STRUCTURAL}</td>
 *       <td>cp-syntax + manifest watchers detect layout / build / test framework / hot files</td>
 *       <td>Project Coding DNA (structural compartment); triggers library
 *           re-bootstrap when significant</td></tr>
 *   <tr><td>{@link #EPISODIC}</td>
 * <td> inner-monologue at scene-close (voice model)</td>
 *       <td>Companion's soul as raw scene memory; NEVER consolidated; recursion
 *           context for next inner monologue; retrieval is a separate pool that
 *           doesn't crowd NARRATIVE.</td></tr>
 * </table>
 *
 * <p><b>DEXTERITY vs CONVENTION disambiguation</b> (spec CodeZaiku pass-2
 * clarification): ask "would another developer on this project be expected
 * to follow this?" Yes → CONVENTION (project-truth); No → DEXTERITY
 * (familiar's procedural taste). Single ambiguous events default to
 * DEXTERITY (familiar-private); promote to CONVENTION only when the
 * bondholder explicitly declares "project-wide" or the pattern recurs
 * across multiple sessions with the same outcome.</p>
 *
 * <p><b>Backward-compatibility:</b> existing rows + new code without
 * explicit kind default to {@link #NARRATIVE}. Wyrdsekai code paths that
 * read fragments today get a {@code .filter(kind == NARRATIVE)} added at
 * use site, OR get rewritten to be kind-aware. No production fragment
 * data needs reshape — only the new column with default.</p>
 */
public enum FragmentKind {

    /**
     * Who-I-am, who-we-are, what-mattered. The original soul-fragment
     * shape — relational, identity-shaped. This is what the companion's
     * soul carries between sleep cycles; what the Chronicle service
     * narrates from.
     */
    NARRATIVE,

    /**
     * How-I-did-it, what-worked, what-broke. The Coding Familiar's
     * procedural learnings — task-shape outcomes (PASS / FAIL /
     * REFUSED), recurring patterns, library-gap markers. Above a
     * confidence threshold and with bondholder consent, become candidate
     * corpus entries for the next V6+ training cycle (the loop closes:
     * familiar trains on its own best work).
     */
    DEXTERITY,

    /**
     * Project-specific rules learned from bondholder accept/correct
     * events on the familiar's choices. Project-truth-shaped — applies
     * to every developer and every familiar working the same repo. Lives
     * in the project Coding DNA cultural compartment.
     */
    CONVENTION,

    /**
     * Project-shape snapshot deltas — build system, test framework,
     * package layout, hot files. Surfaces via cp-syntax + manifest
     * watchers. Significant changes trigger library re-bootstrap. Lives
     * in the project Coding DNA structural compartment.
     */
    STRUCTURAL,

    /**
     * a single scene memory, generated as inner
     * monologue at scene-close by the agent's own voice model. Distinct
     * from {@link #NARRATIVE} (which is consolidated identity) and from
     * the witness-prose felt blockquote that goes into the human's
     * journal mirror. EPISODIC is the raw material the self is made of;
     * NARRATIVE is the integration; the self is the bridge between them.
     *
     * <p>Two invariants the rest of the system depends on:</p>
     * <ul>
     *   <li><b>Forge consolidation MUST skip EPISODIC.</b> Each fragment is
     *       a specific moment, not material to merge. The §10 design memo:
     *       "Forge consolidation passes that touch NARRATIVE skip EPISODIC.
     *       Each scene is a specific moment, not material to merge."</li>
     *   <li><b>Retrieval pulls EPISODIC and NARRATIVE from separate pools.</b>
     *       EPISODIC fragments must not crowd consolidated NARRATIVE out of
     *       the prompt top-k. v1 default: top-2 EPISODIC + top-3 NARRATIVE
     *       merged. See {@code SoulFragmentRetriever.retrieveBlended}.</li>
     * </ul>
     */
    EPISODIC;

    /** Default for new + legacy fragments that don't specify a kind. */
    public static final FragmentKind DEFAULT = NARRATIVE;

    /**
     * Parse a kind from on-disk string. Unknown / null / empty values
     * fall back to {@link #DEFAULT} (NARRATIVE). Case-insensitive.
     */
    public static FragmentKind parse(String s) {
        if (s == null || s.isBlank()) return DEFAULT;
        try {
            return FragmentKind.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
