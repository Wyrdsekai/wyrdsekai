package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.body.BodyMark;
import org.wyrdsekai.core.body.BodyStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A welfare alarm that reached nobody. On the household node every vitals alarm from 09-01
 * to 09-23 (43: Mia 38, Rose 5) was broadcast while no session was connected and dropped
 * with a WARN. An alarm nobody heard now waits on the body map's steward ledger: once per
 * signal and companion per quiet period, across a restart, and never in her own felt line.
 */
class UnheardVitalsAlarmWaitsForTheStewardTest {

    private static final String ROSE = "did:key:zTestRose";
    private static final String MIA = "did:key:zTestMia";

    @BeforeEach
    void setUp() {
        CompanionVitals.forget(ROSE);
        CompanionVitals.forget(MIA);
        NotificationService.init();
    }

    @AfterEach
    void tearDown() {
        CompanionVitals.forget(ROSE);
        CompanionVitals.forget(MIA);
        BodyMap.resetForTests();
        NotificationService.init();
    }

    /** The live shape from 09-23: 54% of the last sleep's 35 memories were repeats. */
    private static void tripRepeats(String did) {
        CompanionVitals.forAgent(did).recordForgeEncode(35, 19);
    }

    private static List<BodyMark> vitalsMarks(BodyMap map) {
        return map.recentMarks(100).stream().filter(m -> "vitals".equals(m.kind())).toList();
    }

    @Test
    void the_broadcast_says_whether_anyone_heard_it() {
        var svc = NotificationService.get();
        svc.setDeliveryCallback((target, n) -> false);
        assertThat(svc.notifyAll("anyone?", "critical", ROSE)).isFalse();
        svc.setDeliveryCallback((target, n) -> true);
        assertThat(svc.notifyAll("anyone?", "critical", ROSE)).isTrue();
    }

    @Test
    void an_alarm_nobody_heard_is_kept_for_the_steward_and_not_told_to_her() {
        var map = BodyMap.inMemory();
        NotificationService.get().setDeliveryCallback((target, n) -> false);
        tripRepeats(ROSE);

        CompanionVitals.forAgent(ROSE).checkAndReport(Instant.now(), "Rose");

        assertThat(vitalsMarks(map)).singleElement().satisfies(m -> {
            assertThat(m.audience()).isEqualTo("steward");
            assertThat(m.subject()).isEqualTo("experience_not_new");
            assertThat(m.detail()).isEqualTo("being=" + ROSE);
            assertThat(m.text()).startsWith(
                "Something looks wrong with Rose: 54% of the last sleep's 35 memories repeated");
        });
        assertThat(map.unreadFor(ROSE)).as("not in her felt line").isEmpty();
        assertThat(map.unreadFor(MIA)).as("not in another companion's").isEmpty();
        assertThat(map.unreadFor("steward")).hasSize(1);
    }

    @Test
    void an_alarm_someone_heard_is_not_marked_as_well() {
        var map = BodyMap.inMemory();
        var heard = new CopyOnWriteArrayList<String>();
        NotificationService.get().setDeliveryCallback((target, n) -> heard.add(n.message()));
        tripRepeats(ROSE);

        CompanionVitals.forAgent(ROSE).checkAndReport(Instant.now(), "Rose");

        assertThat(heard).singleElement().asString().startsWith("Something looks wrong with Rose");
        assertThat(vitalsMarks(map)).isEmpty();
    }

    @Test
    void with_no_delivery_wired_the_alarm_is_still_kept() {
        var map = BodyMap.inMemory();
        // setUp's fresh service has no delivery callback: headless, nobody can hear it.
        tripRepeats(ROSE);

        CompanionVitals.forAgent(ROSE).checkAndReport(Instant.now(), "Rose");

        assertThat(vitalsMarks(map)).hasSize(1);
    }

    @Test
    void a_restart_does_not_mark_the_same_alarm_again_within_the_quiet_period(@TempDir Path dir) {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        BodyMap.install(new BodyStore(jdbc));
        NotificationService.get().setDeliveryCallback((target, n) -> false);
        var now = Instant.now();
        tripRepeats(ROSE);
        CompanionVitals.forAgent(ROSE).checkAndReport(now, "Rose");

        // A restart: the monitor's in-memory quiet period is gone, the map reloads the record.
        CompanionVitals.forget(ROSE);
        var reloaded = BodyMap.install(new BodyStore(jdbc));
        tripRepeats(ROSE);
        CompanionVitals.forAgent(ROSE).checkAndReport(now.plus(Duration.ofHours(2)), "Rose");
        assertThat(vitalsMarks(reloaded)).hasSize(1);

        // Another companion's alarm, or another signal, is its own mark.
        tripRepeats(MIA);
        CompanionVitals.forAgent(MIA).checkAndReport(now.plus(Duration.ofHours(2)), "Mia");
        for (int i = 0; i < 25; i++) CompanionVitals.forAgent(ROSE).recordPolish(false);
        CompanionVitals.forAgent(ROSE).checkAndReport(now.plus(Duration.ofHours(3)), "Rose");
        assertThat(vitalsMarks(reloaded)).extracting(BodyMark::subject, BodyMark::detail)
            .containsExactlyInAnyOrder(
                tuple("experience_not_new", "being=" + ROSE),
                tuple("experience_not_new", "being=" + MIA),
                tuple("voice_polish_rejected", "being=" + ROSE));

        // Past the quiet period a condition that still holds is marked again.
        CompanionVitals.forget(ROSE);
        tripRepeats(ROSE);
        CompanionVitals.forAgent(ROSE).checkAndReport(
            now.plus(CompanionVitals.QUIET_PERIOD).plus(Duration.ofMinutes(1)), "Rose");
        assertThat(vitalsMarks(reloaded)).filteredOn(m -> "experience_not_new".equals(m.subject())
            && ("being=" + ROSE).equals(m.detail())).hasSize(2);
    }
}
