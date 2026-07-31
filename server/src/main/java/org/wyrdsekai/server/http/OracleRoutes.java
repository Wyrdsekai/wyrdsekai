package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.wyrdsekai.core.oracle.OracleBridge;
import org.wyrdsekai.core.oracle.OracleEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP routes for Oracle prediction engine.
 *
 * Proxies requests to the oracle-core sidecar.
 * Browser extension and phone clients POST here.
 *
 *   POST /api/oracle/ingest     — forward events to oracle-core
 *   POST /api/oracle/anticipate — get predictions
 *   GET  /api/oracle/stats      — get oracle stats
 */
public final class OracleRoutes {

    public void register(JavalinDefaultRoutingApi app) {
        var bridge = OracleBridge.getInstance();
        if (bridge == null) return;

        app.post("/api/oracle/ingest", ctx -> {
            var body = ctx.body();
            // Forward directly to oracle-core
            bridge.ingest("default", List.of()).thenAccept(count -> {});
            // For now, parse and forward the full body
            var mapper = new ObjectMapper();
            var json = mapper.readTree(body);
            var userId = json.path("user_id").asText("default");
            var eventsNode = json.path("events");
            if (!eventsNode.isArray()) {
                ctx.status(400).json(Map.of("error", "missing events array"));
                return;
            }
            var events = new ArrayList<OracleEvent>();
            for (var node : eventsNode) {
                events.add(new OracleEvent(
                    Instant.parse(node.path("timestamp").asText()),
                    node.path("source").asText(""),
                    node.path("event_type").asText(""),
                    node.path("content").asText(""),
                    node.path("entity_id").asText(""),
                    node.path("room_id").asText("")
                ));
            }
            var count = bridge.ingest(userId, events).join();
            ctx.json(Map.of("ingested", count));
        });

        app.post("/api/oracle/anticipate", ctx -> {
            var mapper = new ObjectMapper();
            var json = mapper.readTree(ctx.body());
            var userId = json.path("user_id").asText("default");
            var minConf = json.path("min_confidence").asDouble(0.5);
            var predictions = bridge.anticipate(userId, minConf).join();
            ctx.json(Map.of("insights", predictions));
        });

        app.get("/api/oracle/stats", ctx -> {
            ctx.json(Map.of("status", "ok", "oracle_available", true));
        });
    }
}
