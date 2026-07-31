package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/** §4.38 — Garmin (activities/sleep). Tier 5 (sensitive PII). */
public final class GarminAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "garmin"; }
    @Override public String credentialSlot() { return "garmin.user_token"; }
    @Override public Set<String> capabilities() {
        return caps("list_activities", "list_sleep");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
