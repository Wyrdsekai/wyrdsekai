package org.wyrdsekai.core.mail;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everyone in this household that mail can be addressed to, by the name a person types.
 *
 * <p>Mail stores the identity, never the typed address, so renaming a zone — or a companion
 * — leaves old mail readable and replyable. The address travels beside it as display text.</p>
 *
 * <p>The node installs the real directory at boot (people from the account store, companions
 * from the registry). Without one, nothing resolves and the mail verbs say so rather than
 * delivering into a void.</p>
 */
public interface MailDirectory {

    /**
     * @param identity the canonical id mail is filed under — a person DID or a companion's
     *                 entity id. Never the typed name.
     * @param name     the name to show, as its owner spells it
     * @param kind     {@code person} or {@code companion}
     */
    record Recipient(String identity, String name, String kind) {}

    /** Resolve a typed name. Case- and spacing-insensitive; empty when nobody answers to it. */
    Optional<Recipient> byName(String name);

    /** Everyone addressable here — for "I don't know anyone called X; here is who is here". */
    List<Recipient> all();

    MailDirectory EMPTY = new MailDirectory() {
        @Override public Optional<Recipient> byName(String name) { return Optional.empty(); }
        @Override public List<Recipient> all() { return List.of(); }
    };

    /** Build one from a fixed list — what the node installs, and what tests use. */
    static MailDirectory of(List<Recipient> people) {
        var list = List.copyOf(people);
        return new MailDirectory() {
            @Override public Optional<Recipient> byName(String name) {
                if (name == null) return Optional.empty();
                var raw = name.strip();
                var key = raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
                if (key.isEmpty()) return Optional.empty();
                for (var r : list) {
                    if (r.name() != null
                        && r.name().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip().equals(key)) {
                        return Optional.of(r);
                    }
                }
                // Identities match EXACTLY: a did:key is base58, where case carries meaning,
                // so the lowercased name key must never be used to look one up.
                for (var r : list) {
                    if (raw.equals(r.identity())) return Optional.of(r);
                }
                return Optional.empty();
            }
            @Override public List<Recipient> all() { return list; }
        };
    }
}
