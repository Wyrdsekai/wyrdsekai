package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@link Want} lifecycle + {@link WantStore} persistence against
 * a real SQLite DB. Mirrors {@code CapabilityGapStoreTest} structurally.
 */
class WantStoreTest {

    private static final String AGENT = "did:key:zTestAgentWants";

    @TempDir Path tmp;
    private WantStore store;

    @BeforeEach void initDb() {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("test.db"));
        store = new WantStore(jdbc);
        store.deleteAll(AGENT);
    }

    @Test void upsert_then_get_roundtrips() {
        var w = Want.active(AGENT, "I want to read about Saudade",
            "{\"Curiosity\": 0.8}", 0.7, null);
        store.upsert(w);

        var loaded = store.get(w.wantId()).orElseThrow();
        assertThat(loaded.text()).isEqualTo("I want to read about Saudade");
        assertThat(loaded.feltWeight()).isEqualTo(0.7);
        assertThat(loaded.status()).isEqualTo(Want.Status.ACTIVE);
        assertThat(loaded.driveResonance()).contains("Curiosity");
        assertThat(loaded.visitCount()).isEqualTo(1);
        assertThat(loaded.parentWantId()).isNull();
        assertThat(loaded.satisfiedAt()).isNull();
    }

    @Test void visited_increments_count_and_bumps_lastVisited() throws InterruptedException {
        var w = Want.active(AGENT, "x", null, 0.5, null);
        store.upsert(w);
        Thread.sleep(5);  // ensure timestamp advances

        var visited = w.visited();
        store.upsert(visited);

        var loaded = store.get(w.wantId()).orElseThrow();
        assertThat(loaded.visitCount()).isEqualTo(2);
        assertThat(loaded.lastVisitedAt()).isAfter(w.bornAt());
    }

    @Test void deepens_after_three_visits() {
        var w = Want.active(AGENT, "deep want", null, 0.5, null);
        w = w.visited(); // 2
        w = w.visited(); // 3 → deepens
        assertThat(w.status()).isEqualTo(Want.Status.DEEPENED);
    }

    @Test void satisfied_terminates_with_note() {
        var w = Want.active(AGENT, "x", null, 0.5, null);
        store.upsert(w);
        store.upsert(w.satisfied("read 3 chapters"));

        var loaded = store.get(w.wantId()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(Want.Status.SATISFIED);
        assertThat(loaded.satisfactionNote()).isEqualTo("read 3 chapters");
        assertThat(loaded.satisfiedAt()).isNotNull();
        assertThat(loaded.isLive()).isFalse();
    }

    @Test void abandoned_terminates_with_reason() {
        var w = Want.active(AGENT, "x", null, 0.5, null);
        store.upsert(w);
        store.upsert(w.abandoned("not actually pulling anymore"));
        var loaded = store.get(w.wantId()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(Want.Status.ABANDONED);
        assertThat(loaded.satisfactionNote()).contains("not actually pulling");
    }

    @Test void reconciled_terminates_with_parent_ref() {
        var w1 = Want.active(AGENT, "vague want", null, 0.5, null);
        var w2 = Want.active(AGENT, "absorbed into w2", null, 0.6, null);
        store.upsert(w1);
        store.upsert(w2);
        store.upsert(w1.reconciled(w2.wantId()));

        var loaded = store.get(w1.wantId()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(Want.Status.RECONCILED);
        assertThat(loaded.satisfactionNote()).contains(w2.wantId());
    }

    @Test void byAgentAndStatus_filters_correctly() {
        var live = Want.active(AGENT, "live one", null, 0.5, null);
        var done = Want.active(AGENT, "done one", null, 0.5, null);
        store.upsert(live);
        store.upsert(done);
        store.upsert(done.satisfied("done"));

        var actives = store.byAgentAndStatus(AGENT, Want.Status.ACTIVE);
        var satisfieds = store.byAgentAndStatus(AGENT, Want.Status.SATISFIED);
        assertThat(actives).hasSize(1);
        assertThat(actives.get(0).text()).isEqualTo("live one");
        assertThat(satisfieds).hasSize(1);
        assertThat(satisfieds.get(0).text()).isEqualTo("done one");
    }

    @Test void loadLive_returns_active_and_deepened() {
        var active = Want.active(AGENT, "active", null, 0.5, null);
        var deep = Want.active(AGENT, "deep", null, 0.5, null);
        deep = deep.visited().visited(); // → DEEPENED
        assertThat(deep.status()).isEqualTo(Want.Status.DEEPENED);
        var done = Want.active(AGENT, "done", null, 0.5, null).satisfied("ok");

        store.upsert(active);
        store.upsert(deep);
        store.upsert(done);

        var live = store.loadLive(AGENT);
        assertThat(live).hasSize(2);
        assertThat(live).extracting(Want::text)
            .containsExactlyInAnyOrder("active", "deep");
    }

    @Test void countLive_matches_loadLive() {
        store.upsert(Want.active(AGENT, "a", null, 0.5, null));
        store.upsert(Want.active(AGENT, "b", null, 0.5, null));
        store.upsert(Want.active(AGENT, "c", null, 0.5, null).abandoned("nope"));
        assertThat(store.countLive(AGENT)).isEqualTo(2);
    }

    @Test void wants_are_agent_scoped() {
        store.upsert(Want.active(AGENT, "my want", null, 0.5, null));
        store.upsert(Want.active("did:key:zOtherAgent", "their want", null, 0.5, null));
        assertThat(store.loadLive(AGENT)).hasSize(1);
        assertThat(store.loadLive("did:key:zOtherAgent")).hasSize(1);
    }

    @Test void delete_removes_row() {
        var w = Want.active(AGENT, "x", null, 0.5, null);
        store.upsert(w);
        assertThat(store.delete(w.wantId())).isTrue();
        assertThat(store.get(w.wantId())).isEmpty();
    }

    @Test void null_inputs_are_noops() {
        store.upsert(null);
        assertThat(store.get(null)).isEmpty();
        assertThat(store.loadLive(null)).isEmpty();
        assertThat(store.countLive(null)).isZero();
        assertThat(store.delete(null)).isFalse();
    }

    @Test void parent_want_id_persists_for_lineage() {
        var parent = Want.active(AGENT, "vague pull", null, 0.4, null);
        store.upsert(parent);

        var child = Want.active(AGENT, "specific shape of vague pull",
            null, 0.6, parent.wantId());
        store.upsert(child);

        var loaded = store.get(child.wantId()).orElseThrow();
        assertThat(loaded.parentWantId()).isEqualTo(parent.wantId());
    }
}
