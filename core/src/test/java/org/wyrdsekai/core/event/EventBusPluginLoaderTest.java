package org.wyrdsekai.core.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventBusPluginLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_returns_empty_when_no_config_file() {
        var plugins = EventBusPluginLoader.load(
            tempDir.resolve("nonexistent.json"), new InProcessEventBus());
        assertTrue(plugins.isEmpty());
    }

    @Test
    void load_webhook_plugin_from_config() throws Exception {
        var config = """
            {
              "plugins": [
                {"type": "webhook", "url": "http://localhost:9999/hook", "events": ["system"]}
              ]
            }
            """;
        var configPath = tempDir.resolve("eventbus.json");
        Files.writeString(configPath, config);

        var bus = new InProcessEventBus();
        var plugins = EventBusPluginLoader.load(configPath, bus);

        assertEquals(1, plugins.size());
        assertEquals("webhook", plugins.getFirst().name());
        assertEquals(1, bus.subscriberCount());
    }

    @Test
    void load_skips_unknown_plugin_types() throws Exception {
        var config = """
            {
              "plugins": [
                {"type": "unknown_type", "url": "http://example.com"}
              ]
            }
            """;
        var configPath = tempDir.resolve("eventbus.json");
        Files.writeString(configPath, config);

        var plugins = EventBusPluginLoader.load(configPath, new InProcessEventBus());
        assertTrue(plugins.isEmpty());
    }

    @Test
    void load_skips_webhook_without_url() throws Exception {
        var config = """
            {
              "plugins": [
                {"type": "webhook"}
              ]
            }
            """;
        var configPath = tempDir.resolve("eventbus.json");
        Files.writeString(configPath, config);

        var plugins = EventBusPluginLoader.load(configPath, new InProcessEventBus());
        assertTrue(plugins.isEmpty());
    }

    @Test
    void load_multiple_plugins() throws Exception {
        var config = """
            {
              "plugins": [
                {"type": "webhook", "url": "http://hook1.example.com"},
                {"type": "webhook", "url": "http://hook2.example.com", "secret": "s3cr3t"}
              ]
            }
            """;
        var configPath = tempDir.resolve("eventbus.json");
        Files.writeString(configPath, config);

        var bus = new InProcessEventBus();
        var plugins = EventBusPluginLoader.load(configPath, bus);

        assertEquals(2, plugins.size());
        assertEquals(2, bus.subscriberCount());
    }

    @Test
    void shutdown_all_doesnt_throw() {
        var bus = new InProcessEventBus();
        var plugin = new WebhookEventBus("http://example.com", null, null);
        plugin.initialize(bus);

        assertDoesNotThrow(() ->
            EventBusPluginLoader.shutdownAll(List.of(plugin)));
    }
}
