package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * A single memory node in compacted memory.
 * A-Mem-style linked memories with impression weighting (section 109.3)
 * and formative impression protection (section 109.4).
 *
 * Impression depth determines decay resistance: emotionally charged
 * memories resist consolidation (amygdala-hippocampal analog).
 * Formative memories NEVER decay, NEVER merge, NEVER get pruned.
 *
 * @param id              Unique identifier
 * @param content         The memory text
 * @param keywords        A-Mem keywords for retrieval
 * @param importance      0.0-1.0, decays unless reinforced
 * @param impressionDepth Emotional charge at encoding (section 109.3).
 *                        Higher = deeper memory = slower decay.
 * @param formative       True = load-bearing identity fragment (section 109.4).
 *                        NEVER consolidated away by the Forge.
 * @param primaryEmotion  Dominant emotion at encoding (grief, joy, fear, etc.)
 * @param lastAccessed    When this memory was last retrieved
 * @param accessCount     How many times retrieved
 * @param originLocale    BCP 47 language tag of the memory's origin language (§104.2)
 */
public record MemoryNode(
    @JsonProperty("id") String id,
    @JsonProperty("content") String content,
    @JsonProperty("keywords") List<String> keywords,
    @JsonProperty("importance") float importance,
    @JsonProperty("impressionDepth") float impressionDepth,
    @JsonProperty("formative") boolean formative,
    @JsonProperty("primaryEmotion") String primaryEmotion,
    @JsonProperty("lastAccessed") Instant lastAccessed,
    @JsonProperty("accessCount") int accessCount,
    @JsonProperty("originLocale") String originLocale,
    /** The language she spoke this line in, for her own lines ("I said: …"): the language of the
     *  turn, so a reply in the person's language is not read as drift from the household's. Null
     *  on nodes written before 0.5.6 and on what others said. */
    @JsonProperty("spokenIn") String spokenIn
) {
    @JsonCreator
    public MemoryNode {}

    /** A node with no record of the language it was spoken in (every caller before 0.5.6). */
    public MemoryNode(String id, String content, List<String> keywords, float importance,
                      float impressionDepth, boolean formative, String primaryEmotion,
                      Instant lastAccessed, int accessCount, String originLocale) {
        this(id, content, keywords, importance, impressionDepth, formative, primaryEmotion,
            lastAccessed, accessCount, originLocale, null);
    }

    /** The same node, with the language it was spoken in. */
    public MemoryNode withSpokenIn(String lang) {
        return new MemoryNode(id, content, keywords, importance, impressionDepth, formative,
            primaryEmotion, lastAccessed, accessCount, originLocale, lang);
    }

    /** Create a neutral memory with no emotional charge (defaults to "en" locale). */
    public static MemoryNode neutral(String id, String content, List<String> keywords) {
        return new MemoryNode(id, content, keywords, 0.5f, 0.0f, false, "none",
            Instant.now(), 0, "en");
    }

    /** Create a neutral memory with explicit locale (§104.2). */
    public static MemoryNode neutral(String id, String content, List<String> keywords,
                                      String locale) {
        return new MemoryNode(id, content, keywords, 0.5f, 0.0f, false, "none",
            Instant.now(), 0, locale);
    }

    /** Create a formative memory that will never be consolidated (defaults to "en" locale). */
    public static MemoryNode formative(String id, String content, List<String> keywords,
                                        String emotion, float impressionDepth) {
        return new MemoryNode(id, content, keywords, 1.0f, impressionDepth, true, emotion,
            Instant.now(), 0, "en");
    }

    /** Create a formative memory with explicit locale (§104.2). */
    public static MemoryNode formative(String id, String content, List<String> keywords,
                                        String emotion, float impressionDepth, String locale) {
        return new MemoryNode(id, content, keywords, 1.0f, impressionDepth, true, emotion,
            Instant.now(), 0, locale);
    }

    /** Decay importance, respecting impression depth and formative flag. */
    public MemoryNode decayed(float decayRate) {
        if (formative) return this; // formative memories NEVER decay
        // Higher impression depth = slower decay
        float effectiveDecay = decayRate * (1.0f - impressionDepth * 0.8f);
        float newImportance = Math.max(0.0f, importance - effectiveDecay);
        return new MemoryNode(id, content, keywords, newImportance, impressionDepth,
            false, primaryEmotion, lastAccessed, accessCount, originLocale, spokenIn);
    }

    /** Reinforce by access. */
    public MemoryNode accessed() {
        float boosted = Math.min(1.0f, importance + 0.1f);
        return new MemoryNode(id, content, keywords, boosted, impressionDepth,
            formative, primaryEmotion, Instant.now(), accessCount + 1, originLocale, spokenIn);
    }
}
