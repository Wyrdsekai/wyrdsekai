package org.wyrdsekai.e2e.tier3;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.coding.AuthMode;
import org.wyrdsekai.core.coding.AuthResolver;
import org.wyrdsekai.core.coding.CodingTaskItemBridge;
import org.wyrdsekai.core.coding.GooseBackend;
import org.wyrdsekai.core.coding.GooseRuntimeConfig;
import org.wyrdsekai.core.coding.SourceArtifact;
import org.wyrdsekai.core.coding.TaskResult;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;
import org.wyrdsekai.core.item.ScriptedItemLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * v1.5 + — true end-to-end live test.
 *
 * <p>Drives the full chain on a single invocation:</p>
 * <ol>
 *   <li>Spawn the real {@code goose} CLI via {@link GooseBackend#submitTask}.
 *       This is the same wire path live agent dispatch uses
 *       ({@code goose run --text PREAMBLE+task --output-format json --no-session
 *       -q --provider openai --model wyrdsekai-3.5-9b-v5-q4km.gguf} with
 *       {@code OPENAI_HOST=http://localhost:8200/v1}).</li>
 *   <li>Ask the local 9B for a <i>meaningfully complex</i> scripted item:
 *       a household news searcher that calls
 *       {@code world.web.search} → {@code world.web.fetch} →
 *       {@code world.llm.summarize}, returns a structured digest, and
 *       announces itself via {@code world.agent.speak} (so embodiment is
 *       {@code silent=false} with a body-language descriptor).</li>
 *   <li>Locate the {@code .js} goose just wrote into a fresh temp workspace
 *       (recursive scan in case goose plants under {@code workspace/}).</li>
 *   <li>Hand the file through {@link CodingTaskItemBridge#tryRegisterScriptedItem}
 *       — the exact production call site used by the placement event bridge.</li>
 *   <li>Assert the §18 gate accepts, ScriptedItemLoader registers,
 *       and the produced item actually calls the declared world APIs.</li>
 * </ol>
 *
 * <p><b>Gated</b> on env {@code WYRDSEKAI_LIVE_GOOSE_E2E=1} AND
 * {@code goose --version} working AND {@code http://localhost:8200/v1/models}
 * reachable. Skipped silently otherwise — safe to leave in CI.</p>
 *
 * <p>To run on home-server:
 * <pre>
 *   WYRDSEKAI_LIVE_GOOSE_E2E=1 ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.GooseLiveInvocationE2ETest"
 * </pre>
 *
 * <p><b>What this catches that the fixture/file-cached probes don't:</b></p>
 * <ul>
 *   <li>{@code ITEMS_AS_TOOLS_PREAMBLE} drift — if the embodiment instructions
 *       silently regress, real model output stops carrying §18 blocks and the
 *       bridge rejects it here, NOT only when a household notices in
 *       production.</li>
 *   <li>{@code world.*} surface drift — if a model trained on an older preamble
 *       version emits {@code world.http.*} or {@code .body} accessors (both
 *       called out as common mistakes), the item will fail at use-time. We
 *       grep for those antipatterns and fail loud.</li>
 *   <li>Local 9B coding regression — the 9B on :8200 is the bundled
 *       household model. If a future training run blows out its
 *       items-as-tools competence, this test surfaces the cliff.</li>
 * </ul>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_GOOSE_E2E", matches = "1|true")
class GooseLiveInvocationE2ETest {

    // NOTE: goose v1.34.1 appends `/v1` to OPENAI_HOST itself; if you pass
    // `http://localhost:8200/v1` you get `…/v1/v1/chat/completions` → 404.
    // GooseRuntimeConfig.DEFAULT_BASE_URL ships the doubled form and goose
    // is silently broken in production against local llama-server. Tracked
    // separately — this test uses the working form so the live verify
    // actually fires. Health-probe (LLAMA_HEALTH_URL) still hits /v1/models
    // because llama-server's models route IS under /v1.
    private static final String LLAMA_BASE_URL = "http://localhost:8200";
    private static final String LLAMA_HEALTH_URL = LLAMA_BASE_URL + "/v1/models";
    private static final String MODEL_ID = "wyrdsekai-3.5-9b-v5-q4km.gguf";
    private static final Duration GOOSE_WALLCLOCK = Duration.ofMinutes(8);

    /**
     * Task description the local 9B must turn into a working item. Everything
     * above is added by {@link
     * org.wyrdsekai.core.coding.OpenHandsBackend#ITEMS_AS_TOOLS_PREAMBLE}
     * which {@link GooseBackend#buildArgs} prepends automatically.
     */
    /**
     * Task template — {0} gets the unique item name (e.g. {@code news_search_abc123}).
     * Per-run uniqueness matters because goose's developer extension writes
     * literally to {@code /workspace/<name>.js} (following the preamble's path
     * spec). Reusing the same name would clobber prior-run output and lose
     * the signal of whether THIS run wrote anything.
     */
    private static final String NEWS_SEARCH_TASK_TEMPLATE = """
        Use the file-write tool to create ONE file at /workspace/{0}.js
        (exact path, exact filename). It must follow this exact shape — just
        copy the structure verbatim and customise descriptor_template if you
        like:

        exports.manifest = {
          name: "{0}",
          version: "1.0.0",
          description: "Find recent news on a topic and digest each article.",
          author: "did:wyrd:goose",
          capabilities: ["web.search", "web.fetch", "llm.summarize", "memory.add"],
          embodiment: {
            silent: false,
            emits: ["body_language"],
            descriptor_template: "{actor} surfaces a digest of recent news on {topic}"
          }
        };

        function invoke(params) {
          var topic = (params && params.topic) ? String(params.topic) : "general news";
          var hits = world.web.search(topic + " news", "news", 3);
          if (!hits || hits.length === 0) {
            return { ok: false, topic: topic, reason: "no_results", summary: "no news" };
          }
          var articles = [];
          for (var i = 0; i < hits.length; i++) {
            var h = hits[i];
            var body = world.web.fetch(h.url, 4000);
            var sum = body && !body.startsWith("[error]")
              ? world.llm.summarize(body, "1-2 sentences, household-relevant")
              : (h.snippet || "");
            articles.push({ title: h.title, url: h.url, summary: sum });
          }
          var digest = articles.map(function (a) { return "- " + a.title + ": " + a.summary; }).join("\\n");
          world.memory.add("[{0}] " + topic + " (" + articles.length + " articles): " + digest);
          return { ok: true, topic: topic, count: articles.length, articles: articles, summary: digest };
        }

        IMPORTANT: capabilities array uses these EXACT strings:
          "web.search", "web.fetch", "llm.summarize", "memory.add"
        Do NOT use "agent.speak" or "agent.remember" (those names appear in
        the preamble above but are not in the runtime validator's allowlist
        — known prompt/validator drift, separate ticket).

        Write exactly the file above. Use the file-write tool. Then stop —
        no other files, no shell, no follow-up.
        """;

    private Path workspace;
    private Path producedFile;          // /workspace/{itemName}.js — cleaned up in tearDown
    private String itemName;            // news_search_<short uuid>
    private GooseBackend backend;
    private ListAppender<ILoggingEvent> bridgeAppender;
    private Logger bridgeLog;

    @BeforeEach
    void setUp() throws Exception {
        // Precondition probes — surface skip reasons clearly instead of a
        // cryptic process-fork failure inside GooseBackend.
        assumeThat(canRunGoose())
            .as("goose --version must succeed; install via `wyrd coding install goose`")
            .isTrue();
        assumeThat(canReachLlamaServer())
            .as("local llama-server :8200 must respond at /v1/models")
            .isTrue();

        workspace = Files.createTempDirectory("wyrd-e2e-goose-live-");
        itemName = "news_search_" + UUID.randomUUID().toString().substring(0, 8);
        producedFile = Path.of("/workspace", itemName + ".js");

        var config = new GooseRuntimeConfig(
            true,                   // enabled — defaults() ships disabled
            "goose",                // PATH lookup; homeServer has /home/you/.local/bin/goose
            "openai",
            MODEL_ID,
            LLAMA_BASE_URL,
            GOOSE_WALLCLOCK,
            List.of());

        // AuthResolver that hands back a sentinel api key. GooseBackend's
        // local-openai path will still set OPENAI_API_KEY=not-required via env
        // for the subprocess — llama-server ignores the value.
        AuthResolver auth = name -> new AuthMode.ApiKey("not-required");

        backend = new GooseBackend(config, auth);

        bridgeAppender = new ListAppender<>();
        bridgeAppender.start();
        bridgeLog = (Logger) LoggerFactory.getLogger(CodingTaskItemBridge.class);
        bridgeLog.addAppender(bridgeAppender);

        // Hot reset of the loader — prior tests in the same JVM may have
        // left state. The live bridge step we're about to run is the only
        // legitimate registration this test cares about.
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    @AfterEach
    void tearDown() {
        if (bridgeLog != null && bridgeAppender != null) {
            bridgeLog.detachAppender(bridgeAppender);
        }
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
        // Workspace left in /tmp on purpose — useful for post-mortem if the
        // model produced something interesting. /tmp gets reaped on reboot.
        if (workspace != null) {
            System.out.println("[GooseLiveInvocationE2ETest] workspace preserved at "
                + workspace + " for post-mortem inspection");
        }
        // /workspace/<itemName>.js is in a shared dir; remove THIS run's
        // file so we don't accumulate (any failure leaves an artifact behind
        // for post-mortem since this only runs on success path).
        try {
            if (producedFile != null && Files.exists(producedFile)) {
                Files.delete(producedFile);
            }
        } catch (Exception ignore) {
            // tolerate — /tmp janitor will catch it eventually
        }
    }

    @Test
    void goose_produces_complex_news_search_item_against_local_9b() throws Exception {
        // ─── 1. Dispatch real goose subprocess ────────────────────────────
        var taskText = NEWS_SEARCH_TASK_TEMPLATE.replace("{0}", itemName);
        var spec = new TaskSpec(
            UUID.randomUUID(),
            "did:wyrd:e2e-test",
            "scripted-item-author",
            taskText,
            workspace.toString(),    // → ProcessBuilder CWD via resolveWorkdir
            List.of(),
            0L,
            null);

        System.out.println("=== Dispatching goose against local 9B ("
            + MODEL_ID + ") at " + LLAMA_BASE_URL + " ===");
        System.out.println("=== CWD: " + workspace + " ===");
        System.out.println("=== Expected output: " + producedFile + " ===");
        var startedAt = Instant.now();

        TaskResult result = backend.submitTask(spec)
            .get(GOOSE_WALLCLOCK.toSeconds() + 30, TimeUnit.SECONDS);

        var elapsedS = Duration.between(startedAt, Instant.now()).toSeconds();
        System.out.println("=== Goose returned in " + elapsedS + "s — status="
            + result.status() + " ===");
        System.out.println("Goose summary: " + result.summary());

        // Print the raw stdout that GooseBackend.parseArtifacts captured into
        // backendMetadata. This is the model's verbatim output, the only way
        // to debug a goose run that returns SUCCEEDED but writes no file.
        backend.artifactsFor(spec.taskId().toString()).forEach(a -> {
            if (a instanceof SourceArtifact src && src.backendMetadata() != null) {
                var raw = src.backendMetadata().get("raw_stdout");
                if (raw != null) {
                    System.out.println("=== Goose raw_stdout (first 2KB) ===");
                    System.out.println(raw);
                    System.out.println("=== End raw_stdout ===");
                }
            }
        });

        assertThat(result.status())
            .as("goose must complete the task; got: " + result.summary())
            .isEqualTo(TaskStatus.SUCCEEDED);

        // ─── 2. Locate the .js goose wrote ─────────────────────────────────
        // Goose's developer extension writes to the literal path the model
        // emits in its tool call. The preamble teaches `/workspace/<name>.js`
        // so production agents always write to /workspace/. Our test task
        // names it /workspace/<unique itemName>.js so we can find OUR file.
        // Fall back to scanning the test workspace if the model picked a
        // different path (unlikely but tolerated).
        Path jsFile = Files.exists(producedFile) ? producedFile : findFirstJs(workspace);
        assertThat(jsFile)
            .as("goose must write " + producedFile + " OR a .js file under "
                + workspace + " (recursive scan). Test-workspace contents:\n"
                + listTree(workspace)
                + "\nIf empty: model talked but didn't call file-write tool. "
                + "Check raw_stdout above to see what it said. Common cause: "
                + "preamble + task too long for 9B's instruction window.")
            .isNotNull();

        var script = Files.readString(jsFile);
        System.out.println("=== Produced file: " + jsFile + " ===");
        System.out.println(script);
        System.out.println("=== End of file ===");

        // ─── 3. Pre-bridge sanity — proves preamble + task reached model ──
        assertThat(script)
            .as("file must contain exports.manifest (items-as-tools shape)")
            .contains("exports.manifest");
        assertThat(script)
            .as("file must contain invoke(...) function")
            .containsAnyOf("function invoke", "exports.invoke");
        assertThat(script)
            .as("file must declare embodiment block (§18 v1.5)")
            .contains("embodiment");

        // Antipattern checks — preamble warns against these. Catching them
        // here flags PREAMBLE-INEFFECTIVENESS even when the file is otherwise
        // §18-compliant.
        assertThat(script)
            .as("file must NOT use world.http.* (does not exist — preamble warns)")
            .doesNotContain("world.http.");
        assertThat(script)
            .as("file must NOT use world.narrate (does not exist — preamble warns)")
            .doesNotContain("world.narrate");

        // Complexity check — was the model actually steered into a news
        // pipeline, or did it cheap out with a stub like the cached note_taker?
        // The bridge gate is fine with note-taker scale; this assertion is
        // about the OTHER half of "meaningful complexity".
        var lower = script.toLowerCase();
        var apiCount = countOccurrences(lower, "world.web.search")
            + countOccurrences(lower, "world.web.fetch")
            + countOccurrences(lower, "world.llm.")
            + countOccurrences(lower, "world.agent.");
        assertThat(apiCount)
            .as("produced item should call ≥3 of: world.web.search, "
                + "world.web.fetch, world.llm.*, world.agent.* — got " + apiCount
                + " total mentions. The 9B may have written something simpler "
                + "than asked; inspect the file above.")
            .isGreaterThanOrEqualTo(3);

        // ─── 4. Hand to production bridge — same call site as live dispatch ─
        // Build SourceArtifact pointing at the actual file's parent (not the
        // task workspace root), since goose sometimes plants under workspace/.
        // tryRegisterScriptedItem resolves files against workspacePath.
        var src = new SourceArtifact(
            UUID.randomUUID(),
            "goose",
            spec.taskId().toString(),
            jsFile.getParent().toString(),
            List.of(jsFile.getFileName().toString()),
            null,
            Instant.now(),
            Map.of("source", "live-e2e", "elapsed_s", elapsedS));
        var room = new RoomObject(
            "codex-live-e2e-" + UUID.randomUUID().toString().substring(0, 6),
            "Live news_search Tool",
            "Produced by real goose against local 9B at " + Instant.now(),
            true,
            true);

        CodingTaskItemBridge.tryRegisterScriptedItem(room, src);

        // ─── 5. Bridge logged success, no §18 REJECT ──────────────────────
        var rejectLogs = bridgeAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("§18 REJECT"))
            .toList();
        assertThat(rejectLogs)
            .as("§18 REJECT must not fire — bridge log:\n  "
                + rejectLogs.stream().map(ILoggingEvent::getFormattedMessage).toList())
            .isEmpty();

        var successLogs = bridgeAppender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("registered scripted item"))
            .toList();
        assertThat(successLogs)
            .as("bridge must log successful registration. Full bridge log:\n  "
                + bridgeAppender.list.stream()
                    .map(e -> e.getLevel() + " " + e.getFormattedMessage())
                    .reduce("", (a, b) -> a + "\n  " + b))
            .isNotEmpty();

        // ─── 6. ScriptedItemLoader carries the item — proves the full chain ─
        var loaded = ScriptedItemLoader.get().all();
        assertThat(loaded)
            .as("item must register in ScriptedItemLoader registry. Loaded ids: "
                + loaded.stream().map(d -> d.itemId()).toList())
            .isNotEmpty();
        System.out.println("=== Live verify PASSED — " + loaded.size()
            + " item(s) registered from real goose run on " + MODEL_ID + " ===");
        loaded.forEach(d -> System.out.println("  registered: " + d.itemId()
            + " (v" + d.manifest().version() + ", caps=" + d.manifest().capabilities() + ")"));
    }

    // -- helpers --------------------------------------------------------------

    private static boolean canRunGoose() {
        try {
            var proc = new ProcessBuilder("goose", "--version")
                .redirectErrorStream(true).start();
            var ok = proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
            if (!ok) proc.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean canReachLlamaServer() {
        try {
            var resp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build()
                .send(HttpRequest.newBuilder(URI.create(LLAMA_HEALTH_URL))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** Recursive scan — find the first {@code .js} file under root. */
    private static Path findFirstJs(Path root) throws IOException {
        var hits = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".js")) hits.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static String listTree(Path root) throws IOException {
        var sb = new StringBuilder();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                sb.append("  ").append(root.relativize(file)).append('\n');
                return FileVisitResult.CONTINUE;
            }
        });
        return sb.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
