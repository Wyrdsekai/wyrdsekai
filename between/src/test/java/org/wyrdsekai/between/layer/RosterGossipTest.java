package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.TestSchema;
import org.wyrdsekai.core.identity.HouseholdStore;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** A machine that joined earlier learns later members, but only from a machine already on its roster. */
@Tag("integration")
class RosterGossipTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tmp;
    private HouseholdStore roster;
    private NodeIdentity self;
    private NodeIdentity hub;
    private NodeIdentity later;
    private NodeIdentity stranger;
    private RosterGossip gossip;

    @BeforeEach
    void setUp() throws Exception {
        roster = new HouseholdStore(TestSchema.freshDb());
        self = NodeIdentity.loadOrGenerate(tmp.resolve("self.json"));
        hub = NodeIdentity.loadOrGenerate(tmp.resolve("hub.json"));
        later = NodeIdentity.loadOrGenerate(tmp.resolve("later.json"));
        stranger = NodeIdentity.loadOrGenerate(tmp.resolve("stranger.json"));
        roster.upsert(hub.nodeId(), hub.publicKeyBytes(), "fp", "did");
        gossip = new RosterGossip(null, self.nodeId(), roster);
    }

    private BetweenEnvelope announcement(NodeIdentity sender, NodeIdentity member, byte[] key) {
        var p = MAPPER.createObjectNode();
        p.put("type", "roster");
        var m = p.putArray("members").addObject();
        m.put("nodeId", member.nodeId());
        m.put("publicKeyB64", Base64.getEncoder().encodeToString(key));
        return BetweenEnvelope.create(sender.nodeId(), null, p, sender);
    }

    @Test
    void aRosterMachineCanIntroduceALaterMember() {
        gossip.onRoster(announcement(hub, later, later.publicKeyBytes()));
        assertThat(roster.get(later.nodeId())).isPresent();
    }

    @Test
    void aStrangerCannotAddMembers() {
        gossip.onRoster(announcement(stranger, stranger, stranger.publicKeyBytes()));
        assertThat(roster.get(stranger.nodeId())).isEmpty();
    }

    @Test
    void aKnownMembersKeyIsNotReplaced() {
        gossip.onRoster(announcement(hub, later, later.publicKeyBytes()));
        gossip.onRoster(announcement(hub, later, stranger.publicKeyBytes()));
        assertThat(roster.get(later.nodeId()).orElseThrow().publicKey()).containsExactly(later.publicKeyBytes());
    }
}
