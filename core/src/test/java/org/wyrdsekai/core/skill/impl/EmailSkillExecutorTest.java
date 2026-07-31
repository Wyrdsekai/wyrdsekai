package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailSkillExecutorTest {

    private final EmailSkillExecutor executor = new EmailSkillExecutor();

    @Nested
    class Registration {

        @Test
        void registers_four_skills() {
            var skills = executor.availableSkills();
            assertEquals(4, skills.size());
            var ids = skills.stream().map(s -> s.id()).toList();
            assertTrue(ids.contains("herald.email.inbox"));
            assertTrue(ids.contains("herald.email.read"));
            assertTrue(ids.contains("herald.email.send"));
            assertTrue(ids.contains("herald.email.draft"));
        }

        @Test
        void supports_known_skill() {
            assertTrue(executor.supports("herald.email.send"));
        }

        @Test
        void does_not_support_unknown_skill() {
            assertFalse(executor.supports("herald.email.unknown"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class Execution {

        @Test
        void missing_credentials_returns_error() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            var result = executor.execute("herald.email.inbox", Map.of(), ctx);
            assertFalse(result.success());
        }

        @Test
        void send_denied_for_agent_session() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room",
                Map.of("email_credentials", "user:pass@host:993:587"), 1000);
            var result = executor.execute("herald.email.send",
                Map.of("to", "a@b.com", "subject", "test", "body", "hello"), ctx);
            assertFalse(result.success());
        }
    }
}
