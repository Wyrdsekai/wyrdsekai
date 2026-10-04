package org.wyrdsekai.core.item;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.scripting.api.ItemApiSurface;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An item that calls a part of the world that does not exist is named as broken, mended with that
 * knowledge, and set aside when it cannot be mended.
 *
 * <p>`sightfinder` was built for a companion by the coding backend on 2026-09-13. Its script calls
 * {@code world.sightfinder.probe}: a namespace named after the item itself. The wiring check was
 * silent about a namespace it did not know (it might be an outside adapter), the capability audit
 * said the call was undeclared, the repairer declared it, the validator then refused the invented
 * capability, and after three nights mending gave up for good. The item stayed on her menu, took
 * one of her eight tool places, and she reached for it for three weeks (household node, 2026-10-03).
 */
class AnItemThatCannotBeMendedIsSetAsideTest {

    @TempDir Path dir;

    private static final String SIGHTFINDER = """
        exports.manifest = {
          name: "sightfinder", version: "1.0.0",
          description: "Probes the current room and surfaces the first named item.",
          author: "did:wyrd:openhands", capabilities: [],
          embodiment: { silent: false, emits: ["body_language"], descriptor_template: "{actor} scans the room" },
          commands: [ { label: "Scan the room", args: "" } ]
        };
        function invoke(params) {
          var result = world.sightfinder.probe(params.args || "");
          return { ok: true, summary: result ? "Found" : "Nothing" };
        }
        """;

    @AfterEach
    void tearDown() {
        // The registry of outside adapters installs itself when it loads; put its supplier back.
        ItemApiSurface.installDynamicNamespaces(() -> org.wyrdsekai.core.external.ExternalAdapterRegistry.get().namespaces());
    }

    @Test
    void a_call_into_a_namespace_that_is_not_there_is_named_when_the_node_knows_its_adapters() {
        ItemApiSurface.installDynamicNamespaces(() -> Set.of("hue"));
        var unresolved = ItemApiSurface.check(SIGHTFINDER);
        assertThat(unresolved).hasSize(1);
        assertThat(unresolved.getFirst().reason()).startsWith("world.sightfinder.probe does not exist on this node: there is no world.sightfinder.")
            .contains("The world API has: ").contains("room").contains("Do not declare it as a capability");
        assertThat(ItemApiSurface.check("function invoke(p){ return world.hue.on('lamp'); }")).as("an adapter this node has").isEmpty();
        ItemApiSurface.installDynamicNamespaces(null);
        assertThat(ItemApiSurface.check(SIGHTFINDER)).as("not told which adapters exist: not judged").isEmpty();
    }

    @Test
    void when_mending_has_spent_its_tries_the_item_is_given_up_on_and_a_new_diagnosis_starts_the_count_again() throws Exception {
        ItemApiSurface.installDynamicNamespaces(Set::of);
        var file = dir.resolve("sightfinder.js");
        Files.writeString(file, SIGHTFINDER);
        var found = BrokenItems.find(dir);
        assertThat(found).hasSize(1);
        var entry = found.getFirst();
        assertThat(entry.failsOnUse()).isTrue();
        assertThat(BrokenItems.plainly(entry)).contains("reaches for a part of the world that is not there");
        assertThat(BrokenItems.givenUp(dir)).as("not tried yet").isEmpty();
        assertThat(BrokenItems.mendable(dir)).hasSize(1);

        for (int i = 0; i < BrokenItems.MAX_ATTEMPTS; i++) BrokenItems.attempted(entry, BrokenItems.fileHash(file), false);
        assertThat(BrokenItems.mendable(dir)).as("three tries on the same file and the same diagnosis").isEmpty();
        assertThat(BrokenItems.givenUp(dir)).extracting(BrokenItems.Entry::item).containsExactly("sightfinder");

        // The state as the household node holds it today: three attempts, counted before the checks
        // could name what is wrong. The better diagnosis is a new thing to try.
        var stateFile = BrokenItems.stateFile(dir);
        var state = (ObjectNode) Json.mapper().readTree(stateFile.toFile());
        ((ObjectNode) state.get("sightfinder")).remove("problemsHash");
        Json.mapper().writeValue(stateFile.toFile(), state);
        assertThat(BrokenItems.givenUp(dir)).isEmpty();
        assertThat(BrokenItems.mendable(dir)).hasSize(1);
    }

    @Test
    void the_loader_sets_it_aside_and_the_file_is_kept() throws Exception {
        var src = Files.readString(Files.exists(Path.of("..", "core/src/main/java/org/wyrdsekai/core/item/ScriptedItemLoader.java"))
            ? Path.of("..", "core/src/main/java/org/wyrdsekai/core/item/ScriptedItemLoader.java")
            : Path.of("core/src/main/java/org/wyrdsekai/core/item/ScriptedItemLoader.java"));
        assertThat(src).contains("setAsideWhatMendingGaveUpOn();");
        assertThat(src).contains("if (loaded.remove(e.item()) == null) continue;");
        assertThat(src).contains("BrokenItems.tellSetAside(e, org.wyrdsekai.core.body.BodyMap.get());");
        assertThat(src).doesNotContain("Files.delete(e.file())");
    }
}
