package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZoneManifestV1Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DID =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

    private static ZoneManifestV1 sample() {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen",
            "Alice's Kitchen", "\u2615",
            "Afternoon tea and light conversation.",
            "A warm, low-key space for casual chat and crafts. Welcoming to families.",
            List.of("family", "social", "crafts"),
            new ZoneManifestV1.Capabilities(
                new ZoneManifestV1.ParlorInfo(DID + ":kitchen:parlor-public",
                    "The Parlor", "A sunny sitting room."),
                List.of(new ZoneManifestV1.PublicRoom("garden", "The Garden", "Outdoors.")),
                List.of(new ZoneManifestV1.PublicAgent("kettle", "Kettle", "companion",
                    "The household companion.")),
                Map.of("open", "after-school", "closed", "overnight"),
                Map.of("tourist",
                    new ZoneManifestV1.TierCaps("PT1H", Map.of("inference", 5000)))
            ),
            new ZoneManifestV1.Contact(
                "federation.{did}.{zoneLabel}.gate.propose",
                "PT24H",
                "Please introduce yourself."),
            null, // mcp_endpoint
            14,
            "2026-01-15T00:00:00Z",
            "2026-04-19T15:30:00Z",
            null); // signature set by publisher
    }

    // ── validation ────────────────────────────────────────────────────

    @Test void validate_happyPath() {
        assertDoesNotThrow(() -> sample().validate());
    }

    @Test void validate_rejectsUnsupportedSchemaVersion() {
        var bad = new ZoneManifestV1(
            "wyrd-zone-manifest/99", DID, "kitchen", "Test", null,
            "tag", "desc", List.of(), null, null, null, 0, null, null, null);
        assertThrows(IllegalStateException.class, bad::validate);
    }

    @Test void validate_rejectsMalformedDid() {
        var bad = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, "not-a-did", "kitchen", "Test", null,
            "tag", "desc", List.of(), null, null, null, 0, null, null, null);
        assertThrows(IllegalStateException.class, bad::validate);
    }

    @Test void validate_rejectsReservedZoneLabel() {
        var bad = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "home", "Test", null,
            "tag", "desc", List.of(), null, null, null, 0, null, null, null);
        assertThrows(IllegalArgumentException.class, bad::validate);
    }

    @Test void validate_rejectsOversizedTagline() {
        var bad = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen", "Test", null,
            "x".repeat(ZoneManifestV1.TAGLINE_MAX + 1),
            "desc", List.of(), null, null, null, 0, null, null, null);
        assertThrows(IllegalStateException.class, bad::validate);
    }

    @Test void validate_rejectsOversizedDescription() {
        var bad = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen", "Test", null,
            "tag", "x".repeat(ZoneManifestV1.DESCRIPTION_MAX + 1),
            List.of(), null, null, null, 0, null, null, null);
        assertThrows(IllegalStateException.class, bad::validate);
    }

    @Test void validate_acceptsAtLimits() {
        var ok = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen", "Test", null,
            "x".repeat(ZoneManifestV1.TAGLINE_MAX),
            "x".repeat(ZoneManifestV1.DESCRIPTION_MAX),
            List.of(), null, null, null, 0, null, null, null);
        assertDoesNotThrow(ok::validate);
    }

    // ── JSON round-trip ───────────────────────────────────────────────

    @Test void jsonRoundTrip_preservesAllFields() {
        var original = sample();
        var json = original.toJsonString();
        var parsed = ZoneManifestV1.fromJsonString(json);

        assertEquals(original.did(), parsed.did());
        assertEquals(original.zoneLabel(), parsed.zoneLabel());
        assertEquals(original.displayName(), parsed.displayName());
        assertEquals(original.icon(), parsed.icon());
        assertEquals(original.tagline(), parsed.tagline());
        assertEquals(original.tags(), parsed.tags());
        assertEquals(original.agreementsCount(), parsed.agreementsCount());
    }

    @Test void jsonRoundTrip_preservesNestedCapabilities() {
        var original = sample();
        var parsed = ZoneManifestV1.fromJsonString(original.toJsonString());

        assertNotNull(parsed.capabilities());
        assertEquals(original.capabilities().parlor().tagline(),
            parsed.capabilities().parlor().tagline());
        assertEquals(1, parsed.capabilities().rooms().size());
        assertEquals("garden", parsed.capabilities().rooms().get(0).label());
    }

    @Test void jsonOmitsNullOptionalFields() throws Exception {
        // icon, mcp_endpoint, signature all @JsonInclude(NON_NULL) — should
        // not appear when null, keeping the manifest compact.
        var m = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen", "Test", null,
            "tag", "desc", List.of(), null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
        var json = m.toJsonString();
        var node = MAPPER.readTree(json);
        assertFalse(node.has("icon"));
        assertFalse(node.has("mcp_endpoint"));
        assertFalse(node.has("signature"));
    }

    @Test void fromJson_rejectsUnknownSchemaVersion() {
        var json = "{\"schema_version\":\"wyrd-zone-manifest/99\",\"did\":\""
            + DID + "\",\"zoneLabel\":\"kitchen\",\"displayName\":\"X\","
            + "\"tagline\":\"\",\"description\":\"\",\"tags\":[]}";
        assertThrows(IllegalStateException.class,
            () -> ZoneManifestV1.fromJsonString(json));
    }

    @Test void fromJson_tolerantOfUnknownFields() {
        // Spec §5.2 "Unknown fields are preserved on propagation" — our
        // parser at minimum shouldn't reject them outright. (Preservation
        // requires a separate pass at propagation time; not tested here.)
        var json = "{\"schema_version\":\"" + ZoneManifestV1.SCHEMA_VERSION
            + "\",\"did\":\"" + DID + "\",\"zoneLabel\":\"kitchen\","
            + "\"displayName\":\"X\",\"tagline\":\"\",\"description\":\"\","
            + "\"tags\":[],\"made_up_field\":\"ignored\"}";
        assertDoesNotThrow(() -> ZoneManifestV1.fromJsonString(json));
    }

    // ── size cap ──────────────────────────────────────────────────────

    @Test void toJsonBytes_belowTargetSize() {
        // Sample manifest is small — must fit well under target.
        var bytes = sample().toJsonBytes();
        assertTrue(bytes.length < ZoneManifestV1.SIZE_TARGET_BYTES,
            "sample manifest should fit under 2KB target, got " + bytes.length);
    }

    @Test void toJsonBytes_rejectsOverHardCap() {
        // Description is capped by validate() at 500 chars, but a giant
        // capabilities.rooms list can push past the hard cap. Build one to
        // confirm the size-check fires.
        var bigRooms = new ArrayList<ZoneManifestV1.PublicRoom>();
        for (int i = 0; i < 500; i++) {
            bigRooms.add(new ZoneManifestV1.PublicRoom(
                "r" + i, "Room " + i,
                "A room with a long description to push the manifest size over 4KB: "
                    + "x".repeat(30)));
        }
        var m = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen", "Big", null,
            "tag", "desc", List.of(),
            new ZoneManifestV1.Capabilities(null, bigRooms, List.of(), Map.of(), Map.of()),
            null, null, 0, null, null, null);
        assertThrows(IllegalStateException.class, m::toJsonBytes);
    }

    // ── signing bytes ─────────────────────────────────────────────────

    @Test void signingBytes_excludesSignatureField() throws Exception {
        var signed = sample().withSignature("ed25519:fake-sig");
        var signingBytes = signed.signingBytes();
        var signingJson = new String(signingBytes, StandardCharsets.UTF_8);
        assertFalse(signingJson.contains("fake-sig"),
            "signature must not be part of what it signs");
        assertFalse(signingJson.contains("\"signature\""),
            "signing bytes must omit the signature field");
    }

    @Test void signingBytes_stableAcrossWithSignatureCalls() {
        // Signing bytes should be identical before and after attaching a
        // signature — otherwise verification would never succeed.
        var before = sample();
        var after = sample().withSignature("ed25519:sig");
        assertArrayEquals(before.signingBytes(), after.signingBytes());
    }

    @Test void withSignature_returnsNewRecord() {
        var before = sample();
        assertNull(before.signature());
        var after = before.withSignature("ed25519:xyz");
        assertEquals("ed25519:xyz", after.signature());
        // Original unchanged (record is immutable).
        assertNull(before.signature());
    }

    // ── estimated size ────────────────────────────────────────────────

    @Test void estimatedSizeBytes_matchesActualSerialization() {
        var m = sample();
        var estimated = m.estimatedSizeBytes();
        var actual = m.toJsonBytes().length;
        assertEquals(actual, estimated);
    }

    // ── expanded schema: reputation, attestations, contact endpoint/relay,
    //                     agent skills, per-tier cost ───────────────────

    @Test void reputation_roundTripsThroughJson() throws Exception {
        var rep = new ZoneManifestV1.Reputation(0.82, 23,
            Map.of("reliable", 0.87, "helpful", 0.91));
        var m = sample().withReputation(rep, null);
        var bytes = m.toJsonBytes();
        var parsed = ZoneManifestV1.fromJsonBytes(bytes);
        assertNotNull(parsed.reputation());
        assertEquals(0.82, parsed.reputation().score());
        assertEquals(23, parsed.reputation().samples());
        assertEquals(0.87, parsed.reputation().categories().get("reliable"));
    }

    @Test void reputation_nullOmittedFromJson() throws Exception {
        var m = sample();  // no reputation
        var json = m.toJsonString();
        assertFalse(json.contains("\"reputation\""),
            "null reputation must be omitted so manifests stay compact");
    }

    @Test void attestations_roundTripAndTopNOrder() throws Exception {
        var atts = List.of(
            new ZoneManifestV1.Attestation(DID, "reliable", 0.9,  "2026-03-15T00:00:00Z"),
            new ZoneManifestV1.Attestation(DID, "helpful",  0.75, "2026-02-01T00:00:00Z")
        );
        var m = sample().withReputation(null, atts);
        var parsed = ZoneManifestV1.fromJsonBytes(m.toJsonBytes());
        assertEquals(2, parsed.attestations().size());
        assertEquals("reliable", parsed.attestations().get(0).category());
        assertEquals(0.9, parsed.attestations().get(0).weight());
    }

    @Test void attestations_emptyListOmittedFromJson() {
        var m = sample().withReputation(null, List.of());
        var json = m.toJsonString();
        assertFalse(json.contains("\"attestations\""),
            "empty list must be omitted (@JsonInclude NON_EMPTY)");
    }

    @Test void contact_endpointAndRelayRoundTrip() throws Exception {
        var c = new ZoneManifestV1.Contact(
            "https://alice.example.com",
            "relay://relay-node.example.com/alice",
            "federation.x.propose", "PT24H", "please knock");
        var m = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen", "Alice's Kitchen", null,
            "tag", "desc", List.of(), null, c,
            null, 0, "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);

        var parsed = ZoneManifestV1.fromJsonBytes(m.toJsonBytes());
        assertEquals("https://alice.example.com", parsed.contact().endpoint());
        assertEquals("relay://relay-node.example.com/alice", parsed.contact().relay());
    }

    @Test void contact_backwardCompatConstructor() {
        var c = new ZoneManifestV1.Contact(
            "federation.x.propose", "PT24H", "please knock");
        assertNull(c.endpoint());
        assertNull(c.relay());
        assertEquals("please knock", c.knockGuidance());
    }

    @Test void tierCaps_costCuPerRequestRoundTrip() throws Exception {
        var tier = new ZoneManifestV1.TierCaps("PT1H",
            Map.of("inference", 5000), 10);
        assertEquals(10, tier.costCuPerRequest());

        var caps = new ZoneManifestV1.Capabilities(null, List.of(), List.of(),
            Map.of(), Map.of("tourist", tier));
        var m = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen", "Name", null,
            "tag", "desc", List.of(), caps, null,
            null, 0, "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
        var parsed = ZoneManifestV1.fromJsonBytes(m.toJsonBytes());
        var parsedTier = parsed.capabilities().tiers().get("tourist");
        assertEquals(10, parsedTier.costCuPerRequest());
    }

    @Test void tierCaps_backwardCompatConstructor() {
        var tier = new ZoneManifestV1.TierCaps("PT1H", Map.of("inference", 5000));
        assertNull(tier.costCuPerRequest(),
            "back-compat 2-arg constructor must default cost to null");
    }

    @Test void publicAgent_skillsRoundTrip() throws Exception {
        var agent = new ZoneManifestV1.PublicAgent(
            "kettle", "Kettle", "companion",
            "The household companion.",
            List.of("greeting", "recipe-lookup", "meal-planning"));
        assertEquals(3, agent.skills().size());

        var caps = new ZoneManifestV1.Capabilities(null, List.of(),
            List.of(agent), Map.of(), Map.of());
        var m = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, "kitchen", "Name", null,
            "tag", "desc", List.of(), caps, null,
            null, 0, "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
        var parsed = ZoneManifestV1.fromJsonBytes(m.toJsonBytes());
        var parsedAgent = parsed.capabilities().agents().get(0);
        assertEquals(List.of("greeting", "recipe-lookup", "meal-planning"),
            parsedAgent.skills());
    }

    @Test void publicAgent_backwardCompatConstructor() {
        var a = new ZoneManifestV1.PublicAgent("kettle", "Kettle", "companion", "desc");
        assertNull(a.skills());
    }

    @Test void signingBytes_coversReputationAndAttestations() {
        var rep = new ZoneManifestV1.Reputation(0.5, 1, Map.of());
        var m1 = sample().withReputation(rep, null);
        var m2 = sample().withReputation(null, null);

        // Different reputations → different signing bytes.
        assertFalse(Arrays.equals(m1.signingBytes(), m2.signingBytes()),
            "reputation must be covered by the signature");
    }

    @Test void fromJsonBytes_ignoresUnknownFields() {
        // Forward-compat: future schema_version/1.1 might add fields; we
        // must preserve round-trip for the fields we know about.
        var json = "{\"schema_version\":\"" + ZoneManifestV1.SCHEMA_VERSION
            + "\",\"did\":\"" + DID + "\",\"zoneLabel\":\"kitchen\","
            + "\"displayName\":\"Name\",\"tagline\":\"t\",\"description\":\"d\","
            + "\"tags\":[],\"agreements_count\":0,"
            + "\"created_at\":\"2026-01-15T00:00:00Z\","
            + "\"refreshed_at\":\"2026-04-19T15:30:00Z\","
            + "\"future_field\":{\"unknown\":true},"
            + "\"capabilities\":null,\"contact\":null}";
        assertDoesNotThrow(() -> ZoneManifestV1.fromJsonString(json));
    }
}
