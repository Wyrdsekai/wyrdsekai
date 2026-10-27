package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.item.HouseholdItems;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What she has, said at once: a look at her own things is never a build. Pure.
 *
 * <p>Her own-time prompt said "check your skills and inventory", no tool showed them, and both
 * companions on the household node sent that sentence to the workshop, which built a checking
 * tool each time: twelve of them in two weeks, on both menus, some crashing on first use
 * (2026-10-27).
 */
public final class HerThings {

    private HerThings() {}

    /** A request that opens as a build, a repair or a reading is not a look at her things. */
    private static final Pattern BUILD_OPENER = Pattern.compile(
        "^\\s*(?:please\\s+)?(?:write|build|create|make|implement|carve|fix|repair|mend|place|furnish|run|install|read|open|research|search|find)\\b",
        Pattern.CASE_INSENSITIVE);
    /** The words of a look. */
    private static final Pattern LOOK = Pattern.compile(
        "\\b(?:check|look at|look over|look through|see|review|list|take stock of|examine|inspect|show me|go over|audit|quick inventory|what do i have|what (?:tools|skills|items) do i have)\\b",
        Pattern.CASE_INSENSITIVE);
    /** Her own things. */
    private static final Pattern HER_THINGS = Pattern.compile(
        "\\b(?:skills?|inventory|capabilities|tools(?:/functions| i can use| i have)?|items? (?:i have|on hand|currently held|in my inventory|needing repair)|"
            + "what i (?:have|carry|can do)|my things|the workbench|my workbench|workbench ledger|artifacts)\\b", Pattern.CASE_INSENSITIVE);
    /** A look at some other thing of hers is answered by that thing, not by this list. */
    private static final Pattern ANOTHER_THING = Pattern.compile(
        "\\b(?:pinboard|notebook|project[ _]?notes?|journal|library|mail|letters?|calendar|the file|a file|/tmp/|\\.md\\b|room (?:and|or) nearby|house:)",
        Pattern.CASE_INSENSITIVE);

    /** True when a workshop request is a look at what she has: skills, inventory, tools, the workbench. */
    public static boolean asksForHerThings(String description) {
        if (description == null || description.isBlank()) return false;
        var text = description.strip();
        int nl = text.indexOf('\n');
        var head = (nl > 0 ? text.substring(0, nl) : text);
        if (head.length() > 300) head = head.substring(0, 300);
        var low = head.toLowerCase(Locale.ROOT);
        if (BUILD_OPENER.matcher(low).find()) return false;
        if (ANOTHER_THING.matcher(low).find()) return false;
        return LOOK.matcher(low).find() && HER_THINGS.matcher(low).find();
    }

    /** One of her projects, as the list says it. */
    public record Project(String title, int entries, String next) {}

    /** The answer, in a few sentences; every part left out when empty. */
    public static String describe(List<String> carried, List<String> tools, List<HouseholdItems.Unfinished> unfinished,
                                  List<String> putAway, List<Project> projects) {
        var sb = new StringBuilder();
        if (carried != null && !carried.isEmpty()) sb.append("You carry: ").append(String.join(", ", carried)).append(". ");
        if (tools != null && !tools.isEmpty()) {
            var shown = tools.size() <= 12 ? String.join(", ", tools) : String.join(", ", tools.subList(0, 12)) + " and " + (tools.size() - 12) + " more";
            sb.append("Tools in the house that work: ").append(shown).append(". ");
        }
        if (unfinished != null && !unfinished.isEmpty()) {
            sb.append("Unfinished: ");
            for (int i = 0; i < unfinished.size(); i++) {
                var u = unfinished.get(i);
                if (i > 0) sb.append("; ");
                sb.append(u.name()).append(" (").append(shorten(u.problem(), 90)).append(")");
            }
            sb.append(". Mend one with dispatch_task, naming it and what fails. ");
        }
        if (putAway != null && !putAway.isEmpty()) sb.append("Put away, kept: ").append(String.join(", ", putAway)).append(". ");
        if (projects != null && !projects.isEmpty()) {
            sb.append("Your projects: ");
            for (int i = 0; i < projects.size(); i++) {
                var p = projects.get(i);
                if (i > 0) sb.append("; ");
                sb.append(p.title()).append(" (").append(p.entries()).append(p.entries() == 1 ? " entry" : " entries");
                if (p.next() != null && !p.next().isBlank()) sb.append("; next: ").append(shorten(p.next(), 80));
                sb.append(")");
            }
            sb.append(". ");
        }
        if (sb.isEmpty()) return "You carry nothing yet, have made nothing yet, and have no project open. Nothing needs checking.";
        return sb.toString().strip();
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        var t = s.strip().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }
}
