package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Using something you are carrying — the resolution every surface shares.
 *
 * <h2>Why one class</h2>
 * This logic was written three times and diverged three ways. SSH learned the arg-split
 * fallback in July and WS never did, so {@code use web-search antikythera} worked over ssh
 * and failed on the phone. SSH and WS learned in August that a backend-authored item keeps
 * its code in the loader rather than its inventory row. Telnet learned neither and sent
 * every {@code use} straight to the room, which does not hold what you are carrying — so
 * it answered "No such object" for an item in your hands.
 *
 * <p>Four surfaces patched one at a time for the same feature in two days. These are the
 * behaviours; there is now one place that has them.
 */
class AnItemYouAreHoldingIsStillAToolTest {

    private String jdbc;
    private InventoryService inventory;

    @BeforeEach
    void setUp() {
        jdbc = TestDb.createInMemory();
        inventory = new InventoryService(jdbc);
    }

    /**
     * The args spelling the contract actually teaches.
     *
     * <p>The items-as-tools preamble tells every backend that {@code use <name> <args>}
     * delivers {@code params.args}. {@code RoomActor} sets it for room-placed items; the
     * carried paths set only {@code target} and {@code query}. So goose wrote — correctly
     * against the contract it was given — {@code if (typeof params.args !== "string")
     * return an error}, and the item refused to work the moment it was picked up.
     */
    @Test
    void the_params_carry_every_spelling_a_script_may_read() {
        var params = CarriedItemUse.params("alice", "salt almanac");
        assertThat(params.get("args")).isEqualTo("salt almanac");
        assertThat(params.get("target")).isEqualTo("salt almanac");
        assertThat(params.get("query")).isEqualTo("salt almanac");
        assertThat(params.get("entityId")).isEqualTo("alice");
    }

    /** Never null: a script doing {@code params.args.trim()} must not NPE on a bare use. */
    @Test
    void a_bare_use_still_passes_a_string() {
        var params = CarriedItemUse.params("alice", null);
        assertThat(params.get("args")).isEqualTo("");
        assertThat(params.get("query")).isEqualTo("");
    }

    @Test
    void a_crafted_item_runs_from_its_own_row() {
        inventory.addItem("alice", "obj-1", "lens", "a lens", true, "crafted",
            "function invoke(p) { return { ok: true }; }", "lens");
        var resolved = CarriedItemUse.resolve(inventory, "alice", "lens", "");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().source()).contains("function invoke");
    }

    /**
     * The 2026-08-20 failure: {@code take} copies id, name, description and nothing else,
     * so a backend-authored item lost the link to its own code the moment it was picked
     * up. {@code isScripted()} said false and the room — which no longer held it —
     * answered "No such object".
     */
    @Test
    void a_backend_authored_item_keeps_working_once_picked_up() throws Exception {
        var dir = Files.createTempDirectory("carried-items");
        Files.writeString(dir.resolve("teller.js"), """
            exports.manifest = {
              name: "teller",
              version: "1.0.0",
              description: "Tells.",
              author: "did:wyrd:goose",
              capabilities: [],
              embodiment: { silent: true, reason: "quiet tool" },
              commands: [ { label: "Tell", args: "" } ]
            };
            function invoke(params) { return { ok: true }; }
            """);
        ScriptedItemLoader.get().register(dir.resolve("teller.js"));

        // The inventory row a `take` produces: no script columns at all.
        inventory.addItem("alice", "codex-abc", "teller", "Tells.", true, "nexus");

        var resolved = CarriedItemUse.resolve(inventory, "alice", "teller", "");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().source()).contains("function invoke");
    }

    /**
     * {@code use library_query salt almanac} arrives with the WHOLE phrase as the object
     * name. SSH learned to retry on the first token; WS never did.
     */
    @Test
    void the_whole_phrase_falls_back_to_item_plus_args() {
        inventory.addItem("alice", "obj-2", "reader", "reads", true, "crafted",
            "function invoke(p) { return { ok: true }; }", "reader");
        var resolved = CarriedItemUse.resolve(
            inventory, "alice", "reader salt almanac", "");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().item().objectName()).isEqualTo("reader");
        assertThat(resolved.get().target()).isEqualTo("salt almanac");
    }

    /** Something you are not carrying is not resolved — the room still gets its turn. */
    @Test
    void nothing_carried_means_nothing_resolved() {
        assertThat(CarriedItemUse.resolve(inventory, "alice", "ghost", ""))
            .isEmpty();
    }

    /** A carried item with no script anywhere is not a scripted use. */
    @Test
    void a_plain_object_is_not_a_scripted_use() {
        inventory.addItem("alice", "obj-3", "pebble", "a pebble", true, "nexus");
        assertThat(CarriedItemUse.resolve(inventory, "alice", "pebble", ""))
            .isEmpty();
    }
}
