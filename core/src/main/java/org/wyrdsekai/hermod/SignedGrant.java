package org.wyrdsekai.hermod;

import java.time.Instant;

/**
 * A consent grant that TRAVELS WITH the task and is verified by the
 * EXECUTING device. Consent is enforced at the data, not at the decider:
 * a router running stale policy cannot over-route, because the door
 * checks the grant itself.
 */
public record SignedGrant(
    String grantId,
    String householdId,
    String dataDomain,
    String grantedToDeviceClass, // which class of origin may send into this domain
    Instant issuedAt,
    Instant expiresAt,
    String policyVersion,
    byte[] authoritySignature) {  // the household policy authority's key
}
