package org.wyrdsekai.scripting.api;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server for SKILL_SERVER level workbench scripts (§).
 *
 * <p>Allows scripts to expose HTTP endpoints for MCP tools, webhooks, and inter-skill
 * communication. Each skill gets its own path prefix. The server runs on a single
 * port with virtual threads.</p>
 *
 * <p>Security: only localhost binding. No external access without explicit relay.</p>
 */
public class ScriptHttpServer {

    private static final Logger log = LoggerFactory.getLogger(ScriptHttpServer.class);
    private static final int DEFAULT_PORT = 9300;

    private HttpServer server;
    private final int port;
    private final Map<String, ScriptHttpHandler> handlers = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface ScriptHttpHandler {
        String handle(String method, String path, String body, Map<String, String> headers);
    }

    public ScriptHttpServer() {
        this(DEFAULT_PORT);
    }

    public ScriptHttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            var body = new String(exchange.getRequestBody().readAllBytes());
            var headers = new HashMap<String, String>();
            exchange.getRequestHeaders().forEach((k, v) ->
                headers.put(k.toLowerCase(), v.isEmpty() ? "" : v.getFirst()));

            // Find matching handler by path prefix
            String response = "{\"error\": \"not found\"}";
            int status = 404;
            for (var entry : handlers.entrySet()) {
                if (path.startsWith(entry.getKey())) {
                    try {
                        response = entry.getValue().handle(method, path, body, headers);
                        status = 200;
                    } catch (Exception e) {
                        response = "{\"error\": \"" + e.getMessage() + "\"}";
                        status = 500;
                    }
                    break;
                }
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            var responseBytes = response.getBytes();
            exchange.sendResponseHeaders(status, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();
        log.info("ScriptHttpServer started on localhost:{}", port);
    }

    /** Register a handler for a path prefix (e.g., "/skill/weather"). */
    public void registerHandler(String pathPrefix, ScriptHttpHandler handler) {
        handlers.put(pathPrefix, handler);
        log.debug("Registered script HTTP handler: {}", pathPrefix);
    }

    /** Unregister a handler. */
    public void unregisterHandler(String pathPrefix) {
        handlers.remove(pathPrefix);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("ScriptHttpServer stopped");
        }
    }

    public int port() { return port; }
    public int handlerCount() { return handlers.size(); }
}
