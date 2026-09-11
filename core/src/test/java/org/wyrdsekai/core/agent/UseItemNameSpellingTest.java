package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spelling is not permission.
 *
 * <p>Live 2026-09-03: she crafted {@code repair_handoff_258449800_1787366666829}, then asked to
 * inspect {@code repair_handoff_258449800-1787366666829} — one hyphen where an underscore was —
 * and the hatch answered "not in your permitted scope". She owned the thing and was told she
 * may not touch it. The permitted-scope check now compares under a spelling-insensitive key
 * and rewrites the target to the tool's real name.
 */
class UseItemNameSpellingTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    void hyphens_spaces_dots_and_case_fold_onto_the_manifest_spelling() {
        var canonical = CompanionActor.toolNameKey("repair_handoff_258449800_1787366666829");
        assertEquals(canonical, CompanionActor.toolNameKey("repair_handoff_258449800-1787366666829"));
        assertEquals(canonical, CompanionActor.toolNameKey("Repair-Handoff 258449800.1787366666829 "));
        assertEquals("library_summarizer", CompanionActor.toolNameKey("Library Summarizer"));
        assertFalse(CompanionActor.toolNameKey("library_summarizer")
            .equals(CompanionActor.toolNameKey("library_summariser")),
            "a genuinely different name must still miss");
    }

    @Test
    void the_hatch_compares_under_the_key_and_rewrites_the_target() throws IOException {
        var src = Files.readString(ACTOR);
        var start = src.indexOf("private JsonNode unwrapUseItem");
        assertTrue(start > 0, "unwrapUseItem not found");
        var unwrap = src.substring(start, Math.min(src.length(), start + 12000));
        // The key comparison lives in hatchNameFor (2026-09-10: it also drops a placement suffix);
        // the hatch must go through it and take the tool's real name back.
        assertTrue(unwrap.contains("hatchNameFor(target, scopedNames)"),
            "permitted-scope must match on the spelling-insensitive key (hatchNameFor)");
        assertTrue(unwrap.contains("target = resolved;"),
            "a key match must rewrite the target to the tool's real name, or dispatch misses");
        assertEquals("key_chest", CompanionActor.hatchNameFor("Key Chest", java.util.List.of("key_chest")));
    }
}
