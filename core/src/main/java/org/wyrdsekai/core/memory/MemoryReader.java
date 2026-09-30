package org.wyrdsekai.core.memory;

import org.wyrdsekai.core.identity.PersonIds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The person a turn answers, as far as memory is concerned: what may be read back into it.
 *
 * <p>A turn that answers a person reads that person's own private memories and everything
 * open. Memories whose teller is unknown (written before origins were recorded) are read
 * only in turns with her bondholder. A turn that answers no one (her own time, a peer
 * companion) reads open memories only. See {@link MemoryOrigin}.</p>
 *
 * @param personIds  every identifier the person is known by here (the id they arrived under
 *                   and its canonical person DID); empty for no one
 * @param bondholder whether the person is her bondholder
 */
public record MemoryReader(Set<String> personIds, boolean bondholder) {

    /** Her own time, a peer, or anything that answers no person: open memories only. */
    public static final MemoryReader NO_ONE = new MemoryReader(Set.of(), false);

    public MemoryReader {
        personIds = personIds == null ? Set.of() : Set.copyOf(personIds);
    }

    /** The reader for a turn that answers this person. */
    public static MemoryReader of(String personId, boolean bondholder) {
        if (personId == null || personId.isBlank()) return NO_ONE;
        var ids = new LinkedHashSet<String>();
        ids.add(personId);
        try {
            var canonical = PersonIds.canonical(personId);
            if (canonical != null && !canonical.isBlank()) ids.add(canonical);
        } catch (RuntimeException ignored) {
            // unresolvable: the id as given is all we have
        }
        return new MemoryReader(ids, bondholder);
    }

    public boolean isSomeone() {
        return !personIds.isEmpty();
    }

    /** May this memory be read back into the turn? */
    public boolean mayRead(MemoryOrigin origin) {
        if (origin == null) origin = MemoryOrigin.UNKNOWN;
        return switch (origin.visibility()) {
            case OPEN -> true;
            case PRIVATE -> isTeller(origin.tellerDid());
            case UNKNOWN -> bondholder;
        };
    }

    /** May a memory with this stored audience value be read back? A missing value is unknown. */
    public boolean mayReadAudience(String audience) {
        return mayRead(MemoryOrigin.fromAudience(audience));
    }

    /**
     * Is this memory something the person told her themselves? Used for what is known ABOUT
     * the person (the facts block, recall of "what did I tell you"): another person's open
     * words are not facts about this one.
     */
    public boolean told(MemoryOrigin origin) {
        if (origin == null) origin = MemoryOrigin.UNKNOWN;
        if (origin.visibility() == MemoryOrigin.Visibility.UNKNOWN) return bondholder;
        return isTeller(origin.tellerDid());
    }

    /** The audience values a search may match for this reader. */
    public List<String> audiences() {
        var out = new ArrayList<String>();
        out.add(MemoryOrigin.AUDIENCE_OPEN);
        for (var id : personIds) out.add(MemoryOrigin.AUDIENCE_PERSON + id);
        if (bondholder) out.add(MemoryOrigin.AUDIENCE_BONDHOLDER);
        return out;
    }

    private boolean isTeller(String teller) {
        if (teller == null || personIds.isEmpty()) return false;
        if (personIds.contains(teller)) return true;
        for (var id : personIds) {
            if (PersonIds.samePerson(id, teller)) return true;
        }
        return false;
    }
}
