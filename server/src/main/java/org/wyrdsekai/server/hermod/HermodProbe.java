package org.wyrdsekai.server.hermod;

import io.nats.client.Nats;
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

        var nats = Nats.connect(natsUrl);
        var gossip = new NatsGossip(nats, scope);
        var table = new CapabilityTable(Duration.ofSeconds(90));
        table.attach(gossip);
        gossip.publish(new Capability(deviceId, scope, "probe", List.of(),
            List.of(), true, true, 0.0, Instant.now()));

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
        var envelope = new TaskEnvelope("probe-" + System.nanoTime(), scope, deviceId,
            "inference.chat", "none", capClass,
            Map.of("model", "default", "prompt", prompt),
            256, Instant.now(), Instant.now().plusSeconds(180),
            Optional.empty(), new byte[]{1});

        System.out.println("[probe] submitting errand for '" + capClass + "' ...");
        var result = mesh.submit(envelope);
        System.out.println("[probe] ok=" + result.ok());
        System.out.println("[probe] output: " + result.output());
        System.out.println("[probe] error: " + result.error());
        nats.close();
        System.exit(result.ok() ? 0 : 1);
    }
}
