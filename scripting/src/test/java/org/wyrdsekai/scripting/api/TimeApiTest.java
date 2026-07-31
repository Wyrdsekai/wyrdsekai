package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code world.time} wall-clock primitives.
 * Tier 1 across the board; this pins the contract on iso/parse/elapsed/tz.
 */
class TimeApiTest {

    private final ItemWorldApiProvider provider = new ItemWorldApiProvider() {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String topic, String type) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String tgt, String msg) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
        @Override public String timezone() { return "America/Los_Angeles"; }
    };

    private final ItemWorldApi.TimeApi time = new ItemWorldApi.TimeApi(provider);

    @Test
    void now_is_within_a_second_of_system() {
        long sys = System.currentTimeMillis();
        long got = time.now();
        assertThat(Math.abs(sys - got)).isLessThan(1_000L);
    }

    @Test
    void iso_round_trips_through_parse() {
        long t = Instant.parse("2026-05-06T12:34:56Z").toEpochMilli();
        var iso = time.iso(t);
        assertThat(iso).isEqualTo("2026-05-06T12:34:56Z");
        assertThat(time.parse(iso)).isEqualTo(t);
    }

    @Test
    void iso_no_arg_yields_parseable_now() {
        var iso = time.iso();
        long parsed = time.parse(iso);
        assertThat(parsed).isGreaterThan(0L);
        assertThat(Math.abs(System.currentTimeMillis() - parsed)).isLessThan(1_500L);
    }

    @Test
    void parse_invalid_returns_zero() {
        assertThat(time.parse(null)).isZero();
        assertThat(time.parse("")).isZero();
        assertThat(time.parse("not a timestamp")).isZero();
    }

    @Test
    void elapsed_breaks_down_into_units() {
        long then = System.currentTimeMillis() - (3_600_000L + 2_000L);
        var e = time.elapsed(then);
        assertThat(((Number) e.get("ms")).longValue()).isGreaterThanOrEqualTo(3_600_000L + 2_000L);
        assertThat(((Number) e.get("seconds")).longValue()).isGreaterThanOrEqualTo(3_602L);
        assertThat(((Number) e.get("minutes")).longValue()).isGreaterThanOrEqualTo(60L);
        assertThat(((Number) e.get("hours")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(e).containsKey("days");
    }

    @Test
    void tz_returns_provider_timezone() {
        assertThat(time.tz()).isEqualTo("America/Los_Angeles");
    }
}
