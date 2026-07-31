package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave 7-Furnishings — verify the three substrate
 * Study furnishings (bondholder_pinboard, repair_mirror, substrate_scroll)
 * load with valid manifests from the bundled {@code scripts/items/}
 * directory.
 *
 * <p>This is a source-text wiring test, not a runtime behaviour test:
 * it asserts the JS files exist, their manifests parse, and the
 * validator accepts the {@code substrate.read} capability. Runtime
 * behaviour is exercised by the {@code ItemWorldApiProviderImpl}
 * methods + GraalJS executor in higher-level tests.
 */
class SubstrateFurnishingsLoaderTest {

    private ScriptedItemLoader loader;
    private List<Path> originalDirs;

    @BeforeEach
    void setup() {
        loader = ScriptedItemLoader.get();
        // Resolve scripts/items relative to the repo root. Gradle :core:test
        // runs with cwd=core/, so we walk up one level. If somehow run from
        // the repo root, fall back to ./scripts/items.
        var fromCore = Paths.get("..", "scripts", "items");
        var fromRoot = Paths.get("scripts", "items");
        var dir = Files.isDirectory(fromCore) ? fromCore : fromRoot;
        loader.setSearchDirs(List.of(dir));
        loader.reloadAll();
    }

    @AfterEach
    void teardown() {
        loader.setSearchDirs(List.of());
        loader.reloadAll();
    }

    @Test
    void bondholder_pinboard_loads_with_substrate_read_cap() {
        var def = loader.get("bondholder_pinboard");
        assertTrue(def.isPresent(),
            "bondholder_pinboard furnishing should be loaded from scripts/items/");
        var manifest = def.get().manifest();
        assertEquals("1.0.0", manifest.version());
        assertTrue(manifest.capabilities().contains("substrate.read"),
            "bondholder_pinboard should declare substrate.read");
    }

    @Test
    void repair_mirror_loads_with_substrate_read_cap() {
        var def = loader.get("repair_mirror");
        assertTrue(def.isPresent(),
            "repair_mirror furnishing should be loaded from scripts/items/");
        var manifest = def.get().manifest();
        assertEquals("1.0.0", manifest.version());
        assertTrue(manifest.capabilities().contains("substrate.read"),
            "repair_mirror should declare substrate.read");
    }

    @Test
    void substrate_scroll_loads_with_substrate_read_cap() {
        var def = loader.get("substrate_scroll");
        assertTrue(def.isPresent(),
            "substrate_scroll furnishing should be loaded from scripts/items/");
        var manifest = def.get().manifest();
        assertEquals("1.0.0", manifest.version());
        assertTrue(manifest.capabilities().contains("substrate.read"),
            "substrate_scroll should declare substrate.read");
    }

    @Test
    void all_three_substrate_furnishings_load_together() {
        var ids = loader.all().stream()
            .map(ScriptedItemDef::itemId)
            .collect(Collectors.toSet());
        assertTrue(ids.contains("bondholder_pinboard"),
            "bondholder_pinboard missing from loaded set: " + ids);
        assertTrue(ids.contains("repair_mirror"),
            "repair_mirror missing from loaded set: " + ids);
        assertTrue(ids.contains("substrate_scroll"),
            "substrate_scroll missing from loaded set: " + ids);
    }

    @Test
    void hearth_furnishing_kit_includes_substrate_items() {
        // Wave 7-Furnishings — the three substrate furnishings auto-place
        // into every companion's Hearth on onboarding. HearthFurnishingKit
        // resolves them from ScriptedItemLoader; missing scripts degrade
        // silently (kit still ships Drives Mirror).
        var defaults = HearthFurnishingKit.defaults();
        var ids = defaults.stream()
            .map(ToolItem::id)
            .collect(Collectors.toSet());
        assertTrue(ids.contains("bondholder_pinboard"),
            "Hearth defaults should include bondholder_pinboard: " + ids);
        assertTrue(ids.contains("repair_mirror"),
            "Hearth defaults should include repair_mirror: " + ids);
        assertTrue(ids.contains("substrate_scroll"),
            "Hearth defaults should include substrate_scroll: " + ids);
        // Drives Mirror still in the kit — substrate additions are
        // additive, not a replacement.
        assertTrue(ids.contains("drives-mirror"),
            "Drives Mirror should still be in Hearth defaults: " + ids);
    }
}
