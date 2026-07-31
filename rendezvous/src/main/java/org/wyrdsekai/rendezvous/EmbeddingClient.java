package org.wyrdsekai.rendezvous;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Text → embedding vector via an OpenAI-compatible
 * {@code /embeddings} endpoint (llama-server, vLLM, SGLang, Ollama —
 * all expose the same shape). The rendezvous calls this once per
 * manifest at publish time and once per search query.
 *
 * <p>The client is deliberately dependency-light: {@code java.net.http}
 * only, no vendored embedding library. Pluggable via env
 * {@code WYRDSEKAI_EMBEDDING_URL} (default: local llama-server). When
 * unset/unreachable the semantic index degrades to the scored
 * substring match in {@link DirectoryStore#searchText}, so agent
 * clients don't need a different code path.</p>
 *
 * <h2>Protocol</h2>
 * <pre>
 * POST {url}/v1/embeddings
 * { "model": "…", "input": "…" }
 * →
 * { "data": [ { "embedding": [0.1, 0.2, …] } ] }
 * </pre>
 */
public final class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String endpoint;
    private final String model;
    private final HttpClient http;

    public EmbeddingClient(String endpoint, String model) {
        this(endpoint, model,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    public EmbeddingClient(String endpoint, String model, HttpClient http) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint required");
        }
        // Normalize: strip trailing slash, accept either the bare base
        // URL or one that already includes /v1/embeddings.
        var url = endpoint.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/v1/embeddings")) url = url.substring(0, url.length() - "/v1/embeddings".length());
        this.endpoint = url + "/v1/embeddings";
        this.model = (model == null || model.isBlank()) ? "default" : model;
        this.http = http;
    }

    /**
     * @return the embedding vector for {@code text}, or empty if the
     *     embedding service is unreachable / returns a malformed body.
     */
    public Optional<float[]> embed(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        var body = "{\"model\":\"" + model + "\",\"input\":"
            + MAPPER.valueToTree(text).toString() + "}";
        try {
            var req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.debug("embedding endpoint returned HTTP {}: {}",
                    resp.statusCode(), resp.body());
                return Optional.empty();
            }
            var node = MAPPER.readTree(resp.body());
            var data = node.path("data");
            if (!data.isArray() || data.size() == 0) return Optional.empty();
            var emb = data.get(0).path("embedding");
            if (!emb.isArray()) return Optional.empty();
            var vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vec[i] = (float) emb.get(i).asDouble();
            }
            return Optional.of(vec);
        } catch (Exception e) {
            log.debug("embed failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** @return true if the client was configured with a URL. */
    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank();
    }
}
