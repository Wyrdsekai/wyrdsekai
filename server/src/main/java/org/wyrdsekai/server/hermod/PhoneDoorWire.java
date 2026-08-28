package org.wyrdsekai.server.hermod;

import com.fasterxml.jackson.databind.JsonNode;
import org.wyrdsekai.hermod.TaskEnvelope;
import org.wyrdsekai.hermod.Mesh;

import java.util.List;

/**
 * The zone⇄phone leg of a proxied hermod door: JSON text messages over
 * the phone's authenticated WebSocket. Reuses DoorWire's envelope and
 * answer DTOs so the mesh wire format has exactly one definition; this
 * class only adds the framing ({@code type} discriminator + knockId
 * correlation) and the heartbeat.
 *
 * The heartbeat deliberately carries NO identity fields — the zone
 * stamps deviceId/householdId/advertisedAt from the pairing record, so
 * a phone cannot advertise as anyone but itself.
 */
public final class PhoneDoorWire {

    /** zone→phone on connect: the identity the zone will stamp for you. */
    public record Hello(String type, String deviceId, String householdId) {
        public static Hello of(String deviceId, String householdId) {
            return new Hello("hello", deviceId, householdId);
        }
    }

    /** zone→phone: an errand at the door. */
    public record Knock(String type, String knockId, DoorWire.EnvelopeDto envelope) {
        public static Knock of(String knockId, TaskEnvelope e) {
            return new Knock("knock", knockId, DoorWire.EnvelopeDto.of(e));
        }
    }

    /** phone→zone: the answer to one knock. */
    public record Answer(String type, String knockId, DoorWire.AnswerDto answer) {
    }

    /** phone→zone: battery-truth advertisement (identity stamped by the zone). */
    public record Heartbeat(
        String type, String capabilityClass, List<String> models,
        List<String> residentDataDomains, boolean charging, boolean idle,
        double loadFactor) {
    }

    public static String encode(Object message) {
        try {
            return DoorWire.JSON.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("phone door encode failed", e);
        }
    }

    /** Peek at the discriminator; empty string when unreadable. */
    public static String typeOf(String json) {
        try {
            JsonNode n = DoorWire.JSON.readTree(json).get("type");
            return n == null ? "" : n.asText("");
        } catch (Exception e) {
            return "";
        }
    }

    public static Answer decodeAnswer(String json) throws Exception {
        return DoorWire.JSON.readValue(json, Answer.class);
    }

    public static Heartbeat decodeHeartbeat(String json) throws Exception {
        return DoorWire.JSON.readValue(json, Heartbeat.class);
    }

    static Mesh.DoorProtocol.Outcome outcomeOf(Answer a, String envelopeId) {
        if (a.answer() == null) {
            return new Mesh.DoorProtocol.Declined("phone sent an empty answer");
        }
        return a.answer().toOutcome(envelopeId);
    }

    private PhoneDoorWire() {
    }
}
