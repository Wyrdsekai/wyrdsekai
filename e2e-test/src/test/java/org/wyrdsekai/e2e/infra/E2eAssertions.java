package org.wyrdsekai.e2e.infra;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quality assertion utilities for E2E tests.
 * Adopts the hard/soft pattern from CodePlane:
 *
 * <ul>
 *   <li><b>Hard assertions</b> — Infrastructure must work. Test fails.
 *   <li><b>Soft assertions</b> — Model quality varies. Log warning, don't fail CI.
 * </ul>
 *
 * <p>Semantic checks validate that the LLM actually engages with the
 * domain (room names, agent identity, world vocabulary) rather than
 * just checking "is non-null" or "length > N".
 */
public final class E2eAssertions {

    private static final Logger log = LoggerFactory.getLogger(E2eAssertions.class);

    private E2eAssertions() {}

    // ─── Hard assertions (infrastructure — MUST pass) ───

    /**
     * Assert that a Prose message was received (infrastructure check).
     * This validates the full pipeline: WebSocket → RoomActor → CompanionActor →
     * InferenceRouter → Backend → response → broadcast.
     */
    public static void assertProseReceived(JsonNode prose, String context) {
        assertNotNull(prose,
            "[HARD] Should receive Prose response (" + context + ")");
        var text = prose.path("text").asText();
        assertNotNull(text,
            "[HARD] Prose should have 'text' field (" + context + ")");
        assertFalse(text.isBlank(),
            "[HARD] Prose text should not be blank (" + context + ")");
    }

    /**
     * Assert that RoomState was received with expected room ID.
     */
    public static void assertRoomState(JsonNode roomState, String expectedRoomId) {
        assertNotNull(roomState,
            "[HARD] Should receive RoomState");
        var roomId = roomState.path("room").path("roomId").asText();
        assertEquals(expectedRoomId, roomId,
            "[HARD] Room ID should match");
        assertTrue(roomState.path("room").has("exits"),
            "[HARD] RoomState should have exits");
    }

    /**
     * Assert that a RoomState has standard Foundation structure.
     */
    public static void assertFoundationRoom(JsonNode roomState) {
        assertNotNull(roomState, "[HARD] Should receive RoomState");
        var room = roomState.path("room");
        assertTrue(room.has("roomId"), "[HARD] Room should have roomId");
        assertTrue(room.has("name"), "[HARD] Room should have name");
        assertTrue(room.has("description"), "[HARD] Room should have description");
        assertTrue(room.has("exits"), "[HARD] Room should have exits");
    }

    // ─── Soft assertions (model quality — log warning, don't fail) ───

    /**
     * Soft-check that response mentions any of the given domain concepts.
     * Returns true if at least one concept found, logs warning if not.
     */
    public static boolean softAssertMentions(JsonNode prose, String testName,
                                              String... concepts) {
        var text = prose.path("text").asText().toLowerCase();
        for (var concept : concepts) {
            if (text.contains(concept.toLowerCase())) {
                log.info("[E2E {}] Response mentions '{}' — semantic check passed", testName, concept);
                return true;
            }
        }
        log.warn("[E2E {} WARN] Response does not mention any of {}. " +
            "This is a model quality issue, not infrastructure. Response: {}",
            testName, List.of(concepts),
            text.length() > 200 ? text.substring(0, 200) + "..." : text);
        return false;
    }

    /**
     * Soft-check that response is substantive (exceeds minimum length).
     * Length-only checks are weak but appropriate for small models (0.6B).
     */
    public static boolean softAssertSubstantive(JsonNode prose, String testName,
                                                 int minLength) {
        var text = prose.path("text").asText();
        if (text.length() >= minLength) {
            return true;
        }
        log.warn("[E2E {} WARN] Response shorter than {} chars (got {}). " +
            "Model may not produce substantive output. Response: {}",
            testName, minLength, text.length(), text);
        return false;
    }

    /**
     * Soft-check that an action tag was emitted in the response.
     * Format: [action:ACTION_TYPE:{...json...}]
     */
    public static boolean softAssertActionTag(JsonNode prose, String testName,
                                               String expectedAction) {
        var text = prose.path("text").asText();
        var actionPattern = "[action:" + expectedAction + ":";
        if (text.contains(actionPattern)) {
            log.info("[E2E {}] Found action tag '{}' — tool calling verified", testName, expectedAction);
            return true;
        }
        log.warn("[E2E {} WARN] No '{}' action tag in response. " +
            "Model may not reliably produce tool calls. Response: {}",
            testName, expectedAction,
            text.length() > 200 ? text.substring(0, 200) + "..." : text);
        return false;
    }

    /**
     * Soft-check conversation continuity: does the response reference a
     * previously mentioned concept?
     */
    public static boolean softAssertContinuity(JsonNode prose, String testName,
                                                String concept) {
        var text = prose.path("text").asText().toLowerCase();
        if (text.contains(concept.toLowerCase())) {
            log.info("[E2E {}] Conversation continuity verified — '{}' recalled",
                testName, concept);
            return true;
        }
        log.warn("[E2E {} WARN] Companion did not recall '{}'. " +
            "Context may have been truncated or model lacks recall. Response: {}",
            testName, concept,
            text.length() > 200 ? text.substring(0, 200) + "..." : text);
        return false;
    }

    // ─── Timeout scaling ───

    /** Scale timeout for CPU-only environments (2x GPU). */
    public static Duration timeout(Duration gpuTimeout) {
        return isCpuOnly() ? gpuTimeout.multipliedBy(2) : gpuTimeout;
    }

    /** Check if running in CPU-only mode. */
    public static boolean isCpuOnly() {
        return "cpu".equalsIgnoreCase(
            System.getProperty("wyrdsekai.e2e.device",
                System.getenv().getOrDefault("WYRDSEKAI_E2E_DEVICE", "gpu")));
    }
}
