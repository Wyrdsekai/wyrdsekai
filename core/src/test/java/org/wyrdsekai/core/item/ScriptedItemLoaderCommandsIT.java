package org.wyrdsekai.core.item;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Items-as-tools contract — fail-fast {@code commands} + entrypoint gates.
 *
 * <p>Boot scan ({@code allowMigration=true}) shims items missing the
 * {@code commands:} block with a derived default {@code {label: "Use <name>",
 * args: ""}} entry, records each in {@link ScriptedItemLoader#commandsAudit()},
 * and keeps entrypoint-less legacy files alive with a WARN. Register /
 * hot-reload REJECTS both: an item without commands can't be discovered, and
 * an item without an {@code invoke()}/{@code execute()} entrypoint is dead on
 * first {@code use}. Companion to {@link ScriptedItemLoaderEmbodimentIT}.</p>
 */
class ScriptedItemLoaderCommandsIT {

    private ListAppender<ILoggingEvent> appender;
    private Logger loaderLog;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        loaderLog = (Logger) LoggerFactory.getLogger(ScriptedItemLoader.class);
        loaderLog.addAppender(appender);
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    @AfterEach
    void detach() {
        loaderLog.detachAppender(appender);
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    /** Fully contract-compliant: embodiment + commands + invoke. */
    private static final String FULL_CONTRACT = """
        exports.manifest = {
          name: "full_contract",
          version: "1.0.0",
          description: "declares everything",
          author: "did:wyrd:test",
          capabilities: [],
          embodiment: { silent: true, reason: "pure compute" },
          commands: [
            { label: "Run it", args: "" },
            { label: "Show details", args: "details" }
          ]
        };
        function invoke(params) { return { ok: true }; }
        """;

    /** Valid manifest + entrypoint, but no commands block. */
    private static final String NO_COMMANDS = """
        exports.manifest = {
          name: "no_commands_tool",
          version: "1.0.0",
          description: "forgot to declare commands",
          author: "did:wyrd:test",
          capabilities: [],
          embodiment: { silent: true, reason: "pure compute" }
        };
        function invoke(params) { return { ok: true }; }
        """;

    /** Valid manifest + commands, but no callable invoke()/execute() body. */
    private static final String NO_ENTRYPOINT = """
        exports.manifest = {
          name: "dead_tool",
          version: "1.0.0",
          description: "declares commands it cannot run",
          author: "did:wyrd:test",
          capabilities: [],
          embodiment: { silent: true, reason: "pure compute" },
          commands: [ { label: "Do the thing", args: "" } ]
        };
        // note: helper only — the required invoke entrypoint is absent
        function helperOnly(x) { return x; }
        """;

    // ─── register / hot-reload (strict) path ────────────────────────────────

    @Test
    void registerRejectsScriptWithManifestButNoEntrypoint(@TempDir Path tempDir)
            throws Exception {
        var file = tempDir.resolve("dead.js");
        Files.writeString(file, NO_ENTRYPOINT);

        var result = ScriptedItemLoader.get().register(file);
        assertTrue(result.isEmpty(),
            "register() returns empty when the script has no invoke()/execute()");
        assertTrue(ScriptedItemLoader.get().get("dead_tool").isEmpty(),
            "entrypoint-less item is not in the loaded map after REJECT");

        var rejects = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("entrypoint REJECT"))
            .toList();
        assertEquals(1, rejects.size(),
            "exactly one entrypoint REJECT error logged: " + appender.list);
        assertTrue(rejects.get(0).getFormattedMessage().contains("dead.js"),
            "REJECT mentions the offending file path");
    }

    @Test
    void registerRejectsScriptMissingCommands(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("no_commands.js");
        Files.writeString(file, NO_COMMANDS);

        var result = ScriptedItemLoader.get().register(file);
        assertTrue(result.isEmpty(),
            "register() returns empty when commands are missing on hot-reload");
        assertTrue(ScriptedItemLoader.get().get("no_commands_tool").isEmpty());

        var rejects = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("commands REJECT"))
            .toList();
        assertEquals(1, rejects.size(),
            "exactly one commands REJECT error logged: " + appender.list);
        assertTrue(rejects.get(0).getFormattedMessage().contains("no_commands.js"));
    }

    @Test
    void registerAcceptsFullContractItem(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("full.js");
        Files.writeString(file, FULL_CONTRACT);

        var result = ScriptedItemLoader.get().register(file);
        assertTrue(result.isPresent(), "contract-compliant item registers");
        assertEquals(2, result.get().manifest().commands().size(),
            "declared commands pass through untouched");
        assertTrue(ScriptedItemLoader.get().commandsAudit().isEmpty(),
            "no commands audit entry — item declared commands explicitly");
    }

    // ─── boot scan (migration) path ─────────────────────────────────────────

    @Test
    void bootScanShimsMissingCommandsWithDerivedDefault(@TempDir Path tempDir)
            throws Exception {
        Files.writeString(tempDir.resolve("no_commands.js"), NO_COMMANDS);
        Files.writeString(tempDir.resolve("full.js"), FULL_CONTRACT);

        ScriptedItemLoader.get().setSearchDirs(List.of(tempDir));
        var loaded = ScriptedItemLoader.get().reloadAll();
        assertEquals(2, loaded.size(),
            "boot scan loads BOTH items — one via commands shim, one as-declared");

        var shimmed = ScriptedItemLoader.get().get("no_commands_tool").orElseThrow();
        var commands = shimmed.manifest().commands();
        assertEquals(1, commands.size(), "shim derives exactly one default command");
        assertEquals("Use No Commands Tool", commands.getFirst().label());
        assertEquals("", commands.getFirst().args());

        var audit = ScriptedItemLoader.get().commandsAudit();
        assertEquals(1, audit.size(), "exactly one commands-shim audit entry");
        assertEquals("no_commands_tool", audit.getFirst().itemId());

        var shimWarns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("derived default commands shim"))
            .toList();
        assertEquals(1, shimWarns.size(), "one shim WARN per shimmed item");
    }

    @Test
    void bootScanKeepsEntrypointlessLegacyItemAliveWithWarn(@TempDir Path tempDir)
            throws Exception {
        Files.writeString(tempDir.resolve("dead.js"), NO_ENTRYPOINT);

        ScriptedItemLoader.get().setSearchDirs(List.of(tempDir));
        var loaded = ScriptedItemLoader.get().reloadAll();
        assertEquals(1, loaded.size(),
            "boot scan keeps the legacy entrypoint-less item alive");

        var warns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("no invoke()/execute()"))
            .toList();
        assertEquals(1, warns.size(),
            "boot scan WARNs about the missing entrypoint: " + appender.list);
    }

    // ─── bundled scripts/items back-compat ──────────────────────────────────

    /**
     * The ~23 bundled {@code scripts/items/*.js} must all still load at boot:
     * the commands shim + entrypoint WARN keep them alive. Baseline = every
     * file with a parseable manifest that passes {@code validate()} (the
     * pre-contract acceptance criteria — embodiment was already shim-on-boot),
     * so this asserts the NEW gates dropped nothing.
     */
    @Test
    void bundledItemsAllStillLoadAtBoot() throws Exception {
        var bundled = locateBundledItemsDir();
        assertNotNull(bundled, "scripts/items dir must be locatable from the test cwd");

        Set<String> expected = new HashSet<>();
        try (Stream<Path> files = Files.list(bundled)) {
            for (var p : files.filter(f -> f.getFileName().toString().endsWith(".js")).toList()) {
                var manifest = ItemManifestParser.parse(Files.readString(p));
                if (manifest == null) continue;
                if (!ItemManifestValidator.validate(manifest).valid()) continue;
                expected.add(manifest.name());
            }
        }
        assertTrue(expected.size() >= 15,
            "sanity: the bundled dir should hold a substantial item shelf, got "
                + expected);

        ScriptedItemLoader.get().setSearchDirs(List.of(bundled));
        var loaded = ScriptedItemLoader.get().reloadAll();
        var loadedIds = new HashSet<String>();
        for (var def : loaded) loadedIds.add(def.itemId());

        assertEquals(expected, loadedIds,
            "every bundled item with a pre-contract-valid manifest must survive "
                + "the commands/entrypoint gates at boot");
    }

    private static Path locateBundledItemsDir() throws IOException {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            var candidate = dir.resolve("scripts").resolve("items");
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }
}
