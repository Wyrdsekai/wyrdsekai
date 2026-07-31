package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Items-as-tools contract — proof that the bundled {@code scripts/items}
 * shelf is fully contract-compliant: ZERO boot-time shims. Every bundled
 * item declares its own non-empty {@code commands} block and carries a
 * callable {@code invoke()}/{@code execute()} entrypoint, so
 * {@link ScriptedItemLoader#commandsAudit()} stays empty after a boot scan.
 *
 * <p>Companion to {@link ScriptedItemLoaderCommandsIT} (which proves the
 * gates themselves); this class proves the corpus.</p>
 */
class BundledItemsContractIT {

    /** The 22 legacy items brought up to the contract. ("compass" — the loose
     *  drives-reading script — was retired 2026-07-18 in favour of the provisioned
     *  "companion_glass" Study furnishing, which surfaces the same world.drives
     *  snapshot with a richer read; the kit's notification "Compass" is separate.) */
    private static final Set<String> LEGACY_22 = Set.of(
        "bond_chapel", "bondholder_pinboard", "calculator", "chronicle",
        "companion_glass", "expense_summary", "forge_workbench", "journal_archiver",
        "leather_chair", "morning_briefing", "morning_lights", "nostr_quill",
        "notify_team", "observation_chart", "pr_notifier", "quote_card",
        "recipes_console", "repair_mirror", "research_assistant",
        "research_clipper", "trip_planner", "web_clipper");

    @AfterEach
    void reset() {
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    @Test
    void bundledShelfLoadsWithZeroShims() throws Exception {
        var bundled = locateBundledItemsDir();
        assertNotNull(bundled, "scripts/items dir must be locatable from the test cwd");

        ScriptedItemLoader.get().setSearchDirs(List.of(bundled));
        var loaded = ScriptedItemLoader.get().reloadAll();

        var loadedIds = new HashSet<String>();
        for (var def : loaded) loadedIds.add(def.itemId());

        // The 22 legacy items all load — including the two whose manifests
        // were previously unparseable and never loaded at all.
        for (var id : LEGACY_22) {
            assertTrue(loadedIds.contains(id),
                "legacy item '" + id + "' must load; loaded=" + loadedIds);
        }
        assertTrue(loadedIds.contains("morning_lights"),
            "morning_lights (manifest repaired) now loads");
        assertTrue(loadedIds.contains("research_assistant"),
            "research_assistant (manifest repaired) now loads");

        // 22 legacy + 14 new = full shelf.
        assertTrue(loaded.size() >= 36,
            "expected >= 36 bundled items, got " + loaded.size() + ": " + loadedIds);

        // Every def declares its own commands and a callable entrypoint.
        for (var def : loaded) {
            assertFalse(def.manifest().commands().isEmpty(),
                "item '" + def.itemId() + "' must declare non-empty manifest.commands");
            for (var cmd : def.manifest().commands()) {
                assertNotNull(cmd.label(), def.itemId() + " command label null");
                assertFalse(cmd.label().isBlank(),
                    "item '" + def.itemId() + "' has a blank command label");
            }
            assertTrue(ScriptedItemLoader.hasEntrypoint(def.scriptSource()),
                "item '" + def.itemId() + "' must have an invoke()/execute() entrypoint");
        }

        // ZERO commands shims — the whole point of the migration.
        assertTrue(ScriptedItemLoader.get().commandsAudit().isEmpty(),
            "no bundled item may need the derived-commands shim: "
                + ScriptedItemLoader.get().commandsAudit());
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
