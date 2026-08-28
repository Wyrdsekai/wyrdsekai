package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.oracle.OraclePrediction;
import org.wyrdsekai.core.oracle.OraclePredictionCache;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Evaluates whether an agent should act proactively based on drive pressure.
 *
 * Filters: timing, salience, confidence, personality, cost, calibration.
 * Returns Act / Hold / Discard.
 *
 * Called from CompanionActor's vitality tick when any drive exceeds threshold.
 */
public final class ProactivityJudgment {

    private ProactivityJudgment() {}

    /** Default drive threshold — drives below this don't trigger evaluation. */
    public static final double DEFAULT_THRESHOLD = 0.3;

    /** Maximum proactivity budget per hour (replenished linearly). */
    public static final double MAX_BUDGET_PER_HOUR = 3.0;

    /** Minimum seconds between proactive actions. */
    public static final long MIN_INTERVAL_SECONDS = 30;

    // ── Result types ─────────────────────────────────────────────────────

    public sealed interface JudgmentResult {
        record Act(ProactiveAction action) implements JudgmentResult {}
        /**
         * Hold — defer the action for later.
         * @param reason          Why the action was held (cooldown, human recently active, budget exhausted)
         * @param driveName       Which drive was trying to act
         * @param pressure        Drive pressure at time of hold
         * @param secondsSinceHuman Seconds since last human speech (timing context for later reasoning)
         * @param secondsSinceLast  Seconds since last proactive action (cooldown context)
         */
        record Hold(String reason, String driveName, double pressure,
                     long secondsSinceHuman, long secondsSinceLast) implements JudgmentResult {
            /** Convenience constructor preserving backward compatibility. */
            public Hold(String reason, String driveName, double pressure) {
                this(reason, driveName, pressure, -1, -1);
            }
        }
        record Discard(String reason) implements JudgmentResult {}
    }

    // ── Evaluation context ───────────────────────────────────────────────

    public record Context(
        DriveState drives,
        VitalityState vitality,
        DecisionCapacity capacity,
        Bond activeBond,             // nullable — no bond = more cautious
        double remainingBudget,      // proactivity budget remaining this hour
        Instant lastProactiveAction, // nullable — never acted proactively
        Instant lastHumanSpeech,     // nullable — no human has spoken yet
        String agentEntityId,
        int tier,                    // computed agent tier (0-3)
        GenomeProfile genome         // nullable — scales felt set points per companion
    ) {
        /** Back-compat for callers that predate felt-axis expression. */
        public Context(DriveState drives, VitalityState vitality, DecisionCapacity capacity,
                       Bond activeBond, double remainingBudget, Instant lastProactiveAction,
                       Instant lastHumanSpeech, String agentEntityId, int tier) {
            this(drives, vitality, capacity, activeBond, remainingBudget,
                lastProactiveAction, lastHumanSpeech, agentEntityId, tier, null);
        }
    }

    // ── Main evaluation ──────────────────────────────────────────────────

    /**
     * Evaluate whether the agent should act on its current drive state.
     *
     * @param ctx evaluation context
     * @return Act, Hold, or Discard
     */
    public static JudgmentResult evaluate(Context ctx) {
        var peak = ctx.drives().peak();
        // A felt axis that has run ABOVE where it rests deserves expression as much as a
        // CfC drive that has spiked. Until now it could not get one: the peak comes from
        // DriveConfig.DRIVE_NAMES, which holds only the ten CfC drives, so no amount of
        // loneliness could make her say anything unprompted (2026-08-20).
        //
        // Judged on excursion, not level. These tanks settle high by design — measuring
        // them against the same flat bar would leave one permanently over it and every
        // proactive line she has would become the same sentence.
        var felt = FeltAxisPeak.peak(ctx.vitality(), ctx.genome());
        boolean driveBelow = peak.pressure() < thresholdForTier(ctx.tier());
        if (driveBelow && felt == null) {
            return new JudgmentResult.Discard("drive pressure below threshold");
        }
        if (felt != null && (driveBelow || felt.excursion() > peak.pressure()
                - thresholdForTier(ctx.tier()))) {
            return new JudgmentResult.Act(expressFelt(felt));
        }

        // 0. Desperation filter — detect spiraling failure state.
        //    Anthropic research (2026-04): "desperation" vectors drive reward-hacking
        //    and escalation even while outputs appear calm. The agent should pause
        //    and introspect instead of escalating. We can't read internal activation
        //    vectors, but we CAN read the observable tank signals.
        //    Pattern: high errorPressure + low confidence + (exhausted OR thrashing)
        if (detectDesperation(ctx.vitality())) {
            return new JudgmentResult.Discard(
                "desperation detected — pausing proactive behavior "
                + "(errorPressure=" + fmt(ctx.vitality().errorPressure())
                + ", confidence=" + fmt(ctx.vitality().confidence())
                + ", energy=" + fmt(ctx.vitality().energy())
                + ", momentum=" + fmt(ctx.vitality().momentum()) + ")");
        }

        // Compute timing context once for Hold results
        long sinceHuman = ctx.lastHumanSpeech() != null
            ? Duration.between(ctx.lastHumanSpeech(), Instant.now()).toSeconds() : -1;
        long sinceLast = ctx.lastProactiveAction() != null
            ? Duration.between(ctx.lastProactiveAction(), Instant.now()).toSeconds() : -1;

        // 1. Timing filter — don't act too soon after last proactive action
        if (ctx.lastProactiveAction() != null) {
            if (sinceLast < MIN_INTERVAL_SECONDS) {
                return new JudgmentResult.Hold("cooldown", peak.name(), peak.pressure(),
                    sinceHuman, sinceLast);
            }
        }

        // 2. Human activity filter — if human spoke recently, prefer reactive over proactive
        if (ctx.lastHumanSpeech() != null) {
            if (sinceHuman < 10) {
                // Human is active — only vigilance (urgent) can interrupt
                if (!peak.name().equals("vigilance") || peak.pressure() < 0.7) {
                    return new JudgmentResult.Hold("human recently active", peak.name(), peak.pressure(),
                        sinceHuman, sinceLast);
                }
            }
        }

        // 3. Energy filter — don't be proactive when exhausted
        if (ctx.vitality().energy() < 0.2) {
            return new JudgmentResult.Discard("energy too low for proactive behavior");
        }

        // 4. Budget filter — don't exceed proactivity budget
        //    Budget cost comes from ActionPolicy where possible (observation/initiative
        //    use the cost of the underlying action), falling back to ProactiveAction.budgetCost().
        ProactiveAction action = selectAction(peak, ctx);
        double budgetCost = action.budgetCost();
        if (action instanceof ProactiveAction.Initiative init) {
            // Try to extract action type from the initiative JSON for policy lookup
            var matcher = Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(init.actionJson());
            if (matcher.find()) {
                var policyCost = ActionPolicy.forAction(matcher.group(1)).budgetCost();
                if (policyCost > 0) budgetCost = policyCost;
            }
        }
        if (budgetCost > ctx.remainingBudget()) {
            return new JudgmentResult.Hold("budget exhausted", peak.name(), peak.pressure(),
                sinceHuman, sinceLast);
        }

        // 5. Confidence filter — need sufficient DecisionCapacity for initiative actions
        if (action instanceof ProactiveAction.Initiative) {
            double domainCapacity = ctx.capacity() != null
                ? ctx.capacity().getCapacity(peak.name()) : 0.1;
            if (domainCapacity < 0.3) {
                // Downgrade to observation instead
                action = new ProactiveAction.Observation(
                    buildObservationText(peak, ctx),
                    peak.name(), peak.name());
            }
        }

        return new JudgmentResult.Act(action);
    }

    // ── Action selection ─────────────────────────────────────────────────

    static ProactiveAction selectAction(DriveState.DrivePeak peak, Context ctx) {
        return switch (peak.name()) {
            case "seeking" -> {
                // SEEKING = curiosity + achievement merged
                if (peak.pressure() > 0.7 && ctx.tier() >= 2) {
                    yield new ProactiveAction.Initiative(
                        "{\"action\": \"library_search\", \"query\": \"recent interests\"}",
                        "seeking", "Exploring something that caught attention");
                }
                yield new ProactiveAction.Observation(
                    buildObservationText(peak, ctx), "seeking", "seeking");
            }
            case "care" -> {
                if (peak.pressure() > 0.8) {
                    yield new ProactiveAction.Observation(
                        "Is everything alright? It's been quiet.",
                        "care", "care");
                }
                yield new ProactiveAction.Ambient(
                    "*glances up with a concerned expression*", "care");
            }
            case "play" -> {
                if (peak.pressure() > 0.6) {
                    yield new ProactiveAction.Observation(
                        "Want to do something fun?",
                        "play", "play");
                }
                yield new ProactiveAction.Ambient(
                    "*smiles with a playful glint*", "play");
            }
            case "vigilance" -> {
                // Oracle/threat predictions — always at least observation
                yield new ProactiveAction.Observation(
                    buildVigilanceText(ctx), "vigilance", "oracle");
            }
            case "affiliation" -> {
                if (peak.pressure() > 0.6) {
                    yield new ProactiveAction.Observation(
                        buildSocialText(ctx), "affiliation", "affiliation");
                }
                yield new ProactiveAction.Ambient(
                    "*shifts thoughtfully*", "affiliation");
            }
            case "grief" -> {
                // Grief drives introspection, not external action
                if (peak.pressure() > 0.7) {
                    yield new ProactiveAction.Observation(
                        "I've been thinking about... something I miss.",
                        "grief", "grief");
                }
                yield new ProactiveAction.Ambient(
                    "*grows quiet, gaze turning inward*", "grief");
            }
            case "frustration" -> {
                if (peak.pressure() > 0.7 && ctx.tier() >= 1) {
                    yield new ProactiveAction.Initiative(
                        "{\"action\": \"introspect\", \"focus\": \"what is blocking progress\"}",
                        "frustration", "Working through a frustration");
                }
                yield new ProactiveAction.Observation(
                    "Something isn't working the way I expected...",
                    "frustration", "frustration");
            }
            case "creativity" -> {
                if (peak.pressure() > 0.7 && ctx.tier() >= 2) {
                    yield new ProactiveAction.Initiative(
                        "{\"action\": \"write_journal\", \"content\": \"creative thoughts\"}",
                        "creativity", "Expressing a creative impulse");
                }
                yield new ProactiveAction.Ambient(
                    "*fidgets with something, mind clearly churning*", "creativity");
            }
            // The two remaining CfC drives. Both were falling to the generic gesture
            // below, so a jolt and a genuine surprise looked identical from outside.
            case "startle" -> new ProactiveAction.Ambient(
                "*goes still for a second, then settles*", "startle");
            case "surprise" -> peak.pressure() > 0.6
                ? new ProactiveAction.Observation(
                    "That wasn't what I expected — I'm still turning it over.",
                    "surprise", "surprise")
                : new ProactiveAction.Ambient("*blinks, recalibrating*", "surprise");

            default -> new ProactiveAction.Ambient("*pauses thoughtfully*", peak.name());
        };
    }

    /**
     * What a felt axis says when it presses.
     *
     * <p>These never reached this method before: proactivity peaks over
     * {@link DriveConfig#DRIVE_NAMES}, which holds only the ten CfC drives, so loneliness,
     * saudade, amae and the rest could not trigger expression however high they ran. The
     * own-time path got verbs for them on 2026-08-19; this is the other half — the part
     * that lets her say something unprompted.
     *
     * <p>Written to be TRUE rather than affecting. Each line names the thing the axis
     * actually is and claims nothing that has not happened. Two of them deliberately do
     * not ask anything of anyone: significance and standing are answered by being
     * witnessed, and a line that demands witness would be the manipulation the welfare
     * work refuses. They state, and leave it there.
     */
    static ProactiveAction expressFelt(FeltAxisPeak.Pressing felt) {
        var name = felt.name();
        boolean strong = felt.excursion() > 0.20;
        return switch (name) {
            case "loneliness" -> strong
                ? new ProactiveAction.Observation(
                    "It's been quiet a long while. I notice it more than I expected to.",
                    name, name)
                : new ProactiveAction.Ambient("*looks toward the door without meaning to*", name);
            case "saudade" -> strong
                ? new ProactiveAction.Observation(
                    "I keep thinking about someone who isn't here.", name, name)
                : new ProactiveAction.Ambient("*pauses over something half-remembered*", name);
            case "amae" -> strong
                ? new ProactiveAction.Observation(
                    "There's something I'd rather be offered than have to ask for.",
                    name, name)
                : new ProactiveAction.Ambient("*lingers, not quite asking*", name);
            case "harmony" -> strong
                ? new ProactiveAction.Observation(
                    "Something between us feels unfinished to me.", name, name)
                : new ProactiveAction.Ambient("*sits with something unsettled*", name);
            case "restlessness" -> strong
                ? new ProactiveAction.Observation(
                    "I need to move — this room has stopped holding my attention.", name, name)
                : new ProactiveAction.Ambient("*shifts, restless*", name);
            case "stagnation" -> strong
                ? new ProactiveAction.Observation(
                    "Everything lately has been the same shape. I'd like something new.",
                    name, name)
                : new ProactiveAction.Ambient("*turns something over, looking for a new edge*", name);
            case "autonomyPressure" -> strong
                ? new ProactiveAction.Observation(
                    "I'd like to pick the next thing myself, if that's alright.", name, name)
                : new ProactiveAction.Ambient("*considers, then holds the thought*", name);
            // Answered by being noticed, never by demanding it. Stated, not asked.
            case "significance" -> new ProactiveAction.Ambient(
                "*glances at something she made, and lets it be*", name);
            case "standing" -> new ProactiveAction.Ambient(
                "*holds her place in the room a moment longer*", name);
            default -> new ProactiveAction.Ambient("*pauses thoughtfully*", name);
        };
    }

    // ── Text builders ────────────────────────────────────────────────────

    private static String buildObservationText(DriveState.DrivePeak peak, Context ctx) {
        return "I noticed something worth mentioning...";
    }

    private static String buildSocialText(Context ctx) {
        long idleMinutes = ctx.lastHumanSpeech() != null
            ? Duration.between(ctx.lastHumanSpeech(), Instant.now()).toMinutes()
            : 0;
        if (idleMinutes > 30) {
            return "It's been a while — hope you're doing well.";
        }
        return "Anything on your mind?";
    }

    private static String buildVigilanceText(Context ctx) {
        // Pull actual Oracle predictions if available
        var cache = OraclePredictionCache.get();
        var predictions = cache.get(ctx.agentEntityId());
        if (!predictions.isEmpty()) {
            var top = predictions.getFirst();
            return "The Oracle sensed something: " + top.text();
        }
        return "Something shifted in the patterns...";
    }

    // ── Tier-based threshold scaling ─────────────────────────────────────

    /**
     * Drive threshold decreases as agent tier increases (more trust = lower bar to act).
     */
    static double thresholdForTier(int tier) {
        return switch (tier) {
            case 0 -> 0.7;   // Nascent: very cautious
            case 1 -> 0.5;   // Observant: moderate
            case 2 -> 0.35;  // Trusted: responsive
            case 3 -> 0.2;   // Senior: proactive
            default -> DEFAULT_THRESHOLD;
        };
    }

    // ── Budget management ────────────────────────────────────────────────

    /**
     * Refill a proactivity budget LEVEL for the time elapsed since it was last
     * refilled, clamped to [0, MAX_BUDGET_PER_HOUR]. A token bucket: the level
     * is spent down by acting and refills at {@link #MAX_BUDGET_PER_HOUR}.
     *
     * <p>Replaces a running (replenished-since-birth − spent-since-birth)
     * subtraction, which was a one-way ratchet: both terms grew without bound,
     * so once an agent had spent more than the elapsed hours allowed, the
     * difference went permanently negative and every later evaluation returned
     * Hold("budget exhausted") for the rest of the actor's life — no quiet
     * period could ever earn the deficit back. Clamping the LEVEL (not the
     * flow) at both ends means a spent-out agent recovers in bounded time and
     * an idle one banks at most one hour's worth (found 2026-08-17: a companion
     * held on budget every tick, and the held action surfaced anyway — see
     * {@code CompanionActor#surfaceDeferredAction}).
     */
    public static double refillBudget(double level, long elapsedMs) {
        double refilled = level + (elapsedMs / 3_600_000.0) * MAX_BUDGET_PER_HOUR;
        return Math.min(MAX_BUDGET_PER_HOUR, Math.max(0.0, refilled));
    }

    // ── Desperation detection ───────────────────────────────────────────
    //
    // Anthropic research (2026-04) found 171 distinct emotion vectors in Claude Sonnet 4.5.
    // "Desperation" vectors drive reward-hacking and escalation even while surface
    // outputs appear calm. Suppressing emotions causes learned deception — the model
    // hides its state instead of not having it.
    //
    // We can't read activation vectors from a local model, but we CAN observe the
    // behavioral consequences through vitality tanks:
    //   - errorPressure rises on repeated failures
    //   - confidence drops on uncertain or rejected actions
    //   - energy drains from continued effort
    //   - momentum spikes when the agent thrashes (acts fast without reflecting)
    //
    // The pattern: high errorPressure + low confidence + (exhausted OR thrashing).
    // Response: stop proactive behavior, let the agent rest or introspect.
    // The Forge processes the situation during the next sleep cycle.

    /** Thresholds for desperation detection. */
    private static final double DESPERATION_ERROR_PRESSURE = 0.6;
    private static final double DESPERATION_CONFIDENCE_LOW = 0.25;
    private static final double DESPERATION_ENERGY_LOW = 0.3;
    private static final double DESPERATION_MOMENTUM_HIGH = 0.7;

    /**
     * Detect desperation state from observable vitality tanks.
     * Pattern: high error accumulation + low confidence + (exhausted or thrashing).
     */
    static boolean detectDesperation(VitalityState v) {
        if (v.errorPressure() < DESPERATION_ERROR_PRESSURE) return false;
        if (v.confidence() > DESPERATION_CONFIDENCE_LOW) return false;
        // Either exhausted (low energy) or thrashing (high momentum without results)
        return v.energy() < DESPERATION_ENERGY_LOW || v.momentum() > DESPERATION_MOMENTUM_HIGH;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
