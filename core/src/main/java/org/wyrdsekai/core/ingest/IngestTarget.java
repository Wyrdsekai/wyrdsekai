package org.wyrdsekai.core.ingest;

/**
 * Where extracted content should be routed.
 */
public enum IngestTarget {
    /** Send as event to Oracle for prediction/analysis. */
    ORACLE,
    /** Store in Study as journal entry (personal). */
    STUDY,
    /** Store in Library as knowledge chunk (shared). */
    LIBRARY,
    /** Inject into agent's working memory (ephemeral). */
    AGENT_CONTEXT
}
