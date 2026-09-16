package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fail-fast tests for the embodiment-block contract.
 * Silence in the world must be a declared choice, not a default.
 */
class ItemEmbodimentValidationTest {

    @Test
    void migrationShimReturnedWhenAllowedAndMissing() {
        var shim = ItemManifestValidator.requireEmbodiment(null, true, "legacy_item");
        assertTrue(shim.isMigrated(), "boot pass should shim missing embodiment");
        assertTrue(shim.silent(), "shim defaults to silent");
        assertEquals(ItemEmbodimentSpec.MIGRATION_REASON, shim.reason());
        assertEquals(ItemEmbodimentSpec.MIGRATION_VERSION, shim.migration().version());
    }

    @Test
    void rejectsMissingEmbodimentWhenMigrationDisallowed() {
        var ex = assertThrows(
            ItemManifestValidator.ManifestEmbodimentMissingException.class,
            () -> ItemManifestValidator.requireEmbodiment(null, false, "broken_item"));
        assertTrue(ex.getMessage().contains("broken_item"));
        // The message names the missing block. It must not be pinned to a spec citation:
        // the public export strips private-doc citations from literals, so a test that
        // asserted "§18" passed privately and failed in the shipped tree.
        assertTrue(ex.getMessage().contains("embodiment"));
    }

    @Test
    void rejectsSilentWithoutReason() {
        assertThrows(IllegalArgumentException.class,
            () -> ItemEmbodimentSpec.silent(""));
    }

    @Test
    void rejectsEmittingWithoutEmitsList() {
        assertThrows(IllegalArgumentException.class,
            () -> ItemEmbodimentSpec.emits(List.of(), "tpl"));
    }

    @Test
    void acceptsValidSilentDeclaration() {
        var spec = ItemEmbodimentSpec.silent("computational furnishing; no body events");
        assertSame(spec,
            ItemManifestValidator.requireEmbodiment(spec, false, "compute_widget"));
    }

    @Test
    void acceptsValidEmittingDeclaration() {
        var spec = ItemEmbodimentSpec.emits(
            List.of("posture_change", "body_language"),
            "{actor} settles into the worn leather chair.");
        var checked = ItemManifestValidator.requireEmbodiment(spec, false, "leather_chair");
        assertEquals(2, checked.emits().size());
        assertNotNull(checked.descriptorTemplate());
    }

    @Test
    void parseEmbodimentExtractsBlock() {
        var script = """
            exports.manifest = {
              name: "test_item",
              version: "1.0.0",
              description: "test",
              author: "did:wyrd:test",
              capabilities: [],
              embodiment: {
                emits: ["posture_change"],
                descriptor_template: "{actor} sits"
              }
            };
            """;
        var spec = ItemManifestParser.parseEmbodiment(script);
        assertNotNull(spec);
        assertFalse(spec.silent());
        assertEquals(1, spec.emits().size());
        assertEquals("posture_change", spec.emits().get(0));
    }

    @Test
    void parseEmbodimentReturnsNullWhenAbsent() {
        var script = """
            exports.manifest = {
              name: "test_item",
              version: "1.0.0",
              description: "test",
              author: "did:wyrd:test",
              capabilities: []
            };
            """;
        assertNull(ItemManifestParser.parseEmbodiment(script));
    }
}
