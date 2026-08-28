package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.home.ActionGrants;
import org.wyrdsekai.core.home.HomeClients;
import org.wyrdsekai.core.identity.PersonIdentityProvisioner;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.util.ArrayList;
import java.util.List;

/**
 * A person's reach into the household's shelves: their own, plus anything the
 * zone owner has granted them.
 *
 * <h2>Why a person needs no grant from themselves</h2>
 * The Study leg began life knowing exactly one question — "who is my
 * companion's bondholder?" — because a companion was the only thing that ever
 * asked. When a PERSON picked up an item, that question had no answer and the
 * search returned nothing: on the home node, 2026-08-25, the steward's 74,681
 * books were unreachable from his own hands while generic pack rows answered
 * instead.
 *
 * <p>Answering it needs the caller's identity, which is why this is constructed
 * per caller rather than shared. The identifier may be a person DID, a local
 * credential id or a username — {@link PersonIdentityProvisioner}'s resolver
 * maps all three; anything it cannot map reaches nothing.</p>
 */
public final class PersonStudyReach implements StudyReach {

    private static final Logger log = LoggerFactory.getLogger(PersonStudyReach.class);

    private final String identifier;

    private PersonStudyReach(String identifier) {
        this.identifier = identifier;
    }

    /** Reach for whoever {@code identifier} resolves to; never {@code null}. */
    public static StudyReach forPerson(String identifier) {
        if (identifier == null || identifier.isBlank()) return StudyReach.NONE;
        return new PersonStudyReach(identifier);
    }

    /**
     * The person {@code identifier} names, or {@code null} if it names none.
     *
     * <p>With provisioning on, the resolver maps a DID, a local credential id
     * or a username to the person. With it off there is no mapping to make: an
     * identifier that is ALREADY a person DID is that person — it comes from
     * the authenticated session, not from anything the caller typed. Without
     * that second case an un-provisioned node reaches no shelf at all.</p>
     *
     * <p>One place decides this. It used to be decided twice — once here and
     * once inside the companion provider's own study leg — which is how the two
     * came to disagree.</p>
     */
    public static String resolvePerson(String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        var resolver = PersonIdentityProvisioner.resolver().orElse(null);
        return resolver != null
            ? resolver.resolve(identifier).orElse(null)
            : (identifier.startsWith("did:") ? identifier : null);
    }

    @Override
    public List<WyrdLuceneStore.SearchResult> search(String query, int limit) {
        var store = HouseholdResources.lucene();
        if (store == null || query == null || query.isBlank()) return List.of();

        var selfDid = resolvePerson(identifier);
        if (selfDid == null) {
            log.debug("Study leg: '{}' does not resolve to a person — pack results only",
                identifier);
            return List.of();
        }
        try {
            // The consent ORACLE, not the field: StudyService.hasAccess fails
            // closed on a null HomeClient, and a grant check with no way to
            // check grants must not read as "no grant" (2026-08-07).
            var svc = new StudyService(store, HomeClients.get());
            // Their own shelves — document-typed, so private journals stay out.
            var hits = new ArrayList<>(svc.searchAllDocuments(selfDid, query, limit));

            // A person who is NOT this household's owner may still hold read
            // grants on the owner's collections — a second household member, a
            // guest from another zone. The consent machinery could always say
            // yes; the retrieval path never asked. Ungranted collections return
            // nothing, exactly as for a companion.
            var zoneOwner = ActionGrants.get() != null
                ? ActionGrants.get().fallbackOwnerDid() : null;
            if (zoneOwner != null && !zoneOwner.equals(selfDid)) {
                hits.addAll(svc.searchAsCompanion(zoneOwner, selfDid, query, limit));
            }
            log.info("Study leg (person) for '{}': {} hits (self={}, viaZoneOwner={})",
                query, hits.size(), selfDid, zoneOwner != null && !zoneOwner.equals(selfDid));
            return List.copyOf(hits);
        } catch (RuntimeException e) {
            log.warn("Person study search failed for '{}': {}", query, e.toString());
            return List.of();
        }
    }
}
