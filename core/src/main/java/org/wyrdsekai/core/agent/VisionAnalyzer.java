package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.model.ImageAttachment;

import java.util.List;

/**
 * Builds vision analysis context for the companion agent.
 * <p>
 * When a human sends a message with image attachments, VisionAnalyzer provides:
 * <ol>
 *   <li>A vision prompt for ToolInferRequest (sent to a vision-capable model)</li>
 *   <li>Context formatting for the analysis result (injected into identity inference)</li>
 *   <li>Graceful degradation when vision capability is unavailable</li>
 * </ol>
 * <p>
 * The vision model is a TOOL — the agent's identity stays on the small model, the
 * vision capability is delegated to a specialized model. Same pattern as
 * {@code ThinkDeeply} for coding or reasoning delegation.
 */
public final class VisionAnalyzer {

    private VisionAnalyzer() {}

    /**
     * Check whether a Said event's attachments contain any images.
     *
     * @param attachments nullable list from WorldEvent.Said
     * @return true if at least one image attachment is present with data or URL
     */
    public static boolean hasImages(List<ImageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return false;
        return attachments.stream().anyMatch(a -> a.hasData() || a.hasUrl());
    }

    /**
     * Get the first usable image from a list of attachments.
     *
     * @param attachments nullable list of attachments
     * @return the first attachment with data or URL, or null if none
     */
    public static ImageAttachment firstImage(List<ImageAttachment> attachments) {
        if (attachments == null) return null;
        return attachments.stream()
            .filter(a -> a.hasData() || a.hasUrl())
            .findFirst()
            .orElse(null);
    }

    /**
     * Build the vision prompt for a ToolInferRequest to a vision-capable model.
     * <p>
     * For base64 images, includes the data inline (most vision models accept this).
     * For URL images, includes the URL reference.
     *
     * @param image       the image attachment to analyze
     * @param userMessage the human's accompanying text (nullable)
     * @return a prompt string for the vision model
     */
    public static String buildVisionPrompt(ImageAttachment image, String userMessage) {
        var sb = new StringBuilder();
        sb.append("Analyze the following image");
        if (userMessage != null && !userMessage.isBlank()) {
            sb.append(". The user asks: ").append(userMessage);
        }
        sb.append("\n\n[Image: ").append(image.mimeType());
        if (image.hasData()) {
            // Include full base64 for vision models that accept inline data
            sb.append(", base64 data: ").append(image.base64Data());
        } else if (image.hasUrl()) {
            sb.append(", url: ").append(image.url());
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Build the context block that gets injected into the agent's identity inference
     * after a successful vision analysis.
     *
     * @param image    the original image attachment
     * @param analysis the vision model's analysis text
     * @return formatted context block for PromptAssembler's additionalContext
     */
    public static String buildVisionContext(ImageAttachment image, String analysis) {
        return "## Image Attachment\nThe human shared an image with their message.\n"
            + "[Vision analysis: " + analysis + "]";
    }

    /**
     * Build a degraded context block when vision capability is not available.
     * This informs the LLM that an image was shared but cannot be analyzed.
     *
     * @return context block indicating vision is unavailable
     */
    public static String buildVisionUnavailableContext() {
        return "## Image Attachment\n"
            + "The human shared an image with their message.\n"
            + "[Image shared but vision analysis unavailable]";
    }
}
