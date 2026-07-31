package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/**
 * §4.39 — data.gov catalog search. Tier 4.
 *
 * <p>data.gov is unauthenticated (CKAN-backed catalog at catalog.data.gov);
 * Phase U scaffolds the surface but defers the live CKAN-API call.</p>
 */
public final class DataGovAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "datagov"; }
    @Override public String credentialSlot() { return ""; }
    @Override public Set<String> capabilities() {
        return caps("query");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        return stub(req.method());
    }
}
