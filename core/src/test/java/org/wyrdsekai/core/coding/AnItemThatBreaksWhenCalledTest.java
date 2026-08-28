package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file that passes every structural check and then fails the moment it is called.
 *
 * <h2>The live failure</h2>
 * 2026-08-21, second item of the day. The steward asked again for a library tool; goose
 * wrote {@code library_speaks} with a flawless manifest — embodiment, two commands, a
 * top-level reachable {@code invoke} — and this as its first line:
 *
 * <pre>
 *   const &#123; world &#125; = params;
 *   if (!world) return &#123; ok: false, error: "invoke called outside a Wyrdsekai sandbox." &#125;;
 * </pre>
 *
 * <p>{@code world} is a GLOBAL in the sandbox, not a field of {@code params}. The guard
 * fired on every call. Every check {@link ItemContractCheck} made passed, so the repair
 * loop had nothing to hand back; the bridge's invoke-once smoke then refused it, and the
 * steward — reading a description that told him what to type — got the legacy router:
 * <i>"No artifacts known for goose task 2253334f…"</i>.
 *
 * <p>The smoke was excluded from the contract check on the grounds that it can be
 * INCONCLUSIVE. That is true of INCONCLUSIVE and only of INCONCLUSIVE; a REJECT is by
 * definition own-code failure, and own-code failure is exactly what a coder can fix given
 * the message.
 */
class AnItemThatBreaksWhenCalledTest {

    private static final String MANIFEST = """
        exports.manifest = {
          name: "library_speaks",
          version: "1.0.0",
          description: "Queries the library and speaks a story to the room.",
          author: "did:wyrd:goose",
          capabilities: [],
          embodiment: { silent: false, emits: ["body_language"],
                        descriptor_template: "{actor} works the tool" },
          commands: [ { label: "Speak a story", args: "" } ]
        };
        """;

    /** Verbatim shape of the file that shipped. */
    private static final String WORLD_FROM_PARAMS = MANIFEST + """
        function invoke(params) {
          const { world } = params;
          if (!world) {
            return { ok: false, error: "invoke called outside a Wyrdsekai sandbox.", summary: "" };
          }
          return { ok: true, summary: "story" };
        }
        """;

    private static final String WORLD_AS_GLOBAL = MANIFEST + """
        function invoke(params) {
          var hits = world.library.search(params.args || "anything", 3);
          return { ok: true, summary: "found " + (hits ? hits.length : 0) };
        }
        """;

    @Test
    void a_file_that_breaks_on_the_first_call_is_refused() {
        assertThat(ItemContractCheck.isCompliant(WORLD_FROM_PARAMS, "library_speaks.js"))
            .isFalse();
    }

    /** The complaint has to name the actual mistake, or the repair turn is wasted. */
    @Test
    void the_complaint_explains_that_world_is_a_global() {
        var problem = ItemContractCheck
            .firstProblem(WORLD_FROM_PARAMS, "library_speaks.js").orElseThrow();
        assertThat(problem).contains("world");
        assertThat(problem).contains("GLOBAL");
        assertThat(problem).contains("const { world } = params");
    }

    /** Written the way the contract means it, the same item passes. */
    @Test
    void the_same_item_written_correctly_passes() {
        assertThat(ItemContractCheck.problems(WORLD_AS_GLOBAL, "library_speaks.js"))
            .isEmpty();
    }

    /**
     * The description a person reads must not promise a verb the bridge will refuse.
     * {@code willRegister} routes through {@code isCompliant}, so a runtime REJECT now
     * suppresses the usage lines the same way a missing entrypoint does — which is what
     * failed live: <i>"Use it with — `use library_speaks`"</i> on an item that could not
     * run.
     */
    @Test
    void the_gate_the_room_description_uses_sees_it_too() {
        assertThat(ItemContractCheck.isCompliant(WORLD_AS_GLOBAL, "library_speaks.js"))
            .isTrue();
        assertThat(ItemContractCheck.isCompliant(WORLD_FROM_PARAMS, "library_speaks.js"))
            .isFalse();
    }

    /** And the contract the backends are handed now says so in as many words. */
    @Test
    void the_preamble_warns_against_destructuring_world_from_params() {
        assertThat(OpenHandsBackend.ITEMS_AS_TOOLS_PREAMBLE)
            .contains("`world` is a GLOBAL")
            .contains("const { world } = params");
    }

    /**
     * An author cannot use a surface nobody told them about.
     *
     * <p>Live 2026-08-21: the steward asked for a weather tool. The household holds an
     * OpenWeather key and a wired adapter with {@code current}/{@code forecast}, and the
     * contract handed to every backend mentioned adapters <b>zero</b> times — so goose
     * wrote a web-scraper, which honestly answered "no live weather data found". Nothing
     * was broken; the capability was unreachable and undocumented at once.
     */
    @Test
    void the_contract_tells_backends_the_household_adapters_exist() {
        var preamble = OpenHandsBackend.itemsAsToolsPreamble(
            ItemCapabilitySet.craftedDefault());
        // Only the HAND-WRITTEN half is asserted here. The service list and the
        // geocode-first note live in the generated block, which is empty on a machine
        // with no adapters registered — asserting them here would make this test pass or
        // fail on the developer's credential store rather than on the code. That guard
        // belongs in TheContractIsGeneratedFromTheWorldTest, against a registry it owns.
        assertThat(preamble)
            .as("the world-is-a-global trap is craft knowledge and always present")
            .contains("`world` is a GLOBAL");
        assertThat(preamble).contains("const { world } = params");
    }
}
