#!/usr/bin/env python3
"""
Generate synthetic training data from the hand-designed Hill function drive engine.
This is the "teacher model" — we run the Phase 1 equations forward with randomized
inputs to produce (state, input, next_state) trajectories for CfC pre-training.

Output: trajectories/{archetype}/episode_{n}.json
Each episode: list of {tanks_before, drives_before, events, delta_time, tanks_after, drives_after}

Usage:
    python generate_synthetic.py --output ./trajectories --episodes 500 --variants 80
"""

import json
import math
import os
import random
import argparse
from dataclasses import dataclass, field
from typing import List, Dict

# Drive indices (must match DriveConfig.java)
SEEKING, CARE, PLAY, VIGILANCE, AFFILIATION, GRIEF, FRUSTRATION, CREATIVITY = range(8)
DRIVE_NAMES = ["seeking", "care", "play", "vigilance", "affiliation", "grief", "frustration", "creativity"]

# Tank indices
CTX_BUDGET, CONFIDENCE, ENERGY, ALIGNMENT, ERROR_PRESSURE, MOMENTUM, RAPPORT, FOCUS = range(8)


@dataclass
class DriveConfig:
    hill_n: float
    hill_k: float
    base_rate: float
    relief_floor: float
    archetype_scale: float
    cross_mod: List[float]


# Default configs matching DriveConfig.defaults() in Java
DEFAULTS = [
    DriveConfig(1.5, 0.4, 0.0003, 0.05, 1.0, [0.0, 0.0, 0.2, -0.1, 0.1, 0.0, -0.2, 0.3]),   # SEEKING
    DriveConfig(2.0, 0.5, 0.0002, 0.1, 1.0,  [0.0, 0.0, -0.1, 0.2, 0.3, 0.2, 0.0, 0.0]),     # CARE
    DriveConfig(1.0, 0.3, 0.0004, 0.0, 1.0,  [0.1, 0.0, 0.0, -0.3, 0.2, -0.2, -0.3, 0.2]),   # PLAY
    DriveConfig(3.0, 0.6, 0.0001, 0.0, 1.0,  [-0.2, 0.1, -0.4, 0.0, -0.1, 0.0, 0.2, -0.2]),  # VIGILANCE
    DriveConfig(1.5, 0.4, 0.0003, 0.05, 1.0, [0.1, 0.2, 0.3, -0.1, 0.0, 0.3, -0.1, 0.0]),    # AFFILIATION
    DriveConfig(3.0, 0.7, 0.0, 0.0, 1.0,     [-0.3, 0.3, -0.4, 0.1, 0.2, 0.0, 0.2, -0.3]),   # GRIEF
    DriveConfig(2.5, 0.5, 0.0, 0.0, 1.0,     [0.2, -0.1, -0.3, 0.3, -0.2, 0.1, 0.0, -0.1]),  # FRUSTRATION
    DriveConfig(1.0, 0.3, 0.0002, 0.0, 1.0,  [0.2, 0.0, 0.2, -0.1, 0.0, -0.1, -0.2, 0.0]),   # CREATIVITY
]

# Archetype boost profiles (must match AgentArchetype.java)
ARCHETYPES = {
    "scholar":  {"seeking": 0.3, "creativity": 0.2},
    "guardian":  {"vigilance": 0.3, "care": 0.2},
    "artisan":   {"creativity": 0.3, "seeking": 0.2},
    "diplomat":  {"affiliation": 0.3, "play": 0.2},
    "explorer":  {"seeking": 0.3, "play": 0.2},
    "steward":   {"care": 0.3, "vigilance": 0.2},
}


def hill(x, n, k):
    if x <= 0:
        return 0.0
    if n == 1.0:
        return x / (k + x)
    xn = x ** n
    kn = k ** n
    return xn / (kn + xn)


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def drive_index(name):
    return DRIVE_NAMES.index(name)


def apply_archetype(configs, archetype_name):
    """Apply archetype boosts to drive configs."""
    configs = [DriveConfig(c.hill_n, c.hill_k, c.base_rate, c.relief_floor,
                           c.archetype_scale, list(c.cross_mod)) for c in configs]
    boosts = ARCHETYPES.get(archetype_name, {})
    for name, boost in boosts.items():
        idx = drive_index(name)
        configs[idx].archetype_scale = 1.0 + boost
    return configs


def randomize_genome(configs, sigma=0.15):
    """Apply constrained randomness to drive configs (domain randomization)."""
    result = []
    for c in configs:
        result.append(DriveConfig(
            hill_n=max(0.5, c.hill_n + random.gauss(0, sigma * 0.5)),
            hill_k=clamp(c.hill_k + random.gauss(0, sigma * 0.2), 0.1, 0.9),
            base_rate=max(0, c.base_rate * (1 + random.gauss(0, sigma))),
            relief_floor=clamp(c.relief_floor + random.gauss(0, sigma * 0.1), 0, 0.3),
            archetype_scale=max(0.5, c.archetype_scale + random.gauss(0, sigma * 0.2)),
            cross_mod=[cm + random.gauss(0, sigma * 0.1) for cm in c.cross_mod],
        ))
    return result


def tick_drives(drives, tanks, configs, dt):
    """One tick of the drive engine (matches DriveEngine.tick() in Java)."""
    d = list(drives)
    energy = tanks[ENERGY]
    focus = tanks[FOCUS]
    confidence = tanks[CONFIDENCE]
    error_pressure = tanks[ERROR_PRESSURE]
    rapport = tanks[RAPPORT]

    for i in range(8):
        cfg = configs[i]
        if cfg.base_rate <= 0 and d[i] <= 0:
            continue

        rate = cfg.base_rate * cfg.archetype_scale

        # Cross-drive modulation
        cross_factor = 1.0
        for j in range(8):
            if i != j:
                cross_factor += cfg.cross_mod[j] * d[j]
        rate *= max(0.1, cross_factor)

        # Tank gating
        if energy < 0.15:
            rate = 0
        elif energy < 0.30:
            rate *= 0.5
        if focus < 0.30:
            rate *= 0.8

        # Additional tank modulation
        if error_pressure > 0.6:
            if i == FRUSTRATION: rate *= 1.3
            if i == PLAY: rate *= 0.5
            if i == CREATIVITY: rate *= 0.7
        if confidence > 0.7:
            if i == SEEKING: rate *= 1.2
            if i == CREATIVITY: rate *= 1.2
        elif confidence < 0.2:
            if i == SEEKING: rate *= 0.7
            if i == VIGILANCE: rate *= 1.2
        if rapport > 0.7:
            if i == AFFILIATION: rate *= 1.2
            if i == PLAY: rate *= 1.3
            if i == CARE: rate *= 1.1
        elif rapport < 0.2:
            if i == AFFILIATION: rate *= 1.3

        d[i] += rate * dt
        d[i] = clamp(d[i])

    return d


def tick_tanks(tanks, dt):
    """Simple tank tick (matches VitalityState.tick() in Java)."""
    t = list(tanks)
    t[CTX_BUDGET] = clamp(t[CTX_BUDGET] + 0.003 * dt)
    # confidence: no natural change
    t[ENERGY] = clamp(t[ENERGY] - 0.0002 * dt)
    t[ALIGNMENT] = clamp(t[ALIGNMENT] - 0.001 * dt)
    t[ERROR_PRESSURE] = clamp(t[ERROR_PRESSURE] - 0.005 * dt)
    t[MOMENTUM] = clamp(t[MOMENTUM] - 0.003 * dt)
    t[RAPPORT] = clamp(t[RAPPORT] - 0.001 * dt)
    t[FOCUS] = clamp(t[FOCUS] - 0.002 * dt)
    return t


def random_events():
    """Generate random event vector (8-dim)."""
    events = [0.0] * 8
    # Randomly spike 0-2 events
    for _ in range(random.randint(0, 2)):
        idx = random.randint(0, 7)
        events[idx] = random.uniform(0.1, 0.8)
    return events


def generate_episode(configs, steps=200):
    """Generate one trajectory episode."""
    tanks = [0.5, 0.5, 1.0, 0.3, 0.0, 0.0, 0.3, 0.5]  # VitalityState.initial()
    drives = [0.0] * 8

    trajectory = []
    for step in range(steps):
        dt = random.uniform(0.5, 3.0)  # variable timestep
        events = random_events()

        tanks_before = list(tanks)
        drives_before = list(drives)

        # Apply event spikes to drives
        for i in range(8):
            if events[i] > 0:
                drives[i] = clamp(drives[i] + events[i] * 0.1)

        # Tick
        tanks = tick_tanks(tanks, dt)
        drives = tick_drives(drives, tanks, configs, dt)

        trajectory.append({
            "tanks_before": tanks_before,
            "drives_before": drives_before,
            "events": events,
            "delta_time": dt,
            "tanks_after": list(tanks),
            "drives_after": list(drives),
        })

    return trajectory


def main():
    parser = argparse.ArgumentParser(description="Generate synthetic CfC training data")
    parser.add_argument("--output", default="./trajectories", help="Output directory")
    parser.add_argument("--episodes", type=int, default=500, help="Episodes per archetype")
    parser.add_argument("--variants", type=int, default=80, help="Genome variants per archetype")
    parser.add_argument("--steps", type=int, default=200, help="Steps per episode")
    args = parser.parse_args()

    total = 0
    for arch_name in ARCHETYPES:
        arch_dir = os.path.join(args.output, arch_name)
        os.makedirs(arch_dir, exist_ok=True)

        base_configs = apply_archetype(DEFAULTS, arch_name)

        for ep in range(args.episodes):
            # Pick a random genome variant
            variant_seed = ep % args.variants
            random.seed(42 + variant_seed * 1000 + ep)
            configs = randomize_genome(base_configs, sigma=0.15)

            trajectory = generate_episode(configs, steps=args.steps)
            path = os.path.join(arch_dir, f"episode_{ep:04d}.json")
            with open(path, "w") as f:
                json.dump(trajectory, f)
            total += 1

        print(f"  {arch_name}: {args.episodes} episodes ({args.episodes * args.steps} timesteps)")

    print(f"Total: {total} episodes across {len(ARCHETYPES)} archetypes")
    print(f"Output: {args.output}/")


if __name__ == "__main__":
    main()
