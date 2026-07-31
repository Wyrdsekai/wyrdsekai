package org.wyrdsekai.core.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IngestPipelineTest {

    private IngestPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new IngestPipeline();
        pipeline.registerExtractor(new TextExtractor());
    }

    @Test
    void process_text_content() {
        var content = IngestContent.text("test-1", "Hello world");
        var routed = new AtomicReference<IngestPipeline.IngestEvent>();
        pipeline.registerRouter(IngestTarget.ORACLE, routed::set);

        var result = pipeline.process(content, Set.of(IngestTarget.ORACLE), "user1");

        assertTrue(result.success());
        assertEquals("Hello world", result.extractedText());
        assertEquals(Set.of(IngestTarget.ORACLE), result.targets());
        assertNotNull(routed.get());
        assertEquals("user1", routed.get().userId());
    }

    @Test
    void process_clipboard_content() {
        var content = IngestContent.clipboard("clip-1", "Clipboard text");
        var result = pipeline.process(content, Set.of(IngestTarget.STUDY), "user1");
        assertTrue(result.success());
        assertEquals("Clipboard text", result.extractedText());
    }

    @Test
    void process_binary_text_content() {
        var data = "Binary text content".getBytes();
        var content = new IngestContent("bin-1", "text", "text/plain",
            data, null, Map.of(), Instant.now());

        var result = pipeline.process(content, Set.of(IngestTarget.LIBRARY), "user1");
        assertTrue(result.success());
        assertEquals("Binary text content", result.extractedText());
    }

    @Test
    void process_fails_for_unsupported_binary() {
        var data = new byte[]{1, 2, 3};
        var content = new IngestContent("bin-2", "unknown", "application/octet-stream",
            data, null, Map.of(), Instant.now());

        var result = pipeline.process(content, Set.of(IngestTarget.ORACLE), "user1");
        assertFalse(result.success());
        assertNotNull(result.error());
    }

    @Test
    void process_fails_for_empty_text() {
        var content = IngestContent.text("empty-1", "");
        var result = pipeline.process(content, Set.of(IngestTarget.ORACLE), "user1");
        assertFalse(result.success());
    }

    @Test
    void routes_to_multiple_targets() {
        var oracleEvents = new ArrayList<IngestPipeline.IngestEvent>();
        var studyEvents = new ArrayList<IngestPipeline.IngestEvent>();
        pipeline.registerRouter(IngestTarget.ORACLE, oracleEvents::add);
        pipeline.registerRouter(IngestTarget.STUDY, studyEvents::add);

        var content = IngestContent.text("multi-1", "Important text");
        var result = pipeline.process(content,
            Set.of(IngestTarget.ORACLE, IngestTarget.STUDY), "user1");

        assertTrue(result.success());
        assertEquals(Set.of(IngestTarget.ORACLE, IngestTarget.STUDY), result.targets());
        assertEquals(1, oracleEvents.size());
        assertEquals(1, studyEvents.size());
    }

    @Test
    void listener_notified_on_success() {
        var results = new ArrayList<IngestResult>();
        pipeline.addListener(results::add);

        pipeline.process(IngestContent.text("listen-1", "test"), Set.of(), "user1");
        assertEquals(1, results.size());
        assertTrue(results.getFirst().success());
    }

    @Test
    void listener_notified_on_failure() {
        var results = new ArrayList<IngestResult>();
        pipeline.addListener(results::add);

        pipeline.process(IngestContent.text("listen-2", ""), Set.of(), "user1");
        assertEquals(1, results.size());
        assertFalse(results.getFirst().success());
    }

    @Test
    void processText_convenience_method() {
        var oracleEvents = new ArrayList<IngestPipeline.IngestEvent>();
        pipeline.registerRouter(IngestTarget.ORACLE, oracleEvents::add);
        pipeline.registerRouter(IngestTarget.STUDY, e -> {});

        var result = pipeline.processText("Quick note", "user1");
        assertTrue(result.success());
        assertEquals(1, oracleEvents.size());
    }

    @Test
    void custom_extractor_used_for_matching_mime() {
        pipeline.registerExtractor(new ContentExtractor() {
            @Override public boolean canExtract(String mimeType) { return "custom/type".equals(mimeType); }
            @Override public String extract(IngestContent content) { return "extracted-custom"; }
            @Override public String name() { return "custom"; }
        });

        var content = new IngestContent("custom-1", "custom", "custom/type",
            new byte[]{1}, null, Map.of(), Instant.now());

        var result = pipeline.process(content, Set.of(), "user1");
        assertTrue(result.success());
        assertEquals("extracted-custom", result.extractedText());
    }

    @Test
    void router_exception_doesnt_break_other_targets() {
        pipeline.registerRouter(IngestTarget.ORACLE, e -> { throw new RuntimeException("boom"); });
        pipeline.registerRouter(IngestTarget.STUDY, e -> {});

        var content = IngestContent.text("error-1", "test");
        var result = pipeline.process(content,
            Set.of(IngestTarget.ORACLE, IngestTarget.STUDY), "user1");

        assertTrue(result.success());
        // STUDY succeeded even though ORACLE threw
        assertTrue(result.targets().contains(IngestTarget.STUDY));
    }
}
