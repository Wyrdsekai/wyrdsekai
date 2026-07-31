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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Items-as-tools contract — bridge-side degradation for agent-authored
 * artifacts that violate the {@code commands}/entrypoint contract.
 *
 * <p>Companion to {@link CodingTaskItemBridgeEmbodimentRejectTest} (same
 * harness). When {@link CodingTaskItemBridge#tryRegisterScriptedItem} meets a
 * workspace {@code .js} that is missing the {@code commands} block or has no
 * callable {@code invoke()}/{@code execute()} entrypoint, the bridge must NOT
 * throw — the artifact stays placed as a plain legacy artifact and the bridge
 * emits a WARN naming exactly what the agent got wrong.</p>
 */
class CodingTaskItemBridgeContractRejectTest {

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

    private static SourceArtifact source(Path workspace, String fileName) {
        return new SourceArtifact(
            UUID.randomUUID(),
            "goose",
            "task-" + UUID.randomUUID().toString().substring(0, 8),
            workspace.toString(),
            List.of(fileName),
            null,
            Instant.now(),
            Map.of());
    }

    private static RoomObject placement(String id) {
        return new RoomObject(id, "Agent-Authored Tool",
            "A tool freshly authored by the coding backend.", true);
    }

    @Test
    void workspace_js_missing_invoke_falls_back_to_plain_artifact_without_throwing(
            @TempDir Path workspace) throws Exception {
        var fileName = "dead_agent_tool.js";
        Files.writeString(workspace.resolve(fileName), """
            // Agent declared commands but forgot the invoke() entrypoint —
            // the tool would be dead on first `use`.
            exports.manifest = {
              name: "dead_agent_tool",
              version: "1.0.0",
              description: "Declares commands it cannot run.",
              author: "did:wyrd:goose",
              embodiment: { silent: true, reason: "no body trace" },
              commands: [ { label: "Do the thing", args: "" } ]
            };
            function helperOnly(x) { return x; }
            """);

        var roomObj = placement("codex-dead-" + UUID.randomUUID().toString().substring(0, 6));
        assertThatCode(() ->
                CodingTaskItemBridge.tryRegisterScriptedItem(roomObj, source(workspace, fileName)))
            .as("a contract-violating artifact must not crash the bridge")
            .doesNotThrowAnyException();

        // Bridge WARNed, naming the missing entrypoint + the fallback.
        var warns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("contract REJECT"))
            .toList();
        assertThat(warns)
            .as("bridge must WARN what the agent got wrong — captured: " + appender.list)
            .isNotEmpty();
        assertThat(warns.get(0).getFormattedMessage())
            .contains(roomObj.id())
            .contains(fileName)
            .contains("invoke()/execute()")
            .contains("plain artifact placement");

        // Not registered as a scripted item — legacy router path stays in charge.
        assertThat(ScriptedItemLoader.get().all())
            .extracting(d -> d.itemId())
            .doesNotContain("dead_agent_tool");
    }

    @Test
    void workspace_js_missing_commands_falls_back_to_plain_artifact_without_throwing(
            @TempDir Path workspace) throws Exception {
        var fileName = "undocumented_tool.js";
        Files.writeString(workspace.resolve(fileName), """
            // Agent wrote a working invoke() but forgot to declare commands —
            // nobody could discover how to use it.
            exports.manifest = {
              name: "undocumented_tool",
              version: "1.0.0",
              description: "Works but does not say how it is used.",
              author: "did:wyrd:goose",
              embodiment: { silent: true, reason: "no body trace" }
            };
            function invoke(params) { return { ok: true }; }
            """);

        var roomObj = placement("codex-undoc-" + UUID.randomUUID().toString().substring(0, 6));
        assertThatCode(() ->
                CodingTaskItemBridge.tryRegisterScriptedItem(roomObj, source(workspace, fileName)))
            .doesNotThrowAnyException();

        var warns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("contract REJECT"))
            .toList();
        assertThat(warns).isNotEmpty();
        assertThat(warns.get(0).getFormattedMessage())
            .contains(roomObj.id())
            .contains(fileName)
            .contains("commands");

        assertThat(ScriptedItemLoader.get().all())
            .extracting(d -> d.itemId())
            .doesNotContain("undocumented_tool");
    }

    @Test
    void contract_compliant_artifact_still_registers(@TempDir Path workspace) throws Exception {
        var fileName = "proper_tool.js";
        Files.writeString(workspace.resolve(fileName), """
            exports.manifest = {
              name: "proper_tool",
              version: "1.0.0",
              description: "Self-documenting and runnable.",
              author: "did:wyrd:goose",
              embodiment: { silent: true, reason: "no body trace" },
              commands: [
                { label: "Run the tool", args: "" },
                { label: "Show details", args: "details" }
              ]
            };
            function invoke(params) { return { ok: true, summary: "ran" }; }
            """);

        var roomObj = placement("codex-proper-" + UUID.randomUUID().toString().substring(0, 6));
        CodingTaskItemBridge.tryRegisterScriptedItem(roomObj, source(workspace, fileName));

        assertThat(appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("contract REJECT"))
                .filter(e -> e.getFormattedMessage().contains(fileName)))
            .as("compliant artifact must not trip the contract gate")
            .isEmpty();
        assertThat(ScriptedItemLoader.get().all())
            .extracting(d -> d.itemId())
            .contains("proper_tool");
    }
}
