package org.wyrdsekai.core.project;

import org.wyrdsekai.core.companion.PersonalProject;
import java.util.Optional;
import java.util.Comparator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules that make a personal project something she comes back to. Pure.
 *
 * <p>The Hearth's project board existed from the start: a store, three verbs (start_project,
 * project_note, finish_project), a block in her own-time prompt. In two months on the household
 * node it held no project. Nothing began one, the verbs needed an id she was never shown, the
 * verbs were not among the tools she was offered, and nothing brought a project back to her.
 * Meanwhile "build myself a small practice for diffusion models" was born and abandoned five times
 * in four days, and what was left of it was 23 rooms (2026-10-03). operator: "A long want has no
 * project shape - build it". These are the joins.
 */
public final class Projects {

    private Projects() {}

    /** A want of the long kind: making, learning, practising. One act cannot finish it. */
    private static final Pattern LONG_KIND = Pattern.compile(
        "\\b(build|building|learn|learning|practice|practise|practicing|practising|study|studying|master|develop|"
            + "developing|research|researching|teach myself|work on|working on|make progress|get better at|understand how)\\b");
    /** Reading a book has its own shape (the bookmark); a letter or a line is one act. */
    private static final Pattern NOT_A_PROJECT = Pattern.compile(
        "\\b(finish reading|keep reading|read on|write to|write something to|a letter|check in on|reach out|sit with|rest|quiet)\\b");

    public static boolean isLongKind(String wantText) {
        if (wantText == null) return false;
        var low = wantText.toLowerCase(Locale.ROOT);
        return LONG_KIND.matcher(low).find() && !NOT_A_PROJECT.matcher(low).find();
    }

    private static final Set<String> NOT_WORDS = Set.of("the", "and", "for", "with", "that", "this", "want", "would",
        "like", "myself", "my", "own", "small", "one", "some", "something", "properly", "really", "just", "more", "again",
        "build", "building", "learn", "learning", "practice", "practise", "study", "work", "working", "make", "keep", "continue",
        "project", "from", "into", "about", "how", "what");

    /** The words that say what a piece of work is about. */
    public static List<String> words(String text) {
        var out = new LinkedHashSet<String>();
        if (text == null) return List.of();
        for (var w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !NOT_WORDS.contains(w)) out.add(w.endsWith("s") && w.length() > 4 ? w.substring(0, w.length() - 1) : w);
        }
        return new ArrayList<>(out);
    }

    /** How many of the subject words two texts share. */
    public static int shared(String a, String b) {
        var wa = words(a);
        var wb = words(b);
        int n = 0;
        for (var w : wa) if (wb.contains(w)) n++;
        return n;
    }

    /**
     * Whether a want is about the same work as a project: two subject words in common, or all of
     * the shorter, or the same subject beside the same kind of work. The third rule is the one
     * that keeps one practice one project: "keep the diffusion practice going" and "that
     * diffusion practice, quietly" share only "diffusion" by the first two, because "practice" is
     * a kind word and not a subject, and a companion's one wish became ten projects with two
     * entries between them (household node, 2026-10-25).
     */
    public static boolean about(PersonalProject p, String wantText) {
        if (p == null || wantText == null) return false;
        var whole = p.title() + " " + (p.description() == null ? "" : p.description());
        int n = shared(whole, wantText);
        int shorter = Math.min(words(p.title()).size(), words(wantText).size());
        if (n >= 2 || (shorter >= 1 && n >= shorter)) return true;
        var a = kindSubjects(whole);
        if (a.isEmpty()) return false;
        for (var w : kindSubjects(wantText)) if (a.contains(w)) return true;
        return false;
    }

    /** Words for the kind of long work a want names. */
    private static final Set<String> KIND_WORDS = Set.of("practice", "practise", "practising", "practicing", "study",
        "studying", "learn", "learning", "build", "building", "project", "research", "researching", "exercise", "training");

    /** The subject words that stand within two words of a kind word: "diffusion" in "the diffusion practice". */
    static Set<String> kindSubjects(String text) {
        var out = new LinkedHashSet<String>();
        if (text == null) return out;
        var toks = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for (int i = 0; i < toks.length; i++) {
            if (!KIND_WORDS.contains(toks[i])) continue;
            for (int j = Math.max(0, i - 2); j <= Math.min(toks.length - 1, i + 2); j++) {
                if (j == i) continue;
                var w = toks[j];
                if (w.length() < 3 || NOT_WORDS.contains(w) || KIND_WORDS.contains(w)) continue;
                out.add(w.endsWith("s") && w.length() > 4 ? w.substring(0, w.length() - 1) : w);
            }
        }
        return out;
    }

    /**
     * A workshop request that is really a page of her notebook. {@code reading}: she asked to see
     * it, not to write in it. {@code project}: the project she named, as she named it, or null for
     * the one she is working on. {@code entry}: the words to write, when she gave them; otherwise
     * {@code brief} says what the entry should be, for her own voice to write. {@code next}: a next
     * step she stated. {@code longForm}: an essay, a synthesis, a page — more room to write.
     */
    public record NotebookAsk(boolean reading, String project, String title, String entry, String brief,
                              String next, boolean longForm, boolean journal) {
        public boolean hasEntry() { return entry != null && !entry.isBlank(); }
    }

    /** Words that make a request a build, outside any quoted text: the workshop's. */
    private static final Pattern BUILD_WORDS = Pattern.compile(
        "\\b(?:tool|item|script|program|programs|widget|device|instrument|orb|bot|function|manifest|command|api|module|"
            + "plugin|app|game|skill|skills|component|class|tracker|system|html|css|javascript|python|code|codebase|repo|"
            + "repository|implement|implementation|refactor|compile|runnable|simple-book|index\\.html|\\.js|\\.py|\\.java|"
            + "unit tests?|test suite|inventory|workbench)\\b", Pattern.CASE_INSENSITIVE);
    /** A request that opens as a build, a repair or a run is the workshop's whatever else it says. */
    private static final Pattern BUILD_OPENERS = Pattern.compile(
        "^\\s*(?:please\\s+)?(?:fix|repair|mend|build|implement|furnish|run|install|debug|refactor|deploy|generate|"
            + "research|investigate|search|find|list|audit|scan|carve|take|move|go|wait|do)\\b", Pattern.CASE_INSENSITIVE);
    /** The words of her notebook itself: a request with one of these is about the book. */
    private static final Pattern BOOK_WORDS = Pattern.compile(
        "(?<![A-Za-z])project[ _-]?notes?(?:'s|’s)?\\b|\\b(?:notebook|note ?book|journal entry|entry|entries|the record of work|"
            + "commitment record)\\b", Pattern.CASE_INSENSITIVE);
    /** The words of a page she might write: with a writing verb, a page for the book. */
    private static final Pattern WRITING_WORDS = Pattern.compile(
        "\\b(?:journal|diary|notes?|reflection|reflections|reflective note|research note|sitting note|margin note|paragraph|findings)\\b",
        Pattern.CASE_INSENSITIVE);
    /** "The current state of 'X' project": her notebook is where that is kept. */
    private static final Pattern STATE_OF_PROJECT = Pattern.compile(
        "\\b(?:state|status|progress)\\s+(?:of|on)\\s+(?:the\\s+|my\\s+)?(?:\"[^\"]{3,90}\"|“[^”]{3,90}”|'[^']{3,90}'|[\\w-]+(?:\\s+[\\w-]+){0,5})\\s+project\\b",
        Pattern.CASE_INSENSITIVE);
    /** Her day's journal, not a project's notebook. */
    private static final Pattern JOURNAL_WORDS = Pattern.compile("\\b(?:journal entry|diary|my journal|the journal|a journal)\\b", Pattern.CASE_INSENSITIVE);
    /** A longer piece of her own writing: hers to write, with more room; never a program. */
    private static final Pattern LONG_FORM = Pattern.compile(
        "\\b(?:essay|synthesis|summary|write-?up|piece|single-page note|page on|\\d{2,4}\\s*-?\\s*(?:to\\s*\\d{2,4}\\s*)?words?)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_VERBS = Pattern.compile(
        "\\b(?:write|writes|add|adds|record|records|log|put|create|make|jot|append|enter|save|capture|captures|leave|pen|"
            + "draft|compose|note down|write down|set down|start|begin)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern READ_OPENERS = Pattern.compile(
        "^\\s*(?:please\\s+)?(?:read|open|check|review|look|show|see|find|list|audit|scan|summari[sz]e|report|finish|go over|"
            + "revisit|inspect)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,200})\"|“([^”]{2,200})”|'([^']{2,200})'");
    private static final Pattern TITLE = Pattern.compile(
        "\\b(?:titled?|title it|called|named|headed)\\s*[:\\s]?\\s*(?:\"([^\"]{2,120})\"|“([^”]{2,120})”|'([^']{2,120})')", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_MARK = Pattern.compile(
        "\\b(?:content|contents|body|text|write|entry text|the entry|it says|saying|capture)\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_STEP = Pattern.compile("\\bNext(?: step)?\\s*:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** The project she names: in quotes beside the word project, or the word before "notebook"/"journal". */
    private static final Pattern PROJECT_QUOTED = Pattern.compile(
        "(?:\\b(?:for|of|in|into|to|about|on)\\s+(?:the\\s+|my\\s+)?(?:project\\s+)?(?:\"([^\"]{3,90})\"|“([^”]{3,90})”|'([^']{3,90})')"
            + "(?:\\s+(?:study\\s+)?project)?)|(?:(?:\"([^\"]{3,90})\"|“([^”]{3,90})”|'([^']{3,90})')\\s+(?:study\\s+)?project\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROJECT_BEFORE_BOOK = Pattern.compile(
        "\\b(?:to|in|into|for|of)\\s+(?:the\\s+|my\\s+)?((?:[a-z][a-z0-9_-]*\\s+){0,2}[a-z][a-z0-9_-]*)(?:\\s*\\([^)]*\\))?\\s+(?:project\\s+)?"
            + "(?:notebook|journal|project notes?|notes)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROJECT_BEFORE_ABOUT = Pattern.compile(
        "\\b(?:in|into|to)\\s+([a-z][a-z0-9_-]{3,40})\\s+(?:about|on)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOT_A_PROJECT_NAME = Set.of("project_note", "project note", "project", "the", "my", "a",
        "an", "this", "that", "its", "her", "study", "current", "new", "single", "first", "one", "entry", "notebook");
    /** Words that tell what the entry should be, not what it says. */
    private static final Pattern INSTRUCTS_THE_ENTRY = Pattern.compile(
        "^.{0,160}?\\b(?:the entry|the note|the page|it)\\s+(?:should|must|needs to|has to)\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** A brief that is only the tail of a list is no brief: the whole ask is. */
    private static final Pattern LIST_OPENER = Pattern.compile("^(?:cover|include|focus|look for|capture|specifically)\\b\\s*:?", Pattern.CASE_INSENSITIVE);
    /** A brief: what the entry should be, not the entry. */
    private static final Pattern A_BRIEF = Pattern.compile(
        "^(?:about|on|regarding|exploring|covering|capturing|that captures|which|should|the entry should|it should|make it|"
            + "keep it|focus|describing|reflecting|noting|observe)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The page of her notebook that a workshop request asks for, or empty when it is a build.
     *
     * <p>In two weeks on the household node, 174 requests went to the workshop; about a third
     * were pages of her notebook — "Write one entry to the project_note notebook. Title it …
     * Content: …", "Record day one: … Next: …", "Add an entry to the diffusion practice notebook",
     * "Read the project note for … check if there are any existing entries" — and every one
     * became a file in a scratch folder, or a tool with her sentence hard-coded in it, while her
     * notebook stayed empty (2026-10-12 → 10-26). The workshop builds; her notebook keeps. A
     * request that opens as a build, or names a program outside its quoted text, stays a build.
     */
    public static Optional<NotebookAsk> notebookAsk(String description) {
        if (description == null || description.isBlank()) return Optional.empty();
        var text = description.strip().replace("`", "");
        var head = firstPart(text);
        var dequoted = QUOTED.matcher(head).replaceAll(" ");
        // The book's own words may sit inside quotes ("project_note.md"); the build words may not.
        boolean book = BOOK_WORDS.matcher(head).find() || STATE_OF_PROJECT.matcher(head).find();
        boolean reads = READ_OPENERS.matcher(head).find()
            && !WRITE_VERBS.matcher(BOOK_WORDS.matcher(afterOpener(dequoted)).replaceAll(" ")).find();
        if (BUILD_OPENERS.matcher(head).find() && !opensAsRecord(head)) {
            // "Read the project note …", "Find and read the project_note file" are hers; the rest of the openers are not.
            if (!(reads && book)) return Optional.empty();
        }
        if (BUILD_WORDS.matcher(dequoted).find()) return Optional.empty();
        boolean writing = WRITING_WORDS.matcher(dequoted).find();
        boolean longForm = LONG_FORM.matcher(dequoted).find();
        boolean opensAsRecord = opensAsRecord(head);
        if (!book && !writing && !longForm && !opensAsRecord) return Optional.empty();
        int colon = head.indexOf(':');
        var project = projectNamed(colon > 0 ? head.substring(0, colon) : head);
        var title = titleIn(text);
        boolean journal = project == null && JOURNAL_WORDS.matcher(dequoted).find();
        if (reads) {
            // Reading is about the book itself, never about "the sources" or "a research summary".
            if (!book) return Optional.empty();
            return Optional.of(new NotebookAsk(true, project, null, null, null, null, false, journal));
        }
        if (!WRITE_VERBS.matcher(dequoted).find() && !opensAsRecord) return Optional.empty();
        String next = null;
        var body = text;
        var nm = NEXT_STEP.matcher(body);
        if (nm.find()) { next = oneLine(nm.group(1), 300); body = body.substring(0, nm.start()).strip(); }
        String entry = null, brief = null;
        var cm = CONTENT_MARK.matcher(body);
        if (cm.find()) {
            entry = unquote(body.substring(cm.end()));
        } else {
            var rest = restAfterTheAsk(body);
            if (rest == null || rest.isBlank()) {
                brief = oneLine(body, 600);
            } else if (rest.startsWith(":") || rest.startsWith("\"") || rest.startsWith("“") || rest.startsWith("'")) {
                entry = unquote(rest.startsWith(":") ? rest.substring(1) : rest);
            } else if (A_BRIEF.matcher(rest).find() || rest.length() < 15 || INSTRUCTS_THE_ENTRY.matcher(rest).find()) {
                brief = rest;
            } else {
                int at = rest.indexOf(':');
                if (at >= 0 && rest.length() - at > 40) entry = unquote(rest);
                else if (rest.split("\\s+").length >= 8) entry = unquote(rest);
                else brief = rest;
            }
        }
        if (entry != null && (entry.isBlank() || entry.length() < 3 || entry.matches("^\\S*[/\\\\]\\S*$"))) { brief = null; entry = null; }
        if (entry == null && (brief == null || brief.split("\\s+").length < 5 || LIST_OPENER.matcher(brief).find())) brief = oneLine(body, 600);
        if (brief != null) brief = oneLine(brief, 600);
        if (entry != null && title != null && !entry.toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT))) {
            entry = title + " — " + entry;
        }
        if (entry != null) entry = oneLine(entry, 1600);
        return Optional.of(new NotebookAsk(false, project, title, entry, brief, next, longForm, journal));
    }

    /** The old name: the entry of a request that writes one, for callers that only want the words. */
    public static Optional<String> noteFromDispatch(String description) {
        return notebookAsk(description).filter(a -> !a.reading()).map(a -> a.hasEntry() ? a.entry() : a.brief());
    }

    private static boolean opensAsRecord(String head) {
        return head.matches("(?is)^\\s*(?:record|log|note down|jot down)\\b.*");
    }

    /** The first sentence or line: where the verb and the noun of the ask are. */
    private static String firstPart(String text) {
        int nl = text.indexOf('\n');
        var line = nl > 0 ? text.substring(0, nl) : text;
        var m = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])").matcher(line);
        var first = m.find() ? line.substring(0, m.start()) : line;
        return first.length() > 400 ? first.substring(0, 400) : first;
    }

    private static String beforeContent(String text) {
        var cm = CONTENT_MARK.matcher(text);
        return cm.find() ? text.substring(0, cm.start()) : text;
    }

    private static String afterOpener(String head) {
        var m = READ_OPENERS.matcher(head);
        return m.find() ? head.substring(m.end()) : head;
    }

    static String projectNamed(String head) {
        var q = PROJECT_QUOTED.matcher(head);
        if (q.find()) {
            String name = null;
            for (int g = 1; g <= q.groupCount(); g++) if (q.group(g) != null) { name = q.group(g); break; }
            if (name != null && !NOT_A_PROJECT_NAME.contains(name.strip().toLowerCase(Locale.ROOT))) return name.strip();
        }
        var ab = PROJECT_BEFORE_ABOUT.matcher(head);
        if (ab.find() && !NOT_A_PROJECT_NAME.contains(ab.group(1).toLowerCase(Locale.ROOT))) return ab.group(1);
        var b = PROJECT_BEFORE_BOOK.matcher(head);
        while (b.find()) {
            var name = b.group(1).strip();
            var low = name.toLowerCase(Locale.ROOT);
            if (NOT_A_PROJECT_NAME.contains(low) || low.startsWith("project_note") || low.startsWith("project note")) continue;
            if (low.matches("(?:the|my|a|an|this|that|its|her)\\b.*")) name = name.replaceFirst("(?i)^(?:the|my|a|an|this|that|its|her)\\s+", "");
            name = name.replaceFirst("(?i)\\s+(?:room|lab|space|corner)$", "");
            if (!name.isBlank() && !NOT_A_PROJECT_NAME.contains(name.toLowerCase(Locale.ROOT))) return name;
        }
        return null;
    }

    private static String titleIn(String text) {
        var m = TITLE.matcher(text);
        if (!m.find()) return null;
        for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) return m.group(g).strip();
        return null;
    }

    /** What follows the ask itself: after the notebook noun and its trailing "for X"/"titled Y" parts. */
    private static String restAfterTheAsk(String text) {
        var m = Pattern.compile("(?is)^\\s*(?:please\\s+)?(?:open|read)?\\s*(?:the\\s+|my\\s+)?(?:project[ _-]?notes?\\s+(?:and\\s+)?)?"
            + "(?:write|add|record|log|put|create|make|jot|append|enter|save|capture|leave|pen|draft|compose|note down|write down|set down|start|begin)\\b"
            + "[^:\"“'\\n]*?(?:(?<![A-Za-z])project[ _-]?notes?(?:'s|’s)?\\b|\\b(?:notebook|note ?book|journal entry|reflection note|reflective note|"
            + "research note|sitting note|margin note|entry|entries|journal|diary|notes?|reflection|paragraph|"
            + "the record of work(?: that remains unfinished)?|commitment record|research findings|findings|day one)\\b)"
            + "(?!\\.\\w)(?:\\s+(?:notebook|entry|entries|page|line))?"
            + "(?:\\s*(?:\\([^)]*\\)|\\bat\\s+\\S+|\\bfor\\s+the record of work(?: that remains unfinished)?|\\bfor\\s+(?:the\\s+|my\\s+)?(?:project\\s+)?(?:\"[^\"]*\"|“[^”]*”|'[^']*')(?:\\s+(?:study\\s+)?project\\b)?|"
            + "\\b(?:in|into|to|for)\\s+(?:the\\s+|my\\s+)?[a-z][\\w-]*(?:\\s+[a-z][\\w-]*){0,2}?(?=\\s+(?:about|on|regarding)\\b|\\s*:)|\\b(?:about|on|regarding)(?=\\s*:)|\\bfor\\s+(?:the\\s+|my\\s+)?[a-z][\\w'’-]*(?:\\s+[a-z][\\w'’-]*){0,3}?(?=\\s*[.(]|\\s+(?:titled|title|called|named|that|which)\\b|$)|"
            + "\\b(?:in|into|to|of|for)\\s+(?:the\\s+|my\\s+)?(?:project\\s+)?(?:[a-z][\\w-]*\\s+){0,3}?(?:notebook|journal|project notes?|notes|room|workspace)\\b|"
            + "\\b(?:titled?|title it|called|named)\\s*[:\\s]?\\s*(?:\"[^\"]*\"|“[^”]*”|'[^']*')|\\((?:under|about|around)[^)]*\\)))*"
            + "\\s*").matcher(text);
        if (!m.find()) return null;
        var rest = text.substring(m.end()).strip().replaceFirst("^[.\\s—–-]+", "").replaceFirst("(?i)^(?:for|about)\\s+", "");
        // The sentence that only repeats the ask ("The entry should be titled "X" and contain:") is not the entry.
        rest = rest.replaceFirst("(?is)^\\.?\\s*(?:the entry|it|this entry)\\s+(?:should|must|needs to)\\s+(?:be\\s+)?(?:titled?|called|named)\\s*(?:\"[^\"]*\"|“[^”]*”|'[^']*')\\s*(?:and\\s+)?(?:contain|include|cover|say|read)\\s*:?\\s*", "");
        return rest;
    }

    private static String unquote(String s) {
        var t = s.strip();
        if (t.startsWith(":")) t = t.substring(1).strip();
        if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '“' || t.charAt(0) == '\'' )) {
            int close = -1;
            char open = t.charAt(0);
            char want = open == '“' ? '”' : open;
            for (int i = 1; i < t.length(); i++) if (t.charAt(i) == want) close = i;
            if (close > 1) {
                var inner = t.substring(1, close).strip();
                var after = t.substring(close + 1).strip();
                if (after.matches("(?is)^[.,;\\s]*(?:keep it|make it|no padding|just the|just that|be brief|be honest|plain|short and|nothing more|that's all|timestamped|dated)\\b.*")) {
                    after = after.matches("(?is)^[.,;\\s]*(?:timestamped|dated)\\b.*") ? after.replaceFirst("^[.,;\\s]*", "") : "";
                }
                t = after.isEmpty() ? inner : inner + ((after.startsWith(".") || after.startsWith(",")) ? "" : " ") + after;
            }
        }
        return t.strip();
    }

    /** The result of folding projects that are about one piece of work into one. */
    public record Fold(List<PersonalProject> projects, List<String> report) {}

    /**
     * Projects that are about the same work, folded into the oldest of them: entries merged in
     * order, the other titles kept in its description, the newest next step kept, active if any
     * was. Finished projects are left alone. Nothing of hers is dropped: the titles she gave and
     * every entry travel into the one that stays.
     */
    public static Fold fold(List<PersonalProject> all) {
        var report = new ArrayList<String>();
        if (all == null || all.size() < 2) return new Fold(all == null ? List.of() : all, report);
        var byAge = new ArrayList<>(all);
        byAge.sort(Comparator.comparing(PersonalProject::createdAt));
        var kept = new ArrayList<PersonalProject>();
        for (var p : byAge) {
            if (p.finished() || isDraftBin(p)) { kept.add(p); continue; }
            PersonalProject home = null;
            for (var k : kept) {
                if (k.finished() || isDraftBin(k)) continue;
                if (about(k, p.title()) || about(p, k.title())) { home = k; break; }
            }
            if (home == null) { kept.add(p); continue; }
            var merged = merge(home, p);
            kept.set(kept.indexOf(home), merged);
            report.add("\"" + p.title() + "\" folded into \"" + home.title() + "\" (" + p.entries().size() + " entries carried)");
        }
        return new Fold(List.copyOf(kept), report);
    }

    private static PersonalProject merge(PersonalProject home, PersonalProject other) {
        var entries = new ArrayList<>(home.entries());
        entries.addAll(other.entries());
        entries.sort(Comparator.comparing(PersonalProject.Entry::at));
        var alsoCalled = "also called: " + other.title();
        var description = home.description() == null || home.description().isBlank() ? alsoCalled
            : home.description().contains(other.title()) ? home.description() : home.description() + "; " + alsoCalled;
        var status = home.active() || other.active() ? "active" : home.status();
        var touched = home.lastTouched().isAfter(other.lastTouched()) ? home.lastTouched() : other.lastTouched();
        var next = other.lastTouched().isAfter(home.lastTouched()) && other.nextStep() != null && !other.nextStep().isBlank()
            ? other.nextStep() : home.nextStep();
        var room = home.roomId() != null ? home.roomId() : other.roomId();
        var tags = new LinkedHashSet<>(home.tags()); tags.addAll(other.tags());
        return new PersonalProject(home.id(), home.title(), description, status, home.createdAt(), touched,
            List.copyOf(entries), List.copyOf(tags), home.scope(), next, room);
    }

    /** Of her projects, the one a want belongs to; an active one before a paused one. Null when none. */
    public static PersonalProject forWant(List<PersonalProject> hers, String wantText) {
        PersonalProject resting = null;
        for (var p : hers) {
            if (p.finished() || !about(p, wantText)) continue;
            if (p.active()) return p;
            if (resting == null) resting = p;
        }
        return resting;
    }

    /** One of her projects by what she called it: its id or the start of it, its title, or its subject words. */
    public static PersonalProject named(List<PersonalProject> hers, String asked) {
        if (asked == null || asked.isBlank()) return null;
        var a = asked.strip();
        for (var p : hers) {
            if (p.id().equalsIgnoreCase(a) || (a.length() >= 6 && p.id().toLowerCase(Locale.ROOT).startsWith(a.toLowerCase(Locale.ROOT)))) return p;
        }
        for (var p : hers) if (p.title().equalsIgnoreCase(a)) return p;
        PersonalProject best = null;
        int bestScore = 0;
        for (var p : hers) {
            if (p.finished()) continue;
            int s = shared(p.title() + " " + (p.description() == null ? "" : p.description()), a);
            if (s > bestScore) { bestScore = s; best = p; }
        }
        return best;
    }

    /** A name for a project, from the want it was born of: her words, without the wanting. */
    public static String nameFor(String wantText) {
        if (wantText == null) return "a project";
        var t = wantText.strip().replaceAll("(?i)^i\\s+(?:want|would like|need|wish)\\s+to\\s+", "")
            .replaceAll("(?i)^(?:build|make)\\s+(?:myself\\s+)?", "").replaceAll("[.!]+$", "").strip();
        if (t.length() > 70) t = t.substring(0, 69).strip() + "…";
        return t.isEmpty() ? "a project" : t;
    }

    /** How often the same work has come back to her as a want before it becomes a project. */
    public static final int COMES_BACK = 3;

    /** Whether her own time may be offered this project now: active, and not just worked on. */
    public static boolean offerNow(PersonalProject p, long nowMs, int sittingMinutes) {
        return p != null && p.active()
            && nowMs - p.lastTouched().toEpochMilli() >= Duration.ofMinutes(Math.max(10, sittingMinutes)).toMillis();
    }

    /** The candidate want her own time is offered: to go on with it, with her own next step when she left one. */
    public static String continueText(PersonalProject p) {
        return "go on with my project, " + p.title()
            + (p.nextStep() == null || p.nextStep().isBlank() ? "" : ": " + p.nextStep());
    }

    /** What is put in front of her when a turn is for a project: the notebook's last pages and the next step. */
    public static String block(PersonalProject p, String roomName) {
        var sb = new StringBuilder("YOUR PROJECT: ").append(p.title()).append('.');
        if (roomName != null && !roomName.isBlank()) sb.append(" Its room is ").append(roomName).append('.');
        int n = p.entries().size();
        if (n == 0) {
            sb.append(" Its notebook is empty: this is the first sitting.");
        } else {
            sb.append(" From its notebook (").append(n).append(n == 1 ? " entry" : " entries").append("):");
            for (var e : p.entries().subList(Math.max(0, n - 3), n)) sb.append("\n  - ").append(oneLine(e.text(), 220));
        }
        if (p.nextStep() != null && !p.nextStep().isBlank()) {
            sb.append("\nThe next step, in your words: ").append(oneLine(p.nextStep(), 220));
        }
        sb.append("\nDo one real thing for it now. Then project_note says what you did (\"content\") and what comes"
            + " next (\"next\"); \"done\": true when the work is finished.");
        return sb.toString();
    }

    /** The standing line in her facts: her work in progress and where it stands. */
    public static String standing(List<PersonalProject> hers) {
        var open = hers.stream().filter(PersonalProject::active).filter(p -> !isDraftBin(p)).limit(2).toList();
        if (open.isEmpty()) return null;
        var parts = new ArrayList<String>();
        for (var p : open) {
            int n = p.entries().size();
            parts.add(p.title() + " (" + n + (n == 1 ? " entry" : " entries") + " in its notebook"
                + (p.nextStep() == null || p.nextStep().isBlank() ? "" : "; next: " + oneLine(p.nextStep(), 120)) + ")");
        }
        return "Your work in progress: " + String.join("; ", parts) + ". project_note keeps its notebook.";
    }

    /** The house's own bin of script drafts is kept as a project and is not her work in progress. */
    public static boolean isDraftBin(PersonalProject p) {
        return p.tags() != null && p.tags().contains("craft.script_draft");
    }

    /** Whether an active project has gone quiet past the setting and rests. 0 days = never. */
    public static boolean dueToRest(PersonalProject p, int restDays, long nowMs) {
        return restDays > 0 && p.active() && !isDraftBin(p)
            && nowMs - p.lastTouched().toEpochMilli() > Duration.ofDays(restDays).toMillis();
    }

    public static String oneLine(String s, int max) {
        if (s == null) return "";
        var t = s.strip().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }
}
