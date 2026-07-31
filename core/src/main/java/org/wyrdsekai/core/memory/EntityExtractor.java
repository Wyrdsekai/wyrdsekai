package org.wyrdsekai.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * LLM-based extractor that emits entities + relations
 * in a single inference call (Mem0-style, token-efficient).
 *
 * <p>Used at plant time when {@code looksLikeUserFact} fires. The returned
 * {@link ExtractionResult} is written to {@code memory_entities} and
 * {@code memory_edges} by {@link MemoryEntityStore}.</p>
 *
 * <p>Key properties per spec:
 * <ul>
 *   <li>Temperature 0.0 — deterministic</li>
 *   <li>cap:quick routing — 4B tier is sufficient for extraction</li>
 *   <li>5s timeout — on timeout, returns empty result, V1 Lucene fallback keeps fact searchable</li>
 *   <li>JSON parsing: on failure returns empty result (never throws)</li>
 * </ul>
 */
public final class EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);

    /** Allowed entity types per SPEC (V1). */
    private static final String CATEGORIES = "pet, occupation, location, book, allergy, "
            + "family, language, venue, plan, preference, trait";

    /** JSON schema prompt template. Deterministic at temp 0.0. */
    private static final String PROMPT_TEMPLATE = """
            Extract structured facts from the user statement as JSON.

            Allowed entity types: %s

            Output JSON with exactly two arrays:
              "entities":  [{"type": "<category>", "role": "<role>", "value": "<value>"}]
              "relations": [{"subject": "<s>", "predicate": "<p>", "object": "<o>"}]

            Rules:
            - One entity per distinct fact. A sentence with multiple facts emits multiple entities.
            - "value" is the literal token from the input (case-preserved for names).
            - "role" is optional (use null when not applicable).
            - If the statement is NOT a user fact (e.g. greeting, question, tool result),
              return {"entities": [], "relations": []}.
            - Output ONLY the JSON object, no prose, no markdown.

            Examples:
              input:  "my cat's name is Mochi"
              output: {"entities":[{"type":"pet","role":"name","value":"Mochi"},{"type":"pet","role":"type","value":"cat"}],"relations":[{"subject":"Mochi","predicate":"is_a","object":"cat"}]}

              input:  "I grew up in Portland, Oregon"
              output: {"entities":[{"type":"location","role":"hometown","value":"Portland, Oregon"}],"relations":[{"subject":"I","predicate":"lived_at","object":"Portland, Oregon"}]}

              input:  "I'm allergic to cashews"
              output: {"entities":[{"type":"allergy","role":"food","value":"cashews"}],"relations":[{"subject":"I","predicate":"allergic_to","object":"cashews"}]}

              input:  "I got a new job as a data engineer at a startup"
              output: {"entities":[{"type":"occupation","role":"current","value":"data engineer"}],"relations":[{"subject":"I","predicate":"works_as","object":"data engineer"}]}

              input:  "hello, how are you?"
              output: {"entities":[],"relations":[]}

            Now extract from: "%s"
            """.formatted(CATEGORIES, "%s");

    /**
     * GBNF grammar forcing llama-server to emit our exact JSON shape. Without
     * this, Drive-9B (and any personality-biased model) produces prose instead
     * of JSON; the extractor then parses empty and the V2 index stays dark.
     * Measured 2026-04-23: plain-prompt extraction → 0/8 entities indexed.
     */
    private static final String JSON_GRAMMAR = """
            root   ::= "{" ws "\\"entities\\"" ws ":" ws entArr ws "," ws "\\"relations\\"" ws ":" ws relArr ws "}"
            entArr ::= "[" ws (ent (ws "," ws ent)*)? ws "]"
            relArr ::= "[" ws (rel (ws "," ws rel)*)? ws "]"
            ent    ::= "{" ws "\\"type\\"" ws ":" ws string ws "," ws "\\"role\\"" ws ":" ws roleVal ws "," ws "\\"value\\"" ws ":" ws string ws "}"
            rel    ::= "{" ws "\\"subject\\"" ws ":" ws string ws "," ws "\\"predicate\\"" ws ":" ws string ws "," ws "\\"object\\"" ws ":" ws string ws "}"
            roleVal ::= string | "null"
            string ::= "\\"" char* "\\""
            char   ::= [^"\\\\] | "\\\\" ["\\\\/bfnrt]
            ws     ::= [ \\t\\n]*
            """;

    public record EntityRecord(String type, String role, String value) {}

    public record RelationRecord(String subject, String predicate, String object) {}

    public record ExtractionResult(List<EntityRecord> entities, List<RelationRecord> relations) {
        public static ExtractionResult empty() {
            return new ExtractionResult(List.of(), List.of());
        }

        public boolean isEmpty() {
            return (entities == null || entities.isEmpty())
                    && (relations == null || relations.isEmpty());
        }
    }

    private EntityExtractor() {}

    /**
     * Extract entities + relations from a fact statement.
     *
     * <p>Non-blocking — returns a CompletionStage. On timeout or parse failure,
     * yields {@link ExtractionResult#empty()} rather than an error; callers
     * treat empty results as "skip indexing" and rely on V1 Lucene fallback.</p>
     *
     * @param router     inference router actor ref
     * @param scheduler  actor system scheduler (from getContext().getSystem().scheduler())
     * @param agentId    DID or entityId of the asking agent (for metering)
     * @param factText   the user statement to extract from
     * @param timeout    hard cap per spec (5s default recommended)
     */
    public static CompletionStage<ExtractionResult> extract(
            ActorRef<InferenceRouter.Command> router,
            Scheduler scheduler,
            String agentId,
            String factText,
            Duration timeout) {
        if (router == null || factText == null || factText.isBlank()) {
            return CompletableFuture.completedFuture(ExtractionResult.empty());
        }

        var requestId = "entity-extract-" + UUID.randomUUID();
        var prompt = PROMPT_TEMPLATE.formatted(escape(factText));
        var messages = List.of(new InferenceClient.ChatMessage("user", prompt));

        // ChatRequest with GBNF grammar — forces llama-server to emit JSON
        // matching our schema regardless of soul-prompt bias in the base model.
        // cap:quick routes to 4B tier when registered; falls through to default
        // (Drive-9B) otherwise. Temperature 0.0 for determinism.
        CompletionStage<InferenceRouter.InferResponse> future = AskPattern.ask(
                router,
                (ActorRef<InferenceRouter.InferResponse> replyTo) ->
                        new InferenceRouter.ChatRequest(
                                requestId,
                                "cap:quick",
                                messages,
                                180,
                                0.0,
                                replyTo,
                                null,           // preferredBackend
                                JSON_GRAMMAR,   // GBNF grammar
                                null,           // format
                                null,           // tools
                                null,           // toolChoice
                                null,           // topP
                                null,           // presencePenalty
                                null,           // repetitionPenalty
                                true),          // localOnly
                timeout,
                scheduler);

        return future.handle((response, failure) -> {
            if (failure != null) {
                log.debug("EntityExtractor inference failed: {}", failure.getMessage());
                return ExtractionResult.empty();
            }
            if (response instanceof InferenceRouter.InferOk ok) {
                return parse(ok.content());
            }
            if (response instanceof InferenceRouter.InferError err) {
                log.debug("EntityExtractor inference error: {}", err.error());
            }
            return ExtractionResult.empty();
        });
    }

    /** Parse LLM JSON output into ExtractionResult. Tolerates code fences + trailing prose. */
    static ExtractionResult parse(String content) {
        if (content == null || content.isBlank()) return ExtractionResult.empty();
        var cleaned = stripCodeFences(content);
        // Find first '{' and last '}' to tolerate chatter around the JSON
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return ExtractionResult.empty();
        var jsonPart = cleaned.substring(start, end + 1);

        try {
            JsonNode root = Json.mapper().readTree(jsonPart);
            var entities = new ArrayList<EntityRecord>();
            var relations = new ArrayList<RelationRecord>();

            var entNode = root.get("entities");
            if (entNode != null && entNode.isArray()) {
                for (var n : entNode) {
                    var type = textOrNull(n, "type");
                    var value = textOrNull(n, "value");
                    if (type == null || type.isBlank() || value == null || value.isBlank()) continue;
                    var role = textOrNull(n, "role");
                    entities.add(new EntityRecord(
                            type.toLowerCase().trim(),
                            role == null || role.isBlank() ? null : role.toLowerCase().trim(),
                            value.trim()));
                }
            }

            var relNode = root.get("relations");
            if (relNode != null && relNode.isArray()) {
                for (var n : relNode) {
                    var s = textOrNull(n, "subject");
                    var p = textOrNull(n, "predicate");
                    var o = textOrNull(n, "object");
                    if (s == null || p == null || o == null
                            || s.isBlank() || p.isBlank() || o.isBlank()) continue;
                    relations.add(new RelationRecord(s.trim(), p.trim().toLowerCase(), o.trim()));
                }
            }

            return new ExtractionResult(entities, relations);
        } catch (Exception e) {
            log.debug("EntityExtractor JSON parse failed: {} (content: {})",
                    e.getMessage(), truncate(content, 120));
            return ExtractionResult.empty();
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        var v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String stripCodeFences(String s) {
        var t = s.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            if (first > 0) t = t.substring(first + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
