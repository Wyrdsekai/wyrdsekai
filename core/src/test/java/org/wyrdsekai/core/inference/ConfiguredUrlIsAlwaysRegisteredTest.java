package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inference URL the steward configured is registered whatever else is enabled.
 *
 * <p>0.3.2 Windows install test (2026-09-14): `wyrd inference remote http://gpu-node:8200`
 * wrote the URL, the local voice entry stayed enabled, and the server booted with the dead
 * voice as its only backend — the configured URL was consulted only when the backend list
 * was empty.</p>
 */
class ConfiguredUrlIsAlwaysRegisteredTest {

    private static InferenceBackend backend(String name, String url, int priority) {
        return new InferenceBackend.Cloud(name, new InferenceClient(url), priority, List.of());
    }

    @Test
    @DisplayName("a remote URL is added beside an enabled local voice entry")
    void addedBesideVoice() {
        var backends = new ArrayList<InferenceBackend>();
        backends.add(backend("llama-voice", "http://127.0.0.1:8201", 15));
        var probes = new AtomicInteger();
        var added = InferenceConfig.registerConfiguredUrl(backends, "http://198.51.100.20:8200",
            url -> { probes.incrementAndGet(); return Optional.of(backend("llama-server-auto", url, 5)); });
        assertTrue(added);
        assertEquals(1, probes.get());
        assertEquals(2, backends.size());
        assertEquals("http://198.51.100.20:8200", backends.get(1).url());
    }

    @Test
    @DisplayName("a server already listed is not registered twice")
    void notTwice() {
        var backends = new ArrayList<InferenceBackend>();
        backends.add(backend("llama-server", "http://198.51.100.20:8200", 5));
        var probes = new AtomicInteger();
        assertFalse(InferenceConfig.registerConfiguredUrl(backends, "http://198.51.100.20:8200/",
            url -> { probes.incrementAndGet(); return Optional.empty(); }));
        assertEquals(0, probes.get(), "no probe when the server is already there");
        assertEquals(1, backends.size());
    }

    @Test
    @DisplayName("no URL, nothing happens; a URL nothing answers at adds nothing")
    void nullAndDead() {
        var backends = new ArrayList<InferenceBackend>();
        assertFalse(InferenceConfig.registerConfiguredUrl(backends, null, url -> Optional.of(backend("x", url, 5))));
        assertFalse(InferenceConfig.registerConfiguredUrl(backends, " ", url -> Optional.of(backend("x", url, 5))));
        assertFalse(InferenceConfig.registerConfiguredUrl(backends, "http://198.51.100.99:8200", url -> Optional.empty()));
        assertTrue(backends.isEmpty());
    }
}
