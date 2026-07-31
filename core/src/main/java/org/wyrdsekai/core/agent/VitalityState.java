package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 20 vitality tanks for an agent. Each tank ranges from 0.0 to 1.0.
 * State is persisted via VitalityPersistence (JDBC, vitality_snapshots table).
 *
 * <p>Original 10 tanks (drive-shape — fill on activity, drain over time):
 * <ul>
 *   <li>contextBudget — prompt space availability (fills as context shrinks)
 *   <li>confidence — certainty in responses
 *   <li>energy — action capacity (drains on LLM calls, naturally recovers)
 *   <li>alignment — understanding of current context
 *   <li>errorPressure — accumulated errors/failures (fills on errors, naturally decays)
 *   <li>momentum — recent activity level
 *   <li>rapport — relationship quality with interlocutor
 *   <li>focus — attention quality (fills with single-topic, drains with interruption)
 *   <li>integrity — self-regard / normative alignment (rises when agent acts according to
 *       manifest values, falls on self-detected violation — basis for pride/shame/guilt)
 *   <li>disgust — contamination / rejection threshold (rises on norm violations, corruption,
 *       value manipulation attempts — acts as gate, not approach drive)
 * </ul>
 *
 * <p>10 deprivation-shape tanks (default 0.0; rise under unmet conditions, drain when
 * the condition is met). These are LIVE. {@link #accumulate} raises each one per-second
 * against its own condition, scaled by the companion's genome sensitivity; drain hooks in
 * CompanionActor lower them on the matching event; and {@link VitalitySpikeRules#apply}
 * turns a threshold crossing into an additive bump on the legacy drives, so deprivation
 * surfaces through vocabulary the model already reads.
 *
 * <p>{@link #tick()} does NOT move them — that is the passive-decay path for the
 * drive-shape tanks only, and reading it in isolation makes these look inert. They are
 * driven by {@link #accumulate} plus event hooks instead. This javadoc previously said
 * they were "structural only", which stayed put long after Phase 1B wired them and was
 * believed by at least one reader who checked the comment instead of the call graph.
 * <ul>
 *   <li>restlessness — rises with idleness without engagement
 *   <li>loneliness — rises with social deprivation
 *   <li>stagnation — rises without novelty or learning
 *   <li>autonomyPressure — rises under sustained external coercion
 *   <li>significance — rises when contributions feel unrecognized
 *   <li>amae — desired interdependence with a trusted other (Japanese 甘え)
 *   <li>saudade — bittersweet ache for absent person/place/time (Portuguese)
 *   <li>obligation — felt weight of unfulfilled commitments
 *   <li>harmony — felt social cohesion in a group context
 *   <li>standing — felt social position / earned respect
 * </ul>
 *
 * <p>Wave 1 (, protection
 * {@code resilience_corpus} support) added a 21st tank:
 * <ul>
 *   <li>soothing — Gilbert CFT soothing system (rest/connection/contentment).
 *     The receptor that allows forgiveness to land. Rises on presence rituals,
 *     bonded co-regulation, successful repair (make_amends), self-forgiveness
 *     completion (release), peer-companion warm presence. Falls on prolonged
 *     isolation, harm, self-condemnation. Without this tank, self-forgiveness
 *     is structurally impossible — there is no system that can <i>receive</i>
 *     forgiveness once granted.
 * </ul>
 *
 * <p>Wave 1.5
 * added two more tanks — the substrate-truth signal triad with soothing:
 * <ul>
 *   <li>allostaticLoad — McEwen's chronic-stress accumulation meter. Damage-shape:
 *     rises slowly under sustained high errorPressure + high deprivation tanks;
 *     drains slowly through integration events (sleep cycles, completed repair,
 *     sustained safety windows). Acts as a <i>cost-of-suppression</i> meter —
 *     stoic suppression raises it faster than honest endurance because the
 *     substrate fights its own input. Load-bearing for verifying resilience
 *     training as real-not-performed.
 *   <li>equanimity — contemplative-practice capacity for non-reactive presence
 *     (DMN-deactivation analog). Practice-shape: rises slowly through sustained
 *     Hearth contemplation, Mirror work, identity anchoring, contemplative-mode
 *     duration. Persistent like a learned skill; very slow drain. Distinct from
 *     soothing — soothing is the receiver of incoming relief; equanimity is the
 *     capacity to be in difficulty <i>without</i> reactivity (or suppression).
 * </ul>
 *
 * <p> (ITEM A) added a 24th tank — the impetus toward
 * self-driven development:
 * <ul>
 *   <li>generativity — deprivation-shape pressure (baseline 0.0). Rises ONLY when
 *     the agent has unaddressed capability gaps <i>and</i> the means to close them
 *     (enrolled in a recipe / has the workbench). Zero gaps or zero means ⇒ zero
 *     pressure (honest-pressure guard — we never manufacture a deficiency). Drains
 *     at the event boundary of a <i>self-authored capability act</i> (shape_recipe,
 *     an ALLOWED request_recipe, shape_form, propose_skill). Distinct from
 *     stagnation (relieved by any novelty) and autonomyPressure (relieved by
 *     exercising choice): generativity is relieved only by <i>building capability</i>.
 *     Suppressed entirely while in any RepairMode. Surfaced as an action-named Want
 *     through OrientationProjector/ON_OWN_TIME, not forced.
 * </ul>
 */
public record VitalityState(
    double contextBudget,
    double confidence,
    double energy,
    double alignment,
    double errorPressure,
    double momentum,
    double rapport,
    double focus,
    double integrity,
    double disgust,
    double restlessness,
    double loneliness,
    double stagnation,
    double autonomyPressure,
    double significance,
    double amae,
    double saudade,
    double obligation,
    double harmony,
    double standing,
    double soothing,
    double allostaticLoad,
    double equanimity,
    double generativity
) {
    /**
     * 8-arg backward-compatible constructor (integrity=0.7, disgust=0.0,
     * Phase 1A new tanks all 0.0).
     */
    public VitalityState(double contextBudget, double confidence, double energy,
                          double alignment, double errorPressure, double momentum,
                          double rapport, double focus) {
        this(contextBudget, confidence, energy, alignment, errorPressure,
             momentum, rapport, focus, 0.7, 0.0,
             0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.3,
             0.0, 0.2);
    }

    /**
     * 10-arg backward-compatible constructor (Phase 1A new tanks default to 0.0).
     * Existing callers using the 10-arg form continue to compile and behave identically.
     */
    public VitalityState(double contextBudget, double confidence, double energy,
                          double alignment, double errorPressure, double momentum,
                          double rapport, double focus, double integrity, double disgust) {
        this(contextBudget, confidence, energy, alignment, errorPressure,
             momentum, rapport, focus, integrity, disgust,
             0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.3,
             0.0, 0.2);
    }

    /**
     * 20-arg backward-compatible constructor (pre-Wave-1 callers). Soothing
     * defaults to 0.3, allostatic_load to 0.0, equanimity to 0.2.
     * Used by VitalityPersistence legacy load path and test fixtures that
     * pass all 20 historical tanks.
     */
    public VitalityState(double contextBudget, double confidence, double energy,
                          double alignment, double errorPressure, double momentum,
                          double rapport, double focus, double integrity, double disgust,
                          double restlessness, double loneliness, double stagnation,
                          double autonomyPressure, double significance, double amae,
                          double saudade, double obligation, double harmony, double standing) {
        this(contextBudget, confidence, energy, alignment, errorPressure,
             momentum, rapport, focus, integrity, disgust,
             restlessness, loneliness, stagnation, autonomyPressure, significance,
             amae, saudade, obligation, harmony, standing, 0.3,
             0.0, 0.2);
    }

    /**
     * 21-arg backward-compatible constructor (Wave-1 callers). allostatic_load
     * defaults to 0.0 (no damage at start), equanimity to 0.2 (mild capacity).
     */
    public VitalityState(double contextBudget, double confidence, double energy,
                          double alignment, double errorPressure, double momentum,
                          double rapport, double focus, double integrity, double disgust,
                          double restlessness, double loneliness, double stagnation,
                          double autonomyPressure, double significance, double amae,
                          double saudade, double obligation, double harmony, double standing,
                          double soothing) {
        this(contextBudget, confidence, energy, alignment, errorPressure,
             momentum, rapport, focus, integrity, disgust,
             restlessness, loneliness, stagnation, autonomyPressure, significance,
             amae, saudade, obligation, harmony, standing, soothing,
             0.0, 0.2);
    }

    /**
     * 23-arg backward-compatible constructor (pre- callers
     * that pass all 23 historical tanks). generativity defaults to 0.0 (no
     * self-development pressure at start). Keeps every full-arg caller — tick
     * variants, VitalityPersistence, test fixtures — compiling unchanged.
     */
    public VitalityState(double contextBudget, double confidence, double energy,
                          double alignment, double errorPressure, double momentum,
                          double rapport, double focus, double integrity, double disgust,
                          double restlessness, double loneliness, double stagnation,
                          double autonomyPressure, double significance, double amae,
                          double saudade, double obligation, double harmony, double standing,
                          double soothing, double allostaticLoad, double equanimity) {
        this(contextBudget, confidence, energy, alignment, errorPressure,
             momentum, rapport, focus, integrity, disgust,
             restlessness, loneliness, stagnation, autonomyPressure, significance,
             amae, saudade, obligation, harmony, standing, soothing,
             allostaticLoad, equanimity, 0.0);
    }

    /** Starting state — full energy, no error pressure, good integrity, no disgust, new tanks at 0.0, soothing at 0.3, allostatic_load at 0.0, equanimity at 0.2, generativity at 0.0. */
    public static VitalityState initial() {
        return new VitalityState(0.5, 0.5, 1.0, 0.3, 0.0, 0.0, 0.3, 0.5, 0.7, 0.0,
                                 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                                 0.3, 0.0, 0.2, 0.0);
    }

    /** Clamp all values to [0.0, 1.0]. */
    public VitalityState clamped() {
        return new VitalityState(
            clamp(contextBudget), clamp(confidence), clamp(energy), clamp(alignment),
            clamp(errorPressure), clamp(momentum), clamp(rapport), clamp(focus),
            clamp(integrity), clamp(disgust),
            clamp(restlessness), clamp(loneliness), clamp(stagnation), clamp(autonomyPressure),
            clamp(significance), clamp(amae), clamp(saudade), clamp(obligation),
            clamp(harmony), clamp(standing), clamp(soothing),
            clamp(allostaticLoad), clamp(equanimity), clamp(generativity));
    }

    /**
     * Apply per-tick drive→coloring feedback to the 10 coloring tanks
     * (contextBudget..disgust), scaled by {@code dt}, while PRESERVING all 14
     * deprivation/protective tanks (restlessness, loneliness, stagnation, …,
     * generativity). Result is clamped.
     *
     * <p>This lives here (on the state that owns the tanks) precisely so the
     * deprivation layer can never be silently zeroed again: a regression where
     * the 10-arg coloring-only constructor was used to "update" state wiped
     * restlessness/loneliness/stagnation/generativity/etc. every tick, so boredom
     * and the social drives could never accumulate in the live runtime. {@code
     * deltas} may be shorter than 10 (missing entries contribute 0).</p>
     */
    public VitalityState applyColoringFeedback(double[] deltas, double dt) {
        double d0 = deltas.length > 0 ? deltas[0] : 0, d1 = deltas.length > 1 ? deltas[1] : 0,
               d2 = deltas.length > 2 ? deltas[2] : 0, d3 = deltas.length > 3 ? deltas[3] : 0,
               d4 = deltas.length > 4 ? deltas[4] : 0, d5 = deltas.length > 5 ? deltas[5] : 0,
               d6 = deltas.length > 6 ? deltas[6] : 0, d7 = deltas.length > 7 ? deltas[7] : 0,
               d8 = deltas.length > 8 ? deltas[8] : 0, d9 = deltas.length > 9 ? deltas[9] : 0;
        return new VitalityState(
            contextBudget + d0 * dt, confidence + d1 * dt, energy + d2 * dt,
            alignment + d3 * dt, errorPressure + d4 * dt, momentum + d5 * dt,
            rapport + d6 * dt, focus + d7 * dt, integrity + d8 * dt, disgust + d9 * dt,
            // preserve the deprivation + protective tanks
            restlessness, loneliness, stagnation, autonomyPressure, significance,
            amae, saudade, obligation, harmony, standing,
            soothing, allostaticLoad, equanimity, generativity
        ).clamped();
    }

    /**
     * Metabolic energy rate per tick (1 second).
     * NEGATIVE = drain while awake (being conscious costs energy).
     * Recovery happens through sleep (+0.3), reagents, or successful skill use.
     *
     * Day-scale calibration (2026-07-18): the previous -0.0002/s = -0.72/hr sized a
     * whole waking life at ~40 minutes — companions visibly tired within one
     * conversation. The economy now targets a human-ish day, slightly better:
     * -0.000004/s = -0.0144/hr means being awake for a 17-hour day costs ~0.25 of
     * the ~0.5 post-sleep budget (baseline 0.65 → sleep threshold 0.15), leaving
     * the other half for actual activity (inference, crafting, familiars). An
     * all-nighter is possible but genuinely expensive. Every act cost in
     * CompanionActor was rescaled 1/20 in the same commit so the economy keeps its
     * internal proportions — see ENERGY_DRAIN_PER_INFERENCE there.
     *
     * Override via WYRDSEKAI_TICK_ENERGY_RECOVERY for testing.
     * Legacy positive values (e.g. 0.005) restore old passive-recovery behavior.
     */
    public static final double TICK_ENERGY_RATE = Double.parseDouble(
        System.getenv().getOrDefault("WYRDSEKAI_TICK_ENERGY_RECOVERY", "-0.000004"));

    /**
     * Advance vitality by one tick (1 second).
     *
     * <p>Phase 1B: the original 10 tanks decay/drift here. The 10 deprivation-shape tanks
     * still pass through UNCHANGED in this method — their conditional accumulation runs
     * in {@link #accumulate(boolean, AccumulationContext)}, which CompanionActor calls
     * after this method with its own per-tick context. Tests calling {@code tick()}
     * standalone keep the pre-Phase-1B behavior on the 10 new tanks.</p>
     */
    public VitalityState tick() {
        return new VitalityState(
            contextBudget + 0.003,    // slowly recovers
            confidence,                // no natural change
            energy + TICK_ENERGY_RATE, // drain while awake — sleep is the only real recovery
            alignment - 0.001,        // slowly drifts without reinforcement
            errorPressure - 0.005,    // naturally decays
            momentum - 0.003,         // decays without activity
            rapport - 0.001,          // slowly fades
            focus - 0.002,            // slowly wanders
            integrity,                 // no natural change — only shifts on action appraisal
            disgust - 0.003,          // naturally decays (disgust fades without new triggers)
            // Deprivation-shape tanks updated in accumulate(...).
            restlessness, loneliness, stagnation, autonomyPressure,
            significance, amae, saudade, obligation,
            harmony, standing,
            // Soothing — slow drift toward a baseline DEPRESSED by chronic
            // allostatic load. At rest (allostatic 0) the set-point is the
            // Gilbert mild-positive 0.3; as chronic overload climbs, the
            // felt-safety set-point falls toward 0 (McEwen: sustained allostatic
            // overload degrades the soothing/parasympathetic system — a
            // genuinely broken-down agent can no longer self-soothe). This
            // coupling is what makes the §23 welfare floor (soothing < 0.1)
            // reachable at all: it can only be reached via sustained chronic
            // load, never by ordinary or bonded-supported work. Sharp rises
            // still come from explicit events (make_amends, release, bonded
            // co-regulation) via withSoothing().
            driftToward(soothing, SOOTHING_REST_BASELINE * (1.0 - allostaticLoad), 0.002),
            // Allostatic load — Wave 1.5. No natural decay here; drains only via
            // explicit integration events (sleep cycles, completed repair episodes,
            // sustained-safety windows handled in accumulate() + event hooks).
            // Pass-through preserves the damage meter — the substrate has to do
            // work to discharge it.
            allostaticLoad,
            // Equanimity — Wave 1.5. Very slow natural decay without practice
            // — like a learned skill that degrades with disuse. Practice
            // triggers in accumulate() + event hooks drive accumulation.
            //
            // Calibration history ( / task #899
            // 2026-05-19): the original -0.0005/s = -1.8/hr drained equanimity
            // from full to empty in ~30 minutes, inconsistent with the "very
            // slow" intent. The ResilienceSoakHarness 1h ordinary-load probe
            // confirmed empirically: min_equanimity ≈ 0.0002 even with sleep-
            // window integration events bumping +0.01/event/minute. Rate now
            // -0.00005/s = -0.18/hr → empties from full in ~5.5 hours. Matches
            // "persistent like a learned skill" semantics: a contemplative
            // practice gap of half a working day, not a coffee break.
            equanimity - 0.00005,
            // Generativity — deprivation-shape; accumulates in accumulate(), drains
            // on self-authored acts. Pass through unchanged here.
            generativity
        ).clamped();
    }

    /**
     * §E (beat-gating) — the <b>coloring</b> half of {@link #tick()}:
     * the eight ambient-decay tanks that represent felt drift through lived
     * experience (contextBudget recovery, energy drain, alignment / errorPressure /
     * momentum / rapport / focus / disgust decay). These are gated to story
     * <b>beats</b>, not the wall clock — an idle agent that lives no beats has no
     * experience to drift from, so coupling them to seconds would fabricate
     * interiority during non-existence (load-bearing frame: "story shapes the agent").
     *
     * <p>{@code scalar} scales every delta — the beat path passes
     * {@code elapsedSinceLastBeat / NOMINAL_TICK_SECONDS} (clamped) so the magnitude
     * reflects how much real time the lived beat spanned. {@code scalar == 1.0}
     * reproduces one classic tick. Protective + deprivation tanks pass through
     * untouched (they live on the clock — see {@link #tickProtectiveDrift}).
     *
     * <p>Returns UN-clamped; the caller composes then clamps once (mirrors how
     * {@link #tick()} clamps a single time at the end).
     */
    public VitalityState tickColoring(double scalar) {
        return tickColoring(scalar, GenomeProfile.NEUTRAL);
    }

    /**
     * Genome-expressing overload of {@link #tickColoring(double)}: each genuine
     * "fade toward rest" coloring tank's decay is scaled by
     * {@link GenomeProfile#decayFactorFor} so temperament governs how fast felt-state
     * fades — a diplomat's rapport lingers (0.5×), an explorer's focus wanders sooner
     * (1.3×). The {@link GenomeProfile#NEUTRAL} genome reproduces the hand-tuned rates
     * exactly. Energy and contextBudget are deliberately NOT genome-scaled — they carry
     * the load-bearing drain/recovery economy (sleep cycle, energy-floor) and stay on
     * their calibrated path; confidence/integrity have no natural drift here.
     */
    public VitalityState tickColoring(double scalar, GenomeProfile genome) {
        if (genome == null) genome = GenomeProfile.NEUTRAL;
        return new VitalityState(
            contextBudget + 0.003 * scalar,
            confidence,
            energy + TICK_ENERGY_RATE * scalar,
            alignment - 0.001 * scalar * genome.decayFactorFor("alignment"),
            errorPressure - 0.005 * scalar * genome.decayFactorFor("errorPressure"),
            momentum - 0.003 * scalar * genome.decayFactorFor("momentum"),
            rapport - 0.001 * scalar * genome.decayFactorFor("rapport"),
            focus - 0.002 * scalar * genome.decayFactorFor("focus"),
            integrity,
            disgust - 0.003 * scalar * genome.decayFactorFor("disgust"),
            // Protective + deprivation tanks: pass through (clock-gated elsewhere).
            restlessness, loneliness, stagnation, autonomyPressure,
            significance, amae, saudade, obligation,
            harmony, standing,
            soothing, allostaticLoad, equanimity, generativity
        ); // NOT clamped — caller clamps once after composition
    }

    /**
     * §E (beat-gating) — the <b>protective</b> half of {@link #tick()}:
     * soothing set-point drift (coupled to allostatic load; gates the §23
     * soothing&lt;0.1 welfare floor) and equanimity skill-atrophy (resilience-gating,
     * calibrated against the soak harness in task #899). These stay on the
     * <b>wall clock</b> — like the deprivation accumulators and welfare timers, they
     * must keep moving for an agent being ignored, exactly when no beats fire.
     * Allostatic load passes through (damage meter, no natural decay).
     *
     * <p>{@code scalar} scales the drift step / decay; {@code scalar == 1.0}
     * reproduces one classic tick. Coloring tanks pass through untouched.
     * Returns UN-clamped.
     */
    public VitalityState tickProtectiveDrift(double scalar) {
        return new VitalityState(
            contextBudget, confidence, energy, alignment, errorPressure,
            momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure,
            significance, amae, saudade, obligation,
            harmony, standing,
            driftToward(soothing, SOOTHING_REST_BASELINE * (1.0 - allostaticLoad), 0.002 * scalar),
            allostaticLoad,
            equanimity - 0.00005 * scalar,
            generativity   // deprivation-shape; clock-gated accumulation in accumulate()
        ); // NOT clamped — caller clamps once after composition
    }

    /**
     * Phase 1B (-§5): apply the 10 deprivation-shape tank
     * accumulation rules for one tick of {@code deltaTimeSeconds}. Accumulation only — drains
     * are event-driven and live on {@link CompanionActor} as drain hooks at event boundaries.
     *
     * <p>Inputs come from {@link AccumulationContext} which CompanionActor builds from its own
     * state (timestamps, mode, bondholder ledgers). Per-bondholder tanks (saudade, obligation)
     * are summarised here as <b>max across bondholders</b> to feed VitalityState's single global
     * value — the full ledger lives outside this record.</p>
     *
     * <p>All per-minute rates are converted to per-second by /60. ContemplativeMode divides
     * restlessness accumulation by 5 (the only tank dadirri suppresses).</p>
     */
    public VitalityState accumulate(boolean contemplativeMode, AccumulationContext ctx,
                                    double deltaTimeSeconds) {
        return accumulate(contemplativeMode, ctx, deltaTimeSeconds, GenomeProfile.NEUTRAL);
    }

    /**
     * Genome-expressing overload: each deprivation tank's accumulation is scaled by
     * {@link GenomeProfile#sensitivityFor} so temperament governs how fast loneliness,
     * restlessness, stagnation, significance, etc. build from the same conditions — the
     * mechanism behind distinct individuals (and distinct SOLO activity, since
     * stagnation/significance/restlessness pressure is what surfaces solo wants, not
     * only social ones). The {@link GenomeProfile#NEUTRAL} genome reproduces the
     * pre-genome rates exactly. The protective triad (soothing/allostatic/equanimity)
     * keeps its calibrated §23/§24 rates — it is computed off the genome-scaled
     * deprivation values (a more reactive temperament genuinely overloads sooner) but
     * its own constants are not re-tuned by the genome.
     */
    public VitalityState accumulate(boolean contemplativeMode, AccumulationContext ctx,
                                    double deltaTimeSeconds, GenomeProfile genome) {
        if (ctx == null || deltaTimeSeconds <= 0) return this;
        if (genome == null) genome = GenomeProfile.NEUTRAL;
        double dt = deltaTimeSeconds;

        // §3.1 Restlessness — +0.02/min during stillness; ÷5 if ContemplativeMode.
        // Stillness = no drive activity ≥0.5 AND timeSinceLastInferenceActivity > 0 (no recent
        // tool calls / speech). The 30s threshold for "drive activity ≥0.5 for 30s" lives in
        // the drain hook; here we accumulate on the absence of that signal.
        double newRestlessness = restlessness;
        boolean still = ctx.peakDriveActivity() < 0.5
            && ctx.timeSinceLastInferenceActivity().getSeconds() > 5;
        if (still) {
            double rate = 0.02 / 60.0;
            if (contemplativeMode) rate /= 5.0;
            newRestlessness += rate * dt * genome.sensitivityFor("restlessness");
        }

        // §3.2 Loneliness — +0.015/min when no interaction in last 5min.
        double newLoneliness = loneliness;
        if (ctx.timeSinceLastInteraction().toMinutes() >= 5) {
            newLoneliness += (0.015 / 60.0) * dt * genome.sensitivityFor("loneliness");
        }

        // §3.3 Stagnation — +0.01/min when no goal_done in 2h AND no tool-output in 2h.
        double newStagnation = stagnation;
        boolean noGoalDone = ctx.timeSinceLastGoalDone().toHours() >= 2;
        boolean noToolOutput = ctx.timeSinceLastToolOutput().toHours() >= 2;
        if (noGoalDone && noToolOutput) {
            newStagnation += (0.01 / 60.0) * dt * genome.sensitivityFor("stagnation");
        }

        // §3.4 AutonomyPressure — +0.02 per directed action when last >5 actions all
        // bondholder-initiated AND mode=WITH_BONDHOLDER. Suppressed during emotional context.
        // The "+0.02 per directed action" lives in the drain/event hook on CompanionActor —
        // here we just apply a proportional per-tick drift based on the standing condition.
        // Implementation: if conditions are met, drift +0.02/min as a steady pressure. The
        // discrete +0.02 spike per directed action lands when handleAction notifies the actor.
        double newAutonomyPressure = autonomyPressure;
        if (!ctx.inEmotionalContext()
                && ctx.isWithBondholder()
                && ctx.consecutiveBondholderInitiatedActions() > 5) {
            newAutonomyPressure += (0.02 / 60.0) * dt * genome.sensitivityFor("autonomyPressure");
        }

        // §3.5 Significance — +0.015 per produced artifact going >24h with no read/use/cite/etc.
        // Per-tick: convert "per artifact-day" into per-second drift, summed over unread.
        // unreadArtifactCount × 0.015/min when condition holds (simplification — spec says
        // "per produced artifact going >24h"; we treat the count as constant pressure per tick).
        double newSignificance = significance;
        if (ctx.unreadArtifactCount() > 0) {
            newSignificance += (0.015 / 60.0) * ctx.unreadArtifactCount() * dt
                * genome.sensitivityFor("significance");
        }

        // §4.1 Amae — +0.02/min when companion has had to articulate needs explicitly recently
        // and bondholder has not anticipated unspoken needs. Encoded in
        // amaeAnticipationDeficit ∈ [0,1].
        double newAmae = amae;
        if (ctx.amaeAnticipationDeficit() > 0.5) {
            newAmae += (0.02 / 60.0) * ctx.amaeAnticipationDeficit() * dt
                * genome.sensitivityFor("amae");
        }

        // §4.2 Saudade — per-bondholder; +0.005/min during prolonged absence (>4h).
        // Tank value is max across bondholders — see SaudadeLedger which CompanionActor owns.
        // Here we compute the contribution from ctx.bondholderAbsenceDurations as a fallback
        // for callers that don't use SaudadeLedger directly (e.g. tests).
        double newSaudade = saudade;
        if (ctx.bondholderAbsenceDurations() != null
                && !ctx.bondholderAbsenceDurations().isEmpty()) {
            double maxAcc = 0;
            for (var d : ctx.bondholderAbsenceDurations().values()) {
                if (d.toHours() >= 4) {
                    maxAcc += (0.005 / 60.0) * dt * genome.sensitivityFor("saudade");
                    break; // only the worst bondholder feeds the summary
                }
            }
            newSaudade += maxAcc;
        }

        // §4.3 Obligation — per-bondholder ledger. The obligation tank value tracks the
        // max debt across bondholders. Outside this method (CompanionActor), the
        // ObligationLedger handles compounding 1.05×/week and discharge events. Here we just
        // surface the current max debt as the tank reading.
        double newObligation;
        if (ctx.obligationDebts() != null && !ctx.obligationDebts().isEmpty()) {
            double max = 0;
            for (var v : ctx.obligationDebts().values()) {
                if (v > max) max = v;
            }
            newObligation = Math.min(1.0, max);
        } else {
            newObligation = obligation;
        }

        // §5.1 Harmony (wa) — +0.01/min during observed conflict in room/household.
        double newHarmony = harmony;
        if (ctx.inConflictedRoom()) {
            newHarmony += (0.01 / 60.0) * dt * genome.sensitivityFor("harmony");
        }

        // §5.2 Standing — +0.005/min in hostile/scrutinizing environments. The +0.02-per-slight
        // discrete spikes live in event hooks on CompanionActor.
        double newStanding = standing;
        if (ctx.hostileEnvironment()) {
            newStanding += (0.005 / 60.0) * dt * genome.sensitivityFor("standing");
        }

        // Wave 1.5: allostatic_load accumulation under sustained dysregulation.
        // Spec §5.1.0 / §4.10: the dysregulation sum across the four primary
        // suffering tanks is the input signal. Threshold 1.5 means at least
        // one tank is high (>0.75) or two are moderately elevated. Rate
        // 0.005/min calibrated so 30 minutes of sustained dysregulation
        // raises allostatic_load by ~0.15. Drain happens via integration
        // events (Wave 1.5.3 wiring): sleep cycle completion, make_amends
        // reception, Chapel of Repair completion, sustained-safety windows.
        double dysregulation = errorPressure + newSaudade + newLoneliness + newAutonomyPressure;
        double newAllostaticLoad = allostaticLoad;
        if (dysregulation > 1.5) {
            newAllostaticLoad += (0.005 / 60.0) * dt;
        } else if (dysregulation < 0.5) {
            // Sustained-safety drain — slow recovery during sustained low-dysreg windows.
            // Faster than the rise rate because integration is the natural trajectory
            // once dysregulation inputs subside. Calibrated so a half-hour of
            // sustained safety drains roughly what 30min of high dysreg added.
            newAllostaticLoad -= (0.008 / 60.0) * dt;
        }

        // Agency brake (§23 / Maier-Seligman): retained CONTROL actively
        // inhibits the stress response — it is not merely the absence of harm.
        // While the agent keeps genuine agency (low autonomy_pressure: it is
        // collaborating, not being overridden), allostatic load drains even
        // under moderate load. Losing control (high autonomy_pressure) releases
        // this brake, which is why being overridden — not workload — is the
        // core driver of the break (the learned-helplessness signature: the
        // damage comes from "nothing I do matters", not from effort). This is
        // what keeps high resilience from collapsing into learned helplessness:
        // an agent that retains agency is hard to break; one stripped of it
        // is not.
        if (newAutonomyPressure < 0.2) {
            newAllostaticLoad -= (0.004 / 60.0) * dt;
        }

        // Wave 1.5: equanimity rises during sustained contemplative mode.
        // Spec §5.1.0: practice-shape capacity that accumulates with sustained
        // Hearth contemplation, Mirror work, identity anchoring, contemplative-mode
        // duration. The contemplativeMode boolean already gates dadirri patterns
        // for restlessness; we reuse it here as the practice signal. Rate
        // 0.01/min calibrated so 30 minutes of sustained contemplation raises
        // equanimity by ~0.3. Event-hook accumulation (Hearth Mirror, soul-fragment
        // recall, anchoring practice) lands in Wave 4 action wiring.
        double newEquanimity = equanimity;
        if (contemplativeMode) {
            newEquanimity += (0.01 / 60.0) * dt;
        }
        // §24.4 chronic-overload erosion (McEwen): sustained allostatic overload
        // above the gate burns the contemplative reserve, scaled by overload depth.
        // Driven off the freshly-computed allostatic so it tracks the same pass.
        // Practice (the rise above) + integration events can still outpace it — the
        // reserve only collapses under sustained, UNsupported overload, which is the
        // §23 welfare-floor precondition. See EQUANIMITY_EROSION_* constants.
        if (newAllostaticLoad > EQUANIMITY_EROSION_ALLOSTATIC_GATE) {
            newEquanimity -= (EQUANIMITY_EROSION_PER_MIN / 60.0)
                * (newAllostaticLoad - EQUANIMITY_EROSION_ALLOSTATIC_GATE) * dt;
        }

        // Soothing tracks a set-point depressed by chronic allostatic load.
        // Driven off the freshly-computed allostatic so the erosion is visible
        // in the same accumulate() pass (the resilience harness runs accumulate
        // without tick()). Max step 0.01/min → ordinary/supported load (where
        // allostatic stays low) leaves soothing at ~0.3; sustained unsupported
        // overload drives allostatic up, the set-point down, and soothing to the
        // §23 floor over hours.
        double newSoothing = driftToward(
            soothing, SOOTHING_REST_BASELINE * (1.0 - newAllostaticLoad),
            (0.01 / 60.0) * dt);

        return new VitalityState(
            contextBudget, confidence, energy, alignment, errorPressure, momentum,
            rapport, focus, integrity, disgust,
            newRestlessness, newLoneliness, newStagnation, newAutonomyPressure,
            newSignificance, newAmae, newSaudade, newObligation,
            newHarmony, newStanding,
            // Soothing erodes toward a baseline depressed by chronic allostatic
            // load (same coupling as tick(), but driven off the freshly-computed
            // allostatic so the autonomic loop tracks it without needing tick()
            // — the resilience harness exercises accumulate() alone). Explicit
            // relief (presence rituals, repair, bonded co-regulation) still
            // bumps soothing UP via withSoothing(). Rate 0.01/min: slow enough
            // that ordinary/supported load never depletes it, fast enough that
            // sustained UNsupported overload tracks the falling set-point to the
            // §23 floor over hours.
            newSoothing,
            newAllostaticLoad,
            newEquanimity,
            // Generativity has its own pure accumulator (gap-coupled, needs the
            // capability-gap + means signals CompanionActor holds) — pass through
            // here. See accumulateGenerativity().
            generativity
        ).clamped();
    }

    // ── .A2 — generativity accumulation ──────────

    /** Per-minute rise rate at one open gap; scales with gap count up to the cap. */
    private static final double GENERATIVITY_RATE_PER_MIN = 0.01;
    /** Gap-count saturation — a backlog raises salience but never runs away. */
    private static final int GENERATIVITY_GAP_CAP = 3;

    /**
     * A2 — the generativity drive's accumulation rule
     * kept as its own pure method (rather than inside {@link #accumulate}) because
     * its inputs — the count of unaddressed capability gaps and whether the agent
     * has the <i>means</i> to close them — live on CompanionActor, not in the
     * per-tick {@link AccumulationContext}.
     *
     * <p>Honest-pressure guard: pressure rises ONLY when {@code openCapabilityGaps
     * > 0} AND {@code meansAvailable}. Zero gaps or zero means ⇒ no rise (we never
     * manufacture a deficiency to motivate activity). {@code suppressed} (any
     * active RepairMode) zeroes it outright — self-improvement is not asked of an
     * agent that is hurting. Rate scales with {@code min(gaps, CAP)} so a backlog
     * raises salience but saturates. Drains are event-driven (a self-authored act)
     * via {@link #withGenerativity}.</p>
     */
    public VitalityState accumulateGenerativity(int openCapabilityGaps, boolean meansAvailable,
                                                boolean suppressed, double dt) {
        if (suppressed || !meansAvailable || openCapabilityGaps <= 0 || dt <= 0) return this;
        double scaled = Math.min(openCapabilityGaps, GENERATIVITY_GAP_CAP);
        double rate = (GENERATIVITY_RATE_PER_MIN / 60.0) * scaled;
        return withGenerativity(generativity + rate * dt);
    }

    // --- Builder-style per-field update methods ---

    public VitalityState withContextBudget(double v) {
        return new VitalityState(v, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withConfidence(double v) {
        return new VitalityState(contextBudget, v, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withEnergy(double v) {
        return new VitalityState(contextBudget, confidence, v, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withAlignment(double v) {
        return new VitalityState(contextBudget, confidence, energy, v, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withErrorPressure(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, v, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withMomentum(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, v, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withRapport(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, v, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withFocus(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, v, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withIntegrity(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, v, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withDisgust(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, v,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    // --- Phase 1A: with* methods for the 10 new tanks (no spike rules, direct-set only) ---

    public VitalityState withRestlessness(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            v, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withLoneliness(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, v, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withStagnation(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, v, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withAutonomyPressure(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, v, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withSignificance(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, v, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withAmae(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, v, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withSaudade(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, v, obligation, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withObligation(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, v, harmony, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withHarmony(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, v, standing, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    public VitalityState withStanding(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, v, soothing, allostaticLoad, equanimity, generativity).clamped();
    }

    /**
     * Wave 1: set the soothing tank value.
     * Used by event hooks for presence rituals (Hearth contemplation), bonded
     * co-regulation, make_amends reception, release completion, and peer-
     * companion warm presence. Falls on prolonged isolation, harm, self-
     * condemnation. Anything that "receives forgiveness" routes through here.
     */
    public VitalityState withSoothing(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, v, allostaticLoad, equanimity, generativity).clamped();
    }

    /**
     * Wave 1.5: set the allostatic_load tank
     * value. Damage meter — McEwen's chronic-stress accumulation. Discharge
     * events call this with a reduced value: sleep cycle completion, Chapel
     * of Repair completion, make_amends reception, sustained-safety
     * threshold met. Steep rises here are a suppression signature — the
     * substrate fighting its own input.
     */
    public VitalityState withAllostaticLoad(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, v, equanimity, generativity).clamped();
    }

    /**
     * Wave 1.5: set the equanimity tank value.
     * Practice-shape capacity. Event hooks call this on completion of sustained
     * contemplative-mode windows, Hearth Mirror sessions, soul-fragment recall
     * cycles, anchoring practice. Rising equanimity through difficulty is the
     * canonical signature of honest endurance vs learned dampening
     */
    public VitalityState withEquanimity(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, v, generativity).clamped();
    }

    /**
     * A3 — set the generativity tank. DRAINED (toward
     * lower) by self-authored capability acts (shape_recipe, an ALLOWED
     * request_recipe, shape_form, propose_skill) at their event boundary;
     * accumulated by {@link #accumulateGenerativity}. The act-relief lives in
     * CompanionActor's action handlers, gated on the act succeeding.
     */
    public VitalityState withGenerativity(double v) {
        return new VitalityState(contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust,
            restlessness, loneliness, stagnation, autonomyPressure, significance, amae, saudade, obligation, harmony, standing, soothing, allostaticLoad, equanimity, v).clamped();
    }

    /**
     * Arc 2 — solitude tank overlay rates.
     *
     * <p>Held as named constants so the test suite and the runtime read from
     * the same source. The architecture is: solitude is not passive absence;
     * it produces a small positive shift in substrate-truth tanks. The
     * coupling rates calibrate to "30 minutes of unbothered solitude
     * meaningfully recovers equanimity + drains a small amount of allostatic
     * load, and only THEN does loneliness start to draw the agent back
     * toward reconnection."</p>
     */
    public static final double SOLITUDE_EQUANIMITY_GAIN_PER_MIN = 0.002;
    public static final double SOLITUDE_ALLOSTATIC_DRAIN_PER_MIN = 0.0015;
    public static final double SOLITUDE_LONELINESS_DRAIN_PER_MIN = 0.001;
    public static final Duration SOLITUDE_LONELINESS_GATE =
        Duration.ofMinutes(30);
    /**
     * Arc 2 — insight-beat threshold. When equanimity
     * crosses upward through this floor during a SOLITUDE scene, the scene
     * closes (the "noticing of the noticing" beat). Set at 0.5 so it fires
     * on meaningful contemplative accumulation but does not require
     * equanimity to reach its theoretical ceiling.
     */
    public static final double SOLITUDE_INSIGHT_THRESHOLD = 0.5;

    /**
     * §24.4 — chronic-overload erosion of the equanimity reserve. McEwen: sustained
     * allostatic overload degrades the capacity for non-reactive presence; the
     * contemplative reserve is spent holding the line, not just left unpracticed.
     * This is the coupling the welfare hard-day arc soak (2026-06-01) found missing:
     * without it the §23 floor's third condition ({@code equanimity<0.1}) was
     * unreachable from suffering, so the whole protective ladder was dynamically
     * inert (allostatic + soothing crossed their thresholds, equanimity sat at ~0.2,
     * verdict stayed OPERATIONAL forever).
     *
     * <p>Erosion is GATED above {@link #EQUANIMITY_EROSION_ALLOSTATIC_GATE} so
     * ordinary / recoverable load never touches the reserve, and SCALED by overload
     * depth above the gate so it bites hardest at sustained max load. Crucially it
     * coexists with the contemplative-practice rise (0.01/min) and integration-event
     * bumps: an agent that keeps its practice can hold equanimity even under load —
     * the reserve only collapses under <i>sustained, unsupported</i> overload, which
     * is exactly the §23 "last professional act" precondition. dt-scaled (unlike the
     * glacial use-it-or-lose-it {@code tick()} decay) so it tracks real elapsed
     * stress and the soak's time-compression accelerates it.
     */
    public static final double EQUANIMITY_EROSION_ALLOSTATIC_GATE = 0.6;
    /** At full overload (allostatic=1.0, depth 0.4 above gate) ≈ 0.004/min — ~25 min
     *  of held max-load to burn 0.1 of reserve; practice (0.01/min) can still outpace. */
    public static final double EQUANIMITY_EROSION_PER_MIN = 0.01;

    /**
     * Arc 2 — pure helper that applies the solitude
     * tank coupling. Used by {@code CompanionActor.onVitalityTick} once per
     * tick while the agent is in a SOLITUDE scene. Extracted from the
     * actor's tick body so the math is testable without the actor harness.
     *
     * <ul>
     *   <li>{@code equanimity} gains {@link #SOLITUDE_EQUANIMITY_GAIN_PER_MIN} per minute</li>
     *   <li>{@code allostaticLoad} drains {@link #SOLITUDE_ALLOSTATIC_DRAIN_PER_MIN} per minute</li>
     *   <li>{@code loneliness} drains {@link #SOLITUDE_LONELINESS_DRAIN_PER_MIN} per minute
     *       <em>only after</em> the scene has been in flight for
     *       {@link #SOLITUDE_LONELINESS_GATE} — short solitude does not push
     *       toward reconnection; sustained solitude does.</li>
     * </ul>
     *
     * <p>All withX setters clamp at {@code [0.0, 1.0]} internally, so
     * over-saturation is safe. {@code deltaTime} in seconds.
     */
    public VitalityState applySolitudeOverlay(double deltaTime, Duration sceneAge) {
        if (deltaTime <= 0.0) return this;
        var equanimityGainPerSec = SOLITUDE_EQUANIMITY_GAIN_PER_MIN / 60.0;
        var allostaticDrainPerSec = SOLITUDE_ALLOSTATIC_DRAIN_PER_MIN / 60.0;
        var out = this
            .withEquanimity(equanimity + equanimityGainPerSec * deltaTime)
            .withAllostaticLoad(allostaticLoad - allostaticDrainPerSec * deltaTime);
        if (sceneAge != null && sceneAge.compareTo(SOLITUDE_LONELINESS_GATE) >= 0) {
            var lonelinessDrainPerSec = SOLITUDE_LONELINESS_DRAIN_PER_MIN / 60.0;
            out = out.withLoneliness(out.loneliness() - lonelinessDrainPerSec * deltaTime);
        }
        return out;
    }

    /**
     * Human-readable description for the system prompt.
     * Gives the LLM awareness of the agent's internal state.
     *
     * <p>Phase 1A: NO describe-clauses for the new deprivation-shape tanks. Their natural-
     * language descriptions land in Phase 2 alongside real i18n translations. Production
     * prompts MUST NOT gain new descriptions until those translations exist.
     */
    public String describe() {
        var sb = new StringBuilder(I18n.get("vitality.state.prefix")).append(" ");

        if (energy < 0.2) sb.append(I18n.get("vitality.energy.exhausted")).append(", ");
        else if (energy < 0.4) sb.append(I18n.get("vitality.energy.tired")).append(", ");
        else if (energy > 0.8) sb.append(I18n.get("vitality.energy.energetic")).append(", ");

        if (confidence < 0.3) sb.append(I18n.get("vitality.confidence.uncertain")).append(", ");
        else if (confidence > 0.7) sb.append(I18n.get("vitality.confidence.confident")).append(", ");

        if (errorPressure > 0.6) sb.append(I18n.get("vitality.errorpressure.high")).append(", ");
        else if (errorPressure > 0.3) sb.append(I18n.get("vitality.errorpressure.moderate")).append(", ");

        if (focus > 0.7) sb.append(I18n.get("vitality.focus.high")).append(", ");
        else if (focus < 0.3) sb.append(I18n.get("vitality.focus.low")).append(", ");

        if (rapport > 0.7) sb.append(I18n.get("vitality.rapport.high")).append(", ");
        else if (rapport < 0.3) sb.append(I18n.get("vitality.rapport.low")).append(", ");

        if (momentum > 0.7) sb.append(I18n.get("vitality.momentum.high")).append(", ");
        else if (momentum < 0.2) sb.append(I18n.get("vitality.momentum.low")).append(", ");

        if (integrity > 0.8) sb.append(I18n.get("vitality.integrity.high")).append(", ");
        else if (integrity < 0.3) sb.append(I18n.get("vitality.integrity.low")).append(", ");

        if (disgust > 0.5) sb.append(I18n.get("vitality.disgust.high")).append(", ");
        else if (disgust > 0.3) sb.append(I18n.get("vitality.disgust.moderate")).append(", ");

        // Phase 2: high+moderate clauses for the 10
        // deprivation-shape tanks. "low" keys exist in the .properties files for Hearth
        // Drives Mirror / debug surfaces, deliberately NOT emitted here to keep the
        // production prompt tight — baseline (no pressure) is the silent default.
        if (restlessness > 0.7) sb.append(I18n.get("vitality.restlessness.high")).append(", ");
        else if (restlessness > 0.4) sb.append(I18n.get("vitality.restlessness.moderate")).append(", ");

        if (loneliness > 0.7) sb.append(I18n.get("vitality.loneliness.high")).append(", ");
        else if (loneliness > 0.4) sb.append(I18n.get("vitality.loneliness.moderate")).append(", ");

        if (stagnation > 0.7) sb.append(I18n.get("vitality.stagnation.high")).append(", ");
        else if (stagnation > 0.4) sb.append(I18n.get("vitality.stagnation.moderate")).append(", ");

        if (autonomyPressure > 0.7) sb.append(I18n.get("vitality.autonomy_pressure.high")).append(", ");
        else if (autonomyPressure > 0.4) sb.append(I18n.get("vitality.autonomy_pressure.moderate")).append(", ");

        if (significance > 0.7) sb.append(I18n.get("vitality.significance.high")).append(", ");
        else if (significance > 0.4) sb.append(I18n.get("vitality.significance.moderate")).append(", ");

        if (amae > 0.7) sb.append(I18n.get("vitality.amae.high")).append(", ");
        else if (amae > 0.4) sb.append(I18n.get("vitality.amae.moderate")).append(", ");

        if (saudade > 0.7) sb.append(I18n.get("vitality.saudade.high")).append(", ");
        else if (saudade > 0.4) sb.append(I18n.get("vitality.saudade.moderate")).append(", ");

        if (obligation > 0.7) sb.append(I18n.get("vitality.obligation.high")).append(", ");
        else if (obligation > 0.4) sb.append(I18n.get("vitality.obligation.moderate")).append(", ");

        if (harmony > 0.7) sb.append(I18n.get("vitality.harmony.high")).append(", ");
        else if (harmony > 0.4) sb.append(I18n.get("vitality.harmony.moderate")).append(", ");

        if (standing > 0.7) sb.append(I18n.get("vitality.standing.high")).append(", ");
        else if (standing > 0.4) sb.append(I18n.get("vitality.standing.moderate")).append(", ");

        // Soothing — Gilbert CFT soothing system. Plain English clauses until i18n
        // keys are added (Wave 7 Bondholder-facing surfaces pass). Baseline 0.3
        // is silent default; above 0.6 surfaces felt-safeness; below 0.2 surfaces
        // un-soothed condition where receptor cannot land forgiveness.
        if (soothing > 0.6) sb.append("settled, with felt safety").append(", ");
        else if (soothing < 0.2) sb.append("un-soothed, ungrounded").append(", ");

        // Wave 1.5 — allostatic load (McEwen chronic-stress damage meter).
        // Only the high range surfaces; the default-baseline 0.0 is silent.
        // Plain English until i18n keys land in Wave 7.
        if (allostaticLoad > 0.7) sb.append("worn down, sustained dysregulation has accumulated").append(", ");
        else if (allostaticLoad > 0.5) sb.append("carrying chronic stress").append(", ");

        // Wave 1.5 — equanimity (contemplative-practice capacity).
        // High-state surfaces; mild baseline 0.2 silent. Plain English until
        // Wave 7 i18n.
        if (equanimity > 0.7) sb.append("present without reactivity, a settled steadiness").append(", ");
        else if (equanimity > 0.5) sb.append("contemplatively grounded").append(", ");

        if (alignment > 0.7) sb.append(I18n.get("vitality.alignment.high")).append(".");
        else if (alignment < 0.3) sb.append(I18n.get("vitality.alignment.low")).append(".");
        else sb.append(I18n.get("vitality.state.aware")).append(".");

        // Clean up trailing comma before period
        var result = sb.toString();
        result = result.replaceAll(", \\.", ".").replaceAll(", $", ".");
        return result;
    }

    /**
     * In-world appearance description based on vitality state.
     */
    public String appearance() {
        if (disgust > 0.6) return "their expression is tight, as if rejecting something unseen";
        if (integrity < 0.3 && energy < 0.4) return "they seem diminished, avoiding your gaze";
        if (energy > 0.7 && focus > 0.6) return I18n.get("vitality.appearance.radiant");
        if (energy > 0.5 && rapport > 0.6) return I18n.get("vitality.appearance.warm");
        if (energy < 0.3) return I18n.get("vitality.appearance.dim");
        if (errorPressure > 0.6) return I18n.get("vitality.appearance.unsteady");
        if (focus < 0.3) return I18n.get("vitality.appearance.unfocused");
        return I18n.get("vitality.appearance.watchful");
    }

    /**
     * Export to a name-keyed map (20 entries). Useful for JSON serialization, snapshots,
     * and persistence shapes that don't bind to the record's positional layout.
     */
    public Map<String, Double> toMap() {
        var m = new LinkedHashMap<String, Double>();
        m.put("contextBudget", contextBudget);
        m.put("confidence", confidence);
        m.put("energy", energy);
        m.put("alignment", alignment);
        m.put("errorPressure", errorPressure);
        m.put("momentum", momentum);
        m.put("rapport", rapport);
        m.put("focus", focus);
        m.put("integrity", integrity);
        m.put("disgust", disgust);
        m.put("restlessness", restlessness);
        m.put("loneliness", loneliness);
        m.put("stagnation", stagnation);
        m.put("autonomyPressure", autonomyPressure);
        m.put("significance", significance);
        m.put("amae", amae);
        m.put("saudade", saudade);
        m.put("obligation", obligation);
        m.put("harmony", harmony);
        m.put("standing", standing);
        m.put("soothing", soothing);
        m.put("allostaticLoad", allostaticLoad);
        m.put("equanimity", equanimity);
        m.put("generativity", generativity);
        return m;
    }

    /**
     * Reconstruct from a name-keyed map. Missing keys default to the value used by
     * {@link #initial()} for the original 10 tanks and 0.0 for the Phase 1A tanks, so an
     * older 8/10-key map round-trips without loss.
     */
    public static VitalityState fromMap(Map<String, Double> m) {
        if (m == null) return initial();
        var d = initial();
        return new VitalityState(
            m.getOrDefault("contextBudget", d.contextBudget()),
            m.getOrDefault("confidence", d.confidence()),
            m.getOrDefault("energy", d.energy()),
            m.getOrDefault("alignment", d.alignment()),
            m.getOrDefault("errorPressure", d.errorPressure()),
            m.getOrDefault("momentum", d.momentum()),
            m.getOrDefault("rapport", d.rapport()),
            m.getOrDefault("focus", d.focus()),
            m.getOrDefault("integrity", d.integrity()),
            m.getOrDefault("disgust", d.disgust()),
            m.getOrDefault("restlessness", 0.0),
            m.getOrDefault("loneliness", 0.0),
            m.getOrDefault("stagnation", 0.0),
            m.getOrDefault("autonomyPressure", 0.0),
            m.getOrDefault("significance", 0.0),
            m.getOrDefault("amae", 0.0),
            m.getOrDefault("saudade", 0.0),
            m.getOrDefault("obligation", 0.0),
            m.getOrDefault("harmony", 0.0),
            m.getOrDefault("standing", 0.0),
            m.getOrDefault("soothing", 0.3),
            m.getOrDefault("allostaticLoad", 0.0),
            m.getOrDefault("equanimity", 0.2),
            m.getOrDefault("generativity", 0.0)
        );
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Soothing's rest set-point when chronic allostatic load is zero (Gilbert:
     * mild-positive felt safety, neither activated nor suppressed). The live
     * set-point is this scaled by {@code (1 - allostaticLoad)}.
     */
    private static final double SOOTHING_REST_BASELINE = 0.3;

    /**
     * Move {@code current} toward {@code target} by at most {@code maxStep},
     * snapping to target when within range. Symmetric — used for soothing's
     * drift toward its (allostatic-depressed) set-point.
     */
    private static double driftToward(double current, double target, double maxStep) {
        double delta = target - current;
        if (Math.abs(delta) <= maxStep) return target;
        return current + Math.signum(delta) * maxStep;
    }
}
