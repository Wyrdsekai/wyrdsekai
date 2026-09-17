package org.wyrdsekai.core.body;

import java.time.Duration;
import java.time.Instant;

/**
 * One row of the map: a descriptor plus where the part is in its life.
 *
 * @param lastHeartbeat when it last said it was alive (an attach counts as the first one)
 * @param lastDetail    what it said last, if anything (a gauge reading, an error)
 * @param numbSince     when it stopped answering: the last heartbeat heard, not when we noticed
 * @param lastUsed      when it last did work for her (a brain that answers /health but has
 *                      served nothing for a month is the September failure)
 */
public record BodyPart(LimbDescriptor descriptor, PartState state, Instant firstAttached,
                       Instant lastHeartbeat, String lastDetail, Instant numbSince,
                       Instant goneAt, String goneBy, Instant lastUsed) {

    public String id() { return descriptor.id(); }
    public String name() { return descriptor.name(); }
    public BodyKind kind() { return descriptor.kind(); }

    /** How long it has been quiet, or zero when it is answering. */
    public Duration numbFor(Instant now) {
        if (state == PartState.ATTACHED || numbSince == null) return Duration.ZERO;
        var end = state == PartState.GONE && goneAt != null ? goneAt : now;
        var d = Duration.between(numbSince, end);
        return d.isNegative() ? Duration.ZERO : d;
    }

    /** How long since it last answered, or null when it never has. */
    public Duration heartbeatAge(Instant now) {
        return lastHeartbeat == null ? null : Duration.between(lastHeartbeat, now);
    }

    BodyPart withState(PartState s) {
        return new BodyPart(descriptor, s, firstAttached, lastHeartbeat, lastDetail,
            numbSince, goneAt, goneBy, lastUsed);
    }
}
