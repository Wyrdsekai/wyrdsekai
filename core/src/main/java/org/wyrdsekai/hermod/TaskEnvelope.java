package org.wyrdsekai.hermod;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The unit that moves. A task envelope is signed, scoped to one data
 * domain and one capability class, executes data-locally, and returns
 * only its result. Any device may refuse one — refusal is the
 * concurrency mechanism, not an error.
 *
 * hermod places WORK; it never places PRESENCE. Nothing in this package
 * may reference companions, souls, or seats.
 */
public record TaskEnvelope(
    String envelopeId,
    String householdId,
    String originDeviceId,
    String taskType,          // e.g. "inference.chat", "index.local", "draft.speculative"
    String dataDomain,        // e.g. "none", "photos", "notifications" — grant required if not "none"
    String capabilityClass,   // matches Capability.capabilityClass
    Map<String, String> params,
    long tokenBudget,
    Instant issuedAt,
    Instant expiresAt,
    Optional<SignedGrant> grant,   // REQUIRED for any dataDomain != "none"
    byte[] originSignature) {

    public boolean requiresGrant() {
        return !"none".equals(dataDomain);
    }
}
