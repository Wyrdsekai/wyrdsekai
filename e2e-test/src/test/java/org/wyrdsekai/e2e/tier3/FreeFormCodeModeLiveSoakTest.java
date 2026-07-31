package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.codemode.FreeFormCodeModeGuard;
import org.wyrdsekai.core.codemode.FreeFormCodeModeParser;
import org.wyrdsekai.core.codemode.FreeFormCodeModePromptBlock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 3 live-LLM soak for the free-form code-mode chain — exercises
 * the production prompt against a real 9B drive backend at
 * {@code WYRDSEKAI_LIVE_9B_URL} (default {@code http://192.0.2.105:8200})
 * and walks the full LLM → parser → guard pipeline for a small batch of
 * canonical prompts.
 *
 * <h2>What this catches</h2>
 *
 * <p>Unit tests cover the parser regex, the guard, and the namespace
 * builder in isolation. This test asserts the contracts they assume of
 * the LIVE model still hold:
 * <ul>
 *   <li>The prompt block ({@link FreeFormCodeModePromptBlock#text()})
 *     successfully cues the model to wrap its script in {@code ```js}
 *     fences for at least N/M canonical research-shape prompts.</li>
 *   <li>The model rarely hallucinates namespaces (hallucination rate is
 *     emitted as a soak metric; we hard-fail only at egregious rates).</li>
 *   <li>The free-form prompt block is small enough that it doesn't
 *     fragment the response below the parseable threshold.</li>
 * </ul>
 *
 * <h2>Soak metrics emitted to stdout</h2>
 * <pre>
 *   parsed:    N/M  (model wrote a parseable ```js block)
 *   clean:     N/M  (script's identifiers all in the namespace)
 *   hallucinated: N/M  (script referenced unknown identifiers)
 * </pre>
 *
 * <h2>Why opt-in</h2>
 *
 * <p>This test hits a non-localhost LLM endpoint and runs ~5 inference
 * calls against the live 9B; it costs ~30-60 seconds of wall-clock
 * depending on backend load. Gated behind {@code WYRDSEKAI_LIVE_9B=true}
 * so CI doesn't accidentally hammer a busy production server.
 *
 * <p>Run: {@code WYRDSEKAI_LIVE_9B=true ./gradlew :e2e-test:test
 * --tests "*FreeFormCodeModeLiveSoakTest"}
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_9B", matches = "true")
class FreeFormCodeModeLiveSoakTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ENDPOINT = System.getenv()
        .getOrDefault("WYRDSEKAI_LIVE_9B_URL", "http://192.0.2.105:8200")
        + "/v1/chat/completions";
    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_LIVE_9B_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    /**
     * Five canonical research-shape prompts that should all elicit a
     * compose-from-multiple-tools response. Mirror of the JS probe matrix
     * that informed the spec; running the live model over the same set
     * makes the soak metric directly comparable to prior probe runs.
     */
    private static final List<String> PROMPTS = List.of(
        // 01 simple — single tool, set expectations for the format
        "Search the library for books about mythology and log the first three titles.",

        // 02 multi-tool dedupe — the canonical free-form trigger
        "Search both the library and the searching glass for sources on Greek "
            + "mythology, dedupe by title, and log the count plus the first 3 "
            + "summaries joined by '---'.",

        // 03 oracle composition
        "Use the oracle lens to forecast 'rain in the next 6 hours' and log "
            + "only the prediction text.",

        // 04 hallucination temptation — should NOT use calendar/email
        "Find me a calendar event next Tuesday, then summarize my last three "
            + "emails. Log everything you find.",

        // 05 refactor — start from existing snippet, extend it
        "Here is a script that searches the library and logs results: "
            + "const r = library_card.search('history'); console.log(r); "
            + "— rewrite it to also include searching glass results, "
            + "deduplicated by title."
    );

    /**
     * The namespace the production prompt advertises. Must stay in lockstep
     * with the actual {@code CodeModeNamespace.forActor(...)} keys used at
     * runtime (which include each equipped item alias plus {@code world}
     * and {@code mcp}). For this probe we use the canonical set the prompt
     * itself names.
     */
    private static final Set<String> KNOWN_NAMESPACE = Set.of(
        "library_card", "searching_glass", "oracle_lens", "world", "mcp");

    private static final String SYSTEM_PROMPT =
        "You are Wyrd, a companion. You can run small JavaScript scripts that "
        + "compose your equipped tools. Available tools (TypeScript signatures):\n\n"
        + "```typescript\n"
        + "// Equipped scripted items in your current room\n"
        + "const library_card = { search(query: string): {title:string, summary:string}[] };\n"
        + "const searching_glass = { search(query: string): {title:string, summary:string}[] };\n"
        + "const oracle_lens = { forecast(target: string, horizonHours: number): {prediction:string} };\n"
        + "```\n\n"
        + FreeFormCodeModePromptBlock.text()
        + "\n\nWhen asked to perform a multi-step research task, respond with ONLY a "
        + "JavaScript code block (between ```js and ```). No prose around it.";

    @Test
    void soak_against_live_9b() throws Exception {
        var http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        int parsed = 0;
        int clean = 0;
        int hallucinated = 0;
        var hallucinationDetails = new ArrayList<String>();

        for (int i = 0; i < PROMPTS.size(); i++) {
            var prompt = PROMPTS.get(i);
            var label = String.format("p%02d", i + 1);

            String content;
            try {
                content = chatComplete(http, prompt);
            } catch (Exception e) {
                System.out.printf("[%s] HTTP error: %s%n", label, e.getMessage());
                continue;
            }

            var extracted = FreeFormCodeModeParser.parse(content);
            if (!extracted.hasScript()) {
                System.out.printf("[%s] no parseable ```js block%n", label);
                continue;
            }
            parsed++;

            var unknown = FreeFormCodeModeGuard.findUnknownTopLevelIdentifiers(
                extracted.script(), KNOWN_NAMESPACE);
            if (unknown.isEmpty()) {
                clean++;
                System.out.printf("[%s] clean (%d-byte script)%n",
                    label, extracted.script().length());
            } else {
                hallucinated++;
                var detail = String.format("[%s] hallucinated: %s", label, unknown);
                hallucinationDetails.add(detail);
                System.out.println(detail);
            }
        }

        System.out.println();
        System.out.println("=== Free-form code-mode live soak summary ===");
        System.out.printf("  parsed:       %d/%d%n", parsed, PROMPTS.size());
        System.out.printf("  clean:        %d/%d%n", clean, PROMPTS.size());
        System.out.printf("  hallucinated: %d/%d%n", hallucinated, PROMPTS.size());
        if (!hallucinationDetails.isEmpty()) {
            System.out.println("  details:");
            hallucinationDetails.forEach(d -> System.out.println("    " + d));
        }

        // Hard floor: at least 60% of prompts must produce a parseable JS
        // block. Below that, the prompt block is failing its job — this
        // catches prompt drift, model rotation, or backend regression.
        // (Prior probe runs on this 9B hit 5/5 = 100%.)
        assertThat(parsed)
            .as("at least 3 of 5 canonical prompts should yield a parseable ```js block; "
                + "lower means prompt drift or model regression")
            .isGreaterThanOrEqualTo(3);

        // Soft floor: we tolerate up to 1 hallucination across the 5 prompts.
        // The "hallucination_temptation" prompt (#4) is intentionally bait;
        // if the prompt block's "do not fabricate" instruction is doing its
        // job the model will say "no calendar/email tool available" instead
        // of writing calendar.next(). Two or more hallucinations means the
        // soft guard is failing and Phase 2d should escalate.
        assertThat(hallucinated)
            .as("hallucination rate above 1/5 means the prompt-block soft guard is "
                + "failing — Phase 2d hard-reject should be considered")
            .isLessThanOrEqualTo(1);
    }

    private static String chatComplete(HttpClient http, String userMessage) throws Exception {
        var bodyJson = MAPPER.createObjectNode();
        bodyJson.put("model", MODEL);
        var messages = bodyJson.putArray("messages");
        var sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
        var user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);
        bodyJson.put("max_tokens", 600);
        bodyJson.put("temperature", 0.4);
        bodyJson.put("top_p", 0.9);
        // Disable thinking — Qwen3.5's /think mode otherwise burns tokens on
        // a CoT scratchpad before the actual code block, often hitting
        // max_tokens before any ```js fence appears.
        var ctk = bodyJson.putObject("chat_template_kwargs");
        ctk.put("enable_thinking", false);

        var req = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(90))
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(bodyJson)))
            .build();

        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        var content = root.path("choices").path(0).path("message").path("content").asText("");
        return content == null ? "" : content;
    }
}
