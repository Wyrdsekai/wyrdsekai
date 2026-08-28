package org.wyrdsekai.hermod;

import java.util.List;
import java.util.Optional;

/**
 * Every device runs one. It routes ONLY its own originating requests,
 * using the gossiped (eventually consistent) capability table. There is
 * no central router and no placement lock: proposals go to a device's
 * AdmissionGate, which may refuse, and the router then tries the next
 * candidate.
 *
 * Contract (survives OSS extraction unchanged): hermod places WORK.
 * Presence — who is *someone* somewhere — is never a routable thing.
 */
public interface Router {

    /** Rank candidate devices for this envelope from the local view. */
    List<Capability> candidates(TaskEnvelope envelope);

    /** Choose the next candidate to propose to, if any remain. */
    Optional<Capability> place(TaskEnvelope envelope, List<Capability> remaining);
}
