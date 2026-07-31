package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/** §4.40 — Mapbox (geocode/directions/isochrone). Tier 4. */
public final class MapboxAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "mapbox"; }
    @Override public String credentialSlot() { return "mapbox.access_token"; }
    @Override public Set<String> capabilities() {
        return caps("geocode", "directions", "isochrone");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
