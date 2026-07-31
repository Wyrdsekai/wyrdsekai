package org.wyrdsekai.core.familiar;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-agent thought-form copy.
 *
 * <p>Copying a form is a <strong>social act</strong>: the source's identity
 * persists in the provenance chain, but the copy is a <em>fork</em> — from
 * here on, the two forms diverge. Updates to the source do not propagate;
 * the recipient owns their copy and may revise or retire it freely.</p>
 *
 * <p>Copy semantics:</p>
 * <ul>
 *   <li><b>New id</b> — distinct FamilyLocker record, avoids collision.</li>
 *   <li><b>Name preserved</b> — the recipient may rename later via revise,
 *       but the initial copy keeps the known name.</li>
 *   <li><b>Version pinned</b> — copy records the source's version at copy
 *       time (§7.3). Recipient's own revisions bump from there.</li>
 *   <li><b>Provenance extended</b> — {@link Provenance.Action#COPIED_FROM}
 *       edit appended, naming the recipient. {@code originalAuthor}
 *       preserved (§7.4 structural guarantee).</li>
 *   <li><b>Counters reset</b> — {@code summonCount / successCount /
 *       failureCount / bondCharge} all start at zero for the recipient.
 *       Usage stats are per-copy.</li>
 * </ul>
 *
 * <p>Intentionally non-static is the <b>acceptance step</b>: calling
 * {@link #copy} yields a new ThoughtForm, but the caller must still
 * {@link org.wyrdsekai.core.soul.FamilyLocker#shapeThoughtForm store it}
 * in the recipient's locker. That keeps authorization checks at the
 * locker boundary (the recipient DID must be authorized to write into
 * their own locker).</p>
 */
public final class FormTransfer {

    private FormTransfer() {}

    /** Reason for the copy, recorded in provenance. */
    public enum Intent {
        GIFT,        // direct give_copy — no price
        TEACHING,    // agent explicitly teaching another
        PURCHASE,    // via Trading Post
        INHERIT      // promotion ceremony (§17.2 stage 4)
    }

    /**
     * Produce a copy of {@code source} owned by {@code recipientDid}.
     * The returned form is freshly-id'd, version-pinned, and has provenance
     * extended with a COPIED_FROM edit.
     *
     * @param source       form being copied
     * @param recipientDid DID of the agent receiving the copy
     * @param intent       why the copy is being made — recorded in provenance
     * @param note         optional human-readable note (e.g. "gift from wyrd")
     * @throws IllegalArgumentException if source is null or recipientDid is blank
     */
    public static ThoughtForm copy(ThoughtForm source, String recipientDid,
                                    Intent intent, String note) {
        if (source == null) throw new IllegalArgumentException("source required");
        if (recipientDid == null || recipientDid.isBlank()) {
            throw new IllegalArgumentException("recipientDid required");
        }
        if (intent == null) intent = Intent.GIFT;

        var combinedNote = note == null || note.isBlank()
            ? "copied from " + source.provenance().currentOwner() + " (" + intent + ")"
            : intent + ": " + note;

        var newProvenance = source.provenance().append(new Provenance.Edit(
            recipientDid, Provenance.Action.COPIED_FROM, Instant.now(), combinedNote));

        return new ThoughtForm(
            UUID.randomUUID().toString(),       // new id — fork, not alias
            source.name(),                      // name carries over
            source.version(),                   // §7.3 pinned at copy time
            newProvenance,
            source.systemPrompt(),
            source.toolSurface(),
            source.defaultTanks(),
            source.maxTanks(),
            source.maxTrials(),
            source.maxNestDepth(),
            source.evalCriteria(),
            Instant.now(),                      // createdAt on the recipient's timeline
            Instant.now(),                      // revisedAt = createdAt
            0, 0, 0,                            // counters reset (§7.2 forks diverge)
            0.0f                                // bondCharge starts at 0 for new owner
        );
    }

    /** Convenience — {@link #copy} with intent = GIFT and no note. */
    public static ThoughtForm gift(ThoughtForm source, String recipientDid) {
        return copy(source, recipientDid, Intent.GIFT, null);
    }
}
