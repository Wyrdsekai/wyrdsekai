package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an authoring backend is told must be what the runtime can actually do.
 *
 * <h2>Why a document cannot be hand-maintained</h2>
 * The items-as-tools preamble is the entire world an authoring backend sees — goose
 * introspects nothing; it is handed a string and writes JavaScript against it. So a
 * capability that exists but is not in that string does not exist, and the only symptom is
 * items that quietly never use it.
 *
 * <p>On 2026-08-21 that gap was seventeen adapters wide. The steward asked for a weather
 * tool; the household held an OpenWeather key and a working adapter with {@code current}
 * and {@code forecast}; the preamble mentioned adapters zero times; goose wrote a web
 * scraper, which was the correct choice given its only source of truth. The item worked
 * exactly as written and reported no weather.
 *
 * <p>The lesson generalises past that one item: a mirror of the runtime kept by hand rots
 * the first time someone adds a capability without editing a string in another module.
 * Generating it is the only fix that does not depend on anyone remembering.
 */
class TheContractIsGeneratedFromTheWorldTest {

    /** A keyless adapter — nothing to resolve, so availability is never in doubt. */
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

    /** Register an adapter and it appears — with no edit to any string anywhere. */
    @Test
    void a_newly_registered_adapter_shows_up_without_anyone_editing_prose() {
        assertThat(ItemApiSurface.availableLines(ItemCapabilitySet.UNRESTRICTED)).isEmpty();

        ExternalAdapterRegistry.get().register(
            new FakeAdapter("tidewatch", Set.of("current", "forecast")));

        // One line per METHOD since 2026-08-22, each naming its return keys where the
        // adapter declares them — a contract that named only the method had an author
        // guessing the shape, and a working weather tool spoke "undefined°C" because of it.
        var lines = String.join("\n", ItemApiSurface.availableLines(ItemCapabilitySet.UNRESTRICTED));
        assertThat(lines)
            .contains("world.tidewatch.current")
            .contains("world.tidewatch.forecast");
    }

    /**
     * The ceiling filters the list. Advertising a capability that gets DENIED is worse
     * than silence: it produces an item that tries and fails, rather than one that finds
     * another way.
     */
    @Test
    void a_surface_the_item_would_be_denied_is_not_advertised() {
        ExternalAdapterRegistry.get().register(
            new FakeAdapter("oura", Set.of("sleep")));
        ExternalAdapterRegistry.get().register(
            new FakeAdapter("openweather", Set.of("current")));

        var lines = String.join("\n",
            ItemApiSurface.availableLines(ItemCapabilitySet.craftedDefault()));

        assertThat(lines)
            .as("public weather is inside the crafted ceiling")
            .contains("world.openweather");
        assertThat(lines)
            .as("personal health is not, and must not be offered to a crafted item")
            .doesNotContain("world.oura");
    }

    /** With nothing registered, the block is empty rather than a heading over nothing. */
    @Test
    void an_empty_registry_produces_no_section_at_all() {
        assertThat(ItemApiSurface.adapterBlock(ItemCapabilitySet.UNRESTRICTED)).isEmpty();
    }

    /** The generated surface is appended to the contract every backend is handed. */
    @Test
    void the_generated_surface_reaches_the_preamble() {
        ExternalAdapterRegistry.get().register(
            new FakeAdapter("tidewatch", Set.of("current")));
        var preamble = OpenHandsBackend.itemsAsToolsPreamble(ItemCapabilitySet.UNRESTRICTED);
        assertThat(preamble)
            .as("the hand-written craft notes survive")
            .contains("`world` is a GLOBAL");
        assertThat(preamble)
            .as("and the generated list is there too")
            .contains("world.tidewatch");
    }

    /** Both variants carry it — the CWD one is what every subprocess backend sends. */
    @Test
    void the_cwd_variant_carries_it_too() {
        ExternalAdapterRegistry.get().register(
            new FakeAdapter("tidewatch", Set.of("current")));
        assertThat(OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.UNRESTRICTED))
            .contains("world.tidewatch")
            .contains("CURRENT WORKING DIRECTORY");
    }

    /**
     * No backend may reach past the composed contract to the raw constant — that is
     * precisely how the document and the world drift apart again.
     */
    @Test
    void no_backend_uses_the_raw_constant() throws Exception {
        var dir = java.nio.file.Path.of("src/main/java/org/wyrdsekai/core/coding");
        if (!java.nio.file.Files.isDirectory(dir)) {
            dir = java.nio.file.Path.of("core/src/main/java/org/wyrdsekai/core/coding");
        }
        assertThat(java.nio.file.Files.isDirectory(dir))
            .as("this guard must never silently pass for want of its sources").isTrue();
        var offenders = new java.util.ArrayList<String>();
        try (var files = java.nio.file.Files.list(dir)) {
            for (var f : files.filter(p -> p.getFileName().toString().endsWith("Backend.java"))
                    .filter(p -> !p.getFileName().toString().equals("OpenHandsBackend.java"))
                    .toList()) {
                for (var line : java.nio.file.Files.readAllLines(f)) {
                    var code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//")) continue;
                    if (code.contains("ITEMS_AS_TOOLS_PREAMBLE")) {
                        offenders.add(f.getFileName() + ": " + code);
                    }
                }
            }
        }
        assertThat(offenders)
            .as("call itemsAsToolsPreamble(ceiling); the constant is only half the contract")
            .isEmpty();
    }
}
