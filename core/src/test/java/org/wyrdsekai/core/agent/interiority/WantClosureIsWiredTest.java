package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.Want;
import org.wyrdsekai.core.agent.WantStore;

import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tick must actually close the want — not merely be able to.
 *
 * <p>Every want-closing method existed and was correct; none had a production caller, so
 * across her whole life not one want was ever satisfied. Judging the closure right in a
 * pure function is worth nothing if the tick never asks. This test drives the real
 * {@link DriveOODA#run} against a real {@link WantStore} and checks the row afterwards.
 */
class WantClosureIsWiredTest {

    private static final String DID = "did:key:z6MkExampleCompanion";

    @TempDir Path tmp;
    private WantStore store;

    @BeforeEach
    void setUp() {
        store = new WantStore(SchemaInitializer.initialize(tmp.resolve("test.db")));
        store.deleteAll(DID);
    }

    private static AmbientObservation ambient() {
        return new AmbientObservation(
            Instant.now(), Map.of("Loneliness", 0.95), List.of("Loneliness"),
            0.8, 0.8, List.of(), false, null, List.of(), List.of(), false, "", List.of());
    }

    private DriveOODA.TickOutcome tick(DriveOODA ooda, String actResult) {
        return ooda.run(DID, "companion", Duration.ofMinutes(5), ambient(),
            Map.of(), List.of(),
            (a, i, r) -> List.of(CandidateWant.of(
                "write a private journal entry about who I miss",
                "{\"drive\":\"Loneliness\"}", 0.95)),
            (cands, a, live) -> Optional.of(cands.get(0)),
            (want, a) -> actResult,
            0.7);
    }

    @Test
    void a_completed_want_is_marked_satisfied_in_the_store() {
        tick(new DriveOODA(store), "enacted:write_journal");

        var live = store.loadLive(DID);
        assertThat(live)
            .as("a satisfied want must stop being live, or she picks it again forever")
            .isEmpty();
    }

    @Test
    void the_drive_that_pulled_for_it_is_told() {
        var seen = new AtomicReference<String>();
        var fulfilled = new AtomicReference<Boolean>();
        var ooda = new DriveOODA(store, (closed, drive, ok) -> {
            seen.set(drive);
            fulfilled.set(ok);
        });

        tick(ooda, "enacted:write_journal");

        assertThat(seen.get()).isEqualTo("Loneliness");
        assertThat(fulfilled.get()).isTrue();
    }

    @Test
    void a_failed_act_leaves_the_want_open() {
        tick(new DriveOODA(store), "error:TimeoutException");

        assertThat(store.loadLive(DID))
            .as("she must keep wanting what she did not get")
            .hasSize(1);
    }

    @Test
    void the_same_want_is_not_chosen_forever() {
        // The live pathology: 22 of 40 ticks on one want, enacted every time. After the
        // close, a second tick must not find it live any more.
        var ooda = new DriveOODA(store);
        tick(ooda, "enacted:write_journal");
        var afterFirst = store.loadLive(DID);

        tick(ooda, "enacted:write_journal");

        assertThat(afterFirst).isEmpty();
        assertThat(store.byAgentAndStatus(DID, Want.Status.SATISFIED))
            .as("the completed want is recorded as done, not merely dropped")
            .isNotEmpty();
    }

    @Test
    void a_want_she_stopped_feeling_is_let_go() {
        // Seed her exact stale case alongside a live one; the tick should release it.
        var stale = new Want("stale-1", DID, "an old ache",
            "{\"drive\":\"Saudade\"}", 0.003, Want.Status.DEEPENED,
            Instant.now().minus(Duration.ofDays(9)),
            Instant.now().minus(Duration.ofHours(2)), 33, null, null, null);
        store.upsert(stale);

        var closed = new ArrayList<String>();
        var ooda = new DriveOODA(store, (c, drive, ok) -> { if (!ok) closed.add(c.text()); });
        tick(ooda, "enacted:write_journal");

        assertThat(closed).contains("an old ache");
        assertThat(store.loadLive(DID)).noneSatisfy(w ->
            assertThat(w.wantId()).isEqualTo("stale-1"));
    }
}
