package org.wyrdsekai.core.companion;

import org.wyrdsekai.core.economy.VisibilityGrant;

import java.time.Instant;
import java.util.*;

/**
 * Manages visibility grants for aging companions (§99.4).
 * Graduated stewardship: the person controls what family sees.
 * Privacy is non-negotiable — conversation content never shared.
 */
public class VisibilityManager {

    private final Map<String, List<VisibilityGrant>> grants = new LinkedHashMap<>();

    /** Add a visibility grant. */
    public void addGrant(VisibilityGrant grant) {
        grants.computeIfAbsent(grant.grantorDid(), k -> new ArrayList<>()).add(grant);
    }

    /** Revoke all grants to a specific grantee. */
    public int revokeAll(String grantorDid, String granteeDid) {
        var grantList = grants.get(grantorDid);
        if (grantList == null) return 0;

        int revoked = 0;
        for (int i = 0; i < grantList.size(); i++) {
            var grant = grantList.get(i);
            if (grant.granteeDid().equals(granteeDid) && grant.active()) {
                grantList.set(i, grant.revoke());
                revoked++;
            }
        }
        return revoked;
    }

    /** Check if a grantee can see a specific category for a grantor. */
    public boolean canSee(String grantorDid, String granteeDid, String category) {
        // NEVER_SHARED categories are structurally blocked
        if (VisibilityGrant.NEVER_SHARED.contains(category)) return false;

        var grantList = grants.get(grantorDid);
        if (grantList == null) return false;

        return grantList.stream()
            .filter(g -> g.granteeDid().equals(granteeDid))
            .anyMatch(g -> g.isVisible(category));
    }

    /** Check if grantee gets alerts only (not routine data). */
    public boolean isAlertsOnly(String grantorDid, String granteeDid) {
        var grantList = grants.get(grantorDid);
        if (grantList == null) return true;

        return grantList.stream()
            .filter(g -> g.granteeDid().equals(granteeDid))
            .filter(VisibilityGrant::isValid)
            .allMatch(VisibilityGrant::alertsOnly);
    }

    /** Get all active grants for a grantor. */
    public List<VisibilityGrant> activeGrants(String grantorDid) {
        var grantList = grants.get(grantorDid);
        if (grantList == null) return List.of();

        return grantList.stream()
            .filter(VisibilityGrant::isValid)
            .toList();
    }

    /** Get all grantees who have visibility into a grantor's data. */
    public List<String> grantees(String grantorDid) {
        return activeGrants(grantorDid).stream()
            .map(VisibilityGrant::granteeDid)
            .distinct()
            .toList();
    }

    /** Summarize visibility for display. */
    public String describe(String grantorDid) {
        var active = activeGrants(grantorDid);
        if (active.isEmpty()) return "No active visibility grants.";

        var sb = new StringBuilder("Visibility grants:\n");
        for (var grant : active) {
            sb.append("- ").append(grant.granteeDid()).append(": ");
            if (grant.alertsOnly()) sb.append("alerts only");
            else sb.append(String.join(", ", grant.categories()));
            sb.append("\n");
        }
        return sb.toString();
    }
}
