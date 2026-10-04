package org.wyrdsekai.hermod;

import java.time.Clock;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Default door: verifies expiry, budget against a local ceiling, and —
 * for any data-domain task — the presence AND validity of the traveling
 * grant. Grant cryptography is pluggable (the household authority's
 * verifier); refusal reasons are honest and specific.
 */
public final class LocalAdmissionGate implements AdmissionGate {

    private final Clock clock;
    private final long tokenCeiling;
    private final Predicate<SignedGrant> grantVerifier;
    private final Predicate<TaskEnvelope> busyCheck;
    /** The live advert of the device an envelope says it comes from; null means this gate does not verify origins. */
    private final Function<String, Capability> originOf;

    /**
     * A gate that does not verify who sent the envelope. For tests of the other checks only: the
     * doors the product builds verify (see the other constructor), and
     * {@code AnEnvelopeIsSignedByItsSenderTest} holds them to it.
     */
    public LocalAdmissionGate(Clock clock, long tokenCeiling,
                              Predicate<SignedGrant> grantVerifier,
                              Predicate<TaskEnvelope> busyCheck) {
        this(clock, tokenCeiling, grantVerifier, busyCheck, null);
    }

    /**
     * The door as the product builds it: the envelope's origin is looked up in the household's
     * capability table and its signature verified against that device's key, before anything else.
     */
    public LocalAdmissionGate(Clock clock, long tokenCeiling,
                              Predicate<SignedGrant> grantVerifier,
                              Predicate<TaskEnvelope> busyCheck,
                              Function<String, Capability> originOf) {
        this.clock = clock;
        this.tokenCeiling = tokenCeiling;
        this.grantVerifier = grantVerifier;
        this.busyCheck = busyCheck;
        this.originOf = originOf;
    }

    /** Whether this gate verifies who sent an envelope. */
    public boolean verifiesOrigin() { return originOf != null; }

    @Override
    public Decision consider(TaskEnvelope e) {
        // Who sent it, first. Everything after this trusts the envelope's fields.
        Capability origin = null;
        if (originOf != null) {
            origin = originOf.apply(e.originDeviceId());
            if (origin == null) {
                return Decision.refuse("origin '" + e.originDeviceId() + "' is not a device this household knows");
            }
            if (origin.publicKey() == null || origin.publicKey().length == 0) {
                return Decision.refuse("origin '" + e.originDeviceId() + "' advertises no key; its envelopes cannot be verified");
            }
            if (!EnvelopeSigning.verify(e, origin.publicKey())) {
                return Decision.refuse("origin signature invalid");
            }
            if (origin.householdId() != null && !origin.householdId().equals(e.householdId())) {
                return Decision.refuse("envelope names household '" + e.householdId() + "'; origin is of '" + origin.householdId() + "'");
            }
        }
        if (e.expiresAt().isBefore(clock.instant())) {
            return Decision.refuse("envelope expired");
        }
        if (e.tokenBudget() > tokenCeiling) {
            return Decision.refuse("budget " + e.tokenBudget() + " exceeds local ceiling " + tokenCeiling);
        }
        if (e.requiresGrant()) {
            if (e.grant().isEmpty()) {
                return Decision.refuse("no grant for domain '" + e.dataDomain() + "'");
            }
            var g = e.grant().get();
            if (!g.dataDomain().equals(e.dataDomain())) {
                return Decision.refuse("grant is for domain '" + g.dataDomain() + "', task wants '" + e.dataDomain() + "'");
            }
            if (g.expiresAt().isBefore(clock.instant())) {
                return Decision.refuse("grant expired");
            }
            // The grant is for this household and for the class of device that sent the task.
            if (g.householdId() != null && !g.householdId().equals(e.householdId())) {
                return Decision.refuse("grant is for household '" + g.householdId() + "', task is of '" + e.householdId() + "'");
            }
            if (origin != null && g.grantedToDeviceClass() != null && !"*".equals(g.grantedToDeviceClass())
                    && !g.grantedToDeviceClass().equals(origin.capabilityClass())) {
                return Decision.refuse("grant is for device class '" + g.grantedToDeviceClass()
                    + "', origin is '" + origin.capabilityClass() + "'");
            }
            if (!grantVerifier.test(g)) {
                return Decision.refuse("grant signature invalid");
            }
        }
        if (busyCheck.test(e)) {
            return Decision.queue("busy");
        }
        return Decision.admit();
    }
}
