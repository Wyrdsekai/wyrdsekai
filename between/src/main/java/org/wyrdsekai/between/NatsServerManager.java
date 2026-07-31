package org.wyrdsekai.between;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Manages nats-server as a child process.
 * Same pattern as LlamaServerManager — discover or start external binary,
 * health check via HTTP monitoring endpoint.
 */
public final class NatsServerManager {

    private static final Logger log = LoggerFactory.getLogger(NatsServerManager.class);
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final int HEALTH_CHECK_INTERVAL_MS = 200;

    private final String executable;
    private final int clientPort;
    private final int monitorPort;
    private final Path configDir;
    private final boolean bindAllInterfaces;

    private Process process;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    /**
     * @param executable       nats-server binary (on PATH or absolute path)
     * @param clientPort       NATS client port (default 4222)
     * @param monitorPort      HTTP monitoring port (default 8222)
     * @param configDir        directory for nats.conf (e.g., ~/.wyrdsekai/)
     * @param bindAllInterfaces true for cluster mode (0.0.0.0), false for local (127.0.0.1)
     */
    public NatsServerManager(String executable, int clientPort, int monitorPort,
                              Path configDir, boolean bindAllInterfaces) {
        this.executable = executable;
        this.clientPort = clientPort;
        this.monitorPort = monitorPort;
        this.configDir = configDir;
        this.bindAllInterfaces = bindAllInterfaces;
    }

    /**
     * Start nats-server and wait for it to become healthy.
     *
     * @return NATS URL for clients to connect to
     * @throws IOException if the server fails to start
     */
    public String start() throws IOException {
        if (process != null && process.isAlive()) {
            log.info("nats-server already running (pid={})", process.pid());
            return natsUrl();
        }

        // Check if nats-server is already running on this port
        if (isHealthy()) {
            log.info("nats-server already running on port {} (external)", clientPort);
            return natsUrl();
        }

        // Write config
        writeConfig();

        // Start nats-server
        var configFile = configDir.resolve("nats.conf");
        var cmd = new ArrayList<String>();
        cmd.add(executable);
        cmd.add("-c");
        cmd.add(configFile.toString());

        log.info("Starting nats-server: {}", String.join(" ", cmd));

        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .inheritIO();

        process = pb.start();

        // Wait for health
        var deadline = System.currentTimeMillis() + HEALTH_CHECK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("nats-server exited with code " + process.exitValue());
            }
            if (isHealthy()) {
                log.info("nats-server healthy on port {} (pid={})", clientPort, process.pid());
                return natsUrl();
            }
            try {
                Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for nats-server", e);
            }
        }

        process.destroyForcibly();
        throw new IOException("nats-server failed to become healthy within "
            + HEALTH_CHECK_TIMEOUT.toSeconds() + "s");
    }

    /**
     * Stop the nats-server process.
     */
    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("Stopping nats-server (pid={})", process.pid());
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    public boolean isRunning() {
        return (process != null && process.isAlive()) || isHealthy();
    }

    public String natsUrl() {
        if (bindAllInterfaces) {
            var lanIp = detectLanIp();
            if (lanIp != null) return "nats://" + lanIp + ":" + clientPort;
        }
        return "nats://127.0.0.1:" + clientPort;
    }

    public int clientPort() {
        return clientPort;
    }

    // --- Internal ---

    private boolean isHealthy() {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + monitorPort + "/varz"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeConfig() throws IOException {
        Files.createDirectories(configDir);

        var listenAddr = bindAllInterfaces ? "0.0.0.0" : "127.0.0.1";
        var sb = new StringBuilder();
        sb.append("# Wyrdsekai NATS server configuration (auto-generated)\n");
        sb.append("listen: ").append(listenAddr).append(":").append(clientPort).append("\n");
        sb.append("http_port: ").append(monitorPort).append("\n");
        sb.append("max_payload: 1048576\n");
        sb.append("max_connections: 64\n");
        sb.append("write_deadline: \"60s\"\n"); // default 10s — too aggressive for WiFi/slow clients
        sb.append("\n");
        sb.append("# JetStream for persistent streams (account replication, checkpoints)\n");
        sb.append("jetstream {\n");
        // Forward slashes: NATS treats '\' as an escape char inside a quoted
        // string, so a raw Windows path (C:\Users\...) fails to parse. Forward
        // slashes are accepted on every platform and need no escaping.
        var storeDir = configDir.toAbsolutePath().toString().replace('\\', '/');
        sb.append("  store_dir: \"").append(storeDir).append("\"\n");
        sb.append("}\n");

        // Phones (RN/KMP) speak NATS-over-WEBSOCKET only — until 2026-07-11 no
        // shipped config opened a ws listener, so LAN phones could never reach
        // Between (relay wss was the only path). Port = clientPort+1 (4223).
        sb.append("\n");
        sb.append("# WebSocket listener for mobile clients (NATS-over-WS)\n");
        sb.append("websocket {\n");
        sb.append("  listen: \"").append(listenAddr).append(":").append(clientPort + 1).append("\"\n");
        sb.append("  no_tls: true\n");
        sb.append("}\n");

        // When binding all interfaces (cluster mode), advertise the LAN IP
        // so remote clients get the correct reconnect URL instead of 127.0.0.1
        if (bindAllInterfaces) {
            var lanIp = detectLanIp();
            if (lanIp != null) {
                sb.append("\n");
                sb.append("# Auto-detected LAN IP for dual-homed/multi-interface networks\n");
                sb.append("client_advertise: \"").append(lanIp).append(":").append(clientPort).append("\"\n");
                log.info("NATS client_advertise set to {}:{}", lanIp, clientPort);
            }
        }

        Files.writeString(configDir.resolve("nats.conf"), sb.toString());
    }

    /**
     * Detect the first non-loopback IPv4 address on this machine.
     * Prefers 192.168.x.x or 10.x.x.x (private LAN ranges).
     * Returns null if no suitable address found.
     */
    static String detectLanIp() {
        try {
            String fallback = null;
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        var ip = addr.getHostAddress();
                        // Prefer private LAN ranges
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            return ip;
                        }
                        if (fallback == null && !ip.startsWith("127.")) {
                            fallback = ip;
                        }
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            log.debug("Failed to detect LAN IP: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if nats-server binary is available on PATH or in a known
     * install-relative location.
     *
     * <p>Discovery order:
     * <ol>
     *   <li>The literal {@code executable} arg via PATH (system default)</li>
     *   <li>Sibling of the running JAR ({@code <app-dir>/nats-server[.exe]})
     *       — Windows .msi bundle case, jpackage drops the binary in the
     *       app dir which isn't on system PATH</li>
     *   <li>Standard install dirs: {@code /opt/wyrdsekai/bin} (.deb),
     *       {@code /usr/local/wyrdsekai/bin} (.pkg before symlink), and
     *       {@code %ProgramFiles%\\Wyrdsekai\\app}</li>
     * </ol>
     * Returns the FIRST candidate that works, and stashes the resolved
     * absolute path so {@link #resolved()} can return it. The literal
     * {@code executable} string still wins — caller can override via env.
     */
    private static volatile String resolvedExecutable;

    public static boolean isAvailable(String executable) {
        // 1) The literal name via PATH (or absolute, if given).
        if (probe(executable)) {
            resolvedExecutable = executable;
            return true;
        }

        // An EXPLICIT path (absolute, or containing a separator) is a literal
        // request for THAT binary — honour it as-is. The PATH/app-dir/install-dir
        // fallbacks below exist to resolve a BARE command name ("nats-server"),
        // not to silently substitute a different binary when the caller named a
        // specific file that doesn't work. Without this, isAvailable("/no/such/
        // nats-server") returns true on any host that merely has nats-server
        // installed under /opt/wyrdsekai/bin — wrong, and it breaks on install boxes.
        if (looksLikePath(executable)) {
            return false;
        }

        // 2) Sibling of the running JAR / jpackage app dir.
        var binary = isWindows() ? "nats-server.exe" : "nats-server";
        var appDir = jvmAppDir();
        if (appDir != null) {
            var candidate = appDir.resolve(binary).toString();
            if (probe(candidate)) {
                log.info("Found nats-server next to JVM app: {}", candidate);
                resolvedExecutable = candidate;
                return true;
            }
        }

        // 3) Standard install locations (only meaningful on the matching OS).
        String[] roots;
        if (isWindows()) {
            var pf = System.getenv("ProgramFiles");
            roots = pf != null ? new String[] { pf + "\\Wyrdsekai\\app" }
                              : new String[] {};
        } else {
            roots = new String[] {
                "/opt/wyrdsekai/bin",
                "/usr/local/wyrdsekai/bin",
                "/opt/homebrew/bin",
            };
        }
        for (var root : roots) {
            var candidate = root + (isWindows() ? "\\" : "/") + binary;
            if (probe(candidate)) {
                log.info("Found nats-server in install dir: {}", candidate);
                resolvedExecutable = candidate;
                return true;
            }
        }
        return false;
    }

    /**
     * The path that {@link #isAvailable} successfully resolved on the most
     * recent call, or {@code null} if no successful resolution happened.
     * Callers should prefer this over the literal arg they passed in so the
     * actual binary used matches the one validated.
     */
    public static String resolved() { return resolvedExecutable; }

    /** True if {@code s} names a filesystem path (absolute or with a separator)
     *  rather than a bare command to be looked up on PATH / install dirs. */
    private static boolean looksLikePath(String s) {
        if (s == null || s.isBlank()) return false;
        return new File(s).isAbsolute()
            || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0;
    }

    private static boolean probe(String executable) {
        try {
            var proc = new ProcessBuilder(executable, "--version")
                .redirectErrorStream(true)
                .start();
            var exit = proc.waitFor();
            if (exit == 0) {
                var output = new String(proc.getInputStream().readAllBytes()).trim();
                log.info("Found nats-server: {}", output);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    /**
     * Best-effort: locate the directory that contains the running JAR. On
     * jpackage builds this is the app dir where bundled resources live.
     * Returns null if the cwd / classpath don't yield a usable directory
     * (e.g. running from gradle via classpath dirs, not from a built dist).
     */
    private static Path jvmAppDir() {
        try {
            var src = NatsServerManager.class.getProtectionDomain()
                .getCodeSource();
            if (src == null || src.getLocation() == null) return null;
            var loc = Path.of(src.getLocation().toURI());
            // If loc is a JAR file, parent is the app dir; if a dir (gradle
            // run), use its parent so we land on something resembling install.
            return Files.isDirectory(loc) ? loc.getParent() : loc.getParent();
        } catch (Exception e) {
            return null;
        }
    }
}
