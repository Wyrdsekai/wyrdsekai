package org.wyrdsekai.between;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.naming.EnvelopeVerificationMode;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2026-09-28: NatsBridge handed every envelope to its handler unverified. The guard now drops
 * forged, unsigned, unknown-sender, stale and replayed envelopes, pins a non-roster node's key at its
 * first signed hello, and lets a key change only through a rotation signed by the old key.
 */
class EnvelopeGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZONE = "home";

    @TempDir Path tmp;
    private NodeIdentity hub;
    private NodeIdentity stranger;
    private NodeIdentity impostor;
    private final Map<String, byte[]> roster = new HashMap<>();
    private final Map<String, byte[]> zones = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        hub = NodeIdentity.loadOrGenerate(tmp.resolve("hub.json"));
        stranger = NodeIdentity.loadOrGenerate(tmp.resolve("stranger.json"));
        impostor = NodeIdentity.loadOrGenerate(tmp.resolve("impostor.json"));
        roster.put(hub.nodeId(), hub.publicKeyBytes());
    }

    private EnvelopeGuard guard(EnvelopeVerificationMode mode, Clock clock, PeerKeyPins pins) {
        return new EnvelopeGuard(ZONE, id -> Optional.ofNullable(roster.get(id)), pins,
            z -> Optional.ofNullable(zones.get(z)), (id, k) -> roster.put(id, k),
            mode, EnvelopeGuard.DEFAULT_MAX_SKEW, clock);
    }

    /** What actually arrives: the envelope after serialization. */
    private static BetweenEnvelope wire(BetweenEnvelope env) {
        return BetweenEnvelope.fromBytes(env.toBytes());
    }

    private EnvelopeGuard guard() {
        return guard(EnvelopeVerificationMode.HARD, Clock.systemUTC(), new PeerKeyPins(tmp.resolve("peer-keys.json")));
    }

    private static String subject(String src, String layer, String topic) {
        return "between." + ZONE + "." + src + ".*." + layer + "." + topic;
    }

    private static ObjectNode payload(String type) {
        var p = MAPPER.createObjectNode();
        p.put("type", type);
        return p;
    }

    private static ObjectNode hello(String nodeId, NodeIdentity keyOwner) {
        var p = payload("hello");
        p.put("nodeId", nodeId);
        p.put("publicKey", keyOwner.publicKeyBase64());
        return p;
    }

    @Test
    void aGoodEnvelopeFromARosterNodeIsDelivered() {
        var env = wire(BetweenEnvelope.create(hub.nodeId(), null, payload("state"), hub));
        assertThat(guard().admit(subject(hub.nodeId(), "crdt", "state"), "shared", env)).isTrue();
    }

    @Test
    void aForgedEnvelopeIsDropped() {
        // Claims to be the hub, signed by someone else.
        var env = wire(BetweenEnvelope.create(hub.nodeId(), null, payload("state"), impostor));
        assertThat(guard().admit(subject(hub.nodeId(), "crdt", "state"), "shared", env)).isFalse();
    }

    @Test
    void anUnsignedEnvelopeIsDropped() {
        var env = new BetweenEnvelope(1, hub.nodeId(), null, Instant.now(), null, payload("state"));
        assertThat(guard().admit(subject(hub.nodeId(), "crdt", "state"), "shared", env)).isFalse();
    }

    @Test
    void anUnknownSenderIsDropped() {
        var env = wire(BetweenEnvelope.create(stranger.nodeId(), null, payload("state"), stranger));
        assertThat(guard().admit(subject(stranger.nodeId(), "crdt", "state"), "shared", env)).isFalse();
    }

    @Test
    void aReplayedEnvelopeIsDropped() {
        var g = guard();
        var env = wire(BetweenEnvelope.create(hub.nodeId(), null, payload("state"), hub));
        var subj = subject(hub.nodeId(), "crdt", "state");
        assertThat(g.admit(subj, "shared", env)).isTrue();
        assertThat(g.admit(subj, "shared", env)).as("the same envelope again").isFalse();
        // Re-published on another subject of the same channel: still the same envelope.
        assertThat(g.admit(subject(hub.nodeId(), "memory", "entry"), "shared", env)).isFalse();
    }

    @Test
    void aStaleEnvelopeIsDropped() {
        var env = wire(BetweenEnvelope.create(hub.nodeId(), null, payload("state"), hub));
        var tenMinutesLater = Clock.fixed(Instant.now().plus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        var g = guard(EnvelopeVerificationMode.HARD, tenMinutesLater, new PeerKeyPins(null));
        assertThat(g.admit(subject(hub.nodeId(), "crdt", "state"), "shared", env)).isFalse();
    }

    @Test
    void theSubjectMustNameTheEnvelopesSender() {
        var env = wire(BetweenEnvelope.create(hub.nodeId(), null, payload("state"), hub));
        assertThat(guard().admit(subject(stranger.nodeId(), "crdt", "state"), "shared", env)).isFalse();
    }

    @Test
    void aFirstHelloPinsTheKeyAndAnotherKeyIsRefusedAfterwards() {
        var g = guard();
        var first = wire(BetweenEnvelope.create(stranger.nodeId(), null, hello(stranger.nodeId(), stranger), stranger));
        assertThat(g.admit(subject(stranger.nodeId(), "cluster", "hello"), "shared", first)).isTrue();
        assertThat(g.householdKey(stranger.nodeId())).contains(stranger.publicKeyBytes());

        var later = wire(BetweenEnvelope.create(stranger.nodeId(), null, payload("state"), stranger));
        assertThat(g.admit(subject(stranger.nodeId(), "crdt", "state"), "shared", later)).isTrue();

        // Someone else says hello under the same node id with their own key.
        var hijack = wire(BetweenEnvelope.create(stranger.nodeId(), null, hello(stranger.nodeId(), impostor), impostor));
        assertThat(g.admit(subject(stranger.nodeId(), "cluster", "hello"), "shared", hijack)).isFalse();
        var forged = wire(BetweenEnvelope.create(stranger.nodeId(), null, payload("state"), impostor));
        assertThat(g.admit(subject(stranger.nodeId(), "crdt", "state"), "shared", forged)).isFalse();
        assertThat(g.householdKey(stranger.nodeId())).contains(stranger.publicKeyBytes());
    }

    @Test
    void aHelloFromARosterNodeMustCarryTheRosterKey() {
        // hello is checked against the pinned (roster) key, not only against the key inside it.
        var hijack = wire(BetweenEnvelope.create(hub.nodeId(), null, hello(hub.nodeId(), impostor), impostor));
        assertThat(guard().admit(subject(hub.nodeId(), "cluster", "hello"), "shared", hijack)).isFalse();
    }

    @Test
    void pinsSurviveARestart() {
        var file = tmp.resolve("pins.json");
        var first = guard(EnvelopeVerificationMode.HARD, Clock.systemUTC(), new PeerKeyPins(file));
        var hello = wire(BetweenEnvelope.create(stranger.nodeId(), null, hello(stranger.nodeId(), stranger), stranger));
        assertThat(first.admit(subject(stranger.nodeId(), "cluster", "hello"), "shared", hello)).isTrue();

        var afterRestart = guard(EnvelopeVerificationMode.HARD, Clock.systemUTC(), new PeerKeyPins(file));
        var hijack = wire(BetweenEnvelope.create(stranger.nodeId(), null, hello(stranger.nodeId(), impostor), impostor));
        assertThat(afterRestart.admit(subject(stranger.nodeId(), "cluster", "hello"), "shared", hijack)).isFalse();
        assertThat(afterRestart.householdKey(stranger.nodeId())).contains(stranger.publicKeyBytes());
    }

    @Test
    void aKeyChangesOnlyThroughARotationSignedByTheOldKey() throws Exception {
        var g = guard();
        g.admit(subject(stranger.nodeId(), "cluster", "hello"), "shared",
            wire(BetweenEnvelope.create(stranger.nodeId(), null, hello(stranger.nodeId(), stranger), stranger)));
        var next = NodeIdentity.loadOrGenerate(tmp.resolve("next.json"));

        // Without the proof from the new key: refused, pin unchanged.
        var noProof = payload(KeyRotation.TYPE);
        noProof.put("newPublicKey", next.publicKeyBase64());
        noProof.put("newKeySig", Base64.getEncoder().encodeToString(new byte[64]));
        assertThat(g.admit(subject(stranger.nodeId(), "cluster", "key_rotation"), "shared",
            wire(BetweenEnvelope.create(stranger.nodeId(), null, noProof, stranger)))).isFalse();
        assertThat(g.householdKey(stranger.nodeId())).contains(stranger.publicKeyBytes());

        // Signed by the new key only (not the old): refused.
        var rotation = payload(KeyRotation.TYPE);
        rotation.put("newPublicKey", next.publicKeyBase64());
        rotation.put("newKeySig", Base64.getEncoder().encodeToString(next.sign(
            KeyRotation.statement(stranger.nodeId(), stranger.publicKeyBytes(), next.publicKeyBytes()))));
        assertThat(g.admit(subject(stranger.nodeId(), "cluster", "key_rotation"), "shared",
            wire(BetweenEnvelope.create(stranger.nodeId(), null, rotation, next)))).isFalse();

        // Envelope signed by the old key, proof by the new: the pin moves.
        assertThat(g.admit(subject(stranger.nodeId(), "cluster", "key_rotation"), "shared",
            wire(BetweenEnvelope.create(stranger.nodeId(), null, rotation, stranger)))).isTrue();
        assertThat(g.householdKey(stranger.nodeId())).contains(next.publicKeyBytes());
        assertThat(g.admit(subject(stranger.nodeId(), "crdt", "state"), "shared",
            wire(BetweenEnvelope.create(stranger.nodeId(), null, payload("state"), stranger)))).isFalse();
        assertThat(g.admit(subject(stranger.nodeId(), "crdt", "state"), "shared",
            wire(BetweenEnvelope.create(stranger.nodeId(), null, payload("state"), next)))).isTrue();
    }

    @Test
    void anotherZonesSubjectNeedsTheKeyItsAgreementPinned() throws Exception {
        var beta = NodeIdentity.loadOrGenerate(tmp.resolve("beta.json"));
        var subj = "between.beta." + beta.nodeId() + ".*.capability.announce";
        var env = wire(BetweenEnvelope.create(beta.nodeId(), null, payload("announce"), beta));
        assertThat(guard().admit(subj, "shared", env)).as("no agreement with beta").isFalse();

        zones.put("beta", beta.publicKeyBytes());
        assertThat(guard().admit(subj, "shared", env)).isTrue();
        var forged = wire(BetweenEnvelope.create(beta.nodeId(), null, payload("announce"), impostor));
        assertThat(guard().admit(subj, "shared", forged)).isFalse();
    }

    @Test
    void softModeStillDeliversForTheTransition() {
        var g = guard(EnvelopeVerificationMode.SOFT, Clock.systemUTC(), new PeerKeyPins(null));
        var forged = wire(BetweenEnvelope.create(hub.nodeId(), null, payload("state"), impostor));
        assertThat(g.admit(subject(hub.nodeId(), "crdt", "state"), "shared", forged)).isTrue();
    }

    @Test
    void federationEnvelopesGetTheWindowAndTheReplayCacheHere() {
        // The zone signature is FederationActor's job (ZoneGate); the bus still drops repeats and stale ones.
        var g = guard();
        var subj = "federation.home.gate.revoke";
        var env = wire(BetweenEnvelope.create(stranger.nodeId(), null, payload("revoke"), stranger));
        assertThat(g.admit(subj, "shared", env)).isTrue();
        assertThat(g.admit(subj, "shared", env)).as("replayed revoke").isFalse();

        var later = Clock.fixed(Instant.now().plus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        var fresh = wire(BetweenEnvelope.create(stranger.nodeId(), null, payload("revoke"), stranger));
        assertThat(guard(EnvelopeVerificationMode.HARD, later, new PeerKeyPins(null)).admit(subj, "shared", fresh))
            .as("stale revoke").isFalse();
    }
}
