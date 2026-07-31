package org.wyrdsekai.core.ingest;

import java.util.Set;

/**
 * Result of processing content through the ingestion pipeline.
 *
 * @param contentId    the original content ID
 * @param extractedText the text extracted from the content
 * @param targets      where the content was routed
 * @param success      whether processing succeeded
 * @param error        error message if failed (null on success)
 */
public record IngestResult(
    String contentId,
    String extractedText,
    Set<IngestTarget> targets,
    boolean success,
    String error
) {
    public static IngestResult success(String contentId, String text, Set<IngestTarget> targets) {
        return new IngestResult(contentId, text, targets, true, null);
    }

    public static IngestResult failure(String contentId, String error) {
        return new IngestResult(contentId, null, Set.of(), false, error);
    }
}
