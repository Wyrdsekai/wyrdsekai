package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole path, on the real file, with nothing stubbed out between the script and the
 * words a person reads.
 *
 * <h2>Why this test and not three unit tests</h2>
 * The recorded lesson from 2026-08-19 was <b>test the seams — every bridge test started
 * downstream of the break</b>, and on 2026-08-21 I proved it again by fixing three
 * separate defects on this path while each unit test stayed green and the person still
 * got nothing usable. So this runs
 * {@code core/src/test/resources/items/library_keeper.js} — byte-for-byte the file goose
 * wrote and the bridge accepted on the household node — through the real
 * {@link ItemScriptExecutor} against a player-shaped provider, and asserts on the two
 * things that actually reach a person: what the room hears, and what the terminal prints.
 *
 * <p>Three defects it would have caught, none of which it was written after guessing:
 * <ol>
 *   <li>{@code params.args} unset on the carried path → the script's own guard rejects
 *       every use.</li>
 *   <li>{@code world.agent.speak} a no-op for a player-held item → the room stays
 *       silent.</li>
 *   <li>{@code world.library.search} / {@code world.llm.summarize} answering
 *       "visiting foreign zone" inside the person's own house → the story reads
 *       {@code "found 1 items … [LLM unavailable — visiting foreign zone]"}.</li>
 * </ol>
 */
class TheToolSheBuiltActuallyAnswersTest {

    private ItemScriptExecutor executor;
    private String script;

    /** A household that has a library and a model, standing in for the real ones. */
    private static final class FakeHousehold extends VisitorItemProvider {
        FakeHousehold() { super("home", "home"); }

        @Override
        public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            return List.of(
                Map.of("id", "c1", "title", "The Salt Almanac", "text", "A ledger of tides.",
                       "score", 0.9),
                Map.of("id", "c2", "title", "Tidewater Notes", "text", "A road with no end.",
                       "score", 0.7));
        }

        @Override
        public String llmSummarize(String text, String instruction) {
            return "A paragraph about " + text;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        executor = new ItemScriptExecutor();
        try (var in = getClass().getResourceAsStream("/items/library_keeper.js")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void tearDown() {
        executor.close();
        HouseholdItemContent.resetForTests();
    }

    /** The provider a person gets: visitor-shaped, but at home. */
    private VisitorItemProvider playerProvider(AtomicReference<String> heard) {
        return new VisitorItemProvider("home", "home")
            .withHouseholdContent(new FakeHousehold())
            .withRoomVoice(heard::set);
    }

    @Test
    void it_answers_with_a_story_not_a_foreign_zone_stub() {
        var heard = new AtomicReference<String>();
        var out = executor.execute("library_keeper", script,
            CarriedItemUse.params("alice", "salt almanac"),
            playerProvider(heard), ItemCapabilitySet.UNRESTRICTED);

        var text = ItemScriptResponse.extractText(out, "library_keeper");
        assertThat(text)
            .doesNotContain("LLM unavailable")
            .doesNotContain("visiting foreign zone")
            .isNotEqualTo("You use the library_keeper.")
            .contains("A paragraph about");
    }

    /** He asked for a tool that speaks it out loud to the room. It has to reach the room. */
    @Test
    void the_story_is_spoken_into_the_room() {
        var heard = new AtomicReference<String>();
        executor.execute("library_keeper", script,
            CarriedItemUse.params("alice", "salt almanac"),
            playerProvider(heard), ItemCapabilitySet.UNRESTRICTED);

        assertThat(heard.get()).isNotNull();
        assertThat(heard.get()).contains("A paragraph about");
    }

    /**
     * The script's first statement is
     * {@code if (typeof params.args !== "string") return an error} — correct against the
     * contract it was given, and fatal while the carried path set only target/query.
     */
    @Test
    void the_args_the_contract_promises_reach_the_script() {
        var out = executor.execute("library_keeper", script,
            CarriedItemUse.params("alice", "details"),
            playerProvider(new AtomicReference<>()), ItemCapabilitySet.UNRESTRICTED);

        var text = ItemScriptResponse.extractText(out, "library_keeper");
        assertThat(text)
            .doesNotContain("requires a query string")
            .contains("searches the library");
    }

    /**
     * And abroad it still tells the truth. The foreign-zone stubs are correct when you
     * ARE abroad — the bug was only ever that a person at home got them.
     */
    @Test
    void a_genuine_foreign_zone_still_says_so() {
        var out = executor.execute("library_keeper", script,
            CarriedItemUse.params("alice", "salt almanac"),
            new VisitorItemProvider("far", "far"), ItemCapabilitySet.UNRESTRICTED);

        var text = ItemScriptResponse.extractText(out, "library_keeper");
        assertThat(text).contains("foreign zone");
    }

    /**
     * And under the ceiling it ACTUALLY runs with.
     *
     * <p>A backend-authored item is not a trusted bundled one, so
     * {@link CarriedItemUse#capabilitiesFor} hands it {@code craftedDefault()}. Proving
     * the path unrestricted proves nothing about production — the capability set is
     * exactly the kind of thing that turns a working item into a denial in a person's
     * hands, and the real object id is what selects it.
     */
    @Test
    void it_still_answers_under_the_crafted_ceiling_a_real_item_gets() {
        var caps = CarriedItemUse.capabilitiesFor("codex-cd2492e9");
        assertThat(caps).isSameAs(ItemCapabilitySet.craftedDefault());

        var heard = new AtomicReference<String>();
        var out = executor.execute("library_keeper", script,
            CarriedItemUse.params("alice", "salt almanac"),
            playerProvider(heard), caps);

        assertThat(out).doesNotContainKey("capability_denied");
        var text = ItemScriptResponse.extractText(out, "library_keeper");
        assertThat(text)
            .doesNotContain("LLM unavailable")
            .contains("A paragraph about");
        assertThat(heard.get()).contains("A paragraph about");
    }
}
