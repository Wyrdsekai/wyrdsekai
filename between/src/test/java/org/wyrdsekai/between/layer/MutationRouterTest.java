package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MutationRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mutation_classification_correct() {
        // Mutations (require primary)
        assertThat(MutationRouter.isMutation("TakeObject")).isTrue();
        assertThat(MutationRouter.isMutation("DropObject")).isTrue();
        assertThat(MutationRouter.isMutation("CreateRoom")).isTrue();
        assertThat(MutationRouter.isMutation("AddExit")).isTrue();
        assertThat(MutationRouter.isMutation("SetBehaviorScript")).isTrue();
        assertThat(MutationRouter.isMutation("Quarantine")).isTrue();
        assertThat(MutationRouter.isMutation("Unquarantine")).isTrue();

        // Local (no primary needed)
        assertThat(MutationRouter.isMutation("SayInRoom")).isFalse();
        assertThat(MutationRouter.isMutation("EmoteInRoom")).isFalse();
        assertThat(MutationRouter.isMutation("LookRoom")).isFalse();
        assertThat(MutationRouter.isMutation("EnterRoom")).isFalse();
        assertThat(MutationRouter.isMutation("LeaveRoom")).isFalse();
        assertThat(MutationRouter.isMutation("Subscribe")).isFalse();
        assertThat(MutationRouter.isMutation("GetSnapshot")).isFalse();
    }

    @Test
    void clonable_items_do_not_require_primary() {
        // Clonable = true → not a unique take → no primary needed
        assertThat(MutationRouter.isTakeUnique(true)).isFalse();
        // Clonable = false → unique item → primary required
        assertThat(MutationRouter.isTakeUnique(false)).isTrue();
    }

    @Test
    void idempotency_keys_are_unique() {
        var key1 = MutationRouter.newIdempotencyKey();
        var key2 = MutationRouter.newIdempotencyKey();
        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).hasSize(36); // UUID format
    }

    @Test
    void forwarded_mutation_serialization() {
        var cmd = MAPPER.createObjectNode().put("objectName", "sword").put("entityId", "player-1");
        var mutation = new MutationRouter.ForwardedMutation(
            "TakeObject", "nexus", 42L, "key-123",
            cmd, "node-abc", Instant.now());

        assertThat(mutation.type()).isEqualTo("TakeObject");
        assertThat(mutation.roomId()).isEqualTo("nexus");
        assertThat(mutation.epoch()).isEqualTo(42L);
        assertThat(mutation.idempotencyKey()).isEqualTo("key-123");
        assertThat(mutation.sourceNodeId()).isEqualTo("node-abc");
    }

    @Test
    void mutation_result_serialization() {
        var success = new MutationRouter.MutationResult("key-1", true, null, 42L);
        assertThat(success.success()).isTrue();
        assertThat(success.epoch()).isEqualTo(42L);

        var failure = new MutationRouter.MutationResult("key-2", false, "Stale epoch", 43L);
        assertThat(failure.success()).isFalse();
        assertThat(failure.reason()).isEqualTo("Stale epoch");
    }

    // --- Durable dedup across primary handover (spec/tla/PrimaryFencing.tla, P3) ---

    @Test
    void broadcast_result_is_recorded_into_dedup_on_a_standby_node() {
        // A "standby" node (not yet primary) with no bridge / protocol wired.
        var standby = new MutationRouter(null, "node-standby", null);

        assertThat(standby.isDuplicate("k-1")).isFalse();

        // The primary applied mutation k-1 and gossiped the result on room.mutation/result.
        var resultPayload = MAPPER.valueToTree(
            new MutationRouter.MutationResult("k-1", true, null, 1L));
        standby.recordBroadcastResult(resultPayload);

        // The standby now holds the dedup entry — so when it later wins a primary
        // HANDOVER, a retry of k-1 (re-stamped at the new epoch) is recognised as a
        // duplicate instead of being applied a second time (closes NoDoubleApply).
        assertThat(standby.isDuplicate("k-1")).isTrue();
    }

    @Test
    void broadcast_result_recording_is_idempotent_and_null_tolerant() {
        var node = new MutationRouter(null, "node-a", null);

        var payload = MAPPER.valueToTree(
            new MutationRouter.MutationResult("k-dup", true, null, 7L));
        node.recordBroadcastResult(payload);
        node.recordBroadcastResult(payload);   // re-gossip / self-echo — no throw, still one entry
        assertThat(node.isDuplicate("k-dup")).isTrue();

        // Malformed / empty payloads are ignored, never throw.
        node.recordBroadcastResult(MAPPER.createObjectNode());
        node.recordBroadcastResult(MAPPER.nullNode());
        assertThat(node.isDuplicate("anything-else")).isFalse();
    }

    @Test
    void forwarded_mutation_json_roundtrip() throws Exception {
        var cmd = MAPPER.createObjectNode().put("test", true);
        var original = new MutationRouter.ForwardedMutation(
            "DropObject", "library", 100L, "uuid-test",
            cmd, "node-xyz", Instant.parse("2026-04-10T12:00:00Z"));

        var json = MAPPER.writeValueAsString(original);
        var deserialized = MAPPER.readValue(json, MutationRouter.ForwardedMutation.class);

        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.roomId()).isEqualTo(original.roomId());
        assertThat(deserialized.epoch()).isEqualTo(original.epoch());
        assertThat(deserialized.idempotencyKey()).isEqualTo(original.idempotencyKey());
        assertThat(deserialized.sourceNodeId()).isEqualTo(original.sourceNodeId());
    }
}
