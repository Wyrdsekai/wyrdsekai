package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.TestSchema;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Account replication is signed by the origin machine and accepted only from machines on this
 * household's roster; roles, removals and setting changes need a steward acting; invite codes travel
 * sealed to each machine (audit 2026-09-28).
 */
class IdentityReplicatorSigningTest {

    private static final String HASH = "$2a$12$abcdefghijklmnopqrstuuYlS1v7xWb5rZ2xw3yJ0j8Qv3lGmCq2a";

    @TempDir Path tmp;
    private AuthService auth;
    private InviteService invites;
    private NodeIdentity local;
    private NodeIdentity remote;
    private NodeIdentity stranger;
    private IdentityReplicator here;
    private IdentityReplicator fromRemote;
    private IdentityReplicator fromStranger;
    private final List<Map.Entry<String, byte[]>> wire = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = TestSchema.freshDb();
        auth = new AuthService(jdbc);
        invites = new InviteService(jdbc);
        local = NodeIdentity.loadOrGenerate(tmp.resolve("local.json"));
        remote = NodeIdentity.loadOrGenerate(tmp.resolve("remote.json"));
        stranger = NodeIdentity.loadOrGenerate(tmp.resolve("stranger.json"));
        var now = Instant.now();
        var localRow = new HouseholdStore.Row(local.nodeId(), local.publicKeyBytes(), "fp", "did",
            local.x25519PublicKeyBytes(), now, now);
        // This machine's roster knows the remote machine, not the stranger.
        here = new IdentityReplicator(null, (s, b) -> { }, local, local.nodeId(),
            id -> id.equals(remote.nodeId()) ? Optional.of(remote.publicKeyBytes()) : Optional.empty(),
            () -> List.of(localRow), auth, invites, false);
        fromRemote = new IdentityReplicator(null, (s, b) -> wire.add(Map.entry(s, b)), remote, remote.nodeId(),
            id -> Optional.empty(), () -> List.of(localRow), auth, invites, false);
        fromStranger = new IdentityReplicator(null, (s, b) -> wire.add(Map.entry(s, b)), stranger, stranger.nodeId(),
            id -> Optional.empty(), () -> List.of(localRow), auth, invites, false);
    }

    private void deliverAll() throws Exception {
        for (var e : List.copyOf(wire)) here.apply(e.getKey(), e.getValue());
        wire.clear();
    }

    private String steward(String name) {
        return auth.register(name, "correct-horse-battery", name, "steward").orElseThrow().userId();
    }

    @Test
    void aSignedAccountFromARosterMachineKeepsTheRoleItsStewardGave() throws Exception {
        var keeper = steward("keeper");
        var id = UUID.randomUUID().toString();
        fromRemote.publishAccountCreated(id, "second-steward", HASH, "Second", "steward", keeper);
        deliverAll();
        assertThat(auth.findUserByUsername("second-steward").orElseThrow().role()).isEqualTo("steward");
    }

    @Test
    void theHouseholdsFirstStewardReplicatesAsSteward() throws Exception {
        var id = UUID.randomUUID().toString();
        fromRemote.publishAccountCreated(id, "first", HASH, "First", "steward", id);
        deliverAll();
        assertThat(auth.findUserByUsername("first").orElseThrow().role()).isEqualTo("steward");
    }

    @Test
    void aRoleWithoutAStewardActingArrivesAsMember() throws Exception {
        var member = auth.register("plain", "correct-horse-battery", "Plain", "member").orElseThrow().userId();
        fromRemote.publishAccountCreated(UUID.randomUUID().toString(), "climber", HASH, "Climber", "steward", member);
        deliverAll();
        assertThat(auth.findUserByUsername("climber").orElseThrow().role()).isEqualTo("member");
    }

    @Test
    void anAccountFromAMachineNotOnTheRosterIsRefused() throws Exception {
        fromStranger.publishAccountCreated(UUID.randomUUID().toString(), "intruder", HASH, "Intruder", "member", null);
        deliverAll();
        assertThat(auth.findUserByUsername("intruder")).isEmpty();
    }

    @Test
    void anEventForgedInARosterMachinesNameIsRefused() throws Exception {
        fromRemote.publishAccountCreated(UUID.randomUUID().toString(), "forged", HASH, "Forged", "member", null);
        var genuine = BetweenEnvelope.fromBytes(wire.getFirst().getValue());
        wire.clear();
        // Same claimed origin and payload, signed by the stranger.
        var forged = BetweenEnvelope.create(remote.nodeId(), null, genuine.payload(), stranger);
        here.apply("account.created", forged.toBytes());
        assertThat(auth.findUserByUsername("forged")).isEmpty();
    }

    @Test
    void anEventMovedToAnotherSubjectIsRefused() throws Exception {
        var keeper = steward("keeper3");
        var victim = auth.register("victim", "correct-horse-battery", "Victim", "member").orElseThrow().userId();
        fromRemote.publishAccountRemoved(victim, keeper);
        var bytes = wire.getFirst().getValue();
        wire.clear();
        here.apply("account.config.changed", bytes);
        assertThat(auth.findUserByUsername("victim")).isPresent();
        here.apply("account.removed", bytes);
        assertThat(auth.findUserByUsername("victim")).isEmpty();
    }

    @Test
    void aRemovalNeedsAStewardActing() throws Exception {
        var member = auth.register("m1", "correct-horse-battery", "M1", "member").orElseThrow().userId();
        var victim = auth.register("m2", "correct-horse-battery", "M2", "member").orElseThrow().userId();
        fromRemote.publishAccountRemoved(victim, member);
        deliverAll();
        assertThat(auth.findUserByUsername("m2")).isPresent();
    }

    @Test
    void aSettingChangeNeedsAStewardAndAnOldOneCannotRevertANewerOne() throws Exception {
        var keeper = steward("keeper4");
        fromRemote.publishConfigChanged("open_registration", "false", keeper);
        var older = wire.getFirst().getValue();
        wire.clear();
        Thread.sleep(5);
        fromRemote.publishConfigChanged("open_registration", "true", keeper);
        deliverAll();
        assertThat(auth.getConfig("open_registration")).isEqualTo("true");
        here.apply("account.config.changed", older);
        assertThat(auth.getConfig("open_registration")).isEqualTo("true");

        var member = auth.register("m3", "correct-horse-battery", "M3", "member").orElseThrow().userId();
        fromRemote.publishConfigChanged("open_registration", "false", member);
        deliverAll();
        assertThat(auth.getConfig("open_registration")).isEqualTo("true");
    }

    @Test
    void anInviteCodeTravelsSealedAndOpensOnlyHere() throws Exception {
        var keeper = steward("keeper5");
        var code = "amber anchor anvil arrow ash aurora";
        var invite = new InviteService.Invite(UUID.randomUUID().toString(), code, "Newcomer", "member",
            keeper, Instant.now(), Instant.now().plusSeconds(3600), null, null);
        fromRemote.publishInviteCreated(invite);
        var bytes = wire.getFirst().getValue();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("amber anchor");
        deliverAll();
        assertThat(invites.claimInvite(code, "claim:test")).isPresent();
    }
}
