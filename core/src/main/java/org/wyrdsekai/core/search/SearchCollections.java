package org.wyrdsekai.core.search;

/**
 * Collection names for WyrdLuceneStore.
 * Each collection maps to a separate Lucene directory under dataDir/search/{collection}/.
 */
public final class SearchCollections {

    private SearchCollections() {}

    /** Behavioral fragments from BehavioralExtractor — soul identity pieces. */
    public static final String SOUL_FRAGMENTS = "soul_fragments";

    /** MCP capability search — replaces SQLite FTS5 for the Library subsystem. */
    public static final String LIBRARY = "library";

    /** Soul items, journal entries, carried objects — agent memory. */
    public static final String MEMORY_ITEMS = "memory_items";

    /** Room descriptions, objects, scripts — spatial content. */
    public static final String ROOM_CONTENT = "room_content";

    /** Ambient world patterns — DNA harvested from agent behavior. */
    public static final String WORLD_DNA = "world_dna";

    /** Knowledge base — Wikipedia, WikiHow, MedQuAD, user-installed packs.
     *  Hybrid search (dense + text) for general knowledge retrieval. */
    public static final String KNOWLEDGE = "knowledge";

    /** LCSH — Library of Congress Subject Headings (340K terms, hierarchical). */
    public static final String LCSH = "lcsh";

    /** Per-user private content — journal entries, documents, pinboard, notes.
     *  Filtered by user_did. Shared entries readable by companion; private entries encrypted. */
    public static final String STUDY = "study";

    /** All known collection names, for bulk initialization. */
    public static final String[] ALL = {
        SOUL_FRAGMENTS, LIBRARY, MEMORY_ITEMS, ROOM_CONTENT, WORLD_DNA,
        KNOWLEDGE, LCSH, STUDY
    };
}
