package org.wyrdsekai.hermod;

import java.util.ArrayList;
import java.util.function.BiFunction;

/**
 * The origin-side loop: rank candidates, OFFER the envelope to each door
 * in turn, take the first completed result. A door is one round trip —
 * admission and execution answered together — so remote doors cost one
 * exchange, not two. Refusals are expected traffic. When nobody
 * capable-and-consented exists, the failure says so honestly
 * (capability-door pattern: "can't, and why").
 */
public final class Mesh {

    /** One knock: either a completed result, or a decline with a reason. */
    public interface DoorProtocol {
        sealed interface Outcome permits Completed, Declined {}
        record Completed(TaskExecutor.TaskResult result) implements Outcome {}
        record Declined(String reason) implements Outcome {}

        Outcome offer(TaskEnvelope envelope);
    }

    /** In-process door: the local gate decides, the local executor runs. */
    public static DoorProtocol local(AdmissionGate gate, TaskExecutor executor) {
        return envelope -> {
            var decision = gate.consider(envelope);
            return switch (decision.verdict()) {
                case ADMIT -> {
                    if (executor == null || !executor.handles(envelope.taskType())) {
                        yield new DoorProtocol.Declined("no executor for " + envelope.taskType());
                    }
                    yield new DoorProtocol.Completed(executor.execute(envelope));
                }
                case QUEUE, REFUSE -> new DoorProtocol.Declined(decision.reason());
            };
        };
    }

    /** A door that always declines with the given reason. */
    public static DoorProtocol closed(String reason) {
        return envelope -> new DoorProtocol.Declined(reason);
    }

    private final Router router;
    private final BiFunction<TaskEnvelope, Capability, DoorProtocol> doorOf;

    public Mesh(Router router, BiFunction<TaskEnvelope, Capability, DoorProtocol> doorOf) {
        this.router = router;
        this.doorOf = doorOf;
    }

    public TaskExecutor.TaskResult submit(TaskEnvelope e) {
        var remaining = new ArrayList<>(router.candidates(e));
        var refusals = new ArrayList<String>();
        while (true) {
            var next = router.place(e, remaining);
            if (next.isEmpty()) {
                return TaskExecutor.TaskResult.fail(e.envelopeId(),
                    refusals.isEmpty()
                        ? "no device advertises capability '" + e.capabilityClass() + "'"
                          + (e.requiresGrant() ? " with resident domain '" + e.dataDomain() + "'" : "")
                        : "all candidates declined: " + String.join("; ", refusals));
            }
            var target = next.get();
            remaining.remove(target);
            switch (doorOf.apply(e, target).offer(e)) {
                case DoorProtocol.Completed c -> { return c.result(); }
                case DoorProtocol.Declined d -> refusals.add(target.deviceId() + ": " + d.reason());
            }
        }
    }
}
