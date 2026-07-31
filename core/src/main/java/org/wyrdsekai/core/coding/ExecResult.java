package org.wyrdsekai.core.coding;

import java.time.Duration;

/**
 * Result of executing an artifact (codex or build artifact) — the
 * payload returned by {@link CodingTaskBackend#runArtifact},
 * {@link CodingTaskBackend#testArtifact}, etc.
 *
 * <p>. {@code success} reflects
 * exit code 0 + no exception; {@code unsupportedReason} is non-null
 * iff the backend doesn't implement the verb (handlers narrate this
 * distinctly from "ran but failed").</p>
 *
 * @param success            true iff the run succeeded
 * @param stdout             captured stdout (never null; "" on miss)
 * @param stderr             captured stderr (never null; "" on miss)
 * @param exitCode           process exit code; -1 when no process ran
 * @param duration           wallclock time (never null)
 * @param entrypoint         the command that was actually executed,
 *                           for narration / debugging. Empty when
 *                           unsupported.
 * @param unsupportedReason  null when the backend ran the artifact;
 *                           otherwise human-readable "why not"
 */
public record ExecResult(
        boolean success,
        String stdout,
        String stderr,
        int exitCode,
        Duration duration,
        String entrypoint,
        String unsupportedReason) {

    public ExecResult {
        if (stdout == null) stdout = "";
        if (stderr == null) stderr = "";
        if (duration == null) duration = Duration.ZERO;
        if (entrypoint == null) entrypoint = "";
    }

    /**
     * The backend doesn't support this verb yet. Surface the reason so
     * narration can degrade cleanly.
     */
    public static ExecResult unsupported(String backendName, String verb) {
        return new ExecResult(false, "", "", -1, Duration.ZERO, "",
            backendName + " does not support '" + verb + "' yet");
    }

    /**
     * The backend supports the verb but couldn't find the artifact /
     * its workspace. Distinct from "unsupported" so callers can tell
     * "no such artifact" from "this backend never runs anything".
     */
    public static ExecResult notFound(String backendName, String artifactId) {
        return new ExecResult(false, "", "", -1, Duration.ZERO, "",
            backendName + ": no workspace found for artifact " + artifactId);
    }

    /**
     * The backend tried to run but couldn't detect an entrypoint —
     * e.g. an empty workspace, no recognised build/run hint.
     */
    public static ExecResult noEntrypoint(String backendName, String workspacePath) {
        return new ExecResult(false, "", "", -1, Duration.ZERO, "",
            backendName + ": no runnable entrypoint detected in '" + workspacePath
                + "' (looked for Makefile/run, package.json, Cargo.toml, go.mod, "
                + "main.py, index.js)");
    }

    public boolean isUnsupported() { return unsupportedReason != null; }
}
