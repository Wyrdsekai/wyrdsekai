package org.wyrdsekai.core.item;

import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.util.List;

/**
 * How far into the household's private shelves <em>this caller</em> may read.
 *
 * <h2>The seam</h2>
 * Knowledge search has two legs. The pack leg — Wikipedia, installed packs, a
 * shelf published zone-wide — is the same for everyone and needs no identity.
 * The Study leg is an authorisation question: <em>whose</em> private documents
 * may be returned. Those two were fused inside one method on one shared
 * provider, so the authorisation question was answered by whichever identity
 * that object happened to carry.
 *
 * <p>Making the reach a separate, caller-supplied thing is what keeps that from
 * recurring. An object with no caller cannot invent one — it passes
 * {@link #NONE}, and the search is honestly pack-only. A caller's own provider
 * passes the reach that its identity earns:</p>
 *
 * <ul>
 *   <li>a person reading their own shelves needs no grant from themselves,
 *       and may additionally hold grants on the zone owner's collections;</li>
 *   <li>a companion reads through its bondholder's consent, per collection.</li>
 * </ul>
 *
 * @see KnowledgeSearch
 * @see HouseholdResources
 */
@FunctionalInterface
public interface StudyReach {

    /** Study documents this caller may see for {@code query}, newest ranking aside. */
    List<WyrdLuceneStore.SearchResult> search(String query, int limit);

    /**
     * No private reach at all — the honest answer when nothing knows who is
     * asking. Shared, caller-agnostic providers use this.
     */
    StudyReach NONE = (query, limit) -> List.of();
}
