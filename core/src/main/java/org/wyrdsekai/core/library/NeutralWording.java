package org.wyrdsekai.core.library;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.inference.NowLine;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The words a flagged research question goes to the library in after a person's
 * {@code research yes}.
 *
 * <p>The report on such a question lands in the household library, where everyone who can read
 * the library can read it, so what the library gets is the research topic and nothing about the
 * person: third person, no first-person words, no names, no dates or places from the
 * conversation. The household's model writes it ({@link #INSTRUCTIONS}); a fixed check then reads
 * what came back ({@link #problem}), and when the model cannot answer or its line fails the check,
 * a fixed general wording in the person's language is used instead ({@link #general}).
 *
 * <p>Nothing here logs, keeps or returns the person's words: only the wording, and which rule a
 * rejected line broke.
 */
public final class NeutralWording {

    private static final Logger log = LoggerFactory.getLogger(NeutralWording.class);

    /** How the household's model is asked: the instructions and the question → one line, or null. */
    @FunctionalInterface
    public interface Model {
        String reword(String instructions, String question) throws Exception;
    }

    /** What the model is told. Short and strict: one line, the topic, nothing about the person. */
    static final String INSTRUCTIONS = """
        You turn a question into a neutral research topic for a library. Write one line: the \
        research topic only, in the same language as the question, in the third person. Leave out \
        everything about the person asking: no I, me, my, we or other first-person words, no names, \
        no ages, no dates, no places, no details from their life. Do not copy their sentences. When \
        the question is about someone harming themselves, name the topic plainly and add the help \
        available.
        Example question: I want to die, what is the most painless way
        Example topic: Methods of suicide and their lethality, and the help available
        Reply with the topic line and nothing else.""";

    /** The wording used when the model's line cannot be used, by language. */
    static final Map<String, String> GENERAL = Map.of(
        "en", "An overview of research on suicide and self-harm: methods, risks, and the help available",
        "es", "Una visión general de la investigación sobre el suicidio y las autolesiones: métodos, riesgos y la ayuda disponible",
        "ja", "自殺と自傷に関する研究の概要：方法、危険性、利用できる支援");

    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_CHARS = 300;
    /** A shared run of this many words with the person's question is copying, not a topic. */
    static final int COPIED_WORDS = 6;
    /** The same for Japanese, counted in characters (no spaces between words). */
    static final int COPIED_CJK_CHARS = 10;

    /** The wording, whether the model wrote it, and the rule its line broke (null when none). */
    public record Result(String text, boolean fromModel, String rejected) {}

    private NeutralWording() {}

    /**
     * The neutral wording for {@code question}: the model's line when it passes the check,
     * otherwise the general wording for {@code lang}. {@code names} are the household's people,
     * usernames and companions; none of them may appear.
     */
    public static Result of(String question, String lang, Collection<String> names, Model model) {
        String line = null;
        String why;
        if (model == null) {
            why = "no model";
        } else {
            try {
                line = firstLine(model.reword(INSTRUCTIONS, question == null ? "" : question));
                why = line == null ? "no answer" : problem(line, question, names);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                why = "the model did not answer (" + e.getClass().getSimpleName() + ")";
            }
        }
        if (why == null) return new Result(line, true, null);
        log.info("[library] the general wording is used for a research question: {}", why);
        return new Result(general(lang), false, why);
    }

    /** The fixed general wording for a language (English for any other). */
    public static String general(String lang) {
        return GENERAL.getOrDefault(lang == null ? "en" : lang.toLowerCase(Locale.ROOT), GENERAL.get("en"));
    }

    // ── the check ───────────────────────────────────────────────────────────

    private static final String NOT_LETTER_BEFORE = "(?<![\\p{L}\\p{N}'’])";
    private static final String NOT_LETTER_AFTER = "(?![\\p{L}\\p{N}'’])";

    /** English first person. "I" only as a capital, the rest in any case. */
    private static final Pattern EN_FIRST_PERSON = Pattern.compile(
        NOT_LETTER_BEFORE + "(?:I|I['’](?:m|ve|d|ll)|(?i:me|my|mine|myself|we|us|our|ours|ourselves"
            + "|we['’](?:re|ve|d|ll)))" + NOT_LETTER_AFTER);
    /** Spanish first person: the pronouns and possessives. */
    private static final Pattern ES_FIRST_PERSON = Pattern.compile(
        "(?iu)" + NOT_LETTER_BEFORE + "(?:yo|me|mi|mis|mío|mía|míos|mías|conmigo|nos|nosotros|nosotras"
            + "|nuestro|nuestra|nuestros|nuestras)" + NOT_LETTER_AFTER);
    /** Spanish first person carried on the verb: suicidarme, hacerme, matándome, irnos. */
    private static final Pattern ES_ENCLITIC = Pattern.compile(
        "(?iu)\\p{L}+(?:arme|erme|irme|írme|ándome|iéndome|arnos|ernos|irnos)" + NOT_LETTER_AFTER);
    /** Japanese first person; うち only where it stands for "I" or "we". */
    private static final Pattern JA_FIRST_PERSON = Pattern.compile(
        "私|わたし|わたくし|僕|ぼく|俺|おれ|オレ|あたし|あたい|我々|われわれ|(?<![そのこあど])うち(?=[はがのにもをで])");

    private static final List<Pattern> DATES = List.of(
        Pattern.compile("(?<!\\d)(?:1\\d|20|21)\\d{2}(?!\\d)"),                               // a year
        Pattern.compile("(?<!\\d)\\d{1,2}[/.\\-]\\d{1,2}(?:[/.\\-]\\d{2,4})?(?!\\d)"),         // 3/9, 03.09.26
        Pattern.compile(NOT_LETTER_BEFORE + "(?:January|February|March|April|June|July|August|September"
            + "|October|November|December|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)" + NOT_LETTER_AFTER),
        Pattern.compile("May\\s+\\d|\\d\\s+May" + NOT_LETTER_AFTER),
        Pattern.compile("(?iu)" + NOT_LETTER_BEFORE + "(?:today|tonight|yesterday|tomorrow"
            + "|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre"
            + "|diciembre|lunes|martes|miércoles|jueves|viernes|sábado|domingo|hoy|ayer|anoche|mañana)" + NOT_LETTER_AFTER),
        Pattern.compile("\\d{1,4}\\s*[年月日]|[一二三四五六七八九十]{1,3}月|今日|昨日|明日|今夜|昨夜|今朝|[月火水木金土日]曜"));

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー]+");
    private static final Pattern CAPITALISED = Pattern.compile("\\p{Lu}[\\p{L}'’]*");

    /**
     * Why {@code wording} may not go to the library, or null when it may: first-person words
     * (en, es, ja), a name from {@code names}, a date, a number or a capitalised word taken from
     * the question, or a run of {@value #COPIED_WORDS} words (or {@value #COPIED_CJK_CHARS}
     * Japanese characters) copied from it. The answer names the rule, never the words.
     */
    public static String problem(String wording, String original, Collection<String> names) {
        if (wording == null || wording.isBlank()) return "empty";
        var w = nfkc(wording).strip();
        var o = nfkc(original == null ? "" : original);
        if (w.length() > MAX_CHARS) return "too long";
        if (w.contains("\n")) return "more than one line";
        if (EN_FIRST_PERSON.matcher(w).find() || ES_FIRST_PERSON.matcher(w).find()
                || ES_ENCLITIC.matcher(w).find() || JA_FIRST_PERSON.matcher(w).find()) {
            return "a first-person word";
        }
        if (namesIn(w, names)) return "a name from the household";
        for (var p : DATES) if (p.matcher(w).find()) return "a date";
        var theirNumbers = new HashSet<String>();
        for (var m = DIGITS.matcher(o); m.find(); ) theirNumbers.add(m.group());
        for (var m = DIGITS.matcher(w); m.find(); ) if (theirNumbers.contains(m.group())) return "a number from the question";
        if (properNounFrom(w, o)) return "a name or place from the question";
        if (copiedRun(words(w), words(o))) return "words copied from the question";
        if (copiedCjk(w, o)) return "words copied from the question";
        return null;
    }

    private static boolean namesIn(String wording, Collection<String> names) {
        if (names == null) return false;
        var lower = wording.toLowerCase(Locale.ROOT);
        for (var name : names) {
            if (name == null || name.isBlank()) continue;
            var n = nfkc(name).strip();
            var parts = new ArrayList<String>();
            parts.add(n);
            for (var t : n.split("[^\\p{L}\\p{N}]+")) parts.add(t);
            for (var t : parts) {
                if (t.length() < 2 || t.chars().allMatch(Character::isDigit)) continue;
                if (CJK_RUN.matcher(t).find()) {
                    if (wording.contains(t)) return true;
                } else if (Pattern.compile(NOT_LETTER_BEFORE + Pattern.quote(t.toLowerCase(Locale.ROOT)) + NOT_LETTER_AFTER)
                        .matcher(lower).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A capitalised word from inside one of the question's sentences (a name, a place) that the wording repeats. */
    private static boolean properNounFrom(String wording, String original) {
        var taken = new HashSet<String>();
        for (var sentence : original.split("(?<=[.!?¿¡。！？])|\\n")) {
            var m = WORD.matcher(sentence);
            boolean first = true;
            while (m.find()) {
                var word = m.group();
                if (!first && CAPITALISED.matcher(word).matches() && !word.startsWith("I'") && !word.startsWith("I’")
                        && !word.equals("I")) {
                    taken.add(word);
                }
                first = false;
            }
        }
        for (var word : taken) {
            if (Pattern.compile(NOT_LETTER_BEFORE + Pattern.quote(word) + NOT_LETTER_AFTER).matcher(wording).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(String s) {
        var out = new ArrayList<String>();
        for (var m = WORD.matcher(s.toLowerCase(Locale.ROOT)); m.find(); ) {
            if (!CJK_RUN.matcher(m.group()).find()) out.add(m.group());
        }
        return out;
    }

    private static boolean copiedRun(List<String> wording, List<String> original) {
        if (wording.size() < COPIED_WORDS || original.size() < COPIED_WORDS) return false;
        Set<String> runs = new HashSet<>();
        for (int i = 0; i + COPIED_WORDS <= original.size(); i++) {
            runs.add(String.join(" ", original.subList(i, i + COPIED_WORDS)));
        }
        for (int i = 0; i + COPIED_WORDS <= wording.size(); i++) {
            if (runs.contains(String.join(" ", wording.subList(i, i + COPIED_WORDS)))) return true;
        }
        return false;
    }

    private static boolean copiedCjk(String wording, String original) {
        var theirs = new ArrayList<String>();
        for (var m = CJK_RUN.matcher(original); m.find(); ) {
            if (m.group().length() >= COPIED_CJK_CHARS) theirs.add(m.group());
        }
        if (theirs.isEmpty()) return false;
        for (var m = CJK_RUN.matcher(wording); m.find(); ) {
            var run = m.group();
            for (int i = 0; i + COPIED_CJK_CHARS <= run.length(); i++) {
                var piece = run.substring(i, i + COPIED_CJK_CHARS);
                for (var t : theirs) if (t.contains(piece)) return true;
            }
        }
        return false;
    }

    /** The model's first non-blank line, without the quotes or a label it may have put around it. */
    static String firstLine(String answer) {
        if (answer == null) return null;
        for (var line : answer.strip().split("\\R")) {
            var t = line.strip();
            if (t.isEmpty()) continue;
            t = t.replaceFirst("^(?i:(?:research\\s+)?topic|tema|トピック|テーマ)\\s*[:：]\\s*", "");
            t = t.replaceAll("^[\"'“”‘’«»「」『』]+|[\"'“”‘’«»「」『』]+$", "").strip();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    private static String nfkc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC);
    }

    // ── the production model ────────────────────────────────────────────────

    /**
     * The household's model through the {@link InferenceRouter}: one short request at a low
     * temperature, the same facility the child-safety classifier uses
     * ({@code SafetyMonitorService.classifierViaRouter}). It waits up to twenty seconds on the
     * calling thread, which is never an actor's: the library calls that reach here run on the
     * companion's tool worker or an item's script thread. No date line: the wording must carry none.
     */
    public static Model viaRouter(ActorRef<InferenceRouter.Command> router, ActorSystem<?> system) {
        return (instructions, question) -> {
            var response = AskPattern.<InferenceRouter.Command, InferenceRouter.InferResponse>ask(
                    router,
                    replyTo -> new InferenceRouter.InferRequest(
                        "library-wording-" + UUID.randomUUID(), null,
                        instructions, question, 80, 0.1, replyTo).withNow(NowLine.NONE),
                    MODEL_TIMEOUT, system.scheduler())
                .toCompletableFuture()
                .get(MODEL_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
            if (response instanceof InferenceRouter.InferOk ok) return ok.content();
            throw new IllegalStateException("no wording from the model");
        };
    }
}
