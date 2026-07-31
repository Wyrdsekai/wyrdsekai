package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Tag;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.EmbeddingService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard unit-integration test.
 *
 * <p>We substitute a MOCK train script for the real {@code train_classifier.py}
 * — the mock writes a deliberately-low val-accuracy.json (0.30) and a
 * minimal ONNX stand-in. Forge runs the subprocess, reads the low accuracy,
 * compares against the shipped baseline (0.948), detects a regression far
 * past the 5% tolerance, and rolls back. For a first-time retrain (no
 * per-agent backup), rollback means DELETING the new artifacts so the
 * shipped model remains authoritative.
 *
 * <p>Does not require the real Python stack — only {@code python3} itself
 * to execute the mock script.
 */
@Tag("integration")
@Tag("needs-classifier")
class ClassifierForgeRegressionGuardTest {

    private static final String DID_PREFIX = "did:test:forge-regression-";

    @BeforeAll
    static void initEmbeddings() {
        EmbeddingService.init();
    }

    @BeforeEach
    void preflight() {
        Assumptions.assumeTrue("1".equals(System.getenv(ClassifierForge.RETRAIN_ENV)),
            "Set " + ClassifierForge.RETRAIN_ENV + "=1 to run this test");
        try {
            var pb = new ProcessBuilder("python3", "--version");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            Assumptions.assumeTrue(
                proc.waitFor(5, TimeUnit.SECONDS)
                    && proc.exitValue() == 0,
                "python3 not available");
        } catch (Exception e) {
            Assumptions.abort("python3 not available: " + e.getMessage());
        }
    }

    @Test void regression_rolls_back_and_keeps_shipped_model(@TempDir Path scriptsRoot)
            throws Exception {
        // Install the mock train script at <scriptsRoot>/classifier/train_classifier.py
        // and point Forge at it via the wyrdsekai.scripts system property.
        var classifierDir = scriptsRoot.resolve("classifier");
        Files.createDirectories(classifierDir);
        var mockScript = classifierDir.resolve("train_classifier.py");
        Files.writeString(mockScript, MOCK_TRAIN_SCRIPT, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(mockScript,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {}
        System.setProperty("wyrdsekai.scripts", scriptsRoot.toString());

        try {
            var did = DID_PREFIX + UUID.randomUUID();
            var arm = ClassifierArm.forAgent(did);
            assertNotNull(arm);
            var perAgent = arm.perAgentDir();
            assertFalse(Files.exists(perAgent.resolve("request_type.onnx")),
                "per-agent ONNX should not pre-exist");

            // Seed a couple of events — content doesn't matter; we're testing
            // the guard mechanism, not the pseudo-label merge.
            arm.eventLog().record(new ClassifierEventLog.Event(
                Instant.now(), "REQUEST_TYPE",
                "hi there, good morning to you", "chat", 0.92, "L1"));

            var results = ClassifierForge.consolidate(arm);
            var req = results.stream()
                .filter(r -> r.head().equals("REQUEST_TYPE"))
                .findFirst()
                .orElseThrow();

            assertTrue(req.retrainAttempted(), "retrain should have fired");
            assertFalse(req.retrainSucceeded(),
                "mock script produced val_accuracy=0.30; guard must reject");
            assertEquals(0.30, req.newAccuracy(), 0.01);
            assertTrue(req.priorAccuracy() >= 0.90,
                "prior accuracy from shipped sidecar should be high: "
                    + req.priorAccuracy());
            assertTrue(req.note().toLowerCase().contains("regression"),
                "note should mention regression: " + req.note());

            // Rollback: no prior per-agent model existed, so the new artifacts
            // must have been DELETED — the runtime falls back to shipped.
            assertFalse(Files.exists(perAgent.resolve("request_type.onnx")),
                "bad new ONNX must be deleted after regression rollback");
            assertFalse(Files.exists(perAgent.resolve("request_type.val-accuracy.json")),
                "bad val-accuracy sidecar must be deleted after rollback");

            // Lineage records it.
            var lineage = perAgent.resolve("request_type.lineage.jsonl");
            assertTrue(Files.exists(lineage));
            var content = Files.readString(lineage);
            assertTrue(content.contains("\"retrain_succeeded\":false"));
            assertTrue(content.contains("\"prior_accuracy\""));
            assertTrue(content.contains("\"new_accuracy\":0.3"));

            // Fresh arm still works — loads shipped resources.
            arm.close();
            var freshArm = ClassifierArm.forAgent(did);
            assertNotNull(freshArm);
            var probe = freshArm.classify(ClassifierHead.REQUEST_TYPE,
                "hi Wyrd, good morning");
            assertEquals("chat", probe.label(),
                "shipped model should still classify chat correctly");
            assertTrue(probe.confidence() > 0.5);
            freshArm.close();
            cleanupAgent(perAgent);
        } finally {
            System.clearProperty("wyrdsekai.scripts");
        }
    }

    private static void cleanupAgent(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }

    /**
     * Mock train script: parses Forge's CLI args, writes a deliberately-bad
     * val-accuracy.json (0.30) alongside a tiny placeholder ONNX. Forge's
     * guard will read the 0.30 and detect a regression against the shipped
     * baseline (~0.948).
     */
    private static final String MOCK_TRAIN_SCRIPT = """
        #!/usr/bin/env python3
        import argparse, json, sys
        from pathlib import Path

        ap = argparse.ArgumentParser()
        ap.add_argument('--corpus', required=True, type=Path)
        ap.add_argument('--output', required=True, type=Path)
        ap.add_argument('--labels-output', required=True, type=Path)
        ap.add_argument('--test-size', type=float, default=0.2)
        ap.add_argument('--seed', type=int, default=42)
        args = ap.parse_args()

        # Minimal ONNX placeholder — Forge only checks file exists + reads
        # the accuracy sidecar for the regression decision. Real loading
        # happens only if the guard approves, which this mock won't trigger.
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(b'onnx-placeholder')
        args.labels_output.write_text(json.dumps({
            'labels': ['chat'], 'feature_dim': 384,
        }))

        acc_path = args.output.with_name(args.output.stem + '.val-accuracy.json')
        acc_path.write_text(json.dumps({
            'accuracy': 0.30,
            'training_examples': 10,
            'validation_examples': 5,
            'labels': ['chat'],
        }))
        print(f'mock retrain: wrote accuracy 0.30 to {acc_path}', file=sys.stderr)
        """;
}
