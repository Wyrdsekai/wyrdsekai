package org.wyrdsekai.core.net;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * the Java service behind the {@code world.net.*}
 * item namespace. GraalJS item scripts (courier satchel / far-hand / postrider)
 * reach the network ONLY through here; they never see a socket, a key, or a raw
 * process. Every call is {@link NetworkGate}-checked BEFORE any I/O, and
 * credentials are resolved from an injected {@link CredentialResolver} at call
 * time — the {@code keyRef} in an allowlist entry is only a handle.
 *
 * <p>Transport is injected too ({@link NetworkExec}) so the service is unit
 * testable without spawning ssh/scp: a test asserts "denied host ⇒ no exec",
 * "allowed host ⇒ exec with the resolved keyfile", "command-prefix enforced".</p>
 */
public final class NetworkCapability {

    private static final Logger log = LoggerFactory.getLogger(NetworkCapability.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Resolves a {@code keyRef} handle to on-disk key material (0600 keyfile). */
    @FunctionalInterface
    public interface CredentialResolver {
        /** @return the path to a private-key file for the handle, or empty if unresolvable. */
        Optional<String> resolveKeyfile(String keyRef);
    }

    /** Executes an argv with a timeout. Default impl spawns a real process. */
    @FunctionalInterface
    public interface NetworkExec {
        ExecResult run(List<String> argv, Duration timeout);
    }

    public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {}

    /** Transfers a local file to a household peer over the authenticated bus. */
    @FunctionalInterface
    public interface HouseholdTransport {
        /**
         * Copy {@code localPath} to {@code remotePath} on the enrolled node.
         * The result carries where the file ACTUALLY landed — the receiving
         * node may redirect into its courier inbox (path policy is the
         * receiver's) — so the sender can narrate honestly.
         */
        Result copyTo(String nodeId, String localPath, String remotePath);

        record Result(boolean ok, String landedPath, String error) {
            public static Result success(String landedPath) {
                return new Result(true, landedPath, null);
            }
            public static Result fail(String error) {
                return new Result(false, null, error);
            }
        }
    }

    private final NetworkGate gate;
    private final CredentialResolver credentials;
    private final NetworkExec exec;
    private final HouseholdTransport household;   // nullable — household copy unavailable if null

    public NetworkCapability(NetworkGate gate, CredentialResolver credentials,
                             NetworkExec exec, HouseholdTransport household) {
        this.gate = gate != null ? gate : NetworkGate.empty();
        this.credentials = credentials;
        this.exec = exec != null ? exec : defaultExec();
        this.household = household;
    }

    // ─── far-hand: run one command on an allowlisted host ──────────────

    public Map<String, Object> sshRun(String host, String command, Map<String, Object> opts) {
        var v = gate.checkSshCommand(host, command);
        if (!v.allowed()) return denied("ssh", host, v.reason());
        var keyfile = resolveKey(v);
        if (keyfile.isEmpty()) return denied("ssh", host, "deny:no-credential");

        var argv = sshBaseArgv(keyfile.get(), userAtHost(host, opts));
        argv.add(command == null ? "" : command);
        return runArgv("ssh", host, argv, timeoutOf(opts));
    }

    // ─── postrider: scp to / from an allowlisted host ──────────────────

    public Map<String, Object> scpTo(String host, String localPath, String remotePath,
                                     Map<String, Object> opts) {
        return scp(host, localPath, targetSpec(host, remotePath, opts), opts, true);
    }

    public Map<String, Object> scpFrom(String host, String remotePath, String localPath,
                                       Map<String, Object> opts) {
        return scp(host, targetSpec(host, remotePath, opts), localPath, opts, false);
    }

    private Map<String, Object> scp(String host, String src, String dst,
                                    Map<String, Object> opts, boolean sending) {
        var v = gate.check("scp", host, null);
        if (!v.allowed()) return denied("scp", host, v.reason());
        var keyfile = resolveKey(v);
        if (keyfile.isEmpty()) return denied("scp", host, "deny:no-credential");
        if (src == null || src.isBlank() || dst == null || dst.isBlank()) {
            return denied("scp", host, "deny:bad-path");
        }
        var argv = new ArrayList<>(List.of(
            "scp", "-B", "-q",
            "-i", keyfile.get(),
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=accept-new"));
        argv.add(src);
        argv.add(dst);
        var res = runArgv("scp", host, argv, timeoutOf(opts));
        res.put("direction", sending ? "to" : "from");
        return res;
    }

    // ─── courier satchel: household-bus transfer (no ssh, no keys) ─────

    public Map<String, Object> householdCopy(String nodeId, String localPath, String remotePath) {
        if (household == null) {
            return Map.of("ok", false, "error", "household transport not wired",
                "reason", "deny:no-transport");
        }
        if (nodeId == null || nodeId.isBlank() || localPath == null || localPath.isBlank()) {
            return Map.of("ok", false, "error", "missing nodeId or path", "reason", "deny:bad-args");
        }
        HouseholdTransport.Result r;
        try {
            r = household.copyTo(nodeId, localPath, remotePath);
        } catch (Exception e) {
            log.warn("[NetworkCapability] household copy to {} failed: {}", nodeId, e.getMessage());
            return Map.of("ok", false, "error", e.getMessage(), "reason", "error:transport");
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", r != null && r.ok());
        out.put("node", nodeId);
        out.put("kind", "household");
        if (r != null && r.landedPath() != null) out.put("landed_path", r.landedPath());
        if (r == null || !r.ok()) {
            out.put("reason", "error:transport");
            if (r != null && r.error() != null) out.put("error", r.error());
        }
        return out;
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private Optional<String> resolveKey(NetworkVerdict v) {
        if (credentials == null || v.entry() == null || v.entry().keyRef() == null) {
            return Optional.empty();
        }
        try {
            return credentials.resolveKeyfile(v.entry().keyRef());
        } catch (Exception e) {
            log.warn("[NetworkCapability] keyRef '{}' failed to resolve: {}",
                v.entry().keyRef(), e.getMessage());
            return Optional.empty();
        }
    }

    private List<String> sshBaseArgv(String keyfile, String userHost) {
        var argv = new ArrayList<String>(List.of(
            "ssh",
            "-i", keyfile,
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=10"));
        argv.add(userHost);
        return argv;
    }

    private Map<String, Object> runArgv(String kind, String host, List<String> argv, Duration timeout) {
        try {
            var r = exec.run(argv, timeout);
            var out = new LinkedHashMap<String, Object>();
            out.put("ok", !r.timedOut() && r.exitCode() == 0);
            out.put("kind", kind);
            out.put("host", host);
            out.put("exit", r.exitCode());
            out.put("stdout", r.stdout() == null ? "" : r.stdout());
            out.put("stderr", r.stderr() == null ? "" : r.stderr());
            if (r.timedOut()) out.put("reason", "timeout");
            return out;
        } catch (Exception e) {
            log.warn("[NetworkCapability] {} to {} errored: {}", kind, host, e.getMessage());
            return Map.of("ok", false, "kind", kind, "host", host,
                "error", e.getMessage() == null ? "exec_error" : e.getMessage(),
                "reason", "error:exec");
        }
    }

    private static Map<String, Object> denied(String kind, String host, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", false);
        out.put("kind", kind);
        out.put("host", host);
        out.put("denied", true);
        out.put("reason", reason);
        out.put("message", "network gate denied " + kind + " to " + host + " (" + reason + ")");
        return out;
    }

    private static String userAtHost(String host, Map<String, Object> opts) {
        if (opts != null && opts.get("user") instanceof String u && !u.isBlank()) {
            return u + "@" + host;
        }
        return host;
    }

    private static String targetSpec(String host, String remotePath, Map<String, Object> opts) {
        if (remotePath == null) return null;
        return userAtHost(host, opts) + ":" + remotePath;
    }

    private static Duration timeoutOf(Map<String, Object> opts) {
        if (opts != null && opts.get("timeoutSeconds") instanceof Number n) {
            long s = n.longValue();
            if (s > 0 && s <= 3600) return Duration.ofSeconds(s);
        }
        return DEFAULT_TIMEOUT;
    }

    /** Real subprocess exec — argv, no shell. Used in production. */
    private static NetworkExec defaultExec() {
        return (argv, timeout) -> {
            try {
                var pb = new ProcessBuilder(argv);
                pb.redirectErrorStream(false);
                // ssh/scp inherit only a minimal env — no shell, argv-only.
                var process = pb.start();
                var out = new StringBuilder();
                var err = new StringBuilder();
                var to = Thread.ofVirtual().start(() -> readInto(process.getInputStream(), out));
                var te = Thread.ofVirtual().start(() -> readInto(process.getErrorStream(), err));
                boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!done) {
                    process.destroyForcibly();
                    to.join(500);
                    te.join(500);
                    return new ExecResult(-1, out.toString(), err.toString(), true);
                }
                to.join();
                te.join();
                return new ExecResult(process.exitValue(), out.toString(), err.toString(), false);
            } catch (Exception e) {
                return new ExecResult(-1, "", String.valueOf(e.getMessage()), false);
            }
        };
    }

    private static void readInto(InputStream in, StringBuilder sb) {
        try (in) {
            sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception ignored) { /* swallow */ }
    }
}
