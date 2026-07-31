package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Graceful-degradation tests for ClassifierArm. These are the contracts callers
 * rely on before any models have been trained: the arm must initialize without
 * crashing, classification must return {@code unavailable()} cleanly when a
 * model is missing, and the close() path must be safe to call.
 *
 * <p>Real end-to-end tests (with actual trained ONNX models) are tier 2 and
 * live in BootstrapClassifierE2ETest once the pretrained artifacts exist.
 */
class ClassifierArmTest {

    @Test void forAgent_with_no_model_returns_arm_but_classify_unavailable() {
        var arm = ClassifierArm.forAgent("did:test:no-model");
        // Arm should initialize even without models — just reports unavailable.
        // In environments without onnxruntime native libs it may return null;
        // skip in that case.
        if (arm == null) return;
        try {
            var result = arm.classify(ClassifierHead.REQUEST_TYPE, "hello Wyrd");
            assertNotNull(result);
            // With no trained model shipped in test resources, we expect
            // unavailable. If someone ships a model later this test stays
            // meaningful because the sentinel distinguishes L1 from null.
            if (result.label() == null) {
                assertEquals("null", result.source());
                assertEquals(0.0, result.confidence());
            } else {
                // Model is present — confidence should be between 0 and 1 inclusive.
                assertTrue(result.confidence() >= 0.0 && result.confidence() <= 1.0);
                assertEquals("L1", result.source());
            }
        } finally {
            arm.close();
        }
    }

    @Test void classify_with_null_text_returns_unavailable() {
        var arm = ClassifierArm.forAgent("did:test:null-text");
        if (arm == null) return;
        try {
            var result = arm.classify(ClassifierHead.REQUEST_TYPE, null);
            assertEquals("null", result.source());
            assertEquals(0.0, result.confidence());
        } finally {
            arm.close();
        }
    }

    @Test void classify_with_blank_text_returns_unavailable() {
        var arm = ClassifierArm.forAgent("did:test:blank-text");
        if (arm == null) return;
        try {
            var result = arm.classify(ClassifierHead.REQUEST_TYPE, "   ");
            assertEquals("null", result.source());
        } finally {
            arm.close();
        }
    }

    @Test void close_is_idempotent() {
        var arm = ClassifierArm.forAgent("did:test:close-twice");
        if (arm == null) return;
        arm.close();
        // Second close should not throw.
        assertDoesNotThrow(arm::close);
    }

    @Test void classification_record_unavailable_sentinel() {
        var u = Classification.unavailable();
        assertNull(u.label());
        assertEquals(0.0, u.confidence());
        assertEquals("null", u.source());
        assertTrue(u.probs().isEmpty());
    }

    @Test void classifier_head_resource_paths_are_well_formed() {
        var head = ClassifierHead.REQUEST_TYPE;
        assertEquals("request_type", head.resourceName());
        assertEquals("classifier/pretrained/request_type.onnx", head.modelResourcePath());
        assertEquals("classifier/pretrained/request_type.labels.json", head.labelsResourcePath());
    }
}
