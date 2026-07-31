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

class DocumentExtractorSkillExecutorTest {

    @TempDir
    Path tempDir;

    @Nested
    class Registration {

        @Test
        void registers_one_skill(@TempDir Path dir) {
            var executor = new DocumentExtractorSkillExecutor(dir.toString());
            var skills = executor.availableSkills();
            assertEquals(1, skills.size());
            assertEquals("library.doc.extract", skills.get(0).id());
        }

        @Test
        void supports_extract_skill(@TempDir Path dir) {
            var executor = new DocumentExtractorSkillExecutor(dir.toString());
            assertTrue(executor.supports("library.doc.extract"));
        }

        @Test
        void does_not_support_unknown(@TempDir Path dir) {
            var executor = new DocumentExtractorSkillExecutor(dir.toString());
            assertFalse(executor.supports("library.doc.other"));
        }

        @Test
        void tier_is_native(@TempDir Path dir) {
            var executor = new DocumentExtractorSkillExecutor(dir.toString());
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class ParamValidation {

        private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

        @Test
        void missing_path_returns_error() {
            var executor = new DocumentExtractorSkillExecutor(tempDir.toString());
            var result = executor.execute("library.doc.extract", Map.of(), ctx);
            assertFalse(result.success());
        }

        @Test
        void blank_path_returns_error() {
            var executor = new DocumentExtractorSkillExecutor(tempDir.toString());
            var result = executor.execute("library.doc.extract", Map.of("path", "  "), ctx);
            assertFalse(result.success());
        }
    }

    @Nested
    class Security {

        private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

        @Test
        void path_traversal_blocked() {
            var executor = new DocumentExtractorSkillExecutor(tempDir.toString());
            var result = executor.execute("library.doc.extract",
                Map.of("path", "../../../etc/passwd"), ctx);
            assertFalse(result.success());
        }
    }

    @Nested
    class UnsupportedFormat {

        private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

        @Test
        void unsupported_extension_returns_error() throws IOException {
            Files.writeString(tempDir.resolve("test.xyz"), "content");
            var executor = new DocumentExtractorSkillExecutor(tempDir.toString());
            var result = executor.execute("library.doc.extract",
                Map.of("path", "test.xyz"), ctx);
            assertFalse(result.success());
        }
    }
}
