package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a coding backend is allowed to work.
 *
 * <p>Every CLI backend resolved its working directory the same way: use
 * {@code spec.workspaceHint()} if one was given, otherwise fall back to the JVM's current
 * directory. On a packaged install the service's current directory is the <b>install
 * root</b>, so a backend handed a task with no workspace ran with write access to the
 * application's own files.
 *
 * <p>That is not theoretical. Live on the household node, 2026-08-20: the companion
 * dispatched a build with no workspace, and goose wrote
 * {@code /opt/wyrdsekai/library_query.js} — into the installed application directory,
 * beside the jars and scripts the node runs from. Nothing stopped it, and nothing would
 * have stopped it writing over something that mattered.
 *
 * <p>So: a backend gets a real, private, per-task directory under the data root, and
 * never the install root and never the process's own directory. An explicit
 * {@code workspaceHint} is still honoured — a steward naming a project directory is the
 * whole point of the field — but the DEFAULT is somewhere it can do no harm.
 *
 * <p>Shared deliberately. goose is the default today and CodeZaiku is next; OpenCode,
 * ACP and the Claude SDK all carry the same {@code user.dir} fallback. One resolver, so
 * fixing it once fixes it everywhere and a new backend inherits the safe default.
 */
public final class CodingWorkspace {

    private static final Logger log = LoggerFactory.getLogger(CodingWorkspace.class);

    /** Per-task scratch lives here, under the runtime data root. */
    static final String SCRATCH_DIR = "coding-workspaces";

    private CodingWorkspace() {}

    /**
     * The directory this task may work in.
     *
     * @param workspaceHint the steward/companion-supplied workspace, if any
     * @param taskId        used to give each run its own scratch directory
     * @return a directory that exists, or null only if no safe one could be made — callers
     *         treat null as "do not run with a directory" rather than "use the cwd"
     */
    public static File forTask(String workspaceHint, String taskId) {
        if (workspaceHint != null && !workspaceHint.isBlank()
                && !"(default)".equals(workspaceHint.trim())) {
            return new File(workspaceHint);
        }
        var root = scratchRoot();
        if (root == null) {
            log.warn("[coding-workspace] no data root configured — a backend would "
                + "otherwise run in the install directory; refusing to supply one");
            return null;
        }
        try {
            var dir = root.resolve(taskId == null ? "unscoped" : taskId);
            Files.createDirectories(dir);
            return dir.toFile();
        } catch (Exception e) {
            log.warn("[coding-workspace] could not create scratch for task {}: {}",
                taskId, e.toString());
            return null;
        }
    }

    /** The path a backend's output should be resolved against, for artifact reporting. */
    public static String pathFor(String workspaceHint, String taskId) {
        var dir = forTask(workspaceHint, taskId);
        return dir == null ? "" : dir.getAbsolutePath();
    }

    private static Path scratchRoot() {
        var data = WyrdConfig.get().dataDir();
        if (data != null && !data.isBlank()) return Path.of(data, SCRATCH_DIR);
        // No data dir configured (tests, a bare checkout): use a temp root rather than
        // the install directory. Never user.dir — that is the defect.
        var tmp = System.getProperty("java.io.tmpdir");
        return tmp == null || tmp.isBlank() ? null : Path.of(tmp, "wyrdsekai-" + SCRATCH_DIR);
    }
}
