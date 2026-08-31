package org.wyrdsekai.core.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire's contract at the Java seam: env-gated, never throws, resolves
 * the shipped script. The training itself is the python script's job (its
 * gates are exercised live); what Java owes is that a disabled or broken
 * environment can never hurt sleep completion.
 */
class SleepWeightWriteTest {

    @AfterEach
    void clear() {
        System.clearProperty("wyrdsekai.sleep.write");
    }

    @Test
    void disabled_by_default() {
        assertThat(SleepWeightWrite.enabled()).isFalse();
    }

    @Test
    void enabled_by_property() {
        System.setProperty("wyrdsekai.sleep.write", "1");
        assertThat(SleepWeightWrite.enabled()).isTrue();
        System.setProperty("wyrdsekai.sleep.write", "true");
        assertThat(SleepWeightWrite.enabled()).isTrue();
        System.setProperty("wyrdsekai.sleep.write", "0");
        assertThat(SleepWeightWrite.enabled()).isFalse();
    }

    @Test
    void fire_and_forget_never_throws_when_disabled() {
        SleepWeightWrite.fireAndForget("test-agent");
    }

    @Test
    void wyrd_cli_resolvable_from_the_repo_tree() {
        // The auto-apply path shells out to the CLI; the ".." candidate must
        // find the real bin/wyrd this deb ships.
        var p = SleepWeightWrite.resolveWyrd();
        org.assertj.core.api.Assertions.assertThat(p).isNotNull();
    }

    @Test
    void resolves_the_shipped_script_from_the_repo_tree() {
        // Test working dir is the core module; the ".." candidate must find
        // the real script this deb will ship.
        var p = SleepWeightWrite.resolveScript();
        assertThat(p).as("sleep_write.py resolvable from module dir").isNotNull();
        assertThat(p.toString()).endsWith("sleep_write.py");
    }
}
