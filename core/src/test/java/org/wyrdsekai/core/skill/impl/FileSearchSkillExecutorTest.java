package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileSearchSkillExecutorTest {

    @TempDir
    Path tempDir;

    private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

    @Nested
    class Registration {
        @Test
        void registers_three_skills() {
            var executor = new FileSearchSkillExecutor(List.of(Path.of("/tmp")));
            assertEquals(3, executor.availableSkills().size());
            assertTrue(executor.supports("vault.files.search"));
            assertTrue(executor.supports("vault.files.recent"));
            assertTrue(executor.supports("vault.files.index"));
        }

        @Test
        void tier_is_native() {
            var executor = new FileSearchSkillExecutor(List.of());
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class MissingQuery {
        @Test
        void search_requires_query_param() {
            var executor = new FileSearchSkillExecutor(List.of(tempDir));
            SkillResult result = executor.execute("vault.files.search", Map.of(), ctx);
            assertFalse(result.success());
        }
    }

    @Nested
    class SearchWithGlob {
        @Test
        void finds_matching_files() throws IOException {
            Files.writeString(tempDir.resolve("notes.txt"), "hello");
            Files.writeString(tempDir.resolve("readme.md"), "world");
            Files.writeString(tempDir.resolve("data.txt"), "data");

            var executor = new FileSearchSkillExecutor(List.of(tempDir));
            SkillResult result = executor.execute("vault.files.search",
                Map.of("query", "notes", "glob", "*.txt"), ctx);
            assertTrue(result.success());
            @SuppressWarnings("unchecked")
            var results = (List<String>) result.data().get("results");
            assertEquals(1, results.size());
            assertTrue(results.get(0).contains("notes.txt"));
        }
    }
}
