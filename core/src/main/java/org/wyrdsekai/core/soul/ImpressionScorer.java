package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.List;

/**
 * Converts emotional charge at encoding time into impression depth
 * for memory nodes. The amygdala-hippocampal analog (section 109.3):
 * higher emotional charge at encoding = deeper memory = slower decay.
 *
 * Formative impression detection (section 109.4): extremely high-charge
 * genuine experiences become load-bearing identity fragments that NEVER
 * get consolidated by the Forge. They encode the WHY behind behavior.
 */
public final class ImpressionScorer {

    /** Intensity threshold for formative classification. */
    private static final float FORMATIVE_INTENSITY = 0.8f;
    /** Confidence threshold for formative classification. */
    private static final float FORMATIVE_CONFIDENCE = 0.7f;

    private ImpressionScorer() {}

    /**
     * Score impression depth from emotional charge.
     * Non-significant charges get zero depth (normal decay).
     *
     * @param charge Emotional charge at the time of memory encoding
     * @return Impression depth (0.0-1.0), higher = slower decay
     */
    public static float score(EmotionalCharge charge) {
        if (!charge.isSignificant()) {
            return 0.0f;
        }
        return charge.intensity() * charge.confidence();
    }

    /**
     * Determine if this charge produces a formative impression.
     * Formative memories are load-bearing identity fragments (section 109.4):
     * - NEVER consolidated away by the Forge
     * - Always get their own dedicated soul fragment
     * - Encode the WHY behind behavioral patterns
     *
     * Requires: significant charge + very high intensity + high confidence.
     *
     * @param charge Emotional charge at encoding
     * @return true if this should be a formative memory
     */
    public static boolean isFormative(EmotionalCharge charge) {
        return charge.isSignificant()
            && charge.intensity() >= FORMATIVE_INTENSITY
            && charge.confidence() >= FORMATIVE_CONFIDENCE;
    }

    /**
     * Create a MemoryNode from event content with impression scoring.
     *
     * @param id       Memory identifier
     * @param content  Memory text content
     * @param keywords A-Mem keywords for retrieval
     * @param charge   Emotional charge at encoding
     * @return Appropriately weighted MemoryNode
     */
    public static MemoryNode encode(String id, String content,
                                     List<String> keywords,
                                     EmotionalCharge charge) {
        float depth = score(charge);
        boolean formative = isFormative(charge);
        float importance = formative ? 1.0f : Math.max(0.5f, depth);

        if (formative) {
            return MemoryNode.formative(id, content, keywords,
                charge.primaryEmotion(), depth);
        }

        return new MemoryNode(id, content, keywords, importance, depth,
            false, charge.primaryEmotion(), Instant.now(), 0, "en");
    }
}
