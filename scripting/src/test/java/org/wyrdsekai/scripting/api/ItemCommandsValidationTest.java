package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Items-as-tools contract — fail-fast tests for the manifest {@code commands}
 * block. An agent-built tool must self-document: at least one
 * {@code {label, args}} entry, every label non-blank. Boot loads shim missing
 * lists with a derived default; register/hot-reload rejects.
 */
class ItemCommandsValidationTest {

    private static ItemManifest manifest(List<ItemManifest.Command> commands) {
        return new ItemManifest(
            "test_tool", "1.0.0", "A test tool.", "did:wyrd:test",
            List.of(), null, null, null, null, null, null,
            null, null, null, null, null, commands);
    }

    // ─── requireCommands — boot (allowMigration=true) ────────────────────

    @Test
    void migrationShimDerivesDefaultUseCommandWhenMissing() {
        var shimmed = ItemManifestValidator.requireCommands(
            manifest(List.of()), true, "Test Tool");
        assertEquals(1, shimmed.size(), "boot pass shims exactly one default command");
        assertEquals("Use Test Tool", shimmed.getFirst().label());
        assertEquals("", shimmed.getFirst().args());
    }

    @Test
    void migrationShimFallsBackToManifestNameWhenDisplayNameBlank() {
        var shimmed = ItemManifestValidator.requireCommands(
            manifest(List.of()), true, null);
        assertEquals("Use test_tool", shimmed.getFirst().label());
    }

    @Test
    void nullManifestShimsOnBootPath() {
        var shimmed = ItemManifestValidator.requireCommands(null, true, "Mystery");
        assertEquals(1, shimmed.size());
        assertEquals("Use Mystery", shimmed.getFirst().label());
    }

    // ─── requireCommands — register/hot-reload (allowMigration=false) ────

    @Test
    void rejectsMissingCommandsWhenMigrationDisallowed() {
        var ex = assertThrows(
            ItemManifestValidator.ManifestCommandsMissingException.class,
            () -> ItemManifestValidator.requireCommands(
                manifest(List.of()), false, "Test Tool"));
        assertTrue(ex.getMessage().contains("test_tool"));
        assertTrue(ex.getMessage().contains("commands"));
    }

    @Test
    void rejectsBlankLabelEvenOnBootPath() {
        // Garbage is never shimmed — declared-but-invalid throws regardless
        // of allowMigration, mirroring the invalid-embodiment rule.
        var bad = manifest(List.of(new ItemManifest.Command("  ", "go")));
        assertThrows(ItemManifestValidator.ManifestCommandsMissingException.class,
            () -> ItemManifestValidator.requireCommands(bad, true, "Test Tool"));
        assertThrows(ItemManifestValidator.ManifestCommandsMissingException.class,
            () -> ItemManifestValidator.requireCommands(bad, false, "Test Tool"));
    }

    @Test
    void acceptsDeclaredCommandsAsGiven() {
        var declared = List.of(
            new ItemManifest.Command("Read summary", ""),
            new ItemManifest.Command("Read details", "details"));
        var checked = ItemManifestValidator.requireCommands(
            manifest(declared), false, "Pinboard");
        assertEquals(declared, checked, "valid declarations pass through untouched");
    }

    @Test
    void emptyArgsIsValidForNoArgDefaultInvoke() {
        var declared = List.of(new ItemManifest.Command("Run it", ""));
        var checked = ItemManifestValidator.requireCommands(
            manifest(declared), false, "Runner");
        assertEquals("", checked.getFirst().args());
    }

    // ─── validate() — structural checks on declared entries ──────────────

    @Test
    void validateErrorsOnBlankCommandLabel() {
        var result = ItemManifestValidator.validate(
            manifest(List.of(new ItemManifest.Command("", "status"))));
        assertFalse(result.valid(), "blank command label must be a validation error");
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.contains("commands[0]") && e.contains("label")));
    }

    @Test
    void validateWarnsButDoesNotErrorOnMissingCommands() {
        // Back-compat: missing commands is a boot-time shim, not a validate()
        // error — otherwise the ~23 bundled scripts/items/*.js would be
        // dropped at boot. The strict gate lives in requireCommands.
        var result = ItemManifestValidator.validate(manifest(List.of()));
        assertTrue(result.valid(),
            "missing commands must not fail validate(): " + result.errors());
        assertTrue(result.warnings().stream()
            .anyMatch(w -> w.contains("commands")));
    }

    @Test
    void validateAcceptsWellFormedCommands() {
        var result = ItemManifestValidator.validate(
            manifest(List.of(new ItemManifest.Command("Check status", "status"))));
        assertTrue(result.valid(), () -> "errors: " + result.errors());
    }
}
