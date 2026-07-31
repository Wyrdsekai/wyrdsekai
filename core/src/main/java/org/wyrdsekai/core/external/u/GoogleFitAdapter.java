package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/**
 * §4.38 — Google Fit (steps/workouts). Tier 5 (sensitive PII).
 *
 * <p>Note: Google Fit was deprecated in 2026 in favour of Health Connect.
 * The {@code google_fit.*} namespace is preserved for stewards on legacy
 * devices; new installations should declare {@code health_connect.*} in a
 * future phase.</p>
 */
public final class GoogleFitAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "google_fit"; }
    @Override public String credentialSlot() { return "google_fit.access_token"; }
    @Override public Set<String> capabilities() {
        return caps("list_steps", "list_workouts");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        if (requireCredential().isEmpty()) return credentialMissing();
        return stub(req.method());
    }
}
