package org.wyrdsekai.core.story;

/**
 * D.3 — how an arc came to exist.
 *
 * <p>Mirrors the structured-memory model: declared = inline emphasis
 * (focal entity says "it's coding time"); emergent = recognition layer
 * (Forge sleep-pass clusters recent scenes).</p>
 */
public enum ArcKind {
    /** Player or agent declared the arc inline. Active from declaration → rangeEnd. */
    DECLARED,
    /** Forge sleep-pass clustered scenes into a recognized arc; awaiting human review. */
    EMERGENT
}
