package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/**
 * §4.39 — IRS public data (rates/deadlines). Tier 4.
 *
 * <p>Read-only; the IRS does not publish a unified JSON API for general
 * tax data, so the live integration scrapes documented endpoints (per
 * §4.39 fallthrough). Phase U registers the capability surface.</p>
 */
public final class IRSAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "irs"; }
    @Override public String credentialSlot() { return "irs.api_key"; }
    @Override public Set<String> capabilities() {
        return caps("rates", "deadlines");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        // IRS data sources are largely public — credential is optional but
        // helps with rate limits when wired. Stub regardless.
        return stub(req.method());
    }
}
