package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.ApiProvider;
import org.wyrdsekai.core.inference.InferenceClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier-3 live bake proof for {@link ThemedDescriptionService}: drives the exact
 * bake internals (real {@link ThemedDescriptionService#buildSystemPrompt} →
 * real V10 4B voice backend on :8201 → real
 * {@link ThemedDescriptionService#sanitize}) against the authored Nexus
 * description, across three themes. Skips when no voice backend is reachable.
 *
 * <p>Run: {@code ./gradlew :core:test --tests '*ThemedDescriptionLiveBakeTest' --rerun-tasks}.</p>
 */
class ThemedDescriptionLiveBakeTest {

    private static final String VOICE_URL = "http://127.0.0.1:8201";
    private static final String NEXUS =
        "A gentle hum fills the air. Soft light pulses from crystalline walls, "
        + "casting warm shadows across the smooth stone floor. This is the heart "
        + "of the world — where all paths begin and all travelers arrive.";

    @Test
    void bakesRealRewritesAcrossThemes() throws Exception {
        Assumptions.assumeTrue(voiceReachable(), "voice backend :8201 not reachable — skipping live bake");

        // backendHint "llama-server" → enable_thinking=false (the V10 is a thinking
        // model; without it the whole budget goes into a <think> block and content is empty).
        var client = new InferenceClient(
            VOICE_URL, null, Duration.ofSeconds(45), new ApiProvider.OpenAI("llama-server"));
        System.out.println("\n=== BASE (authored) ===\n" + NEXUS + "\n");

        for (var aesthetic : new ZoneAesthetic[]{
                ZoneAesthetic.arcane(), ZoneAesthetic.cyberpunk(), ZoneAesthetic.garden()}) {
            var system = ThemedDescriptionService.buildSystemPrompt(aesthetic, "en");
            var raw = client.complete("default", system, NEXUS, 240, 0.7)
                .get(45, TimeUnit.SECONDS);
            var rewritten = ThemedDescriptionService.sanitize(raw);

            System.out.println("=== " + aesthetic.name().toUpperCase() + " ===");
            System.out.println(rewritten);
            System.out.println();

            assertThat(rewritten).as("rewrite for %s", aesthetic.name()).isNotBlank();
            assertThat(rewritten).isNotEqualTo(NEXUS);            // genuinely rewritten
            assertThat(rewritten.length()).isBetween(20, 1200);    // passed sanitize length gate
        }
    }

    private static boolean voiceReachable() {
        try {
            var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(VOICE_URL + "/v1/models"))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
