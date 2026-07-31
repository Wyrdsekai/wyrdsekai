package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillTier;

import static org.junit.jupiter.api.Assertions.*;

class SignalSkillExecutorTest {

    private final SignalSkillExecutor executor = new SignalSkillExecutor();

    @Nested
    class Registration {
        @Test
        void registers_four_skills() {
            assertEquals(4, executor.availableSkills().size());
        }

        @Test
        void skill_ids_present() {
            assertTrue(executor.supports("herald.signal.inbox"));
            assertTrue(executor.supports("herald.signal.send"));
            assertTrue(executor.supports("herald.signal.groups"));
            assertTrue(executor.supports("herald.signal.contacts"));
        }

        @Test
        void does_not_support_unknown() {
            assertFalse(executor.supports("herald.signal.unknown"));
        }
    }

    @Nested
    class Tier {
        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Test
    void default_constructor_works() {
        var exec = new SignalSkillExecutor();
        assertNotNull(exec.availableSkills());
    }
}
