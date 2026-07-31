package org.wyrdsekai.core.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks GPU-to-process assignments to prevent double-allocation.
 * Thread-safe via synchronized methods over ConcurrentHashMap.
 */
public final class GpuAllocator {

    private static final Logger log = LoggerFactory.getLogger(GpuAllocator.class);

    private final ConcurrentHashMap<Integer, String> allocations = new ConcurrentHashMap<>();

    /**
     * Result of an allocation attempt.
     */
    public record AllocationResult(boolean success, String error, List<Integer> allocated) {
        public static AllocationResult ok(List<Integer> gpus) {
            return new AllocationResult(true, null, gpus);
        }
        public static AllocationResult failed(String reason) {
            return new AllocationResult(false, reason, List.of());
        }
    }

    /**
     * Atomically reserve a set of GPUs for the given owner.
     * Fails if any GPU is already allocated to a different owner.
     *
     * @param ownerName  identifying name (e.g., "llama-server-0", "embedding-1")
     * @param gpuIndices GPU indices to allocate
     * @return result indicating success or failure
     */
    public synchronized AllocationResult allocate(String ownerName, List<Integer> gpuIndices) {
        // Check for conflicts
        for (int idx : gpuIndices) {
            String existing = allocations.get(idx);
            if (existing != null && !existing.equals(ownerName)) {
                return AllocationResult.failed(
                    "GPU " + idx + " already allocated to '" + existing + "'");
            }
        }
        // All clear — assign
        for (int idx : gpuIndices) {
            allocations.put(idx, ownerName);
        }
        log.info("Allocated GPU(s) {} to '{}'", gpuIndices, ownerName);
        return AllocationResult.ok(List.copyOf(gpuIndices));
    }

    /**
     * Release all GPUs held by the given owner.
     *
     * @param ownerName the owner to release
     * @return list of GPU indices that were freed
     */
    public synchronized List<Integer> release(String ownerName) {
        List<Integer> freed = new ArrayList<>();
        allocations.entrySet().removeIf(e -> {
            if (e.getValue().equals(ownerName)) {
                freed.add(e.getKey());
                return true;
            }
            return false;
        });
        if (!freed.isEmpty()) {
            log.info("Released GPU(s) {} from '{}'", freed, ownerName);
        }
        return freed;
    }

    /**
     * Get the subset of GPUs that are currently unallocated.
     */
    public List<GpuProbe.GpuInfo> getAvailable(List<GpuProbe.GpuInfo> allGpus) {
        return allGpus.stream()
            .filter(g -> !allocations.containsKey(g.index()))
            .collect(Collectors.toList());
    }

    /**
     * Get current allocation map (GPU index → owner name).
     */
    public Map<Integer, String> getAllocations() {
        return Collections.unmodifiableMap(new HashMap<>(allocations));
    }

    /**
     * Check if a specific GPU is allocated.
     */
    public boolean isAllocated(int gpuIndex) {
        return allocations.containsKey(gpuIndex);
    }

    /**
     * Total number of allocated GPUs.
     */
    public int allocatedCount() {
        return allocations.size();
    }
}
