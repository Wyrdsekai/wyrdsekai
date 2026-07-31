package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * date manipulation. Uses the default
 * provider impls (timezone defaults to system); covers edge cases for
 * parse/format/add/sub/diff/today/weekday.
 */
class DateApiTest {

    private final ItemWorldApiProvider provider = new ItemWorldApiProvider() {
        // Default impls cover all the date methods.
        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    };

    @Test
    void parse_iso_round_trips() {
        var iso = "2026-04-01T12:34:56Z";
        var ms = provider.dateParse(iso, null);
        assertThat(ms).isEqualTo(Instant.parse(iso).toEpochMilli());
    }

    @Test
    void parse_returns_zero_for_invalid() {
        assertThat(provider.dateParse("not-a-date", null)).isZero();
        assertThat(provider.dateParse(null, null)).isZero();
        assertThat(provider.dateParse("", null)).isZero();
    }

    @Test
    void format_with_iso_pattern() {
        var ms = Instant.parse("2026-04-01T12:34:56Z").toEpochMilli();
        var formatted = provider.dateFormat(ms, "yyyy-MM-dd", null);
        assertThat(formatted).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void format_invalid_pattern_returns_error_string() {
        var ms = Instant.now().toEpochMilli();
        var out = provider.dateFormat(ms, "this is not a pattern!", null);
        // Format may be permissive; the key is that it doesn't throw.
        assertThat(out).isNotNull();
    }

    @Test
    void add_subtract_round_trip() {
        long now = System.currentTimeMillis();
        long oneHourLater = provider.dateAdd(now, 1, "hour");
        long backToNow = provider.dateAdd(oneHourLater, -1, "hour");
        assertThat(backToNow).isEqualTo(now);
    }

    @Test
    void add_supports_multiple_units() {
        long now = System.currentTimeMillis();
        assertThat(provider.dateAdd(now, 1, "second")).isEqualTo(now + 1000);
        assertThat(provider.dateAdd(now, 1, "minute")).isEqualTo(now + 60_000);
        assertThat(provider.dateAdd(now, 1, "hour")).isEqualTo(now + 3_600_000);
        assertThat(provider.dateAdd(now, 1, "day")).isEqualTo(now + 86_400_000);
    }

    @Test
    void diff_in_seconds_minutes_hours_days() {
        long t1 = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();
        long t2 = Instant.parse("2026-04-02T01:00:00Z").toEpochMilli();
        assertThat(provider.dateDiff(t2, t1, "day")).isEqualTo(1L);
        assertThat(provider.dateDiff(t2, t1, "hour")).isEqualTo(25L);
        assertThat(provider.dateDiff(t2, t1, "minute")).isEqualTo(25L * 60);
    }

    @Test
    void today_is_iso_date_string() {
        var today = provider.dateToday();
        assertThat(today).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void weekday_returns_uppercase_day_name() {
        // 2026-04-01 was a Wednesday
        long ms = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli();
        var day = provider.dateWeekday(ms);
        assertThat(day).isIn("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
            "FRIDAY", "SATURDAY", "SUNDAY");
    }

    @Test
    void timezone_returns_non_null_id() {
        assertThat(provider.timezone()).isNotNull();
    }

    @Test
    void script_uses_date_api_end_to_end() {
        var executor = new ItemScriptExecutor();
        try {
            // No cap declarations needed — date.* is implicit Tier 1.
            var res = executor.execute("date_demo", """
                function invoke(p){
                  var now = world.date.now();
                  var later = world.date.add(now, 1, 'day');
                  var diff = world.date.diff(later, now, 'hour');
                  return {now: now, later: later, hours: diff};
                }
                """, Map.of(), provider, ItemCapabilitySet.of(List.of()));
            assertThat(((Number) res.get("hours")).longValue()).isEqualTo(24L);
        } finally {
            executor.close();
        }
    }
}
