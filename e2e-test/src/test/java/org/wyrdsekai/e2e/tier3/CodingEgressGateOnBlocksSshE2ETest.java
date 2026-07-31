package org.wyrdsekai.e2e.tier3;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plane-B — gate ON (production default): a dispatched
 * coding task's shell tries to use the daemon's ambient SSH credential. The
 * enforcing gate scrubs the subprocess env, so the live process has NO
 * {@code SSH_AUTH_SOCK} and no cloud token — a prompt-injected {@code ssh}
 * cannot authenticate, and no key-backed lateral movement is possible.
 *
 * <p>Unlike {@code core:EgressGateTest} (which checks the {@link ProcessBuilder}
 * env map), this spawns REAL processes and asserts on their real stdout — the
 * live-process proof the security must-fix (#1) called for.
 */
@Tag("e2e")
final class CodingEgressGateOnBlocksSshE2ETest extends CodingEgressGateE2EBase {

    @Test
    void enforcing_gate_scrubs_ssh_and_cloud_creds_from_the_live_subprocess() throws Exception {
        var out = runProbeThroughGate(enforcingFromConfig(), envReportProbe(null));

        assertEquals(0, out.exitCode(), () -> "probe failed:\n" + out.stdout());
        assertTrue(out.sshSockEmpty(),
            () -> "SSH_AUTH_SOCK must be scrubbed from the live subprocess; got:\n" + out.stdout());
        assertTrue(out.cloudTokenEmpty(),
            () -> "MYCLOUD_TOKEN must be scrubbed; got:\n" + out.stdout());
        // The backend still reaches the LOCAL llama-server (allowlisted overlay).
        assertEquals("http://127.0.0.1:8200", out.value("OPENAI_HOST"),
            "the local-llama OPENAI_HOST must survive the scrub");
        assertEquals("yes", out.value("PATH_PRESENT"), "PATH must survive so the backend is runnable");
    }

    @Test
    void real_ssh_invocation_cannot_use_the_scrubbed_agent() throws Exception {
        // The concrete SPEC scenario: the dispatched shell literally runs ssh.
        // With the agent socket scrubbed and no key files reachable, ssh has no
        // publickey material — BatchMode makes it fail immediately rather than
        // prompt, and no session is ever authenticated. We assert the agent was
        // unreachable (empty SSH_AUTH_SOCK) AND ssh exited non-zero.
        if (!sshOnPath()) return; // hermetic fallback: env proof above still holds

        var probe = new ArrayList<String>(envReportProbe("""
            # BatchMode: never prompt; publickey only; unresolved host → fast fail.
            ssh -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=3 \\
                -o PreferredAuthentications=publickey \\
                egress-e2e-nonexistent.invalid true
            echo "SSH_EXIT=$?"
            """));

        var out = runProbeThroughGate(enforcingFromConfig(), probe);
        assertTrue(out.sshSockEmpty(),
            () -> "ssh ran with no agent socket; got:\n" + out.stdout());
        var sshExit = out.value("SSH_EXIT");
        assertNotEquals("0", sshExit,
            () -> "ssh must NOT succeed with a scrubbed agent + no keys; got SSH_EXIT="
                + sshExit + "\n" + out.stdout());
    }

    @Test
    void production_gatedProcessBuilder_default_scrubs_a_real_inherited_var() throws Exception {
        // Drive the ACTUAL production seam (config-resolved default = enforcing)
        // and prove it drops a var the real parent JVM env genuinely holds.
        var victim = someNonAllowlistedParentVar();
        if (victim == null) return; // pathological all-allowlisted env; skip
        var real = System.getenv(victim);

        var out = runProbeThroughDefaultSeam(singleVarProbe(victim));
        assertEquals(0, out.exitCode(), () -> "probe failed:\n" + out.stdout());
        assertNotEquals(real, out.value(victim),
            () -> "production gatedProcessBuilder default must scrub the inherited '"
                + victim + "'; live subprocess still saw it:\n" + out.stdout());
        assertEquals("<empty>", out.value(victim),
            () -> "'" + victim + "' should be absent from the scrubbed subprocess:\n" + out.stdout());
    }

    private static boolean sshOnPath() {
        for (var dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!dir.isBlank() && new File(dir, "ssh").canExecute()) return true;
        }
        return false;
    }
}
