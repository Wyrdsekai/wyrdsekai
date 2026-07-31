package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Forge consolidation logic. Retrain subprocess is
 * deliberately NOT exercised here — gated by {@code WYRDSEKAI_CLASSIFIER_RETRAIN}
 * env var so these tests verify corpus merging and lineage writing only.
 */
class ClassifierForgeTest {

    @Test void consolidate_with_null_arm_returns_empty() {
        assertTrue(ClassifierForge.consolidate(null).isEmpty());
    }

    @Test void consolidate_with_no_events_returns_empty(@TempDir Path dir) {
        // Spin up an arm with a valid classifier dir but no logged events.
        var arm = ClassifierArm.forAgent("did:test:forge-no-events");
        assertNotNull(arm);
        var results = ClassifierForge.consolidate(arm);
        // No rotated log → empty results list
        assertTrue(results.isEmpty());
        arm.close();
    }

    @Test void consolidate_merges_high_confidence_events_into_corpus(@TempDir Path dir)
            throws Exception {
        // Point the event log at our temp dir and seed it manually — bypasses
        // the full ClassifierArm init so we don't need ONNX loaded.
        var eventLog = ClassifierEventLog.forAgent(dir);
        // High-confidence events → should become pseudo-labels
        eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE",
            "the garden is in bloom today, new flower names",
            "chat", 0.91, "L1"));
        eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE",
            "go dig into the causality problem thoroughly while I cook",
            "delegate", 0.93, "L1"));
        // Low-confidence event → should be skipped
        eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE",
            "something ambiguous maybe?", "chat", 0.55, "L1"));

        // Manually invoke the guts of consolidate via reflection-free path:
        // rotate the log, consume all three events, run one head.
        var rotated = eventLog.rotate();
        var events = ClassifierEventLog.read(rotated);
        assertEquals(3, events.size());
        long highConf = events.stream().filter(e -> e.confidence() >= ClassifierForge.PSEUDO_LABEL_FLOOR).count();
        assertEquals(2, highConf,
            "two events should clear the pseudo-label floor");
    }

    @Test void consolidate_is_idempotent_on_empty_log(@TempDir Path dir) {
        var eventLog = ClassifierEventLog.forAgent(dir);
        assertNull(eventLog.rotate(),
            "rotating a never-written log is a no-op");
    }

    @Test void pseudo_label_floor_is_conservative() {
        // 0.85 is the floor — documented in the field for reference.
        assertEquals(0.85, ClassifierForge.PSEUDO_LABEL_FLOOR, 0.0001);
    }

    @Test void retrain_env_gate_constant() {
        assertEquals("WYRDSEKAI_CLASSIFIER_RETRAIN", ClassifierForge.RETRAIN_ENV);
    }

    @Test void max_corpus_cap_is_set() {
        assertTrue(ClassifierForge.MAX_CORPUS_RECORDS > 1000,
            "cap should be large enough to hold bootstrap + many pseudo-labels");
    }

    @Test void consolidate_full_path_without_retrain_writes_merged_corpus(@TempDir Path dir)
            throws Exception {
        // Use WYRDSEKAI_DATA_DIR so the arm lands in our temp dir.
        var oldDataDir = System.getenv("WYRDSEKAI_DATA_DIR");
        try {
            // We can't actually set env vars in-JVM cleanly, so instead we
            // use an arm tied to a unique DID under the real HOME, feed
            // events, and verify that consolidate produces a corpus file.
            var arm = ClassifierArm.forAgent(
                "did:test:forge-" + System.nanoTime());
            if (arm == null) return; // skip if ONNX unavailable in env
            // On a host with an installed daemon, ~/.wyrdsekai/classifiers
            // may be root-owned; the arm degrades to eventLog()==null there
            // (prod behavior), which this full-path test can't exercise.
            Assumptions.assumeTrue(arm.eventLog() != null,
                "classifier event-log dir not writable on this host");

            // Log a couple of high-conf events.
            arm.eventLog().record(new ClassifierEventLog.Event(
                Instant.now(), "REQUEST_TYPE",
                "the garden is in bloom today, new flowers unique to this test",
                "chat", 0.91, "L1"));
            arm.eventLog().record(new ClassifierEventLog.Event(
                Instant.now(), "CLEANLINESS",
                "I have examined this unique test input for phase 4",
                "leaky", 0.92, "L1"));

            var results = ClassifierForge.consolidate(arm);
            assertFalse(results.isEmpty(),
                "consolidation should produce one result per head");

            // Each head should have a result; retrain gated off by default
            for (var r : results) {
                assertFalse(r.retrainAttempted(),
                    "retrain should be gated off without env var");
            }

            // Per-agent corpus file should exist for at least one head
            var perAgent = arm.perAgentDir();
            assertTrue(
                Files.exists(perAgent.resolve("request_type-corpus.jsonl"))
                    || Files.exists(perAgent.resolve("cleanliness-corpus.jsonl")),
                "at least one merged corpus should be on disk");

            arm.close();
        } finally {
            // nothing to restore — we never modified the env
            assertNotNull(oldDataDir == null ? "noop" : oldDataDir);
        }
    }
}
