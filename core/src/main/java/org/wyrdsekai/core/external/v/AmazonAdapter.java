package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Amazon Product Advertising API adapter.
 *
 * <p>Read-only — {@code search} (PA-API SearchItems) and
 * {@code item_lookup} (PA-API GetItems). Writes (purchase, cart) live in
 * a separate Tier 7 phase. Returns structured stub when credentials missing.</p>
 */
public final class AmazonAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "amazon"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "item_lookup");
    }

    @Override public String credentialSlot() { return "amazon.access_key"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        var args = request.args();
        return switch (request.method()) {
            case "search" -> search(args);
            case "item_lookup" -> itemLookup(args);
            default -> AdapterResponse.fail("unknown_method",
                "amazon." + request.method() + " is not supported", false);
        };
    }

    private AdapterResponse search(Map<String, Object> args) {
        var query = str(args, "query");
        if (query.isBlank()) {
            return AdapterResponse.fail("bad_request", "search requires {query}", false);
        }
        if (credential().isEmpty()) {
            return stub("results", "credential_missing:amazon.access_key");
        }
        return stub("results", "live_not_wired");
    }

    private AdapterResponse itemLookup(Map<String, Object> args) {
        var asin = str(args, "asin");
        if (asin.isBlank()) {
            return AdapterResponse.fail("bad_request", "item_lookup requires {asin}", false);
        }
        if (credential().isEmpty()) {
            return stub("item", "credential_missing:amazon.access_key");
        }
        return stub("item", "live_not_wired");
    }

    private AdapterResponse stub(String key, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put(key, key.equals("item") ? Map.of() : List.of());
        return AdapterResponse.ok(out);
    }
}
