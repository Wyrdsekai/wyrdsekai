package org.wyrdsekai.core.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Loads, lists, runs, and tracks recipes. This is the service the GraalJS
 * {@code world.recipe.*} surface (list/inspect/run/status) delegates to — keeping the host-API
 * layer thin and the logic here, testable without the item sandbox.
 *
 * <p>Recipes resolve from a household recipes directory (a library compartment, OPEN-R1) first,
 * then fall back to the classpath-bundled defaults under {@code recipes/}. Runs are executed by
 * {@link RecipeRunner} (gates enforced in-runtime, §4) and kept in an in-memory registry keyed by
 * a run id so {@code status(runId)} can report progress.
 */
public final class RecipeService {

    /** Bundled-on-classpath recipe names (ship even with no household recipes dir). */
    // Reserved names: an authored household recipe may not shadow ANY bundled
    // recipe (a household file otherwise won over the classpath bundle in
    // loadManifest — found 2026-08-16 while shipping the sleep-forge pair,
    // whose welfare:permanent gates guard weight-writes to a companion's
    // soul). Discovered from the classpath at init so a newly bundled recipe
    // is protected the day it ships; CRITICAL_FLOOR is the belt under the
    // suspenders — discovery failing must never leave these three exposed.
    private static final List<String> CRITICAL_FLOOR = List.of(
            "retrain-classifier-head", "sleep-forge-spine", "sleep-forge-organ");
    private static final List<String> BUNDLED = discoverBundled();

    private static List<String> discoverBundled() {
        var names = new LinkedHashSet<>(CRITICAL_FLOOR);
        try {
            var urls = RecipeService.class.getClassLoader().getResources("recipes");
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                if ("jar".equals(url.getProtocol())) {
                    var conn = (JarURLConnection) url.openConnection();
                    try (var jar = conn.getJarFile()) {
                        var entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String e = entries.nextElement().getName();
                            if (e.startsWith("recipes/") && e.endsWith(EXT)) {
                                names.add(e.substring("recipes/".length(),
                                        e.length() - EXT.length()));
                            }
                        }
                    }
                } else {
                    Path dir = Path.of(url.toURI());
                    if (Files.isDirectory(dir)) {
                        try (Stream<Path> s = Files.list(dir)) {
                            s.map(p -> p.getFileName().toString())
                                    .filter(f -> f.endsWith(EXT))
                                    .map(f -> f.substring(0, f.length() - EXT.length()))
                                    .forEach(names::add);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fall through with whatever was gathered — CRITICAL_FLOOR at minimum.
        }
        return List.copyOf(names);
    }

    private static final String EXT = ".recipe.yaml";

    private final Path recipesDir; // nullable — classpath-only if absent
    private final RecipeRunner runner;
    private final String agentDid; // nullable — when set, completed runs feed the sleep-pass Forge
    private final Path scriptsRoot; // nullable — when set, loadManifest enforces recipe-callable invariant
    private final Map<String, RecipeRunner.RecipeRun> runs = new ConcurrentHashMap<>();
    // #1142 — nullable param-override store. When wired, tuned param
    // defaults are merged UNDER caller-supplied params at run time. Null in tests.
    private SqlRecipeParamOverrides paramOverrides;

    public RecipeService(Path recipesDir, RecipeRunner runner) {
        this(recipesDir, runner, null, null);
    }

    /**
     * @param agentDid when non-null, every completed run is recorded to {@link RecipeRunLog} under
     *                 this DID so {@code CompanionActor.completeSleep} can ingest it into the soul
     *                 (P6). Null in unit tests / non-agent contexts → no Forge recording.
     */
    public RecipeService(Path recipesDir, RecipeRunner runner, String agentDid) {
        this(recipesDir, runner, agentDid, null);
    }

    /**
     * @param scriptsRoot when non-null, every {@link #loadManifest(String)} runs
     *                    {@link RecipeCallableValidator#validate} against
     *                    the manifest. Any referenced script missing the
     *                    {@value RecipeCallableValidator#HEADER_MARKER}
     *                    header throws {@link RecipeValidationException}.
     *                    Production wiring should pass the install dir's
     *                    {@code scripts/} path; tests pass null to skip.
     */
    public RecipeService(Path recipesDir, RecipeRunner runner, String agentDid, Path scriptsRoot) {
        this.recipesDir = recipesDir;
        this.runner = runner;
        this.agentDid = agentDid;
        this.scriptsRoot = scriptsRoot;
    }

    /** Lightweight listing entry for {@code world.recipe.list()}. */
    public record Summary(String name, String version, String description,
                          RecipeManifest.Ownership ownership, boolean deploys) {}

    public record StartedRun(String runId, RecipeRunner.RecipeRun run) {}

    /** All discoverable recipes (household dir ∪ bundled), de-duplicated by name. */
    public List<Summary> list() {
        Map<String, Summary> byName = new LinkedHashMap<>();
        for (String name : discoverNames()) {
            try {
                RecipeManifest m = loadManifest(name);
                byName.put(name, new Summary(m.recipe(), m.version(), m.description(), m.ownership(), m.deploys()));
            } catch (RuntimeException ignored) {
                // a malformed recipe must not break listing the rest
            }
        }
        return new ArrayList<>(byName.values());
    }

    public RecipeManifest inspect(String name) {
        return loadManifest(name);
    }

    /** Execute a recipe; returns the run id + result. The run is retained for {@link #status}. */
    /**
     * #1142 — wire the param-override store so tuned defaults take
     * effect on future runs. Optional; null leaves behaviour unchanged.
     */
    public RecipeService withParamOverrides(SqlRecipeParamOverrides store) {
        this.paramOverrides = store;
        return this;
    }

    public StartedRun run(String name, Map<String, Object> params) {
        RecipeManifest m = loadManifest(name);
        RecipeRunner.RecipeRun result = runner.run(m, effectiveParams(m.recipe(), params));
        String runId = UUID.randomUUID().toString();
        runs.put(runId, result);
        // P6 — feed the sleep-pass Forge: a completed run becomes a DEXTERITY fragment at next
        // sleep. Recorded under the owning agent's DID so completeSleep can drain it.
        if (agentDid != null) {
            RecipeRunLog.get().record(agentDid,
                    new RecipeForgeIngester.CompletedRun(m.recipe(), m.deploys(), result));
        }
        // Sleep-forge provenance: a successful run that produced a weight
        // artifact (context carries artifact_path) gets signed with the owning
        // companion's identity. In-runtime because subprocess steps must never
        // see the household secret. Best-effort — never fails the run.
        if (agentDid != null && result.status() == RecipeRunner.Status.SUCCESS
                && result.context().get("artifact_path") instanceof String ap) {
            SoulArtifactSigner.sign(agentDid, Path.of(ap));
        }
        return new StartedRun(runId, result);
    }

    public Optional<RecipeRunner.RecipeRun> status(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /** Names that ship on the classpath — reserved; an authored recipe (#1014)
     *  may not shadow them. */
    public static List<String> bundledNames() {
        return BUNDLED;
    }

    /**
     * #1014 (OPEN-R1) — names discovered in the household
     * {@code data/recipes/} compartment only (the agent-authored set), excluding
     * the classpath-bundled ship recipes. The write path is {@link
     * RecipeAuthorService}; this is the read side the workbench + REST list from.
     */
    public List<String> householdRecipeNames() {
        var out = new ArrayList<String>();
        if (recipesDir != null && Files.isDirectory(recipesDir)) {
            try (Stream<Path> s = Files.list(recipesDir)) {
                s.map(p -> p.getFileName().toString())
                        .filter(f -> f.endsWith(EXT))
                        .map(f -> f.substring(0, f.length() - EXT.length()))
                        .sorted()
                        .forEach(out::add);
            } catch (IOException ignored) {
                // dir unreadable — empty authored set
            }
        }
        return out;
    }

    /**
     * Merge stored param overrides under the caller-supplied params. Per-agent
     * overrides win over household-wide; an explicit per-run param wins over both
     * (a steward forcing a value isn't second-guessed by a stored tune). No
     * store wired → caller params verbatim.
     */
    private Map<String, Object> effectiveParams(String recipeId, Map<String, Object> caller) {
        if (paramOverrides == null) return caller == null ? Map.of() : caller;
        var eff = new LinkedHashMap<String, Object>(
            paramOverrides.effectiveFor(recipeId, agentDid));
        if (caller != null) eff.putAll(caller);
        return eff;
    }

    // ── loading ────────────────────────────────────────────────────────────────

    private RecipeManifest loadManifest(String name) {
        String safe = sanitize(name);
        RecipeManifest manifest;
        // Bundled names load from the CLASSPATH ONLY. The household dir must
        // not be consulted for them: a file dropped directly into
        // data/recipes/ bypasses the AuthoredRecipeValidator shadow check,
        // and a bundled recipe's gates (some welfare:permanent) must not be
        // replaceable by any on-disk write.
        if (recipesDir != null && !BUNDLED.contains(safe)) {
            Path p = recipesDir.resolve(safe + EXT);
            if (Files.isRegularFile(p)) {
                manifest = RecipeParser.parseManifestFile(p);
                return enforceRecipeCallable(name, manifest);
            }
        }
        String cp = classpathRecipe(safe);
        if (cp != null) {
            manifest = RecipeParser.parseManifest(cp);
            return enforceRecipeCallable(name, manifest);
        }
        throw new RecipeValidationException("recipe not found: " + name);
    }

    /** recipe-callable invariant: any {@code scripts/...}
     *  referenced by the manifest must carry the
     *  {@value RecipeCallableValidator#HEADER_MARKER} header. Skipped when
     *  no {@link #scriptsRoot} was configured (test contexts). */
    private RecipeManifest enforceRecipeCallable(String name, RecipeManifest manifest) {
        if (scriptsRoot == null) return manifest;
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        if (!violations.isEmpty()) {
            throw new RecipeValidationException(
                "recipe '" + name + "' fails recipe-callable invariant: "
                + RecipeCallableValidator.summarize(violations));
        }
        return manifest;
    }

    private List<String> discoverNames() {
        List<String> names = new ArrayList<>(BUNDLED);
        if (recipesDir != null && Files.isDirectory(recipesDir)) {
            try (Stream<Path> s = Files.list(recipesDir)) {
                s.map(p -> p.getFileName().toString())
                        .filter(f -> f.endsWith(EXT))
                        .map(f -> f.substring(0, f.length() - EXT.length()))
                        .forEach(n -> { if (!names.contains(n)) names.add(n); });
            } catch (IOException ignored) {
                // dir unreadable — fall back to bundled only
            }
        }
        return names;
    }

    private static String classpathRecipe(String name) {
        try (InputStream in = RecipeService.class.getClassLoader()
                .getResourceAsStream("recipes/" + name + EXT)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Prevent path traversal in a recipe name from the script surface. */
    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            throw new RecipeValidationException("recipe name is blank");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new RecipeValidationException("illegal recipe name: " + name);
        }
        return name;
    }
}
