package org.wyrdsekai.core.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.HotReloadableConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Maps capability names ("reasoning", "coding", "quick") to inference backends.
 * Agents request capabilities instead of specific models; the registry resolves
 * to the best available backend based on priority and tier constraints.
 *
 * <p>Tier hierarchy: "local" &lt; "household" &lt; "cloud".
 * When resolving with a maxTier, only backends at or below that tier are considered.
 *
 * <p>Thread-safe: backed by ConcurrentHashMap, safe for concurrent registration
 * and resolution from multiple actors.
 */
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);

    /** Tier ordering: lower = more local. */
    private static final Map<String, Integer> TIER_ORDER = Map.of(
        "local", 0,
        "household", 1,
        "cloud", 2
    );

    /**
     * A single capability entry mapping a capability name to a backend + model.
     *
     * @param capability   Capability name ("reasoning", "coding", "quick", "default", etc.)
     * @param backendName  References an {@link InferenceBackend} by name
     * @param model        Model to use on that backend
     * @param tier         "local", "household", or "cloud"
     * @param priority     Lower = preferred. Used to pick the best entry when multiple exist.
     */
    public record CapabilityEntry(
        String capability,
        String backendName,
        String model,
        String tier,
        int priority
    ) {
        public CapabilityEntry {
            Objects.requireNonNull(capability, "capability must not be null");
            Objects.requireNonNull(backendName, "backendName must not be null");
            Objects.requireNonNull(model, "model must not be null");
            Objects.requireNonNull(tier, "tier must not be null");
            if (!TIER_ORDER.containsKey(tier)) {
                throw new IllegalArgumentException("Unknown tier: " + tier
                    + ". Must be one of: " + TIER_ORDER.keySet());
            }
        }
    }

    /** capability -> list of entries, kept sorted by priority (ascending). */
    private final Map<String, List<CapabilityEntry>> capabilities = new ConcurrentHashMap<>();

    /**
     * Process-wide accessor wired at startup so non-actor callers (prompt
     * assemblers, scripted item glue, capability gates) can check
     * {@code hasCapableBackend(...)} without taking a dependency on the
     * {@link InferenceRouter} actor's protocol.
     *
     * <p>Set once by the bootstrapper after constructing the registry; tests
     * call {@link #setActive(CapabilityRegistry)} (and clear with null on
     * teardown) so they can stub a registry with a known capability set.
     * Returns null if not yet wired — callers must treat that as "no
     * capabilities known" and gate-fail closed.
     */
    private static volatile CapabilityRegistry active;

    public static CapabilityRegistry getActive() { return active; }
    public static void setActive(CapabilityRegistry registry) { active = registry; }

    /**
     * Register a capability entry. If multiple entries share the same capability,
     * they are ordered by priority (lower = more preferred).
     */
    public void register(CapabilityEntry entry) {
        capabilities.compute(entry.capability(), (cap, existing) -> {
            var list = existing != null ? new ArrayList<>(existing) : new ArrayList<CapabilityEntry>();
            list.add(entry);
            list.sort(Comparator.comparingInt(CapabilityEntry::priority));
            return List.copyOf(list);
        });
        log.debug("Registered capability '{}' → backend '{}', model '{}', tier '{}', priority {}",
            entry.capability(), entry.backendName(), entry.model(), entry.tier(), entry.priority());
    }

    /**
     * Find the best (lowest priority) backend for a capability.
     *
     * @param capability The capability name
     * @return The highest-priority entry, or empty if no entry registered
     */
    public Optional<CapabilityEntry> resolve(String capability) {
        var entries = capabilities.get(capability);
        if (entries == null || entries.isEmpty()) return Optional.empty();
        return Optional.of(entries.getFirst());
    }

    /**
     * Find the best backend for a capability within a tier limit.
     *
     * <ul>
     *   <li>"local" — only returns entries with tier "local"</li>
     *   <li>"household" — returns entries with tier "local" or "household"</li>
     *   <li>"cloud" — returns all entries</li>
     * </ul>
     *
     * @param capability The capability name
     * @param maxTier    Maximum tier to consider
     * @return The highest-priority entry within the tier constraint, or empty
     */
    public Optional<CapabilityEntry> resolve(String capability, String maxTier) {
        var entries = capabilities.get(capability);
        if (entries == null || entries.isEmpty()) return Optional.empty();

        int maxTierOrder = TIER_ORDER.getOrDefault(maxTier, 2);
        return entries.stream()
            .filter(e -> TIER_ORDER.getOrDefault(e.tier(), 2) <= maxTierOrder)
            .findFirst(); // already sorted by priority
    }

    /**
     * @return All registered capability names.
     */
    public Set<String> availableCapabilities() {
        return Collections.unmodifiableSet(capabilities.keySet());
    }

    /**
     * @return All entries for a given capability, ordered by priority.
     */
    public List<CapabilityEntry> entries(String capability) {
        var entries = capabilities.get(capability);
        return entries != null ? entries : List.of();
    }

    /**
     * Build a human-readable summary of available capabilities for prompt injection.
     * Format:
     * <pre>
     * ## Available Reasoning Tools
     * - reasoning: Deep analysis (household, model-name)
     * - coding: Code review (local, coder-model)
     * - quick: Simple tasks (local, small-model)
     * </pre>
     *
     * @return Formatted string, or null if no capabilities registered
     */
    public String buildPromptContext() {
        if (capabilities.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("## Available Reasoning Tools\n");

        // Sort capabilities alphabetically for deterministic output
        var sorted = new TreeSet<>(capabilities.keySet());
        for (var cap : sorted) {
            var entries = capabilities.get(cap);
            if (entries == null || entries.isEmpty()) continue;
            var best = entries.getFirst();
            sb.append("- ").append(cap).append(": ")
              .append(best.tier()).append(" ").append(best.model())
              .append("\n");
        }

        return sb.toString();
    }

    /**
     * Auto-detect capabilities from a list of backends using simple heuristics.
     *
     * <ul>
     *   <li>Model name contains "coder" or "code" → "coding" capability</li>
     *   <li>Model name contains a size indicator &gt; 30B → "reasoning" capability</li>
     *   <li>Model name contains a size indicator &lt; 4B → "quick" capability</li>
     *   <li>All models → "default" capability</li>
     * </ul>
     *
     * Tier assignment:
     * <ul>
     *   <li>llama-server, ollama → "local"</li>
     *   <li>vllm, sglang → "household"</li>
     *   <li>cloud, claude-cli → "cloud"</li>
     * </ul>
     *
     * @param backends The configured inference backends
     * @return A pre-populated registry
     */
    public static CapabilityRegistry fromBackends(List<InferenceBackend> backends) {
        var registry = new CapabilityRegistry();

        for (var backend : backends) {
            var tier = inferTier(backend);
            var models = backend.models();

            if (models.isEmpty()) {
                // Backend with no specific models — register as default
                registry.register(new CapabilityEntry(
                    "default", backend.name(), "default", tier, backend.priority()));
                continue;
            }

            for (var model : models) {
                var modelLower = model.toLowerCase(Locale.ROOT);

                // Always register as default
                registry.register(new CapabilityEntry(
                    "default", backend.name(), model, tier, backend.priority()));

                // Heuristic: coding
                if (modelLower.contains("coder") || modelLower.contains("codestral")
                        || modelLower.contains("deepseek-coder")
                        || modelLower.contains("starcoder")) {
                    registry.register(new CapabilityEntry(
                        "coding", backend.name(), model, tier, backend.priority()));
                }

                // Heuristic: extract parameter size from model name
                var sizeB = extractSizeBillions(modelLower);
                if (sizeB > 30) {
                    registry.register(new CapabilityEntry(
                        "reasoning", backend.name(), model, tier, backend.priority()));
                }
                // "quick" catches small models — <=4B (covers our 4B voice model).
                if (sizeB > 0 && sizeB <= 4) {
                    registry.register(new CapabilityEntry(
                        "quick", backend.name(), model, tier, backend.priority()));
                }

                // Heuristic: tool_dispatch (≤1B models — excellent at structured tool calls)
                if (sizeB > 0 && sizeB <= 1) {
                    registry.register(new CapabilityEntry(
                        "tool_dispatch", backend.name(), model, tier, backend.priority()));
                }

                // Heuristic: summarize (1-4B models — fast, good for extractive tasks)
                if (sizeB > 0 && sizeB <= 4) {
                    registry.register(new CapabilityEntry(
                        "summarize", backend.name(), model, tier, backend.priority()));
                }

                // Heuristic: analysis (large models or those named "analyst"/"analysis")
                if (sizeB > 14 || modelLower.contains("analy")) {
                    registry.register(new CapabilityEntry(
                        "analysis", backend.name(), model, tier, backend.priority()));
                }

                // capability gate for the typed-namespace
                // prompt block. Backends declare whether they can write JS that
                // matches the spec's namespace shape:
                //   * Cloud / ClaudeCli — always true (Anthropic / OpenAI handle JS fine).
                //   * Local "drive" 9B (e.g. wyrdsekai-3.5-9b-v5-q4km) — probe-confirmed.
                //   * Models with size ≥7B that aren't pinned-base 4B — assume true.
                //   * Smaller / base-only models — false (4B Qwen3.5 base wasn't
                //     validated; spec §9 says don't include the block for those).
                // The override file (capabilities.properties) lets operators flip
                // any model on/off explicitly: `code-mode.<backend>=<model>,<tier>,<priority>`.
                boolean codeModeCapable = isCodeModeCapableHeuristic(backend, model, tier, sizeB);
                if (codeModeCapable) {
                    registry.register(new CapabilityEntry(
                        "code-mode", backend.name(), model, tier, backend.priority()));
                }
            }
        }

        // Fallback: if no backend registered as "reasoning" (e.g. all models are <30B),
        // promote the backend with the largest model so cap:reasoning always resolves.
        // Without this, cap:reasoning falls through to first-healthy-by-priority, which
        // in a dual-inference deploy would misroute COMPLEX turns to the voice backend.
        if (!registry.availableCapabilities().contains("reasoning")) {
            InferenceBackend largest = null;
            double largestSize = -1;
            String largestModel = null;
            for (var backend : backends) {
                for (var model : backend.models()) {
                    var sizeB = extractSizeBillions(model.toLowerCase(Locale.ROOT));
                    if (sizeB > largestSize) {
                        largestSize = sizeB;
                        largest = backend;
                        largestModel = model;
                    }
                }
            }
            if (largest != null && largestModel != null) {
                registry.register(new CapabilityEntry(
                    "reasoning", largest.name(), largestModel,
                    inferTier(largest), largest.priority()));
                log.info("Registered '{}' ({}) as fallback reasoning backend ({}B)",
                    largest.name(), largestModel, largestSize);
            }
        }

        log.info("CapabilityRegistry auto-detected {} capabilities from {} backends: {}",
            registry.availableCapabilities().size(), backends.size(),
            registry.availableCapabilities());

        return registry;
    }

    /**
     * does any registered backend declare {@code code-mode}
     * within (or below) {@code maxTier}? Used by
     * {@code CompanionActor.maybeAppendFreeFormCodeModeBlock} to decide whether
     * to emit the typed-namespace prompt block at all. If no capable backend
     * exists for the request's tier, the block is suppressed (the prompt would
     * leak to a backend that can't act on it).
     *
     * @param capability the capability to look up (e.g. {@code "code-mode"})
     * @param maxTier    nullable; when null, any tier counts.
     * @return true if at least one entry exists within the tier limit.
     */
    public boolean hasCapableBackend(String capability, String maxTier) {
        if (capability == null) return false;
        if (maxTier == null) {
            var entries = capabilities.get(capability);
            return entries != null && !entries.isEmpty();
        }
        return resolve(capability, maxTier).isPresent();
    }

    /** Convenience: any tier. */
    public boolean hasCapableBackend(String capability) {
        return hasCapableBackend(capability, null);
    }

    // --- Internal ---

    /**
     * heuristic: which backends can be trusted to write
     * the typed-namespace JS the prompt block expects? Conservative — when in
     * doubt, return false so the block doesn't leak to a base 4B that hasn't
     * been validated. Operators can override via {@code capabilities.properties}
     * (see {@link #enableHotReload}).
     */
    static boolean isCodeModeCapableHeuristic(InferenceBackend backend, String model,
                                                String tier, double sizeB) {
        // Cloud / ClaudeCli — Anthropic + OpenAI handle JS fine.
        if (backend instanceof InferenceBackend.Cloud) return true;
        if (backend instanceof InferenceBackend.ClaudeCli) return true;

        var modelLower = model == null ? "" : model.toLowerCase(Locale.ROOT);
        // Probe-confirmed: 9B "drive" models from SSD training.
        if (modelLower.contains("drive") && sizeB >= 7) return true;
        // Generic large models — best-guess yes. The 14B+ band is where
        // structured-output reliability tends to land.
        if (sizeB >= 14) return true;
        // 7-13B: tentative yes (probe at deploy time and override via the
        // capabilities file when something falls over).
        if (sizeB >= 7) return true;
        // 4B base / smaller / unknown — no.
        return false;
    }

    /** Infer tier from backend type. */
    public static String inferTier(InferenceBackend backend) {
        return switch (backend) {
            case InferenceBackend.LlamaServer _ -> "local";
            case InferenceBackend.Ollama _ -> "local";
            case InferenceBackend.Mlx _ -> "local";   // macOS in-process server
            case InferenceBackend.VLLM _ -> "household";
            case InferenceBackend.SGLang _ -> "household";
            case InferenceBackend.Cloud _ -> "cloud";
            case InferenceBackend.ClaudeCli _ -> "cloud";
            case InferenceBackend.NatsRemote _ -> "household";  // cross-zone via relay
        };
    }

    /**
     * Extract parameter count in billions from a model name string.
     * Matches patterns like "72b", "7b", "3.5b", "0.5b", "1.5B".
     *
     * @return Size in billions, or -1 if not found
     */
    static double extractSizeBillions(String modelName) {
        // Match patterns: "72b", "7b", "3.5b", "0.5b", optionally preceded by :/-
        var matcher = Pattern
            .compile("(\\d+\\.?\\d*)[bB]")
            .matcher(modelName);

        double largest = -1;
        while (matcher.find()) {
            try {
                double size = Double.parseDouble(matcher.group(1));
                if (size > largest) largest = size;
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return largest;
    }

    // --- Hot-reload from properties file ---

    /** Optional hot-reloadable override config. */
    private HotReloadableConfig<Map<String, CapabilityEntry>> overrideConfig;

    /**
     * Enable hot-reload from a capabilities properties file.
     * Format:
     * <pre>
     * # capability.backend=model,tier,priority
     * reasoning.gpu-host-sglang=qwen2.5:72b,household,1
     * coding.gpu-host-sglang=qwen3-coder:30b,household,1
     * quick.local-ollama=qwen2.5:7b,local,1
     * </pre>
     *
     * <p>On reload, new/changed capabilities are added. Existing capabilities from
     * the file that changed are updated. Capabilities NOT in the file (e.g. auto-detected
     * from backends) are kept — the file only adds/overrides, never removes.</p>
     *
     * @param path Path to capabilities.properties
     */
    public void enableHotReload(Path path) {
        this.overrideConfig = new HotReloadableConfig<>(
            path, CapabilityRegistry::loadCapabilitiesFile, Map.of());
    }

    /**
     * Apply any pending hot-reload overrides. Call this before resolving
     * capabilities if hot-reload is enabled. Cheap if the file hasn't changed.
     */
    public void applyOverrides() {
        if (overrideConfig == null) return;
        var overrides = overrideConfig.get();
        for (var entry : overrides.values()) {
            // Replace existing entries for this capability+backend, or add new
            capabilities.compute(entry.capability(), (cap, existing) -> {
                var list = existing != null ? new ArrayList<>(existing) : new ArrayList<CapabilityEntry>();
                // Remove any existing entry with same backend
                list.removeIf(e -> e.backendName().equals(entry.backendName()));
                list.add(entry);
                list.sort(Comparator.comparingInt(CapabilityEntry::priority));
                return List.copyOf(list);
            });
        }
    }

    /**
     * Parse a capabilities properties file.
     * Format: {@code capability.backend=model,tier,priority}
     *
     * @param path File path
     * @return Map of "capability.backend" -> CapabilityEntry
     */
    static Map<String, CapabilityEntry> loadCapabilitiesFile(Path path) {
        var result = new LinkedHashMap<String, CapabilityEntry>();
        try {
            var props = new Properties();
            try (var reader = Files.newBufferedReader(path)) {
                props.load(reader);
            }

            for (var key : props.stringPropertyNames()) {
                var value = props.getProperty(key);
                // key = "capability.backend", value = "model,tier,priority"
                int dot = key.indexOf('.');
                if (dot <= 0 || dot >= key.length() - 1) {
                    log.warn("Skipping malformed capability key: {}", key);
                    continue;
                }
                var capability = key.substring(0, dot).strip();
                var backendName = key.substring(dot + 1).strip();
                var parts = value.split(",");
                if (parts.length < 2) {
                    log.warn("Skipping malformed capability value for {}: {}", key, value);
                    continue;
                }
                var model = parts[0].strip();
                var tier = parts[1].strip();
                int priority = parts.length >= 3 ? Integer.parseInt(parts[2].strip()) : 1;

                try {
                    var entry = new CapabilityEntry(capability, backendName, model, tier, priority);
                    result.put(key, entry);
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping invalid capability entry {}: {}", key, e.getMessage());
                }
            }

            log.info("Loaded {} capability overrides from {}", result.size(), path);
        } catch (IOException | NumberFormatException e) {
            log.warn("Failed to load capabilities from {}: {}", path, e.getMessage());
        }
        return result;
    }

    /** Get the hot-reload config, if enabled. May be null. */
    public HotReloadableConfig<Map<String, CapabilityEntry>> overrideConfig() {
        return overrideConfig;
    }
}
