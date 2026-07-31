package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.familiar.DynamicFormValidator;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Generic event bridge: listens on {@link
 * org.wyrdsekai.core.agent.AgentEventStream} for any
 * {@link AgentEvent.ZoneBroadcast} whose namespace matches a registered
 * {@link BackendAdapter}, and places the resulting {@link CodingArtifact}
 * into the originating room.
 *
 * <p>. Replaces the codeplane-specific
 * {@link org.wyrdsekai.core.codeplane.CodePlaneItemBridge}, which now
 * delegates to this class.</p>
 */
public class CodingTaskItemBridge implements Consumer<AgentEvent> {

    private static final Logger log = LoggerFactory.getLogger(CodingTaskItemBridge.class);

    private final BackendRegistry registry;
    private final Consumer<RoomItemPlacement> roomObjectPlacer;

    /**
     * @param registry         the process-wide backend registry; the bridge
     *                         dispatches events through whichever adapter
     *                         is registered for the inbound namespace.
     * @param roomObjectPlacer callback that places the generated room
     *                         object(s) into the originating room.
     */
    public CodingTaskItemBridge(BackendRegistry registry,
                                 Consumer<RoomItemPlacement> roomObjectPlacer) {
        this.registry = registry;
        this.roomObjectPlacer = roomObjectPlacer;
    }

    /** Where to place a generated set of room objects. */
    public record RoomItemPlacement(String roomId, List<RoomObject> objects) {}

    @Override
    public void accept(AgentEvent event) {
        if (!(event instanceof AgentEvent.ZoneBroadcast zb)) return;

        var adapter = registry.adapterFor(zb.namespace()).orElse(null);
        if (adapter == null) {
            // Not a coding backend event — drop silently. Other namespaces
            // (IoT, observability, …) are handled by their own listeners.
            return;
        }

        CodingArtifact artifact;
        try {
            artifact = adapter.translateEvent(zb);
        } catch (Exception e) {
            log.warn("Adapter {} failed to translate event in room {}: {}",
                adapter.namespace(), zb.roomId(), e.getMessage());
            return;
        }
        if (artifact == null) return;  // not artifact-bearing

        var roomObjects = new ArrayList<RoomObject>();
        var primary = toRoomObject(artifact);
        if (primary != null) {
            roomObjects.add(primary);
            stampRegistry(primary, artifact);
        }

        // Best-effort: pull any sibling artifacts the adapter knows about
        // (e.g. CodePlane emits a SourceArtifact + BuildArtifact pair from
        // a single board completion). Adapters can stash siblings on the
        // primary's backendMetadata; we don't presume the shape here.
        if (artifact instanceof SourceArtifact src && src.backendMetadata() != null) {
            var sibling = src.backendMetadata().get("__sibling_build");
            if (sibling instanceof BuildArtifact ba) {
                var rb = toRoomObject(ba);
                if (rb != null) {
                    roomObjects.add(rb);
                    stampRegistry(rb, ba);
                }
            }
        }

        if (roomObjects.isEmpty()) return;
        roomObjectPlacer.accept(new RoomItemPlacement(zb.roomId(), roomObjects));
        log.info("Placed {} {} item(s) in room {} (task {})",
            roomObjects.size(), artifact.backend(), zb.roomId(), artifact.taskId());
    }

    /**
     * Stamp the {@link CodingItemRegistry} with the link between this
     * placed RoomObject id and the underlying backend + artifact id.
     * RoomActor.onUseObject reads
     * this on {@code use <id> <verb>} to route through the router
     * instead of the room script's regular {@code onUse} hook.
     *
     * <p>Also tries to register the artifact's GraalJS file with
     * {@link org.wyrdsekai.core.item.ScriptedItemLoader} so {@code use}
     * resolves through {@link
     * org.wyrdsekai.scripting.sandbox.ItemScriptExecutor} (the
     * items-as-tools path) instead of needing the backend's
     * {@code runArtifact}. Best-effort — when the agent didn't produce a
     * matching {@code .js} (or didn't follow the manifest contract), we
     * fall through to the legacy router path silently.</p>
     */
    private static void stampRegistry(RoomObject roomObject, CodingArtifact artifact) {
        if (roomObject == null || artifact == null) return;
        var kind = artifact instanceof SourceArtifact ? "codex" : "artifact";
        CodingItemRegistry.get().stamp(new CodingItemMetadata(
            roomObject.id(),
            artifact.backend(),
            artifact.taskId(),
            artifact.artifactId(),
            kind));
        if (artifact instanceof SourceArtifact src) {
            tryRegisterScriptedItem(roomObject, src);
        }
    }

    /**
     * Look for {@code <name>.js} files in the source artifact's workspace
     * and register them with {@link
     * org.wyrdsekai.core.item.ScriptedItemLoader}. The contract (per
     * {@code OpenHandsBackend.ITEMS_AS_TOOLS_PREAMBLE}): agent writes one
     * {@code .js} at the workspace root with an
     * {@code exports.manifest = {name: "..."}} and an
     * {@code invoke(params)} function. We rely on the loader's own
     * manifest validator to reject malformed files.
     *
     * <p>Workspace path interpretation: the recorded
     * {@code workspacePath} is what the backend told us (already
     * host-remapped by {@link OpenHandsBackend}'s
     * {@link CodingWorkspaceMount} when configured). Reusable across
     * coding backends — Goose / Cline / Continue land here too once
     * their adapters wire the same items-as-tools prompt.</p>
     */
    // Public for testability — the in-package CodingTaskItemBridgeEmbodimentRejectTest
    // invokes this with hand-crafted artifacts to assert the §18 REJECT gate;
    // GooseLiveInvocationE2ETest (tier3, different package) invokes it with the
    // actual file a real goose run wrote against the local 9B. Production
    // callers reach here via the event-bridge placement flow (stampRegistry).
    public static void tryRegisterScriptedItem(RoomObject roomObject, SourceArtifact src) {
        var workspace = src.workspacePath();
        if (workspace == null || workspace.isBlank()) return;
        var root = Path.of(workspace);
        if (!Files.isDirectory(root)) {
            log.debug("CodingTaskItemBridge: workspace {} not host-readable; "
                + "skipping ScriptedItemLoader registration", workspace);
            return;
        }
        // Prefer the agent's declared file list. Fall back to scanning
        // the workspace root for any *.js the loader can parse.
        var candidates = new ArrayList<Path>();
        if (src.files() != null) {
            for (var f : src.files()) {
                if (f == null || f.isBlank()) continue;
                if (!f.toLowerCase().endsWith(".js")) continue;
                var p = root.resolve(f.startsWith("/")
                    ? f.substring(f.lastIndexOf('/') + 1) : f);
                if (Files.isRegularFile(p)) candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            try (var stream = Files.list(root)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".js"))
                    .forEach(candidates::add);
            } catch (Exception e) {
                log.debug("CodingTaskItemBridge: workspace {} list failed: {}",
                    workspace, e.getMessage());
                return;
            }
        }
        var loader = ScriptedItemLoader.get();
        var codingRegistry = CodingItemRegistry.get();
        for (var p : candidates) {
            // v1.5 — pre-check the embodiment block
            // BEFORE handing to ScriptedItemLoader.register so we can emit
            // a steward-visible denial that names the missing/invalid
            // shape. Same gate as hot-reload (ManifestEmbodimentMissingException);
            // the steward needs to see this in the bridge log to know which
            // agent-authored artifact got rejected and why.
            try {
                var script = Files.readString(p);
                var manifest = ItemManifestParser.parse(script);
                var manifestName = manifest != null ? manifest.name() : p.getFileName().toString();
                DynamicFormValidator.requireEmbodiment(
                    script, manifestName);
                // Items-as-tools contract — same pre-check idea for the
                // `commands` block and the invoke()/execute() entrypoint,
                // BEFORE handing to ScriptedItemLoader.register (which
                // enforces both on its no-migration path anyway). The
                // pre-check exists so the bridge log NAMES what the agent
                // got wrong; the artifact stays placed as a plain legacy
                // artifact either way.
                ItemManifestValidator.requireCommands(
                    manifest, /* allowMigration */ false, manifestName);
                if (!ScriptedItemLoader.hasEntrypoint(script)) {
                    log.warn("CodingTaskItemBridge: contract REJECT registering "
                        + "agent-authored scripted item from {} (RoomObject {}): "
                        + "script declares no invoke()/execute() entrypoint — the "
                        + "item would be dead on `use`. Falling back to plain "
                        + "artifact placement (legacy router path).",
                        p, roomObject.id());
                    continue;
                }
            } catch (ItemManifestValidator.ManifestEmbodimentMissingException ex) {
                var denial = DynamicFormValidator.denialFrom(
                    ex, p.getFileName().toString());
                log.error("CodingTaskItemBridge: §18 REJECT registering agent-authored "
                    + "scripted item from {} (RoomObject {}): [{}] {}",
                    p, roomObject.id(), denial.messageKey(), denial.detail());
                continue;
            } catch (ItemManifestValidator.ManifestCommandsMissingException ex) {
                log.warn("CodingTaskItemBridge: contract REJECT registering "
                    + "agent-authored scripted item from {} (RoomObject {}): {} "
                    + "Falling back to plain artifact placement (legacy router path).",
                    p, roomObject.id(), ex.getMessage());
                continue;
            } catch (IOException ioe) {
                log.debug("CodingTaskItemBridge: read failed for {} during contract pre-check: {}",
                    p, ioe.getMessage());
                continue;
            }
            var def = loader.register(p);
            if (def.isPresent()) {
                var scriptedId = def.get().itemId();
                log.info("CodingTaskItemBridge: registered scripted item '{}' "
                    + "from {} (placed as RoomObject '{}')",
                    scriptedId, p, roomObject.id());
                // Re-stamp the side-registry with the scripted item id so
                // RoomActor.dispatchCodingItemUse can resolve the placed
                // codex-XYZ RoomObject back to the registered manifest
                // and route through ItemScriptExecutor instead of the
                // legacy LocalCommandRouter / runArtifact path.
                var existing = codingRegistry.lookup(roomObject.id()).orElse(null);
                if (existing != null) {
                    codingRegistry.stamp(new CodingItemMetadata(
                        existing.roomObjectId(),
                        existing.backend(),
                        existing.taskId(),
                        existing.artifactId(),
                        existing.kind(),
                        scriptedId));
                }
                // Take only the first manifest-bearing file. The
                // items-as-tools contract is one file per task — extra
                // .js files (helpers, tests) shouldn't shadow the
                // primary item.
                break;
            } else {
                log.debug("CodingTaskItemBridge: {} did not register "
                    + "(no parseable manifest) — `use` will fall through",
                    p.getFileName());
            }
        }
    }

    /**
     * Render a {@link CodingArtifact} as a {@link RoomObject}.
     *
     * <p>Default rendering is a takeable, visible item with a one-line
     * description. Backend-specific extras (CodePlane board ID, language,
     * etc.) live in {@link SourceArtifact#backendMetadata() backendMetadata}
     * — adapters that want richer rendering can override this method via
     * the {@link BackendAdapter} hook in a future revision.</p>
     */
    private static RoomObject toRoomObject(CodingArtifact a) {
        if (a == null) return null;
        var idPrefix = a instanceof SourceArtifact ? "codex" : "artifact";
        var typeLabel = a instanceof SourceArtifact ? "codex" : "artifact";
        var desc = describe(a);
        var roomId = idPrefix + "-" + shortId(a.artifactId());
        return new RoomObject(roomId, typeLabel, desc, true, true);
    }

    private static String describe(CodingArtifact a) {
        return switch (a) {
            case SourceArtifact s -> {
                int n = s.files() == null ? 0 : s.files().size();
                yield String.format("A %s codex containing %d file(s) for task %s.",
                    s.backend(), n, s.taskId());
            }
            case BuildArtifact b -> String.format(
                "A %s artifact (status=%s, tests %d/%d) for task %s.",
                b.backend(), b.status(), b.testsPassed(),
                b.testsPassed() + b.testsFailed(), b.taskId());
        };
    }

    private static String shortId(UUID id) {
        if (id == null) return "0";
        return id.toString().substring(0, 8);
    }
}
