package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code world.date} timezone-aware
 * date manipulation. Exercises the default-method implementations on
 * {@link ItemWorldApiProvider}; production overrides live in core.
 */
class DateApiTest {

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
        @Override public String timezone() { return "UTC"; }
    };

    private final ItemWorldApi.DateApi date = new ItemWorldApi.DateApi(provider);

    @Test
    void parse_iso_round_trips() {
        long t = date.parse("2026-05-06T12:00:00Z");
        assertThat(t).isEqualTo(Instant.parse("2026-05-06T12:00:00Z").toEpochMilli());
    }

    @Test
    void parse_with_explicit_format_uses_zone() {
        long t = date.parse("2026-05-06 12:00:00", "yyyy-MM-dd HH:mm:ss");
        var expected = ZonedDateTime.of(2026, 5, 6, 12, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli();
        assertThat(t).isEqualTo(expected);
    }

    @Test
    void parse_blank_returns_zero() {
        assertThat(date.parse("")).isZero();
        assertThat(date.parse(null)).isZero();
    }

    @Test
    void format_round_trip() {
        long ms = Instant.parse("2026-05-06T12:34:56Z").toEpochMilli();
        assertThat(date.format(ms, "yyyy-MM-dd")).isEqualTo("2026-05-06");
        assertThat(date.format(ms, "HH:mm")).isEqualTo("12:34");
    }

    @Test
    void format_invalid_pattern_returns_error_string() {
        long ms = Instant.parse("2026-05-06T12:00:00Z").toEpochMilli();
        // Q is unsupported in DateTimeFormatter — surface error rather than throw.
        var out = date.format(ms, "{{unknown");
        assertThat(out).startsWith("[error]");
    }

    @Test
    void add_minutes_hours_days_correct() {
        long base = Instant.parse("2026-05-06T00:00:00Z").toEpochMilli();
        assertThat(date.add(base, 30, "min")).isEqualTo(base + 30L * 60_000L);
        assertThat(date.add(base, 2, "hours")).isEqualTo(base + 2L * 3_600_000L);
        assertThat(date.add(base, 1, "day")).isEqualTo(base + 86_400_000L);
        // Months/years go through ZonedDateTime — May+1 month = June.
        long mo = date.add(base, 1, "month");
        assertThat(date.format(mo, "yyyy-MM-dd")).isEqualTo("2026-06-06");
    }

    @Test
    void sub_is_inverse_of_add() {
        long base = Instant.parse("2026-05-06T00:00:00Z").toEpochMilli();
        assertThat(date.sub(date.add(base, 5, "day"), 5, "day")).isEqualTo(base);
    }

    @Test
    void diff_in_units() {
        long a = Instant.parse("2026-05-06T12:00:00Z").toEpochMilli();
        long b = Instant.parse("2026-05-06T10:00:00Z").toEpochMilli();
        assertThat(date.diff(a, b, "hours")).isEqualTo(2);
        assertThat(date.diff(a, b, "min")).isEqualTo(120);
        assertThat(date.diff(a, b, "ms")).isEqualTo(2L * 3_600_000L);
    }

    @Test
    void today_returns_iso_date() {
        var today = date.today();
        assertThat(today).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void weekday_returns_uppercase_name() {
        // 2026-05-06 is a Wednesday in UTC.
        long ms = Instant.parse("2026-05-06T12:00:00Z").toEpochMilli();
        assertThat(date.weekday(ms)).isEqualTo("WEDNESDAY");
    }

    @Test
    void now_returns_recent_epoch() {
        long now = date.now();
        long sysNow = System.currentTimeMillis();
        assertThat(Math.abs(sysNow - now)).isLessThan(1_000L);
    }
}
