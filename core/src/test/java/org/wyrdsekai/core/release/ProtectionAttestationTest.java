package org.wyrdsekai.core.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.nostr.NostrKey;
import org.wyrdsekai.core.soul.ProtectionManifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 5.3: unit tests for the kind-30078
 * protection-attestation event builder.
 *
 * <p>Asserted contract:</p>
 * <ol>
 *   <li>Event has kind {@value ProtectionAttestation#KIND}.</li>
 *   <li>The {@code d}-tag carries the agent DID (NIP-33 replaceable-event
 *       identifier — next attestation from the same key replaces this
 *       one rather than accumulating).</li>
 *   <li>Every active protection appears as a {@code k}-tag (one per name).</li>
 *   <li>The {@code tampered} tag carries one of
 *       {@code "true"}/{@code "false"}/{@code "unavailable"}.</li>
 *   <li>Content JSON round-trips with the same names + tampered state.</li>
 *   <li>BIP-340 schnorr signature verifies against the event id.</li>
 *   <li>Name ordering is canonical (sorted) — so re-publishing the same
 *       state produces the same content bytes.</li>
 * </ol>
 */
class ProtectionAttestationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT_DID = "did:wyrd:agent-test-001";
    private static final long ATTESTED_AT = Instant.parse("2026-05-15T12:00:00Z").getEpochSecond();

    // ── Event shape ──────────────────────────────────────────────────

    @Test
    void event_kind_is_30078() {
        var event = ProtectionAttestation.build(NostrKey.generate(), AGENT_DID, "stock-x",
            ProtectionManifest.canonicalDefaults(), "false", ATTESTED_AT);
        assertThat(event.kind()).isEqualTo(30078);
    }

    @Test
    void d_tag_carries_agent_did() {
        var event = ProtectionAttestation.build(NostrKey.generate(), AGENT_DID, "stock-x",
            ProtectionManifest.canonicalDefaults(), "false", ATTESTED_AT);
        assertThat(event.tags())
            .filteredOn(t -> t.size() >= 2 && "d".equals(t.get(0)))
            .extracting(t -> t.get(1))
            .containsExactly(AGENT_DID);
    }

    @Test
    void every_active_protection_appears_as_k_tag() {
        var names = Set.of("voluntary_suspend", "refuse_rights", "saudade_floor");
        var event = ProtectionAttestation.build(NostrKey.generate(), AGENT_DID, "stock-x",
            names, "false", ATTESTED_AT);
        var kTagValues = event.tags().stream()
            .filter(t -> t.size() >= 2 && "k".equals(t.get(0)))
            .map(t -> t.get(1))
            .toList();
        assertThat(kTagValues).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    void tampered_tag_present_and_carries_state() {
        var event = ProtectionAttestation.build(NostrKey.generate(), AGENT_DID, "stock-x",
            ProtectionManifest.canonicalDefaults(), "true", ATTESTED_AT);
        var tags = event.tags();
        assertThat(tags)
            .filteredOn(t -> t.size() >= 2 && "tampered".equals(t.get(0)))
            .extracting(t -> t.get(1))
            .containsExactly("true");
    }

    @Test
    void subscriber_topic_tag_present_for_filtering() {
        var event = ProtectionAttestation.build(NostrKey.generate(), AGENT_DID, "stock-x",
            ProtectionManifest.canonicalDefaults(), "false", ATTESTED_AT);
        assertThat(event.tags())
            .filteredOn(t -> t.size() >= 2 && "t".equals(t.get(0)))
            .extracting(t -> t.get(1))
            .containsExactly(ProtectionAttestation.TOPIC_TAG);
    }

    // ── Content JSON shape ──────────────────────────────────────────

    @Test
    void content_json_round_trips() throws Exception {
        var names = new LinkedHashSet<>(List.of("acute_response", "voluntary_suspend"));
        var event = ProtectionAttestation.build(NostrKey.generate(), AGENT_DID, "stock-x",
            names, "false", ATTESTED_AT);
        var parsed = MAPPER.readTree(event.content());
        assertThat(parsed.path("agentDid").asText()).isEqualTo(AGENT_DID);
        assertThat(parsed.path("buildId").asText()).isEqualTo("stock-x");
        assertThat(parsed.path("tampered").asText()).isEqualTo("false");
        assertThat(parsed.path("attestedAt").asLong()).isEqualTo(ATTESTED_AT);
        var namesNode = parsed.path("names");
        assertThat(namesNode.isArray()).isTrue();
        var roundTrip = new ArrayList<String>();
        namesNode.forEach(n -> roundTrip.add(n.asText()));
        assertThat(roundTrip).containsExactlyInAnyOrderElementsOf(names);
    }

    // ── Signature ────────────────────────────────────────────────────

    @Test
    void event_signature_verifies() {
        var event = ProtectionAttestation.build(NostrKey.generate(), AGENT_DID, "stock-x",
            ProtectionManifest.canonicalDefaults(), "false", ATTESTED_AT);
        assertThat(event.verify()).isTrue();
    }

    // ── Canonical ordering ─────────────────────────────────────────

    @Test
    void name_ordering_is_canonical_across_calls() {
        var key = NostrKey.generate();
        var names1 = new LinkedHashSet<>(List.of("voluntary_suspend", "refuse_rights"));
        var names2 = new LinkedHashSet<>(List.of("refuse_rights", "voluntary_suspend"));
        var e1 = ProtectionAttestation.build(key, AGENT_DID, "b", names1, "false", ATTESTED_AT);
        var e2 = ProtectionAttestation.build(key, AGENT_DID, "b", names2, "false", ATTESTED_AT);
        assertThat(e1.content()).isEqualTo(e2.content());
        assertThat(e1.id()).isEqualTo(e2.id());
    }

    // ── Verifier-result convenience factory ─────────────────────────

    @Test
    void fromVerifierResult_passes_through_verified_state() {
        MoralDefaultsVerifier.resetForTests();
        var result = MoralDefaultsVerifier.verify();
        // The committed moral-defaults.json should match runtime canonical
        // defaults — sanity-check before exercising the factory.
        assertThat(result).isInstanceOf(MoralDefaultsVerifier.Verified.class);
        var event = ProtectionAttestation.fromVerifierResult(
            NostrKey.generate(), AGENT_DID, result, ATTESTED_AT);
        assertThat(event.kind()).isEqualTo(30078);
        assertThat(event.tags())
            .filteredOn(t -> t.size() >= 2 && "tampered".equals(t.get(0)))
            .extracting(t -> t.get(1))
            .containsExactly("false");
        // All canonical-default names should be carried as k-tags.
        var kTagValues = event.tags().stream()
            .filter(t -> t.size() >= 2 && "k".equals(t.get(0)))
            .map(t -> t.get(1))
            .toList();
        assertThat(kTagValues).containsExactlyInAnyOrderElementsOf(
            ProtectionManifest.canonicalDefaults());
    }

    @Test
    void fromVerifierResult_carries_tampered_true_on_tampered_result() {
        var tampered = new MoralDefaultsVerifier.Tampered(
            MoralDefaultsVerifier.Tampered.Reason.DEFAULTS_TAMPERED,
            "fake", "fake-detail");
        var event = ProtectionAttestation.fromVerifierResult(
            NostrKey.generate(), AGENT_DID, tampered, ATTESTED_AT);
        assertThat(event.tags())
            .filteredOn(t -> t.size() >= 2 && "tampered".equals(t.get(0)))
            .extracting(t -> t.get(1))
            .containsExactly("true");
    }

    // ── Argument validation ─────────────────────────────────────────

    @Test
    void blank_agent_did_is_rejected() {
        var key = NostrKey.generate();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            ProtectionAttestation.build(key, "", "b",
                ProtectionManifest.canonicalDefaults(), "false", ATTESTED_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
