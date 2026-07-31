package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.Set;

/**
 * §4.38 — Apple HealthKit. Tier 5 (sensitive PII).
 *
 * <p>HealthKit access goes through the Shortcuts bridge (§4.30 path) — there
 * is no server-side API. The Wyrdsekai phone runtime invokes a pre-shaped
 * Shortcut that returns HK data over local IPC. This JVM-side adapter is a
 * stub that returns {@code not_yet_wired}; the phone-side runtime overrides
 * the namespace with a native handler when running on iOS.</p>
 */
public final class AppleHealthAdapter extends AbstractPhaseUAdapter {
    @Override public String namespace() { return "apple_health"; }
    @Override public String credentialSlot() { return ""; } // native OS auth
    @Override public Set<String> capabilities() {
        return caps("read", "list_workouts", "list_sleep", "list_heart_rate");
    }
    @Override public AdapterResponse invoke(AdapterRequest req) {
        return AdapterResponse.fail("phone_only",
            "apple_health.* requires the iOS Shortcuts bridge — server-side stub",
            false);
    }
}
