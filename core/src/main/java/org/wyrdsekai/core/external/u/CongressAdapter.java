package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/**
 * §4.39 — api.congress.gov (bills/members/votes). Tier 4.
 *
 * <p>Free key required; surface is read-only public legislative data.</p>
 */
public final class CongressAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "congress"; }
    @Override public String credentialSlot() { return "congress.api_key"; }
    @Override public Set<String> capabilities() {
        return caps("bills", "members", "votes");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
