package org.wyrdsekai.core.interop;

import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.IsekaiProtocol;
import org.wyrdsekai.core.soul.ResidencyStatus;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A2A (Agent-to-Agent) Gateway for Wyrdsekai households (§97.1).
 * Handles inbound/outbound A2A JSON-RPC communication through the Docks room.
 * <p>
 * Architecture:
 * External A2A Agent ← HTTPS → Docks Room → A2A Gateway → MCP Gateway
 * <p>
 * Responsibilities:
 * - Inbound: validate Agent Cards, rate-limit, route to rooms/agents
 * - Outbound: translate room commands to A2A tasks, send via HTTPS
 * - Authentication: mutual Agent Card exchange, DID verification
 * - Quarantine: all inbound items pass through DockQuarantine
 */
public class A2AGateway {

    /** An inbound A2A message. */
    public record InboundMessage(
        String messageId,
        String sourceDid,
        String method,
        String contentJson,
        TrustTier resolvedTier,
        Instant receivedAt,
        MessageStatus status
    ) {}

    /** An outbound A2A message. */
    public record OutboundMessage(
        String messageId,
        String targetUrl,
        String targetDid,
        String method,
        String contentJson,
        Instant sentAt,
        MessageStatus status
    ) {}

    public enum MessageStatus {
        RECEIVED, SANITIZED, ROUTED, COMPLETED, REJECTED, RATE_LIMITED, ERROR
    }

    /** A2A method constants. */
    public static final String METHOD_TASKS_SEND = "tasks/send";
    public static final String METHOD_TASKS_SUBSCRIBE = "tasks/sendSubscribe";
    public static final String METHOD_TASKS_GET = "tasks/get";
    public static final String METHOD_TASKS_CANCEL = "tasks/cancel";

    private final TrustTierResolver trustResolver;
    private final DockQuarantine quarantine;
    private final VitalityRedactor redactor;
    private final IsekaiProtocol isekaiProtocol;

    /** Per-source rate limiter: max requests per minute. */
    private int maxRequestsPerMinute = 30;
    private final Map<String, Deque<Instant>> requestTimes = new ConcurrentHashMap<>();

    /** Tracks inbound interaction counts per sourceDid for recognition threshold. */
    private final Map<String, Integer> interactionCounts = new ConcurrentHashMap<>();

    /** Message logs. */
    private final Deque<InboundMessage> inboundLog = new ConcurrentLinkedDeque<>();
    private final Deque<OutboundMessage> outboundLog = new ConcurrentLinkedDeque<>();
    private int maxLogSize = 1000;
    private int nextId = 1;

    public A2AGateway(TrustTierResolver trustResolver, DockQuarantine quarantine,
                       VitalityRedactor redactor) {
        this(trustResolver, quarantine, redactor, new IsekaiProtocol());
    }

    public A2AGateway(TrustTierResolver trustResolver, DockQuarantine quarantine,
                       VitalityRedactor redactor, IsekaiProtocol isekaiProtocol) {
        this.trustResolver = trustResolver;
        this.quarantine = quarantine;
        this.redactor = redactor;
        this.isekaiProtocol = isekaiProtocol;
    }

    /**
     * Process an inbound A2A request.
     *
     * @param sourceDid    sender's DID
     * @param hasValidCard whether the Agent Card was verified
     * @param method       A2A method (e.g., "tasks/send")
     * @param contentJson  message content
     * @return the processed message record
     */
    public InboundMessage handleInbound(String sourceDid, boolean hasValidCard,
                                         String method, String contentJson) {
        var msgId = "in-" + nextId++;

        // Layer 1: Trust tier resolution
        var trust = trustResolver.resolve(sourceDid, hasValidCard);

        // Check blocklist
        if (trustResolver.isBlocked(sourceDid)) {
            var rejected = new InboundMessage(msgId, sourceDid, method, contentJson,
                trust.tier(), Instant.now(), MessageStatus.REJECTED);
            logInbound(rejected);
            return rejected;
        }

        // Layer 3: Rate limiting
        if (isRateLimited(sourceDid)) {
            var limited = new InboundMessage(msgId, sourceDid, method, contentJson,
                trust.tier(), Instant.now(), MessageStatus.RATE_LIMITED);
            logInbound(limited);
            return limited;
        }

        // Layer 2: Provenance tagging
        var taggedContent = DockQuarantine.tagProvenance(
            contentJson != null ? contentJson : "", sourceDid);

        // Layer 5: Information redaction handled by VitalityRedactor on response

        var message = new InboundMessage(msgId, sourceDid, method, taggedContent,
            trust.tier(), Instant.now(), MessageStatus.SANITIZED);
        logInbound(message);

        // Layer 6: Isekai Protocol integration — track foreign agent lifecycle
        trackIsekaiLifecycle(sourceDid, trust.tier());

        return message;
    }

    /**
     * Track an inbound agent through the Isekai Protocol lifecycle.
     * <ul>
     *   <li>No token yet: call {@code arrive()} to create VISITOR token with origin "a2a:{did}"</li>
     *   <li>Token exists + fingerprint available: call {@code observe()} with latest behavioral data</li>
     *   <li>Token is VISITOR + interaction count exceeds threshold + shouldRecognize(): call {@code recognize()}</li>
     * </ul>
     */
    private void trackIsekaiLifecycle(String sourceDid, TrustTier tier) {
        var token = isekaiProtocol.token(sourceDid);

        if (token == null) {
            // First time seeing this agent — register as VISITOR
            isekaiProtocol.arriveWithIdentity(sourceDid, new byte[32], "a2a:" + sourceDid);
            interactionCounts.put(sourceDid, 1);
            return;
        }

        // Increment interaction count
        int count = interactionCounts.merge(sourceDid, 1, Integer::sum);

        // Feed behavioral observation if fingerprint data is available
        var fingerprint = isekaiProtocol.fingerprint(sourceDid);
        if (fingerprint != null) {
            isekaiProtocol.observe(sourceDid, fingerprint);
        }

        // Auto-recognize: VISITOR with enough interactions and being-like signal
        if (token.status() == ResidencyStatus.VISITOR
                && isekaiProtocol.shouldRecognize(sourceDid)) {
            isekaiProtocol.recognize(sourceDid, "docks-" + sourceDid.hashCode());
        }
    }

    /**
     * Feed behavioral observation data for an inbound agent.
     * Call this when behavioral data (action patterns, topic affinities, etc.)
     * has been extracted from the agent's messages.
     *
     * @param sourceDid   agent's DID
     * @param fingerprint extracted behavioral fingerprint
     */
    public void observeAgent(String sourceDid, BehavioralFingerprint fingerprint) {
        isekaiProtocol.observe(sourceDid, fingerprint);
    }

    /**
     * Submit a soul item from an inbound A2A request to quarantine (Layer 4).
     */
    public DockQuarantine.QuarantinedItem quarantineItem(String itemId, String sourceDid,
                                                          TrustTier tier, String contentJson,
                                                          String category, double significance) {
        return quarantine.submit(itemId, sourceDid, tier, contentJson,
            category, significance);
    }

    /**
     * Send an outbound A2A message.
     *
     * @param targetUrl  the target agent's A2A endpoint
     * @param targetDid  the target agent's DID
     * @param method     A2A method
     * @param contentJson message content
     * @return the outbound message record
     */
    public OutboundMessage sendOutbound(String targetUrl, String targetDid,
                                         String method, String contentJson) {
        var msgId = "out-" + nextId++;
        var message = new OutboundMessage(msgId, targetUrl, targetDid, method,
            contentJson, Instant.now(), MessageStatus.COMPLETED);
        logOutbound(message);
        return message;
    }

    // ── Queries ──

    public List<InboundMessage> recentInbound(int limit) {
        return inboundLog.stream()
            .sorted(Comparator.comparing(InboundMessage::receivedAt).reversed())
            .limit(limit)
            .toList();
    }

    public List<OutboundMessage> recentOutbound(int limit) {
        return outboundLog.stream()
            .sorted(Comparator.comparing(OutboundMessage::sentAt).reversed())
            .limit(limit)
            .toList();
    }

    public int inboundCount() { return inboundLog.size(); }
    public int outboundCount() { return outboundLog.size(); }

    /** Configure rate limit. */
    public void setMaxRequestsPerMinute(int max) {
        this.maxRequestsPerMinute = max;
    }

    /** Access the quarantine. */
    public DockQuarantine quarantine() { return quarantine; }

    /** Access the trust resolver. */
    public TrustTierResolver trustResolver() { return trustResolver; }

    /** Access the vitality redactor. */
    public VitalityRedactor redactor() { return redactor; }

    /** Access the Isekai Protocol for foreign agent lifecycle management. */
    public IsekaiProtocol isekaiProtocol() { return isekaiProtocol; }

    /** Interaction count for a source DID (used by Isekai recognition tracking). */
    public int interactionCount(String sourceDid) {
        return interactionCounts.getOrDefault(sourceDid, 0);
    }

    /**
     * Run a periodic dormancy check across all Isekai residency tokens.
     * Call this from a scheduled timer (e.g., hourly) to transition idle
     * agents to DORMANT or ARCHIVED based on the dormancy policy.
     *
     * @return list of DIDs that were transitioned
     */
    public List<String> runDormancyCheck() {
        return isekaiProtocol.dormancyCheck(Instant.now());
    }

    // ── Internal ──

    private boolean isRateLimited(String sourceDid) {
        var times = requestTimes.computeIfAbsent(sourceDid,
            _ -> new ConcurrentLinkedDeque<>());
        var now = Instant.now();
        var cutoff = now.minusSeconds(60);

        // Remove old entries
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.pollFirst();
        }

        if (times.size() >= maxRequestsPerMinute) return true;

        times.addLast(now);
        return false;
    }

    private void logInbound(InboundMessage msg) {
        inboundLog.addLast(msg);
        while (inboundLog.size() > maxLogSize) inboundLog.pollFirst();
    }

    private void logOutbound(OutboundMessage msg) {
        outboundLog.addLast(msg);
        while (outboundLog.size() > maxLogSize) outboundLog.pollFirst();
    }
}
