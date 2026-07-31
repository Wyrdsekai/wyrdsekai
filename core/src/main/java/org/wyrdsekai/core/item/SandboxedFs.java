package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * sandboxed per-agent filesystem helper.
 *
 * <p>All operations are confined to {@code <root>/items/<agent-did>/fs/}.
 * Paths containing {@code ..} segments, absolute paths, or symlinks pointing
 * outside the sandbox are rejected with a structured error.</p>
 *
 * <p>Caps: 4MB per file, 64MB per agent total (per spec §4.23).</p>
 */
public final class SandboxedFs {

    private static final Logger log = LoggerFactory.getLogger(SandboxedFs.class);

    /** Per-spec §4.23 — max bytes per individual file. */
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** Per-spec §4.23 — max total bytes across the whole sandbox. */
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private final Path root;        // <root>/items/<agentId>/fs (canonicalised)

    public SandboxedFs(Path dataRoot, String agentId) {
        if (dataRoot == null) {
            throw new IllegalArgumentException("dataRoot is null");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is blank");
        }
        // Sanitise agent-id to filesystem-safe form. DIDs use ':' which is
        // invalid on some filesystems; encode them as '_'. #14 (2026-07-19 OSS
        // hardening): '.' is NOT in the allowed set — otherwise an agentId of
        // "." or ".." survived sanitisation and the normalize() below collapsed
        // "items/../fs" out of the items dir, escaping the per-agent sandbox.
        // (Structured DIDs contain no '.', so existing sandbox dirs are unchanged.)
        var safeAgent = agentId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safeAgent.isBlank() || safeAgent.equals(".") || safeAgent.equals("..")
                || safeAgent.chars().allMatch(c -> c == '_')) {
            // Degenerate/ambiguous — fall back to a stable, path-safe encoding.
            safeAgent = "agent_" + Integer.toHexString(agentId.hashCode());
        }
        this.root = dataRoot.resolve("items").resolve(safeAgent).resolve("fs").toAbsolutePath().normalize();
    }

    private SandboxedFs(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Sandbox rooted directly at an arbitrary directory — used by the Study's
     * mounted-shelf surface (W1), where the root is a steward-mounted host
     * path rather than the per-agent {@code items/<agent>/fs} layout. All the
     * same traversal/symlink containment rules apply.
     */
    public static SandboxedFs rootedAt(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root is null");
        }
        return new SandboxedFs(root);
    }

    /** Ensure the sandbox directory exists. Returns the canonical root. */
    public Path ensureRoot() throws IOException {
        if (!Files.exists(root)) {
            Files.createDirectories(root);
        }
        return root;
    }

    /** Canonical sandbox root (test access). */
    public Path root() { return root; }

    /**
     * Resolve a relative path within the sandbox. Throws IllegalArgumentException
     * if the path tries to escape the sandbox via {@code ..}, absolute paths,
     * or symlinks pointing outside.
     */
    public Path resolve(String relPath) {
        if (relPath == null) throw new IllegalArgumentException("path is null");
        var trimmed = relPath.trim();
        if (trimmed.isEmpty()) {
            // Empty -> root itself
            return root;
        }
        // Reject absolute paths
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")
                || (trimmed.length() >= 2 && trimmed.charAt(1) == ':')) {
            throw new IllegalArgumentException("absolute path not allowed: " + relPath);
        }
        // Reject explicit '..' segments — defence in depth even though resolve+normalize would catch.
        for (var segment : trimmed.split("[/\\\\]")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("parent traversal '..' not allowed: " + relPath);
            }
        }
        var resolved = root.resolve(trimmed).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes sandbox: " + relPath);
        }
        // #14 (2026-07-19 OSS hardening) — symlink containment. The old check only
        // inspected the FINAL component, so an INTERMEDIATE symlink (a/ -> /etc,
        // accessed as a/passwd) or a symlinked parent being written THROUGH slipped
        // past. Canonicalise the deepest EXISTING ancestor of the target and
        // require its real path to stay under the sandbox root's real path — this
        // resolves every symlink on the way down, not just the leaf.
        assertRealPathContained(resolved, relPath);
        return resolved;
    }

    /**
     * Resolve symlinks along the whole path and assert containment. Walks up to
     * the nearest existing ancestor (the target itself may not exist yet, e.g. a
     * write), takes its real path, and checks it is under the sandbox root's real
     * path. Fails closed on any resolution error.
     */
    private void assertRealPathContained(Path resolved, String relPath) {
        Path probe = resolved;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        // Only canonicalise symlinks that live INSIDE the sandbox subtree. When
        // the nearest existing ancestor is at/above the (not-yet-created) root,
        // the lexical containment check already proved safety and there is
        // nothing inside root to resolve. `probe` existing and being under `root`
        // implies `root` itself exists, so root.toRealPath() is safe.
        if (probe == null || !probe.startsWith(root)) return;
        try {
            var real = probe.toRealPath();
            var rootReal = root.toRealPath();
            if (!real.startsWith(rootReal)) {
                throw new IllegalArgumentException("path escapes sandbox (symlink): " + relPath);
            }
        } catch (IOException e) {
            // Fail closed — if we can't prove containment, deny.
            throw new IllegalArgumentException("path resolution failed: " + relPath);
        }
    }

    /** Total bytes currently used in the sandbox. */
    public long totalBytes() {
        if (!Files.exists(root)) return 0L;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                .filter(Files::isRegularFile)
                .mapToLong(p -> {
                    try { return Files.size(p); } catch (IOException _) { return 0L; }
                }).sum();
        } catch (IOException e) {
            log.warn("totalBytes failed: {}", e.getMessage());
            return 0L;
        }
    }

    /** Read text content (UTF-8). */
    public String read(String relPath) throws IOException {
        var p = resolve(relPath);
        if (!Files.exists(p)) {
            throw new IOException("not_found: " + relPath);
        }
        if (Files.isDirectory(p)) {
            throw new IOException("is_directory: " + relPath);
        }
        var size = Files.size(p);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("file_too_large: " + size + " > " + MAX_FILE_BYTES);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * Write text content (UTF-8). Returns map with size on success; throws
     * IOException with reason on failure.
     */
    public Map<String, Object> write(String relPath, String content) throws IOException {
        var p = resolve(relPath);
        var bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("file_too_large: " + bytes.length + " > " + MAX_FILE_BYTES);
        }
        ensureRoot();
        // Create parent dirs as needed (still within sandbox).
        var parent = p.getParent();
        if (parent != null && !parent.equals(root) && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        // Quota check using delta: new file size minus existing (if any).
        var existing = Files.exists(p) ? Files.size(p) : 0L;
        var quotaDelta = bytes.length - existing;
        if (quotaDelta > 0 && totalBytes() + quotaDelta > MAX_TOTAL_BYTES) {
            throw new IOException("quota_exceeded: would exceed " + MAX_TOTAL_BYTES + " total bytes");
        }
        Files.write(p, bytes);
        var out = new HashMap<String, Object>();
        out.put("ok", true);
        out.put("size", (long) bytes.length);
        return out;
    }

    /** List a directory. Returns empty list if missing. */
    public List<Map<String, Object>> list(String relDir) {
        try {
            var dir = (relDir == null || relDir.isBlank()) ? root : resolve(relDir);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) return List.of();
            var out = new ArrayList<Map<String, Object>>();
            try (Stream<Path> children = Files.list(dir)) {
                children.sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p -> {
                        try {
                            var attr = Files.readAttributes(p, BasicFileAttributes.class);
                            var m = new HashMap<String, Object>();
                            m.put("name", p.getFileName().toString());
                            m.put("size", attr.size());
                            m.put("modified", attr.lastModifiedTime().toMillis());
                            m.put("isDir", attr.isDirectory());
                            out.add(m);
                        } catch (IOException _) {}
                    });
            }
            return out;
        } catch (Exception e) {
            log.warn("list failed for {}: {}", relDir, e.getMessage());
            return List.of();
        }
    }

    /** Delete a file or empty directory. */
    public Map<String, Object> delete(String relPath) throws IOException {
        var p = resolve(relPath);
        if (p.equals(root)) {
            throw new IOException("cannot_delete_root");
        }
        if (!Files.exists(p)) {
            return Map.of("ok", false, "error", "not_found");
        }
        Files.delete(p);
        return Map.of("ok", true);
    }

    /** Existence check. Never throws — returns false for invalid paths. */
    public boolean exists(String relPath) {
        try {
            return Files.exists(resolve(relPath));
        } catch (Exception _) {
            return false;
        }
    }

    /** Stat a file. Returns map with name/size/modified/isDir or {error}. */
    public Map<String, Object> stat(String relPath) {
        try {
            var p = resolve(relPath);
            if (!Files.exists(p)) return Map.of("error", "not_found");
            var attr = Files.readAttributes(p, BasicFileAttributes.class);
            var out = new HashMap<String, Object>();
            out.put("name", p.getFileName() == null ? "" : p.getFileName().toString());
            out.put("size", attr.size());
            out.put("modified", attr.lastModifiedTime().toMillis());
            out.put("isDir", attr.isDirectory());
            return out;
        } catch (Exception e) {
            return Map.of("error", e.getMessage() == null ? "stat_failed" : e.getMessage());
        }
    }

    /** Create a directory inside the sandbox. */
    public Map<String, Object> mkdir(String relPath) throws IOException {
        var p = resolve(relPath);
        Files.createDirectories(p);
        return Map.of("ok", true);
    }
}
