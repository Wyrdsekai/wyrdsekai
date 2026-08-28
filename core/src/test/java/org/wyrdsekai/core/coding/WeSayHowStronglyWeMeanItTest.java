package org.wyrdsekai.core.coding;

import org.wyrdsekai.core.inference.LocalInferenceEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A value the operator chose and a value we guessed must not look the same to CodeZaiku.
 *
 * <h2>The defect, as the CodeZaiku team reported it</h2>
 * {@code CodeZaikuRuntimeConfig}'s compact constructor filled unset values with defaults
 * and, in doing so, erased the fact that they were defaults. {@code buildEnv()} could then
 * only inject {@code CODEZAIKU_DRIVE} unconditionally, and CodeZaiku has no choice but to
 * treat the environment as authoritative.
 *
 * <p>Consequence: a machine where someone installed and configured CodeZaiku against a
 * hosted endpoint was silently redirected to {@code localhost:8200} — and nothing said so,
 * because {@code codezaiku doctor} reads the config FILE. It reported healthy while the
 * summoned run went somewhere else.
 *
 * <p>Their precedence, shipped in 55ab5182: environment override → the machine's config
 * file → {@code <KEY>_DEFAULT} → built-in. So the fix is to say how strongly we mean it.
 */
class WeSayHowStronglyWeMeanItTest {
    @BeforeEach
    void pinNothingLive() {
        // These tests assert the compiled-in FALLBACK endpoint. On a developer box with
        // a live local model the resolver would (correctly) find it instead.
        LocalInferenceEndpoint.pinNothingLiveForTests(true);
    }

    @AfterEach
    void unpinNothingLive() {
        LocalInferenceEndpoint.pinNothingLiveForTests(false);
    }


    private static CodeZaikuRuntimeConfig from(String hocon) {
        return CodeZaikuRuntimeConfig.fromConfig(ConfigFactory.parseString(hocon));
    }

    @Test
    void a_value_the_operator_chose_is_authoritative() {
        var backend = new CodeZaikuBackend(from("""
            wyrdsekai.coding.backends.codezaiku {
              drive-url = "https://drive.example.com/v1"
              model = "big-model"
            }
            """), null);
        var env = backend.buildEnv();
        assertThat(env).containsEntry("CODEZAIKU_DRIVE", "https://drive.example.com/v1");
        assertThat(env).containsEntry("CODEZAIKU_MODEL", "big-model");
        assertThat(env).doesNotContainKey("CODEZAIKU_DRIVE_DEFAULT");
    }

    /**
     * The whole point: a machine whose owner configured CodeZaiku themselves must keep
     * their endpoint. We still offer ours, but only as the level that loses to their file.
     */
    @Test
    void a_value_we_merely_defaulted_yields_to_the_machines_own_config() {
        var env = new CodeZaikuBackend(
            from("wyrdsekai.coding.backends.codezaiku { enabled = true }"), null).buildEnv();
        assertThat(env)
            .as("unset must never claim authority over the operator's own config file")
            .doesNotContainKey("CODEZAIKU_DRIVE")
            .doesNotContainKey("CODEZAIKU_MODEL");
        assertThat(env).containsEntry("CODEZAIKU_DRIVE_DEFAULT",
            CodeZaikuRuntimeConfig.DEFAULT_DRIVE_URL);
        assertThat(env).containsEntry("CODEZAIKU_MODEL_DEFAULT",
            CodeZaikuRuntimeConfig.DEFAULT_MODEL);
    }

    /** Each setting is decided on its own — one chosen value does not pin the other. */
    @Test
    void the_two_settings_are_decided_independently() {
        var env = new CodeZaikuBackend(from("""
            wyrdsekai.coding.backends.codezaiku { model = "big-model" }
            """), null).buildEnv();
        assertThat(env).containsEntry("CODEZAIKU_MODEL", "big-model");
        assertThat(env).containsKey("CODEZAIKU_DRIVE_DEFAULT");
        assertThat(env).doesNotContainKey("CODEZAIKU_DRIVE");
    }

    /**
     * A present-but-blank setting is not a choice. CodeZaiku treats blank as absent at
     * every level, so claiming authority for one would pin nothing while looking
     * authoritative from here.
     */
    @Test
    void a_blank_setting_is_not_a_choice() {
        var env = new CodeZaikuBackend(from("""
            wyrdsekai.coding.backends.codezaiku { drive-url = "" }
            """), null).buildEnv();
        assertThat(env).doesNotContainKey("CODEZAIKU_DRIVE");
        assertThat(env).containsEntry("CODEZAIKU_DRIVE_DEFAULT",
            CodeZaikuRuntimeConfig.DEFAULT_DRIVE_URL);
    }

    /** And nothing we emit is ever blank, at either level. */
    @Test
    void no_key_is_ever_emitted_empty() {
        for (var hocon : java.util.List.of(
                "wyrdsekai.coding.backends.codezaiku { enabled = true }",
                "wyrdsekai.coding.backends.codezaiku { drive-url = \"\", model = \"\" }",
                "wyrdsekai.coding.backends.codezaiku { drive-url = \"http://x\" }")) {
            var env = new CodeZaikuBackend(from(hocon), null).buildEnv();
            assertThat(env.values())
                .as("blank is treated as absent by CodeZaiku — emitting one pins nothing")
                .allSatisfy(v -> assertThat(v).isNotBlank());
        }
    }

    /**
     * Constructing directly with a value IS choosing it — typing a URL into a
     * constructor is a deliberate act. Only an absent or blank one falls to the default
     * level, which is what {@link CodeZaikuRuntimeConfig#defaults()} does.
     */
    @Test
    void passing_a_value_directly_counts_as_choosing_it() {
        var chosen = new CodeZaikuRuntimeConfig(true, "codezaiku", "http://somewhere",
            "some-model", null, null);
        assertThat(chosen.driveUrlFromConfig()).isTrue();
        assertThat(chosen.modelFromConfig()).isTrue();

        var unset = CodeZaikuRuntimeConfig.defaults();
        assertThat(unset.driveUrlFromConfig()).isFalse();
        assertThat(unset.modelFromConfig()).isFalse();
        assertThat(unset.driveUrl()).isEqualTo(CodeZaikuRuntimeConfig.DEFAULT_DRIVE_URL);
    }
}
