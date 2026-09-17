package org.wyrdsekai.core.body;

import java.time.Duration;

/**
 * One standard for describing any part of the body: what it is, how it says it is alive, whose
 * it is, and what she loses when it stops answering.
 *
 * @param id             stable id, {@code kind:name} by convention ("brain:llama-voice")
 * @param kind           the class of thing
 * @param name           the part in her terms ("voice brain", "the record", "kitchen camera")
 * @param owner          whose limb it is: hers, the household's, a person's
 * @param transport      how it is reached (a URL, a device path, a container name)
 * @param heartbeatEvery how often it promises to say it is alive; a part with no contract
 *                       cannot be attached
 * @param feltWeight     how present it is when fine and how loud its loss is
 * @param numbBehaviour  what she loses when it stops answering, in her own terms, first person
 * @param shedTier       what the reflex arena may do to it: "first", "last", "never"
 */
public record LimbDescriptor(String id, BodyKind kind, String name, String owner,
                             String transport, Duration heartbeatEvery, FeltWeight feltWeight,
                             String numbBehaviour, String shedTier) {

    public LimbDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("a part needs an id");
        if (kind == null) throw new IllegalArgumentException("a part needs a kind");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a part needs a name");
        if (heartbeatEvery == null || heartbeatEvery.isZero() || heartbeatEvery.isNegative()) {
            throw new IllegalArgumentException(
                "a part with no heartbeat contract cannot be attached: " + id);
        }
        if (feltWeight == null) feltWeight = FeltWeight.PRESENT;
        if (owner == null || owner.isBlank()) owner = "household";
        if (shedTier == null || shedTier.isBlank()) shedTier = "never";
    }

    /** Past this age without a heartbeat the part is numb: twice the promised interval. */
    public Duration numbAfter() {
        return heartbeatEvery.multipliedBy(2);
    }
}
