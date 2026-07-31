package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Tag;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.search.EmbeddingService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Forge retrain loop: seed synthetic "interaction events",
 * run consolidation with {@code WYRDSEKAI_CLASSIFIER_RETRAIN=1}, verify
 * that a fresh ONNX model + val-accuracy sidecar land in the per-agent dir,
 * and that a new {@link ClassifierArm} picks up the per-agent override.
 *
 * <p>Requires {@code python3} + the sklearn/onnxruntime/skl2onnx stack. The
 * test auto-skips when the training script isn't runnable. Tagged
 * {@code integration} so short-path CI can exclude it.
 *
 * <p>Note: this does NOT test accuracy *improvement* — that's Phase 7 (see
 * {@code scripts/classifier/validate_forge_loop.py}). It tests that the
 * mechanism runs end-to-end and produces a model the Java runtime can use.
 */
@Tag("integration")
@Tag("needs-classifier")
class ClassifierForgeRetrainIntegrationTest {

    private static final String DID_PREFIX = "did:test:forge-retrain-";

    @BeforeAll
    static void initEmbeddings() {
        EmbeddingService.init();
    }

    @BeforeEach
    void enableRetrain() {
        // We can't set env vars in-JVM — the ProcessBuilder inherits the
        // JVM's env. Caller must run gradle test with
        // WYRDSEKAI_CLASSIFIER_RETRAIN=1 to exercise the full path.
        // Skip if not set.
        var gate = System.getenv(ClassifierForge.RETRAIN_ENV);
        Assumptions.assumeTrue("1".equals(gate),
            "Set " + ClassifierForge.RETRAIN_ENV + "=1 to run this test");
        // Also need python3 available.
        try {
            var pb = new ProcessBuilder("python3", "--version");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var ok = proc.waitFor(5, TimeUnit.SECONDS);
            Assumptions.assumeTrue(ok && proc.exitValue() == 0,
                "python3 not available");
        } catch (Exception e) {
            Assumptions.abort("python3 not available: " + e.getMessage());
        }
    }

    @Test void full_retrain_loop_produces_usable_model() throws Exception {
        var did = DID_PREFIX + UUID.randomUUID();
        var arm = ClassifierArm.forAgent(did);
        assertNotNull(arm, "ClassifierArm failed to init");

        // Seed ~50 synthetic high-confidence events across labels.
        // These simulate the classifier having handled real traffic.
        var events = syntheticRequestTypeEvents();
        assertEquals(56, events.size());
        for (var e : events) arm.eventLog().record(e);

        // Run consolidation. Regression guard + retrain are both exercised.
        var results = ClassifierForge.consolidate(arm);
        assertFalse(results.isEmpty());

        var requestResult = results.stream()
            .filter(r -> r.head().equals("REQUEST_TYPE"))
            .findFirst()
            .orElseThrow();

        assertTrue(requestResult.retrainAttempted(),
            "retrain should fire when env gate is on");

        if (!requestResult.retrainSucceeded()) {
            // Regression can legitimately prevent install — but we seeded
            // correct labels, so a fail here is actually a problem.
            fail("retrain did not succeed: " + requestResult.note()
                + " (prior=" + requestResult.priorAccuracy()
                + ", new=" + requestResult.newAccuracy() + ")");
        }

        // Per-agent model + labels + accuracy sidecar must exist.
        var perAgent = arm.perAgentDir();
        assertTrue(Files.exists(perAgent.resolve("request_type.onnx")),
            "per-agent request_type.onnx should land after successful retrain");
        assertTrue(Files.exists(perAgent.resolve("request_type.labels.json")));
        assertTrue(Files.exists(perAgent.resolve("request_type.val-accuracy.json")));

        // The new accuracy should be reported and non-negative.
        assertTrue(requestResult.newAccuracy() > 0,
            "new accuracy should be measured: " + requestResult.newAccuracy());
        // And within regression tolerance of the baseline.
        assertTrue(
            requestResult.newAccuracy()
                >= requestResult.priorAccuracy() - ClassifierForge.REGRESSION_TOLERANCE,
            String.format("new accuracy %.4f should be within %s of prior %.4f",
                requestResult.newAccuracy(),
                ClassifierForge.REGRESSION_TOLERANCE,
                requestResult.priorAccuracy()));

        // Now spawn a FRESH arm for the same DID and verify it loads the
        // per-agent override. Classify a probe and check it routes sensibly.
        arm.close();
        var freshArm = ClassifierArm.forAgent(did);
        assertNotNull(freshArm);

        var probeChat = freshArm.classify(ClassifierHead.REQUEST_TYPE,
            "hi Wyrd, how's your afternoon going");
        assertEquals("chat", probeChat.label(),
            "retrained model should still classify greetings as chat");

        var probeDelegate = freshArm.classify(ClassifierHead.REQUEST_TYPE,
            "go research the history of MUDs thoroughly while I wait, "
                + "take your time and give me multiple sources");
        assertNotNull(probeDelegate.label());
        assertTrue(
            List.of("delegate", "factual").contains(probeDelegate.label()),
            "delegate-shaped tell should land on delegate or factual, got: "
                + probeDelegate.label());

        // Lineage file should have at least one entry with accuracy fields.
        var lineage = perAgent.resolve("request_type.lineage.jsonl");
        assertTrue(Files.exists(lineage));
        var lineageContent = Files.readString(lineage);
        assertTrue(lineageContent.contains("\"prior_accuracy\""),
            "lineage should record prior accuracy");
        assertTrue(lineageContent.contains("\"new_accuracy\""),
            "lineage should record new accuracy");

        freshArm.close();
        cleanupAgent(perAgent);
    }

    private static void cleanupAgent(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }

    private static List<ClassifierEventLog.Event> syntheticRequestTypeEvents() {
        var now = Instant.now();
        var out = new ArrayList<ClassifierEventLog.Event>();
        // Seven example phrasings per label, all high-confidence. These
        // mimic what the current classifier would log after confident
        // predictions on real traffic.
        String[][] buckets = {
            {"chat",
                "hey there, beautiful morning isn't it",
                "hi Wyrd, good to see you today",
                "mornin' — how you holding up",
                "evening, friend",
                "hey hey, what's cookin'",
                "hello again, been a minute",
                "good to hear your voice"},
            {"reflective",
                "what have you been noticing about yourself lately",
                "how has your sense of purpose shifted these past weeks",
                "tell me something you've learned about your own patterns",
                "what do you think is changing in the way you relate to me",
                "when you look back at your growth, what stands out",
                "what part of yourself feels unresolved right now",
                "share something you've been sitting with internally"},
            {"emotional",
                "I'm really struggling right now, everything feels heavy",
                "I'm so tired of pretending to be fine",
                "the grief just won't let up today",
                "I feel completely alone in this",
                "I've been crying on and off all morning",
                "nothing I do seems to make it better",
                "I just needed someone to be here, you know"},
            {"factual",
                "what's the capital of Belize",
                "how tall is Mount Fuji in meters",
                "tell me how photosynthesis works",
                "who invented the telephone",
                "give me a quick summary of neural network basics",
                "what year did Apollo 11 land on the moon",
                "explain the difference between RAM and ROM"},
            {"delegate",
                "please look into the history of MUDs thoroughly, take your time while I cook",
                "while I'm away, dig deep into the philosophy of embodied cognition",
                "do a thorough multi-source research on octopus intelligence",
                "take your time and give me an in-depth breakdown of cognitive architectures",
                "research the ancient Greek schools of thought in depth while I'm busy",
                "explore the Kyoto school philosophy deeply — I'll check back later",
                "dig into the literature on distributed systems, I'm going out for a bit"},
            {"action",
                "go to the kitchen",
                "head north to the garden",
                "walk into the library",
                "pick up the lantern on the table",
                "open the door to the east",
                "grab that book from the shelf",
                "move into the courtyard"},
            {"write",
                "jot down that the meeting is moved to Tuesday",
                "save a note that the dog needs meds at 8pm",
                "write in my journal about today's rainstorm",
                "put a reminder about buying milk tomorrow",
                "record that I want to call my mother later",
                "note that the rose bush is finally blooming",
                "save the recipe I just described for later"},
            {"tell_someone",
                "tell Alice I'll be thirty minutes late",
                "let Ember know the files are ready",
                "pass on to the kids that dinner is at six",
                "forward the news to Masumi when he's back",
                "give Chief the update on the project",
                "send a message to Lain about the party tonight",
                "let my sister know I'm thinking of her"},
        };
        for (var bucket : buckets) {
            var label = bucket[0];
            for (int i = 1; i < bucket.length; i++) {
                out.add(new ClassifierEventLog.Event(
                    now.plusSeconds(i),
                    "REQUEST_TYPE",
                    bucket[i],
                    label,
                    0.92, // high confidence — clears PSEUDO_LABEL_FLOOR
                    "L1"));
            }
        }
        return out;
    }
}
