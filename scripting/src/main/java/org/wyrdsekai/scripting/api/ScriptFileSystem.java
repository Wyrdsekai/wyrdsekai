package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Java-backed file system exposed to GraalJS scripts.
 * Available at {@link org.wyrdsekai.scripting.sandbox.SandboxLevel#SKILL_DATA} and above.
 *
 * <p>All operations are sandboxed to the workspace directory.
 * Path traversal attempts (using ".." or absolute paths) are blocked.
 *
 * <p>Scripts use this as:
 * <pre>
 *   fs.write("output.txt", "Hello world");
 *   var content = fs.read("output.txt");
 *   var files = fs.list(".");
 *   fs.delete("output.txt");
 * </pre>
 */
public class ScriptFileSystem {

    private static final Logger log = LoggerFactory.getLogger(ScriptFileSystem.class);

    /** Maximum file size for read/write (512 KB). */
    private static final int MAX_FILE_SIZE = 524_288;

    private final Path workspaceRoot;

    /**
     * Create a sandboxed filesystem rooted at the workspace directory.
     *
     * @param workspaceRoot The workspace root directory (must exist)
     */
    public ScriptFileSystem(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("Workspace root must not be null");
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Read a file relative to the workspace root.
     *
     * @param relativePath Path relative to workspace root
     * @return File contents as a string
     * @throws RuntimeException if file not found or path escapes workspace
     */
    @HostAccess.Export
    public String read(String relativePath) {
        var resolved = resolveSafe(relativePath);
        try {
            long size = Files.size(resolved);
            if (size > MAX_FILE_SIZE) {
                throw new RuntimeException("File too large: " + size + " bytes (max " + MAX_FILE_SIZE + ")");
            }
            return Files.readString(resolved);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read file: " + relativePath + " — " + e.getMessage(), e);
        }
    }

    /**
     * Write content to a file relative to the workspace root.
     * Creates parent directories if needed.
     *
     * @param relativePath Path relative to workspace root
     * @param content      Content to write
     * @throws RuntimeException if path escapes workspace or content too large
     */
    @HostAccess.Export
    public void write(String relativePath, String content) {
        var resolved = resolveSafe(relativePath);
        if (content != null && content.length() > MAX_FILE_SIZE) {
            throw new RuntimeException("Content too large: " + content.length() + " chars (max " + MAX_FILE_SIZE + ")");
        }
        try {
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content != null ? content : "");
        } catch (IOException e) {
            throw new RuntimeException("Cannot write file: " + relativePath + " — " + e.getMessage(), e);
        }
    }

    /**
     * List files and directories in a directory relative to the workspace root.
     *
     * @param relativePath Directory path relative to workspace root
     * @return List of file/directory names
     * @throws RuntimeException if path escapes workspace or directory not found
     */
    @HostAccess.Export
    public List<String> list(String relativePath) {
        var resolved = resolveSafe(relativePath);
        if (!Files.isDirectory(resolved)) {
            throw new RuntimeException("Not a directory: " + relativePath);
        }
        try (Stream<Path> entries = Files.list(resolved)) {
            return entries
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("Cannot list directory: " + relativePath + " — " + e.getMessage(), e);
        }
    }

    /**
     * Check if a file or directory exists relative to the workspace root.
     *
     * @param relativePath Path relative to workspace root
     * @return true if the path exists
     */
    @HostAccess.Export
    public boolean exists(String relativePath) {
        var resolved = resolveSafe(relativePath);
        return Files.exists(resolved);
    }

    /**
     * Delete a file relative to the workspace root.
     * Does not delete directories (must be empty first).
     *
     * @param relativePath Path relative to workspace root
     * @throws RuntimeException if path escapes workspace or deletion fails
     */
    @HostAccess.Export
    public void delete(String relativePath) {
        var resolved = resolveSafe(relativePath);
        try {
            if (!Files.exists(resolved)) return;
            if (Files.isDirectory(resolved)) {
                throw new RuntimeException("Cannot delete directory directly: " + relativePath);
            }
            Files.delete(resolved);
        } catch (IOException e) {
            throw new RuntimeException("Cannot delete file: " + relativePath + " — " + e.getMessage(), e);
        }
    }

    /**
     * Resolve a relative path safely, preventing path traversal.
     * All returned paths are guaranteed to be within the workspace root.
     */
    Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        // Block absolute paths
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new IllegalArgumentException("Absolute paths not allowed: " + relativePath);
        }
        var resolved = workspaceRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path traversal detected: " + relativePath);
        }
        return resolved;
    }
}
