package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

/**
 * The household's shared, caller-agnostic resources.
 *
 * <h2>Why this exists</h2>
 * A <em>provider</em> is identity-bearing by nature: it exists to answer "what
 * may <em>I</em> see and do". A <em>resource</em> is not — the Lucene index and
 * the model belong to the household, not to whoever is holding an item.
 * {@link HouseholdItemContent} conflated the two: it shared a whole
 * {@code ItemWorldApiProviderImpl} (built in {@code Main} with the placeholder
 * identity {@code "household"}), and every caller-aware surface forwarded to it
 * evaluated its decision as that placeholder.
 *
 * <p>Live consequence, 2026-08-25: the steward asked his own fairy-tale tool
 * about Takeshi Kovacs. His 74,681 books were in his Study, his identity row was
 * correctly linked — and the search logged <em>"Study leg skipped: caller is not
 * a person and no bondholder resolves to one"</em>, because the question "whose
 * private shelves may I read?" was being asked of {@code "household"}. He got an
 * answer only because the shelf had also been published zone-wide.</p>
 *
 * <p>So: share <strong>this</strong>, never a provider. Identity-dependent
 * decisions live on the caller's own provider, which knows who is asking; the
 * caller-agnostic parts read their inputs from here. The invariant is then
 * structural rather than documentary — there is no shared provider left to ask
 * the wrong question of.</p>
 */
public final class HouseholdResources {

    private static final Logger log = LoggerFactory.getLogger(HouseholdResources.class);

    private static volatile WyrdLuceneStore luceneStore;

    private HouseholdResources() {}

    /** Boot: install the household's shared search index. Last write wins. */
    public static void register(WyrdLuceneStore store) {
        luceneStore = store;
        log.info("HouseholdResources: search index wired for caller-aware item surfaces");
    }

    /** The household search index, or {@code null} on a node that has none. */
    public static WyrdLuceneStore lucene() {
        return luceneStore;
    }

    /** Test seam. */
    public static void resetForTests() {
        luceneStore = null;
    }
}
