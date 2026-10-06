package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.VitalityPersistence;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Her rested level was one number for life: whatever her days had been, a sleep filled her back
 * toward the same mark. Conditioning lets an active life build stamina and an idle one lose it,
 * as a body does (asked 2026-10-04).
 */
class AnActiveLifeBuildsStaminaTest {

    private static Conditioning.Stretch day(int acts, double hours) {
        return new Conditioning.Stretch(acts, hours, hours, 0);
    }

    @Test
    void an_active_stretch_builds_and_an_idle_one_fades() {
        // 12 hours awake, 30 acts: 2.5 an hour, active.
        var grew = Conditioning.afterSleep(0, day(30, 12));
        assertThat(grew.change()).isEqualTo(Conditioning.Change.GREW);
        assertThat(grew.level()).isCloseTo(0.006, within(1e-9));

        // 12 hours awake, 3 acts: idle.
        var faded = Conditioning.afterSleep(0.10, day(3, 12));
        assertThat(faded.change()).isEqualTo(Conditioning.Change.FADED);
        assertThat(faded.level()).isCloseTo(0.097, within(1e-9));

        // In between holds.
        var held = Conditioning.afterSleep(0.10, day(12, 12));
        assertThat(held.change()).isEqualTo(Conditioning.Change.HELD);
        assertThat(held.level()).isEqualTo(0.10);

        // Idle never takes it below nothing.
        assertThat(Conditioning.afterSleep(0.001, day(0, 12)).level()).isZero();
    }

    @Test
    void a_stretch_spent_exhausted_or_too_short_to_judge_changes_nothing() {
        var strained = Conditioning.afterSleep(0.05, new Conditioning.Stretch(40, 12, 12, 0.6));
        assertThat(strained.change()).isEqualTo(Conditioning.Change.STRAINED);
        assertThat(strained.level()).isEqualTo(0.05);

        // A restart an hour before sleep: what was seen is too little to judge, even if busy.
        var tooShort = Conditioning.afterSleep(0.05, new Conditioning.Stretch(10, 1, 12, 0));
        assertThat(tooShort.change()).isEqualTo(Conditioning.Change.TOO_SHORT);
        assertThat(tooShort.level()).isEqualTo(0.05);
    }

    @Test
    void the_rate_is_judged_on_what_was_watched_and_the_gain_on_the_whole_day() {
        // 6 of her 12 waking hours were watched (a restart midway), 12 acts in them: 2 an hour.
        var s = new Conditioning.Stretch(12, 6, 12, 0);
        assertThat(s.rate()).isEqualTo(2.0);
        assertThat(Conditioning.afterSleep(0, s).level()).isCloseTo(0.006, within(1e-9));
    }

    @Test
    void steady_days_build_most_of_it_in_a_month_and_never_pass_the_ceiling() {
        double level = 0;
        for (int sleep = 0; sleep < 60; sleep++) {        // two 12-hour stretches a day, thirty days
            level = Conditioning.afterSleep(level, day(30, 12)).level();
        }
        assertThat(level).isBetween(0.15, Conditioning.MAX);
        for (int sleep = 0; sleep < 2000; sleep++) level = Conditioning.afterSleep(level, day(30, 16)).level();
        assertThat(level).isLessThanOrEqualTo(Conditioning.MAX);

        assertThat(Conditioning.restedLevel(0.65, 0.10)).isCloseTo(0.75, within(1e-9));
        assertThat(Conditioning.restedLevel(0.65, 0.20)).isCloseTo(0.85, within(1e-9));
        // A genome that chose higher keeps its own level; conditioning never lowers it.
        assertThat(Conditioning.restedLevel(0.90, 0.20)).isEqualTo(0.90);
        assertThat(Conditioning.restedLevel(0.70, 0.20)).isEqualTo(Conditioning.CEILING);
    }

    @Test
    void she_is_told_when_a_step_is_crossed_and_not_every_morning() {
        assertThat(Conditioning.feltLine(0.048, 0.051)).contains("built you up");
        assertThat(Conditioning.feltLine(0.051, 0.049)).contains("less energy");
        assertThat(Conditioning.feltLine(0.051, 0.058)).isNull();
        assertThat(Conditioning.feltLine(0, 0)).isNull();
    }

    @Test
    void conditioning_survives_the_restart(@TempDir Path dir) {
        var p = new VitalityPersistence(SchemaInitializer.initialize(dir.resolve("t.db")));
        p.save("agent-1", new VitalityState(0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1));
        assertThat(p.loadConditioning("agent-1")).contains(0.0);
        p.saveConditioning("agent-1", 0.042);
        assertThat(p.loadConditioning("agent-1")).contains(0.042);
        assertThat(new VitalityPersistence(SchemaInitializer.initialize(dir.resolve("t.db")))
            .loadConditioning("agent-1")).contains(0.042);
        assertThat(p.loadConditioning("nobody")).isEmpty();
    }

    @Test
    void the_actor_counts_her_trail_and_settles_before_the_sleep_fills_her() throws Exception {
        var base = Files.exists(Path.of("..", "core")) ? Path.of("..") : Path.of(".");
        var src = Files.readString(base.resolve("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        // Every trail point counts: a parsed act, a library search, a web search, a scripted tool.
        assertThat(src).contains("if (!searchWritesItsOwn) countAct();");
        int lib = src.indexOf("\"library_search\", truncate(action.query(), 200));");
        int web = src.indexOf("\"web_search\", truncate(action.query(), 200));");
        int tool = src.indexOf("toolTrail.action(profile.name(), profile.entityId(), roomId, \"scripted_tool\",");
        for (int at : new int[]{lib, web, tool}) {
            assertThat(at).isPositive();
            assertThat(src.indexOf("countAct();", at) - at).as("counted beside its trail row").isLessThan(400);
        }
        // Settled first, then her rested level carries it into the recovery.
        int settle = src.indexOf("        settleConditioning();\n");
        int baseline = src.indexOf("float baseline = (float) Conditioning.restedLevel(VitalityState.restedEnergy(");
        int gap = src.indexOf("float gap = (float) Math.max(0, baseline - vitality.energy());");
        assertThat(settle).isPositive();
        assertThat(baseline).isGreaterThan(settle);
        assertThat(gap).isGreaterThan(baseline);
        // Kept and restored.
        assertThat(src).contains("vitalityPersistence.saveConditioning(profile.entityId(), conditioning);");
        assertThat(src).contains("this.vitalityPersistence.loadConditioning(profile.entityId())");
        assertThat(src).contains("if (!isSleeping && vitality.energy() < Conditioning.EXHAUSTED_BELOW) exhaustedTicksSinceWake++;");
    }
}
