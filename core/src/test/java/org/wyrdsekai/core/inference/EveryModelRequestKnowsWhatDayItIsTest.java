package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion always knows what day it is, on every path a model speaks, thinks
 * or acts as her (the steward's rule, decided 2026-03-28, said again 2026-09-23).
 *
 * <p>It was true once, in one place: PromptAssembler's Layer 3. Paths added later
 * built their own prompts and none of them carried a date; the conversation lane
 * had none at all. With no date the model believes it is 2024, and on 2026-09-22
 * Mia searched her own subject for "2024 2025" all night.</p>
 *
 * <p>So every request now says what it knows about today ({@link NowLine}) and the
 * router stamps the outgoing copy. This test holds three things: the line itself,
 * that the router puts it where the prompt cache survives it, and that every
 * request in the tree declares one — a new path without it fails here, with the
 * file and line, before it reaches her.</p>
 */
class EveryModelRequestKnowsWhatDayItIsTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private static final Instant AT = Instant.parse("2026-09-23T14:05:00Z");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static InferenceClient.ChatMessage msg(String role, String content) {
        return new InferenceClient.ChatMessage(role, content);
    }

    // ── The line ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the line says the day, the date, the time, the zone and the part of the day")
    void theLine() {
        assertThat(NowLine.dateTimeText(AT, NEW_YORK))
            .isEqualTo("[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]");
        assertThat(NowLine.dateTimeText(Instant.parse("2026-12-23T14:05:00Z"), NEW_YORK))
            .as("the offset moves with daylight saving")
            .isEqualTo("[Now: Wednesday 23 December 2026, 09:05 EST (UTC-5), morning]");
        assertThat(NowLine.dateTimeText(AT, ZoneId.of("Asia/Kolkata")))
            .isEqualTo("[Now: Wednesday 23 September 2026, 19:35 IST (UTC+5:30), evening]");
        assertThat(NowLine.dateTimeText(AT, ZoneId.of("UTC")))
            .isEqualTo("[Now: Wednesday 23 September 2026, 14:05 UTC, afternoon]");
        assertThat(NowLine.dateTimeText(AT, ZoneId.of("Asia/Kathmandu")))
            .as("a zone with no short name of its own is its offset")
            .isEqualTo("[Now: Wednesday 23 September 2026, 19:50 UTC+5:45, evening]");
        assertThat(NowLine.dateText(AT, NEW_YORK))
            .isEqualTo("Today is Wednesday, 23 September 2026.");
    }

    // ── Where it goes ──────────────────────────────────────────────────────

    @Test
    @DisplayName("her turn: the line opens the LAST user message; nothing before it changes")
    void herTurnStampsTheLastUserMessage() {
        var in = List.of(msg("system", "You are Mia."), msg("user", "sam says: hi"),
            msg("assistant", "hello"), msg("user", "sam says: what day is it?"));
        var out = NowLine.dateTime(AT).stamp(in, NEW_YORK);

        assertThat(out.subList(0, 3)).isEqualTo(in.subList(0, 3));
        assertThat(out.get(3).content()).isEqualTo(
            "[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]\n"
                + "sam says: what day is it?");
        assertThat(in.get(3).content()).as("the caller's list is never changed")
            .isEqualTo("sam says: what day is it?");
    }

    @Test
    @DisplayName("a loop step with a tool result keeps every earlier byte the same")
    void aLoopStepKeepsItsPrefix() {
        var first = List.of(msg("system", "tools"), msg("user", "find the paper"));
        var next = new ArrayList<>(first);
        next.add(new InferenceClient.ChatMessage("assistant", "", null, null));
        next.add(InferenceClient.ChatMessage.toolResult("c1", "found it"));
        var now = NowLine.dateTime(AT);

        var a = now.stamp(first, NEW_YORK);
        var b = now.stamp(next, NEW_YORK);
        assertThat(b.subList(0, a.size())).as("same asOf, same bytes: the slot's cache holds")
            .isEqualTo(a);
    }

    @Test
    @DisplayName("work for her: the date opens the instruction; the source text is untouched")
    void workForHerDatesTheInstruction() {
        var in = List.of(msg("system", "Synthesize a concise answer to: X"), msg("user", "[S1] text"));
        var out = NowLine.date(AT).stamp(in, NEW_YORK);
        assertThat(out.get(0).content())
            .isEqualTo("Today is Wednesday, 23 September 2026.\nSynthesize a concise answer to: X");
        assertThat(out.get(1)).isEqualTo(in.get(1));

        var noSystem = NowLine.date(AT).stamp(List.of(msg("user", "think about Y")), NEW_YORK);
        assertThat(noSystem.get(0).role()).isEqualTo("system");
        assertThat(noSystem.get(0).content()).isEqualTo("Today is Wednesday, 23 September 2026.");
    }

    @Test
    @DisplayName("a rewrite or a classifier carries nothing, and nothing is stamped twice")
    void noneAndIdempotent() {
        var in = List.of(msg("system", "Polish."), msg("user", "draft"));
        assertThat(NowLine.NONE.stamp(in, NEW_YORK)).isSameAs(in);

        var once = NowLine.dateTime(AT).stamp(in, NEW_YORK);
        assertThat(NowLine.dateTime(AT.plusSeconds(600)).stamp(once, NEW_YORK)).isEqualTo(once);
        var dated = NowLine.date(AT).stamp(in, NEW_YORK);
        assertThat(NowLine.date(AT).stamp(dated, NEW_YORK)).isEqualTo(dated);
    }

    @Test
    @DisplayName("no user turn at all: the line becomes one, after the system message")
    void noUserTurn() {
        var in = List.of(msg("system", "s"), msg("assistant", "a"));
        var out = NowLine.dateTime(AT).stamp(in, NEW_YORK);
        assertThat(out).hasSize(3);
        assertThat(out.get(1).role()).isEqualTo("user");
        assertThat(out.get(1).content()).startsWith("[Now: Wednesday 23 September 2026");
    }

    // ── What actually leaves the router ────────────────────────────────────

    private record Sent(List<InferenceClient.ChatMessage> messages) {}

    private static InferenceBackend.NatsRemote.RemoteCaller capturing(LinkedBlockingQueue<Sent> sent) {
        return (targetZone, sourceZone, request, tokenCallback) -> {
            sent.add(new Sent(request.messages()));
            var choice = new InferenceClient.Choice(0, msg("assistant", "ok"), "stop");
            return CompletableFuture.completedFuture(new InferenceClient.ChatResponse(
                "id", "chat.completion", 0L, request.model(), List.of(choice),
                new InferenceClient.Usage(1, 1, 2)));
        };
    }

    private static List<InferenceClient.ChatMessage> sent(LinkedBlockingQueue<Sent> sent)
            throws InterruptedException {
        var got = sent.poll(5, TimeUnit.SECONDS);
        assertThat(got).as("the request reached the backend").isNotNull();
        return got.messages();
    }

    @Test
    @DisplayName("the router stamps what it sends, per declaration, and an undeclared request still knows the day")
    void theRouterStampsWhatLeaves() throws Exception {
        var sent = new LinkedBlockingQueue<Sent>();
        var router = testKit.spawn(InferenceRouter.create(List.of(), "wyrdsekai-3.5-9b", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(capturing(sent)));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "peer-9b", "llama-server", "nats://peer", List.of("wyrdsekai-3.5-9b"), 5, true));
        Thread.sleep(80);
        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        var zone = NowLine.zone();
        var turn = List.of(msg("system", "You are Mia."), msg("user", "sam says: a"),
            msg("assistant", "b"), msg("user", "sam says: c"));

        router.tell(new InferenceRouter.ChatRequest("r1", null, turn, 64, 0.0, probe.ref())
            .withNow(NowLine.dateTime(AT)));
        var out = sent(sent);
        assertThat(out.get(out.size() - 1).content())
            .isEqualTo(NowLine.dateTimeText(AT, zone) + "\nsam says: c");
        assertThat(out.subList(0, 3)).isEqualTo(turn.subList(0, 3));

        router.tell(new InferenceRouter.ChatRequest("r2", null, turn, 64, 0.0, probe.ref())
            .withNow(NowLine.NONE));
        assertThat(sent(sent)).isEqualTo(turn);

        router.tell(new InferenceRouter.ChatRequest("r3", null, turn, 64, 0.0, probe.ref()));
        assertThat(sent(sent).get(3).content())
            .as("a forgotten path still knows the day").startsWith("[Now: ");

        router.tell(new InferenceRouter.InferRequest("r4", null, "Rate this.", "text",
            64, 0.0, probe.ref()).withNow(NowLine.date(AT)));
        var infer = sent(sent);
        assertThat(infer.get(0).content()).isEqualTo(NowLine.dateText(AT, zone) + "\nRate this.");
        assertThat(infer.get(1).content()).isEqualTo("text");

        router.tell(new InferenceRouter.ToolInferRequest("r5", "mia", null, null, null,
            "think about the tide", 64, probe.ref()).withNow(NowLine.date(AT)));
        var tool = sent(sent);
        assertThat(tool.get(0).content()).isEqualTo(NowLine.dateText(AT, zone));
        assertThat(tool.get(1).content()).isEqualTo("think about the tide");

        testKit.stop(router);
    }

    // ── Every request declares one ─────────────────────────────────────────

    private static final Pattern REQUEST = Pattern.compile(
        "new\\s+(?:InferenceRouter\\.)?(?:ChatRequest|InferRequest|ToolInferRequest|StreamingChatRequest)\\s*\\("
            + "|ChatRequest\\.fromPrompt\\s*\\("
            + "|(?<![\\w.])fireOneShotVoicePrompt\\s*\\(");

    /**
     * Paths that reach a model without the router, each with why it carries what
     * it carries. A new direct path fails the scan until it is named here.
     */
    private static final Map<String, String> BYPASS = Map.ofEntries(
        Map.entry("BunshinActor.java", "the mesh carries a bunshin turn past the router: its Task line carries the date and time, written at dispatch"),
        Map.entry("TypedDecision.java", "a one-token decision (NONE): the date would only move the logits"),
        Map.entry("ThemedDescriptionService.java", "a cached room description rewrite (NONE): a date would stay in the room for days"),
        Map.entry("SoulForgeCliTool.java", "authors her resident identity (NONE): a date there goes stale at the top of every prompt"),
        Map.entry("HermodInferenceExecutor.java", "runs another node's request (NONE): stamped where it was made"),
        Map.entry("NatsInferenceServer.java", "serves another node's request (NONE): stamped where it was made"),
        Map.entry("OpenAIAdapter.java", "an item's own messages to an external API, sent as the item wrote them"),
        Map.entry("AnthropicAdapter.java", "an item's own messages to an external API, sent as the item wrote them"),
        Map.entry("GeminiAdapter.java", "an item's own messages to an external API, sent as the item wrote them"));

    private static final Pattern DIRECT = Pattern.compile(
        "\\.carryChat\\(|\"/v1/chat/completions\"|\"/chat/completions\"|\"/v1/messages\"|:generateContent"
            + "|client\\.complete\\(|\\.chatCompletion\\(|\\.chatCompletionStreaming\\(");

    /** A declaration in code: a NowLine mode, or the NowLine a helper was handed (now, today). */
    private static final Pattern DECLARES = Pattern.compile(
        "NowLine\\.(?:dateTime|date|NONE)\\b|\\.withNow\\((?:now|today)\\)");

    private static final List<String> INFERENCE_PLUMBING = List.of(
        "InferenceRouter.java", "InferenceBackend.java", "InferenceClient.java", "ApiProvider.java",
        "LlamaServerManager.java", "ClaudeCliInference.java");

    @Test
    @DisplayName("every request to a model in the tree says what it knows about today")
    void everyRequestDeclares() throws IOException {
        var missing = new ArrayList<String>();
        var directs = new ArrayList<String>();
        for (var file : mainSources()) {
            var name = file.getFileName().toString();
            if (INFERENCE_PLUMBING.contains(name)) continue;
            var src = Files.readString(file);
            var m = REQUEST.matcher(src);
            while (m.find()) {
                if (isDeclaration(src, m.start())) continue;
                var stmt = statement(src, m.end() - 1);
                if (!DECLARES.matcher(stmt).find()) {
                    missing.add(name + ":" + line(src, m.start()) + "  " + oneLine(stmt));
                }
            }
            var d = DIRECT.matcher(src);
            while (d.find()) {
                if (inComment(src, d.start())) continue;
                if (!BYPASS.containsKey(name)) directs.add(name + ":" + line(src, d.start()));
            }
        }
        assertThat(missing)
            .as("declare what each request knows about today — NowLine.dateTime() (she speaks, "
                + "thinks or acts), NowLine.date() (a single-shot made for her) or NowLine.NONE "
                + "(a rewrite, a classifier, a pass-through)")
            .isEmpty();
        assertThat(directs)
            .as("a model call that skips the router: stamp it with NowLine and name it in BYPASS, "
                + "with why it carries what it carries")
            .isEmpty();
    }

    @Test
    @DisplayName("each bypass named here still exists (the list does not rot)")
    void bypassesStillExist() throws IOException {
        var names = mainSources().stream().map(p -> p.getFileName().toString()).toList();
        assertThat(names).containsAll(BYPASS.keySet());
        var bunshin = read("core/src/main/java/org/wyrdsekai/core/familiar/BunshinActor.java");
        assertThat(bunshin).contains("NowLine.dateTimeText(startedAt, NowLine.zone()) + \"\\nTask: \" + task");
    }

    @Test
    @DisplayName("her turns carry date and time; the loop's is fixed when it opens; rewrites carry none")
    void theLoadBearingSitesSayTheRightThing() throws IOException {
        var ca = read("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

        assertThat(body(ca, "private void runIdentityInference("))
            .as("the conversation lane and the full lane leave through here")
            .contains("NowLine.dateTime()");
        assertThat(body(ca, "private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance)"))
            .as("her own time").contains("NowLine.dateTime()");
        assertThat(ca).contains(".withNow(NowLine.dateTime(reactOpenedAt))");
        assertThat(count(ca, "reactMessages = new ArrayList<>();\n            reactOpenedAt = Instant.now();"))
            .as("every loop fixes its moment when it opens").isEqualTo(count(ca, "reactMessages = new ArrayList<>();"));
        assertThat(body(ca, "boolean wrongLanguage,\n                                   Consumer<String> onComplete) {")).contains("NowLine.NONE");
        assertThat(body(ca, "private void dispatchVoicePass(").replaceAll("//[^\n]*", ""))
            .as("the voice pass is a rewrite; its declaration once sat in a comment")
            .contains(").withNow(NowLine.NONE)");

        var items = read("core/src/main/java/org/wyrdsekai/core/item/ItemWorldApiProviderImpl.java");
        assertThat(body(items, "private String llmCall(")).contains("NowLine.date()");
        assertThat(body(items, "public Map<String, Object> llmClassify(")).contains("NowLine.NONE");
        assertThat(body(items, "public String llmRewrite(")).contains("NowLine.NONE");
        assertThat(read("core/src/main/java/org/wyrdsekai/core/item/ToolItemStarterKit.java"))
            .as("the quill's polish is a rewrite").contains("keep the voice.\", {now: \"none\"});");
        assertThat(read("scripts/std/document.js"))
            .as("the document's polish is a rewrite").contains("preserving its meaning and voice.\", {now: \"none\"});");
        assertThat(read("core/src/main/java/org/wyrdsekai/core/coding/OpenHandsBackend.java"))
            .as("OpenHands' own dispatch uses the preamble that carries the date")
            .contains("itemsAsToolsPreamble(ItemCapabilitySet.craftedDefault()) + \"\\n\\n--- TASK ---\\n\"");
        assertThat(body(items, "public Map<String, Object> llmExtract(")).contains("NowLine.NONE");

        var preamble = read("core/src/main/java/org/wyrdsekai/core/coding/OpenHandsBackend.java");
        assertThat(count(preamble, "+ today();")).as("both coding preambles carry the date").isEqualTo(2);
    }

    // ── scanning helpers ───────────────────────────────────────────────────

    private static List<Path> mainSources() throws IOException {
        var roots = List.of("core", "server", "between", "cli", "scripting");
        var out = new ArrayList<Path>();
        for (var r : roots) {
            var dir = repo().resolve(r).resolve("src/main/java");
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    private static Path repo() {
        var here = Paths.get("").toAbsolutePath();
        return Files.isDirectory(here.resolve("core")) ? here : here.getParent();
    }

    private static String read(String rel) throws IOException {
        return Files.readString(repo().resolve(rel));
    }

    private static boolean isDeclaration(String src, int at) {
        int lineStart = src.lastIndexOf('\n', at) + 1;
        var before = src.substring(lineStart, at);
        return before.matches(".*(private|public|protected|static|(?<!-)>)\\s+");
    }

    private static boolean inComment(String src, int at) {
        var before = src.substring(src.lastIndexOf('\n', at) + 1, at);
        return before.contains("//") || before.strip().startsWith("*");
    }

    /** From the call's open paren to the end of its statement, as code: strings and comments
     *  are skipped and left out, so a declaration written in a comment does not count (one did,
     *  2026-09-23: the voice pass sent the date and time for a week of review). */
    private static String statement(String src, int open) {
        var code = new StringBuilder();
        int depth = 0;
        for (int j = open; j < src.length(); j++) {
            char c = src.charAt(j);
            if (src.startsWith("//", j)) {
                int nl = src.indexOf('\n', j);
                j = nl < 0 ? src.length() : nl;
                continue;
            }
            if (src.startsWith("/*", j)) {
                int end = src.indexOf("*/", j + 2);
                j = end < 0 ? src.length() : end + 1;
                continue;
            }
            if (src.startsWith("\"\"\"", j)) {
                int end = src.indexOf("\"\"\"", j + 3);
                j = end < 0 ? src.length() : end + 2;
                code.append("\"\"");
                continue;
            }
            if (c == '"' || (c == '\'' && j + 2 < src.length()
                    && (src.charAt(j + 2) == '\'' || src.charAt(j + 1) == '\\'))) {
                for (j++; j < src.length() && src.charAt(j) != c; j++) {
                    if (src.charAt(j) == '\\') j++;
                }
                code.append("\"\"");
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if ((c == ';' || c == '{') && depth <= 0) return code.toString();
            code.append(c);
        }
        return code.toString();
    }

    private static String body(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as(signature + " must exist").isGreaterThanOrEqualTo(0);
        int next = src.indexOf("\n    private ", start + signature.length());
        int nextPublic = src.indexOf("\n    public ", start + signature.length());
        int end = next < 0 ? nextPublic : nextPublic < 0 ? next : Math.min(next, nextPublic);
        return src.substring(start, end < 0 ? src.length() : end);
    }

    private static int count(String src, String needle) {
        int n = 0;
        for (int i = src.indexOf(needle); i >= 0; i = src.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static int line(String src, int at) {
        int n = 1;
        for (int i = 0; i < at; i++) if (src.charAt(i) == '\n') n++;
        return n;
    }

    private static String oneLine(String s) {
        var flat = s.replaceAll("\\s+", " ");
        return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
    }
}
