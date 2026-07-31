package org.wyrdsekai.core.ingest;

import java.nio.charset.StandardCharsets;

/**
 * Passthrough extractor for text content types.
 * Handles text/plain, text/html, text/markdown, application/json.
 */
public class TextExtractor implements ContentExtractor {

    @Override
    public boolean canExtract(String mimeType) {
        return mimeType != null && (
            mimeType.startsWith("text/") ||
            "application/json".equals(mimeType) ||
            "application/xml".equals(mimeType)
        );
    }

    @Override
    public String extract(IngestContent content) {
        // Text content: return the text directly, or decode binary as UTF-8
        if (content.hasText()) return content.text();
        if (content.hasBinaryData()) {
            return new String(content.data(), StandardCharsets.UTF_8);
        }
        return null;
    }

    @Override
    public String name() {
        return "text-passthrough";
    }
}
