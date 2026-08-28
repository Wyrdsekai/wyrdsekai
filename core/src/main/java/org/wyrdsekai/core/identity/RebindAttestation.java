package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A signed statement that one identity has become another.
 *
 * <p><b>Why an attestation rather than a rewrite.</b> Two kinds of data cannot
 * simply have their identity field updated:</p>
 *
 * <ul>
 *   <li><b>Signed artifacts.</b> {@code SoulVerifier} checks an Ed25519
 *       signature over {@code manifest.canonicalBytes()}. Editing an identity
 *       inside signed material breaks the signature, and re-signing needs the
 *       old key — which in a compromise or loss is exactly what is gone.</li>
 *   <li><b>Audit history.</b> Rewriting {@code audit_log.actor} to a new
 *       identity asserts that a different person took an action. That is
 *       falsifying the record. An audit log should say what was true at the
 *       time.</li>
 * </ul>
 *
 * <p>So rebinding is <b>selective</b>: live references (bonds, residency,
 * ownership, content envelopes) re-point, while signed and historical records
 * keep the old identity and are read <em>through</em> a chain of attestations.
 * The attestation is itself an audit event.</p>
 *
 * <p><b>Signed by the OLD identity</b>, while its key still exists — that is
 * what makes the claim meaningful rather than something anyone could assert.
 * It also propagates: another zone learns about a rebind by receiving the
 * attestation, instead of needing a coordinated rewrite nobody can guarantee.</p>
 *
 * @param fromDid   the identity being left behind
 * @param toDid     the identity taking over
 * @param issuedAt  when the claim was made
 * @param signature Ed25519 signature by {@code fromDid} over {@link #canonicalBytes}
 */
public record RebindAttestation(String fromDid, String toDid, Instant issuedAt,
                                byte[] signature, String attesterDid) {

    /** Self-issued: the old identity declaring what it became. */
    public RebindAttestation(String fromDid, String toDid, Instant issuedAt, byte[] signature) {
        this(fromDid, toDid, issuedAt, signature, null);
    }

    private static final Logger log = LoggerFactory.getLogger(RebindAttestation.class);

    public RebindAttestation {
        Objects.requireNonNull(fromDid, "fromDid");
        Objects.requireNonNull(toDid, "toDid");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(signature, "signature");
        if (fromDid.equals(toDid)) {
            throw new IllegalArgumentException("A rebind must change identity: " + fromDid);
        }
    }

    /**
     * Issue an attestation, signed by the identity being left behind.
     *
     * @param from            the old identity — must still hold its key
     * @param to              the new identity
     * @param householdSecret unlocks the old identity's private key
     */
    public static RebindAttestation issue(PersonIdentity from, PersonIdentity to,
                                          byte[] householdSecret) throws Exception {
        if (from.did().equals(to.did())) {
            throw new IllegalArgumentException("A rebind must change identity: " + from.did());
        }
        var issuedAt = Instant.now();
        var payload = canonicalBytes(from.did(), to.did(), issuedAt);
        var sig = from.sign(payload, householdSecret);
        log.info("Rebind attested: {} -> {}", from.did(), to.did());
        return new RebindAttestation(from.did(), to.did(), issuedAt, sig, null);
    }

    /**
     * The same claim, made by an AGENT about itself.
     *
     * <p>Identical payload to {@link #issue} — a self-declaration is a
     * self-declaration whoever makes it, and a reader should not have to know
     * whether the signer was a person or a companion to check it. Companions
     * could not make this claim at all until {@link AgentIdentityStore} existed;
     * one born before that has no key and must still be
     * {@linkplain #issueWitnessed witnessed}.</p>
     *
     * @param from            the old identity — must still hold its key
     * @param toDid           the new identity
     * @param householdSecret unlocks the old identity's private key
     */
    public static RebindAttestation issueSelf(AgentIdentity from, String toDid,
                                              byte[] householdSecret) throws Exception {
        if (toDid == null || from.did().equals(toDid)) {
            throw new IllegalArgumentException("A rebind must change identity: " + from.did());
        }
        if (from.privateKeyEncrypted() == null || from.privateKeyEncrypted().length == 0) {
            throw new IllegalStateException(
                "Agent " + from.did() + " holds no private key — it cannot declare this itself. "
                    + "Use issueWitnessed and record who vouched.");
        }
        var issuedAt = Instant.now();
        var payload = canonicalBytes(from.did(), toDid, issuedAt);
        var sig = Base64.getDecoder().decode(from.sign(payload, householdSecret));
        log.info("Rebind self-attested by agent: {} -> {}", from.did(), toDid);
        return new RebindAttestation(from.did(), toDid, issuedAt, sig, null);
    }

    /** Verify a self-issued attestation against the old AGENT's public key. */
    public boolean verify(AgentIdentity from) {
        if (attesterDid != null) return false;   // witnessed, not self-issued
        if (!from.did().equals(fromDid)) return false;
        return from.verify(canonicalBytes(fromDid, toDid, issuedAt),
            Base64.getEncoder().encodeToString(signature));
    }

    /**
     * Attest that one identity became another, signed by a WITNESS rather than
     * by the identity being left behind.
     *
     * <p><b>Why this is a different claim, and must not verify as the same one.</b>
     * {@link #issue} means "I declare I became them" — only the old identity can
     * say it, which is exactly what makes it worth anything. This means "I saw
     * that this became that", which is a weaker and honest thing to record when
     * self-attestation is impossible.</p>
     *
     * <p>It was impossible for every companion until {@link AgentIdentityStore}:
     * {@code CompanionActor} minted a DID with {@code DidKey.generate()}, kept the
     * public half, and let the private key fall out of scope — the same defect the
     * person-identity work fixed for {@code PlayerAccount.create()}, left standing
     * on the agent side. There was no {@code agent_identities} table at all.
     * Companions born since keep their keys and should use {@link #issueSelf};
     * those born before still cannot, and their key cannot be recovered, so
     * recording the steward's witness remains what can honestly be recorded.
     * Pretending she signed it would be a lie in the audit trail.</p>
     *
     * <p>The signed payload names the attester and uses a distinct version tag,
     * so {@link #verify} cannot accept a witnessed attestation as a self-issued
     * one. A reader can always tell which kind of claim it is holding.</p>
     *
     * @param attester who is vouching — needs a real key, so in practice a person
     */
    public static RebindAttestation issueWitnessed(PersonIdentity attester,
                                                   String fromDid, String toDid,
                                                   byte[] householdSecret) throws Exception {
        if (fromDid == null || toDid == null || fromDid.equals(toDid)) {
            throw new IllegalArgumentException("A rebind must change identity: " + fromDid);
        }
        var issuedAt = Instant.now();
        var payload = witnessedBytes(fromDid, toDid, issuedAt, attester.did());
        var sig = attester.sign(payload, householdSecret);
        log.info("Rebind WITNESSED by {}: {} -> {}", attester.did(), fromDid, toDid);
        return new RebindAttestation(fromDid, toDid, issuedAt, sig, attester.did());
    }

    /**
     * Verify this attestation against the old identity's public key.
     *
     * @param from the identity that supposedly issued it
     */
    public boolean verify(PersonIdentity from) {
        if (attesterDid != null) return false;   // witnessed, not self-issued
        if (!from.did().equals(fromDid)) return false;
        return from.verify(canonicalBytes(fromDid, toDid, issuedAt), signature);
    }

    /** Verify a witnessed attestation against the WITNESS's public key. */
    public boolean verifyWitnessed(PersonIdentity attester) {
        if (attesterDid == null || !attesterDid.equals(attester.did())) return false;
        return attester.verify(
            witnessedBytes(fromDid, toDid, issuedAt, attesterDid), signature);
    }

    /** True when this records someone else's observation, not a self-declaration. */
    public boolean isWitnessed() {
        return attesterDid != null;
    }

    private static byte[] witnessedBytes(String from, String to, Instant at, String by) {
        return ("wyrdsekai:rebind:v1:witnessed|" + from + "|" + to + "|"
            + at.getEpochSecond() + "|" + by).getBytes(StandardCharsets.UTF_8);
    }

    /** Deterministic bytes covered by the signature. */
    public byte[] canonicalBytes() {
        return canonicalBytes(fromDid, toDid, issuedAt);
    }

    private static byte[] canonicalBytes(String from, String to, Instant at) {
        return ("wyrdsekai:rebind:v1|" + from + "|" + to + "|" + at.getEpochSecond())
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Follow a chain of attestations from any historical identity to the current one.
     *
     * <p>This is how an audit row written years ago under an old identity still
     * resolves to the person it refers to, without the row ever being edited.</p>
     *
     * @param start        an identity that may since have been rebound
     * @param attestations every attestation known to this node
     * @return the current identity, or {@code start} if it was never rebound
     */
    public static String resolveCurrent(String start, Collection<RebindAttestation> attestations) {
        if (start == null) return null;
        var current = start;
        var seen = new HashSet<String>();
        seen.add(current);

        boolean moved = true;
        while (moved) {
            moved = false;
            for (var a : attestations) {
                if (a.fromDid().equals(current)) {
                    if (!seen.add(a.toDid())) {
                        // A cycle. Refuse to loop; the chain is corrupt.
                        log.warn("Rebind chain cycles at {} — stopping", a.toDid());
                        return current;
                    }
                    current = a.toDid();
                    moved = true;
                    break;
                }
            }
        }
        return current;
    }

    /**
     * The full chain from an identity to its current form, oldest first.
     * Useful for showing a person (or a companion) what actually happened.
     */
    public static List<RebindAttestation> chain(String start,
                                                Collection<RebindAttestation> attestations) {
        var out = new ArrayList<RebindAttestation>();
        var current = start;
        var seen = new HashSet<String>();
        seen.add(current);

        boolean moved = true;
        while (moved) {
            moved = false;
            for (var a : attestations) {
                if (a.fromDid().equals(current) && seen.add(a.toDid())) {
                    out.add(a);
                    current = a.toDid();
                    moved = true;
                    break;
                }
            }
        }
        return out;
    }

    /** Find an attestation that explains how {@code did} was reached, if any. */
    public static Optional<RebindAttestation> attestationTo(
            String did, Collection<RebindAttestation> attestations) {
        return attestations.stream().filter(a -> a.toDid().equals(did)).findFirst();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RebindAttestation other)) return false;
        return fromDid.equals(other.fromDid)
            && toDid.equals(other.toDid)
            && issuedAt.getEpochSecond() == other.issuedAt.getEpochSecond()
            && Arrays.equals(signature, other.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromDid, toDid, issuedAt.getEpochSecond(), Arrays.hashCode(signature));
    }

    @Override
    public String toString() {
        return "RebindAttestation[" + fromDid + " -> " + toDid + " at " + issuedAt + "]";
    }
}
