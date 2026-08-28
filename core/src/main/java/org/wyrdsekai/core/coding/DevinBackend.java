package org.wyrdsekai.core.coding;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Devin (cognition.ai) implementation of {@link CodingTaskBackend}, the
 * async-cloud (shape #3) outlier in the Phase 2 backend set. No local
 * binary; REST-only against {@code https://api.devin.ai/v3/...}.
 *
 * <p><b>Lifecycle (Phase 2e, May 2026)</b>:
 * <ol>
 *   <li>{@code POST /v3/organizations/{org_id}/sessions} with task
 *       description → {@code session_id}.</li>
 *   <li>Poll {@code GET /v3/organizations/{org_id}/sessions/{session_id}}
 *       on an exponential-backoff schedule (initial interval from
 *       config, capped at 30s).</li>
 *   <li>Terminal status values surfaced by upstream: {@code stopped},
 *       {@code blocked}, {@code finished}, {@code completed}, etc. We
 *       treat anything that's not a "running" / "pending" / "queued"
 *       value as terminal and stop polling.</li>
 *   <li>The terminal response carries {@code pull_request} (URL +
 *       title), {@code structured_output}, {@code messages}, and
 *       {@code session_id} — all folded into the
 *       {@link SourceArtifact}'s {@code backendMetadata}.</li>
 * </ol>
 *
 * <p><b>Auth</b>: API-key only via {@code Authorization: Bearer
 * $DEVIN_API_KEY} header. {@link AuthMode.OAuthSession} is unreachable
 * (manifest declares no OAuth path); the resolver returns
 * {@link AuthMode.ApiKey} or {@link AuthMode.AuthMissing}.</p>
 *
 * <p><b>Tier</b>: {@link BackendTier#CLOUD_PAID}. CU estimate:
 * <b>5000 default</b> (HIGH — async cloud sessions routinely run hours
 * and cost real money). The conservative cap is load-bearing per the
 * Phase 2e brief — never bias below this without a cost-policy review.</p>
 *
 * <p>Configuration is read via {@link DevinRuntimeConfig}.</p>
 */
public final class DevinBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link DevinEventAdapter#namespace()}. */
    public static final String NAME = "devin";

    private static final Logger log = LoggerFactory.getLogger(DevinBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Conservative CU estimate per SPEC Phase 2e brief. */
    static final long DEFAULT_CU_ESTIMATE = 5000L;

    /** Hard cap so the estimate never silently overshoots. */
    private static final long MAX_CU_ESTIMATE = 50000L;

    /**
     * Cap on individual poll intervals. The adapter doubles the interval
     * up to this ceiling — at 30s most long-running sessions stay
     * responsive without flooding the API.
     */
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(30);

    private final DevinRuntimeConfig config;
    private final AuthResolver authResolver;
    private final HttpClient httpClient;
    private final Sleeper sleeper;

    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses a default JDK {@link HttpClient}. */
    public DevinBackend(DevinRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, defaultHttpClient(), Thread::sleep);
    }

    /** Test constructor — pluggable HTTP + sleeper for unit tests. */
    public DevinBackend(DevinRuntimeConfig config,
                        AuthResolver authResolver,
                        HttpClient httpClient,
                        Sleeper sleeper) {
        this.config = config != null ? config : DevinRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name,
                "set DEVIN_API_KEY in your Key Chest",
                "AuthResolver not wired"));
        this.httpClient = httpClient != null ? httpClient : defaultHttpClient();
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
    }

    @Override public String name() { return NAME; }

    @Override public BackendTier tier() { return BackendTier.CLOUD_PAID; }

    @Override
    public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
        var future = new CompletableFuture<TaskResult>();
        var started = System.currentTimeMillis();
        var taskId = spec != null && spec.taskId() != null ? spec.taskId() : UUID.randomUUID();

        if (!config.enabled()) {
            future.complete(failed(taskId,
                "Devin backend is disabled in config", started));
            return future;
        }
        if (config.orgId() == null || config.orgId().isBlank()) {
            future.complete(failed(taskId,
                "Devin backend requires coding.backends.devin.org_id", started));
            return future;
        }

        var auth = authResolver.resolveAuth(NAME);
        if (auth instanceof AuthMode.AuthMissing missing) {
            future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                "LOGIN_REQUIRED: " + missing.reason()
                    + " (recovery: " + missing.recoveryCommand() + ")",
                List.of(), 0L, System.currentTimeMillis() - started));
            return future;
        }

        // Devin needs an API key (no OAuth path). If the resolver hands us
        // an OAuthSession (defensive — shouldn't happen given the manifest)
        // we still refuse cleanly.
        String apiKey = null;
        if (auth instanceof AuthMode.ApiKey k) apiKey = k.value();
        if (apiKey == null || apiKey.isBlank()) {
            future.complete(failed(taskId,
                "LOGIN_REQUIRED: Devin requires an API key (set DEVIN_API_KEY in your Key Chest)",
                started));
            return future;
        }
        final String authHeader = "Bearer " + apiKey;

        // Build the create-session payload up-front so unit tests can
        // assert the wire shape via buildCreateSessionBody().
        Map<String, Object> body;
        try {
            body = buildCreateSessionBody(spec, taskId);
        } catch (Exception e) {
            future.complete(failed(taskId,
                "Failed to construct Devin create-session body: " + e.getMessage(), started));
            return future;
        }

        Thread.ofVirtual().name("devin-task-" + taskId).start(() -> {
            try {
                String sessionId = createSession(body, authHeader);
                log.debug("[Devin] created session {} for task {}", sessionId, taskId);

                JsonNode terminal = pollUntilTerminal(sessionId, authHeader, started);
                long durationMs = System.currentTimeMillis() - started;

                var artifacts = parseArtifacts(taskId, spec, terminal, sessionId);
                artifactCache.put(taskId.toString(), artifacts);
                var ids = new ArrayList<UUID>();
                for (var a : artifacts) ids.add(a.artifactId());

                future.complete(new TaskResult(taskId, NAME, TaskStatus.SUCCEEDED,
                    summarise(spec, terminal, artifacts),
                    List.copyOf(ids), DEFAULT_CU_ESTIMATE, durationMs));
            } catch (TimeoutException te) {
                future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                    "Devin session exceeded wallclock cap of "
                        + config.maxWallclockHours() + " hours",
                    List.of(), 0L, System.currentTimeMillis() - started));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.complete(new TaskResult(taskId, NAME, TaskStatus.CANCELLED,
                    "Devin polling interrupted",
                    List.of(), 0L, System.currentTimeMillis() - started));
            } catch (Exception e) {
                future.complete(failed(taskId,
                    "Devin REST error: " + e.getMessage(), started));
            }
        });
        return future;
    }

    @Override
    public Stream<CodingArtifact> artifactsFor(String taskId) {
        if (taskId == null) return Stream.empty();
        var list = artifactCache.get(taskId);
        return list == null ? Stream.empty() : list.stream();
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.enabled()) return false;
            // Devin is a cloud SaaS — no local probe possible. Treat
            // "configured + auth present" as healthy. The actual API
            // surfaces errors at submit time.
            if (config.orgId() == null || config.orgId().isBlank()) return false;
            var auth = authResolver.resolveAuth(NAME);
            return !(auth instanceof AuthMode.AuthMissing);
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        // SPEC Phase 2e: HIGH default 5000. Async cloud sessions
        // routinely run for hours. Length scaling never lowers the floor
        // — only raises it past the default for unusually long
        // descriptions.
        long base = DEFAULT_CU_ESTIMATE;
        if (spec != null && spec.description() != null) {
            // Add 500 CU per ~1000 chars of description.
            base += (spec.description().length() / 1000) * 500L;
        }
        return Math.min(base, MAX_CU_ESTIMATE);
    }

    /** Snapshot of the runtime config; useful for tests + diagnostics. */
    public DevinRuntimeConfig config() { return config; }

    // -- REST payload construction (unit-testable) ------------------------

    /**
     * Build the JSON body for {@code POST /v3/organizations/{org_id}/sessions}.
     * Exposed package-private so unit tests can assert the wire shape
     * without spinning a real Devin org.
     *
     * <p>The api-key value never appears in the body — it travels in the
     * {@code Authorization} header.</p>
     */
    Map<String, Object> buildCreateSessionBody(TaskSpec spec, UUID taskId) {
        var body = new LinkedHashMap<String, Object>();
        if (spec != null) {
            if (spec.description() != null) {
                // Devin's primary input is a natural-language prompt; the
                // upstream field is `prompt`.
                //
                // Wyrdsekai items-as-tools contract — wrap with the
                // OpenHands preamble (canonical source) so Devin emits
                // the same single-.js-with-exports.manifest shape every
                // backend must produce. See OpenHandsBackend's
                // ITEMS_AS_TOOLS_PREAMBLE for the full rationale.
                // 2026-08-16 CWD sweep: Devin deliberately KEEPS the
                // /workspace variant — its session runs in a REMOTE cloud
                // sandbox with its own filesystem layout, so "current
                // working directory" wording (written for local subprocess
                // backends) would be the misleading one here.
                body.put("prompt", OpenHandsBackend.itemsAsToolsPreamble(ItemCapabilitySet.craftedDefault())
                    + "\n\n--- TASK ---\n" + spec.description());
            }
            if (spec.taskType() != null) body.put("task_type", spec.taskType());
            if (spec.workspaceHint() != null) body.put("workspace_hint", spec.workspaceHint());
            if (spec.companionDid() != null) body.put("submitted_by", spec.companionDid());
        }
        body.put("idempotency_key", taskId.toString());
        return body;
    }

    /** Resolve the create-session URL for the configured org. */
    String createSessionUrl() {
        return config.apiBase() + "/v3/organizations/" + config.orgId() + "/sessions";
    }

    /** Resolve the poll URL for a specific session. */
    String pollSessionUrl(String sessionId) {
        return config.apiBase() + "/v3/organizations/" + config.orgId()
            + "/sessions/" + sessionId;
    }

    // -- HTTP plumbing ----------------------------------------------------

    private String createSession(Map<String, Object> body, String authHeader) throws IOException, InterruptedException {
        var json = MAPPER.writeValueAsString(body);
        var req = HttpRequest.newBuilder(URI.create(createSessionUrl()))
                .timeout(config.requestTimeout())
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Devin create-session error " + resp.statusCode()
                + ": " + resp.body());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        // Upstream uses snake_case; tolerate camelCase too.
        String id = root.path("session_id").asText(
            root.path("sessionId").asText(""));
        if (id == null || id.isBlank()) {
            throw new IOException(
                "Devin create-session response missing session_id: " + resp.body());
        }
        return id;
    }

    /**
     * Poll {@code GET /v3/.../sessions/{id}} until the session reports a
     * terminal status (anything not in {@code running}/{@code pending}/
     * {@code queued}/{@code in_progress}) or the wallclock cap elapses.
     *
     * <p>Backoff: starts at the configured poll interval, doubles per
     * iteration, caps at {@link #MAX_POLL_INTERVAL}.</p>
     *
     * @throws TimeoutException when total wall-clock exceeds
     *                          {@link DevinRuntimeConfig#maxWallclockHours()}.
     */
    JsonNode pollUntilTerminal(String sessionId, String authHeader, long startedMs)
            throws IOException, InterruptedException, TimeoutException {
        var pollUrl = URI.create(pollSessionUrl(sessionId));
        long wallclockCapMs = config.maxWallclock().toMillis();
        Duration interval = config.pollInterval();
        JsonNode last = null;

        while (true) {
            long elapsed = System.currentTimeMillis() - startedMs;
            if (elapsed > wallclockCapMs) {
                throw new TimeoutException(
                    "Devin session exceeded wallclock cap of "
                        + config.maxWallclockHours() + " hours");
            }

            var req = HttpRequest.newBuilder(pollUrl)
                    .timeout(config.requestTimeout())
                    .header("Authorization", authHeader)
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("Devin poll error " + resp.statusCode()
                    + ": " + resp.body());
            }
            last = MAPPER.readTree(resp.body());
            String status = last.path("status_enum").asText(
                last.path("status").asText(""));

            if (isTerminalStatus(status)) {
                return last;
            }

            // Backoff before next poll.
            sleeper.sleep(interval.toMillis());
            interval = nextInterval(interval);
        }
    }

    /** True when the session has reached a state that won't change further. */
    static boolean isTerminalStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toLowerCase(Locale.ROOT);
        return switch (s) {
            // Running / waiting states — keep polling.
            case "running", "pending", "queued", "in_progress",
                 "starting", "initializing" -> false;
            // Anything else (stopped, blocked, finished, completed, failed,
            // expired, cancelled, …) is terminal.
            default -> true;
        };
    }

    /** Exponential-backoff step: double, capped at {@link #MAX_POLL_INTERVAL}. */
    static Duration nextInterval(Duration current) {
        Duration doubled = current.multipliedBy(2);
        if (doubled.compareTo(MAX_POLL_INTERVAL) > 0) return MAX_POLL_INTERVAL;
        return doubled;
    }

    // -- output parsing ---------------------------------------------------

    /**
     * Translate Devin's terminal session response into
     * {@link CodingArtifact}s. The PR URL + title (when present), the
     * {@code structured_output} blob, and the message tail land in
     * {@code backendMetadata}.
     */
    private List<CodingArtifact> parseArtifacts(
            UUID taskId, TaskSpec spec, JsonNode terminal, String sessionId) {
        // The workspace REPORTED on the artifact is what CodingTaskItemBridge scans for
        // the item's .js. Falling back to the process directory pointed that scan at the
        // install root on a packaged node — the same defect as running there.
        var workspace = CodingWorkspace.pathFor(
            spec != null ? spec.workspaceHint() : null,
            taskId == null ? null : taskId.toString());

        var files = new ArrayList<String>();
        if (terminal != null) {
            if (terminal.has("files") && terminal.get("files").isArray()) {
                for (JsonNode f : terminal.get("files")) {
                    if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
                }
            }
            // structured_output may also carry a files list.
            JsonNode structured = terminal.path("structured_output");
            if (structured.has("files") && structured.get("files").isArray()) {
                for (JsonNode f : structured.get("files")) {
                    if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
                }
            }
        }

        var metadata = new HashMap<String, Object>();
        metadata.put("source", "devin");
        metadata.put("backend", NAME);
        metadata.put("session_id", sessionId);
        if (terminal != null) {
            // PR fold-in.
            JsonNode pr = terminal.path("pull_request");
            if (pr.isObject()) {
                String prUrl = pr.path("url").asText("");
                String prTitle = pr.path("title").asText("");
                if (!prUrl.isBlank()) metadata.put("pull_request_url", prUrl);
                if (!prTitle.isBlank()) metadata.put("pull_request_title", prTitle);
            } else if (pr.isTextual()) {
                // Some upstream versions flatten pull_request to a URL string.
                metadata.put("pull_request_url", pr.asText());
            }

            // Status enum.
            String statusEnum = terminal.path("status_enum").asText(
                terminal.path("status").asText(""));
            if (!statusEnum.isBlank()) metadata.put("status_enum", statusEnum);

            // Messages tail (last 3 entries).
            JsonNode messages = terminal.path("messages");
            if (messages.isArray() && messages.size() > 0) {
                var tail = new ArrayList<String>();
                int from = Math.max(0, messages.size() - 3);
                for (int i = from; i < messages.size(); i++) {
                    var m = messages.get(i);
                    String text = m.path("text").asText(
                        m.path("content").asText(""));
                    if (!text.isBlank()) tail.add(text);
                }
                if (!tail.isEmpty()) metadata.put("messages_tail", List.copyOf(tail));
            }

            // structured_output as raw JSON (if present).
            JsonNode structured = terminal.path("structured_output");
            if (!structured.isMissingNode() && !structured.isNull()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> asMap = MAPPER.convertValue(structured, Map.class);
                    if (asMap != null && !asMap.isEmpty()) {
                        metadata.put("structured_output", asMap);
                    }
                } catch (Exception _) {
                    // ignore — structured_output may not be a JSON object
                }
            }

            // Total runtime (when the API exposes it).
            if (terminal.has("total_runtime_seconds")) {
                metadata.put("total_runtime_seconds",
                    terminal.get("total_runtime_seconds").asLong(0));
            } else if (terminal.has("runtime_seconds")) {
                metadata.put("total_runtime_seconds",
                    terminal.get("runtime_seconds").asLong(0));
            }

            // Origin (session originator).
            String origin = terminal.path("origin").asText("");
            if (!origin.isBlank()) metadata.put("origin", origin);
        }

        var src = new SourceArtifact(
            UUID.randomUUID(),
            NAME,
            taskId.toString(),
            workspace,
            List.copyOf(files),
            null,
            Instant.now(),
            Map.copyOf(metadata)
        );
        return List.of(src);
    }

    private TaskResult failed(UUID taskId, String summary, long startedMs) {
        return new TaskResult(taskId, NAME, TaskStatus.FAILED, summary,
            List.of(), 0L, System.currentTimeMillis() - startedMs);
    }

    private static String summarise(TaskSpec spec, JsonNode terminal,
                                    List<CodingArtifact> artifacts) {
        var taskType = spec != null && spec.taskType() != null ? spec.taskType() : "task";
        if (terminal != null) {
            JsonNode pr = terminal.path("pull_request");
            String prUrl = pr.isObject() ? pr.path("url").asText("")
                : (pr.isTextual() ? pr.asText() : "");
            if (!prUrl.isBlank()) {
                return "Devin completed the " + taskType + " — opened PR " + prUrl + ".";
            }
        }
        int files = 0;
        for (var a : artifacts) {
            if (a instanceof SourceArtifact s) files += s.files().size();
        }
        if (files > 0) {
            return "Devin completed the " + taskType + ", touching " + files + " file(s).";
        }
        return "Devin completed the " + taskType + " (no artifacts captured).";
    }

    // -- DI seams ---------------------------------------------------------

    /** Test-injectable sleeper so polling tests don't actually sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }
}
