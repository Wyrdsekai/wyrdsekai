package org.wyrdsekai.server.hermod;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.CapabilityTable;
import org.wyrdsekai.hermod.EnvelopeSigning;
import org.wyrdsekai.hermod.LocalAdmissionGate;
import org.wyrdsekai.hermod.TaskEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The device's key rides in its advertisement, so the door on the other node can verify what the
 * device sends. An advertisement from a node that predates keys decodes without one, and such a
 * device's envelopes are refused with that reason rather than with a crash.
 */
class TheAdvertisementCarriesTheKeyAcrossTheWireTest {

    @Test
    void the_key_survives_the_gossip_wire_and_an_old_advert_still_decodes() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var spki = kp.getPublic().getEncoded();
        var sent = new Capability("dev-a", "home", "llm.local-gpu", List.of("m"), List.of("photos"), true, false, 0.2, Instant.now(), spki);
        var back = NatsGossip.decode(NatsGossip.encode(sent));
        assertThat(back.publicKey()).isEqualTo(spki);
        assertThat(back.deviceId()).isEqualTo("dev-a");

        var old = """
            {"deviceId":"dev-old","householdId":"home","capabilityClass":"llm.local-cpu","models":["m"],
             "residentDataDomains":[],"charging":true,"idle":true,"loadFactor":0.1,"advertisedAt":"%s"}
            """.formatted(Instant.now());
        var decodedOld = NatsGossip.decode(old.getBytes(StandardCharsets.UTF_8));
        assertThat(decodedOld.publicKey()).isNull();

        var table = new CapabilityTable(Duration.ofMinutes(5));
        table.merge(back);
        table.merge(decodedOld);
        var gate = new LocalAdmissionGate(Clock.systemUTC(), 10_000, g -> true, e -> false, id -> table.find(id, Instant.now()).orElse(null));
        var env = new TaskEnvelope("e1", "home", "dev-a", "inference.chat", "none", "llm.local-gpu", Map.of("prompt", "hi"), 100,
            Instant.now(), Instant.now().plusSeconds(60), Optional.empty(), new byte[0]);
        var signed = EnvelopeSigning.sign(env, b -> {
            try { var s = Signature.getInstance("Ed25519"); s.initSign(kp.getPrivate()); s.update(b); return s.sign(); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        assertThat(DoorWire.decodeEnvelope(DoorWire.encodeEnvelope(signed)).originSignature()).isEqualTo(signed.originSignature());
        assertThat(gate.consider(DoorWire.decodeEnvelope(DoorWire.encodeEnvelope(signed))).verdict().name()).isEqualTo("ADMIT");
        var fromOld = new TaskEnvelope("e2", "home", "dev-old", "inference.chat", "none", "llm.local-cpu", Map.of(), 100,
            Instant.now(), Instant.now().plusSeconds(60), Optional.empty(), new byte[]{1});
        assertThat(gate.consider(fromOld).reason()).contains("advertises no key");
    }
}
