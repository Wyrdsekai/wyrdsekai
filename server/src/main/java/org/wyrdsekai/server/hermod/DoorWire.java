package org.wyrdsekai.server.hermod;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.wyrdsekai.hermod.Mesh;
import org.wyrdsekai.hermod.SignedGrant;
import org.wyrdsekai.hermod.TaskEnvelope;
import org.wyrdsekai.hermod.TaskExecutor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The knock and the answer, on the wire. Hand-rolled DTOs (nullable
 * grant, no Optional in JSON) so the codec needs nothing beyond the
 * modules the server already ships. One request/reply per offer —
 * admission and result travel together, matching DoorProtocol.
 */
public final class DoorWire {

    // ISO-8601 instants and lenient reads: phones (Kotlin) speak this wire
    // too, and devices in one household update at different times.
    static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public record EnvelopeDto(
        String envelopeId, String householdId, String originDeviceId,
        String taskType, String dataDomain, String capabilityClass,
        Map<String, String> params, long tokenBudget,
        Instant issuedAt, Instant expiresAt,
        SignedGrant grant,            // nullable on the wire
        byte[] originSignature) {

        static EnvelopeDto of(TaskEnvelope e) {
            return new EnvelopeDto(e.envelopeId(), e.householdId(), e.originDeviceId(),
                e.taskType(), e.dataDomain(), e.capabilityClass(), e.params(),
                e.tokenBudget(), e.issuedAt(), e.expiresAt(),
                e.grant().orElse(null), e.originSignature());
        }

        TaskEnvelope toEnvelope() {
            return new TaskEnvelope(envelopeId, householdId, originDeviceId, taskType,
                dataDomain, capabilityClass, params, tokenBudget, issuedAt, expiresAt,
                Optional.ofNullable(grant), originSignature);
        }
    }

    /** completed=true carries the result; completed=false carries the decline reason. */
    public record AnswerDto(boolean completed, boolean ok, String output, String error, String declineReason) {

        static AnswerDto of(Mesh.DoorProtocol.Outcome outcome) {
            return switch (outcome) {
                case Mesh.DoorProtocol.Completed c ->
                    new AnswerDto(true, c.result().ok(), c.result().output(), c.result().error(), null);
                case Mesh.DoorProtocol.Declined d ->
                    new AnswerDto(false, false, null, null, d.reason());
            };
        }

        Mesh.DoorProtocol.Outcome toOutcome(String envelopeId) {
            if (!completed) {
                return new Mesh.DoorProtocol.Declined(declineReason == null ? "declined" : declineReason);
            }
            return new Mesh.DoorProtocol.Completed(ok
                ? TaskExecutor.TaskResult.ok(envelopeId, output == null ? "" : output)
                : TaskExecutor.TaskResult.fail(envelopeId, error == null ? "" : error));
        }
    }

    public static String doorSubject(String scopeId, String deviceId) {
        return "hh." + scopeId + ".hermod.door." + deviceId;
    }

    public static byte[] encodeEnvelope(TaskEnvelope e) throws Exception {
        return JSON.writeValueAsBytes(EnvelopeDto.of(e));
    }

    public static TaskEnvelope decodeEnvelope(byte[] b) throws Exception {
        return JSON.readValue(b, EnvelopeDto.class).toEnvelope();
    }

    public static byte[] encodeAnswer(Mesh.DoorProtocol.Outcome o) throws Exception {
        return JSON.writeValueAsBytes(AnswerDto.of(o));
    }

    public static Mesh.DoorProtocol.Outcome decodeAnswer(byte[] b, String envelopeId) throws Exception {
        return JSON.readValue(b, AnswerDto.class).toOutcome(envelopeId);
    }

    /** The server side of one knock: decode, offer to the own door, encode. */
    public static byte[] answer(byte[] request, Mesh.DoorProtocol ownDoor) {
        try {
            var envelope = decodeEnvelope(request);
            return encodeAnswer(ownDoor.offer(envelope));
        } catch (Exception e) {
            try {
                return encodeAnswer(new Mesh.DoorProtocol.Declined(
                    "malformed knock: " + e.getMessage()));
            } catch (Exception impossible) {
                return new byte[0];
            }
        }
    }
}
