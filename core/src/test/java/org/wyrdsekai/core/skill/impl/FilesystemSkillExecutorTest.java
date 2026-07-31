package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FilesystemSkillExecutorTest {

    @TempDir
    Path tempDir;

    @Nested
    class Registration {
        @Test
        void registers_three_skills() {
            var executor = new FilesystemSkillExecutor(Map.of("docs", Path.of("/tmp")));
            assertEquals(3, executor.availableSkills().size());
            assertTrue(executor.supports("vault.fs.read"));
            assertTrue(executor.supports("vault.fs.write"));
            assertTrue(executor.supports("vault.fs.list"));
        }

        @Test
        void tier_is_native() {
            var executor = new FilesystemSkillExecutor(Map.of());
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class PathTraversal {
        @Test
        void traversal_blocked() {
            var executor = new FilesystemSkillExecutor(Map.of("docs", tempDir));
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            SkillResult result = executor.execute("vault.fs.read",
                Map.of("mount", "docs", "path", "../../etc/passwd"), ctx);
            assertFalse(result.success());
            assertTrue(result.output().toLowerCase().contains("traversal")
                || result.output().toLowerCase().contains("blocked"));
        }
    }

    @Nested
    class UnknownMount {
        @Test
        void not_mounted_for_unknown_mount() {
            var executor = new FilesystemSkillExecutor(Map.of("docs", tempDir));
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            SkillResult result = executor.execute("vault.fs.read",
                Map.of("mount", "nonexistent", "path", "file.txt"), ctx);
            assertFalse(result.success());
            assertTrue(result.output().toLowerCase().contains("not_mounted")
                || result.output().toLowerCase().contains("mount"));
        }
    }
}
