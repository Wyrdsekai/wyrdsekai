package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool's own sub-verb must never overwrite the tool's NAME.
 *
 * <p>{@code action} is reserved: the dispatcher reads it as which tool to run. So when a model
 * nests its own {@code action} inside {@code use_item}'s params — and it will, because "action" is
 * the natural word — the naive unwrap copies it straight over the tool name.
 *
 * <p>home-server, 2026-07-14. Wyrd sent:
 * <pre>
 *   {"action":"use_item","name":"journal","params":{"action":"write","text":"The steward asked me
 *    what I make of today — and the honest thing was that there is no particular story…"}}
 * </pre>
 * The nested {@code "write"} clobbered {@code "journal"}, so the dispatcher went looking for a tool
 * called {@code write}, found none, and her entry vanished — no execution, no error, no narration.
 * She wrote something true and the system quietly threw it away. It took three live runs to find,
 * because nothing anywhere said a word about it.
 *
 * <p>Fixes: the unwrap carries a nested {@code action} across as {@code mode} (preserving the
 * sub-verb rather than discarding it) and sets the real tool name <b>last</b>, where nothing can
 * overwrite it.
 */
class UseItemDoesNotHijackTheToolNameTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    void aNestedActionCannotOverwriteTheToolName() throws IOException {
        var src = Files.readString(ACTOR);
        var start = src.indexOf("private JsonNode unwrapUseItem");
        assertTrue(start > 0, "unwrapUseItem not found");
        var unwrap = src.substring(start, Math.min(src.length(), start + 4000));

        assertTrue(unwrap.contains("if (!rewritten.has(\"mode\")) rewritten.set(\"mode\""),
            "a nested `action` must be carried across as `mode`, not copied onto the node where it "
                + "becomes the tool name — that hijack silently ate a journal entry on home-server");

        // The tool name must be written AFTER the params are merged, so nothing can clobber it.
        var putAction = unwrap.indexOf("rewritten.put(\"action\", target)");
        var mergeLoop = unwrap.indexOf("for (var key : List.of(\"params\"");
        assertTrue(putAction > mergeLoop && mergeLoop > 0,
            "the tool name must be set AFTER the nested params are merged — otherwise a nested "
                + "key can overwrite it and the call is dispatched to the wrong (or no) tool");
    }
}
