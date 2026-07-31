package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * resource-requisites (option c — BYO cloud). The third fallback in
 * the local-first chain: wraps a delegate dispatcher and, only when that returns
 * {@link RecipeRunner.Status#RESOURCE_DENIED}, shells out to a
 * <em>steward-configured launch script</em> that runs the recipe on the
 * household's own cloud (Vast / RunPod / their own GPU box / a managed RFT API).
 *
 * <p>This is deliberately a <b>seam, not a provisioner</b>. We do not model any
 * provider's API — the power user already has a cloud relationship; we hand their
 * script a documented job-spec and take back a result. Same shape as
 * {@link CodingBackendDispatcher} shelling to goose/opencode. If no script is
 * configured, the local {@code RESOURCE_DENIED} passes through unchanged so the
 * steward-ask (option a) still fires.</p>
 *
 * <p>Contract: the script is invoked as {@code bash <script> <jobspec.json>} and
 * must print a single JSON object to stdout:
 * {@code {"status":"SUCCESS|GATE_FAILED|STEP_FAILED|ERROR","artifact":"<path-or-uri>","message":"..."}}.
 * The job-spec carries the recipe name, params (incl. declared bank paths + base
 * model), the {@code requires} contract, and {@code wallClockMin}. Two conventions
 * the reference scripts honour: <b>pull the base from HF on the cloud side</b>
 * (don't upload 17GB), and <b>treat {@code wallClockMin} as a hard kill-TTL</b> so
 * a botched teardown can't bleed. The process timeout here is an outer backstop
 * (wallClock × 1.2) on top of the script's own teardown.</p>
 */
public final class CloudRecipeDispatcher implements RecipeScheduler.Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(CloudRecipeDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RecipeScheduler.Dispatcher delegate;
    private final Supplier<String> launchScript;            // steward-configured path; blank = disabled
    private final Function<String, RecipeManifest> manifestResolver;
    private final Function<Duration, CommandRunner> runnerFactory;

    public CloudRecipeDispatcher(RecipeScheduler.Dispatcher delegate,
                                 Supplier<String> launchScript,
                                 Function<String, RecipeManifest> manifestResolver,
                                 Function<Duration, CommandRunner> runnerFactory) {
        this.delegate = delegate;
        this.launchScript = launchScript;
        this.manifestResolver = manifestResolver;
        this.runnerFactory = runnerFactory;
    }

    @Override
    public RecipeService.StartedRun dispatch(String agentDid, String recipeName,
                                             Map<String, Object> params) {
        RecipeService.StartedRun local = delegate.dispatch(agentDid, recipeName, params);
        if (local == null || local.run() == null
                || local.run().status() != RecipeRunner.Status.RESOURCE_DENIED) {
            return local;
        }

        String script = launchScript != null ? launchScript.get() : null;
        if (script == null || script.isBlank() || !Files.isExecutable(Path.of(script))) {
            if (script != null && !script.isBlank()) {
                log.warn("Cloud launch script '{}' configured but not executable — leaving RESOURCE_DENIED", script);
            }
            return local; // no cloud configured → steward ask (option a) fires.
        }

        RecipeManifest manifest = safeResolve(recipeName);
        List<ResourceRequirement> requires = manifest != null ? manifest.requires() : List.of();
        double wallClockMin = requires.stream()
            .filter(r -> r.kind() == ResourceRequirement.Kind.WALL_CLOCK_MIN)
            .mapToDouble(ResourceRequirement::amount).max().orElse(0);
        Duration ttl = wallClockMin > 0
            ? Duration.ofMinutes((long) Math.ceil(wallClockMin * 1.2))
            : DEFAULT_TTL;

        Path jobspec = null;
        try {
            jobspec = writeJobSpec(recipeName, agentDid, params, requires, wallClockMin);
            String cmd = "bash '" + script + "' '" + jobspec + "'";
            log.info("Cloud-borrow: dispatching '{}' via steward launch script '{}' (ttl={})",
                recipeName, script, ttl);
            CommandRunner runner = runnerFactory.apply(ttl);
            CommandRunner.Result res = runner.run(cmd, ttl);
            return mapResult(recipeName, res);
        } catch (Exception e) {
            log.warn("Cloud-borrow of '{}' failed: {} — leaving RESOURCE_DENIED for steward ask",
                recipeName, e.toString());
            return local;
        } finally {
            if (jobspec != null) try { Files.deleteIfExists(jobspec); } catch (Exception ignored) {}
        }
    }

    private Path writeJobSpec(String recipeName, String agentDid, Map<String, Object> params,
                              List<ResourceRequirement> requires, double wallClockMin) throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("recipe", recipeName);
        spec.put("agentDid", agentDid);
        spec.put("params", params == null ? Map.of() : params);
        spec.put("wallClockMin", wallClockMin);
        List<Map<String, Object>> reqs = requires.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", r.kind().name());
            m.put("amount", r.amount());
            m.put("target", r.target());
            m.put("hard", r.hard());
            return m;
        }).toList();
        spec.put("requires", reqs);
        Path f = Files.createTempFile("wyrd-cloud-job-", ".json");
        Files.writeString(f, MAPPER.writeValueAsString(spec));
        return f;
    }

    private RecipeService.StartedRun mapResult(String recipeName, CommandRunner.Result res) {
        String runId = UUID.randomUUID().toString();
        if (res == null) {
            return started(runId, RecipeRunner.Status.ERROR, "[cloud] no result", Map.of());
        }
        // The script's JSON is the last non-blank stdout line (scripts may log above it).
        String json = lastJsonLine(res.stdout());
        if (res.ok() && json != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> out = MAPPER.readValue(json, Map.class);
                RecipeRunner.Status status = parseStatus(String.valueOf(out.get("status")));
                String msg = String.valueOf(out.getOrDefault("message", ""));
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("cloud_backed", true);
                if (out.get("artifact") != null) ctx.put("cloud_artifact", out.get("artifact"));
                return started(runId, status, "[cloud] " + msg, ctx);
            } catch (Exception e) {
                log.warn("Cloud-borrow '{}' returned unparseable JSON: {}", recipeName, e.toString());
            }
        }
        String detail = res.stderr() != null && !res.stderr().isBlank()
            ? res.stderr() : "exit=" + res.exitCode();
        return started(runId, RecipeRunner.Status.ERROR, "[cloud] launch failed: " + detail, Map.of());
    }

    private static RecipeService.StartedRun started(String runId, RecipeRunner.Status status,
                                                    String message, Map<String, Object> ctx) {
        return new RecipeService.StartedRun(runId,
            new RecipeRunner.RecipeRun(status, message, List.of(), new RecipeContext(ctx)));
    }

    private static String lastJsonLine(String stdout) {
        if (stdout == null) return null;
        String[] lines = stdout.strip().split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].strip();
            if (l.startsWith("{") && l.endsWith("}")) return l;
        }
        return null;
    }

    private static RecipeRunner.Status parseStatus(String s) {
        if (s == null) return RecipeRunner.Status.ERROR;
        try {
            return RecipeRunner.Status.valueOf(s);
        } catch (IllegalArgumentException e) {
            return RecipeRunner.Status.ERROR;
        }
    }

    private RecipeManifest safeResolve(String recipeName) {
        try {
            return manifestResolver.apply(recipeName);
        } catch (Exception e) {
            return null;
        }
    }
}
