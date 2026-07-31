package org.wyrdsekai.between.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Routes skill invocations across the Between mesh.
 * When a phone agent invokes a LOCAL skill, the request flows over Between
 * to the home zone. When a home agent needs PHONE data, it requests from
 * the phone node.
 *
 * Between subjects:
 *   between.{householdId}.skill.{nodeId}.invoke      — directed request
 *   between.{householdId}.skill.broadcast.invoke     — broadcast request (target unknown;
 *                                                      every node subscribes, only nodes
 *                                                      that HAVE the skill answer)
 *   between.{householdId}.skill.{nodeId}.result      — response
 *
 * <p>Note: NATS treats {@code *} as a wildcard only on SUBSCRIBE — publishing to a
 * literal {@code skill.*.invoke} reaches nobody. Broadcast therefore uses the
 * dedicated {@code skill.broadcast.invoke} subject that all bridges subscribe to.</p>
 */
public class BetweenSkillBridge {

    private final String localNodeId;
    private final String householdId;
    private final SkillRegistry localRegistry;
    private final Map<String, CompletableFuture<SkillResult>> pendingRequests = new ConcurrentHashMap<>();
    private BetweenTransport transport;

    /** Timeout for remote skill invocations. */
    private Duration remoteTimeout = Duration.ofSeconds(30);

    /** Override the remote invocation timeout (tests / latency-sensitive callers). */
    public void setRemoteTimeout(Duration timeout) {
        if (timeout != null) this.remoteTimeout = timeout;
    }

    public BetweenSkillBridge(String localNodeId, String householdId, SkillRegistry localRegistry) {
        this.localNodeId = localNodeId;
        this.householdId = householdId;
        this.localRegistry = localRegistry;
    }

    /** Set the Between transport (NATS or mock for testing). */
    public void setTransport(BetweenTransport transport) {
        this.transport = transport;
        // Subscribe to inbound skill requests directed at this node
        transport.subscribe(invokeSubject(), (subject, payload) ->
            onRemoteInvoke(payload, false));
        // Subscribe to broadcast skill requests (requester didn't know the target
        // node). Only nodes that actually have the skill answer these.
        transport.subscribe(invokeSubjectBroadcast(), (subject, payload) ->
            onRemoteInvoke(payload, true));
        // Subscribe to inbound results
        transport.subscribe(resultSubject(), this::onRemoteResult);
    }

    /**
     * Invoke a skill, routing remotely if needed.
     * If the skill is local and available, executes directly.
     * If the skill needs to run on another node, routes over Between.
     */
    public SkillResult invoke(String skillId, Map<String, Object> params,
                               SkillContext context, SkillLocality targetLocality) {
        // Can we handle it locally?
        if (canHandleLocally(skillId, targetLocality)) {
            return localRegistry.execute(skillId, params, context);
        }

        // Route remotely
        return invokeRemote(skillId, params, context);
    }

    /** Handle an inbound skill invocation from a remote node. */
    private void onRemoteInvoke(byte[] payload, boolean broadcast) {
        try {
            SkillInvokeRequest request = deserialize(payload, SkillInvokeRequest.class);

            // Never answer our own broadcast (we broadcast precisely because
            // the skill was NOT available locally).
            if (localNodeId.equals(request.sourceNodeId())) return;

            // On the broadcast subject, only nodes that HAVE the skill answer —
            // otherwise a skill-less node's error result races the real answer.
            if (broadcast && !localRegistry.hasSkill(request.skillId())) return;

            // Execute locally
            SkillContext context = SkillContext.forAgent(request.agentDid(), request.roomId(),
                Map.of(), Long.MAX_VALUE);
            SkillResult result = localRegistry.execute(request.skillId(), request.params(), context);

            // Send result back
            SkillInvokeResponse response = new SkillInvokeResponse(request.requestId(), result);
            transport.publish(resultSubjectFor(request.sourceNodeId()), serialize(response));
        } catch (Exception e) {
            // Log error — don't crash the Between listener
        }
    }

    /** Handle an inbound result from a remote skill invocation. */
    private void onRemoteResult(String subject, byte[] payload) {
        try {
            SkillInvokeResponse response = deserialize(payload, SkillInvokeResponse.class);
            CompletableFuture<SkillResult> future = pendingRequests.remove(response.requestId());
            if (future != null) {
                future.complete(response.result());
            }
        } catch (Exception e) {
            // Log error
        }
    }

    /** Send a skill invocation to a remote node and wait for the result. */
    private SkillResult invokeRemote(String skillId, Map<String, Object> params,
                                      SkillContext context) {
        if (transport == null) {
            return SkillResult.error(I18n.get("skill.between.no_transport"), 0, null, skillId);
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<SkillResult> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        SkillInvokeRequest request = new SkillInvokeRequest(
            requestId, localNodeId, skillId, params,
            context.agentDid(), context.roomId());

        // Publish to the home zone (or broadcast if target unknown)
        transport.publish(invokeSubjectBroadcast(), serialize(request));

        try {
            return future.get(remoteTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            return SkillResult.error(I18n.get("skill.between.timeout"), remoteTimeout.toMillis(), null, skillId);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            return SkillResult.error(I18n.get("skill.between.failed", e.getMessage()), 0, null, skillId);
        }
    }

    private boolean canHandleLocally(String skillId, SkillLocality targetLocality) {
        if (targetLocality == SkillLocality.ANY) {
            return localRegistry.hasSkill(skillId);
        }
        // LOCAL skills only handled by home zone nodes, PHONE only by phone nodes
        // For now, try local first
        return localRegistry.hasSkill(skillId);
    }

    // --- Subject naming ---

    private String invokeSubject() {
        return "between." + householdId + ".skill." + localNodeId + ".invoke";
    }

    private String resultSubject() {
        return "between." + householdId + ".skill." + localNodeId + ".result";
    }

    private String resultSubjectFor(String nodeId) {
        return "between." + householdId + ".skill." + nodeId + ".result";
    }

    private String invokeSubjectBroadcast() {
        // A real subject, NOT "skill.*.invoke" — publishing to a literal `*`
        // segment matches no subscription (NATS wildcards apply on subscribe only).
        return "between." + householdId + ".skill.broadcast.invoke";
    }

    // --- Serialization (simplified — real impl uses Jackson) ---

    private <T> byte[] serialize(T obj) {
        try {
            return new ObjectMapper().writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    private <T> T deserialize(byte[] data, Class<T> type) {
        try {
            return new ObjectMapper().readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    // --- Wire protocol records ---

    public record SkillInvokeRequest(
        String requestId,
        String sourceNodeId,
        String skillId,
        Map<String, Object> params,
        String agentDid,
        String roomId
    ) {}

    public record SkillInvokeResponse(
        String requestId,
        SkillResult result
    ) {}

    /** Transport abstraction for Between messaging. */
    public interface BetweenTransport {
        void publish(String subject, byte[] payload);
        void subscribe(String subject, BiConsumer<String, byte[]> handler);
    }
}
