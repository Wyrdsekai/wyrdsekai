package org.wyrdsekai.core.gpu;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GpuAllocatorTest {

    private final GpuAllocator allocator = new GpuAllocator();

    @Test
    void allocate_single_gpu() {
        var result = allocator.allocate("llama-0", List.of(0));
        assertTrue(result.success());
        assertNull(result.error());
        assertEquals(List.of(0), result.allocated());
    }

    @Test
    void allocate_multiple_gpus() {
        var result = allocator.allocate("tp-model", List.of(0, 1));
        assertTrue(result.success());
        assertEquals(List.of(0, 1), result.allocated());
    }

    @Test
    void double_allocation_same_owner_succeeds() {
        allocator.allocate("llama-0", List.of(0));
        var result = allocator.allocate("llama-0", List.of(0));
        assertTrue(result.success());
    }

    @Test
    void double_allocation_different_owner_fails() {
        allocator.allocate("llama-0", List.of(0));
        var result = allocator.allocate("llama-1", List.of(0));
        assertFalse(result.success());
        assertTrue(result.error().contains("already allocated"));
        assertTrue(result.allocated().isEmpty());
    }

    @Test
    void partial_conflict_fails_atomically() {
        allocator.allocate("llama-0", List.of(1));
        // Try to allocate GPU 0 and 1, but 1 is taken
        var result = allocator.allocate("llama-1", List.of(0, 1));
        assertFalse(result.success());
        // GPU 0 should NOT be allocated (atomic failure)
        assertFalse(allocator.isAllocated(0));
    }

    @Test
    void release_frees_gpus() {
        allocator.allocate("llama-0", List.of(0, 1));
        var freed = allocator.release("llama-0");
        assertEquals(2, freed.size());
        assertTrue(freed.contains(0));
        assertTrue(freed.contains(1));
        assertFalse(allocator.isAllocated(0));
        assertFalse(allocator.isAllocated(1));
    }

    @Test
    void release_unknown_owner_returns_empty() {
        var freed = allocator.release("nonexistent");
        assertTrue(freed.isEmpty());
    }

    @Test
    void get_available_filters_allocated() {
        var allGpus = List.of(
            new GpuProbe.GpuInfo(0, "GPU0", 24000, 20000, 4000, 10),
            new GpuProbe.GpuInfo(1, "GPU1", 24000, 22000, 2000, 5),
            new GpuProbe.GpuInfo(2, "GPU2", 24000, 23000, 1000, 2)
        );
        allocator.allocate("llama-0", List.of(0));

        var available = allocator.getAvailable(allGpus);
        assertEquals(2, available.size());
        assertEquals(1, available.get(0).index());
        assertEquals(2, available.get(1).index());
    }

    @Test
    void is_allocated() {
        assertFalse(allocator.isAllocated(0));
        allocator.allocate("owner", List.of(0));
        assertTrue(allocator.isAllocated(0));
    }

    @Test
    void allocated_count() {
        assertEquals(0, allocator.allocatedCount());
        allocator.allocate("a", List.of(0));
        assertEquals(1, allocator.allocatedCount());
        allocator.allocate("b", List.of(1, 2));
        assertEquals(3, allocator.allocatedCount());
    }

    @Test
    void get_allocations_returns_snapshot() {
        allocator.allocate("llama-0", List.of(0));
        allocator.allocate("embed-1", List.of(1));
        var map = allocator.getAllocations();
        assertEquals(2, map.size());
        assertEquals("llama-0", map.get(0));
        assertEquals("embed-1", map.get(1));
    }

    @Test
    void release_then_reallocate() {
        allocator.allocate("llama-0", List.of(0));
        allocator.release("llama-0");
        var result = allocator.allocate("llama-1", List.of(0));
        assertTrue(result.success());
    }
}
