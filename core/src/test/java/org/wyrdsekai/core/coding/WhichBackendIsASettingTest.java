package org.wyrdsekai.core.coding;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Choosing the coding backend must be a decision a steward can make, not a rebuild.
 *
 * <h2>What prompted this</h2>
 * Asked on 2026-08-21 whether the installer should offer CodeZaiku instead of Goose. The
 * answer turned out to be that there was nothing for an installer to set: the default was
 * compiled into five call sites — {@code backendFor(GooseBackend.NAME)} in the
 * companion's {@code dispatch_task}, and three literal {@code List.of("goose", "pi")}
 * chains. Installing CodeZaiku and enabling it in config would have changed nothing at
 * all, and the failure would have been invisible: she would simply have kept using Goose.
 */
class WhichBackendIsASettingTest {

    @AfterEach
    void tearDown() {
        CodingBackendPreference.resetForTests();
    }

    @Test
    void with_nothing_configured_the_chain_is_what_was_compiled_in_before() {
        assertThat(CodingBackendPreference.chain(null))
            .as("an existing node must behave exactly as it did")
            .containsExactlyElementsOf(CodingBackendPreference.BUILT_IN_CHAIN);
    }

    @Test
    void a_single_name_puts_that_backend_first() {
        var config = ConfigFactory.parseString(
            CodingBackendPreference.CONFIG_KEY + " = codezaiku");
        assertThat(CodingBackendPreference.chain(config)).first().isEqualTo("codezaiku");
    }

    /**
     * Preference, not replacement. A node whose chosen backend is not installed keeps
     * working instead of going silent — and the fallback is logged, because "it quietly
     * used a different one" is how a person debugs the wrong backend for an hour.
     */
    @Test
    void naming_one_does_not_drop_the_others_as_fallbacks() {
        var config = ConfigFactory.parseString(
            CodingBackendPreference.CONFIG_KEY + " = codezaiku");
        assertThat(CodingBackendPreference.chain(config))
            .containsSubsequence("codezaiku", "goose");
    }

    @Test
    void an_explicit_chain_is_honoured_in_order() {
        var config = ConfigFactory.parseString(
            CodingBackendPreference.CONFIG_CHAIN_KEY + " = [codezaiku, opencode]");
        assertThat(CodingBackendPreference.chain(config))
            .containsSubsequence("codezaiku", "opencode", "goose");
    }

    @Test
    void naming_goose_explicitly_does_not_list_it_twice() {
        var config = ConfigFactory.parseString(
            CodingBackendPreference.CONFIG_KEY + " = goose");
        var chain = CodingBackendPreference.chain(config);
        assertThat(chain).containsOnlyOnce("goose");
        assertThat(chain).first().isEqualTo("goose");
    }

    @Test
    void names_are_case_and_whitespace_forgiving() {
        var config = ConfigFactory.parseString(
            CodingBackendPreference.CONFIG_KEY + " = \"  CodeZaiku  \"");
        assertThat(CodingBackendPreference.chain(config)).first().isEqualTo("codezaiku");
    }

    /** A malformed setting must not take the dispatch path down with it. */
    @Test
    void an_unreadable_setting_falls_back_rather_than_throwing() {
        var config = ConfigFactory.parseString(
            CodingBackendPreference.CONFIG_CHAIN_KEY + " = 7");
        assertThat(CodingBackendPreference.chain(config))
            .containsExactlyElementsOf(CodingBackendPreference.BUILT_IN_CHAIN);
    }

    /**
     * The setting has to be REACHED, not merely readable. The whole failure class this
     * came out of is code that exists and is never called.
     */
    @Test
    void the_dispatch_path_no_longer_names_a_backend_class() throws Exception {
        var actor = sourceFile("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        var src = Files.readString(actor);
        assertThat(src)
            .as("dispatch_task must resolve through the setting")
            .contains("CodingBackendPreference.resolve()");
        assertThat(src)
            .as("no call site may hardcode the default backend's chain")
            .doesNotContain("List.of(\"goose\", \"pi\")");
    }

    /** Fails loudly rather than skipping — a guard that cannot find its target is not a guard. */
    private static Path sourceFile(String repoRelative) {
        for (var prefix : java.util.List.of("", "../")) {
            var p = Path.of(prefix + repoRelative);
            if (Files.isRegularFile(p)) return p;
            var stripped = Path.of(prefix + repoRelative.replaceFirst("^core/", ""));
            if (Files.isRegularFile(stripped)) return stripped;
        }
        throw new IllegalStateException("source not found from "
            + System.getProperty("user.dir") + " — this guard must never silently pass");
    }
}
