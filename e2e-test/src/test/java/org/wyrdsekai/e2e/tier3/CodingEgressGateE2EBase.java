package org.wyrdsekai.e2e.tier3;

import com.typesafe.config.ConfigFactory;
import org.wyrdsekai.core.coding.EgressGate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Plane-B — shared harness for the coding-backend egress
 * gate E2E matrix. Proves the gate on a REAL spawned OS process, which is the
 * gap the unit test ({@code core:EgressGateTest}) can't cover: that test
 * inspects the {@link ProcessBuilder} env <em>map</em> and never
 * {@code start()}s, so it never proves the <em>live</em> subprocess actually
 * sees the scrub.
 *
 * <p>Every subprocess coding backend funnels its {@link ProcessBuilder} through
 * the ONE shared seam {@link EgressGate#gatedProcessBuilder} (and
 * {@code EgressGate.applyEnv} underneath it) — PiCodingBackend, OpenCodeBackend,
 * CodexCliBackend, ClineBackend, ContinueBackend, GeminiCliBackend,
 * ClaudeSdkBackend, plus CliSkillExecutor. Driving that seam with a real probe
 * process and reading its real stdout is therefore an end-to-end proof of what
 * every companion coding dispatch inherits.
 */
abstract class CodingEgressGateE2EBase {

    /** An ambient credential the daemon JVM holds; a scrubbed subprocess must NOT see it. */
    static final String SSH_SOCK_VALUE = "/tmp/egress-e2e-ssh/agent.4242";
    /** A second ambient secret (matches the *_TOKEN scrub-by-omission rule). */
    static final String CLOUD_TOKEN_VALUE = "egress-e2e-cloud-token-abc123";

    /** Result of running a probe subprocess. */
    record ProbeOutput(int exitCode, String stdout) {
        boolean sawSshSock() { return stdout.contains("SSH_AUTH_SOCK=" + SSH_SOCK_VALUE); }
        boolean sshSockEmpty() { return stdout.contains("SSH_AUTH_SOCK=<empty>"); }
        boolean sawCloudToken() { return stdout.contains("MYCLOUD_TOKEN=" + CLOUD_TOKEN_VALUE); }
        boolean cloudTokenEmpty() { return stdout.contains("MYCLOUD_TOKEN=<empty>"); }
        String value(String marker) {
            for (var line : stdout.split("\n")) {
                if (line.startsWith(marker + "=")) return line.substring(marker.length() + 1);
            }
            return null;
        }
    }

    /**
     * The ambient env the daemon JVM would hold when it spawns a backend:
     * two credentials plus the minimal runnables. The ON/OFF cases feed the
     * SAME map into the gate, so the only variable is the knob.
     */
    static Map<String, String> ambientParentEnv() {
        var env = new HashMap<String, String>();
        env.put("SSH_AUTH_SOCK", SSH_SOCK_VALUE);
        env.put("MYCLOUD_TOKEN", CLOUD_TOKEN_VALUE);
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", System.getenv().getOrDefault("HOME", "/tmp"));
        return env;
    }

    /**
     * Build a probe through an EXPLICIT gate (ON or OFF), feeding a known
     * ambient env so the ON→OFF delta is exactly the knob. This is precisely
     * what {@code gatedProcessBuilder} does internally
     * ({@code defaultInstance().applyEnv(pb.environment(), backendEnv)}), with
     * the gate's enabled flag pinned so the test is deterministic without
     * global {@code ConfigFactory} surgery.
     */
    ProbeOutput runProbeThroughGate(EgressGate gate, List<String> command) throws Exception {
        var pb = new ProcessBuilder(command);
        pb.environment().clear();
        pb.environment().putAll(ambientParentEnv());
        // The backend's own resolved env (e.g. OPENAI_HOST for the local llama).
        var backendEnv = Map.of("OPENAI_HOST", "http://127.0.0.1:8200");
        gate.applyEnv(pb.environment(), backendEnv);
        return exec(pb);
    }

    /**
     * Spawn a probe through the PRODUCTION shared seam
     * ({@link EgressGate#gatedProcessBuilder}) at its config-resolved default.
     * ProcessBuilder pre-seeds its env from the REAL parent process, so this
     * proves the production default scrubs a genuinely-inherited var — not just
     * our synthetic map.
     */
    ProbeOutput runProbeThroughDefaultSeam(List<String> command) throws Exception {
        return exec(EgressGate.gatedProcessBuilder(command, null));
    }

    /**
     * Pick a var the REAL parent JVM env holds that is NOT on the gate's
     * allowlist, so we can prove the production seam drops a real inherited var.
     * Returns null only in the pathological case of an all-allowlisted env.
     */
    static String someNonAllowlistedParentVar() {
        for (var key : System.getenv().keySet()) {
            if (!EgressGate.DEFAULT_ENV_ALLOWLIST.contains(key)
                    && !key.isBlank()
                    && key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return key;
            }
        }
        return null;
    }

    /** A probe reporting a single named env var as the subprocess sees it. */
    static List<String> singleVarProbe(String varName) throws IOException {
        var script = Files.createTempFile("egress-var-probe-", ".sh");
        script.toFile().deleteOnExit();
        Files.writeString(script,
            "#!/bin/sh\necho \"" + varName + "=${" + varName + ":-<empty>}\"\n",
            StandardCharsets.UTF_8);
        return List.of("/bin/sh", script.toString());
    }

    /** An enforcing gate resolved from HOCON exactly like production default (ON). */
    static EgressGate enforcingFromConfig() {
        return EgressGate.fromConfig(ConfigFactory.parseString(
            "wyrdsekai.coding.egress-gate { enabled = true }"));
    }

    /** A disabled gate resolved from HOCON — the steward opt-out knob (OFF). */
    static EgressGate disabledFromConfig() {
        return EgressGate.fromConfig(ConfigFactory.parseString(
            "wyrdsekai.coding.egress-gate { enabled = false }"));
    }

    private ProbeOutput exec(ProcessBuilder pb) throws Exception {
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean done = proc.waitFor(30, TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
            throw new AssertionError("probe subprocess did not exit within 30s; output:\n" + out);
        }
        return new ProbeOutput(proc.exitValue(), out);
    }

    /**
     * A probe shell script that reports the two ambient creds as the subprocess
     * actually sees them, plus optional {@code extraShell}. {@code <empty>}
     * sentinels make absence unambiguous in stdout.
     */
    static List<String> envReportProbe(String extraShell) throws IOException {
        var script = Files.createTempFile("egress-probe-", ".sh");
        script.toFile().deleteOnExit();
        Files.writeString(script, """
            #!/bin/sh
            echo "SSH_AUTH_SOCK=${SSH_AUTH_SOCK:-<empty>}"
            echo "MYCLOUD_TOKEN=${MYCLOUD_TOKEN:-<empty>}"
            echo "OPENAI_HOST=${OPENAI_HOST:-<empty>}"
            echo "PATH_PRESENT=${PATH:+yes}"
            %s
            """.formatted(extraShell == null ? "" : extraShell), StandardCharsets.UTF_8);
        return List.of("/bin/sh", script.toString());
    }

    static Path tempDir() throws IOException {
        var d = Files.createTempDirectory("egress-e2e-");
        d.toFile().deleteOnExit();
        return d;
    }
}
