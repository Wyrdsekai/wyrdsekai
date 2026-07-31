package org.wyrdsekai.core.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Multi-modal ingestion pipeline that processes content from various sources
 * (camera, voice, screenshots, clipboard, text) through extractors and routes
 * to appropriate targets (Oracle, Study, Library, agent context).
 *
 * <p>Processing flow:
 * <ol>
 *   <li>Content arrives from an {@link IngestSource} as {@link IngestContent}</li>
 *   <li>If content has binary data, find a matching {@link ContentExtractor} and extract text</li>
 *   <li>Route the extracted text to configured {@link IngestTarget}s</li>
 *   <li>Notify listeners of the result</li>
 * </ol>
 *
 * <p>Thread-safe. Extractors and routers are registered at startup; content can be
 * submitted from any thread (phone share intents, API calls, etc.).</p>
 */
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final List<ContentExtractor> extractors = new CopyOnWriteArrayList<>();
    private final Map<IngestTarget, Consumer<IngestEvent>> routers = new LinkedHashMap<>();
    private final List<Consumer<IngestResult>> listeners = new CopyOnWriteArrayList<>();

    /** Global singleton. */
    private static volatile IngestPipeline instance;

    /** Initialize global instance. */
    public static IngestPipeline init() {
        instance = new IngestPipeline();
        return instance;
    }

    /** Get global instance (null if not initialized). */
    public static IngestPipeline get() {
        return instance;
    }

    /**
     * Event delivered to a target router.
     *
     * @param contentId  original content ID
     * @param sourceType where the content came from
     * @param text       extracted text
     * @param metadata   original content metadata
     * @param userId     user who submitted the content (nullable)
     */
    public record IngestEvent(
        String contentId,
        String sourceType,
        String text,
        Map<String, String> metadata,
        String userId
    ) {}

    /**
     * Register a content extractor (OCR, transcription, etc.).
     */
    public void registerExtractor(ContentExtractor extractor) {
        extractors.add(extractor);
        log.info("Registered content extractor: {}", extractor.name());
    }

    /**
     * Register a target router. When content matches this target,
     * the router callback is invoked with the extracted event.
     */
    public void registerRouter(IngestTarget target, Consumer<IngestEvent> router) {
        routers.put(target, router);
        log.debug("Registered ingest router for target: {}", target);
    }

    /**
     * Register a result listener (for UI updates, logging, etc.).
     */
    public void addListener(Consumer<IngestResult> listener) {
        listeners.add(listener);
    }

    /**
     * Process content through the pipeline.
     *
     * @param content the raw content to ingest
     * @param targets where to route the extracted content
     * @param userId  the user submitting the content (nullable)
     * @return processing result
     */
    public IngestResult process(IngestContent content, Set<IngestTarget> targets, String userId) {
        log.debug("Ingesting content: id={}, source={}, mime={}, hasData={}, hasText={}",
            content.id(), content.sourceType(), content.mimeType(),
            content.hasBinaryData(), content.hasText());

        String extractedText = content.text();

        // Step 1: Extract text from binary content if needed
        if (content.hasBinaryData() && !content.hasText()) {
            extractedText = extract(content);
            if (extractedText == null || extractedText.isBlank()) {
                var result = IngestResult.failure(content.id(),
                    "No extractor available for " + content.mimeType());
                notifyListeners(result);
                return result;
            }
        }

        if (extractedText == null || extractedText.isBlank()) {
            var result = IngestResult.failure(content.id(), "No text content to ingest");
            notifyListeners(result);
            return result;
        }

        // Step 2: Route to targets
        var event = new IngestEvent(
            content.id(), content.sourceType(), extractedText,
            content.metadata(), userId);

        var routedTargets = EnumSet.noneOf(IngestTarget.class);
        for (var target : targets) {
            var router = routers.get(target);
            if (router != null) {
                try {
                    router.accept(event);
                    routedTargets.add(target);
                    log.debug("Routed content {} to {}", content.id(), target);
                } catch (Exception e) {
                    log.warn("Failed to route content {} to {}: {}",
                        content.id(), target, e.getMessage());
                }
            } else {
                log.debug("No router for target {} — skipping", target);
            }
        }

        var result = IngestResult.success(content.id(), extractedText, routedTargets);
        notifyListeners(result);
        log.info("Ingested content {}: {} chars, routed to {}",
            content.id(), extractedText.length(), routedTargets);
        return result;
    }

    /**
     * Convenience: process text content, auto-detect targets.
     */
    public IngestResult processText(String text, String userId) {
        var content = IngestContent.text(UUID.randomUUID().toString(), text);
        return process(content, Set.of(IngestTarget.ORACLE, IngestTarget.STUDY), userId);
    }

    // --- Internal ---

    private String extract(IngestContent content) {
        for (var extractor : extractors) {
            if (extractor.canExtract(content.mimeType())) {
                try {
                    var text = extractor.extract(content);
                    if (text != null && !text.isBlank()) {
                        log.debug("Extracted {} chars from content {} via {}",
                            text.length(), content.id(), extractor.name());
                        return text;
                    }
                } catch (Exception e) {
                    log.warn("Extractor {} failed on content {}: {}",
                        extractor.name(), content.id(), e.getMessage());
                }
            }
        }
        return null;
    }

    private void notifyListeners(IngestResult result) {
        for (var listener : listeners) {
            try {
                listener.accept(result);
            } catch (Exception e) {
                log.warn("Ingest listener threw: {}", e.getMessage());
            }
        }
    }
}
