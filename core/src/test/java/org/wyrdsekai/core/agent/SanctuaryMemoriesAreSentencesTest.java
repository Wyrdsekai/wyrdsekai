package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What we hand her memory, she lives in.
 *
 * <p>Both sanctuary exits (the bounds reaper and the dispatch walk-back) used to remember a
 * tagged record, {@code "[repair_handoff] attendant → nexus (…)"}. Nothing read the tag. She
 * did: live 2026-09-03 she took "repair handoff" for a thing that existed, named a mailbox, a
 * key and a room after it, and wrote an importance-0.8 memory about "the handoff tool that
 * held us". The memory line is now a sentence in her own voice with no tag and no id in it.
 */
class SanctuaryMemoriesAreSentencesTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    void no_sanctuary_exit_remembers_a_tag() throws IOException {
        var src = Files.readString(ACTOR);
        assertFalse(src.contains("remember(\"[repair_handoff]"),
            "sanctuary exits must remember a plain sentence, never a bracketed tag");
        assertTrue(src.contains("remember(\"I stepped out of the sanctuary\""),
            "the sanctuary-exit memory should be in her voice");
        var walkBack = src.indexOf("private void leaveSanctuaryTo");
        assertTrue(walkBack > 0);
        assertFalse(src.substring(walkBack, walkBack + 1500).contains("+ why + \")\""),
            "the walk-back reason belongs in the sentence, not in a parenthesis after a tag");
    }
}
