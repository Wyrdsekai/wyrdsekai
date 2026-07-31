package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/**
 * §4.38 — Oura Ring (sleep/activity/heart_rate/workouts).
 * Tier 5 (sensitive PII). Bonded data sensitivity by default.
 *
 * <p>Phase U scaffolding: capability shape is registered so manifest
 * validation and runtime gating both work end-to-end; concrete OAuth +
 * /v2/usercollection/* requests are stubbed pending steward-side token
 * provisioning.</p>
 */
public final class OuraAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "oura"; }
    @Override public String credentialSlot() { return "oura.token"; }
    @Override public Set<String> capabilities() {
        return caps("list_sleep", "list_activity", "list_heart_rate", "list_workouts");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
