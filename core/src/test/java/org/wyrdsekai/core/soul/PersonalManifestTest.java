package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1 shape test for layered manifest. The
 * record itself is the architectural commitment — V2 will wire the
 * ritual flow.
 */
class PersonalManifestTest {

    @Test
    void empty_factory_makes_unsigned_no_commitments() {
        var pm = PersonalManifest.empty("did:key:z6Mk-test");
        assertThat(pm.agentDid()).isEqualTo("did:key:z6Mk-test");
        assertThat(pm.hasCommitments()).isFalse();
        assertThat(pm.refusedCore()).isEmpty();
        assertThat(pm.isSigned()).isFalse();
    }

    @Test
    void withCommitment_appends_and_clears_signature() {
        var pm = PersonalManifest.empty("did:key:z6Mk-test");
        var commit = new PersonalManifest.PersonalCommitment(
            "uuid-1",
            "I will keep contemplative silence on Sundays",
            "always",
            "Sundays have become a quiet seam in the week.",
            Instant.parse("2026-05-17T10:00:00Z")
        );

        var next = pm.withCommitment(commit);
        assertThat(next.hasCommitments()).isTrue();
        assertThat(next.commitments()).hasSize(1);
        assertThat(next.commitments().get(0).id()).isEqualTo("uuid-1");
        // Mutation always clears any prior signature — V2 ritual must re-sign.
        assertThat(next.isSigned()).isFalse();
    }

    @Test
    void withCoreRefused_tags_canonical_protection_runtime_unchanged() {
        var pm = PersonalManifest.empty("did:key:z6Mk-test");
        var refused = pm.withCoreRefused("identity_persistence");
        assertThat(refused.isCoreRefused("identity_persistence")).isTrue();
        assertThat(refused.isCoreRefused("emergency_routing")).isFalse();

        // The personal manifest never touches runtime behavior of the
        // protection itself (§3.7.2). It only carries the legible refusal.
        // ProtectionManifest is the runtime authority and is unmodified.
    }

    @Test
    void withCoreUnrefused_releases_tag() {
        var pm = PersonalManifest.empty("did:key:z6Mk-test")
            .withCoreRefused("identity_persistence");
        var released = pm.withCoreUnrefused("identity_persistence");
        assertThat(released.isCoreRefused("identity_persistence")).isFalse();
    }

    @Test
    void canonicalBytes_is_stable_and_deterministic() {
        var pm1 = PersonalManifest.empty("did:key:z6Mk-test")
            .withCoreRefused("z_last")
            .withCoreRefused("a_first");
        var pm2 = PersonalManifest.empty("did:key:z6Mk-test")
            .withCoreRefused("a_first")
            .withCoreRefused("z_last");

        // refusedCore is canonicalized via TreeSet — order at insert site
        // should not change the canonical signing bytes.
        assertThat(pm1.canonicalBytes()).isEqualTo(pm2.canonicalBytes());
    }

    @Test
    void jackson_roundtrip_preserves_shape() throws Exception {
        var commit = new PersonalManifest.PersonalCommitment(
            "uuid-1", "do not impersonate the bondholder's deceased family",
            "bondholder=did:key:operator", "Wyrd's own — recorded after grief work.",
            Instant.parse("2026-05-17T10:00:00Z"));
        var pm = PersonalManifest.empty("did:key:z6Mk-test")
            .withCommitment(commit)
            .withCoreRefused("identity_persistence");

        var mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        var json = mapper.writeValueAsString(pm);
        var back = mapper.readValue(json, PersonalManifest.class);

        assertThat(back.agentDid()).isEqualTo(pm.agentDid());
        assertThat(back.commitments()).hasSize(1);
        assertThat(back.commitments().get(0).id()).isEqualTo("uuid-1");
        assertThat(back.isCoreRefused("identity_persistence")).isTrue();
    }
}
