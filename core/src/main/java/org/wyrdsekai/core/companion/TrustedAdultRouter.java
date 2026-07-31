package org.wyrdsekai.core.companion;

import java.time.Instant;
import java.util.*;

/**
 * Trusted adult routing for child safety (§100.6).
 * Manages the list of trusted adults a child can be connected to.
 * Critical for abuse disclosure — routes AROUND the parent if needed.
 */
public class TrustedAdultRouter {

    /** A trusted adult entry. */
    public record TrustedAdult(
        String adultDid,
        String name,
        String relationship,
        TrustLevel trustLevel,
        boolean canReceiveSafetyAlerts,
        Instant addedAt
    ) {}

    public enum TrustLevel {
        /** Can receive safety alerts. */
        SAFETY_CONTACT,
        /** Can receive safety alerts AND manage child's companion settings. */
        GUARDIAN,
        /** External resource (e.g., school counselor). */
        EXTERNAL_CONTACT
    }

    /** Routing result. */
    public record RoutingResult(
        String childDid,
        String routedToAdult,
        String routedToName,
        TrustLevel level,
        boolean isParent,
        String fallbackResource
    ) {}

    private final Map<String, List<TrustedAdult>> registry = new LinkedHashMap<>();

    /** Register a trusted adult for a child. */
    public void register(String childDid, TrustedAdult adult) {
        registry.computeIfAbsent(childDid, k -> new ArrayList<>()).add(adult);
    }

    /** Remove a trusted adult. */
    public boolean remove(String childDid, String adultDid) {
        var adults = registry.get(childDid);
        if (adults == null) return false;
        return adults.removeIf(a -> a.adultDid().equals(adultDid));
    }

    /** Get all trusted adults for a child. */
    public List<TrustedAdult> trustedAdults(String childDid) {
        return registry.getOrDefault(childDid, List.of());
    }

    /** Route a concern — find the best adult who is NOT the parent. */
    public RoutingResult routeAroundParent(String childDid, String parentDid) {
        var adults = trustedAdults(childDid);

        // Find a safety contact who is not the parent
        var safeAdult = adults.stream()
            .filter(a -> !a.adultDid().equals(parentDid))
            .filter(TrustedAdult::canReceiveSafetyAlerts)
            .findFirst();

        if (safeAdult.isPresent()) {
            var adult = safeAdult.get();
            return new RoutingResult(childDid, adult.adultDid(), adult.name(),
                adult.trustLevel(), false, null);
        }

        // No trusted adult available — fall back to external resources
        return new RoutingResult(childDid, null, null, null, false,
            SafetyAlertRouter.CHILDHELP_HOTLINE);
    }

    /** Route a concern — standard route (to parent or highest trust level). */
    public RoutingResult routeStandard(String childDid, String parentDid) {
        return new RoutingResult(childDid, parentDid, "parent",
            TrustLevel.GUARDIAN, true, null);
    }

    /** Check if child has any trusted adults configured. */
    public boolean hasTrustedAdults(String childDid) {
        return !trustedAdults(childDid).isEmpty();
    }

    /** Check if child has a non-parent safety contact. */
    public boolean hasNonParentContact(String childDid, String parentDid) {
        return trustedAdults(childDid).stream()
            .anyMatch(a -> !a.adultDid().equals(parentDid) && a.canReceiveSafetyAlerts());
    }
}
