package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/**
 * §4.39 — USAJOBS (federal job search). Tier 4.
 *
 * <p>Public API at data.usajobs.gov; requires a User-Agent + simple API key
 * registration. Phase U scaffolds the {@code search} surface; the live HTTP
 * call is added when the steward populates the {@code usajobs.api_key}
 * slot.</p>
 */
public final class USAJobsAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "usajobs"; }
    @Override public String credentialSlot() { return "usajobs.api_key"; }
    @Override public Set<String> capabilities() {
        return caps("search");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
