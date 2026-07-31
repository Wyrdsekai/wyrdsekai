package org.wyrdsekai.core.ingest;

/**
 * Extracts text from raw content (OCR for images, transcription for audio).
 *
 * <p>Implementations are platform-specific:
 * <ul>
 *   <li>Android: MLKit OCR, Whisper via ONNX</li>
 *   <li>iOS: Vision framework OCR, Whisper via CoreML</li>
 *   <li>Server: Tesseract OCR, Whisper server</li>
 * </ul>
 */
public interface ContentExtractor {

    /** Whether this extractor can handle the given MIME type. */
    boolean canExtract(String mimeType);

    /**
     * Extract text from content.
     *
     * @param content the raw content to extract from
     * @return extracted text, or null if extraction failed
     */
    String extract(IngestContent content);

    /** Extractor display name (for logging). */
    String name();
}
