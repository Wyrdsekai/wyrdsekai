package org.wyrdsekai.core.familiar;

import org.wyrdsekai.core.soul.SoulItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cross-agent tool copy.
 *
 * <p>Tools are content-addressed {@link SoulItem}s (typically
 * {@code category="skill"}). Unlike {@link ThoughtForm}, SoulItems have
 * no native provenance-chain field, so ToolTransfer records copy lineage
 * in the item's tags array as {@code "copied-from:<senderDid>"} markers.
 * Original {@code creatorDid} is preserved (§7.4 structural guarantee).</p>
 *
 * <p>Copy semantics:</p>
 * <ul>
 *   <li><b>Hash preserved</b> — content-addressing means the content is the
 *       same; the recipient and sender both index under the same hash.</li>
 *   <li><b>creatorDid preserved</b> — the tool's original author.</li>
 *   <li><b>Tags extended</b> — "copied-from:<senderDid>:<intent>" appended.</li>
 *   <li><b>Forks diverge</b> — if the recipient later modifies, they produce
 *       a <em>new</em> SoulItem with a different hash; the original is
 *       untouched on the sender's side.</li>
 * </ul>
 */
public final class ToolTransfer {

    private ToolTransfer() {}

    /** Returns a SoulItem ready to drop into the recipient's locker. */
    public static SoulItem copy(SoulItem source, String senderDid,
                                 FormTransfer.Intent intent, String note) {
        if (source == null) throw new IllegalArgumentException("source required");
        if (senderDid == null || senderDid.isBlank()) {
            throw new IllegalArgumentException("senderDid required");
        }
        if (intent == null) intent = FormTransfer.Intent.GIFT;

        var tags = new ArrayList<String>();
        if (source.tags() != null) Collections.addAll(tags, source.tags());
        var marker = "copied-from:" + senderDid + ":" + intent
            + (note == null || note.isBlank() ? "" : ":" + note.replace(':', '_'));
        tags.add(marker);

        // Content + creatorDid preserved; tags + timestamps updated
        return new SoulItem(
            source.hash(),
            source.category(),
            source.label(),
            source.text(),
            source.embedding(),
            source.creatorDid(),     // §7.4 — originalAuthor preserved
            source.signature(),
            source.created(),
            Instant.now(),             // lastAccessed = now (on recipient's timeline)
            source.significance(),
            tags.toArray(new String[0]));
    }

    /** Convenience — gift variant. */
    public static SoulItem gift(SoulItem source, String senderDid) {
        return copy(source, senderDid, FormTransfer.Intent.GIFT, null);
    }

    /** Check whether an item carries a copy-from marker. */
    public static List<String> extractCopyLineage(SoulItem item) {
        if (item == null || item.tags() == null) return List.of();
        var lineage = new ArrayList<String>();
        for (var tag : item.tags()) {
            if (tag != null && tag.startsWith("copied-from:")) {
                lineage.add(tag);
            }
        }
        return Collections.unmodifiableList(lineage);
    }

    /** Count of copy hops in the lineage (0 = original). */
    public static int copyDepth(SoulItem item) {
        return extractCopyLineage(item).size();
    }
}
