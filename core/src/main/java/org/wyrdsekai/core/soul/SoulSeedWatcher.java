package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Watches ~/.wyrdsekai/souls/incoming/ for seed files.
 * When a new JSON file appears, it triggers the forge pipeline.
 *
 * The seed file format is the same as SoulForgeCliTool.SoulSeed:
 * {"name": "Kai", "description": "...", "homeRoom": "boiler-room"}
 *
 * Flow:
 * 1. File detected in incoming/
 * 2. Parsed as SoulSeed
 * 3. Callback invoked with seed + path (caller decides: auto-forge or confirm)
 * 4. On success: seed file moved to incoming/processed/
 * 5. On failure: seed file moved to incoming/failed/
 *
 * Runs on a virtual thread. Call stop() on shutdown.
 */
public class SoulSeedWatcher {

    private static final Logger log = LoggerFactory.getLogger(SoulSeedWatcher.class);
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final Path incomingDir;
    private final BiConsumer<SoulForgeCliTool.SoulSeed, Path> onSeedDetected;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread watchThread;

    /**
     * @param incomingDir     directory to watch (e.g., ~/.wyrdsekai/souls/incoming)
     * @param onSeedDetected  callback when a seed file is detected (seed, filePath)
     */
    public SoulSeedWatcher(Path incomingDir,
                            BiConsumer<SoulForgeCliTool.SoulSeed, Path> onSeedDetected) {
        this.incomingDir = incomingDir;
        this.onSeedDetected = onSeedDetected;
    }

    /** Start watching. Non-blocking — runs on a virtual thread. */
    public void start() {
        if (running.getAndSet(true)) return;

        try {
            Files.createDirectories(incomingDir);
            Files.createDirectories(incomingDir.resolve("processed"));
            Files.createDirectories(incomingDir.resolve("failed"));
        } catch (Exception e) {
            log.warn("Failed to create incoming directories: {}", e.getMessage());
            running.set(false);
            return;
        }

        // Process any files already in incoming/ at startup
        processExisting();

        watchThread = Thread.ofVirtual().name("soul-seed-watcher").start(this::watchLoop);
        log.info("SoulSeedWatcher started: {}", incomingDir);
    }

    /** Stop watching. */
    public void stop() {
        running.set(false);
        if (watchThread != null) {
            watchThread.interrupt();
        }
        log.info("SoulSeedWatcher stopped");
    }

    private void processExisting() {
        try (var stream = Files.list(incomingDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                .filter(Files::isRegularFile)
                .sorted()
                .forEach(this::processFile);
        } catch (Exception e) {
            log.warn("Failed to scan existing seeds: {}", e.getMessage());
        }
    }

    private void watchLoop() {
        try (var watchService = FileSystems.getDefault().newWatchService()) {
            incomingDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            while (running.get()) {
                var key = watchService.poll(5, TimeUnit.SECONDS);
                if (key == null) continue;

                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    var ev = (WatchEvent<Path>) event;
                    var filename = ev.context();
                    var fullPath = incomingDir.resolve(filename);

                    if (filename.toString().endsWith(".json") && Files.isRegularFile(fullPath)) {
                        // Small delay to ensure file write is complete
                        Thread.sleep(500);
                        processFile(fullPath);
                    }
                }

                if (!key.reset()) {
                    log.warn("SoulSeedWatcher: watch key invalidated");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("SoulSeedWatcher error: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void processFile(Path path) {
        try {
            log.info("Soul seed detected: {}", path.getFileName());
            var seed = JSON.readValue(path.toFile(), SoulForgeCliTool.SoulSeed.class);
            log.info("  Name: {}, Room: {}", seed.name(),
                seed.homeRoom() != null ? seed.homeRoom() : "nexus");

            // Invoke callback — caller handles forge + confirmation
            onSeedDetected.accept(seed, path);

            // Move to processed
            var processed = incomingDir.resolve("processed").resolve(path.getFileName());
            Files.move(path, processed, StandardCopyOption.REPLACE_EXISTING);
            log.info("  Moved to processed: {}", processed.getFileName());

        } catch (Exception e) {
            log.warn("Failed to process seed {}: {}", path.getFileName(), e.getMessage());
            try {
                var failed = incomingDir.resolve("failed").resolve(path.getFileName());
                Files.move(path, failed, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveErr) {
                log.error("Failed to move to failed/: {}", moveErr.getMessage());
            }
        }
    }

    /** Whether the watcher is running. */
    public boolean isRunning() {
        return running.get();
    }
}
