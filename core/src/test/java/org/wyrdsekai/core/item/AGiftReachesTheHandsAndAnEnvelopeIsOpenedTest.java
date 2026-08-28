package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two findings from one home-node evening (2026-08-24, after the fairy-tale
 * tool finally built clean):
 *
 * <ol>
 *   <li>"give me the tool" left the tool on the floor: the hand-off re-read
 *       the LIVE trigger at completion, and a minutes-long build loses that
 *       race to whatever spoke since — her musings had overwritten it, the
 *       intent check read a musing, and the person walked over and typed
 *       {@code get}.</li>
 *   <li>{@code use library_fairytale glass tide} printed
 *       {@code {tokensOut=189, tokensIn=83, text=El bosque…, latencyMs=2023}}
 *       — the item stuffed the whole {@code world.llm.complete} envelope into
 *       {@code summary}, and the renderer stringified the map, braces and all,
 *       where the story should have been.</li>
 * </ol>
 */
class AGiftReachesTheHandsAndAnEnvelopeIsOpenedTest {

    // ── the envelope ─────────────────────────────────────────────────────

    private static Map<String, Object> llmEnvelope() {
        var env = new LinkedHashMap<String, Object>();
        env.put("tokensOut", 189);
        env.put("tokensIn", 83);
        env.put("text", "El bosque había dormido en calma…");
        env.put("latencyMs", 2023);
        return env;
    }

    @Test
    @DisplayName("a map stuffed into summary yields its text, not its toString")
    void envelopeInSummaryIsOpened() {
        var result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("summary", llmEnvelope());
        assertThat(ItemScriptResponse.extractText(result, "library_fairytale"))
            .isEqualTo("El bosque había dormido en calma…");
        assertThat(ItemScriptResponse.firstTextField(result))
            .isEqualTo("El bosque había dormido en calma…");
    }

    @Test
    @DisplayName("an item that IS the bare envelope still speaks its text")
    void bareEnvelopeSpeaksItsText() {
        assertThat(ItemScriptResponse.extractText(llmEnvelope(), "library_fairytale"))
            .isEqualTo("El bosque había dormido en calma…");
    }

    @Test
    @DisplayName("a nested map with no text field falls through unchanged")
    void structuredResultsStillRender() {
        var result = new LinkedHashMap<String, Object>();
        result.put("summary", Map.of("count", 3, "ok", true));
        // No text inside the nested map: the old stringify behaviour stands —
        // showing structure honestly beats hiding it.
        assertThat(ItemScriptResponse.extractText(result, "thing"))
            .contains("count").contains("3");
    }

    @Test
    @DisplayName("an envelope stuffed into details is opened too")
    void envelopeInDetailsIsOpened() {
        // second-node, dev10, same evening: summary was a clean string, details was
        // the raw llm map — the braces followed the sentence. The first fix
        // covered the text fields and missed this one.
        var result = new LinkedHashMap<String, Object>();
        result.put("summary", "Found 5 results and crafted a fairy tale story about them.");
        result.put("details", llmEnvelope());
        assertThat(ItemScriptResponse.extractText(result, "library_query_tool"))
            .isEqualTo("Found 5 results and crafted a fairy tale story about them.\n"
                + "El bosque había dormido en calma…");
    }

    @Test
    @DisplayName("a plain string summary renders exactly as before")
    void plainSummaryUnchanged() {
        assertThat(ItemScriptResponse.extractText(
            Map.of("summary", "THE MOON RISES"), "echo_stone"))
            .isEqualTo("THE MOON RISES");
    }

    // ── the gift ─────────────────────────────────────────────────────────

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("hand-off intent is stamped at dispatch, with the requester")
    void handoffIntentIsStampedAtDispatch() throws Exception {
        var src = actorSource();
        assertThat(src).contains(
            "Map<String, WorldEvent.Said> buildAskedIntoHands = new ConcurrentHashMap<>()");
        var stamp = src.indexOf("buildAskedIntoHands.put(spec.taskId().toString(), dispatchReq)");
        var dispatch = src.indexOf("backend.submitTask(spec)");
        assertThat(stamp).as("the stamp exists").isGreaterThan(-1);
    }

    @Test
    @DisplayName("the completed build hands to the STAMPED requester, not the live trigger")
    void completionPrefersTheStampedRequester() throws Exception {
        var src = actorSource();
        var body = src.substring(src.indexOf("private void maybeHandOffDispatchedArtifact"));
        body = body.substring(0, body.indexOf("\n    }"));
        var stamped = body.indexOf("buildAskedIntoHands.remove(result.taskId().toString())");
        var live = body.indexOf("lastReactTrigger");
        assertThat(stamped).as("the stamp is consulted").isGreaterThan(-1);
        assertThat(stamped)
            .as("the stamp is read BEFORE the live-trigger fallback")
            .isLessThan(live);
        assertThat(body)
            .as("a stamped task skips the re-check that a stale trigger would fail")
            .contains("stamped == null");
    }
}
