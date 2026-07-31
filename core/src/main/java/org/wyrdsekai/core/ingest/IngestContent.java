package org.wyrdsekai.core.ingest;

import java.time.Instant;
import java.util.Map;

/**
 * Raw content from an ingest source, before extraction.
 *
 * @param id         unique content ID
 * @param sourceType source type (image, voice, text, screenshot, clipboard)
 * @param mimeType   MIME type (image/jpeg, audio/wav, text/plain, etc.)
 * @param data       raw bytes (images, audio) or null for text-only
 * @param text       text content (for text/clipboard sources, or extracted text for others)
 * @param metadata   additional metadata (filename, duration, dimensions, etc.)
 * @param timestamp  when the content was captured
 */
public record IngestContent(
    String id,
    String sourceType,
    String mimeType,
    byte[] data,
    String text,
    Map<String, String> metadata,
    Instant timestamp
) {
    /** Convenience: text-only content. */
    public static IngestContent text(String id, String text) {
        return new IngestContent(id, "text", "text/plain", null, text,
            Map.of(), Instant.now());
    }

    /** Convenience: image content. */
    public static IngestContent image(String id, byte[] data, String mimeType) {
        return new IngestContent(id, "image", mimeType, data, null,
            Map.of(), Instant.now());
    }

    /** Convenience: voice recording. */
    public static IngestContent voice(String id, byte[] data, String mimeType, int durationMs) {
        return new IngestContent(id, "voice", mimeType, data, null,
            Map.of("durationMs", String.valueOf(durationMs)), Instant.now());
    }

    /** Convenience: clipboard content. */
    public static IngestContent clipboard(String id, String text) {
        return new IngestContent(id, "clipboard", "text/plain", null, text,
            Map.of(), Instant.now());
    }

    /** Whether this content has binary data (needs extraction). */
    public boolean hasBinaryData() {
        return data != null && data.length > 0;
    }

    /** Whether this content already has extracted text. */
    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
