package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WhisperSkillExecutorTest {

    private final WhisperSkillExecutor executor = new WhisperSkillExecutor();

    @Nested
    class Registration {
        @Test
        void registers_one_skill() {
            assertEquals(1, executor.availableSkills().size());
            assertTrue(executor.supports("scriptorium.whisper.transcribe"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class MissingParams {
        @Test
        void missing_file_path_and_url_returns_error() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            SkillResult result = executor.execute("scriptorium.whisper.transcribe", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
