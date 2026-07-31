package org.wyrdsekai.e2e.infra;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe ephemeral port allocator for test infrastructure.
 * Uses OS-assigned ports via ServerSocket(0) with collision tracking
 * to prevent port reuse within a single test run.
 */
public final class PortAllocator {

    private static final Set<Integer> allocated = ConcurrentHashMap.newKeySet();

    private PortAllocator() {}

    /**
     * Allocate a fresh ephemeral port guaranteed unique within this JVM.
     *
     * @return an available port number
     * @throws IllegalStateException if no port can be allocated after retries
     */
    public static int allocate() {
        for (int attempt = 0; attempt < 10; attempt++) {
            try (var socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                int port = socket.getLocalPort();
                if (allocated.add(port)) {
                    return port;
                }
            } catch (IOException e) {
                // Retry on bind failure
            }
        }
        throw new IllegalStateException("Failed to allocate ephemeral port after 10 attempts");
    }

    /**
     * Release a previously allocated port (for cleanup in @AfterAll).
     */
    public static void release(int port) {
        allocated.remove(port);
    }

    /**
     * Reset all allocations (for test suite teardown).
     */
    public static void reset() {
        allocated.clear();
    }
}
