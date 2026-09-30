package org.wyrdsekai.core.agent.decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.classifier.Classification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A typed decision: a bounded question over a piece of text, answered with a probability for
 * each option rather than with prose. The shape of a decision model (Jev, Kev), without the
 * model: measured on 2026-09-22 against the shipped task-present corpora, the household's own
 * resident model asked such a question through its first-token log-probabilities scored 100% on
 * the held-out anchors, 97.6% on the seeds and got every one of that week's live misroutes right,
 * at ~110 ms with the fixed prefix cached — better than a purpose-built 4B decision model, and
 * with nothing new to serve. The classifier head stays as the other backend and the fallback.
 *
 * <p>{@link #askAsync} never blocks the caller: the question is sent when a person's line is
 * heard and read, if it has come back, when the line is routed; otherwise the head decides and
 * the question is cancelled. On a single-slot server the question queues behind whatever the
 * model is generating, so it often has not come back, and the head deciding is the normal case
 * then, not a failure.
 */
public final class TypedDecision {

    private static final Logger log = LoggerFactory.getLogger(TypedDecision.class);
    static final Duration TIMEOUT = Duration.ofMillis(1500);
    /**
     * How long a surprise question may wait. Nothing waits on it — the answer is applied when it
     * comes — and on a model busy with a long reply 1.5 s left 28 of an hour's questions unanswered
     * (household node, 2026-09-29), so a line could never surprise her.
     */
    public static final Duration UNEXPECTED_TIMEOUT = Duration.ofSeconds(10);
    /** The least probability the options' first tokens must carry together for an answer to count. */
    static final double MIN_OPTION_MASS = 0.5;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    /** A question: an instruction and the options, each with a one-line description. */
    /**
     * {@code also}: other words that count for an option when the model's first token is exactly
     * one of them. A small model answers a Spanish or Japanese line in that language ("sí", "はい").
     */
    public record Question(String id, String instructions, Map<String, String> options, Map<String, Set<String>> also) {
        public Question(String id, String instructions, Map<String, String> options) {
            this(id, instructions, options, Map.of());
        }
    }

    /** The answer: the chosen option, a probability per option, and which backend answered. */
    public record Answer(String choice, Map<String, Double> probabilities, String source) {
        public double confidence() { return probabilities.getOrDefault(choice, 0.0); }
    }

    /** The routing question the voice route asks: is the person asking for something to be done? */
    public static final Question TASK_PRESENT = new Question("task_present",
        "You classify one line a person said to a companion. Answer with exactly one word: "
            + "actionable if the line asks the companion to DO something now (make, search, list, build, fix, look up, summarise); "
            + "none if it is only talk, a feeling, a greeting, a reflection, an invitation to learn over time, or a question about how things went.",
        new LinkedHashMap<>(Map.of("actionable", "the line asks for something to be done now",
                                   "none", "no task now")));

    /**
     * Whether a line someone just said was unexpected: surprise at what is said, the way news or a
     * punchline surprises, not at wording that happens to be new. Asked with the last lines of the
     * conversation and what the Oracle expects her to hear.
     */
    public static final Question UNEXPECTED = new Question("unexpected",
        "You read the last lines of a conversation and one new line. Answer with exactly one word: "
            + "expected if the new line goes on the way the conversation was going; "
            + "new if it brings news, a sudden change of subject, or a turn nobody saw coming, the way a punchline does.",
        new LinkedHashMap<>(Map.of("expected", "the line goes on the way the conversation was going",
                                   "new", "news, a sudden turn, or a punchline")));

    /**
     * Whether she takes a name someone offers for the bond between them: the naming ritual she
     * proposes when a bond becomes sacred. Asked with who offers it, what the two have shared, and
     * the last lines between them. The name is kept only on her yes.
     *
     * <p>Worded as a yes or no, decided for her as her. The first wording asked her, as a companion,
     * for "take" or "another"; the real model then began a reply in her voice, echoed the name, or
     * said "no", and the two words held under half of the first token on 7 of 20 offers, so her
     * answer did not count (household node, 2026-09-30: two offers, neither answered). For a Spanish
     * or Japanese name the smaller model answers in that language, so "sí" and "はい" count as yes.
     * This wording, 26 offers each: every one answered on the 35B and on the 9B.
     */
    public static final Question BOND_NAME = new Question("bond_name",
        "You decide one thing for a companion, as the companion. Someone she shares a deep bond with offers a name "
            + "or a symbol for that bond, to be kept between the two of them. You read who they are to each other, "
            + "their last lines, and the name. "
            + "Answer with exactly one English word, yes or no, whatever language the name is in: "
            + "yes if she takes the name as theirs; "
            + "no if it belittles her or the bond, or does not fit what the two of them share, and she would rather "
            + "be offered a different one.",
        new LinkedHashMap<>(Map.of("yes", "she takes the name as theirs",
                                   "no", "she would rather be offered a different one")),
        Map.of("yes", Set.of("sí", "si", "はい"), "no", Set.of("いいえ")));

    /** The naming question may wait behind a reply in progress. */
    public static final Duration BOND_NAME_TIMEOUT = Duration.ofSeconds(20);

    private TypedDecision() {}

    /**
     * Ask the resident model without waiting. The future completes with the answer, or empty
     * when the model did not answer within {@link #TIMEOUT} or named no option; it never fails.
     */
    public static CompletableFuture<Optional<Answer>> askAsync(String baseUrl, Question q, String state) {
        return askAsync(baseUrl, q, state, TIMEOUT);
    }

    /** {@link #askAsync(String, Question, String)} with its own wait. */
    public static CompletableFuture<Optional<Answer>> askAsync(String baseUrl, Question q, String state, Duration timeout) {
        if (baseUrl == null || state == null || state.isBlank()) return CompletableFuture.completedFuture(Optional.empty());
        try {
            var req = request(baseUrl, q, state, timeout);
            var send = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            var answer = send
                .thenApply(resp -> resp.statusCode() == 200 ? fromLogprobs(resp.body(), q) : Optional.<Answer>empty())
                .exceptionally(e -> {
                    log.debug("typed decision '{}' not answered by the model: {}", q.id(), e.toString());
                    return Optional.empty();
                });
            // Cancelling the answer cancels the exchange: a question nobody will read should not
            // keep a single-slot server busy ahead of her reply.
            answer.whenComplete((r, t) -> {
                if (answer.isCancelled()) send.cancel(true);
            });
            return answer;
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private static HttpRequest request(String baseUrl, Question q, String state, Duration timeout) throws Exception {
        var body = Map.of(
            "messages", List.of(Map.of("role", "system", "content", q.instructions()),
                                Map.of("role", "user", "content", state)),
            "max_tokens", 1, "temperature", 0, "logprobs", true, "top_logprobs", 10,
            "cache_prompt", true,
            "chat_template_kwargs", Map.of("enable_thinking", false));
        return HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(body)))
            .build();
    }

    /**
     * Ask the resident model and wait up to {@link #TIMEOUT}. For tools and measurement only:
     * never call it from an actor.
     */
    public static Optional<Answer> ask(String baseUrl, Question q, String state) {
        if (baseUrl == null || state == null || state.isBlank()) return Optional.empty();
        try {
            var resp = HTTP.send(request(baseUrl, q, state, TIMEOUT), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Optional.empty();
            return fromLogprobs(resp.body(), q);
        } catch (Exception e) {
            log.debug("typed decision '{}' not answered by the model: {}", q.id(), e.toString());
            return Optional.empty();
        }
    }

    /** The answer read from a chat completion's first-token top log-probabilities. */
    static Optional<Answer> fromLogprobs(String responseJson, Question q) {
        try {
            var root = Json.mapper().readTree(responseJson);
            var first = root.path("choices").path(0);
            var top = first.path("logprobs").path("content").path(0).path("top_logprobs");
            var mass = new LinkedHashMap<String, Double>();
            for (var opt : q.options().keySet()) mass.put(opt, 0.0);
            if (top.isArray()) {
                for (var e : top) {
                    var tok = e.path("token").asText("").strip().toLowerCase(Locale.ROOT);
                    if (tok.isEmpty()) continue;
                    for (var opt : q.options().keySet()) {
                        if (opt.startsWith(tok) && tok.length() >= 2 || tok.startsWith(opt)
                                || q.also().getOrDefault(opt, Set.of()).contains(tok)) {
                            mass.merge(opt, Math.exp(e.path("logprob").asDouble(-99)), Double::sum);
                            break;
                        }
                    }
                }
            }
            double total = mass.values().stream().mapToDouble(Double::doubleValue).sum();
            // The options must carry most of the first token's probability. A first token that is
            // the start of an answer ("Sure", "I") with "No" somewhere in the tail is not a
            // decision, and renormalising that tail would make it look like a confident one.
            if (total > 0 && total < MIN_OPTION_MASS) return Optional.empty();
            if (total <= 0) {
                var text = first.path("message").path("content").asText("").strip().toLowerCase(Locale.ROOT);
                for (var opt : q.options().keySet()) if (text.startsWith(opt)) { mass.put(opt, 1.0); total = 1.0; }
                if (total <= 0) return Optional.empty();
            }
            var probs = new LinkedHashMap<String, Double>();
            for (var e : mass.entrySet()) probs.put(e.getKey(), e.getValue() / total);
            var choice = probs.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            return Optional.of(new Answer(choice, probs, "model-logprobs"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The head's classification in the same shape, so callers hold one kind of answer. */
    public static Optional<Answer> fromHead(Classification c) {
        if (c == null || c.label() == null) return Optional.empty();
        var probs = c.probs() == null || c.probs().isEmpty() ? Map.of(c.label(), c.confidence()) : c.probs();
        return Optional.of(new Answer(c.label(), new LinkedHashMap<>(probs), "head:" + c.source()));
    }
}
