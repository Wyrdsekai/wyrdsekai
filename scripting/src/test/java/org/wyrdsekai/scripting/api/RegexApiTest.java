package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bounded regex with 100ms timeout +
 * compiled-pattern cache. ReDoS-class inputs must not hang the script
 * thread for longer than the timeout window.
 */
class RegexApiTest {

    private final ItemWorldApi.RegexApi regex = new ItemWorldApi.RegexApi();

    @Test
    void simple_match_returns_groups_and_index() {
        var matches = regex.match("hello world", "(\\w+) (\\w+)", "");
        assertThat(matches).hasSize(1);
        var m = matches.getFirst();
        assertThat(m.get("match")).isEqualTo("hello world");
        assertThat(m.get("index")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        var groups = (List<String>) m.get("groups");
        assertThat(groups).containsExactly("hello", "world");
    }

    @Test
    void global_flag_yields_all_matches() {
        var matches = regex.match("foo bar foo baz", "foo", "g");
        assertThat(matches).hasSize(2);
        assertThat(matches.getFirst().get("index")).isEqualTo(0);
        assertThat(matches.get(1).get("index")).isEqualTo(8);
    }

    @Test
    void case_insensitive_flag_works() {
        var matches = regex.match("HELLO", "hello", "i");
        assertThat(matches).hasSize(1);
    }

    @Test
    void replace_substitutes_all_when_global() {
        assertThat(regex.replace("foo bar foo", "foo", "X", "g"))
            .isEqualTo("X bar X");
    }

    @Test
    void replace_substitutes_first_when_not_global() {
        assertThat(regex.replace("foo bar foo", "foo", "X", ""))
            .isEqualTo("X bar foo");
    }

    @Test
    void split_breaks_on_pattern() {
        assertThat(regex.split("a,b,c,d", ",", ""))
            .containsExactly("a", "b", "c", "d");
    }

    @Test
    void empty_or_null_inputs_are_handled() {
        assertThat(regex.match(null, "foo", "")).isEmpty();
        assertThat(regex.match("text", null, "")).isEmpty();
        assertThat(regex.replace(null, "foo", "bar", "")).isNull();
        assertThat(regex.split(null, ",", "")).isEmpty();
    }

    @Test
    void catastrophic_pattern_times_out_within_window() {
        // Classic ReDoS: (a+)+b on "aaaa...!" backtracks exponentially.
        // Without the timeout this hangs; with it, we get a regex_timeout entry.
        var pattern = "(a+)+b";
        var input = "a".repeat(40) + "!";  // no 'b' → unbounded backtrack
        long start = System.currentTimeMillis();
        var matches = regex.match(input, pattern, "");
        long elapsed = System.currentTimeMillis() - start;

        // Either we got a structured timeout error, or the regex completed
        // without a match (input unbounded, would never match anyway).
        // The key invariant is: we returned in < 1 second.
        assertThat(elapsed).isLessThan(1000L);
        if (!matches.isEmpty()) {
            var first = matches.getFirst();
            // If timeout fired, it's surfaced as a structured error.
            if (first.containsKey("error")) {
                assertThat((String) first.get("error")).isEqualTo("regex_timeout");
            }
        }
    }

    @Test
    void compiled_patterns_are_cached() {
        // Repeating the same pattern many times should not allocate new Patterns
        // — we just check the API works repeatedly.
        for (int i = 0; i < 1000; i++) {
            var matches = regex.match("hello " + i, "\\d+", "");
            assertThat(matches).hasSize(1);
        }
    }

    @Test
    void replace_handles_special_replacement_chars() {
        // The replacement is treated literally (Matcher.quoteReplacement),
        // so $1 in the replacement is not a backreference.
        assertThat(regex.replace("hello", "(l)", "$1", "g"))
            .isEqualTo("he$1$1o");
    }
}
