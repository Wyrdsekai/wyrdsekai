package org.wyrdsekai.server.hermod;

import io.nats.client.Nats;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.inference.HermodInferenceExecutor;
import org.wyrdsekai.core.inference.InferenceClient;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * A COMPUTE-ONLY household node: no world, no rooms, no companions —
 * just the three verbs of mesh membership. Advertise what this box can
 * run, answer knocks on its own door, execute admitted errands on its
 * LOCAL inference. This is how a big idle GPU (or a mac in the closet)
 * lends itself to the household without hosting anything.
 *
 * Deliberately tiny and killable: one JVM, no listening sockets of its
 * own, NATS as its only door to the world. Ctrl-C / SIGTERM = the box
 * leaves the mesh; its advertisement ages out by TTL everywhere.
 *
 * Usage: hermod-node <natsUrl> <scopeId> <capabilityClass> <inferenceUrl> <model> [think|nothink]
 *   e.g. hermod-node nats://127.0.0.1:4223 home llm.local-gpu http://127.0.0.1:8210 default nothink
 *
 * Identity: <WYRDSEKAI_DATA_DIR|~/.wyrdsekai>/node-identity.json
 * (loadOrGenerate — a fresh box mints itself an identity on first run).
 * Resident data domains: WYRDSEKAI_HERMOD_DOMAINS (comma list), default none.
 */
public final class HermodNodeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: hermod-node <natsUrl> <scopeId> <capabilityClass>"
                + " <inferenceUrl> <model> [think|nothink]");
            System.exit(2);
        }
        var natsUrl = args[0];
        var scope = args[1];
        var capClass = args[2];
        var inferenceUrl = args[3];
        var model = args[4];
        var thinking = args.length < 6 || !"nothink".equalsIgnoreCase(args[5]);

        var dataDir = Path.of(System.getenv().getOrDefault(
            "WYRDSEKAI_DATA_DIR", System.getProperty("user.home") + "/.wyrdsekai"));
        var identity = NodeIdentity.loadOrGenerate(dataDir.resolve("node-identity.json"));

        var nats = Nats.connect(natsUrl);
        var gossip = new NatsGossip(nats, scope);
        var executor = new HermodInferenceExecutor(
            new InferenceClient(inferenceUrl, "", Duration.ofSeconds(120)),
            120, thinking);
        var service = new HermodService(gossip, scope, identity.nodeId(),
            capClass, model.isBlank() ? List.of() : List.of(model),
            executor, Clock.systemUTC(), identity.publicKeyBytes());
        var domainsRaw = System.getenv().getOrDefault("WYRDSEKAI_HERMOD_DOMAINS", "");
        service.residentDomains(domainsRaw.isBlank() ? List.of()
            : Arrays.stream(domainsRaw.split(",")).map(String::trim)
                .filter(d -> !d.isBlank()).toList());
        service.start();

        var doors = new NatsDoors(nats, scope);
        doors.serve(service.deviceId(), service.ownDoor());
        service.remoteDoors(doors);

        System.out.println("[hermod-node] " + identity.nodeId() + " lending '" + capClass
            + "' (" + model + (thinking ? ", think" : ", nothink") + ") on scope " + scope
            + " via " + natsUrl + " — Ctrl-C to leave the mesh");
        Thread.currentThread().join(); // serve until killed; TTL cleans up after us
    }
}
