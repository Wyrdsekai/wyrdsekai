package org.wyrdsekai.core.agent;

import java.util.regex.Pattern;

/**
 * A scene-level read of the four cultural-relational dimensions (2026-06-02).
 *
 * <p>Grounding for the harmony/standing/amae/obligation tanks. Rather than a new
 * classifier head (the SetFit encoder was never trained on these), the perception
 * is read from the model that ALREADY learned it — the drive/voice models were
 * fine-tuned on the cultural-tank corpus. A scene-close inference rates how the
 * just-closed exchange landed; this record carries the four signed scores.
 *
 * <p>Fields are VALENCE in [-1, 1] (+1 went well, -1 went badly) — the 2026-06-02
 * live probe found the model reads the dimension reliably but as valence, not
 * "tank pressure", so we store valence and let the consumer invert:
 * <ul>
 *   <li><b>standing</b> (面子 / dignity): +1 clearly respected/recognized;
 *       -1 slighted, dismissed, disrespected.</li>
 *   <li><b>harmony</b> (和 / wa): +1 warmth / a rift eased; -1 conflict, friction, discord.</li>
 *   <li><b>amae</b>, <b>obligation</b>: present in the record but NOT populated by the
 *       scene appraisal (the probe found them unreliable as valence — they're
 *       relational-bookkeeping tanks awaiting event-grounding). Default 0.</li>
 * </ul>
 *
 * <p>The consumer ({@code CompanionActor.applyCulturalAppraisal}) INVERTS valence onto
 * the distress tanks: a negative-valence exchange aggravates the tank (it rises), a
 * positive one relieves it (it falls), conservatively (threshold + small gain) so a
 * fuzzy read can't manufacture suffering. {@link #parse(String)} is deliberately
 * forgiving: any field it can't read defaults to 0, so a garbled response moves nothing.
 */
public record CulturalAppraisal(double standing, double harmony, double amae, double obligation) {

    public static CulturalAppraisal neutral() {
        return new CulturalAppraisal(0.0, 0.0, 0.0, 0.0);
    }

    public boolean isNeutral() {
        return standing == 0.0 && harmony == 0.0 && amae == 0.0 && obligation == 0.0;
    }

    /**
     * Forgiving parse of a model's appraisal output. Looks for each named key
     * followed by a number (JSON {@code "standing": -0.4}, loose {@code standing=-0.4},
     * or {@code standing -0.4}); missing/unparseable → 0.0. Clamps to [-1, 1].
     * Never throws — a blank or nonsense input yields {@link #neutral()}.
     */
    public static CulturalAppraisal parse(String text) {
        if (text == null || text.isBlank()) return neutral();
        return new CulturalAppraisal(
            field(text, "standing"),
            field(text, "harmony"),
            field(text, "amae"),
            field(text, "obligation"));
    }

    private static double field(String text, String key) {
        // key, optional quote/colon/equals/whitespace, then a signed decimal.
        var m = Pattern.compile("\"?" + key + "\"?\\s*[:=]?\\s*(-?\\d*\\.?\\d+)",
            Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return 0.0;
        try {
            return clamp(Double.parseDouble(m.group(1)));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
