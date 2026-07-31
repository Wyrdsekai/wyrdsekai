package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of companion agents within a zone (§).
 *
 * <p>Extracted from ZoneGuardian. Tracks which agents are spawned, their state,
 * and provides query methods for agent discovery. The actual Pekko actor spawning
 * is still done by ZoneGuardian — this class manages the metadata.</p>
 *
 * <p>Thread-safe singleton.</p>
 */
public class AgentSupervisor {

    private static final Logger log = LoggerFactory.getLogger(AgentSupervisor.class);

    public enum AgentState { SPAWNING, IDLE, THINKING, SLEEPING, STOPPED }

    public record AgentInfo(
        String agentId,
        String agentName,
        String roomId,
        AgentState state,
        Instant spawnedAt
    ) {}

    private static volatile AgentSupervisor instance;
    private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();

    public static AgentSupervisor init() {
        instance = new AgentSupervisor();
        return instance;
    }

    public static AgentSupervisor get() {
        return instance;
    }

    /** Register a spawned agent. Called by ZoneGuardian after CompanionActor creation. */
    public void register(String agentId, String agentName, String roomId) {
        agents.put(agentId, new AgentInfo(agentId, agentName, roomId,
            AgentState.IDLE, Instant.now()));
        log.debug("Agent registered: {} ({}) in room {}", agentName, agentId, roomId);
    }

    /** Unregister a stopped agent. */
    public void unregister(String agentId) {
        var removed = agents.remove(agentId);
        if (removed != null) {
            log.debug("Agent unregistered: {} ({})", removed.agentName(), agentId);
        }
    }

    /** Update agent state (IDLE, THINKING, SLEEPING). */
    public void updateState(String agentId, AgentState state) {
        agents.computeIfPresent(agentId, (id, info) ->
            new AgentInfo(id, info.agentName(), info.roomId(), state, info.spawnedAt()));
    }

    /** Update agent room. */
    public void updateRoom(String agentId, String roomId) {
        agents.computeIfPresent(agentId, (id, info) ->
            new AgentInfo(id, info.agentName(), roomId, info.state(), info.spawnedAt()));
    }

    /** Get info for a specific agent. */
    public AgentInfo getAgent(String agentId) {
        return agents.get(agentId);
    }

    /** Get all registered agents. */
    public Map<String, AgentInfo> allAgents() {
        return Map.copyOf(agents);
    }

    /** Get agents in a specific room. */
    public Set<String> agentsInRoom(String roomId) {
        var result = new HashSet<String>();
        for (var entry : agents.entrySet()) {
            if (roomId.equals(entry.getValue().roomId())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** Number of active agents. */
    public int agentCount() {
        return agents.size();
    }
}
