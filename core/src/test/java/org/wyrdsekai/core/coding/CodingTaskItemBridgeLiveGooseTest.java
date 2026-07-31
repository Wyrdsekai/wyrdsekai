package org.wyrdsekai.core.coding;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * v1.5 — LIVE-VERIFY for the goose → bridge → registered-item
 * chain. Picks up an actual goose-produced file from {@link #LIVE_FILE} and
 * runs it through the production {@link CodingTaskItemBridge} gate.
 *
 * <p>Gated on the env var {@code WYRDSEKAI_LIVE_GOOSE_FILE} pointing
 * at a real .js file goose just wrote. Skipped by default in CI. To run:
 *
 * <pre>
 *   WYRDSEKAI_LIVE_GOOSE_FILE=/tmp/goose-live-embodiment-test/note_taker.js \
 *     ./gradlew :core:test --tests CodingTaskItemBridgeLiveGooseTest
 * </pre>
 *
 * <p>Procedure for a full live verify:</p>
 * <ol>
 *   <li>Run goose with the items-as-tools preamble pointed at a live LLM
 *       (e.g. local llama-server on :8200 via {@code OPENAI_HOST}). Goose
 *       writes a .js into a workspace.</li>
 *   <li>Run this test pointing at that .js. The test feeds it through the
 *       production bridge gate (same {@link CodingTaskItemBridge#tryRegisterScriptedItem}
 *       call site as live agent dispatch) and asserts the item registers.</li>
 * </ol>
 *
 * <p>What this catches that the fixture tests don't:</p>
 * <ul>
 *   <li>Prompt drift — if {@code OpenHandsBackend.ITEMS_AS_TOOLS_PREAMBLE}
 *       stops actually teaching real models to emit embodiment, the goose
 *       output won't have the field and the bridge rejects it. Fixture tests
 *       can't see this because they hand-craft inputs.</li>
 *   <li>Model-shape mismatch — if a real model omits {@code author}, {@code
 *       version}, or other validator-required fields, the bridge's
 *       upstream {@code ScriptedItemLoader.register} fails after the §18
 *       gate passes. Live runs surface this.</li>
 * </ul>
 */
@Tag("tier2")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_GOOSE_FILE", matches = ".+")
class CodingTaskItemBridgeLiveGooseTest {

    private static final String LIVE_FILE = System.getenv("WYRDSEKAI_LIVE_GOOSE_FILE");

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

    @Test
    void live_goose_output_passes_bridge_s18_gate_and_registers() throws Exception {
        var file = Path.of(LIVE_FILE);
        assertThat(Files.exists(file))
            .as("live goose-produced file must exist at " + LIVE_FILE)
            .isTrue();

        var script = Files.readString(file);
        System.out.println("=== Live goose output (first 500 chars) ===");
        System.out.println(script.length() > 500 ? script.substring(0, 500) + "…" : script);
        System.out.println("=== End ===");

        // Sanity assertions before bridge — proves goose actually obeyed
        // the items-as-tools preamble. If these fail, the prompt didn't
        // reach the model OR the model ignored it.
        assertThat(script)
            .as("goose output must contain exports.manifest — items-as-tools "
                + "preamble was either ignored or not delivered")
            .contains("exports.manifest");
        assertThat(script)
            .as("goose output must contain embodiment block "
                + "§18 part of the preamble was either ignored or not delivered")
            .contains("embodiment");

        // Hand to bridge — same call the production placement event-bridge makes.
        var workspace = file.getParent();
        var src = new SourceArtifact(
            UUID.randomUUID(),
            "goose",
            "live-task-" + UUID.randomUUID().toString().substring(0, 8),
            workspace.toString(),
            List.of(file.getFileName().toString()),
            null,
            Instant.now(),
            Map.of());
        var room = new RoomObject(
            "codex-live-" + UUID.randomUUID().toString().substring(0, 6),
            "Live Goose Tool",
            "Tool produced by a real goose run against the local LLM",
            true);

        CodingTaskItemBridge.tryRegisterScriptedItem(room, src);

        // No §18 REJECT in the bridge log.
        var rejectLogs = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("§18 REJECT"))
            .toList();
        assertThat(rejectLogs)
            .as("goose output passed the §18 gate — but bridge logged REJECT: "
                + rejectLogs.stream().map(e -> e.getFormattedMessage()).toList())
            .isEmpty();

        // Bridge logged successful registration.
        var successLogs = appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("registered scripted item"))
            .toList();
        assertThat(successLogs)
            .as("bridge must log successful registration of goose output. "
                + "Full bridge log:\n  "
                + appender.list.stream()
                    .map(e -> e.getLevel() + " " + e.getFormattedMessage())
                    .reduce("", (a, b) -> a + "\n  " + b))
            .isNotEmpty();

        // Item appears in the ScriptedItemLoader registry.
        var loaded = ScriptedItemLoader.get().all();
        assertThat(loaded)
            .as("goose-authored item must register in ScriptedItemLoader. Loaded ids: "
                + loaded.stream().map(d -> d.itemId()).toList())
            .isNotEmpty();
        System.out.println("=== Live verify PASSED — " + loaded.size()
            + " item(s) registered from goose output ===");
        loaded.forEach(d -> System.out.println("  registered: " + d.itemId()));
    }
}
