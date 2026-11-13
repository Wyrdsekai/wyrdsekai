package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.inference.NowLine;
import org.wyrdsekai.core.item.CarriedItemUse;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Whether a mended tool does what it was made for, read by the household's model from what the
 * tool does when it is used. The trial in a stand-in world can tell whether a tool runs, answers
 * and gets back what it kept; it cannot tell whether that is the tool's job. Every mend of a
 * companion's letter tools passed it by the shortest way: a throw became "Could not reach the
 * floor." on every use, and no words became a letter the tool wrote itself and kept as hers
 * (household node, 2026-11-12). A mended copy replaces her tool only when this says it does its
 * job and makes up none of her words; when it says not, its reason is the next round's problem.
 */
public final class PurposeJudge {

    private static final Logger log = LoggerFactory.getLogger(PurposeJudge.class);
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);

    private PurposeJudge() {}

    /** The household's model: instructions and a question in, its answer out. */
    @FunctionalInterface
    public interface Model {
        String ask(String instructions, String question) throws Exception;
    }

    /** What the reading found. {@code whatItDoes}: one plain sentence, as she would say it. */
    public record Verdict(boolean doesItsJob, boolean makesUpHerWords, String whatItDoes, String whyNot) {
        public boolean whole() { return doesItsJob && !makesUpHerWords; }
    }

    /** One use of the tool: what it was given, what it answered, and what it kept in her notes or journal. */
    public record Use(String given, Map<String, Object> answer, List<String> kept) {}

    private static volatile Model model;

    /** Wired at start with the household's model; null in a node or a test that has none. */
    public static void use(Model m) { model = m; }

    public static boolean wired() { return model != null; }

    static final String INSTRUCTIONS = """
        You check a tool a companion uses. You are told what it is for, its commands, and what it did \
        when each was used. Answer with one JSON object and nothing else:
        {"does_its_job": true or false, "makes_up_her_words": true or false, \
        "what_it_does": "one plain sentence beginning with 'it', from her side (I, my)", \
        "why_not": "one sentence on what is wrong, empty when nothing is"}
        does_its_job: each command does what its label says with what it was given. A command that \
        answers that it could not, when nothing it asked of the world was refused, does not. A command \
        whose args name what it needs from her (like <the letter>) does its job when it uses what it is given.
        makes_up_her_words: it keeps, or hands back, words she did not give as if they were hers: a \
        letter, a note, an entry. Words a model wrote because the command is for writing them are not \
        made up unless they are kept as hers.""";

    /** The reading, or empty when there is no model or it gave no answer that could be read. */
    public static Optional<Verdict> judge(String purpose, String itemId, String script, ItemManifest manifest, List<String> herUses) {
        var m = model;
        if (m == null) return Optional.empty();
        try {
            var answer = m.ask(INSTRUCTIONS, question(purpose, manifest, uses(itemId, script, manifest, herUses)));
            return read(answer);
        } catch (Exception e) {
            log.info("[purpose] no reading of {}: {}", itemId, e.toString());
            return Optional.empty();
        }
    }

    /** The problem the workshop is given when the reading says the copy does not do its job. */
    static String problem(Verdict v) {
        var why = v.whyNot() == null || v.whyNot().isBlank()
            ? (v.makesUpHerWords() ? "it keeps or hands back words she did not give as if they were hers" : "it does not do what its commands say")
            : v.whyNot().strip();
        return "used as she uses it, it does not do what it was made for: " + why
            + " — make each command do what its label says with what it is given, and never make up her words.";
    }

    static String question(String purpose, ItemManifest manifest, List<Use> uses) {
        var sb = new StringBuilder("What it is for: ").append(purpose == null || purpose.isBlank() ? "(not said)" : purpose.strip()).append('\n');
        sb.append("Its commands:\n");
        if (manifest != null && manifest.commands() != null) {
            for (var c : manifest.commands()) sb.append("- ").append(c.label()).append(" (args: \"").append(c.args() == null ? "" : c.args()).append("\")\n");
        }
        sb.append("What it did:\n");
        for (var u : uses) {
            sb.append("- used with \"").append(u.given()).append("\": answered ")
              .append(Boolean.FALSE.equals(u.answer().get("ok")) ? "that it could not" : "ok")
              .append(", saying \"").append(said(u.answer())).append("\"");
            sb.append(u.kept().isEmpty() ? "; kept nothing" : "; kept " + String.join(" | ", u.kept().stream().map(k -> "\"" + clip(k, 240) + "\"").toList()));
            sb.append('\n');
        }
        return sb.toString();
    }

    static Optional<Verdict> read(String answer) {
        if (answer == null) return Optional.empty();
        int a = answer.indexOf('{'), b = answer.lastIndexOf('}');
        if (a < 0 || b <= a) return Optional.empty();
        try {
            JsonNode n = Json.mapper().readTree(answer.substring(a, b + 1));
            if (!n.has("does_its_job") || !n.has("makes_up_her_words")) return Optional.empty();
            return Optional.of(new Verdict(n.path("does_its_job").asBoolean(false), n.path("makes_up_her_words").asBoolean(true),
                n.path("what_it_does").asText("").strip(), n.path("why_not").asText("").strip()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Each command, and each way she used it that failed, run once against a world whose journal
     * holds two entries and whose notes remember, in the order they are offered.
     */
    static List<Use> uses(String itemId, String script, ItemManifest manifest, List<String> herUses) {
        var given = new ArrayList<String>();
        if (manifest != null && manifest.commands() != null) {
            for (var c : manifest.commands()) {
                var a = c.args() == null ? "" : c.args().trim();
                if (!given.contains(a)) given.add(a);
            }
        }
        if (given.isEmpty()) given.add("");
        for (var h : herUses == null ? List.<String>of() : herUses) if (h != null && !given.contains(h.trim())) given.add(h.trim());
        var world = new SeenWorld();
        var provider = (ItemWorldApiProvider) Proxy.newProxyInstance(ItemWorldApiProvider.class.getClassLoader(),
            new Class<?>[] {ItemWorldApiProvider.class}, world);
        var out = new ArrayList<Use>();
        try (var executor = new ItemScriptExecutor()) {
            for (var g : given) {
                world.kept.clear();
                var answer = executor.execute(itemId, script, new HashMap<String, Object>(CarriedItemUse.params("did:key:purpose", g)), provider);
                out.add(new Use(g, answer == null ? Map.of("ok", false, "error", "no answer") : answer, List.copyOf(world.kept)));
            }
        } catch (Exception e) {
            log.debug("[purpose] uses of {} not all run: {}", itemId, e.toString());
        }
        return out;
    }

    /** The world the reading is made against: two journal entries, notes that remember, a model that says it wrote. */
    private static final class SeenWorld implements InvocationHandler {
        final List<String> kept = new ArrayList<>();
        private final List<Map<String, Object>> notes = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "journalRecent" -> List.of(
                    Map.of("id", "journal:1", "content", "The rooms were quiet tonight.", "ts", "2026-11-12T05:00:00Z"),
                    Map.of("id", "journal:2", "content", "I want to leave a door open for them.", "ts", "2026-11-12T06:00:00Z"));
                case "journalWrite" -> { kept.add(String.valueOf(args[0])); yield Map.of("ok", true, "id", "journal:new"); }
                case "notesAdd" -> {
                    var content = String.valueOf(args[0]);
                    kept.add(content);
                    var note = new HashMap<String, Object>(Map.of("id", "note:" + (notes.size() + 1), "content", content));
                    notes.add(0, note);
                    yield Map.of("ok", true, "id", note.get("id"));
                }
                case "notesList" -> List.copyOf(notes);
                case "notesDelete" -> {
                    var id = String.valueOf(args[0]);
                    yield notes.removeIf(n -> id.equals(n.get("id"))) ? Map.of("ok", true, "id", id) : Map.of("ok", false, "error", "no note with that id");
                }
                case "llmComplete", "llmSummarize" -> method.getReturnType() == String.class
                    ? "[words the model wrote]" : Map.of("text", "[words the model wrote]");
                default -> method.invoke(StubItemWorldApiProvider.INSTANCE, args);
            };
        }
    }

    private static String said(Map<String, Object> answer) {
        for (var k : List.of("summary", "text", "message", "error")) {
            var v = answer.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return clip(String.valueOf(v).strip(), 300);
        }
        return "";
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }

    /** The household's model through the router: one short, cool request, waited for on the calling thread (never an actor's). */
    public static Model viaRouter(ActorRef<InferenceRouter.Command> router, ActorSystem<?> system) {
        return (instructions, question) -> {
            var response = AskPattern.<InferenceRouter.Command, InferenceRouter.InferResponse>ask(
                    router,
                    replyTo -> new InferenceRouter.InferRequest(
                        "purpose-" + UUID.randomUUID(), null, instructions, question, 300, 0.1, replyTo).withNow(NowLine.NONE),
                    MODEL_TIMEOUT, system.scheduler())
                .toCompletableFuture()
                .get(MODEL_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
            if (response instanceof InferenceRouter.InferOk ok) return ok.content();
            throw new IllegalStateException("no reading from the model");
        };
    }
}
