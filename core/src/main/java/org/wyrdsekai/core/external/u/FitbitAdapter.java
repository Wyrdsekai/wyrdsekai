package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/** §4.38 — Fitbit (sleep/activity/heart_rate/weight). Tier 5 (sensitive PII). */
public final class FitbitAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "fitbit"; }
    @Override public String credentialSlot() { return "fitbit.access_token"; }
    @Override public Set<String> capabilities() {
        return caps("list_sleep", "list_activity", "heart_rate", "weight");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
