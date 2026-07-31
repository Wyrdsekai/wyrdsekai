package org.wyrdsekai.core.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads event bus plugins from {@code ~/.wyrdsekai/eventbus.json}.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "plugins": [
 *     {"type": "webhook", "url": "https://hooks.example.com/wyrdsekai", "events": ["speech", "oracle"]},
 *     {"type": "webhook", "url": "https://slack.example.com/hook", "secret": "hmac-key"}
 *   ]
 * }
 * </pre>
 */
public final class EventBusPluginLoader {

    private static final Logger log = LoggerFactory.getLogger(EventBusPluginLoader.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private EventBusPluginLoader() {}

    /**
     * Load plugins from the config file and initialize them with the given event bus.
     *
     * @param configPath path to eventbus.json
     * @param bus        the event bus to register plugins with
     * @return list of initialized plugins (for later shutdown)
     */
    public static List<EventBusPlugin> load(Path configPath, EventBus bus) {
        var plugins = new ArrayList<EventBusPlugin>();

        if (!Files.exists(configPath)) {
            log.debug("No eventbus.json found at {} — no plugins loaded", configPath);
            return plugins;
        }

        try {
            var root = mapper.readTree(configPath.toFile());
            var pluginsNode = root.get("plugins");
            if (pluginsNode == null || !pluginsNode.isArray()) {
                log.warn("eventbus.json has no 'plugins' array");
                return plugins;
            }

            for (var node : pluginsNode) {
                var plugin = createPlugin(node);
                if (plugin != null) {
                    try {
                        plugin.initialize(bus);
                        plugins.add(plugin);
                        log.info("EventBus plugin loaded: {} ({})", plugin.name(), node);
                    } catch (Exception e) {
                        log.warn("Failed to initialize EventBus plugin {}: {}",
                            plugin.name(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read eventbus.json: {}", e.getMessage());
        }

        return plugins;
    }

    private static EventBusPlugin createPlugin(JsonNode node) {
        var type = node.has("type") ? node.get("type").asText() : "";

        return switch (type) {
            case "webhook" -> {
                var url = node.has("url") ? node.get("url").asText() : "";
                if (url.isBlank()) {
                    log.warn("Webhook plugin missing 'url'");
                    yield null;
                }
                Set<String> events = null;
                if (node.has("events") && node.get("events").isArray()) {
                    events = new HashSet<>();
                    for (var e : node.get("events")) {
                        events.add(e.asText());
                    }
                }
                var secret = node.has("secret") ? node.get("secret").asText() : null;
                yield new WebhookEventBus(url, events, secret);
            }
            default -> {
                log.warn("Unknown EventBus plugin type: '{}'", type);
                yield null;
            }
        };
    }

    /**
     * Shut down all loaded plugins.
     *
     * @param plugins list of initialized plugins
     */
    public static void shutdownAll(List<EventBusPlugin> plugins) {
        for (var plugin : plugins) {
            try {
                plugin.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down plugin {}: {}", plugin.name(), e.getMessage());
            }
        }
    }
}
