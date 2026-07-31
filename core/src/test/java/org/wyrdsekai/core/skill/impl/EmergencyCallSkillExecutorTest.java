package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillTier;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyCallSkillExecutorTest {

    private final EmergencyCallSkillExecutor executor = new EmergencyCallSkillExecutor();

    @Nested
    class Registration {
        @Test
        void registers_three_skills() {
            assertEquals(3, executor.availableSkills().size());
            assertTrue(executor.supports("herald.call.emergency"));
            assertTrue(executor.supports("herald.call.dial"));
            assertTrue(executor.supports("herald.call.status"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Test
    void default_constructor_creates_empty_contacts() {
        var exec = new EmergencyCallSkillExecutor();
        assertNotNull(exec.availableSkills());
        assertEquals(3, exec.availableSkills().size());
    }
}
