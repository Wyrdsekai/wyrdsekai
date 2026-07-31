package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lyft price + ETA estimate adapter.
 *
 * <p>Read-only. Mirrors {@link UberAdapter}; ride requests stay Tier 7.</p>
 */
public final class LyftAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "lyft"; }

    @Override public Set<String> capabilities() { return Set.of("estimate"); }

    @Override public String credentialSlot() { return "lyft.client_id"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        if (!"estimate".equals(request.method())) {
            return AdapterResponse.fail("unknown_method",
                "lyft." + request.method() + " is not supported", false);
        }
        var args = request.args();
        if (str(args, "from").isBlank() || str(args, "to").isBlank()) {
            return AdapterResponse.fail("bad_request",
                "estimate requires {from, to}", false);
        }
        if (credential().isEmpty()) {
            return stub("credential_missing:lyft.client_id");
        }
        return stub("live_not_wired");
    }

    private AdapterResponse stub(String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put("products", List.of());
        return AdapterResponse.ok(out);
    }
}
