package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/**
 * §4.40 — Timezone lookup by coords / IP.
 *
 * <p>Distinct from {@code world.date.*} (Phase A2) — that is local-runtime
 * tz formatting. {@code world.timezone.*} resolves an IP or {lat,lon} pair
 * to an IANA tz identifier via an external service (e.g. ipapi, timezonedb).
 * Tier 4.</p>
 */
public final class TimezoneAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "timezone"; }
    @Override public String credentialSlot() { return "timezone.api_key"; }
    @Override public Set<String> capabilities() {
        return caps("lookup_by_coords", "lookup_by_ip");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
