package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A backend that needs the local model should ask the node, not assume a port.
 *
 * <p>Staged fresh on 2026-08-21: the install auto-detected its only llama-server on
 * {@code :8201}; every coding backend defaulted to {@code :8200}, where nothing listened.
 * goose answered "Network error: Could not connect to localhost:8200" in 7 seconds. The
 * defaults were one machine's port layout, and production only worked because that
 * machine happened to match.
 */
class TheNodeKnowsWhereItsModelIsTest {

    private static LocalInferenceEndpoint.Prober serving(Map<String, String> byUrl) {
        return url -> byUrl.containsKey(url) ? List.of(byUrl.get(url)) : List.of();
    }

    @Test
    void a_configured_url_wins_when_it_serves_something() {
        var ep = LocalInferenceEndpoint.resolve("http://192.0.2.5:9000",
            serving(Map.of("http://192.0.2.5:9000", "big.gguf", "http://127.0.0.1:8200", "x")));
        assertThat(ep).isPresent();
        assertThat(ep.get().url()).isEqualTo("http://192.0.2.5:9000");
        assertThat(ep.get().modelId()).isEqualTo("big.gguf");
    }

    /** The staging case: nothing on 8200, the real model on 8201. */
    @Test
    void with_nothing_configured_it_finds_the_port_that_is_actually_live() {
        var ep = LocalInferenceEndpoint.resolve(null,
            serving(Map.of("http://127.0.0.1:8201", "/models/wyrdsekai-3.5-4b-v10-q4km.gguf")));
        assertThat(ep).isPresent();
        assertThat(ep.get().url()).isEqualTo("http://127.0.0.1:8201");
        assertThat(ep.get().modelId()).endsWith("4b-v10-q4km.gguf");
    }

    @Test
    void a_configured_url_that_serves_nothing_does_not_block_discovery() {
        var ep = LocalInferenceEndpoint.resolve("http://127.0.0.1:9999",
            serving(Map.of("http://127.0.0.1:8200", "drive.gguf")));
        assertThat(ep).isPresent();
        assertThat(ep.get().url()).isEqualTo("http://127.0.0.1:8200");
    }

    @Test
    void a_node_that_serves_nothing_says_so() {
        assertThat(LocalInferenceEndpoint.resolve(null, url -> List.of())).isEmpty();
    }

    @Test
    void a_prober_that_throws_is_treated_as_nothing_there() {
        assertThat(LocalInferenceEndpoint.resolve(null,
            url -> { throw new RuntimeException("boom"); })).isEmpty();
    }
}
