package org.wyrdsekai.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.typesafe.config.ConfigFactory;

import org.wyrdsekai.core.library.LibraryAllowPolicy;
import org.wyrdsekai.core.library.LibraryConsent;
import org.wyrdsekai.core.mcp.transport.HttpTransportHandler;
import org.wyrdsekai.core.mcp.transport.McpToolException;
import org.wyrdsekai.core.mcp.transport.McpTransportFactory;
import org.wyrdsekai.core.mcp.transport.McpTransportHandler;
import org.wyrdsekai.core.skill.ContentQuarantine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Central MCP gateway orchestrator (§86.2).
 * Narrative metaphor: the Docks harbor master.
 *
 * Every outbound MCP call flows through this gateway: room scripts (world.mcp),
 * native MCP skills, and — through {@link McpServerManager#invokeTool} — item
 * {@code mcp.invoke}, the librarian's desk and patron, and code-mode
 * {@code mcp.execute}. It checks grants, circuit breakers, rate limits and the
 * spend cap, injects credentials, quarantines what comes back, charges the
 * price, and logs every call.
 *
 * The gateway does NOT directly call MCP servers — it delegates to a
 * transport function. This keeps it testable without real HTTP connections.
 *
 * Usage from room scripts (via world.mcp()):
 *   world.mcp("searxng", "search", { query: "hello" })
 *   → McpGatewayService.execute("agent-1", "zone-1", "searxng", "search", params)
 *   → rate limit check → circuit breaker check → delegate to transport → return McpResult
 */
public class McpGatewayService {

    private static final Logger log = LoggerFactory.getLogger(McpGatewayService.class);

    private final McpServiceRegistry registry;
    private final McpRateLimiter rateLimiter;
    private final McpCircuitBreaker circuitBreaker;
    private final McpTransport transport;

    /** Key store for resolving auth credentials via TheSafe (nullable — no auth if absent). */
    private volatile McpKeyStore keyStore;

    /** Optional cost recording callback — wired to CountingHouse when available. */
    private volatile CostRecorder costRecorder;

    /**
     * Callback for recording MCP call costs to the economy system.
     * Injected by server startup when CountingHouse is available.
     */
    @FunctionalInterface
    public interface CostRecorder {
        void record(String agentId, String serviceId, String toolName, double cost, long latencyMs);
    }

    // Request deduplication — identical calls within 5s return cached result
    private final Map<String, CachedResult> deduplicationCache = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 5000;

    /**
     * Transport abstraction for actually calling MCP servers.
     * Injected to allow testing without real HTTP connections.
     */
    @FunctionalInterface
    public interface McpTransport {
        /**
         * Call an MCP tool on a service.
         *
         * @param endpoint  Server URL
         * @param toolName  Tool to call
         * @param params    Tool parameters
         * @param authHeader Optional auth header value (from TheSafe)
         * @return Result data as string
         * @throws Exception on failure
         */
        String callTool(String endpoint, String toolName,
                         Map<String, Object> params, String authHeader) throws Exception;

        /** The call with the price the service reported, for a transport that can read one. */
        default Reply call(String endpoint, String toolName,
                           Map<String, Object> params, String authHeader) throws Exception {
            return new Reply(callTool(endpoint, toolName, params, authHeader), null);
        }
    }

    /** What came back from a service: the tool's text and the price it reported (null when none). */
    public record Reply(String text, Double reportedCost) {}

    /** A refusal at one of the gateway's own checks (circuit breaker, rate limit, spend cap, unknown service). */
    public static final class Refused extends RuntimeException {
        public Refused(String message) { super(message); }
    }

    /**
     * What a metered call is charged when the service reports no price and the steward
     * configured none ({@code price_per_call}). An estimate, not a price.
     */
    public static final double ESTIMATED_PRICE = 0.001;

    /** JSON results past this size are quarantined as text (and so truncated). */
    private static final int MAX_STRUCTURED_RESULT = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * An in-process MCP service — no HTTP, no external server. Registered via
     * {@link #registerLocalService}; calls bypass the transport (and TheSafe
     * auth) but still pass rate limiting, circuit-breaker checks, dedup, and
     * cost recording. First user: the Study's "skill" service (study.fs.*,
     * vault.doc.extract) backing the shelf/mount surface.
     */
    @FunctionalInterface
    public interface LocalMcpService {
        /**
         * @param agentId  Agent making the request
         * @param zoneId   Zone the agent is in
         * @param toolName Tool to call
         * @param params   Tool parameters (includes host-injected {@code _room}
         *                 when called from a room script via world.mcp())
         * @return Result data as string
         * @throws Exception with a message that TEACHES — it is surfaced
         *         verbatim to the caller as the error text
         */
        String call(String agentId, String zoneId, String toolName,
                     Map<String, Object> params) throws Exception;
    }

    /** In-process services by id — consulted before the remote transport. */
    private final Map<String, LocalMcpService> localServices = new ConcurrentHashMap<>();
    private volatile McpGrantCheck grantCheck; // nullable — permissive when unset (tests, embedders)
    /** Registered services that serve the household itself: the librarian playing the library role. */
    private volatile Supplier<Set<String>> householdServices = Set::of;
    /** Live connections made at boot; a connected service is called on its own connection. */
    private volatile McpServerManager connections;
    // Prompt-injection defense on EXTERNAL tool output (OWASP ASI01/03): strip
    // invisible-unicode / HTML / scripts, detect injection patterns, bound size —
    // BEFORE the result enters the agent's context. Always-on (self-contained, no
    // wiring) so external MCP results can't carry an injection payload into the
    // model. Only in-process services (the Study's own files) are exempt.
    private final ContentQuarantine externalQuarantine = new ContentQuarantine(65536);

    /**
     * Process-wide gateway, installed at startup so the companion spawn path
     * (ZoneGuardian → CompanionCapabilities) can hand a companion the SAME gateway
     * room scripts use — before Phase 1 every companion got mcpGateway=null.
     */
    private static volatile McpGatewayService shared;

    /** Install the process-wide gateway (server startup). */
    public static void installShared(McpGatewayService gateway) {
        shared = gateway;
    }

    /** The installed gateway, or null before startup wires it. */
    public static McpGatewayService shared() {
        return shared;
    }

    public McpGatewayService(McpServiceRegistry registry, McpTransport transport) {
        this(registry, new McpRateLimiter(), new McpCircuitBreaker(), transport);
    }

    public McpGatewayService(McpServiceRegistry registry, McpRateLimiter rateLimiter,
                              McpCircuitBreaker circuitBreaker, McpTransport transport) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.transport = transport;
    }

    /** Set the cost recorder (wired by server startup when CountingHouse is available). */
    public void setCostRecorder(CostRecorder recorder) {
        this.costRecorder = recorder;
    }

    /**
     * 0.5b — the HARD daily spend cap (the minimum "Accelerando safeguard").
     * ON by default: the gateway constructs its own tracker with the
     * {@code wyrdsekai.mcp.daily-spend-cap} limit (default $10/agent/service/
     * day), so there is no wiring step whose omission silently disables it.
     * Only METERED services ever accrue spend, so free/local services are
     * never denied by this gate.
     */
    private volatile McpBudgetTracker budgetTracker = new McpBudgetTracker(dailySpendCapFromConfig());

    /** Override the budget tracker (tests / a CountingHouse-backed one). */
    public void setBudgetTracker(McpBudgetTracker tracker) {
        if (tracker != null) this.budgetTracker = tracker;
    }

    /** The live budget tracker (Ledger surfaces read spend/remaining here). */
    public McpBudgetTracker budgetTracker() {
        return budgetTracker;
    }

    private static double dailySpendCapFromConfig() {
        try {
            var config = ConfigFactory.load();
            if (config.hasPath("wyrdsekai.mcp.daily-spend-cap")) {
                return config.getDouble("wyrdsekai.mcp.daily-spend-cap");
            }
        } catch (Exception e) {
            log.debug("daily-spend-cap config read failed — $10 default: {}", e.getMessage());
        }
        return 10.0;
    }

    /** Set the key store for TheSafe credential resolution. */
    public void setKeyStore(McpKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /**
     * Set the authorization gate ( MCP_TOOL). Every service needs a
     * grant except in-process services and household services
     * ({@link #setHouseholdServices}). Permissive when unset; the server wires it
     * strict unless the steward chose open grants.
     */
    public void setGrantCheck(McpGrantCheck grantCheck) {
        this.grantCheck = grantCheck;
    }

    /**
     * The registered services that serve the household itself and so need no
     * per-companion grant: the service the steward linked as the household's
     * librarian ({@code WYRDSEKAI_LIBRARY_SERVICE}). Rate limits, the circuit breaker,
     * the spend cap and the output quarantine still apply to it, and which {@code allow}
     * values may reach it is the household's rule ({@link LibraryAllowPolicy}), not the
     * library's.
     */
    public void setHouseholdServices(Supplier<Set<String>> serviceIds) {
        this.householdServices = serviceIds == null ? Set::of : serviceIds;
    }

    /** Whether a service is exempt from grants: in-process, or a household service. */
    public boolean isHouseholdService(String serviceId) {
        if (serviceId == null) return false;
        if (localServices.containsKey(serviceId)) return true;
        try {
            var ids = householdServices.get();
            return ids != null && ids.contains(serviceId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Call services that have a live boot-time connection on that connection. */
    public void useConnections(McpServerManager manager) {
        this.connections = manager;
    }

    /**
     * The production transport: a handler built from the service's own config
     * (http/stdio/ws/sse) per call — initialise, call, close — reporting the
     * price the service sent back. Services with a live connection never reach it
     * ({@link #useConnections}).
     */
    public static McpTransport handlerTransport(McpServiceRegistry registry) {
        return new McpTransport() {
            @Override
            public String callTool(String endpoint, String toolName,
                                   Map<String, Object> params, String authHeader) throws Exception {
                return call(endpoint, toolName, params, authHeader).text();
            }

            @Override
            public Reply call(String endpoint, String toolName,
                              Map<String, Object> params, String authHeader) throws Exception {
                var cfg = registry.enabledServices().stream()
                    .filter(s -> endpoint != null && endpoint.equals(s.endpoint()))
                    .findFirst()
                    .orElse(null);
                McpTransportHandler handler = cfg != null
                    ? McpTransportFactory.create(cfg, authHeader)
                    : new HttpTransportHandler(endpoint, Map.of(), authHeader);
                try {
                    handler.initialize();
                    var result = handler.callTool(toolName, params != null ? params : Map.of());
                    return new Reply(result.textContent(), result.reportedCost());
                } finally {
                    try { handler.close(); } catch (Exception ignore) { /* best-effort */ }
                }
            }
        };
    }

    /**
     * Register an in-process service. The config is registered in the service
     * registry (so {@code isAvailable}/{@code world.mcpAvailable} see it) and
     * calls to its id are routed to the handler instead of the transport.
     */
    public void registerLocalService(McpServiceConfig config, LocalMcpService service) {
        if (config == null || service == null) {
            throw new IllegalArgumentException("local service registration needs both a config and a handler");
        }
        registry.register(config);
        localServices.put(config.id(), service);
        log.info("Registered local in-process MCP service: {}", config.id());
    }

    /**
     * Execute an MCP tool call through the gateway.
     *
     * @param agentId   Who is calling: the companion, or the person whose item it is
     * @param zoneId    Zone the agent is in
     * @param serviceId MCP service to call
     * @param toolName  Tool within the service
     * @param params    Tool parameters
     * @return McpResult with success/failure and data
     */
    public McpResult execute(String agentId, String zoneId, String serviceId,
                              String toolName, Map<String, Object> params) {
        return run(agentId, zoneId, serviceId, toolName, params, false).result();
    }

    /**
     * The same door for Java callers ({@link McpServerManager#invokeTool}): returns
     * the tool's text, rethrows the transport's own exception (so a caller can tell
     * "could not reach it" from "it answered with an error"), throws
     * {@link SecurityException} when no grant allows the call and {@link Refused}
     * for the gateway's other refusals.
     */
    public String call(String agentId, String zoneId, String serviceId,
                       String toolName, Map<String, Object> params) throws Exception {
        return unwrap(run(agentId, zoneId, serviceId, toolName, params, false));
    }

    /**
     * The one call that may carry {@code allow} to the librarian: a person's own yes to a
     * {@code library_research} the library asked them about (ResearchZosho 0.5.0, the
     * {@code confirm} answer). {@code personId} is that person; what they may send is
     * {@link LibraryAllowPolicy}'s to say ({@code ["self-harm"]} for an adult, nothing for a
     * child). Every other door strips {@code allow} (see {@link #withoutAllow}).
     */
    public String callWithPersonsYes(String personId, String zoneId, String serviceId,
                                     String toolName, Map<String, Object> params) throws Exception {
        if (!RESEARCH_TOOL.equals(toolName)) {
            throw new IllegalArgumentException("a person's yes goes only on " + RESEARCH_TOOL);
        }
        return unwrap(run(personId, zoneId, serviceId, toolName, params, true));
    }

    private static String unwrap(Outcome outcome) throws Exception {
        if (outcome.failure() != null) throw outcome.failure();
        var result = outcome.result();
        if (!result.success()) {
            if (outcome.denied()) throw new SecurityException(result.error());
            throw new Refused(result.error());
        }
        return result.data();
    }

    /** The librarian's tool a person's yes goes on. */
    static final String RESEARCH_TOOL = "library_research";

    /** What a room script, a skill or any other caller is told when it sends research to the librarian itself. */
    static final String RESEARCH_THROUGH_THE_DESK = "research goes through the library desk";

    /**
     * The arguments without {@code allow}. The librarian (ResearchZosho 0.5.0) does some work
     * only when the call carries {@code allow}, and who may send which value is the household's
     * rule ({@link LibraryAllowPolicy}): a companion, an item, a room script, a skill or an
     * outside MCP client never can.
     */
    static Map<String, Object> withoutAllow(Map<String, Object> params) {
        if (params == null || !params.containsKey("allow")) return params;
        var out = new HashMap<String, Object>(params);
        out.remove("allow");
        return out;
    }

    /** Whether {@code serviceId} is the librarian the steward linked (a household service). */
    private boolean isLibrarian(String serviceId) {
        try {
            var ids = householdServices.get();
            return ids != null && serviceId != null && ids.contains(serviceId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private record Outcome(McpResult result, Exception failure, boolean denied) {
        static Outcome of(McpResult result) { return new Outcome(result, null, false); }
    }

    private Outcome run(String agentId, String zoneId, String serviceId,
                        String toolName, Map<String, Object> params, boolean personsYes) {
        long start = System.currentTimeMillis();
        if (params == null) params = Map.of();
        if (RESEARCH_TOOL.equals(toolName) && !personsYes && isLibrarian(serviceId)
                && !LibraryConsent.sendingResearch()) {
            // Research goes out only from LibraryConsent: its checks, the notice, the person's
            // yes and the day's count. A room script, a skill or an outside client calling the
            // librarian directly would skip them all.
            log.info("MCP: refused {} to the librarian from {} (service={}): not sent through the library desk",
                toolName, agentId, serviceId);
            return Outcome.of(McpResult.error(RESEARCH_THROUGH_THE_DESK, serviceId, toolName, 0));
        }
        if (params.containsKey("allow") && isLibrarian(serviceId)) {
            // Who may send which allow value is the household's decision (LibraryAllowPolicy),
            // never the library's: every caller here reaches it on the one household token.
            boolean yes = personsYes && RESEARCH_TOOL.equals(toolName);
            var who = yes ? LibraryAllowPolicy.personOf(agentId) : LibraryAllowPolicy.Caller.AGENT;
            var send = LibraryAllowPolicy.toSend(who,
                yes ? LibraryAllowPolicy.Door.OWN_YES : LibraryAllowPolicy.Door.OTHER, params.get("allow"));
            if (send.isEmpty()) {
                log.info("MCP: dropped 'allow' from a call to the librarian (service={}, tool={}, caller={}): "
                    + "not a value this caller may send", serviceId, toolName, agentId);
                params = withoutAllow(params);
            } else {
                var kept = new HashMap<String, Object>(params);
                kept.put("allow", send);
                params = kept;
            }
        }

        // 1. Service lookup
        var config = registry.get(serviceId);
        if (config.isEmpty()) {
            return Outcome.of(McpResult.error("Unknown service: " + serviceId, serviceId, toolName, 0));
        }
        if (!config.get().enabled()) {
            return Outcome.of(McpResult.error("Service is disabled: " + serviceId, serviceId, toolName, 0));
        }

        // 2. Authorization. Every service needs a grant except in-process services and
        // household services (the librarian the steward linked). Every check below applies
        // to all of them, and `allow` was settled above by the household's own rule.
        var gc = grantCheck;
        if (gc != null && !isHouseholdService(serviceId)
                && !gc.canUse(agentId, serviceId, toolName)) {
            log.debug("MCP grant denied: agent={}, service={}, tool={}", agentId, serviceId, toolName);
            return new Outcome(McpResult.error("Not authorized for " + serviceId + "/" + toolName
                + " — no MCP-tool grant", serviceId, toolName, 0), null, true);
        }

        // 3. Circuit breaker check
        var cbNarrative = circuitBreaker.check(serviceId);
        if (cbNarrative != null) {
            log.debug("Circuit breaker open for {}: {}", serviceId, cbNarrative);
            return Outcome.of(McpResult.circuitOpen(cbNarrative, serviceId, toolName));
        }

        // 4. Rate limit check
        var rlNarrative = rateLimiter.check(agentId, serviceId, zoneId,
            config.get().rateLimitOverride());
        if (rlNarrative != null) {
            log.debug("Rate limited: agent={}, service={}: {}", agentId, serviceId, rlNarrative);
            return Outcome.of(McpResult.rateLimited(rlNarrative, serviceId, toolName));
        }

        // 5. HARD daily spend cap (0.5b). Only priced calls accrue spend (record()
        // ignores cost<=0), so this trips exclusively where money actually flows.
        // The denial is a narrative the agent can speak.
        var budgetNarrative = budgetTracker.check(agentId, serviceId);
        if (budgetNarrative != null) {
            log.warn("MCP spend cap reached: agent={}, service={} — call denied",
                agentId, serviceId);
            return Outcome.of(McpResult.error(budgetNarrative, serviceId, toolName, 0));
        }

        // 6. Deduplication check
        String dedupKey = agentId + ":" + serviceId + ":" + toolName + ":" + params.hashCode();
        var cached = deduplicationCache.get(dedupKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < DEDUP_WINDOW_MS) {
            log.debug("Dedup hit: {}", dedupKey);
            return Outcome.of(cached.result);
        }

        // 7. Record the request
        rateLimiter.record(agentId, serviceId, zoneId);

        // 8. Execute the call — in-process services first, then the service itself.
        var local = localServices.get(serviceId);
        if (local != null) {
            try {
                String data = local.call(agentId, zoneId, toolName, params);
                long elapsed = System.currentTimeMillis() - start;
                circuitBreaker.recordSuccess(serviceId);
                var result = McpResult.ok(data, serviceId, toolName, elapsed, null);
                deduplicationCache.put(dedupKey, new CachedResult(result, System.currentTimeMillis()));
                log.debug("Local MCP call: agent={}, service={}, tool={}, latency={}ms",
                    agentId, serviceId, toolName, elapsed);
                return Outcome.of(result);
            } catch (Exception e) {
                // A local service throwing is a teaching error for the caller
                // (bad path, unsupported format), not a service outage — do
                // NOT feed the circuit breaker, or a few typos would seal the
                // whole service behind "the harbor master's" closed circuit.
                long elapsed = System.currentTimeMillis() - start;
                log.debug("Local MCP call refused: service={}, tool={}, reason={}",
                    serviceId, toolName, e.getMessage());
                return new Outcome(McpResult.error(e.getMessage(), serviceId, toolName, elapsed), e, false);
            }
        }

        try {
            // "_room" is a host-injected routing hint for LOCAL services
            // (see WorldApi.mcp) — don't leak it to external MCP servers.
            var remoteParams = params;
            if (params.containsKey("_room")) {
                remoteParams = new HashMap<>(params);
                remoteParams.remove("_room");
            }
            var conns = connections;
            Reply reply;
            if (conns != null && conns.isConnected(serviceId)) {
                reply = conns.callConnected(serviceId, toolName, remoteParams);
            } else {
                // Credentials via TheSafe / McpKeyStore, for a handler made for this call.
                String authHeader = null;
                if (keyStore != null && config.get().requiresAuth()) {
                    try {
                        authHeader = keyStore.resolveAuth(config.get());
                    } catch (Exception e) {
                        log.warn("Failed to resolve auth for service {}: {}", serviceId, e.getMessage());
                    }
                }
                reply = transport.call(config.get().endpoint(), toolName, remoteParams, authHeader);
            }

            // Quarantine the service's output before it reaches the agent (§0.2 /
            // ): strip injection payloads, HTML/scripts, invisible
            // unicode; bound size. External MCP output is untrusted web-class content.
            String data = quarantine(serviceId, toolName, reply == null ? null : reply.text());

            long elapsed = System.currentTimeMillis() - start;
            Double cost = priceOf(config.get(), reply == null ? null : reply.reportedCost());

            circuitBreaker.recordSuccess(serviceId);
            var result = McpResult.ok(data, serviceId, toolName, elapsed, cost);

            // Record cost to economy system + the hard-cap tracker (0.5b —
            // the tracker is what makes the NEXT over-cap call deniable).
            if (cost != null) {
                budgetTracker.record(agentId, serviceId, cost);
                if (costRecorder != null) {
                    try {
                        costRecorder.record(agentId, serviceId, toolName, cost, elapsed);
                    } catch (Exception ex) {
                        log.debug("Cost recording failed (non-fatal): {}", ex.getMessage());
                    }
                }
            }

            // Cache for dedup
            deduplicationCache.put(dedupKey, new CachedResult(result, System.currentTimeMillis()));

            log.debug("MCP call: agent={}, service={}, tool={}, latency={}ms",
                agentId, serviceId, toolName, elapsed);
            return Outcome.of(result);

        } catch (McpToolException e) {
            // The service answered with an error (declined, confirm, …): it is up, and the
            // answer is not an outage. Only the code is logged; the message can carry a
            // model's words about the question.
            long elapsed = System.currentTimeMillis() - start;
            circuitBreaker.recordSuccess(serviceId);
            log.info("MCP tool answered with an error: service={}, tool={}, code={}{}",
                serviceId, toolName, e.rpcCode(), e.dataCode() == null ? "" : " (" + e.dataCode() + ")");
            return new Outcome(McpResult.error(e.getMessage(), serviceId, toolName, elapsed), e, false);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            circuitBreaker.recordFailure(serviceId);
            log.warn("MCP call failed: service={}, tool={}, error={}",
                serviceId, toolName, e.getMessage());
            return new Outcome(McpResult.error(e.getMessage(), serviceId, toolName, elapsed), e, false);
        }
    }

    /**
     * What a call is charged: the price the service reported, else the steward's
     * {@code price_per_call}, else {@link #ESTIMATED_PRICE} on a metered service.
     * Null means free.
     */
    static Double priceOf(McpServiceConfig config, Double reported) {
        if (reported != null && Double.isFinite(reported) && reported >= 0) return reported;
        var configured = config.pricePerCall();
        if (configured != null && Double.isFinite(configured) && configured >= 0) return configured;
        return config.isMetered() ? ESTIMATED_PRICE : null;
    }

    /**
     * Quarantine a service's output. A JSON result is cleaned value by value so its
     * structure survives (a library's answer package is parsed after this); anything
     * else is cleaned as text.
     */
    String quarantine(String serviceId, String toolName, String raw) {
        if (raw == null) return null;
        var source = ContentQuarantine.ContentSource.web(serviceId);
        var structured = raw.length() <= MAX_STRUCTURED_RESULT ? jsonTree(raw) : null;
        if (structured != null) {
            var flagged = new boolean[1];
            var cleaned = quarantineTree(structured, source, flagged);
            if (flagged[0]) {
                log.warn("MCP tool output flagged for prompt-injection: service={}, tool={}",
                    serviceId, toolName);
            }
            try {
                return JSON.writeValueAsString(cleaned);
            } catch (Exception e) {
                log.debug("re-serialising a quarantined result failed; quarantining as text: {}", e.toString());
            }
        }
        var quarantined = externalQuarantine.sanitize(raw, source);
        if (quarantined.injectionSuspected()) {
            log.warn("MCP tool output flagged for prompt-injection: service={}, tool={}, note={}",
                serviceId, toolName, quarantined.quarantineNote());
        }
        return quarantined.sanitizedText();
    }

    private JsonNode quarantineTree(JsonNode node, ContentQuarantine.ContentSource source, boolean[] flagged) {
        if (node.isTextual()) {
            var q = externalQuarantine.sanitize(node.asText(), source);
            if (q.injectionSuspected()) flagged[0] = true;
            return TextNode.valueOf(q.sanitizedText());
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            for (var element : node) out.add(quarantineTree(element, source, flagged));
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            for (var field : node.properties()) {
                var key = externalQuarantine.sanitize(field.getKey(), source);
                if (key.injectionSuspected()) flagged[0] = true;
                out.set(key.sanitizedText(), quarantineTree(field.getValue(), source, flagged));
            }
            return out;
        }
        return node;
    }

    private static JsonNode jsonTree(String raw) {
        var t = raw.strip();
        if (!(t.startsWith("{") || t.startsWith("["))) return null;
        try {
            var node = JSON.readTree(t);
            return node != null && (node.isObject() || node.isArray()) ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Check if a service is available (registered, enabled, circuit not open). */
    public boolean isAvailable(String serviceId) {
        if (!registry.isAvailable(serviceId)) return false;
        return circuitBreaker.check(serviceId) == null;
    }

    /** List available tools for a service (from registry, not live query). */
    public Optional<McpServiceConfig> getServiceConfig(String serviceId) {
        return registry.get(serviceId);
    }

    /** Get remaining budget for an agent+service. */
    public int remainingBudget(String agentId, String serviceId) {
        return rateLimiter.remainingForAgent(agentId);
    }

    /** Get the service registry. */
    public McpServiceRegistry registry() {
        return registry;
    }

    /** Get the rate limiter (for testing/monitoring). */
    public McpRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /** Get the circuit breaker (for testing/monitoring). */
    public McpCircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private record CachedResult(McpResult result, long timestamp) {}
}
