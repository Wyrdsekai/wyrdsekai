package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Heuristic + override resolver for "how do I run this workspace".
 * Used by every backend
 * that opts into {@link CodingTaskBackend#runArtifact}.
 *
 * <p>Resolution order (first match wins):</p>
 * <ol>
 *   <li>Explicit override via {@link #fromMetadata}: backend stamps
 *       {@code metadata.entrypoint = "python3 main.py"} on the
 *       {@link SourceArtifact}; we trust it verbatim.</li>
 *   <li>{@code Makefile} with a {@code run:} target → {@code make run}.</li>
 *   <li>{@code package.json} with {@code scripts.start} → {@code npm start}.</li>
 *   <li>{@code package.json} with {@code main} → {@code node <main>}.</li>
 *   <li>{@code Cargo.toml} → {@code cargo run --quiet}.</li>
 *   <li>{@code go.mod} → {@code go run .}.</li>
 *   <li>{@code main.py} → {@code python3 main.py}.</li>
 *   <li>{@code index.js} → {@code node index.js}.</li>
 *   <li>{@code main.go} → {@code go run main.go}.</li>
 *   <li>None matched → {@link Optional#empty()}; caller surfaces a
 *       {@code no_entrypoint} error instead of guessing.</li>
 * </ol>
 *
 * <p>The detector is intentionally conservative — false positives run
 * code the steward didn't expect, false negatives surface a clear
 * error message they can fix by stamping {@code entrypoint} on the
 * artifact's metadata.</p>
 */
public final class EntrypointDetector {

    private static final Logger log = LoggerFactory.getLogger(EntrypointDetector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntrypointDetector() {}

    /**
     * Read an explicit entrypoint override stamped on a backend's
     * {@code SourceArtifact.backendMetadata}. Returns empty when
     * absent or blank.
     */
    public static Optional<List<String>> fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) return Optional.empty();
        var raw = metadata.get("entrypoint");
        if (raw == null) return Optional.empty();
        var s = String.valueOf(raw).trim();
        if (s.isEmpty()) return Optional.empty();
        return Optional.of(splitCommand(s));
    }

    /**
     * Walk a workspace directory, applying the heuristic above.
     * Returns the argv (split for {@link ProcessBuilder}) or empty.
     */
    public static Optional<List<String>> detect(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return Optional.empty();
        }
        try {
            // Makefile run
            var makefile = workspace.resolve("Makefile");
            if (Files.isRegularFile(makefile)) {
                var content = Files.readString(makefile);
                if (content.lines().anyMatch(l -> l.startsWith("run:"))) {
                    return Optional.of(List.of("make", "run"));
                }
            }
            // package.json
            var pkg = workspace.resolve("package.json");
            if (Files.isRegularFile(pkg)) {
                try {
                    JsonNode node = MAPPER.readTree(pkg.toFile());
                    var scripts = node.path("scripts");
                    if (scripts.path("start").isTextual() && !scripts.path("start").asText().isBlank()) {
                        return Optional.of(List.of("npm", "start", "--silent"));
                    }
                    var main = node.path("main");
                    if (main.isTextual() && !main.asText().isBlank()) {
                        return Optional.of(List.of("node", main.asText()));
                    }
                } catch (Exception e) {
                    log.debug("EntrypointDetector: package.json parse failed at {}: {}",
                        pkg, e.getMessage());
                }
            }
            // Cargo.toml
            if (Files.isRegularFile(workspace.resolve("Cargo.toml"))) {
                return Optional.of(List.of("cargo", "run", "--quiet"));
            }
            // go.mod or main.go
            if (Files.isRegularFile(workspace.resolve("go.mod"))) {
                return Optional.of(List.of("go", "run", "."));
            }
            // Bare entrypoint files.
            if (Files.isRegularFile(workspace.resolve("main.py"))) {
                return Optional.of(List.of("python3", "main.py"));
            }
            if (Files.isRegularFile(workspace.resolve("index.js"))) {
                return Optional.of(List.of("node", "index.js"));
            }
            if (Files.isRegularFile(workspace.resolve("main.go"))) {
                return Optional.of(List.of("go", "run", "main.go"));
            }
        } catch (Exception e) {
            log.debug("EntrypointDetector failed for {}: {}", workspace, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Split a shell-style command string into argv. Naive whitespace
     * split — no quoting / escape handling. Matches what most steward
     * overrides will look like ({@code python3 main.py},
     * {@code npx tsx index.ts}); anything fancier should use a
     * different field (e.g. a JSON array) which we can add later.
     */
    static List<String> splitCommand(String s) {
        var parts = new ArrayList<String>();
        for (var p : s.split("\\s+")) {
            if (!p.isEmpty()) parts.add(p);
        }
        return List.copyOf(parts);
    }
}
