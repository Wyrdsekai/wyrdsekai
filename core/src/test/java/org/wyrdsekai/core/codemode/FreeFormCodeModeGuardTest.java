package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2c contract for {@link FreeFormCodeModeGuard}. Each scenario
 * exercises a hallucination pattern we expect or a legitimate JS
 * construct that must NOT be flagged.
 *
 * <p>The known set in these tests mirrors the typical equipped-item
 * baseline: {@code library_card}, {@code searching_glass}, {@code oracle_lens},
 * plus {@code world} and {@code mcp}.
 */
class FreeFormCodeModeGuardTest {

    private static final Set<String> KNOWN = Set.of(
        "library_card", "searching_glass", "oracle_lens", "world", "mcp");

    @Test
    void empty_or_null_script_returns_empty() {
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(null, KNOWN)).isEmpty();
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers("", KNOWN)).isEmpty();
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers("   \n\n", KNOWN)).isEmpty();
    }

    @Test
    void clean_script_returns_empty() {
        var script = """
            const r = library_card.search('mythology');
            console.log(r.length);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void multi_tool_clean_script_returns_empty() {
        var script = """
            const a = library_card.search('greek mythology');
            const b = searching_glass.search('greek mythology');
            const merged = [...a, ...b];
            const seen = new Set();
            const unique = merged.filter(x => {
              if (seen.has(x.title)) return false;
              seen.add(x.title);
              return true;
            });
            console.log(unique.length);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void calendar_hallucination_is_caught() {
        var script = """
            const events = calendar.next(7);
            console.log(events);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN))
            .containsExactly("calendar");
    }

    @Test
    void multiple_hallucinations_returned_in_order() {
        var script = """
            const events = calendar.upcoming();
            const mail = email.summarize(3);
            console.log(events, mail);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN))
            .containsExactly("calendar", "email");
    }

    @Test
    void hallucination_in_string_literal_is_ignored() {
        // The model writes prose mentioning 'calendar' inside a string —
        // that's narration, not a tool call.
        var script = """
            const r = library_card.search('what does calendar.next() mean');
            console.log(r);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void hallucination_in_template_literal_is_ignored() {
        var script = """
            const q = `calendar.next() example`;
            const r = library_card.search(q);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void hallucination_in_line_comment_is_ignored() {
        var script = """
            // Originally tried calendar.next() but switched to library_card.
            const r = library_card.search('schedule');
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void hallucination_in_block_comment_is_ignored() {
        var script = """
            /* email.summarize(3) is what we'd want, but no email tool. */
            const r = library_card.search('summary');
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void local_const_is_not_flagged() {
        var script = """
            const helper = library_card.search('x');
            const merged = helper.concat([]);
            console.log(merged);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void local_let_and_var_are_not_flagged() {
        var script = """
            let acc = [];
            var i = 0;
            while (i < 3) { acc.push(i); i = i + 1; }
            console.log(acc.length);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void function_params_are_not_flagged() {
        var script = """
            function pickFirst(items, n) {
              return items.slice(0, n);
            }
            const r = pickFirst(library_card.search('x'), 3);
            console.log(r);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void arrow_function_params_are_not_flagged() {
        var script = """
            const r = library_card.search('x');
            const titles = r.map(x => x.title);
            const filtered = titles.filter(t => t.length > 0);
            console.log(filtered);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void js_builtins_never_flagged() {
        var script = """
            const r = library_card.search('x');
            const top = Math.min(r.length, 3);
            const out = JSON.stringify(r.slice(0, top));
            const ms = Date.now();
            console.log(out, ms);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void member_chain_does_not_double_flag() {
        // foo.bar.baz() — only 'foo' is a head; 'bar' and 'baz' are members.
        // If foo is unknown, we should report 'foo' once, not 'foo' + 'bar' + 'baz'.
        var script = "calendar.upcoming.first().title";
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN))
            .containsExactly("calendar");
    }

    @Test
    void duplicates_collapse() {
        var script = """
            const a = calendar.next();
            const b = calendar.previous();
            const c = calendar.today();
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN))
            .containsExactly("calendar");
    }

    @Test
    void mixed_clean_and_hallucinated_returns_only_unknown() {
        var script = """
            const lib = library_card.search('mythology');
            const events = calendar.next(7);
            const forecast = oracle_lens.forecast('rain', 6);
            console.log(lib, events, forecast);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN))
            .containsExactly("calendar");
    }

    @Test
    void containsHallucination_convenience() {
        assertThat(FreeFormCodeModeGuard.containsHallucination(
            "library_card.search('x')", KNOWN)).isFalse();
        assertThat(FreeFormCodeModeGuard.containsHallucination(
            "calendar.next()", KNOWN)).isTrue();
    }

    @Test
    void object_destructuring_locals_are_not_flagged() {
        // The 9B drive often destructures search results into named locals.
        // Without this fix the guard flagged `sources` as a hallucination
        // when the model wrote `const { sources, count } = ...`.
        var script = """
            const r = library_card.search('myth');
            const { sources, count } = { sources: r, count: r.length };
            console.log(`${count}: ${sources.map(x => x.title).join(', ')}`);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void array_destructuring_locals_are_not_flagged() {
        var script = """
            const lib = library_card.search('greek');
            const [first, second] = lib;
            console.log(first.title, second.title);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void destructuring_with_aliases_and_defaults_not_flagged() {
        // Aliased + defaulted destructuring — the alias is the actual local name.
        var script = """
            const r = library_card.search('myth');
            const { title: t = 'untitled', summary: s = '' } = r[0];
            console.log(t, s);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void destructuring_with_rest_spread_not_flagged() {
        var script = """
            const r = library_card.search('myth');
            const [head, ...tail] = r;
            console.log(head.title, tail.length);
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }

    @Test
    void typeof_keyword_not_flagged_as_identifier() {
        // `typeof foo === 'string'` doesn't have foo in member-access position
        // — it's an operator over foo. But we want to make sure typeof itself
        // isn't flagged as an unknown identifier in any pathological case.
        var script = """
            const r = library_card.search('x');
            if (typeof r === 'object') console.log('ok');
            """;
        assertThat(FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(script, KNOWN)).isEmpty();
    }
}
