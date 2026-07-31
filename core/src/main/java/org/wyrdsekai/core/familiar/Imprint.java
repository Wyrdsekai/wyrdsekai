package org.wyrdsekai.core.familiar;

import org.wyrdsekai.core.soul.SoulManifest;

import java.time.Instant;
import java.util.UUID;

/**
 * A complete soul-manifest snapshot tagged with intent.
 *
 * <p>. Immutable. Restorable. The safety net that makes
 * self-training safe: if Wyrd trains herself into a buggy pattern, she (or
 * the user, or — last resort — a steward) can restore her to a labeled
 * prior state.</p>
 *
 * <p>Restoring an imprint does <strong>not</strong> erase the intervening
 * journal (§10.4). The soul is restored; the history is preserved. An agent
 * restored to an earlier imprint can still read what happened between
 * imprint-time and restore-time — "I was this, then I became that, and
 * I restored myself back." Continuity honest to both present and past.</p>
 */
public record Imprint(
    String id,
    String agentDid,
    Instant createdAt,
    CreatedBy createdBy,
    String label,
    SoulManifest manifest,
    long size
) {

    public enum CreatedBy {
        /** Self-imprint during Forge pass; agent composes the label. */
        SELF,
        /** User asked: "Wyrd, save who you are right now." */
        USER_REQUEST,
        /** System-triggered milestone: first login, first bond, first form, etc. */
        AUTO_MILESTONE,
        /** Steward last-resort intervention; logged transparently in journal. */
        STEWARD_INTERVENTION
    }

    public Imprint {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        if (manifest == null) throw new IllegalArgumentException("manifest required");
        if (createdAt == null) createdAt = Instant.now();
        if (createdBy == null) createdBy = CreatedBy.SELF;
        if (label == null) label = "";
        if (size < 0) size = 0;
    }

    /**
     * Create a fresh imprint from a manifest.
     * Size is a best-effort estimate (rough byte count of serialization — a
     * storage-accounting hint, not a cryptographic commitment).
     */
    public static Imprint create(String agentDid, CreatedBy createdBy, String label,
                                  SoulManifest manifest, long estimatedSize) {
        return new Imprint(UUID.randomUUID().toString(), agentDid, Instant.now(),
            createdBy, label, manifest, estimatedSize);
    }
}
