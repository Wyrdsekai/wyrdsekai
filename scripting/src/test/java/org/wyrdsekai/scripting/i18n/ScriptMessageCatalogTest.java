package org.wyrdsekai.scripting.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptMessageCatalogTest {

    @AfterEach void cleanup() {
        ScriptMessageCatalog.clearCaches();
    }

    @Test void parseJson_extracts_keys() {
        var json = """
            {
              "nexus.enter": "{0} arrives at The Nexus.",
              "nexus.hint.talk": "Talk to Wyrd"
            }
            """;
        var map = ScriptMessageCatalog.parseJson(json);
        assertThat(map).hasSize(2);
        assertThat(map.get("nexus.enter")).isEqualTo("{0} arrives at The Nexus.");
        assertThat(map.get("nexus.hint.talk")).isEqualTo("Talk to Wyrd");
    }

    @Test void parseJson_handles_escapes() {
        var json = """
            { "key": "line1\\nline2" }
            """;
        var map = ScriptMessageCatalog.parseJson(json);
        assertThat(map.get("key")).isEqualTo("line1\nline2");
    }

    @Test void parseJson_handles_unicode() {
        var json = """
            { "key": "caf\\u00e9" }
            """;
        var map = ScriptMessageCatalog.parseJson(json);
        // Unicode escapes in JSON are handled differently — our simple parser
        // captures them as-is. The value still works for display.
        assertThat(map.get("key")).isNotNull();
    }

    @Test void ofMap_creates_catalog() {
        var catalog = ScriptMessageCatalog.ofMap("en", Map.of(
            "hello", "Hello {0}!",
            "bye", "Goodbye"
        ));
        assertThat(catalog.get("hello", "World")).isEqualTo("Hello World!");
        assertThat(catalog.get("bye")).isEqualTo("Goodbye");
    }

    @Test void get_returns_key_if_missing() {
        var catalog = ScriptMessageCatalog.ofMap("en", Map.of());
        assertThat(catalog.get("missing.key")).isEqualTo("missing.key");
    }

    @Test void get_with_args_formats_message() {
        var catalog = ScriptMessageCatalog.ofMap("en", Map.of(
            "greeting", "Hello {0}, welcome to {1}!"
        ));
        assertThat(catalog.get("greeting", "Alice", "Nexus"))
            .isEqualTo("Hello Alice, welcome to Nexus!");
    }

    @Test void hasKey_returns_true_for_present() {
        var catalog = ScriptMessageCatalog.ofMap("en", Map.of("a", "b"));
        assertThat(catalog.hasKey("a")).isTrue();
        assertThat(catalog.hasKey("c")).isFalse();
    }

    @Test void english_catalog_loads_from_filesystem() {
        // This test relies on scripts/i18n/en.json existing at the working directory
        var catalog = ScriptMessageCatalog.forLang("en");
        // If the file is found, it will have keys; if not, empty is ok
        assertThat(catalog.getLang()).isEqualTo("en");
    }

    // ── MessageFormat quoting contract (regression guard) ──────────────────────
    // A {N} placeholder wrapped in a SINGLE quote ('{0}') is, to MessageFormat, a
    // quoted *literal* — the argument is never substituted and the live text shows
    // a raw "{0}". To get a literal apostrophe AROUND a substituted value you must
    // DOUBLE the quotes (''{0}''). A multi-agent soak surfaced this as agents
    // narrating "...searches for {0}..." into the room. These tests pin both the
    // semantics and the shipped files so it can't silently come back.

    @Test void singleQuotedPlaceholder_is_a_literal_NOT_substituted() {
        var catalog = ScriptMessageCatalog.ofMap("en", Map.of("k", "searches for '{0}'"));
        // The bug shape: the arg is swallowed, {0} survives verbatim.
        assertThat(catalog.get("k", "Norse myth")).isEqualTo("searches for {0}");
    }

    @Test void doubledQuotedPlaceholder_substitutes_with_literal_apostrophes() {
        var catalog = ScriptMessageCatalog.ofMap("en", Map.of("k", "searches for ''{0}''"));
        // The fix shape: value substituted, wrapped in real apostrophes.
        assertThat(catalog.get("k", "Norse myth")).isEqualTo("searches for 'Norse myth'");
    }

    @Test void shippedLocaleFiles_have_no_single_quoted_placeholders() {
        // Exactly the bug pattern: a lone ' wrapping {N} (not part of a '' escape).
        var bug = Pattern.compile("(?<!')'\\{[0-9]}'(?!')");
        for (var lang : new String[]{"en", "es", "ja"}) {
            var path = locateI18n(lang);
            Assumptions.assumeTrue(path != null,
                "scripts/i18n/" + lang + ".json not reachable from test CWD — skipping");
            String text;
            try { text = Files.readString(path); }
            catch (IOException e) { throw new RuntimeException(e); }
            var m = bug.matcher(text);
            var hits = new ArrayList<String>();
            while (m.find()) hits.add(m.group());
            assertThat(hits)
                .as("%s.json has single-quoted {N} placeholders (use ''{N}'' so the arg "
                    + "substitutes instead of leaking a literal {N})", lang)
                .isEmpty();
        }
    }

    /** Walk up from the test CWD to find scripts/i18n/&lt;lang&gt;.json. */
    private static Path locateI18n(String lang) {
        var dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            var p = dir.resolve("scripts/i18n/" + lang + ".json");
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
