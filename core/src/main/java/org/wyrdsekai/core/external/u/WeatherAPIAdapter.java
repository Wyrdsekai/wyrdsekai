package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/** §4.41 — WeatherAPI.com (alt provider, current/forecast). Tier 4. */
public final class WeatherAPIAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "weatherapi"; }
    @Override public String credentialSlot() { return "weatherapi.api_key"; }
    @Override public Set<String> capabilities() {
        return caps("current", "forecast");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
