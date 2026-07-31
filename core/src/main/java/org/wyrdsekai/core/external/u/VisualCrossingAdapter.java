package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/** §4.41 — Visual Crossing (current/forecast/history). Tier 4. */
public final class VisualCrossingAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "visualcrossing"; }
    @Override public String credentialSlot() { return "visualcrossing.api_key"; }
    @Override public Set<String> capabilities() {
        return caps("current", "forecast", "history");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
