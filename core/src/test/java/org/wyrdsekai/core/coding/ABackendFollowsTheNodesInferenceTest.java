package org.wyrdsekai.core.coding;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.LocalInferenceEndpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An unconfigured endpoint follows the node; a configured one is honoured.
 *
 * <p>Every coding backend shipped {@code localhost:8200} and a 9B model name as its
 * default. On a fresh node with one 4B on {@code :8201} — which the install itself had
 * already detected — that sent goose to a dead port and reported success. This pins the
 * three cases: configured wins; unconfigured follows the live endpoint; nothing live
 * falls back to the compiled default rather than throwing.
 */
class ABackendFollowsTheNodesInferenceTest {

    private static final LocalInferenceEndpoint.Endpoint LIVE =
        new LocalInferenceEndpoint.Endpoint("http://127.0.0.1:8201",
            "/models/wyrdsekai-3.5-4b-v10-q4km.gguf");

    @BeforeEach
    void live() { LocalInferenceEndpoint.overrideForTests(LIVE); }

    @AfterEach
    void clear() { LocalInferenceEndpoint.overrideForTests(null); }

    @Test
    void goose_unconfigured_follows_the_node() {
        var cfg = GooseRuntimeConfig.fromConfig(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.goose { enabled = true }"));
        assertThat(cfg.baseUrlFromConfig()).isFalse();
        assertThat(cfg.effectiveBaseUrl()).isEqualTo("http://127.0.0.1:8201");
        assertThat(cfg.effectiveModel()).isEqualTo(LIVE.modelId());
    }

    @Test
    void goose_configured_is_honoured() {
        var cfg = GooseRuntimeConfig.fromConfig(ConfigFactory.parseString("""
            wyrdsekai.coding.backends.goose {
              base-url = "http://gpu-box:8200", model = "my-9b"
            }
            """));
        assertThat(cfg.baseUrlFromConfig()).isTrue();
        assertThat(cfg.effectiveBaseUrl()).isEqualTo("http://gpu-box:8200");
        assertThat(cfg.effectiveModel()).isEqualTo("my-9b");
    }

    /** The env goose actually receives carries the resolved endpoint. */
    @Test
    void goose_env_points_at_the_live_model() {
        var backend = new GooseBackend(GooseRuntimeConfig.fromConfig(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.goose { enabled = true }")), null, null);
        var env = backend.buildEnv(new AuthMode.ApiKey("not-required"));
        assertThat(env).containsEntry("OPENAI_HOST", "http://127.0.0.1:8201");
        assertThat(env).containsEntry("GOOSE_MODEL", LIVE.modelId());
    }

    @Test
    void opencode_unconfigured_follows_the_node_with_its_v1_suffix() {
        var cfg = OpenCodeRuntimeConfig.fromConfig(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.opencode { enabled = true }"));
        assertThat(cfg.effectiveBaseUrl()).isEqualTo("http://127.0.0.1:8201/v1");
        assertThat(cfg.effectiveModel()).isEqualTo(LIVE.modelId());
    }

    @Test
    void codezaiku_advertises_the_live_endpoint_as_its_default() {
        var cfg = CodeZaikuRuntimeConfig.fromConfig(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.codezaiku { enabled = true }"));
        assertThat(cfg.driveUrlFromConfig()).isFalse();
        assertThat(cfg.effectiveDriveUrl()).isEqualTo("http://127.0.0.1:8201");
        var env = new CodeZaikuBackend(cfg, null).buildEnv();
        assertThat(env).containsEntry("CODEZAIKU_DRIVE_DEFAULT", "http://127.0.0.1:8201");
    }

    /** Nothing live at all: the old default, not an exception. */
    @Test
    void with_nothing_live_the_compiled_default_remains() {
        LocalInferenceEndpoint.overrideForTests(null);
        // No override and no live probe is environment-dependent; assert only the
        // contract that matters — it never throws and always answers a URL.
        var cfg = GooseRuntimeConfig.defaults();
        assertThat(cfg.effectiveBaseUrl()).isNotBlank();
        assertThat(cfg.effectiveModel()).isNotBlank();
    }

    /** The back-compat constructor: passing the compiled default is not a choice. */
    @Test
    void passing_the_default_through_the_old_constructor_is_not_a_choice() {
        var cfg = GooseRuntimeConfig.defaults();
        assertThat(cfg.baseUrlFromConfig()).isFalse();
        assertThat(cfg.modelFromConfig()).isFalse();
        var chosen = new GooseRuntimeConfig(true, "goose", "openai", "m", "http://x:1",
            null, null);
        assertThat(chosen.baseUrlFromConfig()).isTrue();
    }
}
