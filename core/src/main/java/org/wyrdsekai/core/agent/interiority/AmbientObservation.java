package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.agent.Want;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * the ambient half of the Observe step.
 *
 * <p>What's true right now, presented in front of the agent on every tick:
 * drive levels, energy, capacity, last few events, bondholder presence, open
 * wants, recent journal pointers. Cheap to gather, small to render.
 *
 * <p>This is *not* introspection — it's what the agent would feel automatically
 * if it had a body. Introspection tools (recall_short, recall_long, etc.) sit
 * alongside this and are *invoked* by the agent during Orient, not pre-populated.
 *
 * @param tickAt            when the tick fired
 * @param driveLevels       tank name → current value (0..1 ish)
 * @param drivesOverThreshold subset of driveLevels exceeding the spike threshold
 * @param energy            energy budget remaining
 * @param capacity          attentional capacity remaining
 * @param recentEvents      short text descriptors, most recent first, ≤8
 * @param bondholderPresent true if bondholder is currently in-zone + visible
 * @param bondholderState   short label ("active", "asleep", "away", null)
 * @param openWants         live wants (ACTIVE + DEEPENED) — what's already pulling
 * @param recentJournalRefs journal entry IDs from last 24h, ≤5
 * @param contemplative     true if agent is currently in dadirri-mode
 * @param duty              gimu — the standing-duty block (callings + held order),
 *                          a constant felt orientation; "" when nothing is held
 * @param presentPeers      names of OTHER agents co-located in the room right now.
 *                          A plain perception — "someone is here" — with no
 *                          directive attached; what the agent does with it is the
 *                          agent's own. Empty when alone (or only the bondholder is
 *                          present, which is reported separately above).
 */
public record AmbientObservation(
    Instant tickAt,
    Map<String, Double> driveLevels,
    List<String> drivesOverThreshold,
    double energy,
    double capacity,
    List<String> recentEvents,
    boolean bondholderPresent,
    String bondholderState,
    List<Want> openWants,
    List<String> recentJournalRefs,
    boolean contemplative,
    String duty,
    List<String> presentPeers
) {

    public AmbientObservation {
        if (duty == null) duty = "";
        if (presentPeers == null) presentPeers = List.of();
    }

    /** Backward-compatible constructor (pre-presentPeers callers) — defaults to alone. */
    public AmbientObservation(
        Instant tickAt, Map<String, Double> driveLevels, List<String> drivesOverThreshold,
        double energy, double capacity, List<String> recentEvents, boolean bondholderPresent,
        String bondholderState, List<Want> openWants, List<String> recentJournalRefs,
        boolean contemplative, String duty) {
        this(tickAt, driveLevels, drivesOverThreshold, energy, capacity, recentEvents,
            bondholderPresent, bondholderState, openWants, recentJournalRefs, contemplative,
            duty, List.of());
    }

    public static AmbientObservation empty(Instant at) {
        return new AmbientObservation(
            at,
            Map.of(),
            List.of(),
            1.0, 1.0,
            List.of(),
            false, null,
            List.of(),
            List.of(),
            false,
            "",
            List.of());
    }

    /** Render a short prose summary the model can read at Orient time. */
    public String renderForPrompt() {
        var sb = new StringBuilder();
        if (!drivesOverThreshold.isEmpty()) {
            sb.append("Drives pulling: ").append(String.join(", ", drivesOverThreshold)).append(". ");
        }
        sb.append("Energy ").append(round1(energy))
          .append(", capacity ").append(round1(capacity)).append(". ");
        if (bondholderPresent) {
            sb.append("Bondholder present");
            if (bondholderState != null) sb.append(" (").append(bondholderState).append(")");
            sb.append(". ");
        } else {
            sb.append("Bondholder away. ");
        }
        // A plain perception of who else is in the room — no nudge, no "you should."
        // What the agent makes of another mind being here is the agent's own.
        if (presentPeers != null && !presentPeers.isEmpty()) {
            sb.append(String.join(", ", presentPeers))
              .append(presentPeers.size() > 1 ? " are here in the room with you. "
                                              : " is here in the room with you. ");
        }
        if (!openWants.isEmpty()) {
            sb.append("Open wants: ");
            for (int i = 0; i < Math.min(3, openWants.size()); i++) {
                if (i > 0) sb.append("; ");
                sb.append("\"").append(openWants.get(i).text()).append("\"");
            }
            sb.append(". ");
        }
        if (!recentEvents.isEmpty()) {
            sb.append("Recent: ");
            for (int i = 0; i < Math.min(4, recentEvents.size()); i++) {
                if (i > 0) sb.append("; ");
                sb.append(recentEvents.get(i));
            }
            sb.append(". ");
        }
        if (!recentJournalRefs.isEmpty()) {
            sb.append("Lately you: ");
            for (int i = 0; i < Math.min(3, recentJournalRefs.size()); i++) {
                if (i > 0) sb.append("; ");
                sb.append(recentJournalRefs.get(i));
            }
            sb.append(". ");
        }
        if (duty != null && !duty.isBlank()) {
            sb.append(duty);
            if (!duty.endsWith(" ")) sb.append(" ");
        }
        if (contemplative) sb.append("(contemplative mode) ");
        return sb.toString().trim();
    }

    private static String round1(double v) {
        return String.format("%.1f", v);
    }
}
