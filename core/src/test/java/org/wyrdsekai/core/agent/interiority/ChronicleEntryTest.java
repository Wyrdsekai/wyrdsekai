package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Track-C C5 — {@link ChronicleEntry} record contract.
 *
 * <p>Verifies the data is defensively copied (callers can't mutate stored
 * rows after the fact), the {@link ChronicleEntry#toMap} flat view holds
 * every field the Study furnishing + transport layer expect, and the
 * defensive-defaults (null kind → NOTE; null data → empty map) work so
 * the C5 sleep-pass synthesizer can't crash the {@code completeSleep}
 * critical path on a bad input.</p>
 */
class ChronicleEntryTest {

    @Test
    void agentDid_required() {
        assertThatThrownBy(() -> new ChronicleEntry(
                null, Instant.now(), ChronicleEntry.Kind.NOTE, "x", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agentDid");

        assertThatThrownBy(() -> new ChronicleEntry(
                "  ", Instant.now(), ChronicleEntry.Kind.NOTE, "x", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_defaults_fill_in_safely() {
        var e = new ChronicleEntry("did:x", null, null, null, null);
        assertThat(e.ts()).isNotNull();
        assertThat(e.kind()).isEqualTo(ChronicleEntry.Kind.NOTE);
        assertThat(e.data()).isEmpty();
    }

    @Test
    void data_is_defensively_copied_on_construction() {
        var mutable = new LinkedHashMap<String, Object>();
        mutable.put("k", "v1");
        var entry = new ChronicleEntry("did:x", Instant.now(),
            ChronicleEntry.Kind.RECIPE_RUN, "s", mutable);

        mutable.put("k", "tampered");
        mutable.put("late", "added");

        assertThat(entry.data()).containsEntry("k", "v1").doesNotContainKey("late");
    }

    @Test
    void toMap_round_trips_every_field() {
        var ts = Instant.parse("2026-05-25T10:00:00Z");
        var data = new LinkedHashMap<String, Object>();
        data.put("recipeId", "retrain-classifier-head");
        data.put("status", "SUCCESS");
        data.put("primaryMetric", "val_accuracy");
        data.put("primaryMetricValue", 0.8734);

        var entry = new ChronicleEntry("did:wyrd:companion-x",
            ts, ChronicleEntry.Kind.RECIPE_RUN,
            "Ran retrain-classifier-head and it succeeded.", data);

        var map = entry.toMap();
        assertThat(map).containsEntry("agentDid", "did:wyrd:companion-x")
            .containsEntry("ts", "2026-05-25T10:00:00Z")
            .containsEntry("kind", "RECIPE_RUN")
            .containsEntry("summary",
                "Ran retrain-classifier-head and it succeeded.");

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) map.get("data");
        assertThat(nested).containsEntry("recipeId", "retrain-classifier-head")
            .containsEntry("primaryMetricValue", 0.8734);
    }

    @Test
    void all_kinds_serialize_to_their_name() {
        for (var kind : ChronicleEntry.Kind.values()) {
            var e = new ChronicleEntry("did:x", Instant.now(), kind, null, Map.of());
            assertThat(e.toMap()).containsEntry("kind", kind.name());
        }
    }
}
