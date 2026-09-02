package org.wyrdsekai.core.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI's three locale catalogues carry the same keys.
 *
 * <p>{@code bin/wyrd} falls back to English for a missing key, so a locale that
 * drifts behind never errors — a Japanese steward just silently gets English
 * for the newest commands. Found 2026-09-01: every {@code wyrd sleepwrite}
 * message had shipped un-keyed for a week, and two new keys landed in en/es
 * only. Nothing checked. This does.
 */
class CliLocaleKeysStayInStepTest {

    private static Path i18nDir() {
        var dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            var candidate = dir.resolve("scripts/i18n");
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("scripts/i18n not found above " + Path.of("").toAbsolutePath());
    }

    @Test
    void every_locale_has_every_key() throws Exception {
        var mapper = new ObjectMapper();
        var dir = i18nDir();
        var en = mapper.readValue(dir.resolve("wyrd_en.json").toFile(), Map.class);
        for (var loc : new String[] {"es", "ja"}) {
            var other = mapper.readValue(dir.resolve("wyrd_" + loc + ".json").toFile(), Map.class);
            var missing = new TreeSet<>(en.keySet()); missing.removeAll(other.keySet());
            var extra = new TreeSet<>(other.keySet()); extra.removeAll(en.keySet());
            assertThat(missing).as("keys in en missing from " + loc).isEmpty();
            assertThat(extra).as("keys in " + loc + " missing from en").isEmpty();
        }
    }
}
