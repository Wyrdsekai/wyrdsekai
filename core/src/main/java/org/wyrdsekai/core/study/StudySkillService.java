package org.wyrdsekai.core.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.mcp.McpServiceConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The Study's local "skill" MCP service (W1).
 *
 * <p>Backs the whole shell/mount surface of {@code scripts/rooms/study.js}:
 * {@code ls}/{@code find}/{@code grep}/{@code cat} over mounted shelves, and
 * document extraction. Before this service existed, every
 * {@code world.mcp("skill", ...)} call answered "Unknown service: skill" —
 * the surface was theater end-to-end.</p>
 *
 * <p>Tools:</p>
 * <ul>
 *   <li>{@code study.fs.read} {path} — read a text file from a mounted shelf</li>
 *   <li>{@code study.fs.list} {path} — list a shelf directory ("" lists shelves)</li>
 *   <li>{@code study.fs.search} {query, type?, path?} — filename or content search</li>
 *   <li>{@code study.fs.mounts} {} — the room's shelf table as JSON</li>
 *   <li>{@code vault.doc.extract} {path|itemPath|itemId} — plain-text extraction</li>
 * </ul>
 *
 * <p>Every path stays inside a mounted root via {@link StudyMountRegistry}'s
 * SandboxedFs resolution. Errors are thrown with messages that teach; the MCP
 * gateway surfaces them verbatim as {@code result.error}.</p>
 */
public final class StudySkillService {

    public static final String SERVICE_ID = "skill";

    /** Host-injected room-of-origin param (see WorldApi.mcp). */
    public static final String ROOM_PARAM = "_room";

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Formats we know we cannot extract yet — be honest instead of mangling bytes. */
    private static final Set<String> UNSUPPORTED_DOC_EXTS = Set.of(
        "pdf", "doc", "docx", "epub", "odt", "odp", "ods", "rtf",
        "ppt", "pptx", "xls", "xlsx", "pages", "key", "numbers");

    private static final int MAX_SEARCH_MATCHES = 100;
    private static final int MAX_SEARCH_DEPTH = 8;
    private static final long MAX_CONTENT_SEARCH_BYTES = 1024 * 1024;

    private final StudyMountRegistry mounts;

    public StudySkillService(StudyMountRegistry mounts) {
        this.mounts = mounts;
    }

    /**
     * Register this service with the gateway under id {@link #SERVICE_ID}.
     * Rate limits are raised well above the remote defaults — every shell
     * keystroke in the Study lands here, and 10/min would strangle an
     * ordinary browsing session.
     */
    public static StudySkillService register(McpGatewayService gateway,
                                             StudyMountRegistry mounts) {
        var service = new StudySkillService(mounts);
        gateway.registerLocalService(
            new McpServiceConfig(SERVICE_ID, "Study skills (local)",
                "local", "local:" + SERVICE_ID, "local",
                null,
                Map.of("per_agent", 120, "per_service", 600, "per_zone", 600),
                true),
            (agentId, zoneId, toolName, params) -> service.call(toolName, params));
        return service;
    }

    /** Dispatch a tool call. Throws with a teaching message on any refusal. */
    public String call(String toolName, Map<String, Object> params) throws Exception {
        var safeParams = params == null ? Map.<String, Object>of() : params;
        return switch (toolName) {
            case "study.fs.read" -> read(roomOf(safeParams), pathParam(safeParams));
            case "study.fs.list" -> list(roomOf(safeParams), str(safeParams, "path"));
            case "study.fs.search" -> search(roomOf(safeParams), safeParams);
            case "study.fs.mounts" -> mountsJson(roomOf(safeParams));
            case "vault.doc.extract" -> extract(roomOf(safeParams), pathParam(safeParams));
            default -> throw new IllegalArgumentException(
                "The Study skill service has no tool named '" + toolName + "'. It offers: "
                + "study.fs.read, study.fs.list, study.fs.search, study.fs.mounts, "
                + "vault.doc.extract.");
        };
    }

    // ─── tools ───────────────────────────────────────────────────────

    private String read(String roomId, String path) throws IOException {
        var resolved = mounts.resolve(roomId, path);
        if (resolved.relPath().isBlank()) {
            throw new IllegalArgumentException(
                "'" + resolved.label() + "' is a shelf, not a file — "
                + "list it first (ls " + resolved.label() + "), then read "
                + resolved.label() + "/<file>.");
        }
        try {
            return resolved.fs().read(resolved.relPath());
        } catch (IOException e) {
            throw new IOException(translateFsError(e, path, resolved.label()), e);
        }
    }

    private String list(String roomId, String path) {
        if (path == null || path.isBlank() || path.trim().equals("/")) {
            var table = mounts.mountsFor(roomId);
            if (table.isEmpty()) {
                return "No shelves are mounted yet. Say: mount /path/to/folder as docs";
            }
            var lines = new ArrayList<String>();
            table.forEach((label, host) -> lines.add(label + "/ -> " + host));
            return String.join("\n", lines);
        }
        var resolved = mounts.resolve(roomId, path);
        var entries = resolved.fs().list(resolved.relPath());
        if (entries.isEmpty()) {
            // Distinguish empty dir from wrong path — teach, don't shrug.
            if (!resolved.relPath().isBlank() && !resolved.fs().exists(resolved.relPath())) {
                throw new IllegalArgumentException(
                    "Nothing at '" + path + "' on shelf '" + resolved.label()
                    + "'. List the shelf itself first: ls " + resolved.label());
            }
            var stat = resolved.fs().stat(resolved.relPath());
            if (Boolean.FALSE.equals(stat.get("isDir"))) {
                throw new IllegalArgumentException(
                    "'" + path + "' is a file — read it instead: cat " + path);
            }
            return "(empty)";
        }
        var lines = new ArrayList<String>();
        for (var entry : entries) {
            var name = String.valueOf(entry.get("name"));
            if (Boolean.TRUE.equals(entry.get("isDir"))) {
                lines.add(name + "/");
            } else {
                lines.add(name + "  (" + entry.get("size") + " bytes)");
            }
        }
        return String.join("\n", lines);
    }

    private String mountsJson(String roomId) throws IOException {
        return mapper.writeValueAsString(mounts.mountsFor(roomId));
    }

    private String search(String roomId, Map<String, Object> params) throws IOException {
        var query = str(params, "query");
        if (query.isBlank()) query = str(params, "pattern");
        if (query.isBlank()) {
            throw new IllegalArgumentException(
                "Search needs a query. Try: find <name-fragment> or grep <text> <shelf>");
        }
        var byContent = "content".equalsIgnoreCase(str(params, "type"));
        var explicitPath = str(params, "path");

        // Shell convention tolerance: "grep TODO notes" arrives as one query
        // string. If the last token names a mounted location, treat it as the
        // path and the rest as the pattern.
        var pattern = query.trim();
        var scopePath = explicitPath;
        if (scopePath.isBlank() && pattern.contains(" ")) {
            var lastSpace = pattern.lastIndexOf(' ');
            var candidate = pattern.substring(lastSpace + 1);
            if (resolvesQuietly(roomId, candidate)) {
                scopePath = candidate;
                pattern = pattern.substring(0, lastSpace).trim();
            }
        }

        var roots = searchRoots(roomId, scopePath);
        if (roots.isEmpty()) {
            return "No shelves are mounted to search. Say: mount /path/to/folder as docs";
        }

        var matches = new ArrayList<String>();
        for (var root : roots) {
            if (matches.size() >= MAX_SEARCH_MATCHES) break;
            if (byContent) {
                contentSearch(root, pattern, matches);
            } else {
                nameSearch(root, pattern, matches);
            }
        }
        if (matches.isEmpty()) {
            return (byContent ? "No lines containing '" : "No files matching '")
                + pattern + "' under " + describeScope(roomId, scopePath) + ".";
        }
        var header = matches.size() >= MAX_SEARCH_MATCHES
            ? "First " + MAX_SEARCH_MATCHES + " matches:\n" : "";
        return header + String.join("\n", matches);
    }

    private String extract(String roomId, String path) throws IOException {
        var resolved = mounts.resolve(roomId, path);
        if (resolved.relPath().isBlank()) {
            throw new IllegalArgumentException(
                "'" + resolved.label() + "' is a shelf, not a document — name a file on it: "
                + resolved.label() + "/<file>");
        }
        var ext = extensionOf(resolved.relPath());
        if (UNSUPPORTED_DOC_EXTS.contains(ext)) {
            throw new IllegalArgumentException(
                "Extraction for ." + ext + " isn't supported yet — text files only. "
                + "Convert it to .txt/.md on the host, or read a text sibling instead.");
        }
        String content;
        try {
            content = resolved.fs().read(resolved.relPath());
        } catch (IOException e) {
            throw new IOException(translateFsError(e, path, resolved.label()), e);
        }
        if (looksBinary(content)) {
            throw new IllegalArgumentException(
                "'" + path + "' is a binary file — extraction handles text files only.");
        }
        return content;
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private String roomOf(Map<String, Object> params) {
        var room = str(params, ROOM_PARAM);
        if (room.isBlank()) {
            throw new IllegalArgumentException(
                "This call did not carry its room of origin — the Study skill service "
                + "resolves shelves per room. Call it through world.mcp() from a room "
                + "script (which injects _room), or pass _room explicitly.");
        }
        return room;
    }

    private String pathParam(Map<String, Object> params) {
        var path = str(params, "path");
        if (path.isBlank()) path = str(params, "itemPath");
        if (path.isBlank()) path = str(params, "itemId");
        return path;
    }

    private static String str(Map<String, Object> params, String key) {
        var value = params.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean resolvesQuietly(String roomId, String path) {
        try {
            var resolved = mounts.resolve(roomId, path);
            return resolved.relPath().isBlank() || resolved.fs().exists(resolved.relPath());
        } catch (Exception _) {
            return false;
        }
    }

    /** (label, dir) pairs to search: one scoped location, or every shelf root. */
    private List<Root> searchRoots(String roomId, String scopePath) {
        if (scopePath != null && !scopePath.isBlank()) {
            var resolved = mounts.resolve(roomId, scopePath);
            var dir = resolved.fs().resolve(resolved.relPath());
            return List.of(new Root(resolved.label(), resolved.fs().root(), dir));
        }
        var out = new ArrayList<Root>();
        mounts.mountsFor(roomId).forEach((label, host) -> {
            var root = Path.of(host);
            out.add(new Root(label, root, root));
        });
        return out;
    }

    private record Root(String label, Path shelfRoot, Path searchDir) {}

    private void nameSearch(Root root, String pattern, List<String> matches) throws IOException {
        var needle = pattern.toLowerCase(Locale.ROOT);
        if (!Files.isDirectory(root.searchDir())) return;
        try (Stream<Path> walk = Files.walk(root.searchDir(), MAX_SEARCH_DEPTH)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle))
                .limit((long) MAX_SEARCH_MATCHES - matches.size())
                .forEach(p -> matches.add(
                    root.label() + "/" + root.shelfRoot().relativize(p)));
        }
    }

    private void contentSearch(Root root, String pattern, List<String> matches) throws IOException {
        var needle = pattern.toLowerCase(Locale.ROOT);
        if (!Files.isDirectory(root.searchDir())) {
            // grep of a single file
            if (Files.isRegularFile(root.searchDir())) {
                grepFile(root, root.searchDir(), needle, matches);
            }
            return;
        }
        try (Stream<Path> walk = Files.walk(root.searchDir(), MAX_SEARCH_DEPTH)) {
            var files = walk.filter(Files::isRegularFile).toList();
            for (var file : files) {
                if (matches.size() >= MAX_SEARCH_MATCHES) return;
                grepFile(root, file, needle, matches);
            }
        }
    }

    private void grepFile(Root root, Path file, String needle, List<String> matches) {
        try {
            if (Files.size(file) > MAX_CONTENT_SEARCH_BYTES) return;
            var content = Files.readString(file, StandardCharsets.UTF_8);
            if (looksBinary(content)) return;
            var lines = content.split("\n", -1);
            var rel = root.label() + "/" + root.shelfRoot().relativize(file);
            for (int i = 0; i < lines.length; i++) {
                if (matches.size() >= MAX_SEARCH_MATCHES) return;
                if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                    var line = lines[i].length() > 200 ? lines[i].substring(0, 200) + "…" : lines[i];
                    matches.add(rel + ":" + (i + 1) + ": " + line.trim());
                }
            }
        } catch (IOException _) {
            // Unreadable/undecodable file — skip quietly; it is a search, not an audit.
        }
    }

    private String describeScope(String roomId, String scopePath) {
        if (scopePath != null && !scopePath.isBlank()) return "'" + scopePath + "'";
        var labels = mounts.mountsFor(roomId).keySet();
        return labels.isEmpty() ? "(no shelves)" : "shelves " + String.join(", ", labels);
    }

    private static String extensionOf(String path) {
        var name = path.substring(path.lastIndexOf('/') + 1);
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean looksBinary(String content) {
        var probe = content.length() > 8192 ? content.substring(0, 8192) : content;
        return probe.indexOf('\0') >= 0 || probe.indexOf('\uFFFD') >= 0;
    }

    private static String translateFsError(IOException e, String path, String label) {
        var msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith("not_found")) {
            return "Nothing at '" + path + "' on shelf '" + label
                + "'. List it to see what's there: ls " + label;
        }
        if (msg.startsWith("is_directory")) {
            return "'" + path + "' is a directory — list it instead: ls " + path;
        }
        if (msg.startsWith("file_too_large")) {
            return "'" + path + "' is too large to read in-world (" + msg + ").";
        }
        return "Could not read '" + path + "': " + msg;
    }
}
