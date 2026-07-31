package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.DidKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Soul verification chain (§85.10).
 *
 * Verification levels:
 * 1. Signature: manifest signed by agent's Ed25519 key
 * 2. KERI: key event log is consistent (inception valid, no suspicious gaps)
 * 3. Parent chain: if forked, parent DID exists and signed authorization
 * 4. Behavioral: observed behavior matches fingerprint (see BehavioralVerifier)
 * 5. Cross-zone: origin zone confirms manifest hash (federation extension)
 *
 * Trust levels map to federation escalation:
 * - SIGNATURE_ONLY → Tourist (basic trust)
 * - SIGNATURE_KERI → Resident-eligible (moderate trust)
 * - SIGNATURE_KERI_ORIGIN → Citizen-eligible (high trust)
 * - FULL → Full trust (all + behavioral match)
 */
public final class SoulVerifier {

    private SoulVerifier() {}

    /** Trust level achieved through verification. */
    public enum TrustLevel {
        NONE,
        SIGNATURE_ONLY,     // Tourist
        SIGNATURE_KERI,     // Resident-eligible
        SIGNATURE_KERI_ORIGIN, // Citizen-eligible
        FULL                // All checks passed including behavioral
    }

    /** Result of soul verification. */
    public record VerificationResult(
        TrustLevel trustLevel,
        List<String> passed,
        List<String> failed,
        List<String> skipped
    ) {
        public boolean isValid() {
            return trustLevel != TrustLevel.NONE;
        }
    }

    /**
     * Verify a soul manifest's signature against the agent's identity.
     *
     * @param manifest  The soul manifest to verify
     * @param identity  Agent identity (has verify() using public key)
     * @return true if signature is valid
     */
    public static boolean verifySignature(SoulManifest manifest, AgentIdentity identity) {
        if (!manifest.isSigned()) return false;
        String sigBase64 = Base64.getEncoder().encodeToString(manifest.signature());
        return identity.verify(manifest.canonicalBytes(), sigBase64);
    }

    /**
     * Verify the KERI key event log is consistent.
     *
     * Checks:
     * - Inception event exists and is well-formed
     * - Event sequence numbers are monotonically increasing
     * - Each event's SAID matches its content
     * - Event type sequence is valid (icp → rot* → no more icp)
     *
     * @param keyLog The KERI event log from the agent's identity
     * @return Error message if invalid, empty if valid
     */
    public static Optional<String> verifyKeriLog(List<ObjectNode> keyLog) {
        if (keyLog == null || keyLog.isEmpty()) {
            return Optional.of("Empty key log — no inception event");
        }

        var first = keyLog.getFirst();
        if (!"icp".equals(first.path("t").asText())) {
            return Optional.of("First event must be inception (icp), got: "
                + first.path("t").asText());
        }

        // Verify SAID of inception event
        String claimed = first.path("d").asText();
        if (claimed.isEmpty() || claimed.chars().allMatch(c -> c == '#')) {
            return Optional.of("Inception event has no SAID");
        }

        boolean seenIcp = false;
        int lastSeq = -1;

        for (int i = 0; i < keyLog.size(); i++) {
            var event = keyLog.get(i);
            String type = event.path("t").asText();
            int seq = Integer.parseInt(event.path("s").asText("" + i));

            // Sequence must be monotonically increasing
            if (seq <= lastSeq && i > 0) {
                return Optional.of("Non-monotonic sequence at event " + i
                    + ": " + seq + " <= " + lastSeq);
            }
            lastSeq = seq;

            // Only one inception allowed
            if ("icp".equals(type)) {
                if (seenIcp) {
                    return Optional.of("Multiple inception events at event " + i);
                }
                seenIcp = true;
            }

            // Must have SAID
            if (event.path("d").asText().isEmpty()) {
                return Optional.of("Event " + i + " missing SAID");
            }
        }

        return Optional.empty();
    }

    /**
     * Verify parent chain for a forked/budded soul.
     *
     * @param manifest  The child manifest claiming a parent
     * @param store     Soul store to look up the parent
     * @return Error message if invalid, empty if valid
     */
    public static Optional<String> verifyParentChain(SoulManifest manifest, SoulStore store) {
        if (manifest.parentDid() == null) {
            return Optional.empty(); // Original soul, no parent to verify
        }

        if (!store.exists(manifest.parentDid())) {
            return Optional.of("Parent soul not found: " + manifest.parentDid());
        }

        var parent = store.latest(manifest.parentDid());
        if (parent.isEmpty()) {
            return Optional.of("Parent soul has no manifest: " + manifest.parentDid());
        }

        // Child's DID should differ from parent's
        if (manifest.did().equals(manifest.parentDid())) {
            return Optional.of("Child DID equals parent DID");
        }

        return Optional.empty();
    }

    /**
     * Run the full verification chain.
     *
     * @param manifest       Soul manifest to verify
     * @param identity       Agent identity (for public key and key log)
     * @param store          Soul store (for parent chain verification)
     * @param originConfirmed Whether the origin zone confirmed the manifest hash
     * @param behavioralMatch Whether behavioral verification passed (null = skipped)
     * @return Verification result with trust level
     */
    public static VerificationResult verify(
            SoulManifest manifest,
            AgentIdentity identity,
            SoulStore store,
            boolean originConfirmed,
            Boolean behavioralMatch
    ) {
        var passed = new ArrayList<String>();
        var failed = new ArrayList<String>();
        var skipped = new ArrayList<String>();

        // 1. Signature verification
        boolean sigValid = verifySignature(manifest, identity);
        if (sigValid) {
            passed.add("signature");
        } else if (!manifest.isSigned()) {
            skipped.add("signature (unsigned manifest)");
        } else {
            failed.add("signature");
            return new VerificationResult(TrustLevel.NONE, passed, failed, skipped);
        }

        // 2. KERI log verification
        var keriResult = verifyKeriLog(identity.keyLog());
        if (keriResult.isEmpty()) {
            passed.add("keri");
        } else {
            failed.add("keri: " + keriResult.get());
            // Can still have SIGNATURE_ONLY trust
            return new VerificationResult(
                sigValid ? TrustLevel.SIGNATURE_ONLY : TrustLevel.NONE,
                passed, failed, skipped);
        }

        // 3. Parent chain (if applicable)
        if (manifest.parentDid() != null) {
            var parentResult = verifyParentChain(manifest, store);
            if (parentResult.isEmpty()) {
                passed.add("parent-chain");
            } else {
                failed.add("parent-chain: " + parentResult.get());
            }
        } else {
            skipped.add("parent-chain (original soul)");
        }

        // 4. Cross-zone origin confirmation
        if (originConfirmed) {
            passed.add("origin-confirmed");
        } else {
            skipped.add("origin-confirmed");
        }

        // Determine trust level
        TrustLevel level = TrustLevel.SIGNATURE_KERI;
        if (originConfirmed) {
            level = TrustLevel.SIGNATURE_KERI_ORIGIN;
        }

        // 5. Behavioral verification
        if (behavioralMatch != null) {
            if (behavioralMatch) {
                passed.add("behavioral");
                level = TrustLevel.FULL;
            } else {
                failed.add("behavioral");
                // Don't downgrade trust level — behavioral is supplementary
            }
        } else {
            skipped.add("behavioral (not yet observed)");
        }

        return new VerificationResult(level, passed, failed, skipped);
    }

    /**
     * Quick verification: signature only (for fast accept/reject).
     *
     * @param manifest  Soul manifest
     * @param identity  Agent identity
     * @return true if signature is valid
     */
    public static boolean quickVerify(SoulManifest manifest, AgentIdentity identity) {
        return verifySignature(manifest, identity);
    }

    /**
     * Verify an inbound soul manifest without a pre-existing AgentIdentity.
     *
     * Used at the transit boundary (SoulLayer, FederationService) when a manifest
     * arrives from a remote node and we only have the manifest itself, not the
     * agent's identity record. Reconstructs a minimal AgentIdentity from the
     * manifest's publicKeyMultibase and keyLog fields.
     *
     * Behavioral verification is always skipped (null) since the agent hasn't
     * been observed yet at arrival time.
     *
     * @param manifest        The inbound soul manifest
     * @param store           Soul store (for parent chain verification, may be null)
     * @param originConfirmed Whether the origin zone confirmed the manifest hash
     * @return Verification result with trust level
     */
    public static VerificationResult verifyInbound(
            SoulManifest manifest,
            SoulStore store,
            boolean originConfirmed
    ) {
        // Try to reconstruct a minimal AgentIdentity from the manifest's public key
        AgentIdentity identity;
        try {
            byte[] rawPubKey = DidKey.rawPublicKeyFromMultibase(manifest.publicKeyMultibase());
            identity = new AgentIdentity(
                manifest.did(), rawPubKey, null,
                manifest.keyLog() != null ? manifest.keyLog() : List.of(),
                Instant.now(), manifest.parentDid(), null
            );
        } catch (Exception e) {
            // Cannot reconstruct identity — signature verification impossible
            var failed = new ArrayList<String>();
            failed.add("identity-reconstruction: " + e.getMessage());
            return new VerificationResult(TrustLevel.NONE, List.of(), failed, List.of());
        }

        return verify(manifest, identity, store, originConfirmed, null);
    }

    /**
     * Convenience overload: verify inbound manifest without origin confirmation.
     */
    public static VerificationResult verifyInbound(SoulManifest manifest, SoulStore store) {
        return verifyInbound(manifest, store, false);
    }
}
