package org.wyrdsekai.core.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import org.wyrdsekai.common.event.WorldEvent;

/**
 * Grounds curiosity in a real epistemic signal: how NOVEL a perceived event is.
 *
 * <p>The agent implicitly predicts "more of the familiar"; a perception that
 * violates that — a new speaker, a new topic, a new kind of event — is a positive
 * prediction error. Fed through {@code OracleDriveIntegration.applyPredictionError}
 * it spikes SEEKING ("what else is out there?"), while familiar repeats habituate
 * (the evaluator's deadzone). This is the difference between a surprise drive that's
 * only a steering-vector affect label and one grounded in the agent's own
 * experience (2026-06-02 — the missing half of the SEEKING grounding).
 *
 * <p>Bounded LRU recency map: a signature unseen in the recent window reads as
 * fully novel; repeats within the window decay toward familiar; evicted-then-seen
 * reads as mildly novel again (re-encountering something after a long gap).
 */
public final class NoveltyTracker {

    private final int capacity;
    private final LinkedHashMap<String, Integer> seen;

    public NoveltyTracker() { this(64); }

    public NoveltyTracker(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> e) {
                return size() > NoveltyTracker.this.capacity;
            }
        };
    }

    /**
     * Novelty of this signature in [0,1] and record it: 1.0 the first time seen,
     * decaying with repeats (0.5, 0.33, …) so habituation sets in. Null/blank → 0
     * (no novelty, no record).
     */
    public synchronized double observe(String signature) {
        if (signature == null || signature.isBlank()) return 0.0;
        int prior = seen.getOrDefault(signature, 0);
        seen.put(signature, prior + 1);
        return 1.0 / (1.0 + prior);
    }

    /** Coarse, stable signature for a perceived event — the "what kind of thing
     *  is this" the agent habituates to. New actor / new topic / new event-kind
     *  reads as novel; the same chatter repeated reads as familiar. */
    public static String signatureFor(WorldEvent e) {
        if (e == null) return null;
        String kind = e.getClass().getSimpleName();
        return switch (e) {
            case WorldEvent.Said s -> kind + "|" + nz(s.entityId()) + "|" + topic(s.text());
            case WorldEvent.Told t -> kind + "|" + nz(t.fromEntityId()) + "|" + topic(t.text());
            case WorldEvent.EntityEntered en -> kind + "|" + nz(en.entityId());
            default -> kind;
        };
    }

    /**
     * Signature for the agent's OWN output (a journal entry, reflection, search result it
     * surfaced). Distinct namespace ("produce|") from perception signatures, so feeding both
     * through one tracker never collides — yet shares the same "have I encountered this before"
     * recency space. The {@code 1/(1+priorSeen)} decay is the anti-wirehead guard: re-producing
     * the same content reads as familiar (≈0 novelty), so it can't fake satisfaction — only
     * genuinely new work registers (2026-06-02, the self-accreting-world half of the open-loop
     * fix). Finer-grained than perception (12 words vs 3) because two real journal entries that
     * open the same way must still read as distinct.
     */
    public static String signatureForProduction(String kind, String content) {
        if (content == null || content.isBlank()) return null;
        return "produce|" + (kind == null ? "" : kind) + "|" + gist(content, 12);
    }

    /** A short human-readable topic of produced content — for thread labels the agent can see. */
    public static String topicOf(String content) { return gist(content, 6); }

    /** First few normalized words — so a NEW topic from a familiar speaker still
     *  registers as novel, but the same line repeated does not. */
    private static String topic(String text) { return gist(text, 3); }

    /** First {@code n} normalized words of the text (lowercased, punctuation stripped). */
    private static String gist(String text, int n) {
        if (text == null || text.isBlank()) return "";
        var words = text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        var sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, words.length); i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
