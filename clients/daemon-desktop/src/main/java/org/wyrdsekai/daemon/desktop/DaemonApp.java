package org.wyrdsekai.daemon.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.daemon.common.DaemonConfig;

import java.util.Arrays;

/**
 * Entry point for the desktop inference daemon.
 *
 * Launches the daemon service (inference process + NATS + gossip) and
 * optionally shows a system tray icon for status and control.
 *
 * Usage:
 *   java -jar daemon-desktop.jar                    # tray mode (default)
 *   java -jar daemon-desktop.jar --headless         # no tray, for servers/systemd
 *   java -jar daemon-desktop.jar --nats-url nats://host:4222
 *   java -jar daemon-desktop.jar --model-path /path/to/model.gguf
 */
public final class DaemonApp {

    private static final Logger log = LoggerFactory.getLogger(DaemonApp.class);

    public static void main(String[] args) {
        var config = new DaemonConfig();
        boolean headless = false;

        // Parse CLI args (override preferences)
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--headless" -> headless = true;
                case "--nats-url" -> {
                    if (i + 1 < args.length) config.setNatsUrl(args[++i]);
                }
                case "--model-path" -> {
                    if (i + 1 < args.length) config.setModelPath(args[++i]);
                }
                case "--model-id" -> {
                    if (i + 1 < args.length) config.setModelId(args[++i]);
                }
                case "--port" -> {
                    if (i + 1 < args.length) config.setInferencePort(Integer.parseInt(args[++i]));
                }
                case "--node-name" -> {
                    if (i + 1 < args.length) config.setNodeName(args[++i]);
                }
                case "--gpu-layers" -> {
                    if (i + 1 < args.length) config.setGpuLayers(Integer.parseInt(args[++i]));
                }
                case "--threads" -> {
                    if (i + 1 < args.length) config.setMaxThreads(Integer.parseInt(args[++i]));
                }
                case "--help" -> {
                    printHelp();
                    return;
                }
                default -> log.warn("Unknown argument: {}", args[i]);
            }
        }

        log.info("Wyrdsekai Inference Daemon starting");
        log.info("  Node: {}", config.nodeName());
        log.info("  NATS: {}", config.natsUrl());
        log.info("  Port: {}", config.inferencePort());

        var service = new DaemonService(config);

        // Shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            service.stop();
        }));

        if (!headless && DaemonTray.isSupported()) {
            DaemonTray.install(service, config);
        }

        service.start();

        // Block main thread (service runs in background threads)
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printHelp() {
        System.out.println("""
            Wyrdsekai Inference Daemon (Desktop)

            Usage: java -jar daemon-desktop.jar [options]

            Options:
              --headless           Run without system tray (for servers/systemd)
              --nats-url <url>     NATS server URL (default: nats://127.0.0.1:4222)
              --model-path <path>  Path to GGUF model file
              --model-id <id>      Model identifier for gossip
              --port <port>        Inference HTTP port (default: 8080)
              --node-name <name>   Human-readable node name
              --gpu-layers <n>     GPU layers for llama-server (0 = CPU only)
              --threads <n>        Inference threads (0 = auto)
              --help               Show this help
            """);
    }
}
