package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config a template cannot hold must be named, not swallowed.
 *
 * <p>Generated setters sit behind a {@code typeof} guard, because an unguarded
 * {@code item.set_X()} for a setter the base script lacks throws and kills the whole item.
 * That guard is right, and it turns a crash into silence.
 *
 * <p>Live 2026-08-19: asked for an item that searches the library and tells a story aloud,
 * the companion chose {@code scrying-crystal} and expressed the entire request through
 * config — {@code query_mode}, {@code max_paragraphs}, {@code output_style}. That template
 * declares one param and one config key. Everything else vanished without a word, so she
 * believed she had built what was asked for and handed over an item that does nothing.
 */
class DroppedConfigIsNotSilentTest {

    private static StandardItemLibrary library;

    @BeforeAll
    static void setUp() {
        // Gradle runs tests from core/, but scripts/ lives at the project root.
        var scriptsPath = Path.of("scripts");
        if (!scriptsPath.resolve("std/book.js").toFile().exists()) {
            scriptsPath = Path.of("../scripts");
        }
        library = new StandardItemLibrary(scriptsPath);
    }

    private static Map<String, String> config(String... kv) {
        var m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void the_live_case_names_all_three_dropped_keys() {
        var template = library.templates().get("scrying-crystal");
        assertThat(template).as("scrying-crystal must still exist").isNotNull();

        var dropped = StandardItemLibrary.unsupportedConfigKeys(template,
            config("query_mode", "library",
                   "max_paragraphs", "3",
                   "output_style", "narrative"));

        assertThat(dropped).containsExactlyInAnyOrder(
            "query_mode", "max_paragraphs", "output_style");
    }

    @Test
    void a_declared_param_is_not_dropped() {
        var template = library.templates().get("scrying-crystal");
        var paramName = template.params().get(0).name();
        assertThat(StandardItemLibrary.unsupportedConfigKeys(template,
            config(paramName, "the library"))).isEmpty();
    }

    @Test
    void a_default_config_key_is_not_dropped() {
        var template = library.templates().get("scrying-crystal");
        assertThat(template.defaultConfig()).isNotEmpty();
        var key = template.defaultConfig().keySet().iterator().next();
        assertThat(StandardItemLibrary.unsupportedConfigKeys(template, config(key, "x")))
            .isEmpty();
    }

    @Test
    void name_is_always_honoured() {
        // set_name is on every base script, so it is never a drop even though no template
        // declares it.
        var template = library.templates().get("scrying-crystal");
        assertThat(StandardItemLibrary.unsupportedConfigKeys(template,
            config("name", "Library Teller"))).isEmpty();
    }

    @Test
    void nothing_configured_drops_nothing() {
        var template = library.templates().get("simple-book");
        assertThat(StandardItemLibrary.unsupportedConfigKeys(template, Map.of())).isEmpty();
        assertThat(StandardItemLibrary.unsupportedConfigKeys(template, null)).isEmpty();
        assertThat(StandardItemLibrary.unsupportedConfigKeys(null, config("a", "b"))).isEmpty();
    }
}
