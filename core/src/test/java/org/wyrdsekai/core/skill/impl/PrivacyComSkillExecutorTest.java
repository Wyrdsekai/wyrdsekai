package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyComSkillExecutorTest {

    private final PrivacyComSkillExecutor executor = new PrivacyComSkillExecutor();

    @Nested
    class Registration {
        @Test
        void registers_five_skills() {
            assertEquals(5, executor.availableSkills().size());
            assertTrue(executor.supports("trading.privacy.create-card"));
            assertTrue(executor.supports("trading.privacy.list-cards"));
            assertTrue(executor.supports("trading.privacy.transactions"));
            assertTrue(executor.supports("trading.privacy.pause"));
            assertTrue(executor.supports("trading.privacy.update-limit"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class NotConfigured {
        @Test
        void returns_error_when_api_key_missing() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            SkillResult result = executor.execute("trading.privacy.list-cards", Map.of(), ctx);
            assertFalse(result.success());
            assertTrue(result.output().toLowerCase().contains("not configured")
                || result.output().toLowerCase().contains("privacy"));
        }
    }
}
