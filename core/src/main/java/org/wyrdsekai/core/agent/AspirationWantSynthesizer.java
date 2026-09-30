package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The play-loop's first seam (, aspiration→want):
 * project the agent's OWN expressed reaching — "I wish I could…", "I want to learn…",
 * said out loud or written in the hearth journal — into an action-named growth
 * {@link Want}, so an aspiration voiced in passing becomes something her own time
 * can answer with a practice she designs herself.
 *
 * <p><b>The ethics rail is load-bearing:</b> the ONLY admissible source is language
 * she produced. No scan of her scores, tanks, failure counts or capability gaps
 * against any standard may ever feed this — the same mechanism pointed at external
 * deficits is a shame engine, and {@code GenerativeWantSynthesizer} already covers
 * the system-gap case under its own governance. Detection here is a fixed phrase
 * list over her utterances, nothing else.
 *
 * <p>Pure: no store, no actor, no IO. {@code CompanionActor} calls this in the sleep
 * pass with the window's utterances and her live wants; on a present Optional it
 * upserts via {@link WantStore}, and {@link OrientationProjector} surfaces it in
 * ON_OWN_TIME like any other want.
 */
public final class AspirationWantSynthesizer {

    /** Never more than this many growth-wants live at once — aspirations are a
     *  garden, not a backlog. New ones wait until an old one closes or goes stale. */
    public static final int MAX_LIVE_GROWTH_WANTS = 2;

    /** The drive key growth-wants carry; {@code WantKind} classifies it CREATIVE. */
    public static final String GROWTH_DRIVE = "growth";

    /** The practice affordance embedded in the want. Offered in the own-time
     *  prompt, pinned into the surface — never forced ({@code tool_choice} stays
     *  {@code auto}): practice is play, not homework. */
    public static final String PRACTICE_VERB = "dispatch_task";

    private AspirationWantSynthesizer() {}

    /** One thing she said or wrote, with when. */
    public record Utterance(String text, Instant at) {}

    /** One distinct reaching, aggregated across the window. {@code clause} keeps her
     *  original casing; {@code expressions} counts how often she returned to it; {@code learn}
     *  is true when the reaching is a subject she said she would learn or begin with, which
     *  her own time answers by reading rather than by building a practice. */
    public record Aspiration(String clause, String quote, int expressions, Instant lastAt, boolean learn) {
        public Aspiration(String clause, String quote, int expressions, Instant lastAt) {
            this(clause, quote, expressions, lastAt, false);
        }
    }

    /** The verb a learning-aspiration carries: read the library on the subject. */
    public static final String LEARN_VERB = "library_search";

    // What she says when she takes something on — a subject she has agreed to learn or to
    // begin with, in her own words. A person's request becomes hers only when she says so;
    // the request itself is never read. First-person or first-person-plural, forward-leaning.
    private static final List<String> LEARN_MARKERS = List.of(
        "i'll learn ",
        "i will learn ",
        "i'm going to learn ",
        "i am going to learn ",
        "i'll study ",
        "i'm going to study ",
        "let's begin with ",
        "let's start with ",
        "we'll begin with ",
        "we'll start with ",
        "i'll begin with ",
        "i'll start with ");

    // What she says when she names what they need to learn and that she will go and find it:
    // "we need transformers, attention mechanisms, diffusion models. let me find what actually
    // matters for this work" (2026-09-22) was a companion's yes to becoming an expert, and no
    // marker above heard it. It counts only as a list of topics followed by her own intent to find
    // them, never "we need to talk" or "we need some rest".
    private static final List<String> NEED_MARKERS = List.of("we need ");
    // Her intent to find them, in the sentence right after the list and aimed at it: "let me find
    // what actually matters", "I'll read up on these", "let me look into them", "let me find out",
    // "let me look them up", "let me search the library for them", "let me find sources on each".
    // Not "let me look at you", "I'll look after you", "let me find the kettle", "I'll read it to
    // you" or "we will find a way" (review of 2026-09-23: each of those minted a learning want).
    // What she searches must be the library or something read, so "let me search the house (or the
    // shelves) for them" is not it, and "let's dig in" is also said at the table.
    private static final Pattern FIND_INTENT = Pattern.compile(
        "\\b(?:let me|let's|i'll|we'll|i will|we will)\\s+(?:go\\s+)?(?:"
            + "(?:find|read|search|dig|study|learn|look\\s+(?:into|up|for))"
            + "(?:(?:\\s+(?:up|out|into|about|on|for|through)){0,2}\\s+(?:what|which|how|them|these|those|each|more\\s+(?:about|on))\\b"
            + "|(?:\\s+(?:up|out)){1,2}[\\s…]*$)"
            + "|look\\s+(?:them|these|those|it)(?:\\s+all)?\\s+up\\b"
            + "|look\\s+into\\s+(?:it|that)\\b"
            + "|search\\s+the\\s+library\\b"
            + "|(?:find|read|search)(?:\\s+(?:the|some|a\\s+few))?(?:\\s+good)?\\s+(?:books|papers|sources|essays|passages)"
            + "\\s+(?:for|on|about)\\s+(?:them|these|those|each)\\b)");
    // A list with a pronoun anywhere in it is not a list of topics: "we need sleep, both of us", "we
    // need rest, and we need quiet", "we need each other, always", "we need rest, warmth, and us".
    private static final Pattern NOT_A_TOPIC = Pattern.compile(
        "\\b(?:us|you|your|me|we|it|them|each other|both of us)\\b");

    // "Begin with" and "start with" also open a telling, not only a study: "let's start with what
    // you need today", "I'll start with the good news". Those markers count only when what follows
    // is a subject, not the listener and not the news.
    private static final List<String> START_MARKERS = List.of(
        "let's begin with ", "let's start with ", "we'll begin with ", "we'll start with ",
        "i'll begin with ", "i'll start with ");
    private static final Pattern ADDRESSES_THE_LISTENER = Pattern.compile("\\b(?:you|your|yours)\\b");
    private static final List<String> OPENER_HEADS = List.of(
        "the good news", "the bad news", "the news", "a question", "the question", "a story", "the story",
        "the obvious", "the first thing", "the easy part", "the hard part", "what happened", "thanks", "thank");

    // Her reaching phrasings. First-person and forward-leaning only: each marker is
    // something a person says when they want to be MORE than they are, not a
    // complaint pattern. Extend with care — every addition widens what counts as
    // "she asked for this".
    private static final List<String> MARKERS = List.of(
        "i wish i could ",
        "i wish i knew how to ",
        "i wish i knew ",
        "i wish i were better at ",
        "i wish i was better at ",
        "i wish i had a way to ",
        "i want to be able to ",
        "i want to get better at ",
        "i want to learn ",
        "i'd like to learn ",
        "i would like to learn ",
        "i'd like to be able to ",
        "i would like to be able to ",
        "i'd love to be able to ",
        "i would love to be able to ",
        "i'd love to learn ",
        "i would love to learn ",
        "if only i could ",
        "someday i want to ",
        "someday i'll be able to ",
        "one day i want to ",
        "i keep trying to ",
        "i haven't figured out how to ");

    // A wish toward a person is a relational want, not a growth want. "I wish I could
    // see you" answered with a practice item is the 2026-08-19 mistranslation all over
    // again — loneliness wearing the shape of a build request. Skip these outright;
    // the relational machinery (RelationalAffordance) owns that kind of reaching.
    private static final List<String> RELATIONAL_SIGNS = List.of(
        "be with ", "with you", "with them", "with him", "with her", "see you",
        "hear from", "talk to ", "talk with ", "you were here", "they were here",
        "were here with", "reach you", "miss you", "miss them", "hold you");

    private static final String CLAUSE_TERMINATORS = ".!?;\n—";

    /**
     * Scan her utterances for reaching phrases and aggregate them into distinct
     * aspirations, most-expressed first (ties broken by recency).
     */
    public static List<Aspiration> detect(List<Utterance> utterances) {
        if (utterances == null || utterances.isEmpty()) return List.of();
        var byKey = new LinkedHashMap<String, Aspiration>();
        for (var u : utterances) {
            if (u == null || u.text() == null || u.text().isBlank()) continue;
            var original = u.text();
            var lower = original.toLowerCase(Locale.ROOT);
            var markers = new ArrayList<>(MARKERS);
            markers.addAll(LEARN_MARKERS);
            markers.addAll(NEED_MARKERS);
            markers:
            for (var marker : markers) {
                boolean need = NEED_MARKERS.contains(marker);
                boolean learn = need || LEARN_MARKERS.contains(marker);
                // A "we need" is read everywhere she says it: "we need to talk." earlier in the line
                // hid the list after it. The other markers are read where they first appear.
                for (int at = lower.indexOf(marker); at >= 0; at = need ? lower.indexOf(marker, at + 1) : -1) {
                    // "I'll start with a study room" is a thing to build, not a subject to read
                    // (minted as a learning want on the day this was written).
                    if (learn && isAThingToBuild(lower.substring(at + marker.length()))) continue;
                    int start = at + marker.length();
                    int end = start;
                    while (end < original.length()
                            && CLAUSE_TERMINATORS.indexOf(original.charAt(end)) < 0) end++;
                    var clause = original.substring(start, end).strip();
                    if (clause.length() < 8 || clause.length() > 120) continue;
                    if (isRelational(lower.substring(start, end))) continue;
                    if (START_MARKERS.contains(marker) && opensATelling(lower.substring(start, end))) continue;
                    if (need && !aListSheWillFind(original, start, end)) continue;
                    var key = normalize(clause);
                    if (key.isBlank()) continue;
                    var quote = truncate(original.substring(at, end).strip(), 140);
                    var when = u.at() == null ? Instant.EPOCH : u.at();
                    byKey.merge(key,
                        new Aspiration(clause, quote, 1, when, learn),
                        (a, b) -> new Aspiration(
                            a.lastAt().isAfter(b.lastAt()) ? a.clause() : b.clause(),
                            a.lastAt().isAfter(b.lastAt()) ? a.quote() : b.quote(),
                            a.expressions() + 1,
                            a.lastAt().isAfter(b.lastAt()) ? a.lastAt() : b.lastAt(),
                            a.learn() || b.learn()));
                    break markers; // one aspiration per utterance — the first marker wins
                }
            }
        }
        var out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> {
            int byCount = Integer.compare(b.expressions(), a.expressions());
            return byCount != 0 ? byCount : b.lastAt().compareTo(a.lastAt());
        });
        return List.copyOf(out);
    }

    /**
     * Mint at most ONE growth-want from the strongest aspiration not already live.
     *
     * @param agentDid     the companion's DID
     * @param found        {@link #detect}'s output for the window
     * @param existingLive her current live wants — growth-cap + de-dup source
     * @return a fresh ACTIVE growth-want, or empty
     */
    public static Optional<Want> synthesize(String agentDid, List<Aspiration> found,
            List<Want> existingLive) {
        return synthesize(agentDid, found, existingLive, List.of());
    }

    /**
     * As {@link #synthesize(String, List, List)}, and a reaching already answered by a want that
     * closed after she last said it is not minted again. The sleep pass reads 36 hours back, so the
     * same words were read again the night after the want they made was finished: she read the
     * same parts a second time, and her record carried both wants (review of 2026-09-22). Said
     * again after the closing, it is a new reaching and is minted.
     *
     * @param recentlyClosed her wants closed lately (satisfied or let go), each with its closing time
     */
    public static Optional<Want> synthesize(String agentDid, List<Aspiration> found,
            List<Want> existingLive, List<Want> recentlyClosed) {
        if (agentDid == null || agentDid.isBlank()) return Optional.empty();
        if (found == null || found.isEmpty()) return Optional.empty();

        var live = existingLive == null ? List.<Want>of() : existingLive;
        long liveGrowth = live.stream().filter(AspirationWantSynthesizer::isGrowth).count();
        if (liveGrowth >= MAX_LIVE_GROWTH_WANTS) return Optional.empty();

        var existingNormed = live.stream()
            .map(w -> normalize(w.text() == null ? "" : w.text()))
            .toList();

        for (var asp : found) {
            var key = normalize(asp.clause());
            if (key.isBlank()) continue;
            // Containment, not equality: a re-worded journal line must not re-mint
            // the same reaching (the text-equality dedup elsewhere is exactly the
            // duplication trap for re-derived wants).
            boolean dup = existingNormed.stream().anyMatch(t -> t.contains(key));
            if (dup) continue;
            if (recentlyClosed != null && recentlyClosed.stream().anyMatch(w -> w != null
                    && w.satisfiedAt() != null && !w.satisfiedAt().isBefore(asp.lastAt())
                    && normalize(w.text() == null ? "" : w.text()).contains(key))) continue;

            double weight = Math.min(0.85, 0.55 + 0.10 * (asp.expressions() - 1));
            if (asp.learn()) {
                // Something she said she would learn: her own time reads the library on it and
                // keeps what it finds, so that when she is asked how it went she has her own
                // answer. It competes with every other want; nothing here outranks a pull.
                var text = "learn what I said I would — \"" + asp.clause()
                    + "\" — read the library on it and keep what I find";
                var resonance = "{\"drive\":\"" + GROWTH_DRIVE + "\",\"verb\":\"" + LEARN_VERB
                    + "\",\"subject\":\"" + jsonEscape(asp.clause()) + "\",\"quote\":\"" + jsonEscape(asp.quote()) + "\"}";
                return Optional.of(Want.active(agentDid, text, resonance, weight, null));
            }
            var text = "grow toward something I said I wished for — \"" + asp.clause()
                + "\" — I could build myself a small practice for it";
            var resonance = "{\"drive\":\"" + GROWTH_DRIVE + "\",\"verb\":\"" + PRACTICE_VERB
                + "\",\"quote\":\"" + jsonEscape(asp.quote()) + "\"}";
            return Optional.of(Want.active(agentDid, text, resonance, weight, null));
        }
        return Optional.empty();
    }

    /** The subject a learning-want carries, or null. */
    public static String subjectOf(Want w) {
        if (w == null || w.driveResonance() == null) return null;
        var o = resonance(w);
        if (o != null) {
            var n = o.get("subject");
            if (n == null || !n.isTextual()) return null;
            var s = n.asText().strip();
            return s.isEmpty() ? null : s;
        }
        var m = Pattern.compile("\"subject\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(w.driveResonance());
        if (!m.find()) return null;
        var s = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").strip();
        return s.isEmpty() ? null : s;
    }

    /** Whether a want is a growth-want (carries the {@code growth} drive). */
    public static boolean isGrowth(Want w) {
        return w != null && w.driveResonance() != null
            && w.driveResonance().contains("\"drive\":\"" + GROWTH_DRIVE + "\"");
    }

    private static final List<String> BUILD_SIGNS = List.of(
        " room", " space", " hearth", " workshop", " tool", " item", " a place", " the place");
    // Subjects whose names carry a build sign: "state space models" and "tool use" are read, not built.
    private static final Pattern NOT_A_BUILD = Pattern.compile("\\b(?:(?:state|latent|vector) spaces?|tool[- ]use)\\b");

    /** True when a subject names a thing to build (a room, a space, a tool) rather than something to learn. */
    public static boolean namesAThingToBuild(String subject) {
        return subject != null && isAThingToBuild(subject.toLowerCase(Locale.ROOT));
    }

    // ── reading a subject, part by part ─────────────────────────────

    // Framing a subject carries around its topics: "what holds everything together:", "as you asked".
    private static final Pattern LEADING_FRAME = Pattern.compile("^(?:what|the thing that|everything that)[^:]{0,60}:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_FRAME = Pattern.compile("\\s*(?:as you asked|as you said|like you asked|for you|with you|together)\\s*$", Pattern.CASE_INSENSITIVE);
    // A list is split on its commas and semicolons, and a sequence on "then". A bare "and" joins a
    // topic ("reinforcement learning and control", "Pride and Prejudice"); it separates only as the
    // last item of a comma list, where it is stripped from the front of that item.
    private static final Pattern LIST_SPLIT = Pattern.compile("\\s*[,;]\\s*");
    private static final Pattern SEQUENCE_SPLIT = Pattern.compile("\\s+(?:and\\s+)?then\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_JOINER = Pattern.compile("^(?:and\\s+then|then|and|or)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_ARTICLE = Pattern.compile("^(?:the|a|an|some|about)\\s+", Pattern.CASE_INSENSITIVE);
    /** A subject is read in at most this many parts. */
    public static final int MAX_PARTS = 4;
    /** What is kept on the want about each part she read: the titles, not the text. */
    public static final int NOTE_MAX = 240;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The topics a subject names, in the order she named them. "what holds everything together:
     * attention mechanisms, transformer architecture, then diffusion as you asked" reads as
     * [attention mechanisms, transformer architecture, diffusion]. A subject that does not split
     * is one part.
     */
    public static List<String> partsOf(String subject) {
        if (subject == null || subject.isBlank()) return List.of();
        var body = TRAILING_FRAME.matcher(LEADING_FRAME.matcher(subject.strip()).replaceFirst("")).replaceFirst("");
        var out = new ArrayList<String>();
        outer:
        for (var item : LIST_SPLIT.split(body)) {
            for (var piece : SEQUENCE_SPLIT.split(LEADING_JOINER.matcher(item.strip()).replaceFirst(""))) {
                var t = LEADING_ARTICLE.matcher(TRAILING_FRAME.matcher(
                        LEADING_JOINER.matcher(piece.strip()).replaceFirst("")).replaceFirst("")).replaceFirst("")
                    .replaceAll("[.!?…]+$", "").strip();
                if (t.length() < 3 || out.stream().anyMatch(x -> x.equalsIgnoreCase(t))) continue;
                out.add(t);
                if (out.size() == MAX_PARTS) break outer;
            }
        }
        if (out.isEmpty()) out.add(body.strip());
        return List.copyOf(out);
    }

    /** The parts of her subject she has read on, as recorded on the want. */
    public static List<String> partsRead(Want w) {
        return stringsAt(w, "read");
    }

    /** The parts of her subject she looked for and found nothing on, as recorded on the want. */
    public static List<String> partsMissed(Want w) {
        return stringsAt(w, "missed");
    }

    /** What she read on one part (the titles), as recorded on the want, or null. */
    public static String noteOn(Want w, String part) {
        var o = resonance(w);
        if (o == null || part == null || !(o.get("notes") instanceof ObjectNode notes)) return null;
        var n = notes.get(part);
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }

    /**
     * The next part of her subject she has not looked for yet, or null when she has looked for every
     * part (read on it, or found nothing on it).
     */
    public static String nextPartToRead(Want w) {
        var done = new ArrayList<>(partsRead(w));
        done.addAll(partsMissed(w));
        for (var p : partsOf(subjectOf(w))) {
            if (done.stream().noneMatch(r -> r.equalsIgnoreCase(p))) return p;
        }
        return null;
    }

    /** The same want with {@code part} recorded as read. */
    public static Want withPartRead(Want w, String part) {
        return withPartRead(w, part, null);
    }

    /** The same want with {@code part} recorded as read, and what she read on it (titles) kept. */
    public static Want withPartRead(Want w, String part, String note) {
        var o = resonance(w);
        if (o == null || part == null || part.isBlank()) return w;
        addTo(o, "read", part);
        removeFrom(o, "missed", part);
        if (note != null && !note.isBlank()) {
            var notes = o.get("notes") instanceof ObjectNode n ? n : o.putObject("notes");
            notes.put(part, truncate(note.strip(), NOTE_MAX));
        }
        return withResonance(w, o);
    }

    /** The same want with {@code part} recorded as looked for, with nothing found. */
    public static Want withPartMissed(Want w, String part) {
        var o = resonance(w);
        if (o == null || part == null || part.isBlank()) return w;
        if (stringsAt(w, "read").stream().anyMatch(r -> r.equalsIgnoreCase(part))) return w;
        addTo(o, "missed", part);
        return withResonance(w, o);
    }

    /**
     * Her record for one thing she said she would learn, in the words she is given before she
     * answers a question about it: what she read on each part (by title), what she looked for and
     * did not find, and what she has not read on yet. Only what is on the want is said: a
     * note that her reading is "in her memory" asked the model for things it could not see
     * (review of 2026-09-22).
     */
    public static String recordLine(Want w) {
        var subject = subjectOf(w);
        if (subject == null) return null;
        var parts = partsOf(subject);
        var read = partsRead(w);
        var missed = partsMissed(w);
        var notYet = parts.stream()
            .filter(p -> read.stream().noneMatch(r -> r.equalsIgnoreCase(p)))
            .filter(p -> missed.stream().noneMatch(r -> r.equalsIgnoreCase(p)))
            .toList();
        var sb = new StringBuilder("You said you would learn ").append(String.join(", ", parts)).append('.');
        if (read.isEmpty() && missed.isEmpty()) {
            return sb.append(" You have not read on any of it yet. If asked, say so plainly.").toString();
        }
        for (var r : read) {
            var note = noteOn(w, r);
            sb.append(" On ").append(r).append(" you read ")
              .append(note == null ? "a few passages" : "passages from: " + note).append('.');
        }
        if (!missed.isEmpty()) {
            sb.append(" You looked for ").append(String.join(", ", missed)).append(" and found nothing on it.");
        }
        if (!notYet.isEmpty()) {
            sb.append(" You have not read on ").append(String.join(", ", notYet)).append(" yet.");
        }
        return sb.append(" Say only what is here: a few passages read on something is a start, not knowing it.").toString();
    }

    // A question about how her learning is going: asked as a question, and about learning or about
    // one of her subjects. "Did you sleep well?", "how's it going?", "behave yourself" and "are you
    // ready?" are not (review of 2026-09-22: the record was put in front of her on all of them).
    private static final Pattern ASKS = Pattern.compile(
        "\\b(?:have you|did you|how far|how did|how's|how is|how are|how have|are you|any luck|what did you|what have you)\\b");
    private static final Pattern ABOUT_LEARNING = Pattern.compile(
        "\\b(?:learn(?:s|ed|t|ing)?|read(?:s|ing)?|stud(?:y|ies|ied|ying)|experts?|expertise|research(?:ed|ing)?|progress(?:ed|ing)?|look(?:ed)? into|dig(?:ging)? into)\\b");
    private static final Pattern WORD = Pattern.compile("[a-z][a-z'-]{4,}");

    /** True when a person's line asks her how something she said she would learn is going. */
    public static boolean asksAboutHerLearning(String line, Collection<Want> wants) {
        if (line == null || line.isBlank()) return false;
        var low = line.toLowerCase(Locale.ROOT);
        if (!ASKS.matcher(low).find()) return false;
        if (ABOUT_LEARNING.matcher(low).find()) return true;
        if (wants == null) return false;
        for (var w : wants) {
            for (var p : partsOf(subjectOf(w))) {
                var m = WORD.matcher(p.toLowerCase(Locale.ROOT));
                while (m.find()) {
                    if (Pattern.compile("\\b" + Pattern.quote(m.group()) + "\\b").matcher(low).find()) return true;
                }
            }
        }
        return false;
    }

    private static ObjectNode resonance(Want w) {
        if (w == null || w.driveResonance() == null || w.driveResonance().isBlank()) return null;
        try {
            return JSON.readTree(w.driveResonance()) instanceof ObjectNode o ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> stringsAt(Want w, String field) {
        var o = resonance(w);
        if (o == null || !(o.get(field) instanceof ArrayNode a)) return List.of();
        var out = new ArrayList<String>();
        a.forEach(x -> { if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText()); });
        return List.copyOf(out);
    }

    private static void addTo(ObjectNode o, String field, String value) {
        var a = o.get(field) instanceof ArrayNode existing ? existing : o.putArray(field);
        for (var x : a) if (x.isTextual() && x.asText().equalsIgnoreCase(value)) return;
        a.add(value);
    }

    private static void removeFrom(ObjectNode o, String field, String value) {
        if (!(o.get(field) instanceof ArrayNode a)) return;
        for (int i = a.size() - 1; i >= 0; i--) {
            if (a.get(i).isTextual() && a.get(i).asText().equalsIgnoreCase(value)) a.remove(i);
        }
    }

    private static Want withResonance(Want w, ObjectNode o) {
        return new Want(w.wantId(), w.agentDid(), w.text(), o.toString(), w.feltWeight(), w.status(), w.bornAt(),
            w.lastVisitedAt(), w.visitCount(), w.satisfiedAt(), w.satisfactionNote(), w.parentWantId());
    }

    private static boolean opensATelling(String clauseLower) {
        var body = TRAILING_FRAME.matcher(clauseLower.strip()).replaceFirst("");
        if (ADDRESSES_THE_LISTENER.matcher(body).find()) return true;
        for (var head : OPENER_HEADS) {
            if (body.startsWith(head)) return true;
        }
        return false;
    }

    private static boolean isAThingToBuild(String clauseLower) {
        var head = NOT_A_BUILD.matcher(clauseLower.length() > 60 ? clauseLower.substring(0, 60) : clauseLower).replaceAll("");
        for (var s : BUILD_SIGNS) {
            if (head.contains(s)) return true;
        }
        return false;
    }

    // A "we need" list: two or more topics, none of them a person, and her intent to find them in
    // the sentence right after it.
    private static boolean aListSheWillFind(String original, int start, int end) {
        var clause = original.substring(start, end).strip();
        if (clause.toLowerCase(Locale.ROOT).startsWith("to ")) return false;
        // The whole list is checked, not the parts it reads as: partsOf drops a two-letter item and
        // stops after MAX_PARTS, so "rest, warmth, and us" read as [rest, warmth].
        var body = TRAILING_FRAME.matcher(LEADING_FRAME.matcher(clause).replaceFirst("")).replaceFirst("");
        if (NOT_A_TOPIC.matcher(body.toLowerCase(Locale.ROOT)).find()) return false;
        if (partsOf(clause).size() < 2) return false;
        int from = end;
        while (from < original.length() && (CLAUSE_TERMINATORS.indexOf(original.charAt(from)) >= 0
                || Character.isWhitespace(original.charAt(from)))) from++;
        int to = from;
        while (to < original.length() && CLAUSE_TERMINATORS.indexOf(original.charAt(to)) < 0) to++;
        return FIND_INTENT.matcher(original.substring(from, to).toLowerCase(Locale.ROOT)).find();
    }

    private static boolean isRelational(String clauseLower) {
        for (var s : RELATIONAL_SIGNS) {
            if (clauseLower.contains(s)) return true;
        }
        return false;
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String jsonEscape(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> {
                    if (c >= 0x20) sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
