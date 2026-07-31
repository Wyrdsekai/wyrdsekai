package org.wyrdsekai.core.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates all critical config paths at startup.
 *
 * <p>Reports all errors at once (not fail-fast on first). ERROR severity
 * prevents startup. WARNING severity logs but allows startup.</p>
 */
public final class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    private ConfigValidator() {}

    public enum Severity { ERROR, WARNING }

    public record ValidationError(String path, String message, Severity severity) {
        @Override
        public String toString() {
            return "[" + severity + "] " + path + ": " + message;
        }
    }

    private static final Set<String> VALID_DB_BACKENDS = Set.of("sqlite", "postgresql");
    private static final Set<String> VALID_INFERENCE_TYPES = Set.of(
        "ollama", "llama-server", "sglang", "vllm", "vllm-mlx", "mlx",
        "claude-cli", "openai", "anthropic", "cloud"
    );

    /** Coding-backend tiers / {@code BackendTier}. */
    private static final Set<String> VALID_CODING_TIERS = Set.of(
        "LOCAL_FREE", "LOCAL_HEAVY", "CLOUD_PAID"
    );

    /**
     * Validate all critical config paths.
     *
     * @param config the resolved HOCON config
     * @return list of errors (empty = valid)
     */
    public static List<ValidationError> validate(Config config) {
        var errors = new ArrayList<ValidationError>();

        // Database
        validateDatabase(config, errors);

        // Inference backends
        validateInference(config, errors);

        // Network ports
        validatePorts(config, errors);

        // Between (inter-node)
        validateBetween(config, errors);

        // Scheduler
        validatePositiveInt(config, "wyrdsekai.scheduler.queue-depth", errors);

        // Resilience
        validatePositiveInt(config, "wyrdsekai.resilience.max-concurrent-inferences", errors);

        // Study
        validateStudy(config, errors);

        // Coding-task delegation backends
        validateCoding(config, errors);

        return errors;
    }

    /**
     * Run validation and log results. Returns true if no fatal errors.
     */
    public static boolean validateAndLog(Config config) {
        var errors = validate(config);
        var fatals = errors.stream().filter(e -> e.severity() == Severity.ERROR).toList();
        var warnings = errors.stream().filter(e -> e.severity() == Severity.WARNING).toList();

        warnings.forEach(w -> log.warn("Config warning: {} — {}", w.path(), w.message()));

        if (!fatals.isEmpty()) {
            fatals.forEach(e -> log.error("Config error: {} — {}", e.path(), e.message()));
            log.error("{} config error(s) found. Fix application.conf and restart.", fatals.size());
            return false;
        }

        if (!warnings.isEmpty()) {
            log.info("Config validation passed with {} warning(s)", warnings.size());
        }
        return true;
    }

    // --- Section validators ---

    private static void validateDatabase(Config config, List<ValidationError> errors) {
        var backend = getString(config, "wyrdsekai.database.backend");
        if (backend != null && !VALID_DB_BACKENDS.contains(backend)) {
            errors.add(new ValidationError("wyrdsekai.database.backend",
                "must be one of " + VALID_DB_BACKENDS + ", got '" + backend + "'",
                Severity.ERROR));
        }

        if ("postgresql".equals(backend)) {
            var url = getString(config, "wyrdsekai.database.postgresql.url");
            if (url != null && !url.startsWith("jdbc:postgresql://")) {
                errors.add(new ValidationError("wyrdsekai.database.postgresql.url",
                    "must be a valid JDBC PostgreSQL URL", Severity.ERROR));
            }
        }
    }

    private static void validateInference(Config config, List<ValidationError> errors) {
        try {
            var backends = config.getConfigList("wyrdsekai.inference.backends");
            if (backends.isEmpty()) {
                errors.add(new ValidationError("wyrdsekai.inference.backends",
                    "at least one inference backend must be defined", Severity.ERROR));
                return;
            }

            for (int i = 0; i < backends.size(); i++) {
                var backend = backends.get(i);
                var path = "wyrdsekai.inference.backends[" + i + "]";

                // Type
                var type = getString(backend, "type");
                if (type != null && !VALID_INFERENCE_TYPES.contains(type)) {
                    errors.add(new ValidationError(path + ".type",
                        "must be one of " + VALID_INFERENCE_TYPES + ", got '" + type + "'",
                        Severity.ERROR));
                }

                // URL — http(s) for llama-server/sglang/ollama, mlx:// for MLX backends
                // ( §"Phase 2" wired mlx:// to InferenceConfig.createMlx
                // which speaks OpenAI-compatible HTTP under the hood but tags the backend
                // type as Mlx so /v1/models is the health probe, not /health).
                var url = getString(backend, "url");
                if (url != null
                        && !url.startsWith("http://")
                        && !url.startsWith("https://")
                        && !url.startsWith("mlx://")) {
                    errors.add(new ValidationError(path + ".url",
                        "must be a valid HTTP(S) or mlx:// URL, got '" + url + "'",
                        Severity.ERROR));
                }
            }
        } catch (ConfigException.Missing _) {
            // No backends configured — might be using env vars
            errors.add(new ValidationError("wyrdsekai.inference.backends",
                "inference backends not configured", Severity.WARNING));
        } catch (ConfigException _) {
            errors.add(new ValidationError("wyrdsekai.inference.backends",
                "invalid format (expected list of objects)", Severity.ERROR));
        }

        // Default model
        var defaultModel = getString(config, "wyrdsekai.inference.default-model");
        if (defaultModel != null && defaultModel.isBlank()) {
            errors.add(new ValidationError("wyrdsekai.inference.default-model",
                "must be non-empty", Severity.WARNING));
        }
    }

    private static void validatePorts(Config config, List<ValidationError> errors) {
        var ports = new HashSet<Integer>();
        var portPaths = List.of("wyrdsekai.telnet.port", "wyrdsekai.ssh.port", "wyrdsekai.http.port");

        for (var path : portPaths) {
            var port = getInt(config, path);
            if (port != null) {
                if (port < 1 || port > 65535) {
                    errors.add(new ValidationError(path,
                        "port must be 1-65535, got " + port, Severity.ERROR));
                } else if (!ports.add(port)) {
                    errors.add(new ValidationError(path,
                        "port " + port + " conflicts with another service", Severity.ERROR));
                }
            }
        }
    }

    private static void validateBetween(Config config, List<ValidationError> errors) {
        var enabled = getBoolean(config, "wyrdsekai.between.enabled");
        if (Boolean.TRUE.equals(enabled)) {
            var natsUrl = getString(config, "wyrdsekai.between.nats.url");
            if (natsUrl != null && !natsUrl.startsWith("nats://")) {
                errors.add(new ValidationError("wyrdsekai.between.nats.url",
                    "must be a valid NATS URL (nats://...)", Severity.ERROR));
            }
        }
    }

    /**
     * Validate the {@code wyrdsekai.coding.*} block
     * §9.1. The schema is intentionally permissive: per-backend objects may
     * carry arbitrary backend-specific keys (max_files_per_task, docker_image,
     * extra_flags…) so future backends slot in without a schema bump. We only
     * validate the small invariant set:
     * <ul>
     *   <li>{@code default_backend} must reference an entry under {@code backends.*}</li>
     *   <li>each {@code backends.<name>.tier}, when present, must be one of
     *       LOCAL_FREE / LOCAL_HEAVY / CLOUD_PAID</li>
     *   <li>each {@code backends.<name>.url}, when present, must look like a URL</li>
     *   <li>{@code fallback_chain} entries that have no matching {@code backends.<name>}
     *       block emit warnings (the policy script will skip them at runtime, but
     *       a typo in the chain is a common config mistake)</li>
     * </ul>
     * Missing entire {@code coding.*} block is fine — Phase 1b backends fall
     * back to "codeplane" via the GraalJS policy script's defaults.
     */
    private static void validateCoding(Config config, List<ValidationError> errors) {
        if (!config.hasPath("wyrdsekai.coding")) {
            // No coding block at all — fine, Phase 1b is optional / additive.
            return;
        }

        var defaultBackend = getString(config, "wyrdsekai.coding.default-backend");
        var fallbackChain = config.hasPath("wyrdsekai.coding.fallback-chain")
            ? safeGetStringList(config, "wyrdsekai.coding.fallback-chain")
            : List.<String>of();

        Set<String> definedBackends = new HashSet<>();
        if (config.hasPath("wyrdsekai.coding.backends")) {
            try {
                var backends = config.getConfig("wyrdsekai.coding.backends");
                // Each top-level key under coding.backends is a backend name.
                for (var entry : backends.root().entrySet()) {
                    var name = entry.getKey();
                    definedBackends.add(name);
                    var path = "wyrdsekai.coding.backends." + name;

                    var tier = getString(config, path + ".tier");
                    if (tier != null && !VALID_CODING_TIERS.contains(tier)) {
                        errors.add(new ValidationError(path + ".tier",
                            "must be one of " + VALID_CODING_TIERS + ", got '" + tier + "'",
                            Severity.ERROR));
                    }

                    var url = getString(config, path + ".url");
                    if (url != null && !url.isBlank()
                            && !url.startsWith("http://")
                            && !url.startsWith("https://")
                            && !url.startsWith("ws://")
                            && !url.startsWith("wss://")) {
                        errors.add(new ValidationError(path + ".url",
                            "must be a valid HTTP(S) or WS(S) URL, got '" + url + "'",
                            Severity.ERROR));
                    }

                    // key-required without a key-chest-slot is meaningless —
                    // surfaces as a warning, not an error, because installs
                    // mid-configure pass through that state.
                    var keyRequired = getBoolean(config, path + ".key-required");
                    var keySlot = getString(config, path + ".key-chest-slot");
                    if (Boolean.TRUE.equals(keyRequired)
                            && (keySlot == null || keySlot.isBlank())) {
                        errors.add(new ValidationError(path + ".key-chest-slot",
                            "key-required=true but no key-chest-slot configured",
                            Severity.WARNING));
                    }
                }
            } catch (ConfigException _) {
                errors.add(new ValidationError("wyrdsekai.coding.backends",
                    "invalid format (expected nested object of backend configs)",
                    Severity.ERROR));
            }
        }

        if (defaultBackend != null && !defaultBackend.isBlank()
                && !definedBackends.isEmpty()
                && !definedBackends.contains(defaultBackend)) {
            errors.add(new ValidationError("wyrdsekai.coding.default-backend",
                "references unknown backend '" + defaultBackend
                    + "' (defined: " + definedBackends + ")",
                Severity.ERROR));
        }

        for (var name : fallbackChain) {
            if (!definedBackends.contains(name)) {
                errors.add(new ValidationError("wyrdsekai.coding.fallback-chain",
                    "entry '" + name + "' has no matching backends.* block — "
                        + "the policy script will skip it at runtime",
                    Severity.WARNING));
            }
        }
    }

    private static List<String> safeGetStringList(Config config, String path) {
        try { return config.getStringList(path); }
        catch (ConfigException _) { return List.of(); }
    }

    private static void validateStudy(Config config, List<ValidationError> errors) {
        var maxDocSize = getInt(config, "wyrdsekai.study.max-document-size");
        if (maxDocSize != null) {
            if (maxDocSize <= 0) {
                errors.add(new ValidationError("wyrdsekai.study.max-document-size",
                    "must be > 0", Severity.ERROR));
            } else if (maxDocSize > 10_000_000) {
                errors.add(new ValidationError("wyrdsekai.study.max-document-size",
                    "value " + maxDocSize + " is very large (>10MB)", Severity.WARNING));
            }
        }
    }

    // --- Helpers ---

    private static void validatePositiveInt(Config config, String path, List<ValidationError> errors) {
        var value = getInt(config, path);
        if (value != null && value <= 0) {
            errors.add(new ValidationError(path, "must be > 0, got " + value, Severity.ERROR));
        }
    }

    private static String getString(Config config, String path) {
        try { return config.getString(path); }
        catch (ConfigException _) { return null; }
    }

    private static Integer getInt(Config config, String path) {
        try { return config.getInt(path); }
        catch (ConfigException _) { return null; }
    }

    private static Boolean getBoolean(Config config, String path) {
        try { return config.getBoolean(path); }
        catch (ConfigException _) { return null; }
    }
}
