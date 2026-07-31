package org.wyrdsekai.core.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Track-C C9 — ship-default recipe enrollment.
 *
 * <p>Fresh installs must produce a working scheduler with no steward
 * configuration. This provisioner is the pure-logic policy that decides
 * <em>which</em> recipes get enrolled for <em>which</em> agents at
 * first boot:</p>
 *
 * <ul>
 *   <li>Recipe = {@code retrain-classifier-head} (the bundled one from
 * Track-A; same scaffold for every head).</li>
 *   <li>One enrollment per (head, companion DID) pair. Each starts in
 *       {@link CadenceTier#WARMUP} with zero consecutive successes. The
 *       agent-scoped gap keys list the head's misroute pattern so
 *       gap-detection re-fires the same enrollment.</li>
 *   <li>Heads are taken from {@link
 *       org.wyrdsekai.core.config.WyrdConfig#schedulerEnrolledHeads()}
 *       when non-empty; otherwise from disk discovery; otherwise from
 *       the curated baseline (so first-boot before models exist still
 *       works — the runtime will just NEEDS_BACKEND every run until
 *       the head's bootstrap corpus + ONNX file land).</li>
 * </ul>
 *
 * <p>Pure — caller writes the rows to {@link RecipeEnrollmentStore}.
 * Idempotent — re-running with the same inputs upserts the same rows.</p>
 */
public final class ShipDefaultEnrollmentProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ShipDefaultEnrollmentProvisioner.class);

    private ShipDefaultEnrollmentProvisioner() {}

    /** The recipe every classifier head enrolls in. */
    public static final String DEFAULT_RECIPE = "retrain-classifier-head";

    /**
     * Ship-default recipes that auto-enroll for every fresh companion.
     * Order:
     * <ol>
     *   <li>{@code retrain-classifier-head} — gap-triggered + cron when stale; needs
     *       {@code head} param supplied at dispatch (via gap event or steward
     *       override). Cron firings without a head fail fast — that's the welfare
     *       floor, not a bug.</li>
     *   <li>{@code consolidate-memory-graph} — daily SQL housekeeping. Needs
     *       {@code agent_did} (from enrollment row) + {@code jdbc_url} (defaults
     *       to {@code $WYRDSEKAI_JDBC_URL} env, then to recipe-default). Cheap,
     *       CPU-only, safe to auto-fire.</li>
     *   <li>{@code consolidate-soul-fragments} — daily soul_fragments
     *       housekeeping (#1130). Same shape as consolidate-memory-graph: only
     *       {@code agent_did} required, pure-SQLite, CPU-only, deploys:true with
     *       two welfare gates.</li>
     *   <li>{@code welfare-floor-checkup} — read-only health checkup (#1132).
     *       deploys:false, only {@code agent_did} required; surfaces a report
     *       of which maintenance recipes are due. Safe to auto-fire — it mutates
     *       nothing.</li>
     * </ol>
     * Other ship-default recipes (extract-steering-vector, run-substrate-sft,
     * compact-library-index, align-bondholder-voice, reembed-soul-fragments)
     * require dispatch-time choices (which vector, which corpus, which
     * collection, which bondholder) or only matter after an encoder bump
     * (reembed), which the cron trigger can't decide. They stay on-demand or
     * gap-triggered.
     */
    public static final List<String> SHIP_DEFAULT_RECIPES = List.of(
        DEFAULT_RECIPE,
        "consolidate-memory-graph",
        "consolidate-soul-fragments",
        "welfare-floor-checkup");

    /**
     * Curated baseline matching {@link
     * org.wyrdsekai.core.agent.classifier.ClassifierHead} on
     * 2026-05-25. Kept as a string list (not a direct enum reference) so
     * this module stays usable from contexts that don't pull the
     * classifier package.
     */
    public static final List<String> BASELINE_HEADS = List.of(
        "request_type", "cleanliness", "task_present", "substrate_present");

    /**
     * Discover head names. Resolution order:
     * <ol>
     *   <li>{@code configCsv} — comma-separated steward override (empty = skip)</li>
     *   <li>{@code pretrainedDir} — list {@code <name>.onnx} files (excludes
     *       {@code .labels.json} mirrors)</li>
     *   <li>{@link #BASELINE_HEADS} fallback</li>
     * </ol>
     * Returns the de-duplicated, stably-ordered set.
     */
    public static List<String> discoverHeads(String configCsv, Path pretrainedDir) {
        if (configCsv != null && !configCsv.isBlank()) {
            return parseCsv(configCsv);
        }
        if (pretrainedDir != null && Files.isDirectory(pretrainedDir)) {
            var out = new LinkedHashSet<String>();
            try (var s = Files.list(pretrainedDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".onnx"))
                    .map(p -> {
                        var name = p.getFileName().toString();
                        return name.substring(0, name.length() - ".onnx".length());
                    })
                    .sorted()
                    .forEach(out::add);
            } catch (Exception ignored) {}
            if (!out.isEmpty()) return new ArrayList<>(out);
        }
        return BASELINE_HEADS;
    }

    /**
     * Build the canonical enrollment set. No I/O.
     *
     * <p>The {@link RecipeEnrollmentStore} primary key is
     * {@code (recipe_id, agent_did)}, so we emit <em>one</em> row per
     * agent with the union of every head's gap-key. The {@code head}
     * which the run trains is supplied at dispatch-time through the
     * enqueue's {@code params} map, not through the enrollment row.
     * This keeps the schema honest and lets the steward enable/disable
     * the recipe once instead of per-head.</p>
     *
     * @param heads      e.g. ["request_type", "task_present", …]
     * @param agentDids  companion DIDs to enroll
     * @param now        enrolledAt timestamp; tests pass a deterministic value
     */
    public static List<RecipeEnrollment> defaults(List<String> heads,
            Collection<String> agentDids, Instant now) {
        var t0 = now == null ? Instant.now() : now;
        var rows = new ArrayList<RecipeEnrollment>();
        var mergedGapKeys = new LinkedHashSet<String>();
        for (var head : heads) {
            mergedGapKeys.add(head + ".misroute");
        }
        for (var did : agentDids) {
            // retrain-classifier-head: gap-keyed by every head's misroute pattern.
            rows.add(new RecipeEnrollment(
                DEFAULT_RECIPE,
                did,
                CadenceTier.WARMUP,
                0,
                t0,
                true,
                Set.copyOf(mergedGapKeys)));
            // consolidate-memory-graph: cron-only daily housekeeping. No gap
            // keys — fires on cadence, not on event. The cron trigger fills in
            // agent_did from this row's column at dispatch time.
            for (var extra : SHIP_DEFAULT_RECIPES) {
                if (DEFAULT_RECIPE.equals(extra)) continue;
                rows.add(new RecipeEnrollment(
                    extra,
                    did,
                    CadenceTier.WARMUP,
                    0,
                    t0,
                    true,
                    Set.of()));
            }
        }
        return rows;
    }

    /**
     * One-call provision: discover heads, build defaults, upsert. Returns
     * the rows that were upserted. Safe to call repeatedly — store's
     * {@link RecipeEnrollmentStore#upsert} is idempotent by composite key.
     */
    public static List<RecipeEnrollment> provision(
            RecipeEnrollmentStore store, String configCsv,
            Path pretrainedDir, Collection<String> agentDids, Instant now) {
        if (store == null || agentDids == null || agentDids.isEmpty()) {
            return List.of();
        }
        var heads = discoverHeads(configCsv, pretrainedDir);
        var rows = defaults(heads, agentDids, now);
        for (var r : rows) store.upsert(r);
        return rows;
    }

    /**
     * Per-companion provisioning hook (#1008). Called from
     * {@code CompanionActor} immediately after a fresh soul is birthed
     * and persisted. Reads the runtime context from
     * {@link RecipeEnrollmentRegistry} and writes one ship-default
     * enrollment row per ship-default head — same shape as the
     * boot-time path, just for one DID.
     *
     * <p>No-op when (a) the registry is unset (scheduler disabled or
     * pre-boot), (b) the DID is null/blank, or (c) any persistence
     * error fires — we never want recipe enrollment to crash the
     * soul-birth path. Failures log at WARN and the companion proceeds.</p>
     *
     * <p>Idempotent: the upsert is keyed on {@code (recipe_id,
     * agent_did)}, so calling this on a spawn that already has rows is
     * harmless. Wakes up before the next scheduler tick — the new
     * companion's WARMUP-tier enrollment is live within the hour
     * regardless.</p>
     *
     * @return number of rows written (0 when registry unset, DID blank,
     *         or write fails)
     */
    public static int provisionForCompanion(String agentDid) {
        if (agentDid == null || agentDid.isBlank()) return 0;
        var ctx = RecipeEnrollmentRegistry.get();
        if (ctx == null) return 0;
        try {
            var rows = provision(
                ctx.store(),
                ctx.headsConfigCsv(),
                ctx.pretrainedDir(),
                List.of(agentDid),
                Instant.now());
            return rows.size();
        } catch (Exception e) {
            // Log + swallow — never crash soul-birth on recipe-side issues.
            log.warn("provisionForCompanion failed for {}: {}", agentDid, e.toString());
            return 0;
        }
    }

    private static List<String> parseCsv(String s) {
        return Arrays.stream(s.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .distinct()
            .toList();
    }
}
