package org.wyrdsekai.core.coding;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.LocalInferenceEndpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command line and the environment must name the same model.
 *
 * <p>{@code buildEnv} was taught to follow the node's real inference; {@code buildArgs}
 * was not — and {@code --model} on the command line wins over {@code GOOSE_MODEL} in the
 * environment. So one half of the same invocation quietly reasserted the compiled-in 9B
 * name at a server serving something else.
 *
 * <p>Found on 2026-08-21 by reading the argv after the env fix "worked": two halves of
 * one call, fixed one at a time, is the shape of most of this day.
 */
class TheArgvAndTheEnvAgreeTest {

    private static final LocalInferenceEndpoint.Endpoint LIVE =
        new LocalInferenceEndpoint.Endpoint("http://127.0.0.1:8201", "the-live-model.gguf");

    @BeforeEach
    void live() { LocalInferenceEndpoint.overrideForTests(LIVE); }

    @AfterEach
    void clear() { LocalInferenceEndpoint.overrideForTests(null); }

    private static GooseBackend backend(String hocon) {
        return new GooseBackend(GooseRuntimeConfig.fromConfig(
            ConfigFactory.parseString(hocon)), null, null);
    }

    @Test
    void an_unconfigured_backend_names_the_live_model_in_both_places() {
        var b = backend("wyrdsekai.coding.backends.goose { enabled = true }");
        var args = b.buildArgs(TaskSpec.create("did:key:z", "host_task", "build a thing"));
        var env = b.buildEnv(new AuthMode.ApiKey("not-required"));

        var i = args.indexOf("--model");
        assertThat(i).as("--model must be present").isGreaterThan(-1);
        assertThat(args.get(i + 1))
            .as("argv wins over the environment, so it must not disagree with it")
            .isEqualTo(LIVE.modelId())
            .isEqualTo(env.get("GOOSE_MODEL"));
        assertThat(env).containsEntry("OPENAI_HOST", LIVE.url());
    }

    @Test
    void a_configured_model_is_honoured_in_both_places() {
        var b = backend("""
            wyrdsekai.coding.backends.goose {
              enabled = true, model = "chosen.gguf", base-url = "http://gpu:9000"
            }
            """);
        var args = b.buildArgs(TaskSpec.create("did:key:z", "host_task", "build a thing"));
        var env = b.buildEnv(new AuthMode.ApiKey("not-required"));
        assertThat(args.get(args.indexOf("--model") + 1)).isEqualTo("chosen.gguf");
        assertThat(env).containsEntry("GOOSE_MODEL", "chosen.gguf");
        assertThat(env).containsEntry("OPENAI_HOST", "http://gpu:9000");
    }
}
