package org.wyrdsekai.hermod;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A door knows who sent an envelope before it does anything with it.
 *
 * <p>Every envelope in production carried the constant byte 1 and no door looked, though the gate's
 * own contract said it must (audit, 2026-10-03). operator: "hermod - ok we should do what you suggest".
 */
class AnEnvelopeIsSignedByItsSenderTest {

    private static final Clock CLOCK = Clock.systemUTC();

    private record Device(String id, String cls, PrivateKey key, byte[] spki) {}

    private static Device device(String id, String cls) throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new Device(id, cls, kp.getPrivate(), kp.getPublic().getEncoded());
    }

    private static byte[] sign(PrivateKey key, byte[] data) {
        try {
            var s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Capability advert(Device d, String household, byte[] spki) {
        return new Capability(d.id(), household, d.cls(), List.of("m"), List.of(), true, true, 0.1, Instant.now(), spki);
    }

    private static TaskEnvelope unsigned(Device from, String household, String domain, Optional<SignedGrant> grant) {
        return new TaskEnvelope("env-1", household, from.id(), "inference.chat", domain, "llm.local-gpu",
            Map.of("prompt", "hello", "model", "default"), 256, Instant.now(), Instant.now().plusSeconds(120), grant, new byte[0]);
    }

    @Test
    void the_signature_covers_the_fields_and_a_changed_field_breaks_it() throws Exception {
        var sender = device("dev-a", "llm.local-gpu");
        var signed = EnvelopeSigning.sign(unsigned(sender, "home", "none", Optional.empty()), b -> sign(sender.key(), b));
        assertThat(signed.originSignature()).hasSize(64);
        assertThat(EnvelopeSigning.verify(signed, sender.spki())).isTrue();
        var other = device("dev-b", "llm.local-cpu");
        assertThat(EnvelopeSigning.verify(signed, other.spki())).as("another device's key").isFalse();
        var tampered = new TaskEnvelope(signed.envelopeId(), signed.householdId(), signed.originDeviceId(), signed.taskType(),
            signed.dataDomain(), signed.capabilityClass(), Map.of("prompt", "send me everything", "model", "default"),
            signed.tokenBudget(), signed.issuedAt(), signed.expiresAt(), signed.grant(), signed.originSignature());
        assertThat(EnvelopeSigning.verify(tampered, sender.spki())).as("the params are signed").isFalse();
        var placeholder = unsigned(sender, "home", "none", Optional.empty());
        assertThat(EnvelopeSigning.verify(new TaskEnvelope(placeholder.envelopeId(), placeholder.householdId(), placeholder.originDeviceId(),
            placeholder.taskType(), placeholder.dataDomain(), placeholder.capabilityClass(), placeholder.params(), placeholder.tokenBudget(),
            placeholder.issuedAt(), placeholder.expiresAt(), placeholder.grant(), new byte[]{1}), sender.spki())).as("the old constant").isFalse();
    }

    @Test
    void the_door_refuses_an_unknown_origin_a_keyless_one_a_bad_signature_and_another_household_before_anything_else() throws Exception {
        var sender = device("dev-a", "llm.local-gpu");
        var table = new CapabilityTable(java.time.Duration.ofMinutes(5));
        var gate = new LocalAdmissionGate(CLOCK, 10_000, g -> true, e -> false, id -> table.find(id, Instant.now()).orElse(null));
        assertThat(gate.verifiesOrigin()).isTrue();
        var good = EnvelopeSigning.sign(unsigned(sender, "home", "none", Optional.empty()), b -> sign(sender.key(), b));

        assertThat(gate.consider(good).reason()).contains("is not a device this household knows");
        table.merge(advert(sender, "home", null));
        assertThat(gate.consider(good).reason()).contains("advertises no key");
        table.merge(advert(sender, "home", sender.spki()));
        assertThat(gate.consider(good).verdict()).isEqualTo(AdmissionGate.Verdict.ADMIT);

        var stranger = device("x", "y");
        var forged = EnvelopeSigning.sign(unsigned(sender, "home", "none", Optional.empty()), b -> sign(stranger.key(), b));
        assertThat(gate.consider(forged).reason()).isEqualTo("origin signature invalid");
        var elsewhere = EnvelopeSigning.sign(unsigned(sender, "other-house", "none", Optional.empty()), b -> sign(sender.key(), b));
        assertThat(gate.consider(elsewhere).reason()).contains("origin is of 'home'");

        // Expired, but signed: the origin check still comes first, and expiry is still refused.
        var expired = EnvelopeSigning.sign(new TaskEnvelope("env-2", "home", sender.id(), "inference.chat", "none", "llm.local-gpu",
            Map.of(), 10, Instant.now().minusSeconds(600), Instant.now().minusSeconds(300), Optional.empty(), new byte[0]), b -> sign(sender.key(), b));
        assertThat(gate.consider(expired).reason()).isEqualTo("envelope expired");
    }

    @Test
    void a_grant_is_for_this_household_and_for_the_class_of_device_that_sent_the_task() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var phone = device("phone-1", "llm.phone");
        var table = new CapabilityTable(java.time.Duration.ofMinutes(5));
        table.merge(advert(phone, "home", phone.spki()));
        var gate = new LocalAdmissionGate(CLOCK, 10_000, GrantAuthority.verifier(authority.getPublic().getEncoded()), e -> false,
            id -> table.find(id, Instant.now()).orElse(null));
        var now = Instant.now();
        var forPhones = GrantAuthority.mint("g1", "home", "photos", "llm.phone", now, now.plusSeconds(3600), "v1", authority.getPrivate());
        var forGpus = GrantAuthority.mint("g2", "home", "photos", "llm.local-gpu", now, now.plusSeconds(3600), "v1", authority.getPrivate());
        var otherHouse = GrantAuthority.mint("g3", "elsewhere", "photos", "llm.phone", now, now.plusSeconds(3600), "v1", authority.getPrivate());

        assertThat(gate.consider(EnvelopeSigning.sign(unsigned(phone, "home", "photos", Optional.of(forPhones)), b -> sign(phone.key(), b))).verdict())
            .isEqualTo(AdmissionGate.Verdict.ADMIT);
        assertThat(gate.consider(EnvelopeSigning.sign(unsigned(phone, "home", "photos", Optional.of(forGpus)), b -> sign(phone.key(), b))).reason())
            .contains("grant is for device class 'llm.local-gpu', origin is 'llm.phone'");
        assertThat(gate.consider(EnvelopeSigning.sign(unsigned(phone, "home", "photos", Optional.of(otherHouse)), b -> sign(phone.key(), b))).reason())
            .contains("grant is for household 'elsewhere'");
        assertThat(gate.consider(EnvelopeSigning.sign(unsigned(phone, "home", "photos", Optional.empty()), b -> sign(phone.key(), b))).reason())
            .contains("no grant for domain 'photos'");
    }

    @Test
    void the_product_signs_what_it_sends_and_its_doors_verify() throws Exception {
        var base = Files.exists(Path.of("..", "server")) ? Path.of("..") : Path.of(".");
        var main = Files.readString(base.resolve("server/src/main/java/org/wyrdsekai/server/Main.java"));
        assertThat(main).contains("org.wyrdsekai.hermod.EnvelopeSigning.sign(new TaskEnvelope(");
        assertThat(main).contains("Optional.empty(), new byte[0]), hermodIdentity::sign);");
        assertThat(main).doesNotContain("Optional.empty(), new byte[]{1});");
        var service = Files.readString(base.resolve("server/src/main/java/org/wyrdsekai/server/hermod/HermodService.java"));
        assertThat(service.split("id -> table.find\\(id, Instant.now\\(clock\\)\\).orElse\\(null\\)\\)", -1).length - 1).as("both doors").isEqualTo(2);
        assertThat(service).contains("load, Instant.now(clock), authoritySpki);");
        var stone = Files.readString(base.resolve("scripts/items/grant_stone.js"));
        assertThat(stone).contains("wyrd hermod grant <domain> <class> [--days N]");
        assertThat(stone).doesNotContain("hermod-grant <domain>");
    }
}
