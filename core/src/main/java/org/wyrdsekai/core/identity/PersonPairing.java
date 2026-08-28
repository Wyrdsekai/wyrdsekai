package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Lets a second device or node JOIN an existing person instead of minting a new one.
 *
 * <p><b>Why this is not optional.</b> {@link PersonIdentityProvisioner} mints a
 * person at {@code register()}, which is right exactly once per human — and
 * wrong once they add a second machine. Without a join flow, registering on a
 * phone and on a household box makes one human into two people, each with their
 * own Study, their own bonds, and their own half of the content. Merging them
 * afterwards is a {@link RebindAttestation} rebind under worse conditions, with
 * irreplaceable journal entries on both sides.</p>
 *
 * <p><b>The shape.</b> The node that already holds the person issues a
 * short-lived invite <em>signed by that person</em>. The joining side redeems
 * it, which binds its local credential to the existing DID. No new identity is
 * created and no key material crosses the wire — the person's private key never
 * leaves the node that holds it.</p>
 *
 * <p>Single-use and time-boxed, because an invite that adopts an identity is
 * exactly as sensitive as the identity.</p>
 */
public final class PersonPairing {

    private static final Logger log = LoggerFactory.getLogger(PersonPairing.class);

    /** Invites are deliberately short-lived — this adopts a person, not a session. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private PersonPairing() {}

    /**
     * A pairing invite. Carries no key material — only a claim, signed by the
     * person, that a bearer may bind a local credential to them.
     *
     * @param personDid the person being joined
     * @param nonce     single-use random value
     * @param expiresAt when the invite stops being valid
     * @param signature Ed25519 signature by the person over {@link #canonicalBytes}
     */
    public record Invite(String personDid, String nonce, Instant expiresAt, byte[] signature) {

        public Invite {
            Objects.requireNonNull(personDid, "personDid");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(signature, "signature");
        }

        public byte[] canonicalBytes() {
            return canonical(personDid, nonce, expiresAt);
        }

        public boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }

        /** Compact transferable form — safe to show as a QR code or read aloud. */
        public String encode() {
            return personDid + "|" + nonce + "|" + expiresAt.getEpochSecond() + "|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        }

        public static Optional<Invite> decode(String s) {
            if (s == null) return Optional.empty();
            var parts = s.split("\\|");
            if (parts.length != 4) return Optional.empty();
            try {
                return Optional.of(new Invite(parts[0], parts[1],
                    Instant.ofEpochSecond(Long.parseLong(parts[2])),
                    Base64.getUrlDecoder().decode(parts[3])));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
    }

    private static byte[] canonical(String did, String nonce, Instant expiresAt) {
        return ("wyrdsekai:person-pairing:v1|" + did + "|" + nonce + "|"
            + expiresAt.getEpochSecond()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Issue an invite to join an existing person.
     *
     * @param person          the person being joined — must be held on this node
     * @param householdSecret unlocks their private key to sign the invite
     * @param ttl             how long the invite stays valid
     */
    public static Invite issue(PersonIdentity person, byte[] householdSecret, Duration ttl)
            throws Exception {
        var expiresAt = Instant.now().plus(ttl == null ? DEFAULT_TTL : ttl);
        var nonceBytes = new byte[16];
        SecureRandom.getInstanceStrong().nextBytes(nonceBytes);
        var nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);

        var sig = person.sign(canonical(person.did(), nonce, expiresAt), householdSecret);
        log.info("Pairing invite issued for person {} (expires {})", person.did(), expiresAt);
        return new Invite(person.did(), nonce, expiresAt, sig);
    }

    /** Verify an invite really came from the person it names, and is still live. */
    public static boolean verify(Invite invite, PersonIdentity person, Instant now) {
        if (invite == null || person == null) return false;
        if (!person.did().equals(invite.personDid())) return false;
        if (invite.isExpired(now)) {
            log.debug("Pairing invite for {} has expired", invite.personDid());
            return false;
        }
        return person.verify(invite.canonicalBytes(), invite.signature());
    }

    /**
     * Redeem an invite: bind a local credential to the existing person.
     *
     * <p><b>Mints nothing.</b> That is the entire point — the human already
     * exists, and this machine is simply learning who they are.</p>
     *
     * @param invite       the invite presented
     * @param localUserId  the local credential ({@code users.id} or username) to bind
     * @param identities   this node's identity store
     * @param resolver     this node's resolver
     * @return the person DID now bound, or empty if the invite did not verify
     */
    public static Optional<String> redeem(Invite invite, String localUserId,
                                          PersonIdentityStore identities,
                                          PersonIdentityResolver resolver) {
        if (invite == null || localUserId == null) return Optional.empty();

        var person = identities.findByDid(invite.personDid()).orElse(null);
        if (person == null) {
            log.warn("Pairing refused — person {} is not known to this node", invite.personDid());
            return Optional.empty();
        }
        if (!verify(invite, person, Instant.now())) {
            log.warn("Pairing refused — invite for {} did not verify", invite.personDid());
            return Optional.empty();
        }

        resolver.linkUserToPerson(localUserId, person.did());
        log.info("Local credential '{}' joined existing person {}", localUserId, person.did());
        return Optional.of(person.did());
    }
}
