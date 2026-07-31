package org.wyrdsekai.core.item;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fail-fast embodiment-block gate.
 *
 * <p>Boot scan ({@code allowMigration=true}) shims items missing the
 * {@code embodiment:} block and records each one in
 * {@link ScriptedItemLoader#migrationAudit()}; {@link ScriptedItemLoader#writeMigrationAudit}
 * persists the audit to {@code data/manifest_audit.json}.</p>
 *
 * <p>Hot-reload via {@link ScriptedItemLoader#register} REJECTs missing-embodiment
 * items — the item is silently dropped from {@code loaded}, an error is logged,
 * and {@code register} returns {@link java.util.Optional#empty()}.</p>
 */
class ScriptedItemLoaderEmbodimentIT {

    private ListAppender<ILoggingEvent> appender;
    private Logger loaderLog;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        loaderLog = (Logger) LoggerFactory.getLogger(ScriptedItemLoader.class);
        loaderLog.addAppender(appender);
        // Reset shared state between tests so we don't cross-pollinate.
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    @AfterEach
    void detach() {
        loaderLog.detachAppender(appender);
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    private static final String GOOD_SILENT = """
        exports.manifest = {
          name: "good_silent",
          version: "1.0.0",
          description: "valid silent item",
          author: "did:wyrd:test",
          capabilities: [],
          embodiment: {
            silent: true,
            reason: "pure compute, no in-world body"
          },
          commands: [ { label: "Run the silent tool", args: "" } ]
        };
        function invoke(params) { return { ok: true }; }
        """;

    private static final String GOOD_EMITS = """
        exports.manifest = {
          name: "good_emits",
          version: "1.0.0",
          description: "valid emits item",
          author: "did:wyrd:test",
          capabilities: [],
          embodiment: {
            silent: false,
            emits: ["body_language"],
            descriptor_template: "{actor} taps a key."
          },
          commands: [ { label: "Tap the key", args: "" } ]
        };
        function invoke(params) { return { ok: true }; }
        """;

    private static final String MISSING_EMBODIMENT = """
        exports.manifest = {
          name: "broken_no_embodiment",
          version: "1.0.0",
          description: "missing embodiment block",
          author: "did:wyrd:test",
          capabilities: []
        };
        """;

    @Test
    void hotReloadRejectsItemMissingEmbodiment(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("broken.js");
        Files.writeString(file, MISSING_EMBODIMENT);

        var result = ScriptedItemLoader.get().register(file);
        assertTrue(result.isEmpty(),
            "register() returns empty when embodiment missing on hot-reload");
        assertTrue(ScriptedItemLoader.get().get("broken_no_embodiment").isEmpty(),
            "item is not in the loaded map after REJECT");

        var rejects = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("§18 REJECT"))
            .toList();
        assertEquals(1, rejects.size(),
            "exactly one §18 REJECT error logged: " + appender.list);
        assertTrue(rejects.get(0).getFormattedMessage().contains("broken.js"),
            "REJECT mentions the offending file path");
    }

    @Test
    void hotReloadAcceptsItemWithValidSilentEmbodiment(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("good.js");
        Files.writeString(file, GOOD_SILENT);

        var result = ScriptedItemLoader.get().register(file);
        assertTrue(result.isPresent(), "valid silent item registers");
        assertEquals("good_silent", result.get().itemId());
        assertTrue(ScriptedItemLoader.get().migrationAudit().isEmpty(),
            "no migration audit entry — item declared embodiment explicitly");
    }

    @Test
    void hotReloadAcceptsItemWithValidEmitsEmbodiment(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("good.js");
        Files.writeString(file, GOOD_EMITS);

        var result = ScriptedItemLoader.get().register(file);
        assertTrue(result.isPresent(), "valid emits item registers");
        assertEquals("good_emits", result.get().itemId());
    }

    @Test
    void bootScanShimsMissingEmbodimentAndAccumulatesAudit(@TempDir Path tempDir) throws Exception {
        // Two missing-embodiment items + one good one in the scan dir.
        Files.writeString(tempDir.resolve("broken_a.js"),
            MISSING_EMBODIMENT.replace("broken_no_embodiment", "broken_a"));
        Files.writeString(tempDir.resolve("broken_b.js"),
            MISSING_EMBODIMENT.replace("broken_no_embodiment", "broken_b"));
        Files.writeString(tempDir.resolve("good.js"), GOOD_SILENT);

        ScriptedItemLoader.get().setSearchDirs(List.of(tempDir));
        var loaded = ScriptedItemLoader.get().reloadAll();
        assertEquals(3, loaded.size(),
            "boot scan loads ALL three items — two via shim, one as-declared");

        var audit = ScriptedItemLoader.get().migrationAudit();
        assertEquals(2, audit.size(),
            "exactly two shim entries (broken_a + broken_b)");
        var ids = audit.stream()
            .map(ScriptedItemLoader.MigrationAuditEntry::itemId)
            .toList();
        assertTrue(ids.contains("broken_a"));
        assertTrue(ids.contains("broken_b"));
        assertFalse(ids.contains("good_silent"),
            "good_silent must not appear in the audit — it declared embodiment");

        // Boot-scan WARNs surface the shim event.
        var shimWarns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("v1-default embodiment shim"))
            .toList();
        assertEquals(2, shimWarns.size(),
            "one shim WARN per shimmed item");
    }

    @Test
    void writeMigrationAuditProducesExpectedJson(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("broken.js"),
            MISSING_EMBODIMENT.replace("broken_no_embodiment", "broken_one"));
        ScriptedItemLoader.get().setSearchDirs(List.of(tempDir));
        ScriptedItemLoader.get().reloadAll();

        var auditPath = tempDir.resolve("audit").resolve("manifest_audit.json");
        ScriptedItemLoader.get().writeMigrationAudit(auditPath);

        assertTrue(Files.exists(auditPath), "audit file written");
        var body = Files.readString(auditPath);
        Map<String, Object> parsed = new ObjectMapper()
            .readValue(body, new TypeReference<>() {});
        assertEquals("embodiment/18.3", parsed.get("spec"));
        assertNotNull(parsed.get("writtenAt"), "writtenAt timestamp present");
        assertNotNull(parsed.get("migrationShimVersion"),
            "shim version recorded so future audits can detect drift");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) parsed.get("items");
        assertEquals(1, items.size());
        assertEquals("broken_one", items.get(0).get("itemId"));
        assertNotNull(items.get(0).get("path"), "audit row records originating file path");
        assertNotNull(items.get(0).get("at"), "audit row records shim instant");
    }

    @Test
    void invalidEmbodimentBlockRejectedOnHotReload(@TempDir Path tempDir) throws Exception {
        // silent=true but no reason — structurally invalid per ItemEmbodimentSpec.isValid()
        var bad = """
            exports.manifest = {
              name: "bad_silent",
              version: "1.0.0",
              description: "silent without reason",
              author: "did:wyrd:test",
              capabilities: [],
              embodiment: { silent: true }
            };
            """;
        var file = tempDir.resolve("bad.js");
        Files.writeString(file, bad);

        var result = ScriptedItemLoader.get().register(file);
        assertTrue(result.isEmpty(), "register() rejects structurally invalid embodiment");
        var rejects = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("§18 REJECT"))
            .toList();
        assertEquals(1, rejects.size(), "structural invalidity triggers REJECT");
    }
}
