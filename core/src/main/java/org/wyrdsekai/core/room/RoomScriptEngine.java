package org.wyrdsekai.core.room;

import com.fasterxml.jackson.core.type.TypeReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.LocalCommandRouter;
import org.wyrdsekai.core.agent.WorldApiZoneCommandDispatcher;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.coding.CodeZaikuBackend;
import org.wyrdsekai.core.coding.DefaultCodingBackendProvider;
import org.wyrdsekai.core.coding.ScriptedCodingBackendProvider;
import org.wyrdsekai.scripting.api.BridgeDataProvider;
import org.wyrdsekai.scripting.api.McpGatewayProvider;
import org.wyrdsekai.scripting.api.WorldApi;
import org.wyrdsekai.scripting.loader.ScriptLoader;
import org.wyrdsekai.scripting.sandbox.ScriptSandbox;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Manages the scripting lifecycle for a single room.
 * Wraps ScriptSandbox + WorldApi + ScriptLoader into a single bridge.
 * Created per-room, called by RoomActor on relevant events.
 */
public class RoomScriptEngine implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RoomScriptEngine.class);

    public record ScriptEmission(String eventType, Map<String, Object> data) {}

    private final String roomId;
    private final ScriptSandbox sandbox;
    private final ScriptLoader loader;
    private final WorldApi worldApi;
    private final List<ScriptEmission> pendingEmissions = new ArrayList<>();

    public RoomScriptEngine(String roomId, ScriptLoader loader) {
        this(roomId, loader, null);
    }

    public RoomScriptEngine(String roomId, ScriptLoader loader,
                            BridgeDataProvider bridgeDataProvider) {
        this(roomId, loader, bridgeDataProvider, null);
    }

    public RoomScriptEngine(String roomId, ScriptLoader loader,
                            BridgeDataProvider bridgeDataProvider,
                            McpGatewayProvider mcpGatewayProvider) {
        this.roomId = roomId;
        this.sandbox = new ScriptSandbox(roomId);
        this.loader = loader;
        this.worldApi = new WorldApi(roomId);

        // Wire the coding-backend provider so room scripts can probe
        // `world.codingBackendAvailable("opencode")` etc. (
        // §2.5 Workshop narration) AND so `world.codingBackendFor(...)` runs
        // the policy script (§4.4 / Phase 2c — explore→openhands etc.).
        // We pick ScriptedCodingBackendProvider when a coding-backend.js is
        // discoverable on disk; otherwise fall back to the Phase 1a default
        // so unit tests without script bundles still resolve "codezaiku".
        var registry = BackendRegistry.get();
        var policyPath = locateCodingBackendPolicy();
        if (policyPath != null) {
            var fallbackChain = List.of(
                CodeZaikuBackend.NAME,
                "opencode", "openhands", "goose", "cline", "continue",
                "claude-sdk", "codex", "gemini", "devin");
            worldApi.setCodingBackendProvider(
                new ScriptedCodingBackendProvider(
                    registry, policyPath,
                    CodeZaikuBackend.NAME,
                    fallbackChain,
                    /* soulStore */ null,
                    /* householdPolicy */ null,
                    /* costTracker */ null,
                    /* driveLookup */ null,
                    /* defaultDailyCuBudget */ 0L));
        } else {
            worldApi.setCodingBackendProvider(
                new DefaultCodingBackendProvider(registry));
        }

        // Inject bridge data provider for rooms that need it (The Bridge)
        if (bridgeDataProvider != null) {
            worldApi.setBridgeDataProvider(bridgeDataProvider);
        }

        // Inject MCP gateway provider for rooms that need MCP access (§86.1).
        // When the caller didn't hand one over (RoomActor never does), fall
        // back to the process-wide bridge installed at server startup — until
        // 2026-07-11 there was no fallback and world.mcp() was dead in every
        // production room (W1).
        if (mcpGatewayProvider != null) {
            worldApi.setMcpGatewayProvider(mcpGatewayProvider);
        } else {
            var installed = RoomMcpBridge.get();
            if (installed != null) {
                worldApi.setMcpGatewayProvider(installed);
            }
        }

        // Wire `world.zoneCommand(name, payload)` so room scripts can
        // dispatch namespaced commands through the same router that
        // serves player WebSocket commands and agent ZoneCommand
        // actions. single router, three
        // entrypoints. The dispatcher is a thin adapter over
        // LocalCommandRouter (the process-wide singleton); when no
        // handler is registered for a namespace, scripts get a null
        // back from world.zoneCommand and fall through gracefully.
        worldApi.setZoneCommandDispatcher(
            new WorldApiZoneCommandDispatcher(
                LocalCommandRouter.get()));

        // Capture script emissions
        worldApi.onEvent((type, data) -> pendingEmissions.add(new ScriptEmission(type, data)));
    }

    /**
     * Find scripts/policy/coding-backend.js relative to the script loader's
     * room base dir. Returns null if not found — callers should fall back to
     * the Phase 1a default provider.
     */
    private Path locateCodingBackendPolicy() {
        try {
            var roomsDir = loader.getBaseDir();
            if (roomsDir == null) return null;
            // baseDir for rooms is `<scripts>/rooms`; policy lives at
            // `<scripts>/policy/coding-backend.js`. Normalise so a relative
            // baseDir like "../scripts/rooms" still resolves cleanly.
            var candidate = roomsDir.resolveSibling("policy")
                .resolve("coding-backend.js");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        } catch (Exception e) {
            log.debug("Failed to locate coding-backend.js policy: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Sync WorldApi state with current room state.
     * Call before invoking script hooks.
     */
    public void syncState(RoomState state) {
        worldApi.setRoomName(state.name());
        worldApi.setRoomDescription(state.description());
        worldApi.setProperties(state.properties());
        // Sync entities
        for (var entry : state.entities().entrySet()) {
            var entity = entry.getValue();
            worldApi.addEntity(entity.id(), entity.name(), entity.type());
        }
        // Sync objects
        for (var entry : state.objects().entrySet()) {
            var obj = entry.getValue();
            worldApi.addObject(obj.id(), obj.name(), obj.description());
        }
    }

    /** Set the locale for script execution (world.t() will use this). */
    public void setLocale(String locale) {
        worldApi.setLocale(locale);
    }

    /** Translate a key using the current locale's catalog. Returns the key if no translation. */
    public String translate(String key) {
        return worldApi.t(key);
    }

    /** Set zone ID for MCP gateway agent tracking. */
    public void setZoneId(String zoneId) {
        worldApi.setZoneId(zoneId);
    }

    /** Set current entity context for MCP gateway agent tracking. */
    public void setCurrentEntityId(String entityId) {
        worldApi.setCurrentEntityId(entityId);
    }

    /**
     * Invoke a script hook function (e.g., "onEnter", "onSay", "onUse").
     * Returns any emissions the script produced.
     */
    public List<ScriptEmission> invokeHook(String hookName, Object... args) {
        pendingEmissions.clear();
        var source = loader.load(roomId);
        if (source == null) return List.of();

        sandbox.callHook(source, hookName, worldApi, args);
        return List.copyOf(pendingEmissions);
    }

    /**
     * Ask the script for contextual hints via getHints() function.
     * Returns empty if no script or no getHints function defined.
     */
    public Optional<List<Hint>> getHints() {
        var source = loader.load(roomId);
        if (source == null) return Optional.empty();

        return sandbox.callHintsFunction(source, worldApi);
    }

    /**
     * Query getToolDefinitions() from the room script, if it exists.
     * Returns tool definitions for scripted room objects that agents can call
     * via tool calling. Room scripts opt-in by exporting this function.
     *
     * <p>Example room script:
     * <pre>
     * function getToolDefinitions() {
     *   return [
     *     { name: "card_catalog", description: "Search the card catalog",
     *       params: { query: { type: "string", description: "Search term" } } }
     *   ];
     * }
     * </pre>
     *
     * @return List of tool definition maps, or empty list if not supported
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getToolDefinitions() {
        var source = loader.load(roomId);
        if (source == null) return List.of();

        // Wrap getToolDefinitions() call to return JSON string (Values die with context).
        // The wrapper script calls the function and JSON.stringify's the result.
        var wrapper = source + "\n;typeof getToolDefinitions === 'function' "
            + "? JSON.stringify(getToolDefinitions()) : '[]'";
        var json = sandbox.executeAsString(wrapper, worldApi);
        if (json == null || "[]".equals(json) || json.isBlank()) return List.of();

        try {
            return Json.mapper().readValue(json,
                new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse getToolDefinitions() in room {}: {}", roomId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Call onActivate hook (§31) — room is being activated (recovery complete or first access).
     */
    public List<ScriptEmission> invokeActivate(RoomState state) {
        return invokeLifecycle(state, "onActivate");
    }

    /**
     * Call onPassivate hook (§31) — room is being passivated (idle timeout).
     */
    public List<ScriptEmission> invokePassivate(RoomState state) {
        return invokeLifecycle(state, "onPassivate");
    }

    /**
     * Call onTimer hook (§31) — periodic timer fired.
     * @param timerId the timer's identifier
     */
    public List<ScriptEmission> invokeTimer(RoomState state, String timerId) {
        return invokeTimer(state, timerId, "onTimer");
    }

    /**
     * Call a timer hook (§31) — periodic timer fired. The hook name is the one
     * the script asked for in {@code world.scheduleTimer(id, secs, hookName)};
     * defaults to {@code onTimer} when the request carried none.
     */
    public List<ScriptEmission> invokeTimer(RoomState state, String timerId, String hookName) {
        pendingEmissions.clear();
        var source = loader.load(roomId);
        if (source == null) return List.of();
        syncState(state);
        sandbox.callHook(source,
            (hookName == null || hookName.isBlank()) ? "onTimer" : hookName,
            worldApi, timerId);
        return List.copyOf(pendingEmissions);
    }

    /**
     * Consume any pending timer scheduling requests from the script.
     */
    public List<WorldApi.TimerRequest> consumeTimerRequests() {
        return worldApi.consumeTimerRequests();
    }

    private List<ScriptEmission> invokeLifecycle(RoomState state, String hookName) {
        pendingEmissions.clear();
        var source = loader.load(roomId);
        if (source == null) return List.of();
        syncState(state);
        sandbox.callHook(source, hookName, worldApi);
        return List.copyOf(pendingEmissions);
    }

    @Override
    public void close() {
        sandbox.close();
    }
}
