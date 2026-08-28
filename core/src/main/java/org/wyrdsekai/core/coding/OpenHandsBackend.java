package org.wyrdsekai.core.coding;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.inference.LocalInferenceEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * OpenHands implementation of {@link CodingTaskBackend}, targeting the
 * <b>V1 Agent Server</b> (per the 2026-05-05 live-verified reconciliation).
 *
 * <p><b>Live-verified contract</b> (against
 * {@code ghcr.io/openhands/agent-server:1.19.1-python}, probed 2026-05-05):
 * </p>
 * <ul>
 *   <li>{@code POST /api/conversations} — create a conversation. Body
 *       requires {@code workspace} ({@code LocalWorkspace}) and {@code
 *       agent} (with embedded {@code llm} config); optional {@code
 *       initial_message}, {@code max_iterations}, {@code
 *       confirmation_policy}, {@code stuck_detection},
 *       {@code agent_definitions}, {@code tool_module_qualnames}.
 *       Returns {@code ConversationInfo} with {@code id}.</li>
 *   <li>{@code POST /api/conversations/{id}/run} — kick off the agent
 *       loop. Returns {@code Success} (idempotent — safe to call once
 *       after creation).</li>
 *   <li>{@code GET /api/conversations/{id}/events/search?...} — paged
 *       event list. Supports {@code limit}, {@code sort_order}, and
 *       {@code timestamp__gte} for incremental polling.</li>
 *   <li>{@code GET /api/conversations/{id}} — returns
 *       {@code ConversationInfo} including {@code execution_status}
 *       (one of {@code idle, running, paused, waiting_for_confirmation,
 *       finished, error, stuck, deleting}).</li>
 *   <li>{@code GET /api/conversations/{id}/agent_final_response} —
 *       returns {@code {"response": "..."}} once the agent has produced
 *       a finish message.</li>
 *   <li>{@code DELETE /api/conversations/{id}} — clean up.</li>
 *   <li>{@code GET /health} — health probe (returns the literal string
 *       {@code "OK"} with 200 OK; <b>not</b> {@code /api/health}).</li>
 * </ul>
 *
 * <p><b>Lifecycle the adapter implements</b>:</p>
 * <ol>
 *   <li>POST create-conversation → conversation id</li>
 *   <li>POST {id}/run → start the agent loop</li>
 *   <li>Poll GET {id}/events/search incrementally (1 s → 5 s exponential
 *       backoff while quiet) and GET {id} for terminal
 *       {@code execution_status}, until terminal state or wallclock
 *       expires</li>
 *   <li>GET {id}/agent_final_response → final reply text (folded into
 *       summary)</li>
 *   <li>DELETE {id} for cleanup (best-effort)</li>
 * </ol>
 *
 * <p><b>WebSocket</b>: there is no WebSocket route in the V1 OpenAPI
 * spec ({@code /openapi.json} on a live v1.19.1 container, 2026-05-05).
 * The adapter therefore relies on REST polling. If a future build adds a
 * WS route, the polling loop can be replaced by a listener — the
 * {@link AgentServerClient#streamEvents} interface is forward-compatible.</p>
 *
 * <p><b>LLM wiring</b>: two ways to point the agent-server at our
 * llama-server. Primary: <i>container env vars</i> ({@code LLM_BASE_URL},
 * {@code LLM_API_KEY}, {@code LLM_MODEL}) — set when the container is
 * launched, picked up by litellm internally. Secondary: <i>per-conversation
 * override</i> via the {@code agent.llm} block in the create-conversation
 * body, which {@link OpenHandsRuntimeConfig} populates from
 * {@code llmBaseUrl()}/{@code llmModel()} when those are set. The
 * adapter <b>does not</b> emit {@code agent.llm.api_key} in the JSON
 * payload — keys travel via env to keep them out of submit logs.</p>
 *
 * <p>Tier: {@link BackendTier#LOCAL_HEAVY}. Auth: provider-pluggable via
 * {@code OPENHANDS_LLM_KEY} (still resolved through {@link AuthResolver}
 * even though the value is forwarded to the agent-server's env, not the
 * REST body — the resolver double-checks "is the household configured?"
 * before opening any sockets).</p>
 */
public final class OpenHandsBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link OpenHandsEventAdapter#namespace()}. */
    public static final String NAME = "openhands";

    private static final Logger log = LoggerFactory.getLogger(OpenHandsBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Live-verified REST path for creating a conversation (V1 plural). */
    static final String REST_PATH_CONVERSATIONS = "/api/conversations";

    /**
     * Items-as-tools output contract prepended to every task prompt so the
     * agent produces a single GraalJS file in the
     * {@link org.wyrdsekai.core.item.ScriptedItemDef} shape. Backend-agnostic
     * — Goose / Cline / Continue should reuse this verbatim once their
     * adapters land.
     *
     * <p>Why this exists: pre-2026-05-06 the agent received only the user's
     * raw task description. With file_editor wired, the agent then wrote
     * arbitrary files (typically Python) — meaning the resulting artifact
     * couldn't be loaded as a scripted item, and {@code use <id>} via
     * {@link ItemScriptExecutor} had nothing to invoke. The contract below
     * pins the output shape so the bridge can register a runtime
     * {@code ScriptedItemDef} from the file the agent writes.</p>
     */
    static final String ITEMS_AS_TOOLS_PREAMBLE = """
        WYRDSEKAI ITEMS-AS-TOOLS OUTPUT CONTRACT (read carefully — required).

        Your task is to produce a SINGLE GraalJS file at /workspace/<name>.js
        that follows the Wyrdsekai items-as-tools shape. This file becomes a
        scripted item placed in the player's room. When a player types
        `use <name>`, Wyrdsekai loads this file and calls invoke(params)
        inside a sandboxed GraalJS context with a `world` API binding.

        EMBODIMENT — REQUIRED, not optional.

        Every scripted item in Wyrdsekai must declare HOW IT TOUCHES THE WORLD.
        Silence is allowed but must be a *declared* choice with a reason.
        Forgetting the field is a hard reject — your file will not load.
        Decide what this tool looks like from outside BEFORE you write the
        code. Pick exactly one shape inside the manifest:

          // Silent — produces no body event when invoked.
          embodiment: { silent: true, reason: "<why this tool emits nothing>" }

          // Emits — produces one or more body events; a descriptor template
          // names how observers see the act ({actor} placeholder allowed).
          embodiment: {
            silent: false,
            emits: ["body_language"],     // or "posture_change" / "ambient_shift"
            descriptor_template: "{actor} <verb phrase observers see>"
          }

        COMMANDS — REQUIRED, not optional.

        Every scripted item must declare HOW IT IS USED. A tool nobody can
        discover is a dead tool. List the actions your invoke() understands
        in the manifest; each entry surfaces as a discovery hint in the room
        action menu and dispatches as `use <name> <args>` — the script's
        invoke(params) receives params.args = "<args>". Rules:

          commands: [
            { label: "Human-readable action", args: "verb-args-the-invoke-understands" }
          ]

          - At least ONE entry. Forgetting the field is a hard reject — your
            file will not register.
          - Every label must be non-blank. args may be "" for the no-arg
            default invoke.
          - Your invoke() MUST implement every command you declare — a
            declared-but-unhandled args string is a broken promise.

        FILE SHAPE (mandatory):

          // Brief description.
          exports.manifest = {
            name: "<short_snake_case_name>",
            version: "1.0.0",
            description: "<one-line summary>",
            author: "did:wyrd:openhands",
            capabilities: ["web.search", "web.fetch", "llm.summarize"],
              // ↑ list every Tier 2+ world.* capability your invoke() calls.
              // Tier 1 helpers (math/json/regex/date/time/crypto) need NO
              // declaration. Examples below mark which tier each surface is.
            embodiment: {
              // REQUIRED — pick one shape from EMBODIMENT block above.
              silent: false,
              emits: ["body_language"],
              descriptor_template: "{actor} works the tool with focused attention"
            },
            commands: [
              // REQUIRED — at least one entry; see COMMANDS block above.
              { label: "Run <what the default action does>", args: "" },
              { label: "Show details", args: "details" }
            ]
          };

          function invoke(params) {
            // params carries ONLY the caller's arguments:
            //   params.args    the string after `use <name> ` — always a String,
            //                  "" when the person typed no arguments
            //   params.target / params.query   the same string, older spellings
            //   params.entityId / params.roomId  who used it, and where
            //   params.locale  the USER's language as a BCP-47 tag ("en", "es", "ja")
            // Return a JSON-serialisable result object.
            return { ok: true, summary: "..." };
          }

          ⚠️ `world` is a GLOBAL. It is NOT a field of params. Write
          `world.library.search(...)` directly. Do NOT write
          `const { world } = params` — world is undefined there, and a guard
          like `if (!world) return { error: ... }` will then fire on every
          call. Live 2026-08-21: a file did exactly that, passed every
          structural check, and was refused because it broke when called.

          ⚠️ invoke MUST be reachable at the TOP LEVEL of the file. Do NOT wrap
          the file in an IIFE or any other function — a module wrapper such as
          `(function (exports) { ... })(exports)` HIDES invoke from the runtime,
          and the item is refused. Live 2026-08-21: a file shaped exactly that
          way passed every structural check and then had nothing to call.
          Two legal shapes, and only these two:
            function invoke(params) { ... }        ← at top level
            exports.invoke = function (params) { ... };

        WORLD API — REAL SURFACE (do NOT redefine; do NOT invent methods).
        ⚠️ EVERY line below shows the EXACT return type. Use it verbatim.
        Do NOT assume objects with `.body` / `.text` / `.data` fields unless
        the line explicitly shows that shape.

          // ── Tier 1 (no capability declaration needed) ───────────────────
          world.math.sum(arr)                       → Number
          world.math.mean(arr) / median / stddev / min / max / clamp / lerp  → Number
          world.json.parse(text)                    → any (parsed JSON)
          world.json.stringify(value, pretty?)      → String
          world.json.path(obj, "$.a.b")             → any
          world.regex.match(text, pattern)          → Array<String>
          world.regex.matchAll(text, pattern)       → Array<Array<String>>
          world.regex.replace(text, pat, repl)      → String
          world.date.now() / today() / weekday(ms)  → Number (ms epoch) / Number / String
          world.date.parse(iso, fmt?)               → Number (ms)
          world.date.format(ms, "yyyy-MM-dd")       → String
          world.date.add(ms, n, "day")              → Number (ms)
          world.time.now() / elapsed(thenMs)        → Number (ms)
          world.time.iso() / iso(ms) / parse(iso)   → String / String / Number
          world.time.tz()                           → String
          world.crypto.hash(text, "sha256"?)        → String (hex)
          world.crypto.hmac(key, text)              → String (hex)
          world.crypto.random(n) / uuid()           → String
          world.embed.similarity(vecA, vecB)        → Number (0..1)

          // ── Tier 2 (auto-grant; declare capability) ─────────────────────
          world.library.search(query, limit?)       cap: "library.search"     → Array<{id,text,title,score}>
          world.library.add(text, opts)             cap: "library.add"        → {id,ok}
          world.journal.write(content, opts?)       cap: "journal.write"      → {id,ok}
          world.notes.add(content, tags?)           cap: "notes.add"          → {id,ok}
          world.memory.add(content, tags?)          cap: "memory.add"         → {id,ok}
          world.tags.list()                         cap: "tags.read"          → Array<String>
          world.tags.add(id, tags)                  cap: "tags.write"         → {ok}

          // ── Tier 3 (room write — return void / no useful payload) ───────
          world.agent.speak(text)                   cap: "agent.speak"        → undefined (side-effect)
          world.agent.tell(target, message)         cap: "agent.tell"         → undefined (side-effect)
          world.agent.remember(content)             cap: "agent.remember"     → undefined (side-effect)
          world.room.emit(eventType, data)          cap: "room.emit"          → undefined (side-effect)

          // ── Tier 3 (inventory dispatch) ─────────────────────────────────
          world.inventory.list()                                              → Array<{id,name,...}>
          world.inventory.use(itemId, params)       cap: "inventory.use"      → Map (item-defined return)
            // ⚠️ EXACTLY 2 args. NOT 3. There is NO `depth` parameter.

          // ── Tier 4 (compute / inference) ────────────────────────────────
          // ⚠️ PICK THE RIGHT ONE. llm.summarize CONDENSES text that already
          // exists — it cannot invent. If the person asked for a story, a tale,
          // a retelling or anything COMPOSED, use llm.complete with a prompt
          // that says so; summarize will hand them a précis and call it a
          // story. Live 2026-08-21: asked for "a story based on what it found",
          // an item shipped calling summarize("...into two paragraphs") and
          // returned an accurate summary nobody wanted.
          //   summarize/extract/classify/analyze → about text you already have
          //   complete                           → new prose you are writing
          // ⚠️ SAY WHICH LANGUAGE. Prose you generate for the person is in THEIR
          // language — params.locale — unless their request names another. Put it
          // in the prompt: "Write in English." The language of what you FOUND must
          // not decide the language of what you SAY: live 2026-08-24, an English
          // speaker asked for a tale about a book, the library hits happened to be
          // Spanish catalog rows, and the whole story came back in Spanish.
          world.web.search(query, type?, limit?)    cap: "web.search"         → Array<{title,url,snippet}>
            // type: "general" | "news" | "videos"
          world.web.fetch(url, maxChars?)           cap: "web.fetch"          → String (plain text body!)
            // ⚠️ RETURNS A STRING. Do NOT do `.body`, `.text`, `.html`,
            // `.content` on the result — there are no such fields.
            // On error, returns a string starting with "[error]".
          world.llm.summarize(text, instruction?)   cap: "llm.summarize"      → String
          world.llm.analyze(text, prompt)           cap: "llm.analyze"        → String
          world.llm.complete(prompt, opts?)         cap: "llm.complete"       → {text, ...}
          world.llm.classify(text, labels)          cap: "llm.classify"       → {label, confidence}
          world.llm.extract(text, schema)           cap: "llm.extract"        → object
          world.embed.encode(text)                  cap: "embed.encode"       → Array<Number>
          world.schedule.in(seconds, hookName)      cap: "schedule.in"        → {id,ok}

          // ── Tier 5 (steward consent / outbound) ─────────────────────────
          world.web.post(url, body, opts?)          cap: "web.post"           → String (body)
          world.mailbox.send(to, subject, body)     cap: "mailbox.send"       → {id,ok}

          // ── Self / introspection (no declaration) ───────────────────────
          world.self.callerDid()                    → String
          world.zone() / world.timezone()           → String

        ⚠️ COMMON MISTAKES TO AVOID — observed in past runs:
          ❌  var r = world.web.fetch(url); if (r.body) { ... }
              // wrong: web.fetch returns a STRING, not an object.
              // right: var r = world.web.fetch(url, 4000); if (r && !r.startsWith("[error]")) { ... }
          ❌  world.inventory.use(id, params, 1)   // 3 args
              // wrong: inventory.use takes EXACTLY 2 args.
              // right: world.inventory.use(id, params)
          ❌  var w = world.openweather.current({...}); if (w.startsWith("[error]")) ...
              // wrong: keyed services return a MAP {success, data, error} — only
              // web.fetch returns a raw string. A string method on that map throws
              // TypeError: Unknown identifier: startsWith the first time a person
              // uses it (live 2026-08-25: a weather tool died on its first real
              // query; the smoke test missed it because the call sat behind an
              // args branch).
              // right: if (!w.success) { return { ok: false, summary: w.error.message }; }
          ❌  declaring `var r; for (...) { r = world.llm.analyze(...); }` and using `r` outside
              // wrong: in JS the variable IS hoisted, but if the loop body
              // gets short-circuited (continue), `r` stays undefined. If
              // your digest needs per-source results, push them INTO an
              // array inside the loop, not into a single var.

        DO:
          - Write exactly ONE .js file. No tests, no README, no Python, no main.py.
          - Use file_editor with command=create.
          - Place the file directly under /workspace/ (NOT a subdirectory).
          - Name it <short_snake_case_name>.js matching manifest.name.
          - List EVERY Tier 2+ surface you call in manifest.capabilities (without
            the declaration the runtime denies the call).
          - Declare at least one manifest.commands entry AND make invoke()
            handle every declared args string (switch on params.args).
          - Return a non-empty `summary` string in the result so the player sees
            something meaningful when they `use` the item.
          - When done, call finish.

        DO NOT:
          - Do NOT use Node modules (no `require('fs')`, no `require('http')`,
            no `require('path')`). The sandbox has NO Node.js runtime.
          - Do NOT call `world.http.*`, `world.narrate(...)`, `world.html.*` —
            those don't exist. Use the surfaces listed above EXACTLY.
          - Do NOT create main.py, package.json, requirements.txt, helper files.
          - Do NOT run terminal commands to execute the file — Wyrdsekai runs it.
          - Do NOT write to subdirectories.

        Below is the user's task — translate it into the file shape above.
        """;

    /**
     * The same contract for CWD-workspace backends. The original preamble
     * says {@code /workspace/<name>.js} — an OpenHands bind-mount path that
     * only exists inside OpenHands' container. Every subprocess backend uses
     * the CWD as its workspace, and an obedient agent given the original
     * wording puts the file in a {@code workspace/} SUBDIRECTORY (live,
     * CodeZaiku promotion battery case A, 2026-08-15: perfectly-shaped item,
     * wrong place; the CWD wording in case A2 landed it at the root).
     * Derived, not duplicated — the contract has one source of truth.
     * Goose + CodeZaiku switched 2026-08-15; the remaining CLI backends
     * (Codex, Gemini, Cline, Continue, OpenCode, Pi, ClaudeSdk, Devin)
     * still send the /workspace wording — sweep them when each is next
     * live-tested.
     */
    /**
     * The contract as an authoring backend should receive it: the hand-written craft
     * notes, plus the external surface GENERATED from what is registered and keyed.
     *
     * <p>Every backend must call this rather than reading the constant. The constant is
     * the durable half — the shapes and the traps, each bought with a real failure. The
     * generated half is whatever this house can actually reach today, and it is the half
     * that used to rot: on 2026-08-21 it was seventeen adapters out of date, including
     * the OpenWeather key the steward was asking to use.
     *
     * @param ceiling the capability set the item will run under; only surfaces it would
     *                genuinely permit are advertised
     */
    public static String itemsAsToolsPreamble(ItemCapabilitySet ceiling) {
        return ITEMS_AS_TOOLS_PREAMBLE + ItemApiSurface.manifestRulesBlock()
            + ItemApiSurface.callingConventionBlock()
            + ItemApiSurface.hostBlock(ceiling) + ItemApiSurface.adapterBlock(ceiling);
    }

    /** The CWD-workspace variant, with the same generated surface appended. */
    public static String itemsAsToolsPreambleCwd(ItemCapabilitySet ceiling) {
        return ITEMS_AS_TOOLS_PREAMBLE_CWD + ItemApiSurface.manifestRulesBlock()
            + ItemApiSurface.callingConventionBlock()
            + ItemApiSurface.hostBlock(ceiling) + ItemApiSurface.adapterBlock(ceiling);
    }

    static final String ITEMS_AS_TOOLS_PREAMBLE_CWD = ITEMS_AS_TOOLS_PREAMBLE
        .replace("at /workspace/<name>.js",
            "at <name>.js in the CURRENT WORKING DIRECTORY")
        .replace("directly under /workspace/ (NOT a subdirectory)",
            "directly in the current working directory (NOT a subdirectory)");

    /** Live-verified health endpoint (returns "OK" 200; <b>not</b> /api/health). */
    static final String REST_PATH_HEALTH = "/health";

    private final OpenHandsRuntimeConfig config;
    private final AuthResolver authResolver;
    private final AgentServerClientFactory clientFactory;
    private final DockerProbe dockerProbe;
    /**
     * Seam: "does this node have a local drive?" — the question that decides
     * whether missing cloud auth means REFUSE or means keyless-local. A unit
     * test must be able to answer it both ways regardless of what happens to
     * be listening on the developer machine's ports.
     */
    private final java.util.function.BooleanSupplier localDriveProbe;

    /**
     * Cache of taskId → produced artifacts. Phase 5 will replace with a
     * persistent index (mirrors {@link OpenCodeBackend}'s pattern).
     */
    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses the real REST client + Docker probe. */
    public OpenHandsBackend(OpenHandsRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, defaultClientFactory(), defaultDockerProbe());
    }

    /** Test constructor — pluggable client + Docker probe for unit tests. */
    public OpenHandsBackend(OpenHandsRuntimeConfig config,
                            AuthResolver authResolver,
                            AgentServerClientFactory clientFactory,
                            DockerProbe dockerProbe) {
        this.config = config != null ? config : OpenHandsRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name, "wyrd setup openhands",
                "AuthResolver not wired"));
        this.clientFactory = clientFactory != null ? clientFactory
            : defaultClientFactory();
        this.dockerProbe = dockerProbe != null ? dockerProbe : defaultDockerProbe();
        this.localDriveProbe = () -> LocalInferenceEndpoint.resolve().isPresent();
    }

    /** Test constructor — additionally pins the local-drive answer. */
    public OpenHandsBackend(OpenHandsRuntimeConfig config,
                            AuthResolver authResolver,
                            AgentServerClientFactory clientFactory,
                            DockerProbe dockerProbe,
                            java.util.function.BooleanSupplier localDriveProbe) {
        this.config = config != null ? config : OpenHandsRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name, "wyrd setup openhands",
                "AuthResolver not wired"));
        this.clientFactory = clientFactory != null ? clientFactory
            : defaultClientFactory();
        this.dockerProbe = dockerProbe != null ? dockerProbe : defaultDockerProbe();
        this.localDriveProbe = localDriveProbe != null ? localDriveProbe
            : () -> LocalInferenceEndpoint.resolve().isPresent();
    }

    @Override public String name() { return NAME; }

    @Override public BackendTier tier() { return BackendTier.LOCAL_HEAVY; }

    @Override
    public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
        var future = new CompletableFuture<TaskResult>();
        var started = System.currentTimeMillis();
        var taskId = spec != null && spec.taskId() != null ? spec.taskId() : UUID.randomUUID();

        if (!config.enabled()) {
            future.complete(failed(taskId,
                "OpenHands backend is disabled in config", started));
            return future;
        }

        // ── AuthResolver gate ──
        // OpenHands needs a downstream LLM key (provider-pluggable). The
        // resolver returns AuthMissing → we surface LOGIN_REQUIRED without
        // ever opening a socket. (Even though the agent-server reads its
        // LLM key from container env, we still honour the resolver as a
        // "is the household configured?" gate.)
        var auth = authResolver.resolveAuth(NAME);
        if (auth instanceof AuthMode.AuthMissing missing) {
            // The agent-server's LLM config demands an api_key FIELD; a local
            // OpenAI-compatible drive accepts any value in it. This gate used
            // to refuse whenever the Key Chest slot was empty, which turned
            // "no cloud key" into "cannot use the household's own inference" —
            // the same conflation pi's adapter had. A resolvable local drive
            // means keyless is a configuration, not an accident.
            if (localDriveProbe.getAsBoolean()) {
                auth = new AuthMode.ApiKey("local");
            } else {
                future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                    "LOGIN_REQUIRED: " + missing.reason()
                        + " (recovery: " + missing.recoveryCommand()
                        + " -- or start a local drive; OpenHands runs keyless against it)",
                    List.of(), 0L, System.currentTimeMillis() - started));
                return future;
            }
        }

        // Build the create-conversation REST payload.
        Map<String, Object> body = buildCreateConversationBody(spec, taskId, auth);

        // Run async on a virtual thread — submitTask() must not block.
        Thread.ofVirtual().name("openhands-task-" + taskId).start(() -> {
            try (AgentServerClient client = clientFactory.connect(config.agentServerUrl(),
                    config.requestTimeout())) {

                String conversationId = client.startConversation(body);
                log.debug("[OpenHands] created conversation {} for task {}",
                        conversationId, taskId);

                // Kick the agent loop off explicitly. POST /run is
                // idempotent — even if `initial_message.run=true` is
                // honoured by some builds, calling /run is safer.
                client.runConversation(conversationId);

                // Poll events + execution_status until terminal or
                // wallclock expires. The client is responsible for the
                // backoff strategy; the adapter just hands it the budget.
                List<JsonNode> events = client.streamEvents(conversationId,
                        config.maxWallclock());

                // Pull the final response + terminal status. The
                // status determines TaskStatus mapping: finished →
                // SUCCEEDED, error/stuck → FAILED. (Live-verified
                // 2026-05-05: an unreachable LLM completes the
                // conversation with execution_status=error, not
                // finished.)
                String finalResponse = client.fetchFinalResponse(conversationId);
                String terminalStatus = client.fetchTerminalStatus(conversationId);

                long durationMs = System.currentTimeMillis() - started;

                var artifacts = parseArtifacts(taskId, spec, events, finalResponse);
                artifactCache.put(taskId.toString(), artifacts);
                var ids = new ArrayList<UUID>();
                for (var a : artifacts) ids.add(a.artifactId());

                // Best-effort cleanup — log but don't fail if delete fails.
                try {
                    client.deleteConversation(conversationId);
                } catch (Exception cleanupErr) {
                    log.debug("[OpenHands] best-effort cleanup of conversation {} failed: {}",
                            conversationId, cleanupErr.getMessage());
                }

                TaskStatus mappedStatus = switch (terminalStatus) {
                    case "finished" -> TaskStatus.SUCCEEDED;
                    case "error", "stuck" -> TaskStatus.FAILED;
                    // Empty / unknown — treat as failed; we shouldn't
                    // claim success on an opaque terminal state.
                    default -> TaskStatus.FAILED;
                };
                String summary = mappedStatus == TaskStatus.SUCCEEDED
                    ? summarise(spec, artifacts, finalResponse)
                    : "OpenHands agent ended in '" + terminalStatus + "' state"
                        + (finalResponse != null && !finalResponse.isBlank()
                            ? ": " + finalResponse : "");
                future.complete(new TaskResult(taskId, NAME, mappedStatus,
                    summary, List.copyOf(ids), 0L, durationMs));
            } catch (TimeoutException timeoutErr) {
                future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                    "OpenHands task exceeded wallclock cap of "
                        + config.maxWallclock().toMinutes() + " min",
                    List.of(), 0L, System.currentTimeMillis() - started));
            } catch (Exception e) {
                future.complete(failed(taskId,
                    "OpenHands Agent Server error: " + e.getMessage(), started));
            }
        });
        return future;
    }

    @Override
    public Stream<CodingArtifact> artifactsFor(String taskId) {
        if (taskId == null) return Stream.empty();
        var cached = artifactCache.get(taskId);
        if (cached == null || cached.isEmpty()) return Stream.empty();
        return cached.stream();
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.enabled()) return false;

            // V1 Agent Server is pip-installable; Docker daemon is only
            // required when the household has opted in to the legacy
            // runtime image. We probe Docker but treat its absence as a
            // soft hint — the REST probe is the source of truth.
            boolean dockerOk = dockerProbe.isAvailable();
            if (!dockerOk) {
                log.debug("OpenHands health: Docker daemon not reachable; "
                        + "REST probe will decide health.");
            }

            try (AgentServerClient client = clientFactory.connect(config.agentServerUrl(),
                    Duration.ofSeconds(5))) {
                boolean restOk = client.probeHealth();
                if (!restOk) {
                    log.debug("OpenHands REST health probe at {} reports unhealthy",
                            config.agentServerUrl());
                    return false;
                }
                // If Docker probe failed AND REST is up, we still report
                // healthy — the V1 path doesn't require Docker.
                return true;
            } catch (Exception e) {
                log.debug("OpenHands REST health probe failed at {}: {}",
                    config.agentServerUrl(), e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        // OpenHands runs locally — host RAM/disk is the cost, not metered
        // CU. The estimates here exist so cost-policy gates have a number
        // to compare against.
        if (spec == null || spec.taskType() == null) return 100L;
        return switch (spec.taskType().toLowerCase()) {
            case "explore", "explore_unknown_repo", "survey", "research" -> 50L;
            case "implement_feature", "implement", "build" -> 200L;
            case "refactor" -> 150L;
            default -> 100L;
        };
    }

    /** Snapshot of the runtime config; useful for tests + diagnostics. */
    public OpenHandsRuntimeConfig config() { return config; }

    // ─── Phase C — invocable artifacts ──────────────────────────────────
    //
    // OpenHands keeps each task's workspace bind-mounted under a known
    // host path (config.defaultWorkingDir + "/" + taskId by default).
    // run + examine read directly from there. Sandboxing follows
    // currently L0 (bare exec); L1
    // wrapping arrives with the firejail/sandbox-exec helpers.

    /**
     * Container↔host workspace mount. Read from
     * {@code WYRDSEKAI_OPENHANDS_WORKSPACE_MOUNT} (format
     * {@code container_prefix:host_path}) so the test JVM /
     * {@link HostSubprocessRunner} can read files that OpenHands wrote
     * inside its docker container. {@link CodingWorkspaceMount#NONE}
     * when unset (production behaviour: assume workspacePath is already
     * host-readable, e.g. when running on bare metal). Same helper is
     * intended for Goose's container mode and any future sandboxed
     * adapter —.
     */
    private static final CodingWorkspaceMount WORKSPACE_MOUNT =
        CodingWorkspaceMount.fromEnv("WYRDSEKAI_OPENHANDS_WORKSPACE_MOUNT");

    @Override
    public Optional<Path> workspacePathFor(UUID artifactId) {
        if (artifactId == null) return Optional.empty();
        // Linear scan over cached artifact lists — at most a few dozen
        // entries in practice. The first SourceArtifact whose
        // workspacePath is non-blank is the right one for the task.
        // Apply the container→host workspace remap so callers
        // (HostSubprocessRunner, examineArtifact's resolve loop) see a
        // host-readable path.
        for (var list : artifactCache.values()) {
            for (var a : list) {
                if (a.artifactId().equals(artifactId)) {
                    if (a instanceof SourceArtifact src && src.workspacePath() != null
                            && !src.workspacePath().isBlank()) {
                        return Optional.of(Path.of(
                            WORKSPACE_MOUNT.toHost(src.workspacePath())));
                    }
                    // BuildArtifact — workspace lives on its sibling
                    // SourceArtifact; fall through to find it.
                }
            }
            // Find sibling SourceArtifact for any BuildArtifact in this
            // list (same task id).
            for (var a : list) {
                if (a instanceof SourceArtifact src && src.workspacePath() != null
                        && !src.workspacePath().isBlank()
                        && list.stream().anyMatch(b -> b.artifactId().equals(artifactId))) {
                    return Optional.of(Path.of(
                        WORKSPACE_MOUNT.toHost(src.workspacePath())));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public CompletableFuture<ExecResult> runArtifact(UUID artifactId,
                                                      List<String> args,
                                                      Map<String, String> env) {
        var workspaceOpt = workspacePathFor(artifactId);
        if (workspaceOpt.isEmpty()) {
            return CompletableFuture.completedFuture(
                ExecResult.notFound(NAME, artifactId == null ? "null" : artifactId.toString()));
        }
        var workspace = workspaceOpt.get();
        // Locate the SourceArtifact's metadata for an explicit override
        // before falling back to the heuristic detector.
        var src = findSourceArtifact(artifactId);
        var argv = src != null
            ? EntrypointDetector.fromMetadata(src.backendMetadata())
                .or(() -> EntrypointDetector.detect(workspace))
            : EntrypointDetector.detect(workspace);
        if (argv.isEmpty()) {
            return CompletableFuture.completedFuture(
                ExecResult.noEntrypoint(NAME, workspace.toString()));
        }
        return HostSubprocessRunner.run(workspace, argv.get(),
            args == null ? List.of() : args,
            env == null ? Map.of() : env,
            Duration.ofSeconds(30));
    }

    @Override
    public CompletableFuture<ExamineResult> examineArtifact(UUID artifactId) {
        var src = findSourceArtifact(artifactId);
        if (src == null) {
            return CompletableFuture.completedFuture(
                ExamineResult.notFound(NAME,
                    artifactId == null ? "null" : artifactId.toString()));
        }
        var workspace = src.workspacePath();
        var files = src.files() == null ? List.<String>of() : src.files();
        var previews = new LinkedHashMap<String, String>();
        if (workspace != null && !workspace.isBlank()) {
            // Resolve via the container↔host mount helper so absolute
            // /workspace paths get translated to their host equivalent
            // and bare relative paths land under the host root. Same
            // helper backs Goose's container mode + any future sandboxed
            // adapter — keep this call site small.
            var root = Path.of(workspace);
            for (var f : files) {
                if (f == null || f.isBlank()) continue;
                var p = WORKSPACE_MOUNT.resolveFile(root, f);
                try {
                    if (Files.isRegularFile(p)) {
                        var bytes = Files.readAllBytes(p);
                        var max = 4096;
                        if (bytes.length <= max) {
                            previews.put(f, new String(bytes,
                                StandardCharsets.UTF_8));
                        } else {
                            var prefix = new String(bytes, 0, max,
                                StandardCharsets.UTF_8);
                            previews.put(f, prefix
                                + "\n[…truncated " + (bytes.length - max) + "B]");
                        }
                    }
                } catch (Exception e) {
                    // Skip unreadable file — examine should never throw.
                }
            }
        }
        var notes = new ArrayList<String>();
        if (src.backendMetadata() != null) {
            var av = src.backendMetadata().get("agent_version");
            if (av != null) notes.add("agent " + av);
            var status = src.backendMetadata().get("status");
            if (status != null) notes.add("status " + status);
        }
        return CompletableFuture.completedFuture(new ExamineResult(
            artifactId, NAME, workspace,
            List.copyOf(files),
            Map.copyOf(previews),
            List.copyOf(notes),
            null));
    }

    private SourceArtifact findSourceArtifact(UUID artifactId) {
        if (artifactId == null) return null;
        for (var list : artifactCache.values()) {
            for (var a : list) {
                if (a instanceof SourceArtifact s && s.artifactId().equals(artifactId)) {
                    return s;
                }
            }
            // Caller may have passed a BuildArtifact id; return the
            // SourceArtifact in the same task bundle so examine still
            // surfaces the file list.
            for (var a : list) {
                if (a.artifactId().equals(artifactId)) {
                    return list.stream()
                        .filter(x -> x instanceof SourceArtifact)
                        .map(x -> (SourceArtifact) x)
                        .findFirst().orElse(null);
                }
            }
        }
        return null;
    }

    // -- Create-conversation REST payload ---------------------------------

    /**
     * Build the JSON body for the V1 Agent Server's create-conversation
     * REST call. Exposed package-private so unit tests can assert the
     * wire shape without spinning a real Agent Server.
     *
     * <p>Payload shape (matches live OpenAPI {@code StartConversationRequest}
     * for v1.19.1 — required: {@code workspace}, {@code agent}):</p>
     * <pre>{@code
     * {
     *   "workspace":      {"kind":"LocalWorkspace","working_dir":"/tmp/repo"},
     *   "agent":          {"llm":{"model":"openai/X","base_url":"..."}},
     *   "initial_message":{"role":"user","content":[{"type":"text","text":"..."}],"run":false},
     *   "max_iterations": 30,
     *   "confirmation_policy": {"kind":"NeverConfirm"},
     *   "stuck_detection":     true
     * }
     * }</pre>
     *
     * <p>Note that legacy adapter fields ({@code taskId}, {@code taskType},
     * {@code description}, {@code workspace} (string), {@code submittedBy},
     * {@code files}, {@code limits}, {@code provider}, {@code auth_mode})
     * are <b>also</b> emitted under the {@code tags} key so observers can
     * still trace task identity through the V1 surface — V1 accepts
     * arbitrary string tags via {@code tags: {key: value}}.</p>
     */
    Map<String, Object> buildCreateConversationBody(TaskSpec spec, UUID taskId, AuthMode auth) {
        var body = new LinkedHashMap<String, Object>();

        // ── workspace (required) ──
        var workspaceDir = spec != null && spec.workspaceHint() != null
            ? spec.workspaceHint()
            : config.defaultWorkingDir();
        body.put("workspace", Map.of(
            "kind", "LocalWorkspace",
            "working_dir", workspaceDir));

        // ── agent (required) ──
        // V1 requires `agent.llm.model` to be set on every create call —
        // pydantic validation rejects an empty {}. We therefore always
        // emit a model. The `llm_model`/`llm_base_url`/`llm_api_key`
        // config keys are the steward's override path; without them we
        // fall back to the V1 default model name (which works iff the
        // operator has wired {@code LLM_*} container env appropriately).
        // Pre-2026-05 the adapter passed an empty agent.llm block —
        // that surfaces as a 500 with "model must be specified in LLM"
        // against v1.19.1.
        //
        // <b>Note on api_key</b>: this is the <i>downstream LLM provider</i>'s
        // key, distinct from the household auth key resolved via
        // {@link AuthResolver}. The AuthResolver-resolved key never appears
        // in the JSON body (asserted by unit test). Stewards using the
        // local llama-server should set {@code llm_api_key="not-required"}
        // — litellm requires <i>some</i> string when {@code base_url} +
        // {@code model} are set per-call (env-var fallback is disabled
        // for per-call overrides).
        var agentBlock = new LinkedHashMap<String, Object>();
        var llmBlock = new LinkedHashMap<String, Object>();
        // Keyless-local completion: when the steward configured no LLM at all
        // and this node has a drive, the drive IS the llm config. Without
        // this, the keyless path got as far as a real conversation and then
        // died server-side with litellm.AuthenticationError — the body
        // carried neither base_url nor api_key, so the agent-server aimed its
        // default model at the real provider with no credentials. Auth is
        // passed in-body ONLY here: "local" is a sentinel, not a secret.
        var effectiveLlmModel = config.llmModel();
        var effectiveLlmBase = config.llmBaseUrl();
        var effectiveLlmKey = config.llmApiKey();
        if ((effectiveLlmBase == null || effectiveLlmBase.isBlank())
                && auth instanceof AuthMode.ApiKey k && "local".equals(k.value())) {
            var ep = LocalInferenceEndpoint.resolve().orElse(null);
            if (ep != null) {
                var base = ep.url().endsWith("/v1") ? ep.url() : ep.url() + "/v1";
                effectiveLlmBase = base;
                effectiveLlmKey = "local";
                if (effectiveLlmModel == null || effectiveLlmModel.isBlank()) {
                    // litellm reads the provider from the prefix before the
                    // first slash; "openai/" + anything = openai-compatible.
                    effectiveLlmModel = "openai/" + ep.modelId();
                }
            }
        }
        var modelName = (effectiveLlmModel != null && !effectiveLlmModel.isBlank())
            ? effectiveLlmModel
            : OpenHandsRuntimeConfig.V1_DEFAULT_MODEL;
        llmBlock.put("model", modelName);
        if (effectiveLlmBase != null && !effectiveLlmBase.isBlank()) {
            llmBlock.put("base_url", effectiveLlmBase);
        }
        if (effectiveLlmKey != null && !effectiveLlmKey.isBlank()) {
            llmBlock.put("api_key", effectiveLlmKey);
        }

        // Disable native tool calling for small local models. The V1 SDK's
        // NonNativeToolCallingMixin converts tool schemas to text-based
        // prompt instructions and parses tool calls from model output via
        // structured prompts/regex. This dodges the catastrophic JSON
        // tool-call escaping bug we hit live 2026-05-07: the 9B authored
        // a perfect items-as-tools .js file but mis-escaped an apostrophe
        // in a comment when emitting it as a JSON-string `file_editor`
        // arg, and litellm rejected the entire turn ("Failed to parse
        // tool call arguments as JSON ... missing closing quote"). With
        // native_tool_calling=false the same content is emitted as
        // structured text (Markdown/XML-ish) which the SDK parses
        // robustly — no JSON-in-JSON escape gauntlet.
        // Stewards may opt back in via OpenHandsRuntimeConfig if their
        // upstream model handles native tool calls cleanly (Anthropic,
        // GPT-5+, Qwen3-Coder-32B, etc.).
        if (!config.nativeToolCalling()) {
            llmBlock.put("native_tool_calling", false);
        }

        agentBlock.put("llm", llmBlock);

        // ── tools (required for actual work) ──
        // The V1 Agent Server defaults the agent's tool list to
        // ["FinishTool", "ThinkTool"] only — i.e. an agent that can
        // think and finish, but has no way to read or write files. A
        // submit without tools is therefore a no-op: the agent calls
        // FinishTool with a hallucinated "Created /workspace/X" message
        // and the conversation reports execution_status=finished
        // having never touched a file. (Live-verified 2026-05-06 against
        // v1.19.1 — see commit message + RoomActorCodingItemTest gap notes.)
        //
        // We explicitly request the V1 stock toolbelt so the agent can
        // actually edit files, run shell commands, and track multi-step
        // tasks. Tool names are the snake_case identifiers exposed by
        // {@code GET /api/tools/} (e.g. "file_editor", not
        // "FileEditorTool" — the latter is the Python class but the V1
        // tool registry keys are snake_case). Pre-fix this surfaced as
        // a 500 with "ToolDefinition 'FileEditorTool' is not registered"
        // when the agent tried to bind tools at first message.
        // No params needed — V1 defaults handle workspace scoping.
        var toolList = new ArrayList<Map<String, Object>>();
        toolList.add(Map.of("name", "file_editor", "params", Map.of()));
        toolList.add(Map.of("name", "terminal", "params", Map.of()));
        toolList.add(Map.of("name", "task_tracker", "params", Map.of()));
        agentBlock.put("tools", toolList);

        body.put("agent", agentBlock);

        // ── initial_message (optional but always sent — that's the prompt) ──
        // The user's task description is wrapped with the items-as-tools
        // contract so the agent produces a single .js file matching the
        // shape that {@link CodingTaskItemBridge}
        // can register as a runtime ScriptedItemDef. Without this wrap
        // the agent would write arbitrary code (e.g. Python scripts) and
        // {@code use <item>} would have nothing to invoke through the
        // ItemScriptExecutor path. Reusable across backends — Goose's
        // adapter should apply the same contract.
        if (spec != null && spec.description() != null && !spec.description().isBlank()) {
            var msg = new LinkedHashMap<String, Object>();
            msg.put("role", "user");
            msg.put("content", List.of(Map.of(
                "type", "text",
                "text", ITEMS_AS_TOOLS_PREAMBLE + "\n\n--- TASK ---\n"
                    + spec.description())));
            // We trigger /run explicitly — leave run=false here.
            msg.put("run", false);
            body.put("initial_message", msg);
        }

        // ── caps ──
        body.put("max_iterations", Math.max(1, config.maxIterations()));
        body.put("confirmation_policy", Map.of("kind", "NeverConfirm"));
        body.put("stuck_detection", config.stuckDetection());

        // ── tags (preserves task identity, traceable observability) ──
        // V1 enforces strict tag-key validation: keys must be lowercase
        // alphanumeric only (no '_', '-', or punctuation). Sending
        // {@code task_id} surfaces as a 500 with "Tag key 'task_id' is
        // invalid" against v1.19.1. Tag values are unrestricted. We use
        // squashed lowercase keys: {@code taskid}, {@code tasktype},
        // {@code submittedby}, {@code authmode}.
        var tags = new LinkedHashMap<String, Object>();
        tags.put("taskid", taskId.toString());
        if (spec != null) {
            if (spec.taskType() != null) {
                tags.put("tasktype", spec.taskType().toLowerCase());
            }
            if (spec.companionDid() != null) {
                tags.put("submittedby", spec.companionDid());
            }
        }
        tags.put("provider", config.defaultProvider().toLowerCase());
        tags.put("authmode", switch (auth) {
            case AuthMode.OAuthSession _ -> "oauth";
            case AuthMode.ApiKey _ -> "apikey";
            case AuthMode.AuthMissing _ -> "missing"; // unreachable — gated above
        });
        body.put("tags", tags);

        return body;
    }

    // -- output parsing ---------------------------------------------------

    /**
     * Translate the V1 Agent Server's event list into
     * {@link CodingArtifact}s.
     *
     * <p>Strategy: walk the events for {@code ActionEvent}s with file-edit
     * tool calls (e.g. {@code str_replace_editor},
     * {@code FileEditorTool}) and collect the touched file paths. The
     * synthesized {@link SourceArtifact}'s workspace is taken from the
     * spec's hint (since V1 events don't carry workspace info per-event).
     * If the agent produced a final-response text, we surface it in the
     * source-artifact metadata for downstream consumers.</p>
     */
    private List<CodingArtifact> parseArtifacts(
            UUID taskId, TaskSpec spec, List<JsonNode> events, String finalResponse) {
        if (events == null || events.isEmpty()) {
            return List.of(emptySource(taskId, spec, finalResponse));
        }

        var workspace = spec != null && spec.workspaceHint() != null
            ? spec.workspaceHint()
            : config.defaultWorkingDir();
        var files = new ArrayList<String>();
        boolean foundActions = false;
        boolean errorSeen = false;
        String agentVersion = null;

        for (JsonNode e : events) {
            if (e == null) continue;
            String kind = e.path("kind").asText("");
            if ("ActionEvent".equals(kind)) {
                foundActions = true;
                // Walk the tool_call.function.arguments for a `path` /
                // `file_path` field (the standard FileEditorTool /
                // str_replace_editor argument shape).
                JsonNode toolCall = e.path("tool_call");
                JsonNode args = toolCall.path("function").path("arguments");
                String maybePath = extractPathFromArgs(args);
                if (maybePath != null && !files.contains(maybePath)) {
                    files.add(maybePath);
                }
            } else if ("AgentErrorEvent".equals(kind) || "ConversationErrorEvent".equals(kind)
                    || "ServerErrorEvent".equals(kind)) {
                errorSeen = true;
            } else if ("SystemPromptEvent".equals(kind)) {
                // SystemPromptEvent doesn't carry agentVersion in V1, but
                // future builds may. Defensive: take whatever's there.
                if (e.has("agent_version") && e.get("agent_version").isTextual()) {
                    agentVersion = e.get("agent_version").asText();
                }
            }
        }

        if (!foundActions && finalResponse == null) {
            return List.of(emptySource(taskId, spec, finalResponse));
        }

        var metadata = new HashMap<String, Object>();
        metadata.put("source", "openhands");
        metadata.put("backend", NAME);
        metadata.put("provider", config.defaultProvider());
        metadata.put("docker_image", config.dockerImage());
        metadata.put("agent_server_url", config.agentServerUrl());
        metadata.put("event_count", events.size());
        if (agentVersion != null) metadata.put("agent_version", agentVersion);
        if (finalResponse != null && !finalResponse.isBlank()) {
            metadata.put("final_response", finalResponse);
        }
        if (errorSeen) metadata.put("error_seen", true);

        var artifacts = new ArrayList<CodingArtifact>();
        var src = new SourceArtifact(
            UUID.randomUUID(),
            NAME,
            taskId.toString(),
            workspace,
            List.copyOf(files),
            null, // OpenHands doesn't surface a git ref by default
            Instant.now(),
            Map.copyOf(metadata)
        );
        artifacts.add(src);
        return List.copyOf(artifacts);
    }

    /**
     * Best-effort extraction of a file path from a tool-call arguments
     * object. V1 tool-call arguments arrive as a JSON-encoded string in
     * {@code function.arguments} (OpenAI-tool-calling shape) — we parse
     * that string, then look for a {@code path} or {@code file_path}
     * field. Returns null when no path is present (e.g. terminal tool
     * calls, finish, think).
     */
    private String extractPathFromArgs(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) return null;
        try {
            JsonNode parsed;
            if (args.isTextual()) {
                String raw = args.asText();
                if (raw.isBlank()) return null;
                parsed = MAPPER.readTree(raw);
            } else {
                parsed = args;
            }
            for (var key : List.of("path", "file_path", "filepath", "filename")) {
                if (parsed.has(key) && parsed.get(key).isTextual()) {
                    String v = parsed.get(key).asText();
                    if (!v.isBlank()) return v;
                }
            }
        } catch (Exception ignore) {
            // Malformed args — skip silently. The trace is informational.
        }
        return null;
    }

    private SourceArtifact emptySource(UUID taskId, TaskSpec spec, String finalResponse) {
        var workspace = spec != null && spec.workspaceHint() != null
            ? spec.workspaceHint()
            : config.defaultWorkingDir();
        var metadata = new HashMap<String, Object>();
        metadata.put("source", "openhands");
        metadata.put("backend", NAME);
        metadata.put("note", "trace was empty or opaque");
        if (finalResponse != null && !finalResponse.isBlank()) {
            metadata.put("final_response", finalResponse);
        }
        return new SourceArtifact(
            UUID.randomUUID(),
            NAME,
            taskId.toString(),
            workspace,
            List.of(),
            null,
            Instant.now(),
            Map.copyOf(metadata)
        );
    }

    private TaskResult failed(UUID taskId, String summary, long startedMs) {
        return new TaskResult(taskId, NAME, TaskStatus.FAILED, summary,
            List.of(), 0L, System.currentTimeMillis() - startedMs);
    }

    private static String summarise(TaskSpec spec, List<CodingArtifact> artifacts,
                                    String finalResponse) {
        int files = 0;
        for (var a : artifacts) {
            if (a instanceof SourceArtifact s) files += s.files().size();
        }
        var taskType = spec != null && spec.taskType() != null ? spec.taskType() : "task";
        var base = artifacts.isEmpty()
            ? "OpenHands completed the " + taskType + " (no artifacts captured)."
            : "OpenHands completed the " + taskType + ", touching " + files + " file(s).";
        if (finalResponse != null && !finalResponse.isBlank()) {
            // Keep it short — Workshop logs surface this string as-is.
            String trimmed = finalResponse.length() > 240
                ? finalResponse.substring(0, 240) + "…"
                : finalResponse;
            return base + " " + trimmed;
        }
        return base;
    }

    // -- Dependency-injection seams (pluggable for tests) -----------------

    /**
     * Indirection between {@link OpenHandsBackend} and the production
     * REST stack. Tests substitute a stub that returns canned responses
     * without opening any sockets.
     */
    @FunctionalInterface
    public interface AgentServerClientFactory {
        AgentServerClient connect(String baseUrl, Duration timeout) throws Exception;
    }

    /**
     * Minimal client surface the adapter needs against the V1 Agent
     * Server. Each method maps 1:1 to a REST call (or a small loop, in
     * the case of {@link #streamEvents}). Exists so unit tests can
     * substitute canned responses without holding the V1 Agent Server's
     * full HTTP contract.
     */
    public interface AgentServerClient extends AutoCloseable {
        /** POST {@code /api/conversations} → {@code id} (string UUID). */
        String startConversation(Map<String, Object> body) throws Exception;

        /** POST {@code /api/conversations/{id}/run} → kicks off the agent loop. */
        void runConversation(String conversationId) throws Exception;

        /**
         * Poll {@code /api/conversations/{id}/events/search} +
         * {@code /api/conversations/{id}} for execution_status until a
         * terminal state ({@code finished}, {@code error}, {@code stuck})
         * is reached or {@code wallclock} elapses (in which case
         * {@link java.util.concurrent.TimeoutException} is thrown). Returns
         * the full ordered event list collected during the run.
         */
        List<JsonNode> streamEvents(String conversationId, Duration wallclock) throws Exception;

        /**
         * GET {@code /api/conversations/{id}} → {@code execution_status}.
         * Returns one of {@code finished}, {@code error}, {@code stuck},
         * or empty string when not reachable. Used by the adapter to map
         * a run to {@link TaskStatus#SUCCEEDED} (finished) or
         * {@link TaskStatus#FAILED} (error/stuck).
         */
        String fetchTerminalStatus(String conversationId) throws Exception;

        /**
         * GET {@code /api/conversations/{id}/agent_final_response} →
         * the agent's final reply text, or empty string if none.
         */
        String fetchFinalResponse(String conversationId) throws Exception;

        /** DELETE {@code /api/conversations/{id}} — best-effort cleanup. */
        void deleteConversation(String conversationId) throws Exception;

        /** GET {@code /health} → 200/2xx → true. */
        boolean probeHealth();

        @Override void close() throws Exception;
    }

    /**
     * Indirection between {@link OpenHandsBackend} and the host's Docker
     * daemon probe. {@link #healthCheck()} treats Docker absence as a
     * soft hint (the V1 pip-installable path doesn't require it).
     */
    @FunctionalInterface
    public interface DockerProbe {
        boolean isAvailable();
    }

    private static AgentServerClientFactory defaultClientFactory() {
        return (baseUrl, timeout) -> new HttpAgentServerClient(baseUrl, timeout);
    }

    private static DockerProbe defaultDockerProbe() {
        return OpenHandsBackend::probeDockerDefault;
    }

    /**
     * Cheap "is Docker reachable" probe — runs {@code docker info} with a
     * 3s deadline. Mirrors the pattern in {@code bin/wyrd}'s
     * {@code do_setup} ({@code docker info &>/dev/null 2>&1}). Skips the
     * subprocess entirely if {@code docker} isn't on PATH.
     */
    static boolean probeDockerDefault() {
        try {
            String path = System.getenv("PATH");
            if (path == null) return false;
            boolean found = false;
            for (var dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                var candidate = Path.of(dir, "docker");
                if (Files.isExecutable(candidate)) {
                    found = true;
                    break;
                }
                var podman = Path.of(dir, "podman");
                if (Files.isExecutable(podman)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;

            var pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            var process = pb.start();
            boolean done = process.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("Docker probe error: {}", e.getMessage());
            return false;
        }
    }

    // -- Default REST client implementation -------------------------------

    /**
     * Production {@link AgentServerClient} — speaks REST via
     * {@link HttpClient} (Java 25 standard library). Live-verified
     * against {@code ghcr.io/openhands/agent-server:1.19.1-python} on
     * 2026-05-05.
     */
    static final class HttpAgentServerClient implements AgentServerClient {
        private final String baseUrl;
        /**
         * Timeout for one-shot endpoints (create / run / finalResponse /
         * delete). These should fail fast on real network issues but
         * surface as errors when exceeded — they're not retried.
         */
        private final Duration timeout;
        /**
         * Timeout for polled endpoints ({@link #fetchEventsPage},
         * {@link #fetchExecutionStatus}). Short on purpose: if a single
         * GET hangs server-side past this, we abort that one request,
         * treat as "no events this tick", and continue the polling loop.
         * The wallclock cap on {@link #streamEvents} bounds total runtime
         * — using a long per-request cap as a proxy for "is the agent
         * still making progress" is brittle (single-instance LLMs can
         * legitimately hold the connection for minutes during a heavy
         * iteration). Default 10s is well above normal RTT, well below
         * any reasonable deadline.
         */
        private final Duration pollTimeout;
        private final Duration pollMin;
        private final Duration pollMax;
        private final HttpClient http;

        /** Set of execution_status values that mean "the agent stopped". */
        private static final Set<String> TERMINAL_STATES = Set.of(
            "finished", "error", "stuck"
            // intentionally NOT: idle (the initial state), running,
            // paused, waiting_for_confirmation, deleting
        );

        HttpAgentServerClient(String baseUrl, Duration timeout) {
            this(baseUrl, timeout,
                Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofSeconds(10));
        }

        HttpAgentServerClient(String baseUrl, Duration timeout,
                              Duration pollMin, Duration pollMax) {
            this(baseUrl, timeout, pollMin, pollMax, Duration.ofSeconds(10));
        }

        HttpAgentServerClient(String baseUrl, Duration timeout,
                              Duration pollMin, Duration pollMax,
                              Duration pollTimeout) {
            this.baseUrl = stripTrailingSlash(baseUrl);
            this.timeout = timeout;
            this.pollMin = pollMin;
            this.pollMax = pollMax;
            this.pollTimeout = pollTimeout;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        @Override
        public String startConversation(Map<String, Object> body) throws Exception {
            var json = MAPPER.writeValueAsString(body);
            var req = HttpRequest.newBuilder(URI.create(baseUrl + REST_PATH_CONVERSATIONS))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("create-conversation REST error "
                        + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            // V1 returns ConversationInfo with "id" (UUID string).
            String id = root.path("id").asText("");
            if (id.isBlank()) {
                // Defensive: some pre-v1.19 builds returned conversation_id.
                id = root.path("conversation_id").asText("");
            }
            if (id.isBlank()) {
                throw new IOException(
                        "create-conversation response missing id: " + resp.body());
            }
            return id;
        }

        @Override
        public void runConversation(String conversationId) throws Exception {
            var req = HttpRequest.newBuilder(URI.create(baseUrl
                    + REST_PATH_CONVERSATIONS + "/" + conversationId + "/run"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // Live-verified V1 v1.19.1 (2026-05-06): POST /api/conversations
            // already auto-starts the agent loop when an initial_message is
            // present — calling /run a second time returns 409 "Conversation
            // already running. Wait for completion or pause first." This is
            // a benign duplicate kick, not a real failure: the conversation
            // IS running (which is what we wanted). Treat 409 as a no-op so
            // streamEvents can still observe the in-flight run. All other
            // non-2xx codes still bubble as errors.
            int code = resp.statusCode();
            if (code == 409) {
                log.debug("[OpenHands] /run on {} returned 409 (already running) — benign, conversation auto-started on create",
                        conversationId);
                return;
            }
            if (code / 100 != 2) {
                throw new IOException("run-conversation REST error "
                        + code + ": " + resp.body());
            }
        }

        @Override
        public List<JsonNode> streamEvents(String conversationId, Duration wallclock)
                throws Exception {
            var deadline = Instant.now().plus(wallclock);
            var collected = new ArrayList<JsonNode>();
            var seenIds = new HashSet<String>();
            String lastTimestamp = null; // ISO-8601 string, used as timestamp__gte cursor
            Duration backoff = pollMin;
            int quietRounds = 0;

            while (true) {
                if (Instant.now().isAfter(deadline)) {
                    throw new TimeoutException(
                            "OpenHands event polling exceeded wallclock cap of "
                                    + wallclock.toMinutes() + " min");
                }

                // Pull the next page of events strictly after our last
                // cursor. Default ascending sort so the cursor advances.
                List<JsonNode> page = fetchEventsPage(conversationId, lastTimestamp);
                int newCount = 0;
                for (JsonNode e : page) {
                    String id = e.path("id").asText("");
                    if (!id.isBlank() && !seenIds.add(id)) continue;
                    collected.add(e);
                    newCount++;
                    String ts = e.path("timestamp").asText("");
                    if (!ts.isBlank()) lastTimestamp = ts;
                }

                // Check execution_status to know when to stop. A finished
                // (or errored / stuck) conversation may still have events
                // to drain; we read once more after seeing terminal,
                // catch any tail events, then break.
                String execStatus = fetchExecutionStatus(conversationId);
                if (TERMINAL_STATES.contains(execStatus)) {
                    // Drain any tail events that arrived while we were
                    // polling status.
                    List<JsonNode> tail = fetchEventsPage(conversationId, lastTimestamp);
                    for (JsonNode e : tail) {
                        String id = e.path("id").asText("");
                        if (!id.isBlank() && !seenIds.add(id)) continue;
                        collected.add(e);
                    }
                    return List.copyOf(collected);
                }

                if (newCount == 0) {
                    quietRounds++;
                    // Exponential backoff up to pollMax.
                    long nextMs = Math.min(pollMax.toMillis(),
                            backoff.toMillis() * 2L);
                    backoff = Duration.ofMillis(nextMs);
                } else {
                    quietRounds = 0;
                    backoff = pollMin;
                }

                long sleepMs = backoff.toMillis();
                long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
                if (remainingMs <= 0) {
                    throw new TimeoutException(
                            "OpenHands event polling exceeded wallclock cap of "
                                    + wallclock.toMinutes() + " min");
                }
                Thread.sleep(Math.min(sleepMs, remainingMs));

                // Avoid pathological infinite quiet — give up after ~10
                // consecutive quiet rounds at max backoff (≈50 s of no
                // events AND a non-terminal status). Surface as timeout
                // so the upstream flow can react. quietRounds isn't a
                // hard cap (deadline still wins) but stops a stuck
                // listener from racing the wallclock.
                if (quietRounds > 12 && Instant.now().isAfter(
                        deadline.minus(Duration.ofSeconds(5)))) {
                    throw new TimeoutException(
                            "OpenHands event polling: no progress before wallclock");
                }
            }
        }

        /** Fetch one page of events strictly after {@code afterTimestamp}. */
        private List<JsonNode> fetchEventsPage(String conversationId,
                                               String afterTimestamp) throws Exception {
            var query = new StringBuilder("?limit=100&sort_order=TIMESTAMP");
            if (afterTimestamp != null && !afterTimestamp.isBlank()) {
                query.append("&timestamp__gte=").append(URLEncoder.encode(
                    afterTimestamp, StandardCharsets.UTF_8));
            }
            var req = HttpRequest.newBuilder(URI.create(baseUrl
                    + REST_PATH_CONVERSATIONS + "/" + conversationId
                    + "/events/search" + query))
                    .timeout(pollTimeout)
                    .GET()
                    .build();
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException timedOut) {
                // The server held the connection past pollTimeout. Most
                // likely the agent-server is mid-iteration and just hasn't
                // flushed the page yet. Treat as "no events this tick" —
                // the streamEvents loop will retry on its next backoff.
                // The wallclock cap is the real deadline.
                log.debug("[OpenHands] events/search timed out after {}s on {}; treating as quiet tick",
                        pollTimeout.toSeconds(), conversationId);
                return List.of();
            }
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("events/search REST error "
                        + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode items = root.path("items");
            var out = new ArrayList<JsonNode>();
            if (items.isArray()) {
                for (JsonNode e : items) {
                    if (e != null && !e.isNull()) out.add(e);
                }
            }
            return out;
        }

        /**
         * GET conversation → {@code execution_status}. Returns "" on
         * transient {@link java.net.http.HttpTimeoutException} so the
         * polling loop treats it as non-terminal and keeps polling — the
         * wallclock cap on {@link #streamEvents} bounds total runtime.
         */
        private String fetchExecutionStatus(String conversationId) throws Exception {
            var req = HttpRequest.newBuilder(URI.create(baseUrl
                    + REST_PATH_CONVERSATIONS + "/" + conversationId))
                    .timeout(pollTimeout)
                    .GET()
                    .build();
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException timedOut) {
                log.debug("[OpenHands] get-conversation timed out after {}s on {}; treating as non-terminal",
                        pollTimeout.toSeconds(), conversationId);
                return "";
            }
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("get-conversation REST error "
                        + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            return root.path("execution_status").asText("");
        }

        @Override
        public String fetchTerminalStatus(String conversationId) throws Exception {
            return fetchExecutionStatus(conversationId);
        }

        @Override
        public String fetchFinalResponse(String conversationId) throws Exception {
            var req = HttpRequest.newBuilder(URI.create(baseUrl
                    + REST_PATH_CONVERSATIONS + "/" + conversationId
                    + "/agent_final_response"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException timedOut) {
                // The V1 agent-server can synthesise the final reply
                // server-side via another LLM call; a slow 9B can hold
                // this past the long-shot {@link #timeout}. Returning
                // empty is safe — artifacts are already extracted from
                // events, the summariser composes from artifact data
                // alone when finalResponse is blank, and the conversation
                // is already in a terminal state at this point.
                log.warn("[OpenHands] final-response timed out after {}s on {}; returning empty (artifacts already extracted from events)",
                        timeout.toSeconds(), conversationId);
                return "";
            }
            if (resp.statusCode() / 100 != 2) {
                // Non-terminal — just return empty; the summary still
                // composes from artifact data.
                log.debug("[OpenHands] final-response REST returned {}: {}",
                        resp.statusCode(), resp.body());
                return "";
            }
            JsonNode root = MAPPER.readTree(resp.body());
            return root.path("response").asText("");
        }

        @Override
        public void deleteConversation(String conversationId) throws Exception {
            var req = HttpRequest.newBuilder(URI.create(baseUrl
                    + REST_PATH_CONVERSATIONS + "/" + conversationId))
                    .timeout(timeout)
                    .DELETE()
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                log.debug("[OpenHands] delete-conversation REST returned {}",
                        resp.statusCode());
            }
        }

        @Override
        public boolean probeHealth() {
            try {
                var req = HttpRequest.newBuilder(URI.create(baseUrl + REST_PATH_HEALTH))
                        .timeout(timeout)
                        .GET()
                        .build();
                var resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                return resp.statusCode() / 100 == 2;
            } catch (Exception e) {
                log.debug("[OpenHands] health probe failed: {}", e.getMessage());
                return false;
            }
        }

        @Override public void close() { /* HttpClient is self-cleaning */ }

        private static String stripTrailingSlash(String url) {
            if (url == null) return "";
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

}
