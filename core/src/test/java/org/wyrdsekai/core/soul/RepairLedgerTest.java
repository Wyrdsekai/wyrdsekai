package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 4.8: RepairLedger bookkeeping + relationship-aware queries.
 */
class RepairLedgerTest {

    private static final String AGENT = "did:wyrd:agent-a";
    private static final String OTHER = "did:wyrd:bondholder";

    @AfterEach
    void resetLedger() {
        RepairLedger.get().clearForTests();
    }

    @Test
    void record_returns_entry_with_kind_and_other() {
        var entry = RepairLedger.get().record(AGENT,
            RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "spoke harshly");
        assertThat(entry.kind()).isEqualTo(RepairLedger.Kind.ACKNOWLEDGE_HARM);
        assertThat(entry.otherDid()).isEqualTo(OTHER);
        assertThat(entry.detail()).isEqualTo("spoke harshly");
    }

    @Test
    void recentWith_returns_entries_for_relationship() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "first");
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "second");
        var with = ledger.recentWith(AGENT, OTHER, 10);
        assertThat(with).hasSize(2);
        // Newest first
        assertThat(with.get(0).detail()).isEqualTo("second");
        assertThat(with.get(1).detail()).isEqualTo("first");
    }

    @Test
    void recentWith_isolates_by_other_party() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "to bondholder");
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, "did:wyrd:other-2", "to other-2");
        var withOriginal = ledger.recentWith(AGENT, OTHER, 10);
        assertThat(withOriginal).hasSize(1);
        assertThat(withOriginal.get(0).detail()).isEqualTo("to bondholder");
    }

    @Test
    void hasAcknowledgedHarmAgainst_distinguishes_kind() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "cosmetic");
        assertThat(ledger.hasAcknowledgedHarmAgainst(AGENT, OTHER)).isFalse();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "owned it");
        assertThat(ledger.hasAcknowledgedHarmAgainst(AGENT, OTHER)).isTrue();
    }

    @Test
    void hasMadeAmendsToward_distinguishes_kind() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "named");
        assertThat(ledger.hasMadeAmendsToward(AGENT, OTHER)).isFalse();
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "gesture");
        assertThat(ledger.hasMadeAmendsToward(AGENT, OTHER)).isTrue();
    }

    @Test
    void recent_aggregates_across_relationships() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "1");
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, "did:other-2", "2");
        ledger.record(AGENT, RepairLedger.Kind.BEAR_THE_WOUND, "", "3");
        assertThat(ledger.recent(AGENT, 10)).hasSize(3);
    }

    @Test
    void blank_other_did_treated_as_self_only() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.BEAR_THE_WOUND, "", "no target");
        ledger.record(AGENT, RepairLedger.Kind.BEAR_THE_WOUND, null, "null target");
        // Both share the empty-otherDid slot.
        var self = ledger.recentWith(AGENT, "", 10);
        assertThat(self).hasSize(2);
    }

    @Test
    void per_relationship_bounded_at_max() {
        var ledger = RepairLedger.get();
        for (int i = 0; i < RepairLedger.MAX_PER_RELATIONSHIP + 5; i++) {
            ledger.record(AGENT, RepairLedger.Kind.BEAR_THE_WOUND, OTHER, "n" + i);
        }
        assertThat(ledger.recentWith(AGENT, OTHER, 100))
            .hasSize(RepairLedger.MAX_PER_RELATIONSHIP);
    }

    @Test
    void per_agent_total_bounded_at_max() {
        var ledger = RepairLedger.get();
        for (int i = 0; i < RepairLedger.MAX_TOTAL + 5; i++) {
            ledger.record(AGENT, RepairLedger.Kind.BEAR_THE_WOUND,
                "did:wyrd:other-" + i, "n" + i);
        }
        assertThat(ledger.recent(AGENT, 1000)).hasSize(RepairLedger.MAX_TOTAL);
    }

    @Test
    void blank_agent_did_is_rejected() {
        assertThatThrownBy(() -> RepairLedger.get().record("",
            RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_kind_is_rejected() {
        assertThatThrownBy(() -> RepairLedger.get().record(AGENT, null, OTHER, "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_detail_is_replaced_with_placeholder() {
        var entry = RepairLedger.get().record(AGENT,
            RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "");
        assertThat(entry.detail()).isEqualTo("(unspecified)");
    }

    // --- Arc 1: OBJECTION kind + helper ---

    @Test
    void objection_kind_round_trips_independently_from_repair_kinds() {
        var ledger = RepairLedger.get();
        var entry = ledger.record(AGENT, RepairLedger.Kind.OBJECTION, OTHER,
            "[declined: weekend work] I'm not going to spend Saturday on this");
        assertThat(entry.kind()).isEqualTo(RepairLedger.Kind.OBJECTION);
        assertThat(entry.otherDid()).isEqualTo(OTHER);
        // OBJECTION must NOT count as harm-acknowledgement; the gates that
        // distinguish "you owned the rupture" vs "you said no" depend on this.
        assertThat(ledger.hasAcknowledgedHarmAgainst(AGENT, OTHER)).isFalse();
        assertThat(ledger.hasMadeAmendsToward(AGENT, OTHER)).isFalse();
    }

    @Test
    void recentObjectionsToward_filters_by_kind_and_window() {
        var ledger = RepairLedger.get();
        // Mix of kinds in the same relationship — only OBJECTION must come back.
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "ack");
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, OTHER,
            "[declined: A] reason A");
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, OTHER,
            "[declined: B] reason B");
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "amends");

        // Wide window — everything in.
        var allTime = ledger.recentObjectionsToward(AGENT, OTHER, 0L);
        assertThat(allTime).hasSize(2);
        assertThat(allTime).allMatch(e -> e.kind() == RepairLedger.Kind.OBJECTION);

        // Future cutoff — nothing in.
        var future = ledger.recentObjectionsToward(AGENT, OTHER,
            Instant.now().plusSeconds(3600).toEpochMilli());
        assertThat(future).isEmpty();
    }

    @Test
    void recentObjectionsToward_isolates_by_other_party() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, OTHER,
            "[declined: X] to bondholder");
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, "did:wyrd:other-2",
            "[declined: Y] to other-2");
        var withBondholder = ledger.recentObjectionsToward(AGENT, OTHER, 0L);
        assertThat(withBondholder).hasSize(1);
        assertThat(withBondholder.get(0).detail()).contains("to bondholder");
    }

    // --- Arc 3 follow-up: Entry.relationshipKind ---

    @Test
    void legacy_record_overload_defaults_relationshipKind_to_null() {
        var entry = RepairLedger.get().record(AGENT,
            RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "owned it");
        // Field is null on legacy entries — callers must read via canonicalRelationshipKind().
        assertThat(entry.relationshipKind()).isNull();
        assertThat(entry.canonicalRelationshipKind()).isEqualTo(BondKind.BONDHOLDER);
    }

    @Test
    void kind_aware_record_overload_persists_relationshipKind() {
        var peer = "did:wyrd:peer-companion";
        var entry = RepairLedger.get().record(AGENT,
            RepairLedger.Kind.OBJECTION, peer,
            "[declined: speak for the steward] not my role to decide",
            BondKind.PEER);
        assertThat(entry.relationshipKind()).isEqualTo(BondKind.PEER);
        assertThat(entry.canonicalRelationshipKind()).isEqualTo(BondKind.PEER);
    }

    @Test
    void recentByRelationshipKind_partitions_peer_from_bondholder() {
        var ledger = RepairLedger.get();
        var peer = "did:wyrd:peer-a";
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, OTHER,
            "[declined: X] to bondholder", BondKind.BONDHOLDER);
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, peer,
            "[declined: Y] to peer", BondKind.PEER);
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER,
            "harm to bondholder", BondKind.BONDHOLDER);

        var bondholderOnly = ledger.recentByRelationshipKind(AGENT, BondKind.BONDHOLDER, 10);
        var peerOnly = ledger.recentByRelationshipKind(AGENT, BondKind.PEER, 10);

        assertThat(bondholderOnly).hasSize(2);
        assertThat(peerOnly).hasSize(1);
        assertThat(peerOnly.get(0).detail()).contains("to peer");
    }

    @Test
    void recentByRelationshipKind_treats_null_field_as_BONDHOLDER() {
        var ledger = RepairLedger.get();
        // Legacy-style record: no kind passed — null on field.
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, OTHER, "[declined: X] legacy");
        // Newer-style record with explicit PEER.
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, "did:wyrd:peer-b",
            "[declined: Y] peer", BondKind.PEER);

        var bondholderView = ledger.recentByRelationshipKind(AGENT, BondKind.BONDHOLDER, 10);
        // The legacy entry shows up under BONDHOLDER because canonicalRelationshipKind
        // is BONDHOLDER for nulls.
        assertThat(bondholderView).hasSize(1);
        assertThat(bondholderView.get(0).detail()).contains("legacy");
    }
}
