package org.wyrdsekai.core.study;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.item.SandboxedFs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Host-side table of the Study's mounted shelves (W1).
 *
 * <p>The room script keeps its own mount map in a room property, but that map
 * lives inside room state where no host-side service can read it. This
 * registry is the authoritative copy: RoomActor writes it when the script
 * emits {@code fs_mount}/{@code fs_unmount}, and {@link StudySkillService}
 * (the "skill" MCP service) reads it to resolve {@code shelf/path} paths.</p>
 *
 * <p>Persisted as JSON at {@code <dataDir>/study-mounts.json}, keyed by room
 * id so one household member's shelves are not readable through another's
 * Study. Every resolved path is wrapped in a {@link SandboxedFs} rooted at
 * the mounted directory, so reads can never escape a mounted root.</p>
 */
public final class StudyMountRegistry {

    private static final Logger log = LoggerFactory.getLogger(StudyMountRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private static volatile StudyMountRegistry instance;

    /** Process-wide registry backed by {@code <dataDir>/study-mounts.json}. */
    public static StudyMountRegistry get() {
        var existing = instance;
        if (existing != null) return existing;
        synchronized (StudyMountRegistry.class) {
            if (instance == null) {
                instance = new StudyMountRegistry(
                    SystemPaths.dataDir().resolve("study-mounts.json"));
            }
            return instance;
        }
    }

    /** Replace the process-wide instance (tests, or explicit rewiring). */
    public static void install(StudyMountRegistry registry) {
        instance = registry;
    }

    private final Path storeFile;

    /** roomId → (shelf label → host directory path). */
    private final Map<String, Map<String, String>> mounts = new LinkedHashMap<>();

    public StudyMountRegistry(Path storeFile) {
        this.storeFile = storeFile;
        load();
    }

    private void load() {
        if (storeFile == null || !Files.exists(storeFile)) return;
        try {
            var loaded = mapper.readValue(Files.readString(storeFile),
                new TypeReference<Map<String, Map<String, String>>>() {});
            mounts.putAll(loaded);
            log.info("Loaded study mount table from {} ({} rooms)", storeFile, loaded.size());
        } catch (Exception e) {
            log.warn("Could not read study mount table {} — starting empty: {}",
                storeFile, e.getMessage());
        }
    }

    private synchronized void persist() {
        if (storeFile == null) return;
        try {
            var parent = storeFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(storeFile,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mounts));
        } catch (IOException e) {
            log.warn("Could not persist study mount table to {}: {}",
                storeFile, e.getMessage());
        }
    }

    /** Shelf label → host path for one room (copy; empty when none mounted). */
    public synchronized Map<String, String> mountsFor(String roomId) {
        var forRoom = mounts.get(roomId);
        return forRoom == null ? Map.of() : new LinkedHashMap<>(forRoom);
    }

    /**
     * Mount a host directory under a shelf label for a room. Validates before
     * recording; throws {@link IllegalArgumentException} with a message that
     * teaches when the label or path is unusable.
     *
     * @return the canonical mounted path
     */
    public synchronized Path mount(String roomId, String label, String hostPath) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("Mount refused: no room of origin was given.");
        }
        if (label == null || !LABEL_PATTERN.matcher(label).matches()) {
            throw new IllegalArgumentException(
                "Mount refused: shelf labels are a single word of letters, digits, dots, "
                + "dashes or underscores (got '" + label + "'). Try: mount " + hostPath + " as docs");
        }
        if (hostPath == null || hostPath.isBlank()) {
            throw new IllegalArgumentException(
                "Mount refused: no host path given. Try: mount /path/to/folder as " + label);
        }
        var expanded = expandHome(hostPath.trim());
        var path = Path.of(expanded).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException(
                "Mount refused: " + path + " does not exist on this host.");
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(
                "Mount refused: " + path + " is a file, not a directory — mount its parent "
                + "folder instead, then read the file through the shelf.");
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException(
                "Mount refused: " + path + " exists but is not readable by the wyrdsekai process.");
        }
        mounts.computeIfAbsent(roomId, _ -> new LinkedHashMap<>())
            .put(label, path.toString());
        persist();
        return path;
    }

    /** Remove a shelf. Returns false when no such label was mounted. */
    public synchronized boolean unmount(String roomId, String label) {
        var forRoom = mounts.get(roomId);
        if (forRoom == null || forRoom.remove(label) == null) return false;
        if (forRoom.isEmpty()) mounts.remove(roomId);
        persist();
        return true;
    }

    /** Root directory of a mounted shelf, if that label exists for the room. */
    public synchronized Optional<Path> resolveRoot(String roomId, String label) {
        var forRoom = mounts.get(roomId);
        if (forRoom == null) return Optional.empty();
        var path = forRoom.get(label);
        return path == null ? Optional.empty() : Optional.of(Path.of(path));
    }

    /** A shelf-relative location: the label, its sandbox, and the path inside it. */
    public record ResolvedPath(String label, SandboxedFs fs, String relPath) {}

    /**
     * Resolve a {@code shelf/inner/path} string against this room's mounts.
     * The first path segment is the shelf label; the rest is resolved inside
     * a {@link SandboxedFs} rooted at the mounted directory, so {@code ..},
     * absolute paths and escaping symlinks are refused. Throws
     * {@link IllegalArgumentException} with a teaching message on any miss.
     */
    public ResolvedPath resolve(String roomId, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException(
                "No path given. Use <shelf>/<file>" + mountedHint(roomId));
        }
        var trimmed = rawPath.trim();
        if (trimmed.startsWith("~") || trimmed.startsWith("/")
                || trimmed.startsWith("\\")
                || (trimmed.length() >= 2 && trimmed.charAt(1) == ':')) {
            throw new IllegalArgumentException(
                "Host paths can't be read directly — mount the folder onto a shelf first "
                + "(mount " + parentDirOf(trimmed) + " as somename), then read "
                + "somename/<file>." + mountedHint(roomId));
        }
        var slash = trimmed.indexOf('/');
        var label = slash < 0 ? trimmed : trimmed.substring(0, slash);
        var rel = slash < 0 ? "" : trimmed.substring(slash + 1);
        var root = resolveRoot(roomId, label).orElseThrow(() ->
            new IllegalArgumentException(
                "No shelf named '" + label + "' is mounted in this Study."
                + mountedHint(roomId)));
        return new ResolvedPath(label, SandboxedFs.rootedAt(root), rel);
    }

    private String mountedHint(String roomId) {
        var labels = mountsFor(roomId).keySet();
        return labels.isEmpty()
            ? " Nothing is mounted yet — say: mount /path/to/folder as docs"
            : " Mounted shelves: " + String.join(", ", labels);
    }

    private static String parentDirOf(String path) {
        var idx = path.lastIndexOf('/');
        return idx > 0 ? path.substring(0, idx) : path;
    }

    private static String expandHome(String path) {
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
