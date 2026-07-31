package org.wyrdsekai.e2e.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared pool of LlamaDockerFixture instances keyed by model filename.
 * Starting a llama-server container involves model loading that can take 60-90s,
 * exceeding the 120s JUnit setUp timeout if each test class restarts containers.
 *
 * <p>Non-degradation tests should use {@link #acquire} to get a shared instance
 * and should NOT stop it in their tearDown. Degradation tests that need to
 * stop/restart containers should manage their own fixtures.
 *
 * <p>All shared containers are cleaned up via a JVM shutdown hook.
 */
public final class SharedLlamaPool {

    private static final Logger log = LoggerFactory.getLogger(SharedLlamaPool.class);
    private static final ConcurrentHashMap<String, LlamaDockerFixture> pool =
        new ConcurrentHashMap<>();
    private static volatile boolean shutdownHookRegistered = false;

    private SharedLlamaPool() {}

    /**
     * Acquire a shared LlamaDockerFixture for the given model.
     * If a running instance exists for this model, return it.
     * Otherwise start a new one.
     *
     * @param name       human-readable name for logging
     * @param modelFile  GGUF filename (pool key)
     * @param port       host port (used only if creating new instance)
     * @param ctxSize    context size (used only if creating new instance)
     * @return a running LlamaDockerFixture
     */
    public static synchronized LlamaDockerFixture acquire(
            String name, String modelFile, int port, int ctxSize) throws Exception {
        var existing = pool.get(modelFile);
        if (existing != null && existing.isRunning()) {
            log.info("Reusing shared llama-server for model {} (port {})",
                modelFile, existing.port());
            return existing;
        }

        // Release any shared SGLang fixture to free GPU memory — can't coexist
        E2eTestSupport.releaseSharedFixture();

        log.info("Starting shared llama-server for model {} on port {}", modelFile, port);
        var fixture = new LlamaDockerFixture(
            "shared-" + name, modelFile, port, ctxSize);
        fixture.start();
        pool.put(modelFile, fixture);
        registerShutdownHook();
        return fixture;
    }

    private static void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Stopping {} shared llama-server containers (JVM shutdown)",
                    pool.size());
                pool.values().forEach(LlamaDockerFixture::stop);
                pool.clear();
            }, "shared-llama-cleanup"));
            shutdownHookRegistered = true;
        }
    }
}
