package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C5 — {@link ChronicleEntryStore} JDBC contract.
 *
 * <p>Round-trips a fresh SQLite file: every {@link ChronicleEntry.Kind}
 * lands in the {@code chronicle_entries} table and reads back identical
 * (modulo non-string number widening). Recency windows + kind filters
 * are what the Study Chronicle furnishing and C7 recipes console
 * actually call.</p>
 */
class ChronicleEntryStoreTest {

    @TempDir
    Path tmp;

    private ChronicleEntryStore store;

    @BeforeEach
    void setUp() {
        store = new ChronicleEntryStore(
            "jdbc:sqlite:" + tmp.resolve("ce.db").toAbsolutePath());
    }

    @Test
    void append_and_recent_round_trip_a_recipe_run_entry() {
        var ts = Instant.now();
        var data = new LinkedHashMap<String, Object>();
        data.put("recipeId", "retrain-classifier-head");
        data.put("status", "SUCCESS");
        data.put("primaryMetric", "val_accuracy");
        data.put("primaryMetricValue", 0.873);
        data.put("gatesPassed", 2);
        data.put("gatesTotal", 2);
        data.put("deployed", true);
        data.put("rolledBack", false);
        data.put("cadenceTier", "WARMUP");

        store.append(new ChronicleEntry("did:wyrd:companion-a", ts,
            ChronicleEntry.Kind.RECIPE_RUN,
            "Ran retrain-classifier-head and it succeeded.", data));

        var hits = store.recent("did:wyrd:companion-a", Duration.ofDays(1), 10);
        assertThat(hits).hasSize(1);
        var e = hits.get(0);
        assertThat(e.kind()).isEqualTo(ChronicleEntry.Kind.RECIPE_RUN);
        assertThat(e.summary()).contains("retrain-classifier-head");
        assertThat(e.data())
            .containsEntry("recipeId", "retrain-classifier-head")
            .containsEntry("status", "SUCCESS")
            .containsEntry("deployed", true)
            .containsEntry("gatesPassed", 2);
        // Round-trip widens JSON numbers; just check the value.
        assertThat(((Number) e.data().get("primaryMetricValue")).doubleValue())
            .isEqualTo(0.873);
    }

    @Test
    void recent_returns_newest_first() {
        var did = "did:wyrd:c-1";
        var t0 = Instant.now().minusSeconds(60);
        store.append(new ChronicleEntry(did, t0,
            ChronicleEntry.Kind.NOTE, "older", Map.of()));
        store.append(new ChronicleEntry(did, t0.plusSeconds(30),
            ChronicleEntry.Kind.NOTE, "newer", Map.of()));

        var hits = store.recent(did, Duration.ofHours(1), 10);
        assertThat(hits).extracting(ChronicleEntry::summary)
            .containsExactly("newer", "older");
    }

    @Test
    void recent_respects_window_boundary() {
        var did = "did:wyrd:c-2";
        // Outside window — older than 1h.
        store.append(new ChronicleEntry(did,
            Instant.now().minus(Duration.ofHours(2)),
            ChronicleEntry.Kind.NOTE, "stale", Map.of()));
        // Inside window.
        store.append(new ChronicleEntry(did, Instant.now(),
            ChronicleEntry.Kind.NOTE, "fresh", Map.of()));

        assertThat(store.recent(did, Duration.ofHours(1), 10))
            .extracting(ChronicleEntry::summary)
            .containsExactly("fresh");
    }

    @Test
    void recentByKind_filters_to_one_kind() {
        var did = "did:wyrd:c-3";
        store.append(new ChronicleEntry(did, Instant.now(),
            ChronicleEntry.Kind.NOTE, "n", Map.of()));
        store.append(new ChronicleEntry(did, Instant.now(),
            ChronicleEntry.Kind.RECIPE_RUN, "r1", Map.of()));
        store.append(new ChronicleEntry(did, Instant.now(),
            ChronicleEntry.Kind.RECIPE_RUN, "r2", Map.of()));
        store.append(new ChronicleEntry(did, Instant.now(),
            ChronicleEntry.Kind.SUBSTRATE_PATTERN, "s", Map.of()));

        var hits = store.recentByKind(did,
            ChronicleEntry.Kind.RECIPE_RUN, Duration.ofHours(1), 10);
        assertThat(hits).hasSize(2)
            .allMatch(e -> e.kind() == ChronicleEntry.Kind.RECIPE_RUN);
    }

    @Test
    void recent_scopes_to_did() {
        var t = Instant.now();
        store.append(new ChronicleEntry("did:a", t,
            ChronicleEntry.Kind.NOTE, "a", Map.of()));
        store.append(new ChronicleEntry("did:b", t,
            ChronicleEntry.Kind.NOTE, "b", Map.of()));

        assertThat(store.recent("did:a", Duration.ofHours(1), 10))
            .singleElement().extracting(ChronicleEntry::summary).isEqualTo("a");
        assertThat(store.recent("did:b", Duration.ofHours(1), 10))
            .singleElement().extracting(ChronicleEntry::summary).isEqualTo("b");
    }

    @Test
    void singleton_set_get_reset_round_trip() {
        try {
            ChronicleEntryStore.setInstance(store);
            assertThat(ChronicleEntryStore.get()).isSameAs(store);
        } finally {
            ChronicleEntryStore.resetForTests();
        }
        assertThat(ChronicleEntryStore.get()).isNull();
    }
}
