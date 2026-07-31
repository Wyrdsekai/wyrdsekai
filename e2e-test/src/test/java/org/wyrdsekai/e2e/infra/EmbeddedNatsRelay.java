package org.wyrdsekai.e2e.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Starts a real {@code nats-server} process as a test fixture. Picks a random
 * free port on loopback and waits for the server to accept TCP connections
 * before returning from {@link #start()}.
 *
 * <p>This is the relay NATS that cross-zone components publish to / subscribe
 * from. Using the real binary (from {@code packaging/nats-server}) means we
 * exercise actual wire auth, subject routing, and reconnect behavior — not a
 * mocked substitute.</p>
 *
 * <p>Caller is responsible for {@link #stop()} in {@code @AfterAll}.</p>
 */
public final class EmbeddedNatsRelay {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedNatsRelay.class);

    private Process process;
    private int port;
    private final Path binaryPath;

    public EmbeddedNatsRelay() {
        this.binaryPath = locateBinary();
    }

    public int port() { return port; }
    public String url() { return "nats://127.0.0.1:" + port; }

    public void start() throws IOException, InterruptedException {
        if (process != null && process.isAlive()) return;

        port = pickFreePort();
        var cmd = new ProcessBuilder(
            binaryPath.toString(),
            "--addr", "127.0.0.1",
            "--port", Integer.toString(port),
            "--http_port", "-1",             // disable monitoring endpoint
            "--log_size_limit", "0",
            "--name", "embedded-relay-" + port
        ).redirectErrorStream(true);

        process = cmd.start();

        // Drain stdout on a daemon thread so the process doesn't block on a full pipe.
        var drainer = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[embedded-nats:{}] {}", port, line);
                }
            } catch (IOException ignored) {}
        }, "embedded-nats-drainer-" + port);
        drainer.setDaemon(true);
        drainer.start();

        waitForPort(port, Duration.ofSeconds(10));
        log.info("Embedded NATS relay ready on {}", url());
    }

    public void stop() {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    private static int pickFreePort() {
        try (var sock = new ServerSocket(0)) {
            return sock.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to pick free port", e);
        }
    }

    private static void waitForPort(int port, Duration timeout) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (var sock = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException ignored) {
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new RuntimeException("nats-server did not open port " + port + " within " + timeout);
    }

    /**
     * Resolves the {@code nats-server} binary path. Looks at {@code packaging/}
     * relative to the repo root, which is either the gradle module dir or its
     * parent depending on how tests are invoked.
     */
    private static Path locateBinary() {
        // Allow override for environments where the binary lives elsewhere.
        var override = System.getenv("WYRDSEKAI_NATS_SERVER_BINARY");
        if (override != null && !override.isBlank()) {
            var path = Paths.get(override);
            if (Files.isExecutable(path)) return path;
        }

        var candidates = new Path[] {
            Paths.get("packaging/nats-server"),
            Paths.get("../packaging/nats-server"),
            Paths.get(System.getProperty("user.dir"), "packaging", "nats-server"),
            Paths.get(System.getProperty("user.dir"), "..", "packaging", "nats-server"),
        };
        for (var c : candidates) {
            if (Files.isExecutable(c)) return c.toAbsolutePath().normalize();
        }
        throw new IllegalStateException(
            "nats-server binary not found. Build with `packaging/build-all.sh --deb` "
                + "first, or set WYRDSEKAI_NATS_SERVER_BINARY to an absolute path.");
    }
}
