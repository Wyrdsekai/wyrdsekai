package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillTier;

import static org.junit.jupiter.api.Assertions.*;

class KeybaseSkillExecutorTest {

    private final KeybaseSkillExecutor executor = new KeybaseSkillExecutor();

    @Nested
    class Registration {
        @Test
        void registers_six_skills() {
            assertEquals(6, executor.availableSkills().size());
        }

        @Test
        void chat_skills_present() {
            assertTrue(executor.supports("herald.keybase.inbox"));
            assertTrue(executor.supports("herald.keybase.send"));
            assertTrue(executor.supports("herald.keybase.teams"));
        }

        @Test
        void kbfs_skills_present() {
            assertTrue(executor.supports("vault.kbfs.read"));
            assertTrue(executor.supports("vault.kbfs.write"));
            assertTrue(executor.supports("vault.kbfs.ls"));
        }

        @Test
        void does_not_support_unknown() {
            assertFalse(executor.supports("vault.kbfs.unknown"));
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
        var exec = new KeybaseSkillExecutor();
        assertNotNull(exec.availableSkills());
    }
}
