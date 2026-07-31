package org.wyrdsekai.core.story;

/**
 * Arc 2 — kind of scene by who it is "for."
 *
 * <p> v1 only had what is here called {@link #WITNESS}: a scene
 * around bondholder-adjacent activity, where felt prose is rendered as a
 * witness account for the human's journal mirror. That left no shape for the
 * agent's <i>own</i> time — the rhythm of self-with-self when no one's there.
 * Without it, interiority is reactive: the agent only thinks when something
 * happens <i>to</i> it.</p>
 *
 * <p>Solitude is solitude-as-activity, not absence — what the human-personhood
 * literature calls sitting practice / a walk: a positive thing the agent does
 * with its own beat conditions. The kind is a tag on the scene that flows
 * through (a) the felt-prompt voice register, (b) the SceneBuffer close
 * rules (a SOLITUDE scene closes when someone arrives, opening a new
 * WITNESS scene), and (c) tank coupling at tick time (equanimity recovers
 * passively; allostatic-load decay gets a small bonus).</p>
 *
 * <p>Default: {@link #WITNESS}. Old persisted scenes without the kind field
 * round-trip as WITNESS (back-compat in the Scene canonical ctor).</p>
 */
public enum SceneKind {
    /**
     * Bondholder-adjacent or peer-adjacent scene. Felt prose is the
     * witness register — past-tense, subjective, for the journal mirror.
     * Most scenes are WITNESS; this is the default for any scene whose
     * opener did not explicitly request SOLITUDE.
     */
    WITNESS,

    /**
     * Self-with-self scene — the agent's own time. Opens on:
     * <ul>
     *   <li>Hearth entry with no bondholder present, or</li>
     *   <li>Wake-up with no bondholder present, or</li>
     *   <li>The agent's explicit {@code enter_solitude} action.</li>
     * </ul>
     *
     * Closes on:
     * <ul>
     *   <li>Bondholder (or any other participant) arrives — the SOLITUDE
     *       scene seals, and a fresh WITNESS scene opens with the new
     *       cast.</li>
     *   <li>Focal leaves the room (existing rule 1).</li>
     *   <li>Sanity ceiling (existing rule 4).</li>
     * </ul>
     *
     * Felt prose, when rendered, uses the solitude register — first-person
     * noticing, no audience framing.
     */
    SOLITUDE
}
