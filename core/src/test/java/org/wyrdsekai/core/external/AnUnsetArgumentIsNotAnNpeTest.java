package org.wyrdsekai.core.external;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * An argument a script did not set must not take the whole call down.
 *
 * <h2>The live failure</h2>
 * {@code AdapterRequest} copied its args with {@code Map.copyOf}, which rejects null
 * VALUES — not just a null map — with a bare {@link NullPointerException}. A JS
 * {@code undefined} property converts to a Java null on the way through
 * {@code AdapterMethodProxy}, so one unset field killed the call two layers below
 * anything that knew which adapter was being invoked.
 *
 * <p>Live 2026-08-21: a weather item calling {@code world.nominatim.geocode({...})} — the
 * first item ever written against the generated adapter surface, and correct — surfaced
 * to the steward as <b>"Script error: null"</b>. No adapter named, no cause, nothing to
 * act on. Three separate places had to learn to say something: the bridge proxy, this
 * record, and the executor's error text.
 */
class AnUnsetArgumentIsNotAnNpeTest {

    @Test
    void an_unset_argument_is_dropped_rather_than_fatal() {
        var args = new HashMap<String, Object>();
        args.put("q", "Cambridge, MA");
        args.put("countrycode", null);   // a JS `undefined` arrives exactly like this

        var req = new AdapterRequest("nominatim", "geocode", args,
            ItemCapabilitySet.UNRESTRICTED, null);

        assertThat(req.args()).containsEntry("q", "Cambridge, MA");
        assertThat(req.args())
            .as("what the script did not set, it did not send")
            .doesNotContainKey("countrycode");
    }

    @Test
    void a_null_map_is_still_an_empty_call() {
        var req = new AdapterRequest("nominatim", "geocode", null,
            ItemCapabilitySet.UNRESTRICTED, null);
        assertThat(req.args()).isEmpty();
    }

    @Test
    void every_argument_set_survives_untouched() {
        var req = AdapterRequest.of("openweather", "current",
            Map.of("lat", 42.37, "lon", -71.11));
        assertThat(req.args()).containsEntry("lat", 42.37).containsEntry("lon", -71.11);
    }

    @Test
    void building_a_request_never_throws_on_a_null() {
        var args = new HashMap<String, Object>();
        args.put("a", null);
        args.put("b", null);
        assertThatCode(() -> new AdapterRequest("x", "y", args,
            ItemCapabilitySet.UNRESTRICTED, null)).doesNotThrowAnyException();
    }
}
