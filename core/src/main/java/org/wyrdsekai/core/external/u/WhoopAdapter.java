package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/** §4.38 — WHOOP (recovery/strain/sleep). Tier 5 (sensitive PII). */
public final class WhoopAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "whoop"; }
    @Override public String credentialSlot() { return "whoop.refresh_token"; }
    @Override public Set<String> capabilities() {
        return caps("list_recovery", "list_strain", "list_sleep");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
