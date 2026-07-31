package org.wyrdsekai.core.familiar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Journals familiar-lifecycle events to the agent's private journal (§15).
 *
 * <p>The spec's visibility guarantee — "the user never has to wonder what the
 * agent has been doing" — turns into concrete entries written through
 * {@link StudyService#writePrivateJournalEntry}. Entries are searchable via
 * the existing Study L1 pinboard and {@code wyrd journal} tooling. Each entry
 * is prefixed with a machine-readable tag so a future {@code wyrd journal
 * search "shape"} query can surface all thought-form activity in one pass.</p>
 *
 * <p>This class has no side-effects beyond its input arguments and is null-safe
 * on a missing store — the fallback is a log line. Journaling should never
 * block the operation it witnesses.</p>
 */
public final class FamiliarJournal {

    private static final Logger log = LoggerFactory.getLogger(FamiliarJournal.class);

    /** Entry kind prefix — first word of the entry, lets search filter by event type. */
    public enum Kind {
        SHAPED("familiar.shaped"),
        REVISED("familiar.revised"),
        RETIRED("familiar.retired"),
        UNRETIRED("familiar.unretired"),
        SUMMONED("familiar.summoned"),
        TOOL_BROKEN("tool.broken"),
        TOOLS_LOANED("familiar.tools_loaned"),
        TOOLS_RETURNED("familiar.tools_returned"),
        RETURNED("familiar.returned"),
        STUCK("familiar.stuck"),
        CANCELLED("familiar.cancelled"),
        BUNSHIN_DISPATCH("bunshin.dispatch"),
        BUNSHIN_RETURN("bunshin.return"),
        IMPRINT_CREATED("imprint.created"),
        IMPRINT_RESTORED("imprint.restored");

        public final String tag;
        Kind(String tag) { this.tag = tag; }
    }

    private final WyrdLuceneStore store;

    public FamiliarJournal(WyrdLuceneStore store) {
        this.store = store;
    }

    /** Is journaling available? If false, {@link #write} is a no-op. */
    public boolean available() { return store != null; }

    /**
     * Write a journal entry. Nullable fields collapse gracefully. Never throws
     * — journaling is decorative, not load-bearing for its caller.
     */
    public void write(String agentDid, Kind kind, String summary, String details) {
        if (store == null || agentDid == null || kind == null) {
            log.debug("journal write skipped: store={}, did={}, kind={}",
                store != null, agentDid, kind);
            return;
        }
        try {
            var studyService = new StudyService(store);
            var entry = formatEntry(kind, summary, details);
            studyService.writePrivateJournalEntry(agentDid, entry);
        } catch (Exception e) {
            log.warn("Failed to write familiar journal entry: {}", e.getMessage());
        }
    }

    /** Form-authoring convenience. */
    public void shaped(String agentDid, ThoughtForm form) {
        if (form == null) return;
        var details = "name=" + form.name()
            + " version=" + form.version()
            + " tools=" + form.toolSurface()
            + (form.evalCriteria() == null || form.evalCriteria().isBlank()
                ? "" : " eval=" + truncate(form.evalCriteria(), 120))
            + "\nprompt: " + truncate(form.systemPrompt(), 400);
        write(agentDid, Kind.SHAPED, "Shaped thought form '" + form.name() + "'", details);
    }

    /** Form-revision convenience. Emits a diff-ish summary between versions. */
    public void revised(String agentDid, ThoughtForm previous, ThoughtForm revised, String note) {
        if (previous == null || revised == null) return;
        var changes = summarizeChanges(previous, revised);
        var details = "name=" + revised.name()
            + " " + previous.version() + " → " + revised.version()
            + (changes.isEmpty() ? " (no surface changes detected)" : "\nchanges: " + String.join("; ", changes))
            + (note == null || note.isBlank() ? "" : "\nnote: " + note);
        write(agentDid, Kind.REVISED,
            "Revised '" + revised.name() + "' to " + revised.version(), details);
    }

    /** Form-retirement convenience. */
    public void retired(String agentDid, ThoughtForm form, String note) {
        if (form == null) return;
        var details = "name=" + form.name()
            + " version=" + form.version()
            + " bondCharge=" + form.bondCharge()
            + " summons=" + form.summonCount()
            + " success=" + form.successCount()
            + " failure=" + form.failureCount()
            + (note == null || note.isBlank() ? "" : "\nnote: " + note);
        write(agentDid, Kind.RETIRED, "Retired '" + form.name() + "'", details);
    }

    /** Summoning convenience. */
    public void summoned(String agentDid, ThoughtForm form, String task, Tanks tanks) {
        if (form == null) return;
        var details = "form=" + form.name() + "@" + form.version()
            + " task=" + truncate(task, 200)
            + " tanks=" + tanks;
        write(agentDid, Kind.SUMMONED, "Summoned '" + form.name() + "'", details);
    }

    /** Tool-breaking convenience (§14) — farewell event for a destroyed tool. */
    public void toolBroken(String agentDid, String toolLabel, int ageDays, String farewell) {
        if (toolLabel == null) return;
        var details = "tool=" + toolLabel
            + " age=" + ageDays + "d"
            + (farewell == null || farewell.isBlank() ? "" : "\nfarewell: " + farewell);
        write(agentDid, Kind.TOOL_BROKEN,
            "Broke '" + toolLabel + "' after " + ageDays + "d", details);
    }

    /** Tool-loan convenience (§7.1) — visibility for tools handed out for the familiar's lifetime. */
    public void toolsLoaned(String agentDid, ThoughtForm form, String handle, List<String> tools) {
        if (form == null || tools == null || tools.isEmpty()) return;
        var details = "form=" + form.name() + "@" + form.version()
            + " handle=" + handle
            + " tools=" + tools;
        write(agentDid, Kind.TOOLS_LOANED,
            "Loaned " + tools.size() + " tool(s) to '" + handle + "'", details);
    }

    /** Tool-return convenience (§7.1) — logged when the familiar terminates. */
    public void toolsReturned(String agentDid, String handle, List<String> tools) {
        if (tools == null || tools.isEmpty()) return;
        var details = "handle=" + handle + " tools=" + tools;
        write(agentDid, Kind.TOOLS_RETURNED,
            "Returned " + tools.size() + " tool(s) from '" + handle + "'", details);
    }

    /** Imprint-creation convenience (§10.1). */
    public void imprintCreated(String agentDid, Imprint imprint) {
        if (imprint == null) return;
        var details = "id=" + imprint.id()
            + " createdBy=" + imprint.createdBy()
            + " label=" + imprint.label()
            + " size=" + imprint.size() + "B";
        write(agentDid, Kind.IMPRINT_CREATED,
            "Imprint created — " + (imprint.label().isBlank() ? imprint.createdBy() : imprint.label()),
            details);
    }

    /**
     * Imprint-restoration convenience (§10.2 / §10.4).
     * The journal entry is the spec's "I was this, then I became that, and
     * I restored myself back." — honest continuity across restores.
     */
    public void imprintRestored(String agentDid, Imprint imprint, String restoredBy) {
        if (imprint == null) return;
        var details = "restoredFromId=" + imprint.id()
            + " originalCreatedBy=" + imprint.createdBy()
            + " originalLabel=" + imprint.label()
            + " restoredBy=" + (restoredBy == null ? "SELF" : restoredBy);
        write(agentDid, Kind.IMPRINT_RESTORED,
            "Restored to imprint '"
                + (imprint.label().isBlank() ? imprint.id() : imprint.label()) + "'",
            details);
    }

    /** Familiar termination convenience — status-aware. */
    public void familiarReturned(String agentDid, Familiar fam, String narrativeSummary) {
        if (fam == null) return;
        Kind kind = switch (fam.status()) {
            case DONE -> Kind.RETURNED;
            case STUCK -> Kind.STUCK;
            case DEAD -> Kind.CANCELLED;
            default -> Kind.RETURNED;
        };
        var details = "formId=" + fam.formId() + "@" + fam.formVersion()
            + " status=" + fam.status()
            + " turns=" + fam.log().size()
            + (fam.result().isPresent() ? "\nresult: " + truncate(fam.result().get().toString(), 400) : "")
            + (narrativeSummary == null ? "" : "\nsummary: " + truncate(narrativeSummary, 400));
        write(agentDid, kind,
            "Familiar for task '" + truncate(fam.task(), 60) + "' — " + fam.status(),
            details);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String formatEntry(Kind kind, String summary, String details) {
        var sb = new StringBuilder(kind.tag).append(" — ");
        sb.append(summary == null ? "" : summary);
        if (details != null && !details.isBlank()) {
            sb.append("\n").append(details);
        }
        return sb.toString();
    }

    private static List<String> summarizeChanges(ThoughtForm prev, ThoughtForm next) {
        var changes = new ArrayList<String>();
        if (!prev.systemPrompt().equals(next.systemPrompt())) {
            changes.add("prompt updated");
        }
        if (!prev.toolSurface().equals(next.toolSurface())) {
            changes.add("tool surface " + prev.toolSurface() + " → " + next.toolSurface());
        }
        if (!equalsNullable(prev.evalCriteria(), next.evalCriteria())) {
            changes.add("eval criteria updated");
        }
        return changes;
    }

    private static boolean equalsNullable(String a, String b) {
        if (a == null) return b == null || b.isBlank();
        if (b == null) return a.isBlank();
        return a.equals(b);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
