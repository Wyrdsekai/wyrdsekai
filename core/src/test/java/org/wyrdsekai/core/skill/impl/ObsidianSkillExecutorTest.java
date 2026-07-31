package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillTier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObsidianSkillExecutorTest {

    @TempDir
    Path tempDir;

    @Nested
    class Registration {

        @Test
        void registers_four_skills(@TempDir Path dir) {
            var executor = new ObsidianSkillExecutor(dir.toString());
            var skills = executor.availableSkills();
            assertEquals(4, skills.size());
            var ids = skills.stream().map(s -> s.id()).toList();
            assertTrue(ids.contains("scriptorium.obsidian.read"));
            assertTrue(ids.contains("scriptorium.obsidian.write"));
            assertTrue(ids.contains("scriptorium.obsidian.search"));
            assertTrue(ids.contains("scriptorium.obsidian.list"));
        }

        @Test
        void supports_known_skill(@TempDir Path dir) {
            var executor = new ObsidianSkillExecutor(dir.toString());
            assertTrue(executor.supports("scriptorium.obsidian.read"));
        }

        @Test
        void tier_is_native(@TempDir Path dir) {
            var executor = new ObsidianSkillExecutor(dir.toString());
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class PathTraversal {

        @Test
        void traversal_blocked() {
            var executor = new ObsidianSkillExecutor(tempDir.toString());
            var ctx = SkillContext.forHuman("human1", "test-room", Map.of(), true);
            var result = executor.execute("scriptorium.obsidian.read",
                Map.of("path", "../../../etc/passwd"), ctx);
            assertFalse(result.success());
        }

        @Test
        void write_traversal_blocked() {
            var executor = new ObsidianSkillExecutor(tempDir.toString());
            var ctx = SkillContext.forHuman("human1", "test-room", Map.of(), true);
            var result = executor.execute("scriptorium.obsidian.write",
                Map.of("path", "../../../tmp/evil.md", "content", "pwned"), ctx);
            assertFalse(result.success());
        }
    }

    @Nested
    class AccessControl {

        @Test
        void write_denied_for_agent_session() {
            var executor = new ObsidianSkillExecutor(tempDir.toString());
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            var result = executor.execute("scriptorium.obsidian.write",
                Map.of("path", "note.md", "content", "test"), ctx);
            assertFalse(result.success());
        }
    }

    @Nested
    class ReadBehavior {

        @Test
        void not_found_for_nonexistent_note() {
            var executor = new ObsidianSkillExecutor(tempDir.toString());
            var ctx = SkillContext.forHuman("human1", "test-room", Map.of(), true);
            var result = executor.execute("scriptorium.obsidian.read",
                Map.of("path", "nonexistent.md"), ctx);
            assertFalse(result.success());
        }

        @Test
        void reads_existing_note() throws IOException {
            Files.writeString(tempDir.resolve("hello.md"), "# Hello World");
            var executor = new ObsidianSkillExecutor(tempDir.toString());
            var ctx = SkillContext.forHuman("human1", "test-room", Map.of(), true);
            var result = executor.execute("scriptorium.obsidian.read",
                Map.of("path", "hello.md"), ctx);
            assertTrue(result.success());
            assertTrue(result.output().contains("Hello World"));
        }
    }
}
