package org.wyrdsekai.core.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondRitual;

import java.util.HashMap;
import java.util.Map;

/**
 * Mirror {@link Bond} lifecycle events into HomeRegistry Grants
 * ( BOND).
 *
 * <p>Each active bond materializes as a pair of reciprocal
 * {@code Grant(capability=read, resource=home://X/bond/Y, subject=Y)} rows —
 * one on each party's Home — so either side can see the bond via
 * {@code /api/home/grants/issued} and the Shelf furnishing.</p>
 *
 * <p>BondRitual remains the state authority. Grants are the queryable view.</p>
 */
public final class BondGrantSync implements BondRitual.BondListener {

    private static final Logger log = LoggerFactory.getLogger(BondGrantSync.class);

    private final HomeClient homeClient;

    public BondGrantSync(HomeClient homeClient) {
        this.homeClient = homeClient;
    }

    @Override
    public void onBondWritten(Bond bond) {
        try {
            issuePair(bond);
        } catch (Exception e) {
            log.warn("BondGrantSync.onBondWritten {}: {}", bond.bondId(), e.getMessage());
        }
    }

    @Override
    public void onBondSevered(Bond bond) {
        try {
            revokePair(bond);
        } catch (Exception e) {
            log.warn("BondGrantSync.onBondSevered {}: {}", bond.bondId(), e.getMessage());
        }
    }

    private void issuePair(Bond bond) {
        // A → B
        issueOne(bond.agentADid(), bond.agentBDid(), bond);
        // B → A
        issueOne(bond.agentBDid(), bond.agentADid(), bond);
    }

    private void issueOne(String owner, String other, Bond bond) {
        var resource = ResourceUri.of(owner, ResourceTypeRegistry.BOND, other);
        homeClient.issueOrReplace(
            owner, other, resource, Capability.read,
            scopeOf(bond), null,
            "bond:" + bond.depth().name());
    }

    private void revokePair(Bond bond) {
        revokeOne(bond.agentADid(), bond.agentBDid());
        revokeOne(bond.agentBDid(), bond.agentADid());
    }

    private void revokeOne(String owner, String other) {
        var resource = ResourceUri.of(owner, ResourceTypeRegistry.BOND, other);
        homeClient.revokeByKey(owner, other, resource, Capability.read);
    }

    private static Map<String, Object> scopeOf(Bond b) {
        var m = new HashMap<String, Object>();
        m.put("depth", b.depth().name());
        m.put("depthLevel", b.depth().level());
        m.put("interactionCount", b.interactionCount());
        m.put("scarred", b.scarred());
        m.put("mutualConsent", b.mutualConsent());
        return m;
    }
}
