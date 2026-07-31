package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GmailSkillExecutorTest {

    private final GmailSkillExecutor executor = new GmailSkillExecutor();

    @Nested
    class Registration {

        @Test
        void registers_six_skills() {
            var skills = executor.availableSkills();
            assertEquals(6, skills.size());
            var ids = skills.stream().map(s -> s.id()).toList();
            assertTrue(ids.contains("herald.gmail.inbox"));
            assertTrue(ids.contains("herald.gmail.read"));
            assertTrue(ids.contains("herald.gmail.draft"));
            assertTrue(ids.contains("herald.gmail.send"));
            assertTrue(ids.contains("herald.gmail.label"));
            assertTrue(ids.contains("herald.gmail.search"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class Configuration {

        @Test
        void missing_google_oauth_returns_not_configured() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            var result = executor.execute("herald.gmail.inbox", Map.of(), ctx);
            assertFalse(result.success());
        }
    }

    @Nested
    class ParamValidation {

        private final SkillContext ctx = SkillContext.forAgent(
            "did:test:agent1", "test-room",
            Map.of("google_oauth", "test-token"), 1000);

        @Test
        void read_missing_messageId_returns_error() {
            var result = executor.execute("herald.gmail.read", Map.of(), ctx);
            assertFalse(result.success());
        }

        @Test
        void send_missing_params_returns_error() {
            var result = executor.execute("herald.gmail.send", Map.of("to", "a@b.com"), ctx);
            assertFalse(result.success());
        }

        @Test
        void search_missing_query_returns_error() {
            var result = executor.execute("herald.gmail.search", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
