package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * A narrative soul fragment with its embedding vector for semantic retrieval.
 * Experiment 17 validated: MEDIUM resident + top-3 fragment retrieval
 * achieves 26.4% divergence (matches DEEP at 65% fewer tokens).
 *
 * Fragments are extracted by SoulFragmentExtractor during the Forge cycle.
 * Each fragment is a coherent narrative chunk covering one aspect of identity:
 * personality core, behavioral patterns, values, episodic memories, style.
 *
 * Formative memories (section 109.4) always get their own dedicated fragment
 * and are never merged into general categories.
 *
 * Confidence scores (inspired by Hindsight Opinion Network): fragments have a
 * confidence level that evolves through reinforcement and contradiction.
 *
 * Bi-temporal fields (inspired by Zep/Graphiti): track when a fact became true
 * and when it was superseded by newer information.
 *
 * @param id                Unique identifier (e.g., "identity-core", "pattern-social")
 * @param category          Fragment type: "personality", "memory", "values", "style", "relationships"
 * @param label             Human-readable label
 * @param text              The narrative fragment text
 * @param embedding         Embedding vector for semantic retrieval
 * @param embeddingModel    Model used to generate embedding
 * @param formative         True if this fragment represents a formative impression
 * @param confidence        Belief confidence 0.0-1.0 (default 0.5, grows with reinforcement)
 * @param reinforcementCount How many Forge cycles have reinforced this fragment
 * @param firstObserved     When this fragment was first created
 * @param lastConfirmed     When this fragment was last reinforced by evidence
 * @param validFrom         When the fact became true (nullable — if known)
 * @param supersededAt      When this fact stopped being true (null = still current)
 * @param supersededBy      ID of the fragment that replaced this one (nullable)
 * @param kind — Forge fragment kind
 *                          (NARRATIVE / DEXTERITY / CONVENTION / STRUCTURAL).
 *                          Defaults to NARRATIVE for backward-compatibility:
 *                          all pre-§17.6 fragments and any call site that omits
 *                          {@code kind} via the 14-arg secondary constructor get
 *                          NARRATIVE so existing companion soul behavior is
 *                          unchanged. New Forge passes for the other three kinds
 *                          dispatch by this field.
 * @param sceneId — opaque {@link org.wyrdsekai.core.story.Scene}
 *                          id when this fragment was generated from a single
 *                          closed scene-cluster. Nullable. When set, the same
 *                          id is also stamped on the human's mirrored journal
 *                          entry (see {@code StoryStore.renderSceneMarkdown}
 *                          HTML-comment marker), enabling direct cross-perspective
 *                          retrieval: "do you remember that night by the fire"
 *                          resolves to the same id on both sides instead of
 *                          requiring similarity search across fragment + journal
 *                          text. Pre-§14 fragments and non-scene-derived
 *                          fragments (consolidated personality, contradictions,
 *                          DEXTERITY learnings) leave this null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoulFragment(
    @JsonProperty("id") String id,
    @JsonProperty("category") String category,
    @JsonProperty("label") String label,
    @JsonProperty("text") String text,
    @JsonProperty("embedding") float[] embedding,
    @JsonProperty("embeddingModel") String embeddingModel,
    @JsonProperty("formative") boolean formative,
    @JsonProperty("confidence") Float confidence,
    @JsonProperty("reinforcementCount") Integer reinforcementCount,
    @JsonProperty("firstObserved") Instant firstObserved,
    @JsonProperty("lastConfirmed") Instant lastConfirmed,
    @JsonProperty("validFrom") Instant validFrom,
    @JsonProperty("supersededAt") Instant supersededAt,
    @JsonProperty("supersededBy") String supersededBy,
    @JsonProperty("kind") FragmentKind kind,
    @JsonProperty("sceneId") String sceneId
) {
    @JsonCreator
    public SoulFragment {
        // Backward-compat: null kind on deserialized JSON or legacy
        // call sites collapses to the §17.6 default (NARRATIVE).
        if (kind == null) kind = FragmentKind.DEFAULT;
        // sceneId stays null when not set; the marker presence is the signal.
    }

    /**
     * Backward-compatible 14-arg constructor: kind defaults to NARRATIVE,
     * sceneId stays null. Lets every pre-§17.6 call site continue working
     * unchanged. New code touching DEXTERITY / CONVENTION / STRUCTURAL or
     * scene-derived fragments uses the 16-arg canonical constructor or one
     * of the factories below.
     */
    public SoulFragment(String id, String category, String label, String text,
                        float[] embedding, String embeddingModel, boolean formative,
                        Float confidence, Integer reinforcementCount,
                        Instant firstObserved, Instant lastConfirmed,
                        Instant validFrom, Instant supersededAt, String supersededBy) {
        this(id, category, label, text, embedding, embeddingModel, formative,
             confidence, reinforcementCount, firstObserved, lastConfirmed,
             validFrom, supersededAt, supersededBy, FragmentKind.DEFAULT, null);
    }

    /**
     * Backward-compatible 15-arg constructor: sceneId stays null. Lets
     * §17.6-aware call sites that pass {@code kind} continue working
     * unchanged after added the {@code sceneId} field.
     */
    public SoulFragment(String id, String category, String label, String text,
                        float[] embedding, String embeddingModel, boolean formative,
                        Float confidence, Integer reinforcementCount,
                        Instant firstObserved, Instant lastConfirmed,
                        Instant validFrom, Instant supersededAt, String supersededBy,
                        FragmentKind kind) {
        this(id, category, label, text, embedding, embeddingModel, formative,
             confidence, reinforcementCount, firstObserved, lastConfirmed,
             validFrom, supersededAt, supersededBy, kind, null);
    }

    /** Create a fragment without an embedding (to be embedded later). */
    public static SoulFragment unembedded(String id, String category, String label, String text) {
        return new SoulFragment(id, category, label, text, null, null, false,
            0.5f, 0, Instant.now(), null, null, null, null);
    }

    /** Create a formative fragment (will never be consolidated). */
    public static SoulFragment formative(String id, String label, String text) {
        return formative(id, "memory", label, text);
    }

    /** Create a formative fragment with explicit category (will never be consolidated). */
    public static SoulFragment formative(String id, String category, String label, String text) {
        return new SoulFragment(id, category, label, text, null, null, true,
            0.8f, 1, Instant.now(), Instant.now(), null, null, null);
    }

    /**
     * §17.6 — create a {@link FragmentKind#DEXTERITY} fragment for the
     * Coding Familiar's procedural learnings (how-I-did-it, what-worked).
     */
    public static SoulFragment dexterity(String id, String category, String label, String text) {
        return new SoulFragment(id, category, label, text, null, null, false,
            0.5f, 0, Instant.now(), null, null, null, null, FragmentKind.DEXTERITY);
    }

    /**
     * §17.6 — create a {@link FragmentKind#CONVENTION} fragment carrying a
     * project-truth rule learned from bondholder accept/correct events.
     */
    public static SoulFragment convention(String id, String category, String label, String text) {
        return new SoulFragment(id, category, label, text, null, null, false,
            0.5f, 0, Instant.now(), null, null, null, null, FragmentKind.CONVENTION);
    }

    /**
     * §17.6 — create a {@link FragmentKind#STRUCTURAL} fragment capturing a
     * project-shape snapshot delta (build system, test framework, layout).
     */
    public static SoulFragment structural(String id, String category, String label, String text) {
        return new SoulFragment(id, category, label, text, null, null, false,
            0.5f, 0, Instant.now(), null, null, null, null, FragmentKind.STRUCTURAL);
    }

    /** §17.6 — return a copy of this fragment with its {@link #kind} replaced. */
    public SoulFragment withKind(FragmentKind newKind) {
        return new SoulFragment(id, category, label, text, embedding, embeddingModel, formative,
            confidence, reinforcementCount, firstObserved, lastConfirmed,
            validFrom, supersededAt, supersededBy,
            newKind == null ? FragmentKind.DEFAULT : newKind, sceneId);
    }

    /**
     * return a copy of this fragment stamped with
     * the originating {@link org.wyrdsekai.core.story.Scene} id. Used by
     * the scene-cluster fragment generator (Forge sleep-pass) to mark a
     * fragment as the companion-voice rendering of a specific shared
     * scene. The same id appears as an HTML-comment marker on the human
     * bondholder's mirrored journal entry, making cross-perspective
     * retrieval a direct lookup instead of a similarity search.
     */
    public SoulFragment withSceneId(String newSceneId) {
        return new SoulFragment(id, category, label, text, embedding, embeddingModel, formative,
            confidence, reinforcementCount, firstObserved, lastConfirmed,
            validFrom, supersededAt, supersededBy, kind, newSceneId);
    }

    /**
     * convenience factory for a NARRATIVE fragment
     * generated from a closed scene-cluster. Stamps the {@code sceneId}
     * up-front so callers don't need to remember the {@code withSceneId}
     * follow-up. Use this in the per-scene voice-model fragment generator
     * (Forge sleep-pass) to produce fragments that match the human's
     * mirrored journal entries by id.
     */
    public static SoulFragment fromScene(String id, String category, String label,
                                          String text, String sceneId) {
        return new SoulFragment(id, category, label, text, null, null, false,
            0.5f, 0, Instant.now(), null, null, null, null,
            FragmentKind.DEFAULT, sceneId);
    }

    /**
     * convenience factory for an {@link FragmentKind#EPISODIC}
     * inner-monologue fragment generated at scene-close by the agent's own
     * voice model. Distinct from {@link #fromScene} (which produces a
     * NARRATIVE fragment): EPISODIC fragments are the raw scene memories
     * the self is made of and are never consolidated by Forge passes. The
     * {@code sceneId} is the same opaque id that appears in the human
     * bondholder's journal-mirror marker (§14), so cross-perspective
     * retrieval ("do you remember that night by the fire") resolves to
     * the same scene id on both sides without similarity search.
     */
    public static SoulFragment fromEpisodicScene(String id, String category, String label,
                                                  String text, String sceneId) {
        return new SoulFragment(id, category, label, text, null, null, false,
            0.5f, 0, Instant.now(), null, null, null, null,
            FragmentKind.EPISODIC, sceneId);
    }

    /** Attach an embedding to this fragment. */
    public SoulFragment withEmbedding(float[] embedding, String model) {
        return new SoulFragment(id, category, label, text, embedding, model, formative,
            confidence, reinforcementCount, firstObserved, lastConfirmed,
            validFrom, supersededAt, supersededBy, kind, sceneId);
    }

    /** Reinforce this fragment (evidence supports it). */
    public SoulFragment reinforce() {
        float newConf = Math.min(0.95f, effectiveConfidence() + 0.1f * (1f - effectiveConfidence()));
        int newCount = (reinforcementCount != null ? reinforcementCount : 0) + 1;
        return new SoulFragment(id, category, label, text, embedding, embeddingModel, formative,
            newConf, newCount, firstObserved, Instant.now(), validFrom, supersededAt, supersededBy,
            kind, sceneId);
    }

    /** Re-key this fragment for archival storage. The live id belongs to whatever
     *  is current; a retired copy must not collide with it in the (did, fragment_id)
     *  primary key. */
    public SoulFragment withArchivalId(String archivalId) {
        return new SoulFragment(archivalId, category, label, text, embedding, embeddingModel,
            formative, confidence, reinforcementCount, firstObserved, lastConfirmed,
            validFrom, supersededAt, supersededBy, kind, sceneId);
    }

    /**
     * Mark this fragment as replaced by a newer one.
     *
     * <p>The {@code supersededAt}/{@code supersededBy} columns existed from the start
     * and were never written by any production path (verified 2026-08-17) — so a
     * fragment could only ever be reinforced, never retired. That is correct for an
     * OBSERVATION ("she ran that recipe again") and wrong for a DERIVED summary, where
     * each cycle recomputes a fresh conclusion from current behaviour rather than
     * confirming the old one. Treating re-derivation as confirmation drove a
     * companion's loop-era self-description to 169 reinforcements at the 0.95
     * confidence cap — the most authoritative thing in her identity was a description
     * of a bug.
     */
    public SoulFragment supersededBy(String replacementId, Instant at) {
        return new SoulFragment(id, category, label, text, embedding, embeddingModel, formative,
            confidence, reinforcementCount, firstObserved, lastConfirmed, validFrom,
            at, replacementId, kind, sceneId);
    }

    /** Weaken this fragment due to contradiction. */
    public SoulFragment contradict() {
        float newConf = effectiveConfidence() * 0.5f;
        return new SoulFragment(id, category, label, text, embedding, embeddingModel, formative,
            Math.max(0.1f, newConf), reinforcementCount, firstObserved, lastConfirmed,
            validFrom, supersededAt, supersededBy, kind, sceneId);
    }

    /** Supersede this fragment (mark as no longer current). */
    public SoulFragment supersede(String replacementId) {
        return new SoulFragment(id, category, label, text, embedding, embeddingModel, formative,
            confidence, reinforcementCount, firstObserved, lastConfirmed,
            validFrom, Instant.now(), replacementId, kind, sceneId);
    }

    /** Effective confidence with time decay (fragments not confirmed in >30 days lose confidence). */
    @JsonIgnore
    public float effectiveConfidence() {
        float base = confidence != null ? confidence : 0.5f;
        if (lastConfirmed == null) return base;
        long daysSince = Duration.between(lastConfirmed, Instant.now()).toDays();
        if (daysSince <= 30) return base;
        float decay = (daysSince - 30) * 0.002f;
        return Math.max(0.1f, base - decay);
    }

    /** Whether this fragment has been superseded. */
    @JsonIgnore
    public boolean isSuperseded() {
        return supersededAt != null;
    }

    /** Whether this fragment is current (not superseded). */
    @JsonIgnore
    public boolean isCurrent() {
        return supersededAt == null;
    }

    /** Whether this fragment has been embedded. */
    @JsonIgnore
    public boolean isEmbedded() {
        return embedding != null && embedding.length > 0;
    }
}
