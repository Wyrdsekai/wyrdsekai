package org.wyrdsekai.core.coding;

import java.io.IOException;
import java.util.List;

/**
 * Test-injectable seam over {@link ProcessBuilder}. Exists so {@code
 * wyrd coding login} can be unit-tested without actually spawning a
 * paid-backend's OAuth flow.
 *
 * <p>Production wiring uses {@link #realCommandRunner()}; tests pass a
 * recording fake. The interface is deliberately tiny — exit-code +
 * argv is all the login flow needs (stdout/stderr/stdin are inherited
 * from the parent so the user sees the real CLI's output).</p>
 */
public interface CommandRunner {

    /**
     * Run {@code argv} with stdio inherited from the parent (so the
     * user can interact with the OAuth flow), block until exit, and
     * return the exit code.
     *
     * @throws IOException        if the binary can't be spawned
     * @throws InterruptedException if the parent is interrupted while
     *                              waiting on the subprocess
     */
    int run(List<String> argv) throws IOException, InterruptedException;

    /**
     * Production runner: shells out via {@link ProcessBuilder} with
     * {@code inheritIO()}. Mirrors the SPEC §9.2.1 step "exec-replaces
     * into the backend's native login flow".
     */
    static CommandRunner realCommandRunner() {
        return argv -> {
            var pb = new ProcessBuilder(argv);
            pb.inheritIO();
            Process p = pb.start();
            return p.waitFor();
        };
    }
}
