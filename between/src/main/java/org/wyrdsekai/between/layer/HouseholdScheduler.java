package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Household scheduler — the OS kernel (Wave 7: Household Scheduler).
 *
 * Decides what runs, when, where, and in what order across all nodes.
 * Job types by priority: interactive > reactive > service > batch > background.
 * Higher-priority jobs preempt lower-priority jobs on the same resource.
 *
 * One node is the scheduler primary (claim/heartbeat/failover, same as companions).
 * All nodes submit jobs to NATS; the scheduler primary routes them to the best node
 * based on resource slots + RTT.
 */
public final class HouseholdScheduler {

    private static final Logger log = LoggerFactory.getLogger(HouseholdScheduler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    // ── Job types by priority ──

    public enum JobType {
        INTERACTIVE(0),   // companion responding, room script
        REACTIVE(1),      // autonomous action, bond ritual
        SERVICE(2),       // Oracle query, Library search, MCP tool
        BATCH(3),         // Forge cycle, knowledge indexing, Oracle training
        BACKGROUND(4);    // study checkpoint, Between sync, crash log

        public final int priority;
        JobType(int priority) { this.priority = priority; }
    }

    public enum JobState {
        PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    /**
     * A job in the household scheduler.
     */
    public record Job(
        @JsonProperty("id") String id,
        @JsonProperty("type") JobType type,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("submittedBy") String submittedBy,    // nodeId
        @JsonProperty("assignedTo") String assignedTo,      // nodeId (nullable = unassigned)
        @JsonProperty("state") JobState state,
        @JsonProperty("progress") double progress,          // 0.0 - 1.0
        @JsonProperty("estimatedDurationMs") long estimatedDurationMs,
        @JsonProperty("resourceRequirements") Set<String> resourceRequirements,  // "inference", "gpu", etc.
        @JsonProperty("submittedAt") Instant submittedAt,
        @JsonProperty("startedAt") Instant startedAt,       // nullable
        @JsonProperty("completedAt") Instant completedAt    // nullable
    ) {
        @JsonCreator
        public Job {}

        public Job withState(JobState newState) {
            return new Job(id, type, name, description, submittedBy, assignedTo,
                newState, progress, estimatedDurationMs, resourceRequirements,
                submittedAt, newState == JobState.RUNNING ? Instant.now() : startedAt,
                newState == JobState.COMPLETED || newState == JobState.FAILED ? Instant.now() : completedAt);
        }

        public Job withProgress(double newProgress) {
            return new Job(id, type, name, description, submittedBy, assignedTo,
                state, newProgress, estimatedDurationMs, resourceRequirements,
                submittedAt, startedAt, completedAt);
        }

        public Job withAssignment(String nodeId) {
            return new Job(id, type, name, description, submittedBy, nodeId,
                state, progress, estimatedDurationMs, resourceRequirements,
                submittedAt, startedAt, completedAt);
        }
    }

    /**
     * Resource slot availability for a node.
     */
    public record ResourceSlots(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("inferenceSlots") int inferenceSlots,
        @JsonProperty("inferenceAvailable") int inferenceAvailable,
        @JsonProperty("gpuAvailable") boolean gpuAvailable,
        @JsonProperty("cpuIdlePct") double cpuIdlePct,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public ResourceSlots {}
    }

    // ── State ──

    private final NatsBridge nats;
    private final String localNodeId;
    private final PlacementEngine placementEngine;
    private final AtomicLong jobIdCounter = new AtomicLong(0);

    /** All jobs in the scheduler: jobId → job. */
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    /** Resource slots per node. */
    private final ConcurrentHashMap<String, ResourceSlots> nodeSlots = new ConcurrentHashMap<>();

    /** Whether this node is the scheduler primary. */
    private volatile boolean isSchedulerPrimary = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { var t = new Thread(r, "household-scheduler"); t.setDaemon(true); return t; });

    public HouseholdScheduler(NatsBridge nats, String localNodeId, PlacementEngine placementEngine) {
        this.nats = nats;
        this.localNodeId = localNodeId;
        this.placementEngine = placementEngine;
    }

    /**
     * Submit a job to the scheduler.
     */
    public Job submit(JobType type, String name, String description,
                      Set<String> resourceRequirements, long estimatedDurationMs) {
        var id = localNodeId.substring(0, 4) + "-" + jobIdCounter.incrementAndGet();
        var job = new Job(id, type, name, description, localNodeId, null,
            JobState.PENDING, 0.0, estimatedDurationMs,
            resourceRequirements != null ? resourceRequirements : Set.of(),
            Instant.now(), null, null);

        jobs.put(id, job);
        nats.broadcast("scheduler", "submit", MAPPER.valueToTree(job));
        log.info("Job submitted: {} ({}) type={}", id, name, type);

        // If we're the scheduler primary, route it immediately
        if (isSchedulerPrimary) {
            routeJob(job);
        }

        return job;
    }

    /**
     * Update job progress.
     */
    public void updateProgress(String jobId, double progress) {
        var job = jobs.get(jobId);
        if (job == null) return;
        var updated = job.withProgress(progress);
        jobs.put(jobId, updated);
        nats.broadcast("scheduler", "progress", MAPPER.valueToTree(updated));
    }

    /**
     * Complete a job.
     */
    public void complete(String jobId) {
        var job = jobs.get(jobId);
        if (job == null) return;
        var updated = job.withState(JobState.COMPLETED);
        jobs.put(jobId, updated);
        nats.broadcast("scheduler", "complete", MAPPER.valueToTree(updated));
        log.info("Job completed: {} ({})", jobId, job.name());
    }

    /**
     * Cancel a job.
     */
    public void cancel(String jobId) {
        var job = jobs.get(jobId);
        if (job == null) return;
        var updated = job.withState(JobState.CANCELLED);
        jobs.put(jobId, updated);
        nats.broadcast("scheduler", "cancel", MAPPER.valueToTree(updated));
        log.info("Job cancelled: {} ({})", jobId, job.name());
    }

    /**
     * Get all active (non-completed, non-cancelled) jobs.
     */
    public List<Job> activeJobs() {
        return jobs.values().stream()
            .filter(j -> j.state() != JobState.COMPLETED
                && j.state() != JobState.CANCELLED
                && j.state() != JobState.FAILED)
            .sorted(Comparator.comparingInt(j -> j.type().priority))
            .toList();
    }

    /** Get a job by ID. */
    public Optional<Job> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /** Get all jobs. */
    public Map<String, Job> getAllJobs() { return Map.copyOf(jobs); }

    /**
     * Update resource slots for a node (called from capability gossip).
     */
    public void updateSlots(ResourceSlots slots) {
        nodeSlots.put(slots.nodeId(), slots);
    }

    /**
     * Start replication — subscribe to job updates from other nodes.
     */
    public void startReplication() {
        nats.subscribeBroadcast("scheduler", ">", env -> {
            try {
                var job = MAPPER.convertValue(env.payload(), Job.class);
                jobs.put(job.id(), job);
            } catch (Exception e) {
                log.debug("Failed to parse scheduler message: {}", e.getMessage());
            }
        });
    }

    /**
     * Claim scheduler primary role.
     */
    public void claimPrimary() {
        isSchedulerPrimary = true;
        log.info("This node is now the scheduler primary");

        // Schedule periodic routing evaluation
        scheduler.scheduleAtFixedRate(() -> {
            jobs.values().stream()
                .filter(j -> j.state() == JobState.PENDING)
                .forEach(this::routeJob);
        }, 5000, 5000, TimeUnit.MILLISECONDS);
    }

    public boolean isSchedulerPrimary() { return isSchedulerPrimary; }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ── Routing ──

    private void routeJob(Job job) {
        // Find the best node for this job based on requirements + load
        var bestNode = findBestNodeForJob(job);
        if (bestNode != null) {
            var updated = job.withAssignment(bestNode).withState(JobState.RUNNING);
            jobs.put(job.id(), updated);
            nats.broadcast("scheduler", "route", MAPPER.valueToTree(updated));
            log.info("Job {} routed to node {}", job.id(), bestNode);
        }
    }

    private String findBestNodeForJob(Job job) {
        return placementEngine.getNodeSnapshots().values().stream()
            .filter(snap -> snap.satisfiesRequirements(job.resourceRequirements()))
            .filter(snap -> !"DOWN".equals(snap.nodeState())
                && !"MAINTENANCE".equals(snap.nodeState()))
            .max(Comparator.comparingDouble(placementEngine::score))
            .map(NodeCapabilities.Snapshot::nodeId)
            .orElse(null);
    }
}
