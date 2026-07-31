package org.wyrdsekai.core.study;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.persistence.InventoryService;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Host-side backing for the Study's shelf verbs (W1).
 *
 * <p>The study room script emits {@code command} events with verbs
 * {@code fs_mount}, {@code fs_unmount} and {@code take}. Until 2026-07-11
 * these fell into RoomActor's honest-narrate fallback ("no machinery received
 * it") — and before that they vanished silently while the script narrated
 * false successes ("Taken: X"). This bridge is the machinery:</p>
 *
 * <ul>
 *   <li>{@code fs_mount} — validates the host path and records it in the
 *       persisted {@link StudyMountRegistry}, which the "skill" MCP service
 *       ({@link StudySkillService}) reads to resolve shelf paths;</li>
 *   <li>{@code fs_unmount} — removes the shelf from the registry;</li>
 *   <li>{@code take} — reads the file through the shelf sandbox and imports
 *       its content as a takeable inventory item for the acting entity.</li>
 * </ul>
 *
 * <p>Pattern mirrors {@code ForgeRoomBridge}/{@code HostActionService}:
 * static canHandle/handle, returns narration lines for the room.</p>
 */
public final class StudyShellBridge {

    private static final Logger log = LoggerFactory.getLogger(StudyShellBridge.class);

    private static final Set<String> VERBS = Set.of("fs_mount", "fs_unmount", "take");

    /** Files bigger than this import truncated — inventory rows are not a disk. */
    private static final int MAX_IMPORT_CHARS = 256 * 1024;

    private StudyShellBridge() {}

    public static boolean canHandle(String verb) {
        return verb != null && VERBS.contains(verb);
    }

    /** Execute a shelf verb; returns the narration lines for the room. */
    public static List<String> handle(String verb, Map<String, Object> data, String roomId) {
        try {
            return switch (verb) {
                case "fs_mount" -> mount(data, roomId);
                case "fs_unmount" -> unmount(data, roomId);
                case "take" -> take(data, roomId);
                default -> List.of();
            };
        } catch (IllegalArgumentException e) {
            // Teaching refusal from the registry/sandbox — narrate it verbatim.
            return List.of(e.getMessage());
        } catch (Exception e) {
            log.warn("StudyShellBridge {} in {} failed: {}", verb, roomId, e.getMessage());
            return List.of("(The shelf machinery hit an unexpected fault: "
                + e.getMessage() + ")");
        }
    }

    private static List<String> mount(Map<String, Object> data, String roomId) {
        var label = str(data, "label");
        var path = str(data, "target");
        var mounted = StudyMountRegistry.get().mount(roomId, label, path);
        return List.of("Shelf '" + label + "' now holds " + mounted
            + ". Browse it with: ls " + label);
    }

    private static List<String> unmount(Map<String, Object> data, String roomId) {
        var label = str(data, "target");
        var registry = StudyMountRegistry.get();
        if (registry.unmount(roomId, label)) {
            return List.of("Shelf '" + label + "' unmounted — its files are no longer "
                + "reachable from this Study.");
        }
        var labels = registry.mountsFor(roomId).keySet();
        return List.of("No shelf named '" + label + "' is mounted host-side."
            + (labels.isEmpty() ? "" : " Mounted shelves: " + String.join(", ", labels)));
    }

    private static List<String> take(Map<String, Object> data, String roomId) throws IOException {
        var target = str(data, "target");
        var actor = str(data, "actor");
        if (actor.isBlank()) {
            return List.of("(Take failed: the request carried no acting entity, "
                + "so there is no inventory to put the file into.)");
        }

        var resolved = StudyMountRegistry.get().resolve(roomId, target);
        if (resolved.relPath().isBlank()) {
            return List.of("'" + resolved.label() + "' is a whole shelf — take a single "
                + "file from it: take " + resolved.label() + "/<file>");
        }

        String content;
        try {
            content = resolved.fs().read(resolved.relPath());
        } catch (IOException e) {
            var msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.startsWith("not_found")) {
                return List.of("Nothing at '" + target + "' to take. "
                    + "List the shelf first: ls " + resolved.label());
            }
            if (msg.startsWith("is_directory")) {
                return List.of("'" + target + "' is a directory — take a single file from it.");
            }
            throw e;
        }

        var truncated = false;
        if (content.length() > MAX_IMPORT_CHARS) {
            content = content.substring(0, MAX_IMPORT_CHARS)
                + "\n\n[truncated at " + MAX_IMPORT_CHARS + " characters on import]";
            truncated = true;
        }

        var jdbcUrl = System.getProperty("wyrdsekai.jdbc.url");
        if (jdbcUrl == null) jdbcUrl = WyrdConfig.get().jdbcUrl();
        if (jdbcUrl == null) {
            return List.of("(The file was found, but this household has no database "
                + "configured — there is no inventory to import it into.)");
        }

        var fileName = fileNameOf(resolved.relPath());
        var objectId = importObjectId(resolved.label(), resolved.relPath());
        var inventory = new InventoryService(jdbcUrl);
        inventory.addItem(actor, objectId, fileName, content, /* takeable = */ true, roomId);
        if (!inventory.hasItem(actor, objectId)) {
            // addItem logs-and-swallows SQL failures; verify so "Taken" is never a lie.
            return List.of("(Take failed: the file was read but the inventory write "
                + "did not stick — check the household database.)");
        }
        return List.of("Taken: " + fileName + " — imported from "
            + resolved.label() + "/" + resolved.relPath() + " into your inventory"
            + (truncated ? " (truncated: the original exceeds "
                + MAX_IMPORT_CHARS + " characters)" : "") + ".");
    }

    private static String fileNameOf(String relPath) {
        var idx = relPath.lastIndexOf('/');
        return idx < 0 ? relPath : relPath.substring(idx + 1);
    }

    /** Stable id so re-taking the same file upserts instead of duplicating. */
    private static String importObjectId(String label, String relPath) {
        var slug = (label + "-" + relPath).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]", "-");
        return "file-" + slug;
    }

    private static String str(Map<String, Object> data, String key) {
        var value = data.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
