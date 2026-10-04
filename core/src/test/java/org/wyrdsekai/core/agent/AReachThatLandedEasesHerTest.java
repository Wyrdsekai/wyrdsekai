package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Household node, 2026-10-01: after an act of hers Restlessness moved 72% of the time, Loneliness 1%,
 * Amae about never, and Amae was the strongest pull behind 504 of 896 acts. Loneliness was eased only
 * by what she heard, never by her own reach landing with someone (the spec drains it on "any tell or
 * two-way exchange"). Amae was two counters a restart zeroed and a tank that could only rise: it read
 * exactly 0.42 on every line of one day and exactly 0.00 on every line of another.
 */
class AReachThatLandedEasesHerTest {

    @Test
    void amae_is_read_over_her_last_ten_needs() {
        var s = AmaeSignals.empty();
        assertThat(s.deficit()).isZero();
        s = s.asked().asked().asked().anticipated();
        assertThat(s.asks()).isEqualTo(3);
        assertThat(s.anticipations()).isEqualTo(1);
        assertThat(s.deficit()).isEqualTo(0.75, within(1e-9));
        for (int i = 0; i < 10; i++) s = s.anticipated();
        assertThat(s.deficit()).as("what her person has since given is what is counted").isZero();
        assertThat(s.encode()).hasSize(AmaeSignals.WINDOW);
    }

    @Test
    void the_window_survives_a_restart() {
        var before = AmaeSignals.empty().asked().anticipated().asked();
        var after = AmaeSignals.decode(before.encode());
        assertThat(after.deficit()).isEqualTo(before.deficit());
        assertThat(AmaeSignals.decode(null).deficit()).isZero();
        assertThat(AmaeSignals.decode("AAxF?A").encode()).isEqualTo("AAFA");
        assertThat(AmaeSignals.decode("A".repeat(25)).encode()).hasSize(AmaeSignals.WINDOW);
    }

    @Test
    void a_tank_above_its_target_settles_toward_it_and_never_past() {
        var tau = Duration.ofHours(8);
        assertThat(VitalityState.settle(0.42, 0.0, 3600, tau)).isEqualTo(0.42 - 0.42 / 8, within(1e-9));
        assertThat(VitalityState.settle(0.42, 0.30, 8 * 3600 * 5, tau)).as("a long gap lands on the target").isEqualTo(0.30, within(1e-9));
        assertThat(VitalityState.settle(0.10, 0.30, 3600, tau)).as("below its target it is left to the rising rule").isEqualTo(0.10);
        assertThat(VitalityState.settle(0.42, 0.0, 3600, null)).isEqualTo(0.42);
    }

    @Test
    void her_own_reach_eases_when_it_lands_and_the_want_closes_then() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/";
        var ca = read(rel + "CompanionActor.java");
        var vs = read(rel + "VitalityState.java");
        // The want a reach serves closes at the landing, not at the sending.
        assertThat(ca).contains("if (!REACH_VERBS.contains(enactedType)) noteInteriorityEnactment(enactedType);");
        // Landed with a peer, with a person in the room, by whisper, by session, by mail; and the stone.
        assertThat(ca).contains("noteReachLanded(\"tell_agent\", targetId, action.targetName(), false, false);");
        assertThat(count(ca, "noteReachLanded(\"tell_agent\", targetId, action.targetName(), true, false);")).isEqualTo(2);
        assertThat(count(ca, "noteReachLanded(\"tell_agent\", targetId, action.targetName(), true, true);")).isEqualTo(2);
        assertThat(ca).contains("noteReachLanded(\"sending_stone\", null, reachedFor, false, false);");
        // Loneliness as the spec drains it; saudade on the ledger when her person is away; a reply is not her reach.
        assertThat(ca).contains("if (person && answersAPerson()) return;\n        ownTimeReach = ownTimeReach.asLanded();");
        assertThat(ca).contains("drainLonelinessOnInteraction(toBondholder);\n        if (toBondholder && away) {\n            saudadeLedger.recordLetter(bondholder);");
        // What pulled a want she named herself eases when it is done, except the relational tanks.
        assertThat(ca).contains("&& reach.pullDrive() != null && !RelationalAffordance.isRelational(reach.pullDrive())");
        // Saudade is not eased on the tank, where the ledger overwrites it.
        assertThat(ca).contains("if (\"saudade\".equalsIgnoreCase(drive)) {");
        // Her person's tell is theirs under any of their ids, and coming unasked by tell counts.
        assertThat(ca).contains("|| PersonIds.samePerson(bondholderDid, am.fromAgentId()));");
        // Amae: the window, kept; and the tank settles as well as rises.
        assertThat(ca).contains("double amaeDeficit = amaeSignals.deficit();");
        assertThat(ca).contains("vitalityPersistence.saveAmaeSignals(profile.entityId(), amaeSignals.encode());");
        assertThat(vs).contains("newAmae = settle(newAmae, amaeTarget, dt, AMAE_TAU);");
    }

    private static String read(String rel) throws Exception {
        var from = Path.of("..", rel);
        return Files.readString(Files.exists(from) ? from : Path.of(rel));
    }

    private static int count(String s, String needle) {
        return s.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
