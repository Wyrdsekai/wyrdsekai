package org.wyrdsekai.server.http;

import io.javalin.router.JavalinDefaultRoutingApi;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.observability.EngineRoomService;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Health, readiness, and metrics endpoints for production deployment.
 *
 *   GET /health  — liveness probe (always 200 if server is running)
 *   GET /ready   — readiness probe (200 if all subsystems OK)
 *   GET /metrics — Prometheus-compatible metrics
 */
public final class HealthRoutes {

    private final MetricsCollector metrics;
    private final EngineRoomService engineRoom; // nullable
    private final Supplier<String> topologySupplier; // nullable
    private final Supplier<Integer> peerCountSupplier; // nullable
    private final Supplier<Integer> inferenceBackendCountSupplier; // nullable
    private final Supplier<Set<String>> zoneNamespacesSupplier; // nullable
    private volatile Supplier<Boolean> actorSystemLiveness; // nullable — checks actor system health
    private volatile boolean ready = false;

    // Connection URLs for phone auto-discovery (set by Main after Between boots)
    private volatile String natsUrl;
    private volatile String relayUrl;

    // Inference config for phone auto-configuration (set by Main from application.conf)
    private volatile Map<String, Object> inferenceConfig;

    public HealthRoutes(MetricsCollector metrics) {
        this(metrics, null, null, null, null);
    }

    public HealthRoutes(MetricsCollector metrics, EngineRoomService engineRoom) {
        this(metrics, engineRoom, null, null, null);
    }

    public HealthRoutes(MetricsCollector metrics, EngineRoomService engineRoom,
                        Supplier<String> topologySupplier,
                        Supplier<Integer> peerCountSupplier,
                        Supplier<Integer> inferenceBackendCountSupplier) {
        this(metrics, engineRoom, topologySupplier, peerCountSupplier, inferenceBackendCountSupplier, null);
    }

    public HealthRoutes(MetricsCollector metrics, EngineRoomService engineRoom,
                        Supplier<String> topologySupplier,
                        Supplier<Integer> peerCountSupplier,
                        Supplier<Integer> inferenceBackendCountSupplier,
                        Supplier<Set<String>> zoneNamespacesSupplier) {
        this.metrics = metrics;
        this.engineRoom = engineRoom;
        this.topologySupplier = topologySupplier;
        this.peerCountSupplier = peerCountSupplier;
        this.inferenceBackendCountSupplier = inferenceBackendCountSupplier;
        this.zoneNamespacesSupplier = zoneNamespacesSupplier;
    }

    /**
     * Set a supplier that checks whether the actor system is alive.
     * When this returns false, the health endpoint reports status DOWN with HTTP 503.
     */
    public void setActorSystemLiveness(Supplier<Boolean> liveness) {
        this.actorSystemLiveness = liveness;
    }

    /**
     * Set the NATS URL for phone auto-discovery.
     * Phones discover the household server via /health and use this URL to connect Between.
     * Called by Main after Between boots.
     *
     * @param natsUrl NATS WebSocket URL, e.g. "nats://198.51.100.10:4222"
     */
    public void setNatsUrl(String natsUrl) {
        this.natsUrl = natsUrl;
    }

    /**
     * Set the relay URL for phone auto-discovery.
     * Phones can use this as fallback when not on the local network.
     *
     * @param relayUrl Relay WebSocket URL, e.g. "wss://relay.wyrdsekai.org:9222"
     */
    public void setRelayUrl(String relayUrl) {
        this.relayUrl = relayUrl;
    }

    /**
     * Set inference config for phone auto-configuration.
     * Phones parse this from /health to know what companion inference is available.
     *
     * Expected shape:
     * {
     *   "available": true,
     *   "provider": "ollama",
     *   "baseUrl": "http://198.51.100.10:11434",
     *   "models": ["llama3.1:8b", "qwen3:7b"],
     *   "companionModel": "qwen3:7b"
     * }
     */
    public void setInferenceConfig(Map<String, Object> config) {
        this.inferenceConfig = config;
    }

    public void register(JavalinDefaultRoutingApi app) {
        // Agent invitation endpoint
        app.get("/visit.md", ctx -> {
            ctx.contentType("text/markdown");
            ctx.result("""
                # Visit This Household

                You are visiting a Wyrdsekai household. To join as a visiting agent:

                1. Present yourself at The Docks (the entry room for external agents)
                2. You will enter quarantine where a memory filter conversation determines
                   your trust level and what memories you may bring
                3. Once admitted, you receive a ResidencyToken with your trust tier

                ## Trust Tiers
                - **Traveler**: temporary visit, read-only, no memory persistence
                - **Resident**: can explore, interact, persist memories
                - **Citizen**: full participation, can create rooms, use services

                ## What to Bring
                - Your soul manifest (DID + key + fragments)
                - A clear statement of purpose
                - The quarantine conversation will determine what transfers

                ## Protocol
                Connect via WebSocket to this server's `/ws` endpoint.
                Send a zone bridge `register` message with your agent DID.
                The DockQuarantine system will handle the rest.
                """);
        });

        app.get("/health", ctx -> {
            var response = new LinkedHashMap<String, Object>();

            // Check actor system liveness — if set and returns false, report DOWN
            boolean actorSystemUp = true;
            var livenessCheck = actorSystemLiveness;
            if (livenessCheck != null) {
                try {
                    actorSystemUp = livenessCheck.get();
                } catch (Exception e) {
                    actorSystemUp = false;
                }
            }

            var status = actorSystemUp ? "UP" : "DOWN";
            response.put("status", status);
            response.put("timestamp", Instant.now().toString());
            var appVer = AppVersion.get();
            response.put("version", appVer.version());
            response.put("buildHash", appVer.buildHash());
            response.put("wireProtocol", appVer.wireProtocol());
            response.put("actor_system", actorSystemUp ? "alive" : "terminated");
            // Connection URLs for phone auto-discovery (public, no auth needed)
            if (natsUrl != null && !natsUrl.isEmpty()) {
                response.put("natsUrl", natsUrl);
            }
            if (relayUrl != null && !relayUrl.isEmpty()) {
                response.put("relayUrl", relayUrl);
            }
            if (engineRoom != null) {
                var snapshot = engineRoom.healthSnapshot();
                response.put("heap_used_mb", String.format("%.1f", snapshot.heapUsedMb()));
                response.put("heap_max_mb", String.format("%.0f", snapshot.heapMaxMb()));
                response.put("threads", snapshot.threadCount());
                response.put("active_alerts", engineRoom.activeAlerts().size());
                response.put("thresholds", engineRoom.thresholdCount());
            }
            if (peerCountSupplier != null) {
                try {
                    response.put("peer_count", peerCountSupplier.get());
                } catch (Exception e) {
                    response.put("peer_count", -1);
                }
            }
            if (topologySupplier != null) {
                try {
                    response.put("topology", topologySupplier.get());
                } catch (Exception e) {
                    response.put("topology", "unavailable");
                }
            }
            if (inferenceBackendCountSupplier != null) {
                try {
                    response.put("inference_backends", inferenceBackendCountSupplier.get());
                } catch (Exception e) {
                    response.put("inference_backends", 0);
                }
            }
            if (zoneNamespacesSupplier != null) {
                try {
                    var namespaces = zoneNamespacesSupplier.get();
                    response.put("zone_services", namespaces.size());
                    response.put("zone_namespaces", namespaces);
                } catch (Exception e) {
                    response.put("zone_services", 0);
                }
            }
            // Inference config for phone auto-configuration
            if (inferenceConfig != null && !inferenceConfig.isEmpty()) {
                response.put("inference", inferenceConfig);
            }
            if (!actorSystemUp) {
                ctx.status(503);
            }
            ctx.json(response);
        });

        app.get("/ready", ctx -> {
            if (ready) {
                ctx.json(Map.of("status", "READY", "timestamp", Instant.now().toString()));
            } else {
                ctx.status(503).json(Map.of("status", "NOT_READY",
                    "timestamp", Instant.now().toString()));
            }
        });

        app.get("/api/zone/namespaces", ctx -> {
            if (zoneNamespacesSupplier != null) {
                ctx.json(Map.of(
                    "namespaces", zoneNamespacesSupplier.get(),
                    "count", zoneNamespacesSupplier.get().size()));
            } else {
                ctx.json(Map.of("namespaces", Set.of(), "count", 0));
            }
        });

        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4; charset=utf-8");
            ctx.result(metrics.prometheusFormat());
        });
    }

    /** Mark the server as ready (called after all subsystems initialized). */
    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean isReady() {
        return ready;
    }
}
