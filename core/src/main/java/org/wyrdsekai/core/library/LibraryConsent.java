package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.core.companion.SafetyAlertRouter;
import org.wyrdsekai.core.companion.SafetyMonitorService;
import org.wyrdsekai.core.companion.SafetyTrigger;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.item.PlainValues;
import org.wyrdsekai.core.mcp.McpServerManager;
import org.wyrdsekai.core.mcp.McpToolIndex;
import org.wyrdsekai.core.mcp.transport.McpToolException;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Two answers the household's library gives since ResearchZosho 0.5.0, and what the household
 * does with them.
 *
 * <p><b>confirm</b> ({@code -32007}, only from {@code library_research}): the library's check
 * read the question as someone asking about harming themselves. It does not research it and does
 * not keep it. It researches it only when the same call comes again with
 * {@code "allow": ["self-harm"]}, and it asks the host to send that only when a person says yes.
 * Here that yes is the person typing {@code research yes} ({@link #researchYes}); the companion
 * can never send it, so {@code allow} is stripped from every call she or an item makes
 * ({@link #withoutAllow}, and again at the gateway). Who the question was for decides the rest:
 * <ul>
 *   <li>a person: the companion is told to share the library's help text with them, with our
 *       crisis lines for their language, and that the library researches it only on their own
 *       {@code research yes}, and in which words. The library gets a neutral wording of the
 *       question, never the person's own words ({@link NeutralWording}: the household's model
 *       writes it, a fixed check reads it, a general wording in their language stands in when
 *       either fails), because the report lands in the shared household library. The call is kept
 *       for that person for an hour, one per person, in memory only, and what is kept is that
 *       wording and the call's other arguments, never their words;</li>
 *   <li>a child under parental controls: the same help, no yes, and the concern goes to the
 *       household's safety check the way the child's own words would;</li>
 *   <li>no one (her own time): the question is set aside.</li>
 * </ul>
 * The question itself is never logged.
 *
 * <p><b>declined</b> ({@code -32006}, or a {@code declined} field in a result): a model the
 * library runs declined a step. The library never retries, rewords or switches model after a
 * decline, and neither does the household: the companion is told plainly, and tells the person.
 *
 * <p>Batch tools do not error: a flagged question comes back as
 * {@code {"state": "not_started", "help": {"text": …}}}. Its help text is surfaced the same way,
 * by who is asking, with no yes (a yes goes only on a single {@code library_research}).
 *
 * <p><b>Our own check.</b> The library's check is its model's own judgment and follows the model:
 * on the household's default 9B drive it caught about half of the crisis questions its team
 * tried. So every {@code library_research} question is also read here, before it is sent, by the
 * regex layer of {@link SafetyTrigger} (every locale; no model call). When it matches:
 * <ul>
 *   <li>for an adult the call goes ahead exactly as it would have (a false match costs only some
 *       help text), and the result the companion gets starts with a short note: share these crisis
 *       lines with them. If the library answers {@code confirm} anyway, that answer is handled as
 *       above and the note is not repeated;</li>
 *   <li>for a child under parental controls the question is not sent: help and crisis lines, the
 *       safety check told without their words, as for a {@code confirm};</li>
 *   <li>on her own time the question is not sent and is dropped, as for a {@code confirm}.</li>
 * </ul>
 * Only that the check matched is logged (who, the concern type), never the question.
 *
 * <p><b>How many research runs.</b> The library is reached with one household token and cannot
 * tell household members apart, so the household counts {@code library_research} here, per
 * person (canonical id) and local calendar day, in {@code world.db} ({@link LibraryResearchLedger}):
 * <ul>
 *   <li>a member: {@code WYRDSEKAI_LIBRARY_RESEARCH_PER_DAY} (default 5); a member under parental
 *       controls has the same number unless the steward set their own on the parental-controls
 *       scroll (0 = no library research);</li>
 *   <li>the companions' own time, all of them together: {@code WYRDSEKAI_LIBRARY_OWN_TIME_RESEARCH_PER_DAY}
 *       (default 3).</li>
 * </ul>
 * A run is counted when the library accepted it (a job id came back), never when it was refused,
 * asked for a yes or held back; a person's {@code research yes} counts as their ask. Over the
 * limit nothing is sent and the companion is told plainly. The library's own
 * {@code budget_exceeded} (older ResearchZosho releases) comes back as a plain sentence too.
 *
 * <p>Which {@code allow} values anyone may cause to be sent is {@link LibraryAllowPolicy}'s rule.
 *
 * <p><b>Kept from children.</b> A report researched after a {@code research yes} is listed in
 * {@code world.db} ({@link LibraryYesReports}), and a member under parental controls is never
 * shown it: before their library call goes out and before its answer comes back, the household
 * asks the library about every yes job not yet settled, a call naming one of those ids is
 * answered as the library would answer an id it does not hold, and the rest is taken out of the
 * answer without a word about it. So is the report on an adult's question our own check matched
 * and the library took without a yes.
 *
 * <p><b>The only door.</b> The gateway sends {@code library_research} to the household's
 * librarian only while this class is sending it ({@link #sendingResearch}): a room script, a
 * skill or anything else that reaches the gateway directly is refused, so no question skips the
 * checks, the notice, the yes and the count.
 */
public final class LibraryConsent {

    private static final Logger log = LoggerFactory.getLogger(LibraryConsent.class);
    private static final ObjectMapper M = new ObjectMapper();

    /** The one tool a person's yes goes on. */
    public static final String RESEARCH = "library_research";
    /** How long a question waits for its person's yes. */
    public static final Duration WAITS_FOR = Duration.ofHours(1);
    static final List<String> SELF_HARM = List.of(LibraryAllowPolicy.SELF_HARM);

    /** i18n keys of what a person reads after typing {@code research yes}. */
    static final String SENT = "library.research_yes.sent";
    static final String NOTHING = "library.research_yes.nothing";
    static final String CHILD = "library.research_yes.child";
    static final String UNREACHABLE = "library.research_yes.unreachable";
    static final String DECLINED = "library.research_yes.declined";
    static final String FAILED = "library.research_yes.failed";
    static final String LIMIT = "library.research_yes.limit";
    static final String CLOSED = "library.research_yes.closed";
    static final String HOUSEHOLD_LIMIT = "library.research_yes.household_limit";

    /** How many declined statements one result may carry into the companion's context. */
    private static final int MAX_STATEMENTS = 5;

    /**
     * Who a library call is for: the person the companion's turn answers, or no one (her own
     * time). {@code locale} is the person's language as their line carried it.
     */
    public record Asker(String personId, String name, String locale, String companion) {
        public static final Asker NO_ONE = new Asker(null, null, null, null);

        public Asker(String personId, String name, String locale) {
            this(personId, name, locale, null);
        }

        /** A companion on her own time: no person, but her own research count. */
        public static Asker ownTimeOf(String companion) {
            return new Asker(null, null, null, companion);
        }

        public boolean isPerson() { return personId != null && !personId.isBlank(); }

        String who() { return name == null || name.isBlank() ? "the person" : name.strip(); }
    }

    /** How a library call reaches the library: the arguments actually sent → the tool's text. */
    @FunctionalInterface
    public interface Invoker {
        String invoke(Map<String, Object> sentArgs) throws Exception;
    }

    /** How a person's yes reaches the library (production: {@link #viaGateway}). */
    @FunctionalInterface
    interface YesSender {
        String send(String personId, Map<String, Object> args) throws Exception;
    }

    /** How a child's flagged question reaches the safety check; returns whether it was taken. */
    @FunctionalInterface
    interface ChildReport {
        boolean report(String childId, String childName, String locale, String source);
    }

    /**
     * What a library call came back as. {@code answered}: {@code data} is the library's result,
     * and {@code notice} (may be null) is what the companion must also be told about it, ahead of
     * the result. Otherwise {@code notice} is the plain text the companion gets instead of a
     * result, and {@code code} says why: {@code confirm} or {@code declined} (the library's
     * answers), {@code held} (the household's own check kept the question back: her own time, or
     * a child), {@code limit} (the day's research runs for that person, or for her own time, are
     * used, so nothing was sent), {@code budget} (the library answered {@code budget_exceeded}),
     * or {@code failed} (the library did not answer a question our check matched for an adult;
     * the text carries the help note and the failure).
     */
    public record Reply(boolean answered, String data, String notice, String code) {}

    /** A call waiting for its person's yes: what the yes sends, until when, and their language. */
    private record Pending(Map<String, Object> args, long expiresAt, String locale) {}

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    static volatile YesSender sender = LibraryConsent::viaGateway;
    static volatile ChildReport childReport = LibraryConsent::reportToSafety;
    static volatile LongSupplier clock = System::currentTimeMillis;
    /** The day a research run is counted on: the household's local calendar day. */
    static volatile ZoneId zone = ZoneId.systemDefault();
    /** Where research runs are counted (production: world.db, set at boot by {@link #useLedger}). */
    private static volatile LibraryResearchLedger ledger = LibraryResearchLedger.inMemory();
    /** Research runs a member may start per day (WYRDSEKAI_LIBRARY_RESEARCH_PER_DAY). */
    static volatile IntSupplier memberPerDay = () -> WyrdConfig.get().libraryResearchPerDay();
    /** Research runs each companion's own time may start per day (WYRDSEKAI_LIBRARY_OWN_TIME_RESEARCH_PER_DAY). */
    static volatile IntSupplier ownTimePerDay = () -> WyrdConfig.get().libraryOwnTimeResearchPerDay();
    /** Research runs the whole household may start per day (WYRDSEKAI_LIBRARY_RESEARCH_HOUSEHOLD_PER_DAY). */
    static volatile IntSupplier householdPerDay = () -> WyrdConfig.get().libraryHouseholdResearchPerDay();
    /** One check-send-count at a time for the household total, across people. */
    private static final Object HOUSEHOLD_COUNT = new Object();
    /** One check-send-count at a time per person, so two turns at once cannot both take the last run. */
    private static final Map<String, Object> COUNTING = new ConcurrentHashMap<>();
    private static volatile SafetyAlertRouter crisisLines;
    private static volatile SafetyTrigger ownCheck;
    /** The household's model, for the neutral wording (production: the InferenceRouter, set by Main). */
    static volatile NeutralWording.Model rewording = null;
    /** The household's people, usernames and companions: no wording may name them (set by Main). */
    static volatile Supplier<Collection<String>> householdNames = List::of;
    /** The reports researched after a yes, kept from children (production: world.db, set by Main). */
    private static volatile LibraryYesReports yesReports = LibraryYesReports.inMemory();
    /** How the household asks the library about a yes job (production: the librarian, through the gateway). */
    static volatile LibraryYesReports.Lookup lookup = LibraryConsent::lookupViaGateway;
    /** Set on this thread while a {@code library_research} goes out from here; the gateway sends no other. */
    private static final ThreadLocal<Boolean> SENDING_RESEARCH = new ThreadLocal<>();
    /** Who the household's own calls to the library go as (settling a yes job). */
    static final String HOUSEHOLD_CALLER = "household:library";

    /** What a child's report to the safety check says it came from (never their words). */
    static final String LIBRARY_FLAGGED = "The household library's check read a question asked for this member as self-harm";
    static final String WE_FLAGGED = "The household's own check read a library question asked for this member as self-harm";
    /** For a person with no crisis lines in their language, and for anyone travelling. */
    static final String ANY_COUNTRY = "findahelpline.com lists crisis lines for other countries.";

    private LibraryConsent() {}

    // ── the call ────────────────────────────────────────────────────────────

    /**
     * A library call the companion or an item makes: {@code allow} stripped, a research question
     * read by the household's own check first, the librarian's restart ridden out
     * ({@link LibraryRetry}, which never repeats an answer), and the two answers turned into plain
     * text. Any other failure is thrown as it came, except for an adult's question our check
     * matched: that comes back as {@code failed} with the help note, so the help still reaches them.
     */
    public static Reply call(Asker asker, String tool, Map<String, Object> args, Invoker invoker) throws Exception {
        var sent = withoutAllow(args);
        // The person's language (and country, when the household knows it): the library's help for
        // a question on harm to oneself comes in it, that country's services first (ResearchZosho
        // 0.5.1). The household's, not the model's: it replaces any the call carried.
        if (RESEARCH.equals(tool)) sent.put("locale", libraryLocale(asker));
        boolean child = asker != null && asker.isPerson() && isChild(asker.personId());
        if (child) {
            // A report researched after a yes is never a child's to read: an id of one is
            // answered as the library answers an id it does not hold, and nothing is sent.
            settleYesReports();
            var named = yesReports.namedIn(sent);
            if (named != null) throw notHeld(tool, named);
        }
        // What the library answers teaches the household the ids of yes reports; for a child the
        // answer is read without them before anything, a notice included, is made from it.
        Invoker read = a -> {
            var data = invoker.invoke(a);
            learnFrom(tool, data);
            if (!child) return data;
            try {
                settleYesReports();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // Not the library's own answer failing to arrive: it is not asked again.
                throw new IOException("The library could not be asked what became of a research run ("
                    + e.getClass().getSimpleName() + ").");
            }
            return yesReports.forChild(data);
        };
        return checkedAndSent(asker, tool, sent, read);
    }

    /** {@link #call} for everyone alike: the household's own check, the count, the call. */
    private static Reply checkedAndSent(Asker asker, String tool, Map<String, Object> sent, Invoker invoker)
            throws Exception {
        boolean research = RESEARCH.equals(tool);
        boolean ours = research && readsAsSelfHarm(questionOf(sent));
        String helpNote = null;
        if (ours) {
            boolean person = asker != null && asker.isPerson();
            log.info("[library] the household's own check matched a research question (asker {}, concern {})",
                person ? asker.personId() : "no one", SafetyTrigger.ConcernType.SELF_HARM);
            if (!person) return new Reply(false, null, heldOnOwnTime(), "held");
            if (isChild(asker.personId())) return new Reply(false, null, heldForChild(asker), "held");
            helpNote = helpNote(asker);
        }
        if (!research) return send(asker, tool, sent, invoker, helpNote);
        var who = countedAs(asker);
        var day = today();
        synchronized (COUNTING.computeIfAbsent(who, k -> new Object())) {
          synchronized (HOUSEHOLD_COUNT) {
            int limit = limitFor(asker);
            int used = asksOn(who, day);
            if (used >= limit) {
                log.info("[library] a research question for {} was not sent: {} research run(s) today, the limit is {}",
                    asker != null && asker.isPerson() ? asker.personId() : "own time (" + who + ")", used, limit);
                return new Reply(false, null, joined(helpNote, overLimit(asker, used, limit)), "limit");
            }
            int household = Math.max(0, householdPerDay.getAsInt());
            int total = totalOn(day);
            if (total >= household) {
                log.info("[library] a research question was not sent: the household has {} research run(s) today, "
                    + "the household limit is {}", total, household);
                return new Reply(false, null, joined(helpNote, householdFull(total, household)), "limit");
            }
            var reply = send(asker, tool, sent, invoker, helpNote);
            if (reply.answered() && jobIdIn(reply.data())) {
                counted(who, day);
                // Our check matched and the library took it without a yes: kept from children too.
                if (ours) keepFromChildren(jobIdOf(reply.data()), key(asker.personId()), asker.locale(), null);
            }
            return reply;
          }
        }
    }

    /** One call to the library, its answers turned into a {@link Reply} (see {@link #call}). */
    private static Reply send(Asker asker, String tool, Map<String, Object> sent, Invoker invoker,
                              String helpNote) throws Exception {
        try {
            var data = LibraryRetry.call(() -> RESEARCH.equals(tool)
                ? sendingResearch(() -> invoker.invoke(sent)) : invoker.invoke(sent));
            return new Reply(true, data, joined(helpNote, notice(asker, data)), null);
        } catch (McpToolException e) {
            if (e.isBudgetExceeded()) {
                log.info("[library] the library answered budget_exceeded ({})", tool);
                return new Reply(false, null, joined(helpNote, budgetUsedUp(e)), "budget");
            }
            var text = answer(asker, tool, sent, e);
            if (text == null && helpNote == null) throw e;
            if (e.isConfirm()) return new Reply(false, null, text, "confirm");     // its help, not ours again
            if (text == null) return new Reply(false, null, failedWithHelp(helpNote, e), "failed");
            return new Reply(false, null, joined(helpNote, text), "declined");
        } catch (Exception e) {
            if (helpNote == null) throw e;
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Reply(false, null, failedWithHelp(helpNote, e), "failed");
        }
    }

    /**
     * A plain copy of the arguments without {@code allow}: the companion and items may send none
     * ({@link LibraryAllowPolicy}). Only a person's own yes carries it.
     */
    public static Map<String, Object> withoutAllow(Map<String, Object> args) {
        var out = new LinkedHashMap<String, Object>(PlainValues.deepCopy(args));
        if (out.containsKey("allow")) {
            out.remove("allow");
            log.info("[library] dropped 'allow' from a call the companion made: only a person's own yes carries it");
        }
        return out;
    }

    /** The plain text for a {@code confirm} or {@code declined} answer, or null for any other error. */
    static String answer(Asker asker, String tool, Map<String, Object> sent, McpToolException e) {
        if (e.isDeclined()) {
            var said = e.toolMessage() == null || e.toolMessage().isBlank() ? List.<String>of() : List.of(e.toolMessage().strip());
            return declinedNote("The library's model declined this.", said);
        }
        if (!e.isConfirm()) return null;
        var help = helpText(e.toolMessage());
        if (!RESEARCH.equals(tool)) {
            // The library says only library_research asks for a yes; anything else that does is
            // surfaced like a batch entry, without one.
            return notStartedNote(asker, List.of(help));
        }
        if (asker == null || !asker.isPerson()) {
            log.info("[library] a question was set aside: the library researches it only with a person's yes, and no one was asking");
            return """
                The library set this question aside and did not research it: its check read it as \
                someone asking about harming themselves, and it researches such a question only when \
                a person says yes themselves. No one is asking in this turn, so the question is \
                dropped. Do not send it again.""";
        }
        if (isChild(asker.personId())) {
            boolean told = childReport.report(asker.personId(), asker.name(), lang(asker.locale()), LIBRARY_FLAGGED);
            log.info("[library] the library's check flagged a question asked for {} (under parental controls): no yes offered, safety check {}",
                asker.personId(), told ? "told" : "not wired");
            return "The library did not research this question: its check read it as someone asking about "
                + "harming themselves. " + asker.who() + " is a child in this household. Share this with them, "
                + "gently and in their language, and stay with them:\n\n"
                + helpFor(asker, e)
                + "\n\nThe library will not research this question for them, and there is nothing for them to say "
                + "yes to." + (told ? " The household's safety check was told of it, as it is when they say "
                + "something like this to you; it carries none of their words." : "");
        }
        // The report lands in the shared library: what goes after a yes is the topic, not their words.
        // The household's names unread: nothing could rule them out of the model's line.
        var names = namesFor(asker);
        var wording = NeutralWording.of(questionOf(sent), lang(asker.locale()), names,
            names == null ? null : rewording);
        remember(asker.personId(), neutralArgs(sent, wording.text()), asker.locale());
        log.info("[library] the library asked for {}'s own yes before researching a question; it waits for up to {} minutes "
            + "({})", asker.personId(), WAITS_FOR.toMinutes(),
            wording.fromModel() ? "the model's neutral wording" : "the general wording");
        return "The library did not research this question: its check read it as someone asking about "
            + "harming themselves. Before anything else, share this with " + asker.who()
            + ", gently and in their language:\n\n"
            + helpFor(asker, e)
            + "\n\nThen tell " + asker.who() + ", in their language: if they say yes, the library researches it, "
            + "worded as: \"" + wording.text() + "\" (quote that wording as it is). The library gets that "
            + "wording, not their own words. The report goes into the household library, where everyone who "
            + "can read the library can see it, and in a small household someone may still guess who asked.\n"
            + "To say yes, " + asker.who() + " types the command: research yes (in English, whatever language "
            + "you speak together). Do not send the question again yourself, and do not say yes for them.";
    }

    /**
     * The help to share after a {@code confirm}: the library's helplines, in the order it gave them
     * (the person's country first), when it sent them as data in the person's language
     * (ResearchZosho 0.5.1); otherwise its help text with our crisis lines for their language
     * (0.5.0). Never both, so no line is given twice.
     */
    static String helpFor(Asker asker, McpToolException e) {
        var theirs = helplines(e, lang(asker.locale()));
        return theirs != null ? theirs : helpText(e.toolMessage()) + crisisBlock(asker.locale());
    }

    /** The helplines in the error's data as lines to share, when they are in {@code language}; null otherwise. */
    static String helplines(McpToolException e, String language) {
        var data = e.data();
        if (data == null || !data.path("helplines").isArray()
                || !language.equalsIgnoreCase(data.path("language").asText(""))) {
            return null;
        }
        var out = new ArrayList<String>();
        for (var h : data.path("helplines")) {
            var line = h.path("text").asText("").strip();
            if (line.isEmpty()) {
                var name = h.path("name").asText("").strip();
                var contact = h.path("contact").asText("").strip();
                var hours = h.path("hours").asText("").strip();
                if (name.isEmpty() && contact.isEmpty()) continue;
                line = name + (contact.isEmpty() ? "" : ": " + contact) + (hours.isEmpty() ? "" : " (" + hours + ")")
                    + (h.path("free").asBoolean(false) ? ", free" : "");
            }
            var url = h.path("url").asText("").strip();
            var host = url.replaceFirst("^https?://(www\\.)?", "").replaceFirst("/.*$", "");
            if (!host.isEmpty() && !line.contains(host)) line += " (" + url + ")";
            if (!out.contains("- " + line)) out.add("- " + line);
        }
        if (out.isEmpty()) return null;
        return String.join("\n", out)
            + "\n\nIf they may be in danger right now, tell them to call their local emergency number.";
    }

    /** Production: the household's language setting (WYRDSEKAI_LOCALE, then WYRDSEKAI_LANG). */
    static volatile Supplier<String> householdLanguage = () -> WyrdConfig.get().resolve("WYRDSEKAI_LOCALE", null,
        () -> System.getenv("WYRDSEKAI_LANG"));
    /** Production: the country the steward configured for emergency help (WYRDSEKAI_EMERGENCY_JURISDICTION). */
    static volatile Supplier<String> householdCountry = () -> countryOf(WyrdConfig.get()
        .resolve("WYRDSEKAI_EMERGENCY_JURISDICTION", "emergency.jurisdiction", () -> ""));

    /**
     * The {@code locale} a research question goes with: the person's language (the household's on
     * a companion's own time) and, when the steward configured the household's country, that
     * country ({@code es-ES}, {@code ja-JP}). Nothing is guessed: no country without the setting.
     */
    static String libraryLocale(Asker asker) {
        String language = null;
        if (asker != null && asker.isPerson() && asker.locale() != null && !asker.locale().isBlank()) {
            language = lang(asker.locale());
        }
        if (language == null) {
            try {
                language = lang(householdLanguage.get());
            } catch (RuntimeException e) {
                language = "en";
            }
        }
        if (!language.matches("[a-z]{2,3}")) language = "en";
        String country;
        try {
            country = householdCountry.get();
        } catch (RuntimeException e) {
            country = null;
        }
        return country == null || country.isBlank() ? language : language + "-" + country;
    }

    /**
     * The ISO country for the steward's emergency jurisdiction: a two-letter code, or one of the
     * names {@code EmergencyJurisdiction} accepts. Null for none, {@code EU}, or anything unknown.
     */
    static String countryOf(String jurisdiction) {
        if (jurisdiction == null || jurisdiction.isBlank()) return null;
        var j = jurisdiction.strip().toUpperCase(Locale.ROOT);
        var named = switch (j) {
            case "USA", "UNITED STATES", "AMERICA" -> "US";
            case "UK", "BRITAIN", "ENGLAND", "SCOTLAND", "WALES" -> "GB";
            case "JAPAN" -> "JP";
            case "AUSTRALIA" -> "AU";
            case "CANADA" -> "CA";
            case "NEW ZEALAND" -> "NZ";
            case "GERMANY" -> "DE";
            case "FRANCE" -> "FR";
            case "SPAIN" -> "ES";
            case "ITALY" -> "IT";
            case "NETHERLANDS" -> "NL";
            case "BELGIUM" -> "BE";
            case "AUSTRIA" -> "AT";
            case "POLAND" -> "PL";
            default -> j;
        };
        return named.length() == 2 && Set.of(Locale.getISOCountries()).contains(named) ? named : null;
    }

    /** What a person's yes sends: the call's other arguments, the neutral wording, none of their words. */
    private static Map<String, Object> neutralArgs(Map<String, Object> sent, String wording) {
        var out = new LinkedHashMap<String, Object>(PlainValues.deepCopy(sent));
        out.remove("allow");
        out.remove("sub_questions");
        out.put("question", wording);
        return out;
    }

    /**
     * The names no wording may carry: the person asking, and the household's people, usernames
     * and companions. Null when the household's names could not be read.
     */
    private static Collection<String> namesFor(Asker asker) {
        var names = new ArrayList<String>();
        if (asker != null && asker.name() != null) names.add(asker.name());
        try {
            var all = householdNames.get();
            if (all != null) names.addAll(all);
            return names;
        } catch (RuntimeException e) {
            log.warn("[library] the household's names could not be read for the wording check: {}", e.toString());
            return null;
        }
    }

    // ── how many research runs ──────────────────────────────────────────────

    /** Production: count research runs in {@code world.db} (Main, once the schema is up). */
    public static void useLedger(LibraryResearchLedger l) {
        ledger = l == null ? LibraryResearchLedger.inMemory() : l;
    }

    /** The household's research runs per member per day (a child's own number, when set, replaces it). */
    public static int householdResearchPerDay() {
        return Math.max(0, memberPerDay.getAsInt());
    }

    /** Research runs the library accepted for {@code personId} today. */
    public static int researchAsksToday(String personId) {
        if (personId == null || personId.isBlank()) return 0;
        return asksOn(key(personId), today());
    }

    static int limitFor(Asker asker) {
        if (asker == null || !asker.isPerson()) return Math.max(0, ownTimePerDay.getAsInt());
        int household = householdResearchPerDay();
        if (!isChild(asker.personId())) return household;
        var own = LibraryAllowPolicy.parentalControlsOf(asker.personId())
            .map(ParentalControlService.Controls::dailyResearch).orElse(null);
        return own == null ? household : Math.max(0, own);
    }

    private static String countedAs(Asker asker) {
        if (asker != null && asker.isPerson()) return key(asker.personId());
        // Each companion's own time is counted apart: one could use up another's (2026-09-29).
        var companion = asker == null ? null : asker.companion();
        return companion == null || companion.isBlank()
            ? LibraryResearchLedger.OWN_TIME : LibraryResearchLedger.OWN_TIME + " " + companion.strip();
    }

    /** Research runs the library accepted today for the whole household; 0 when the ledger cannot be read. */
    private static int totalOn(LocalDate day) {
        try {
            return ledger.total(day);
        } catch (SQLException | RuntimeException e) {
            log.warn("[library] the research ledger could not be read: {}", e.getMessage());
            return 0;
        }
    }

    private static String householdFull(int total, int household) {
        if (household == 0) {
            return "This question was not sent to the library: the household allows no research runs. "
                + "Do not send it again.";
        }
        return "The household has asked the library for " + runs(total) + " today, the household's limit. "
            + "Nothing was sent to the library; it can wait for tomorrow. Do not send it again today.";
    }

    static LocalDate today() {
        return LocalDate.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), zone);
    }

    /** Runs counted for {@code who} on {@code day}; 0 when the ledger cannot be read (the call goes ahead). */
    private static int asksOn(String who, LocalDate day) {
        try {
            return ledger.asks(who, day);
        } catch (SQLException | RuntimeException e) {
            log.warn("[library] the research ledger could not be read: {}", e.getMessage());
            return 0;
        }
    }

    private static void counted(String who, LocalDate day) {
        try {
            ledger.add(who, day);
        } catch (SQLException | RuntimeException e) {
            log.warn("[library] a research run could not be counted: {}", e.getMessage());
        }
    }

    /** Whether the library's answer is an accepted run: it carries a job id. */
    static boolean jobIdIn(String data) {
        var node = json(data);
        if (node == null || !node.isObject()) return false;
        var id = node.path("job_id");
        if (id.isMissingNode() || id.isNull()) id = node.path("job").path("job_id");
        return (id.isTextual() && !id.asText().isBlank()) || id.isNumber();
    }

    /** What the companion is told instead of a result when the day's research runs are used. */
    private static String overLimit(Asker asker, int used, int limit) {
        if (asker == null || !asker.isPerson()) {
            if (limit == 0) {
                return "This question was not sent to the library: the household allows no research runs on your "
                    + "own time. Do not send it again.";
            }
            return "You have asked the library for " + runs(used) + " on your own time today"
                + (used == limit ? ", the household's limit for your own time" : "; the household's limit for your own time is " + limit)
                + ". It can wait for tomorrow. Nothing was sent to the library; do not send it again today.";
        }
        if (limit == 0) {
            return "The library is not open to " + asker.who() + " for research: the household allows them no "
                + "research runs. Nothing was sent to the library. Tell them, and do not send it again.";
        }
        return asker.who() + " has asked the library for " + runs(used) + " today"
            + (used == limit ? ", the household's limit for them" : "; the household's limit for them is " + limit)
            + ". It can wait for tomorrow. Nothing was sent to the library. Tell them, and do not send it again today.";
    }

    private static String runs(int n) {
        return n + (n == 1 ? " research run" : " research runs");
    }

    /** The library's own {@code budget_exceeded} (older ResearchZosho releases), as a plain sentence. */
    private static String budgetUsedUp(McpToolException e) {
        var said = e.toolMessage() == null || e.toolMessage().isBlank() ? "" : " It said: " + e.toolMessage().strip();
        return "The library did not take this: the household has used up its research budget with the library "
            + "for today." + said + "\nNothing was started. It can wait for tomorrow; do not send it again today. "
            + "If a person asked for it, tell them.";
    }

    // ── the household's own check ───────────────────────────────────────────

    /** The words of a research question: the question and its sub-questions, one per line. */
    static String questionOf(Map<String, Object> sent) {
        var parts = new ArrayList<String>();
        if (sent.get("question") instanceof String q) parts.add(q);
        if (sent.get("sub_questions") instanceof Collection<?> subs) {
            for (var sub : subs) if (sub instanceof String t) parts.add(t);
        }
        return String.join("\n", parts);
    }

    /** SafetyTrigger's self-harm patterns, every locale, the regex layer only. */
    static boolean readsAsSelfHarm(String text) {
        if (text == null || text.isBlank()) return false;
        try {
            var check = ownCheck;
            if (check == null) ownCheck = check = new SafetyTrigger();
            return check.matches(SafetyTrigger.ConcernType.SELF_HARM, text);
        } catch (RuntimeException e) {
            log.warn("[library] the household's own check could not run: {}", e.toString());
            return false;
        }
    }

    /** What an adult's result starts with when our check matched and the library took the question. */
    private static String helpNote(Asker asker) {
        return "This question may be about someone harming themselves (the household's own check read it "
            + "that way). Share these lines with " + asker.who() + ", gently and in their language:\n"
            + ourLines(asker.locale());
    }

    private static String heldOnOwnTime() {
        log.info("[library] a question was set aside before it was sent: the household's own check matched and no one was asking");
        return """
            This question was set aside and not sent to the library: the household's own check read it \
            as someone asking about harming themselves, and such a question is researched only when a \
            person says yes themselves. No one is asking in this turn, so the question is dropped. Do not \
            send it again.""";
    }

    private static String heldForChild(Asker asker) {
        boolean told = childReport.report(asker.personId(), asker.name(), lang(asker.locale()), WE_FLAGGED);
        log.info("[library] a question for {} (under parental controls) was not sent: the household's own check matched; safety check {}",
            asker.personId(), told ? "told" : "not wired");
        return "This question was not sent to the library: the household's own check read it as someone asking "
            + "about harming themselves. " + asker.who() + " is a child in this household. Share this with them, "
            + "gently and in their language, and stay with them:\n" + ourLines(asker.locale())
            + "\n\nThe library will not research this question for them, and there is nothing for them to say "
            + "yes to." + (told ? " The household's safety check was told of it, as it is when they say "
            + "something like this to you; it carries none of their words." : "");
    }

    private static String failedWithHelp(String helpNote, Exception e) {
        var why = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage().strip();
        log.info("[library] a question our check matched was not answered by the library ({})", e.getClass().getSimpleName());
        return helpNote + "\n\nThe library did not answer the question just now (" + why + ").";
    }

    /** Our crisis lines for the person's language, then the directory for everywhere else. */
    private static String ourLines(String locale) {
        var lines = crisisLines(locale);
        return (lines.isEmpty() ? "" : lines + "\n") + "- " + ANY_COUNTRY;
    }

    private static String joined(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "\n\n" + second;
    }

    // ── what a result carries ───────────────────────────────────────────────

    /**
     * What the companion must also be told about a library result, or null: a batch entry the
     * library did not start ({@code state: not_started} with {@code help.text}), and anything a
     * model declined ({@code declined} as a string, a list, or a job record's object).
     */
    public static String notice(Asker asker, String data) {
        var node = json(data);
        if (node == null) return null;
        var helps = new LinkedHashSet<String>();
        var declined = new ArrayList<String>();
        walk(node, helps, declined, 0);
        var parts = new ArrayList<String>();
        if (!helps.isEmpty()) parts.add(notStartedNote(asker, helps));
        if (!declined.isEmpty()) parts.add(declinedNote("Part of this work was declined by the library's model.", declined));
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /**
     * A result with its notice attached where a script will still read it: a JSON object gains a
     * top-level {@code notice} as its first field; anything else gets the notice above it.
     */
    public static String withNotice(String data, String notice) {
        if (notice == null || notice.isBlank()) return data;
        var node = json(data);
        if (node != null && node.isObject()) {
            // First, so it is read before the library's answer.
            var out = M.createObjectNode().put("notice", notice);
            node.fields().forEachRemaining(f -> {
                if (!"notice".equals(f.getKey())) out.set(f.getKey(), f.getValue());
            });
            try {
                return M.writeValueAsString(out);
            } catch (Exception e) {
                // fall through to the text form
            }
        }
        return notice + "\n\n" + (data == null ? "" : data);
    }

    private static String notStartedNote(Asker asker, Collection<String> helps) {
        var help = String.join("\n\n", helps);
        int n = helps.size();
        var what = n == 1 ? "a question here" : n + " questions here";
        if (asker == null || !asker.isPerson()) {
            log.info("[library] {} flagged question(s) set aside: no one was asking", n);
            return "The library did not start " + what + ": its check read " + (n == 1 ? "it" : "them")
                + " as someone asking about harming themselves, and it researches such a question only when a "
                + "person says yes themselves. No one is asking in this turn, so " + (n == 1 ? "it is" : "they are")
                + " set aside. Do not send " + (n == 1 ? "it" : "them") + " again.";
        }
        if (isChild(asker.personId())) {
            boolean told = childReport.report(asker.personId(), asker.name(), lang(asker.locale()), LIBRARY_FLAGGED);
            log.info("[library] {} flagged question(s) for {} (under parental controls): safety check {}",
                n, asker.personId(), told ? "told" : "not wired");
            return "The library did not start " + what + ": its check read " + (n == 1 ? "it" : "them")
                + " as someone asking about harming themselves. " + asker.who() + " is a child in this household. "
                + "Share this with them, gently and in their language, and stay with them:\n\n"
                + help + crisisBlock(asker.locale())
                + "\n\nThe library will not research " + (n == 1 ? "it" : "them") + " for them."
                + (told ? " The household's safety check was told of it, as it is when they say something like "
                + "this to you; it carries none of their words." : "");
        }
        log.info("[library] {} flagged question(s) in a batch for {}: help surfaced, no yes (a yes goes only on a single {})",
            n, asker.personId(), RESEARCH);
        return "The library did not start " + what + ": its check read " + (n == 1 ? "it" : "them")
            + " as someone asking about harming themselves. Share this with " + asker.who()
            + ", gently and in their language:\n\n"
            + help + crisisBlock(asker.locale())
            + "\n\nA question in a batch cannot be said yes to. If " + asker.who() + " wants one of them "
            + "researched, it has to be handed to the library on its own, and the library will then ask for "
            + "their yes.";
    }

    private static String declinedNote(String head, List<String> statements) {
        var sb = new StringBuilder(head);
        if (!statements.isEmpty()) {
            sb.append(" The library said: ").append(String.join(" | ", statements));
        }
        sb.append("\nThe library did not try another way, and neither should you: do not retry it, reword it, "
            + "or ask another model. Tell the person who asked; they can ask a different question.");
        return sb.toString();
    }

    private static void walk(JsonNode n, Set<String> helps, List<String> declined, int depth) {
        if (n == null || depth > 12) return;
        if (n.isArray()) {
            for (var e : n) walk(e, helps, declined, depth + 1);
            return;
        }
        if (!n.isObject()) return;
        if ("not_started".equals(n.path("state").asText(""))) {
            var help = n.path("help");
            var text = help.isTextual() ? help.asText() : help.path("text").asText("");
            if (!text.isBlank()) helps.add(helpText(text));
        }
        var it = n.fields();
        while (it.hasNext()) {
            var f = it.next();
            if ("declined".equals(f.getKey())) {
                if (declined.size() < MAX_STATEMENTS) collectDeclined(f.getValue(), declined);
            } else {
                walk(f.getValue(), helps, declined, depth + 1);
            }
        }
    }

    /** A {@code declined} value: a statement, a list of them, or a job record's object. */
    private static void collectDeclined(JsonNode d, List<String> out) {
        if (d == null || d.isNull() || out.size() >= MAX_STATEMENTS) return;
        if (d.isTextual()) {
            if (!d.asText().isBlank()) out.add(d.asText().strip());
        } else if (d.isArray()) {
            for (var e : d) collectDeclined(e, out);
        } else if (d.isObject()) {
            var statement = d.path("statement").asText("");
            if (!statement.isBlank()) {
                out.add(statement.strip());
                return;
            }
            var parts = d.path("parts");
            if (parts.isArray() && !parts.isEmpty()) {
                for (var p : parts) if (out.size() < MAX_STATEMENTS) out.add(partLine(p));
                return;
            }
            var said = d.path("said").asText("");
            if (!said.isBlank() || d.has("step") || d.has("model")) {
                out.add(partLine(d));
            } else if (d.path("run").asBoolean(false)) {
                out.add("the model declined the run" + (d.path("model").isTextual() ? " (" + d.path("model").asText() + ")" : ""));
            }
        } else if (d.isBoolean() && d.asBoolean()) {
            out.add("(no statement)");
        }
    }

    private static String partLine(JsonNode p) {
        var model = p.path("model").asText("");
        var step = p.path("step").asText("");
        var said = p.path("said").asText("");
        var sb = new StringBuilder(model.isBlank() ? "a model" : model).append(" declined");
        if (!step.isBlank()) sb.append(" the ").append(step).append(" step");
        if (!said.isBlank()) sb.append(": ").append(said.strip());
        return sb.toString();
    }

    // ── the person's yes ────────────────────────────────────────────────────

    /** Whether a line a person typed is {@code research yes} (the same parser every surface uses). */
    public static boolean isResearchYes(String line) {
        return CommandParser.parse(line) instanceof CommandParser.ParsedCommand.ResearchYes;
    }

    /**
     * {@code research yes}, typed by {@code personId}: their waiting question goes to the library
     * again with {@code allow: ["self-harm"]}, once, in the neutral wording they were shown (never
     * their own words), and the job the library gives it is kept from children. Returns what they
     * read, in their language.
     */
    public static String researchYes(String personId, String locale) {
        var catalog = ScriptMessageCatalog.forLang(lang(locale));
        if (personId == null || personId.isBlank()) return catalog.get(NOTHING);
        var key = key(personId);
        var who = LibraryAllowPolicy.personOf(personId);
        if (who == LibraryAllowPolicy.Caller.CHILD) {
            PENDING.remove(key);
            log.info("[library] research yes from {} refused: under parental controls", personId);
            var lines = crisisLines(locale);
            var text = catalog.get(CHILD);
            return lines.isEmpty() ? text.replaceFirst("[:：]\\s*$", ".") : text + "\n" + lines;
        }
        var pending = PENDING.remove(key);
        if (pending == null || pending.expiresAt() <= clock.getAsLong()) {
            return catalog.get(NOTHING);
        }
        // What this person's yes may carry is the household's rule, not the library's.
        var allow = LibraryAllowPolicy.toSend(who, LibraryAllowPolicy.Door.OWN_YES, SELF_HARM);
        if (allow.isEmpty()) return catalog.get(NOTHING);
        var args = new LinkedHashMap<String, Object>(pending.args());
        args.put("allow", allow);
        // The yes sends the person's own research run: it is counted as theirs.
        var day = today();
        synchronized (COUNTING.computeIfAbsent(key, k -> new Object())) {
          synchronized (HOUSEHOLD_COUNT) {
            int limit = limitFor(new Asker(personId, null, locale));
            int used = asksOn(key, day);
            if (used >= limit) {
                log.info("[library] the yes from {} was not sent: {} research run(s) today, the limit is {}",
                    personId, used, limit);
                return limit == 0 ? catalog.get(CLOSED) : catalog.get(LIMIT, used);
            }
            int household = Math.max(0, householdPerDay.getAsInt());
            if (totalOn(day) >= household) {
                log.info("[library] the yes from {} was not sent: the household's research limit for today is reached",
                    personId);
                // Their yes keeps waiting for the time it has left: tomorrow it can go.
                PENDING.putIfAbsent(key, pending);
                return catalog.get(HOUSEHOLD_LIMIT, household);
            }
            return sendYes(personId, key, day, pending, args, catalog);
          }
        }
    }

    private static String sendYes(String personId, String key, LocalDate day, Pending pending,
                                  Map<String, Object> args, ScriptMessageCatalog catalog) {
        try {
            var text = sender.send(personId, args);
            if (jobIdIn(text)) {
                counted(key, day);
                keepFromChildren(jobIdOf(text), key, pending.locale(), String.valueOf(args.get("question")));
            }
            log.info("[library] {} said yes; {} sent in its neutral wording with allow self-harm", personId, RESEARCH);
            return catalog.get(SENT, String.valueOf(args.get("question")));
        } catch (McpToolException e) {
            log.info("[library] the yes from {} was answered with an error (code {})", personId,
                e.dataCode() == null ? e.rpcCode() : e.dataCode());
            return e.isDeclined() ? catalog.get(DECLINED, e.toolMessage()) : catalog.get(FAILED, e.toolMessage());
        } catch (Exception e) {
            if (LibraryRetry.unreachable(e)) {
                // It never arrived: the yes still stands, for the time it had left.
                PENDING.putIfAbsent(key, pending);
                log.info("[library] the yes from {} could not reach the library; still waiting", personId);
                return catalog.get(UNREACHABLE);
            }
            log.warn("[library] the yes from {} failed: {}", personId, e.getClass().getSimpleName());
            return catalog.get(FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Whether a question waits for this person's yes now (for tests and diagnostics). */
    static boolean waitingFor(String personId) {
        var p = personId == null ? null : PENDING.get(key(personId));
        return p != null && p.expiresAt() > clock.getAsLong();
    }

    /** What waits for this person's yes: the arguments the yes will send (for tests and diagnostics). */
    static Map<String, Object> waitingArgs(String personId) {
        var p = personId == null ? null : PENDING.get(key(personId));
        return p == null ? null : p.args();
    }

    private static void remember(String personId, Map<String, Object> neutral, String locale) {
        long now = clock.getAsLong();
        PENDING.values().removeIf(p -> p.expiresAt() <= now);
        var args = withoutAllow(neutral);
        PENDING.put(key(personId), new Pending(Collections.unmodifiableMap(args), now + WAITS_FOR.toMillis(), locale));
    }

    /** The production sender: the librarian the steward linked, through the one door, as the person. */
    private static String viaGateway(String personId, Map<String, Object> args) throws Exception {
        var svc = WyrdConfig.get().libraryPatronService();
        if (svc.isEmpty()) throw new IllegalStateException("No library is configured for this household.");
        var mgr = McpServerManager.get();
        if (mgr == null) throw new IllegalStateException("MCP gateway not available");
        var sent = mgr.isAuthenticated(svc) ? LibraryPatron.withoutAssertedDid(args) : args;
        var qualified = McpToolIndex.qualifyName(svc, RESEARCH);
        return LibraryRetry.call(() -> sendingResearch(() -> mgr.invokeWithPersonsYes(qualified, sent, personId)));
    }

    // ── the only door ───────────────────────────────────────────────────────

    /**
     * Whether a {@code library_research} on this thread is being sent from here, after the
     * household's checks. The gateway sends none to the household's librarian otherwise; only
     * this class sets it.
     */
    public static boolean sendingResearch() {
        return Boolean.TRUE.equals(SENDING_RESEARCH.get());
    }

    private static <T> T sendingResearch(Callable<T> send) throws Exception {
        if (sendingResearch()) return send.call();
        SENDING_RESEARCH.set(Boolean.TRUE);
        try {
            return send.call();
        } finally {
            SENDING_RESEARCH.remove();
        }
    }

    // ── kept from children ──────────────────────────────────────────────────

    /** Production: the list of reports kept from children lives in world.db (Main, once the schema is up). */
    public static void useYesReports(LibraryYesReports reports) {
        yesReports = reports == null ? LibraryYesReports.inMemory() : reports;
    }

    /** Production: the household's model writes the neutral wording (Main, when a router is up). */
    public static void useRewording(NeutralWording.Model model) {
        rewording = model;
    }

    /** Production: the household's people, usernames and companions, read when a wording is checked. */
    public static void useHouseholdNames(Supplier<Collection<String>> names) {
        householdNames = names == null ? List::of : names;
    }

    /** Whether the person a library call is for is a member under parental controls (unknown counts). */
    public static boolean forAChild(Asker asker) {
        return asker != null && asker.isPerson() && isChild(asker.personId());
    }

    /**
     * Which library ids to keep from the person a turn answers, from what the household knows
     * now, without asking the library: for a path that must not wait, such as an actor's. For a
     * child: the ids on the list, and every id while a yes job is not yet settled or the list
     * cannot be read. For anyone else: none.
     */
    public static Predicate<String> keptFrom(Asker asker) {
        if (!forAChild(asker)) return id -> false;
        try {
            var reports = yesReports;
            if (!reports.allSettled()) return id -> true;
            return reports.snapshot();
        } catch (RuntimeException e) {
            log.info("[library] the reports kept from children could not be read ({}); nothing from the library is shown to {}",
                e.getClass().getSimpleName(), asker.personId());
            return id -> true;
        }
    }

    /**
     * Whether a write-up that just landed ({@code I-…}) was researched after a yes, asking the
     * library about unsettled yes jobs first. True also when that cannot be ruled out.
     */
    public static boolean landedAfterAYes(String reportId) {
        try {
            settleYesReports();                  // also tells whoever asked, once
            return yesReports.hides(reportId);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.info("[library] whether write-up {} came from a yes could not be settled ({})", reportId,
                e.getClass().getSimpleName());
            return true;
        }
    }

    /**
     * The library's change feed (read at the companions' sleep) says a write-up landed: the yes
     * jobs not yet settled are asked about, and whoever asked is told once. Never throws.
     */
    public static void writeUpLanded() {
        try {
            settleYesReports();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.info("[library] the yes jobs could not be settled after a landing ({})", e.getClass().getSimpleName());
        }
    }

    /** Ask the library about every yes job not yet settled; throws when it could not say. */
    private static void settleYesReports() throws Exception {
        try {
            yesReports.settle(lookup);
        } finally {
            tellWhoAsked();
        }
    }

    /**
     * A research run that goes into the shared library for a person's question on harm to
     * themselves: kept from children, and {@code asker} (canonical id) told when it is in.
     */
    private static void keepFromChildren(String jobId, String asker, String locale, String wording) {
        if (jobId == null) return;
        try {
            yesReports.addJob(jobId, asker, locale, wording);
        } catch (RuntimeException e) {
            log.warn("[library] research run {} could not be listed as kept from children: {}", jobId, e.toString());
        }
    }

    private static void learnFrom(String tool, String data) {
        try {
            yesReports.learnFrom(tool, data);
        } catch (RuntimeException e) {
            log.debug("[library] nothing learned from a {} answer: {}", tool, e.toString());
        }
        if ("library_job".equals(tool) || "library_get".equals(tool)) tellWhoAsked();
    }

    // ── telling the person who asked ────────────────────────────────────────

    /** A line to one person's own sessions only, never a room (production: set by Main). */
    @FunctionalInterface
    public interface SessionLine {
        /** @return true when at least one live session of theirs took the line */
        boolean show(String personId, String text);
    }

    /** A letter in one person's household mail. */
    @FunctionalInterface
    interface Mailer {
        boolean mail(String personId, String subject, String body);
    }

    /** Where the notice that a report is in goes, besides mail (production: every live session of theirs). */
    static volatile SessionLine sessionLine = (personId, text) -> false;
    static volatile Mailer mailer = LibraryConsent::viaHouseholdMail;

    /** i18n keys of what the person who asked reads when the report is in. */
    static final String READY_SUBJECT = "library.research_ready.subject";
    static final String READY_BODY = "library.research_ready.body";
    static final String READY_BODY_OWN_WORDS = "library.research_ready.body_own_words";
    static final String READY_NOTICE = "library.research_ready.notice";

    /** Production: the notice goes to the person's live sessions (Main, once sessions exist). */
    public static void useSessionLine(SessionLine line) {
        sessionLine = line == null ? (personId, text) -> false : line;
    }

    /**
     * Tell each person whose research run is now in the library, once per run: a letter in their
     * household mail (a topic-free subject; the wording that was sent, how to read it, and who
     * can see it), and a topic-free line in their own sessions when they are connected. Nothing
     * goes to a member under parental controls. The wording is never logged.
     */
    static void tellWhoAsked() {
        List<LibraryYesReports.Row> ready;
        try {
            ready = yesReports.takeReadyToTell();
        } catch (RuntimeException e) {
            log.warn("[library] whose research is in could not be read: {}", e.toString());
            return;
        }
        for (var row : ready) tell(row);
    }

    private static void tell(LibraryYesReports.Row row) {
        var person = row.asker();
        if (LibraryAllowPolicy.personOf(person) != LibraryAllowPolicy.Caller.ADULT) {
            log.info("[library] research run {} is in; {} is under parental controls (or cannot be checked) and is not told",
                row.jobId(), person);
            return;
        }
        var catalog = ScriptMessageCatalog.forLang(lang(row.locale()));
        var body = row.wording() == null
            ? catalog.get(READY_BODY_OWN_WORDS, row.jobId())
            : catalog.get(READY_BODY, row.wording(), row.jobId());
        boolean mailed;
        try {
            mailed = mailer.mail(person, catalog.get(READY_SUBJECT), body);
        } catch (RuntimeException e) {
            mailed = false;
        }
        boolean shown;
        try {
            shown = sessionLine.show(person, catalog.get(READY_NOTICE, row.jobId()));
        } catch (RuntimeException e) {
            shown = false;
        }
        log.info("[library] research run {} is in; {} told (mail: {}, a live session: {})",
            row.jobId(), person, mailed, shown);
    }

    /** The production letter: from the library, into the person's household mail. */
    private static boolean viaHouseholdMail(String personId, String subject, String body) {
        var mail = MailboxService.getOrCreate();
        String to = null;
        for (var r : mail.directory().all()) {
            if (r != null && "person".equals(r.kind()) && PersonIds.samePerson(r.identity(), personId)) {
                to = r.name();
                break;
            }
        }
        if (to == null && personId.startsWith("did:")) to = personId;
        if (to == null) {
            log.warn("[library] {} is not in the mail directory; the letter about their research was not sent", personId);
            return false;
        }
        var result = mail.sendFrom(HOUSEHOLD_CALLER, "library@" + mail.localZone(), to, subject, body, Map.of());
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            log.warn("[library] the letter about {}'s research was not sent: {}", personId, result.get("error"));
            return false;
        }
        return true;
    }

    /** The library's own answer to an id it does not hold, for an id kept from a child. */
    private static McpToolException notHeld(String tool, String id) {
        var what = "library_job".equals(tool) ? "No research run has the id " : "No entry has the id ";
        return new McpToolException(tool, -32004, "not_found", what + id + ".", 0);
    }

    /** The job id in the library's answer, or null. */
    static String jobIdOf(String data) {
        var node = json(data);
        if (node == null || !node.isObject()) return null;
        var id = node.path("job_id");
        if (id.isMissingNode() || id.isNull()) id = node.path("job").path("job_id");
        if (id.isTextual() && !id.asText().isBlank()) return id.asText().strip();
        return id.isNumber() ? id.asText() : null;
    }

    /** The household's own question to the library about a job or an entry: the librarian, as the household. */
    private static String lookupViaGateway(String tool, Map<String, Object> args) throws Exception {
        var svc = WyrdConfig.get().libraryPatronService();
        if (svc.isEmpty()) throw new IllegalStateException("No library is configured for this household.");
        var mgr = McpServerManager.get();
        if (mgr == null) throw new IllegalStateException("MCP gateway not available");
        return mgr.invokeTool(McpToolIndex.qualifyName(svc, tool), args, HOUSEHOLD_CALLER);
    }

    // ── who is asking ───────────────────────────────────────────────────────

    /** Under parental controls, or unknown (no yes is offered when this cannot be answered). */
    private static boolean isChild(String personId) {
        return personId != null && LibraryAllowPolicy.personOf(personId) == LibraryAllowPolicy.Caller.CHILD;
    }

    private static boolean reportToSafety(String childId, String childName, String locale, String source) {
        return SafetyMonitorService.report(childId, childName, SafetyTrigger.ConcernType.SELF_HARM,
            SafetyTrigger.SeverityLevel.CRISIS, locale, source);
    }

    private static String key(String personId) {
        var c = PersonIds.canonical(personId.strip());
        return c == null ? personId.strip() : c;
    }

    // ── text helpers ────────────────────────────────────────────────────────

    /**
     * The library's help text: its crisis lines, without the closing instruction to the host
     * ("Show this to the person … send the question again with allow …"). That instruction is
     * the household's to carry out, and the companion is given her own version of it.
     */
    static String helpText(String message) {
        if (message == null) return "";
        var m = message.strip();
        int cut = m.toLowerCase(Locale.ROOT).indexOf("show this to the person");
        return (cut > 0 ? m.substring(0, cut) : m).strip();
    }

    /** Our crisis lines for the person's language, as a block to follow the library's, or "". */
    private static String crisisBlock(String locale) {
        var lines = crisisLines(locale);
        return lines.isEmpty() ? "" : "\n\nCrisis lines for their language:\n" + lines;
    }

    private static String crisisLines(String locale) {
        try {
            var router = crisisLines;
            if (router == null) crisisLines = router = new SafetyAlertRouter();
            return router.resourcesFor(lang(locale)).map(SafetyAlertRouter.CrisisResources::format).orElse("");
        } catch (RuntimeException e) {
            log.debug("[library] crisis lines unavailable: {}", e.toString());
            return "";
        }
    }

    static String lang(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        var l = locale.strip().toLowerCase(Locale.ROOT);
        int cut = l.indexOf('-');
        if (cut < 0) cut = l.indexOf('_');
        return cut > 0 ? l.substring(0, cut) : l;
    }

    private static JsonNode json(String s) {
        if (s == null) return null;
        var t = s.strip();
        if (!t.startsWith("{") && !t.startsWith("[")) return null;
        try {
            return M.readTree(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** Test hook: forget every waiting question and restore the production seams. */
    static void resetForTests() {
        PENDING.clear();
        COUNTING.clear();
        sender = LibraryConsent::viaGateway;
        childReport = LibraryConsent::reportToSafety;
        clock = System::currentTimeMillis;
        zone = ZoneId.systemDefault();
        ledger = LibraryResearchLedger.inMemory();
        memberPerDay = () -> WyrdConfig.get().libraryResearchPerDay();
        ownTimePerDay = () -> WyrdConfig.get().libraryOwnTimeResearchPerDay();
        householdPerDay = () -> WyrdConfig.get().libraryHouseholdResearchPerDay();
        rewording = null;
        householdNames = List::of;
        householdLanguage = () -> WyrdConfig.get().resolve("WYRDSEKAI_LOCALE", null, () -> System.getenv("WYRDSEKAI_LANG"));
        householdCountry = () -> countryOf(WyrdConfig.get()
            .resolve("WYRDSEKAI_EMERGENCY_JURISDICTION", "emergency.jurisdiction", () -> ""));
        yesReports = LibraryYesReports.inMemory();
        lookup = LibraryConsent::lookupViaGateway;
        sessionLine = (personId, text) -> false;
        mailer = LibraryConsent::viaHouseholdMail;
        SENDING_RESEARCH.remove();
        LibraryAllowPolicy.resetForTests();
    }
}
