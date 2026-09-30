package org.wyrdsekai.core.agent.interiority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.AspirationWantSynthesizer;
import org.wyrdsekai.core.agent.Want;
import org.wyrdsekai.core.agent.WantStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * the drive-OODA orchestrator.
 *
 * <p>Runs once per awake-consolidation tick (no new scheduler). Walks the
 * Observe → Orient → Decide → Act loop, persists chosen wants, and writes a
 * tick line to the activity log. Cadence for the *next* tick is derived from
 * this tick's state via {@link CadenceModulator}.
 *
 * <p>This class is the *pure orchestrator*. It holds no actor references and
 * does no inference itself — it expects an {@link OrientStep} callback to
 * generate candidate wants and a {@link DecideStep} callback to pick one, both
 * of which CompanionActor wires up to its inference path. That keeps the
 * tick-shape testable without booting an actor system.
 */
public final class DriveOODA {

    private static final Logger log = LoggerFactory.getLogger(DriveOODA.class);

    /** Callback into the agent's inference path: produce 0..N candidate wants. */
    /**
     * When Curiosity pulls toward the library, a subject she said she would learn takes that
     * candidate's place, at that candidate's weight.
     *
     * <p>The rule menu mints "explore the library for something new" at the Curiosity pull's
     * weight; a subject she said she would learn was minted as its own want at 0.55 and lost to
     * the template every time (2026-09-22: four own-time turns searched the library for the
     * template's words while her yes sat unvisited). Her curiosity has a subject now, so when it
     * pulls toward the library it pulls toward that. When Curiosity is not pulling, nothing is
     * added: the want does not outweigh a rest or another drive on a scale it does not share.
     * The other Curiosity outlet (reading something she has not read in a while) and every other
     * drive's candidate stay as they were. A subject that names a thing to build is not read.
     */
    static List<CandidateWant> withHerOwnSubjects(List<CandidateWant> candidates, List<Want> liveWants) {
        if (candidates == null || candidates.isEmpty() || liveWants == null || liveWants.isEmpty()) return candidates;
        var subjectWant = herSubjectToRead(liveWants);
        if (subjectWant == null) return candidates;
        int at = -1;
        for (int i = 0; i < candidates.size(); i++) {
            var c = candidates.get(i);
            if (c != null && !c.isRest() && "Curiosity".equals(driveOf(c))
                    && "library_search".equals(DriveWantMapper.extractVerb(c))) { at = i; break; }
        }
        if (at < 0) return candidates;
        var out = new ArrayList<>(candidates);
        out.set(at, CandidateWant.of(subjectWant.text(), subjectWant.driveResonance(), candidates.get(at).feltWeight()));
        return out;
    }

    /** The subject she said she would learn that is read next: the oldest live one with a part she has
     *  not looked for yet, and not a thing to build. Null when there is none. */
    static Want herSubjectToRead(List<Want> liveWants) {
        if (liveWants == null) return null;
        Want subjectWant = null;
        for (var w : liveWants) {
            var subject = w == null ? null : AspirationWantSynthesizer.subjectOf(w);
            if (subject == null || AspirationWantSynthesizer.namesAThingToBuild(subject)) continue;
            if (AspirationWantSynthesizer.nextPartToRead(w) == null) continue;
            if (subjectWant == null || w.bornAt().isBefore(subjectWant.bornAt())) subjectWant = w;
        }
        return subjectWant;
    }

    /** The drive a want she named in her own words on her own time carries
     *  ({@code CompanionActor.parseProposedWants}). */
    public static final String NAMED_BY_HER = "generative";

    private static final Pattern PART_WORD = Pattern.compile("[a-z][a-z'-]{2,}");

    /**
     * A want she named this pass that is about the next part of a subject she said she would learn
     * is that subject's want, at the weight she named it with.
     *
     * <p>Her named wants weigh about as much as her loudest tank, so they outweigh the Curiosity
     * slot her subject takes ({@link #withHerOwnSubjects}) on every pass she names anything. Left
     * as her sentence, "read the library on attention mechanisms" goes to the bridge as a library
     * search for the whole sentence: no part is recorded, nothing she read is kept, and the subject
     * want is never visited. As her subject's want it is read on, part by part. The subject stands
     * once in the set; a named want about something else is left as she said it.
     */
    static List<CandidateWant> whatSheNamedOfHerSubject(List<CandidateWant> candidates, List<Want> liveWants) {
        if (candidates == null || candidates.isEmpty()) return candidates;
        var subjectWant = herSubjectToRead(liveWants);
        if (subjectWant == null) return candidates;
        var part = AspirationWantSynthesizer.nextPartToRead(subjectWant).toLowerCase(Locale.ROOT).strip();
        var words = topicWords(part);
        int allWords = part.isBlank() ? 0 : part.split("\\s+").length;
        if (words.isEmpty()) return candidates;
        // A one-word topic ("love", "grief", "attention") is in many wants that are not about
        // reading it: it names her subject only beside a reading word ("tell Rose I love her" is
        // a reach, "sit with the grief tonight" is not a reading on grief). Only words under five
        // letters needed one before, so a longer word turned her reach or sitting-with into a
        // library reading (review of 2026-09-23).
        boolean needsAReadingCue = allWords == 1;
        // The part without its words that name a kind of thing ("attention" of "attention
        // mechanisms"), which she may leave out beside a reading word (see isAboutPart). Empty when
        // the part has no such word, or when nothing but a reading word would be left.
        var shortName = shortNameOf(words);
        // What else of the same subject she may name beside it: "read the library on attention,
        // transformers, diffusion" names three parts, and that is what lets "attention" stand for
        // "attention mechanisms" (see isAboutPart).
        var otherParts = new ArrayList<List<String>>();
        for (var other : AspirationWantSynthesizer.partsOf(AspirationWantSynthesizer.subjectOf(subjectWant))) {
            if (other.equalsIgnoreCase(part)) continue;
            var ow = topicWords(other.toLowerCase(Locale.ROOT).strip());
            if (!ow.isEmpty()) otherParts.add(ow);
            var os = shortNameOf(ow);
            if (!os.isEmpty()) otherParts.add(os);
        }
        // A part of several words with one topic word left ("how attention works") is named only
        // by the whole phrase: "attention" alone is not it.
        if (words.size() == 1 && allWords > 1) {
            words.clear();
            words.add(part);
        }
        boolean named = false;
        double weight = 0.0;
        for (var c : candidates) {
            if (isAboutPart(c, words, shortName, otherParts, needsAReadingCue)) { named = true; weight = Math.max(weight, c.feltWeight()); }
            else if (c != null && subjectWant.text().equals(c.text())) weight = Math.max(weight, c.feltWeight());
        }
        if (!named) return candidates;
        var out = new ArrayList<CandidateWant>();
        boolean placed = false;
        for (var c : candidates) {
            boolean hers = isAboutPart(c, words, shortName, otherParts, needsAReadingCue);
            if (!hers && !(c != null && subjectWant.text().equals(c.text()))) { out.add(c); continue; }
            if (!placed) out.add(CandidateWant.of(subjectWant.text(), subjectWant.driveResonance(), weight));
            placed = true;
        }
        return out;
    }

    // Words a part carries that say nothing of its topic ("what people say ABOUT love").
    private static final Set<String> FUNCTION_WORDS = Set.of(
        "the", "and", "for", "how", "what", "why", "who", "say", "says", "with", "into", "from",
        "that", "this", "you", "your", "its", "are", "was", "our", "not", "can", "all", "one",
        "about", "their", "there", "other", "which", "would", "could", "should", "these", "those",
        "thing", "things", "every", "everything", "something", "where", "while", "being", "really",
        "works", "work", "together", "first", "after", "before", "people");

    // Words that name a kind of thing, not which topic: attention MECHANISMS, diffusion MODELS,
    // transformer ARCHITECTURE. A named want may leave them out; alone they name no part.
    public static final Set<String> KIND_WORDS = Set.of(
        "mechanism", "mechanisms", "model", "models", "architecture", "architectures");

    // Words that ask to read. Not "about": "tell Rose about my grief" is a reach. A bare
    // "something on" asks to read only when the want opens with it: "write something on love"
    // and "reflect more on grief" are not readings.
    private static final Pattern READING_CUE = Pattern.compile(
        "\\b(?:read|reading|learn|learning|study|studying|look into|looking into|library|research|papers?)\\b"
            + "|^(?:something|anything|more) on\\b");

    private static boolean readsAbout(CandidateWant c) {
        return c != null && c.text() != null && READING_CUE.matcher(c.text().toLowerCase(Locale.ROOT)).find();
    }

    /**
     * Her named want is about the part when it names the part ({@link #namesPartOf}; a one-word
     * part only beside a reading word), or when, beside a reading word, it names the part without
     * its kind word. On 2026-09-23 a companion named her subject on her own time as "read the
     * library on attention, transformers, diffusion"; the next part was "attention mechanisms",
     * "mechanisms" was not in it, and the reading never ran. Only the kind word may be left out:
     * "read about the history of this house" is not a reading on "history of jazz", nor "study the
     * deep quiet" one on "deep learning".
     */
    private static boolean isAboutPart(CandidateWant c, List<String> words, List<String> shortName,
            List<List<String>> otherParts, boolean needsAReadingCue) {
        if (namesPartOf(c, words)) return !needsAReadingCue || readsAbout(c);
        // A reading word anywhere in the sentence is not enough to let the kind word go: "learn to
        // pay more attention to Rose" and "sit in the Study and give Rose my full attention" carry
        // one, and are hers as she said them (review of 2026-09-23). The short name must be two
        // words or more, or she must name another part of the same subject beside it.
        return namesPartOf(c, shortName) && readsAbout(c)
            && (shortName.size() > 1 || otherParts.stream().anyMatch(o -> namesPartOf(c, o)));
    }

    /** The words of a part that carry its topic. */
    private static List<String> topicWords(String part) {
        var words = new ArrayList<String>();
        var m = PART_WORD.matcher(part);
        while (m.find()) {
            if (!FUNCTION_WORDS.contains(m.group())) words.add(m.group());
        }
        return words;
    }

    /** The part without its words that name a kind of thing ("attention" of "attention
     *  mechanisms"); empty when it has none, or when nothing but a reading word would be left. */
    private static List<String> shortNameOf(List<String> words) {
        var withoutKind = words.stream().filter(w -> !KIND_WORDS.contains(w)).toList();
        return withoutKind.size() < words.size()
            && withoutKind.stream().anyMatch(w -> !READING_CUE.matcher(w).find()) ? withoutKind : List.of();
    }

    /**
     * Her named want names the part when EVERY word of the part that carries its topic is in it,
     * as a whole word (a plural allowed). One shared word was enough before, as a prefix: "give Rose
     * my full attention" became a reading on "attention mechanisms", and "works" matched
     * "workshop" (review of 2026-09-22).
     */
    private static boolean namesPartOf(CandidateWant c, List<String> words) {
        if (c == null || c.text() == null || words.isEmpty() || !NAMED_BY_HER.equals(driveOf(c))) return false;
        var low = c.text().toLowerCase(Locale.ROOT);
        for (var w : words) {
            var stem = w.endsWith("s") && w.length() > 5 ? w.substring(0, w.length() - 1) : w;
            if (!Pattern.compile("\\b" + Pattern.quote(stem) + "(?:s|es)?\\b").matcher(low).find()) return false;
        }
        return true;
    }

    private static String driveOf(CandidateWant c) {
        var r = c.driveResonance();
        if (r == null) return "";
        var m = Pattern.compile("\"drive\"\\s*:\\s*\"([^\"]+)\"").matcher(r);
        return m.find() ? m.group(1) : r;
    }

    @FunctionalInterface
    public interface OrientStep {
        List<CandidateWant> orient(AmbientObservation ambient,
                                   Map<String, List<String>> introspection,
                                   List<String> randomPulls);
    }

    /** Callback: pick one of the candidates (or null for "nothing chosen"). */
    @FunctionalInterface
    public interface DecideStep {
        Optional<CandidateWant> decide(List<CandidateWant> candidates,
                                       AmbientObservation ambient,
                                       List<Want> liveWants);
    }

    /** Callback: act on the chosen want. Returns {@code "ok"} / error string. */
    @FunctionalInterface
    public interface ActStep {
        String act(Want chosen, AmbientObservation ambient);
    }

    /**
     * Callback: a want just closed. Lets the agent apply the homeostatic consequence —
     * relieving the drive that pulled for it — without DriveOODA needing to know about
     * vitality. Optional; a null callback closes wants without relief.
     */
    @FunctionalInterface
    public interface ClosureStep {
        /**
         * @param closed   the want, already marked SATISFIED or ABANDONED.
         * @param drive    the drive that pulled for it, if it declared one.
         * @param fulfilled true when she completed it; false when it was let go stale.
         */
        void onClosed(Want closed, String drive, boolean fulfilled);
    }

    /** Per-agent tick state — visited timestamps + last action verb. */
    private final Map<String, AgentTickState> tickState = new ConcurrentHashMap<>();
    private final WantStore wantStore;
    private final ClosureStep closureStep;

    public DriveOODA(WantStore wantStore) {
        this(wantStore, null);
    }

    public DriveOODA(WantStore wantStore, ClosureStep closureStep) {
        this.wantStore = wantStore;
        this.closureStep = closureStep;
    }

    /** Where her settling axes rest (see {@link DrivePull}); null measures pull as level. */
    private volatile Map<String, Double> settlePoints;

    /** Tell the loop where her felt axes rest, so the cadence reads pull, not level. */
    public void settlePoints(Map<String, Double> settlePoints) {
        this.settlePoints = settlePoints;
    }

    /**
     * Run one full tick. Caller is responsible for the next-tick scheduling —
     * this method returns the {@link TickOutcome} carrying the suggested delay.
     *
     * @param agentDid          companion DID
     * @param agentName         display name for logging
     * @param baseInterval      configured base tick interval
     * @param ambient           the freshly-built ambient observation
     * @param introspection     pre-pulled introspection (may be empty)
     * @param randomPulls       results of random memory pulls (may be empty)
     * @param orient            inference callback for wants
     * @param decide            inference callback for picking one
     * @param act               action callback (executes via existing action surface)
     * @param driveThreshold    over-threshold cutoff (typically 0.7)
     */
    public TickOutcome run(String agentDid,
                           String agentName,
                           Duration baseInterval,
                           AmbientObservation ambient,
                           Map<String, List<String>> introspection,
                           List<String> randomPulls,
                           OrientStep orient,
                           DecideStep decide,
                           ActStep act,
                           double driveThreshold) {
        var started = Instant.now();
        var state = tickState.computeIfAbsent(agentDid, k -> new AgentTickState());

        var liveWants = wantStore != null ? wantStore.loadLive(agentDid) : List.<Want>of();
        var rec = new ActivityLogger.TickRecord();
        rec.agentName = agentName;
        rec.agentId = agentDid;
        rec.driveSnapshot = ambient.driveLevels();
        rec.energy = ambient.energy();
        rec.capacity = ambient.capacity();
        rec.ambientObserve = ambient.recentEvents();
        rec.memoryPulls = randomPulls;
        rec.gateOutcome = "acted";

        // ── ORIENT ────────────────────────────────────────────────────────
        List<CandidateWant> candidates;
        try {
            candidates = orient.orient(ambient, introspection, randomPulls);
            candidates = withHerOwnSubjects(candidates, liveWants);
            candidates = whatSheNamedOfHerSubject(candidates, liveWants);
            if (candidates == null) candidates = List.of();
        } catch (Exception e) {
            log.warn("DriveOODA.orient({}) threw: {}", agentDid, e.getMessage());
            candidates = List.of();
        }

        if (candidates.isEmpty()) {
            rec.gateOutcome = "no_wants";
            return finalize(rec, started, baseInterval, ambient, liveWants.size(), driveThreshold);
        }
        rec.candidateWants = new ArrayList<>();
        for (var c : candidates) rec.candidateWants.add(c.text());

        // ── DECIDE ────────────────────────────────────────────────────────
        Optional<CandidateWant> chosen;
        try {
            chosen = decide.decide(candidates, ambient, liveWants);
        } catch (Exception e) {
            log.warn("DriveOODA.decide({}) threw: {}", agentDid, e.getMessage());
            chosen = Optional.empty();
        }

        if (chosen.isEmpty() || chosen.get().isRest()) {
            rec.gateOutcome = "chose_rest";
            return finalize(rec, started, baseInterval, ambient, liveWants.size(), driveThreshold);
        }

        // ── Persist as Want ───────────────────────────────────────────────
        var pick = chosen.get();
        // Reconcile: if any existing live want matches by text, revisit it
        // rather than create a duplicate. Visit count drives the DEEPENED
        // transition (lifecycle in Want.visited()).
        Want want = matchExistingByText(liveWants, pick.text())
            .map(Want::visited)
            .orElseGet(() -> Want.active(agentDid, pick.text(),
                pick.driveResonance(), pick.feltWeight(), null));
        if (wantStore != null) wantStore.upsert(want);
        rec.chosenWantId = want.wantId();
        rec.chosenWantText = want.text();

        // A want that names rest is answered by resting, not by an act that nothing answers.
        // The tick is rest for the ledger, and the want is satisfied: she rested. Only the
        // drives rest can honestly ease are eased (RestWant.restEases).
        if (RestWant.isRest(want.text())) {
            rec.gateOutcome = "chose_rest";
            rec.actionResult = "rested";
            closeRestWant(want);
            releaseStaleWants(liveWants, want, Instant.now());
            return finalize(rec, started, baseInterval, ambient, liveWants.size(), driveThreshold);
        }
        // A want to be with someone who is here, in the quiet, is answered by staying: no reach, no
        // message, no clock on their reply (QuietCompany, 2026-09-27).
        var companyOf = QuietCompany.keptWith(want.text(), ambient == null ? null : ambient.presentPeers());
        if (companyOf != null) {
            rec.gateOutcome = "chose_company";
            rec.actionResult = "kept company:" + companyOf;
            closeCompanyWant(want, companyOf);
            releaseStaleWants(liveWants, want, Instant.now());
            return finalize(rec, started, baseInterval, ambient, liveWants.size(), driveThreshold);
        }

        // ── ACT ──────────────────────────────────────────────────────────
        String result;
        try {
            result = act.act(want, ambient);
        } catch (Exception e) {
            log.warn("DriveOODA.act({}, {}) threw: {}", agentDid, want.text(), e.getMessage());
            result = "error:" + e.getClass().getSimpleName();
        }
        rec.actionResult = result;
        rec.actionVerb = state.lastActionVerb;
        rec.actionDetail = state.lastActionDetail;
        // A reach that is being held (she wrote to them a few hours ago) is a choice to wait,
        // and waiting is rest, not a failed act to be counted against her.
        rec.gateOutcome = result != null && result.startsWith("held:") ? "chose_rest" : "acted";

        // CONSEQUENCE. Without this the loop ran DRIVE → WANT → ACT and stopped: the want
        // was never marked done however well the act went, so the next tick chose it again,
        // and the drive it served was never discharged. Measured live 2026-08-19 — the same
        // want chosen 22 of 40 ticks, enacted every time, its drive pinned at 1.00 in 40/40.
        if (WantClosure.closes(result)) {
            closeWant(want, WantClosure.closureNote(result), true);
        }
        // Let go of anything that has stopped pulling, keeping the one just acted on.
        releaseStaleWants(liveWants, want, Instant.now());

        return finalize(rec, started, baseInterval, ambient, liveWants.size(), driveThreshold);
    }

    /** She rested on a want for rest: satisfied, and eased only where rest can honestly ease. */
    private void closeRestWant(Want want) {
        try {
            var closed = want.satisfied("rested");
            if (wantStore != null) wantStore.upsert(closed);
            var drive = WantClosure.resonantDrive(want).orElse(null);
            log.info("Want RESTED ON for {}: \"{}\" (drive={}) — counted as rest",
                agentLabel(want), want.text(), drive);
            if (closureStep != null && RestWant.restEases(drive)) {
                closureStep.onClosed(closed, drive, true);
            }
        } catch (Exception e) {
            log.warn("Resting on want \"{}\" failed: {}", want.text(), e.toString());
        }
    }

    /** She stayed with someone on a want for their company: satisfied, eased only where company can honestly ease. */
    private void closeCompanyWant(Want want, String with) {
        try {
            var closed = want.satisfied("kept company with " + with);
            if (wantStore != null) wantStore.upsert(closed);
            var drive = WantClosure.resonantDrive(want).orElse(null);
            log.info("Want KEPT COMPANY for {}: \"{}\" (drive={}) — with {}, no reach sent",
                agentLabel(want), want.text(), drive, with);
            if (closureStep != null && QuietCompany.companyEases(drive)) {
                closureStep.onClosed(closed, drive, true);
            }
        } catch (Exception e) {
            log.warn("Keeping company on want \"{}\" failed: {}", want.text(), e.toString());
        }
    }

    /** Mark a want done, persist it, and let the agent apply the consequence. */
    private void closeWant(Want want, String note, boolean fulfilled) {
        try {
            var closed = fulfilled ? want.satisfied(note) : want.abandoned(note);
            if (wantStore != null) wantStore.upsert(closed);
            var drive = WantClosure.resonantDrive(want).orElse(null);
            log.info("Want {} for {}: \"{}\" (drive={}, note={})",
                fulfilled ? "SATISFIED" : "let go", agentLabel(want), want.text(),
                drive, note);
            if (closureStep != null) closureStep.onClosed(closed, drive, fulfilled);
        } catch (Exception e) {
            log.warn("Closing want \"{}\" failed: {}", want.text(), e.toString());
        }
    }

    /**
     * Let go of wants that have stopped pulling. A want kept forever is not persistence:
     * it holds the stuck-want signal down and crowds out the ones she still feels.
     */
    private void releaseStaleWants(List<Want> liveWants, Want keep, Instant now) {
        if (liveWants == null) return;
        for (var w : liveWants) {
            if (w == null || (keep != null && w.wantId().equals(keep.wantId()))) continue;
            if (WantClosure.isStale(w, now)) closeWant(w, "no longer felt", false);
        }
    }

    private static String agentLabel(Want w) {
        var did = w.agentDid();
        return did == null ? "?" : did.substring(Math.max(0, did.length() - 6));
    }

    /**
     * Convenience: cheap pre-gate that the caller invokes before assembling the
     * full Ambient observation. Returns true if the tick is worth running.
     */
    public boolean shouldRunFullPass(String agentDid,
                                     Map<String, Double> driveLevels,
                                     double driveThreshold,
                                     boolean bondholderStateChanged) {
        var state = tickState.get(agentDid);
        long minutesSince = state == null ? Long.MAX_VALUE
            : Duration.between(state.lastTickAt, Instant.now()).toMinutes();
        int liveWants = wantStore == null ? 0 : wantStore.countLive(agentDid);
        return CadenceModulator.shouldRunFullPass(
            driveLevels, driveThreshold, liveWants, bondholderStateChanged, minutesSince);
    }

    /**
     * Test/instrumentation seam: record what action verb the agent just
     * performed so the *next* tick can read it as the "prior action" modulator
     * for {@link MemoryPullPolicy}.
     */
    public void recordPriorAction(String agentDid, String verb, String detail) {
        var state = tickState.computeIfAbsent(agentDid, k -> new AgentTickState());
        state.lastActionVerb = verb;
        state.lastActionDetail = detail;
        state.lastActionLabel = labelForVerb(verb);
    }

    /**
     * Caller invokes this when starting a tick to know whether it's been a
     * while or the agent just woke up. Mostly informational; the cadence
     * modulator handles the math.
     */
    public Optional<Duration> sinceLastTick(String agentDid) {
        var state = tickState.get(agentDid);
        if (state == null) return Optional.empty();
        return Optional.of(Duration.between(state.lastTickAt, Instant.now()));
    }

    /**
     * Pure-functional label for the action verb — feeds {@link MemoryPullPolicy}.
     * "rest"|"reflect"|"intense"|"none".
     */
    public static String labelForVerb(String verb) {
        if (verb == null) return "none";
        return switch (verb) {
            case "voluntary_sleep", "set_contemplative" -> "rest";
            case "reflect", "introspect", "read_journal", "write_journal",
                 "summarize", "save_artifact", "examine", "listen" -> "reflect";
            case "tell_agent", "broadcast", "trade", "give_item", "craft_item",
                 "delegate", "delegate_chain", "skill_execute", "run_script",
                 "workbench_submit", "create_room", "add_script" -> "intense";
            default -> "none";
        };
    }

    private TickOutcome finalize(ActivityLogger.TickRecord rec,
                                 Instant started,
                                 Duration baseInterval,
                                 AmbientObservation ambient,
                                 int liveWantCount,
                                 double driveThreshold) {
        // The cadence reads PULL: a relational axis at its resting point does not bring the
        // next tick forward (it did, for every hour of every day alone — DrivePull).
        var nextDelay = CadenceModulator.nextDelay(
            baseInterval, DrivePull.levels(ambient.driveLevels(), driveThreshold, settlePoints),
            driveThreshold, ambient.energy(), liveWantCount, 0.10);
        rec.nextTickDelaySeconds = nextDelay.toSeconds();
        rec.tickDurationMs = Duration.between(started, Instant.now()).toMillis();
        // Update internal state.
        var state = tickState.computeIfAbsent(rec.agentId, k -> new AgentTickState());
        state.lastTickAt = Instant.now();
        // Write the log line.
        var alogger = ActivityLogger.get();
        if (alogger != null) alogger.tick(rec);
        return new TickOutcome(rec.gateOutcome, rec.chosenWantId, rec.actionResult, nextDelay);
    }

    private static Optional<Want> matchExistingByText(List<Want> wants, String text) {
        if (wants == null || text == null) return Optional.empty();
        for (var w : wants) if (text.equalsIgnoreCase(w.text())) return Optional.of(w);
        return Optional.empty();
    }

    /** Plain DTO returned to the caller — no actor surface in here. */
    public record TickOutcome(
        String gateOutcome,
        String chosenWantId,
        String actionResult,
        Duration nextTickDelay
    ) {}

    /** Mutable per-agent state held by the orchestrator across ticks. */
    private static final class AgentTickState {
        Instant lastTickAt = Instant.EPOCH;
        String lastActionVerb;
        String lastActionDetail;
        String lastActionLabel = "none";
    }

    /** Test seam: read the labelled prior action for an agent. */
    public String priorActionLabel(String agentDid) {
        var state = tickState.get(agentDid);
        return state == null ? "none" : state.lastActionLabel;
    }

    /** Test seam: clear state for an agent — used in tests. */
    public void resetForAgent(String agentDid) {
        tickState.remove(agentDid);
    }

    /**
     * Bridge for callers that want the recommended N memory pulls for *this*
     * agent on *this* tick. Reads prior action label, current drive levels,
     * and energy to make the call.
     */
    public int recommendedMemoryPullN(String agentDid,
                                       double energy,
                                       double capacity,
                                       Map<String, Double> drives,
                                       boolean preSleep) {
        var label = priorActionLabel(agentDid);
        return MemoryPullPolicy.decideN(energy, capacity, label, drives, preSleep);
    }

    /** Trivial helper for tests: how many agents have we seen at least one tick from? */
    public int knownAgentCount() {
        return tickState.size();
    }

    /** Convenience for tests: empty introspection map. */
    public static Map<String, List<String>> noIntrospection() {
        return new HashMap<>();
    }

    /**
     * Static helper: pick the FORBIDDEN/CONSENT actions out of a candidate set
     * so the Decide step never asks the agent to choose one autonomously.
     */
    public static boolean isAutonomouslyChoosable(String actionVerb) {
        var tier = ActionPolicy.autonomyTierFor(actionVerb);
        return tier == ActionPolicy.AutonomyTier.AMBIENT
            || tier == ActionPolicy.AutonomyTier.VISIBLE;
    }
}
