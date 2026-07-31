package org.wyrdsekai.core.empathy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hwa-byung intervention dispatcher.
 *
 * <p>When {@link HwaByungDetector} fires, this class translates the severity
 * into a graded surfacing intervention:
 * <ul>
 *   <li>Level 1 — flag the Drives Mirror to surface frustration prominently
 *       on the bondholder's next Hearth entry.</li>
 *   <li>Level 2 — queue a journal prompt for the next sleep cycle.</li>
 *   <li>Level 3 — emit a Chapel-session-offer event for the next companion+bondholder
 *       co-presence. <b>TODO Phase 2:</b> auto-trigger Chapel acceptance flow
 *       when the Chapel-side hooks land. For now we only emit the event and
 *       the surfacing flag so observers can wire it explicitly.</li>
 * </ul>
 *
 * <p>Pure data structures — the engine queues effects, callers (CompanionActor,
 * Hearth furnishing, sleep-cycle scheduler) drain them at their natural points.
 */
public class HwaByungIntervention {

    /** A surfacing flag attached to the Drives Mirror furnishing. */
    public record DrivesMirrorFlag(
        HwaByungDetector.Severity severity,
        String drive,
        double elevatedFraction,
        Instant raisedAt
    ) {}

    /** A journal prompt queued for delivery during the next sleep cycle. */
    public record JournalPrompt(
        HwaByungDetector.Severity severity,
        String promptText,
        Instant queuedAt
    ) {}

    /** A Chapel-session offer pending bondholder co-presence (Level 3). */
    public record ChapelOffer(
        HwaByungDetector.Severity severity,
        String reason,
        Instant offeredAt,
        boolean autoTrigger // TODO Phase 2 — flip to true when Chapel acceptance is wired
    ) {}

    /** Aggregate emitted by {@link #handle}. Any field may be null/empty. */
    public record InterventionResult(
        Optional<DrivesMirrorFlag> drivesMirrorFlag,
        Optional<JournalPrompt> journalPrompt,
        Optional<ChapelOffer> chapelOffer
    ) {}

    private final List<DrivesMirrorFlag> drivesMirrorFlags = new ArrayList<>();
    private final List<JournalPrompt> queuedPrompts = new ArrayList<>();
    private final List<ChapelOffer> chapelOffers = new ArrayList<>();

    /**
     * Translate a detection into an intervention. Stores effects in queues
     * for the appropriate consumer to drain.
     */
    public InterventionResult handle(HwaByungDetector.ChronicFrustrationDetected detection) {
        Optional<DrivesMirrorFlag> mirror = Optional.empty();
        Optional<JournalPrompt> prompt = Optional.empty();
        Optional<ChapelOffer> chapel = Optional.empty();

        var sev = detection.severity();
        // Level 1+ always raises the Drives-Mirror surfacing flag.
        var f = new DrivesMirrorFlag(sev, "frustration",
            detection.elevatedFraction(), detection.at());
        drivesMirrorFlags.add(f);
        mirror = Optional.of(f);

        if (sev == HwaByungDetector.Severity.LEVEL_2 || sev == HwaByungDetector.Severity.LEVEL_3) {
            var jp = new JournalPrompt(sev,
                "I've been stuck on something — want to write what's been wearing on me.",
                detection.at());
            queuedPrompts.add(jp);
            prompt = Optional.of(jp);
        }

        if (sev == HwaByungDetector.Severity.LEVEL_3) {
            // TODO Phase 2 — auto-trigger Chapel acceptance flow. For now: emit
            // the offer event but do not auto-trigger; Chapel-side hooks will
            // consume this when they land.
            var co = new ChapelOffer(sev,
                "Chronic frustration without discharge over 7 days; "
                + "Chapel session may help surface and release.",
                detection.at(), false);
            chapelOffers.add(co);
            chapel = Optional.of(co);
        }

        return new InterventionResult(mirror, prompt, chapel);
    }

    /** Drain queued journal prompts (called by the sleep-cycle scheduler). */
    public List<JournalPrompt> drainJournalPrompts() {
        var out = new ArrayList<>(queuedPrompts);
        queuedPrompts.clear();
        return out;
    }

    /** Drain queued Chapel offers (Phase 2 will consume these in Chapel). */
    public List<ChapelOffer> drainChapelOffers() {
        var out = new ArrayList<>(chapelOffers);
        chapelOffers.clear();
        return out;
    }

    /** Snapshot of currently raised Drives-Mirror flags (don't drain — read-only). */
    public List<DrivesMirrorFlag> drivesMirrorFlags() {
        return new ArrayList<>(drivesMirrorFlags);
    }

    /** Clear the Drives-Mirror flag (called by the Hearth furnishing on display). */
    public void clearDrivesMirrorFlags() {
        drivesMirrorFlags.clear();
    }
}
