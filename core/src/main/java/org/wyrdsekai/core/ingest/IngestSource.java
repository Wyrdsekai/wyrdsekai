package org.wyrdsekai.core.ingest;

/**
 * Source of content for the ingestion pipeline.
 *
 * <p>Implementations provide raw content from various sources:
 * phone camera photos, voice recordings, screenshots, clipboard paste,
 * text input. Each source produces {@link IngestContent} that the pipeline
 * extracts and routes to Oracle, Study, or Library.</p>
 */
public interface IngestSource {

    /** Source type identifier (e.g., "image", "voice", "text", "screenshot", "clipboard"). */
    String type();

    /** Whether this source is currently available on the platform. */
    boolean isAvailable();
}
