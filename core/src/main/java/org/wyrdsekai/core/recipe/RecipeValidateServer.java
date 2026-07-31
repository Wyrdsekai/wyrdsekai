package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * the reward backend's YAML-validity oracle.
 *
 * <p>The RFT (GRPO) loop scores each rollout: when the drive model emits a
 * {@code shape_recipe} tool call, the loop POSTs the emitted {@code yaml} here and
 * gets back whether it parses and passes the authoring contract. This reuses the
 * EXACT boundary the in-world {@code shape_recipe} action runs through
 * ({@link RecipeParser#parseManifest} → {@link AuthoredRecipeValidator}) so the
 * training reward and the production gate can never drift — a recipe that would be
 * accepted live is exactly the one that earns full reward, and vice versa.</p>
 *
 * <p>This is the oracle ONLY. The act-vs-rest decision math (reward when the model
 * correctly rests at low generativity, penalty when it emits at low generativity)
 * lives in the Python reward fn — — so the decision is
 * auditable in one place. Here we answer one question: is this YAML a valid recipe?</p>
 *
 * <p>Dependency-free beyond what core already has (JDK {@code com.sun.net.httpserver}
 * + Jackson). Two modes:</p>
 * <pre>
 *   # long-running reward server (GRPO calls it per rollout)
 *   java -cp ... RecipeValidateServer --port 8077 [--scripts /path/to/scripts]
 *     POST /validate   body = raw recipe YAML  → {"parsed":..,"parse_error":..,"valid":..,"violations":[..]}
 *     GET  /health                              → {"ok":true}
 *
 *   # one-shot (tests / CLI sanity)
 *   java -cp ... RecipeValidateServer --yaml-file foo.recipe.yaml [--scripts ...]
 * </pre>
 *
 * <p>{@code --scripts} sets the install {@code scripts/} root so the on-disk
 * recipe-callable header check runs (a recipe referencing a missing script is then
 * invalid — what we want for reward). Omit it to skip that pass (structural-only).</p>
 */
public final class RecipeValidateServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecipeValidateServer() {}

    public static void main(String[] args) throws Exception {
        int port = 8077;
        String host = "127.0.0.1";  // loopback by default; --host 0.0.0.0 for cross-box training
        Path scriptsRoot = null;
        String yamlFile = null;
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--scripts" -> scriptsRoot = Path.of(args[++i]);
                case "--yaml-file" -> yamlFile = args[++i];
                default -> { /* ignore unknown */ }
            }
        }

        // One-shot mode: validate a file, print one JSON line, exit.
        if (yamlFile != null) {
            String yaml = Files.readString(Path.of(yamlFile), StandardCharsets.UTF_8);
            System.out.println(JSON.writeValueAsString(validate(yaml, scriptsRoot)));
            return;
        }

        final Path scripts = scriptsRoot;
        var server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/health", ex ->
            respond(ex, 200, Map.of("ok", true)));
        server.createContext("/validate", ex -> {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, Map.of("error", "POST a recipe YAML body"));
                return;
            }
            String yaml = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, 200, validate(yaml, scripts));
        });
        server.start();
        System.out.println("{\"ok\":true,\"listening\":\"" + host + ":" + port
            + "\",\"scripts\":" + (scripts == null ? "null" : "\"" + scripts + "\"") + "}");
        // block forever
        Thread.currentThread().join();
    }

    /**
     * The oracle: parse + authoring-contract check, no write. Mirrors
     * {@link RecipeAuthorService#importRecipe} steps 1–2 exactly.
     *
     * @return ordered map → {parsed, parse_error, valid, violations}
     */
    public static Map<String, Object> validate(String yaml, Path scriptsRoot) {
        var out = new LinkedHashMap<String, Object>();
        if (yaml == null || yaml.isBlank()) {
            out.put("parsed", false);
            out.put("parse_error", "empty recipe");
            out.put("valid", false);
            out.put("violations", List.of());
            return out;
        }
        RecipeManifest manifest;
        try {
            manifest = RecipeParser.parseManifest(yaml);
        } catch (RecipeValidationException e) {
            out.put("parsed", false);
            out.put("parse_error", e.getMessage());
            out.put("valid", false);
            out.put("violations", List.of());
            return out;
        } catch (RuntimeException e) {
            out.put("parsed", false);
            out.put("parse_error", "could not parse recipe: " + e.getMessage());
            out.put("valid", false);
            out.put("violations", List.of());
            return out;
        }
        var reserved = new LinkedHashSet<>(RecipeService.bundledNames());
        var v = AuthoredRecipeValidator.validate(manifest, reserved, scriptsRoot);
        out.put("parsed", true);
        out.put("parse_error", null);
        out.put("valid", v.ok());
        out.put("violations", v.violations());
        return out;
    }

    private static void respond(HttpExchange ex, int code, Map<String, Object> body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
