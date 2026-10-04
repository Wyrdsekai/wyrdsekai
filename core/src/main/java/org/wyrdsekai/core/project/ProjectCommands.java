package org.wyrdsekai.core.project;

import org.wyrdsekai.core.companion.PersonalProject;
import org.wyrdsekai.core.companion.PersonalProjectStore;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The companions' projects as a person in the house sees them: {@code projects} lists what each is
 * working on, {@code projects <words>} opens one's notebook.
 */
public final class ProjectCommands {

    private ProjectCommands() {}

    private static final Pattern LINE = Pattern.compile("(?i)^projects?(?:\\s+(.*))?$");
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    /** A project and whose it is. */
    public record Held(String maker, PersonalProject project) {}

    private static volatile Supplier<Map<String, String>> COMPANIONS;

    /** Each companion's name and the key her project board is kept under; installed at boot. */
    public static void installCompanions(Supplier<Map<String, String>> companions) { COMPANIONS = companions; }

    public static boolean isCommand(String line) {
        return line != null && LINE.matcher(line.strip()).matches();
    }

    static String lang(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        var l = locale.strip().toLowerCase(Locale.ROOT);
        return l.startsWith("es") ? "es" : l.startsWith("ja") ? "ja" : "en";
    }

    /** Every companion's projects, the most recently worked first; the house's own draft bins left out. */
    static List<Held> everyones() {
        var out = new ArrayList<Held>();
        var companions = COMPANIONS;
        if (companions == null) return null;
        for (var c : companions.get().entrySet()) {
            for (var p : new PersonalProjectStore(c.getValue()).list()) {
                if (!Projects.isDraftBin(p)) out.add(new Held(c.getKey(), p));
            }
        }
        out.sort((a, b) -> b.project().lastTouched().compareTo(a.project().lastTouched()));
        return out;
    }

    /** The line a person typed, answered. {@code visitor}: someone from another household. */
    public static String command(String personId, String line, String locale, boolean visitor) {
        return command(personId, line, locale, visitor, v -> everyones());
    }

    static String command(String personId, String line, String locale, boolean visitor, Function<Void, List<Held>> held) {
        var text = ScriptMessageCatalog.forLang(lang(locale));
        if (visitor) return text.get("projects.visitor");
        if (personId == null || personId.isBlank()) return text.get("projects.sign_in");
        var all = held.apply(null);
        if (all == null) return text.get("projects.unavailable");
        var m = LINE.matcher(line == null ? "" : line.strip());
        var rest = m.matches() && m.group(1) != null ? m.group(1).strip() : "";
        long now = System.currentTimeMillis();
        if (rest.isEmpty()) {
            if (all.isEmpty()) return text.get("projects.none");
            var sb = new StringBuilder(text.get("projects.head"));
            for (var h : all) sb.append('\n').append(row(h, text, now));
            return sb.append('\n').append(text.get("projects.how")).toString();
        }
        Held one = null;
        int best = 0;
        for (var h : all) {
            int s = Projects.shared(h.project().title() + " " + h.maker(), rest);
            if (s > best) { best = s; one = h; }
        }
        if (one == null) return text.get("projects.no_such", rest);
        var sb = new StringBuilder(row(one, text, now).strip());
        var entries = one.project().entries();
        if (entries.isEmpty()) sb.append('\n').append(text.get("projects.notebook.empty"));
        for (var e : entries.subList(Math.max(0, entries.size() - 8), entries.size())) {
            sb.append("\n  ").append(days(now - e.at().toEpochMilli(), text)).append(": ").append(Projects.oneLine(e.text(), 240));
        }
        if (one.project().nextStep() != null && !one.project().nextStep().isBlank()) {
            sb.append('\n').append(text.get("projects.next", Projects.oneLine(one.project().nextStep(), 240)));
        }
        return sb.toString();
    }

    private static String row(Held h, ScriptMessageCatalog text, long now) {
        var p = h.project();
        var state = text.get("projects.state." + (p.active() ? "open" : p.finished() ? "done" : "resting"));
        return text.get("projects.row", p.title(), h.maker(), String.valueOf(p.entries().size()),
            days(now - p.lastTouched().toEpochMilli(), text), state);
    }

    private static String days(long ms, ScriptMessageCatalog text) {
        long d = Math.max(0, ms / DAY_MS);
        return d == 0 ? text.get("projects.today") : text.get("projects.days_ago", String.valueOf(d));
    }
}
