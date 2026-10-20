package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
            (url, name, priority, kind) -> { probes.incrementAndGet(); return Optional.of(backend(name != null ? name : "llama-server-auto", url, priority)); });
        assertTrue(added);
        assertEquals(1, probes.get());
        assertEquals(2, backends.size());
        assertEquals("http://198.51.100.20:8200", backends.get(1).url());
        assertEquals(InferenceConfig.CONFIGURED_URL_PRIORITY, backends.get(1).priority(), "the steward's URL comes first");
    }

    @Test
    @DisplayName("a server already listed is not registered twice; it is probed again and moved first")
    void listedServerMovesFirst() {
        var backends = new ArrayList<InferenceBackend>();
        backends.add(backend("llama-server", "http://127.0.0.1:11525", 5));
        backends.add(new InferenceBackend.Ollama("ollama", new InferenceClient("http://198.51.100.161:11434"), 30, List.of()));
        var probes = new AtomicInteger();
        assertTrue(InferenceConfig.registerConfiguredUrl(backends, "http://198.51.100.161:11434/",
            (url, name, priority, kind) -> { probes.incrementAndGet();
                return Optional.of(new InferenceBackend.Ollama(name, new InferenceClient(url), priority, List.of("gpt-oss:20b"))); }));
        assertEquals(1, probes.get(), "the listed server is asked what it serves");
        assertEquals(2, backends.size(), "not registered twice");
        var ollama = backends.get(1);
        assertEquals("ollama", ollama.name(), "it keeps its name");
        assertEquals(InferenceConfig.CONFIGURED_URL_PRIORITY, ollama.priority(), "and comes first");
        assertEquals(List.of("gpt-oss:20b"), ollama.models());
    }

    @Test
    @DisplayName("a listed server that does not answer keeps its place; a managed local one is left alone")
    void silentOrManaged() {
        var backends = new ArrayList<InferenceBackend>();
        backends.add(new InferenceBackend.Ollama("ollama", new InferenceClient("http://198.51.100.161:11434"), 30, List.of()));
        assertFalse(InferenceConfig.registerConfiguredUrl(backends, "http://198.51.100.161:11434",
            (url, name, priority, kind) -> Optional.empty()));
        assertEquals(30, backends.get(0).priority());

        var managed = new InferenceBackend.LlamaServer("llama-server", new InferenceClient("http://127.0.0.1:11525"), 5,
            List.of(), new LlamaServerManager("llama-server", "/models/x.gguf", 11525, 8192, 0));
        var local = new ArrayList<InferenceBackend>(List.of(managed));
        var probes = new AtomicInteger();
        assertFalse(InferenceConfig.registerConfiguredUrl(local, "http://localhost:11525",
            (url, name, priority, kind) -> { probes.incrementAndGet(); return Optional.empty(); }));
        assertEquals(0, probes.get(), "a managed local server is not probed or replaced");
        assertSame(managed, local.get(0));
    }

    @Test
    @DisplayName("a listed Ollama is asked again as Ollama; an unlisted URL as whatever it is")
    void aListedOllamaStaysOllama() {
        // The household's Ollama, re-probed as llama-server first, answered /v1/models and was
        // registered as MLX (2026-10-19). The probe is told what the entry is.
        var backends = new ArrayList<InferenceBackend>();
        backends.add(new InferenceBackend.Ollama("ollama", new InferenceClient("http://198.51.100.161:11434"), 30, List.of()));
        var kinds = new ArrayList<String>();
        assertTrue(InferenceConfig.registerConfiguredUrl(backends, "http://198.51.100.161:11434",
            (url, name, priority, kind) -> { kinds.add(kind);
                return Optional.of(new InferenceBackend.Ollama(name, new InferenceClient(url), priority, List.of("gpt-oss:20b"))); }));
        assertEquals(List.of("ollama"), kinds);
        var listedLlama = new ArrayList<InferenceBackend>(List.of(
            new InferenceBackend.LlamaServer("llama-server", new InferenceClient("http://198.51.100.20:8200"), 5, List.of(), null)));
        InferenceConfig.registerConfiguredUrl(listedLlama, "http://198.51.100.20:8200",
            (url, name, priority, kind) -> { kinds.add(kind); return Optional.empty(); });
        assertEquals("llama-server", kinds.get(1));
        InferenceConfig.registerConfiguredUrl(new ArrayList<>(), "http://198.51.100.99:8200",
            (url, name, priority, kind) -> { kinds.add(kind); return Optional.empty(); });
        assertNull(kinds.get(2), "nothing listed: probed as whatever it is");
    }

    @Test
    @DisplayName("no URL, nothing happens; a URL nothing answers at adds nothing")
    void nullAndDead() {
        var backends = new ArrayList<InferenceBackend>();
        assertFalse(InferenceConfig.registerConfiguredUrl(backends, null, (url, name, priority, kind) -> Optional.of(backend("x", url, 5))));
        assertFalse(InferenceConfig.registerConfiguredUrl(backends, " ", (url, name, priority, kind) -> Optional.of(backend("x", url, 5))));
        assertFalse(InferenceConfig.registerConfiguredUrl(backends, "http://198.51.100.99:8200", (url, name, priority, kind) -> Optional.empty()));
        assertTrue(backends.isEmpty());
    }
}
