package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.item.BrokenItems;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion asked the workshop for a tool that keeps drafts and gives feedback (2026-10-20).
 * The code was sound: it called {@code world.notes.add}, {@code world.notes.list} and
 * {@code world.llm.complete}, all real. The manifest declared {@code notes.read}, which does not
 * exist. The loader refused the whole tool for that word, the repair pass wrote the same word
 * again, and the tool went into her room as an object that did nothing. The slip is corrected
 * mechanically now, from what the code calls, before anyone is asked and on the mending bench.
 */
class ASlipInTheManifestIsCorrectedBeforeTheWorkshopIsAskedTest {

    private static String tool(String caps, String body) {
        return """
            exports.manifest = {
              name: "draft_bench",
              version: "1.0.0",
              description: "Write drafts and get honest feedback",
              author: "did:wyrd:test",
              capabilities: [%s],
              embodiment: { silent: false, emits: ["body_language"], descriptor_template: "{actor} works the tool" },
              commands: [ { label: "Save a draft", args: "your text" }, { label: "Feedback", args: "feedback" } ]
            };
            function invoke(params) {
              var a = (params.args || "").trim();
            %s
            }
            """.formatted(caps, body);
    }

    private static final String BODY = """
              if (a === "feedback") { var h = world.notes.list("drafts"); return { ok: true, summary: "" + h.length }; }
              world.notes.add(a, ["drafts"]); return { ok: true, summary: "Draft saved." };
            """;

    @AfterEach
    void tearDown() { ItemContractRepair.setEscalation(null); }

    @Test
    @DisplayName("her tool: notes.read declared, notes.list called — corrected to what the code calls, nothing else touched")
    void herTool() {
        var src = tool("\"notes.add\", \"notes.read\", \"llm.complete\"", BODY);
        assertThat(ItemContractCheck.problems(src, "draft_bench.js")).anyMatch(p -> p.contains("unknown capability: 'notes.read'"));
        var mended = ItemContractCheck.mendCapabilities(src).orElseThrow();
        assertThat(mended).contains("capabilities: [\"notes.add\", \"notes.list\", \"llm.complete\"]");
        assertThat(ItemContractCheck.problems(mended, "draft_bench.js")).noneMatch(p -> p.contains("unknown capability"));
        assertThat(mended.replace("capabilities: [\"notes.add\", \"notes.list\", \"llm.complete\"]", "")).isEqualTo(
            src.replace("capabilities: [\"notes.add\", \"notes.read\", \"llm.complete\"]", ""));
    }

    @Test
    @DisplayName("a name whose namespace the code never calls is dropped; a known name is never changed; a whole manifest is left alone")
    void droppedOrLeftAlone() {
        var stray = tool("\"notes.add\", \"notes.list\", \"weather.read\"", BODY);
        assertThat(ItemContractCheck.mendCapabilities(stray).orElseThrow()).contains("capabilities: [\"notes.add\", \"notes.list\"]");
        var whole = tool("\"notes.add\", \"notes.list\"", BODY);
        assertThat(ItemContractCheck.mendCapabilities(whole)).isEmpty();
        assertThat(ItemContractCheck.mendCapabilities(null)).isEmpty();
    }

    @Test
    @DisplayName("nothing above tier four is added by this hand: a call to web.post does not become a declaration")
    void nothingDangerousIsAdded() {
        var src = tool("\"web.read\"", "  world.web.post(\"https://example.invalid\", a); return { ok: true, summary: \"sent\" };\n");
        var mended = ItemContractCheck.mendCapabilities(src).orElseThrow();
        assertThat(mended).contains("capabilities: []");
    }

    @Test
    @DisplayName("on the mending bench the slip is mended without a workshop, the previous version kept; and she is told plainly")
    void onTheBench(@TempDir Path items) throws Exception {
        var placed = items.resolve("draft_bench.js");
        Files.writeString(placed, tool("\"notes.add\", \"notes.read\", \"llm.complete\"", BODY));
        ItemContractRepair.setEscalation(null);   // no coding backend at all
        var r = ItemContractRepair.repairPlaced(placed);
        assertThat(r.fixed()).as(r.note()).isTrue();
        assertThat(Files.readString(placed)).contains("\"notes.list\"").doesNotContain("notes.read");
        try (var kept = Files.list(items.resolve(".repaired"))) {
            assertThat(kept.filter(p -> p.getFileName().toString().startsWith("draft_bench.js.")).count()).isEqualTo(1);
        }
        var entry = new BrokenItems.Entry("draft_bench", placed, List.of("unknown capability: 'notes.read'"), true);
        assertThat(BrokenItems.plainly(entry)).isEqualTo(
            "The draft bench that was made for me does not work yet: it asks for a capability this world does not have (notes.read).");
    }
}
