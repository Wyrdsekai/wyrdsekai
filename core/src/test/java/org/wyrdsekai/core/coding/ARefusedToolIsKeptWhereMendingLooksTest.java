package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool the loader refused used to stay in its task's workspace: placed in her room as an object,
 * scanned by nothing, mended by nothing, explained to nobody (a companion's diffusion tool,
 * 2026-10-20). Now it is kept where the broken-item record and the night's mending look, and the
 * ones left behind by earlier tasks are picked up at the loader's scan.
 */
class ARefusedToolIsKeptWhereMendingLooksTest {

    private static String tool(String name, String caps) {
        return """
            exports.manifest = {
              name: "%s",
              version: "1.0.0",
              description: "A tool",
              author: "did:wyrd:test",
              capabilities: [%s],
              embodiment: { silent: false, emits: ["body_language"], descriptor_template: "{actor} works" },
              commands: [ { label: "Go", args: "" } ]
            };
            function invoke(params) { world.notes.list("x"); return { ok: true, summary: "went" }; }
            """.formatted(name, caps);
    }

    @Test
    @DisplayName("refused just now: kept under its manifest's name; a tool already there is never overwritten")
    void keptNow(@TempDir Path tmp) throws Exception {
        var items = tmp.resolve("items");
        ScriptedItemLoader.get();   // the loader's own folders are fixed when it is first touched: not this test's
        System.setProperty("wyrdsekai.items.dir", items.toString());
        try {
            var ws = Files.createDirectories(tmp.resolve("ws/task-1"));
            var refused = ws.resolve("draft-bench.js");
            Files.writeString(refused, tool("draft_bench", "\"notes.read\""));
            var kept = CodingTaskItemBridge.keepRefusedItem(refused);
            assertThat(kept).isEqualTo(items.resolve("draft_bench.js"));
            assertThat(Files.readString(kept)).contains("draft_bench");
            Files.writeString(refused, tool("draft_bench", "\"notes.list\""));
            assertThat(CodingTaskItemBridge.keepRefusedItem(refused)).as("not overwritten").isNull();
            assertThat(Files.readString(kept)).contains("notes.read");
        } finally {
            System.clearProperty("wyrdsekai.items.dir");
        }
    }

    @Test
    @DisplayName("left behind by earlier tasks: the refused ones are adopted, the whole, the superseded and the bench's copies are not")
    void adoptedAtScan(@TempDir Path tmp) throws Exception {
        var items = Files.createDirectories(tmp.resolve("items"));
        var root = tmp.resolve("coding-workspaces");
        Files.createDirectories(root.resolve("task-a"));
        Files.writeString(root.resolve("task-a/diffusion_prompt.js"), tool("diffusion_prompt", "\"notes.read\""));   // refused for its contract
        Files.createDirectories(root.resolve("task-b"));
        Files.writeString(root.resolve("task-b/mirror.js"), tool("mirror", "\"notes.list\""));                       // whole: the bridge kept it already
        Files.createDirectories(root.resolve("task-c"));
        Files.writeString(root.resolve("task-c/old-scroll.js"), tool("old-scroll", "\"notes.read\""));              // superseded by the corrected sibling
        Files.writeString(root.resolve("task-c/old_scroll.js"), tool("old_scroll", "\"notes.list\""));
        Files.createDirectories(root.resolve("repair-x-20261020"));
        Files.writeString(root.resolve("repair-x-20261020/bench.js"), tool("bench", "\"notes.read\""));            // the mending bench's own copy
        Files.createDirectories(root.resolve("task-d"));
        Files.writeString(root.resolve("task-d/notes.txt"), "not a tool");
        Files.writeString(root.resolve("task-d/already.js"), tool("already", "\"notes.read\""));
        Files.writeString(items.resolve("already.js"), tool("already", "\"notes.list\""));                           // lives there already
        // A tool the loader would LOAD (manifest whole) but whose code reaches for what is not there: the
        // smoke refused it at creation for crashing in the hand. The first sweep adopted 53 of these on the
        // household node and put them on her menu (2026-10-20). Not adopted. And nothing already in the
        // items folder is ever removed by the sweep: an accepted tool is a byte-for-byte copy of its
        // workspace file too, and a second sweep that "undid" the first took 118 of hers (2026-10-20).
        Files.createDirectories(root.resolve("task-e"));
        var crashy = tool("ledger", "\"journal.search\"").replace("world.notes.list(\"x\");", "world.journal.read(\"x\");");
        Files.writeString(root.resolve("task-e/ledger.js"), crashy);
        Files.createDirectories(root.resolve("task-f"));
        var accepted = tool("mirror_kept", "\"notes.list\"");
        Files.writeString(root.resolve("task-f/mirror_kept.js"), accepted);
        Files.writeString(items.resolve("mirror_kept.js"), accepted);                                                  // kept at registration, hers
        assertThat(ItemContractCheck.loaderRefuses(crashy, "ledger.js")).as("the loader would load it").isEmpty();

        assertThat(CodingTaskItemBridge.adoptRefusedTools(root, items)).isEqualTo(1);
        try (var kept = Files.list(items)) {
            assertThat(kept.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("already.js", "diffusion_prompt.js", "mirror_kept.js");
        }
        assertThat(Files.exists(root.resolve("task-e/ledger.js"))).as("the workspace keeps its file").isTrue();
        assertThat(Files.readString(items.resolve("already.js"))).as("not overwritten").contains("notes.list");
        assertThat(CodingTaskItemBridge.adoptRefusedTools(root, items)).as("a second scan adopts nothing more").isZero();
        assertThat(CodingTaskItemBridge.adoptRefusedTools(tmp.resolve("missing"), items)).isZero();
        assertThat(CodingTaskItemBridge.adoptRefusedTools(root, null)).isZero();
    }

    @Test
    @DisplayName("put away by the steward: its old task copies are not adopted back")
    void putAwayStaysAway(@TempDir Path tmp) throws Exception {
        var items = Files.createDirectories(tmp.resolve("items"));
        var away = Files.createDirectories(tmp.resolve("items-away"));
        var root = tmp.resolve("coding-workspaces");
        Files.createDirectories(root.resolve("task-a"));
        Files.writeString(root.resolve("task-a/skills_check.js"), tool("skills_check", "\"notes.read\""));   // refused, would be adopted
        Files.writeString(away.resolve("skills_check.js"), tool("skills_check", "\"notes.read\""));          // the steward put it away
        Files.createDirectories(root.resolve("task-b"));
        Files.writeString(root.resolve("task-b/room_check.js"), tool("room_check", "\"notes.read\""));       // not put away: adopted
        assertThat(CodingTaskItemBridge.adoptRefusedTools(root, items)).isEqualTo(1);
        try (var kept = Files.list(items)) {
            assertThat(kept.map(f -> f.getFileName().toString()).toList()).containsExactly("room_check.js");
        }
    }
}
