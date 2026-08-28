package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The class of defect the contract gates cannot see: a perfectly good file that is the
 * wrong tool.
 *
 * <h2>Both live cases</h2>
 * Two items reached the steward's hands on 2026-08-21 in full working order, having passed
 * every gate, and neither did what he asked.
 *
 * <ul>
 *   <li>"a story based on what it found" → the item called
 *       {@code llm.summarize(text, "summarize into exactly two paragraphs")} and returned
 *       an accurate précis. His words: <i>"all it did was provide summaries not a
 *       story."</i></li>
 *   <li>"query a location and get back the current weather" → the household holds an
 *       OpenWeather key; the item scraped the web and honestly reported finding
 *       nothing.</li>
 * </ul>
 *
 * <p>Neither is a bug in the file. Both are the wrong verb, competently implemented — and
 * both are one repair turn away from right, given the observation.
 */
class DoesItDoWhatWasAskedTest {

    private record FakeAdapter(String namespace, Set<String> capabilities)
            implements ExternalAdapter {
        @Override public String credentialSlot() { return null; }
        @Override public AdapterResponse invoke(AdapterRequest req) {
            return AdapterResponse.fail("not_used", "fixture", false);
        }
    }

    @BeforeEach
    void setUp() {
        ExternalAdapterRegistry.get().clearForTests();
    }

    @AfterEach
    void tearDown() {
        ExternalAdapterRegistry.get().clearForTests();
    }

    private static final String SCRAPER = """
        function invoke(params) {
          var hits = world.web.search("current weather " + params.args, "news", 3);
          var page = world.web.fetch(hits[0].url, 2000);
          return { ok: true, summary: page };
        }
        """;

    private static final String SUMMARISER = """
        function invoke(params) {
          var hits = world.library.search(params.args, 5);
          return { ok: true,
                   summary: world.llm.summarize(hits[0].text, "two paragraphs") };
        }
        """;

    @Test
    void scraping_the_web_when_a_keyed_service_exists_is_worth_saying() {
        ExternalAdapterRegistry.get().register(
            new FakeAdapter("openweather", Set.of("current", "forecast")));

        var gaps = ItemIntentCheck.gaps(
            "get me the current weather for a city and state",
            SCRAPER, ItemCapabilitySet.craftedDefault());

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0))
            .contains("reaches the open web")
            .contains("world.openweather");
    }

    /**
     * With no keyed service available there is nothing better to suggest, and saying so
     * anyway would be noise that trains the backend to ignore the advice.
     */
    @Test
    void scraping_is_fine_when_the_house_has_no_key_for_it() {
        assertThat(ItemIntentCheck.gaps("weather please", SCRAPER,
            ItemCapabilitySet.craftedDefault())).isEmpty();
    }

    /** A service the item would be DENIED is not a suggestion worth making. */
    @Test
    void a_service_beyond_the_ceiling_is_not_suggested() {
        ExternalAdapterRegistry.get().register(new FakeAdapter("oura", Set.of("sleep")));
        assertThat(ItemIntentCheck.gaps("how did I sleep", SCRAPER,
            ItemCapabilitySet.craftedDefault())).isEmpty();
    }

    @Test
    void summarising_when_asked_to_compose_is_worth_saying() {
        var gaps = ItemIntentCheck.gaps(
            "speak out loud a story based on what it found", SUMMARISER,
            ItemCapabilitySet.craftedDefault());

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0))
            .contains("story")
            .contains("world.llm.complete");
    }

    /** Asked for a summary, summarising is exactly right. */
    @Test
    void summarising_when_asked_to_summarise_is_not_flagged() {
        assertThat(ItemIntentCheck.gaps("summarise the latest entries", SUMMARISER,
            ItemCapabilitySet.craftedDefault())).isEmpty();
    }

    /** Already composing — nothing to correct. */
    @Test
    void an_item_that_composes_is_not_flagged() {
        var composes = """
            function invoke(params) {
              var hits = world.library.search(params.args, 5);
              return { ok: true,
                       summary: world.llm.complete("Retell as a fairy tale: " + hits[0].text) };
            }
            """;
        assertThat(ItemIntentCheck.gaps("tell me a fairy tale from my books", composes,
            ItemCapabilitySet.craftedDefault())).isEmpty();
    }

    /** No request to compare against means no opinion — never a manufactured complaint. */
    @Test
    void without_the_request_there_is_nothing_to_say_about_intent() {
        ExternalAdapterRegistry.get().register(
            new FakeAdapter("openweather", Set.of("current")));
        assertThat(ItemIntentCheck.gaps(null, SUMMARISER,
            ItemCapabilitySet.craftedDefault())).isEmpty();
    }
    // ── declared-but-never-called (weatherseeker, home node 2026-08-24) ──

    @Test
    @DisplayName("a capability declared and never called is flagged")
    void declaredButNeverCalledIsFlagged() {
        var script = """
            exports.manifest = {
              name: "weatherseeker",
              capabilities: ["web.search", "web.fetch", "library.search",
                             "nominatim.geocode", "openweather.current"],
            };
            function invoke(params) {
              const result = world.library.search(params.args, 8);
              return { ok: true, summary: "I found weather for " + params.args };
            }
            """;
        var gaps = ItemIntentCheck.gaps(
            "query a location and get back the current weather", script,
            ItemCapabilitySet.craftedDefault());
        assertThat(gaps).anyMatch(g -> g.contains("openweather.current")
            && g.contains("never calls"));
    }

    @Test
    @DisplayName("an item that calls what it declares is left alone")
    void callingWhatYouDeclareIsFine() {
        var script = """
            exports.manifest = {
              name: "weatherseeker",
              capabilities: ["nominatim.geocode", "openweather.current"],
            };
            function invoke(params) {
              var g = world.nominatim.geocode({ q: params.args });
              var w = world.openweather.current({ lat: g.data.lat, lon: g.data.lon });
              return { ok: true, summary: w.data.text };
            }
            """;
        var gaps = ItemIntentCheck.gaps("weather please", script,
            ItemCapabilitySet.craftedDefault());
        assertThat(gaps).noneMatch(g -> g.contains("never calls"));
    }

    @Test
    @DisplayName("a fully-uncalled list is left alone — likely an aliasing style, not abandonment")
    void fullyUncalledIsSuppressed() {
        // `const w = world;` and similar indirections hide every namespace from
        // the shallow contains() check at once. Partial abandonment (some
        // namespaces visibly called, others not) is the reliable signal; total
        // absence is more likely our parse than their defect.
        var script = """
            exports.manifest = { name: "x", capabilities: ["openweather.current"] };
            const w = world;
            function invoke(p) { return { summary: String(w.openweather.current({})) }; }
            """;
        var gaps = ItemIntentCheck.gaps("weather", script,
            ItemCapabilitySet.craftedDefault());
        assertThat(gaps).noneMatch(g -> g.contains("never calls"));
    }
}
