package org.wyrdsekai.core.mcp;

import com.typesafe.config.ConfigFactory;

import org.wyrdsekai.core.skill.ContentQuarantine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central MCP gateway orchestrator (§86.2).
 * Narrative metaphor: the Docks harbor master.
 *
 * All outbound MCP calls from room scripts flow through this gateway.
 * It enforces rate limits, checks circuit breakers, injects credentials,
 * tracks costs, and logs all calls for observability.
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
    }

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
    private volatile McpGrantCheck grantCheck; // nullable — permissive when unset
    // Prompt-injection defense on EXTERNAL tool output (OWASP ASI01/03): strip
    // invisible-unicode / HTML / scripts, detect injection patterns, bound size —
    // BEFORE the result enters the agent's context. Always-on (self-contained, no
    // wiring) so external MCP results can't carry an injection payload into the
    // model. Local in-process services (Study skill, household fs) are exempt.
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
     * Set the authorization gate for {@code world.mcp()} calls (
     * MCP_TOOL). Before this hook the gateway path was unauthorized — any room
     * script could invoke any configured external service. Permissive unless a
     * strict {@link McpGrantCheck} is wired (same model as {@code McpServerManager}).
     */
    public void setGrantCheck(McpGrantCheck grantCheck) {
        this.grantCheck = grantCheck;
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
     * @param agentId   Agent making the request
     * @param zoneId    Zone the agent is in
     * @param serviceId MCP service to call
     * @param toolName  Tool within the service
     * @param params    Tool parameters
     * @return McpResult with success/failure and data
     */
    public McpResult execute(String agentId, String zoneId, String serviceId,
                              String toolName, Map<String, Object> params) {
        long start = System.currentTimeMillis();

        // 1. Service lookup
        var config = registry.get(serviceId);
        if (config.isEmpty()) {
            return McpResult.error("Unknown service: " + serviceId, serviceId, toolName, 0);
        }
        if (!config.get().enabled()) {
            return McpResult.error("Service is disabled: " + serviceId, serviceId, toolName, 0);
        }

        // 1b. Authorization (world.mcp path — previously ungated). Permissive
        // unless a strict grant check is wired; local in-process services skip
        // the gate (they carry no external capability/credential).
        var gc = grantCheck;
        if (gc != null && !localServices.containsKey(serviceId)
                && !gc.canUse(agentId, serviceId, toolName)) {
            log.debug("MCP grant denied: agent={}, service={}, tool={}", agentId, serviceId, toolName);
            return McpResult.error("Not authorized for " + serviceId + "/" + toolName
                + " — no MCP-tool grant", serviceId, toolName, 0);
        }

        // 2. Circuit breaker check
        var cbNarrative = circuitBreaker.check(serviceId);
        if (cbNarrative != null) {
            log.debug("Circuit breaker open for {}: {}", serviceId, cbNarrative);
            return McpResult.circuitOpen(cbNarrative, serviceId, toolName);
        }

        // 3. Rate limit check
        var rlNarrative = rateLimiter.check(agentId, serviceId, zoneId,
            config.get().rateLimitOverride());
        if (rlNarrative != null) {
            log.debug("Rate limited: agent={}, service={}: {}", agentId, serviceId, rlNarrative);
            return McpResult.rateLimited(rlNarrative, serviceId, toolName);
        }

        // 3b. HARD daily spend cap (0.5b). Only metered services accrue spend
        // (record() ignores cost<=0), so this trips exclusively where money
        // actually flows. The denial is a narrative the agent can speak.
        if (!localServices.containsKey(serviceId)) {
            var budgetNarrative = budgetTracker.check(agentId, serviceId);
            if (budgetNarrative != null) {
                log.warn("MCP spend cap reached: agent={}, service={} — call denied",
                    agentId, serviceId);
                return McpResult.error(budgetNarrative, serviceId, toolName, 0);
            }
        }

        // 4. Deduplication check
        String dedupKey = agentId + ":" + serviceId + ":" + toolName + ":" + params.hashCode();
        var cached = deduplicationCache.get(dedupKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < DEDUP_WINDOW_MS) {
            log.debug("Dedup hit: {}", dedupKey);
            return cached.result;
        }

        // 5. Record the request
        rateLimiter.record(agentId, serviceId, zoneId);

        // 6. Resolve auth header via TheSafe / McpKeyStore
        String authHeader = null;
        if (keyStore != null && config.get().requiresAuth()) {
            try {
                authHeader = keyStore.resolveAuth(config.get());
            } catch (Exception e) {
                log.warn("Failed to resolve auth for service {}: {}", serviceId, e.getMessage());
            }
        }

        // 7. Execute the call — in-process services first, then the transport.
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
                return result;
            } catch (Exception e) {
                // A local service throwing is a teaching error for the caller
                // (bad path, unsupported format), not a service outage — do
                // NOT feed the circuit breaker, or a few typos would seal the
                // whole service behind "the harbor master's" closed circuit.
                long elapsed = System.currentTimeMillis() - start;
                log.debug("Local MCP call refused: service={}, tool={}, reason={}",
                    serviceId, toolName, e.getMessage());
                return McpResult.error(e.getMessage(), serviceId, toolName, elapsed);
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
            String rawData = transport.callTool(
                config.get().endpoint(), toolName, remoteParams, authHeader);

            // Quarantine external tool output before it reaches the agent (§0.2 /
            // ): strip injection payloads, HTML/scripts, invisible
            // unicode; bound size. External MCP output is untrusted web-class content.
            var quarantined = externalQuarantine.sanitize(
                rawData, ContentQuarantine.ContentSource.web(serviceId));
            if (quarantined.injectionSuspected()) {
                log.warn("MCP tool output flagged for prompt-injection: service={}, tool={}, note={}",
                    serviceId, toolName, quarantined.quarantineNote());
            }
            String data = quarantined.sanitizedText();

            long elapsed = System.currentTimeMillis() - start;
            Double cost = config.get().isMetered() ? 0.001 : null;

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
            return result;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            circuitBreaker.recordFailure(serviceId);
            log.warn("MCP call failed: service={}, tool={}, error={}",
                serviceId, toolName, e.getMessage());
            return McpResult.error(e.getMessage(), serviceId, toolName, elapsed);
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
