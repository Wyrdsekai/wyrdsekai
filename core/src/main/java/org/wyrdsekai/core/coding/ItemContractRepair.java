package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.nio.file.Files;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Give a coding backend another turn to fix an item file the bridge would refuse.
 *
 * <p>Backend-agnostic on purpose. Goose is today's default and CodeZaiku is next, with
 * OpenHands, OpenCode, Codex, Continue, Cline, Gemini and the Claude SDK all behind the
 * same {@code ITEMS_AS_TOOLS_PREAMBLE} and the same {@link CodingTaskItemBridge}. They fail
 * this contract for the same reasons and the repair is the same for all of them, so it
 * lives here and each backend supplies only the one thing it alone knows: how to re-run
 * itself in the workspace it just used.
 *
 * <p><b>Why a repair at all.</b> Measured live against the household 9B driving goose
 * (2026-08-20): two runs, two different generated files, both refused for a manifest with
 * no {@code commands} block. The preamble already demands it in the strongest terms it has
 * — "Forgetting the field is a hard reject — your file will not register" — and the model
 * omits it anyway. Prose in a contract does not produce compliance; a named defect and
 * another turn might.
 *
 * <p><b>Why every problem at once.</b> Told only that {@code commands} was missing, goose
 * added it and <b>deleted the embodiment block in the same edit</b>. Naming one defect at
 * a time to a model trades one rejection for another.
 *
 * <p><b>Why bounded at two.</b> Round one is the fix. Round two is the safety net for
 * collateral damage, because that is a thing that happened. A model that cannot fix a
 * named, quoted defect in two tries will not fix it in five, and every round is wallclock
 * a person waits through.
 */
public final class ItemContractRepair {

    private static final Logger log = LoggerFactory.getLogger(ItemContractRepair.class);

    /** Rounds allowed per file. See the class note — this is measured, not arbitrary. */
    public static final int MAX_ROUNDS = 2;

    /** Where the shared items-as-tools preamble teaches every backend to write. */
    private static final Path PREAMBLE_WORKSPACE = Path.of("/workspace");

    private ItemContractRepair() {}

    /** Re-run the backend in its workspace with this instruction. True if it completed. */
    @FunctionalInterface
    public interface Reprompt {
        boolean rerun(String prompt);
    }

    /**
     * Check every item file the run produced and repair what the bridge would refuse.
     *
     * <p>Never throws and never fails a task: a repair that goes wrong must not turn
     * completed work into a failure. Whatever survives goes on to the bridge, which keeps
     * the final say exactly as before.
     *
     * @param workdir       the directory handed to the backend, if any
     * @param taskId        for logging
     * @param runStartedAt  when this run began — ONLY files written at or after this are
     *                      considered. {@code /workspace} is a shared directory holding
     *                      output from every past task on the host; without this bound a
     *                      repair would re-prompt the backend about other people's files.
     * @param reprompt      how this backend re-runs itself
     */
    public static void repair(Path workdir, String taskId, Instant runStartedAt,
            Reprompt reprompt) {
        if (reprompt == null) return;
        try {
            for (var script : candidateScripts(workdir, runStartedAt)) {
                repairOne(script, taskId, reprompt);
            }
        } catch (Exception e) {
            log.warn("[item-contract] repair errored for task {} ({}) — shipping the "
                + "artifacts unchanged", taskId, e.toString());
        }
    }

    /**
     * Repair the files the backend ITSELF says it wrote.
     *
     * <p>Prefer this over the directory scan, always. Live on the household node
     * 2026-08-20, the first time the whole chain ran in production: goose wrote
     * {@code /opt/wyrdsekai/library_query.js} — its own working directory, the install
     * root — which is neither the workspace it was handed ({@code '(default)'}) nor the
     * {@code /workspace} the preamble teaches. The scan looked in both and found nothing,
     * so the repair never ran and the bridge refused the file for a missing
     * {@code embodiment} block. The person got a codex he could pick up, examine, and not
     * use.
     *
     * <p>The backend reports its own paths in the artifact it produces. That is the truth;
     * a directory guess is a guess.
     *
     * @param files absolute or workspace-relative paths the run declared
     * @param workspace what to resolve relative paths against; may be null
     */
    public static void repairFiles(List<String> files, Path workspace, String taskId,
            Reprompt reprompt) {
        if (reprompt == null || files == null || files.isEmpty()) return;
        try {
            for (var f : files) {
                if (f == null || !f.toLowerCase().endsWith(".js")) continue;
                var p = Path.of(f);
                if (!p.isAbsolute() && workspace != null) p = workspace.resolve(f);
                if (!Files.isRegularFile(p)) {
                    log.debug("[item-contract] declared file {} is not readable here "
                        + "— skipping", p);
                    continue;
                }
                repairOne(p, taskId, reprompt);
            }
        } catch (Exception e) {
            log.warn("[item-contract] repair errored for task {} ({}) — shipping the "
                + "artifacts unchanged", taskId, e.toString());
        }
    }

    /**
     * How a backend runs itself again. The one thing the repair cannot know.
     *
     * @return true when the re-run completed (exit 0, no timeout)
     */
    @FunctionalInterface
    public interface RunAgain {
        boolean run(List<String> args);
    }

    /**
     * The re-run every CLI backend shares: same invocation, prompt swapped for the
     * complaint.
     *
     * <h2>Why this is a lambda and not a runner type</h2>
     * The repair is backend-agnostic; what a backend alone knows is how to run itself.
     * That one piece was written INSIDE {@code GooseBackend}, which is how self-repair
     * came to exist for the default backend and no other. The obvious extraction — take
     * the runner — was still goose-shaped: {@code GooseBackend.ProcessRunner} runs
     * {@code (args, env, workdir, timeout)} while Cline, Continue, Codex, Gemini and
     * OpenCode each run {@code (args, env, timeout)} with no workdir. Typing the helper
     * to one of them would have generalised from goose to exactly two backends and
     * called it done.
     *
     * <p>So the shared half is only this: <i>the prompt is the last argument; replace it
     * and go again</i>. Every CLI backend supplies four lines for the rest.
     *
     * @param again how this backend re-runs a modified invocation
     * @param args  the invocation it just made; the LAST element must be the prompt
     */
    public static Reprompt rerunWithPrompt(RunAgain again, List<String> args) {
        if (again == null || args == null || args.isEmpty()) return null;
        return prompt -> {
            try {
                var repairArgs = new ArrayList<>(args.subList(0, args.size() - 1));
                repairArgs.add(prompt);
                return again.run(repairArgs);
            } catch (Exception e) {
                log.warn("[item-contract] repair rerun failed: {}", e.toString());
                return false;
            }
        };
    }

    /**
     * Repair whatever this run produced, from the artifacts it declared plus its workdir.
     *
     * <p>The two calls every backend needs, in the order that matters: the DECLARED paths
     * first (the run's own account of where it wrote, which is the truth), then the
     * directory scan as a backstop. Bundled here so adding a backend is one line rather
     * than a copied block that can drift.
     */
    public static void repairRun(List<CodingArtifact> artifacts, Path workdir,
            String taskId, Instant runStartedAt, Reprompt reprompt) {
        repairRun(artifacts, workdir, taskId, runStartedAt, reprompt, null);
    }

    /**
     * @param request the person's own words. Carried so the repair can also ask whether
     *                the item does what was ASKED, not merely whether it will register —
     *                see {@link ItemIntentCheck}. Null skips that half.
     */
    public static void repairRun(List<CodingArtifact> artifacts, Path workdir,
            String taskId, Instant runStartedAt, Reprompt reprompt, String request) {
        if (reprompt == null) return;
        REQUEST.set(request);
        if (artifacts != null) {
            for (var a : artifacts) {
                if (!(a instanceof SourceArtifact src)) continue;
                repairFiles(src.files(),
                    src.workspacePath() == null || src.workspacePath().isBlank()
                        ? null : Path.of(src.workspacePath()),
                    taskId, reprompt);
            }
        }
        try {
            repair(workdir, taskId, runStartedAt, reprompt);
        } finally {
            REQUEST.remove();
            REPAIRED.remove();
        }
    }

    /**
     * The request under repair, for the intent half.
     *
     * <p>A thread-local rather than a parameter threaded through five methods: the repair
     * walks files discovered two levels down, and widening every signature to carry a
     * string that only one check reads is the kind of change that gets reverted. Set and
     * cleared around a single synchronous run on one virtual thread.
     */
    private static final ThreadLocal<String> REQUEST = new ThreadLocal<>();

    /**
     * Files already repaired in this run.
     *
     * <h2>Why</h2>
     * {@link #repairRun} repairs the paths the backend reports it wrote, and THEN scans
     * the working directory — which finds the same files again. Observed on staging
     * 2026-08-22: {@code web-sight.js} went through two identical rounds, "repair
     * SUCCEEDED after 1 round(s)" printed twice, and a model call was spent on each.
     * Worse than wasteful: the second pass re-opens a file the first pass just settled,
     * and the guard that reverts a worsening rewrite only compares within one round.
     *
     * <p>Keyed on the real path so the same file reached by two routes is one entry.
     */
    private static final ThreadLocal<Set<Path>> REPAIRED = ThreadLocal.withInitial(HashSet::new);

    /**
     * One extra round through a DIFFERENT backend after a backend's own rounds
     * exhaust. Measured 2026-08-27 (home-server, the household 4B): goose shipped an
     * invoke()-crash to a person after two rounds; the codezaiku harness fixed
     * the same file on the same model in one. The escalation runs under the
     * same revert-if-worse guard as every other round, so its worst case is
     * the status quo. Wired by {@code CodingBackendBootstrap} when codezaiku
     * registers; absent, exhaust behaves exactly as before.
     */
    @FunctionalInterface
    public interface Escalation {
        /** @return true when the escalation run completed (the file re-read decides the rest) */
        boolean rerun(Path workspace, String prompt);
    }

    private static volatile Escalation escalation;

    public static void setEscalation(Escalation e) {
        escalation = e;
    }

    /** True while the escalation backend itself is the one being repaired — it must not escalate to itself. */
    private static final ThreadLocal<Boolean> ESCALATION_IS_SELF = ThreadLocal.withInitial(() -> false);

    /** Run {@code r} with escalation disabled — for the escalation backend's OWN repair pass. */
    public static void withoutEscalation(Runnable r) {
        ESCALATION_IS_SELF.set(true);
        try {
            r.run();
        } finally {
            ESCALATION_IS_SELF.set(false);
        }
    }

    /**
     * Problems that survived every round, by task. The completion narration
     * consumes this so a companion can say "built, but it does not work yet"
     * instead of announcing success for a tool that will not register —
     * the talks-but-doesn't-do bug, in her own mouth.
     */
    private static final Map<String, List<String>> UNRESOLVED = new ConcurrentHashMap<>();

    /** The problems left standing for this task, removing the record. Empty when everything registered. */
    public static List<String> consumeUnresolved(String taskId) {
        if (taskId == null) return List.of();
        var left = UNRESOLVED.remove(taskId);
        return left == null ? List.of() : left;
    }

    private static void repairOne(Path script, String taskId, Reprompt reprompt)
            throws Exception {
        var name = script.getFileName().toString();
        Path identity;
        try {
            identity = script.toRealPath();
        } catch (Exception e) {
            identity = script.toAbsolutePath().normalize();
        }
        if (!REPAIRED.get().add(identity)) {
            log.debug("[item-contract] {} was already repaired in this run — not again", name);
            return;
        }
        var superseded = supersededBy(script);
        if (superseded != null) {
            // The backend rewrote this item under a corrected name instead of editing it.
            // The good version is right there; spending model rounds on the abandoned one
            // buys nothing. Staged 2026-08-22: invitation-scroll.js exhausted both rounds
            // on its name while invitation_scroll.js sat beside it, clean.
            log.info("[item-contract] {} was superseded by {} — repairing that instead of "
                + "the version the backend abandoned", name, superseded.getFileName());
            return;
        }
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            var source = Files.readString(script);
            var contract = ItemContractCheck.problems(source, name);
            var problems = new ArrayList<>(contract);
            // Advisory gaps NEVER share a round with a fatal defect. They MAY spend
            // the last round: the original rule ("never spend the last one",
            // 2026-08-21) existed because an advisory rewrite could introduce a fatal
            // defect with no budget left to fix it — and the revert-if-worse guard
            // below now closes that exact hole, so the risk the rule managed is gone.
            // What the rule actually cost showed up on 2026-08-24: any item whose
            // round 1 fixed a CONTRACT problem exited with intent gaps never
            // evaluated once — which is how a "weather tool" that declares
            // [web.search, web.fetch], calls nothing, and RETURNS A HARDCODED
            // temperature-72 forecast shipped to a person's hands twice in one
            // evening. Once the contract is clean, there is nothing fatal left to
            // reserve the budget for.
            if (contract.isEmpty()) {
                problems.addAll(ItemIntentCheck.gaps(
                    REQUEST.get(), source, ItemCapabilitySet.craftedDefault()));
            }
            if (problems.isEmpty()) {
                if (round > 1) {
                    log.info("[item-contract] repair SUCCEEDED for {} after {} round(s) "
                        + "— it will register", name, round - 1);
                }
                return;
            }
            log.info("[item-contract] repair round {}/{} for task {}: {} — {}",
                round, MAX_ROUNDS, taskId, name, problems);
            if (!reprompt.rerun(buildPrompt(name, problems))) {
                log.warn("[item-contract] backend did not complete the repair for {} "
                    + "— shipping what we have", name);
                return;
            }
            // A repair is only a repair if the result is not WORSE. Twice on 2026-08-21 a
            // backend acting on one complaint broke something that had been fine — once
            // deleting the embodiment block while adding commands, once wrapping the file
            // in an IIFE while switching to a keyed service. The bounded rounds do not
            // help if each one can introduce a fresh fatal defect, so: if the rewrite has
            // contract problems the previous version did not, put the previous version
            // back. Losing an improvement is cheap; shipping a regression is not.
            var after = ItemContractCheck.problems(Files.readString(script), name);
            if (after.size() > contract.size()) {
                Files.writeString(script, source);
                log.warn("[item-contract] the repair of {} introduced {} — reverting to "
                    + "the version before this round", name, after);
                return;
            }
        }
        var left = ItemContractCheck.problems(Files.readString(script), name);
        // Intent gaps are advisory: an item that scrapes the web on purpose is
        // legitimate. Exhausting the rounds on one is not a reason to complain again.
        if (!left.isEmpty()) {
            left = tryEscalation(script, name, left);
        }
        if (!left.isEmpty()) {
            log.warn("[item-contract] repair exhausted for {} after {} rounds: still {} "
                + "— shipping it anyway; the bridge decides", name, MAX_ROUNDS, left);
            // Bounded: a dispatch route that never narrates (a Workshop zone
            // command) never consumes its entry. These are advisory breadcrumbs,
            // not records — losing old ones costs one softened sentence.
            if (UNRESOLVED.size() > 256) UNRESOLVED.clear();
            UNRESOLVED.merge(taskId, List.copyOf(left), (a, b) -> {
                var merged = new ArrayList<>(a);
                merged.addAll(b);
                return List.copyOf(merged);
            });
        }
    }

    /**
     * One round through the escalation backend, under the same revert-if-worse
     * guard as the in-backend rounds. Returns the problems still standing.
     */
    private static List<String> tryEscalation(Path script, String name, List<String> left) {
        var esc = escalation;
        if (esc == null || ESCALATION_IS_SELF.get()) return left;
        try {
            var source = Files.readString(script);
            log.info("[item-contract] escalating {} to the escalation backend: {}", name, left);
            if (!esc.rerun(script.getParent(), buildPrompt(name, left))) {
                log.info("[item-contract] escalation run for {} did not complete — the file "
                    + "on disk decides", name);
            }
            var after = ItemContractCheck.problems(Files.readString(script), name);
            if (after.size() > left.size()) {
                Files.writeString(script, source);
                log.warn("[item-contract] the escalation of {} introduced {} — reverting to "
                    + "the version before it", name, after);
                return left;
            }
            if (after.isEmpty()) {
                log.info("[item-contract] escalation SUCCEEDED for {} — it will register", name);
            }
            return after;
        } catch (Exception e) {
            log.warn("[item-contract] escalation errored for {} ({}) — keeping the "
                + "pre-escalation file", name, e.toString());
            return left;
        }
    }

    /**
     * The .js files a run may have produced: the workspace the backend was given, plus
     * {@code /workspace}, which is where the shared preamble actually teaches every
     * backend to write — a repair that scans only the passed workdir finds nothing.
     *
     * <p>Bounded to files this run touched. {@code /workspace} is shared and long-lived;
     * everything older than the run belongs to someone else and must be left alone.
     */
    static List<Path> candidateScripts(Path workdir, Instant runStartedAt)
            throws Exception {
        var roots = new LinkedHashSet<Path>();
        if (workdir != null && Files.isDirectory(workdir)) roots.add(workdir);
        if (Files.isDirectory(PREAMBLE_WORKSPACE)) roots.add(PREAMBLE_WORKSPACE);

        var out = new ArrayList<Path>();
        for (var root : roots) {
            try (var walk = Files.walk(root, 3)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".js"))
                    .filter(f -> writtenByThisRun(f, runStartedAt))
                    .forEach(out::add);
            }
        }
        return List.copyOf(out);
    }

    /** Did this run write the file? Unknown timestamps are treated as NOT ours. */
    private static boolean writtenByThisRun(Path file, Instant runStartedAt) {
        if (runStartedAt == null) return true;   // caller declined to bound it
        try {
            return !Files.getLastModifiedTime(file).toInstant().isBefore(runStartedAt);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Every outstanding defect, plus an explicit instruction not to touch anything else —
     * the collateral-damage failure this exists to survive.
     */
    /**
     * Has a clean sibling replaced this file?
     *
     * <p>Only counts a sibling whose manifest name is this one's name normalised —
     * {@code invitation-scroll} → {@code invitation_scroll}. That is the same item under a
     * corrected name, not a different item that happens to share a directory, so a task
     * that legitimately writes two items still gets both repaired.
     */
    private static Path supersededBy(Path script) {
        try {
            var mine = manifestName(Files.readString(script));
            if (mine == null) return null;
            var normalised = normalise(mine);
            if (normalised.equals(mine)) return null;   // this file's own name is fine
            var dir = script.getParent();
            if (dir == null) return null;
            try (var files = Files.list(dir)) {
                for (var sibling : files.toList()) {
                    if (sibling.equals(script) || !sibling.toString().endsWith(".js")) continue;
                    var src = Files.readString(sibling);
                    var name = manifestName(src);
                    if (name == null || !name.equals(normalised)) continue;
                    if (ItemContractCheck.problems(src, sibling.getFileName().toString())
                            .isEmpty()) {
                        return sibling;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[item-contract] supersede check failed for {}: {}", script, e.toString());
        }
        return null;
    }

    private static String manifestName(String source) {
        try {
            var m = ItemManifestParser.parse(source);
            return m == null || m.name() == null || m.name().isBlank() ? null : m.name();
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalise(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    static String buildPrompt(String fileName, List<String> problems) {
        var sb = new StringBuilder();
        // "Edit it in place" is load-bearing. Live on staging 2026-08-22 the backend
        // answered a name complaint by writing a SECOND file with the corrected name and
        // leaving the first untouched. Both rounds were then spent re-reading the file
        // nobody would ever load, and the log ended "repair exhausted for
        // invitation-scroll.js after 2 rounds" while the working item sat beside it.
        sb.append("EDIT ").append(fileName).append(" IN PLACE. Do not create a new file, ")
          .append("do not rename it, do not write a corrected copy alongside it — change ")
          .append("this file.\n")
          // A repair is the moment a test-seeking backend most wants to build a project
          // around the file. CodeZaiku spent 40 turns on a Python test suite for a
          // three-line briefing tool (2026-08-23). Say what done means, here too.
          .append("This is ONE file and nothing else: no project, no src/ tree, no tests, no ")
          .append("second language. It is done the moment it parses and invoke() runs. ")
          .append("Fix the listed problems and stop.\n\n")
          .append("The file ").append(fileName).append(" you just wrote will be REJECTED ")
          .append("and will not register as an item. ")
          .append(problems.size() == 1 ? "The exact problem:" : "The exact problems:")
          .append("\n\n");
        for (var p : problems) sb.append("  - ").append(p).append("\n");
        sb.append("\nEdit that file IN PLACE to fix all of the above. Keep the same file ")
          .append("name. Do NOT remove, rename or alter any other field of the manifest — ")
          .append("in particular keep `embodiment`, `commands`, `capabilities` and the ")
          .append("invoke() function all present and intact, whether or not they are ")
          .append("listed above. Do not create a new file, and do not explain — just make ")
          .append("the edit.");
        return sb.toString();
    }
}
