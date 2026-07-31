package org.wyrdsekai.core.coding;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reusable container↔host workspace path translator for coding backends
 * that run inside Docker (OpenHands, Goose's container mode, future
 * sandboxed adapters).
 *
 * <p>Coding backends typically tell their agent about a workspace
 * directory <i>inside</i> a container (e.g. {@code /workspace}). When
 * the agent edits a file via its tool surface, the file lands inside
 * that container path — but the test JVM, room script, or
 * {@link HostSubprocessRunner} all run on the <i>host</i>. To inspect
 * those files (for {@code use codex examine}) or run them (for
 * {@code use artifact run}), we need the host-equivalent path.</p>
 *
 * <p>Mounts are configured per-backend via env vars or config keys.
 * Format is {@code <container_prefix>:<host_path>}, mirroring Docker's
 * bind-mount syntax. Multiple mounts can be configured by joining with
 * {@code ;} (pattern: {@code /workspace:/data/oh-ws;/srv:/data/srv}).</p>
 *
 * <p><b>Backend-agnostic by design.</b> OpenHands uses
 * {@code WYRDSEKAI_OPENHANDS_WORKSPACE_MOUNT}; Goose will use
 * {@code WYRDSEKAI_GOOSE_WORKSPACE_MOUNT}; both call {@link
 * #fromEnv(String)} with their own env-var name. The translation logic
 * is identical, so it lives here once.</p>
 *
 * <p><b>Conversation-scoped paths.</b> Some backends (OpenHands V1)
 * persist <i>conversation metadata</i> under
 * {@code <host>/conversations/<id>/}, while the agent's actual
 * <i>working directory</i> is the bind-mount root. {@link
 * #resolveFile(Path, String)} treats the file path as relative to the
 * working dir; callers that need to look up conversation metadata can
 * use {@link #conversationDir(UUID)} explicitly.</p>
 */
public final class CodingWorkspaceMount {

    private static final Logger log = LoggerFactory.getLogger(CodingWorkspaceMount.class);

    /** A single container-prefix → host-path bind. */
    public record Bind(String containerPrefix, String hostPath) {
        public Bind {
            if (containerPrefix == null || containerPrefix.isBlank()) {
                throw new IllegalArgumentException("containerPrefix must be non-blank");
            }
            if (hostPath == null || hostPath.isBlank()) {
                throw new IllegalArgumentException("hostPath must be non-blank");
            }
            // Normalise: strip trailing slash on prefixes so we don't
            // double-up on the boundary.
            if (containerPrefix.length() > 1 && containerPrefix.endsWith("/")) {
                containerPrefix = containerPrefix.substring(0, containerPrefix.length() - 1);
            }
            if (hostPath.length() > 1 && hostPath.endsWith("/")) {
                hostPath = hostPath.substring(0, hostPath.length() - 1);
            }
        }
    }

    /** Empty mount — passes paths through unchanged. */
    public static final CodingWorkspaceMount NONE = new CodingWorkspaceMount(List.of());

    private final List<Bind> binds;

    public CodingWorkspaceMount(List<Bind> binds) {
        this.binds = binds == null ? List.of() : List.copyOf(binds);
    }

    /** True iff at least one bind is configured. */
    public boolean isActive() { return !binds.isEmpty(); }

    /**
     * Read a {@code <container_prefix>:<host_path>} (or
     * {@code ;}-separated multi-bind) spec from the named env var. Returns
     * {@link #NONE} when the env var is unset or blank — callers should
     * treat that as "pass paths through unchanged".
     */
    public static CodingWorkspaceMount fromEnv(String envVarName) {
        if (envVarName == null) return NONE;
        var raw = System.getenv(envVarName);
        return parse(raw);
    }

    /** Parse a spec string. {@link #NONE} on null/blank. */
    public static CodingWorkspaceMount parse(String spec) {
        if (spec == null || spec.isBlank()) return NONE;
        var parts = spec.split(";");
        var binds = new ArrayList<Bind>(parts.length);
        for (var part : parts) {
            var trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                log.warn("CodingWorkspaceMount: skipping malformed bind '{}' (expected container:host)",
                    trimmed);
                continue;
            }
            try {
                binds.add(new Bind(trimmed.substring(0, colon), trimmed.substring(colon + 1)));
            } catch (IllegalArgumentException e) {
                log.warn("CodingWorkspaceMount: skipping bind '{}': {}", trimmed, e.getMessage());
            }
        }
        return binds.isEmpty() ? NONE : new CodingWorkspaceMount(binds);
    }

    /**
     * Translate a container-side path to its host-equivalent. If no
     * configured prefix matches, the path is returned unchanged (callers
     * decide whether to treat that as an error or as host-native).
     */
    public String toHost(String containerPath) {
        if (containerPath == null || containerPath.isBlank() || binds.isEmpty()) {
            return containerPath;
        }
        for (var b : binds) {
            if (containerPath.equals(b.containerPrefix())) return b.hostPath();
            if (containerPath.startsWith(b.containerPrefix() + "/")) {
                return b.hostPath() + containerPath.substring(b.containerPrefix().length());
            }
        }
        return containerPath;
    }

    /**
     * Resolve a file path against a container-side workspace, returning
     * the host-side {@link Path} the test JVM should read.
     *
     * <p>Handles three flavours of {@code filePath}:</p>
     * <ul>
     *   <li>Absolute container path that starts with a configured
     *       prefix (e.g. {@code /workspace/greet.py}) — translated.</li>
     *   <li>Absolute path that doesn't match any prefix — taken as-is
     *       (likely already host-side).</li>
     *   <li>Relative path (e.g. {@code greet.py}) — resolved against
     *       the host-translated workspace.</li>
     * </ul>
     */
    public Path resolveFile(Path containerWorkspace, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return containerWorkspace;
        }
        var hostWorkspace = Path.of(toHost(containerWorkspace.toString()));
        // Absolute file path: try to translate via prefix first.
        if (filePath.startsWith("/")) {
            var translated = toHost(filePath);
            if (!translated.equals(filePath)) {
                // We translated — return the host path directly.
                return Path.of(translated);
            }
            // Translation didn't apply, but path is absolute; trust it.
            return Path.of(filePath);
        }
        // Relative — resolve under the host-side workspace root.
        return hostWorkspace.resolve(filePath);
    }

    /**
     * For backends that scope per-conversation persistence under a
     * {@code conversations/<id>/} subdir of the workspace mount, return
     * the host path to that subdir. Returns empty when no mount is
     * configured. The conversation-id format is backend-specific —
     * callers should pass the literal subdir name.
     */
    public Optional<Path> conversationDir(String workspaceContainerPath, String conversationSubdir) {
        if (!isActive() || workspaceContainerPath == null || conversationSubdir == null
                || conversationSubdir.isBlank()) {
            return Optional.empty();
        }
        var host = Path.of(toHost(workspaceContainerPath));
        var candidate = host.resolve("conversations").resolve(conversationSubdir);
        return Optional.of(candidate);
    }

    /**
     * Best-effort check that the host path is readable. Used by callers
     * to detect mis-configured mounts early.
     */
    public boolean hostPathExists(String containerPath) {
        var host = toHost(containerPath);
        return host != null && !host.equals(containerPath) && Files.exists(Path.of(host));
    }

    @Override
    public String toString() {
        return "CodingWorkspaceMount" + binds;
    }

    @SuppressWarnings("unused")
    private static Map<String, String> ignoreImport() {
        // Keep imports stable across edits.
        return Map.of();
    }

    @SuppressWarnings("unused")
    private static UUID ignoreImport2() { return null; }
}
