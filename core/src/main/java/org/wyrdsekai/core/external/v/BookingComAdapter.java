package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Booking.com hotel-search adapter.
 *
 * <p>Read-only. Hotel bookings remain the steward's responsibility (Tier 7
 * out of scope for this phase). Affiliate-id based lookups when credentialed,
 * structured stub otherwise.</p>
 */
public final class BookingComAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "booking"; }

    @Override public Set<String> capabilities() { return Set.of("hotel_search"); }

    @Override public String credentialSlot() { return "booking.affiliate_id"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        if (!"hotel_search".equals(request.method())) {
            return AdapterResponse.fail("unknown_method",
                "booking." + request.method() + " is not supported", false);
        }
        var args = request.args();
        var city = str(args, "city");
        if (city.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "hotel_search requires {city}", false);
        }
        if (credential().isEmpty()) {
            return stub("credential_missing:booking.affiliate_id");
        }
        return stub("live_not_wired");
    }

    private AdapterResponse stub(String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put("hotels", List.of());
        return AdapterResponse.ok(out);
    }
}
