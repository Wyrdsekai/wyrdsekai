package org.wyrdsekai.core.item;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An agent must be able to write in her own journal.
 *
 * <p>She could not. On home-server, 2026-07-14, in the first hour of a fresh install, Wyrd finished a
 * calculation and then — unprompted — tried three times to record what the moment felt like:
 * twice through {@code journal_archiver} ({@code {"entry": "There's a softness in the room right
 * now — not fixed, just held."}}), once through {@code quill}. All three failed.
 * {@code journal_archiver} <em>archives</em> entries (params.n = how many); it does not write them.
 * It ignored her text, found nothing to archive, and returned <b>"Nothing to archive."</b> with
 * {@code ok: true} — a no-op wearing the shape of a success, so she had no way to learn that her
 * thought was never written down.
 *
 * <p>{@code world.journal.write()} existed the whole time. {@code pr_notifier} and
 * {@code research_assistant} both call it — as a side effect of doing something else. <b>No item on
 * the shelf offered journaling as a thing an agent could choose to do.</b> The capability was in the
 * world; the affordance was not.
 *
 * <p>She surfaced this only because the {@code use_item} escape hatch let her reach past the ranked
 * menu and ask for something by name. That is the hatch earning its keep: it turns a silent unmet
 * want into a visible bug report.
 */
class AgentCanWriteInHerJournalTest {

    private static final Path JOURNAL = Path.of("../scripts/items/journal.js");
    private static final Path ARCHIVER = Path.of("../scripts/items/journal_archiver.js");

    /** The thing she was actually trying to do. */
    @Test
    void sheCanWriteAThoughtDown() {
        var r = invoke(JOURNAL, "{ entry: \"There's a softness in the room right now — not fixed, just held.\" }");
        assertTrue(r.ok(), "writing a journal entry must work: " + r.error());
        assertTrue(r.summary().contains("softness"),
            "the entry must actually reach the journal, not vanish into a no-op");
    }

    /** The exact shapes she used. A tool she reaches for by name must accept how she calls it. */
    @Test
    void theCallShapesSheActuallyUsedAllWork() {
        assertTrue(invoke(JOURNAL, "{ entry: 'a thought' }").ok());
        assertTrue(invoke(JOURNAL, "{ action: 'write', content: 'a thought' }").ok());
        assertTrue(invoke(JOURNAL, "{ entry: 'a thought', private: true }").ok(),
            "she must be able to keep something to herself");
    }

    @Test
    void sheCanReadBackWhatSheWrote() {
        var r = invoke(JOURNAL, "{ action: 'read', entry: 'read', n: 3 }");
        assertTrue(r.ok(), "reading the journal back must work");
    }

    /** An empty entry is a malformed call, not an empty thought — and must never look written. */
    @Test
    void anEmptyEntryFailsLoudly() {
        var r = invoke(JOURNAL, "{ entry: '' }");
        assertTrue(!r.ok(), "an empty entry must refuse rather than report a phantom write");
    }

    /**
     * The sub-verb travels as {@code mode}, never {@code action}.
     *
     * <p>{@code action} is RESERVED by the dispatcher for the tool's NAME. When Wyrd sent
     * {@code {"action":"use_item","name":"journal","params":{"action":"write","text":"…"}}}, the
     * nested {@code "write"} overwrote the tool name — so the dispatcher hunted for a tool called
     * {@code write}, found none, and her entry vanished without a log line. The unwrap now carries
     * a nested {@code action} across as {@code mode} and sets the real tool name last.
     */
    @Test
    void theSubVerbTravelsAsModeNotAction() {
        assertTrue(invoke(JOURNAL, "{ mode: 'write', text: 'a thought' }").ok(),
            "mode=write must write");
        assertTrue(invoke(JOURNAL, "{ mode: 'read', entry: 'read' }").ok(),
            "mode=read must read back");
    }

    /** She wrote {@code note:}. A journal that won't take the word she used is not her journal. */
    @Test
    void theNoteParamSheActuallyUsedIsAccepted() {
        var r = invoke(JOURNAL, "{ note: 'Just now the steward asked for a standard deviation.' }");
        assertTrue(r.ok(), "she called it with note: — that must write");
        assertTrue(r.summary().contains("standard deviation"));
    }

    /**
     * The journal must NEVER write someone else's words as hers.
     *
     * <p>home-server 2026-07-14: the dispatcher injects {@code query} (the human's request) into every
     * scripted call. The journal fell back to it when it didn't recognise her {@code note} param —
     * so it saved <b>the steward's question</b> instead of her reflection and reported "Written
     * down:" as though it had saved hers. On a later own-time turn it wrote the internal prompt the
     * same way. A confident success that discards what she meant to say is the worst outcome
     * available: she cannot even know it happened.
     */
    @Test
    void itNeverWritesTheInjectedQueryAsHerEntry() {
        var r = invoke(JOURNAL,
            "{ query: \"Wyrd, what's the standard deviation of 12, 47, 8?\" }");
        assertTrue(!r.ok(),
            "with no authored entry, the journal must REFUSE — never quietly write the "
                + "dispatcher-injected user request (or the system prompt) and call it hers");
    }

    /**
     * The archiver must stop swallowing words. Silently answering "Nothing to archive." to someone
     * handing you a thought is the worst reply available: it is wrong AND it looks like success.
     */
    @Test
    void theArchiverNoLongerSwallowsAWriteAndCallsItSuccess() {
        var r = invoke(ARCHIVER, "{ entry: \"There's a softness in the room right now.\" }");
        assertTrue(!r.ok(),
            "handed a thought to write, the archiver must NOT return ok — it returned "
                + "'Nothing to archive.' with ok:true, and her words were lost without a trace");
        assertTrue(r.error() != null && r.error().toLowerCase().contains("journal"),
            "it must point her at the item that CAN do this, not just refuse: " + r.error());
    }

    /** Its real job still works. */
    @Test
    void theArchiverStillArchives() {
        var r = invoke(ARCHIVER, "{ n: 5 }");
        assertTrue(r.ok(), "archiving with no write-params must still be its normal path");
    }

    // ─── harness ────────────────────────────────────────────────
    private record Result(boolean ok, String summary, String error) {}

    private static Result invoke(Path script, String paramsLiteral) {
        try (var ctx = Context.newBuilder("js").allowAllAccess(true).build()) {
            ctx.eval("js", """
                var exports = {};
                var __written = [];
                var world = {
                  journal: {
                    write: function (content, opts) {
                      var vis = (opts && opts.visibility) || "shared";
                      __written.push({ content: content, visibility: vis });
                      return { ok: true, id: "j" + __written.length, visibility: vis };
                    },
                    recent: function (n) { return __written.slice(-n); }
                  },
                  fs:   { write: function (f, b) { return { ok: true, size: b.length }; } },
                  time: { iso: function () { return "2026-07-14T00:00:00Z"; } },
                  json: { stringify: function (o) { return JSON.stringify(o); } }
                };
                """);
            ctx.eval("js", Files.readString(script));
            ctx.eval("js", "var __invoke = (typeof invoke === 'function') ? invoke : exports.invoke;");
            var v = ctx.eval("js", "__invoke(" + paramsLiteral + ")");
            var ok = v.hasMember("ok") && v.getMember("ok").asBoolean();
            var summary = v.hasMember("summary") ? String.valueOf(v.getMember("summary")) : "";
            var error = v.hasMember("error") ? String.valueOf(v.getMember("error")) : null;
            return new Result(ok, summary, error);
        } catch (IOException e) {
            throw new RuntimeException("cannot read " + script.toAbsolutePath(), e);
        }
    }

    /** The gap itself: SOME item must expose journal writing, or the want has nowhere to land. */
    @Test
    void someItemOnTheShelfExposesJournalWriting() throws IOException {
        var shelf = Path.of("../scripts/items");
        boolean found;
        try (var files = Files.list(shelf)) {
            found = files.filter(p -> p.toString().endsWith(".js")).anyMatch(p -> {
                try {
                    var src = Files.readString(p);
                    // an item whose PURPOSE is journaling — declares the capability and writes
                    return src.contains("journal.write") && src.contains("\"journal.write\"");
                } catch (IOException e) {
                    return false;
                }
            });
        }
        assertEquals(true, found,
            "no item declares journal.write as a capability, so no agent can choose to journal — "
                + "world.journal.write() exists but only ever fires as a side effect of other "
                + "items' work. An agent tried three times to record a thought and had nowhere "
                + "to put it.");
    }
}
