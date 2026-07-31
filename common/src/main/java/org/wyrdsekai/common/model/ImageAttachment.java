package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * An image attachment sent with a message. Supports two transport modes:
 * <ul>
 *   <li>Inline base64 — for small images (phone screenshots, thumbnails)</li>
 *   <li>URL reference — for large images (uploaded to household storage)</li>
 * </ul>
 *
 * <p>Used in {@code C2SMessage.Say} and {@code WorldEvent.Said} to carry
 * image data through the wire protocol to the agent's vision pipeline.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImageAttachment(
    String id,
    String mimeType,
    @JsonProperty("data") String base64Data,   // base64-encoded image (for small images)
    String url,                                 // upload URL (for large images, nullable)
    Instant timestamp
) {

    /**
     * Create an attachment from inline base64 data.
     *
     * @param base64   base64-encoded image bytes
     * @param mimeType MIME type (e.g. "image/jpeg", "image/png")
     * @return a new ImageAttachment with a generated ID and current timestamp
     */
    public static ImageAttachment fromBase64(String base64, String mimeType) {
        return new ImageAttachment(
            UUID.randomUUID().toString().substring(0, 8),
            mimeType, base64, null, Instant.now());
    }

    /**
     * Create an attachment from an upload URL.
     *
     * @param url      URL of the uploaded image
     * @param mimeType MIME type (e.g. "image/jpeg", "image/png")
     * @return a new ImageAttachment with a generated ID and current timestamp
     */
    public static ImageAttachment fromUrl(String url, String mimeType) {
        return new ImageAttachment(
            UUID.randomUUID().toString().substring(0, 8),
            mimeType, null, url, Instant.now());
    }

    /** True if this attachment carries inline base64 data. */
    public boolean hasData() {
        return base64Data != null && !base64Data.isBlank();
    }

    /** True if this attachment carries a URL reference. */
    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }
}
