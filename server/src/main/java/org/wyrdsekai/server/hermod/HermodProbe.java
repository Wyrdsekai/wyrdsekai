package org.wyrdsekai.server.hermod;

import io.nats.client.Nats;
import io.nats.client.Options;
import org.wyrdsekai.between.HouseholdBusClient;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.CapabilityTable;
import org.wyrdsekai.hermod.DefaultRouter;
import org.wyrdsekai.hermod.Mesh;
import org.wyrdsekai.hermod.TaskEnvelope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phone-sim for the live two-node test: joins the household scope as a
 * tiny device, learns the table from gossip, and sends one inference
 * errand through a remote door. Not shipped in any installer path —
 * a bench instrument.
 *
 * Usage: HermodProbe <natsUrl> <scopeId> <capabilityClass> <prompt>
 */
public final class HermodProbe {

    public static void main(String[] args) throws Exception {
        var natsUrl = args[0];
        var scope = args[1];
        var capClass = args[2];
        var prompt = args[3];
        var deviceId = "probe-" + System.getenv().getOrDefault("HOSTNAME", "bench");

        var nats = Nats.connect(HouseholdBusClient.secure(new Options.Builder().server(natsUrl), natsUrl).build());
        var gossip = new NatsGossip(nats, scope);
        var table = new CapabilityTable(Duration.ofSeconds(90));
        table.attach(gossip);
        // The probe advertises its key and signs with it, like any device: a verifying door refuses
        // an envelope from a device whose advert carries no key.
        var probeIdentity = org.wyrdsekai.between.NodeIdentity.loadOrGenerate(java.nio.file.Path.of(
            System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR", System.getProperty("user.home") + "/.wyrdsekai"))
            .resolve("node-identity.json"));
        gossip.publish(new Capability(deviceId, scope, "probe", List.of(),
            List.of(), true, true, 0.0, Instant.now(), probeIdentity.publicKeyBytes()));

        System.out.println("[probe] listening for gossip on scope " + scope + " ...");
        for (int i = 0; i < 24; i++) {
            Thread.sleep(5000);
            var snap = table.snapshot(Instant.now());
            System.out.println("[probe] table: " + snap.stream()
                .map(c -> c.deviceId() + "(" + c.capabilityClass() + ")").toList());
            if (snap.stream().anyMatch(c -> c.capabilityClass().equals(capClass))) break;
        }

        var doors = new NatsDoors(nats, scope);
        var mesh = new Mesh(new DefaultRouter(table, Clock.systemUTC()),
            (e, cap) -> doors.doorTo(cap.deviceId()));
        // Signed like the product's own envelopes: a verifying door refuses the old constant byte.
        var envelope = org.wyrdsekai.hermod.EnvelopeSigning.sign(new TaskEnvelope("probe-" + System.nanoTime(), scope, deviceId,
            "inference.chat", "none", capClass,
            Map.of("model", "default", "prompt", prompt),
            256, Instant.now(), Instant.now().plusSeconds(180),
            Optional.empty(), new byte[0]), probeIdentity::sign);

        System.out.println("[probe] submitting errand for '" + capClass + "' ...");
        var result = mesh.submit(envelope);
        System.out.println("[probe] ok=" + result.ok());
        System.out.println("[probe] output: " + result.output());
        System.out.println("[probe] error: " + result.error());
        nats.close();
        System.exit(result.ok() ? 0 : 1);
    }
}
