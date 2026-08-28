package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

/**
 * Refuses to write content owned by an identity that cannot be resolved.
 *
 * <p><b>This is the defect itself.</b> Every one of the four owner namespaces
 * found on a live household arose the same way: code needed an owner, had no way
 * to resolve one, and wrote a plausible string instead of failing —
 * {@code $(whoami)} in the ingest CLI, {@code 'local-user'} on the mobile Study
 * screen, a raw account UUID on the server. Nothing objected, so 13.7M rows were
 * written under an identifier that referred to nobody.</p>
 *
 * <p>The rule: <b>no resolvable identity means no write.</b> Loudly, not
 * silently, and not with a substitute.</p>
 *
 * <p><b>Backwards compatible by construction.</b> Until
 * {@link PersonIdentityProvisioner#init} has been called this passes everything
 * through unchanged, so an install that has not yet migrated behaves exactly as
 * before rather than half-refusing writes.</p>
 */
public final class StudyOwnerGuard {

    private static final Logger log = LoggerFactory.getLogger(StudyOwnerGuard.class);

    /** Owner strings that are known placeholders rather than identities. */
    private static final Set<String> KNOWN_PLACEHOLDERS =
        Set.of("local-user", "unknown", "anonymous", "default", "user", "");

    private StudyOwnerGuard() {}

    /** Thrown when content would be written under an owner nobody can resolve. */
    public static class UnresolvableOwnerException extends IllegalArgumentException {
        public UnresolvableOwnerException(String message) {
            super(message);
        }
    }

    /**
     * Resolve an owner for a write, or refuse.
     *
     * @param owner whatever the caller believes identifies the owner
     * @return the canonical person DID to store
     * @throws UnresolvableOwnerException when it cannot be resolved to a person
     */
    public static String require(String owner) {
        if (!PersonIdentityProvisioner.isEnabled()) {
            // Not yet migrated — preserve existing behaviour exactly.
            return owner;
        }
        if (owner == null || owner.isBlank()) {
            throw new UnresolvableOwnerException(
                "Refusing to write Study content with no owner. "
                    + "An owner must resolve to a person —");
        }
        var store = PersonIdentityProvisioner.identities().orElse(null);
        boolean anyPeople = store != null && !store.listDids().isEmpty();
        if (anyPeople && KNOWN_PLACEHOLDERS.contains(owner.trim().toLowerCase())) {
            throw new UnresolvableOwnerException(
                "Refusing to write Study content owned by the placeholder '" + owner
                    + "'. This is not a person. Pass a real identity.");
        }

        var resolver = PersonIdentityProvisioner.resolver().orElse(null);
        if (resolver == null) return owner;

        // If this node holds NO people yet, there is nothing to resolve against
        // and refusing every write would brick a fresh install before its first
        // account exists (zone master present, nobody registered). Enforcement
        // begins once there is at least one person to be wrong about.
        var identities = PersonIdentityProvisioner.identities().orElse(null);
        if (identities == null || identities.listDids().isEmpty()) return owner;

        var resolved = resolver.resolve(owner).orElse(null);
        if (resolved != null) return resolved;

        // An AGENT is not a person, and never resolves to one — but it is still a real,
        // minted, registered identity, which is the only thing this guard actually cares
        // about. Refusing it conflated "not a person" with "not resolvable".
        //
        // Live cost (household node, 2026-08-20): every Study write the companion made
        // under her own DID was refused, including her own journal. The raw exception was
        // handed back into a ReAct loop as a tool failure and she narrated it aloud —
        // "The tool refused... There is a key here, and I will not cross it" — which is
        // her describing the did:key in the error text. It also gives a second,
        // independent cause for the 08-19 finding that 107 write_journal enactments
        // produced zero journal entries: even when the tool DID run, the write was
        // refused here.
        //
        // Deliberately narrow: only a DID already present in the agent identity store
        // passes. A guessed string ($(whoami), local-user, a raw UUID) still fails
        // exactly as before — those are the four owner namespaces this guard was built
        // for, and none of them is a registered agent.
        if (isRegisteredAgent(owner)) {
            return owner;
        }

        throw new UnresolvableOwnerException(
            "Refusing to write Study content owned by '" + owner + "' — it is neither a "
                + "person on this node nor a registered agent. Writing it anyway is how "
                + "one human ends up owning content under several different strings.");
    }

    /** Is this owner a DID this node has actually minted or registered for an agent? */
    private static boolean isRegisteredAgent(String owner) {
        if (owner == null || owner.isBlank()) return false;
        try {
            return AgentIdentityProvisioner.identities()
                .map(store -> store.exists(owner.trim()))
                .orElse(false);
        } catch (Exception e) {
            // A guard that throws for an unrelated reason must not become an outage.
            log.debug("Agent-identity check failed for '{}': {}", owner, e.toString());
            return false;
        }
    }

    /**
     * Non-throwing form for read paths, where an unresolvable owner should
     * simply match nothing rather than raise.
     */
    public static String forRead(String owner) {
        if (!PersonIdentityProvisioner.isEnabled() || owner == null) return owner;
        return PersonIdentityProvisioner.resolver()
            .flatMap(r -> r.resolve(owner))
            .orElse(owner);
    }

    /** Whether this owner would be accepted for a write. */
    public static boolean isAcceptable(String owner) {
        try {
            require(owner);
            return true;
        } catch (UnresolvableOwnerException e) {
            log.debug("Owner rejected: {}", e.getMessage());
            return false;
        }
    }
}
