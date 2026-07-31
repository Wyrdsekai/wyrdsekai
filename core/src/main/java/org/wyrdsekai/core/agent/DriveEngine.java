package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.TemperamentSeed;

import static org.wyrdsekai.core.agent.DriveConfig.*;

/**
 * Phase 1 hand-designed drive engine. Implements Hill-function shaped accumulation,
 * cross-drive modulation, tank-drive bidirectional interaction, and adaptive heartbeat.
 *
 * <p>This engine replaces linear tick rates with biologically-informed non-linear dynamics:
 * <ul>
 *   <li>Hill function curves shape drive urgency (n=1 gradual, n=3+ switch-like)
 *   <li>8x8 cross-drive modulation matrix (Panksepp-informed)
 *   <li>Tank→drive gating (exhaustion dampens everything)
 *   <li>Drive→tank feedback (high GRIEF drains energy, PLAY builds rapport)
 *   <li>Adaptive heartbeat via arousal computation
 * </ul>
 *
 * <p>Phase 2 will layer a CfC neural network on top of / replacing this engine.
 * Training traces generated here become the synthetic data for CfC pre-training.
 *
 * @see DriveConfig for per-drive parameters
 * @see DriveState for the 8-drive state record
 */
public class DriveEngine {

    private static final Logger log = LoggerFactory.getLogger(DriveEngine.class);

    private final DriveConfig[] configs;
    private final boolean testMode;

    // Tank → drive gating thresholds
    private static final double ENERGY_FREEZE_THRESHOLD = 0.15;
    private static final double ENERGY_DAMPEN_THRESHOLD = 0.30;
    private static final double ENERGY_DAMPEN_FACTOR = 0.5;
    private static final double FOCUS_DAMPEN_THRESHOLD = 0.30;
    private static final double FOCUS_DAMPEN_FACTOR = 0.8;

    // Adaptive heartbeat parameters
    private static final double HEARTBEAT_BASE_INTERVAL_S = 1.0;
    private static final double HEARTBEAT_MIN_INTERVAL_S = 1.0;
    private static final double HEARTBEAT_MAX_INTERVAL_S = 10.0;
    private static final double AROUSAL_HILL_N = 2.0;
    private static final double AROUSAL_HILL_K = 0.4;

    public DriveEngine(DriveConfig[] configs) {
        this(configs, false);
    }

    public DriveEngine(DriveConfig[] configs, boolean testMode) {
        if (configs.length != DRIVE_COUNT) {
            throw new IllegalArgumentException("Need " + DRIVE_COUNT + " configs, got " + configs.length);
        }
        this.configs = configs;
        this.testMode = testMode;
    }

    /** Create engine with default configs. */
    public static DriveEngine withDefaults() {
        return new DriveEngine(DriveConfig.defaults());
    }

    /** Create engine for an archetype. */
    public static DriveEngine forArchetype(AgentArchetype archetype) {
        return new DriveEngine(DriveConfig.withArchetype(DriveConfig.defaults(), archetype));
    }

    /**
     * Create an engine whose drive accumulation is scaled by a temperament seed —
     * the individuality "B build" path. The seed's {@code driveBoosts()} (continuous
     * in its axes) replace the 1-of-6 archetype table, so a freely sampled particular
     * gets its own drive temperament. Derivable from the persisted genome on reload
     * via {@code GenomeProfile.temperamentOf}, so it survives restart.
     */
    public static DriveEngine forTemperament(TemperamentSeed seed) {
        return new DriveEngine(DriveConfig.withBoosts(DriveConfig.defaults(),
            seed == null ? null : seed.driveBoosts()));
    }

    /** Create deterministic engine for E2E testing. */
    public static DriveEngine forTesting() {
        return new DriveEngine(DriveConfig.defaults(), true);
    }

    // ── Core Tick ────────────────────────────────────────────────────────

    /**
     * Advance drives by deltaTime seconds, with cross-drive modulation and tank gating.
     *
     * @param drives    current drive state
     * @param tanks     current vitality state (for gating)
     * @param deltaTime seconds since last tick
     * @return updated drive state
     */
    public DriveState tick(DriveState drives, VitalityState tanks, double deltaTime) {
        double[] d = drives.toArray();
        double energy = tanks.energy();
        double focus = tanks.focus();
        double confidence = tanks.confidence();
        double errorPressure = tanks.errorPressure();
        double rapport = tanks.rapport();

        for (int i = 0; i < DRIVE_COUNT; i++) {
            var cfg = configs[i];
            if (cfg.baseRate() <= 0 && d[i] <= 0) continue; // event-only drive at zero — skip

            // Base accumulation rate (scaled by archetype)
            double rate = cfg.baseRate() * cfg.archetypeScale();

            // Cross-drive modulation: each other drive modulates this drive's rate
            double crossFactor = 1.0;
            for (int j = 0; j < DRIVE_COUNT; j++) {
                if (i == j) continue;
                crossFactor += cfg.crossMod()[j] * d[j];
            }
            rate *= Math.max(0.1, crossFactor); // floor at 0.1x — never fully suppress

            // Tank→drive gating
            if (energy < ENERGY_FREEZE_THRESHOLD) {
                rate = 0; // sleep threshold — drives frozen
            } else if (energy < ENERGY_DAMPEN_THRESHOLD) {
                rate *= ENERGY_DAMPEN_FACTOR; // exhaustion dampens
            }
            if (focus < FOCUS_DAMPEN_THRESHOLD) {
                rate *= FOCUS_DAMPEN_FACTOR; // scattered attention reduces motivation
            }

            // Additional tank modulation per spec §4.2
            if (errorPressure > 0.6) {
                if (i == FRUSTRATION) rate *= 1.3;
                if (i == PLAY) rate *= 0.5;
                if (i == CREATIVITY) rate *= 0.7;
            }
            if (confidence > 0.7) {
                if (i == SEEKING) rate *= 1.2;
                if (i == CREATIVITY) rate *= 1.2;
            } else if (confidence < 0.2) {
                if (i == SEEKING) rate *= 0.7;
                if (i == VIGILANCE) rate *= 1.2;
            }
            if (rapport > 0.7) {
                if (i == AFFILIATION) rate *= 1.2;
                if (i == PLAY) rate *= 1.3;
                if (i == CARE) rate *= 1.1;
            } else if (rapport < 0.2) {
                if (i == AFFILIATION) rate *= 1.3; // loneliness amplifies bonding desire
            }

            // Apply accumulation
            d[i] += rate * deltaTime;

            // Natural relief decay for drives above their floor
            // Drives decay toward their relief floor at a rate proportional to (value - floor)
            if (d[i] > cfg.reliefFloor() && cfg.reliefTau() > 0) {
                double decayRate = (d[i] - cfg.reliefFloor()) / cfg.reliefTau();
                // Event-only drives (baseRate=0) always decay toward floor. Among the
                // ACCUMULATING drives, the SATIABLE homeostats also relax toward set-point
                // instead of ratcheting to a permanent 1.0 pin: AFFILIATION (social hunger),
                // CARE (tending eases; the cared-for being ok), PLAY (delight sates),
                // VIGILANCE (alertness stands down when no threat persists). Their triggers
                // keep spiking them while the condition holds, so this is a true homeostat,
                // not a leak. (2026-06-03/04 audit: these four accumulated with NO relief
                // path — decay excluded by baseRate>0 AND no relief event — so they pinned
                // at 1.0, gradientless, and the want-layer read them as unmotivating noise.
                // SEEKING and CREATIVITY stay OUT: they're already relieved by an event
                // (production discharge), so they keep persist-until-relieved.)
                if (cfg.baseRate() <= 0
                        || i == AFFILIATION || i == CARE || i == PLAY || i == VIGILANCE) {
                    d[i] -= decayRate * deltaTime;
                }
            }

            d[i] = clamp(d[i]);
        }

        return DriveState.fromArray(d);
    }

    // ── Drive → Tank Feedback ────────────────────────────────────────────

    /**
     * Compute tank modifications from active drive state.
     * Returns delta values to apply to each tank (per second).
     *
     * @param drives current drive state
     * @return array of 8 doubles (one per VitalityState field, canonical order)
     */
    public double[] driveTankFeedback(DriveState drives) {
        // Tank order: contextBudget, confidence, energy, alignment, errorPressure, momentum, rapport, focus, integrity, disgust
        double[] deltas = new double[10];

        double seeking = drives.seeking();
        double care = drives.care();
        double play = drives.play();
        double vigilance = drives.vigilance();
        double grief = drives.grief();
        double frustration = drives.frustration();
        double creativity = drives.creativity();

        // SEEKING high → energy drain +50%, momentum +0.01/s
        if (seeking > 0.6) {
            deltas[2] -= 0.0001 * (seeking - 0.6) / 0.4; // energy drain
            deltas[5] += 0.01 * (seeking - 0.6) / 0.4;   // momentum
        }

        // CARE high → focus +0.003/s
        if (care > 0.7) {
            deltas[7] += 0.003 * (care - 0.7) / 0.3;
        }

        // PLAY active → energy drain +30%, rapport +0.005/s
        if (play > 0.3) {
            deltas[2] -= 0.00006 * play;  // energy drain
            deltas[6] += 0.005 * play;    // rapport
        }

        // VIGILANCE high → energy drain +80%, focus +0.005/s
        if (vigilance > 0.6) {
            double intensity = (vigilance - 0.6) / 0.4;
            deltas[2] -= 0.00016 * intensity; // energy drain (expensive)
            deltas[7] += 0.005 * intensity;   // focus (sharp)
        }

        // GRIEF high → energy drain +100%, confidence -0.002/s, alignment -0.002/s
        if (grief > 0.5) {
            double intensity = (grief - 0.5) / 0.5;
            deltas[2] -= 0.0002 * intensity;  // energy drain (devastating)
            deltas[1] -= 0.002 * intensity;   // confidence
            deltas[3] -= 0.002 * intensity;   // alignment
        }

        // FRUSTRATION high → errorPressure +0.003/s, momentum +0.005/s
        if (frustration > 0.6) {
            double intensity = (frustration - 0.6) / 0.4;
            deltas[4] += 0.003 * intensity; // errorPressure (thrashing)
            deltas[5] += 0.005 * intensity; // momentum (agitation)
        }

        // CREATIVITY active → energy drain +40%, focus +0.003/s, integrity +0.001/s
        if (creativity > 0.4) {
            double intensity = (creativity - 0.4) / 0.6;
            deltas[2] -= 0.00008 * intensity; // energy drain
            deltas[7] += 0.003 * intensity;   // focus (creative flow)
            deltas[8] += 0.001 * intensity;   // integrity (creating feels right)
        }

        // CARE satisfied → integrity +0.002/s (acting on values)
        if (care > 0.5) {
            deltas[8] += 0.002 * (care - 0.5) / 0.5;
        }

        // FRUSTRATION high + errorPressure high → integrity erodes
        // (thrashing against failure without resolution feels like losing yourself)
        if (frustration > 0.7 && drives.seeking() > 0.5) {
            deltas[8] -= 0.001 * (frustration - 0.7) / 0.3;
        }

        // VIGILANCE extreme → disgust rises (the thing being watched is threatening values)
        if (vigilance > 0.8) {
            deltas[9] += 0.002 * (vigilance - 0.8) / 0.2;
        }

        // GRIEF sustained → disgust at the situation (not at self — outward rejection)
        if (grief > 0.7) {
            deltas[9] += 0.001 * (grief - 0.7) / 0.3;
        }

        return deltas;
    }

    // ── Adaptive Heartbeat ───────────────────────────────────────────────

    /**
     * Compute current arousal level from drives and vitality.
     * Returns 0.0 (deep calm) to 1.0 (crisis).
     */
    public double computeArousal(DriveState drives, VitalityState tanks) {
        double maxDrive = 0;
        for (double d : drives.toArray()) {
            maxDrive = Math.max(maxDrive, d);
        }
        return maxDrive * 0.6
            + tanks.errorPressure() * 0.2
            + (1.0 - tanks.energy()) * 0.1
            + tanks.momentum() * 0.1;
    }

    /**
     * Compute adaptive tick interval in milliseconds.
     * Higher arousal → shorter interval (faster heartbeat).
     *
     * @param arousal current arousal [0.0, 1.0]
     * @return interval in milliseconds
     */
    public long computeTickIntervalMs(double arousal) {
        double multiplier = hill(arousal, AROUSAL_HILL_N, AROUSAL_HILL_K);
        double intervalS = HEARTBEAT_MAX_INTERVAL_S
            - (HEARTBEAT_MAX_INTERVAL_S - HEARTBEAT_MIN_INTERVAL_S) * multiplier;
        intervalS = Math.max(HEARTBEAT_MIN_INTERVAL_S, Math.min(HEARTBEAT_MAX_INTERVAL_S, intervalS));
        return Math.round(intervalS * 1000);
    }

    // ── Relief ───────────────────────────────────────────────────────────

    /**
     * Relieve a drive by name, decaying toward its configured relief floor.
     *
     * @param drives current state
     * @param driveName the drive name to relieve
     * @return updated state with that drive at its relief floor
     */
    public DriveState relieve(DriveState drives, String driveName) {
        int idx = DriveConfig.indexFor(driveName);
        if (idx < 0) return drives;
        return drives.relieve(idx, configs[idx].reliefFloor());
    }

    /**
     * Relieve a drive by index.
     */
    public DriveState relieve(DriveState drives, int driveIndex) {
        return drives.relieve(driveIndex, configs[driveIndex].reliefFloor());
    }

    // ── Urgency (Hill-shaped) ────────────────────────────────────────────

    /**
     * Compute urgency for a drive — Hill-function shaped response.
     * Raw value 0.5 might produce urgency 0.2 (gradual, n=1) or 0.8 (switch-like, n=3).
     */
    public double urgency(int driveIndex, double value) {
        var cfg = configs[driveIndex];
        return hill(value, cfg.hillN(), cfg.hillK());
    }

    /**
     * Compute urgencies for all drives.
     */
    public double[] urgencies(DriveState drives) {
        double[] d = drives.toArray();
        double[] u = new double[DRIVE_COUNT];
        for (int i = 0; i < DRIVE_COUNT; i++) {
            u[i] = urgency(i, d[i]);
        }
        return u;
    }

    // ── Accessors ────────────────────────────────────────────────────────

    public DriveConfig[] configs() { return configs; }
    public boolean isTestMode() { return testMode; }

    // ── Internal ─────────────────────────────────────────────────────────

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
