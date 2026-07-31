package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StripeIssuingSkillExecutorTest {

    private final StripeIssuingSkillExecutor executor = new StripeIssuingSkillExecutor();

    @Nested
    class Registration {
        @Test
        void registers_two_skills() {
            assertEquals(5, executor.availableSkills().size());
            assertTrue(executor.supports("trading.stripe.create-card"));
            assertTrue(executor.supports("trading.stripe.fund"));
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
            SkillResult result = executor.execute("trading.stripe.create-card",
                Map.of("cardholder", "ch_123"), ctx);
            assertFalse(result.success());
            assertTrue(result.output().toLowerCase().contains("not configured")
                || result.output().toLowerCase().contains("stripe"));
        }
    }
}
