package org.wyrdsekai.core.project;

import org.wyrdsekai.core.companion.PersonalProject;

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

    /** Whether a want is about the same work as a project: two subject words in common, or all of the shorter. */
    public static boolean about(PersonalProject p, String wantText) {
        if (p == null || wantText == null) return false;
        var whole = p.title() + " " + (p.description() == null ? "" : p.description());
        int n = shared(whole, wantText);
        int shorter = Math.min(words(p.title()).size(), words(wantText).size());
        return n >= 2 || (shorter >= 1 && n >= shorter);
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
