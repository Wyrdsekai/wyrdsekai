package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleChatSkillExecutorTest {

    private final GoogleChatSkillExecutor executor = new GoogleChatSkillExecutor();

    @Nested
    class Registration {
        @Test
        void registers_two_skills() {
            assertEquals(3, executor.availableSkills().size());
        }

        @Test
        void skill_ids_present() {
            assertTrue(executor.supports("herald.gchat.send"));
            assertTrue(executor.supports("herald.gchat.spaces"));
        }

        @Test
        void does_not_support_unknown() {
            assertFalse(executor.supports("herald.gchat.delete"));
        }
    }

    @Nested
    class Tier {
        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class NotConfigured {
        @Test
        void returns_error_when_oauth_token_missing() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            SkillResult result = executor.execute("herald.gchat.spaces", Map.of(), ctx);
            assertFalse(result.success());
            assertTrue(result.output().toLowerCase().contains("not configured")
                || result.output().toLowerCase().contains("google"));
        }
    }
}
