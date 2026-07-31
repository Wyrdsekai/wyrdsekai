package org.wyrdsekai.between.layer;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.between.NatsServerManager;
import org.wyrdsekai.between.NodeIdentity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code CourierSatchelHouseholdE2ETest} (Plane A):
 * a REAL nats-server, two node identities on the household bus, a file
 * couriered from node A to node B. Proves the full chunked protocol
 * (begin/chunk/commit, sha256-verified), the roster-signature gate (an
 * unenrolled sender is refused before any disk effect), and the receiver's
 * landing-path policy (absolute paths closed by default).
 *
 * <p>Tagged like the other NATS ITs; skipped when {@code nats-server} isn't
 * on PATH.</p>
 */
@Tag("integration")
@Tag("needs-nats")
@EnabledIf("isNatsServerAvailable")
final class CourierFileLayerIT {

    static boolean isNatsServerAvailable() {
        return NatsServerManager.isAvailable("nats-server");
    }

    private Process natsProcess;
    private NatsBridge bridgeA;
    private NatsBridge bridgeB;
    private NodeIdentity identityA;
    private NodeIdentity identityB;

    @BeforeEach
    void startNats(@TempDir Path tmp) throws Exception {
        int port;
        try (var s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        var confFile = tmp.resolve("nats.conf");
        Files.writeString(confFile, "port: " + port + "\nhttp_port: 0\n", StandardCharsets.UTF_8);
        // resolved() carries the actual binary isAvailable() found — on install
        // boxes nats-server lives at /opt/wyrdsekai/bin, not on PATH.
        var exe = NatsServerManager.resolved() != null
            ? NatsServerManager.resolved() : "nats-server";
        natsProcess = new ProcessBuilder(exe, "-c", confFile.toString())
            .redirectErrorStream(true).start();

        identityA = NodeIdentity.loadOrGenerate(tmp.resolve("id-a.json"));
        identityB = NodeIdentity.loadOrGenerate(tmp.resolve("id-b.json"));

        var url = "nats://127.0.0.1:" + port;
        bridgeA = new NatsBridge(url, "courier-node-a", "testzone", identityA);
        bridgeB = new NatsBridge(url, "courier-node-b", "testzone", identityB);
        connectWithRetry(bridgeA);
        connectWithRetry(bridgeB);
    }

    private static void connectWithRetry(NatsBridge bridge) throws Exception {
        Exception last = null;
        for (int i = 0; i < 20; i++) {
            try {
                bridge.connect();
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(250);
            }
        }
        throw last;
    }

    @AfterEach
    void stopNats() {
        if (bridgeA != null) bridgeA.close();
        if (bridgeB != null) bridgeB.close();
        if (natsProcess != null) natsProcess.destroyForcibly();
    }

    private Function<String, Optional<byte[]>> rosterOf(NodeIdentity... enrolled) {
        return nodeId -> {
            for (var id : enrolled) {
                if (id.nodeId().equals(nodeId)) return Optional.of(id.publicKeyBytes());
            }
            return Optional.empty();
        };
    }

    @Test
    void file_couriers_a_to_b_and_lands_in_the_inbox(@TempDir Path dirA, @TempDir Path dirB)
            throws Exception {
        // The envelope src carries the BRIDGE node id (not the identity file's
        // hostname-derived one), so the roster keys on the bridge node ids.
        Function<String, Optional<byte[]>> roster = nodeId -> switch (nodeId) {
            case "courier-node-a" -> Optional.of(identityA.publicKeyBytes());
            case "courier-node-b" -> Optional.of(identityB.publicKeyBytes());
            default -> Optional.empty();
        };

        var receiver = new CourierFileLayer(bridgeB, "courier-node-b", roster, dirB, false,
            32L * 1024 * 1024);
        receiver.subscribe();
        var sender = new CourierFileLayer(bridgeA, "courier-node-a", roster, dirA, false,
            32L * 1024 * 1024);

        // Multi-chunk on purpose: 700KB > one 256KB chunk.
        var payload = new byte[700 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        var src = dirA.resolve("outbound.bin");
        Files.write(src, payload);

        var result = sender.copyTo("courier-node-b", src.toString(), "drops/outbound.bin");
        assertTrue(result.ok(), result.error());
        assertNotNull(result.landedPath());

        var landed = Path.of(result.landedPath());
        assertTrue(landed.startsWith(dirB.resolve("courier")),
            "must land in the receiver's courier inbox, landed at " + landed);
        assertArrayEquals(payload, Files.readAllBytes(landed),
            "content must survive the chunked transfer byte-identical");
    }

    @Test
    void unenrolled_sender_is_refused_before_any_disk_effect(@TempDir Path dirA, @TempDir Path dirB)
            throws Exception {
        // Receiver's roster knows NOBODY — node-a's signed envelopes verify
        // against nothing, so the transfer must be refused at begin.
        var receiver = new CourierFileLayer(bridgeB, "courier-node-b", rosterOf(), dirB, false,
            32L * 1024 * 1024);
        receiver.subscribe();
        var sender = new CourierFileLayer(bridgeA, "courier-node-a", rosterOf(identityA), dirA, false,
            32L * 1024 * 1024);

        var src = dirA.resolve("secret.txt");
        Files.writeString(src, "hello");
        var result = sender.copyTo("courier-node-b", src.toString(), "drops/secret.txt");
        assertFalse(result.ok());
        assertTrue(result.error().contains("not an enrolled household node"), result.error());
        assertFalse(Files.exists(dirB.resolve("courier").resolve("drops").resolve("secret.txt")));
    }

    @Test
    void absolute_destination_is_refused_by_default(@TempDir Path dirA, @TempDir Path dirB)
            throws Exception {
        Function<String, Optional<byte[]>> roster = nodeId -> switch (nodeId) {
            case "courier-node-a" -> Optional.of(identityA.publicKeyBytes());
            case "courier-node-b" -> Optional.of(identityB.publicKeyBytes());
            default -> Optional.empty();
        };
        var receiver = new CourierFileLayer(bridgeB, "courier-node-b", roster, dirB, false,
            32L * 1024 * 1024);
        receiver.subscribe();
        var sender = new CourierFileLayer(bridgeA, "courier-node-a", roster, dirA, false,
            32L * 1024 * 1024);

        var src = dirA.resolve("evil.txt");
        Files.writeString(src, "overwrite attempt");
        var target = dirB.resolve("somewhere-outside-inbox.txt");
        var result = sender.copyTo("courier-node-b", src.toString(), target.toString());
        assertFalse(result.ok());
        assertTrue(result.error().contains("allow-absolute"), result.error());
        assertFalse(Files.exists(target));
    }
}
