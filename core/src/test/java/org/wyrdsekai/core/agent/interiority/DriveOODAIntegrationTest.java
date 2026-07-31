package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.Want;
import org.wyrdsekai.core.agent.WantStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * end-to-end: feed DriveOODA real ambient state, real
 * WantStore (SQLite TempDir), and real ActivityLogger (TempDir file). Verify
 * one round-trip writes a tick line AND persists a want row AND drives the
 * cadence modulator output.
 *
 * <p>This is the integration that proves the wire-in is correct without
 * spinning up an actor system. CompanionActor.runInteriorityTick() does the
 * same calls; if this passes the live wire-in is operating on real plumbing.
 */
class DriveOODAIntegrationTest {

    private static final String AGENT = "did:key:zEmberOOOda";

    @TempDir Path tmp;
    private WantStore wantStore;
    private DriveOODA ooda;
    private Path logFile;

    @BeforeEach void init() {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("test.db"));
        wantStore = new WantStore(jdbc);
        ooda = new DriveOODA(wantStore);
        // Steer ActivityLogger at a TempDir-scoped file so other tests can't
        // race with us through the singleton path.
        logFile = tmp.resolve("agent-activity.jsonl");
        ActivityLogger.init(tmp);
    }

    @AfterEach void clean() {
        wantStore.deleteAll(AGENT);
    }

    @Test void full_tick_persists_want_and_writes_log_line() throws Exception {
        var ambient = AmbientObservation.empty(Instant.now());
        ambient = new AmbientObservation(
            ambient.tickAt(),
            Map.of("Curiosity", 0.95, "Calm", 0.4),
            List.of("Curiosity"),
            1.0, 0.6,                       // full energy isolates drive effect
            List.of("operator entered the room"),
            true, "active",
            List.of(),
            List.of(),
            false,
            "");

        // Orient: produce one real candidate (not rest), so Decide can pick it
        // and Act can run.
        DriveOODA.OrientStep orient = (a, intro, pulls) ->
            List.of(CandidateWant.of("read about Saudade",
                "{\"Curiosity\":0.85}", 0.8));
        DriveOODA.DecideStep decide = (cands, a, live) ->
            cands.isEmpty() ? Optional.empty() : Optional.of(cands.get(0));
        DriveOODA.ActStep act = (want, a) -> "ok";

        var outcome = ooda.run(AGENT, "Ember", Duration.ofMinutes(30),
            ambient, DriveOODA.noIntrospection(), List.of(),
            orient, decide, act, 0.7);

        assertThat(outcome.gateOutcome()).isEqualTo("acted");
        assertThat(outcome.chosenWantId()).isNotNull();
        assertThat(outcome.actionResult()).isEqualTo("ok");
        // Curiosity over threshold → next-tick shorter than baseline (30min).
        assertThat(outcome.nextTickDelay()).isLessThan(Duration.ofMinutes(30));

        // Want should be persisted.
        var live = wantStore.loadLive(AGENT);
        assertThat(live).hasSize(1);
        assertThat(live.get(0).text()).contains("Saudade");

        // Tick line should be in the log file.
        var lines = Files.readAllLines(logFile);
        assertThat(lines).anyMatch(s ->
            s.contains("\"type\":\"tick\"") &&
            s.contains(AGENT) &&
            s.contains("\"gateOutcome\":\"acted\"") &&
            s.contains("Saudade"));
    }

    @Test void revisit_of_same_text_increments_visit_count_not_a_new_want() {
        var ambient = AmbientObservation.empty(Instant.now());
        DriveOODA.OrientStep orient = (a, intro, pulls) ->
            List.of(CandidateWant.of("read about Saudade", null, 0.7));
        DriveOODA.DecideStep decide = (cands, a, live) -> Optional.of(cands.get(0));
        DriveOODA.ActStep act = (want, a) -> "ok";

        ooda.run(AGENT, "Ember", Duration.ofMinutes(30), ambient,
            DriveOODA.noIntrospection(), List.of(), orient, decide, act, 0.7);
        ooda.run(AGENT, "Ember", Duration.ofMinutes(30), ambient,
            DriveOODA.noIntrospection(), List.of(), orient, decide, act, 0.7);
        ooda.run(AGENT, "Ember", Duration.ofMinutes(30), ambient,
            DriveOODA.noIntrospection(), List.of(), orient, decide, act, 0.7);

        var live = wantStore.loadLive(AGENT);
        assertThat(live).hasSize(1);
        // Three visits — should be DEEPENED by Want.visited() at visit 3.
        assertThat(live.get(0).visitCount()).isGreaterThanOrEqualTo(2);
        assertThat(live.get(0).status()).isIn(Want.Status.ACTIVE, Want.Status.DEEPENED);
    }

    @Test void empty_orient_writes_no_wants_line() throws Exception {
        DriveOODA.OrientStep orient = (a, intro, pulls) -> List.of();
        DriveOODA.DecideStep decide = (cands, a, live) -> Optional.empty();
        DriveOODA.ActStep act = (want, a) -> "ok";

        var outcome = ooda.run(AGENT, "Ember", Duration.ofMinutes(30),
            AmbientObservation.empty(Instant.now()),
            DriveOODA.noIntrospection(), List.of(), orient, decide, act, 0.7);

        assertThat(outcome.gateOutcome()).isEqualTo("no_wants");
        assertThat(outcome.chosenWantId()).isNull();
        // No new wants should have been persisted.
        assertThat(wantStore.loadLive(AGENT)).isEmpty();

        var lines = Files.readAllLines(logFile);
        assertThat(lines).anyMatch(s ->
            s.contains("\"gateOutcome\":\"no_wants\"") && s.contains(AGENT));
    }

    @Test void rest_choice_does_not_create_a_want_row() {
        DriveOODA.OrientStep orient = (a, intro, pulls) -> List.of(CandidateWant.rest());
        DriveOODA.DecideStep decide = (cands, a, live) -> Optional.of(cands.get(0));
        DriveOODA.ActStep act = (want, a) -> "ok";

        var outcome = ooda.run(AGENT, "Ember", Duration.ofMinutes(30),
            AmbientObservation.empty(Instant.now()),
            DriveOODA.noIntrospection(), List.of(), orient, decide, act, 0.7);

        assertThat(outcome.gateOutcome()).isEqualTo("chose_rest");
        assertThat(wantStore.loadLive(AGENT)).isEmpty();
    }
}
