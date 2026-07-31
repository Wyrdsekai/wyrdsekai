package org.wyrdsekai.core.coding;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.item.ScriptedItemLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.5 D5 — coding-backend → bridge §18 REJECT gate.
 *
 * <p>The full agent flow is:</p>
 * <ol>
 *   <li>Agent dispatches a coding backend (default: goose) to author a tool.</li>
 *   <li>{@link OpenHandsBackend#ITEMS_AS_TOOLS_PREAMBLE} (also used by Goose
 *       via {@code GooseBackend.buildArgs}) tells the model "embodiment block
 *       REQUIRED, here are the two shapes." This is the PROMPT-side enforcement.</li>
 *   <li>Backend writes a {@code .js} into the workspace.</li>
 *   <li>Placement event reaches {@link CodingTaskItemBridge#tryRegisterScriptedItem}
 *       which runs the §18 pre-check via
 *       {@code DynamicFormValidator.requireEmbodiment(...)} BEFORE handing to
 *       {@link ScriptedItemLoader#register}. This is the SERVER-side enforcement.</li>
 *   <li>If embodiment is missing/invalid: the bridge logs
 *       {@code "§18 REJECT registering agent-authored scripted item from … (RoomObject …): [key] detail"}
 *       and skips registration. If valid: the item registers and the bridge logs
 *       {@code "registered scripted item '…' from …"}.</li>
 * </ol>
 *
 * <p>This test exercises step 4–5 with hand-crafted .js fixtures standing in
 * for goose output — proves the gate fires both directions (accept happy-path,
 * reject embodiment-less). Catches drift in either direction: if the bridge
 * gate gets removed/bypassed, or if the validator changes the rejection
 * signature, the assertions fail.</p>
 *
 * <p>Why we don't run goose itself in this test: goose-end E2E lives in
 * {@code GooseE2ETest} (tier2, needs goose installed). This is the structural
 * gate test — orthogonal, runs in core unit suite, fast.</p>
 */
class CodingTaskItemBridgeEmbodimentRejectTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger bridgeLog;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        bridgeLog = (Logger) LoggerFactory.getLogger(CodingTaskItemBridge.class);
        bridgeLog.addAppender(appender);
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    @AfterEach
    void detach() {
        bridgeLog.detachAppender(appender);
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    /** Build a SourceArtifact pointing at {@code workspace} with one declared file. */
    private static SourceArtifact source(Path workspace, String fileName) {
        return new SourceArtifact(
            UUID.randomUUID(),
            "goose",  // backend label — could be openhands, cline, etc.; the
                      // bridge doesn't switch on it for the §18 gate
            "task-" + UUID.randomUUID().toString().substring(0, 8),
            workspace.toString(),
            List.of(fileName),
            null,                  // git ref
            Instant.now(),
            Map.of());
    }

    private static RoomObject placement(String id) {
        return new RoomObject(id, "Agent-Authored Tool",
            "A tool freshly authored by the coding backend.", true);
    }

    // ─── Reject path: missing embodiment block ──────────────────────────────

    @Test
    void rejects_agent_authored_item_with_missing_embodiment_block(@TempDir Path workspace)
            throws Exception {
        var fileName = "no_embodiment_tool.js";
        var script = """
            // Agent forgot the §18 embodiment block entirely — the goose preamble
            // says it's required, but the model can still ship malformed output.
            // The bridge gate is the safety net.
            exports.manifest = {
              name: "no_embodiment_tool",
              version: "1.0.0",
              description: "Missing §18 embodiment block.",
              author: "did:wyrd:goose"
            };
            function invoke(params) { return { ok: true }; }
            """;
        Files.writeString(workspace.resolve(fileName), script);

        var roomObj = placement("codex-room-obj-" + UUID.randomUUID().toString().substring(0, 6));
        CodingTaskItemBridge.tryRegisterScriptedItem(roomObj, source(workspace, fileName));

        // Bridge logged the §18 REJECT line with the room-object id + denial key.
        var rejectLogs = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("§18 REJECT"))
            .toList();
        assertThat(rejectLogs)
            .as("bridge must emit the §18 REJECT log when embodiment is missing — "
                + "captured logs: " + appender.list)
            .isNotEmpty();
        var rejectMsg = rejectLogs.get(0).getFormattedMessage();
        assertThat(rejectMsg)
            .contains(roomObj.id())
            .contains(fileName)
            .contains("embodiment.reject_missing");

        // ScriptedItemLoader does NOT have the item registered.
        var loaded = ScriptedItemLoader.get().all();
        assertThat(loaded)
            .as("rejected items must not appear in ScriptedItemLoader.all()")
            .extracting(d -> d.itemId())
            .doesNotContain("no_embodiment_tool");
    }

    @Test
    void rejects_agent_authored_item_with_structurally_invalid_embodiment(
            @TempDir Path workspace) throws Exception {
        var fileName = "bad_embodiment_tool.js";
        // Embodiment present but missing the silent/emits choice (must be one
        // of the two declared shapes) — DynamicFormValidator catches this too.
        var script = """
            exports.manifest = {
              name: "bad_embodiment_tool",
              version: "1.0.0",
              description: "Embodiment block present but malformed.",
              author: "did:wyrd:goose",
              embodiment: {
                // No `silent` and no `emits` — neither shape satisfied.
                reason: "this is a partial fragment"
              }
            };
            function invoke(params) { return { ok: true }; }
            """;
        Files.writeString(workspace.resolve(fileName), script);

        var roomObj = placement("codex-bad-" + UUID.randomUUID().toString().substring(0, 6));
        CodingTaskItemBridge.tryRegisterScriptedItem(roomObj, source(workspace, fileName));

        var rejectLogs = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("§18 REJECT"))
            .toList();
        assertThat(rejectLogs).isNotEmpty();
        var rejectMsg = rejectLogs.get(0).getFormattedMessage();
        // Could be either missing OR invalid depending on what the validator
        // detects first; both are valid REJECT signatures.
        assertThat(rejectMsg)
            .satisfiesAnyOf(
                msg -> assertThat(msg).contains("embodiment.reject_missing"),
                msg -> assertThat(msg).contains("embodiment.reject_invalid"));

        assertThat(ScriptedItemLoader.get().all())
            .extracting(d -> d.itemId())
            .doesNotContain("bad_embodiment_tool");
    }

    // ─── Accept path: well-formed embodiment block ──────────────────────────

    @Test
    void accepts_agent_authored_item_with_well_formed_silent_embodiment(
            @TempDir Path workspace) throws Exception {
        var fileName = "silent_tool.js";
        var script = """
            // Goose follows the preamble: declares silent + reason.
            exports.manifest = {
              name: "silent_tool",
              version: "1.0.0",
              description: "Agent-authored silent tool.",
              author: "did:wyrd:goose",
              embodiment: { silent: true, reason: "internal calculation, no body trace" },
              commands: [ { label: "Run the calculation", args: "" } ]
            };
            function invoke(params) { return { ok: true, result: 42 }; }
            """;
        Files.writeString(workspace.resolve(fileName), script);

        var roomObj = placement("codex-good-silent-" + UUID.randomUUID().toString().substring(0, 6));
        CodingTaskItemBridge.tryRegisterScriptedItem(roomObj, source(workspace, fileName));

        // No REJECT log for this file.
        var rejectLogs = appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("§18 REJECT"))
            .filter(e -> e.getFormattedMessage().contains(fileName))
            .toList();
        assertThat(rejectLogs)
            .as("happy-path silent embodiment must not trip the §18 gate")
            .isEmpty();

        // Bridge emitted the "registered scripted item" success log.
        var successLogs = appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("registered scripted item")
                && e.getFormattedMessage().contains("silent_tool"))
            .toList();
        assertThat(successLogs)
            .as("bridge must log successful registration for the silent_tool — captured: "
                + appender.list)
            .isNotEmpty();

        // Item appears in the ScriptedItemLoader registry.
        assertThat(ScriptedItemLoader.get().all())
            .extracting(d -> d.itemId())
            .contains("silent_tool");
    }

    @Test
    void accepts_agent_authored_item_with_well_formed_emits_embodiment(
            @TempDir Path workspace) throws Exception {
        var fileName = "emits_tool.js";
        var script = """
            exports.manifest = {
              name: "emits_tool",
              version: "1.0.0",
              description: "Agent-authored tool that broadcasts body language.",
              author: "did:wyrd:goose",
              embodiment: {
                silent: false,
                emits: ["body_language"],
                descriptor_template: "{actor} consults the agent-authored tool"
              },
              commands: [ { label: "Consult the tool", args: "" } ]
            };
            function invoke(params) { return { ok: true }; }
            """;
        Files.writeString(workspace.resolve(fileName), script);

        var roomObj = placement("codex-good-emits-" + UUID.randomUUID().toString().substring(0, 6));
        CodingTaskItemBridge.tryRegisterScriptedItem(roomObj, source(workspace, fileName));

        assertThat(appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("§18 REJECT"))
                .filter(e -> e.getFormattedMessage().contains(fileName)))
            .isEmpty();
        assertThat(ScriptedItemLoader.get().all())
            .extracting(d -> d.itemId())
            .contains("emits_tool");
    }

    // ─── Mixed batch: bad + good in same workspace, only good registers ────

    @Test
    void mixed_batch_only_well_formed_items_register(@TempDir Path workspace) throws Exception {
        var bad = "mixed_bad.js";
        Files.writeString(workspace.resolve(bad), """
            exports.manifest = {
              name: "mixed_bad",
              version: "1.0.0",
              description: "Bad one in a mixed batch.",
              author: "did:wyrd:goose"
            };
            function invoke(p) { return {}; }
            """);
        var good = "mixed_good.js";
        Files.writeString(workspace.resolve(good), """
            exports.manifest = {
              name: "mixed_good",
              version: "1.0.0",
              description: "Good one in a mixed batch.",
              author: "did:wyrd:goose",
              embodiment: { silent: true, reason: "no body trace needed" },
              commands: [ { label: "Use the good one", args: "" } ]
            };
            function invoke(p) { return {}; }
            """);

        // The bridge stops at the first registered file (per the "one file per
        // task" contract — see comment near `break;` in tryRegisterScriptedItem).
        // So the test runs each separately so we can assert both halves.
        var roomBad = placement("codex-mixed-bad-" + UUID.randomUUID().toString().substring(0, 6));
        CodingTaskItemBridge.tryRegisterScriptedItem(roomBad, source(workspace, bad));

        var roomGood = placement("codex-mixed-good-" + UUID.randomUUID().toString().substring(0, 6));
        CodingTaskItemBridge.tryRegisterScriptedItem(roomGood, source(workspace, good));

        var ids = ScriptedItemLoader.get().all().stream()
            .map(d -> d.itemId()).toList();
        assertThat(ids).contains("mixed_good");
        assertThat(ids).doesNotContain("mixed_bad");

        var badRejected = appender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("§18 REJECT")
                && e.getFormattedMessage().contains(bad));
        assertThat(badRejected)
            .as("bad item must have triggered the §18 REJECT log")
            .isTrue();
    }
}
