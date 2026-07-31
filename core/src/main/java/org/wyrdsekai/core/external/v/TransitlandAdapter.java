package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transitland real-time transit adapter.
 *
 * <p>Read-only. Surfaces {@code routes}, {@code stops}, {@code schedules}.
 * Transitland's REST API is largely free with an optional API key for
 * higher rate limits — both modes are handled.</p>
 *
 * <p>Namespace is {@code transit_rt} (real-time public transit), NOT
 * {@code transit}. Definitive re-audit fix (#33-2): SPEC §4.42 documents this
 * as {@code world.transit.*}, but {@code world.transit} is already bound to
 * the §4.20 cross-zone-transit {@code TransitApi} static member on
 * {@code ItemWorldApi} — and the script proxy resolves direct members before
 * dynamic adapter namespaces, so {@code world.transit.routes} could NEVER
 * reach this adapter. Renaming the adapter's namespace (it has zero script
 * callers and is a stub) is the least-disruptive way to make BOTH surfaces
 * reachable: {@code world.transit.*} = zone handoff, {@code world.transit_rt.*}
 * = public-transit routes/stops/schedules.</p>
 */
public final class TransitlandAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "transit_rt"; }

    @Override public Set<String> capabilities() {
        return Set.of("routes", "stops", "schedules");
    }

    @Override public String credentialSlot() { return "transit_rt.api_key"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        var args = request.args();
        return switch (request.method()) {
            case "routes" -> routes(args);
            case "stops" -> stops(args);
            case "schedules" -> schedules(args);
            default -> AdapterResponse.fail("unknown_method",
                "transit_rt." + request.method() + " is not supported", false);
        };
    }

    private AdapterResponse routes(Map<String, Object> args) {
        var stopId = str(args, "stop_id");
        if (stopId.isBlank()) {
            return AdapterResponse.fail("bad_request", "routes requires {stop_id}", false);
        }
        return stub("routes", "live_not_wired");
    }

    private AdapterResponse stops(Map<String, Object> args) {
        var lat = args.get("lat");
        var lon = args.get("lon");
        if (lat == null || lon == null) {
            return AdapterResponse.fail("bad_request", "stops requires {lat, lon}", false);
        }
        return stub("stops", "live_not_wired");
    }

    private AdapterResponse schedules(Map<String, Object> args) {
        var routeId = str(args, "route_id");
        if (routeId.isBlank()) {
            return AdapterResponse.fail("bad_request", "schedules requires {route_id}", false);
        }
        return stub("schedules", "live_not_wired");
    }

    private AdapterResponse stub(String key, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put(key, List.of());
        return AdapterResponse.ok(out);
    }
}
